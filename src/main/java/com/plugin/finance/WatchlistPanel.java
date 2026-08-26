package com.plugin.finance;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class WatchlistPanel extends JPanel {

    private static final String CODES_KEY = "eastmoney.7x24.watchlist.codes";
    private static final String SORT_COL_KEY = "eastmoney.7x24.watchlist.sortColumn";
    private static final String SORT_STATE_KEY = "eastmoney.7x24.watchlist.sortState";
    private static final String INDEX_KEY = "eastmoney.7x24.watchlist.index";
    private static final String FILTER_KEY = "eastmoney.7x24.watchlist.marketFilter";
    private static final String[] COLUMN_NAMES = {"代码", "名称", "最新价", "涨跌幅", "涨跌额"};
    private static final String[] INDEX_NAMES_A = {"上证指数", "深证成指", "创业板指"};
    private static final String[] INDEX_NAMES_HK = {"恒生指数", "恒生科技"};
    private static final String[] INDEX_NAMES_US = {"道琼斯", "纳斯达克", "标普500"};
    private static final String[] INDEX_NAMES_GOLD = {"伦敦金", "纽约金", "伦敦银", "纽约白银"};
    private static final List<String> INDEX_CODES_A = List.of("sh000001", "sz399001", "sz399006");
    private static final List<String> INDEX_CODES_HK = List.of("hkHSI", "hkHSTECH");
    private static final List<String> INDEX_CODES_US = List.of("usDJI", "usIXIC", "usINX");
    private static final List<String> INDEX_CODES_GOLD = List.of("xau", "gc", "xag", "si");
    /** 追问失败时写入 history 的 assistant 占位，保持 user/assistant 严格交替 */
    private static final String AI_FAILED_PLACEHOLDER = "（本轮回答失败，无结论）";

    private enum MarketFilter {
        A("A股"), HK("港股"), US("美股"), GOLD("黄金");
        private final String displayName;
        MarketFilter(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private final Project project;
    private final PropertiesComponent properties;
    private final QuoteService quoteService = new QuoteService();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private String currentApiKey = "";
    private String currentModelId = "";
    private JDialog aiDialog;

    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable quoteTable = new JTable(tableModel);
    private final TableRowSorter<DefaultTableModel> tableSorter = new TableRowSorter<>(tableModel);
    private final JLabel statusLabel = new JLabel("就绪");

    private final JPanel filterTabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final List<JButton> filterButtons = new ArrayList<>();
    private MarketFilter currentFilter = MarketFilter.A;

    private final JComboBox<String> indexCombo = new JComboBox<>(INDEX_NAMES_A);
    private final JLabel indexValueLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel indexChangeLabel = new JLabel("", SwingConstants.CENTER);

    private final Timer autoRefreshTimer;
    private int sortedColumn = -1;
    private int sortState = 0;
    private volatile List<QuoteItem> lastIndexData = new ArrayList<>();
    private long countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
    private boolean isRefreshing = false;

    private static final int REFRESH_INTERVAL_MS = 10000;
    private static final int COUNTDOWN_STEP_MS = 1000;

    public WatchlistPanel(Project project) {
        this.project = project;
        properties = PropertiesComponent.getInstance(project);
        currentModelId = PropertiesComponent.getInstance().getValue("finance.ai.model", "");
        if (currentModelId.isEmpty() || !AIService.MODELS.containsKey(currentModelId)) {
            currentModelId = AIService.MODELS.keySet().iterator().next();
        }
        reloadAiConfig();

        setLayout(new BorderLayout());
        initTopPanel();
        initTable();
        initBottomPanel();
        restoreSortState();
        restoreFilter();

        autoRefreshTimer = new Timer(COUNTDOWN_STEP_MS, e -> tickAndAutoRefresh());

        // 初始加载行情数据，避免重启后表格为空直到首次定时器触发
        refreshQuotes(false);
    }

    private void tickAndAutoRefresh() {
        if (!marketOpenFor(getFilteredCodes())) {
            autoRefreshTimer.stop();
            statusLabel.setText("闭市中");
            return;
        }
        if (isRefreshing) return;
        countdownSeconds--;
        if (countdownSeconds <= 0) {
            countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
            refreshQuotes(true);
        }
        updateCountdownDisplay();
    }

    private void updateCountdownDisplay() {
        String text = statusLabel.getText();
        if (text.contains("下次刷新")) {
            String prefix = text.substring(0, text.indexOf("下次刷新"));
            statusLabel.setText(prefix + "下次刷新" + countdownSeconds + "秒");
        }
    }

    private void setStatus(String baseText) {
        if (baseText.equals("刷新中...")) {
            statusLabel.setText(baseText);
        } else {
            statusLabel.setText(baseText + "    下次刷新" + countdownSeconds + "秒");
        }
    }

    private void initTopPanel() {
        JButton addButton = new JButton("添加");
        addButton.setToolTipText("添加自选股");
        addButton.setPreferredSize(JBUI.size(50, 28));
        addButton.addActionListener(e -> showAddDialog());

        JButton refreshButton = new JButton("刷新");
        refreshButton.setToolTipText("刷新行情");
        refreshButton.setPreferredSize(JBUI.size(50, 28));
        refreshButton.addActionListener(e -> refreshQuotes(true));

        JButton aiBtn = new JButton("AI 分析");
        aiBtn.setToolTipText("AI 分析自选股行情");
        aiBtn.setFont(JBFont.label().deriveFont(12f));
        aiBtn.setPreferredSize(JBUI.size(65, 28));
        aiBtn.addActionListener(e -> showAiAnalysis());

        JButton settingsBtn = new JButton("设置");
        settingsBtn.setToolTipText("AI 设置（API Key）");
        settingsBtn.setFont(JBFont.label().deriveFont(12f));
        settingsBtn.setPreferredSize(JBUI.size(50, 28));
        settingsBtn.addActionListener(e -> showAiSettings());

        JLabel titleLabel = new JLabel("自选行情");
        titleLabel.setFont(JBFont.label().asBold());

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightPanel.add(addButton);
        rightPanel.add(refreshButton);
        rightPanel.add(aiBtn);
        rightPanel.add(settingsBtn);

        JPanel actionRow = new JPanel(new BorderLayout(8, 0));
        actionRow.add(titleLabel, BorderLayout.WEST);
        actionRow.add(statusLabel, BorderLayout.CENTER);
        actionRow.add(rightPanel, BorderLayout.EAST);

        statusLabel.setFont(JBFont.small());
        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));

        JPanel topPanel = new JPanel(new BorderLayout(0, 6));
        topPanel.setBorder(JBUI.Borders.empty(6, 8, 10, 8));
        topPanel.add(actionRow, BorderLayout.NORTH);

        initMarketFilterTabs();
        JPanel filterRow = new JPanel(new BorderLayout());
        filterRow.add(filterTabPanel, BorderLayout.WEST);
        topPanel.add(filterRow, BorderLayout.CENTER);

        add(topPanel, BorderLayout.NORTH);
    }

    private void initMarketFilterTabs() {
        JBFont btnFont = JBFont.label();
        int btnPad = JBUI.scale(6);
        int btnH = JBUI.scale(28);
        int minWidth = JBUI.scale(56);

        for (MarketFilter filter : MarketFilter.values()) {
            JButton btn = new JButton(filter.getDisplayName());
            btn.setFont(btnFont);
            btn.setPreferredSize(new Dimension(Math.max(minWidth, btn.getPreferredSize().width), btnH));
            btn.setFocusPainted(false);
            btn.setOpaque(false);
            btn.setContentAreaFilled(false);
            btn.setBorder(createFilterBorder(false, btnPad));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final MarketFilter f = filter;
            btn.addActionListener(e -> setMarketFilter(f));
            filterTabPanel.add(btn);
            filterButtons.add(btn);
        }
    }

    private static Border createFilterBorder(boolean active, int btnPad) {
        JBColor underline = new JBColor(new Color(0x2F6FAD), new Color(0x4A90D9));
        JBColor transparent = new JBColor(new Color(0xE8E8E8), new Color(0x3D3F41));
        Color line = active ? underline : transparent;
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, JBUI.scale(2), 0, line),
                JBUI.Borders.empty(btnPad, 12, btnPad, 12));
    }

    private void setMarketFilter(MarketFilter filter) {
        currentFilter = filter;
        properties.setValue(FILTER_KEY, filter.name());
        updateFilterButtons();
        updateIndexComboForFilter();
        resumeTimer();
        refreshQuotes(false);
    }

    private void updateIndexComboForFilter() {
        String[] names = currentIndexNames();
        String saved = properties.getValue(indexKeyFor(currentFilter), "0");
        indexCombo.setModel(new DefaultComboBoxModel<>(names));
        try {
            int idx = Integer.parseInt(saved);
            if (idx >= 0 && idx < names.length) {
                indexCombo.setSelectedIndex(idx);
            }
        } catch (NumberFormatException ignored) {}
        refreshIndexData(true);
    }

    private void updateFilterButtons() {
        for (int i = 0; i < filterButtons.size(); i++) {
            JButton btn = filterButtons.get(i);
            MarketFilter bf = MarketFilter.values()[i];
            boolean active = bf == currentFilter;
            btn.setForeground(active
                    ? UIManager.getColor("Label.foreground")
                    : UIManager.getColor("Label.disabledForeground"));
            btn.setBorder(createFilterBorder(active, JBUI.scale(6)));
            btn.setFont(btn.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        }
    }

    private void restoreFilter() {
        String filterStr = properties.getValue(FILTER_KEY, "A");
        try {
            currentFilter = MarketFilter.valueOf(filterStr);
        } catch (IllegalArgumentException ignored) {
            currentFilter = MarketFilter.A;
        }
        updateFilterButtons();
        indexCombo.setModel(new DefaultComboBoxModel<>(currentIndexNames()));
        String idxStr = properties.getValue(indexKeyFor(currentFilter), "0");
        try {
            int idx = Integer.parseInt(idxStr);
            if (idx >= 0 && idx < indexCombo.getItemCount()) {
                indexCombo.setSelectedIndex(idx);
            }
        } catch (NumberFormatException ignored) {}
    }

    private void initTable() {
        quoteTable.setFillsViewportHeight(true);
        quoteTable.setRowHeight(JBUI.scale(30));
        quoteTable.setShowVerticalLines(false);
        quoteTable.setIntercellSpacing(JBUI.emptySize());
        quoteTable.setDefaultRenderer(Object.class, new WatchlistCellRenderer());
        quoteTable.setRowSorter(tableSorter);
        quoteTable.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setupTableWidths();
        setupTableSorting();
        centerTableHeader();
        installPopupMenu();

        add(new JBScrollPane(quoteTable), BorderLayout.CENTER);
    }

    private void initBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        bottomPanel.setBorder(JBUI.Borders.empty(6, 12, 6, 12));

        indexCombo.setFont(indexCombo.getFont().deriveFont(11f));
        indexCombo.setBorder(JBUI.Borders.empty(0, 4));
        indexCombo.setPreferredSize(JBUI.size(110, 22));
        indexCombo.setLightWeightPopupEnabled(true);

        indexValueLabel.setFont(indexValueLabel.getFont().deriveFont(Font.BOLD, 12f));
        indexChangeLabel.setFont(indexChangeLabel.getFont().deriveFont(Font.BOLD, 12f));

        JPanel indexPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        indexPanel.add(indexCombo);
        indexPanel.add(indexValueLabel);
        indexPanel.add(indexChangeLabel);

        bottomPanel.add(indexPanel, BorderLayout.WEST);
        add(bottomPanel, BorderLayout.SOUTH);

        indexCombo.addActionListener(e -> {
            properties.setValue(indexKeyFor(currentFilter), String.valueOf(indexCombo.getSelectedIndex()));
            updateIndexDisplay();
        });
    }

    private String indexKeyFor(MarketFilter filter) {
        return INDEX_KEY + "." + filter.name();
    }

    private void updateIndexDisplay() {
        int idx = indexCombo.getSelectedIndex();
        if (idx < 0 || idx >= lastIndexData.size()) return;
        QuoteItem item = lastIndexData.get(idx);
        Color up = new JBColor(new Color(0xC62828), new Color(0xFF6B6B));
        Color down = new JBColor(new Color(0x2E7D32), new Color(0x69D98A));
        Color flat = UIManager.getColor("Label.infoForeground");

        indexValueLabel.setText(String.format("%.2f", item.getPrice()));
        indexChangeLabel.setText(String.format("%+.2f%%", item.getChangePercent()));

        Color c = item.getChangePercent() > 0 ? up : item.getChangePercent() < 0 ? down : flat;
        indexValueLabel.setForeground(c);
        indexChangeLabel.setForeground(c);
    }

    private void showAddDialog() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null) {
            String input = JOptionPane.showInputDialog(this,
                    "输入股票或 ETF 代码，多个代码可用逗号或空格分隔；\n指数：sh000001 / 港股：hk00700 / 美股：usAAPL / 黄金：xau, gc, xag, si",
                    "添加自选", JOptionPane.PLAIN_MESSAGE);
            if (input != null) addCodesFromText(input);
            return;
        }
        StockSearchDialog dialog = new StockSearchDialog(window);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            List<String> codes = dialog.getSelectedCodes();
            if (codes != null && !codes.isEmpty()) {
                Set<String> existing = new LinkedHashSet<>(parseCodes());
                for (String code : codes) {
                    String normalized = normalizeCode(code);
                    if (normalized != null) {
                        existing.add(normalized);
                    }
                }
                saveCodes(new ArrayList<>(existing));
                statusLabel.setText("已添加 " + codes.size() + " 只自选股，正在刷新...");
                refreshQuotes(true);
            }
        }
    }

    private void addCodesFromText(String input) {
        Set<String> existing = new LinkedHashSet<>(parseCodes());
        List<String> parsed = new ArrayList<>();
        for (String s : input.split("[,，\\s]+")) {
            String normalized = normalizeCode(s.trim());
            if (normalized != null && existing.add(normalized)) {
                parsed.add(normalized);
            }
        }
        if (!parsed.isEmpty()) {
            saveCodes(new ArrayList<>(existing));
            statusLabel.setText("已添加 " + parsed.size() + " 只自选股，正在刷新...");
            refreshQuotes(true);
        }
    }

    private void deleteSelected() {
        int[] rows = quoteTable.getSelectedRows();
        if (rows.length == 0) return;

        List<String> codesToRemove = new ArrayList<>();
        for (int row : rows) {
            int modelRow = quoteTable.convertRowIndexToModel(row);
            String code = String.valueOf(tableModel.getValueAt(modelRow, 0));
            codesToRemove.add(code);
        }

        List<String> codes = parseCodes();
        codes.removeIf(c -> codesToRemove.stream()
                .anyMatch(ctr -> c.toLowerCase().endsWith(ctr.toLowerCase())));
        saveCodes(codes);
        statusLabel.setText("已删除 " + codesToRemove.size() + " 只");
        refreshQuotes(true);
    }

    private void installPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("删除选中");
        deleteItem.addActionListener(e -> deleteSelected());
        popup.add(deleteItem);

        quoteTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { showPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { showPopup(e); }
            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = quoteTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    if (!quoteTable.isRowSelected(row)) {
                        quoteTable.setRowSelectionInterval(row, row);
                    }
                    popup.show(quoteTable, e.getX(), e.getY());
                }
            }
        });
    }

    private void refreshQuotes() {
        refreshQuotes(false);
    }

    private void refreshQuotes(boolean force) {
        List<String> codes = getFilteredCodes();
        List<String> indexCodes = currentIndexCodes();

        Set<String> merged = new LinkedHashSet<>(codes);
        merged.addAll(indexCodes);
        List<String> fetchCodes = new ArrayList<>(merged);
        if (fetchCodes.isEmpty()) {
            tableModel.setRowCount(0);
            setStatus(parseCodes().isEmpty() ? "点击「添加」搜索股票" : "当前市场无自选股");
            return;
        }

        boolean marketClosed = !marketOpenFor(fetchCodes);
        if (marketClosed) {
            setStatus("闭市中");
        } else {
            isRefreshing = true;
            setStatus("刷新中...");
        }
        countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;

        new Thread(() -> {
            Exception lastException = null;
            for (int attempt = 0; attempt <= 3; attempt++) {
                try {
                    List<QuoteItem> allItems = quoteService.fetchQuotes(fetchCodes);
                    SwingUtilities.invokeLater(() -> {
                        Map<String, QuoteItem> itemMap = new HashMap<>();
                        for (QuoteItem item : allItems) {
                            itemMap.put(CodeUtils.stripPrefix(item.getCode()), item);
                        }

                        List<QuoteItem> filteredItems = new ArrayList<>();
                        for (String code : codes) {
                            QuoteItem item = itemMap.get(CodeUtils.stripPrefix(code));
                            if (item != null) filteredItems.add(item);
                        }
                        updateTable(filteredItems);

                        lastIndexData.clear();
                        for (String idxCode : indexCodes) {
                            QuoteItem item = itemMap.get(CodeUtils.stripPrefix(idxCode));
                            if (item != null) lastIndexData.add(item);
                        }
                        updateIndexDisplay();

                        if (marketClosed) {
                            statusLabel.setText("闭市中 · 上次行情 " + LocalTime.now().format(timeFormatter));
                        }
                        isRefreshing = false;
                    });
                    return;
                } catch (Exception e) {
                    lastException = e;
                    if (attempt < 3) {
                        SwingUtilities.invokeLater(() -> setStatus(marketClosed ? "闭市中" : "行情加载中，正在重试..."));
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            Exception failure = lastException;
            SwingUtilities.invokeLater(() -> {
                if (tableModel.getRowCount() > 0) {
                    setStatus(marketClosed ? "闭市中 · 上次行情 " + LocalTime.now().format(timeFormatter) : "网络波动，显示上次行情");
                } else if (!marketClosed) {
                    setStatus("行情加载失败，请稍后重试");
                } else {
                    setStatus("闭市中");
                }
                isRefreshing = false;
            });
        }).start();
    }

    private void refreshIndexData(boolean force) {
        List<String> indexCodes = currentIndexCodes();
        if (indexCodes.isEmpty()) return;
        if (!force && !marketOpenFor(indexCodes)) return;
        new Thread(() -> {
            try {
                lastIndexData = quoteService.fetchQuotes(indexCodes);
            } catch (Exception ignored) {}
            SwingUtilities.invokeLater(() -> updateIndexDisplay());
        }).start();
    }

    private List<String> currentIndexCodes() {
        return switch (currentFilter) {
            case A -> INDEX_CODES_A;
            case HK -> INDEX_CODES_HK;
            case US -> INDEX_CODES_US;
            case GOLD -> INDEX_CODES_GOLD;
        };
    }

    private String[] currentIndexNames() {
        return switch (currentFilter) {
            case A -> INDEX_NAMES_A;
            case HK -> INDEX_NAMES_HK;
            case US -> INDEX_NAMES_US;
            case GOLD -> INDEX_NAMES_GOLD;
        };
    }

    private boolean marketOpenFor(List<String> codes) {
        if (codes.isEmpty()) return false;
        Set<MarketType> markets = codes.stream()
                .map(MarketType::fromCode)
                .collect(Collectors.toSet());
        for (MarketType m : markets) {
            if (isMarketOpen(m)) return true;
        }
        return false;
    }

    private boolean isMarketOpen(MarketType market) {
        return switch (market) {
            case A -> isAShareOpen();
            case HK -> isHKOpen();
            case US -> isUSOpen();
            case GOLD -> isGoldOpen();
        };
    }

    private boolean isAShareOpen() {
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now();
        boolean morning = !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(11, 30));
        boolean afternoon = !now.isBefore(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(15, 0));
        return morning || afternoon;
    }

    private boolean isHKOpen() {
        ZoneId hk = ZoneId.of("Asia/Hong_Kong");
        DayOfWeek dow = LocalDate.now(hk).getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now(hk);
        boolean morning = !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(12, 0));
        boolean afternoon = !now.isBefore(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(16, 0));
        return morning || afternoon;
    }

    private boolean isUSOpen() {
        ZoneId ny = ZoneId.of("America/New_York");
        ZonedDateTime now = ZonedDateTime.now(ny);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0));
    }

    private boolean isGoldOpen() {
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        ZoneId london = ZoneId.of("Europe/London");
        ZonedDateTime now = ZonedDateTime.now(sh);
        DayOfWeek dow = now.getDayOfWeek();
        LocalTime t = now.toLocalTime();
        boolean dst = london.getRules().isDaylightSavings(now.toInstant());
        LocalTime open = dst ? LocalTime.of(6, 0) : LocalTime.of(7, 0);
        LocalTime close = dst ? LocalTime.of(5, 0) : LocalTime.of(6, 0);

        if (dow == DayOfWeek.SUNDAY) return false;
        if (dow == DayOfWeek.SATURDAY) return t.isBefore(close);
        if (dow == DayOfWeek.MONDAY) return !t.isBefore(open);
        return !t.isBefore(open) || t.isBefore(close);
    }

    private void updateTable(List<QuoteItem> items) {
        tableModel.setRowCount(0);
        for (QuoteItem item : items) {
            tableModel.addRow(new Object[]{
                    item.getCode(),
                    item.getName(),
                    formatPrice(item.getPrice()),
                    formatPercent(item.getChangePercent()),
                    formatPrice(item.getChangeAmount())
            });
        }
        setStatus("");
        countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
    }

    public void pauseTimer() { autoRefreshTimer.stop(); }
    public void resumeTimer() {
        if (!autoRefreshTimer.isRunning()) autoRefreshTimer.start();
    }

/** 重新加载 AI 配置（异步从系统钥匙串读取，避免阻塞 EDT） */
    private void reloadAiConfig() {
        AIService.ModelInfo info = AIService.MODELS.get(currentModelId);
        if (info != null) {
            AIService.loadApiKeyAsync(info.provider).thenAccept(key ->
                    SwingUtilities.invokeLater(() -> currentApiKey = key));
        }
    }

    /** 保存 AI 配置（更新字段 + 持久化，保留其他厂商的 key） */
    private void saveAiConfig(String newKey, String newModelId) {
        if (newModelId == null || !AIService.MODELS.containsKey(newModelId)) {
            return;
        }
        currentModelId = newModelId;
        String provider = AIService.MODELS.get(newModelId).provider;
        AIService.saveConfig(provider, newKey);
        PropertiesComponent.getInstance().setValue("finance.ai.model", newModelId);
        currentApiKey = newKey;
    }

    /** 显示 AI 设置对话框 */
    public void showAiSettings() {
        AISettingsDialog dialog = new AISettingsDialog(project, currentModelId);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            saveAiConfig(dialog.getApiKey(), dialog.getModelId());
        }
    }

    public void showAiAnalysis() {
        PluginLog.info("[WatchlistPanel] showAiAnalysis called");
        if (aiDialog != null && aiDialog.isVisible()) {
            PluginLog.info("[WatchlistPanel] aiDialog already open, showing front");
            aiDialog.toFront();
            return;
        }
        PluginLog.info("[WatchlistPanel] currentApiKey=" + (currentApiKey != null ? "len=" + currentApiKey.length() : "null"));
        if (currentApiKey.isEmpty()) {
            PluginLog.info("[WatchlistPanel] no apiKey, opening settings dialog");
            AISettingsDialog dialog = new AISettingsDialog(project, currentModelId);
            dialog.setVisible(true);
            if (dialog.isConfirmed()) {
                saveAiConfig(dialog.getApiKey(), dialog.getModelId());
            } else {
                PluginLog.info("[WatchlistPanel] settings dialog cancelled");
                return;
            }
        }

        List<String> codes = getFilteredCodes();
        PluginLog.info("[WatchlistPanel] filtered codes count=" + codes.size());
        if (codes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前市场暂无自选股，请先添加...", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String marketName = currentFilter.getDisplayName();
        final String systemPrompt = AIService.ANALYSIS_SYSTEM_PROMPT;
        java.util.List<String> history = new java.util.concurrent.CopyOnWriteArrayList<>();

        aiDialog = buildAiAnalysisDialog(marketName, systemPrompt, history);
        PluginLog.info("[WatchlistPanel] dialog built, calling runAiAutoAnalysis");
        runAiAutoAnalysis(marketName, history);
    }

    /**
     * 构建 AI 分析聊天对话框
     */
    @SuppressWarnings("unchecked")
    private JDialog buildAiAnalysisDialog(String marketName, String systemPrompt,
                                          java.util.List<String> history) {
        JDialog dialog = new JDialog((Frame) null, "AI 自选股分析", false);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(560, 460);
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // ── 聊天区域（单个 JTextPane 拼 HTML） ──
        JTextPane chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setContentType("text/html");
        chatPane.setBackground(UIUtil.getPanelBackground());
        chatPane.setCaretColor(UIUtil.getPanelBackground());
        chatPane.setBorder(JBUI.Borders.empty(12, 20));
        chatPane.setFont(JBFont.label().deriveFont(11f));
        JScrollPane chatScrollPane = new JBScrollPane(chatPane);
        chatScrollPane.setBorder(null);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // 根据面板背景亮度选择文本颜色
        final Color panelBg = UIUtil.getPanelBackground();
        final boolean isDarkBg = (panelBg.getRed() + panelBg.getGreen() + panelBg.getBlue()) / 3 < 128;
        final String textColor = isDarkBg ? "#E0E0E0" : "#333333";

        // 预计算气泡颜色（Swing HTML 不支持 rgba，预先融合到背景色）
        final String userBg, aiBg;
        if (isDarkBg) {
            userBg = "#1B4332";  // 微信风格绿色
            aiBg = "#3A3B3D";
        } else {
            userBg = "#95EC69";  // 微信风格浅绿
            aiBg = "#FFFFFF";
        }

        StringBuilder chatHtml = new StringBuilder();
        java.util.function.BiConsumer<Boolean, String> appendMessage = (isUser, content) -> {
            PluginLog.info("[WatchlistPanel] appendMessage isUser=" + isUser + ", content_len=" +
                (content != null ? content.length() : 0) + ", preview=" +
                (content != null ? content.replace("\n","\\n").substring(0, Math.min(100, content.length())) : "null"));
            SwingUtilities.invokeLater(() -> {
                String safe = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                safe = safe.replace("\n", "<br>");
                String label = isUser ? "你" : "AI 分析";
                String labelColor = isUser ? "#007AFF" : "#34A853";
                String bubbleBg = isUser ? userBg : aiBg;
                String align = isUser ? "right" : "left";
                // 使用纯标签属性兼容 Swing HTML 3.2：
                // font 标签不包裹块级元素(div/p)，颜色直接写在 td 上
                chatHtml.append("<p align='").append(align).append("' style='margin-bottom:14px;'>")
                        .append("<font size='2' color='").append(labelColor)
                        .append("'><b>").append(label).append("</b></font><br>")
                        .append("<table align='").append(align).append("' border='0' cellpadding='6' cellspacing='0' bgcolor='").append(bubbleBg)
                        .append("'><tr><td><font size='3' color='").append(textColor).append("'>")
                        .append(safe).append("</font></td></tr></table>")
                        .append("</p>");
                String finalHtml = "<html><body style='margin:0;padding:0;background-color:#"
                        + String.format("%06X", UIUtil.getPanelBackground().getRGB() & 0xFFFFFF)
                        + ";color:" + textColor + ";'>" + chatHtml + "</body></html>";
                PluginLog.info("[WatchlistPanel] finalHtml_len=" + finalHtml.length() + ", preview=" + finalHtml.substring(0, Math.min(200, finalHtml.length())).replace("\n", "\\n"));
                chatPane.setText(finalHtml);
                chatPane.revalidate();
                chatPane.repaint();
                // JTextPane 的光标滚动不可靠，必须显式把 JScrollPane 滚动到底部
                SwingUtilities.invokeLater(() -> {
                    JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
                    vBar.setValue(vBar.getMaximum());
                });
            });
        };

        // ── 顶部加载面板 ──
        JPanel loadingPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        loadingPanel.setBackground(UIUtil.getPanelBackground());
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(16, 16));
        JLabel loadingLabel = new JLabel("正在获取行情数据...");
        loadingLabel.setFont(JBFont.label().deriveFont(12f));
        loadingLabel.setForeground(JBColor.namedColor("Label.disabledForeground", new Color(0x999999)));
        loadingPanel.add(progressBar);
        loadingPanel.add(loadingLabel);

        // ── 思考中提示 ──
        JPanel thinkingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        thinkingPanel.setBackground(UIUtil.getPanelBackground());
        JLabel thinkingLabel = new JLabel("AI 思考中...");
        thinkingLabel.setFont(JBFont.label().deriveFont(11f));
        thinkingLabel.setForeground(JBColor.namedColor("Label.disabledForeground", new Color(0x999999)));
        thinkingPanel.add(thinkingLabel);
        thinkingPanel.setVisible(false);

        // ── 输入区域 ──
        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.setBorder(JBUI.Borders.empty(0, 12, 12, 12));
        inputPanel.setBackground(UIUtil.getPanelBackground());
        JTextField inputField = new JTextField();
        inputField.setToolTipText("输入问题，按 Enter 发送");
        inputField.setFont(JBFont.label().deriveFont(13f));
        JButton sendBtn = new JButton("发送");
        sendBtn.setPreferredSize(new Dimension(60, 30));
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);

        // ── 发送消息 ──
        Runnable sendAction = () -> {
            String userMsg = inputField.getText().trim();
            if (userMsg.isEmpty()) return;
            inputField.setText("");
            history.add(userMsg);
            appendMessage.accept(true, userMsg);
            thinkingPanel.setVisible(true);
            sendBtn.setEnabled(false);

            String key = currentApiKey;
            String model = currentModelId;
            new Thread(() -> {
                try {
                    new AIService(key, model).chatAsync(systemPrompt, history).thenAccept(result -> {
                        SwingUtilities.invokeLater(() -> {
                            thinkingPanel.setVisible(false);
                            sendBtn.setEnabled(true);
                            history.add(result);
                            appendMessage.accept(false, result);
                        });
                    }).exceptionally(e -> {
                        SwingUtilities.invokeLater(() -> {
                            thinkingPanel.setVisible(false);
                            sendBtn.setEnabled(true);
                            // 失败也要补一条 assistant 占位，否则 history 的「偶数 user / 奇数 assistant」
                            // 交替被打破，后续轮次会把用户提问误当作上一轮结论基线
                            history.add(AI_FAILED_PLACEHOLDER);
                            appendMessage.accept(false, "AI 分析失败: " + e.getMessage());
                        });
                        return null;
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        thinkingPanel.setVisible(false);
                        sendBtn.setEnabled(true);
                        history.add(AI_FAILED_PLACEHOLDER);
                        appendMessage.accept(false, "服务初始化失败: " + e.getMessage());
                    });
                }
            }).start();
        };

        sendBtn.addActionListener(e -> sendAction.run());
        inputField.addActionListener(e -> sendAction.run());

        // Esc 关闭
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // 组装
        JPanel inputWrapper = new JPanel(new BorderLayout());
        inputWrapper.setBackground(UIUtil.getPanelBackground());
        inputWrapper.add(thinkingPanel, BorderLayout.NORTH);
        inputWrapper.add(inputPanel, BorderLayout.CENTER);

        dialog.add(loadingPanel, BorderLayout.NORTH);
        dialog.add(chatScrollPane, BorderLayout.CENTER);
        dialog.add(inputWrapper, BorderLayout.SOUTH);

        // 存储需要跨方法访问的组件
        dialog.getRootPane().putClientProperty("loadingPanel", loadingPanel);
        dialog.getRootPane().putClientProperty("loadingLabel", loadingLabel);
        dialog.getRootPane().putClientProperty("appendMessage", appendMessage);

        dialog.setVisible(true);
        return dialog;
    }

    /**
     * 后台获取行情数据并触发 AI 自动分析
     */
    @SuppressWarnings("unchecked")
    private void runAiAutoAnalysis(String marketName, java.util.List<String> history) {
        JDialog dialog = aiDialog;
        JPanel loadingPanel = (JPanel) dialog.getRootPane().getClientProperty("loadingPanel");
        JLabel loadingLabel = (JLabel) dialog.getRootPane().getClientProperty("loadingLabel");
        java.util.function.BiConsumer<Boolean, String> appendMessage =
                (java.util.function.BiConsumer<Boolean, String>) dialog.getRootPane().getClientProperty("appendMessage");

        new Thread(() -> {
            PluginLog.info("[WatchlistPanel] runAiAutoAnalysis thread started, market=" + marketName);
            List<QuoteItem> items = getFilteredItems();
            PluginLog.info("[WatchlistPanel] getFilteredItems returned " + (items != null ? items.size() : 0) + " items");
            if (items.isEmpty()) {
                PluginLog.info("[WatchlistPanel] runAiAutoAnalysis FAIL: no items, hiding loadingPanel");
                SwingUtilities.invokeLater(() -> {
                    loadingPanel.setVisible(false);
                    appendMessage.accept(false, "暂无行情数据，请稍后重试...");
                });
                return;
            }

            final List<QuoteItem> finalItems = items;
            final String marketContext = AIService.buildMarketContext(items, marketName);
            history.add(marketContext);

            SwingUtilities.invokeLater(() ->
                    loadingLabel.setText("正在分析 " + items.size() + " 只自选股..."));

            String key = currentApiKey;
            String model = currentModelId;
            PluginLog.info("[WatchlistPanel] using apiKey_len=" + (key != null ? key.length() : 0) + ", model=" + model);
            try {
                new AIService(key, model).analyzeWatchlistAsync(finalItems, marketName)
                    .orTimeout(60, TimeUnit.SECONDS)
                    .thenAccept(result -> {
                        PluginLog.info("[WatchlistPanel] analyzeWatchlistAsync SUCCESS, result_len=" +
                            (result != null ? result.length() : 0));
                        SwingUtilities.invokeLater(() -> {
                            loadingPanel.setVisible(false);
                            history.add(result);
                            appendMessage.accept(false, result);
                        });
                    }).exceptionally(e -> {
                        PluginLog.error("[WatchlistPanel] analyzeWatchlistAsync FAILED: " + e.getClass().getName() + " - " + e.getMessage(), e);
                        SwingUtilities.invokeLater(() -> {
                            loadingPanel.setVisible(false);
                            Throwable cause = (e instanceof java.util.concurrent.CompletionException) ? e.getCause() : e;
                            String msg = cause != null ? cause.getMessage() : e.getMessage();
                            if (cause instanceof java.util.concurrent.TimeoutException) {
                                msg = "AI 分析超时，请检查网络或 API Key 是否有效";
                            } else if (msg == null || msg.isEmpty()) {
                                msg = "AI 分析失败，请稍后重试";
                            }
                            appendMessage.accept(false, "AI 分析失败: " + msg);
                        });
                        return null;
                    });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    loadingPanel.setVisible(false);
                    appendMessage.accept(false, "服务初始化失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private List<QuoteItem> getFilteredItems() {
        List<String> codes = getFilteredCodes();
        if (codes.isEmpty()) return List.of();
        try {
            return quoteService.fetchQuotes(codes);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override public void removeNotify() { autoRefreshTimer.stop(); super.removeNotify(); }

    @Override public void addNotify() {
        super.addNotify();
        if (!autoRefreshTimer.isRunning()) autoRefreshTimer.start();
        refreshQuotes(true);
    }

    private void setupTableWidths() {
        int[] prefWidths = {JBUI.scale(85), JBUI.scale(220), JBUI.scale(90), JBUI.scale(90), JBUI.scale(90)};
        var columnModel = quoteTable.getColumnModel();
        for (int i = 0; i < columnModel.getColumnCount() && i < prefWidths.length; i++) {
            columnModel.getColumn(i).setMinWidth(JBUI.scale(50));
            columnModel.getColumn(i).setPreferredWidth(prefWidths[i]);
        }
        quoteTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    }

    private void centerTableHeader() {
        JTableHeader header = quoteTable.getTableHeader();
        DefaultTableCellRenderer renderer = (DefaultTableCellRenderer) header.getDefaultRenderer();
        renderer.setHorizontalAlignment(SwingConstants.CENTER);
    }

    private void setupTableSorting() {
        Comparator<String> numericComparator = Comparator.comparingDouble(this::parseDisplayNumber);
        for (int i = 2; i < tableModel.getColumnCount(); i++) {
            tableSorter.setComparator(i, numericComparator);
        }
        JTableHeader header = quoteTable.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int viewColumn = header.columnAtPoint(e.getPoint());
                if (viewColumn < 0) return;
                int modelColumn = quoteTable.convertColumnIndexToModel(viewColumn);
                applyNextSort(modelColumn);
            }
        });
    }

    private void applyNextSort(int modelColumn) {
        if (sortedColumn != modelColumn) {
            sortedColumn = modelColumn;
            sortState = 1;
        } else {
            sortState = (sortState + 1) % 3;
        }
        if (sortState == 0) {
            sortedColumn = -1;
            tableSorter.setSortKeys(null);
        } else {
            SortOrder order = sortState == 1 ? SortOrder.DESCENDING : SortOrder.ASCENDING;
            tableSorter.setSortKeys(List.of(new RowSorter.SortKey(modelColumn, order)));
        }
        saveSortState();
    }

    private void restoreSortState() {
        try {
            sortedColumn = Integer.parseInt(properties.getValue(SORT_COL_KEY, "-1"));
            sortState = Integer.parseInt(properties.getValue(SORT_STATE_KEY, "0"));
        } catch (NumberFormatException ignored) {
            sortedColumn = -1;
            sortState = 0;
        }
        if (sortedColumn >= 0) {
            SortOrder order = sortState == 1 ? SortOrder.DESCENDING : SortOrder.ASCENDING;
            tableSorter.setSortKeys(List.of(new RowSorter.SortKey(sortedColumn, order)));
        }
    }

    private void saveSortState() {
        if (sortedColumn >= 0) {
            properties.setValue(SORT_COL_KEY, String.valueOf(sortedColumn));
            properties.setValue(SORT_STATE_KEY, String.valueOf(sortState));
        } else {
            properties.setValue(SORT_COL_KEY, "-1");
            properties.setValue(SORT_STATE_KEY, "0");
        }
    }

    private String normalizeCode(String raw) {
        return CodeUtils.normalize(raw);
    }

    private List<String> getFilteredCodes() {
        List<String> codes = parseCodes();
        MarketType target = switch (currentFilter) {
            case A -> MarketType.A;
            case HK -> MarketType.HK;
            case US -> MarketType.US;
            case GOLD -> MarketType.GOLD;
        };
        return codes.stream()
                .filter(c -> MarketType.fromCode(c) == target)
                .collect(Collectors.toList());
    }

    private List<String> parseCodes() {
        String raw = properties.getValue(CODES_KEY, "");
        if (raw.isEmpty()) return new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (String s : raw.split("[,，\\s]+")) {
            String normalized = normalizeCode(s.trim());
            if (normalized != null) codes.add(normalized);
        }
        return codes;
    }

    private void saveCodes(List<String> codes) {
        properties.setValue(CODES_KEY, String.join(",", codes));
    }

    private String formatPrice(double price) {
        if (price == 0) return "-";
        return String.format("%.2f", price);
    }

    private String formatPercent(double percent) {
        return String.format("%+.2f%%", percent);
    }

    private double parseDisplayNumber(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).replace("%", "").replace("+", "").trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static class WatchlistCellRenderer extends DefaultTableCellRenderer {
        private static final Color RISE_COLOR = new JBColor(new Color(0xC62828), new Color(0xFF6B6B));
        private static final Color FALL_COLOR = new JBColor(new Color(0x2E7D32), new Color(0x69D98A));
        private static final double EPS = 1e-9;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(JBUI.Borders.empty(0, 6));
            setHorizontalAlignment(SwingConstants.CENTER);
            if (isSelected) return this;

            int modelColumn = table.convertColumnIndexToModel(column);
            if (modelColumn < 2) {
                setForeground(table.getForeground());
                return this;
            }

            int modelRow = table.convertRowIndexToModel(row);
            double percent = parsePercent(table.getModel().getValueAt(modelRow, 3));
            Color color;
            if (percent > EPS) {
                color = RISE_COLOR;
            } else if (percent < -EPS) {
                color = FALL_COLOR;
            } else {
                color = table.getForeground();
            }
            setForeground(color);
            return this;
        }

        private double parsePercent(Object value) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            try {
                return Double.parseDouble(String.valueOf(value).replace("%", "").replace("+", "").trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }
}

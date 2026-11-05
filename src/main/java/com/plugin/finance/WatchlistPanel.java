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
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Collator;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class WatchlistPanel extends JPanel {

    private static final String CODES_KEY = "eastmoney.7x24.watchlist.codes";
    private static final String SORT_COL_KEY = "eastmoney.7x24.watchlist.sortColumn";
    private static final String SORT_STATE_KEY = "eastmoney.7x24.watchlist.sortState";
    private static final String INDEX_KEY = "eastmoney.7x24.watchlist.index";
    private static final String FILTER_KEY = "eastmoney.7x24.watchlist.marketFilter";
    private static final String[] COLUMN_NAMES = {"名称", "最新价", "涨跌幅", "涨跌额"};
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
    /** 三类 AI 会话各自独立保存，收起后按入口恢复 */
    private static final String AI_DIALOG_ANALYSIS = "analysis";
    private static final String AI_DIALOG_SUMMARY = "summary";
    private static final String AI_DIALOG_INTERPRETATION = "interpretation";

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
    private String currentProfileId = "";
    /** 每个入口一份会话窗口：收起（隐藏）后仍保留 history 与气泡，再点同一入口原样恢复 */
    private final Map<String, JDialog> aiDialogs = new HashMap<>();
    private AISettingsDialog settingsDialog;

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
        // 自选代码与界面状态存应用级配置，同一 IDE 的多个项目共用一份
        properties = PropertiesComponent.getInstance();
        migrateProjectScopedSettings();
        // 首次读取会把旧版单份配置自动迁移成配置列表，并始终返回一份有效配置
        currentProfileId = AIService.getActiveProfileId();
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

    /** 旧版本把自选代码、排序、筛选、大盘选择存在项目级配置，各项目互不相通；
     *  改为全局共享后，首次运行把当前项目的旧值补种到应用级。
     *  仅当应用级从未写入过该键时才补种，用户在应用级修改/清空过不会被旧项目数据覆盖。 */
    private void migrateProjectScopedSettings() {
        PropertiesComponent legacy = PropertiesComponent.getInstance(project);
        seedIfAbsent(legacy, CODES_KEY);
        seedIfAbsent(legacy, SORT_COL_KEY);
        seedIfAbsent(legacy, SORT_STATE_KEY);
        seedIfAbsent(legacy, FILTER_KEY);
        for (MarketFilter f : MarketFilter.values()) {
            seedIfAbsent(legacy, INDEX_KEY + "." + f.name());
        }
    }

    private void seedIfAbsent(PropertiesComponent legacy, String key) {
        if (properties.getValue(key) != null) {
            return;
        }
        String value = legacy.getValue(key);
        if (value != null) {
            properties.setValue(key, value);
        }
    }

    private void tickAndAutoRefresh() {
        List<String> codes = getFilteredCodes();
        if (codes.isEmpty()) {
            // 无自选：保持「点击添加」提示，不覆盖为「闭市中」
            autoRefreshTimer.stop();
            return;
        }
        if (!marketOpenFor(codes)) {
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

        JLabel titleLabel = new JLabel("自选行情");
        titleLabel.setFont(JBFont.label().asBold());

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightPanel.add(addButton);
        rightPanel.add(refreshButton);

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

    /** 当前市场筛选名，供工具窗口常驻 AI 入口的 tooltip 显示 */
    public String currentMarketName() {
        return currentFilter.getDisplayName();
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
        quoteTable.setRowHeight(JBUI.scale(44));
        quoteTable.setShowVerticalLines(false);
        quoteTable.setIntercellSpacing(JBUI.emptySize());
        quoteTable.setDefaultRenderer(Object.class, new WatchlistCellRenderer());
        quoteTable.setRowSorter(tableSorter);
        quoteTable.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        quoteTable.getColumnModel().getColumn(0).setCellRenderer(new NameCodeCellRenderer());
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
            addSelectedCodes(dialog.getSelectedCodes());
        }
    }

    /** 把搜索对话框选中的代码并入自选（去重）并刷新 */
    private void addSelectedCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return;
        }
        Set<String> existing = new LinkedHashSet<>(parseCodes());
        for (String code : codes) {
            String normalized = normalizeCode(code);
            if (normalized != null) {
                existing.add(normalized);
            }
        }
        saveCodes(new ArrayList<>(existing));
        statusLabel.setText("已添加 " + codes.size() + " 只自选股，正在刷新...");
        resumeTimer();
        refreshQuotes(true);
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
            resumeTimer();
            refreshQuotes(true);
        }
    }

    private void deleteSelected() {
        int[] rows = quoteTable.getSelectedRows();
        if (rows.length == 0) return;

        List<String> codesToRemove = new ArrayList<>();
        for (int row : rows) {
            int modelRow = quoteTable.convertRowIndexToModel(row);
            Object v = tableModel.getValueAt(modelRow, 0);
            if (v instanceof QuoteItem) {
                codesToRemove.add(((QuoteItem) v).getCode());
            }
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
        if (codes.isEmpty()) {
            // 无自选时只拉底部大盘指数，状态栏保留添加提示（此前会误显示「闭市中」）
            tableModel.setRowCount(0);
            statusLabel.setText(parseCodes().isEmpty() ? "点击「添加」搜索股票" : "当前市场无自选股");
            if (!indexCodes.isEmpty()) {
                refreshIndexData(true);
            }
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
                    item,
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

    /** 当前配置对应的 PasswordSafe 凭据槽（每份配置一个，互不覆盖） */
    private String currentAiProvider() {
        return AIService.findProfile(currentProfileId).keySlot();
    }

    /** 按当前配置构造 AIService：地址、模型 ID、采样参数都来自该配置 */
    private AIService createAiService(String key) {
        return new AIService(key, AIService.findProfile(currentProfileId));
    }

    /** 重新加载 AI 配置（异步从系统钥匙串读取，避免阻塞 EDT） */
    private void reloadAiConfig() {
        AIService.loadApiKeyAsync(currentAiProvider()).thenAccept(key ->
                SwingUtilities.invokeLater(() -> currentApiKey = key));
    }

    /** 保存后的内存同步：配置列表与各份 Key 已由设置面板落盘，这里不再重复写 */
    private void saveAiConfig(String newKey, String newProfileId) {
        if (newProfileId == null || newProfileId.isEmpty()) {
            return;
        }
        currentProfileId = newProfileId;
        currentApiKey = newKey;
    }

    /** 从应用级配置刷新启用项（设置页可能被修改过），Key 异步刷新并返回给需要的调用方。
     *  返回 Future 让新入口能在 Key 真正就绪后再判断是否弹设置框，避免首次点击误判。 */
    private CompletableFuture<String> refreshAiConfigFromStorage() {
        AIService.invalidateProfileCache();
        currentProfileId = AIService.getActiveProfileId();
        final String slot = currentAiProvider();
        return AIService.loadApiKeyAsync(slot)
                .thenApply(key -> {
                    // 回到 EDT 时重读缓存而非使用异步开始时的快照：期间用户可能已在设置里
                    // 保存/清除了 Key，否则晚到的回调会把刚保存的 Key 覆盖回旧值
                    SwingUtilities.invokeLater(() ->
                            currentApiKey = AIService.getCachedApiKey(slot));
                    return key;
                });
    }

    /** 显示 AI 设置对话框（已打开则置前，避免重复弹窗） */
    public void showAiSettings() {
        if (settingsDialog != null && settingsDialog.isVisible()) {
            settingsDialog.toFront();
            return;
        }
        refreshAiConfigFromStorage();
        settingsDialog = new AISettingsDialog(project, this, currentProfileId);
        settingsDialog.setVisible(true);
        if (settingsDialog.isConfirmed()) {
            saveAiConfig(settingsDialog.getApiKey(), settingsDialog.getProfileId());
        }
    }

    /** 恢复某入口的会话窗口：窗口只是被收起（隐藏）时重新显示，气泡与 history 原样保留。
     *  JDialog 没有 Frame.ICONIFIED 最小化态，故用 isDisplayable 而非 isVisible 判断会话是否还在。 */
    private boolean restoreAiDialog(String key) {
        JDialog dialog = aiDialogs.get(key);
        if (dialog == null || !dialog.isDisplayable()) {
            return false;
        }
        if (!dialog.isVisible()) {
            dialog.setVisible(true);
        }
        dialog.toFront();
        dialog.requestFocus();
        return true;
    }

    /** 新建会话窗口并登记到对应入口；Esc 收起后仍可从同一入口恢复 */
    private JDialog openAiDialog(String key, String title, String systemPrompt,
                                 java.util.List<String> history) {
        JDialog dialog = buildAiChatDialog(title, systemPrompt, history);
        aiDialogs.put(key, dialog);
        return dialog;
    }

    public void showAiAnalysis() {
        PluginLog.info("[WatchlistPanel] showAiAnalysis called");
        refreshAiConfigFromStorage();
        if (restoreAiDialog(AI_DIALOG_ANALYSIS)) {
            PluginLog.info("[WatchlistPanel] analysis dialog restored");
            return;
        }
        PluginLog.info("[WatchlistPanel] currentApiKey=" + (currentApiKey != null ? "len=" + currentApiKey.length() : "null"));
        if (currentApiKey.isEmpty()) {
            PluginLog.info("[WatchlistPanel] no apiKey, opening settings dialog");
            showAiSettings();
            if (currentApiKey.isEmpty()) {
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

        JDialog dialog = openAiDialog(AI_DIALOG_ANALYSIS, "AI选股分析", systemPrompt, history);
        PluginLog.info("[WatchlistPanel] dialog built, calling runAiAutoAnalysis");
        runAiAutoAnalysis(marketName, history, dialog);
    }

    /** 最近要闻速览入口：把最新一批快讯注入 system prompt，复用 AI 对话框生成汇总，可继续追问。
     *  清单不进入用户气泡，避免 30 条快讯刷屏；Key 就绪后才决定是否需要弹设置框。 */
    @SuppressWarnings("unchecked")
    public void openNewsSummary(List<KuaixunItem> items) {
        if (items == null || items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "快讯尚未加载完成，请稍后重试", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (restoreAiDialog(AI_DIALOG_SUMMARY)) {
            return;
        }
        final String context = AIService.buildSummaryContext(items);
        if (context.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可用于汇总的快讯", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String systemPrompt = AIService.NEWS_SUMMARY_PROMPT + context;
        // 推理模型对 user message 比 system prompt 敏感，关键约束在首发消息里再压一遍
        final String firstMessage =
                "请用简体中文按三段格式汇总：直接从【一句话综述】开始，依次写【要点】【值得留意】，" +
                "段落名原样保留；不要思考过程、不要英文标题、不要数字编号。";

        refreshAiConfigFromStorage().exceptionally(e -> "").thenAccept(key ->
                SwingUtilities.invokeLater(() -> {
                    currentApiKey = key;
                    if (key.isEmpty()) {
                        showAiSettings();
                        if (currentApiKey.isEmpty()) {
                            return;
                        }
                    }
                    if (restoreAiDialog(AI_DIALOG_SUMMARY)) {
                        return;
                    }
                    java.util.List<String> history = new java.util.concurrent.CopyOnWriteArrayList<>();
                    JDialog dialog = openAiDialog(AI_DIALOG_SUMMARY, "AI速览", systemPrompt, history);
                    JComponent loading = (JComponent) dialog.getRootPane().getClientProperty("loadingPanel");
                    JLabel loadingLabel = (JLabel) dialog.getRootPane().getClientProperty("loadingLabel");
                    if (loading != null && loadingLabel != null) {
                        // 与自选分析一致的等待计时：请求结束后 loadingPanel 被隐藏，下一次 tick 自停
                        final int newsCount = items.size();
                        final long startAt = System.currentTimeMillis();
                        final Timer waitTimer = new Timer(1000, ev -> {
                            if (!loading.isVisible()) {
                                ((Timer) ev.getSource()).stop();
                                return;
                            }
                            long sec = (System.currentTimeMillis() - startAt) / 1000;
                            loadingLabel.setText("正在汇总 " + newsCount + " 条快讯... 已等待 " + sec + " 秒");
                        });
                        loadingLabel.setText("正在汇总 " + newsCount + " 条快讯...");
                        loading.setVisible(true);
                        waitTimer.start();
                    }
                    // 指令只发给模型，不展示成用户气泡（与自选分析一致）
                    Object send = dialog.getRootPane().getClientProperty("sendMessageSilent");
                    if (send instanceof java.util.function.Consumer) {
                        ((java.util.function.Consumer<Object>) send).accept(firstMessage);
                    }
                }));
    }

    /** 快讯 AI 解读入口：后台补齐自选股名称后，在 AI 对话框发起解读，可继续追问 */
    @SuppressWarnings("unchecked")
    public void openNewsInterpretation(String newsTitle, String newsDigest) {
        PluginLog.info("[WatchlistPanel] openNewsInterpretation: " + newsTitle);
        refreshAiConfigFromStorage();
        if (restoreAiDialog(AI_DIALOG_INTERPRETATION)) {
            return;
        }
        if (currentApiKey.isEmpty()) {
            showAiSettings();
            if (currentApiKey.isEmpty()) {
                return;
            }
        }
        final String title = newsTitle == null ? "" : newsTitle;
        final String digest = newsDigest == null ? "" : newsDigest;
        new Thread(() -> {
            // 自选清单带上名称，AI 才能按行业/主题做关联（一次批量行情请求，失败降级为纯代码）
            String brief;
            List<String> codes = parseCodes();
            if (codes.isEmpty()) {
                brief = "（无）";
            } else {
                StringBuilder sb = new StringBuilder();
                try {
                    for (QuoteItem it : quoteService.fetchQuotes(codes)) {
                        sb.append("- ").append(it.getName()).append("(").append(it.getCode()).append(")\n");
                    }
                } catch (Exception e) {
                    PluginLog.info("[WatchlistPanel] 快讯解读取自选行情失败，降级为代码清单: " + e.getMessage());
                }
                if (sb.length() == 0) {
                    for (String code : codes) {
                        sb.append("- ").append(code).append("\n");
                    }
                }
                brief = sb.toString();
            }
            String digestText = digest.isEmpty() || digest.equals(title) ? "（无，仅标题）" : digest;
            // 自选清单附在系统提示里传给模型（关联分析用），不显示在用户气泡中
            final String systemPrompt = AIService.NEWS_INTERPRET_PROMPT
                    + "\n\n用户自选股清单：\n" + brief;
            String firstMessage = "请解读以下财经快讯：\n【标题】" + title
                    + "\n【摘要】" + digestText;
            SwingUtilities.invokeLater(() -> {
                if (restoreAiDialog(AI_DIALOG_INTERPRETATION)) {
                    return;
                }
                List<String> history = new java.util.concurrent.CopyOnWriteArrayList<>();
                JDialog dialog = openAiDialog(AI_DIALOG_INTERPRETATION, "AI 快讯解读", systemPrompt, history);
                JComponent loading = (JComponent) dialog.getRootPane().getClientProperty("loadingPanel");
                if (loading != null) {
                    loading.setVisible(false);
                }
                Object send = dialog.getRootPane().getClientProperty("sendMessage");
                if (send instanceof java.util.function.Consumer) {
                    ((java.util.function.Consumer<Object>) send).accept(firstMessage);
                }
            });
        }).start();
    }

    /**
     * 构建 AI 对话框（自选分析、快讯解读共用）
     */
    @SuppressWarnings("unchecked")
    /** 聊天气泡最大文本宽度（对话框固定 560，留出边距与内边距） */
    private static final int MAX_BUBBLE_TEXT_WIDTH = 380;
    private static final int MIN_BUBBLE_TEXT_WIDTH = 48;
    /** 与 IDEA 蓝灰系一致的气泡底色，随明暗主题切换；不再用微信绿 */
    private static final Color USER_BUBBLE_BG =
            new JBColor(new Color(0xD6E8FF), new Color(0x2F4A6B));
    private static final Color AI_BUBBLE_BG =
            new JBColor(new Color(0xF4F8F8), new Color(0x3A3B3D));

    /** 自绘圆角气泡；Swing HTML 3.2 不支持 border-radius，只能画出来 */
    private static class BubblePanel extends JPanel {
        private final Color bg;

        BubblePanel(Color bg, JTextArea area) {
            this.bg = bg;
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(JBUI.Borders.empty(6, 12));
            add(area, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** 一条消息：行容器 + 气泡 + 可流式原地更新的文本区，宽度按最长行自适应 */
    private static class Bubble {
        final JPanel row;
        final BubblePanel bubble;
        final JTextArea area;

        Bubble(boolean isUser, String text) {
            area = new JTextArea(text == null ? "" : text);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setOpaque(false);
            area.setBorder(null);
            Font labelFont = UIManager.getFont("Label.font");
            area.setFont(labelFont != null ? labelFont.deriveFont(13f)
                    : JBFont.label().deriveFont(13f));
            area.setForeground(UIUtil.getLabelForeground());
            bubble = new BubblePanel(isUser ? USER_BUBBLE_BG : AI_BUBBLE_BG, area);
            row = new JPanel(new FlowLayout(isUser ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.add(bubble);
            setText(text == null ? "" : text);
        }

        void setText(String text) {
            area.setText(text);
            FontMetrics fm = area.getFontMetrics(area.getFont());
            int natural = 0;
            for (String line : text.split("\n", -1)) {
                natural = Math.max(natural, fm.stringWidth(line));
            }
            int w = Math.max(MIN_BUBBLE_TEXT_WIDTH,
                    Math.min(natural + 2, MAX_BUBBLE_TEXT_WIDTH));
            // 给定宽度后取换行后的真实高度，避免气泡留大片空白；
            // FontMetrics 估算行数作为下界，防止不同 LAF 下 getPreferredSize 不按当前宽度换行
            int estimatedLines = 0;
            for (String line : text.split("\n", -1)) {
                estimatedLines += Math.max(1,
                        (int) Math.ceil((double) fm.stringWidth(line) / Math.max(1, w)));
            }
            area.setSize(new Dimension(w, Short.MAX_VALUE));
            int lineHeight = fm.getHeight();
            int measured = area.getPreferredSize().height;
            // 上限收在估算行数 +1 行，避免 JTextArea 尾部多算一行造成气泡底部大片空白
            int h = Math.max(lineHeight * estimatedLines,
                    Math.min(measured, lineHeight * (estimatedLines + 1)));
            area.setPreferredSize(new Dimension(w, h));
            bubble.setPreferredSize(new Dimension(w + 24, h + 12));
            bubble.revalidate();
        }
    }

    private JDialog buildAiChatDialog(String title, String systemPrompt,
                                      java.util.List<String> history) {
        // 以当前 IDE 窗口为 owner 并相对其居中，与「添加」「设置」弹窗位置统一
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                title, Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(560, 460);
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        // JDialog 没有 Frame.ICONIFIED 最小化态：Esc 用 setVisible(false) 收起并保留会话，
        // 标题栏关闭按钮仍为真正关闭（dispose），方便重新开一轮新会话
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // ── 聊天区域：每条消息一个自绘圆角气泡，宽度随内容自适应 ──
        JPanel messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(UIUtil.getPanelBackground());
        messagesPanel.setBorder(JBUI.Borders.empty(10, 14));
        JScrollPane chatScrollPane = new JBScrollPane(messagesPanel);
        chatScrollPane.setBorder(null);
        chatScrollPane.getViewport().setBackground(UIUtil.getPanelBackground());
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        final java.util.concurrent.atomic.AtomicReference<Runnable> cancelRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Bubble[] streamingBubble = {null};
        final StringBuilder streamingText = new StringBuilder();
        final long[] lastRender = {0L};

        Runnable scrollToBottom = () -> SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
        Runnable reflowMessages = () -> {
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom.run();
        };

        // Esc 收起时窗口只是隐藏（仍 displayable），请求仍在进行：下面三个回调不能用
        // isShowing() 提前返回，否则收起期间的消息会丢失；只有真正 dispose 后才跳过。
        // 普通消息：左右对齐即可区分角色，不再浮动「你 / AI 分析」小标签
        java.util.function.BiConsumer<Boolean, String> appendMessage = (isUser, content) -> {
            SwingUtilities.invokeLater(() -> {
                if (!dialog.isDisplayable()) return;
                streamingBubble[0] = null;
                streamingText.setLength(0);
                messagesPanel.add(new Bubble(isUser, content == null ? "" : content).row);
                messagesPanel.add(Box.createVerticalStrut(JBUI.scale(8)));
                reflowMessages.run();
            });
        };

        // 流式增量：原地更新同一个气泡的文本与尺寸，不重建整段聊天区
        java.util.function.Consumer<String> updateStreamingAi = chunk ->
                SwingUtilities.invokeLater(() -> {
                    if (!dialog.isDisplayable()) return;
                    boolean first = streamingBubble[0] == null;
                    if (first) {
                        Bubble b = new Bubble(false, "");
                        streamingBubble[0] = b;
                        streamingText.setLength(0);
                        messagesPanel.add(b.row);
                        messagesPanel.add(Box.createVerticalStrut(JBUI.scale(8)));
                    }
                    streamingText.append(chunk);
                    long now = System.currentTimeMillis();
                    if (!first && now - lastRender[0] < 80) {
                        return;
                    }
                    lastRender[0] = now;
                    streamingBubble[0].setText(streamingText.toString());
                    reflowMessages.run();
                });

        // 流结束：用最终（后处理后）文本替换流式气泡内容；无 chunk 时退化为普通追加
        java.util.function.Consumer<String> finishStreaming = finalText ->
                SwingUtilities.invokeLater(() -> {
                    if (!dialog.isDisplayable()) return;
                    String text = finalText == null ? "" : finalText;
                    if (streamingBubble[0] != null) {
                        streamingBubble[0].setText(text);
                    } else {
                        messagesPanel.add(new Bubble(false, text).row);
                        messagesPanel.add(Box.createVerticalStrut(JBUI.scale(8)));
                    }
                    streamingBubble[0] = null;
                    streamingText.setLength(0);
                    reflowMessages.run();
                });

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
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(6, 8)));
        JButton sendBtn = new JButton("发送");
        sendBtn.setPreferredSize(new Dimension(60, 30));
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);

        // 发送/停止 共用一个按钮：请求期间切换文案，不再单独放一个抢眼的停止按钮
        final boolean[] requesting = {false};
        java.util.function.Consumer<Boolean> setRequesting = busy -> {
            requesting[0] = busy;
            sendBtn.setText(busy ? "停止" : "发送");
            // 按钮始终可点：非请求中用于发送，请求中用于停止
            sendBtn.setToolTipText(busy ? "中止本次生成" : "发送问题（Enter）");
        };

        // ── 发送消息：输入框回车/按钮触发；外部入口经 clientProperty 拿 sendMessage 首发提问 ──
        // 静默首发用于自动触发的入口（速览）：指令只进 history，不占用一条用户气泡
        final boolean[] showUserBubble = {true};
        java.util.function.Consumer<String> sendMessage = text -> {
            String userMsg = text == null ? "" : text.trim();
            if (userMsg.isEmpty()) return;
            inputField.setText("");
            history.add(userMsg);
            if (showUserBubble[0]) {
                appendMessage.accept(true, userMsg);
            }
            thinkingPanel.setVisible(true);
            setRequesting.accept(true);

            // 回调在 EDT 上取出，供后台线程安全引用
            JRootPane root = dialog.getRootPane();
            java.util.function.Consumer<String> onChunk =
                    (java.util.function.Consumer<String>) root.getClientProperty("streamingChunk");
            java.util.function.Consumer<String> finish =
                    (java.util.function.Consumer<String>) root.getClientProperty("finishStreaming");
            java.util.function.Consumer<Runnable> setCancel =
                    (java.util.function.Consumer<Runnable>) root.getClientProperty("setCancel");
            java.util.function.Consumer<String> showAiResult = msg -> {
                if (finish != null) {
                    finish.accept(msg);
                } else {
                    appendMessage.accept(false, msg);
                }
            };

            String key = currentApiKey;
            new Thread(() -> {
                try {
                    AIService svc = createAiService(key);
                    if (setCancel != null) {
                        setCancel.accept(svc::cancel);
                    }
                    svc.chatAsync(systemPrompt, history, onChunk).thenAccept(result -> {
                        SwingUtilities.invokeLater(() -> {
                            thinkingPanel.setVisible(false);
                            loadingPanel.setVisible(false);
                            setRequesting.accept(false);
                            history.add(result);
                            showAiResult.accept(result);
                        });
                    }).exceptionally(e -> {
                        SwingUtilities.invokeLater(() -> {
                            thinkingPanel.setVisible(false);
                            loadingPanel.setVisible(false);
                            setRequesting.accept(false);
                            // 失败也要补一条 assistant 占位，否则 history 的「偶数 user / 奇数 assistant」
                            // 交替被打破，后续轮次会把用户提问误当作上一轮结论基线
                            history.add(AI_FAILED_PLACEHOLDER);
                            showAiResult.accept("AI 分析失败: " + e.getMessage());
                        });
                        return null;
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        thinkingPanel.setVisible(false);
                        loadingPanel.setVisible(false);
                        setRequesting.accept(false);
                        history.add(AI_FAILED_PLACEHOLDER);
                        showAiResult.accept("服务初始化失败: " + e.getMessage());
                    });
                }
            }).start();
        };

        // 自动触发的首发消息：同样进 history 以保证角色交替，但不显示用户气泡
        java.util.function.Consumer<String> sendMessageSilent = text -> {
            showUserBubble[0] = false;
            try {
                sendMessage.accept(text);
            } finally {
                showUserBubble[0] = true;
            }
        };

        Runnable sendAction = () -> {
            if (requesting[0]) {
                Runnable cancel = cancelRef.get();
                if (cancel != null) {
                    cancel.run();
                }
            } else {
                sendMessage.accept(inputField.getText());
            }
        };
        sendBtn.addActionListener(e -> sendAction.run());
        // 请求中按 Enter 不做任何事，避免误触发停止或重复发送
        inputField.addActionListener(e -> {
            if (!requesting[0]) {
                sendAction.run();
            }
        });

        // Esc 收起：只隐藏不销毁，气泡与 history 保留，再点同一入口原样恢复；
        // 想彻底重开一轮，用标题栏关闭按钮
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.setVisible(false),
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
        dialog.getRootPane().putClientProperty("sendMessage", sendMessage);
        dialog.getRootPane().putClientProperty("sendMessageSilent", sendMessageSilent);
        dialog.getRootPane().putClientProperty("streamingChunk", updateStreamingAi);
        dialog.getRootPane().putClientProperty("finishStreaming", finishStreaming);
        dialog.getRootPane().putClientProperty("setCancel",
                (java.util.function.Consumer<Runnable>) cancelRef::set);
        dialog.getRootPane().putClientProperty("setRequesting", setRequesting);

        dialog.setVisible(true);
        return dialog;
    }

    /**
     * 后台获取行情数据并触发 AI 自动分析
     */
    @SuppressWarnings("unchecked")
    private void runAiAutoAnalysis(String marketName, java.util.List<String> history, JDialog dialog) {
        JRootPane root = dialog.getRootPane();
        JPanel loadingPanel = (JPanel) root.getClientProperty("loadingPanel");
        JLabel loadingLabel = (JLabel) root.getClientProperty("loadingLabel");
        java.util.function.BiConsumer<Boolean, String> appendMessage =
                (java.util.function.BiConsumer<Boolean, String>) root.getClientProperty("appendMessage");
        java.util.function.Consumer<String> onChunk =
                (java.util.function.Consumer<String>) root.getClientProperty("streamingChunk");
        java.util.function.Consumer<String> finish =
                (java.util.function.Consumer<String>) root.getClientProperty("finishStreaming");
        java.util.function.Consumer<Runnable> setCancel =
                (java.util.function.Consumer<Runnable>) root.getClientProperty("setCancel");
        java.util.function.Consumer<Boolean> setRequesting =
                (java.util.function.Consumer<Boolean>) root.getClientProperty("setRequesting");
        // 首轮分析也占用发送按钮：期间变为「停止」，同时避免用户并发发起追问
        if (setRequesting != null) {
            setRequesting.accept(true);
        }
        java.util.function.Consumer<String> showAiResult = msg -> {
            if (setRequesting != null) {
                setRequesting.accept(false);
            }
            if (finish != null) {
                finish.accept(msg);
            } else {
                appendMessage.accept(false, msg);
            }
        };

        new Thread(() -> {
            PluginLog.info("[WatchlistPanel] runAiAutoAnalysis thread started, market=" + marketName);
            List<QuoteItem> items = getFilteredItems();
            PluginLog.info("[WatchlistPanel] getFilteredItems returned " + (items != null ? items.size() : 0) + " items");
            if (items.isEmpty()) {
                PluginLog.info("[WatchlistPanel] runAiAutoAnalysis FAIL: no items, hiding loadingPanel");
                SwingUtilities.invokeLater(() -> {
                    loadingPanel.setVisible(false);
                    if (setRequesting != null) {
                        setRequesting.accept(false);
                    }
                    appendMessage.accept(false, "暂无行情数据，请稍后重试...");
                });
                return;
            }

            final List<QuoteItem> finalItems = items;
            final String marketContext = AIService.buildMarketContext(items, marketName);
            history.add(marketContext);

            // 等待期间给出耗时反馈；loadingPanel 隐藏后下一次 tick 自停，无需改动各回调
            final long startAt = System.currentTimeMillis();
            final Timer waitTimer = new Timer(1000, ev -> {
                if (!loadingPanel.isVisible()) {
                    ((Timer) ev.getSource()).stop();
                    return;
                }
                long sec = (System.currentTimeMillis() - startAt) / 1000;
                loadingLabel.setText("正在分析 " + items.size() + " 只自选股... 已等待 " + sec + " 秒");
            });
            SwingUtilities.invokeLater(() -> {
                loadingLabel.setText("正在分析 " + items.size() + " 只自选股...");
                waitTimer.start();
            });

            String key = currentApiKey;
            AIService.AiProfile active = AIService.findProfile(currentProfileId);
            PluginLog.info("[WatchlistPanel] using apiKey_len=" + (key != null ? key.length() : 0)
                    + ", profile=" + active.displayName() + ", model=" + active.model);
            try {
                // 不再叠加全局 orTimeout：callAPI 已有 60s 读取超时，同长的外层超时会在响应
                // 到达前抢先判定失败，且不会取消底层请求，导致已生成的结果被丢弃
                AIService svc = createAiService(key);
                if (setCancel != null) {
                    setCancel.accept(svc::cancel);
                }
                svc.analyzeWatchlistAsync(finalItems, marketName, onChunk)
                    .thenAccept(result -> {
                        PluginLog.info("[WatchlistPanel] analyzeWatchlistAsync SUCCESS, result_len=" +
                            (result != null ? result.length() : 0));
                        SwingUtilities.invokeLater(() -> {
                            loadingPanel.setVisible(false);
                            history.add(result);
                            showAiResult.accept(result);
                        });
                    }).exceptionally(e -> {
                        PluginLog.error("[WatchlistPanel] analyzeWatchlistAsync FAILED: " + e.getClass().getName() + " - " + e.getMessage(), e);
                        SwingUtilities.invokeLater(() -> {
                            loadingPanel.setVisible(false);
                            Throwable cause = (e instanceof java.util.concurrent.CompletionException) ? e.getCause() : e;
                            if (cause instanceof java.util.concurrent.TimeoutException) {
                                showAiResult.accept(AIService.TIMEOUT_HINT);
                                return;
                            }
                            String msg = cause != null ? cause.getMessage() : e.getMessage();
                            if (msg == null || msg.isEmpty()) {
                                msg = "AI 分析失败，请稍后重试";
                            }
                            showAiResult.accept("AI 分析失败: " + msg);
                        });
                        return null;
                    });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    loadingPanel.setVisible(false);
                    showAiResult.accept("服务初始化失败: " + e.getMessage());
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
        int[] prefWidths = {JBUI.scale(130), JBUI.scale(95), JBUI.scale(95), JBUI.scale(95)};
        var columnModel = quoteTable.getColumnModel();
        for (int i = 0; i < columnModel.getColumnCount() && i < prefWidths.length; i++) {
            columnModel.getColumn(i).setMinWidth(JBUI.scale(50));
            columnModel.getColumn(i).setPreferredWidth(prefWidths[i]);
        }
        quoteTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    }

    /** 表头统一居中 */
    private void centerTableHeader() {
        JTableHeader header = quoteTable.getTableHeader();
        DefaultTableCellRenderer renderer = (DefaultTableCellRenderer) header.getDefaultRenderer();
        renderer.setHorizontalAlignment(SwingConstants.CENTER);
    }

    private void setupTableSorting() {
        Comparator<Object> nameComparator = Comparator.comparing(
                o -> o instanceof QuoteItem ? ((QuoteItem) o).getName() : String.valueOf(o),
                Collator.getInstance(Locale.CHINA));
        tableSorter.setComparator(0, nameComparator);
        Comparator<String> numericComparator = Comparator.comparingDouble(this::parseDisplayNumber);
        for (int i = 1; i < tableModel.getColumnCount(); i++) {
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
        if (sortedColumn >= tableModel.getColumnCount()) {
            // 旧版表格是 5 列（代码/名称分列），恢复时序号可能越界
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

    /** 名称 + 代码两行式单元格：名称一行、代码小字灰色一行。
     *  面板保持透明，否则会盖住表格自绘的行分隔线（数值列渲染器透明、行线可见，需保持一致）；选中时才自行填充高亮背景。 */
    private static class NameCodeCellRenderer extends JPanel implements TableCellRenderer {
        private final JLabel nameLabel = new JLabel();
        private final JLabel codeLabel = new JLabel();
        private boolean selected = false;

        private NameCodeCellRenderer() {
            super(new BorderLayout(0, 0));
            setOpaque(false);
            setBorder(JBUI.Borders.empty(3, 6));
            nameLabel.setFont(JBFont.label());
            nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
            codeLabel.setFont(JBFont.label().deriveFont(Font.PLAIN, JBUI.scaleFontSize(11f)));
            codeLabel.setHorizontalAlignment(SwingConstants.CENTER);
            add(nameLabel, BorderLayout.CENTER);
            add(codeLabel, BorderLayout.SOUTH);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            selected = isSelected;
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            if (value instanceof QuoteItem) {
                QuoteItem item = (QuoteItem) value;
                nameLabel.setText(item.getName());
                codeLabel.setText(item.getCode());
            } else {
                nameLabel.setText(value == null ? "" : String.valueOf(value));
                codeLabel.setText("");
            }
            Color mainFg = isSelected ? table.getSelectionForeground() : table.getForeground();
            nameLabel.setForeground(mainFg);
            codeLabel.setForeground(isSelected ? mainFg : UIManager.getColor("Label.infoForeground"));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (selected) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            super.paintComponent(g);
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
            if (modelColumn < 1) {
                setForeground(table.getForeground());
                return this;
            }

            int modelRow = table.convertRowIndexToModel(row);
            double percent = parsePercent(table.getModel().getValueAt(modelRow, 2));
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

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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Collator;
import java.time.LocalTime;
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
    /** 三类 AI 会话各自独立保存，关闭页签后按入口重开 */
    private static final String AI_SESSION_ANALYSIS = "analysis";
    private static final String AI_SESSION_SUMMARY = "summary";
    private static final String AI_SESSION_INTERPRETATION = "interpretation";

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
    /** AI 会话页签的宿主：由外层 KuaixunPanel 注入，未挂载时 AI 入口不可用 */
    private AiSessionHost aiSessionHost;
    /** 快讯解读会话当前对应的话题；切换新闻时重建会话，不能沿用上一条的上下文 */
    private String interpretationTitle = null;
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
        List<String> before = new ArrayList<>(codes);
        codes.removeIf(c -> codesToRemove.stream()
                .anyMatch(ctr -> c.toLowerCase().endsWith(ctr.toLowerCase())));
        saveCodes(codes);
        for (String code : before) {
            if (!codes.contains(code)) {
                AbnormalThresholds.set(code, null);
            }
        }
        statusLabel.setText("已删除 " + codesToRemove.size() + " 只");
        refreshQuotes(true);
    }

    /** 右键为选中自选设置个股异动阈值（%），留空恢复默认；支持多选批量设置 */
    private void showThresholdDialog() {
        int[] rows = quoteTable.getSelectedRows();
        if (rows.length == 0) return;
        List<String> storedCodes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int row : rows) {
            Object v = tableModel.getValueAt(quoteTable.convertRowIndexToModel(row), 0);
            if (v instanceof QuoteItem) {
                QuoteItem item = (QuoteItem) v;
                String stored = storedCodeFor(item);
                if (stored != null) {
                    storedCodes.add(stored);
                    names.add(item.getName());
                }
            }
        }
        if (storedCodes.isEmpty()) return;

        Double current = AbnormalThresholds.getSetting(storedCodes.get(0));
        String target = storedCodes.size() == 1
                ? "「" + names.get(0) + "」"
                : "选中的 " + storedCodes.size() + " 只自选";
        String input = (String) JOptionPane.showInputDialog(this,
                "为" + target + "设置异动阈值（%）：\n当日 |涨跌幅| 达到该值计为异动；留空恢复默认 "
                        + String.format("%.0f", AbnormalThresholds.DEFAULT) + "%。",
                "异动阈值", JOptionPane.PLAIN_MESSAGE, null, null,
                current == null ? "" : String.valueOf(current));
        if (input == null) return;
        input = input.trim();
        Double value = null;
        if (!input.isEmpty()) {
            try {
                value = Double.parseDouble(input);
            } catch (NumberFormatException ignored) {}
            if (value == null || value <= 0 || value > 100) {
                JOptionPane.showMessageDialog(this, "阈值需为 0-100 之间的数字，例如 3 或 1.5",
                        "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        for (String code : storedCodes) {
            AbnormalThresholds.set(code, value);
        }
        MarketMonitorService.getInstance().refreshNow();
        statusLabel.setText(value == null ? "已恢复默认异动阈值" : "异动阈值已设为 " + input + "%");
    }

    /** 表格内是行情源展示码（裸码/大写），映射回自选存储的规范化代码 */
    private String storedCodeFor(QuoteItem item) {
        String display = CodeUtils.stripPrefix(item.getCode());
        for (String code : parseCodes()) {
            if (CodeUtils.stripPrefix(code).equalsIgnoreCase(display)) {
                return code;
            }
        }
        return null;
    }

    private void installPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("删除选中");
        deleteItem.addActionListener(e -> deleteSelected());
        popup.add(deleteItem);
        JMenuItem thresholdItem = new JMenuItem("异动阈值…");
        thresholdItem.addActionListener(e -> showThresholdDialog());
        popup.add(thresholdItem);

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
        return MarketHours.isOpen(market);
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

    /** 保存后的内存同步：配置列表与各份 Key 已由设置面板落盘，这里不再重复写。
     *  同一配置下的空 Key 可能只是异步回填没完成，保留现值；
     *  切换配置时以新配置为准，避免把旧 Key 发到新地址。 */
    private void saveAiConfig(String newKey, String newProfileId) {
        if (newProfileId == null || newProfileId.isEmpty()) {
            return;
        }
        boolean switched = !newProfileId.equals(currentProfileId);
        currentProfileId = newProfileId;
        if (!newKey.isEmpty() || switched) {
            currentApiKey = newKey;
        }
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

    /** 恢复某入口的会话：页签还在就切过去，气泡与 history 原样保留 */
    private boolean restoreAiSession(String key) {
        if (aiSessionHost == null) {
            return false;
        }
        AiChatPanel session = aiSessionHost.findAiSession(key);
        if (session == null) {
            return false;
        }
        aiSessionHost.selectAiSession(session);
        return true;
    }

    /** 新建会话页签并登记到对应入口；按当前配置构造 AIService，Key 在发送时读取 */
    private AiChatPanel openAiSession(String key, String title, String systemPrompt) {
        AiChatPanel session = new AiChatPanel(systemPrompt, () -> createAiService(currentApiKey));
        aiSessionHost.addAiSession(key, title, session);
        return session;
    }

    public void setAiSessionHost(AiSessionHost host) {
        this.aiSessionHost = host;
    }

    public void showAiAnalysis() {
        PluginLog.info("[WatchlistPanel] showAiAnalysis called");
        if (aiSessionHost == null) {
            return;
        }
        // 必须等 Key 真正读完再判断：首次点击时 currentApiKey 还没回填，
        // 同步读取会误判为空并多弹一次设置框
        refreshAiConfigFromStorage().exceptionally(e -> "").thenAccept(key ->
                SwingUtilities.invokeLater(() -> {
                    if (restoreAiSession(AI_SESSION_ANALYSIS)) {
                        PluginLog.info("[WatchlistPanel] analysis session restored");
                        return;
                    }
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
                    AiChatPanel session = openAiSession(
                            AI_SESSION_ANALYSIS, "AI复盘", AIService.ANALYSIS_SYSTEM_PROMPT);
                    PluginLog.info("[WatchlistPanel] analysis session built, calling runAiAutoAnalysis");
                    runAiAutoAnalysis(marketName, session);
                }));
    }

    /** 最近要闻速览入口：把最新一批快讯注入 system prompt，复用 AI 会话生成汇总，可继续追问。
     *  清单不进入用户气泡，避免 30 条快讯刷屏；Key 就绪后才决定是否需要弹设置框。 */
    public void openNewsSummary(List<KuaixunItem> items) {
        if (aiSessionHost == null) {
            return;
        }
        if (items == null || items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "快讯尚未加载完成，请稍后重试", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (restoreAiSession(AI_SESSION_SUMMARY)) {
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
                    if (currentApiKey.isEmpty()) {
                        showAiSettings();
                        if (currentApiKey.isEmpty()) {
                            return;
                        }
                    }
                    if (restoreAiSession(AI_SESSION_SUMMARY)) {
                        return;
                    }
                    AiChatPanel session = openAiSession(AI_SESSION_SUMMARY, "AI速览", systemPrompt);
                    // 计时文案显示真实汇总条数，别再写原始列表长度（之前会虚报成 50 条）
                    int newsCount = Math.min(items.size(), AIService.SUMMARY_MAX_ITEMS);
                    session.startLoading("正在汇总 " + newsCount + " 条快讯...");
                    // 指令只发给模型，不展示成用户气泡（与自选分析一致）
                    session.sendSilent(firstMessage);
                }));
    }

    /** 快讯 AI 解读入口：后台补齐自选股名称后，在 AI 会话里发起解读，可继续追问 */
    public void openNewsInterpretation(String newsTitle, String newsDigest) {
        PluginLog.info("[WatchlistPanel] openNewsInterpretation: " + newsTitle);
        // 与自选分析同样等 Key 就绪，避免第一次点击误判为空
        refreshAiConfigFromStorage().exceptionally(e -> "").thenAccept(key ->
                SwingUtilities.invokeLater(() -> openNewsInterpretationReady(
                        newsTitle, newsDigest)));
    }

    private void openNewsInterpretationReady(String newsTitle, String newsDigest) {
        if (aiSessionHost == null) {
            return;
        }
        if (currentApiKey.isEmpty()) {
            showAiSettings();
            if (currentApiKey.isEmpty()) {
                return;
            }
        }
        final String title = newsTitle == null ? "" : newsTitle;
        if (!title.equals(interpretationTitle)) {
            // 换了一条新闻：上一条的 history 与气泡不能复用，直接丢弃旧会话
            aiSessionHost.closeAiSession(AI_SESSION_INTERPRETATION);
            interpretationTitle = title;
        } else if (restoreAiSession(AI_SESSION_INTERPRETATION)) {
            return;
        }
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
                // 取行情期间用户可能又点了另一条快讯，这条已过期的解读不能再抢占页签
                if (!title.equals(interpretationTitle)) {
                    return;
                }
                if (restoreAiSession(AI_SESSION_INTERPRETATION)) {
                    return;
                }
                AiChatPanel session = openAiSession(
                        AI_SESSION_INTERPRETATION, "AI 快讯解读", systemPrompt);
                session.send(firstMessage);
            });
        }).start();
    }

    /**
     * 后台获取行情数据并触发 AI 自动分析
     */
    private void runAiAutoAnalysis(String marketName, AiChatPanel session) {
        // 首轮分析也占用发送按钮：期间变为「停止」，同时避免用户并发发起追问
        session.beginAutoRequest();
        // 拉行情阶段先给反馈，拿到数据后再切换成按只数计的等待文案
        session.startLoading("正在获取行情数据...");

        new Thread(() -> {
            PluginLog.info("[WatchlistPanel] runAiAutoAnalysis thread started, market=" + marketName);
            List<QuoteItem> items = getFilteredItems();
            PluginLog.info("[WatchlistPanel] getFilteredItems returned " + (items != null ? items.size() : 0) + " items");
            if (items.isEmpty()) {
                PluginLog.info("[WatchlistPanel] runAiAutoAnalysis FAIL: no items, hiding loadingPanel");
                session.notice("暂无行情数据，请稍后重试...");
                return;
            }

            final List<QuoteItem> finalItems = items;
            final String marketContext = AIService.buildMarketContext(items, marketName);
            session.addHistory(marketContext);

            // 等待期间给出耗时反馈；loadingPanel 隐藏后计时的下一次 tick 自停
            session.startLoading("正在分析 " + items.size() + " 只自选股...");

            String key = currentApiKey;
            AIService.AiProfile active = AIService.findProfile(currentProfileId);
            PluginLog.info("[WatchlistPanel] using apiKey_len=" + (key != null ? key.length() : 0)
                    + ", profile=" + active.displayName() + ", model=" + active.model);
            try {
                // 不再叠加全局 orTimeout：callAPI 已有读取超时（流式 120s / 整包 180s），
                // 同长的外层超时会在响应到达前抢先判定失败，且不会取消底层请求，导致已生成的结果被丢弃
                AIService svc = createAiService(key);
                session.attachCancel(svc::cancel);
                svc.analyzeWatchlistAsync(finalItems, marketName, session::onStreamChunk)
                    .thenAccept(result -> {
                        PluginLog.info("[WatchlistPanel] analyzeWatchlistAsync SUCCESS, result_len=" +
                            (result != null ? result.length() : 0));
                        session.completeRequest(result);
                    }).exceptionally(e -> {
                        PluginLog.error("[WatchlistPanel] analyzeWatchlistAsync FAILED: " + e.getClass().getName() + " - " + e.getMessage(), e);
                        Throwable cause = (e instanceof java.util.concurrent.CompletionException) ? e.getCause() : e;
                        String msg = cause != null ? cause.getMessage() : e.getMessage();
                        String failureText = (msg == null || msg.isEmpty())
                                ? "AI 分析失败，请稍后重试" : "AI 分析失败: " + msg;
                        // failRequest 会补一条 assistant 占位：chatAsync 按序号定角色，
                        // 少一条回答会让下一条追问被当成 assistant 消息发出去
                        session.failRequest(failureText);
                        return null;
                    });
            } catch (Exception e) {
                session.failRequest("服务初始化失败: " + e.getMessage());
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

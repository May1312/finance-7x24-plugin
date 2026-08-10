package com.plugin.finance;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private enum MarketFilter {
        A("A股"),
        HK("港股"),
        US("美股"),
        GOLD("黄金");

        private final String displayName;

        MarketFilter(String displayName) { this.displayName = displayName; }

        public String getDisplayName() { return displayName; }
    }

    private final PropertiesComponent properties;
    private final QuoteService quoteService = new QuoteService();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

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

    private static final int REFRESH_INTERVAL_MS = 5000;
    private static final int COUNTDOWN_STEP_MS = 1000;

    public WatchlistPanel(Project project) {
        properties = PropertiesComponent.getInstance(project);

        setLayout(new BorderLayout());

        initTopPanel();
        initTable();
        initBottomPanel();
        restoreSortState();
        restoreFilter();

        // 统一用一个1秒tick的timer：倒计时+到0触发刷新
        autoRefreshTimer = new Timer(COUNTDOWN_STEP_MS, e -> tickAndAutoRefresh());
    }

    private void tickAndAutoRefresh() {
        // 只有 A 股自动刷新；港股/美股/黄金手动刷新
        if (currentFilter != MarketFilter.A) {
            autoRefreshTimer.stop();
            return;
        }
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

    /** 只更新状态栏右侧的倒计时文字 */
    private void updateCountdownDisplay() {
        String text = statusLabel.getText();
        if (text.contains("下次刷新")) {
            String prefix = text.substring(0, text.indexOf("下次刷新"));
            statusLabel.setText(prefix + "下次刷新" + countdownSeconds + "秒");
        }
    }

    /** 设置状态栏（含倒计时后缀），刷新中时不显示倒计时 */
    private void setStatus(String baseText) {
        if (baseText.equals("刷新中...") || currentFilter != MarketFilter.A) {
            statusLabel.setText(baseText);
        } else {
            statusLabel.setText(baseText + "    下次刷新" + countdownSeconds + "秒");
        }
    }

    private void initTopPanel() {
        JButton addButton = new JButton(IconLoader.getIcon("/icons/add.svg", WatchlistPanel.class));
        addButton.setToolTipText("添加自选股");
        addButton.setPreferredSize(JBUI.size(30, 28));
        addButton.addActionListener(e -> showAddDialog());

        JButton refreshButton = new JButton(IconLoader.getIcon("/icons/refresh.svg", WatchlistPanel.class));
        refreshButton.setToolTipText("刷新行情");
        refreshButton.setPreferredSize(JBUI.size(30, 28));
        refreshButton.addActionListener(e -> refreshQuotes(true));

        JLabel titleLabel = new JLabel("自选行情");
        titleLabel.setFont(JBFont.label().asBold());

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightPanel.add(addButton);
        rightPanel.add(refreshButton);

        JPanel actionRow = new JPanel(new BorderLayout(8, 0));
        actionRow.add(titleLabel, BorderLayout.WEST);
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
        topPanel.add(statusLabel, BorderLayout.SOUTH);

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
        if (filter == MarketFilter.A) {
            resumeTimer();
        } else {
            autoRefreshTimer.stop();
        }
        refreshQuotes(false);
    }

    /** 切换底部大盘指数下拉框到当前市场的指数列表，恢复该标签记住的选中项并刷新数据 */
    private void updateIndexComboForFilter() {
        String[] names = currentIndexNames();
        String saved = properties.getValue(indexKeyFor(currentFilter), "0");
        indexCombo.setModel(new DefaultComboBoxModel<>(names));
        try {
            int idx = Integer.parseInt(saved);
            if (idx >= 0 && idx < names.length) {
                indexCombo.setSelectedIndex(idx);
            }
        } catch (NumberFormatException ignored) {
        }
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
        } catch (NumberFormatException ignored) {
        }
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
        if (codes.isEmpty()) {
            tableModel.setRowCount(0);
            setStatus(parseCodes().isEmpty() ? "点击「添加」搜索股票" : "当前市场无自选股");
            refreshIndexData(force);
            return;
        }

        boolean marketClosed = !marketOpenFor(codes);
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
                    List<QuoteItem> items = quoteService.fetchQuotes(codes);
                    SwingUtilities.invokeLater(() -> {
                        updateTable(items);
                        if (marketClosed) {
                            statusLabel.setText("闭市中 · 上次行情 " + LocalTime.now().format(timeFormatter));
                        }
                        isRefreshing = false;
                        refreshIndexData(force);
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
                refreshIndexData(force);
            });
        }).start();
    }

    private void refreshIndexData(boolean force) {
        List<String> indexCodes = currentIndexCodes();
        if (indexCodes.isEmpty()) {
            return;
        }
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
        // 伦敦夏令时 → 北京时间 6:00 开盘/5:00 收盘
        // 伦敦冬令时 → 北京时间 7:00 开盘/6:00 收盘
        boolean dst = london.getRules().isDaylightSavings(now.toInstant());
        LocalTime open = dst ? LocalTime.of(6, 0) : LocalTime.of(7, 0);
        LocalTime close = dst ? LocalTime.of(5, 0) : LocalTime.of(6, 0);

        if (dow == DayOfWeek.SUNDAY) return false;
        if (dow == DayOfWeek.SATURDAY) {
            // 周六凌晨交易到 close 结束
            return t.isBefore(close);
        }
        if (dow == DayOfWeek.MONDAY) {
            // 周一 open 开盘，之前闭市
            return !t.isBefore(open);
        }
        // 周二~周五：open ~ 次日 close 交易，close ~ open 休市结算
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
        setStatus("最后更新 " + LocalTime.now().format(timeFormatter)
                + "    数据源：" + quoteService.getLastSource());
        countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
    }

    public void pauseTimer() {
        autoRefreshTimer.stop();
    }

    public void resumeTimer() {
        if (!autoRefreshTimer.isRunning()) {
            autoRefreshTimer.start();
        }
    }

    @Override
    public void removeNotify() {
        autoRefreshTimer.stop();
        super.removeNotify();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (!autoRefreshTimer.isRunning()) {
            autoRefreshTimer.start();
        }
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
            @Override
            public void mouseClicked(MouseEvent e) {
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

    private void saveSortState() {
        properties.setValue(SORT_COL_KEY, String.valueOf(sortedColumn));
        properties.setValue(SORT_STATE_KEY, String.valueOf(sortState));
    }

    private void restoreSortState() {
        String colStr = properties.getValue(SORT_COL_KEY, "-1");
        String stateStr = properties.getValue(SORT_STATE_KEY, "0");
        try {
            int col = Integer.parseInt(colStr);
            int state = Integer.parseInt(stateStr);
            if (col >= 0 && (state == 1 || state == 2)) {
                sortedColumn = col;
                sortState = state;
                SortOrder order = state == 1 ? SortOrder.DESCENDING : SortOrder.ASCENDING;
                tableSorter.setSortKeys(List.of(new RowSorter.SortKey(col, order)));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private String formatPrice(double value) {
        if (value == 0) return "-";
        return String.format("%.3f", value);
    }

    private String formatPercent(double value) {
        return String.format("%+.2f%%", value);
    }

    private double parseDisplayNumber(String value) {
        try {
            return Double.parseDouble(value.replace("%", "").replace("+", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String normalizeCode(String code) {
        return CodeUtils.normalize(code);
    }

    private List<String> parseCodes() {
        String raw = properties.getValue(CODES_KEY, "");
        if (raw.isEmpty()) return new ArrayList<>();
        String[] parts = raw.split("[,，\\s]+");
        Set<String> codes = new LinkedHashSet<>();
        for (String part : parts) {
            String normalized = CodeUtils.normalize(part);
            if (normalized != null) {
                codes.add(normalized);
            }
        }
        return new ArrayList<>(codes);
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

    private void saveCodes(List<String> codes) {
        properties.setValue(CODES_KEY, String.join(",", codes));
    }

    // --- Renderer ---

    private static class WatchlistCellRenderer extends DefaultTableCellRenderer {
        private static final Color RISE_COLOR = new JBColor(new Color(0xC62828), new Color(0xFF6B6B));
        private static final Color FALL_COLOR = new JBColor(new Color(0x2E7D32), new Color(0x69D98A));
        private static final double EPS = 1e-9;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(JBUI.Borders.empty(0, 6));
            setHorizontalAlignment(CENTER);

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
            try {
                return Double.parseDouble(String.valueOf(value).replace("%", "").replace("+", "").trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }
}

package com.plugin.finance;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WatchlistPanel extends JPanel {

    private static final String CODES_KEY = "eastmoney.7x24.watchlist.codes";
    private static final String SORT_COL_KEY = "eastmoney.7x24.watchlist.sortColumn";
    private static final String SORT_STATE_KEY = "eastmoney.7x24.watchlist.sortState";
    private static final String INDEX_KEY = "eastmoney.7x24.watchlist.index";
    private static final String[] COLUMN_NAMES = {"代码", "名称", "最新价", "涨跌幅", "涨跌额"};
    private static final List<String> INDEX_CODES = List.of("sh000001", "sz399001", "sz399006");
    private static final String[] INDEX_NAMES = {"上证指数", "深证成指", "创业板指"};

    private final PropertiesComponent properties;
    private final QuoteService quoteService = new QuoteService();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable quoteTable = new JTable(tableModel);
    private final TableRowSorter<DefaultTableModel> tableSorter = new TableRowSorter<>(tableModel);
    private final JLabel statusLabel = new JLabel("就绪");

    private final JComboBox<String> indexCombo = new JComboBox<>(INDEX_NAMES);
    private final JLabel indexValueLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel indexChangeLabel = new JLabel("", SwingConstants.CENTER);

    private final Timer autoRefreshTimer;
    private int sortedColumn = -1;
    private int sortState = 0;
    private List<QuoteItem> lastIndexData = new ArrayList<>();
    private long countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;

    private static final int REFRESH_INTERVAL_MS = 30000;
    private static final int COUNTDOWN_STEP_MS = 1000;

    public WatchlistPanel(Project project) {
        properties = PropertiesComponent.getInstance(project);

        setLayout(new BorderLayout());

        initTopPanel();
        initTable();
        initBottomPanel();
        restoreSortState();
        restoreIndexSelection();

        // 统一用一个1秒tick的timer：倒计时+到0触发刷新
        autoRefreshTimer = new Timer(COUNTDOWN_STEP_MS, e -> tickAndAutoRefresh());
        
        refreshQuotes(true);
        autoRefreshTimer.start();
    }

    private void tickAndAutoRefresh() {
        if (!isMarketOpen()) {
            autoRefreshTimer.stop();
            statusLabel.setText("闭市中");
            return;
        }
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

    /** 设置状态栏（含倒计时后缀） */
    private void setStatus(String baseText) {
        statusLabel.setText(baseText + "    下次刷新" + countdownSeconds + "秒");
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
        topPanel.add(statusLabel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
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
        indexCombo.setPreferredSize(JBUI.size(96, 22));
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
            properties.setValue(INDEX_KEY, String.valueOf(indexCombo.getSelectedIndex()));
            updateIndexDisplay();
        });
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
                    "输入股票或 ETF 代码，多个代码可用逗号或空格分隔；指数可输入 sh000001",
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
        codes.removeIf(c -> codesToRemove.stream().anyMatch(c::endsWith));
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
        List<String> codes = parseCodes();
        if (codes.isEmpty()) {
            tableModel.setRowCount(0);
            setStatus("点击「添加」搜索股票");
            refreshIndexData(force);
            return;
        }
        if (!isMarketOpen() && !force) {
            setStatus("闭市中，已暂停行情刷新");
            return;
        }
        if (!isMarketOpen()) {
            setStatus("闭市中，加载最近行情");
        } else {
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
                        refreshIndexData(force);
                    });
                    return;
                } catch (Exception e) {
                    lastException = e;
                    if (attempt < 3) {
                        SwingUtilities.invokeLater(() -> setStatus("行情加载中，正在重试..."));
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
                    setStatus("网络波动，显示上次行情");
                } else {
                    setStatus("行情加载失败，请稍后重试");
                }
                refreshIndexData(force);
            });
        }).start();
    }

    private void refreshIndexData(boolean force) {
        new Thread(() -> {
            try {
                lastIndexData = quoteService.fetchQuotes(INDEX_CODES);
                SwingUtilities.invokeLater(this::updateIndexDisplay);
            } catch (Exception ignored) {}
        }).start();
    }

    private boolean isMarketOpen() {
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now();
        boolean morning = !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(11, 30));
        boolean afternoon = !now.isBefore(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(15, 0));
        return morning || afternoon;
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

    private void restoreIndexSelection() {
        String idxStr = properties.getValue(INDEX_KEY, "0");
        try {
            int idx = Integer.parseInt(idxStr);
            if (idx >= 0 && idx < indexCombo.getItemCount()) {
                indexCombo.setSelectedIndex(idx);
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
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        if (c.matches("(sh|sz)?\\d{6}")) {
            if (!c.startsWith("sh") && !c.startsWith("sz")) {
                c = c.startsWith("5") || c.startsWith("6") || c.startsWith("9") ? "sh" + c : "sz" + c;
            }
            return c;
        }
        return null;
    }

    private List<String> parseCodes() {
        String raw = properties.getValue(CODES_KEY, "");
        if (raw.isEmpty()) return new ArrayList<>();
        String[] parts = raw.split("[,，\\s]+");
        Set<String> codes = new LinkedHashSet<>();
        for (String part : parts) {
            String c = part.trim().toLowerCase();
            if (c.matches("(sh|sz)?\\d{6}")) {
                codes.add(c);
            }
        }
        return new ArrayList<>(codes);
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

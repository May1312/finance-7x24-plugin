package com.ylplugin.eastmoney.kuaixun;

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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WatchlistPanel extends JPanel {
    private static final String CODES_KEY = "eastmoney.7x24.watchlist.codes";
    private final PropertiesComponent properties;
    private final QuoteService quoteService = new QuoteService();
    private final JTextField codesField = new JTextField();
    private final JLabel statusLabel = new JLabel("输入股票或 ETF 代码，多个代码用逗号分隔");
    private final DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"代码", "名称", "最新价", "涨跌幅", "涨跌额"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable quoteTable = new JTable(tableModel);
    private final TableRowSorter<DefaultTableModel> tableSorter = new TableRowSorter<>(tableModel);
    private final Timer refreshTimer;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private int sortedColumn = -1;
    private int sortState = 0;

    public WatchlistPanel(Project project) {
        properties = PropertiesComponent.getInstance(project);

        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(4));

        codesField.setText(properties.getValue(CODES_KEY, "510300,159915,600519"));
        JButton addButton = new JButton(IconLoader.getIcon("/icons/add.svg", WatchlistPanel.class));
        addButton.setToolTipText("添加股票或 ETF 代码");
        addButton.setPreferredSize(JBUI.size(30, 28));
        addButton.addActionListener(e -> showAddCodeDialog());

        JButton refreshButton = new JButton(IconLoader.getIcon("/icons/refresh.svg", WatchlistPanel.class));
        refreshButton.setToolTipText("刷新行情");
        refreshButton.setPreferredSize(JBUI.size(30, 28));
        refreshButton.addActionListener(e -> refreshQuotes());

        JLabel titleLabel = new JLabel("自选行情");
        titleLabel.setFont(JBFont.label().asBold());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.add(addButton);
        buttonPanel.add(refreshButton);

        JPanel actionPanel = new JPanel(new BorderLayout(8, 0));
        actionPanel.add(titleLabel, BorderLayout.CENTER);
        actionPanel.add(buttonPanel, BorderLayout.EAST);

        statusLabel.setFont(JBFont.small());
        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));

        JPanel topPanel = new JPanel(new BorderLayout(0, 6));
        topPanel.setBorder(JBUI.Borders.empty(6, 8, 10, 8));
        topPanel.add(actionPanel, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);

        quoteTable.setFillsViewportHeight(true);
        quoteTable.setRowHeight(JBUI.scale(32));
        quoteTable.setShowVerticalLines(false);
        quoteTable.setIntercellSpacing(JBUI.emptySize());
        quoteTable.setDefaultRenderer(Object.class, new QuoteTableRenderer());
        quoteTable.setRowSorter(tableSorter);
        setupTableSorting();
        centerTableHeader();
        installPopupMenu();

        add(topPanel, BorderLayout.NORTH);
        add(new JBScrollPane(quoteTable), BorderLayout.CENTER);

        refreshTimer = new Timer(30000, e -> refreshQuotes());
        refreshTimer.start();

        Timer initialRefreshTimer = new Timer(2000, e -> refreshQuotes());
        initialRefreshTimer.setRepeats(false);
        initialRefreshTimer.start();
    }

    @Override
    public void removeNotify() {
        refreshTimer.stop();
        super.removeNotify();
    }

    private void showAddCodeDialog() {
        String input = JOptionPane.showInputDialog(
                this,
                "输入股票或 ETF 代码，多个代码可用逗号或空格分隔；指数可输入 sh000001",
                "添加自选",
                JOptionPane.PLAIN_MESSAGE
        );
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        Set<String> codes = new LinkedHashSet<>(parseCodes());
        for (String code : parseCodes(input)) {
            codes.add(code);
        }
        if (codes.isEmpty()) {
            statusLabel.setText("请输入股票或 ETF 代码，例如 600519、510300、sh000001");
            return;
        }

        String joined = String.join(",", codes);
        codesField.setText(joined);
        properties.setValue(CODES_KEY, joined);
        statusLabel.setText("已添加自选，正在刷新...");
        refreshQuotes();
    }

    private void refreshQuotes() {
        refreshQuotes(3);
    }

    private void refreshQuotes(int retriesLeft) {
        List<String> codes = parseCodes();
        if (codes.isEmpty()) {
            tableModel.setRowCount(0);
            statusLabel.setText("请输入股票或 ETF 代码，例如 600519、510300、sh000001");
            return;
        }
        statusLabel.setText("刷新中...");
        new Thread(() -> {
            Exception lastException = null;
            for (int attempt = 0; attempt <= retriesLeft; attempt++) {
                try {
                    List<QuoteItem> items = quoteService.fetchQuotes(codes);
                    SwingUtilities.invokeLater(() -> updateTable(items));
                    return;
                } catch (Exception e) {
                    lastException = e;
                    if (attempt < retriesLeft) {
                        SwingUtilities.invokeLater(() -> statusLabel.setText("行情加载中，正在重试..."));
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            Exception failure = lastException;
            SwingUtilities.invokeLater(() -> handleQuoteFailure(failure));
        }).start();
    }

    private void handleQuoteFailure(Exception e) {
        if (tableModel.getRowCount() > 0) {
            statusLabel.setText("网络波动，显示上次行情");
        } else {
            statusLabel.setText("行情加载失败，请稍后重试");
        }
    }

    private List<String> parseCodes() {
        return parseCodes(codesField.getText());
    }

    private List<String> parseCodes(String text) {
        String[] parts = text.split("[,，\\s]+");
        Set<String> codes = new LinkedHashSet<>();
        for (String part : parts) {
            String code = part.trim().toLowerCase();
            if (code.matches("(sh|sz)?\\d{6}")) {
                codes.add(code);
            }
        }
        return new ArrayList<>(codes);
    }

    private void installPopupMenu() {
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("删除");
        deleteItem.addActionListener(e -> deleteSelectedCode());
        popupMenu.add(deleteItem);

        quoteTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = quoteTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    quoteTable.setRowSelectionInterval(row, row);
                    popupMenu.show(quoteTable, e.getX(), e.getY());
                }
            }
        });
    }

    private void centerTableHeader() {
        JTableHeader header = quoteTable.getTableHeader();
        DefaultTableCellRenderer renderer = (DefaultTableCellRenderer) header.getDefaultRenderer();
        renderer.setHorizontalAlignment(SwingConstants.CENTER);
    }

    private void setupTableSorting() {
        Comparator<String> numericComparator = Comparator.comparingDouble(this::parseDisplayNumber);
        tableSorter.setComparator(2, numericComparator);
        tableSorter.setComparator(3, numericComparator);
        tableSorter.setComparator(4, numericComparator);

        JTableHeader header = quoteTable.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewColumn = header.columnAtPoint(e.getPoint());
                if (viewColumn < 0) {
                    return;
                }
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
    }

    private void deleteSelectedCode() {
        int row = quoteTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = quoteTable.convertRowIndexToModel(row);
        String code = String.valueOf(tableModel.getValueAt(modelRow, 0));
        List<String> codes = parseCodes();
        codes.remove(code);
        String joined = String.join(",", codes);
        codesField.setText(joined);
        properties.setValue(CODES_KEY, joined);
        tableModel.removeRow(modelRow);
        statusLabel.setText("已删除 " + code);
        if (!codes.isEmpty()) {
            refreshQuotes();
        }
    }

    private void updateTable(List<QuoteItem> items) {
        tableModel.setRowCount(0);
        for (QuoteItem item : items) {
            tableModel.addRow(new Object[]{
                    item.getCode(),
                    item.getName(),
                    formatPrice(item.getPrice()),
                    formatPercent(item.getChangePercent()),
                    formatAmount(item.getChangeAmount())
            });
        }
        statusLabel.setText("最后更新 " + LocalTime.now().format(timeFormatter)
                + "    数据源：" + quoteService.getLastSource()
                + "    每 30 秒自动刷新");
    }

    private String formatPrice(double value) {
        return String.format("%.3f", value);
    }

    private String formatPercent(double value) {
        return String.format("%+.2f%%", value);
    }

    private String formatAmount(double value) {
        return String.format("%+.3f", value);
    }

    private double parseDisplayNumber(String value) {
        try {
            return Double.parseDouble(value.replace("%", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static class QuoteTableRenderer extends DefaultTableCellRenderer {
        private static final Color RISE_COLOR = new JBColor(new Color(0xC62828), new Color(0xFF6B6B));
        private static final Color FALL_COLOR = new JBColor(new Color(0x2E7D32), new Color(0x69D98A));

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(JBUI.Borders.empty(0, 8));
            setHorizontalAlignment(CENTER);

            if (!isSelected && column >= 3) {
                String text = String.valueOf(value);
                if (text.startsWith("+")) {
                    component.setForeground(RISE_COLOR);
                } else if (text.startsWith("-")) {
                    component.setForeground(FALL_COLOR);
                } else {
                    component.setForeground(table.getForeground());
                }
            }
            return component;
        }
    }
}

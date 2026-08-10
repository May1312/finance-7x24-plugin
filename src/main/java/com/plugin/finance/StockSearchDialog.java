package com.plugin.finance;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import static com.intellij.util.ui.JBUI.scale;

public class StockSearchDialog extends JDialog {

    private final StockSearchService searchService = new StockSearchService();
    private final DefaultListModel<StockSearchService.StockSearchItem> listModel = new DefaultListModel<>();
    private final JBList<StockSearchService.StockSearchItem> resultList = new JBList<>(listModel);
    private final JTextField searchField = new JTextField();
    private final List<StockSearchService.StockSearchItem> selectedItems = new ArrayList<>();
    private boolean confirmed = false;

    public StockSearchDialog(Window owner) {
        super(owner, "添加自选股", ModalityType.APPLICATION_MODAL);
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(480, 500));
        setResizable(true);

        JPanel searchPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        searchPanel.setBorder(JBUI.Borders.empty(12, 12, 8, 12));

        searchField.setFont(searchField.getFont().deriveFont(14f));
        searchField.putClientProperty("JTextField.placeholderText", "输入股票名称或代码搜索...");

        JButton searchButton = new JButton("搜索");
        searchButton.setPreferredSize(JBUI.size(80, 32));
        searchButton.addActionListener(e -> doSearch());

        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);

        resultList.setCellRenderer(new SearchResultRenderer());
        resultList.setFixedCellHeight(JBUI.scale(52));
        resultList.setEmptyText("请输入关键字搜索");
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    addSelected();
                }
            }
        });

        JButton addButton = new JButton("添加选中");
        addButton.addActionListener(e -> addSelected());

        JButton cancelButton = new JButton("取消");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        buttonPanel.add(addButton);
        buttonPanel.add(cancelButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(JBUI.Borders.empty(8, 12, 12, 12));
        JLabel hintLabel = new JLabel("双击或选中后点击「添加选中」，支持多选");
        hintLabel.setFont(hintLabel.getFont().deriveFont(11f));
        hintLabel.setForeground(UIManager.getColor("Label.infoForeground"));
        bottomPanel.add(hintLabel, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { scheduleSearch(); }
            @Override
            public void removeUpdate(DocumentEvent e) { scheduleSearch(); }
            @Override
            public void changedUpdate(DocumentEvent e) { scheduleSearch(); }
        });

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    doSearch();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN && resultList.getModel().getSize() > 0) {
                    resultList.requestFocus();
                    resultList.setSelectedIndex(0);
                }
            }
        });

        resultList.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        add(searchPanel, BorderLayout.NORTH);
        add(new JBScrollPane(resultList), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(getOwner());
    }

    private Timer searchTimer;
    private void scheduleSearch() {
        if (searchTimer != null && searchTimer.isRunning()) {
            searchTimer.stop();
        }
        searchTimer = new Timer(300, e -> doSearch());
        searchTimer.setRepeats(false);
        searchTimer.start();
    }

    private void doSearch() {
        String keyword = searchField.getText().trim();
        if (keyword.isEmpty()) {
            listModel.clear();
            resultList.setEmptyText("请输入关键字搜索");
            return;
        }
        resultList.setEmptyText("搜索中...");
        new Thread(() -> {
            List<StockSearchService.StockSearchItem> results = searchService.search(keyword);
            SwingUtilities.invokeLater(() -> {
                listModel.clear();
                if (results.isEmpty()) {
                    resultList.setEmptyText("未找到匹配结果");
                } else {
                    for (StockSearchService.StockSearchItem item : results) {
                        listModel.addElement(item);
                    }
                }
            });
        }).start();
    }

    private void addSelected() {
        selectedItems.clear();
        List<StockSearchService.StockSearchItem> selected = resultList.getSelectedValuesList();
        if (selected != null && !selected.isEmpty()) {
            selectedItems.addAll(selected);
        } else if (listModel.getSize() > 0) {
            selectedItems.add(listModel.firstElement());
        }
        if (!selectedItems.isEmpty()) {
            confirmed = true;
            dispose();
        }
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<String> getSelectedCodes() {
        return selectedItems.stream()
                .map(StockSearchService.StockSearchItem::getFullCode)
                .collect(Collectors.toList());
    }

    public List<StockSearchService.StockSearchItem> getSelectedItems() {
        return selectedItems;
    }

    private static class SearchResultRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            JPanel panel = new JPanel(new BorderLayout(JBUI.scale(12), 0));
            panel.setBorder(JBUI.Borders.empty(6, 12));
            panel.setOpaque(true);

            if (isSelected) {
                panel.setBackground(list.getSelectionBackground());
            } else {
                panel.setBackground(list.getBackground());
            }

            if (value instanceof StockSearchService.StockSearchItem) {
                StockSearchService.StockSearchItem item = (StockSearchService.StockSearchItem) value;

                JLabel nameLabel = new JLabel(item.getName());
                nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));

                JLabel marketBadge = new JLabel(" " + marketLabel(item.getMarket()) + " ");
                marketBadge.setFont(marketBadge.getFont().deriveFont(Font.BOLD, 9f));
                marketBadge.setForeground(Color.WHITE);
                marketBadge.setOpaque(true);
                marketBadge.setBackground(marketColor(item.getMarket()));

                JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, scale(6), 0));
                topRow.setOpaque(false);
                topRow.add(nameLabel);
                topRow.add(marketBadge);

                JLabel codeLabel = new JLabel(item.getFullCode());
                codeLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                codeLabel.setForeground(JBColor.namedColor("Label.infoForeground", new Color(0x90A4AE)));

                JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.LEFT, scale(6), 0));
                bottomRow.setOpaque(false);
                bottomRow.add(codeLabel);
                if (!item.getTypeLabel().isEmpty()) {
                    JLabel typeBadge = new JLabel(" " + item.getTypeLabel() + " ");
                    typeBadge.setFont(typeBadge.getFont().deriveFont(Font.BOLD, 9f));
                    typeBadge.setForeground(Color.WHITE);
                    typeBadge.setOpaque(true);
                    typeBadge.setBackground(new Color(0x2E7D32));
                    bottomRow.add(typeBadge);
                }

                JPanel leftPanel = new JPanel();
                leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
                leftPanel.setOpaque(false);
                topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                leftPanel.add(topRow);
                leftPanel.add(bottomRow);

                panel.add(leftPanel, BorderLayout.CENTER);
            }

            return panel;
        }

        private String marketLabel(String market) {
            if ("US".equalsIgnoreCase(market)) return "美股";
            if ("HK".equalsIgnoreCase(market)) return "港股";
            if ("GOLD".equalsIgnoreCase(market)) return "黄金";
            return "SH".equalsIgnoreCase(market) ? "沪" : "深";
        }

        private Color marketColor(String market) {
            if ("US".equalsIgnoreCase(market)) return new Color(0x1565C0);
            if ("HK".equalsIgnoreCase(market)) return new Color(0x2E7D32);
            if ("GOLD".equalsIgnoreCase(market)) return new Color(0xB8860B);
            return "SH".equalsIgnoreCase(market) ? new Color(0xC62828) : new Color(0xE65100);
        }
    }
}
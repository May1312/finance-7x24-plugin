package com.ylplugin.eastmoney.kuaixun;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class KuaixunPanel extends JPanel {
    private final Project project;
    private final DefaultListModel<KuaixunItem> listModel = new DefaultListModel<>();
    private final JBList<KuaixunItem> newsList = new JBList<>(listModel);
    private final KuaixunService service = new KuaixunService();
    private final JLabel statusLabel = new JLabel("正在加载东方财富7x24快讯...");
    private final Timer refreshTimer;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private int currentPage = 1;

    public KuaixunPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(4));

        JPanel newsPanel = new JPanel(new BorderLayout());

        newsList.setCellRenderer(new KuaixunCellRenderer());
        newsList.setFixedCellHeight(-1);
        newsList.setVisibleRowCount(20);
        newsList.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton refreshBtn = new JButton("刷新");
        refreshBtn.addActionListener(e -> refreshData());

        JLabel titleLabel = new JLabel("东方财富 7x24");
        titleLabel.setFont(JBFont.label().asBold());
        statusLabel.setFont(JBFont.small());
        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(titleLabel, BorderLayout.NORTH);
        titlePanel.add(statusLabel, BorderLayout.SOUTH);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(JBUI.Borders.empty(2, 4, 8, 4));
        topPanel.add(titlePanel, BorderLayout.CENTER);
        topPanel.add(refreshBtn, BorderLayout.EAST);

        newsPanel.add(topPanel, BorderLayout.NORTH);
        newsPanel.add(new JBScrollPane(newsList), BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("快讯", newsPanel);
        tabs.addTab("自选", new WatchlistPanel(project));
        add(tabs, BorderLayout.CENTER);

        refreshData();
        refreshTimer = new Timer(30000, e -> checkNewData());
        refreshTimer.start();
    }

    @Override
    public void removeNotify() {
        refreshTimer.stop();
        super.removeNotify();
    }

    private void refreshData() {
        currentPage = 1;
        listModel.clear();
        setStatus("刷新中...");
        loadPage(currentPage);
    }

    private void loadPage(int page) {
        new Thread(() -> {
            try {
                List<KuaixunItem> items = service.fetchPage(page);
                SwingUtilities.invokeLater(() -> {
                    for (KuaixunItem item : items) {
                        listModel.addElement(item);
                    }
                    setStatus("最后更新 " + LocalTime.now().format(timeFormatter) + "    每 30 秒自动刷新");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatus("加载失败，请稍后重试"));
            }
        }).start();
    }

    private void checkNewData() {
        if (listModel.isEmpty()) return;
        String latestId = listModel.firstElement().getId();
        new Thread(() -> {
            try {
                int count = service.checkNewCount(latestId);
                if (count > 0) {
                    List<KuaixunItem> newItems = service.fetchPage(1);
                    SwingUtilities.invokeLater(() -> {
                        for (int i = newItems.size() - 1; i >= 0; i--) {
                            KuaixunItem item = newItems.get(i);
                            if (listModel.size() == 0 || !listModel.firstElement().getId().equals(item.getId())) {
                                listModel.add(0, item);
                            }
                        }
                        setStatus("最后更新 " + LocalTime.now().format(timeFormatter) + "    每 30 秒自动刷新");
                    });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static class KuaixunCellRenderer extends JPanel implements ListCellRenderer<KuaixunItem> {
        private final JLabel timeLabel = new JLabel();
        private final JTextArea contentArea = new JTextArea();

        private KuaixunCellRenderer() {
            super(new BorderLayout(0, 4));
            setOpaque(true);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                    JBUI.Borders.empty(10, 12)
            ));

            timeLabel.setFont(JBFont.small());
            contentArea.setFont(JBFont.label());
            contentArea.setOpaque(false);
            contentArea.setEditable(false);
            contentArea.setFocusable(false);
            contentArea.setLineWrap(true);
            contentArea.setWrapStyleWord(true);

            add(timeLabel, BorderLayout.NORTH);
            add(contentArea, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends KuaixunItem> list, KuaixunItem item, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            timeLabel.setText(item.getDisplayTimeText());
            contentArea.setText(item.getContentText());

            Color background = isSelected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
            setBackground(background);
            timeLabel.setForeground(isSelected ? foreground : UIManager.getColor("Label.infoForeground"));
            contentArea.setForeground(foreground);
            contentArea.setBackground(background);

            int width = list.getWidth() - JBUI.scale(32);
            if (width > 0) {
                contentArea.setSize(width, Short.MAX_VALUE);
            }

            return this;
        }
    }
}

package com.plugin.finance;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.ScrollPaneConstants;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class KuaixunPanel extends JPanel {
    private final Project project;
    private final DefaultListModel<KuaixunItem> listModel = new DefaultListModel<>();
    private final JBList<KuaixunItem> newsList = new JBList<>(listModel);
    private final KuaixunService service = new KuaixunService();
    private final JLabel statusLabel = new JLabel("正在加载 7x24 快讯...");
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

        JButton refreshBtn = new JButton(IconLoader.getIcon("/icons/refresh.svg", KuaixunPanel.class));
        refreshBtn.setToolTipText("刷新");
        refreshBtn.setPreferredSize(JBUI.size(30, 28));
        refreshBtn.addActionListener(e -> refreshData());

        JLabel titleLabel = new JLabel("财经 7x24");
        titleLabel.setFont(JBFont.label().asBold());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.add(refreshBtn);

        JPanel actionPanel = new JPanel(new BorderLayout(8, 0));
        actionPanel.add(titleLabel, BorderLayout.CENTER);
        actionPanel.add(buttonPanel, BorderLayout.EAST);

        statusLabel.setFont(JBFont.small());
        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));

        JPanel topPanel = new JPanel(new BorderLayout(0, 6));
        topPanel.setBorder(JBUI.Borders.empty(6, 8, 10, 8));
        topPanel.add(actionPanel, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);

        newsPanel.add(topPanel, BorderLayout.NORTH);
        JBScrollPane newsScrollPane = new JBScrollPane(newsList);
        newsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        newsPanel.add(newsScrollPane, BorderLayout.CENTER);

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
        private static final Color TITLE_COLOR = new JBColor(new Color(0x1677FF), new Color(0x6DA8FF));
        private final JLabel timeLabel = new JLabel();
        private final JTextArea titleArea = new JTextArea();
        private final JTextArea digestArea = new JTextArea();

        private KuaixunCellRenderer() {
            super(new BorderLayout(0, 6));
            setOpaque(true);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                    JBUI.Borders.empty(10, 12)
            ));

            timeLabel.setFont(JBFont.small());

            titleArea.setFont(JBFont.label().asBold().deriveFont(JBFont.label().getSize() + 2.0f));
            titleArea.setOpaque(false);
            titleArea.setEditable(false);
            titleArea.setFocusable(false);
            titleArea.setLineWrap(true);
            titleArea.setWrapStyleWord(true);

            digestArea.setFont(JBFont.label());
            digestArea.setOpaque(false);
            digestArea.setEditable(false);
            digestArea.setFocusable(false);
            digestArea.setLineWrap(true);
            digestArea.setWrapStyleWord(true);

            JPanel contentPanel = new JPanel(new BorderLayout(0, 4));
            contentPanel.setOpaque(false);
            contentPanel.add(titleArea, BorderLayout.NORTH);
            contentPanel.add(digestArea, BorderLayout.CENTER);

            add(timeLabel, BorderLayout.NORTH);
            add(contentPanel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends KuaixunItem> list, KuaixunItem item, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            timeLabel.setText(item.getDisplayTimeText());

            String title = item.getTitle();
            String digest = item.getDigest();
            boolean hasDigest = digest != null && !digest.isEmpty()
                    && !digest.equals(title)
                    && !digest.replaceAll("[【】。，！？,.;:！？、…·\\s]", "").equals(title.replaceAll("[【】。，！？,.;:！？、…·\\s]", ""));

            titleArea.setText(title != null ? title : "");
            if (hasDigest) {
                digestArea.setText(digest);
                digestArea.setVisible(true);
            } else {
                digestArea.setVisible(false);
            }

            Color bg = list.getBackground();
            Color fg = list.getForeground();
            setBackground(bg);
            timeLabel.setForeground(UIManager.getColor("Label.infoForeground"));
            titleArea.setForeground(TITLE_COLOR);
            digestArea.setForeground(fg);
            titleArea.setBackground(bg);
            digestArea.setBackground(bg);

            int width = list.getWidth() - JBUI.scale(32) - JBUI.scale(16);
            if (width > JBUI.scale(10)) {
                titleArea.setSize(width, Short.MAX_VALUE);
                Dimension ts = titleArea.getPreferredSize();
                ts.width = width;
                titleArea.setPreferredSize(ts);

                if (hasDigest) {
                    digestArea.setSize(width, Short.MAX_VALUE);
                    Dimension ds = digestArea.getPreferredSize();
                    ds.width = width;
                    digestArea.setPreferredSize(ds);
                }
            }

            return this;
        }
    }
}

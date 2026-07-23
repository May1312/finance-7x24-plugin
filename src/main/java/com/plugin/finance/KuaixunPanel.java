package com.plugin.finance;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
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
    private final JPanel cardsPanel = new JPanel();
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

        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        cardsPanel.setBackground(new JBColor(new Color(0xF2F3F5), new Color(0x2B2D30)));

        JButton refreshBtn = new JButton(IconLoader.getIcon("/icons/refresh.svg", KuaixunPanel.class));
        refreshBtn.setToolTipText("刷新");
        refreshBtn.setPreferredSize(JBUI.size(30, 28));
        refreshBtn.addActionListener(e -> refreshData());

        JLabel titleLabel = new JLabel("财经 7x24");
        titleLabel.setFont(JBFont.label().asBold());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(refreshBtn);

        JPanel actionPanel = new JPanel(new BorderLayout(8, 0));
        actionPanel.setOpaque(false);
        actionPanel.add(titleLabel, BorderLayout.CENTER);
        actionPanel.add(buttonPanel, BorderLayout.EAST);

        statusLabel.setFont(JBFont.small());
        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));

        JPanel topPanel = new JPanel(new BorderLayout(0, 6));
        topPanel.setBorder(JBUI.Borders.empty(6, 8, 10, 8));
        topPanel.setOpaque(false);
        topPanel.add(actionPanel, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);

        newsPanel.add(topPanel, BorderLayout.NORTH);
        JBScrollPane newsScrollPane = new JBScrollPane(cardsPanel);
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
        cardsPanel.removeAll();
        setStatus("刷新中...");
        loadPage(currentPage);
    }

    private void loadPage(int page) {
        new Thread(() -> {
            try {
                List<KuaixunItem> items = service.fetchPage(page);
                SwingUtilities.invokeLater(() -> {
                    for (KuaixunItem item : items) {
                        cardsPanel.add(createCard(item));
                        cardsPanel.add(Box.createVerticalStrut(JBUI.scale(4)));
                    }
                    setStatus("最后更新 " + LocalTime.now().format(timeFormatter) + "    每 30 秒自动刷新");
                    cardsPanel.revalidate();
                    cardsPanel.repaint();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatus("加载失败，请稍后重试"));
            }
        }).start();
    }

    private void checkNewData() {
        if (cardsPanel.getComponentCount() == 0) return;
        // 从第一个 card 取 id
        Component first = cardsPanel.getComponent(0);
        if (!(first instanceof NewsCard)) return;
        String latestId = ((NewsCard) first).getItemId();
        new Thread(() -> {
            try {
                int count = service.checkNewCount(latestId);
                if (count > 0) {
                    List<KuaixunItem> newItems = service.fetchPage(1);
                    SwingUtilities.invokeLater(() -> {
                        int insertIndex = 0;
                        for (int i = newItems.size() - 1; i >= 0; i--) {
                            KuaixunItem item = newItems.get(i);
                            if (!item.getId().equals(latestId)) {
                                cardsPanel.add(createCard(item), insertIndex);
                                cardsPanel.add(Box.createVerticalStrut(JBUI.scale(4)), insertIndex);
                            }
                        }
                        setStatus("最后更新 " + LocalTime.now().format(timeFormatter) + "    每 30 秒自动刷新");
                        cardsPanel.revalidate();
                        cardsPanel.repaint();
                    });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private JPanel createCard(KuaixunItem item) {
        return new NewsCard(item);
    }

    /**
     * 自定义卡片组件，使用 JTextArea + BorderLayout，
     * 文本自动根据父容器宽度换行。
     */
    private static class NewsCard extends JPanel {
        private static final Color CARD_BG = new JBColor(new Color(0xFAFAFA), new Color(0x333639));
        private static final Color CARD_HOVER = new JBColor(new Color(0xF0F2F5), new Color(0x3C3F42));
        private static final Color CARD_BORDER = new JBColor(new Color(0xE8E8E8), new Color(0x444749));
        private static final Color TITLE_COLOR = new JBColor(new Color(0x1D2129), new Color(0xEAEAEA));
        private static final Color DIGEST_COLOR = new JBColor(new Color(0x86909C), new Color(0x8B8F94));
        private static final Color TIME_BG = new JBColor(
                new Color(0x1D, 0x21, 0x29, 35),
                new Color(0xFF, 0xFF, 0xFF, 30));
        private static final Color TIME_FG = new JBColor(
                new Color(0x86909C),
                new Color(0xC9CDD4));

        private final String itemId;
        private boolean hovered = false;

        private final String timeText;

        private NewsCard(KuaixunItem item) {
            this.itemId = item.getId();
            this.timeText = item.getDisplayTimeText();
            setOpaque(false);
            setLayout(new BorderLayout());
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));

            String title = item.getTitle() != null ? item.getTitle() : "";
            String digest = item.getDigest();
            boolean hasDigest = digest != null && !digest.isEmpty() && !digest.equals(title);

            int cardPad = JBUI.scale(14);
            int topPad = JBUI.scale(30); // space for time pill
            int bottomPad = JBUI.scale(8);

            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(topPad, cardPad, bottomPad, cardPad));

            JTextArea titleArea = new JTextArea(title);
            titleArea.setFont(JBFont.label().asBold().deriveFont(JBFont.label().getSize() + 1.0f));
            titleArea.setForeground(TITLE_COLOR);
            titleArea.setOpaque(false);
            titleArea.setEditable(false);
            titleArea.setFocusable(false);
            titleArea.setLineWrap(true);
            titleArea.setWrapStyleWord(true);
            titleArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            // 让 JTextArea 根据容器宽度自动计算高度
            titleArea.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));
            titleArea.setBorder(null);
            content.add(titleArea);

            if (hasDigest) {
                JTextArea digestArea = new JTextArea(digest);
                digestArea.setFont(JBFont.label().deriveFont(Font.PLAIN));
                digestArea.setForeground(DIGEST_COLOR);
                digestArea.setOpaque(false);
                digestArea.setEditable(false);
                digestArea.setFocusable(false);
                digestArea.setLineWrap(true);
                digestArea.setWrapStyleWord(true);
                digestArea.setAlignmentX(Component.LEFT_ALIGNMENT);
                digestArea.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));
                digestArea.setBorder(JBUI.Borders.emptyTop(6));
                content.add(digestArea);
            }

            add(content, BorderLayout.CENTER);

            // 外层 border 制造卡片间距
            setBorder(BorderFactory.createEmptyBorder(
                    JBUI.scale(2), JBUI.scale(2), JBUI.scale(2), JBUI.scale(2)));

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                @Override
                public void mouseExited(java.awt.event.MouseEvent e) { hovered = false; repaint(); }
            });
        }

        String getItemId() { return itemId; }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int gap = JBUI.scale(2);
            int arc = JBUI.scale(8);
            int x = gap, y = gap;
            int w = getWidth() - gap * 2, h = getHeight() - gap * 2;

            g2.setColor(hovered ? CARD_HOVER : CARD_BG);
            g2.fillRoundRect(x, y, w, h, arc, arc);

            g2.setColor(CARD_BORDER);
            g2.setStroke(new BasicStroke(JBUI.scale(1f)));
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);

            // time pill
            if (timeText != null && !timeText.isEmpty()) {
                Font pillFont = JBFont.small().deriveFont(Font.PLAIN, JBUI.scale(11f));
                g2.setFont(pillFont);
                FontMetrics fm = g2.getFontMetrics();
                int pillH = fm.getHeight() + JBUI.scale(4);
                int pillW = fm.stringWidth(timeText) + JBUI.scale(12);
                int pillX = x + JBUI.scale(12);
                int pillY = y + JBUI.scale(10);
                int pillArc = JBUI.scale(4);

                g2.setColor(TIME_BG);
                g2.fillRoundRect(pillX, pillY, pillW, pillH, pillArc, pillArc);
                g2.setColor(TIME_FG);
                g2.drawString(timeText, pillX + JBUI.scale(6), pillY + fm.getAscent() + JBUI.scale(2));
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }
}

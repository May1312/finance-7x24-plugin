package com.plugin.finance;

import com.intellij.openapi.project.Project;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class KuaixunPanel extends JPanel {
    private final Project project;
    private final JPanel cardsPanel = new JPanel();
    private final KuaixunService service = new KuaixunService();
    private final JBScrollPane newsScrollPane = new JBScrollPane();
    private final JLabel statusLabel = new JLabel("正在加载 7x24 快讯...");
    private final Timer autoRefreshTimer;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private int currentPage = 1;
    private final Set<String> knownIds = ConcurrentHashMap.newKeySet();
    private long countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
    private boolean isRefreshing = false;

    private static final int REFRESH_INTERVAL_MS = 20000;
    private static final int COUNTDOWN_STEP_MS = 1000;

    public KuaixunPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(4));

        JPanel newsPanel = new JPanel(new BorderLayout());

        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        cardsPanel.setBackground(new JBColor(new Color(0xF2F3F5), new Color(0x2B2D30)));

        JButton refreshBtn = new JButton("刷新");
        refreshBtn.setToolTipText("刷新快讯");
        refreshBtn.setPreferredSize(new Dimension(50, 28));
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
        this.newsScrollPane.setViewportView(cardsPanel);
        this.newsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        newsPanel.add(this.newsScrollPane, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("快讯", newsPanel);
        WatchlistPanel watchlistPanel = new WatchlistPanel(project);
        tabs.addTab("自选", watchlistPanel);
        add(tabs, BorderLayout.CENTER);

        // 统一用一个1秒tick的timer：倒计时+到0触发刷新，避免两个timer不同步
        autoRefreshTimer = new Timer(COUNTDOWN_STEP_MS, e -> tickAndAutoRefresh());

        // 切换tab时控制对应面板的timer：只激活当前tab的计时
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 0) {
                // 切到快讯
                if (!autoRefreshTimer.isRunning()) {
                    autoRefreshTimer.start();
                }
                watchlistPanel.pauseTimer();
            } else {
                // 切到自选
                autoRefreshTimer.stop();
                watchlistPanel.resumeTimer();
            }
        });
        
        autoRefreshTimer.start();
        // 初始在快讯tab，暂停自选的timer
        watchlistPanel.pauseTimer();
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
        refreshData();
    }

    /** 每秒执行一次：倒计时减1，归零时触发自动刷新 */
    private void tickAndAutoRefresh() {
        if (isRefreshing) return;
        countdownSeconds--;
        if (countdownSeconds <= 0) {
            // 倒计时归零 → 触发自动刷新 + 重置倒计时
            countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
            doCheckNewData();
        }
        updateCountdownDisplay();
    }

    /** 只更新状态栏右侧的倒计时文字，不影响左侧的时间戳前缀 */
    private void updateCountdownDisplay() {
        String text = statusLabel.getText();
        if (text.contains("下次刷新")) {
            String prefix = text.substring(0, text.indexOf("下次刷新"));
            statusLabel.setText(prefix + "下次刷新" + countdownSeconds + "秒");
        } else {
            setStatus(text);
        }
    }

    /** 设置状态栏（含倒计时后缀），刷新中时不显示倒计时 */
    private void setStatus(String baseText) {
        if (baseText.equals("刷新中...")) {
            statusLabel.setText(baseText);
        } else {
            statusLabel.setText(baseText + "    下次刷新" + countdownSeconds + "秒");
        }
    }

    private void refreshData() {
        isRefreshing = true;
        knownIds.clear();
        countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
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
                    for (int i = items.size() - 1; i >= 0; i--) {
                        KuaixunItem item = items.get(i);
                        String id = item.getId();
                        if (id != null && !id.isEmpty() && !knownIds.contains(id)) {
                            knownIds.add(id);
                            cardsPanel.add(createCard(item), 0);
                            cardsPanel.add(Box.createVerticalStrut(JBUI.scale(4)), 1);
                        }
                    }
                    cardsPanel.revalidate();
                    cardsPanel.repaint();
                    isRefreshing = false;
                    countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
                    setStatus("最后更新 " + LocalTime.now().format(timeFormatter));
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    isRefreshing = false;
                    countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
                    setStatus("加载失败，请稍后重试");
                });
            }
        }).start();
    }

    /** 使用 SwingWorker 做自动刷新检查 */
    private void doCheckNewData() {
        isRefreshing = true;
        // 立即显示"刷新中..."状态，卡片半透明闪烁
        SwingUtilities.invokeLater(() -> {
            setStatus("刷新中...");
            for (int i = cardsPanel.getComponentCount() - 1; i >= 0; i--) {
                Component c = cardsPanel.getComponent(i);
                if (c instanceof NewsCard) {
                    ((NewsCard) c).setFading(true);
                    c.repaint();
                }
            }
        });

        SwingWorker<List<KuaixunItem>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<KuaixunItem> doInBackground() throws Exception {
                return service.fetchPage(1);
            }

            @Override
            protected void done() {
                try {
                    final List<KuaixunItem> items = get();
                    final boolean hasNew = determineHasNew(items);
                    SwingUtilities.invokeLater(() -> {
                        // 恢复卡片外观
                        for (int i = cardsPanel.getComponentCount() - 1; i >= 0; i--) {
                            Component c = cardsPanel.getComponent(i);
                            if (c instanceof NewsCard) {
                                ((NewsCard) c).setFading(false);
                                c.repaint();
                            }
                        }
                        if (hasNew) {
                            // 保存当前滚动位置
                            javax.swing.JViewport viewport = KuaixunPanel.this.newsScrollPane.getViewport();
                            int savedVpY = viewport.getViewPosition().y;
                            final List<javax.swing.JComponent> inserted = new java.util.ArrayList<>();
                            for (int i = items.size() - 1; i >= 0; i--) {
                                KuaixunItem item = items.get(i);
                                String id = item.getId();
                                if (id != null && !id.isEmpty() && !knownIds.contains(id)) {
                                    knownIds.add(id);
                                    javax.swing.JComponent card = createCard(item);
                                    cardsPanel.add(card, 0);
                                    inserted.add(0, card);
                                    cardsPanel.add(Box.createVerticalStrut(JBUI.scale(4)), 1);
                                }
                            }
                            cardsPanel.revalidate();
                            // 等待布局完成后计算新卡片总高度，恢复滚动位置
                            SwingUtilities.invokeLater(() -> {
                                int insertedHeight = 0;
                                for (javax.swing.JComponent c : inserted) {
                                    insertedHeight += c.getHeight();
                                }
                                // 加入间距：每张卡片一个 spacer
                                int spacerCount = inserted.size();
                                insertedHeight += spacerCount * JBUI.scale(4);
                                // 插入后所有内容下移了 insertedHeight 像素，滚动也要向下补偿
                                viewport.setViewPosition(new java.awt.Point(0, savedVpY + insertedHeight));
                                cardsPanel.repaint();
                            });
                            setStatus("最后更新 " + LocalTime.now().format(timeFormatter));
                        } else {
                            setStatus("最后更新 " + LocalTime.now().format(timeFormatter) + " · 已是最新");
                        }
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("刷新失败，请稍后重试");
                    });
                } finally {
                    isRefreshing = false;
                    countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
                }
            }
        };
        worker.execute();
    }

    /** 旧版手动刷新（保留兼容） */
    private void checkNewData() {
        new Thread(() -> {
            try {
                List<KuaixunItem> items = service.fetchPage(1);
                boolean hasNew = false;
                for (int i = items.size() - 1; i >= 0; i--) {
                    KuaixunItem item = items.get(i);
                    String id = item.getId();
                    if (id != null && !id.isEmpty() && !knownIds.contains(id)) {
                        hasNew = true;
                        break;
                    }
                }
                if (!hasNew) {
                    countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    javax.swing.JViewport viewport = this.newsScrollPane.getViewport();
                    int savedVpY = viewport.getViewPosition().y;
                    final List<javax.swing.JComponent> inserted = new java.util.ArrayList<>();
                    for (int i = items.size() - 1; i >= 0; i--) {
                        KuaixunItem item = items.get(i);
                        String id = item.getId();
                        if (id != null && !id.isEmpty() && !knownIds.contains(id)) {
                            knownIds.add(id);
                            javax.swing.JComponent card = createCard(item);
                            cardsPanel.add(card, 0);
                            inserted.add(0, card);
                            cardsPanel.add(Box.createVerticalStrut(JBUI.scale(4)), 1);
                        }
                    }
                    cardsPanel.revalidate();
                    SwingUtilities.invokeLater(() -> {
                        int insertedHeight = 0;
                        for (javax.swing.JComponent c : inserted) {
                            insertedHeight += c.getHeight();
                        }
                        int spacerCount = inserted.size();
                        insertedHeight += spacerCount * JBUI.scale(4);
                        viewport.setViewPosition(new java.awt.Point(0, savedVpY + insertedHeight));
                        cardsPanel.repaint();
                    });
                    setStatus("最后更新 " + LocalTime.now().format(timeFormatter));
                });
                countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
            } catch (Exception ignored) {
                countdownSeconds = REFRESH_INTERVAL_MS / COUNTDOWN_STEP_MS;
            }
        }).start();
    }

    private boolean determineHasNew(List<KuaixunItem> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            KuaixunItem item = items.get(i);
            String id = item.getId();
            if (id != null && !id.isEmpty() && !knownIds.contains(id)) {
                return true;
            }
        }
        return false;
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
        private float alpha = 1.0f; // 透明度，用于刷新动画

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

        void setFading(boolean fading) {
            this.alpha = fading ? 0.3f : 1.0f;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int gap = JBUI.scale(2);
            int arc = JBUI.scale(8);
            int x = gap, y = gap;
            int w = getWidth() - gap * 2, h = getHeight() - gap * 2;

            g2.setColor(hovered ? CARD_HOVER : CARD_BG);
            if (alpha < 1.0f) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            }
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

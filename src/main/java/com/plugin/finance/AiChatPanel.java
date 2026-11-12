package com.plugin.finance;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * AI 会话面板：宿主在工具窗口页签内，不再使用独立 JDialog。
 * 每个入口一个实例，页签关闭即销毁会话；再次从入口打开是全新一轮。
 */
public class AiChatPanel extends JPanel {

    /** 追问失败时写入 history 的 assistant 占位，保持 user/assistant 严格交替 */
    static final String FAILED_PLACEHOLDER = "（本轮回答失败，无结论）";

    private static final int MAX_BUBBLE_TEXT_WIDTH = 380;
    private static final int MIN_BUBBLE_TEXT_WIDTH = 48;
    private static final Color USER_BUBBLE_BG =
            new JBColor(new Color(0xD6E8FF), new Color(0x2F4A6B));
    private static final Color AI_BUBBLE_BG =
            new JBColor(new Color(0xF4F8F8), new Color(0x3A3B3D));

    private final String systemPrompt;
    private final Supplier<AIService> serviceFactory;
    private final List<String> history = new CopyOnWriteArrayList<>();
    private final List<Bubble> bubbles = new ArrayList<>();

    private final JPanel messagesPanel = new JPanel();
    private final JScrollPane chatScrollPane;
    private final JPanel loadingPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
    private final JLabel loadingLabel = new JLabel("正在获取行情数据...");
    private final JPanel thinkingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JTextField inputField = new JTextField();
    private final JButton sendBtn = new JButton("发送");

    private final AtomicReference<Runnable> cancelRef = new AtomicReference<>();
    private Bubble streamingBubble;
    private final StringBuilder streamingText = new StringBuilder();
    private long lastRender;
    private Timer loadingTimer;
    private String loadingBaseText = "";
    private boolean requesting;
    private volatile boolean disposed;

    public AiChatPanel(String systemPrompt, Supplier<AIService> serviceFactory) {
        this.systemPrompt = systemPrompt;
        this.serviceFactory = serviceFactory;
        setLayout(new BorderLayout());
        setBackground(UIUtil.getPanelBackground());

        // ── 聊天区域：每条消息一个自绘圆角气泡，宽度随面板宽度自适应 ──
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(UIUtil.getPanelBackground());
        messagesPanel.setBorder(JBUI.Borders.empty(10, 14));
        chatScrollPane = new JBScrollPane(messagesPanel);
        chatScrollPane.setBorder(null);
        chatScrollPane.getViewport().setBackground(UIUtil.getPanelBackground());
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // ── 顶部加载面板 ──
        loadingPanel.setBackground(UIUtil.getPanelBackground());
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(16, 16));
        loadingLabel.setFont(JBFont.label().deriveFont(12f));
        loadingLabel.setForeground(JBColor.namedColor("Label.disabledForeground", new Color(0x999999)));
        loadingPanel.add(progressBar);
        loadingPanel.add(loadingLabel);
        loadingPanel.setVisible(false);

        // ── 思考中提示 ──
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
        inputField.setToolTipText("输入问题，按 Enter 发送");
        inputField.setFont(JBFont.label().deriveFont(13f));
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(6, 8)));
        sendBtn.setPreferredSize(new Dimension(60, 30));
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);

        Runnable sendAction = () -> {
            if (requesting) {
                Runnable cancel = cancelRef.get();
                if (cancel != null) {
                    cancel.run();
                }
            } else {
                send(inputField.getText(), true);
            }
        };
        sendBtn.addActionListener(e -> sendAction.run());
        // 请求中按 Enter 不做任何事，避免误触发停止或重复发送
        inputField.addActionListener(e -> {
            if (!requesting) {
                sendAction.run();
            }
        });

        JPanel inputWrapper = new JPanel(new BorderLayout());
        inputWrapper.setBackground(UIUtil.getPanelBackground());
        inputWrapper.add(thinkingPanel, BorderLayout.NORTH);
        inputWrapper.add(inputPanel, BorderLayout.CENTER);

        add(loadingPanel, BorderLayout.NORTH);
        add(chatScrollPane, BorderLayout.CENTER);
        add(inputWrapper, BorderLayout.SOUTH);

        // 面板宽度随工具窗口变化：已渲染气泡按新宽度重排
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                reflowWidths();
            }
        });
    }

    public List<String> history() {
        return history;
    }

    public void addHistory(String text) {
        if (text != null) {
            history.add(text);
        }
    }

    /** 用户提问：进入 history 并显示用户气泡（快讯解读等入口的首发消息） */
    public void send(String text) {
        send(text, true);
    }

    /** 自动触发的首发消息：同样进 history 以保证角色交替，但不显示用户气泡 */
    public void sendSilent(String text) {
        send(text, false);
    }

    /** 首轮自动分析占住发送按钮：期间变为「停止」，同时避免用户并发发起追问 */
    public void beginAutoRequest() {
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                setRequesting(true);
            }
        });
    }

    public void attachCancel(Runnable cancel) {
        cancelRef.set(cancel);
        // 面板可能在行情拉取期间就被关闭：此时立即中止，避免白跑一次请求
        if (disposed) {
            Runnable pending = cancelRef.getAndSet(null);
            if (pending != null) {
                pending.run();
            }
        }
    }

    /** 流式增量：不区分线程，内部回 EDT 原地更新同一个气泡 */
    public void onStreamChunk(String chunk) {
        if (disposed) {
            return;
        }
        SwingUtilities.invokeLater(() -> renderChunk(chunk));
    }

    /** 请求成功：收起等待/思考提示，回到正式气泡 */
    public void completeRequest(String finalText) {
        Runnable done = () -> {
            if (disposed) {
                return;
            }
            cancelRef.set(null);
            thinkingPanel.setVisible(false);
            hideLoadingNow();
            setRequesting(false);
            String text = finalText == null ? "" : finalText;
            history.add(text);
            renderFinal(text);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            done.run();
        } else {
            SwingUtilities.invokeLater(done);
        }
    }

    /** 请求失败：补 assistant 占位维持角色交替，已流式显示的正文保留，错误追加在其后 */
    public void failRequest(String errorText) {
        Runnable done = () -> {
            if (disposed) {
                return;
            }
            cancelRef.set(null);
            thinkingPanel.setVisible(false);
            hideLoadingNow();
            setRequesting(false);
            history.add(FAILED_PLACEHOLDER);
            renderFailure(errorText);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            done.run();
        } else {
            SwingUtilities.invokeLater(done);
        }
    }

    /** 无需进 history 的提示气泡（如暂无行情数据） */
    public void notice(String text) {
        SwingUtilities.invokeLater(() -> {
            if (disposed) {
                return;
            }
            thinkingPanel.setVisible(false);
            hideLoadingNow();
            setRequesting(false);
            appendBubble(false, text == null ? "" : text);
        });
    }

    /** 等待提示：baseText 形如「正在分析 3 只自选股...」，每秒补一次已等待秒数 */
    public void startLoading(String baseText) {
        SwingUtilities.invokeLater(() -> {
            if (disposed) {
                return;
            }
            stopLoadingTimer();
            loadingBaseText = baseText;
            loadingLabel.setText(baseText);
            loadingPanel.setVisible(true);
            final long startAt = System.currentTimeMillis();
            loadingTimer = new Timer(1000, ev -> {
                if (disposed || !loadingPanel.isVisible()) {
                    ((Timer) ev.getSource()).stop();
                    return;
                }
                long sec = (System.currentTimeMillis() - startAt) / 1000;
                loadingLabel.setText(loadingBaseText + " 已等待 " + sec + " 秒");
            });
            loadingTimer.start();
        });
    }

    public void hideLoading() {
        SwingUtilities.invokeLater(this::hideLoadingNow);
    }

    public void focusInput() {
        inputField.requestFocusInWindow();
    }

    /** 页签关闭：中止进行中的请求并标记失效，后续回调不再改动已移除的界面 */
    public void disposeSession() {
        disposed = true;
        Runnable cancel = cancelRef.getAndSet(null);
        if (cancel != null) {
            try {
                cancel.run();
            } catch (Exception ignored) {
            }
        }
        stopLoadingTimer();
    }

    // ── 内部实现 ──

    private void send(String text, boolean showUserBubble) {
        String userMsg = text == null ? "" : text.trim();
        if (userMsg.isEmpty() || requesting || disposed) {
            return;
        }
        inputField.setText("");
        history.add(userMsg);
        if (showUserBubble) {
            appendBubble(true, userMsg);
        }
        thinkingPanel.setVisible(true);
        setRequesting(true);

        new Thread(() -> {
            try {
                AIService svc = serviceFactory.get();
                cancelRef.set(svc::cancel);
                svc.chatAsync(systemPrompt, history, this::onStreamChunk)
                        .thenAccept(this::completeRequest)
                        .exceptionally(e -> {
                            failRequest("AI 分析失败: " + e.getMessage());
                            return null;
                        });
            } catch (Exception e) {
                failRequest("服务初始化失败: " + e.getMessage());
            }
        }, "ai-chat-request").start();
    }

    private void setRequesting(boolean busy) {
        requesting = busy;
        sendBtn.setText(busy ? "停止" : "发送");
        // 按钮始终可点：非请求中用于发送，请求中用于停止
        sendBtn.setToolTipText(busy ? "中止本次生成" : "发送问题（Enter）");
    }

    private void renderChunk(String chunk) {
        if (disposed) {
            return;
        }
        boolean first = streamingBubble == null;
        if (first) {
            streamingBubble = new Bubble(false, "");
            bubbles.add(streamingBubble);
            messagesPanel.add(streamingBubble.row);
            messagesPanel.add(Box.createVerticalStrut(JBUI.scale(8)));
        }
        String text = chunk == null ? "" : chunk;
        boolean hint = text.startsWith(AIService.STREAM_HINT_PREFIX);
        boolean replace = text.startsWith(AIService.STREAM_STATUS_PREFIX);
        if (hint || replace) {
            // 整段替换：等待提示与第一版草稿用弱化样式，定稿恢复常规气泡
            streamingText.setLength(0);
            text = text.substring(1);
            streamingBubble.setStatusMode(hint);
        }
        streamingText.append(text);
        long now = System.currentTimeMillis();
        if (!first && now - lastRender < 80) {
            return;
        }
        lastRender = now;
        streamingBubble.setText(streamingText.toString(), contentWidth());
        reflow();
    }

    private void renderFinal(String finalText) {
        String text = finalText == null ? "" : finalText;
        if (streamingBubble != null) {
            // 最终文本一定是定稿：即使模型没写第二版，也把草稿样式切回正式气泡
            streamingBubble.setStatusMode(false);
            streamingBubble.setText(text, contentWidth());
        } else {
            appendBubble(false, text);
        }
        streamingBubble = null;
        streamingText.setLength(0);
        reflow();
    }

    /** 失败收尾：已显示过的正文不能被错误提示顶掉，只有纯状态提示时才整段替换 */
    private void renderFailure(String errorText) {
        String error = errorText == null ? "" : errorText;
        if (streamingBubble == null) {
            appendBubble(false, error);
            return;
        }
        String shown = streamingText.toString();
        boolean hasContent = !streamingBubble.isStatusMode() && !shown.isEmpty();
        streamingBubble.setStatusMode(false);
        streamingBubble.setText(hasContent ? shown + "\n\n" + error : error, contentWidth());
        streamingBubble = null;
        streamingText.setLength(0);
        reflow();
    }

    private void appendBubble(boolean isUser, String content) {
        streamingBubble = null;
        streamingText.setLength(0);
        Bubble bubble = new Bubble(isUser, content);
        bubbles.add(bubble);
        messagesPanel.add(bubble.row);
        messagesPanel.add(Box.createVerticalStrut(JBUI.scale(8)));
        bubble.setText(content, contentWidth());
        reflow();
    }

    private void reflow() {
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    private void reflowWidths() {
        int width = contentWidth();
        for (Bubble bubble : bubbles) {
            bubble.setText(bubble.text(), width);
        }
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
    }

    /** 气泡可用文本宽度：视口宽度扣掉滚动条、聊天区内边距与气泡左右内边距 */
    private int contentWidth() {
        int viewport = chatScrollPane.getViewport().getWidth();
        if (viewport <= 0) {
            viewport = getWidth() > 0 ? getWidth() : JBUI.scale(360);
        }
        int scrollBar = chatScrollPane.getVerticalScrollBar().isVisible()
                ? chatScrollPane.getVerticalScrollBar().getWidth() : 0;
        return Math.max(JBUI.scale(160), viewport - scrollBar - JBUI.scale(52));
    }

    private void hideLoadingNow() {
        stopLoadingTimer();
        loadingPanel.setVisible(false);
    }

    private void stopLoadingTimer() {
        if (loadingTimer != null) {
            loadingTimer.stop();
            loadingTimer = null;
        }
    }

    /** 自绘圆角气泡；Swing HTML 3.2 不支持 border-radius，只能画出来 */
    private static class BubblePanel extends JPanel {
        private final Color bg;
        /** 思考/等待状态下不画气泡底框，只留一行弱化文字 */
        private boolean statusMode;

        BubblePanel(Color bg, JTextArea area) {
            this.bg = bg;
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(JBUI.Borders.empty(6, 12));
            add(area, BorderLayout.CENTER);
        }

        void setStatusMode(boolean status) {
            if (statusMode != status) {
                statusMode = status;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!statusMode) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(bg);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                } finally {
                    g2.dispose();
                }
            }
            super.paintComponent(g);
        }
    }

    /** 一条消息：行容器 + 气泡 + 可流式原地更新的文本区，宽度按可用宽度自适应 */
    private static class Bubble {
        final JPanel row;
        final BubblePanel bubble;
        final JTextArea area;
        private final boolean isUser;
        private final Font baseFont;
        private boolean statusMode;

        Bubble(boolean isUser, String text) {
            this.isUser = isUser;
            area = new JTextArea(text == null ? "" : text);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setOpaque(false);
            area.setBorder(null);
            Font labelFont = UIManager.getFont("Label.font");
            baseFont = labelFont != null ? labelFont : JBFont.label();
            area.setFont(baseFont.deriveFont(Font.PLAIN, 13f));
            area.setForeground(UIUtil.getLabelForeground());
            bubble = new BubblePanel(isUser ? USER_BUBBLE_BG : AI_BUBBLE_BG, area);
            if (isUser) {
                // 用户消息通常很短，右对齐并按内容自适应
                row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                row.add(bubble);
            } else {
                // AI 输出占满整行：BorderLayout.CENTER 会把气泡横向拉伸到容器宽度
                row = new JPanel(new BorderLayout());
                row.add(bubble, BorderLayout.CENTER);
            }
            row.setOpaque(false);
        }

        String text() {
            return area.getText();
        }

        boolean isStatusMode() {
            return statusMode;
        }

        /** 思考/等待状态：灰字斜体、无气泡底框；正文：常规字体与气泡 */
        void setStatusMode(boolean status) {
            if (statusMode == status) {
                return;
            }
            statusMode = status;
            bubble.setStatusMode(status);
            area.setFont(status
                    ? baseFont.deriveFont(Font.ITALIC, 12f)
                    : baseFont.deriveFont(Font.PLAIN, 13f));
            area.setForeground(status
                    ? JBColor.namedColor("Label.disabledForeground", new Color(0x999999))
                    : UIUtil.getLabelForeground());
            bubble.repaint();
        }

        void setText(String text, int availableWidth) {
            area.setText(text);
            FontMetrics fm = area.getFontMetrics(area.getFont());
            int natural = 0;
            for (String line : text.split("\n", -1)) {
                natural = Math.max(natural, fm.stringWidth(line));
            }
            int maxWidth = Math.max(MIN_BUBBLE_TEXT_WIDTH, availableWidth);
            int w = isUser
                    ? Math.max(MIN_BUBBLE_TEXT_WIDTH,
                    Math.min(natural + 2, Math.min(MAX_BUBBLE_TEXT_WIDTH, maxWidth)))
                    : maxWidth;
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
}

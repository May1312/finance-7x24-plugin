package com.plugin.finance;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * AI 设置对话框：工具窗口「设置」按钮的一键入口。
 * 表单逻辑复用 {@link AISettingsPanel}，IDE 设置页（AISettingsConfigurable）共用同一面板。
 */
public class AISettingsDialog extends JDialog {

    private boolean confirmed = false;
    private String userApiKey = "";
    private String userModelId = "";
    private final Component parent;
    private final AISettingsPanel settingsPanel;
    /** 上一次的渠道布局（内置/自定义），用于判断模式是否真的切换 */
    private boolean lastCustomMode = false;
    /** 会话内上次关闭时的窗口位置：首次打开居中，之后沿用用户拖放的位置 */
    private static Rectangle lastBounds;
    private JPanel bottomPanel;

    public AISettingsDialog(Project project, String savedModelId) {
        this(project, null, savedModelId);
    }

    /** parent 非空时以所在 IDE 窗口为 owner 并相对其居中 */
    public AISettingsDialog(Project project, Component parent, String savedModelId) {
        super(parent != null ? SwingUtilities.getWindowAncestor(parent) : null,
                "AI 自选股分析设置", ModalityType.APPLICATION_MODAL);
        this.parent = parent;
        setResizable(false);
        setLayout(new BorderLayout());

        settingsPanel = new AISettingsPanel();
        settingsPanel.setModeChangeListener(this::onModeChanged);
        add(settingsPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttonPanel.setBorder(JBUI.Borders.empty(0, 18, 14, 18));
        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(JBFont.label());
        cancelBtn.setPreferredSize(new Dimension(60, 28));
        cancelBtn.addActionListener(e -> dispose());
        JButton okBtn = new JButton("保存");
        okBtn.setFont(JBFont.label());
        okBtn.setPreferredSize(new Dimension(60, 28));
        okBtn.addActionListener(e -> onSave());
        buttonPanel.add(cancelBtn);
        buttonPanel.add(okBtn);
        bottomPanel = buttonPanel;
        add(buttonPanel, BorderLayout.SOUTH);

        // 高度按内容自适应（fitContent），行距两种模式严格一致
        settingsPanel.loadSelection(savedModelId);
        fitContent();
        // 首次打开居中；之后沿用上次关闭时的位置（用户拖到哪就在哪）
        if (lastBounds != null && isOnAnyScreen(lastBounds)) {
            setLocation(lastBounds.getLocation());
        } else {
            setLocationRelativeTo(parent);
        }

        // 去掉默认按钮，避免保存按钮出现蓝色焦点框
        SwingUtilities.invokeLater(() -> {
            JRootPane rp = getRootPane();
            if (rp != null) {
                rp.setDefaultButton(null);
            }
        });
    }

    /** 仅在 内置↔自定义 模式真正切换时调整高度；位置保持用户当前拖放的不动 */
    private void onModeChanged() {
        boolean custom = settingsPanel.isCustomSelected();
        if (custom == lastCustomMode) {
            return;
        }
        lastCustomMode = custom;
        fitContent();
    }

    /** 高度按内容自适应（宽 480）：避免固定高度下多余空间被 BoxLayout 摊进行距，
     *  导致内置/自定义两种模式行距不一致 */
    private void fitContent() {
        pack();
        if (getWidth() < 480) {
            setSize(480, getHeight());
        }
    }

    /** 关闭时记住窗口位置，下次打开沿用 */
    @Override
    public void dispose() {
        lastBounds = getBounds();
        super.dispose();
    }

    private static boolean isOnAnyScreen(Rectangle bounds) {
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().intersects(bounds)) {
                return true;
            }
        }
        return false;
    }

    private void onSave() {
        if (settingsPanel.isCustomSelected()) {
            if (settingsPanel.getBaseUrlInput().isEmpty() || settingsPanel.getCustomModelInput().isEmpty()) {
                Messages.showWarningDialog("请填写接口地址与模型 ID", "保存失败");
                return;
            }
            AIService.saveCustomConfig(settingsPanel.getBaseUrlInput(), settingsPanel.getCustomModelInput());
            userModelId = AIService.CUSTOM_MODEL_ID;
        } else {
            userModelId = settingsPanel.getSelectedModelId();
        }
        userApiKey = settingsPanel.getKeyInput();
        String provider = AIService.isCustomModel(userModelId)
                ? AIService.CUSTOM_PROVIDER
                : AIService.MODELS.get(userModelId).provider;
        AIService.saveConfig(provider, userApiKey);
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getApiKey() {
        return userApiKey;
    }

    public String getModelId() {
        return userModelId;
    }
}

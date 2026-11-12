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
    private String userProfileId = "";
    private final Component parent;
    private final AISettingsPanel settingsPanel;
    /** 会话内上次关闭时的窗口位置：首次打开居中，之后沿用用户拖放的位置 */
    private static Rectangle lastBounds;

    public AISettingsDialog(Project project, String activeProfileId) {
        this(project, null, activeProfileId);
    }

    /** parent 非空时以所在 IDE 窗口为 owner 并相对其居中 */
    public AISettingsDialog(Project project, Component parent, String activeProfileId) {
        super(parent != null ? SwingUtilities.getWindowAncestor(parent) : null,
                "AI 模型设置", ModalityType.APPLICATION_MODAL);
        this.parent = parent;
        // 默认 HIDE_ON_CLOSE 会在点右上角关闭时只隐藏窗口，下次再打开又多一个实例
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setLayout(new BorderLayout());

        settingsPanel = new AISettingsPanel();
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
        add(buttonPanel, BorderLayout.SOUTH);

        settingsPanel.loadSelection(activeProfileId);
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

    /** 高度按内容自适应（宽 480），避免固定高度下 BoxLayout 把多余空间摊进行距 */
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
        String error = settingsPanel.validationError();
        if (error != null) {
            Messages.showWarningDialog(error, "保存失败");
            return;
        }
        settingsPanel.applyToStorage();
        userProfileId = settingsPanel.getActiveProfileId();
        userApiKey = settingsPanel.getActiveApiKey();
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getApiKey() {
        return userApiKey;
    }

    /** 当前启用的配置 ID（旧版这里返回内置模型 ID / custom 哨兵） */
    public String getProfileId() {
        return userProfileId;
    }
}

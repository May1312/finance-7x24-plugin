package com.plugin.finance;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/** 工具窗口标题栏的「AI 设置」快捷入口：快讯/自选任意页签下都能一键打开设置 */
public class ShowAiSettingsAction extends AnAction {

    private static final String TOOL_WINDOW_ID = "Finance 7x24 News";

    public ShowAiSettingsAction() {
        super("AI 设置", "打开 AI 模型设置", AllIcons.General.Settings);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null || toolWindow.isDisposed()) {
            return;
        }
        Content content = toolWindow.getContentManager().getContent(0);
        JComponent component = content != null ? content.getComponent() : null;
        if (component instanceof KuaixunPanel) {
            ((KuaixunPanel) component).openAiSettings();
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}

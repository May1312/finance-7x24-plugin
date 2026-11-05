package com.plugin.finance;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public class KuaixunToolWindowFactory implements ToolWindowFactory, DumbAware {
    /** 工具窗口图标 */
    private static final Icon ICON =
            IconLoader.getIcon("/icons/kuaixunToolWindow.svg", KuaixunToolWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(ICON);

        KuaixunPanel panel = new KuaixunPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);

        // 标题栏常驻 AI 入口：速览 / 选股分析 / 设置，快讯与自选页签下都能一键直达
        toolWindow.setTitleActions(java.util.List.of(
                new AiQuickActions.Summary(),
                new AiQuickActions.StockAnalysis(),
                new ShowAiSettingsAction()));
    }
}

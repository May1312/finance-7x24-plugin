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

        // 标题栏常驻「AI 设置」齿轮：快讯/自选任意页签下都能一键打开
        toolWindow.setTitleActions(java.util.List.of(new ShowAiSettingsAction()));
    }
}

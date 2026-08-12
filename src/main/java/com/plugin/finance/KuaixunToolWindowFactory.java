package com.plugin.finance;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public class KuaixunToolWindowFactory implements ToolWindowFactory, DumbAware {
    /** 未选中时的灰色模板图标 */
    private static final Icon NORMAL_ICON =
            IconLoader.getIcon("/icons/kuaixunToolWindow.svg", KuaixunToolWindowFactory.class);
    /** 选中时上涨折线为红色的图标 */
    private static final Icon SELECTED_ICON =
            IconLoader.getIcon("/icons/kuaixunToolWindowSelected.svg", KuaixunToolWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(NORMAL_ICON);

        // 窗口激活/失活时切换图标：选中时上涨折线显示为红色
        Disposable disposable = toolWindow.getDisposable();
        project.getMessageBus().connect(disposable)
                .subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
                    @Override
                    public void stateChanged(@NotNull ToolWindowManager manager,
                                             @NotNull ToolWindow changed,
                                             @NotNull ToolWindowManagerEventType eventType) {
                        if (eventType == ToolWindowManagerEventType.ActivateToolWindow) {
                            toolWindow.setIcon(changed == toolWindow ? SELECTED_ICON : NORMAL_ICON);
                        }
                    }
                });

        KuaixunPanel panel = new KuaixunPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}

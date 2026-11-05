package com.plugin.finance;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * 工具窗口标题栏上的 AI 快捷入口：速览 / 选股分析。
 * 与「AI 设置」齿轮并排，任意页签下都能一键触发，不再占用内容区的横向按钮条。
 */
public final class AiQuickActions {

    private static final String TOOL_WINDOW_ID = "Finance 7x24 News";

    private AiQuickActions() { }

    private static KuaixunPanel findPanel(Project project) {
        if (project == null) {
            return null;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null || toolWindow.isDisposed()) {
            return null;
        }
        Content content = toolWindow.getContentManager().getContent(0);
        JComponent component = content != null ? content.getComponent() : null;
        return component instanceof KuaixunPanel ? (KuaixunPanel) component : null;
    }

    abstract static class BaseAction extends AnAction {
        private final Consumer<KuaixunPanel> handler;

        BaseAction(String text, String description, Icon icon, Consumer<KuaixunPanel> handler) {
            super(text, description, icon);
            this.handler = handler;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            KuaixunPanel panel = findPanel(e.getProject());
            if (panel != null) {
                handler.accept(panel);
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(findPanel(e.getProject()) != null);
        }
    }

    /** AI 速览：汇总最近一批快讯要点 */
    public static class Summary extends BaseAction {
        public Summary() {
            super("AI速览", "AI 汇总最近一批快讯要点，可在对话框中继续追问",
                    AllIcons.Actions.Preview, KuaixunPanel::openAiSummary);
        }
    }

    /** AI 选股分析：按自选页当前市场筛选分析自选股 */
    public static class StockAnalysis extends BaseAction {
        public StockAnalysis() {
            super("AI选股分析", "AI 分析自选股行情，可在对话框中继续追问",
                    AllIcons.Actions.IntentionBulb, KuaixunPanel::openAiStockAnalysis);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            KuaixunPanel panel = findPanel(e.getProject());
            if (panel != null) {
                // 自选页的市场筛选在内部可变，每次显示提示时实时取，避免用户不知道分析的是哪个市场
                e.getPresentation().setDescription(
                        "AI 分析自选股行情（当前市场：" + panel.currentMarketName() + "）");
            }
        }
    }
}

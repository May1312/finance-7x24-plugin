package com.plugin.finance;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.event.MouseEvent;

/**
 * IDE 底部状态栏的隐蔽行情入口：常态只显示插件的灰色折线小图标（形似性能监控 sparkline，
 * 与侧边栏工具窗口图标呼应），A 股大盘指数与自选异动明细全部收在悬停 tooltip 中，
 * 无点击行为（误点不会弹出行情窗口）。不弹任何通知，可通过状态栏右键菜单隐藏。
 * 扩展点声明 order="last"：固定在最右端，编辑器 widget 增减不会把图标挤进编辑器 widget 区。
 */
public final class MarketStatusBarWidgetFactory implements StatusBarWidgetFactory {

    static final String WIDGET_ID = "Finance7x24.Market";

    @Override
    public @NotNull String getId() {
        return WIDGET_ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Finance 行情";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new MarketWidget();
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose(widget);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }

    private static final class MarketWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {
        private static final Icon ICON =
                IconLoader.getIcon("/icons/kuaixunToolWindow.svg", MarketStatusBarWidgetFactory.class);
        private final MarketMonitorService monitor = MarketMonitorService.getInstance();
        private final Runnable listener = this::refresh;
        private StatusBar statusBar;

        @Override
        public @NotNull String ID() {
            return WIDGET_ID;
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
            this.statusBar = statusBar;
            monitor.addListener(listener);
            refresh();
        }

        @Override
        public void dispose() {
            monitor.removeListener(listener);
            statusBar = null;
        }

        private void refresh() {
            StatusBar bar = statusBar;
            if (bar != null) {
                bar.updateWidget(ID());
            }
        }

        @Override
        public @Nullable WidgetPresentation getPresentation() {
            return this;
        }

        @Override
        public @NotNull Icon getIcon() {
            return ICON;
        }

        @Override
        public @Nullable String getTooltipText() {
            MarketMonitorService.Snapshot s = monitor.getSnapshot();
            StringBuilder sb = new StringBuilder("<html><table cellpadding='3' cellspacing='0'>");
            if (!s.loaded) {
                sb.append(row(nobr(dim("行情加载中…")), ""));
            } else {
                if (!s.indexItems.isEmpty()) {
                    // 每个指数独立成行、与异动共用两列网格：不用 colspan、无长行，
                    // 表格偏好宽度=网格宽度，不会被大盘长行撑到屏幕外遭裁切
                    sb.append(row(nobr(dim("<b>大盘</b>")), s.aShareOpen ? "" : nobr(dim("休市"))));
                    for (int i = 0; i < s.indexItems.size(); i++) {
                        sb.append(row(nobr(s.indexNames.get(i)),
                                nobr(pct(s.indexItems.get(i).getChangePercent(), false))));
                    }
                }
                if (!s.abnormalItems.isEmpty()) {
                    sb.append(row(nobr(dim("<b>自选异动</b>")), ""));
                    Integer[] order = new Integer[s.abnormalItems.size()];
                    for (int i = 0; i < order.length; i++) {
                        order[i] = i;
                    }
                    java.util.Arrays.sort(order, (a, b) -> {
                        double ca = s.abnormalItems.get(a).getChangePercent();
                        double cb = s.abnormalItems.get(b).getChangePercent();
                        int ga = ca > 0 ? 0 : ca < 0 ? 1 : 2;
                        int gb = cb > 0 ? 0 : cb < 0 ? 1 : 2;
                        return ga != gb ? Integer.compare(ga, gb) : Double.compare(Math.abs(cb), Math.abs(ca));
                    });
                    for (int i : order) {
                        QuoteItem item = s.abnormalItems.get(i);
                        double threshold = s.abnormalThresholds.get(i);
                        String thresholdText = threshold == Math.floor(threshold)
                                ? String.format("%.0f%%", threshold)
                                : String.format("%.1f%%", threshold);
                        sb.append("<tr><td>").append(nobr(StringUtil.escapeXmlEntities(item.getName())))
                                .append("</td><td align='right'>").append(nobr(dim("阈值 " + thresholdText)))
                                .append("</td><td align='right'>").append(nobr(pct(item.getChangePercent(), true)))
                                .append("</td></tr>");
                    }
                } else if (!s.watchItems.isEmpty()) {
                    sb.append(row(nobr(dim("自选无显著异动")), ""));
                }
            }
            sb.append("</table></html>");
            return sb.toString();
        }

        // IDE 会以窄宽度先布局 tooltip：可断行的表格单元格会被压成逐字换行，nobr 保证单元格整体不断行
        private static String nobr(String content) {
            return content.isEmpty() ? "" : "<nobr>" + content + "</nobr>";
        }

        // 三列表格：名称左 / 阈值灰字中右 / 涨跌幅右，每行单行，行高与大盘行一致
        private static String row(String c1, String c2) {
            return "<tr><td>" + c1 + "</td><td></td><td align='right'>" + c2 + "</td></tr>";
        }

        /** 涨跌着色：A 股口径红涨绿跌；中等明度色值在明暗两套主题下都可读 */
        private static String pct(double value, boolean bold) {
            String color = value > 0 ? "#E5534B" : value < 0 ? "#3FB950" : "#8B949E";
            String text = String.format("%+.2f%%", value);
            return "<font color='" + color + "'>" + (bold ? "<b>" + text + "</b>" : text) + "</font>";
        }

        private static String dim(String text) {
            return "<font color='#8B949E' size='-1'>" + text + "</font>";
        }

        @Override
        public @Nullable Consumer<MouseEvent> getClickConsumer() {
            // 无点击行为：伪装成同步指示，误点不暴露行情窗口
            return null;
        }
    }
}
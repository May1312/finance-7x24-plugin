package com.plugin.finance;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * IDE 设置页：Settings → Tools → Finance 7x24 News AI。
 * 表单逻辑与设置对话框共用 {@link AISettingsPanel}。
 */
public class AISettingsConfigurable implements Configurable {

    private AISettingsPanel panel;
    private String initialSnapshot = "";

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Finance 7x24 News AI";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new AISettingsPanel();
        initialSnapshot = panel.getSnapshot();
        // 设置页通常很高：表单必须顶部对齐、只占首选高度，
        // 否则 BoxLayout 会把每一行拉伸到满屏（下拉框变成巨型控件）
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    @Override
    public boolean isModified() {
        return panel != null && !initialSnapshot.equals(panel.getSnapshot());
    }

    @Override
    public void apply() throws ConfigurationException {
        if (panel == null) {
            return;
        }
        String error = panel.validationError();
        if (error != null) {
            throw new ConfigurationException(error);
        }
        panel.applyToStorage();
        initialSnapshot = panel.getSnapshot();
    }

    @Override
    public void reset() {
        if (panel != null) {
            panel.resetFromStorage();
            initialSnapshot = panel.getSnapshot();
        }
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }
}

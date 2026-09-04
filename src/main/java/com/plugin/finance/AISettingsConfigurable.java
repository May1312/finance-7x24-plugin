package com.plugin.finance;

import com.intellij.ide.util.PropertiesComponent;
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

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Finance 7x24 News AI";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new AISettingsPanel();
        panel.loadSelection(PropertiesComponent.getInstance().getValue("finance.ai.model", ""));
        // 设置页通常很高：表单必须顶部对齐、只占首选高度，
        // 否则 BoxLayout 会把每一行拉伸到满屏（下拉框变成巨型控件）
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    @Override
    public boolean isModified() {
        if (panel == null) {
            return false;
        }
        String savedModel = PropertiesComponent.getInstance().getValue("finance.ai.model", "");
        String selected = panel.getSelectedModelId();
        if (!selected.equals(savedModel)) {
            return true;
        }
        if (panel.isCustomSelected()) {
            if (!panel.getBaseUrlInput().equals(AIService.getCustomBaseUrl())
                    || !panel.getCustomModelInput().equals(AIService.getCustomModelName())) {
                return true;
            }
            // Key 与内存缓存比较，避免首次访问系统钥匙串阻塞设置页
            return !panel.getKeyInput().equals(AIService.getCachedApiKey(AIService.CUSTOM_PROVIDER));
        }
        AIService.ModelInfo info = AIService.MODELS.get(selected);
        if (info == null) {
            return false;
        }
        return !panel.getKeyInput().equals(AIService.getCachedApiKey(info.provider));
    }

    @Override
    public void apply() throws ConfigurationException {
        if (panel == null) {
            return;
        }
        String modelId = panel.getSelectedModelId();
        String key = panel.getKeyInput();
        String provider;
        if (panel.isCustomSelected()) {
            if (panel.getBaseUrlInput().isEmpty() || panel.getCustomModelInput().isEmpty()) {
                throw new ConfigurationException("请填写自定义渠道的接口地址与模型 ID");
            }
            AIService.saveCustomConfig(panel.getBaseUrlInput(), panel.getCustomModelInput());
            provider = AIService.CUSTOM_PROVIDER;
        } else {
            AIService.ModelInfo info = AIService.MODELS.get(modelId);
            provider = info != null ? info.provider : AIService.PROVIDERS.get(0);
        }
        AIService.saveConfig(provider, key);
        PropertiesComponent.getInstance().setValue("finance.ai.model", modelId);
    }

    @Override
    public void reset() {
        if (panel != null) {
            panel.loadSelection(PropertiesComponent.getInstance().getValue("finance.ai.model", ""));
        }
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }
}

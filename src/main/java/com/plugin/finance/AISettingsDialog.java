package com.plugin.finance;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.util.List;

public class AISettingsDialog extends JDialog {

    private boolean confirmed = false;
    private String userApiKey = "";
    private String userModelId = "";
    private final Project project;
    private final JComboBox<String> providerCombo;
    private final JComboBox<String> modelCombo;
    private final JPasswordField keyField;
    private final JLabel helpLabel;
    private String currentUrl = "";

    public AISettingsDialog(Project project, String savedModelId) {
        super((Frame) null, "AI 自选股分析设置", true);
        this.project = project;
        setResizable(false);
        setLayout(new BorderLayout());
        // 固定尺寸，防止切换厂商时布局抖动
        setSize(480, 280);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(14, 18));

        // 厂商选择
        JPanel providerPanel = new JPanel(new BorderLayout(6, 0));
        providerPanel.setOpaque(false);
        JLabel providerLabel = new JLabel("厂商：");
        providerLabel.setFont(JBFont.label());
        providerLabel.setPreferredSize(new Dimension(55, 28));
        providerPanel.add(providerLabel, BorderLayout.WEST);

        providerCombo = new JComboBox<>(AIService.PROVIDERS.toArray(new String[0]));
        providerCombo.setFont(JBFont.label().deriveFont(13f));
        providerCombo.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        providerCombo.setPreferredSize(new Dimension(0, 26));
        providerPanel.add(providerCombo);
        mainPanel.add(providerPanel);

        mainPanel.add(Box.createVerticalStrut(10));

        // 模型选择
        JPanel modelPanel = new JPanel(new BorderLayout(6, 0));
        modelPanel.setOpaque(false);
        JLabel modelLabel = new JLabel("模型：");
        modelLabel.setFont(JBFont.label());
        modelLabel.setPreferredSize(new Dimension(55, 28));
        modelPanel.add(modelLabel, BorderLayout.WEST);

        modelCombo = new JComboBox<>();
        modelCombo.setFont(JBFont.label().deriveFont(13f));
        modelCombo.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        modelCombo.setPreferredSize(new Dimension(0, 26));
        modelPanel.add(modelCombo);
        mainPanel.add(modelPanel);

        mainPanel.add(Box.createVerticalStrut(12));

        // API Key 输入
        JPanel keyPanel = new JPanel(new BorderLayout(6, 0));
        keyPanel.setOpaque(false);
        JLabel keyLabel = new JLabel("Key：");
        keyLabel.setFont(JBFont.label());
        keyLabel.setPreferredSize(new Dimension(55, 28));
        keyPanel.add(keyLabel, BorderLayout.WEST);
        keyField = new JPasswordField();
        keyField.setFont(JBFont.label().deriveFont(13f));
        keyField.setColumns(18);
        keyField.setBorder(null);
        keyField.setPreferredSize(new Dimension(0, 26));
        keyPanel.add(keyField);
        mainPanel.add(keyPanel);

        mainPanel.add(Box.createVerticalStrut(10));

        // 注册提示（可点击链接）
        JPanel helpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        helpPanel.setOpaque(false);
        helpLabel = new JLabel();
        helpLabel.setFont(JBFont.label().deriveFont(11f));
        helpLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        helpPanel.add(helpLabel);
        mainPanel.add(helpPanel);

        // 点击链接打开浏览器
        helpLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!currentUrl.isEmpty()) {
                    BrowserUtil.browse(currentUrl);
                }
            }
        });

        mainPanel.add(Box.createVerticalStrut(15));

        // 按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));

        JButton testBtn = new JButton("测试连接");
        testBtn.setFont(JBFont.label());
        testBtn.setPreferredSize(new Dimension(80, 28));
        testBtn.addActionListener(e -> testConnection());

        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(JBFont.label());
        cancelBtn.setPreferredSize(new Dimension(60, 28));
        cancelBtn.addActionListener(e -> dispose());

        JButton okBtn = new JButton("保存");
        okBtn.setFont(JBFont.label());
        okBtn.setPreferredSize(new Dimension(60, 28));
        okBtn.setDefaultCapable(false);
        okBtn.addActionListener(e -> {
            userApiKey = currentKeyInput();
            userModelId = getSelectedModelId();
            String provider = AIService.MODELS.get(userModelId).provider;
            AIService.saveConfig(provider, userApiKey);
            confirmed = true;
            dispose();
        });

        buttonPanel.add(testBtn);
        buttonPanel.add(cancelBtn);
        buttonPanel.add(okBtn);
        mainPanel.add(buttonPanel);

        add(new JBScrollPane(mainPanel), BorderLayout.CENTER);

        // 去掉默认按钮，避免保存按钮出现蓝色焦点框
        SwingUtilities.invokeLater(() -> {
            JRootPane rp = getRootPane();
            if (rp != null) {
                rp.setDefaultButton(null);
                rp.requestFocus(false);
            }
        });

        // 厂商变化时联动刷新
        providerCombo.addActionListener(e -> onProviderChanged());

        // 恢复已保存的选择
        restoreSelection(savedModelId);

        // 使用固定尺寸，不调用 pack()
        setLocationRelativeTo(null);
    }

    private void onProviderChanged() {
        String provider = (String) providerCombo.getSelectedItem();
        // 刷新模型下拉
        refreshModelCombo();
        // 从系统钥匙串异步加载该厂商保存的 key（首次访问可能弹授权框，不能阻塞 EDT）
        keyField.setText("");
        loadKeyFromFile(provider);
        // 更新申请地址（只 URL 可点击，后面跟操作步骤）
        AIService.ProviderInfo info = AIService.PROVIDER_INFO.get(provider);
        if (info != null) {
            // 提取纯 URL 部分
            String url = info.registerUrl.split("→")[0].trim();
            currentUrl = url;
            // 操作步骤
            String rest = info.registerUrl.substring(info.registerUrl.indexOf("→"));
            helpLabel.setText("<html>申请地址：<a href='#' style='color:#2F6FAD;text-decoration:underline;'>" + url + "</a>" + rest + "</html>");
        } else {
            currentUrl = "";
            helpLabel.setText("");
        }
    }

    /** 异步加载指定厂商的 key 并回填输入框；回填时校验厂商未被切换，避免旧结果覆盖新选择 */
    private void loadKeyFromFile(String provider) {
        AIService.loadApiKeyAsync(provider).thenAccept(savedKey ->
                SwingUtilities.invokeLater(() -> {
                    if (provider.equals(providerCombo.getSelectedItem())) {
                        keyField.setText(savedKey);
                    }
                }));
    }

    private void refreshModelCombo() {
        String provider = (String) providerCombo.getSelectedItem();
        modelCombo.removeAllItems();
        List<AIService.ModelInfo> models = AIService.PROVIDER_MODELS.get(provider);
        if (models != null) {
            for (AIService.ModelInfo m : models) {
                modelCombo.addItem(m.fullDisplayName());
            }
        }
        if (modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0);
        }
    }

    private void restoreSelection(String savedModelId) {
        if (savedModelId == null || savedModelId.isEmpty()) {
            providerCombo.setSelectedIndex(0);
            onProviderChanged();
            return;
        }
        AIService.ModelInfo saved = AIService.MODELS.get(savedModelId);
        if (saved == null) {
            providerCombo.setSelectedIndex(0);
            onProviderChanged();
            return;
        }
        // 选中对应厂商（触发 ActionListener 联动 key 和 URL）
        for (int i = 0; i < providerCombo.getItemCount(); i++) {
            if (providerCombo.getItemAt(i).equals(saved.provider)) {
                providerCombo.setSelectedIndex(i);
                break;
            }
        }
        refreshModelCombo();
        // 确保 key 和 URL 同步
        onProviderChanged();
        // 最后选中对应模型（放在 onProviderChanged 之后，避免被 refreshModelCombo 重置）
        List<AIService.ModelInfo> models = AIService.PROVIDER_MODELS.get(saved.provider);
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(savedModelId)) {
                modelCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    private String getSelectedModelId() {
        String provider = (String) providerCombo.getSelectedItem();
        int idx = modelCombo.getSelectedIndex();
        List<AIService.ModelInfo> models = AIService.PROVIDER_MODELS.get(provider);
        if (models != null && idx >= 0 && idx < models.size()) {
            return models.get(idx).id;
        }
        return AIService.MODELS.keySet().iterator().next();
    }

    /** 读取 Key 输入框内容（JPasswordField 需经 getPassword 读取） */
    private String currentKeyInput() {
        char[] chars = keyField.getPassword();
        String text = new String(chars).trim();
        java.util.Arrays.fill(chars, '\0');
        return text;
    }

    private void testConnection() {
        String key = currentKeyInput();
        if (key.isEmpty()) {
            Messages.showWarningDialog("请输入 API Key", "测试连接失败");
            return;
        }

        new Thread(() -> {
            try {
                AIService service = new AIService(key, getSelectedModelId());
                String result = service.chatAsync("你是一个助手。", java.util.List.of("测试消息")).get();

                SwingUtilities.invokeLater(() -> {
                    if (result.contains("失败") || result.contains("错误") || result.contains("API错误")) {
                        Messages.showWarningDialog(result, "测试连接失败");
                    } else {
                        Messages.showInfoMessage("连接成功！\n\n" + result, "测试连接成功");
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                    Messages.showWarningDialog("连接测试失败: " + e.getMessage(), "错误"));
            }
        }).start();
    }

    public boolean isConfirmed() { return confirmed; }
    public String getApiKey() { return userApiKey; }
    public String getModelId() { return userModelId; }
}
package com.plugin.finance;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * AI 模型设置面板：厂商 / 模型（或自定义 BaseURL + 模型 ID）/ Key / 测试连接。
 * 供设置对话框（AISettingsDialog）与 IDE 设置页（AISettingsConfigurable）复用，避免两处维护。
 */
public class AISettingsPanel extends JPanel {

    /** Key 输入框边框：描边 + 左右内边距 */
    private static final javax.swing.border.Border INPUT_BORDER = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(0, 6));

    private final JComboBox<String> providerCombo;
    private final JComboBox<String> modelCombo;
    private final JPanel modelPanel;
    private final JTextField baseUrlField;
    private final JPanel baseUrlPanel;
    private final JTextField customModelField;
    private final JPanel customModelPanel;
    private final JPasswordField keyField;
    private final JBLabel helpLabel;
    private String currentUrl = "";
    /** 模式切换（内置/自定义）回调，宿主窗口可借此调整自身尺寸 */
    private Runnable modeChangeListener;

    public AISettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(JBUI.Borders.empty(14, 18));

        // 厂商
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
        providerPanel.add(providerCombo, BorderLayout.CENTER);
        add(providerPanel);

        add(Box.createVerticalStrut(10));

        // 模型（自定义渠道时隐藏，改用下方输入行）
        modelPanel = new JPanel(new BorderLayout(6, 0));
        modelPanel.setOpaque(false);
        JLabel modelLabel = new JLabel("模型：");
        modelLabel.setFont(JBFont.label());
        modelLabel.setPreferredSize(new Dimension(55, 28));
        modelPanel.add(modelLabel, BorderLayout.WEST);
        modelCombo = new JComboBox<>();
        modelCombo.setFont(JBFont.label().deriveFont(13f));
        modelCombo.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        modelCombo.setPreferredSize(new Dimension(0, 26));
        modelPanel.add(modelCombo, BorderLayout.CENTER);
        add(modelPanel);

        // 自定义渠道：接口地址 + 模型 ID 输入行（内置渠道时隐藏）。
        // 行距统一由厂商行后的 10px strut 和各行自身的 10px 顶边距承担，
        // 地址行不加顶边距——否则与厂商行后的 strut 叠加出 20px 宽间距
        baseUrlPanel = new JPanel(new BorderLayout(6, 0));
        baseUrlPanel.setOpaque(false);
        JLabel baseUrlLabel = new JLabel("地址：");
        baseUrlLabel.setFont(JBFont.label());
        baseUrlLabel.setPreferredSize(new Dimension(55, 28));
        baseUrlPanel.add(baseUrlLabel, BorderLayout.WEST);
        baseUrlField = new JTextField();
        baseUrlField.setFont(JBFont.label().deriveFont(13f));
        baseUrlField.putClientProperty("JTextField.placeholderText", "OpenAI 兼容地址，如 https://api.deepseek.com/v1");
        baseUrlField.setBorder(INPUT_BORDER);
        baseUrlField.setBackground(providerCombo.getBackground());
        baseUrlField.setPreferredSize(new Dimension(0, 26));
        baseUrlPanel.add(baseUrlField, BorderLayout.CENTER);
        baseUrlPanel.setVisible(false);
        add(baseUrlPanel);

        customModelPanel = new JPanel(new BorderLayout(6, 0));
        customModelPanel.setOpaque(false);
        customModelPanel.setBorder(JBUI.Borders.emptyTop(10));
        JLabel customModelLabel = new JLabel("模型：");
        customModelLabel.setFont(JBFont.label());
        customModelLabel.setPreferredSize(new Dimension(55, 28));
        customModelPanel.add(customModelLabel, BorderLayout.WEST);
        customModelField = new JTextField();
        customModelField.setFont(JBFont.label().deriveFont(13f));
        customModelField.putClientProperty("JTextField.placeholderText", "模型 ID，如 deepseek-chat / kimi-k3");
        customModelField.setBorder(INPUT_BORDER);
        customModelField.setBackground(providerCombo.getBackground());
        customModelField.setPreferredSize(new Dimension(0, 26));
        customModelPanel.add(customModelField, BorderLayout.CENTER);
        customModelPanel.setVisible(false);
        add(customModelPanel);

        add(Box.createVerticalStrut(10));

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
        keyField.setBorder(INPUT_BORDER);
        keyField.setBackground(providerCombo.getBackground());
        keyField.setPreferredSize(new Dimension(0, 26));
        keyPanel.add(keyField, BorderLayout.CENTER);
        add(keyPanel);

        add(Box.createVerticalStrut(10));

        // 注册提示 / 自定义说明 + 测试连接：同一行（左说明、右按钮），两种渠道模式结构一致。
        // （JBLabel 开启自动折行：文字随容器宽度换行，窄面板也不溢出）
        JPanel helpPanel = new JPanel(new BorderLayout(12, 0));
        helpPanel.setOpaque(false);
        helpLabel = new JBLabel();
        helpLabel.setAllowAutoWrapping(true);
        helpLabel.setFont(JBFont.label().deriveFont(11f));
        helpLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        helpPanel.add(helpLabel, BorderLayout.CENTER);
        JButton testBtn = new JButton("测试连接");
        testBtn.setFont(JBFont.label());
        testBtn.setPreferredSize(new Dimension(80, 28));
        testBtn.addActionListener(e -> testConnection());
        helpPanel.add(testBtn, BorderLayout.EAST);
        add(helpPanel);

        // 点击链接打开浏览器
        helpLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!currentUrl.isEmpty()) {
                    BrowserUtil.browse(currentUrl);
                }
            }
        });

        // 厂商变化时联动刷新
        providerCombo.addActionListener(e -> onProviderChanged());
    }

    /** 模式切换回调（宿主窗口调整尺寸 / 重新居中） */
    public void setModeChangeListener(Runnable listener) {
        this.modeChangeListener = listener;
    }

    private void onProviderChanged() {
        String provider = (String) providerCombo.getSelectedItem();
        updateCustomVisibility();
        if (AIService.isCustomProvider(provider)) {
            // 自定义渠道：回填上次保存的地址与模型 ID，提示语替代注册链接
            baseUrlField.setText(AIService.getCustomBaseUrl());
            customModelField.setText(AIService.getCustomModelName());
            currentUrl = "";
            helpLabel.setText("<html>支持任意 OpenAI 格式的 AI 服务，地址填到 /v1 即可，后缀自动补全<br>模型名按你的服务商填写；Key 只存在本机（系统钥匙串），不会上传</html>");
            keyField.setText("");
            loadKeyFromFile(provider);
            return;
        }
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

    private void updateCustomVisibility() {
        boolean custom = isCustomSelected();
        modelPanel.setVisible(!custom);
        baseUrlPanel.setVisible(custom);
        customModelPanel.setVisible(custom);
        if (modeChangeListener != null) {
            modeChangeListener.run();
        }
        revalidate();
        repaint();
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

    /** 恢复已保存的选择（厂商 → 模型 → 自定义字段），并异步回填该渠道的 Key */
    public void loadSelection(String savedModelId) {
        if (AIService.isCustomModel(savedModelId)) {
            // 触发 onProviderChanged：切换布局并回填上次保存的地址 / 模型 ID / Key
            providerCombo.setSelectedItem(AIService.CUSTOM_PROVIDER);
            return;
        }
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

    public boolean isCustomSelected() {
        return AIService.isCustomProvider((String) providerCombo.getSelectedItem());
    }

    public String getSelectedModelId() {
        if (isCustomSelected()) {
            return AIService.CUSTOM_MODEL_ID;
        }
        String provider = (String) providerCombo.getSelectedItem();
        int idx = modelCombo.getSelectedIndex();
        List<AIService.ModelInfo> models = AIService.PROVIDER_MODELS.get(provider);
        if (models != null && idx >= 0 && idx < models.size()) {
            return models.get(idx).id;
        }
        return AIService.MODELS.keySet().iterator().next();
    }

    public String getBaseUrlInput() {
        return baseUrlField.getText().trim();
    }

    public String getCustomModelInput() {
        return customModelField.getText().trim();
    }

    /** 读取 Key 输入框内容（JPasswordField 需经 getPassword 读取，读后清零字符数组） */
    public String getKeyInput() {
        char[] chars = keyField.getPassword();
        String text = new String(chars).trim();
        java.util.Arrays.fill(chars, '\0');
        return text;
    }

    /** 用当前面板输入测试连通性 */
    private void testConnection() {
        String key = getKeyInput();
        if (key.isEmpty()) {
            Messages.showWarningDialog("请输入 API Key", "测试连接失败");
            return;
        }

        new Thread(() -> {
            try {
                AIService service = isCustomSelected()
                        ? new AIService(key, AIService.CUSTOM_MODEL_ID, getBaseUrlInput())
                        : new AIService(key, getSelectedModelId());
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
}

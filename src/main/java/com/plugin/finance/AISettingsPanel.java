package com.plugin.finance;

import com.google.gson.Gson;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * AI 设置面板：多份独立配置（名称 / 地址 / 模型 / Key）+ 预设模板，下拉选中项保存后即为当前使用的模型。
 * 每份配置的 Key 按配置 ID 单独存 PasswordSafe，互不覆盖。
 * 供设置对话框（AISettingsDialog）与 IDE 设置页（AISettingsConfigurable）复用。
 */
public class AISettingsPanel extends JPanel {

    private static final javax.swing.border.Border INPUT_BORDER = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(0, 6));
    private static final Gson GSON = new Gson();
    private static final JBColor STATUS_PENDING =
            new JBColor(new Color(0x2F6FAD), new Color(0x8AB4F8));
    private static final JBColor STATUS_OK =
            new JBColor(new Color(0x1E7A3C), new Color(0x81C995));
    private static final JBColor STATUS_FAIL =
            new JBColor(new Color(0xB00020), new Color(0xF28B82));

    private final JComboBox<AIService.AiProfile> profileCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField();
    private final JTextField baseUrlField = new JTextField();
    private final JTextField modelField = new JTextField();
    private final JPasswordField keyField = new JPasswordField();
    private final JBLabel helpLabel = new JBLabel();
    private final JButton testBtn = new JButton("测试连接");

    /** 面板内可编辑的配置副本；点「保存」前不影响运行时 */
    private List<AIService.AiProfile> profiles = new ArrayList<>();
    /** profileId → Key 文本；切换配置时暂存，避免异步回填互相覆盖 */
    private final Map<String, String> keyInputs = new HashMap<>();
    /** 用户真正改动过 Key 的配置 ID，供 isModified 判断（程序化回填不算改动） */
    private final Set<String> keyDirty = new HashSet<>();
    /** 被删除配置的 Key 槽位，保存时一并从钥匙串清除 */
    private final Set<String> removedSlots = new HashSet<>();
    private String activeId = "";
    private String currentUrl = "";
    /** 当前应显示的帮助 HTML；测试期间被状态文字临时顶替，结束后按情况恢复 */
    private String helpHtml = "";
    /** 测试连接进行中：禁用按钮并抑制帮助文本覆盖状态文字 */
    private boolean testing = false;
    private Timer testTimer;
    /** 当前表单正在编辑的配置对象（切换时表单值仍是上一份的，需先写回） */
    private AIService.AiProfile editing;
    /** 程序化改动表单时为 true，抑制监听器写回与标脏 */
    private boolean switching = false;

    public AISettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(JBUI.Borders.empty(14, 18));

        add(labeledRow("配置：", buildProfileRow()));
        add(Box.createVerticalStrut(10));
        add(labeledRow("名称：", nameField));
        add(Box.createVerticalStrut(10));
        add(labeledRow("地址：", baseUrlField));
        add(Box.createVerticalStrut(10));
        add(labeledRow("模型：", modelField));
        add(Box.createVerticalStrut(10));
        add(labeledRow("Key：", keyField));
        add(Box.createVerticalStrut(10));

        JPanel helpPanel = new JPanel(new BorderLayout(12, 0));
        helpPanel.setOpaque(false);
        helpLabel.setAllowAutoWrapping(true);
        helpLabel.setFont(JBFont.label().deriveFont(11f));
        helpPanel.add(helpLabel, BorderLayout.CENTER);
        testBtn.setFont(JBFont.label());
        testBtn.setPreferredSize(new Dimension(80, 28));
        helpPanel.add(testBtn, BorderLayout.EAST);
        add(helpPanel);

        // 先按存储载入，再注册监听器，避免初始回填触发误写回
        resetFromStorage();

        profileCombo.addActionListener(e -> onProfileChanged());
        testBtn.addActionListener(e -> testConnection());
        keyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onKeyEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onKeyEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onKeyEdited(); }
        });
        helpLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!testing && !currentUrl.isEmpty()) {
                    BrowserUtil.browse(currentUrl);
                }
            }
        });
    }

    private JComponent buildProfileRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        profileCombo.setFont(JBFont.label().deriveFont(13f));
        profileCombo.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        profileCombo.setPreferredSize(new Dimension(0, 26));
        profileCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof AIService.AiProfile) {
                    setText(((AIService.AiProfile) value).displayName());
                }
                return this;
            }
        });
        row.add(profileCombo, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        JButton addBtn = new JButton("新建");
        JButton deleteBtn = new JButton("删除");
        for (JButton b : new JButton[]{addBtn, deleteBtn}) {
            b.setFont(JBFont.label().deriveFont(12f));
            b.setPreferredSize(new Dimension(56, 26));
        }
        addBtn.setToolTipText("从预设模板新建一份独立配置");
        deleteBtn.setToolTipText("删除当前配置（至少保留一份）");
        addBtn.addActionListener(e -> addProfile());
        deleteBtn.addActionListener(e -> removeProfile());
        buttons.add(addBtn);
        buttons.add(deleteBtn);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private JPanel labeledRow(String label, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(JBFont.label());
        l.setPreferredSize(new Dimension(55, 28));
        panel.add(l, BorderLayout.WEST);
        if (field instanceof JTextField) {
            field.setFont(JBFont.label().deriveFont(13f));
            ((JTextField) field).setBorder(INPUT_BORDER);
            field.setPreferredSize(new Dimension(0, 26));
        }
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void initFieldHints() {
        baseUrlField.putClientProperty("JTextField.placeholderText",
                "OpenAI 兼容地址，如 https://api.deepseek.com/v1");
        modelField.putClientProperty("JTextField.placeholderText",
                "模型 ID，如 deepseek-chat / kimi-k3");
        keyField.setColumns(18);
    }

    /** 兼容旧调用：确保选中指定配置；实际数据源以应用级配置为准 */
    public void loadSelection(String activeProfileId) {
        if (activeProfileId == null || activeProfileId.isEmpty()) {
            return;
        }
        boolean exists = false;
        for (AIService.AiProfile p : profiles) {
            if (p.id.equals(activeProfileId)) {
                exists = true;
                break;
            }
        }
        // 外部传入的可能是迁移前的旧模型 ID，忽略它，保持从存储读到的启用项
        if (!exists) {
            return;
        }
        activeId = activeProfileId;
        selectProfile(activeProfileId);
        loadSelected();
    }

    /** 从应用级配置重新载入（设置页 Reset 用） */
    public void resetFromStorage() {
        initFieldHints();
        AIService.invalidateProfileCache();
        profiles = AIService.copyProfiles();
        activeId = AIService.getActiveProfileId();
        keyInputs.clear();
        keyDirty.clear();
        removedSlots.clear();
        editing = null;
        refreshCombo();
        selectProfile(activeId);
        loadSelected();
    }

    private void refreshCombo() {
        switching = true;
        profileCombo.removeAllItems();
        for (AIService.AiProfile p : profiles) {
            profileCombo.addItem(p);
        }
        switching = false;
    }

    private void selectProfile(String id) {
        switching = true;
        for (int i = 0; i < profileCombo.getItemCount(); i++) {
            AIService.AiProfile p = profileCombo.getItemAt(i);
            if (p != null && p.id.equals(id)) {
                profileCombo.setSelectedIndex(i);
                break;
            }
        }
        switching = false;
    }

    private AIService.AiProfile selectedProfile() {
        Object item = profileCombo.getSelectedItem();
        return item instanceof AIService.AiProfile ? (AIService.AiProfile) item : null;
    }

    private void onProfileChanged() {
        if (switching) {
            return;
        }
        // 事件触发时表单里还是上一份配置的内容，先写回再切换
        storeCurrentInto(editing);
        AIService.AiProfile p = selectedProfile();
        if (p == null) {
            return;
        }
        activeId = p.id;
        loadSelected();
    }

    private void loadSelected() {
        AIService.AiProfile p = selectedProfile();
        if (p == null) {
            return;
        }
        editing = p;
        switching = true;
        nameField.setText(p.name);
        baseUrlField.setText(p.baseUrl);
        modelField.setText(p.model);
        keyField.setText(keyInputs.containsKey(p.id)
                ? keyInputs.get(p.id) : AIService.getCachedApiKey(p.keySlot()));
        switching = false;
        updateHelp(p);
        loadKeyAsync(p);
    }

    /** 首次访问系统钥匙串可能弹授权框，异步回填；用户已输入或已切换配置时不覆盖 */
    private void loadKeyAsync(AIService.AiProfile p) {
        if (keyInputs.containsKey(p.id)) {
            return;
        }
        AIService.loadApiKeyAsync(p.keySlot()).thenAccept(key ->
                SwingUtilities.invokeLater(() -> {
                    AIService.AiProfile cur = selectedProfile();
                    if (cur == null || !cur.id.equals(p.id) || keyInputs.containsKey(p.id)) {
                        return;
                    }
                    if (new String(keyField.getPassword()).trim().isEmpty()) {
                        switching = true;
                        keyField.setText(key);
                        switching = false;
                    }
                }));
    }

    private void onKeyEdited() {
        if (switching) {
            return;
        }
        AIService.AiProfile p = selectedProfile();
        if (p == null) {
            return;
        }
        keyInputs.put(p.id, new String(keyField.getPassword()).trim());
        keyDirty.add(p.id);
    }

    /** 把当前表单值写回指定配置（通常是刚切走的那份） */
    private void storeCurrentInto(AIService.AiProfile target) {
        if (target == null) {
            return;
        }
        target.name = nameField.getText().trim();
        target.baseUrl = baseUrlField.getText().trim();
        target.model = modelField.getText().trim();
        keyInputs.put(target.id, getKeyInput());
    }

    private void updateHelp(AIService.AiProfile p) {
        String html;
        if (p.registerUrl != null && !p.registerUrl.isEmpty()) {
            String url = p.registerUrl.split("→")[0].trim();
            String rest = p.registerUrl.contains("→")
                    ? p.registerUrl.substring(p.registerUrl.indexOf("→")) : "";
            currentUrl = url;
            html = "<html>申请地址：<a href='#' style='color:#2F6FAD;text-decoration:underline;'>"
                    + escapeHtml(url) + "</a>" + escapeHtml(rest) + "</html>";
        } else {
            currentUrl = "";
            html = "<html>支持任意 OpenAI 兼容服务，地址填到 /v1 即可（后缀自动补全）<br>"
                    + "下拉中选中的配置保存后即为当前使用的模型；Key 只存本机钥匙串</html>";
        }
        helpHtml = html;
        // 测试过程中的状态文字不能被帮助文本覆盖，结束后由用户操作自然恢复
        if (!testing) {
            helpLabel.setText(helpHtml);
            helpLabel.setCursor(currentUrl.isEmpty()
                    ? Cursor.getDefaultCursor() : new Cursor(Cursor.HAND_CURSOR));
        }
    }

    private void addProfile() {
        String[] names = new String[AIService.PRESETS.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = AIService.PRESETS.get(i).name;
        }
        Object chosen = JOptionPane.showInputDialog(this,
                "选择预设模板（地址、模型、名称随后都可修改）：", "新建配置",
                JOptionPane.PLAIN_MESSAGE, null, names, names[names.length - 1]);
        if (chosen == null) {
            return;
        }
        AIService.Preset preset = null;
        for (AIService.Preset p : AIService.PRESETS) {
            if (p.name.equals(chosen)) {
                preset = p;
                break;
            }
        }
        if (preset == null) {
            return;
        }
        storeCurrentInto(editing);
        AIService.AiProfile p = preset.newProfile();
        // 默认名直接用模型 ID（与迁移、首次初始化一致），不带渠道前缀；名称栏仍可自由修改
        p.name = uniqueName(AIService.defaultProfileName(p.model));
        profiles.add(p);
        keyInputs.put(p.id, "");
        refreshCombo();
        activeId = p.id;
        selectProfile(p.id);
        loadSelected();
        keyField.requestFocus();
    }

    private String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (true) {
            boolean taken = false;
            for (AIService.AiProfile p : profiles) {
                if (name.equals(p.name)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return name;
            }
            name = base + " " + n++;
        }
    }

    private void removeProfile() {
        if (profiles.size() <= 1) {
            Messages.showWarningDialog("至少需要保留一份配置", "无法删除");
            return;
        }
        AIService.AiProfile p = selectedProfile();
        if (p == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定删除配置「" + p.displayName() + "」？", "删除配置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }
        removedSlots.add(p.keySlot());
        keyInputs.remove(p.id);
        keyDirty.remove(p.id);
        profiles.remove(p);
        editing = null;
        refreshCombo();
        activeId = profiles.get(0).id;
        selectProfile(activeId);
        loadSelected();
    }

    /** 保存前校验：所有配置都必须填全地址、模型与名称 */
    public String validationError() {
        storeCurrentInto(editing);
        if (profiles.isEmpty()) {
            return "请至少保留一份配置";
        }
        for (AIService.AiProfile p : profiles) {
            if (p.name.trim().isEmpty()) {
                return "配置名称不能为空";
            }
            if (p.baseUrl.trim().isEmpty()) {
                return "配置「" + p.displayName() + "」未填写接口地址";
            }
            if (p.model.trim().isEmpty()) {
                return "配置「" + p.displayName() + "」未填写模型 ID";
            }
        }
        return null;
    }

    /** 校验通过后把配置列表、启用项与各自的 Key 落盘 */
    public void applyToStorage() {
        storeCurrentInto(editing);
        for (String slot : removedSlots) {
            AIService.saveConfig(slot, "");
        }
        Map<String, String> slotKeys = new HashMap<>();
        for (AIService.AiProfile p : profiles) {
            String key = keyInputs.get(p.id);
            if (key != null) {
                slotKeys.put(p.keySlot(), key);
            }
        }
        AIService.saveProfiles(profiles, getActiveProfileId(), slotKeys);
        keyDirty.clear();
        removedSlots.clear();
    }

    public String getActiveProfileId() {
        AIService.AiProfile p = selectedProfile();
        return p != null ? p.id : activeId;
    }

    public String getActiveApiKey() {
        return getKeyInput();
    }

    /** JPasswordField 需经 getPassword 读取，读后清零字符数组 */
    public String getKeyInput() {
        char[] chars = keyField.getPassword();
        String text = new String(chars).trim();
        java.util.Arrays.fill(chars, '\0');
        return text;
    }

    /** 快照用于设置页 isModified：包含表单里的字段值、启用项、以及用户改动过的 Key 集合 */
    public String getSnapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        AIService.AiProfile selected = selectedProfile();
        List<AIService.AiProfile> copy = new ArrayList<>();
        for (AIService.AiProfile p : profiles) {
            AIService.AiProfile c = new AIService.AiProfile();
            c.id = p.id;
            c.name = p.name;
            c.baseUrl = p.baseUrl;
            c.model = p.model;
            c.temperature = p.temperature;
            c.reasoningEffortNone = p.reasoningEffortNone;
            c.registerUrl = p.registerUrl;
            if (selected != null && c.id.equals(selected.id)) {
                c.name = nameField.getText().trim();
                c.baseUrl = baseUrlField.getText().trim();
                c.model = modelField.getText().trim();
            }
            copy.add(c);
        }
        snap.put("active", getActiveProfileId());
        snap.put("profiles", copy);
        snap.put("keyDirty", new TreeSet<>(keyDirty));
        return GSON.toJson(snap);
    }

    /** 用当前表单内容测试连通性：测「正在编辑的」，而不是已保存的配置。
     *  请求期间禁用按钮并在帮助区显示带耗时的进度，结束后就地给出结果摘要，避免长时间无反馈。 */
    private void testConnection() {
        if (testing) {
            return;
        }
        storeCurrentInto(editing);
        String key = getKeyInput();
        if (key.isEmpty()) {
            Messages.showWarningDialog("请输入 API Key", "测试连接失败");
            return;
        }
        AIService.AiProfile p = selectedProfile();
        if (p == null || p.baseUrl.trim().isEmpty() || p.model.trim().isEmpty()) {
            Messages.showWarningDialog("请填写接口地址与模型 ID", "测试连接失败");
            return;
        }
        // 在 EDT 上取好表单快照并构造服务实例，后台线程只持有不可变对象
        final AIService service = new AIService(key, p);
        final String modelName = p.model;
        final long startAt = System.currentTimeMillis();

        testing = true;
        testBtn.setEnabled(false);
        testBtn.setText("测试中…");
        testBtn.setToolTipText("正在请求模型，最长读取超时 60 秒");
        helpLabel.setCursor(Cursor.getDefaultCursor());
        helpLabel.setText(pendingHtml(modelName, 0));
        testTimer = new Timer(1000, ev -> {
            long waited = (System.currentTimeMillis() - startAt) / 1000;
            helpLabel.setText(pendingHtml(modelName, waited));
        });
        testTimer.start();

        new Thread(() -> {
            String result = null;
            String failure = null;
            try {
                result = service.chatAsync("你是一个助手。", List.of("测试消息")).get();
            } catch (Exception e) {
                Throwable cause = (e instanceof java.util.concurrent.ExecutionException
                        && e.getCause() != null) ? e.getCause() : e;
                failure = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            }
            final String finalResult = result;
            final String finalFailure = failure;
            SwingUtilities.invokeLater(() ->
                    finishTest(startAt, finalResult, finalFailure));
        }, "AI-Settings-TestConnection").start();
    }

    /** 回到 EDT：停计时器、恢复按钮、就地显示结果摘要；面板已关闭时不再弹窗打扰 */
    private void finishTest(long startAt, String result, String failure) {
        if (testTimer != null) {
            testTimer.stop();
            testTimer = null;
        }
        testing = false;
        testBtn.setEnabled(true);
        testBtn.setText("测试连接");
        testBtn.setToolTipText(null);
        helpLabel.setCursor(Cursor.getDefaultCursor());
        long seconds = (System.currentTimeMillis() - startAt) / 1000;
        boolean ok = failure == null && result != null
                && !result.contains("失败") && !result.contains("错误") && !result.contains("API错误");
        if (ok) {
            String summary = brief(result, 50);
            helpLabel.setText("<html><b style='color:" + colorHex(STATUS_OK) + ";'>连接成功</b>"
                    + "（" + seconds + " 秒）"
                    + (summary.isEmpty() ? "" : "　回复：" + escapeHtml(summary)) + "</html>");
            if (isShowing()) {
                Messages.showInfoMessage("连接成功！\n\n" + result, "测试连接成功");
            }
        } else {
            String reason = failure != null ? failure : (result == null ? "无响应" : result);
            helpLabel.setText("<html><b style='color:" + colorHex(STATUS_FAIL) + ";'>连接失败</b>"
                    + "（" + seconds + " 秒）　" + escapeHtml(brief(reason, 70)) + "</html>");
            if (isShowing()) {
                Messages.showWarningDialog(reason, "测试连接失败");
            }
        }
    }

    private static String pendingHtml(String modelName, long seconds) {
        String hint = seconds < 3 ? "正在连接服务…"
                : seconds < 15 ? "已连上，正在等待模型首个响应…"
                : "模型响应较慢，仍在等待（读取超时 60 秒）…";
        return "<html><b style='color:" + colorHex(STATUS_PENDING) + ";'>正在测试 "
                + escapeHtml(modelName) + "</b>　" + hint + "　已等待 " + seconds + " 秒</html>";
    }

    /** 取当前主题下的实际颜色分量，保证明暗主题里状态文字都可读 */
    private static String colorHex(JBColor color) {
        return String.format("#%02X%02X%02X",
                color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}

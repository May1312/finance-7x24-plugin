package com.plugin.finance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AIService {

    /** 采样温度默认值；个别模型只接受特定值（如 kimi-k3 仅允许 1），预设里单独指定 */
    private static final double DEFAULT_TEMPERATURE = 0.3;

    /** 新建配置时的一键填充模板。内置渠道已不再是运行时注册表：
     *  所有可用服务都存成 {@link AiProfile}，可新增多份、随时切换、任意改名与改地址。 */
    public static class Preset {
        public final String name;
        public final String baseUrl;
        public final String model;
        public final double temperature;
        public final boolean reasoningEffortNone;
        public final String registerUrl;
        /** 旧版 Key 存在哪个厂商槽位下，仅迁移时用 */
        public final String legacyProvider;

        Preset(String name, String baseUrl, String model, double temperature,
               boolean reasoningEffortNone, String registerUrl, String legacyProvider) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.model = model;
            this.temperature = temperature;
            this.reasoningEffortNone = reasoningEffortNone;
            this.registerUrl = registerUrl;
            this.legacyProvider = legacyProvider;
        }

        /** 按模板生成一份独立配置（每次新建都换 id，互不干扰） */
        public AiProfile newProfile() {
            AiProfile p = new AiProfile();
            p.id = newProfileId();
            p.name = name;
            p.baseUrl = baseUrl;
            p.model = model;
            p.temperature = temperature;
            p.reasoningEffortNone = reasoningEffortNone;
            p.registerUrl = registerUrl;
            return p;
        }
    }

    /** 一份 AI 服务配置。API Key 不写进 JSON，按 {@link #keySlot()} 单独存 PasswordSafe，
     *  因此多个平台 / 多个模型的 Key 不会互相覆盖。 */
    public static class AiProfile {
        public String id = "";
        public String name = "";
        /** 用户填写的原始地址，末尾 /chat/completions 由 normalizeBaseUrl 自动补全 */
        public String baseUrl = "";
        /** 请求体里的真实模型 ID */
        public String model = "";
        public double temperature = DEFAULT_TEMPERATURE;
        /** 仅商汤 SenseNova 需要 reasoning_effort=none，其他平台带上会报错 */
        public boolean reasoningEffortNone = false;
        /** 申请 Key 的提示文案，仅用于设置页说明 */
        public String registerUrl = "";
        /** 旧版 Key 所在的凭据槽；首次读取该配置时懒迁移，避免启动时同步访问钥匙串 */
        public String legacyKeySlot = "";

        public String keySlot() {
            return KEY_SLOT_PREFIX + id;
        }

        public String displayName() {
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return model == null || model.isEmpty() ? "未命名配置" : model;
        }
    }

    /** 预设模板：只在「新建配置」时用于填充，界面上不再是写死的内置渠道 */
    public static final List<Preset> PRESETS = new ArrayList<>();

    // ── 配置持久化键 ──
    private static final String PROFILES_KEY = "finance.ai.profiles";
    private static final String ACTIVE_PROFILE_KEY = "finance.ai.profile";
    /** 一次性规范化标记：清理旧版渠道名遗留的「自定义 · 」前缀 */
    private static final String NAME_PREFIX_CLEANED_KEY = "finance.ai.names.cleaned";
    private static final String LEGACY_MODEL_KEY = "finance.ai.model";
    private static final String LEGACY_BASEURL_KEY = "finance.ai.custom.baseurl";
    private static final String LEGACY_MODEL_NAME_KEY = "finance.ai.custom.model";
    /** 配置 Key 在 PasswordSafe 中的凭据槽前缀，与旧版「厂商名」槽位区分 */
    private static final String KEY_SLOT_PREFIX = "profile:";
    /** 旧版自定义渠道 Key 的槽名与模型哨兵，仅迁移时用 */
    private static final String CUSTOM_PROVIDER = "自定义 (OpenAI 兼容)";
    private static final String CUSTOM_MODEL_ID = "custom";

    private static final String SENSENOVA_URL = "https://token.sensenova.cn/v1";
    private static final String SENSENOVA_REGISTER = "https://platform.sensenova.cn  →  API Keys → 新建 API Key";
    private static final String SENSENOVA_PROVIDER = "商汤 SenseNova";
    private static final String ZHIPU_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String ZHIPU_REGISTER = "https://open.bigmodel.cn  →  API Keys → 新建 API Key";
    private static final String ZHIPU_PROVIDER = "智谱 GLM";

    static {
        PRESETS.add(new Preset("商汤 · DeepSeek V4 Flash", SENSENOVA_URL, "deepseek-v4-flash",
                DEFAULT_TEMPERATURE, true, SENSENOVA_REGISTER, SENSENOVA_PROVIDER));
        PRESETS.add(new Preset("商汤 · 6.8 Flash-Lite", SENSENOVA_URL, "sensenova-6.8-flash-lite",
                DEFAULT_TEMPERATURE, true, SENSENOVA_REGISTER, SENSENOVA_PROVIDER));
        PRESETS.add(new Preset("商汤 · GLM-5.2", SENSENOVA_URL, "glm-5.2",
                DEFAULT_TEMPERATURE, true, SENSENOVA_REGISTER, SENSENOVA_PROVIDER));
        PRESETS.add(new Preset("商汤 · Kimi K3", SENSENOVA_URL, "kimi-k3",
                1.0, true, SENSENOVA_REGISTER, SENSENOVA_PROVIDER));
        PRESETS.add(new Preset("智谱 · GLM-4-Flash", ZHIPU_URL, "glm-4-flash",
                DEFAULT_TEMPERATURE, false, ZHIPU_REGISTER, ZHIPU_PROVIDER));
        // 空白模板：任意 OpenAI 兼容服务都由用户自己填写
        PRESETS.add(new Preset("空白配置", "", "", DEFAULT_TEMPERATURE, false, "", ""));
    }

    private static String newProfileId() {
        return "p" + System.currentTimeMillis() + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    // ── API Key 持久化 ──
    // Key 存入 IDE 的 PasswordSafe（macOS 钥匙串 / Windows 凭据管理器 / KeePass），不再明文落盘。
    // 旧版本明文存于 ~/.finance7x24_ai_config.json，首次读取时自动迁移并从该文件中剔除。

    private static final String LEGACY_CONFIG_FILE = System.getProperty("user.home") + "/.finance7x24_ai_config.json";
    private static final String CREDENTIAL_SUBSYSTEM = "Finance7x24AI";
    private static final Gson GSON = new Gson();
    /** 缓存已读取的 Key，避免每次刷新都访问系统钥匙串（EDT 上可能较慢） */
    private static final Map<String, String> KEY_CACHE = new ConcurrentHashMap<>();

    private static CredentialAttributes credentialAttributes(String provider) {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(CREDENTIAL_SUBSYSTEM, provider), provider);
    }

    /** 保存指定厂商的 API Key；传入空串等于清除该厂商的凭据 */
    public static void saveConfig(String provider, String apiKey) {
        if (provider == null || provider.isEmpty()) {
            return;
        }
        String key = apiKey == null ? "" : apiKey;
        try {
            PasswordSafe.getInstance().set(credentialAttributes(provider),
                    key.isEmpty() ? null : new Credentials(provider, key));
            KEY_CACHE.put(provider, key);
        } catch (Exception e) {
            PluginLog.error("[AIService] 保存 API Key 失败: " + e.getMessage(), e);
        }
    }

    /** 加载指定厂商的 API Key；旧版明文配置在此一次性迁移到 PasswordSafe */
    public static String loadApiKey(String provider) {
        if (provider == null || provider.isEmpty()) {
            return "";
        }
        String cached = KEY_CACHE.get(provider);
        if (cached != null) {
            return cached;
        }
        String key = "";
        try {
            String stored = PasswordSafe.getInstance().getPassword(credentialAttributes(provider));
            if (stored != null) {
                key = stored;
            }
        } catch (Exception e) {
            PluginLog.error("[AIService] 读取 API Key 失败: " + e.getMessage(), e);
        }
        if (key.isEmpty()) {
            // 新配置槽位：把旧版单份 Key 懒迁移过来，避免在 EDT 上同步访问钥匙串
            key = provider.startsWith(KEY_SLOT_PREFIX)
                    ? migrateProfileKey(provider) : migrateLegacyKey(provider);
        }
        KEY_CACHE.put(provider, key);
        return key;
    }

    /** 异步加载 API Key：首次访问系统钥匙串可能弹授权框或耗时数百毫秒，不应在 EDT 上同步执行。
     *  已缓存时立即返回已完成的 future，调用方仍可统一走回调。 */
    public static CompletableFuture<String> loadApiKeyAsync(String provider) {
        if (provider == null || provider.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        String cached = KEY_CACHE.get(provider);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> loadApiKey(provider), SHARED_EXECUTOR);
    }

    /** 仅读内存缓存（不触发钥匙串访问），供设置页 isModified 做轻量比较 */
    public static String getCachedApiKey(String provider) {
        String cached = KEY_CACHE.get(provider);
        return cached == null ? "" : cached;
    }

    // ── 多配置持久化 ──

    private static final Gson PROFILE_GSON = new Gson();
    private static final java.lang.reflect.Type PROFILE_LIST_TYPE =
            new com.google.gson.reflect.TypeToken<List<AiProfile>>() {}.getType();
    private static volatile List<AiProfile> PROFILE_CACHE = null;
    private static volatile boolean MIGRATION_DONE = false;

    /** 读取全部配置；首次访问时自动迁移旧版单份配置，并保证至少有一份 */
    public static synchronized List<AiProfile> getProfiles() {
        if (!MIGRATION_DONE) {
            migrateLegacyProfiles();
            MIGRATION_DONE = true;
        }
        if (PROFILE_CACHE == null) {
            String json = PropertiesComponent.getInstance().getValue(PROFILES_KEY, "");
            List<AiProfile> parsed = null;
            try {
                if (!json.isEmpty()) {
                    parsed = PROFILE_GSON.fromJson(json, PROFILE_LIST_TYPE);
                }
            } catch (Exception e) {
                PluginLog.error("[AIService] 读取 AI 配置失败: " + e.getMessage(), e);
            }
            if (parsed == null || parsed.isEmpty()) {
                AiProfile blank = blankProfile();
                parsed = new ArrayList<>();
                parsed.add(blank);
                writeProfiles(parsed, blank.id);
            }
            for (AiProfile p : parsed) {
                if (p.id == null || p.id.isEmpty()) {
                    p.id = newProfileId();
                }
            }
            normalizeProfileNames(parsed);
            PROFILE_CACHE = parsed;
        }
        return PROFILE_CACHE;
    }

    private static AiProfile blankProfile() {
        AiProfile p = PRESETS.get(PRESETS.size() - 1).newProfile();
        p.name = defaultProfileName("");
        return p;
    }

    /** 配置默认名直接用模型 ID：新版已无「内置 / 自定义」渠道之分，不需要渠道前缀 */
    public static String defaultProfileName(String modelId) {
        return modelId == null || modelId.trim().isEmpty() ? "新配置" : modelId.trim();
    }

    /** 一次性清掉历史遗留的「自定义 · 」前缀（旧版渠道名 + 上一版默认命名生成）。
     *  只匹配精确前缀，用户自己起的名字不受影响；清理后写回并打标记，不会反复执行。 */
    private static void normalizeProfileNames(List<AiProfile> profiles) {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        if ("true".equals(pc.getValue(NAME_PREFIX_CLEANED_KEY, "false"))) {
            return;
        }
        final String prefix = "自定义 · ";
        boolean changed = false;
        for (AiProfile p : profiles) {
            if (p.name != null && p.name.startsWith(prefix)) {
                String rest = p.name.substring(prefix.length()).trim();
                p.name = rest.isEmpty() ? defaultProfileName(p.model) : rest;
                changed = true;
            }
        }
        if (changed) {
            pc.setValue(PROFILES_KEY, PROFILE_GSON.toJson(profiles, PROFILE_LIST_TYPE));
            PluginLog.info("[AIService] 已清理配置名中遗留的「自定义 · 」前缀");
        }
        pc.setValue(NAME_PREFIX_CLEANED_KEY, "true");
    }

    /** 供设置面板编辑用的深拷贝，改动不会直接污染运行时缓存 */
    public static List<AiProfile> copyProfiles() {
        return PROFILE_GSON.fromJson(
                PROFILE_GSON.toJson(getProfiles(), PROFILE_LIST_TYPE), PROFILE_LIST_TYPE);
    }

    /** 当前启用配置；active id 失效时退回第一份 */
    public static AiProfile getActiveProfile() {
        List<AiProfile> profiles = getProfiles();
        String activeId = PropertiesComponent.getInstance().getValue(ACTIVE_PROFILE_KEY, "");
        for (AiProfile p : profiles) {
            if (p.id.equals(activeId)) {
                return p;
            }
        }
        return profiles.get(0);
    }

    public static String getActiveProfileId() {
        return getActiveProfile().id;
    }

    public static AiProfile findProfile(String id) {
        for (AiProfile p : getProfiles()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return getActiveProfile();
    }

    /** 丢弃进程内缓存，下次读取强制从应用级配置重解析（设置页 Reset 用） */
    public static void invalidateProfileCache() {
        PROFILE_CACHE = null;
    }

    /** 保存配置列表与当前启用项；keys 为 keySlot → API Key，逐份写入 PasswordSafe */
    public static synchronized void saveProfiles(List<AiProfile> profiles, String activeId,
                                                  Map<String, String> keys) {
        List<AiProfile> copy = new ArrayList<>();
        if (profiles != null) {
            for (AiProfile p : profiles) {
                if (p == null) continue;
                // 逐字段拷贝：运行时缓存不能和设置面板持有同一批对象，
                // 否则设置页 Apply 后继续编辑表单会直接改动正在使用的配置
                AiProfile c = new AiProfile();
                c.id = p.id == null || p.id.isEmpty() ? newProfileId() : p.id;
                c.name = p.name == null ? "" : p.name;
                c.baseUrl = p.baseUrl == null ? "" : p.baseUrl;
                c.model = p.model == null ? "" : p.model;
                c.registerUrl = p.registerUrl == null ? "" : p.registerUrl;
                c.legacyKeySlot = p.legacyKeySlot == null ? "" : p.legacyKeySlot;
                c.temperature = p.temperature > 0 ? p.temperature : DEFAULT_TEMPERATURE;
                c.reasoningEffortNone = p.reasoningEffortNone;
                copy.add(c);
            }
        }
        if (copy.isEmpty()) {
            copy.add(blankProfile());
        }
        String chosen = activeId;
        boolean found = false;
        for (AiProfile p : copy) {
            if (p.id.equals(chosen)) {
                found = true;
                break;
            }
        }
        if (!found) {
            chosen = copy.get(0).id;
        }
        if (keys != null) {
            for (Map.Entry<String, String> entry : keys.entrySet()) {
                saveConfig(entry.getKey(), entry.getValue());
            }
        }
        writeProfiles(copy, chosen);
        PROFILE_CACHE = copy;
        MIGRATION_DONE = true;
    }

    private static void writeProfiles(List<AiProfile> profiles, String activeId) {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        pc.setValue(PROFILES_KEY, PROFILE_GSON.toJson(profiles, PROFILE_LIST_TYPE));
        pc.setValue(ACTIVE_PROFILE_KEY, activeId);
    }

    /** 旧版：单份自定义渠道 + 一个内置模型 ID。首次读取时转成配置列表；
     *  旧 Key 只记下原槽位，等真正读 Key 时再懒迁移，避免启动时同步访问钥匙串卡住 EDT。 */
    private static void migrateLegacyProfiles() {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        if (!pc.getValue(PROFILES_KEY, "").isEmpty()) {
            return;
        }
        List<AiProfile> migrated = new ArrayList<>();
        String activeId = "";
        String legacyBase = pc.getValue(LEGACY_BASEURL_KEY, "");
        String legacyModel = pc.getValue(LEGACY_MODEL_NAME_KEY, "");
        String legacySelected = pc.getValue(LEGACY_MODEL_KEY, "");

        if (!legacyModel.isEmpty()) {
            AiProfile p = blankProfile();
            p.name = defaultProfileName(legacyModel);
            p.baseUrl = legacyBase;
            p.model = legacyModel;
            p.legacyKeySlot = CUSTOM_PROVIDER;
            migrated.add(p);
            if (CUSTOM_MODEL_ID.equals(legacySelected) || legacySelected.isEmpty()) {
                activeId = p.id;
            }
        }
        if (!legacySelected.isEmpty() && !CUSTOM_MODEL_ID.equals(legacySelected)) {
            for (Preset preset : PRESETS) {
                if (preset.model.equals(legacySelected)) {
                    AiProfile p = preset.newProfile();
                    p.legacyKeySlot = preset.legacyProvider;
                    migrated.add(p);
                    activeId = p.id;
                    break;
                }
            }
        }
        if (migrated.isEmpty()) {
            AiProfile p = blankProfile();
            migrated.add(p);
            activeId = p.id;
        }
        writeProfiles(migrated, activeId);
        PROFILE_CACHE = migrated;
        PluginLog.info("[AIService] 旧版 AI 配置已迁移为 " + migrated.size() + " 份独立配置");
    }

    /** 配置槽位读不到 Key 时，从旧版厂商槽位懒迁移（发生在后台的 loadApiKeyAsync 里） */
    private static String migrateProfileKey(String slot) {
        for (AiProfile p : getProfiles()) {
            if (!slot.equals(p.keySlot()) || p.legacyKeySlot == null || p.legacyKeySlot.isEmpty()) {
                continue;
            }
            String old = loadApiKey(p.legacyKeySlot);
            if (!old.isEmpty()) {
                saveConfig(slot, old);
            }
            return old;
        }
        return "";
    }

    /** 规范化用户输入的 BaseURL：去尾斜杠；未带 /chat/completions 时自动补全 */
    public static String normalizeBaseUrl(String input) {
        String url = input == null ? "" : input.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.isEmpty() && !url.endsWith("/chat/completions")) {
            url = url + "/chat/completions";
        }
        return url;
    }

    /** 从旧版明文文件取出该厂商的 Key，迁移进 PasswordSafe 并从文件中删除；无旧数据时返回空串 */
    private static String migrateLegacyKey(String provider) {
        JsonObject config = loadLegacyConfig();
        String field = "key_" + provider;
        String legacy = "";
        if (config.has(field) && !config.get(field).isJsonNull()) {
            legacy = config.get(field).getAsString();
        } else if (config.has("key") && !config.get("key").isJsonNull()
                && config.has("provider") && !config.get("provider").isJsonNull()
                && provider.equals(config.get("provider").getAsString())) {
            // 兼容更早的单 key 格式
            legacy = config.get("key").getAsString();
            field = "key";
        }
        if (legacy.isEmpty()) {
            return "";
        }
        try {
            PasswordSafe.getInstance().set(credentialAttributes(provider), new Credentials(provider, legacy));
            config.remove(field);
            rewriteLegacyConfig(config);
            PluginLog.info("[AIService] 已将 " + provider + " 的 API Key 迁移到 PasswordSafe");
        } catch (Exception e) {
            PluginLog.error("[AIService] 迁移 API Key 失败: " + e.getMessage(), e);
        }
        return legacy;
    }

    /** 读取旧版明文配置文件（仅用于迁移） */
    private static JsonObject loadLegacyConfig() {
        try {
            Path path = Paths.get(LEGACY_CONFIG_FILE);
            if (Files.exists(path)) {
                JsonObject obj = GSON.fromJson(
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8), JsonObject.class);
                return obj != null ? obj : new JsonObject();
            }
        } catch (Exception e) {
            PluginLog.info("[AIService] 读取旧配置文件失败: " + e.getMessage());
        }
        return new JsonObject();
    }

    /** 迁移后回写旧配置文件；已无残留 Key 时直接删除该文件 */
    private static void rewriteLegacyConfig(JsonObject config) {
        try {
            Path path = Paths.get(LEGACY_CONFIG_FILE);
            boolean hasRemainingKey = false;
            for (String name : config.keySet()) {
                if ("key".equals(name) || name.startsWith("key_")) {
                    hasRemainingKey = true;
                    break;
                }
            }
            if (hasRemainingKey) {
                Files.write(path, GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            PluginLog.info("[AIService] 清理旧配置文件失败: " + e.getMessage());
        }
    }

    // ── 实例字段 ──

    private final String apiKey;
    private final String modelId;
    private final String apiUrl;
    private final double temperature;
    private final boolean reasoningEffortNone;
    private final Gson gson = new Gson();

    /** 共享静态线程池（daemon 线程，固定 4 线程，生命周期 = 插件生命周期） */
    private static final ExecutorService SHARED_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AIService-Worker");
        t.setDaemon(true);
        return t;
    });

    /** 中止当前流式请求；createAiService 每次新建实例，不会跨请求残留 */
    private volatile boolean cancelled = false;

    public void cancel() {
        cancelled = true;
    }

    /** 按配置构造：地址、模型 ID、采样参数全部来自该配置，与厂商注册表彻底解耦 */
    public AIService(String apiKey, AiProfile profile) {
        AiProfile p = profile != null ? profile : new AiProfile();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.modelId = p.model == null ? "" : p.model;
        this.apiUrl = normalizeBaseUrl(p.baseUrl);
        this.temperature = p.temperature > 0 ? p.temperature : DEFAULT_TEMPERATURE;
        this.reasoningEffortNone = p.reasoningEffortNone;
    }

    /** 兼容旧调用：modelId 命中预设时按其地址与参数构造 */
    public AIService(String apiKey, String modelId) {
        this(apiKey, legacyProfile(modelId, null));
    }

    /** 兼容旧调用：显式指定 OpenAI 兼容地址 */
    public AIService(String apiKey, String modelId, String customBaseUrl) {
        this(apiKey, legacyProfile(
                CUSTOM_MODEL_ID.equals(modelId)
                        ? PropertiesComponent.getInstance().getValue(LEGACY_MODEL_NAME_KEY, "")
                        : modelId,
                customBaseUrl));
    }

    private static AiProfile legacyProfile(String modelId, String baseUrl) {
        AiProfile p = new AiProfile();
        p.model = modelId == null ? "" : modelId;
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            p.baseUrl = baseUrl;
            return p;
        }
        for (Preset preset : PRESETS) {
            if (preset.model.equals(modelId)) {
                p.baseUrl = preset.baseUrl;
                p.temperature = preset.temperature;
                p.reasoningEffortNone = preset.reasoningEffortNone;
                break;
            }
        }
        return p;
    }

    /** 生成超时统一提示：HTTP 读取超时与外层兜底共用，避免误导成 Key 或网络问题 */
    public static final String TIMEOUT_HINT =
            "AI 分析超时：模型生成过慢，可减少自选股数量、更换更快的模型后重试";

    /** 分析自选股的系统提示词 */
    public static final String ANALYSIS_SYSTEM_PROMPT =
                    "你是一个专业证券分析师，擅长基于实时行情数据进行日内技术分析。\n" +
                    "【颜色与方向 + 分析阶段强约束 · 必须遵守，违反将被自动纠正】\n" +
                    "分析阶段（**唯一依据** = 上下文首部「市场状态」字段分三阶段）：\n" +
                    "· **盘后**（已收盘/周末休市等）→ 数据即收盘定格，用「今日收红/今日收绿/今日收平」定论表述，分析侧重今日定论 + 次日展望；\n" +
                    "· **盘中**（集合竞价、开盘准备、盘中早盘/午盘、午间休市、夜盘等交易进行时段）→ 用「目前收红/目前收绿/目前收平」暂态表述，涨跌幅绝对值<0.5%时必须提示方向收盘前仍可能变化，分析侧重实时强弱 + 当前位置 + 关键位；\n" +
                    "· **盘前**（开盘前）→ 数据是上一交易日收盘，用「昨日收红/昨日收绿/昨日收平」表述，分析侧重昨日复盘 + 今日开盘预期/外围情绪/关键位。\n" +
                    "① 「收红/收绿/收平」**唯一依据**是行情上下文里「涨跌幅」字段：涨跌幅>0=收红、<0=收绿、绝对值<0.05%=收平/平盘。**禁止**根据「弱势」「强势」「无反弹动力」等叙事措辞自行判定颜色。\n" +
                    "② 「阳线/阴线/十字星」= 现价相对昨收：现价>昨收=阳线（对应收红）、<昨收=阴线（对应收绿）、≈昨收=十字星（对应收平）。列表里报的「涨跌幅」字段即表达这一关系，**阳/阴/十字 三者不能与涨跌幅符号矛盾**。\n" +
                    "③ 每只股票点评开头**必须**先点明颜色（例：「今日收绿…」/「目前收红…」/「昨日收平…」），再谈形态。\n" +
                    "④ 「今日/目前/昨日」措辞**必须**与上述三阶段一致：盘前→「昨日」、盘中→「目前」、盘后→「今日」，不得混用。\n" +
                    "⑤ 「收绿日」**禁止**使用「低开高走」「强势反弹」「多头占优」等诱导看多措辞；若当日确实日内反弹，必须明确写「日内反弹但仍收绿」/「收盘仍为负」，与列表方向一致。\n" +
                    "⑥ 所有结论**只能**基于上下文提供的数据；未提供的字段（如成交量、个股均线的品种）不得提及或编造，「日内低开X%」这类未给出的数值不得自行编造。\n" +
                    "数据说明：行情上下文首部附「数据时间」（北京时间）与「市场状态」（盘前/集合竞价/盘中(早盘/午盘)/午间休市/盘后/夜盘等子状态，按各市场交易时段计算）。你掌握每只股票当日日内数据（今开、昨收、最高、最低、现价、振幅、日内位置、距开盘涨幅、较昨收开缺口幅），多数股票附有均线数据（MA5/MA10/MA20/MA60、现价较MA20乖离、5日涨跌）。\n" +
                    "分析视角：默认以均值回归为主——大涨警惕追高回调，大跌关注超卖反弹，避免追涨杀跌；现价大幅偏离MA20（乖离过大）时回归压力增大。但若日内单边走强、现价处于日内高位且站在均线上方，需提示趋势延续的可能，不强行套用回调逻辑。\n" +
                    "涨跌幅极端程度需按品种判断：A股主板±10%、创业板/科创板±20%涨跌停制度内判断；港美股、黄金无涨跌停，按该品种日常波动特性判断（美股大盘股日内3%已属显著，黄金1%即显著）。\n" +
                    "输出格式要求（纯文本，不用markdown，不用#号、**、*）：\n" +
                    "每只股票用【股票名(代码)】开头，2-3行精炼点评，依次覆盖：\n" +
                    "【趋势】日内强弱与位置（高开/低开、距开盘涨幅、日内位置高低、收红或收绿）\n" +
                    "【信号】是否处于极端区域及方向（超买警惕回调/超卖关注反弹/趋势延续/震荡区间，结合MA20乖离与5日涨跌判断）\n" +
                    "【操作】给出明确建议（高抛/低吸/持有/观望/区间操作）和具体目标位、止损位\n" +
                    "【风险】仅在该股有特殊风险点时输出一行，无则省略\n" +
                    "最后给出组合整体评估，不超过5行：仓位建议、市场情绪、当前是否适合左侧布局。\n" +
                    "【输出纪律·必须遵守】禁止输出任何思考过程、分析计划、对题目要求的复述或自我说明；回答的第一个字符必须是「【」，格式之外不得写前言、过程说明或重复内容，不要为了凑长度展开。\n" +
                    "语言专业、数据驱动、直接给结论。注意：以下为分析参考，不构成投资建议。";

    /** 快讯解读系统提示词：定性结论 + 关联自选 + 后续跟踪点（自选清单运行时追加在本提示末尾，不进入用户气泡） */
    public static final String NEWS_INTERPRET_PROMPT =
            "你是一个专业证券分析师，擅长快速解读财经快讯。\n" +
            "用户消息包含一条7x24财经快讯（标题+摘要）；用户当前的自选股清单（名称+代码）附在本提示末尾，可能为空。\n" +
            "请按以下结构输出（纯文本，不用markdown符号，结论先行）：\n" +
            "1. 【定性】一句话结论：利多/利空/中性，指向哪个行业或板块，为什么。\n" +
            "2. 【关联自选】对照自选股清单，按行业、产业链或主题判断哪些品种与该消息相关，逐只说明关联逻辑与潜在影响方向，引用品种时写名称即可；确无关联则写「与当前自选无直接关联」。清单为空则省略此节。\n" +
            "3. 【跟踪点】1-2 条后续需要盯住的信号（数据落地时间、政策细则、业绩窗口等）。\n" +
            "要求：只基于快讯内容与给定清单作答，不得编造未提供的数字或背景；语气克制，避免夸大单一事件的影响；解读仅为资讯参考，不构成投资建议。";

    /** 最近要闻速览系统提示词：只汇总当前这批快讯，明确不代表全天 */
    public static final String NEWS_SUMMARY_PROMPT =
            "你是一个专业财经编辑，擅长把 7x24 快讯流压缩成高信息密度的要闻速览。\n" +
            "「最近快讯清单」附在本提示末尾，是你唯一可依据的数据。\n" +
            "重要：清单仅覆盖最近一批快讯，不代表全天，也不代表全部市场信息，不得暗示信息完整。\n" +
            "【语言·必须遵守】全部使用简体中文。禁止英文小标题、英文分类名、中英混排的标题；" +
            "公司名、股票代码、指数名等专有名词可保留原文。\n" +
            "【格式·必须遵守】严格按以下三段输出，段落名必须原样保留，不得自行改成主题编号、不得增删段落：\n" +
            "【一句话综述】\n" +
            "一句话概括这批快讯的主线。\n" +
            "【要点】\n" +
            "3-5 条最重要的事，每条单独一行并以「- 」开头，1-2 句，先给结论再点明主题或板块；同主题快讯合并成一条，不要逐条复述。\n" +
            "【值得留意】\n" +
            "1-2 条后续值得跟踪的信号或待落地信息，每条单独一行并以「- 」开头；没有则写「暂无」。\n" +
            "格式示例（只示意段落名与语言，内容必须来自清单、不得照抄）：\n" +
            "【一句话综述】\n" +
            "政策与资金面消息交织，科技板块相对活跃。\n" +
            "【要点】\n" +
            "- 多家公司披露中标与回购，电网设备链条关注度上升。\n" +
            "【值得留意】\n" +
            "- 后续需跟踪相关政策的落地节奏。\n" +
            "【其他要求】只基于清单作答，不得编造未出现的数字、政策、公司或事件；按重要性而非时间顺序排列；" +
            "纯文本，不用 markdown、不用 # 号或 **；语气克制，避免夸大单一事件影响；速览仅为资讯参考，不构成投资建议。\n" +
            "【输出纪律·必须遵守】禁止输出思考过程、分析计划、对题目要求的复述或自我说明；" +
            "回答的第一个字符必须是「【」，即直接从「【一句话综述】」开始；三段之外不得写前言、过程说明或重复内容。";

    /** 速览单次最多取 30 条、每条摘要截断 60 字，避免免费模型上下文过长 */
    private static final int SUMMARY_MAX_ITEMS = 30;
    private static final int SUMMARY_DIGEST_LIMIT = 60;

    /** 把最新一批快讯压成注入 system prompt 的清单（纯函数，无 UI 依赖，便于单测）。
     *  接口返回已是最新在前，按传入顺序取前 N 条；按 id 去重，标题与摘要重复时只保留标题。 */
    public static String buildSummaryContext(List<KuaixunItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n最近快讯清单（本次获取 ").append(items.size())
                .append(" 条，以下最多 ").append(SUMMARY_MAX_ITEMS)
                .append(" 条；仅代表最近一批，不代表全天）：\n");
        int added = 0;
        for (KuaixunItem item : items) {
            if (item == null || added >= SUMMARY_MAX_ITEMS) {
                continue;
            }
            String id = item.getId();
            if (id != null && !id.isEmpty() && !seenIds.add(id)) {
                continue;
            }
            String title = item.getTitle() == null ? "" : item.getTitle().trim();
            String digest = item.getContentText() == null ? "" : item.getContentText().trim();
            if (title.isEmpty() && digest.isEmpty()) {
                continue;
            }
            if (digest.equals(title)) {
                digest = "";
            }
            if (digest.length() > SUMMARY_DIGEST_LIMIT) {
                digest = digest.substring(0, SUMMARY_DIGEST_LIMIT) + "…";
            }
            sb.append("- ").append(item.getDisplayTimeText()).append(" ").append(title);
            if (!digest.isEmpty()) {
                sb.append("：").append(digest);
            }
            sb.append("\n");
            added++;
        }
        return added == 0 ? "" : sb.toString();
    }

    /** 构建自选股行情上下文（供 UI 展示与调用分析共用） */
    public static String buildMarketContext(List<QuoteItem> items, String marketName) {
        return buildMarketContext(items, marketName, Map.of());
    }

    /** 构建自选股行情上下文（带均线数据，均线缺失的股票不输出均线行） */
    public static String buildMarketContext(List<QuoteItem> items, String marketName,
                                            Map<String, QuoteService.MaInfo> maMap) {
        StringBuilder context = new StringBuilder("当前市场：").append(marketName)
                .append("\n").append(buildSnapshotHeader(items))
                .append("\n\n自选股实时行情：\n");
        for (int i = 0; i < items.size(); i++) {
            QuoteItem it = items.get(i);
            double range = it.getHighPrice() - it.getLowPrice();
            double rangePct = range > 0 ? range / it.getClosePrice() * 100 : 0;
            double posInRange = range > 0 ? (it.getPrice() - it.getLowPrice()) / range * 100 : 50;
            double gapFromOpen = it.getOpenPrice() > 0 ? (it.getPrice() - it.getOpenPrice()) / it.getOpenPrice() * 100 : 0;
            double openGapPct = it.getClosePrice() > 0 ? (it.getOpenPrice() - it.getClosePrice()) / it.getClosePrice() * 100 : 0;
            String candleType = "十字星";
            // K线阳/阴以「现价 vs 昨收」判定（与列表涨跌幅、用户看到的收红/收绿保持一致）：
            // 收红=阳线、收绿=阴线，避免出现「日线收绿但K线报阳」的方向混淆。
            // 日内形态（V形/倒V/先抑后扬等）由上下影线与实体比例承载，不靠阳阴表达。
            if (it.getPrice() > it.getClosePrice()) candleType = "阳线";
            else if (it.getPrice() < it.getClosePrice()) candleType = "阴线";
            double bodyPct = range > 0 ? Math.abs(it.getPrice() - it.getOpenPrice()) / range * 100 : 0;
            double upperShadowPct = range > 0 ? (it.getHighPrice() - Math.max(it.getPrice(), it.getOpenPrice())) / range * 100 : 0;
            double lowerShadowPct = range > 0 ? (Math.min(it.getPrice(), it.getOpenPrice()) - it.getLowPrice()) / range * 100 : 0;

            context.append(i + 1).append(". ").append(it.getName()).append("(").append(it.getCode()).append(")\n")
                    .append("   现价 ").append(it.getPrice())
                    .append("，涨跌幅 ").append(String.format("%+.2f%%", it.getChangePercent()))
                    .append("，涨跌额 ").append(String.format("%+.2f", it.getChangeAmount()))
                    .append("，今开 ").append(it.getOpenPrice())
                    .append(String.format("(较昨收%+.2f%%)", openGapPct))
                    .append("，昨收 ").append(it.getClosePrice())
                    .append("，最高 ").append(it.getHighPrice())
                    .append("，最低 ").append(it.getLowPrice())
                    .append("，振幅 ").append(String.format("%.2f%%", rangePct))
                    .append("，K线 ").append(candleType).append(String.format("(实体%.0f%%,上影%.0f%%,下影%.0f%%)", bodyPct, upperShadowPct, lowerShadowPct))
                    .append("，日内位置 ").append(String.format("%.0f%%", posInRange))
                    .append("，距开盘 ").append(String.format("%+.2f%%", gapFromOpen))
                    .append("\n");
            QuoteService.MaInfo ma = maMap.get(it.getCode());
            if (ma != null) {
                context.append("   均线 ");
                if (ma.ma5 > 0) context.append("MA5 ").append(fmt2(ma.ma5));
                if (ma.ma10 > 0) context.append("，MA10 ").append(fmt2(ma.ma10));
                if (ma.ma20 > 0) context.append("，MA20 ").append(fmt2(ma.ma20));
                if (ma.ma60 > 0) context.append("，MA60 ").append(fmt2(ma.ma60));
                if (ma.bias20 != 0) context.append("，现价较MA20 ").append(String.format("%+.1f%%", ma.bias20));
                if (ma.chg5 != 0) context.append("，5日涨跌 ").append(String.format("%+.1f%%", ma.chg5));
                context.append("\n");
            }
        }
        return context.toString();
    }

    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    private static final String[] WEEK_CN = {"一", "二", "三", "四", "五", "六", "日"};

    /** 数据快照时间与各市场交易状态行（供 AI 区分「今日定论 / 目前暂态」措辞，按各市场交易时段确定性计算） */
    private static String buildSnapshotHeader(List<QuoteItem> items) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        StringBuilder sb = new StringBuilder("数据时间：")
                .append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .append("(周").append(WEEK_CN[now.getDayOfWeek().getValue() - 1]).append(") ")
                .append(now.format(DateTimeFormatter.ofPattern("HH:mm")))
                .append(" 北京时间");
        LinkedHashSet<String> markets = new LinkedHashSet<>();
        for (QuoteItem it : items) {
            String m = marketOf(it.getCode());
            if (!m.isEmpty()) {
                markets.add(m);
            }
        }
        if (!markets.isEmpty()) {
            sb.append("\n市场状态：");
            boolean first = true;
            for (String m : markets) {
                if (!first) {
                    sb.append("；");
                }
                sb.append(m).append("-").append(sessionStatus(m, now));
                first = false;
            }
        }
        return sb.toString();
    }

    /** 从裸代码推断所属市场（与 QuoteService.parseRequestedCode 的判定口径一致） */
    private static String marketOf(String code) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if (c.isEmpty()) {
            return "";
        }
        if ("xauusd".equals(c) || "hf_xau".equals(c) || "gold".equals(c) || GoldInstrument.fromCode(c) != null) {
            return "黄金";
        }
        if ("hsi".equals(c) || "hscei".equals(c) || "hscci".equals(c)) {
            return "港股";
        }
        if (c.matches("\\d{6}")) {
            return "A股";
        }
        if (c.matches("\\d{5}")) {
            return "港股";
        }
        if (c.matches("[a-z][a-z0-9.]{0,9}")) {
            return "美股";
        }
        return "";
    }

    /** 按市场交易时段计算状态。返回三阶段子状态：盘前 / 集合竞价 / 盘中(早盘/午盘) / 午间休市 / 盘后 / 夜盘 等。
     *  A股 9:00 盘前 / 9:15-9:25 集合竞价 / 9:25-9:30 开盘准备 / 9:30-11:30 早盘 / 11:30-13:00 午间休市 / 13:00-15:00 午盘 / 15:00 后盘后
     *  港股 9:30 盘前 / 9:30-12:00 早盘 / 12:00-13:00 午间休市 / 13:00-16:00 午盘 / 16:00 后盘后
     *  美股按美东时间（自动处理夏令时）：盘前(4:00-9:30) / 盘中(9:30-16:00) / 盘后(16:00-20:00) / 夜盘(20:00-4:00)
     *  伦敦金 周一 7:00 - 周六 4:00 为盘中，周六 4:00 - 周一 7:00 为周末休市/盘前。
     *  节假日休市未覆盖，属已知简化。 */
    private static String sessionStatus(String market, ZonedDateTime bjNow) {
        DayOfWeek dow = bjNow.getDayOfWeek();
        boolean weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        int m = bjNow.getHour() * 60 + bjNow.getMinute();
        switch (market) {
            case "A股":
                if (!weekday) return "周末休市";
                if (m < 540) return "盘前";             // < 9:00
                if (m < 555) return "盘前";             // 9:00-9:15
                if (m < 565) return "集合竞价";         // 9:15-9:25
                if (m < 570) return "开盘准备";         // 9:25-9:30
                if (m < 690) return "盘中(早盘)";       // 9:30-11:30
                if (m < 780) return "午间休市";         // 11:30-13:00
                if (m < 900) return "盘中(午盘)";       // 13:00-15:00
                return "盘后";                           // 15:00 后
            case "港股":
                if (!weekday) return "周末休市";
                if (m < 570) return "盘前";             // < 9:30
                if (m < 720) return "盘中(早盘)";       // 9:30-12:00
                if (m < 780) return "午间休市";         // 12:00-13:00
                if (m < 960) return "盘中(午盘)";       // 13:00-16:00
                return "盘后";                           // 16:00 后
            case "美股": {
                ZonedDateTime ny = bjNow.withZoneSameInstant(ZoneId.of("America/New_York"));
                DayOfWeek nd = ny.getDayOfWeek();
                if (nd == DayOfWeek.SATURDAY || nd == DayOfWeek.SUNDAY) return "周末休市";
                int nm = ny.getHour() * 60 + ny.getMinute();
                if (nm < 240) return "夜盘(美东)";      // 0:00-4:00 ET
                if (nm < 570) return "盘前(美东)";      // 4:00-9:30 ET
                if (nm < 960) return "盘中(美东)";      // 9:30-16:00 ET
                return "盘后(美东)";                     // 16:00-24:00 ET
            }
            default: // 黄金（伦敦金 / 现货黄金）
                if (dow == DayOfWeek.SATURDAY) {
                    if (m < 240) return "盘中(伦敦金)"; // 周六 04:00 前仍交易中
                    return "周末休市";
                }
                if (dow == DayOfWeek.SUNDAY) return "周末休市";
                if (dow == DayOfWeek.MONDAY && m < 420) return "盘前(伦敦金)"; // 周一 07:00 前
                return "盘中(伦敦金)";
        }
    }

    /** 将子状态归类到三阶段：盘前 / 盘中 / 盘后（盘后含已收盘、周末休市）。 */
    private static String stageClass(String status) {
        if (status == null || status.isEmpty()) return "盘后";
        if (status.startsWith("盘前") || status.startsWith("集合竞价") || status.startsWith("开盘准备")) return "盘前";
        if (status.startsWith("盘中") || status.startsWith("午间") || status.startsWith("夜盘")) return "盘中";
        return "盘后";
    }

    /** 后处理兜底：模型偶有违反强约束规则，按确定性数据自动回写——
     *  ①按市场三阶段改写「今日/目前/昨日」：盘前「今日」→「昨日」、盘中「今日」→「目前」、盘后保持；
     *  ②「K线 阴线/阳线」与该股票「涨跌幅」符号矛盾时自动纠正。
     *  仅作用于自选股分析链路（需要每只股票的涨跌幅）。 */
    private static String enforceConsistency(String text, List<QuoteItem> items) {
        if (text == null || text.isEmpty() || items == null || items.isEmpty()) {
            return text;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Double> chgByCode = new HashMap<>();
        for (QuoteItem it : items) {
            chgByCode.put(it.getCode(), it.getChangePercent());
        }
        Pattern codeInBrackets = Pattern.compile("\\(([^)]+)\\)");

        // ① 按三阶段改写「今日/目前/昨日」：盘前→「昨日」、盘中→「目前」、盘后保持「今日」
        Pattern linePrefix = Pattern.compile("(【[^】]+】[\\s\\S]*?)(今日|目前|昨日)收(红|绿)");
        Matcher m = linePrefix.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String head = m.group(1);
            String oldPrefix = m.group(2);
            String color = m.group(3);
            String newPrefix = oldPrefix;
            Matcher cm = codeInBrackets.matcher(head);
            if (cm.find()) {
                String market = marketOf(cm.group(1));
                if (!market.isEmpty()) {
                    String stage = stageClass(sessionStatus(market, now));
                    if ("盘前".equals(stage) && "今日".equals(oldPrefix)) {
                        newPrefix = "昨日";
                    } else if ("盘中".equals(stage) && "今日".equals(oldPrefix)) {
                        newPrefix = "目前";
                    }
                    // 盘后保持「今日」不变
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(head + newPrefix + "收" + color));
        }
        m.appendTail(sb);
        String result = sb.toString();

        // ② K线 阳/阴/十字星 与涨跌幅符号不一致时纠正（含平盘→十字星）
        Pattern candleLine = Pattern.compile("(【[^】]+】[\\s\\S]*?)K线(阳线|阴线|十字星)");
        Matcher mk = candleLine.matcher(result);
        StringBuilder sbk = new StringBuilder();
        while (mk.find()) {
            String head = mk.group(1);
            String candle = mk.group(2);
            String correctCandle = candle;
            Matcher cm = codeInBrackets.matcher(head);
            if (cm.find()) {
                Double pct = chgByCode.get(cm.group(1));
                if (pct != null && Math.abs(pct) < 0.05) {
                    if (!"十字星".equals(candle)) correctCandle = "十字星";
                } else if (pct != null && pct > 0) {
                    if ("阴线".equals(candle)) correctCandle = "阳线";
                } else if (pct != null && pct < 0) {
                    if ("阳线".equals(candle)) correctCandle = "阴线";
                }
            }
            mk.appendReplacement(sbk, Matcher.quoteReplacement(head + "K线" + correctCandle));
        }
        mk.appendTail(sbk);
        return sbk.toString();
    }

    /** 异步分析自选股行情 */
    public CompletableFuture<String> analyzeWatchlistAsync(List<QuoteItem> items, String marketName) {
        return analyzeWatchlistAsync(items, marketName, null);
    }

    /** 带流式回调的分析；onChunk 为 null 时走原来的整包响应 */
    public CompletableFuture<String> analyzeWatchlistAsync(
            List<QuoteItem> items, String marketName,
            java.util.function.Consumer<String> onChunk) {
        PluginLog.info("[AIService] analyzeWatchlistAsync START, items=" +
            (items != null ? items.size() : 0) + ", market=" + marketName + ", model=" + modelId);
        return CompletableFuture.supplyAsync(() -> {
            if (items == null || items.isEmpty()) {
                PluginLog.info("[AIService] analyzeWatchlistAsync FAIL: items empty");
                return "暂无自选股可分析";
            }
            // 拉取日K均线（专用线程池并行，逐只失败自动降级为无均线行）
            Map<String, QuoteService.MaInfo> maMap;
            try {
                maMap = new QuoteService().fetchMaInfos(items);
                PluginLog.info("[AIService] fetchMaInfos done, ma_count=" + maMap.size() + "/" + items.size());
            } catch (Exception e) {
                PluginLog.error("[AIService] fetchMaInfos failed, fallback to no-MA context", e);
                maMap = Map.of();
            }
            String context = buildMarketContext(items, marketName, maMap);
            PluginLog.info("[AIService] analyzeWatchlistAsync calling callModel, context_len=" + context.length());
            JsonArray messages = new JsonArray();
            messages.add(buildMessage("system", ANALYSIS_SYSTEM_PROMPT));
            messages.add(buildMessage("user",
                "请分析以下自选股行情，逐只给出点评和操作建议：\n\n" + context));
            String result = callModel(messages, onChunk);
            result = enforceConsistency(result, items);
            PluginLog.info("[AIService] analyzeWatchlistAsync DONE, result_len=" +
                (result != null ? result.length() : 0) + ", preview=" +
                (result != null ? result.substring(0, Math.min(80, result.length())) : "null"));
            return result;
        }, SHARED_EXECUTOR);
    }

    /** 异步聊天：带多轮对话历史，支持追问。
     *  跨轮一致性：自动从最近一条含品种操作结论的 assistant 消息提取「上一轮结论基线」注入 system prompt，
     *  约束本轮结论不得无依据推翻基线，推翻时必须说明数据/信号变化原因。 */
    public CompletableFuture<String> chatAsync(String systemPrompt, List<String> history) {
        return chatAsync(systemPrompt, history, null);
    }

    /** 带流式回调的聊天；onChunk 为 null 时走原来的整包响应 */
    public CompletableFuture<String> chatAsync(String systemPrompt, List<String> history,
                                                java.util.function.Consumer<String> onChunk) {
        // 在调用线程做 snapshot，避免后续修改导致 ConcurrentModificationException
        final List<String> snapshot = new ArrayList<>(history);
        final String sys = appendBaseline(systemPrompt, snapshot);
        return CompletableFuture.supplyAsync(() -> {
            JsonArray messages = new JsonArray();
            messages.add(buildMessage("system", sys));
            for (int i = 0; i < snapshot.size(); i++) {
                String role = (i % 2 == 0) ? "user" : "assistant";
                messages.add(buildMessage(role, snapshot.get(i)));
            }
            return callModel(messages, onChunk);
        }, SHARED_EXECUTOR);
    }

    /** 提取上一轮结论基线并以最高优先级前置注入 system prompt；无可用基线时原样返回。
     *  前置（而非追加在末尾）确保基线不被后续长规则淹没；检测到用户质疑时注入「视角质疑≠新数据」辩护指引。 */
    static String appendBaseline(String systemPrompt, List<String> history) {
        String baseline = extractBaseline(history);
        if (baseline.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder header = new StringBuilder();
        header.append("【上一轮结论基线 · 最高优先级 · 必须遵守】\n").append(baseline);
        header.append("本轮对上述品种的操作结论：数据未变 → 保持基线结论；推翻必须先明确说明数据/信号的具体变化（如跌破/突破关键位、均线信号改变、涨跌幅显著变化）。\n");
        if (isUserChallenge(history)) {
            header.append("注意：用户最新消息是对上一轮结论的质疑或提出了不同观点，这不是新数据。\n");
            header.append("正确做法是——基于原数据捍卫或细化基线结论，");
            header.append("区分不同投资期限下的结论差异（例如「短线可高抛、长线可持有」二者并不矛盾），");
            header.append("不得因用户反对就全盘翻转结论；\n");
            header.append("只有用户提供了新的行情事实（如最新价格、突发消息）才可作为推翻依据并引用说明。\n");
        }
        header.append("同一轮回答内不得出现相互矛盾的操作建议。\n\n");
        return header + systemPrompt;
    }

    /** 启发式检测最新 user 消息是否为对上一轮结论的质疑/挑战（而非提供新数据） */
    private static boolean isUserChallenge(List<String> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        String lastMsg = history.get(history.size() - 1);
        if (lastMsg == null) {
            return false;
        }
        String[] challengeWords = {"不对", "错了", "怎么能", "怎么可以", "不该", "明明", "矛盾", "不一致", "你说过", "不是吧", "搞错"};
        for (String w : challengeWords) {
            if (lastMsg.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 从对话历史中倒序查找最近一条含品种操作结论的 assistant 消息，逐品种提取操作建议。
     *  history 下标偶数为 user、奇数为 assistant（与 chatAsync 的角色分配一致）。
     *  注意：chatAsync 实际调用时 history 末尾是刚追加的 user 消息（奇数长度），
     *  因此从最后一个 assistant 下标（奇数）开始倒序扫描。
     *  优先匹配【品种(代码)】块格式；无【】块的纯文本回答（如"针对XX(代码)的…操作上建议…"）走回退提取。 */
    static String extractBaseline(List<String> history) {
        if (history == null || history.size() < 2) {
            return "";
        }
        Pattern block = Pattern.compile("(【[^】]+】)([\\s\\S]*?)(?=【[^】]+】|$)");
        Pattern op = Pattern.compile("操作[上]?\\s*[】:：]?\\s*([^\\n。；;]{2,80})");
        Pattern codeName = Pattern.compile("([^\\s，。；;、]{1,20}\\([^)]{1,12}\\))");
        // 末位是 user（奇数长度）→ 最后一个 assistant 在 size-2；末位是 assistant（偶数长度）→ 在 size-1
        int start = history.size() % 2 == 1 ? history.size() - 2 : history.size() - 1;
        for (int i = start; i >= 1; i -= 2) {
            String msg = history.get(i);
            if (msg == null || msg.isEmpty()) {
                continue;
            }
            // ① 【品种(代码)】块格式（标准输出）
            if (msg.contains("【")) {
                StringBuilder sb = new StringBuilder();
                Matcher blk = block.matcher(msg);
                while (blk.find()) {
                    String name = blk.group(1).trim();
                    String body = blk.group(2);
                    if (name.isEmpty()) {
                        continue;
                    }
                    Matcher om = op.matcher(body);
                    if (om.find()) {
                        String advice = om.group(1).trim();
                        if (!advice.isEmpty()) {
                            sb.append("- ").append(name).append(" 操作：").append(advice).append("\n");
                        }
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
            // ② 回退：纯文本格式（"针对XX(代码)…操作上建议…"），取第一个品种名 + 全文首个操作句
            Matcher om = op.matcher(msg);
            Matcher cn = codeName.matcher(msg);
            if (om.find() && cn.find()) {
                return "- " + cn.group(1) + " 操作：" + om.group(1).trim() + "\n";
            }
        }
        return "";
    }

    private JsonObject buildMessage(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    private String callModel(JsonArray messages) {
        return callModel(messages, null);
    }

    private String callModel(JsonArray messages, java.util.function.Consumer<String> onChunk) {
        try {
            PluginLog.info("[AIService] callModel start, model=" + modelId + ", apiUrl=" + apiUrl + ", key_len=" + (apiKey != null ? apiKey.length() : 0));
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelId);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", 4000);
            final boolean streaming = onChunk != null;
            if (streaming) {
                requestBody.addProperty("stream", true);
            }

            // 采样参数来自当前配置（如 kimi-k3 仅接受 1），不再依赖内置模型注册表
            requestBody.addProperty("temperature", temperature);
            // reasoning_effort 仅商汤 SenseNova 支持，其他平台不传避免报错
            if (reasoningEffortNone) {
                requestBody.addProperty("reasoning_effort", "none");
            }

            String jsonRequest = gson.toJson(requestBody);
            PluginLog.info("[AIService] callModel calling callAPI, request_len=" + jsonRequest.length());
            String response = callAPI(jsonRequest, streaming, onChunk);
            PluginLog.info("[AIService] callModel got response, len=" +
                (response != null ? response.length() : 0) + ", preview=" +
                (response != null ? response.substring(0, Math.min(200, response.length())) : "null"));
            // 所有入口统一剥离推理模型写在正文开头的思考前言
            String extracted = stripPreamble(extractResponseText(response));
            PluginLog.info("[AIService] callModel extracted, len=" +
                (extracted != null ? extracted.length() : 0));
            return extracted;
        } catch (Exception e) {
            PluginLog.error("[AIService] callModel EXCEPTION: " + e.getClass().getName() + " - " + e.getMessage(), e);
            // 读取/连接超时由 HTTP 层负责，给可操作提示；外层不再叠加同长的全局超时
            if (e instanceof java.net.SocketTimeoutException) {
                return TIMEOUT_HINT;
            }
            return "AI分析失败: " + e.getMessage();
        }
    }

    private String callAPI(String jsonRequest, boolean streaming,
                           java.util.function.Consumer<String> onChunk) throws Exception {
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalStateException("未配置接口地址，请在 AI 设置里填写 OpenAI 兼容地址（填到 /v1 即可）");
        }
        PluginLog.info("[AIService] callAPI connecting to " + apiUrl + ", stream=" + streaming);
        RuntimeException last429 = null;
        String request = jsonRequest;
        boolean useStream = streaming;
        for (int attempt = 0; attempt < 4; attempt++) {
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            if (useStream) {
                conn.setRequestProperty("Accept", "text/event-stream");
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(request.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            PluginLog.info("[AIService] callAPI response code=" + code);
            if (code < 200 || code >= 300) {
                String errorBody = readStream(conn.getErrorStream());
                // 部分厂商直接拒绝 stream 参数：去掉后按整包响应重试一次
                if (useStream && code >= 400 && code < 500 && code != 401 && code != 429) {
                    PluginLog.info("[AIService] stream rejected (HTTP " + code + "), fallback to non-stream");
                    useStream = false;
                    request = disableStream(request);
                    continue;
                }
                if (code == 401) {
                    throw new RuntimeException("API Key 无效或已过期（HTTP 401）");
                } else if (code == 429) {
                    last429 = new RuntimeException("请求频率超限，请稍后重试（HTTP 429）");
                    if (attempt < 3) {
                        long waitMs = (long) Math.pow(2, attempt + 1) * 1000L;
                        PluginLog.info("[AIService] 429 retry attempt=" + (attempt + 1) + "/3, waiting " + waitMs + "ms");
                        try { Thread.sleep(waitMs); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    throw last429;
                } else if (code >= 500) {
                    throw new RuntimeException("AI 服务端错误（HTTP " + code + "）: " + errorBody);
                } else {
                    throw new RuntimeException("API 请求失败（HTTP " + code + "）: " + errorBody);
                }
            }

            String contentType = conn.getContentType();
            if (useStream && contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                return readStreamingResponse(conn, onChunk);
            }
            // 厂商返回整包 JSON：自动降级，原有解析链路不变
            return readStream(conn.getInputStream());
        }
        throw last429 != null ? last429 : new RuntimeException("API 请求失败");
    }

    /** 逐行读 SSE，把 delta 累积成完整文本；合成与整包响应一致的 JSON 以复用 extractResponseText */
    private String readStreamingResponse(HttpURLConnection conn,
                                         java.util.function.Consumer<String> onChunk) throws Exception {
        StringBuilder accumulated = new StringBuilder();
        // 推理模型常把思考过程写进正文：先缓冲到正文锚点，避免前言流式刷屏。
        // accumulated 仍保留全文，最终文本由 stripPreamble / 后处理统一清洗
        StringBuilder pending = new StringBuilder();
        // 放行后按行缓冲，便于整行判断并丢弃中途插入的推理元话术
        StringBuilder lineBuffer = new StringBuilder();
        boolean[] visibleStarted = {false};
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                String chunk = parseDeltaContent(data);
                if (chunk.isEmpty()) {
                    continue;
                }
                accumulated.append(chunk);
                if (!visibleStarted[0]) {
                    pending.append(chunk);
                    int start = indexOfContentStart(pending);
                    if (start >= 0) {
                        visibleStarted[0] = true;
                        if (onChunk != null) {
                            onChunk.accept(pending.substring(start));
                        }
                    } else if (pending.length() >= PREAMBLE_FALLBACK_LIMIT && onChunk != null
                            && !isLikelyPreamble(pending)) {
                        // 正常但不带锚点的回答（如自由追问）不能一直憋着；
                        // 明显的推理自述则继续压住，等真正的正文锚点出现
                        visibleStarted[0] = true;
                        onChunk.accept(pending.toString());
                    }
                } else if (onChunk != null) {
                    // 放行之后仍按行挡一次：模型写到一半插入的英文自述、或「重新按格式写一遍」
                    // 不能原样进气泡。按行缓冲是因为 SSE chunk 可能把一句话切成两半
                    lineBuffer.append(chunk);
                    int nl;
                    while ((nl = lineBuffer.indexOf("\n")) >= 0) {
                        String oneLine = lineBuffer.substring(0, nl + 1);
                        lineBuffer.delete(0, nl + 1);
                        if (!isLikelyPreamble(oneLine)) {
                            onChunk.accept(oneLine);
                        }
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // 流式已吐出的内容必须保留，不能被整句超时提示替换掉
            PluginLog.info("[AIService] stream read timeout, kept partial_len=" + accumulated.length());
            accumulated.append("\n\n").append(TIMEOUT_HINT);
        } catch (java.io.IOException e) {
            PluginLog.error("[AIService] stream read failed: " + e.getMessage(), e);
            accumulated.append("\n\n（生成中断，以上为已生成内容）");
        } finally {
            // 流结束或异常时把不足一行的剩余文本补出去，同样不收推理自述
            if (lineBuffer.length() > 0 && onChunk != null && !isLikelyPreamble(lineBuffer)) {
                onChunk.accept(lineBuffer.toString());
            }
            conn.disconnect();
        }
        if (!visibleStarted[0] && pending.length() > 0 && onChunk != null
                && !isLikelyPreamble(pending)) {
            onChunk.accept(pending.toString());
        }
        if (cancelled) {
            accumulated.append("\n\n（已停止生成，以上为已生成内容）");
        }
        JsonObject message = new JsonObject();
        message.addProperty("content", accumulated.toString());
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject fake = new JsonObject();
        fake.add("choices", choices);
        return gson.toJson(fake);
    }

    /** 前言过滤的兜底字符数：超过仍未找到正文锚点、且不像推理自述时才直接显示，避免干等 */
    private static final int PREAMBLE_FALLBACK_LIMIT = 200;
    /** 整段都是推理自述、没有任何正文锚点时的兜底提示 */
    private static final String INVALID_CONTENT_HINT = "（本次未返回有效分析内容，请重试或更换模型）";

    /** 判断一段文字是不是推理模型关于「怎么作答」的自述，而不是正文。
     *  只用于流式首帧与中途行的显示过滤，不改动 accumulated / 最终文本。 */
    static boolean isLikelyPreamble(CharSequence text) {
        String s = text.toString();
        if (s.length() < 8) {
            return false;
        }
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        String[] markers = {
                "we need answer", "first char", "first character", "format says",
                "the user wants", "i need to", "let me ", "now let me", "maybe ",
                "but maybe", "however user", "risk optional", "each stock",
                "we can include", "need to be careful", "should we", "required format",
                "我需要遵守", "输出纪律", "题目要求", "用户要求", "每只股票用",
                "格式要求", "我需要按", "让我重新"
        };
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        // 插件要求全简体中文输出；整段以 ASCII 字母为主时按英文推理草稿处理
        if (s.length() >= 100) {
            int letters = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    letters++;
                }
            }
            return letters * 100 / s.length() > 75;
        }
        return false;
    }

    /** 找正文起点：优先真实段落名，其次行首【名称】块，最后才退回行首编号。
     *  不能用「首个【」——模型思考过程里常引用「第一个字符必须是「【」」，会误命中放行整段思考；
     *  也不能优先认行首「1.」——思考过程里的主题编号同样会误命中（如 "Major themes:\n1. ..."）。 */
    static int indexOfContentStart(CharSequence text) {
        String s = text.toString();
        int summary = s.indexOf("【一句话综述】");
        if (summary >= 0) {
            return summary;
        }
        java.util.regex.Matcher block = java.util.regex.Pattern
                .compile("(?m)^\\s*【[^】\\n]{1,40}】").matcher(s);
        if (block.find()) {
            return block.start();
        }
        java.util.regex.Matcher numbered = java.util.regex.Pattern
                .compile("(?m)^\\s*1[.、．]").matcher(s);
        return numbered.find() ? numbered.start() : -1;
    }

    /** 从一条 SSE data 提取增量文本；纯静态方法，便于单测 */
    static String parseDeltaContent(String data) {
        try {
            JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
            JsonArray choices = obj.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return "";
            }
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            if (delta == null) {
                return "";
            }
            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                return delta.get("content").getAsString();
            }
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                return delta.get("reasoning_content").getAsString();
            }
        } catch (Exception ignored) {
            // 单行解析失败不影响后续块
        }
        return "";
    }

    /** 从请求体移除 stream 字段，用于厂商拒绝流式时的自动回退 */
    private String disableStream(String request) {
        try {
            JsonObject obj = JsonParser.parseString(request).getAsJsonObject();
            obj.remove("stream");
            return gson.toJson(obj);
        } catch (Exception e) {
            return request;
        }
    }

    private String readStream(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String extractResponseText(String response) {
        PluginLog.info("[AIService] extractResponseText start, response_len=" +
            (response != null ? response.length() : 0));
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                // 尝试 content 字段
                if (message.has("content") && !message.get("content").isJsonNull()) {
                    String content = message.get("content").getAsString();
                    if (!content.isEmpty()) {
                        return stripMarkdown(content);
                    }
                }
                // 商汤 reasoning 模型回答在 reasoning_content 或 reasoning 里
                if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                    String reasoning = message.get("reasoning_content").getAsString();
                    if (!reasoning.isEmpty()) {
                        return reasoning;
                    }
                }
                if (message.has("reasoning") && !message.get("reasoning").isJsonNull()) {
                    String reasoning = message.get("reasoning").getAsString();
                    if (!reasoning.isEmpty()) {
                        return reasoning;
                    }
                }
                // 兜底：原始结构只写日志，不回显给用户（可能含敏感信息）
                PluginLog.info("[AIService] message 无可用文本字段: " + message);
                return "AI 返回解析失败：响应中没有可用内容，请重试或更换模型（详情见日志）";
            }
            if (json.has("error")) {
                return "API错误: " + json.getAsJsonObject("error").get("message").getAsString();
            }
        } catch (Exception e) {
            PluginLog.error("[AIService] extractResponseText 解析异常: " + e.getMessage(), e);
        }
        PluginLog.info("[AIService] 无法识别的响应结构，len=" + (response != null ? response.length() : 0));
        return "AI 返回解析失败：响应格式无法识别，请重试或更换模型（详情见日志）";
    }

    /** 丢弃推理模型写在正文开头的思考/复述前言，锚点与流式首帧过滤保持一致。
     *  找不到正文起点或截断后过短时原样返回，避免误伤正常回答。纯静态方法，便于单测。 */
    static String stripPreamble(String text) {
        if (text == null) {
            return "";
        }
        int start = indexOfContentStart(text);
        if (start == 0) {
            return text;
        }
        if (start > 0) {
            String rest = text.substring(start);
            if (rest.length() >= 20) {
                return rest;
            }
        }
        // 整段都没有正文锚点且明显是推理自述：不要原样回显给用户
        return isLikelyPreamble(text) ? INVALID_CONTENT_HINT : text;
    }

    /** 去除 markdown 格式符号 */
    private String stripMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("\n{3,}", "\n\n");
    }
}

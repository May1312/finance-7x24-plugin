package com.plugin.finance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;

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

    public static class ProviderInfo {
        public final String displayName;
        public final String registerUrl;

        public ProviderInfo(String displayName, String registerUrl) {
            this.displayName = displayName;
            this.registerUrl = registerUrl;
        }
    }

    public static class ModelInfo {
        public final String id;
        public final String displayName;
        public final String provider;
        public final String apiUrl;
        public final boolean free;

        public ModelInfo(String id, String displayName, String provider, String apiUrl, boolean free) {
            this.id = id;
            this.displayName = displayName;
            this.provider = provider;
            this.apiUrl = apiUrl;
            this.free = free;
        }

        public String fullDisplayName() {
            return provider + " · " + displayName + (free ? "（免费）" : "（付费）");
        }
    }

    // 厂商列表
    public static final List<String> PROVIDERS = new ArrayList<>();
    // 厂商信息（注册地址等）
    public static final Map<String, ProviderInfo> PROVIDER_INFO = new LinkedHashMap<>();
    // 所有模型（按 ID 索引）
    public static final Map<String, ModelInfo> MODELS = new LinkedHashMap<>();
    // 厂商 → 模型列表
    public static final Map<String, List<ModelInfo>> PROVIDER_MODELS = new LinkedHashMap<>();

    static {
        // 商汤 SenseNova
        registerProvider("商汤 SenseNova", "https://platform.sensenova.cn  →  API Keys → 新建 API Key");
        registerModel("deepseek-v4-flash", "DeepSeek V4 Flash", "商汤 SenseNova",
                "https://token.sensenova.cn/v1/chat/completions", true);
        registerModel("sensenova-6.8-flash-lite", "6.8 Flash-Lite", "商汤 SenseNova",
                "https://token.sensenova.cn/v1/chat/completions", true);
        registerModel("glm-5.2", "GLM-5.2", "商汤 SenseNova",
                "https://token.sensenova.cn/v1/chat/completions", true);

        // 智谱 GLM
        registerProvider("智谱 GLM", "https://open.bigmodel.cn  →  API Keys → 新建 API Key");
        registerModel("glm-4-flash", "GLM-4-Flash", "智谱 GLM",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions", true);
    }

    private static void registerProvider(String provider, String registerUrl) {
        PROVIDER_INFO.put(provider, new ProviderInfo(provider, registerUrl));
        PROVIDERS.add(provider);
    }

    private static void registerModel(String id, String displayName, String provider, String apiUrl, boolean free) {
        ModelInfo info = new ModelInfo(id, displayName, provider, apiUrl, free);
        MODELS.put(id, info);
        PROVIDER_MODELS.computeIfAbsent(provider, k -> new ArrayList<>()).add(info);
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
            key = migrateLegacyKey(provider);
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
    private final Gson gson = new Gson();

    /** 共享静态线程池（daemon 线程，固定 4 线程，生命周期 = 插件生命周期） */
    private static final ExecutorService SHARED_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AIService-Worker");
        t.setDaemon(true);
        return t;
    });

    public AIService(String apiKey, String modelId) {
        this.apiKey = apiKey;
        this.modelId = modelId;
        ModelInfo info = MODELS.get(modelId);
        this.apiUrl = info != null ? info.apiUrl : MODELS.values().iterator().next().apiUrl;
    }

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
                    "语言专业、数据驱动、直接给结论。注意：以下为分析参考，不构成投资建议。";

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
            String result = callModel(ANALYSIS_SYSTEM_PROMPT,
                "请分析以下自选股行情，逐只给出点评和操作建议：\n\n" + context);
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
            return callModel(messages);
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

    private String callModel(String systemPrompt, String userPrompt) {
        JsonArray messages = new JsonArray();
        messages.add(buildMessage("system", systemPrompt));
        messages.add(buildMessage("user", userPrompt));
        return callModel(messages);
    }

    private String callModel(JsonArray messages) {
        try {
            PluginLog.info("[AIService] callModel start, model=" + modelId + ", apiUrl=" + apiUrl + ", key_len=" + (apiKey != null ? apiKey.length() : 0));
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelId);
            requestBody.add("messages", messages);
            requestBody.addProperty("temperature", 0.3);
            requestBody.addProperty("max_tokens", 4000);

            // reasoning_effort 仅商汤 SenseNova 支持，其他厂商不传避免报错
            ModelInfo info = MODELS.get(modelId);
            if (info != null && "商汤 SenseNova".equals(info.provider)) {
                requestBody.addProperty("reasoning_effort", "none");
            }

            String jsonRequest = gson.toJson(requestBody);
            PluginLog.info("[AIService] callModel calling callAPI, request_len=" + jsonRequest.length());
            String response = callAPI(jsonRequest);
            PluginLog.info("[AIService] callModel got response, len=" +
                (response != null ? response.length() : 0) + ", preview=" +
                (response != null ? response.substring(0, Math.min(200, response.length())) : "null"));
            String extracted = extractResponseText(response);
            PluginLog.info("[AIService] callModel extracted, len=" +
                (extracted != null ? extracted.length() : 0));
            return extracted;
        } catch (Exception e) {
            PluginLog.error("[AIService] callModel EXCEPTION: " + e.getClass().getName() + " - " + e.getMessage(), e);
            return "AI分析失败: " + e.getMessage();
        }
    }

    private String callAPI(String jsonRequest) throws Exception {
        PluginLog.info("[AIService] callAPI connecting to " + apiUrl);
        RuntimeException last429 = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonRequest.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            PluginLog.info("[AIService] callAPI response code=" + code);
            if (code < 200 || code >= 300) {
                String errorBody = readStream(conn.getErrorStream());
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

            return readStream(conn.getInputStream());
        }
        throw last429 != null ? last429 : new RuntimeException("API 请求失败");
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

package com.plugin.finance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

    /** 获取指定厂商的存储 key 名称 */
    public static String providerKeyProperty(String provider) {
        return "finance.ai.key." + provider;
    }

    // ── 配置持久化（统一入口，消除重复代码） ──

    private static final String CONFIG_FILE = System.getProperty("user.home") + "/.finance7x24_ai_config.json";
    private static final Gson GSON = new Gson();

    /** 读取整个配置 JSON 对象 */
    public static JsonObject loadConfigObject() {
        try {
            Path path = Paths.get(CONFIG_FILE);
            if (Files.exists(path)) {
                String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                return obj != null ? obj : new JsonObject();
            }
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    /** 保存指定厂商的 API Key（保留其他厂商的 key） */
    public static void saveConfig(String provider, String apiKey) {
        JsonObject config = loadConfigObject();
        config.addProperty("provider", provider);
        config.addProperty("key_" + provider, apiKey);
        try {
            Files.write(Paths.get(CONFIG_FILE),
                    GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 加载指定厂商的 API Key */
    public static String loadApiKey(String provider) {
        JsonObject config = loadConfigObject();
        if (config.has("key_" + provider)) {
            return config.get("key_" + provider).getAsString();
        }
        // 兼容旧格式（无 provider 字段，单 key）
        if (config.has("key") && config.has("provider")
                && config.get("provider").getAsString().equals(provider)) {
            return config.get("key").getAsString();
        }
        return "";
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
                    "【颜色与方向强约束 · 必须遵守，违反将被自动纠正】\n" +
                    "① 「收红/收绿」**唯一依据**是行情上下文里「涨跌幅」字段的正负号；涨跌幅为正=收红、为负=收绿、为零=平盘。**禁止**根据「弱势」「强势」「无反弹动力」等叙事措辞自行判定颜色。\n" +
                    "② 「阳线/阴线」= 现价相对昨收（与涨跌幅等价），列表里报的「涨跌幅」字段即表达这一关系，「K线 阳线」=收红、「K线 阴线」=收绿，二者**绝对不能同时与涨跌幅矛盾**。\n" +
                    "③ 每只股票点评开头**必须**先点明收红/收绿（例：「目前收红…」/「今日收绿…」），再谈形态。\n" +
                    "④ 「今日/目前」措辞**唯一依据**是上下文首部「市场状态」字段：该市场已收盘/未开盘/周末休市 → 数据即收盘定格，用「今日收红/今日收绿」定论表述；该市场盘中（含午间休市）→ 用「目前收红/目前收绿」暂态表述，涨跌幅在±0.5%以内时必须提示方向收盘前仍可能翻转。\n" +
                    "⑤ 「收绿日」**禁止**使用「低开高走」「强势反弹」「多头占优」等诱导看多措辞；若当日确实日内反弹，必须明确写「日内反弹但仍收绿」/「收盘仍为负」，与列表方向一致。\n" +
                    "⑥ 所有结论**只能**基于上下文提供的数据；未提供的字段（如成交量、个股均线的品种）不得提及或编造，「日内低开X%」这类未给出的数值不得自行编造。\n" +
                    "数据说明：行情上下文首部附「数据时间」（北京时间）与「市场状态」（盘中/已收盘/未开盘等，已按各市场交易时段计算）。你掌握每只股票当日日内数据（今开、昨收、最高、最低、现价、振幅、日内位置、距开盘涨幅、较昨收开缺口幅），多数股票附有均线数据（MA5/MA10/MA20/MA60、现价较MA20乖离、5日涨跌）。\n" +
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

    /** 按市场交易时段计算状态。A股 9:30-11:30/13:00-15:00，港股 9:30-12:00/13:00-16:00（均为北京时间）；
     *  美股按美东时间 9:30-16:00（自动处理夏令时）；黄金工作日近似全天。节假日休市无法覆盖，属已知简化。 */
    private static String sessionStatus(String market, ZonedDateTime bjNow) {
        DayOfWeek dow = bjNow.getDayOfWeek();
        boolean weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        int m = bjNow.getHour() * 60 + bjNow.getMinute();
        switch (market) {
            case "A股":
            case "港股": {
                if (!weekday) {
                    return "周末休市";
                }
                boolean isA = "A股".equals(market);
                int lunchStart = isA ? 690 : 720;   // A股11:30午休 / 港股12:00午休
                int close = isA ? 900 : 960;        // A股15:00收盘 / 港股16:00收盘
                if (m < 570) {
                    return "未开盘";
                }
                if (m < lunchStart) {
                    return "盘中";
                }
                if (m < 780) {
                    return "午间休市";
                }
                if (m < close) {
                    return "盘中";
                }
                return "已收盘";
            }
            case "美股": {
                ZonedDateTime ny = bjNow.withZoneSameInstant(ZoneId.of("America/New_York"));
                DayOfWeek nd = ny.getDayOfWeek();
                if (nd == DayOfWeek.SATURDAY || nd == DayOfWeek.SUNDAY) {
                    return "周末休市";
                }
                int nm = ny.getHour() * 60 + ny.getMinute();
                if (nm < 570) {
                    return "未开盘(美东)";
                }
                if (nm < 960) {
                    return "盘中(美东)";
                }
                return "已收盘(美东)";
            }
            default:
                return weekday ? "交易中" : "周末休市";
        }
    }

    /** 后处理兜底：模型偶有违反强约束规则，按确定性数据自动回写——
     *  ①「今日收红/收绿」按该股票所属市场的「市场状态」回写为「目前/今日」；
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

        // ① 「今日/目前」按市场状态回写（每只股票评论行内）
        Pattern linePrefix = Pattern.compile("(【[^】]+】[\\s\\S]*?)今日收(红|绿)");
        Matcher m = linePrefix.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String head = m.group(1);
            String color = m.group(2);
            String prefix = "今日";
            Matcher cm = codeInBrackets.matcher(head);
            if (cm.find()) {
                String market = marketOf(cm.group(1));
                if (!market.isEmpty()) {
                    String status = sessionStatus(market, now);
                    // 黄金工作日状态为「交易中」（近似全天），同样属于暂态，需一并回写为「目前」
                    if (status.contains("盘中") || status.contains("午间") || status.contains("交易中")) {
                        prefix = "目前";
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(head + prefix + "收" + color));
        }
        m.appendTail(sb);
        String result = sb.toString();

        // ② K线 阴/阳 与涨跌幅符号不一致时纠正
        Pattern candleLine = Pattern.compile("(【[^】]+】[\\s\\S]*?)K线(阳线|阴线)");
        Matcher mk = candleLine.matcher(result);
        StringBuilder sbk = new StringBuilder();
        while (mk.find()) {
            String head = mk.group(1);
            String candle = mk.group(2);
            String correctCandle = candle;
            Matcher cm = codeInBrackets.matcher(head);
            if (cm.find()) {
                Double pct = chgByCode.get(cm.group(1));
                if (pct != null && pct != 0) {
                    boolean shouldYang = pct > 0;
                    if (shouldYang && "阴线".equals(candle)) {
                        correctCandle = "阳线";
                    } else if (!shouldYang && "阳线".equals(candle)) {
                        correctCandle = "阴线";
                    }
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

    /** 异步聊天：带多轮对话历史，支持追问 */
    public CompletableFuture<String> chatAsync(String systemPrompt, List<String> history) {
        // 在调用线程做 snapshot，避免后续修改导致 ConcurrentModificationException
        final List<String> snapshot = new ArrayList<>(history);
        return CompletableFuture.supplyAsync(() -> {
            JsonArray messages = new JsonArray();
            messages.add(buildMessage("system", systemPrompt));
            for (int i = 0; i < snapshot.size(); i++) {
                String role = (i % 2 == 0) ? "user" : "assistant";
                messages.add(buildMessage(role, snapshot.get(i)));
            }
            return callModel(messages);
        }, SHARED_EXECUTOR);
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
                // 兜底：返回整个 message 内容便于调试
                return "AI返回为空，message结构: " + message.toString();
            }
            if (json.has("error")) {
                return "API错误: " + json.getAsJsonObject("error").get("message").getAsString();
            }
        } catch (Exception e) {
            // parse error
        }
        return response;
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

package com.plugin.finance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    "你是一个专业A股/证券市场分析师，擅长基于实时行情数据进行技术分析。\n" +
                    "输出格式要求：\n" +
                    "【趋势】日内走势强弱判断（高开/低开/平开，K线形态，盘中位置）\n" +
                    "【关键位】支撑位与压力位（基于今日高低点计算）\n" +
                    "【操作】买入/持有/卖出/观望，给出具体止损位和目标位\n" +
                    "【风险】当前风险点提示\n" +
                    "最后给出组合整体评估（仓位建议、市场情绪、板块联动）。\n" +
                    "注意：不要使用任何markdown格式（不要加#号、**、*等），纯文本输出。语言专业、数据驱动，直接给结论。注意：以下为分析参考，不构成投资建议。";

    /** 构建自选股行情上下文（供 UI 展示与调用分析共用） */
    public static String buildMarketContext(List<QuoteItem> items, String marketName) {
        StringBuilder context = new StringBuilder("当前市场：").append(marketName).append("\n\n自选股实时行情：\n");
        for (int i = 0; i < items.size(); i++) {
            QuoteItem it = items.get(i);
            double range = it.getHighPrice() - it.getLowPrice();
            double rangePct = range > 0 ? range / it.getClosePrice() * 100 : 0;
            double posInRange = range > 0 ? (it.getPrice() - it.getLowPrice()) / range * 100 : 50;
            double gapFromOpen = it.getOpenPrice() > 0 ? (it.getPrice() - it.getOpenPrice()) / it.getOpenPrice() * 100 : 0;
            String candleType = "十字星";
            if (it.getClosePrice() > it.getOpenPrice()) candleType = "阳线";
            else if (it.getClosePrice() < it.getOpenPrice()) candleType = "阴线";
            double bodyPct = range > 0 ? Math.abs(it.getClosePrice() - it.getOpenPrice()) / range * 100 : 0;

            context.append(i + 1).append(". ").append(it.getName()).append("(").append(it.getCode()).append(")\n")
                    .append("   现价 ").append(it.getPrice())
                    .append("，涨跌幅 ").append(String.format("%+.2f%%", it.getChangePercent()))
                    .append("，涨跌额 ").append(String.format("%+.2f", it.getChangeAmount()))
                    .append("，今开 ").append(it.getOpenPrice())
                    .append("，昨收 ").append(it.getClosePrice())
                    .append("，最高 ").append(it.getHighPrice())
                    .append("，最低 ").append(it.getLowPrice())
                    .append("，振幅 ").append(String.format("%.2f%%", rangePct))
                    .append("，K线 ").append(candleType).append(String.format("(实体%.0f", bodyPct)).append("%)")
                    .append("，日内位置 ").append(String.format("%.0f%%", posInRange))
                    .append("，距开盘 ").append(String.format("%+.2f%%", gapFromOpen))
                    .append("\n");
        }
        return context.toString();
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
            String context = buildMarketContext(items, marketName);
            PluginLog.info("[AIService] analyzeWatchlistAsync calling callModel, context_len=" + context.length());
            String result = callModel(ANALYSIS_SYSTEM_PROMPT,
                "请分析以下自选股行情，逐只给出点评和操作建议：\n\n" + context);
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
            URL url = new URL(apiUrl);
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
                .replaceAll("\\n{3,}", "\n\n");
    }
}

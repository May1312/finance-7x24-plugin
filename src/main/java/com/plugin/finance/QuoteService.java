package com.plugin.finance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QuoteService {
    private static final String EASTMONEY_API_URL = "https://push2delay.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&fields=f12,f14,f2,f3,f4,f15,f16,f17,f18&secids=%s";
    private static final String TENCENT_API_URL = "https://qt.gtimg.cn/q=%s";
    private static final String SINA_API_URL = "https://hq.sinajs.cn/list=%s";

    private static final String REFERER_EASTMONEY = "https://push2delay.eastmoney.com/";
    private static final String REFERER_TENCENT = "https://qt.gtimg.cn/";
    private static final String REFERER_SINA = "https://finance.sina.com.cn/";

    private static final Logger LOG = Logger.getInstance(QuoteService.class);
    private String lastSource = "";

    public List<QuoteItem> fetchQuotes(List<String> codes) throws Exception {
        List<RequestedCode> requestedCodes = parseRequestedCodes(codes);
        if (requestedCodes.isEmpty()) {
            return List.of();
        }

        Map<String, QuoteItem> result = new LinkedHashMap<>();
        List<String> usedSources = new ArrayList<>();

        // 东方财富：仅 A股 / 港股
        List<RequestedCode> emCodes = requestedCodes.stream()
                .filter(rc -> rc.marketType == MarketType.A || rc.marketType == MarketType.HK)
                .collect(Collectors.toList());
        if (!emCodes.isEmpty()) {
            try {
                result.putAll(fetchEastmoneyQuotes(emCodes));
                if (!result.isEmpty()) {
                    usedSources.add("东方财富");
                }
            } catch (Exception e) {
                LOG.warn("东方财富行情请求失败", e);
            }
        }

        // 腾讯：补齐缺失（含美股/黄金）
        List<RequestedCode> missingTencent = requestedCodes.stream()
                .filter(rc -> !result.containsKey(rc.code))
                .collect(Collectors.toList());
        if (!missingTencent.isEmpty()) {
            Map<String, QuoteItem> tcItems = new LinkedHashMap<>();
            try {
                List<RequestedCode> normal = missingTencent.stream()
                        .filter(rc -> rc.marketType != MarketType.GOLD)
                        .collect(Collectors.toList());
                tcItems.putAll(fetchTencentQuotes(normal));
                List<RequestedCode> gold = missingTencent.stream()
                        .filter(rc -> rc.marketType == MarketType.GOLD)
                        .collect(Collectors.toList());
                tcItems.putAll(fetchTencentGoldQuotes(gold));
            } catch (Exception e) {
                LOG.warn("腾讯财经行情请求失败", e);
            }
            for (Map.Entry<String, QuoteItem> e : tcItems.entrySet()) {
                if (!result.containsKey(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                }
            }
            if (!tcItems.isEmpty()) {
                usedSources.add("腾讯财经");
            }
        }

        // 新浪：最终兜底
        List<RequestedCode> missingSina = requestedCodes.stream()
                .filter(rc -> !result.containsKey(rc.code))
                .collect(Collectors.toList());
        if (!missingSina.isEmpty()) {
            Map<String, QuoteItem> sinaItems = new LinkedHashMap<>();
            try {
                List<RequestedCode> normal = missingSina.stream()
                        .filter(rc -> rc.marketType != MarketType.GOLD)
                        .collect(Collectors.toList());
                sinaItems.putAll(fetchSinaQuotes(normal));
                List<RequestedCode> gold = missingSina.stream()
                        .filter(rc -> rc.marketType == MarketType.GOLD)
                        .collect(Collectors.toList());
                sinaItems.putAll(fetchSinaGoldQuotes(gold));
            } catch (Exception e) {
                LOG.warn("新浪财经行情请求失败", e);
            }
            for (Map.Entry<String, QuoteItem> e : sinaItems.entrySet()) {
                if (!result.containsKey(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                }
            }
            if (!sinaItems.isEmpty()) {
                usedSources.add("新浪财经");
            }
        }

        if (result.isEmpty()) {
            throw new IllegalStateException("No quote data returned");
        }
        lastSource = String.join(" + ", usedSources);

        // 按请求顺序返回
        List<QuoteItem> items = new ArrayList<>();
        for (RequestedCode rc : requestedCodes) {
            QuoteItem item = result.get(rc.code);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    public String getLastSource() {
        return lastSource;
    }

    private Map<String, QuoteItem> fetchEastmoneyQuotes(List<RequestedCode> requestedCodes) throws Exception {
        Map<String, QuoteItem> map = new LinkedHashMap<>();
        if (requestedCodes.isEmpty()) {
            return map;
        }
        List<String> secIds = new ArrayList<>();
        for (RequestedCode rc : requestedCodes) {
            secIds.add(rc.eastmoneyMarket + "." + rc.code);
        }

        JsonObject json = doJsonRequest(String.format(EASTMONEY_API_URL, String.join(",", secIds)), REFERER_EASTMONEY);
        JsonObject data = json.getAsJsonObject("data");
        if (data == null) {
            return map;
        }
        JsonArray diff = data.getAsJsonArray("diff");
        if (diff == null) {
            return map;
        }
        for (int i = 0; i < diff.size(); i++) {
            JsonObject quote = diff.get(i).getAsJsonObject();
            RequestedCode rc = findRequestedByCode(requestedCodes, getString(quote, "f12"));
            if (rc == null) {
                continue;
            }
            map.put(rc.code, new QuoteItem(
                    rc.displayCode,
                    getString(quote, "f14"),
                    getDouble(quote, "f2"),
                    getDouble(quote, "f3"),
                    getDouble(quote, "f4"),
                    getDouble(quote, "f17"),
                    getDouble(quote, "f18"),
                    getDouble(quote, "f15"),
                    getDouble(quote, "f16")
            ));
        }
        return map;
    }

    private Map<String, QuoteItem> fetchTencentQuotes(List<RequestedCode> requestedCodes) throws Exception {
        Map<String, QuoteItem> map = new LinkedHashMap<>();
        if (requestedCodes.isEmpty()) {
            return map;
        }
        String joined = requestedCodes.stream().map(rc -> rc.tencentCode).collect(Collectors.joining(","));
        String raw = doTextRequest(String.format(TENCENT_API_URL, joined), Charset.forName("GBK"), REFERER_TENCENT);
        Map<String, RequestedCode> byTc = new HashMap<>();
        for (RequestedCode rc : requestedCodes) {
            byTc.put(rc.tencentCode.toLowerCase(), rc);
        }
        for (String line : raw.split(";")) {
            String tcKey = extractVariableCode(line, "v_");
            if (tcKey.isEmpty()) {
                continue;
            }
            RequestedCode rc = byTc.get(tcKey);
            if (rc == null) {
                continue;
            }
            String payload = extractPayload(line);
            if (payload == null) {
                continue;
            }
            String[] fields = payload.split("~");
            if (fields.length < 33 || fields[1].isEmpty()) {
                continue;
            }
            double currentPrice = parseDouble(fields[3]);
            double closePrice = parseDouble(fields[4]);
            double openPrice = parseDouble(fields.length > 5 ? fields[5] : "");
            double highPrice = parseDouble(fields.length > 33 ? fields[33] : "");
            double lowPrice = parseDouble(fields.length > 34 ? fields[34] : "");
            map.put(rc.code, new QuoteItem(
                    rc.displayCode,
                    fields[1],
                    currentPrice,
                    parseDouble(fields[32]),
                    parseDouble(fields[31]),
                    openPrice,
                    closePrice,
                    highPrice,
                    lowPrice
            ));
        }
        return map;
    }

    private Map<String, QuoteItem> fetchTencentGoldQuotes(List<RequestedCode> requestedCodes) throws Exception {
        Map<String, QuoteItem> map = new LinkedHashMap<>();
        if (requestedCodes.isEmpty()) {
            return map;
        }
        List<RequestedCode> withTencent = requestedCodes.stream()
                .filter(rc -> rc.tencentCode != null && !rc.tencentCode.isEmpty())
                .collect(Collectors.toList());
        if (withTencent.isEmpty()) {
            return map;
        }
        String joined = withTencent.stream().map(rc -> rc.tencentCode).collect(Collectors.joining(","));
        String raw = doTextRequest(String.format(TENCENT_API_URL, joined), Charset.forName("GBK"), REFERER_TENCENT);
        Map<String, RequestedCode> byTc = new HashMap<>();
        for (RequestedCode rc : withTencent) {
            byTc.put(rc.tencentCode.toLowerCase(), rc);
        }
        for (String line : raw.split(";")) {
            String tcKey = extractVariableCode(line, "v_");
            if (tcKey.isEmpty()) {
                continue;
            }
            RequestedCode rc = byTc.get(tcKey);
            if (rc == null) {
                continue;
            }
            String payload = extractPayload(line);
            if (payload == null) {
                continue;
            }
            QuoteItem item = parseGoldFields(payload.split(","), rc.displayCode);
            if (item != null) {
                map.put(rc.code, item);
            }
        }
        return map;
    }

    private Map<String, QuoteItem> fetchSinaQuotes(List<RequestedCode> requestedCodes) throws Exception {
        Map<String, QuoteItem> map = new LinkedHashMap<>();
        if (requestedCodes.isEmpty()) {
            return map;
        }
        String joined = requestedCodes.stream().map(rc -> rc.sinaCode).collect(Collectors.joining(","));
        String raw = doTextRequest(String.format(SINA_API_URL, joined), Charset.forName("GBK"), REFERER_SINA);
        Map<String, RequestedCode> bySina = new HashMap<>();
        for (RequestedCode rc : requestedCodes) {
            bySina.put(rc.sinaCode.toLowerCase(), rc);
        }
        for (String line : raw.split(";")) {
            String sinaKey = extractVariableCode(line, "hq_str_");
            if (sinaKey.isEmpty()) {
                continue;
            }
            RequestedCode rc = bySina.get(sinaKey);
            if (rc == null) {
                continue;
            }
            String payload = extractPayload(line);
            if (payload == null) {
                continue;
            }
            String[] fields = payload.split(",");
            QuoteItem item;
            switch (rc.marketType) {
                case HK:
                    item = parseSinaHK(fields, rc.displayCode);
                    break;
                case US:
                    item = parseSinaUS(fields, rc.displayCode);
                    break;
                default:
                    item = parseSinaA(fields, rc.displayCode);
                    break;
            }
            if (item != null) {
                map.put(rc.code, item);
            }
        }
        return map;
    }

    private Map<String, QuoteItem> fetchSinaGoldQuotes(List<RequestedCode> requestedCodes) throws Exception {
        Map<String, QuoteItem> map = new LinkedHashMap<>();
        if (requestedCodes.isEmpty()) {
            return map;
        }
        List<RequestedCode> withSina = requestedCodes.stream()
                .filter(rc -> rc.sinaCode != null && !rc.sinaCode.isEmpty())
                .collect(Collectors.toList());
        if (withSina.isEmpty()) {
            return map;
        }
        String joined = withSina.stream().map(rc -> rc.sinaCode).collect(Collectors.joining(","));
        String raw = doTextRequest(String.format(SINA_API_URL, joined), Charset.forName("GBK"), REFERER_SINA);
        Map<String, RequestedCode> bySina = new HashMap<>();
        for (RequestedCode rc : withSina) {
            bySina.put(rc.sinaCode.toLowerCase(), rc);
        }
        for (String line : raw.split(";")) {
            String sinaKey = extractVariableCode(line, "hq_str_");
            if (sinaKey.isEmpty()) {
                continue;
            }
            RequestedCode rc = bySina.get(sinaKey);
            if (rc == null) {
                continue;
            }
            String payload = extractPayload(line);
            if (payload == null) {
                continue;
            }
            QuoteItem item = parseGoldFields(payload.split(","), rc.displayCode);
            if (item != null) {
                map.put(rc.code, item);
            }
        }
        return map;
    }

    private QuoteItem parseSinaA(String[] fields, String displayCode) {
        if (fields.length < 6 || fields[0].isEmpty()) {
            return null;
        }
        double open = parseDouble(fields[1]);
        double prevClose = parseDouble(fields[2]);
        double price = parseDouble(fields[3]);
        double high = parseDouble(fields.length > 4 ? fields[4] : "");
        double low = parseDouble(fields.length > 5 ? fields[5] : "");
        double change = price - prevClose;
        double pct = prevClose == 0 ? 0 : change / prevClose * 100;
        return new QuoteItem(displayCode, fields[0], price, pct, change, open, prevClose, high, low);
    }

    private QuoteItem parseSinaHK(String[] fields, String displayCode) {
        if (fields.length < 7 || fields[1].isEmpty()) {
            return null;
        }
        double open = parseDouble(fields[2]);
        double prevClose = parseDouble(fields[3]);
        double high = parseDouble(fields[4]);
        double low = parseDouble(fields[5]);
        double price = parseDouble(fields[6]);
        double change = price - prevClose;
        double pct = prevClose == 0 ? 0 : change / prevClose * 100;
        return new QuoteItem(displayCode, fields[1], price, pct, change, open, prevClose, high, low);
    }

    private QuoteItem parseSinaUS(String[] fields, String displayCode) {
        if (fields.length < 8 || fields[0].isEmpty()) {
            return null;
        }
        double price = parseDouble(fields[1]);
        double pct = parseDouble(fields[2]);
        double change = parseDouble(fields[4]);
        double open = parseDouble(fields[5]);
        double high = parseDouble(fields[6]);
        double low = parseDouble(fields[7]);
        double prevClose = price - change;
        return new QuoteItem(displayCode, fields[0], price, pct, change, open, prevClose, high, low);
    }

    private QuoteItem parseGoldFields(String[] fields, String displayCode) {
        if (fields.length < 8 || fields[0].isEmpty()) {
            return null;
        }
        double current = parseDouble(fields[0]);
        double prevClose = parseDouble(fields[7]);
        double open = parseDouble(fields.length > 2 ? fields[2] : "");
        double high = parseDouble(fields.length > 3 ? fields[3] : "");
        double low = parseDouble(fields.length > 5 ? fields[5] : "");
        double change = current - prevClose;
        double pct = prevClose == 0 ? 0 : change / prevClose * 100;
        String name = fields.length > 13 && !fields[13].isEmpty() ? fields[13] : displayCode;
        return new QuoteItem(displayCode, name, current, pct, change, open, prevClose, high, low);
    }

    private List<RequestedCode> parseRequestedCodes(List<String> codes) {
        List<RequestedCode> requestedCodes = new ArrayList<>();
        for (String code : codes) {
            RequestedCode requestedCode = parseRequestedCode(code);
            if (requestedCode != null) {
                requestedCodes.add(requestedCode);
            }
        }
        return requestedCodes;
    }

    private RequestedCode parseRequestedCode(String rawCode) {
        String c = rawCode == null ? "" : rawCode.trim().toLowerCase();
        if (c.isEmpty()) {
            return null;
        }

        // 黄金/贵金属：xau / gc / xag / si 等（内置品种库），兼容旧代码 xauusd / hf_xau / gold
        if ("xauusd".equals(c) || "hf_xau".equals(c) || "gold".equals(c)) {
            return new RequestedCode("xau", "XAU", MarketType.GOLD, -1, "hf_XAU", "hf_XAU");
        }
        GoldInstrument gold = GoldInstrument.fromCode(c);
        if (gold != null) {
            return new RequestedCode(gold.getCode(), gold.getCode().toUpperCase(), MarketType.GOLD, -1,
                    gold.getTencentCode(), gold.getSinaCode());
        }

        // 港股：hk00700 / 港股指数：hkHSI, hkHSCEI, hkHSCCI
        if (c.startsWith("hk") && c.length() > 2) {
            String code = c.substring(2);
            if (code.matches("\\d{5,6}") || code.matches("[a-z]{2,6}")) {
                String upper = code.toUpperCase();
                return new RequestedCode(code, upper, MarketType.HK, 116, "hk" + upper, "rt_hk" + upper);
            }
        }

        // 美股：usAAPL
        if (c.startsWith("us") && c.length() > 2 && c.substring(2).matches("[a-z0-9.]+")) {
            String code = c.substring(2).toLowerCase();
            String upper = code.toUpperCase();
            return new RequestedCode(code, upper, MarketType.US, -1, "us" + upper, "gb_" + code);
        }

        // A股：sh600000 / sz000001 / 纯6位数字
        String marketPrefix = "";
        if (c.startsWith("sh") || c.startsWith("sz")) {
            marketPrefix = c.substring(0, 2);
            c = c.substring(2);
        }
        if (!c.matches("\\d{6}")) {
            return null;
        }
        if (marketPrefix.isEmpty()) {
            marketPrefix = inferMarketPrefix(c);
        }
        int eastmoneyMarket = "sh".equals(marketPrefix) ? 1 : 0;
        return new RequestedCode(c, c, MarketType.A, eastmoneyMarket, marketPrefix + c, marketPrefix + c);
    }

    private RequestedCode findRequestedByCode(List<RequestedCode> list, String f12) {
        if (f12.isEmpty()) {
            return null;
        }
        for (RequestedCode rc : list) {
            if (rc.code.equals(f12)) {
                return rc;
            }
        }
        return null;
    }

    /** 从 "v_xxx=..." 或 "hq_str_xxx=..." 中提取变量名后缀 xxx */
    private String extractVariableCode(String line, String prefix) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return "";
        }
        String varName = line.substring(0, eq).trim();
        if (!varName.startsWith(prefix)) {
            return "";
        }
        return varName.substring(prefix.length()).toLowerCase();
    }

    private String extractPayload(String line) {
        int q1 = line.indexOf('"');
        int q2 = line.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) {
            return null;
        }
        return line.substring(q1 + 1, q2);
    }

    private JsonObject doJsonRequest(String urlStr, String referer) throws Exception {
        return JsonParser.parseString(doTextRequest(urlStr, StandardCharsets.UTF_8, referer)).getAsJsonObject();
    }

    private String doTextRequest(String urlStr, Charset charset, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Referer", referer);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("Quote request failed: HTTP " + responseCode);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    private String inferMarketPrefix(String code) {
        if ("000001".equals(code)) {
            return "sh";
        }
        return code.startsWith("5") || code.startsWith("6") || code.startsWith("9") ? "sh" : "sz";
    }

    private String getString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    private double getDouble(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsDouble();
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static class RequestedCode {
        final String code;
        final String displayCode;
        final MarketType marketType;
        final int eastmoneyMarket;
        final String tencentCode;
        final String sinaCode;

        RequestedCode(String code, String displayCode, MarketType marketType, int eastmoneyMarket,
                      String tencentCode, String sinaCode) {
            this.code = code;
            this.displayCode = displayCode;
            this.marketType = marketType;
            this.eastmoneyMarket = eastmoneyMarket;
            this.tencentCode = tencentCode;
            this.sinaCode = sinaCode;
        }
    }
}

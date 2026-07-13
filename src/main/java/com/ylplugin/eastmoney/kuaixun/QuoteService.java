package com.ylplugin.eastmoney.kuaixun;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class QuoteService {
    private static final String EASTMONEY_API_URL = "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&fields=f12,f14,f2,f3,f4&secids=%s";
    private static final String TENCENT_API_URL = "https://qt.gtimg.cn/q=%s";
    private static final String SINA_API_URL = "https://hq.sinajs.cn/list=%s";
    private String lastSource = "";

    public List<QuoteItem> fetchQuotes(List<String> codes) throws Exception {
        List<RequestedCode> requestedCodes = parseRequestedCodes(codes);
        if (requestedCodes.isEmpty()) {
            return List.of();
        }

        Exception lastException = null;
        try {
            List<QuoteItem> items = fetchEastmoneyQuotes(requestedCodes);
            if (!items.isEmpty()) {
                lastSource = "东方财富";
                return items;
            }
        } catch (Exception e) {
            lastException = e;
        }

        try {
            List<QuoteItem> items = fetchTencentQuotes(requestedCodes);
            if (!items.isEmpty()) {
                lastSource = "腾讯财经";
                return items;
            }
        } catch (Exception e) {
            lastException = e;
        }

        try {
            List<QuoteItem> items = fetchSinaQuotes(requestedCodes);
            if (!items.isEmpty()) {
                lastSource = "新浪财经";
                return items;
            }
        } catch (Exception e) {
            lastException = e;
        }

        throw lastException == null ? new IllegalStateException("No quote data returned") : lastException;
    }

    public String getLastSource() {
        return lastSource;
    }

    private List<QuoteItem> fetchEastmoneyQuotes(List<RequestedCode> requestedCodes) throws Exception {
        List<String> secIds = new ArrayList<>();
        for (RequestedCode code : requestedCodes) {
            secIds.add(code.eastmoneyMarket + "." + code.code);
        }

        String joined = String.join(",", secIds);
        JsonObject json = doJsonRequest(String.format(EASTMONEY_API_URL, joined));
        List<QuoteItem> items = new ArrayList<>();
        JsonObject data = json.getAsJsonObject("data");
        if (data == null) {
            return items;
        }
        JsonArray diff = data.getAsJsonArray("diff");
        if (diff == null) {
            return items;
        }
        for (int i = 0; i < diff.size(); i++) {
            JsonObject quote = diff.get(i).getAsJsonObject();
            items.add(new QuoteItem(
                    getString(quote, "f12"),
                    getString(quote, "f14"),
                    getDouble(quote, "f2"),
                    getDouble(quote, "f3"),
                    getDouble(quote, "f4")
            ));
        }
        return items;
    }

    private List<QuoteItem> fetchTencentQuotes(List<RequestedCode> requestedCodes) throws Exception {
        List<String> secIds = new ArrayList<>();
        for (RequestedCode code : requestedCodes) {
            secIds.add(code.marketPrefix + code.code);
        }

        String raw = doTextRequest(String.format(TENCENT_API_URL, String.join(",", secIds)), Charset.forName("GBK"));
        List<QuoteItem> items = new ArrayList<>();
        for (String line : raw.split(";")) {
            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (start < 0 || end <= start) {
                continue;
            }
            String[] fields = line.substring(start + 1, end).split("~");
            if (fields.length < 33 || fields[1].isEmpty()) {
                continue;
            }
            items.add(new QuoteItem(
                    fields[2],
                    fields[1],
                    parseDouble(fields[3]),
                    parseDouble(fields[32]),
                    parseDouble(fields[31])
            ));
        }
        return items;
    }

    private List<QuoteItem> fetchSinaQuotes(List<RequestedCode> requestedCodes) throws Exception {
        List<String> secIds = new ArrayList<>();
        for (RequestedCode code : requestedCodes) {
            secIds.add(code.marketPrefix + code.code);
        }

        String raw = doTextRequest(String.format(SINA_API_URL, String.join(",", secIds)), Charset.forName("GBK"));
        List<QuoteItem> items = new ArrayList<>();
        for (String line : raw.split(";")) {
            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (start < 0 || end <= start) {
                continue;
            }
            String[] fields = line.substring(start + 1, end).split(",");
            if (fields.length < 5 || fields[0].isEmpty()) {
                continue;
            }
            String code = extractSinaCode(line);
            double open = parseDouble(fields[1]);
            double previousClose = parseDouble(fields[2]);
            double price = parseDouble(fields[3]);
            double changeAmount = price - previousClose;
            double changePercent = previousClose == 0 ? 0 : changeAmount / previousClose * 100;
            items.add(new QuoteItem(code, fields[0], price == 0 ? open : price, changePercent, changeAmount));
        }
        return items;
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
        String normalized = rawCode == null ? "" : rawCode.trim().toLowerCase();
        String marketPrefix = "";
        if (normalized.startsWith("sh") || normalized.startsWith("sz")) {
            marketPrefix = normalized.substring(0, 2);
            normalized = normalized.substring(2);
        }
        if (!normalized.matches("\\d{6}")) {
            return null;
        }
        if (marketPrefix.isEmpty()) {
            marketPrefix = inferMarketPrefix(normalized);
        }
        int eastmoneyMarket = "sh".equals(marketPrefix) ? 1 : 0;
        return new RequestedCode(normalized, marketPrefix, eastmoneyMarket);
    }

    private JsonObject doJsonRequest(String urlStr) throws Exception {
        return JsonParser.parseString(doTextRequest(urlStr, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private String doTextRequest(String urlStr, Charset charset) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Referer", "https://finance.sina.com.cn/");
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

    private String extractSinaCode(String line) {
        int prefixIndex = line.indexOf("hq_str_");
        int equalsIndex = line.indexOf('=');
        if (prefixIndex >= 0 && equalsIndex > prefixIndex) {
            String secId = line.substring(prefixIndex + "hq_str_".length(), equalsIndex);
            if (secId.length() > 2) {
                return secId.substring(2);
            }
        }
        return "";
    }

    private static class RequestedCode {
        private final String code;
        private final String marketPrefix;
        private final int eastmoneyMarket;

        private RequestedCode(String code, String marketPrefix, int eastmoneyMarket) {
            this.code = code;
            this.marketPrefix = marketPrefix;
            this.eastmoneyMarket = eastmoneyMarket;
        }
    }
}

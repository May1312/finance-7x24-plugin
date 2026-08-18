package com.plugin.finance;

import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class StockSearchService {

    private static final Logger LOG = Logger.getInstance(StockSearchService.class);

    private static final String SEARCH_API = "https://searchadapter.eastmoney.com/api/suggest/get?input=%s&type=14&count=20&token=D43BF722C8E33BDC906FB84D85E326E8";

    public List<StockSearchItem> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String trimmed = keyword.trim();
        List<StockSearchItem> results = new ArrayList<>();

        // 内置黄金/贵金属品种（有行情源），混入搜索结果
        for (GoldInstrument g : GoldInstrument.matchInstruments(trimmed)) {
            results.add(new StockSearchItem(g.getCode(), g.getCode(), g.getDisplayName(), "GOLD", "黄金"));
        }

        try {
            String urlStr = String.format(SEARCH_API, URLEncoder.encode(trimmed, "UTF-8"));
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", "https://finance.eastmoney.com/");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return results;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            conn.disconnect();

            JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root.has("QuotationCodeTable")) {
                JsonObject table = root.getAsJsonObject("QuotationCodeTable");
                if (table.has("Data")) {
                    JsonArray data = table.getAsJsonArray("Data");
                    LinkedHashMap<String, StockSearchItem> unique = new LinkedHashMap<>();
                    for (StockSearchItem r : results) {
                        unique.put(r.getFullCode(), r);
                    }
                    for (JsonElement element : data) {
                        JsonObject item = element.getAsJsonObject();
                        String code = getString(item, "Code");
                        String name = getString(item, "Name");
                        String classify = getString(item, "Classify");
                        String mktNum = getString(item, "MktNum");
                        if (code.isEmpty() || name.isEmpty()) {
                            continue;
                        }

                        String fullCode;
                        String market;
                        String typeLabel;
                        if ("UsStock".equals(classify)) {
                            // 美股
                            if (!code.matches("[A-Za-z0-9.]+")) {
                                continue;
                            }
                            fullCode = "us" + code.toUpperCase();
                            market = "US";
                            typeLabel = "";
                        } else if ("HK".equals(classify)) {
                            // 港股
                            if (!code.matches("\\d{5,6}")) {
                                continue;
                            }
                            fullCode = "hk" + code;
                            market = "HK";
                            typeLabel = "";
                        } else {
                            // A股 / 基金 / 指数。注意：科创板（688xxx，如长鑫科技688825、中芯国际688981）
                            // 的 Classify 返回数字 "23" 而非 "AStock"，需一并放行，否则科创板全部搜不到
                            boolean isStock = "AStock".equals(classify) || "23".equals(classify);
                            boolean isFund = "Fund".equals(classify);
                            boolean isIndex = "Index".equals(classify);
                            if (!isStock && !isFund && !isIndex) {
                                continue;
                            }
                            if (!code.matches("\\d{6}")) {
                                continue;
                            }
                            market = "1".equals(mktNum) ? "sh" : "sz";
                            fullCode = market + code;
                            typeLabel = isFund ? "ETF" : (isIndex ? "指数" : "");
                        }
                        if (!unique.containsKey(fullCode)) {
                            unique.put(fullCode, new StockSearchItem(fullCode, code, name, market, typeLabel));
                        }
                    }
                    results = new ArrayList<>(unique.values());
                }
            }
        } catch (Exception e) {
            LOG.warn("Search failed for keyword: " + trimmed, e);
        }
        return results;
    }

    private String getString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    public static class StockSearchItem {
        private final String fullCode;
        private final String code;
        private final String name;
        private final String market;
        private final String typeLabel;

        public StockSearchItem(String fullCode, String code, String name, String market, String typeLabel) {
            this.fullCode = fullCode;
            this.code = code;
            this.name = name;
            this.market = market;
            this.typeLabel = typeLabel;
        }

        public String getFullCode() { return fullCode; }
        public String getCode() { return code; }
        public String getName() { return name; }
        public String getMarket() { return market; }
        public String getTypeLabel() { return typeLabel; }

        @Override
        public String toString() {
            return name + " (" + fullCode + ")";
        }
    }
}
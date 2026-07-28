package com.plugin.finance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class StockSearchService {

    private static final String SEARCH_API = "https://searchadapter.eastmoney.com/api/suggest/get?input=%s&type=14&count=20&token=D43BF722C8E33BDC906FB84D85E326E8";

    public List<StockSearchItem> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            String urlStr = String.format(SEARCH_API, URLEncoder.encode(keyword.trim(), "UTF-8"));
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", "https://finance.eastmoney.com/");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return new ArrayList<>();
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
                    for (JsonElement element : data) {
                        JsonObject item = element.getAsJsonObject();
                        String code = getString(item, "Code");
                        String name = getString(item, "Name");
                        String classify = getString(item, "Classify");
                        String mktNum = getString(item, "MktNum");
                        if (code.isEmpty() || name.isEmpty() || !code.matches("\\d{6}")) {
                            continue;
                        }
                        boolean isStock = "AStock".equals(classify);
                        boolean isFund = "Fund".equals(classify);
                        boolean isIndex = "Index".equals(classify);
                        if (!isStock && !isFund && !isIndex) {
                            continue;
                        }
                        String type = "1".equals(mktNum) ? "sh" : "sz";
                        String fullCode = type + code;
                        String typeLabel = isFund ? "ETF" : (isIndex ? "指数" : "");
                        if (!unique.containsKey(code)) {
                            unique.put(code, new StockSearchItem(fullCode, code, name, type, typeLabel));
                        }
                    }
                    return new ArrayList<>(unique.values());
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
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
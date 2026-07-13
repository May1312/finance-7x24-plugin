package com.ylplugin.eastmoney.kuaixun;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class KuaixunService {
    private static final String API_URL = "http://newsapi.eastmoney.com/kuaixun/v2/api/list?column=102&p=%d&limit=50";
    private static final String API_CHECK_URL = "http://newsapi.eastmoney.com/kuaixun/v2/api/list?column=102&id=%s&count=count";
    private final Gson gson = new Gson();

    public List<KuaixunItem> fetchPage(int page) throws Exception {
        String urlStr = String.format(API_URL, page);
        JsonObject json = doRequest(urlStr);
        return parseResult(json);
    }

    public int checkNewCount(String latestId) throws Exception {
        String urlStr = String.format(API_CHECK_URL, latestId);
        JsonObject json = doRequest(urlStr);
        if (json != null) {
            int rc = json.get("rc").getAsInt();
            if (rc == 1) {
                return json.get("count").getAsInt();
            }
        }
        return 0;
    }

    private JsonObject doRequest(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String raw = sb.toString().trim();
        if (raw.startsWith("(") && raw.endsWith(")")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        int idx = raw.indexOf('(');
        while (idx > 0 && idx < 20) {
            if (raw.endsWith(")")) {
                raw = raw.substring(idx + 1, raw.length() - 1);
                break;
            }
            idx = raw.indexOf('(', idx + 1);
        }
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private List<KuaixunItem> parseResult(JsonObject json) {
        List<KuaixunItem> items = new ArrayList<>();
        if (json == null) return items;
        int rc = json.get("rc").getAsInt();
        if (rc != 1) return items;
        JsonArray news = json.getAsJsonArray("news");
        if (news == null) return items;
        for (int i = 0; i < news.size(); i++) {
            JsonObject n = news.get(i).getAsJsonObject();
            items.add(new KuaixunItem(
                    getString(n, "id"),
                    getString(n, "title"),
                    getString(n, "digest"),
                    getString(n, "showtime"),
                    n.get("newstype").getAsInt(),
                    getString(n, "newsid")
            ));
        }
        return items;
    }

    private String getString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}

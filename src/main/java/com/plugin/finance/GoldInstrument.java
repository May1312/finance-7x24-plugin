package com.plugin.finance;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置黄金/贵金属品种库。
 * 行情源（腾讯/新浪）仅支持 hf_ 系列代码，东财搜索返回的品种代码无法取行情，
 * 因此动态添加时以本库为数据来源，搜索关键词匹配品种后混入搜索结果。
 */
public enum GoldInstrument {
    LONDON_GOLD("xau", "伦敦金", "hf_XAU", "hf_XAU",
            List.of("xau", "xauusd", "伦敦金", "现货黄金", "伦敦现货黄金", "黄金现货")),
    NY_GOLD("gc", "纽约金", "hf_GC", "hf_GC",
            List.of("gc", "gc00y", "comex", "纽约金", "纽约黄金", "comex黄金", "纽约金主力", "美黄金")),
    LONDON_SILVER("xag", "伦敦银", "hf_XAG", "hf_XAG",
            List.of("xag", "伦敦银", "现货白银", "伦敦现货白银")),
    NY_SILVER("si", "纽约白银", "hf_SI", "hf_SI",
            List.of("si", "纽约白银", "comex白银", "美白银", "纽银"));

    private final String code;
    private final String displayName;
    private final String tencentCode;
    private final String sinaCode;
    private final List<String> keywords;

    GoldInstrument(String code, String displayName, String tencentCode, String sinaCode, List<String> keywords) {
        this.code = code;
        this.displayName = displayName;
        this.tencentCode = tencentCode;
        this.sinaCode = sinaCode;
        this.keywords = keywords;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getTencentCode() { return tencentCode; }
    public String getSinaCode() { return sinaCode; }

    public static GoldInstrument fromCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        for (GoldInstrument g : values()) {
            if (g.code.equals(c)) return g;
        }
        return null;
    }

    /** 关键词是否命中该品种（代码或别名包含匹配） */
    public boolean matches(String keyword) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        if (k.isEmpty()) return false;
        if (k.equals(code)) return true;
        if (k.length() < 2) return false;
        for (String kw : keywords) {
            if (kw.equals(k) || kw.contains(k) || k.contains(kw)) return true;
        }
        return false;
    }

    /** 返回与关键词匹配的品种；"黄金"/"贵金属"/"gold" 等泛词返回全部 */
    public static List<GoldInstrument> matchInstruments(String keyword) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        if (k.isEmpty()) return List.of();
        boolean matchAll = k.contains("黄金") || k.contains("贵金属") || k.equals("gold");
        List<GoldInstrument> result = new ArrayList<>();
        for (GoldInstrument g : values()) {
            if (matchAll || g.matches(k)) result.add(g);
        }
        return result;
    }
}

package com.plugin.finance;

public class CodeUtils {

    public static String normalize(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        if (c.isEmpty()) return null;

        if ("xauusd".equals(c) || "hf_xau".equals(c) || "gold".equals(c)) {
            return "xau";
        }
        GoldInstrument gold = GoldInstrument.fromCode(c);
        if (gold != null) {
            return gold.getCode();
        }

        if (c.startsWith("hk") && c.length() > 2 && (c.substring(2).matches("\\d{5,6}") || c.substring(2).matches("[a-z]{2,6}"))) {
            return c;
        }

        if (c.startsWith("us") && c.length() > 2 && c.substring(2).matches("[a-z0-9.]+")) {
            return c;
        }

        if (c.matches("(sh|sz)?\\d{6}")) {
            if (!c.startsWith("sh") && !c.startsWith("sz")) {
                c = c.startsWith("5") || c.startsWith("6") || c.startsWith("9") ? "sh" + c : "sz" + c;
            }
            return c;
        }

        return null;
    }
}
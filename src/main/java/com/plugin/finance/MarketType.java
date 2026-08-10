package com.plugin.finance;

public enum MarketType {
    A("A股"),
    HK("港股"),
    US("美股"),
    GOLD("黄金");

    private final String displayName;

    MarketType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static MarketType fromCode(String code) {
        if (code == null) {
            return A;
        }
        String c = code.trim().toLowerCase();
        if (c.length() > 2 && c.startsWith("hk") && (c.substring(2).matches("\\d{5,6}") || c.substring(2).matches("[a-z]{2,6}"))) {
            return HK;
        }
        if (c.length() > 2 && c.startsWith("us") && c.substring(2).matches("[a-z0-9.]+")) {
            return US;
        }
        if (GoldInstrument.fromCode(c) != null) {
            return GOLD;
        }
        if (c.startsWith("xau") || c.startsWith("hf_") || c.startsWith("gold")) {
            return GOLD;
        }
        return A;
    }
}

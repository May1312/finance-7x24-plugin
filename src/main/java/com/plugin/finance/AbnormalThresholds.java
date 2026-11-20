package com.plugin.finance;

import com.intellij.ide.util.PropertiesComponent;

/** 个股异动阈值（%）：按自选代码存应用级配置，未设置走默认值 */
public final class AbnormalThresholds {

    public static final double DEFAULT = 3.0;
    private static final String KEY_PREFIX = "eastmoney.7x24.watchlist.abnormal.";

    private AbnormalThresholds() {}

    public static double get(String code) {
        Double setting = getSetting(code);
        return setting == null ? DEFAULT : setting;
    }

    /** 未设置返回 null */
    public static Double getSetting(String code) {
        String raw = PropertiesComponent.getInstance().getValue(KEY_PREFIX + code);
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** value 为 null 时清除设置，恢复默认 */
    public static void set(String code, Double value) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (value == null) {
            properties.unsetValue(KEY_PREFIX + code);
        } else {
            properties.setValue(KEY_PREFIX + code, String.valueOf(value));
        }
    }
}
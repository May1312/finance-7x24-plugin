package com.plugin.finance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 各市场开市时段判断（不含法定节假日），供自选页轮询与状态栏行情共用 */
public final class MarketHours {

    private MarketHours() {}

    public static boolean isOpen(MarketType market) {
        return switch (market) {
            case A -> isAShareOpen();
            case HK -> isHKOpen();
            case US -> isUSOpen();
            case GOLD -> isGoldOpen();
        };
    }

    public static boolean isAShareOpen() {
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        DayOfWeek dow = LocalDate.now(sh).getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now(sh);
        boolean morning = !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(11, 30));
        boolean afternoon = !now.isBefore(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(15, 0));
        return morning || afternoon;
    }

    public static boolean isHKOpen() {
        ZoneId hk = ZoneId.of("Asia/Hong_Kong");
        DayOfWeek dow = LocalDate.now(hk).getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime now = LocalTime.now(hk);
        boolean morning = !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(12, 0));
        boolean afternoon = !now.isBefore(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(16, 0));
        return morning || afternoon;
    }

    public static boolean isUSOpen() {
        ZoneId ny = ZoneId.of("America/New_York");
        ZonedDateTime now = ZonedDateTime.now(ny);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0));
    }

    public static boolean isGoldOpen() {
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        ZoneId london = ZoneId.of("Europe/London");
        ZonedDateTime now = ZonedDateTime.now(sh);
        DayOfWeek dow = now.getDayOfWeek();
        LocalTime t = now.toLocalTime();
        boolean dst = london.getRules().isDaylightSavings(now.toInstant());
        LocalTime open = dst ? LocalTime.of(6, 0) : LocalTime.of(7, 0);
        LocalTime close = dst ? LocalTime.of(5, 0) : LocalTime.of(6, 0);

        if (dow == DayOfWeek.SUNDAY) return false;
        if (dow == DayOfWeek.SATURDAY) return t.isBefore(close);
        if (dow == DayOfWeek.MONDAY) return !t.isBefore(open);
        return !t.isBefore(open) || t.isBefore(close);
    }
}
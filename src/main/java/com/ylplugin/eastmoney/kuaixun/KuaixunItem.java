package com.ylplugin.eastmoney.kuaixun;

public class KuaixunItem {
    private final String id;
    private final String title;
    private final String digest;
    private final String showtime;
    private final int newstype;
    private final String newsid;

    public KuaixunItem(String id, String title, String digest, String showtime, int newstype, String newsid) {
        this.id = id;
        this.title = title;
        this.digest = digest;
        this.showtime = showtime;
        this.newstype = newstype;
        this.newsid = newsid;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDigest() { return digest; }
    public String getShowtime() { return showtime; }
    public String getDisplayText() { return getDisplayTime() + "  " + (digest.isEmpty() ? title : digest); }
    public String getDisplayTimeText() { return getDisplayTime(); }
    public String getContentText() { return digest.isEmpty() ? title : digest; }
    public String getDetailUrl() {
        if (newstype == 1 || newstype == 2) {
            return "http://wap.eastmoney.com/3g/news/article,8,365,1," + newsid + ".shtml";
        }
        return "";
    }

    private String getDisplayTime() {
        if (showtime != null && showtime.length() >= 16) {
            return showtime.substring(11, 16);
        }
        return showtime == null ? "" : showtime;
    }
}

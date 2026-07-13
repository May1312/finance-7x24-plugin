package com.ylplugin.eastmoney.kuaixun;

public class QuoteItem {
    private final String code;
    private final String name;
    private final double price;
    private final double changePercent;
    private final double changeAmount;

    public QuoteItem(String code, String name, double price, double changePercent, double changeAmount) {
        this.code = code;
        this.name = name;
        this.price = price;
        this.changePercent = changePercent;
        this.changeAmount = changeAmount;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public double getChangePercent() { return changePercent; }
    public double getChangeAmount() { return changeAmount; }
}

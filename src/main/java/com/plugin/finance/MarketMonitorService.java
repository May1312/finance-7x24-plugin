package com.plugin.finance;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 状态栏行情与快讯关联标记共用的低频行情快照。
 * 独立于工具窗口轮询：开市约 60 秒一次，闭市降为 5 分钟一次（仅为维护自选名称与最新收盘价）。
 * 只做静默展示，不产生任何系统通知。
 */
public final class MarketMonitorService {

    private static final long POLL_INTERVAL_SEC = 60;
    private static final long CLOSED_REFRESH_MS = 5 * 60_000L;
    private static final String CODES_KEY = "eastmoney.7x24.watchlist.codes";
    /** 大盘只展示 A 股三大指数 */
    private static final List<String> INDEX_CODES = List.of("sh000001", "sz399001", "sz399006");
    private static final List<String> INDEX_NAMES = List.of("上证指数", "深证成指", "创业板指");

    private final QuoteService quoteService = new QuoteService();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = Snapshot.notLoaded();
    private volatile long lastFetchAt = 0;
    private volatile boolean forceNext = false;

    public static MarketMonitorService getInstance() {
        return ApplicationManager.getApplication().getService(MarketMonitorService.class);
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    /** 订阅快照更新，回调在 EDT 触发；首个订阅者启动轮询 */
    public void addListener(Runnable listener) {
        listeners.add(listener);
        if (started.compareAndSet(false, true)) {
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                    this::triggerPoll, 2, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** 阈值等设置变更后立即重算快照，跳过闭市低频间隔 */
    public void refreshNow() {
        forceNext = true;
        triggerPoll();
    }

    private void triggerPoll() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                poll();
            } catch (Exception e) {
                PluginLog.info("[MarketMonitor] 行情快照拉取失败: " + e.getMessage());
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void poll() throws Exception {
        List<String> codes = watchlistCodes();
        boolean anyOpen = false;
        for (MarketType m : MarketType.values()) {
            if (MarketHours.isOpen(m)) {
                anyOpen = true;
                break;
            }
        }

        // 闭市时低频维护即可；首次（lastFetchAt=0）仍立即拉一次
        boolean force = forceNext;
        forceNext = false;
        if (!anyOpen && !force && lastFetchAt > 0 && System.currentTimeMillis() - lastFetchAt < CLOSED_REFRESH_MS) {
            return;
        }

        // 自选 + A 股大盘指数合并为一次批量请求
        LinkedHashSet<String> fetchCodes = new LinkedHashSet<>(codes);
        fetchCodes.addAll(INDEX_CODES);

        List<QuoteItem> items = quoteService.fetchQuotes(new ArrayList<>(fetchCodes));
        lastFetchAt = System.currentTimeMillis();

        Map<String, QuoteItem> itemMap = new HashMap<>();
        for (QuoteItem item : items) {
            itemMap.put(CodeUtils.stripPrefix(item.getCode()), item);
        }
        List<QuoteItem> watchItems = new ArrayList<>();
        List<QuoteItem> abnormal = new ArrayList<>();
        List<Double> abnormalThresholds = new ArrayList<>();
        for (String code : codes) {
            QuoteItem item = itemMap.get(CodeUtils.stripPrefix(code));
            if (item == null) {
                continue;
            }
            watchItems.add(item);
            // 阈值按自选存储代码查；行情源返回的是裸码/大写展示码，不能直接当键
            double threshold = AbnormalThresholds.get(code);
            if (Math.abs(item.getChangePercent()) >= threshold) {
                abnormal.add(item);
                abnormalThresholds.add(threshold);
            }
        }
        List<QuoteItem> indexItems = new ArrayList<>();
        List<String> indexNames = new ArrayList<>();
        for (int i = 0; i < INDEX_CODES.size(); i++) {
            QuoteItem item = itemMap.get(CodeUtils.stripPrefix(INDEX_CODES.get(i)));
            if (item != null) {
                indexItems.add(item);
                indexNames.add(INDEX_NAMES.get(i));
            }
        }
        snapshot = new Snapshot(watchItems, abnormal, abnormalThresholds,
                indexItems, indexNames, MarketHours.isAShareOpen());
        notifyListeners();
    }

    private void notifyListeners() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    private static List<String> watchlistCodes() {
        String raw = PropertiesComponent.getInstance().getValue(CODES_KEY, "");
        List<String> codes = new ArrayList<>();
        if (raw.isEmpty()) {
            return codes;
        }
        for (String s : raw.split("[,，\\s]+")) {
            String normalized = CodeUtils.normalize(s.trim());
            if (normalized != null && !codes.contains(normalized)) {
                codes.add(normalized);
            }
        }
        return codes;
    }

    /** 一次轮询的只读快照 */
    public static final class Snapshot {
        public final List<QuoteItem> watchItems;
        public final List<QuoteItem> abnormalItems;
        /** 与 abnormalItems 同序的触发阈值（%） */
        public final List<Double> abnormalThresholds;
        /** A 股三大指数行情；闭市时为最近收盘 */
        public final List<QuoteItem> indexItems;
        /** 与 indexItems 同序的指数全称（上证指数/深证成指/创业板指） */
        public final List<String> indexNames;
        public final boolean aShareOpen;
        public final boolean loaded;
        private final List<String> watchNames;

        Snapshot(List<QuoteItem> watchItems, List<QuoteItem> abnormalItems,
                 List<Double> abnormalThresholds, List<QuoteItem> indexItems,
                 List<String> indexNames, boolean aShareOpen) {
            this.watchItems = List.copyOf(watchItems);
            this.abnormalItems = List.copyOf(abnormalItems);
            this.abnormalThresholds = List.copyOf(abnormalThresholds);
            this.indexItems = List.copyOf(indexItems);
            this.indexNames = List.copyOf(indexNames);
            this.aShareOpen = aShareOpen;
            List<String> names = new ArrayList<>();
            for (QuoteItem item : watchItems) {
                String name = item.getName() == null ? "" : item.getName().trim();
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
            this.watchNames = List.copyOf(names);
            this.loaded = true;
        }

        private Snapshot() {
            this.watchItems = List.of();
            this.abnormalItems = List.of();
            this.abnormalThresholds = List.of();
            this.indexItems = List.of();
            this.indexNames = List.of();
            this.aShareOpen = false;
            this.watchNames = List.of();
            this.loaded = false;
        }

        static Snapshot notLoaded() {
            return new Snapshot();
        }

        /** 快讯标题/摘要中命中的自选名称（按自选顺序去重） */
        public List<String> relatedNames(String title, String digest) {
            String text = (title == null ? "" : title) + "\n" + (digest == null ? "" : digest);
            List<String> hit = new ArrayList<>();
            for (String name : watchNames) {
                if (text.contains(name)) {
                    hit.add(name);
                }
            }
            return hit;
        }
    }
}
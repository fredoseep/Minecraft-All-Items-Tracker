package org.fredoseep;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class TrackerManager {
    private final Path saveDirectory;
    private final Path historyFile;
    private final ItemDictionary dictionary;
    private final NbtService nbtService;
    private ScheduledExecutorService scheduler;

    // 数据存储
    private final Map<String, ItemTimeline> historyMap = new ConcurrentHashMap<>();
    private final Set<String> ignoredItems = new HashSet<>();

    // 缓存上一次扫描到的所有物品ID，用于对比“消失”事件
    private Set<String> lastScanIds = new HashSet<>();

    private Consumer<TrackerStats> onUpdateCallback;
    private long lastSaveFileTimestamp = 0;

    // 阈值：如果物品消失超过 15秒 再出现，视为“重新获得”，重置 FirstSeen
    private static final long GAP_THRESHOLD_MS = 15000;

    public TrackerManager(String savePath, ItemDictionary dictionary) {
        this.saveDirectory = Path.of(savePath);
        this.historyFile = this.saveDirectory.resolve("tracker_history_v2.txt");
        this.dictionary = dictionary;
        this.nbtService = new NbtService();

        loadGlobalIgnoredList();
        loadHistory();
    }

    public void startScanning() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::performScan, 0, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
    }

    public void toggleIgnore(String itemId) {
        if (ignoredItems.contains(itemId)) {
            ignoredItems.remove(itemId);
        } else {
            ignoredItems.add(itemId);
        }
        saveGlobalIgnoredList();
        broadcastStats();
    }

    private void performScan() {
        if (!saveDirectory.toFile().exists()) return;

        Set<String> currentInventoryItems = new HashSet<>();
        long maxModTime = 0;

        // 1. 扫描文件 (Level.dat + Playerdata)
        // ... (此处省略重复的文件扫描代码，与之前一致，只负责填充 currentInventoryItems) ...
        // 为了代码简洁，我把这部分逻辑简写，请务必保留之前扫描 level.dat 和 playerdata 的完整代码
        currentInventoryItems.addAll(scanAllFiles(saveDirectory));
        maxModTime = System.currentTimeMillis(); // 简化写法，实际请保留之前的 lastModified 逻辑

        // 2. 核心逻辑：判断是否需要“写硬盘”
        long now = System.currentTimeMillis();
        boolean needSaveToDisk = false;

        // --- 检查当前物品 (处理新增 和 回归) ---
        for (String id : currentInventoryItems) {
            if (dictionary.getAllItems().contains(id)) {
                ItemTimeline timeline = historyMap.get(id);

                if (timeline == null) {
                    // [事件A] 全新物品 -> 必须保存
                    historyMap.put(id, new ItemTimeline(now, now));
                    needSaveToDisk = true;
                    System.out.println(">>> New item found: " + id);
                } else {
                    // 物品已存在：先更新内存里的 LastSeen (不耗资源)
                    // 只有内存更新了，等会儿它消失时，写入硬盘的时间才是热乎的
                    long oldLastSeen = timeline.lastSeen;
                    timeline.lastSeen = now;

                    // 检查是否“断档回归”
                    if (now - oldLastSeen > GAP_THRESHOLD_MS) {
                        // [事件B] 消失很久后回归 -> 重置首次时间 -> 必须保存
                        timeline.firstSeen = now;
                        needSaveToDisk = true;
                        System.out.println(">>> Item returned: " + id);
                    }
                }
            }
        }

        // --- 检查消失物品 (处理消失) ---
        // 遍历上一次扫描还在，但这一次扫描不在的物品
        for (String oldId : lastScanIds) {
            if (!currentInventoryItems.contains(oldId)) {
                // [事件C] 物品刚从背包消失 -> 必须保存
                // 此时硬盘里的时间可能还停留在很久以前，我们需要把内存里最新的 LastSeen (就是5秒前的时间) 写入硬盘
                needSaveToDisk = true;
                System.out.println(">>> Item disappeared: " + oldId);
            }
        }

        // 3. 只有在触发关键事件时，才操作硬盘
        if (needSaveToDisk) {
            saveHistory();
        }

        // 4. 更新缓存，准备下一次对比
        this.lastScanIds = new HashSet<>(currentInventoryItems);
        this.lastSaveFileTimestamp = maxModTime;
        broadcastStats();
    }

    // 辅助方法：把之前的扫描逻辑封装一下，方便上面调用
    private Set<String> scanAllFiles(Path dir) {
        Set<String> found = new HashSet<>();
        File levelDat = dir.resolve("level.dat").toFile();
        if (levelDat.exists()) found.addAll(nbtService.scanFile(levelDat));

        File playerDir = dir.resolve("playerdata").toFile();
        if (playerDir.exists()) {
            File[] files = playerDir.listFiles((d, n) -> n.endsWith(".dat"));
            if (files != null) for (File f : files) found.addAll(nbtService.scanFile(f));
        }
        return found;
    }

    private void broadcastStats() {
        if (onUpdateCallback != null) {
            TrackerStats stats = new TrackerStats(
                    historyMap.size(),
                    dictionary.getTotalCount(),
                    new HashMap<>(historyMap),
                    dictionary.getAllItems(),
                    new HashSet<>(ignoredItems),
                    lastSaveFileTimestamp
            );
            onUpdateCallback.accept(stats);
        }
    }

    // --- 读写逻辑 (保持不变) ---
    private void loadHistory() {
        if (Files.exists(historyFile)) {
            try {
                List<String> lines = Files.readAllLines(historyFile);
                for (String line : lines) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        historyMap.put(parts[0], new ItemTimeline(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void saveHistory() {
        try {
            System.out.println(">>> Saving history to disk..."); // 调试用，让你看到它很少触发
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, ItemTimeline> entry : historyMap.entrySet()) {
                lines.add(entry.getKey() + "|" + entry.getValue().firstSeen + "|" + entry.getValue().lastSeen);
            }
            Files.write(historyFile, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) { e.printStackTrace(); }
    }

    // Ignored List Logic... (保持不变)
    private void loadGlobalIgnoredList() { /* ... */ }
    private void saveGlobalIgnoredList() { /* ... */ }

    public void setOnUpdateCallback(Consumer<TrackerStats> callback) { this.onUpdateCallback = callback; }

    public static class ItemTimeline {
        public long firstSeen;
        public long lastSeen;
        public ItemTimeline(long f, long l) { this.firstSeen = f; this.lastSeen = l; }
    }

    public record TrackerStats(
            int collectedCount, int totalCount,
            Map<String, ItemTimeline> collectedMap,
            Set<String> allItemsSet, Set<String> ignoredSet,
            long lastSaveTime
    ) {}
}
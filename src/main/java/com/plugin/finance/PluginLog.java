package com.plugin.finance;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 简单的文件日志工具，写入 ~/finance_plugin_ai.log
 */
public class PluginLog {

    private static final Path LOG_PATH = Paths.get(System.getProperty("user.home"), "finance_plugin_ai.log");
    /** 超过阈值就滚动成 .1，避免长期使用后日志无限增长 */
    private static final long MAX_LOG_BYTES = 5L * 1024 * 1024;
    /** 体积检查的触发间隔：每次写日志都 stat 一次太浪费，按累计写入量粗略触发 */
    private static final long ROTATE_CHECK_BYTES = 256L * 1024;
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static boolean dirReady = false;
    private static long writtenSinceCheck = 0;

    public static synchronized void info(String msg) {
        write("INFO", msg, null);
    }

    public static synchronized void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static void write(String level, String msg, Throwable t) {
        try {
            if (!dirReady) {
                Files.createDirectories(LOG_PATH.getParent());
                rotateIfNeeded();
                dirReady = true;
            }
            writtenSinceCheck += (msg == null ? 0 : msg.length()) + (t == null ? 0 : 1024);
            if (writtenSinceCheck >= ROTATE_CHECK_BYTES) {
                writtenSinceCheck = 0;
                rotateIfNeeded();
            }
            try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_PATH.toFile(), true))) {
                pw.println(LocalDateTime.now().format(TF) + " [" + level + "] " + msg);
                if (t != null) {
                    t.printStackTrace(pw);
                }
                pw.flush();
            }
        } catch (IOException ignored) {
            // 日志写入失败不阻塞业务
        }
    }

    private static void rotateIfNeeded() throws IOException {
        if (!Files.exists(LOG_PATH) || Files.size(LOG_PATH) <= MAX_LOG_BYTES) {
            return;
        }
        Files.move(LOG_PATH, LOG_PATH.resolveSibling(LOG_PATH.getFileName() + ".1"),
                StandardCopyOption.REPLACE_EXISTING);
    }
}

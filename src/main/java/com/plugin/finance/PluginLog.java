package com.plugin.finance;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 简单的文件日志工具，写入 ~/finance_plugin_ai.log
 */
public class PluginLog {

    private static final Path LOG_PATH = Paths.get(System.getProperty("user.home"), "finance_plugin_ai.log");
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static synchronized void info(String msg) {
        write("INFO", msg, null);
    }

    public static synchronized void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static void write(String level, String msg, Throwable t) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
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
}

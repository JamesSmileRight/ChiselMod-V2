package com.chiselranks.staff;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StaffLogger {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaPlugin plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ChiselStaff-Logger");
        thread.setDaemon(true);
        return thread;
    });
    private final Path logPath;

    public StaffLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logPath = plugin.getDataFolder().toPath().resolve("staff-actions.log");
    }

    public void log(String action) {
        executor.execute(() -> {
            try {
                Files.createDirectories(logPath.getParent());
                String line = '[' + LocalDateTime.now().format(FORMATTER) + "] " + action + System.lineSeparator();
                Files.writeString(logPath, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to write staff log: " + exception.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
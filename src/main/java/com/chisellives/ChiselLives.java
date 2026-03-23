package com.chisellives;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.logging.Level;

public final class ChiselLives {

    private final JavaPlugin plugin;

    private DatabaseManager databaseManager;
    private LivesManager livesManager;
    private PurchaseChecker purchaseChecker;
    private BanManager banManager;
    private RevivalTotemManager revivalTotemManager;
    private RevivalRecipe revivalRecipe;
    private PlayerListener playerListener;
    private LivesGUI livesGUI;
    private RevivalListener revivalListener;

    public ChiselLives(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enable() {
        databaseManager = new DatabaseManager(plugin);
        if (!databaseManager.initialize()) {
            return false;
        }

        banManager = new BanManager(plugin);
        livesManager = new LivesManager(plugin, databaseManager, banManager);
        livesGUI = new LivesGUI(plugin, livesManager);
        playerListener = new PlayerListener(livesManager);
        revivalTotemManager = new RevivalTotemManager(plugin, databaseManager, livesManager, banManager);
        livesManager.setRevivalTotemSupplier(revivalTotemManager::createRevivalTotemItem);
        revivalRecipe = new RevivalRecipe(plugin, revivalTotemManager);
        revivalListener = new RevivalListener(plugin, revivalTotemManager);

        plugin.getServer().getPluginManager().registerEvents(playerListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(livesGUI, plugin);
        plugin.getServer().getPluginManager().registerEvents(revivalListener, plugin);
        revivalRecipe.register();

        PluginCommand livesCommand = plugin.getCommand("lives");
        if (livesCommand == null) {
            plugin.getLogger().severe("[ChiselLives] /lives command is missing from plugin.yml.");
            disable();
            return false;
        }

        livesCommand.setExecutor((CommandSender sender, org.bukkit.command.Command command, String label, String[] args) -> {
            if (args.length >= 1) {
                String subcommand = args[0].toLowerCase(Locale.ROOT);
                switch (subcommand) {
                    case "revive" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("Only players can use this command.");
                            return true;
                        }

                        if (args.length < 2) {
                            player.sendMessage("Usage: /lives revive <player>");
                            return true;
                        }

                        revivalTotemManager.tryReviveByUsername(player, args[1]);
                        return true;
                    }
                    case "check" -> {
                        if (!hasAdminPermission(sender)) {
                            sender.sendMessage("You do not have permission to use lives admin commands.");
                            return true;
                        }

                        if (args.length < 2) {
                            sender.sendMessage("Usage: /lives check <player>");
                            return true;
                        }

                        String targetName = args[1];
                        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                        livesManager.getPlayerRecord(target.getUniqueId(), targetName)
                                .thenAccept(record -> runSync(() -> sender.sendMessage(
                                        "[ChiselLives] " + displayName(record, targetName)
                                                + " has " + record.lives() + " lives"
                                                + (record.banned() ? " (banned)" : "") + "."
                                )))
                                .exceptionally(throwable -> {
                                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to check admin lives.", throwable);
                                    runSync(() -> sender.sendMessage("Could not check lives right now."));
                                    return null;
                                });
                        return true;
                    }
                    case "give" -> {
                        if (!hasAdminPermission(sender)) {
                            sender.sendMessage("You do not have permission to use lives admin commands.");
                            return true;
                        }

                        if (args.length < 3) {
                            sender.sendMessage("Usage: /lives give <player> <amount>");
                            return true;
                        }

                        int amount;
                        try {
                            amount = Integer.parseInt(args[2]);
                        } catch (NumberFormatException exception) {
                            sender.sendMessage("Amount must be a whole number.");
                            return true;
                        }

                        if (amount <= 0) {
                            sender.sendMessage("Amount must be greater than 0.");
                            return true;
                        }

                        String targetName = args[1];
                        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                        livesManager.addLivesAdmin(target.getUniqueId(), targetName, amount)
                                .thenAccept(record -> runSync(() -> {
                                    String resolvedName = displayName(record, targetName);
                                    sender.sendMessage("[ChiselLives] Added " + amount + " lives to " + resolvedName
                                            + ". New lives: " + record.lives() + '.');

                                    Player online = Bukkit.getPlayer(target.getUniqueId());
                                    if (online != null && online.isOnline()) {
                                        online.sendMessage("[ChiselLives] You were given " + amount
                                                + " lives by staff. You now have " + record.lives() + " lives.");
                                    }
                                }))
                                .exceptionally(throwable -> {
                                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to give admin lives.", throwable);
                                    runSync(() -> sender.sendMessage("Could not give lives right now."));
                                    return null;
                                });
                        return true;
                    }
                    case "set" -> {
                        if (!hasAdminPermission(sender)) {
                            sender.sendMessage("You do not have permission to use lives admin commands.");
                            return true;
                        }

                        if (args.length < 3) {
                            sender.sendMessage("Usage: /lives set <player> <amount>");
                            return true;
                        }

                        int amount;
                        try {
                            amount = Integer.parseInt(args[2]);
                        } catch (NumberFormatException exception) {
                            sender.sendMessage("Amount must be a whole number.");
                            return true;
                        }

                        if (amount < 0) {
                            sender.sendMessage("Amount cannot be negative.");
                            return true;
                        }

                        String targetName = args[1];
                        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                        livesManager.setLivesAdmin(target.getUniqueId(), targetName, amount)
                                .thenAccept(record -> runSync(() -> {
                                    String resolvedName = displayName(record, targetName);
                                    sender.sendMessage("[ChiselLives] Set " + resolvedName + " to " + record.lives() + " lives.");

                                    Player online = Bukkit.getPlayer(target.getUniqueId());
                                    if (online != null && online.isOnline()) {
                                        online.sendMessage("[ChiselLives] Your lives were set to " + record.lives() + " by staff.");
                                    }
                                }))
                                .exceptionally(throwable -> {
                                    plugin.getLogger().log(Level.SEVERE, "[ChiselLives] Failed to set admin lives.", throwable);
                                    runSync(() -> sender.sendMessage("Could not set lives right now."));
                                    return null;
                                });
                        return true;
                    }
                    default -> {
                        // Fall through to GUI open for players.
                    }
                }
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }

            livesGUI.open(player);
            return true;
        });

        purchaseChecker = new PurchaseChecker(plugin, databaseManager, livesManager);
        purchaseChecker.start();

        plugin.getLogger().info("[ChiselLives] ChiselLives enabled.");
        return true;
    }

    private boolean hasAdminPermission(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }

        return sender.isOp() || sender.hasPermission("chisellives.admin");
    }

    private String displayName(DatabaseManager.PlayerRecord record, String fallback) {
        if (record.username() != null && !record.username().isBlank()) {
            return record.username();
        }
        return fallback;
    }

    private void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public void disable() {
        if (playerListener != null) {
            HandlerList.unregisterAll(playerListener);
            playerListener = null;
        }

        if (livesGUI != null) {
            HandlerList.unregisterAll(livesGUI);
            livesGUI = null;
        }

        if (revivalListener != null) {
            HandlerList.unregisterAll(revivalListener);
            revivalListener = null;
        }

        if (revivalRecipe != null) {
            revivalRecipe.unregister();
            revivalRecipe = null;
        }

        if (purchaseChecker != null) {
            purchaseChecker.stop();
            purchaseChecker = null;
        }

        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }

        livesManager = null;
        revivalTotemManager = null;
        banManager = null;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public LivesManager getLivesManager() {
        return livesManager;
    }
}

package com.beyondminer.kingdoms;

import com.beyondminer.kingdoms.commands.KingdomCommand;
import com.beyondminer.kingdoms.database.DatabaseManager;
import com.beyondminer.kingdoms.listeners.ChatListener;
import com.beyondminer.kingdoms.listeners.JoinListener;
import com.beyondminer.kingdoms.managers.ChatManager;
import com.beyondminer.kingdoms.managers.InviteManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Standalone main plugin class for the kingdoms system.
 */
public class KingdomsPlugin extends JavaPlugin {
    private DatabaseManager databaseManager;
    private KingdomManager kingdomManager;
    private InviteManager inviteManager;
    private ChatManager chatManager;

    @Override
    public void onEnable() {
        getLogger().info("KingdomsBounty kingdoms module enabled!");

        // Initialize managers
        databaseManager = new DatabaseManager(getDataFolder());
        kingdomManager = new KingdomManager(databaseManager);
        inviteManager = new InviteManager();
        chatManager = new ChatManager();

        // Register commands
        KingdomCommand kingdomCommand = new KingdomCommand(this, kingdomManager, inviteManager, chatManager);
        if (getCommand("kingdom") != null) {
            getCommand("kingdom").setExecutor(kingdomCommand);
            getCommand("kingdom").setTabCompleter(kingdomCommand);
        } else {
            getLogger().severe("Command 'kingdom' is missing from plugin.yml");
        }

        if (getCommand("kc") != null) {
            getCommand("kc").setExecutor(kingdomCommand);
            getCommand("kc").setTabCompleter(kingdomCommand);
        } else {
            getLogger().severe("Command 'kc' is missing from plugin.yml");
        }

        // Register listeners
        getServer().getPluginManager().registerEvents(new ChatListener(kingdomManager, chatManager), this);
        getServer().getPluginManager().registerEvents(kingdomCommand, this);
        getServer().getPluginManager().registerEvents(new JoinListener(chatManager, kingdomManager), this);

        getLogger().info("KingdomsBounty kingdoms module fully initialized!");
    }

    @Override
    public void onDisable() {
        getLogger().info("KingdomsBounty kingdoms module disabled!");
        if (chatManager != null) {
            chatManager.clear();
        }
    }
}

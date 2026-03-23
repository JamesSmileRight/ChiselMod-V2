package com.beyondminer.leaderboards.commands;

import com.beyondminer.leaderboards.BountyLeaderboards;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public final class ChiselModCommand implements CommandExecutor, TabCompleter {

    private static final String RESET_PERMISSION = "kingdomsbounty.admin.reset";
    private static final String RESET_SUBCOMMAND = "reset";

    private final BountyLeaderboards plugin;

    public ChiselModCommand(BountyLeaderboards plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args.length > 2 || !args[0].equalsIgnoreCase(RESET_SUBCOMMAND)) {
            sender.sendMessage("Usage: /chiselmod reset [hard|seed-online]");
            return true;
        }

        if (!sender.hasPermission(RESET_PERMISSION)) {
            sender.sendMessage("You do not have permission to reset ChiselMod data.");
            return true;
        }

        BountyLeaderboards.ResetMode mode = resolveMode(args);
        if (mode == null) {
            sender.sendMessage("Usage: /chiselmod reset [hard|seed-online]");
            return true;
        }

        sender.sendMessage("[ChiselMod] Starting full data reset ('" + mode.cliLabel() + "'). This may take a few seconds...");
        BountyLeaderboards.ResetResult result = plugin.resetAllPluginData(mode);
        sender.sendMessage("[ChiselMod] " + result.message());
        return true;
    }

    private BountyLeaderboards.ResetMode resolveMode(String[] args) {
        if (args.length == 1) {
            return BountyLeaderboards.ResetMode.SEED_ONLINE_PLAYERS;
        }

        String modeArg = args[1].toLowerCase();
        if ("hard".equals(modeArg)) {
            return BountyLeaderboards.ResetMode.HARD;
        }
        if ("seed-online".equals(modeArg) || "seed".equals(modeArg)) {
            return BountyLeaderboards.ResetMode.SEED_ONLINE_PLAYERS;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && RESET_SUBCOMMAND.startsWith(args[0].toLowerCase())) {
            return List.of(RESET_SUBCOMMAND);
        }

        if (args.length == 2 && RESET_SUBCOMMAND.equalsIgnoreCase(args[0])) {
            String input = args[1].toLowerCase();
            return List.of("hard", "seed-online").stream()
                    .filter(option -> option.startsWith(input))
                    .toList();
        }

        return Collections.emptyList();
    }
}

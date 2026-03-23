package com.beyondminer.bounty.commands;

import com.beyondminer.assassination.managers.AssassinationManager;
import com.beyondminer.assassination.models.AssassinationContract;
import com.beyondminer.bounty.managers.BountyManager;
import com.beyondminer.bounty.managers.ContractManager;
import com.beyondminer.bounty.managers.KingdomIntegrationManager;
import com.beyondminer.bounty.managers.LeaderboardManager;
import com.beyondminer.kingdoms.models.Kingdom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class BountyCommand implements CommandExecutor, TabCompleter {
    private static final String BOUNTY_PERMISSION = "bounty.command";
    private static final int BOUNTY_POINTS_PER_DIAMOND = 5;

    private final BountyManager bountyManager;
    private final LeaderboardManager leaderboardManager;
    private final ContractManager contractManager;
    private final KingdomIntegrationManager kingdomIntegration;
    private final AssassinationManager assassinationManager;

    public BountyCommand(BountyManager bountyManager, LeaderboardManager leaderboardManager,
                        ContractManager contractManager, KingdomIntegrationManager kingdomIntegration) {
        this(bountyManager, leaderboardManager, contractManager, kingdomIntegration, null);
    }

    public BountyCommand(BountyManager bountyManager, LeaderboardManager leaderboardManager,
                         ContractManager contractManager, KingdomIntegrationManager kingdomIntegration,
                         AssassinationManager assassinationManager) {
        this.bountyManager = bountyManager;
        this.leaderboardManager = leaderboardManager;
        this.contractManager = contractManager;
        this.kingdomIntegration = kingdomIntegration;
        this.assassinationManager = assassinationManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        bountyManager.trackPlayer(player);

        if (!player.hasPermission(BOUNTY_PERMISSION)) {
            player.sendMessage("§cYou do not have permission to use bounty commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(player);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "place":
            case "p":
                return handlePlace(player, args);
            case "check":
            case "c":
                return handleCheck(player, args);
            case "top":
            case "t":
                return handleTop(player, args);
            case "contracts":
            case "ct":
                return handleContracts(player);
            case "help":
            case "h":
                return handleHelp(player);
            case "assassinate":
                return handleLegacyAssassinate(player, args);
            case "hit":
                return handleHit(player, args);
            case "tags":
            case "commands":
                return handleTags(player);
            default:
                sendHelpMessage(player);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(BOUNTY_PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(
                    List.of("place", "check", "top", "contracts", "hit", "tags", "help"),
                    args[0]
            );
        }

        String primary = normalizePrimarySubcommand(args[0]);
        if (primary == null) {
            return Collections.emptyList();
        }

        if ("place".equals(primary) || "check".equals(primary)) {
            if (args.length == 2) {
                return filterByPrefix(List.of("player", "kingdom"), args[1]);
            }

            if (args.length == 3) {
                String type = args[1].toLowerCase();
                if ("player".equals(type)) {
                    return suggestOnlinePlayers(args[2]);
                }
                if ("kingdom".equals(type)) {
                    return filterByPrefix(kingdomIntegration.getAllKingdomNames(), args[2]);
                }
            }

            return Collections.emptyList();
        }

        if ("top".equals(primary) && args.length == 2) {
            return filterByPrefix(List.of("players", "kingdoms"), args[1]);
        }

        if ("hit".equals(primary)) {
            if (args.length == 2) {
                return filterByPrefix(List.of("request", "accept", "list", "assigned", "help"), args[1]);
            }

            if (args.length == 3) {
                String action = args[1].toLowerCase();
                if ("request".equals(action) || "accept".equals(action)) {
                    return suggestOnlinePlayers(args[2]);
                }
            }

            return Collections.emptyList();
        }

        return Collections.emptyList();
    }

    private boolean handlePlace(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /bounty place <player|kingdom> <name>");
            return true;
        }

        String type = args[1].toLowerCase();
        String targetName = args[2];

        ItemStack mainHand = player.getInventory().getItemInMainHand();

        if (mainHand.getType() != Material.DIAMOND) {
            player.sendMessage("§c[Bounty] You must be holding diamonds in your main hand!");
            return true;
        }

        int diamondCount = mainHand.getAmount();
        int bountyAmount = diamondCount * BOUNTY_POINTS_PER_DIAMOND;

        if (type.equals("player")) {
            UUID targetUuid = resolvePlayerUuid(targetName);
            if (targetUuid == null) {
                player.sendMessage("§c[Bounty] Player not found!");
                return true;
            }

            if (player.getUniqueId().equals(targetUuid)) {
                player.sendMessage("§c[Bounty] You cannot place a bounty on yourself!");
                return true;
            }

            String displayName = resolvePlayerName(targetUuid, targetName);
            Player onlineTarget = Bukkit.getPlayer(targetUuid);

            bountyManager.placeBountyOnPlayer(targetUuid, bountyAmount);
            contractManager.placeBounty("player", displayName, player.getName(), bountyAmount);

            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            player.sendMessage("§b[Bounty] §fPlaced §e" + bountyAmount + " §fbounty on §a" + displayName);
            if (onlineTarget != null) {
                bountyManager.trackPlayer(onlineTarget);
                onlineTarget.sendMessage("§b[Bounty] §e" + player.getName() + " §fplaced §e" + bountyAmount + " §fbounty on you!");
            }

        } else if (type.equals("kingdom")) {
            if (!kingdomIntegration.isPluginLoaded()) {
                player.sendMessage("§c[Bounty] Kingdom system is not available right now.");
                return true;
            }

            Kingdom targetKingdom = kingdomIntegration.getKingdom(targetName);
            if (targetKingdom == null) {
                player.sendMessage("§c[Bounty] Kingdom not found!");
                return true;
            }

            String placerKingdom = kingdomIntegration.getPlayerKingdom(player);
            if (placerKingdom != null && placerKingdom.equalsIgnoreCase(targetKingdom.getName())) {
                player.sendMessage("§c[Bounty] You cannot place a bounty on your own kingdom.");
                return true;
            }

            kingdomIntegration.placeBountyOnKingdom(targetKingdom.getName(), bountyAmount);
            contractManager.placeBounty("kingdom", targetKingdom.getName(), player.getName(), bountyAmount);

            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            player.sendMessage("§b[Bounty] §fPlaced §e" + bountyAmount + " §fbounty on kingdom §a"
                    + targetKingdom.getName());

        } else {
            player.sendMessage("§cUsage: /bounty place <player|kingdom> <name>");
            return true;
        }

        return true;
    }

    private boolean handleCheck(Player player, String[] args) {
        if (args.length == 1) {
            // Check own bounty
            int bounty = bountyManager.getBountyValue(player.getUniqueId());
            player.sendMessage("§b[Bounty] §fYour bounty: §e" + bounty);
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§cUsage: /bounty check [player|kingdom] <name>");
            return true;
        }

        String type = args[1].toLowerCase();
        String targetName = args[2];

        if (type.equals("player")) {
            UUID targetUuid = resolvePlayerUuid(targetName);
            if (targetUuid == null) {
                player.sendMessage("§c[Bounty] Player not found!");
                return true;
            }

            int bounty = bountyManager.getBountyValue(targetUuid);
            player.sendMessage("§b[Bounty] " + resolvePlayerName(targetUuid, targetName) + "'s bounty: §e" + bounty);

        } else if (type.equals("kingdom")) {
            if (!kingdomIntegration.isPluginLoaded()) {
                player.sendMessage("§c[Bounty] Kingdom system is not available right now.");
                return true;
            }

            if (!kingdomIntegration.kingdomExists(targetName)) {
                player.sendMessage("§c[Bounty] Kingdom not found!");
                return true;
            }

            int bounty = kingdomIntegration.getKingdomBounty(targetName);
            player.sendMessage("§b[Bounty] " + targetName + " kingdom bounty: §e" + bounty);

        } else {
            player.sendMessage("§cUsage: /bounty check [player|kingdom] <name>");
        }

        return true;
    }

    private boolean handleTop(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /bounty top <players|kingdoms>");
            return true;
        }

        String type = args[1].toLowerCase();

        if (type.equals("players")) {
            List<Map.Entry<String, Integer>> topPlayers = leaderboardManager.getTopPlayerBounties();
            player.sendMessage("§b§l========== TOP PLAYER BOUNTIES ==========");
            int rank = 1;
            for (Map.Entry<String, Integer> entry : topPlayers) {
                player.sendMessage("§f" + rank + ". §a" + entry.getKey() + " §f- §e" + entry.getValue());
                rank++;
            }
            player.sendMessage("§b§l=========================================");

        } else if (type.equals("kingdoms")) {
            if (!kingdomIntegration.isPluginLoaded()) {
                player.sendMessage("§c[Bounty] Kingdom system is not available right now.");
                return true;
            }

            List<Map.Entry<String, Integer>> topKingdoms = leaderboardManager.getTopKingdomBounties();
            player.sendMessage("§b§l========== TOP KINGDOM BOUNTIES ==========");
            int rank = 1;
            for (Map.Entry<String, Integer> entry : topKingdoms) {
                player.sendMessage("§f" + rank + ". §a" + entry.getKey() + " §f- §e" + entry.getValue());
                rank++;
            }
            player.sendMessage("§b§l==========================================");

        } else {
            player.sendMessage("§cUsage: /bounty top <players|kingdoms>");
        }

        return true;
    }

    private boolean handleContracts(Player player) {
        Map<String, Integer> contracts = contractManager.getContractSummary();
        player.sendMessage("§b§l========== ACTIVE BOUNTIES ==========");
        if (contracts.isEmpty()) {
            player.sendMessage("§fNo active bounties.");
        } else {
            for (Map.Entry<String, Integer> entry : contracts.entrySet()) {
                player.sendMessage("§a" + entry.getKey() + " §f- §e" + entry.getValue());
            }
        }
        player.sendMessage("§b§l=====================================");
        return true;
    }

    private boolean handleHelp(Player player) {
        sendHelpMessage(player);
        return true;
    }

    private boolean handleLegacyAssassinate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /bounty assassinate <player> §8(legacy alias for /bounty hit request)");
            return true;
        }

        return handleHit(player, new String[] {"hit", "request", args[1]});
    }

    private boolean handleHit(Player player, String[] args) {
        if (assassinationManager == null) {
            player.sendMessage("§c[Hit] Hit request system is not available right now.");
            return true;
        }

        if (args.length < 2) {
            sendHitHelp(player);
            return true;
        }

        String action = args[1].toLowerCase();
        return switch (action) {
            case "request", "req", "place" -> handleHitRequest(player, args);
            case "accept", "take" -> handleHitAccept(player, args);
            case "list", "open" -> handleHitList(player);
            case "assigned", "my", "status" -> handleHitAssigned(player);
            case "help" -> {
                sendHitHelp(player);
                yield true;
            }
            default -> {
                sendHitHelp(player);
                yield true;
            }
        };
    }

    private boolean handleHitRequest(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /bounty hit request <player>");
            return true;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() != Material.DIAMOND) {
            player.sendMessage("§d[Hit] §cYou must hold diamonds in your main hand!");
            return true;
        }

        String targetName = args[2];
        UUID targetUuid = resolvePlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage("§d[Hit] §cPlayer not found!");
            return true;
        }

        if (player.getUniqueId().equals(targetUuid)) {
            player.sendMessage("§d[Hit] §cYou cannot request a hit on yourself!");
            return true;
        }

        int diamonds = mainHand.getAmount();
        int amount = diamonds * BOUNTY_POINTS_PER_DIAMOND;
        String resolvedTargetName = resolvePlayerName(targetUuid, targetName);

        AssassinationManager.HitRequestResult result = assassinationManager.requestHit(
                player.getUniqueId(),
                player.getName(),
                targetUuid,
                resolvedTargetName,
                amount
        );

        if (result == AssassinationManager.HitRequestResult.TARGET_ALREADY_HAS_ACTIVE_HIT) {
            player.sendMessage("§d[Hit] §cThat player already has an active hit request.");
            return true;
        }

        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        player.sendMessage("§d[Hit] §fRequested a hit on §a" + resolvedTargetName + " §ffor §e" + amount + "§f.");

        Bukkit.broadcast(Component.text("§d[Hit] §e" + player.getName() + " §fposted a hit on §a" + resolvedTargetName
            + " §ffor §e" + amount + "§f. Accept with §d/bounty hit accept " + resolvedTargetName
            + "§f. Time limit: §e24h§f."));
        return true;
    }

    private boolean handleHitAccept(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /bounty hit accept <player>");
            return true;
        }

        String targetName = args[2];
        UUID targetUuid = resolvePlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage("§d[Hit] §cPlayer not found!");
            return true;
        }

        if (player.getUniqueId().equals(targetUuid)) {
            player.sendMessage("§d[Hit] §cYou cannot accept a hit on yourself.");
            return true;
        }

        AssassinationManager.HitAcceptResult result = assassinationManager.acceptHit(
                player.getUniqueId(),
                player.getName(),
                targetUuid
        );

        switch (result) {
            case TARGET_HAS_NO_ACTIVE_HIT -> {
                player.sendMessage("§d[Hit] §cNo active hit found for that target.");
                return true;
            }
            case HIT_ALREADY_ACCEPTED -> {
                player.sendMessage("§d[Hit] §cThat hit request has already been accepted.");
                return true;
            }
            case REQUESTER_CANNOT_ACCEPT_OWN_HIT -> {
                player.sendMessage("§d[Hit] §cYou cannot accept a hit request that you posted.");
                return true;
            }
            case HITMAN_ALREADY_HAS_ACTIVE_HIT -> {
                player.sendMessage("§d[Hit] §cYou already have an active accepted hit.");
                return true;
            }
            case SUCCESS -> {
                AssassinationContract accepted = assassinationManager.getAssignedHit(player.getUniqueId());
                if (accepted == null) {
                    player.sendMessage("§d[Hit] §aHit accepted.");
                    return true;
                }

                long remaining = accepted.getExpiresAt() - System.currentTimeMillis();
                player.sendMessage("§d[Hit] §aAccepted hit on §e" + accepted.getTargetName() + "§a for §e"
                        + accepted.getAmount() + "§a. Time left: §e"
                        + AssassinationManager.formatRemaining(remaining) + "§a.");
                Bukkit.broadcast(Component.text("§d[Hit] §e" + player.getName() + " §faccepted the hit on §a"
                    + accepted.getTargetName() + "§f."));
                return true;
            }
            default -> {
                player.sendMessage("§d[Hit] §cCould not accept this hit right now.");
                return true;
            }
        }
    }

    private boolean handleHitList(Player player) {
        List<AssassinationContract> openHits = assassinationManager.getOpenHits();
        player.sendMessage("§d§l========== OPEN HIT REQUESTS ==========");

        if (openHits.isEmpty()) {
            player.sendMessage("§fNo open hit requests right now.");
            player.sendMessage("§d§l=======================================");
            return true;
        }

        for (AssassinationContract contract : openHits) {
            long remaining = contract.getExpiresAt() - System.currentTimeMillis();
            player.sendMessage("§fTarget: §a" + contract.getTargetName()
                    + " §7| §fReward: §e" + contract.getAmount()
                    + " §7| §fTime Left: §e" + AssassinationManager.formatRemaining(remaining));
        }

        player.sendMessage("§d§l=======================================");
        return true;
    }

    private boolean handleHitAssigned(Player player) {
        AssassinationContract contract = assassinationManager.getAssignedHit(player.getUniqueId());
        if (contract == null) {
            player.sendMessage("§d[Hit] §fYou have no active accepted hit.");
            return true;
        }

        long remaining = contract.getExpiresAt() - System.currentTimeMillis();
        player.sendMessage("§d[Hit] §fYour active target: §a" + contract.getTargetName());
        player.sendMessage("§d[Hit] §fReward: §e" + contract.getAmount());
        player.sendMessage("§d[Hit] §fTime left: §e" + AssassinationManager.formatRemaining(remaining));
        return true;
    }

    private boolean handleTags(Player player) {
        player.sendMessage("§b§l========== BOUNTY TAGS ==========");
        player.sendMessage("§f/bounty p ... §8= place");
        player.sendMessage("§f/bounty c ... §8= check");
        player.sendMessage("§f/bounty t ... §8= top");
        player.sendMessage("§f/bounty ct §8= contracts");
        player.sendMessage("§f/bounty hit request <player> §8= post public hit request");
        player.sendMessage("§f/bounty hit accept <player> §8= accept a hit request");
        player.sendMessage("§f/bounty hit list §8= see open hit requests");
        player.sendMessage("§f/bounty hit assigned §8= see your accepted target");
        player.sendMessage("§f/bounty tags §8= show this quick list");
        player.sendMessage("§f/bounty help §8= full help");
        player.sendMessage("§b§l==================================");

        return true;
    }

    private void sendHitHelp(Player player) {
        player.sendMessage("§d§l========== HIT REQUEST HELP ==========");
        player.sendMessage("§f/bounty hit request <player> §8- Hold diamonds and post a public hit (§e1 diamond = 5 points§8)");
        player.sendMessage("§f/bounty hit accept <player> §8- Accept an open hit request");
        player.sendMessage("§f/bounty hit list §8- View open hit requests");
        player.sendMessage("§f/bounty hit assigned §8- View your accepted hit target");
        player.sendMessage("§fRules: §7Requests expire in 24h. If target survives, bounty is placed on target.");
        player.sendMessage("§d§l======================================");
    }

    private String normalizePrimarySubcommand(String raw) {
        return switch (raw.toLowerCase()) {
            case "place", "p" -> "place";
            case "check", "c" -> "check";
            case "top", "t" -> "top";
            case "contracts", "ct" -> "contracts";
            case "help", "h" -> "help";
            case "hit", "assassinate" -> "hit";
            case "tags", "commands" -> "tags";
            default -> null;
        };
    }

    private List<String> suggestOnlinePlayers(String partial) {
        String lowered = partial.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lowered))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> filterByPrefix(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lowered)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage("§b§l========== BOUNTY HELP ==========");
        player.sendMessage("§f/bounty place player <name> §8- Place bounty on player (§e1 diamond = 5 points§8)");
        player.sendMessage("§f/bounty place kingdom <name> §8- Place bounty on kingdom (not your own, §e1 diamond = 5 points§8)");
        player.sendMessage("§f/bounty check §8- Check your bounty");
        player.sendMessage("§f/bounty check player <name> §8- Check player's bounty");
        player.sendMessage("§f/bounty check kingdom <name> §8- Check kingdom's bounty");
        player.sendMessage("§f/bounty top players §8- Top 10 players");
        player.sendMessage("§f/bounty top kingdoms §8- Top 10 kingdoms");
        player.sendMessage("§f/bounty contracts §8- View active bounties");
        player.sendMessage("§f/bounty hit request <player> §8- Post public hit request (hold diamonds, §e1 diamond = 5 points§8)");
        player.sendMessage("§f/bounty hit accept <player> §8- Accept an open hit request");
        player.sendMessage("§f/bounty hit list §8- List open hit requests");
        player.sendMessage("§f/bounty hit assigned §8- Show your accepted hit");
        player.sendMessage("§f/bounty tags §8- Quick command tags and shortcuts");
        player.sendMessage("§f/bounty help §8- Show this help menu");
        player.sendMessage("§f/bounty assassinate <player> §8- Legacy alias for /bounty hit request <player>");
        player.sendMessage("§b§l==================================");
    }

    private UUID resolvePlayerUuid(String playerName) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer != null) {
            bountyManager.trackPlayer(onlinePlayer);
            return onlinePlayer.getUniqueId();
        }

        return bountyManager.resolvePlayerUuid(playerName);
    }

    private String resolvePlayerName(UUID playerUuid, String fallbackName) {
        Player onlinePlayer = Bukkit.getPlayer(playerUuid);
        if (onlinePlayer != null) {
            bountyManager.trackPlayer(onlinePlayer);
            return onlinePlayer.getName();
        }

        String storedName = bountyManager.getStoredPlayerName(playerUuid);
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        if (offlinePlayer.getName() != null && !offlinePlayer.getName().isBlank()) {
            return offlinePlayer.getName();
        }

        return fallbackName;
    }
}

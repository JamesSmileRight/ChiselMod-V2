package com.beyondminer.kingdoms.commands;

import com.beyondminer.kingdoms.managers.ChatManager;
import com.beyondminer.kingdoms.managers.InviteManager;
import com.beyondminer.kingdoms.managers.KingdomManager;
import com.beyondminer.kingdoms.models.Kingdom;
import com.beyondminer.kingdoms.util.KingdomColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles all kingdom commands and tab completion.
 */
public class KingdomCommand implements CommandExecutor, TabCompleter, Listener {
    private static final String KINGDOM_PERMISSION = "kingdoms.command";
    private static final long CAPITAL_TELEPORT_DELAY_TICKS = 100L;

    private final JavaPlugin plugin;
    private final KingdomManager kingdomManager;
    private final InviteManager inviteManager;
    private final ChatManager chatManager;
    private final Map<UUID, PendingCapitalTeleport> pendingCapitalTeleports = new ConcurrentHashMap<>();

    public KingdomCommand(JavaPlugin plugin, KingdomManager kingdomManager, InviteManager inviteManager, ChatManager chatManager) {
        this.plugin = plugin;
        this.kingdomManager = kingdomManager;
        this.inviteManager = inviteManager;
        this.chatManager = chatManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission(KINGDOM_PERMISSION)) {
            player.sendMessage(Component.text("You do not have permission to use kingdom commands.", NamedTextColor.RED));
            return true;
        }

        if (command.getName().equalsIgnoreCase("kc")) {
            return handleChat(player);
        }

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "create":
                return handleCreate(player, args);
            case "invite":
                return handleInvite(player, args);
            case "join":
                return handleJoin(player, args);
            case "leave":
                return handleLeave(player);
            case "setcapital":
                return handleSetCapital(player);
            case "capital":
                if (args.length > 1 && args[1].equalsIgnoreCase("remove")) {
                    return handleCapitalRemove(player);
                }
                return handleCapitalTeleport(player);
            case "chat":
                return handleChat(player);
            case "help":
                showHelp(player);
                return true;
            default:
                player.sendMessage(Component.text("Unknown command. Type ", NamedTextColor.RED)
                        .append(Component.text("/kingdom help", NamedTextColor.GOLD)));
                return true;
        }
    }

    /**
     * /kingdom create [kingdom name]
     */
    private boolean handleCreate(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            player.sendMessage(Component.text("Usage: /kingdom create <kingdom name> [color]", NamedTextColor.RED));
            return true;
        }

        String kingdomName = args[1];
        String selectedColor = args.length == 3 ? args[2] : "gold";

        // Check if player is already in a kingdom
        if (kingdomManager.isPlayerInKingdom(player.getUniqueId())) {
            player.sendMessage(Component.text("You are already in a kingdom! Leave first with /kingdom leave", NamedTextColor.RED));
            return true;
        }

        // Validate kingdom name
        if (kingdomName.length() < 3 || kingdomName.length() > 16) {
            player.sendMessage(Component.text("Kingdom name must be 3-16 characters long!", NamedTextColor.RED));
            return true;
        }

        if (kingdomName.contains(" ")) {
            player.sendMessage(Component.text("Kingdom name cannot contain spaces!", NamedTextColor.RED));
            return true;
        }

        if (kingdomManager.kingdomExists(kingdomName)) {
            player.sendMessage(Component.text("A kingdom with that name already exists!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 3 && !KingdomColorUtil.isValid(selectedColor)) {
            player.sendMessage(Component.text("Invalid color. Use a named color like gold, aqua, red, green, or a hex color like #55ff55.", NamedTextColor.RED));
            return true;
        }

        String normalizedColor = KingdomColorUtil.normalize(selectedColor);

        // Create the kingdom
        if (kingdomManager.createKingdom(kingdomName, player.getUniqueId(), normalizedColor)) {
            TextColor textColor = KingdomColorUtil.toTextColor(normalizedColor);
            player.sendMessage(Component.text("Created kingdom ", NamedTextColor.GREEN)
                    .append(Component.text("[" + kingdomName + "]", textColor))
                    .append(Component.text(" successfully.", NamedTextColor.GREEN)));
            return true;
        }

        player.sendMessage(Component.text("Failed to create kingdom.", NamedTextColor.RED));
        return true;
    }

    /**
     * /kingdom invite [player name]
     */
    private boolean handleInvite(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage: /kingdom invite [player name]", NamedTextColor.RED));
            return true;
        }

        // Check if player is in a kingdom
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());
        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom!", NamedTextColor.RED));
            return true;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage(Component.text("Player " + targetName + " is not online!", NamedTextColor.RED));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You cannot invite yourself!", NamedTextColor.RED));
            return true;
        }

        // Check if target is already in a kingdom
        if (kingdomManager.isPlayerInKingdom(target.getUniqueId())) {
            player.sendMessage(Component.text(targetName + " is already in a kingdom!", NamedTextColor.RED));
            return true;
        }

        // Send invite to target
        inviteManager.invitePlayer(target.getUniqueId(), kingdom.getName());

        // Create clickable invite message
        Component inviteMessage = Component.empty()
                .append(Component.text("You were invited to join the kingdom ", NamedTextColor.GOLD))
            .append(Component.text("[" + kingdom.getName() + "]", KingdomColorUtil.toTextColor(kingdom.getColor())))
            .append(Component.text("!", NamedTextColor.GOLD));

        Component clickButton = Component.text("\n[CLICK TO JOIN]", NamedTextColor.GREEN, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/kingdom join"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to join this kingdom", NamedTextColor.GRAY)));

        target.sendMessage(inviteMessage);
        target.sendMessage(clickButton);

        player.sendMessage(Component.text("✔ Invite sent to " + targetName, NamedTextColor.GREEN));
        return true;
    }

    /**
     * /kingdom join [kingdom name]
     */
    private boolean handleJoin(Player player, String[] args) {
        if (args.length > 2) {
            player.sendMessage(Component.text("Usage: /kingdom join [kingdom name]", NamedTextColor.RED));
            return true;
        }

        // Check if player is already in a kingdom
        if (kingdomManager.isPlayerInKingdom(player.getUniqueId())) {
            player.sendMessage(Component.text("You are already in a kingdom! Leave first with /kingdom leave", NamedTextColor.RED));
            return true;
        }

        // Check if player has an invite
        if (!inviteManager.hasInvite(player.getUniqueId())) {
            player.sendMessage(Component.text("You don't have an invite to this kingdom!", NamedTextColor.RED));
            return true;
        }

        String invitedKingdom = inviteManager.getInvite(player.getUniqueId());
        if (args.length == 2 && !invitedKingdom.equalsIgnoreCase(args[1])) {
            player.sendMessage(Component.text("You don't have an invite to that kingdom!", NamedTextColor.RED));
            return true;
        }

        // Check if kingdom exists
        if (!kingdomManager.kingdomExists(invitedKingdom)) {
            player.sendMessage(Component.text("That kingdom does not exist!", NamedTextColor.RED));
            inviteManager.removeInvite(player.getUniqueId());
            return true;
        }

        // Add player to kingdom
        kingdomManager.addPlayerToKingdom(player.getUniqueId(), invitedKingdom);
        inviteManager.removeInvite(player.getUniqueId());

        Kingdom joinedKingdom = kingdomManager.getKingdom(invitedKingdom);
        Component kingdomTag = joinedKingdom == null
            ? Component.text(invitedKingdom, NamedTextColor.AQUA)
            : Component.text("[" + joinedKingdom.getName() + "]", KingdomColorUtil.toTextColor(joinedKingdom.getColor()));

        player.sendMessage(Component.text("You joined ", NamedTextColor.GREEN)
            .append(kingdomTag)
            .append(Component.text(".", NamedTextColor.GREEN)));

        if (joinedKingdom != null) {
            Component joinNotice = Component.text("[Kingdom] " + player.getName()
                    + " accepted the invite and joined the kingdom.", NamedTextColor.YELLOW);
            for (UUID memberUUID : joinedKingdom.getMembers()) {
                if (memberUUID.equals(player.getUniqueId())) {
                    continue;
                }
                Player member = Bukkit.getPlayer(memberUUID);
                if (member != null) {
                    member.sendMessage(joinNotice);
                }
            }
        }
        return true;
    }

    /**
     * /kingdom leave
     */
    private boolean handleLeave(Player player) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());

        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom!", NamedTextColor.RED));
            return true;
        }

        boolean wasLeader = kingdom.isLeader(player.getUniqueId());

        if (wasLeader) {
            Set<UUID> formerMembers = new HashSet<>(kingdom.getMembers());

            if (!kingdomManager.deleteKingdom(kingdom.getName())) {
                player.sendMessage(Component.text("Failed to disband your kingdom.", NamedTextColor.RED));
                return true;
            }

            inviteManager.clearInvitesForKingdom(kingdom.getName());

            for (UUID memberUUID : formerMembers) {
                chatManager.disableChat(memberUUID);
                if (memberUUID.equals(player.getUniqueId())) {
                    continue;
                }
                Player member = Bukkit.getPlayer(memberUUID);
                if (member != null) {
                    member.sendMessage(Component.text("The kingdom has been disbanded.", NamedTextColor.RED));
                }
            }

            player.sendMessage(Component.text("✔ You left the kingdom. The kingdom has been disbanded.", NamedTextColor.GREEN));
        } else {
            kingdomManager.removePlayerFromKingdom(player.getUniqueId());
            chatManager.disableChat(player.getUniqueId());
            player.sendMessage(Component.text("✔ You left the kingdom " + kingdom.getName() + ".", NamedTextColor.GREEN));

            // Notify other members
            Kingdom updatedKingdom = kingdomManager.getKingdom(kingdom.getName());
            if (updatedKingdom != null) {
                for (UUID memberUUID : updatedKingdom.getMembers()) {
                    Player member = Bukkit.getPlayer(memberUUID);
                    if (member != null) {
                        member.sendMessage(Component.text(player.getName() + " left the kingdom.", NamedTextColor.YELLOW));
                    }
                }
            }
        }

        return true;
    }

    /**
     * /kingdom chat or /kc
     */
    private boolean handleChat(Player player) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());

        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom!", NamedTextColor.RED));
            return true;
        }

        chatManager.toggleChat(player.getUniqueId());

        if (chatManager.isChatEnabled(player.getUniqueId())) {
            player.sendMessage(Component.text("✔ Kingdom chat enabled!", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("✔ Kingdom chat disabled!", NamedTextColor.GREEN));
        }

        return true;
    }

    private boolean handleSetCapital(Player player) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());

        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom!", NamedTextColor.RED));
            return true;
        }

        if (!kingdom.isLeader(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the kingdom creator can set the capital.", NamedTextColor.RED));
            return true;
        }

        if (!kingdomManager.setKingdomCapital(kingdom.getName(), player.getLocation())) {
            player.sendMessage(Component.text("Failed to save your kingdom capital.", NamedTextColor.RED));
            return true;
        }

        player.sendMessage(Component.text("✔ Kingdom capital set to your current location.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleCapitalTeleport(Player player) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());

        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom!", NamedTextColor.RED));
            return true;
        }

        Location capital = kingdom.getCapitalLocation();
        if (capital == null) {
            player.sendMessage(Component.text("Your kingdom does not have a capital set yet.", NamedTextColor.RED));
            return true;
        }

        if (pendingCapitalTeleports.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("You are already teleporting to your kingdom capital.", NamedTextColor.RED));
            return true;
        }

        pendingCapitalTeleports.put(player.getUniqueId(), new PendingCapitalTeleport(player.getLocation().clone(), capital.clone()));
        player.sendMessage(Component.text("Do not move for 5 seconds. Teleporting to your kingdom capital...", NamedTextColor.GOLD));
        Bukkit.getScheduler().runTaskLater(plugin, () -> completeCapitalTeleport(player.getUniqueId()), CAPITAL_TELEPORT_DELAY_TICKS);
        return true;
    }

    private boolean handleCapitalRemove(Player player) {
        Kingdom kingdom = kingdomManager.getPlayerKingdom(player.getUniqueId());

        if (kingdom == null) {
            player.sendMessage(Component.text("You are not in a kingdom!", NamedTextColor.RED));
            return true;
        }

        if (!kingdom.isLeader(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the kingdom creator can remove the capital.", NamedTextColor.RED));
            return true;
        }

        if (kingdom.getCapitalLocation() == null) {
            player.sendMessage(Component.text("Your kingdom does not have a capital set.", NamedTextColor.RED));
            return true;
        }

        if (!kingdomManager.setKingdomCapital(kingdom.getName(), null)) {
            player.sendMessage(Component.text("Failed to remove your kingdom capital.", NamedTextColor.RED));
            return true;
        }

        player.sendMessage(Component.text("✔ Kingdom capital removed. You can set a new one with /kingdom setcapital.", NamedTextColor.GREEN));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        PendingCapitalTeleport pending = pendingCapitalTeleports.get(event.getPlayer().getUniqueId());
        if (pending == null || event.getTo() == null) {
            return;
        }

        if (!hasMovedBlockPosition(event.getFrom(), event.getTo())) {
            return;
        }

        pendingCapitalTeleports.remove(event.getPlayer().getUniqueId());
        event.getPlayer().sendMessage(Component.text("Kingdom capital teleport cancelled because you moved.", NamedTextColor.RED));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingCapitalTeleports.remove(event.getPlayer().getUniqueId());
    }

    private void completeCapitalTeleport(UUID playerId) {
        PendingCapitalTeleport pending = pendingCapitalTeleports.remove(playerId);
        if (pending == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        if (hasMovedBlockPosition(pending.startLocation(), player.getLocation())) {
            player.sendMessage(Component.text("Kingdom capital teleport cancelled because you moved.", NamedTextColor.RED));
            return;
        }

        player.teleport(pending.destination());
        player.sendMessage(Component.text("✔ Teleported to your kingdom capital.", NamedTextColor.GREEN));
    }

    private boolean hasMovedBlockPosition(Location from, Location to) {
        if (from == null || to == null) {
            return false;
        }

        if (from.getWorld() == null || to.getWorld() == null) {
            return true;
        }

        return !Objects.equals(from.getWorld().getUID(), to.getWorld().getUID())
                || from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }

    /**
     * Shows the help menu.
     */
    private void showHelp(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("-------- Kingdom Commands --------", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("/kingdom create <name> [color]", NamedTextColor.AQUA).append(Component.text(" - Create a kingdom with an optional chat tag color", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom invite [player]", NamedTextColor.AQUA).append(Component.text(" - Invite a player", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom join", NamedTextColor.AQUA).append(Component.text(" - Join your pending invite", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom join [kingdom]", NamedTextColor.AQUA).append(Component.text(" - Join a specific pending invite", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom leave", NamedTextColor.AQUA).append(Component.text(" - Leave your kingdom", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom setcapital", NamedTextColor.AQUA).append(Component.text(" - Set the kingdom capital at your location (creator only)", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom capital", NamedTextColor.AQUA).append(Component.text(" - Teleport to your kingdom capital after 5 seconds without moving", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kingdom chat", NamedTextColor.AQUA).append(Component.text(" - Toggle kingdom chat", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/kc", NamedTextColor.AQUA).append(Component.text(" - Alias for kingdom chat", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Named colors: gold, aqua, red, green, yellow, blue, purple, gray, white", NamedTextColor.GRAY));
        player.sendMessage(Component.empty());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return new ArrayList<>();
        }

        if (command.getName().equalsIgnoreCase("kc")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            // First argument: subcommand
            return getSubcommandSuggestions(args[0]);
        }

        if (args.length == 2) {
            // Second argument: depends on subcommand
            String subcommand = args[0].toLowerCase();
            return getSecondArgumentSuggestions(subcommand, args[1], (Player) sender);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return getColorSuggestions(args[2]);
        }

        return new ArrayList<>();
    }

    private List<String> getSubcommandSuggestions(String partial) {
        List<String> subcommands = Arrays.asList("create", "invite", "join", "leave", "setcapital", "capital", "chat", "help");
        return subcommands.stream()
                .filter(s -> s.startsWith(partial.toLowerCase()))
                .collect(Collectors.toList());
    }

    private List<String> getSecondArgumentSuggestions(String subcommand, String partial, Player player) {
        return switch (subcommand) {
            case "invite" -> getOnlinePlayerNames(partial);
            case "join" -> getKingdomNames(partial);
            default -> new ArrayList<>();
        };
    }

    private List<String> getColorSuggestions(String partial) {
        String lowered = partial.toLowerCase(Locale.ROOT);
        return KingdomColorUtil.suggestions().stream()
                .filter(name -> name.startsWith(lowered))
                .collect(Collectors.toList());
    }

    private List<String> getOnlinePlayerNames(String partial) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partial.toLowerCase()))
                .collect(Collectors.toList());
    }

    private List<String> getKingdomNames(String partial) {
        return kingdomManager.getAllKingdomNames().stream()
                .filter(name -> name.toLowerCase().startsWith(partial.toLowerCase()))
                .collect(Collectors.toList());
    }

    private record PendingCapitalTeleport(Location startLocation, Location destination) {
    }
}

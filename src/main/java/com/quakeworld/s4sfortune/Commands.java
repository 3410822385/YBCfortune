package com.quakeworld.s4sfortune;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Single executor for every plugin command, dispatched by command name. Holds the per-player roll
 * cooldown and the weighted pool pick for /roll.
 */
public class Commands implements CommandExecutor {
  private final Main plugin;
  private HashMap<String, Date> nextRolls;
  private List<Fortune> regularFortunes;
  // one roll in n is a special roll
  private static final int SPECIAL_PROBABILITY = 20;

  public Commands(Main plugin, HashMap<String, Date> nextRolls, List<Fortune> regularFortunes) {
    this.plugin = plugin;
    this.nextRolls = nextRolls;
    this.regularFortunes = regularFortunes;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

    // allowed to roll?
    Date allowedTime = nextRolls.get(sender.getName());

    if (command.getName().equalsIgnoreCase("roll")) {
      // rolling only makes sense for players; silently ignore console/RCON
      if (!(sender instanceof Player roller)) {
        return true;
      }
      if (allowedTime != null) {
        if (allowedTime.after(new Date(System.currentTimeMillis()))) {
          int timeLeft = (int) ((allowedTime.getTime() - System.currentTimeMillis()) / 1000);
          sender.sendMessage("You can roll again in " + timeLeft + " seconds.");
          return true;
        }
      }
      int special = ThreadLocalRandom.current().nextInt(SPECIAL_PROBABILITY);

      List<Fortune> specials = SpecialFortunes.getSpecialFortunes();
      List<Fortune> regulars = RegularFortunes.getRegularFortunes();
      // special roll, falling back to regular when no specials are configured
      List<Fortune> pool = (special == 1 && !specials.isEmpty()) ? specials : regulars;
      if (pool.isEmpty()) {
        sender.sendMessage("No fortunes configured.");
        return true;
      }
      announceRoll(pickWeighted(pool), roller);

      // debounce
      Date nextAllowedRollTime = new Date(System.currentTimeMillis() + 30 * 1000);
      this.nextRolls.put(sender.getName(), nextAllowedRollTime);
    }

    if (command.getName().equalsIgnoreCase("fortunereload")) {
      if (!sender.hasPermission("s4sfortune.admin")) {
        sender.sendMessage("You do not have permission to do that.");
        return true;
      }
      plugin.reloadPluginConfig();
      sender.sendMessage(Component.text("s4sfortune config reloaded.", NamedTextColor.GREEN));
      return true;
    }

    if (command.getName().equalsIgnoreCase("checkRRolls") && sender.isOp()) {
      StringBuilder fortuneNames = new StringBuilder();
      int i = 0;
      // Use RegularFortunes to get the current list
      for (Fortune rf : RegularFortunes.getRegularFortunes()) {
        fortuneNames.append("|§n§3").append(i).append("§r-").append(rf.getFortune());
        ++i;
      }
      sender.sendMessage(fortuneNames.toString());
    }

    if (command.getName().equalsIgnoreCase("checkSRolls") && sender.isOp()) {
      StringBuilder fortuneNames = new StringBuilder();
      int j = 0;
      for (Fortune f : SpecialFortunes.getSpecialFortunes()) {
        fortuneNames.append("|§n§3").append(j).append("§r-").append(f.getFortune());
        ++j;
      }
      sender.sendMessage(fortuneNames.toString());
    }

    if (command.getName().equalsIgnoreCase("funnyban") && sender.isOp()) {
      if (args.length < 1) {
        sender.sendMessage("Usage: /funnyban <player> [reason]");
        return true;
      }

      String recipient = args[0];
      String banReason;

      if (args.length == 1) {
        banReason = "USER WAS BANNED FOR THIS PEKO";
      } else {
        String[] actualMessageNouns = Arrays.copyOfRange(args, 1, args.length);
        banReason = String.join(" ", actualMessageNouns);
      }

      Component message =
          Component.text("Banned " + recipient + " ", NamedTextColor.RED)
              .append(
                  Component.text(
                      "(" + banReason + ")",
                      TextColor.fromHexString("#FF0000"),
                      TextDecoration.BOLD));

      plugin.getServer().broadcast(message);
      return true;
    }

    if (command.getName().equalsIgnoreCase("rrolltest") && sender.isOp()) {
      if (!(sender instanceof Player roller)) {
        return true;
      }
      if (args.length < 1) {
        sender.sendMessage("Usage: /rrolltest <index>");
        return true;
      }
      try {
        int index = Integer.parseInt(args[0]);
        // Use RegularFortunes to get the current list
        List<Fortune> fortunes = RegularFortunes.getRegularFortunes();
        if (index >= 0 && index < fortunes.size()) {
          announceRoll(fortunes.get(index), roller);
        } else {
          sender.sendMessage("Index out of bounds. Use /checkRRolls to see available indices.");
        }
      } catch (NumberFormatException e) {
        sender.sendMessage("Please provide a valid number.");
      }
      return true;
    }

    if (command.getName().equalsIgnoreCase("srolltest") && sender.isOp()) {
      if (!(sender instanceof Player roller)) {
        return true;
      }
      if (args.length < 1) {
        sender.sendMessage("Usage: /srolltest <index>");
        return true;
      }
      try {
        int index = Integer.parseInt(args[0]);
        List<Fortune> fortunes = SpecialFortunes.getSpecialFortunes();
        if (index >= 0 && index < fortunes.size()) {
          announceRoll(fortunes.get(index), roller);
        } else {
          sender.sendMessage("Index out of bounds. Use /checkSRolls to see available indices.");
        }
      } catch (NumberFormatException e) {
        sender.sendMessage("Please provide a valid number.");
      }
      return true;
    }

    if (command.getName().equalsIgnoreCase("unfortunate") && sender.isOp()) {
      if (args.length < 1) {
        sender.sendMessage("Usage: /unfortunate <player>");
        return true;
      }

      OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[0]);
      String playerName = target.getName();
      if (playerName == null) {
        sender.sendMessage(args[0] + " is not banned or doesn't exist");
        return true;
      }

      ProfileBanList banList = Bukkit.getBanList(BanList.Type.PROFILE);
      PlayerProfile profile = target.getPlayerProfile();
      if (banList.isBanned(profile)) {
        banList.pardon(profile);
        Component pardonMessage =
            Component.text(
                playerName + "'s bad luck has been PARDONED!",
                TextColor.fromHexString("#00FF00"),
                TextDecoration.BOLD);
        plugin.getServer().broadcast(pardonMessage);
      } else {
        sender.sendMessage(playerName + " is not banned or doesn't exist");
      }
      return true;
    }

    return true;
  }

  private void announceRoll(Fortune fortune, Player roller) {
    Component toRoller = fortuneMessage("Your fortune: ", fortune);
    Component toEveryoneElse = fortuneMessage(roller.getName() + "'s fortune: ", fortune);

    for (Player player : plugin.getServer().getOnlinePlayers()) {
      if (player.equals(roller)) {
        player.sendMessage(toRoller);
      } else {
        player.sendMessage(toEveryoneElse);
      }
    }

    fortune.sideEffect.run(roller);
    plugin.getLogger().info(roller.getName() + "rolled " + fortune.fortune);
  }

  // Fortune texts may contain legacy § codes; deserialize them server-side so the
  // client never sees raw section signs, then apply the fortune's color and bold.
  private Component fortuneMessage(String prefix, Fortune fortune) {
    return LegacyComponentSerializer.legacySection()
        .deserialize(prefix + fortune.fortune)
        .colorIfAbsent(TextColor.fromHexString(fortune.color))
        .decorate(TextDecoration.BOLD);
  }

  // fortune chance = its weight / sum of all weights in the pool
  private Fortune pickWeighted(List<Fortune> pool) {
    int total = 0;
    for (Fortune f : pool) {
      total += f.weight;
    }
    int pick = ThreadLocalRandom.current().nextInt(total);
    for (Fortune f : pool) {
      pick -= f.weight;
      if (pick < 0) {
        return f;
      }
    }
    return pool.get(pool.size() - 1);
  }
}

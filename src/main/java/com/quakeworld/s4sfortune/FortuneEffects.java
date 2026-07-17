package com.quakeworld.s4sfortune;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Parses and runs config-defined fortune side effects.
 *
 * <p>A fortune entry may carry any of: commands: [ ... ] console commands, %player% replaced with
 * the roller actions: [ ... ] built-in primitives (see parseAction) for effects vanilla commands
 * can't express random: weighted branches; exactly ONE is picked per roll, each branch uses this
 * same schema (recursion allowed)
 *
 * <p>Execution order: commands, then actions, then random. A "delay <ticks>" action postpones every
 * remaining step of its own list (combine with the "run <command>" action to delay commands).
 */
public class FortuneEffects {

  private static final MiniMessage MINI = MiniMessage.miniMessage();

  /** One execution step; returns ticks to wait before the next step (0 = none). */
  @FunctionalInterface
  private interface Step {
    long run(Player player);
  }

  private record Branch(int weight, FortuneSideEffect effect) {}

  /**
   * True when the fortune map uses the given action anywhere, including inside nested random
   * branches. Used to drop fortunes whose actions depend on a disabled module.
   */
  public static boolean usesAction(Map<?, ?> map, String actionName) {
    if (map.get("actions") instanceof List<?> actions) {
      for (Object action : actions) {
        if (action instanceof Map<?, ?> m) {
          if (m.containsKey(actionName)) {
            return true;
          }
        } else if (String.valueOf(action).trim().split("\\s+")[0].equals(actionName)) {
          return true;
        }
      }
    }
    if (map.get("random") instanceof List<?> branches) {
      for (Object branch : branches) {
        if (branch instanceof Map<?, ?> branchMap && usesAction(branchMap, actionName)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns null when the map defines no side effect. */
  public static FortuneSideEffect parse(JavaPlugin plugin, String fortuneText, Map<?, ?> map) {
    List<Step> steps = new ArrayList<>();

    if (map.get("commands") instanceof List<?> commands) {
      for (Object cmd : commands) {
        String command = String.valueOf(cmd);
        steps.add(consoleCommandStep(plugin, fortuneText, command));
      }
    }

    if (map.get("actions") instanceof List<?> actions) {
      for (Object action : actions) {
        Step step = parseAction(plugin, fortuneText, action);
        if (step != null) {
          steps.add(step);
        }
      }
    }

    if (map.get("random") instanceof List<?> branches) {
      Step step = parseRandom(plugin, fortuneText, branches);
      if (step != null) {
        steps.add(step);
      }
    }

    if (steps.isEmpty()) {
      return null;
    }
    return player -> runSteps(plugin, steps, 0, player);
  }

  private static void runSteps(JavaPlugin plugin, List<Step> steps, int start, Player player) {
    for (int i = start; i < steps.size(); i++) {
      long delay = steps.get(i).run(player);
      if (delay > 0 && i + 1 < steps.size()) {
        int next = i + 1;
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                  if (player.isOnline()) {
                    runSteps(plugin, steps, next, player);
                  }
                },
                delay);
        return;
      }
    }
  }

  private static Step consoleCommandStep(JavaPlugin plugin, String fortuneText, String command) {
    return player -> {
      String resolved = command.replace("%player%", player.getName());
      // console-level sender that does NOT broadcast "[Server: ...]" to ops;
      // command feedback goes to the server log only
      var sender =
          Bukkit.createCommandSender(
              feedback ->
                  plugin
                      .getLogger()
                      .info(PlainTextComponentSerializer.plainText().serialize(feedback)));
      if (!Bukkit.dispatchCommand(sender, resolved)) {
        plugin.getLogger().warning("Fortune \"" + fortuneText + "\": unknown command: " + resolved);
      }
      return 0;
    };
  }

  private static Step parseRandom(JavaPlugin plugin, String fortuneText, List<?> rawBranches) {
    List<Branch> branches = new ArrayList<>();
    int totalWeight = 0;

    for (Object rawBranch : rawBranches) {
      if (!(rawBranch instanceof Map<?, ?> branchMap)) {
        plugin
            .getLogger()
            .warning("Fortune \"" + fortuneText + "\": random branch is not a map, skipped");
        continue;
      }
      int weight = 1;
      if (branchMap.get("weight") instanceof Number n) {
        weight = Math.max(1, n.intValue());
      }
      FortuneSideEffect effect = parse(plugin, fortuneText, branchMap);
      branches.add(new Branch(weight, effect));
      totalWeight += weight;
    }

    if (branches.isEmpty()) {
      return null;
    }

    final int total = totalWeight;
    return player -> {
      int pick = ThreadLocalRandom.current().nextInt(total);
      for (Branch branch : branches) {
        pick -= branch.weight();
        if (pick < 0) {
          if (branch.effect() != null) {
            branch.effect().run(player);
          }
          break;
        }
      }
      return 0;
    };
  }

  /**
   * Built-in action primitives. String form: "name arg arg ...". Map form (complex args): only
   * "drop-item: {material, count, name, lore}". Returns null (with a logged warning) for anything
   * unparseable.
   */
  private static Step parseAction(JavaPlugin plugin, String fortuneText, Object raw) {
    try {
      if (raw instanceof Map<?, ?> m) {
        if (m.size() == 1
            && m.keySet().iterator().next().toString().equals("drop-item")
            && m.values().iterator().next() instanceof Map<?, ?> params) {
          return dropItemStep(params);
        }
        plugin.getLogger().warning("Fortune \"" + fortuneText + "\": unknown map action: " + m);
        return null;
      }

      String[] parts = String.valueOf(raw).trim().split("\\s+");
      switch (parts[0]) {
        case "fire":
          {
            int ticks = Integer.parseInt(parts[1]);
            return p -> {
              p.setFireTicks(ticks);
              return 0;
            };
          }
        case "sethealth":
          {
            double hp = Double.parseDouble(parts[1]);
            return p -> {
              AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
              double max = attr != null ? attr.getValue() : 20.0;
              p.setHealth(Math.max(0.0, Math.min(hp, max)));
              return 0;
            };
          }
        case "sethunger":
          {
            int food = Integer.parseInt(parts[1]);
            return p -> {
              p.setFoodLevel(Math.max(0, Math.min(food, 20)));
              return 0;
            };
          }
        case "setair":
          {
            int air = Integer.parseInt(parts[1]);
            return p -> {
              p.setRemainingAir(Math.max(0, Math.min(air, p.getMaximumAir())));
              return 0;
            };
          }
        case "tempban":
          {
            int minMinutes = Integer.parseInt(parts[1]);
            int maxMinutes = Integer.parseInt(parts[2]);
            return p -> {
              int minutes =
                  maxMinutes > minMinutes
                      ? minMinutes
                          + ThreadLocalRandom.current().nextInt(maxMinutes - minMinutes + 1)
                      : minMinutes;
              Date end = new Date(System.currentTimeMillis() + minutes * 60_000L);
              Component msg =
                  Component.text(
                      "Your fortune: (YOU ARE BANNED)", NamedTextColor.RED, TextDecoration.BOLD);
              ProfileBanList bans = Bukkit.getBanList(BanList.Type.PROFILE);
              bans.addBan(
                  p.getPlayerProfile(),
                  LegacyComponentSerializer.legacySection().serialize(msg),
                  end,
                  "s4sfortune");
              p.kick(msg);
              return 0;
            };
          }
        case "lightning-effect":
          return p -> {
            p.getWorld().strikeLightningEffect(p.getLocation());
            return 0;
          };
        case "freeze":
          {
            int ticks = Integer.parseInt(parts[1]);
            return p -> {
              p.setFreezeTicks(Math.max(0, ticks));
              return 0;
            };
          }
        case "setfall":
          {
            float distance = Float.parseFloat(parts[1]);
            return p -> {
              p.setFallDistance(Math.max(0, distance));
              return 0;
            };
          }
        case "spin":
          return p -> {
            float yaw = ThreadLocalRandom.current().nextFloat() * 360f - 180f;
            float pitch = ThreadLocalRandom.current().nextFloat() * 90f - 45f;
            p.setRotation(yaw, pitch);
            return 0;
          };
        case "sound":
          {
            // global: every online player hears it at their own position
            String key = parts[1];
            float volume = parts.length > 2 ? Float.parseFloat(parts[2]) : 6f;
            float pitch = parts.length > 3 ? Float.parseFloat(parts[3]) : 1f;
            return p -> {
              for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), key, SoundCategory.VOICE, volume, pitch);
              }
              return 0;
            };
          }
        case "broadcast":
          {
            String message = joinFrom(parts, 1);
            return p -> {
              Bukkit.getServer()
                  .broadcast(MINI.deserialize(message.replace("%player%", p.getName())));
              return 0;
            };
          }
        case "velocity":
          {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            return p -> {
              p.setVelocity(new Vector(x, y, z));
              return 0;
            };
          }
        case "chat-as":
          {
            String msg = joinFrom(parts, 1);
            return p -> {
              p.chat(msg.replace("%player%", p.getName()));
              return 0;
            };
          }
        case "delay":
          {
            long ticks = Long.parseLong(parts[1]);
            return p -> ticks;
          }
        case "run":
          {
            return consoleCommandStep(plugin, fortuneText, joinFrom(parts, 1));
          }
        case "drop-inventory":
          return p -> {
            PlayerInventory inv = p.getInventory();
            Location loc = p.getLocation();
            for (ItemStack item : inv.getContents()) {
              if (item != null && !item.getType().isAir()) {
                p.getWorld().dropItemNaturally(loc, item);
              }
            }
            inv.clear();
            return 0;
          };
        case "drop-held-item":
          return p -> {
            PlayerInventory inv = p.getInventory();
            ItemStack held = inv.getItemInMainHand();
            if (!held.getType().isAir()) {
              p.getWorld().dropItemNaturally(p.getLocation(), held);
              inv.setItemInMainHand(null);
            }
            return 0;
          };
        case "shuffle-inventory":
          return p -> {
            PlayerInventory inv = p.getInventory();
            List<ItemStack> storage = new ArrayList<>(Arrays.asList(inv.getStorageContents()));
            Collections.shuffle(storage, ThreadLocalRandom.current());
            inv.setStorageContents(storage.toArray(new ItemStack[0]));
            return 0;
          };
        case "steal-random-item":
          // requires the config-gated stash module (see StashFeature)
          return p -> {
            StashFeature stash = plugin instanceof Main main ? main.getStashFeature() : null;
            if (stash == null) {
              plugin
                  .getLogger()
                  .warning(
                      "Fortune \""
                          + fortuneText
                          + "\": steal-random-item skipped, stash feature is disabled");
            } else {
              stash.steal(p);
            }
            return 0;
          };
        case "swap-random-player":
          return p -> {
            List<Player> others = new ArrayList<>(Bukkit.getOnlinePlayers());
            others.remove(p);
            if (!others.isEmpty()) {
              Player other = others.get(ThreadLocalRandom.current().nextInt(others.size()));
              Location mine = p.getLocation().clone();
              p.teleport(other.getLocation());
              other.teleport(mine);
            }
            return 0;
          };
        case "drop-item":
          {
            // simple form: drop-item MATERIAL [count]
            Material material = requireMaterial(parts[1]);
            int count = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
            return dropItemStep(material, count, null, null);
          }
        default:
          plugin.getLogger().warning("Fortune \"" + fortuneText + "\": unknown action: " + raw);
          return null;
      }
    } catch (Exception e) {
      plugin
          .getLogger()
          .warning("Fortune \"" + fortuneText + "\": bad action \"" + raw + "\": " + e);
      return null;
    }
  }

  private static Step dropItemStep(Map<?, ?> params) {
    Material material = requireMaterial(String.valueOf(params.get("material")));
    int count = params.get("count") instanceof Number n ? n.intValue() : 1;
    String name = params.get("name") != null ? String.valueOf(params.get("name")) : null;
    List<String> lore = null;
    if (params.get("lore") instanceof List<?> rawLore) {
      lore = new ArrayList<>();
      for (Object line : rawLore) {
        lore.add(String.valueOf(line));
      }
    }
    return dropItemStep(material, count, name, lore);
  }

  private static Step dropItemStep(
      Material material, int count, String miniName, List<String> miniLore) {
    return p -> {
      ItemStack item = new ItemStack(material, count);
      if (miniName != null || miniLore != null) {
        ItemMeta meta = item.getItemMeta();
        if (miniName != null) {
          meta.displayName(deserializeMini(miniName));
        }
        if (miniLore != null) {
          List<Component> lore = new ArrayList<>();
          for (String line : miniLore) {
            lore.add(deserializeMini(line));
          }
          meta.lore(lore);
        }
        item.setItemMeta(meta);
      }
      p.getWorld().dropItemNaturally(p.getLocation().add(0, 0.5, 0), item);
      return 0;
    };
  }

  // vanilla renders custom names/lore italic unless told otherwise
  private static Component deserializeMini(String miniMessage) {
    return MINI.deserialize(miniMessage)
        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
  }

  private static Material requireMaterial(String name) {
    Material material = Material.matchMaterial(name);
    if (material == null) {
      throw new IllegalArgumentException("unknown material: " + name);
    }
    return material;
  }

  private static String joinFrom(String[] parts, int start) {
    return String.join(" ", Arrays.asList(parts).subList(start, parts.length));
  }
}

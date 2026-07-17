package com.quakeworld.s4sfortune;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Optional "OP RAN WITH THE MONEY" stash module, config-gated:
 *
 * <p>stash: enabled: true world: world x: 0 y: 64 z: 0
 *
 * <p>When enabled: the steal-random-item action stores a random inventory stack in stash.yml, a
 * chest at the configured spot (placed if missing) is covered by an interaction entity (so the
 * chest itself can't be opened), and right-clicking the entity returns the clicking player's stolen
 * items.
 *
 * <p>When disabled (section absent or enabled: false) nothing is loaded — no listener, no
 * chest/entity bootstrap — and stash.yml is NEVER touched, so pending items stay readable for
 * manual restoration: every entry carries player-name, stolen-at, summary and a paste-ready
 * give-command next to the exact-restore data blob.
 */
public class StashFeature implements Listener {

  private static final String ENTITY_TAG = "s4sfortune_stash";

  private final Main plugin;
  private final Location chestLocation;
  private final File stashFile;

  private StashFeature(Main plugin, Location chestLocation) {
    this.plugin = plugin;
    this.chestLocation = chestLocation;
    this.stashFile = new File(plugin.getDataFolder(), "stash.yml");
  }

  /** Returns null (and loads nothing) unless config has stash.enabled: true. */
  public static StashFeature createIfEnabled(Main plugin) {
    ConfigurationSection section = plugin.getConfig().getConfigurationSection("stash");
    if (section == null || !section.getBoolean("enabled", false)) {
      return null;
    }

    String worldName = section.getString("world", "world");
    World world = Bukkit.getWorld(worldName);
    if (world == null) {
      plugin.getLogger().warning("Stash: world \"" + worldName + "\" not found, feature disabled");
      return null;
    }

    Location loc =
        new Location(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    StashFeature feature = new StashFeature(plugin, loc);
    feature.bootstrap();
    Bukkit.getPluginManager().registerEvents(feature, plugin);
    plugin
        .getLogger()
        .info(
            "Stash enabled at "
                + worldName
                + " "
                + loc.getBlockX()
                + ","
                + loc.getBlockY()
                + ","
                + loc.getBlockZ());
    return feature;
  }

  /** Unregisters listeners; the chest, entity and stash.yml are left alone. */
  public void shutdown() {
    HandlerList.unregisterAll(this);
  }

  // place the chest and its covering interaction entity if either is missing
  private void bootstrap() {
    if (chestLocation.getBlock().getType() != Material.CHEST) {
      chestLocation.getBlock().setType(Material.CHEST);
    }

    Location center = chestLocation.clone().add(0.5, 0, 0.5);
    boolean present =
        center
            .getWorld()
            .getNearbyEntities(center, 1, 1, 1, e -> e.getScoreboardTags().contains(ENTITY_TAG))
            .stream()
            .findAny()
            .isPresent();
    if (!present) {
      Interaction interaction = center.getWorld().spawn(center, Interaction.class);
      interaction.setInteractionWidth(1.1f);
      interaction.setInteractionHeight(1.1f);
      interaction.setResponsive(true);
      interaction.setPersistent(true);
      interaction.addScoreboardTag(ENTITY_TAG);
    }
  }

  // Clients send INTERACT and/or INTERACT_AT packets; Bukkit delivers them as
  // two distinct events (PlayerInteractAtEntityEvent does NOT reach handlers
  // of its parent event), so handle both and debounce per click via the hand.

  /** Returns stolen items when the stash's interaction entity is right-clicked (INTERACT). */
  @EventHandler
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    handleStashClick(event);
  }

  /** Returns stolen items when the stash's interaction entity is right-clicked (INTERACT_AT). */
  @EventHandler
  public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
    handleStashClick(event);
  }

  private final java.util.Set<UUID> clickDebounce = new java.util.HashSet<>();

  private void handleStashClick(PlayerInteractEntityEvent event) {
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    if (!event.getRightClicked().getScoreboardTags().contains(ENTITY_TAG)) {
      return;
    }
    event.setCancelled(true);

    // both events can arrive for one physical click; act once per tick
    UUID id = event.getPlayer().getUniqueId();
    if (!clickDebounce.add(id)) {
      return;
    }
    Bukkit.getScheduler().runTask(plugin, () -> clickDebounce.remove(id));

    returnItems(event.getPlayer());
  }

  /** Blocks direct use of the stash chest block (belt and braces next to the entity hitbox). */
  @EventHandler
  public void onInteractBlock(PlayerInteractEvent event) {
    if (event.getClickedBlock() != null
        && event.getClickedBlock().getLocation().equals(chestLocation)) {
      event.setCancelled(true);
    }
  }

  /**
   * Steals one random stack (storage, armor and offhand slots all eligible) from the victim into
   * stash.yml. Empty inventory = nothing happens.
   */
  public void steal(Player victim) {
    PlayerInventory inv = victim.getInventory();
    ItemStack[] contents = inv.getContents();

    List<Integer> occupied = new ArrayList<>();
    for (int i = 0; i < contents.length; i++) {
      if (contents[i] != null && !contents[i].getType().isAir()) {
        occupied.add(i);
      }
    }
    if (occupied.isEmpty()) {
      return;
    }

    int slot = occupied.get(ThreadLocalRandom.current().nextInt(occupied.size()));
    ItemStack stolen = contents[slot].clone();
    inv.setItem(slot, null);

    YamlConfiguration stash = YamlConfiguration.loadConfiguration(stashFile);
    String base = "stashes." + victim.getUniqueId();
    stash.set(base + ".player-name", victim.getName());

    List<Map<?, ?>> items = new ArrayList<>(stash.getMapList(base + ".items"));
    Map<String, Object> entry = new HashMap<>();
    entry.put("stolen-at", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
    entry.put("summary", summarize(stolen));
    entry.put("give-command", giveCommand(victim.getName(), stolen));
    entry.put("data", Base64.getEncoder().encodeToString(stolen.serializeAsBytes()));
    items.add(entry);
    stash.set(base + ".items", items);

    save(stash);
  }

  // returns every stored stack to the clicking player and clears their record
  private void returnItems(Player player) {
    YamlConfiguration stash = YamlConfiguration.loadConfiguration(stashFile);
    String base = "stashes." + player.getUniqueId();
    List<Map<?, ?>> items = stash.getMapList(base + ".items");

    if (items.isEmpty()) {
      player.sendMessage(Component.text("OP DIDN'T TAKE YOUR THINGS", NamedTextColor.GRAY));
      return;
    }

    for (Map<?, ?> entry : items) {
      Object data = entry.get("data");
      if (data == null) {
        continue;
      }
      try {
        ItemStack item =
            ItemStack.deserializeBytes(Base64.getDecoder().decode(String.valueOf(data)));
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
          player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
      } catch (Exception e) {
        plugin
            .getLogger()
            .warning("Stash: could not restore an item for " + player.getName() + ": " + e);
      }
    }

    stash.set(base, null);
    save(stash);
    player.sendMessage(
        Component.text("YOU GOT YOUR MONEY BACK", NamedTextColor.GREEN, TextDecoration.BOLD));
  }

  private String summarize(ItemStack item) {
    String summary = item.getType().name() + " x" + item.getAmount();
    ItemMeta meta = item.getItemMeta();
    if (meta != null && meta.hasDisplayName()) {
      summary +=
          " (" + PlainTextComponentSerializer.plainText().serialize(meta.displayName()) + ")";
    }
    return summary;
  }

  // paste-ready console command for manual restoration without the plugin
  private String giveCommand(String playerName, ItemStack item) {
    String components = "";
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      String asString = meta.getAsComponentString();
      if (asString != null && !asString.equals("[]")) {
        components = asString;
      }
    }
    return "give "
        + playerName
        + " "
        + item.getType().getKey()
        + components
        + " "
        + item.getAmount();
  }

  private void save(YamlConfiguration stash) {
    try {
      stash.save(stashFile);
    } catch (Exception e) {
      plugin.getLogger().severe("Stash: could not save " + stashFile.getName() + ": " + e);
    }
  }
}

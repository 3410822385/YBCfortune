package com.quakeworld.s4sfortune;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point: loads config-defined fortunes and adverts, registers the command executor,
 * schedules the hourly advert broadcast, and handles the chat features (URL linkifying, greentext,
 * whisper linkifying).
 */
public class Main extends JavaPlugin implements Listener {
  // List to store adverts loaded from config
  private List<String> adverts;
  private List<Fortune> regularFortunes = new ArrayList<>();

  private HashMap<String, Date> nextRolls = new HashMap<String, Date>();
  private Commands commandExecutor;
  private StashFeature stashFeature;

  @Override
  public void onEnable() {
    // Save default config if it doesn't exist
    saveDefaultConfig();
    loadAdvertsFromConfig();

    // Stash module first: fortunes that depend on it are dropped when it's off
    stashFeature = StashFeature.createIfEnabled(this);
    regularFortunes = RegularFortunes.loadRegularFortunesFromConfig(this);
    SpecialFortunes.loadSpecialFortunesFromConfig(this);

    // Register command executor
    commandExecutor = new Commands(this, nextRolls, regularFortunes);
    getCommand("roll").setExecutor(commandExecutor);
    getCommand("checkRRolls").setExecutor(commandExecutor);
    getCommand("checkSRolls").setExecutor(commandExecutor);
    getCommand("funnyban").setExecutor(commandExecutor);
    getCommand("rrolltest").setExecutor(commandExecutor);
    getCommand("srolltest").setExecutor(commandExecutor);
    getCommand("unfortunate").setExecutor(commandExecutor);
    getCommand("fortunereload").setExecutor(commandExecutor);

    getServer().getPluginManager().registerEvents(this, this);

    getServer()
        .getScheduler()
        .scheduleSyncRepeatingTask(
            this,
            new Runnable() {
              @Override
              public void run() {
                if (adverts.isEmpty()) {
                  return;
                }
                Component advert =
                    Component.text(
                        "⭐ " + pickRandomFromList(adverts) + " ",
                        TextColor.fromHexString("#0dbd8b"));
                getServer().broadcast(advert);
              }
            },
            0L,
            20L * 60 * 60 // 60 minutes
            );
  }

  /**
   * Reloads config.yml from disk and rebuilds everything derived from it (adverts and the
   * config-defined regular and special fortunes).
   */
  public void reloadPluginConfig() {
    reloadConfig();
    loadAdvertsFromConfig();
    if (stashFeature != null) {
      stashFeature.shutdown();
    }
    stashFeature = StashFeature.createIfEnabled(this);
    regularFortunes = RegularFortunes.loadRegularFortunesFromConfig(this);
    SpecialFortunes.loadSpecialFortunesFromConfig(this);
  }

  /** Returns the stash module, or null when it is disabled in config. */
  public StashFeature getStashFeature() {
    return stashFeature;
  }

  /** Loads adverts from config.yml */
  private void loadAdvertsFromConfig() {
    adverts = new ArrayList<>();
    List<String> configAdverts = getConfig().getStringList("adverts");

    for (String advert : configAdverts) {
      // Replace "future year" with current year for HoloEN3 entry
      if (advert.contains("future year")) {
        advert =
            advert.replace(
                "future year", String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));
      }
      adverts.add(advert);
    }

    getLogger().info("Loaded " + adverts.size() + " adverts from config");
  }

  private <T> T pickRandomFromList(List<T> list) {
    return list.get(ThreadLocalRandom.current().nextInt(list.size()));
  }

  // FIXME break out into separate plugin
  /** Linkifies URLs in chat and colors greentext ({@code >} prefix) messages. */
  @EventHandler
  public void onAsyncChat(AsyncChatEvent e) {
    String plainMessage = PlainTextComponentSerializer.plainText().serialize(e.message());

    Set<Player> recipients = new HashSet<>();
    for (Audience viewer : e.viewers()) {
      if (viewer instanceof Player player) {
        recipients.add(player);
      }
    }
    urlHandler(plainMessage, recipients);

    if (plainMessage.startsWith(">")) {
      e.message(Component.text(plainMessage, NamedTextColor.GREEN));
    }
  }

  /** Linkifies URLs in whispers sent via /msg, /tell and /w. */
  @EventHandler
  public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent e) {
    String[] commandNouns = e.getMessage().split(" ");

    try {
      if (commandNouns[0].equals("/msg")
          || commandNouns[0].equals("/tell")
          || commandNouns[0].equals("/w")) {
        Player recipient = getServer().getPlayerExact(commandNouns[1]);

        if (recipient != null) {
          String[] actualMessageNouns = Arrays.copyOfRange(commandNouns, 2, commandNouns.length);
          String actualMessage = String.join(" ", actualMessageNouns);

          getLogger().info(actualMessage);

          urlHandler(actualMessage, new HashSet<Player>(Arrays.asList(recipient)));
        }
      }
    } catch (IndexOutOfBoundsException expected) {
      // whisper command without a message ("/msg" alone) — nothing to linkify
    }
  }

  /** Sends a clickable link component to the recipients for every valid URL in the message. */
  public void urlHandler(String message, Set<Player> recipients) {
    String[] nouns = message.split(" ");

    for (String noun : nouns) {
      try {
        URL url = new URL(noun);

        Component component =
            Component.text(
                    "[Go To " + url.getHost() + "]",
                    TextColor.fromHexString("#00FF00"),
                    TextDecoration.BOLD,
                    TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(noun));

        for (Player receiver : recipients) {
          getServer()
              .getScheduler()
              .runTask(
                  this,
                  new Runnable() {

                    @Override
                    public void run() {
                      receiver.sendMessage(component);
                    }
                  });
        }
      } catch (MalformedURLException excpt) {
        continue;
      }
    }
  }
}

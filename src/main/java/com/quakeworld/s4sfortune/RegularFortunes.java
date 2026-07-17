package com.quakeworld.s4sfortune;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Regular rolls. All of them live in config.yml under "regularFortunes"; side effects are console
 * commands, built-in actions, and weighted random branches (see FortuneEffects).
 */
public class RegularFortunes {
  private static List<Fortune> regularFortunes = new ArrayList<>();

  /** Rebuilds the regular fortune list from config.yml. */
  public static List<Fortune> loadRegularFortunesFromConfig(Main plugin) {
    regularFortunes.clear();

    List<Map<?, ?>> configFortunes = plugin.getConfig().getMapList("regularFortunes");
    for (Map<?, ?> fortuneMap : configFortunes) {
      Fortune fortune = Fortune.fromConfig(plugin, fortuneMap);
      if (fortune != null) {
        regularFortunes.add(fortune);
      }
    }

    plugin.getLogger().info("Loaded " + regularFortunes.size() + " fortunes from config");

    return regularFortunes;
  }

  /** Returns the list of regular fortunes */
  public static List<Fortune> getRegularFortunes() {
    return regularFortunes;
  }
}

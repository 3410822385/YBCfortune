package com.quakeworld.s4sfortune;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Special (rare) rolls. All of them live in config.yml under "specialFortunes"; side effects are
 * console commands, built-in actions, and weighted random branches (see FortuneEffects).
 */
public class SpecialFortunes {

  private static final List<Fortune> fortunes = new ArrayList<>();

  /** Rebuilds the special fortune list from config.yml. */
  public static List<Fortune> loadSpecialFortunesFromConfig(Main plugin) {
    fortunes.clear();

    List<Map<?, ?>> configFortunes = plugin.getConfig().getMapList("specialFortunes");
    for (Map<?, ?> fortuneMap : configFortunes) {
      Fortune fortune = Fortune.fromConfig(plugin, fortuneMap);
      if (fortune != null) {
        fortunes.add(fortune);
      }
    }

    plugin.getLogger().info("Loaded " + fortunes.size() + " special fortunes from config");

    return fortunes;
  }

  public static List<Fortune> getSpecialFortunes() {
    return fortunes;
  }
}

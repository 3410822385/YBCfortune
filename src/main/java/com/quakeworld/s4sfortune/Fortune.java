package com.quakeworld.s4sfortune;

import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;

/** One rollable fortune: announcement color and text, pool weight, and an optional side effect. */
public class Fortune {
  /** Relative chance within the fortune's pool when no "weight" key is set. */
  public static final int DEFAULT_WEIGHT = 10;

  /** Hex chat color of the announcement, e.g. "#ff0000". */
  public String color;

  /** Announced fortune text; may contain legacy § codes. */
  public String fortune;

  /** Runs on the roller after the announcement; defaults to a no-op. */
  public FortuneSideEffect sideEffect;

  /** Relative chance within this fortune's pool (see DEFAULT_WEIGHT). */
  public int weight = DEFAULT_WEIGHT;

  public Fortune(String color, String fortune, FortuneSideEffect sideEffect) {
    this.color = color;
    this.fortune = fortune;
    this.sideEffect = sideEffect;
  }

  public Fortune(String color, String fortune) {
    this.color = color;
    this.fortune = fortune;
    this.sideEffect = (p) -> {};
  }

  public String getFortune() {
    return fortune;
  }

  /**
   * Builds a Fortune from a config map: color, text, and optional side effects ("commands",
   * "actions", "random" — see FortuneEffects), so fortunes are editable in config.yml (+
   * /fortunereload) without recompiling.
   *
   * <p>Returns null (fortune not loaded at all) when the fortune depends on a disabled module —
   * e.g. steal-random-item with the stash feature off.
   */
  public static Fortune fromConfig(JavaPlugin plugin, Map<?, ?> fortuneMap) {
    String color = (String) fortuneMap.get("color");
    String text = (String) fortuneMap.get("text");

    if (FortuneEffects.usesAction(fortuneMap, "steal-random-item")
        && (!(plugin instanceof Main main) || main.getStashFeature() == null)) {
      plugin.getLogger().info("Fortune \"" + text + "\" not loaded: stash feature is disabled");
      return null;
    }

    FortuneSideEffect effect = FortuneEffects.parse(plugin, text, fortuneMap);
    Fortune fortune = effect == null ? new Fortune(color, text) : new Fortune(color, text, effect);

    if (fortuneMap.get("weight") instanceof Number n) {
      fortune.weight = Math.max(1, n.intValue());
    }
    return fortune;
  }
}

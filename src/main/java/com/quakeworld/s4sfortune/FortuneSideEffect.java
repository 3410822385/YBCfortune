package com.quakeworld.s4sfortune;

import org.bukkit.entity.Player;

/** Side effect executed on the roller after a fortune is announced. */
interface FortuneSideEffect {
  void run(Player player);
}

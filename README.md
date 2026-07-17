# YBCfortune #
Minecraft Plugin that does
* /roll
* Handles Whispers
* Handles in chat Links
* Sends timed banner adverts

The rolls are split into 2 categories Regular(19 in 20) and Special(1 in 20).
All rolls now live in config.yml and reload live with /fortunereload (alias /frl).

## Changelog 2.1 ##
* Updated to Paper 26.2 and 1.21.11
  * Migrated chat off BungeeCord to Adventure
  * Name bans replaced with profile bans
  * Build with mvn package (26.2 jar, Java 25) or mvn package -Pmc121 (1.21.11 jar that also runs on 26.2, Java 21)
* Moved ALL rolls into config.yml, nothing hardcoded anymore
  * commands: console commands, %player% is the roller
  * actions: fire, sethealth, sethunger, setair, tempban, lightning-effect, velocity, chat-as, delay, run, drop-inventory, drop-held-item, shuffle-inventory, swap-random-player, steal-random-item, freeze, setfall, spin, sound, broadcast, drop-item
  * random: weighted branches, ONE picked per roll, nesting allowed
  * weight: per roll rarity inside its pool (default 10 when omitted)
* Added /fortunereload (/frl) to reload config.yml without a restart
* Added the OP RAN WITH THE MONEY roll with a stash chest
  * stash: section in config.yml; disabled = the roll is not loaded at all and stash.yml is never touched
  * Stolen items stored in stash.yml with a paste-ready give command for manual recovery
  * Right click the stash chest to get YOUR items back

## Changelog Pre-1.19.4 ##
* Normal Roll
  *Changed Bad Luck to Very Bad Luck
    * increased amount of cobbstone dropped
    * Poison time reduced to 60s
    * Added effect Wither for 5s
    * Added effect that reduces health to 1/2 heart
  * Bad Luck
    * Added Slow for 90s
  * Moved Godly luck to special Roll
* Special Roll
  * Zeus Roll re-enabled with LightingEffect
  * Added Fortune Op's Blessing Roll
  * Added Levitation effect to ayy lmao
  * Added roll Pippa's foot reveal
  * Godly luck 
    * changed rabbit's foot to Villager eggs
    * Added roll for special iron ingot
  * ADDED fortune Caught a fish

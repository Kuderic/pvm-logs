# Changelog

## 1.0 (first release as Luck vs Skill)
Luck vs Skill is its own Plugin Hub entry. It began as a fork of Arodab's PvM Performance Tracker (version 0.2 there); its settings and fight history are its own, and it leaves that plugin's alone.

**Luck and Execution**
- Luck as a percentile of how the same attacks could have rolled (bottom 20%, the middle half, top 15%), and the seconds it cost or saved.
- Execution split into uptime (the ticks you lost), and prayer, gear (armour only: your weapon and ammo are never judged) and stat boost (the expected damage each cost).
- Downtime the boss forces on you (where a mechanic stops you attacking) isn't lost uptime: it shows as a neutral "mechanics" line.
- Eating is neutral: its own line, not a cost. At Vardorvis, eating for avoidable damage counts against Execution after the kill, and damage taken says how much was avoidable.
- Defence drain: the Defence your and your party's specials took, live and after the kill, with each boss's floor, immunity and regeneration; a missed special counts as bad luck, and the drain's worth is shown in damage and seconds.

**What's counted**
- Your thrall's hits, recoil, vengeance, and burn, poison, venom and bleed are kept apart from your weapon's damage; the boss healing comes off.
- Damage taken, after the fight broken down by kind.
- Boss stats from the OSRS Wiki's DPS calculator, with the right version picked from the health bar (Awakened or not), stats that move with the boss's hitpoints (Vardorvis), the Leviathan's and the Sire's damage modifiers, and Chambers of Xeric and Tombs of Amascut scaling.
- Bosses with parts or phases (the Sire, the Guardians, the Royal Titans, the Kraken, Hespori, the Maggot King, Araxxor with his araxytes, and others) are one kill each. A boss that leaves view alive isn't a finished fight.
- A run of monsters of one kind is one room, not a fight per monster, with a lighter view on the side panel.
- Dying ends the kill, and a fresh boss after a trip is a new kill.
- A hit is counted only up to what the boss had left when it landed; the room's damage that isn't yours isn't counted as another player's, and your hits on the boss count while you're on one of its adds.
- Araxytes hatched under Araxxor take a guaranteed max hit from the right weapons, and the expected damage counts it.
- The slayer helmet's bonus on task, with the game's own task table: alternatives such as the Deranged archaeologist, Artio, Spindel and Calvar'ion count, and a kill the game counts that the table missed is learned for the session.
- A shot thrown just before a gear switch is scored with what you held when you threw it, and a single-target weapon's hit on another monster is its own attack.

**What it shows**
- The fight overlay ("Ledger"): live, then the full breakdown and your usual time after the kill. Execution's parts show only the ones that cost time, unless you choose all of them.
- The DPS overlay: your weapon's DPS, max hit and accuracy against the monster it names, and its special's. It can stay up outside fights.
- Times of day show am and pm, so they can't be read as kill times.
- A new side panel: Fight (where the time went, luck curve, damage and DPS by weapon (the boss, its adds, or both, switched in the card's header), damage over time, tick strip, forced downtime), History (search, your usual time and execution per boss, every kill; bosses and monsters apart, and a room you died in marked) and Trip; boss icons from the game; larger and smooth text options.
- Settings in four sections; Bosses only is on for new installs.

**Under the hood**
- Data lives in `~/.runelite/plugin-data/luck-vs-skill/`, through RuneLite's file API, as Plugin Hub rules require.
- Builds against current RuneLite (1.13.4).
- Save debug log on the side panel, for reporting a figure that looks wrong. Every debug log names the release, or the commit it was built from.
- A random id for this install is kept in the data folder for later. Nothing is sent anywhere, and it isn't tied to your account.
- A Party tab: in a RuneLite party, members who have the plugin share their own damage, DPS, Luck and Execution (on by default; a setting turns sending off).
- With Debug logging on, a Calibration report checks your saved kills against the plugin's expectations and says in plain words where it's off.

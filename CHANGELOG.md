# Changelog

## 1.0 (first release as Luck vs Skill)
Luck vs Skill is its own Plugin Hub entry. It began as a fork of Arodab's PvM Performance Tracker (version 0.2 there); its settings and fight history are its own, and it leaves that plugin's alone.

**Luck and Execution**
- Luck as a percentile of how the same attacks could have rolled (bottom 20%, the middle half, top 15%), and the seconds it cost or saved.
- Execution split into uptime, prayer, gear (armour only: your weapon and ammo are never judged) and stat boost, each in seconds.
- Eating is neutral: its own line, not a cost. At Vardorvis, eating for avoidable damage counts against Execution after the kill, and damage taken says how much was avoidable.
- Defence drain: the Defence your and your party's specials took, live and after the kill, with each boss's floor, immunity and regeneration; a missed special counts as bad luck, and the drain's worth is shown in seconds.

**What's counted**
- Your thrall's hits, recoil, vengeance, and burn, poison, venom and bleed are kept apart from your weapon's damage; the boss healing comes off.
- Damage taken, after the fight broken down by kind.
- Boss stats from the OSRS Wiki's DPS calculator, with the right version picked from the health bar (Awakened or not), stats that move with the boss's hitpoints (Vardorvis), the Leviathan's and the Sire's damage modifiers, and Chambers of Xeric and Tombs of Amascut scaling.
- Bosses with parts or phases (the Sire, the Guardians, the Royal Titans, the Kraken, Hespori and others) are one kill each.
- A shot thrown just before a gear switch is scored with what you held when you threw it.

**What it shows**
- The fight overlay ("Ledger"): live, then the full breakdown and your usual time after the kill.
- The loadout overlay: your weapon's max hit, accuracy and DPS against the target, and its special's.
- A new side panel: Fight (where the time went, luck curve, damage by weapon, damage over time, tick strip), History (search, your usual time and execution per boss, every kill) and Trip; boss icons from the game; larger and smooth text options.
- Settings in four sections; Bosses only is on for new installs.

**Under the hood**
- Data lives in `~/.runelite/plugin-data/luck-vs-skill/`, through RuneLite's file API, as Plugin Hub rules require.
- Builds against current RuneLite (1.13.4).
- Save debug log on the side panel, for reporting a figure that looks wrong.

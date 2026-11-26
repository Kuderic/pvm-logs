# Changelog

## 1.0: the first release as Luck vs Skill
Luck vs Skill is its own Plugin Hub entry. It began as a fork of Arodab's PvM Performance Tracker (version 0.2 there); its settings and fight history are its own, and it leaves that plugin's alone, so the two can be installed side by side.

**After every kill**
- A kill card at the top of the side panel: your time against your usual (with a gold PB on a new best), your Luck as top N%, average or bottom N% of how the same attacks could have rolled, and your Skill as a share of a perfect fight with a grade from S to C.
- DPS against expected on the card and on every weapon row, and damage against expected in Damage's header.
- Grades are against your own last 50 kills at that boss, scored the same way, and come from skill only, never luck.
- Badges for a 100th kill, a Flawless kill (no avoidable damage, at bosses with attack rules), a Clean run (no idle tick, best setup) and streaks of A's.
- Luck in seconds as well: what the dice saved or cost you, and your time with average luck.
- Skill split into uptime and the expected damage your prayer, gear (armour only: your weapon and ammo are never judged) and stat boost cost.
- Defence drain: the Defence your and your party's specials took, each boss's floor and immunity, a missed special counted as bad luck, and the drain's worth.
- One time bar for where the kill's time went: best play, skill's loss, luck's share, and the time a boss's shield held you back (the Alchemical Hydra's).
- At Vardorvis, eating for avoidable damage counts against your skill, and damage taken says how much was avoidable.
- A kill-end moment, the die roll and a flash on the card; the Celebrations setting turns it off.

**Over time**
- History shows your progress at each boss: personal bests, how your grades spread, streaks and badges, and every kill with its time, luck and grade. Bosses and monster runs are listed apart.
- Trip sums the session's kills. CSV export for everything.

**During the fight**
- The fight overlay: damage against expected, Luck and Skill, live, and the full breakdown after the kill.
- The DPS overlay: your weapon's DPS, max hit and accuracy against the monster it names, and its special's. It can stay up outside fights.
- The kill card live, updated as your hits land: the clock, your luck so far on its track, your Skill so far with your last ten attacks (on time, or how late each went out), and your DPS against expected over the time your attacks took.
- Only your own output: nothing about the boss's attacks, and no comparison with your average until the fight is over.

**With a party**
- In a RuneLite party, a Party section on the Fight tab: members who run Luck vs Skill share their own figures, a row each for those in the fight (DPS while it runs, then damage, Luck and Skill), with the party's timeline charted by the figure you pick; click a member for their Fight tab. Members who don't run it are listed too.
- On by default, through RuneLite's own party service only; turn off "Share with my RuneLite party" to send nothing.

**What's counted**
- Your weapon's damage apart from your thrall, cannon, recoil, vengeance, burn, poison, venom and bleed, and the boss healing off. Damage that isn't yours or another player's (a boss hitting itself, araxyte explosions) counts as the room's.
- Boss stats from the OSRS Wiki's DPS calculator, the right version picked from the health bar, and the bosses' own mechanics where they change your damage (the Leviathan, the Sire, the Royal Titans, Yama, the Wardens, the Alchemical Hydra's shield and others). Golembane weapons and flat armour as the DPS calculator has them. The max hit shown leaves out a bolt's special effect.
- Chambers of Xeric and Tombs of Amascut scaling, by party size and raid level.
- A boss with parts, phases or adds is one kill; weak adds, and a raid's weak monsters outside its rooms, are swatted in passing. A run of monsters of one kind is one room, with a lighter view.
- Downtime the boss forces (a stun, a dive, a burrow, the Hydra's form changes, lightning and fire) and stalls the guides teach (Cerberus above 400) aren't held against your uptime. An attack that went out late, as your XP shows, counts its late ticks.
- Dying ends the kill. A boss you come back to after a death, an idle pause or a trip away carries on its kill; a world hop starts a new one.
- The slayer helmet on task, with the game's task table and its alternatives.

**Under the hood**
- Fight history and records are saved so a crash can't wipe them, with a last-good copy of each.
- The history save at a kill is streamed, off the client's thread.
- Data lives in `~/.runelite/plugin-data/luck-vs-skill/`, through RuneLite's file API.
- With Debug logging on: a debug log that names the release it came from, and a calibration report that checks your saved kills against the plugin's expectations.

The task-by-task list kept before this release was never published; it's dropped here, and its content is folded into the notes above.

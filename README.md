# Luck vs Skill

A RuneLite plugin for bossing. After every kill it tells you how much of your time was luck and how much was you, for any boss. There's no account to make, and nothing about you leaves your computer.

It works out what each of your attacks should have done, from your gear, levels, boosts and prayers against the boss's defence, and compares that with what you actually hit. The gap between your kill and a perfect one splits into two parts: **Luck** (the dice) and **Execution** (the time you weren't attacking, or were attacking without the right setup).

## What it shows

### Luck and Execution
- **Luck** says how lucky the kill was: where your damage falls among all the ways the same attacks could have rolled (bottom 20%, the middle half, top 15%), and what that cost or saved in seconds.
- **Execution** says how much of the fight you spent attacking, and with the right setup: uptime, prayer, gear and stat boost, each with the seconds it cost.
- **After a kill at a boss with attack rules** (Vardorvis so far), it also counts the food you ate because of damage you could have avoided, and how much of the damage you took was avoidable.

### The fight overlay
Live during the fight, the full breakdown after the kill.

<table>
<tr>
<td><img src="docs/images/overlay-mid-fight.png" alt="The fight overlay mid-fight, with the loadout overlay under it"></td>
<td><img src="docs/images/overlay-after-kill.png" alt="The fight overlay after a kill"></td>
</tr>
<tr>
<td>Mid-fight, with the loadout overlay under it</td>
<td>After the kill</td>
</tr>
</table>

After a kill at a boss with attack rules, Execution gains an **avoidable eating** line and damage taken says how much was avoidable:

<img src="docs/images/overlay-ruled-kill.png" alt="The fight overlay after a Vardorvis kill, with avoidable eating and avoidable damage taken">

### The loadout overlay
Your current weapon's max hit, accuracy and DPS against the target, and its special attack's.

<img src="docs/images/overlay-loadout.png" alt="The loadout overlay for an Abyssal tentacle">

### The side panel
- **Fight:** where the time went, Luck, Execution, damage by source and damage taken.
- **History:** your saved kills, and your usual time and execution at each boss.
- **Trip:** the session's kills together.

<table>
<tr>
<td><img src="docs/images/panel-fight.png" alt="The side panel's Fight tab after a Vardorvis kill" width="242"></td>
<td><img src="docs/images/panel-history.png" alt="The side panel's History tab" width="242"></td>
<td><img src="docs/images/panel-trip.png" alt="The side panel's Trip tab" width="242"></td>
</tr>
<tr><td>Fight</td><td>History</td><td>Trip</td></tr>
</table>

The Fight tab during a kill, updating every 5 ticks, then the full view once the boss dies (a simulated two-minute Vardorvis kill at 7.5× speed):

<img src="docs/images/panel-fight-live.gif" alt="The Fight tab updating through a kill" width="242">

### What it never shows during a fight
Nothing about the boss's attacks, and no comparison with your own average while you fight: only your own output, as Jagex's rules for third-party clients require. Your average appears once the fight is over.

### Also
- Counts your thrall's hits apart from your own, and burn, poison and the boss healing apart from your weapon.
- Scales boss defence for party size and raid level in the Chambers of Xeric and Tombs of Amascut.
- Exports fights to CSV.

All the pictures here are drawn by the plugin's own code (see Developing), over a plain dark backdrop: the overlays are see-through in game.

## Where it came from
Luck vs Skill began as a fork of Arodab's [PvM Performance Tracker](https://github.com/Arodab/pvm-performance-tracker), and builds on his combat model. It fixed the build against current RuneLite and counts thrall hits apart from your own ([Arodab's issue #1](https://github.com/Arodab/pvm-performance-tracker/issues/1)).

It is its own plugin, with its own settings and its own folder: nothing it does reads, moves or changes PvM Performance Tracker's settings or fight history, so the two can be installed side by side. Fight history, exports and logs live in `~/.runelite/plugin-data/luck-vs-skill/`.

## Developing
These are the steps that work on Windows. Run them in PowerShell, from the repo folder.

1. **Install JDK 11** (once):
   ```powershell
   winget install EclipseAdoptium.Temurin.11.JDK
   ```
2. **Point `JAVA_HOME` at it** (in every new PowerShell window):
   ```powershell
   $env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-11*" | Select-Object -First 1).FullName
   ```
   Or paste the exact folder, e.g. `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-11.0.x-hotspot"`.
3. **Start the dev client** with the plugin loaded (developer mode):
   ```powershell
   .\gradlew.bat run
   ```
4. **Log in:** in the client's sidebar, open the Jagex account panel → **Add Jagex Account** → sign in → pick your character → **Play Now**. If an old `.runelite\credentials.properties` exists, delete it first.
5. **Capture a debug log:** after a fight, press **Save debug log** on the side panel's Fight tab (or **Save log** under History → Tools). It writes the last few thousand lines. For a whole session, turn on **Debug logging** in the plugin's settings instead.
6. **Find the files** in `%USERPROFILE%\.runelite\plugin-data\luck-vs-skill\`: `pvm-debug-recent-*.log` (the button), `pvm-debug-*.log` (the setting), `history.json` and CSV exports.

**Tests:** `.\gradlew.bat test` on Windows, or `./gradlew test` on Linux and macOS (JDK 11 or newer). CI runs them on every push and pull request.

**README pictures:** everything in `docs/images/` comes from one generator, `ReadmePictures` (test code), which draws with the plugin's own overlay and panel code. Run it again whenever the look changes; the same code gives the same files.
```
./gradlew shadowJar
java -Djava.awt.headless=true -cp build/libs/pvm-logs-unspecified-all.jar com.pvmlogs.ReadmePictures
```
On Windows (PowerShell needs the quotes):
```powershell
.\gradlew.bat shadowJar
java "-Djava.awt.headless=true" -cp build\libs\pvm-logs-unspecified-all.jar com.pvmlogs.ReadmePictures
```

`CLAUDE.md` covers how Claude sessions work on this repo. `docs/HANDOFF.md` records what is known about thrall damage and other damage that isn't the player's attack.

## Credits
- Arodab, for PvM Performance Tracker, which Luck vs Skill began as a fork of;
- Special thanks to Kinomoto and zLost for a ton of valuable feedback and ideas for the plugin;
- Portions of the combat math were ported from the LlemonDuck dps-calculator (BSD-2);
- Chambers of Xeric scaling math taken from **GearScape**.
- Defence drain tracking follows RuneLite's Special Attack Counter and was checked against Better Party Defence (with OSParty) and Party Defence Tracker, all BSD-2; notices in `THIRD-PARTY.md`.

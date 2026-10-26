# PvM Performance Tracker

This is a maintained fork of [PvM Performance Tracker](https://github.com/Arodab/pvm-performance-tracker) by Arodab, and builds on his combat model. It fixes the build against current RuneLite and counts thrall hits apart from your own ([Arodab's issue #1](https://github.com/Arodab/pvm-performance-tracker/issues/1)), with per-kill logs to follow.

It keeps the original plugin's name, settings group (`pvmperformance`) and data folder (`~/.runelite/pvm-performance-tracker/`), so it can be released as an update to the existing Plugin Hub entry without resetting anyone's settings or fight history.

A RuneLite plugin that tracks your real vs expected combat performance (damage, accuracy, DPS) against any NPC. 

Instead of just telling you how much damage you dealt, this plugin models your exact gear, stats, and prayers on every single tick to calculate what your **expected** damage should have been. This allows you to evaluate your luck, identify missed attacks, and truly measure your performance.

## Features
- **Efficiency Tracker:** Calculates your expected damage versus what it would have been with flawless execution. It automatically accounts for mistakes by detecting:
  - **Missed Gear Switches:** (Optional) Scans your inventory to see if better gear was available for the weapon used.
  - **Missed Prayers:** Identifies attacks made without the correct offensive prayer active.
  - **Unpotted Attacks:** Tracks attacks made when your stat-boosting potions had expired.
- **Raid Support:** Dynamically scales monster defence based on your party size and raid level in the Chambers of Xeric and Tombs of Amascut.
- **Live Overlay:** Displays a real-time comparison of your actual damage vs expected damage.
- **Exportable Data:** Dumps your session fight data to CSV for further analysis.

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
5. **Capture a debug log:** after a fight, press **Save debug log** in the plugin's side panel. It writes the last few thousand lines. For a whole session, turn on **Debug logging** in the plugin's settings instead.
6. **Find the files** in `%USERPROFILE%\.runelite\pvm-performance-tracker\`: `pvm-debug-recent-*.log` (the button), `pvm-debug-*.log` (the setting), `history.json` and CSV exports.

**Tests:** `.\gradlew.bat test` on Windows, or `bash ./gradlew test` on Linux and macOS (JDK 11 or newer). CI runs them on every push and pull request.

`CLAUDE.md` covers how Claude sessions work on this repo. `docs/HANDOFF.md` records what is known about thrall damage and other damage that isn't the player's attack.

## Credits
- Arodab, for PvM Performance Tracker, which this is forked from;
- Special thanks to Kinomoto and zLost for a ton of valuable feedback and ideas for the plugin;
- Portions of the combat math were ported from the LlemonDuck dps-calculator (BSD-2);
- Chambers of Xeric scaling math taken from **GearScape**.

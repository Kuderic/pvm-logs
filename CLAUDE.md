# CLAUDE.md

**PvM Performance Tracker**, a RuneLite plugin: actual vs expected damage per boss kill, split into luck and execution. It's a maintained fork of [Arodab/pvm-performance-tracker](https://github.com/Arodab/pvm-performance-tracker). "PvM Logs" is the project's working name in its planning docs.

## Build and test
| | Linux / macOS | Windows (PowerShell) |
|---|---|---|
| Tests | `./gradlew test` | `.\gradlew.bat test` |
| One test class | `./gradlew test --tests com.pvmlogs.OriginalNamesTest` | `.\gradlew.bat test --tests com.pvmlogs.OriginalNamesTest` |
| Dev client | `./gradlew run` | `.\gradlew.bat run` |

- Java 11 target; CI runs the tests on Temurin 11 on every push and PR (`.github/workflows/ci.yml`). Newer JDKs build it too.
- The dev client needs a display and a Jagex login, so only Eric can run it. See README "Developing".
- DPS-calc watcher: `python3 scripts/dps-calc-watch/test_watch.py`, and `python3 scripts/dps-calc-watch/watch.py --dry-run`.

## How work is organised
The plan, task briefs and status docs live in the claude.ai Project ("OSRS PvE Performance Tracker IMPROVED"), not in this repo. Read `claude/PROJECT_PLAN.md` there first: roles, the file-ownership table, open tasks.
- Work only on the task brief you were given (`claude/tasks/T-NNN-*.md`), inside the files it and the ownership table give you.
- Use your own branch and open a PR into `master`. Never push to `master`.
- Run `git fetch && git rebase origin/master` before every push.
- End every session by replacing your status doc in `claude/status/`.
- The Orchestrator chat reviews PRs; Eric merges.
- Guardrails: the live overlay shows only the player's own output, never boss behaviour (Jagex rules). No new third-party Java dependencies. No GPL code (loa-logs).

## Claude can't play the game
Any claim about how the game behaves ("thrall splats land on the projectile's first tick", "burn isn't `isMine()`") needs a debug log from Eric's client or a test. Say which. A guess is labelled as one. Read `docs/HANDOFF.md` before working on thralls or any damage that isn't the player's attack: it lists what has already been measured and failed.

## Names that must not drift
- Data folder: `~/.runelite/plugin-data/<PvmPerformanceConfig.INTERNAL_NAME>/` (`pvm-performance-tracker`), from RuneLite's `getPluginDirectory()`. Since T-009 all file access goes through `net.runelite.client.util.Filepath`, never `java.io.File` or `java.nio.file.Files` (Plugin Hub rule). Never write a folder name as a literal.
- Legacy folder: `PvmPerformanceConfig.DATA_DIR` = `pvm-performance-tracker`, i.e. `~/.runelite/pvm-performance-tracker/`. The descriptor's `legacyDataDirectory` makes RuneLite move it into the data folder once.
- Settings group: `PvmPerformanceConfig.GROUP` = `pvmperformance`.
- Display name: PvM Performance Tracker.

`OriginalNamesTest` pins these. Changing any of them would reset settings or hide fight history for the users of Arodab's hub entry. The package `com.pvmlogs` isn't user-visible and stays as it is.

`ForbiddenApiTest` fails the build on anything the Plugin Hub forbids (the wiki's list, plus the `Filepath`-only rule above).

## Layout
- `src/main/java/com/pvmlogs/`: one package. Most logic is in two ~3k-line files:
  - `PvmPerformancePlugin`: event handlers, fight lifecycle, CSV, history;
  - `CombatCalc`: the expected-damage engine.
- Thrall attribution: `ThrallTracker`, `Thralls`, `XpLedger`, `XpDecoder`. Monster stats: `MonsterStatsProvider`, from the DPS calc's `monsters.json`.
- `src/test/java/com/pvmlogs/`: JUnit 4. `PvmPerformancePluginTest` is the dev-client launcher (`main`), not a test.
- `scripts/dps-calc-watch/`: the weekly DPS-calc watcher (`.github/workflows/dps-calc-watch.yml`).
- Code style: tabs, RuneLite's brace style. Match the code around you.

## Debug logs from Eric's PC
They're in `C:\Users\<user>\.runelite\plugin-data\pvm-performance-tracker\`:
- `pvm-debug-recent-<stamp>.log`: the side panel's **Save debug log** button (the last few thousand lines, with a setup header);
- `pvm-debug-<stamp>.log`: the stream while the **Debug logging** setting is on;
- `history.json`, CSV exports (`pvm-<label>-<stamp>.csv`), and the `monsters.json` cache.

Older logs:
- written between T-004 and T-009 were in `...\.runelite\pvm-performance-tracker\`. RuneLite moved them into the folder above the first time a T-009 build started;
- written before T-004 (2026-10-26), including the first Vardorvis logs, are in `...\.runelite\pvm-logs\`, which isn't moved.

To read them, the chat has to be linked to Eric's computer through the Claude desktop app. Request access to that one folder (`~/.runelite/plugin-data/pvm-performance-tracker`); Eric approves the prompt. Read the files where they are, and ask before copying a log into the repo as a test fixture.

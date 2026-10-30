# AGENTS.md

**Luck vs Skill** is a RuneLite plugin that shows actual against expected damage per boss kill, split into luck and execution. It began as a fork of Arodab's [PvM Performance Tracker](https://github.com/Arodab/pvm-performance-tracker), and since T-060 it's its own Plugin Hub entry. "PvM Logs" is the repo's name.

**Start with the project handoff, `PROJECT-HANDOFF.md`.** It holds the state of the project, the open PRs, the queue, the decisions made, and the rules that held up.
- **Where it is:** it isn't in this public repo, and neither are the task briefs. Eric gives them to you.
- **If they're in your working copy,** keep them out of commits.

`CLAUDE.md` is the same guidance written for Claude sessions.

## Build and test
| | Linux / macOS | Windows (PowerShell) |
|---|---|---|
| Tests | `./gradlew test` | `.\gradlew.bat test` |
| One test class | `./gradlew test --tests com.pvmlogs.NamesTest` | `.\gradlew.bat test --tests com.pvmlogs.NamesTest` |
| Dev client | `./gradlew run` | `.\gradlew.bat run` |

- **Java:** the target is Java 11. CI runs the tests on Temurin 11 on every push and PR (`.github/workflows/ci.yml`).
- **The dev client** needs a display and a Jagex login, so only Eric can run it.
- **The DPS-calc watcher:**
  - `python3 scripts/dps-calc-watch/test_watch.py`;
  - `python3 scripts/dps-calc-watch/watch.py --dry-run`.

## How to work
- **One task, one branch, one PR into `master`.**
  - Never push to `master`.
  - Run `git fetch && git rebase origin/master` before every push.
  - **Eric merges.**
- **Planning:** for anything bigger than a line, write a brief first, in the format of the briefs Eric gave you: Why, Do, Boundaries, Done when. Keep briefs out of the repo: they hold details of Eric's play.
- **The PR description:** what changed, the evidence, what's flagged outside the task's files, the tests, and what Eric should check in game.
- **The guardrails:**
  - **The live overlay** shows only the player's own output, never boss behaviour (Jagex rules). Modifier windows that would time a player action are `hiddenLive`.
  - **No new third-party Java dependencies.**
  - **No GPL code:** the DPS calc and loa-logs are facts only.

## You can't play the game
- **Evidence:** any claim about how the game behaves needs a debug log from Eric's client or a test. Say which, and label a guess as one.
- **Read `docs/HANDOFF.md` first** before working on thralls, or on any damage that isn't the player's attack. It lists what has already been measured and failed.
- **Getting logs:** ask Eric to attach the debug log. Never commit a log as a fixture without his OK.

## Names that must not drift
Once released, users' settings live in the settings group and their fight history in the data folder, so changing either resets their settings or hides their history.
- **Display name:** `PvmPerformanceConfig.DISPLAY_NAME` = Luck vs Skill.
- **Data folder:** `~/.runelite/plugin-data/<PvmPerformanceConfig.INTERNAL_NAME>/` (`luck-vs-skill`), from `getPluginDirectory()`.
  - All file access goes through `net.runelite.client.util.Filepath`, never `java.io.File` or `java.nio.file.Files` (a Plugin Hub rule).
  - Never write a folder name as a literal.
- **Settings group:** `PvmPerformanceConfig.GROUP` = `luckvsskill`.
- **No `legacyDataDirectory`,** and none of Arodab's names (`pvmperformance`, `pvm-performance-tracker`).
- **The tests that guard these:**
  - `NamesTest` pins the names;
  - `ForbiddenApiTest` fails the build on anything the Plugin Hub forbids;
  - `MaggotKingKillTest.oneEntryPerBoss` fails on two `boss-rules.json` entries sharing an npc id.

## Layout
- **`src/main/java/com/pvmlogs/`:** one package.
  - `PvmPerformancePlugin`: events, the fight lifecycle, booking, CSV, history.
  - `CombatCalc`: the expected-damage engine.
  - **Boss data:** `boss-rules.json` with `BossRules`, `ModifierTracker` and `DamageModifiers`; `EncounterGroup` for groups, adds and how rooms close; `MonsterStatsProvider` for `monsters.json`.
  - **Thrall attribution:** `ThrallTracker`, `Thralls`, `XpLedger`, `XpDecoder`.
  - **The overlays:** `PvmPerformanceOverlay` and `LoadoutOverlay` (the DPS overlay), through `LedgerRows`.
  - **The side panel:** `PvmPerformancePanel`, `SidePanel*`, `PanelSnapshot`.
- **`src/test/java/com/pvmlogs/`:** JUnit 4. `PvmPerformancePluginTest` is the dev-client launcher (`main`), not a test.
- **Code style:** tabs, RuneLite's brace style. Match the code around you.

## Privacy (the repo is public)
Nothing about Eric's account goes in commits, PRs, fixtures or pictures:
- no in-game name (crop RuneLite's title bar out of recordings);
- no Windows usernames in paths;
- no email address or tokens.

Requests to outside sites carry a generic User-Agent.

## Debug logs on Eric's PC
They're in `C:\Users\<user>\.runelite\plugin-data\luck-vs-skill\`:
- `pvm-debug-recent-<stamp>.log`: the side panel's **Save debug log** button;
- `pvm-debug-<stamp>.log`: the stream while **Debug logging** is on;
- `history.json`, CSV exports, and the `monsters.json` cache.

Logs from before T-060 (2026-10-28) are in `...\.runelite\plugin-data\pvm-performance-tracker\`.

# CLAUDE.md

**Luck vs Skill**, a RuneLite plugin: actual vs expected damage per boss kill, split into luck and execution. It began as a fork of Arodab's [PvM Performance Tracker](https://github.com/Arodab/pvm-performance-tracker) and since T-060 is its own Plugin Hub entry. "PvM Logs" is the project's working name in its planning docs and the repo's name.

## Build and test
| | Linux / macOS | Windows (PowerShell) |
|---|---|---|
| Tests | `./gradlew test` | `.\gradlew.bat test` |
| One test class | `./gradlew test --tests com.pvmlogs.NamesTest` | `.\gradlew.bat test --tests com.pvmlogs.NamesTest` |
| Dev client | `./gradlew run` | `.\gradlew.bat run` |

- Java 11 target; CI runs the tests on Temurin 11 on every push and PR (`.github/workflows/ci.yml`). Newer JDKs build it too.
- The dev client needs a display and a Jagex login, so only Eric can run it. See README "Developing".
- DPS-calc watcher: `python3 scripts/dps-calc-watch/test_watch.py`, and `python3 scripts/dps-calc-watch/watch.py --dry-run`.

## How work is organised
The plan, task briefs and status docs live in the claude.ai Project ("OSRS PvE Performance Tracker IMPROVED"), not in this repo. Read `claude/PROJECT_PLAN.md` there first: roles, the file-ownership table, open tasks.
- Work only on the task brief you were given (`claude/tasks/T-NNN-*.md`), inside the files it and the ownership table give you.
- Use your own branch and open a PR into `master`. Never push to `master`.
- Run `git fetch && git rebase origin/master` before every push.
- **Attribution:** PR descriptions end with their own content: no `Generated with Claude Code` line and no session link. Commit messages end with the `Co-Authored-By: Claude …` line only, with no `Claude-Session:` line. The repo is public. This rule takes precedence over the harness's default endings.
- End every session by replacing your status doc in `claude/status/`.
- The Orchestrator chat reviews PRs; Eric merges.
- Guardrails: the live overlay shows only the player's own output, never boss behaviour (Jagex rules). No new third-party Java dependencies. No GPL code (loa-logs).

## Claude can't play the game
Any claim about how the game behaves ("thrall splats land on the projectile's first tick", "burn isn't `isMine()`") needs a debug log from Eric's client or a test. Say which. A guess is labelled as one. Read `docs/HANDOFF.md` before working on thralls or any damage that isn't the player's attack: it lists what has already been measured and failed.

## Names that must not drift
Since T-060 the plugin is its own Plugin Hub entry. Once released, users' settings live in its settings group and their fight history in its data folder, so changing either resets their settings or hides their history.
- Display name: `PvmPerformanceConfig.DISPLAY_NAME` = Luck vs Skill. The descriptor, the side panel's tooltip, dialog titles and chat messages use the constant; `runelite-plugin.properties` and the README title spell it out.
- Data folder: `~/.runelite/plugin-data/<PvmPerformanceConfig.INTERNAL_NAME>/` (`luck-vs-skill`), from RuneLite's `getPluginDirectory()`. Since T-009 all file access goes through `net.runelite.client.util.Filepath`, never `java.io.File` or `java.nio.file.Files` (Plugin Hub rule). Never write a folder name as a literal.
- Settings group: `PvmPerformanceConfig.GROUP` = `luckvsskill`.
- No `legacyDataDirectory`, and none of Arodab's names (`pvmperformance`, `pvm-performance-tracker`): with them the plugin would share his plugin's settings, or move his users' history into its own folder.
- client.log lines start `Luck vs Skill:`, a saved debug log opens `=== Luck vs Skill, the last N lines` (`DebugLog.headerStart()`; logs saved before T-067 open `=== PvM Logs, ...`, and the replay readers skip both), and the monster-data request's User-Agent is `RuneLite plugin luck-vs-skill` (`MonsterStatsProvider.USER_AGENT`).

`NamesTest` pins these. The package `com.pvmlogs`, the class names and the file names inside the folder (`pvm-debug-*.log`, `pvm-<label>-<stamp>.csv`, `history.json`) aren't user-visible and stay as they are.

`ForbiddenApiTest` fails the build on anything the Plugin Hub forbids (the wiki's list, the packager's `disallowed-apis.txt`, plus the `Filepath`-only rule above). `HubSubmissionTest` checks what the hub's packager checks (`runelite/plugin-hub-tooling`): the properties' keys, the plugin class and internal name, the icon, `LICENSE`, Java 11 classes. `FreshInstallTest` pins every setting's default for a new install.

## Layout
- `src/main/java/com/pvmlogs/`: one package. Most logic is in two ~3k-line files:
  - `PvmPerformancePlugin`: event handlers, fight lifecycle, CSV, history;
  - `CombatCalc`: the expected-damage engine.
- Thrall attribution: `ThrallTracker`, `Thralls`, `XpLedger`, `XpDecoder`. Monster stats: `MonsterStatsProvider`, from the DPS calc's `monsters.json`.
- `src/test/java/com/pvmlogs/`: JUnit 4. `PvmPerformancePluginTest` is the dev-client launcher (`main`), not a test.
- `scripts/dps-calc-watch/`: the weekly DPS-calc watcher (`.github/workflows/dps-calc-watch.yml`).
- Code style: tabs, RuneLite's brace style. Match the code around you.

## Debug logs from Eric's PC
They're in `C:\Users\<user>\.runelite\plugin-data\luck-vs-skill\`:
- `pvm-debug-recent-<stamp>.log`: the side panel's **Save debug log** button (the last few thousand lines, with a setup header);
- `pvm-debug-<stamp>.log`: the stream while the **Debug logging** setting is on;
- `history.json`, CSV exports (`pvm-<label>-<stamp>.csv`), and the `monsters.json` cache.

**Check the build first (T-096).** A log's first line (streamed) or `===` header (saved) names the build that wrote it: `build <commit> (<branch>, clean or dirty: N files changed, committed …, built …)`, or `release <version>` from the Plugin Hub. Before reading a log against a fix, check that commit has the fix.

Older logs:
- written before T-060 (2026-10-28) stay in `...\.runelite\plugin-data\pvm-performance-tracker\`. Eric copies that folder's files into the new one once, by hand (the PowerShell command is in T-060's PR); nothing in the plugin moves them;
- written between T-004 and T-009 were in `...\.runelite\pvm-performance-tracker\`. RuneLite moved them into `plugin-data\pvm-performance-tracker\` the first time a T-009 build started;
- written before T-004 (2026-10-26), including the first Vardorvis logs, are in `...\.runelite\pvm-logs\`, which isn't moved.

To read them, the chat has to be linked to Eric's computer through the Claude desktop app. Request access to that one folder (`~/.runelite/plugin-data/luck-vs-skill`, or `plugin-data/pvm-performance-tracker` for a log from before T-060); Eric approves the prompt. Read the files where they are, and ask before copying a log into the repo as a test fixture.

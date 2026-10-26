# Handoff: thrall damage and other damage with no attack behind it

Read this before touching thrall attribution, burn, poison, recoil or anything else that puts a hitsplat on the target without being one of the player's attacks.

Arodab's commit `1fec762` says its findings were "kept in HANDOFF.md". That file was never committed and isn't anywhere in the history. `CombatCalc.burningClawsAverage` still points to its "Thrall damage" section. This file rebuilds what is known from the git history and from the first real-fight logs. Every claim names its source: a commit, a log, or a project doc. Unverified claims are marked as such.

## Thrall damage

### The problem
A thrall's hitsplats use the summoner's own hitsplat types, so `Hitsplat.isMine()` is true for them. The plugin counts `isMine()` splats as the player's, so before `b16abfe` every thrall splat was booked as a player hit or attack ([Arodab's issue #1](https://github.com/Arodab/pvm-performance-tracker/issues/1)).
- With a greater ghost out, every splat on the target was type 16 (`DAMAGE_ME`): the player's 13s and 10s alongside the thrall's 1s and 2s (`fbadb56`, from Arodab's log).

### Arodab's attempt, 2026-08-24/25, removed in `1fec762`
| Commit | Tried | What the log showed |
|---|---|---|
| `76bfef7` | Separate thrall damage line. Thralls always hit, 0 to the tier's max (wiki). Thrall splats assumed to draw in "the cyan family" | Wrong: same type as the player's (`fbadb56`) |
| `123b281` | Ownership from the resurrect cast's spotanim on the local player, plus a matching thrall appearing within 2 ticks; proximity as a fallback | No NPC field, varbit or `getInteracting` names the owner. Proximity fails at a boss, where everyone stands together |
| `7ac2f3b`, `9aa8bd6` | `Client.getFollower`, plus varbits `ARCEUUS_RESURRECTION_ACTIVE` and its cooldown | `getFollower` is the pet slot and returned null on every tick with a thrall out. The varbit goes 0→1 on a summon but carries no tier (`fbadb56`) |
| `fbadb56` | The thrall's projectile: `getSourceActor` is the thrall NPC, `getEndCycle` the landing tick, matched within a tick | Worked for ghosts and skeletons. A zombie thrall fights in melee and throws no projectile |
| `549b2bc` | Tier from the projectile's source NPC id, with every reskin id (imp and Deadman sets) in the table. Summon, follower and proximity routes deleted | Animations rejected: a reskin changes the id, and every player's thrall plays the same one |
| `b8ecf02` | Measure the 4-tick beat from the thrall's spawn, to cover melee thralls | Not measured before the removal |
| `1fec762` | Removed all of it: −425 lines across 9 files | — |

**Five dead ends, measured rather than reasoned (`1fec762`):** hitsplat colour, the follower slot, the resurrection varbit, proximity, and animations. Each failed **as the sole way to decide**. The current design (below) uses the varbit's timing, spawn distance and the attack animation as weighted evidence, never as the decider.

**Also from `1fec762`, unverified:** both Plugin Hub thrall plugins (`thrall-damage-counter`, `thrall-damage-tracker`) "get wrong" thrall damage and neither is usable. The reasons were in the missing file.

**Arodab's proposed design (`1fec762`):** thrall damage = my hitsplats minus my own attacks, using the experience drop as a yes/no signal rather than a rate. The current design grew from this.

**Recovering his code:** `git show 1fec762^:<path>` for `src/main/java/com/pvmperformance/{Thrall,Fight,Encounter,Raid,SessionTotals,PvmPerformanceOverlay,PvmPerformancePlugin}.java` and `src/test/java/com/pvmperformance/{ThrallTest,ThrallReskinTest}.java`. The package was still `com.pvmperformance` then.

### Experience-drop timing (Arodab, also 2026-08-24)
- `8cfe357`: **the combat XP drop arrives on the attack tick**, measured from casts at distance 2 and 6. The client sees the hitsplat **one tick after** the wiki's hit-delay formula, because the formula is when the server applies the hit.
- `95bff31` reversed that for hitpoints XP ("arrives with the damage"). `b16abfe` found the reversal was wrong: it read a total frozen at 200m XP. The drop is on the attack tick.
- `95bff31`, worth keeping: a `StatChanged` trace printed nothing all session. "When an instrument produces no data, suspect the instrument."

### Current design on `master`
Built in `b16abfe` (traced by `fdc7972`), then revised by [PR #3](https://github.com/Kuderic/pvm-logs/pull/3) (T-003, merged as `d7dc3ec`):
| Commit | What |
|---|---|
| `0a0a093` | XP calibration from what landed; per-attack checks |
| `9f191e7` | The thrall's projectile decides; the player side respects weapon cooldown; same-tick re-judging |
| `3ba670d` | Replay tests over the 8-kill log |
| `ad03b24` | Fixes from Eric's second session (the 15:26 and 16:08 logs) |
| `a64b3b9` | Replay tests over those two logs |

**Evidence:** Eric's three Vardorvis sessions on 2026-10-26, kept as replay fixtures in `src/test/resources/com/pvmlogs/replay/`:
- `vardorvis-8-kills-20261026.log`
- `vardorvis-4-kills-ghost-20261026-1526.log`
- `vardorvis-zombie-then-ghost-20261026-1608.log`

They're replayed by `VardorvisReplayTest` and `MoreVardorvisReplayTest` (through `ThrallReplay`). Numbers below without a commit come from those logs, as measured in `claude/status/thrall-attribution.md`.

**Files:** `ThrallTracker`, `Thralls` (thrall NPC ids with each tier's max hit, including reskins, plus projectile ids), `XpLedger`, `XpDecoder`. `Fight.revokeDamageDealt` and `SessionTotals.revokeAttempt` support re-judging.

While our thrall is out, each small `isMine()` splat is weighed and taken as the thrall's when that is likelier than not. The evidence is folded into odds and logged with every judgement:
- **The thrall's projectile, for ghosts and skeletons** (`9f191e7`):
  - A projectile was first seen on its splat's tick, and logged before it, in 223/223 cases (8-kill log) and 170/170 (4-kill log). No thrall splat came without one. 214/223 had attack animation 11101 a tick earlier.
  - So a projectile opens a window for one 0-to-max splat on its first-seen tick (+1 slack), and a ghost or skeleton splat with no projectile is near impossible.
  - The predicted landing tick (`getEndCycle`) is not used: it was a tick late on 3 of 9 projectiles in the first log (ticks 215, 219, 223).
- **Which thrall is ours:**
  - The thrall reads "interacting nothing", and **its projectiles name no source**. This contradicts `fbadb56` and `549b2bc`, which took `getSourceActor` to be the thrall NPC. A projectile is ours when it names our thrall, or names none and left from beside ours. The plugin names the thrall a sourceless projectile left from beside.
  - Ours is the thrall that spawns beside the player as the resurrection varbit comes up, or as they cast. That window is 5 ticks: in the 16:08 log the varbit came up 4 ticks after the ghost spawned (`ad03b24`).
  - A projectile from a thrall not yet claimed is held for a tick and replayed once it is claimed. In the 16:08 log, tick 194's splat had gone to the player. This also covers a recast's new thrall firing before the old one is gone.
- **Zombies (melee, no projectile):**
  - The attack animation opens a window without needing a target.
  - The 4-tick cycle locks a phase from splats already judged, and a thrall can't land two inside one cycle.
  - The cycle forgets faster: it re-locked within two splats of the zombie's one-tick re-path on ticks 94–99 (`ad03b24`).
- **Experience.** A thrall pays none. Each tick's combat and hitpoints drops decode to the damage the player's attack dealt.
  - Damage nothing paid for isn't the player's; damage that completes an attack's total is.
  - Checks run per attack once its window closes, only on attacks whose every splat was certainly the player's (`0a0a093`). An attack that decodes to nothing fails. So does certain damage with no drop at all, which covers no-XP areas.
  - Damage beyond what an attack decodes to stays with that attack, else goes to the latest attack whose drops couldn't be read, never to one that decoded to nothing (`ad03b24`). A resurrect cast pays magic only, and it had absorbed a claws spec's last splat, which calibrated the 4-kill log to ×1.575.
  - The ledger is ignored while its checks fail.
- **The PvM XP bonus**, from the wiki formula over the DPS calc's stats (matches Graardor +32.5% and Corp +55%). The Olm is set by hand. Calibration pools clean attacks and commits once rounding can't move it half of the game's 1/40 step; a change re-decodes open attacks and re-runs checks (`0a0a093`). Every log calibrates to ×1.55, and kill 1 is trusted from tick 197, before the thrall's first splat at 199.
- **The player side**, in `ThrallTracker.classifyPlayer`, which is pure and shared with the replays:
  - **inside the weapon's cooldown:** impossible;
  - **on cadence:** likely;
  - **later than the weapon allowed:** less likely (the zombie's zeros at 82 and 107 in the 16:08 log);
  - **a special burst still landing:** likely only within 3 ticks of a special. Burning claws puts its third splat a tick late (ticks 2300, 2392, 2730); after an ordinary blow the next tick is in cooldown (171).
- **The amount,** against a thrall's even 0-to-max roll and the player's expected accuracy and max hit.
- **Same-tick re-judging** (`9f191e7`, melee path only). A thrall projectile always brings its splat, but the splat can arrive before anything says whose it is (tick 351: a 0 ahead of the player's 21). So at the top of each tick, before the blow is booked, a projectile whose splat went unclaimed takes back the likeliest player-judged small splat on its target. That reverses fight and session damage and hits, and withdraws the blow if nothing else of the player's landed.
- **Trace:** thrall trace lines are always kept (`ad03b24`). When they were gated on file logging, the panel's saved 16:08 log had none of the zombie's animations.

A thrall splat opens no fight, books nothing, counts no hit and drains nothing. It still lowers the target's remaining HP, and is kept as `thrallDamage` on the fight, room and raid, with its own CSV column.

**Replay results.** Each claim below holds both as logged (×1.70, 98% expected accuracy) and corrected (×1.55, 55%):
- 8-kill log, ticks 197–203: player, thrall, player, thrall.
- Every projectile owns exactly one splat, with no thrall splat lacking one: in the 8-kill and 4-kill logs, and for the 16:08 ghost from tick 198.
- 16:08 zombie stretch, by the cycle alone: all 25 zombie splats and all 7 player misses right.
- No blow inside the weapon's cooldown, in any of the three logs.
- Trusted within 5 clean hits in every kill.

### Still open
- **`XP CONTRADICTS BOOKING`:** an XP drop proving an attack inside the last booking's cooldown is logged, not undone. Same-tick re-judging can still take a splat back before its blow is booked, but bookings from earlier ticks stand. Re-scoring them is the job of workstream 03's Analyzer. Open question: should it become an encounter-log event?
- **Zombie thralls:** the cycle alone gets the 16:08 zombie right, but no log has yet shown the zombie's animation→splat lag. One zombie trip on the current build settles it; the trace now keeps animations with file logging off.

## Lessons from the first real-fight log (Vardorvis, 2026-10-26, master `66da36d`)
Source: `claude/status/vardorvis-log-review.md`. The log is on Eric's PC in `~/.runelite/pvm-logs/`, written before the data folder went back to `pvm-performance-tracker`.
1. **Defence read as 0.** The DPS calc's `monsters.json` stores Vardorvis with `def 0, str 0` as placeholders; the calc computes them from current HP (`src/lib/scaling/Vardorvis.ts`). Expected accuracy showed ~98% against a real ~62% (26/42). → T-001 boss rules; T-002's watcher flags new placeholder-stat bosses.
2. **Wrong version.** Id 12223 is both "Awakened" (1400 HP) and "Post-quest" (700 HP), and the plugin took the first. The XP multiplier gave it away: ×1.70 is the formula on Awakened stats, while every clean hit paid ×1.55, the formula on post-quest stats.
3. **XP evidence never switched on:** the calibration loop, fixed in `0a0a093` (above).
4. **Misattribution before the thrall's cycle locked** (ticks 197–203): the player's misses and the thrall's zeros were swapped, and 3 thrall damage went to the player. Fixed by the projectile rule in `9f191e7` (above).
5. **Burn was invisible.** Burning claws applied burn, but the log has zero `BURN` (74) splats, because only `isMine()` splats were handled or logged. Types seen: 12, 16, 43.
6. **Totals don't reconcile:** 678 player + 52 thrall = 730 against a 700-HP boss, cause unknown (heals weren't logged).

**The general lesson:** log every hitsplat on the target unfiltered (type, amount, `isMine()`), plus heals. On a kill, Σdamage − Σheals should equal max HP; make that a standing check.

## Other damage with no attack behind it
Not attributed on `master` yet. T-001 ("Boss stats & damage sources") is wiring it.
- **Hitsplat types** (RuneLite API 1.13.4, decompiled; T-001 brief): `isMine()` is true only for 12, 16, 18, 20, 22, 24, 43–47, 53, 55. BURN 74, POISON 65, VENOM 5, BLEED 67, DISEASE 4 and HEAL 6 are **not** mine, so today's plugin drops them.
- **Burn from burning claws** is left out of expected damage on purpose (`CombatCalc.burningClawsAverage`): 10 over 40 ticks per inflicting splat, stacking to five, so 0 to 29 extra per spec.
- **Still to check against a real log:** recoil (ring of recoil/suffering), vengeance, item procs, multi-hit weapons and AoE.

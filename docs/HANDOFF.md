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

### Current design on `master` (`b16abfe`, traced by `fdc7972`)
Files: `ThrallTracker`, `Thralls` (thrall NPC ids with each tier's max hit, including reskins, plus projectile ids), `XpLedger`, `XpDecoder`.

While our thrall is out, each small `isMine()` splat is weighed and taken as the thrall's when that is likelier than not. The evidence is folded into odds and logged with every judgement:
- **Experience.** A thrall pays none. Each tick's combat and hitpoints drops decode to the damage the player's attack dealt. Damage nothing paid for isn't the player's; damage that completes an attack's total is. The ledger checks itself against splats that can only be the player's, and is ignored while those checks fail.
- **The PvM XP bonus**, from the wiki formula over the DPS calc's stats (matches Graardor +32.5% and Corp +55%), with the Olm set by hand and calibration from drops where the formula is wrong.
- **The thrall's own attacks:** its animation and projectile, seen at least a tick before its splat, open a window where one 0-to-max splat is due. Ours is the thrall that spawns beside the player as the resurrection varbit comes up, or as they cast.
- **Its 4-tick cycle:** splats already judged its own lock a phase, and it can't land two inside one cycle.
- **The amount,** against a thrall's even 0-to-max roll and the player's expected accuracy and max hit, and **what the player could have done** (a projectile due, a melee weapon off cooldown, their own burst landing).

A thrall splat opens no fight, books nothing, counts no hit and drains nothing. It still lowers the target's remaining HP, and is kept as `thrallDamage` on the fight, room and raid, with its own CSV column.

### Pending: PR #3 (T-003), not merged yet
[PR #3](https://github.com/Kuderic/pvm-logs/pull/3) is a draft. Fold this section into the one above when it merges. From the bugs chat's status doc (`claude/status/thrall-attribution.md`), measured on Eric's 8-kill Vardorvis log (2026-10-26):
- **`5b73cfc`, XP calibration never converged.** At a wrong multiplier the drops decode to nothing, undecodable awards were never spent, and the self-check skipped them, so calibration only "confirmed" the wrong ×1.70. Now: per-attack checks once the attack's window closes, using attacks whose every splat was certainly the player's, and pooled calibration. Result: ×1.70 → ×1.55 after 2 claw specs.
- **`47344b5`, the projectile decides.**
  - 223/223 thrall projectiles were first seen on their splat's tick, and no thrall splat came without one. 214/223 had attack animation 11101 one tick earlier.
  - The thrall reads "interacting nothing", and **its projectiles name no source**. This contradicts `fbadb56`'s `getSourceActor` = thrall. It is now matched by "names our thrall, or names none and leaves within 2 tiles of it".
  - The predicted landing tick (`getEndCycle`) was a tick late on 4 of 9 projectiles in the first log. The splat always came on the first-seen tick, so the prediction is no longer used.
  - Burning claws' third splat lands a tick late.
  - The player is never booked twice inside a weapon's cooldown.
  - An unclaimed projectile re-judges a same-tick small splat.
- **`85b3045`:** replay tests over the 8-kill log (fixture `src/test/resources/com/pvmlogs/replay/vardorvis-8-kills-20261026.log`).
- **Still unverified:** zombie (melee) thralls. The animation-only path needs a log with one.

## Lessons from the first real-fight log (Vardorvis, 2026-10-26, master `66da36d`)
Source: `claude/status/vardorvis-log-review.md`. The log is on Eric's PC in `~/.runelite/pvm-logs/`, written before the data folder went back to `pvm-performance-tracker`.
1. **Defence read as 0.** The DPS calc's `monsters.json` stores Vardorvis with `def 0, str 0` as placeholders; the calc computes them from current HP (`src/lib/scaling/Vardorvis.ts`). Expected accuracy showed ~98% against a real ~62% (26/42). → T-001 boss rules; T-002's watcher flags new placeholder-stat bosses.
2. **Wrong version.** Id 12223 is both "Awakened" (1400 HP) and "Post-quest" (700 HP), and the plugin took the first. The XP multiplier gave it away: ×1.70 is the formula on Awakened stats, while every clean hit paid ×1.55, the formula on post-quest stats.
3. **XP evidence never switched on:** the calibration loop above.
4. **Misattribution before the thrall's cycle locked** (ticks 197–203): the player's misses and the thrall's zeros were swapped, and 3 thrall damage went to the player. Fixed by the projectile rule above.
5. **Burn was invisible.** Burning claws applied burn, but the log has zero `BURN` (74) splats, because only `isMine()` splats were handled or logged. Types seen: 12, 16, 43.
6. **Totals don't reconcile:** 678 player + 52 thrall = 730 against a 700-HP boss, cause unknown (heals weren't logged).

**The general lesson:** log every hitsplat on the target unfiltered (type, amount, `isMine()`), plus heals. On a kill, Σdamage − Σheals should equal max HP; make that a standing check.

## Other damage with no attack behind it
Not attributed on `master` yet. T-001 ("Boss stats & damage sources") is wiring it.
- **Hitsplat types** (RuneLite API 1.13.4, decompiled; T-001 brief): `isMine()` is true only for 12, 16, 18, 20, 22, 24, 43–47, 53, 55. BURN 74, POISON 65, VENOM 5, BLEED 67, DISEASE 4 and HEAL 6 are **not** mine, so today's plugin drops them.
- **Burn from burning claws** is left out of expected damage on purpose (`CombatCalc.burningClawsAverage`): 10 over 40 ticks per inflicting splat, stacking to five, so 0 to 29 extra per spec.
- **Still to check against a real log:** recoil (ring of recoil/suffering), vengeance, item procs, multi-hit weapons and AoE.

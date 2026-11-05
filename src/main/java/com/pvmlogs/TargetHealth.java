package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;

/**
 * What each target has left, from the best source there is.
 *
 * <ol>
 * <li><b>The boss health bar the game draws.</b> Varbits {@code HPBAR_HUD_HP} and
 * {@code HPBAR_HUD_BASEHP} carry the exact current and full hitpoints of the NPC
 * the bar is showing, and varp {@code HPBAR_HUD_NPC} names it by its composition
 * id - which is how RuneLite's own opponent info reads the same three. It is the
 * only source that sees heals and other players' damage as the game counts them,
 * and the only one that knows a boss's full hitpoints without a table, which is
 * how the right version of a boss is told apart (Vardorvis: 700, Awakened 1400).
 * Trusted only while the bar names this target, and only while fresh.</li>
 * <li><b>Counted.</b> Full hitpoints from the tables, less every damage splat on it
 * (the player's, the thrall's, burn, poison, other players'), plus every heal
 * splat. Exact solo while every splat is seen; in a group it can only drift towards
 * "more left", which is the safe side for an overkill cap.</li>
 * </ol>
 *
 * <p>Keyed by npc index. A boss that transforms keeps its index and changes its id,
 * and keeps the damage it has taken, so an id change does not reset anything; every
 * id the index has worn is remembered so a lookup by either finds it. The server
 * reuses an index only after the npc has despawned, which the plugin reports with
 * {@link #forget}.
 *
 * <p>The exception is a boss whose rule says it heals back what it took in a phase
 * ({@code hpRestoredOnLeaving}, T-020): leaving that phase restarts its count at
 * full. The Abyssal Sire is restored to full by the end of its first phase, so the
 * damage dealt while it slept read as 100 off a 425 that was never down.
 *
 * <p>Pure bookkeeping with no client access, so it can be tested on its own; the
 * plugin feeds it splats and bar readings, all on the client thread.
 */
@Singleton
class TargetHealth
{
	/** A bar reading older than this many ticks is not trusted over the count. */
	static final int HUD_FRESH_TICKS = 2;
	/**
	 * T-161: an npc below full regains a hitpoint every this many ticks. Measured, not sourced (the wiki gives no rate
	 * for monsters): Eric's Cerberus kills of 2026-11-02 and 11-03 (builds {@code 1e73c42} and {@code 45a1015}, no boss
	 * bar) each took 3 to 7 more than her 600, every splat paid for in experience, and on all 175 whose count could be
	 * followed the excess is the ticks from her first damage to the killing blow over 20, rounded down or up (21 misses
	 * 9 of them, 22 misses 28). Venenatis's 9 to 19 over her 850 on 11-03 run at the same rate.
	 */
	static final int REGEN_TICKS = 20;
	/**
	 * T-161: the first of them after the npc is first found below full. The game's timer isn't tied to the damage (no one
	 * offset fits every kill), so where in the 20 the first falls is unknown: halfway, which leaves the count within one
	 * of what the game gave back.
	 */
	static final int REGEN_FIRST_TICKS = 10;

	static final class Entry
	{
		int npcId;
		final Set<Integer> ids = new HashSet<>();
		int tableMaxHp = -1;
		// T-146: the table figure was scaled for the raid (RaidScaling.hitpoints).
		boolean raidScaled;
		// T-146: the table figure may be short of the real one (RaidScaling.fullMayBeShort): no overhead-bar bound on it.
		boolean fullMayBeShort;
		int damage;
		// T-146: of the damage, what only its overhead bar showed (others' before the fight opened).
		int unseen;
		int healed;
		int hudHp = -1;
		int hudMax = -1;
		int hudTick = Integer.MIN_VALUE;
		int lastTouchedTick = Integer.MIN_VALUE;
		// T-153: the count of each pool the bar has shown, by its full hitpoints, while another is shown ({@link #hud}).
		Map<Integer, int[]> pools;
		// T-153: the id the npc wore at the last bar reading, and the count's damage and heals then ({@link #hud}).
		int hudId;
		int damageAtHud;
		int healedAtHud;
		// T-161: of the heals, what the count gave back for regeneration ({@link #regenerate}), and the tick it gives
		// the next.
		int regenerated;
		int nextRegenTick = Integer.MIN_VALUE;
		// T-164: the tick it was first found below full, and whether its boss bar has shown it regaining a hitpoint.
		int belowFullSince = Integer.MIN_VALUE;
		boolean regenSeen;
		// T-164: a bar read after a long gap, to be settled at the tick's end against the reading before it.
		boolean settleDue;
		int gapHp;
		int gapDamage;
		int gapHealed;
		int gapTick;
	}

	private final Map<Integer, Entry> byIndex = new HashMap<>();
	// The boss rules in force, for the bosses that heal a phase back and the ones whose count is known to run low. Set by
	// MonsterStatsProvider when it publishes the data those rules were read with.
	private volatile BossRules rules = BossRules.empty();
	// What changed in the bookkeeping that a debug log should say, until the plugin takes it.
	private static final int MAX_NOTES = 32;
	private final List<String> notes = new ArrayList<>();

	void setRules(BossRules rules)
	{
		this.rules = rules == null ? BossRules.empty() : rules;
	}

	private Entry entry(int index, int npcId, int tick)
	{
		final Entry e = byIndex.computeIfAbsent(index, i -> new Entry());
		if (e.npcId != 0 && e.npcId != npcId)
		{
			transformed(index, e, npcId, tick);
		}
		e.npcId = npcId;
		e.ids.add(npcId);
		e.lastTouchedTick = Math.max(e.lastTouchedTick, tick);
		return e;
	}

	/** The npc on this index now wears another id; a rule may say the damage so far was healed back. */
	private void transformed(int index, Entry e, int toId, int tick)
	{
		final BossRules.Boss boss = rules.forNpc(e.npcId);
		if (!BossRules.restoresOnLeaving(boss, e.npcId, toId))
		{
			return;
		}
		// Bounded: the plugin takes them only while a fight is open, and a splat outside one can still change an id.
		if (notes.size() < MAX_NOTES)
		{
			notes.add(String.format("HP RESTORED: npc index %d went %d -> %d on tick %d; %s heals back the %d taken "
				+ "(%d healed) before, so the count starts again at full", index, e.npcId, toId, tick, boss.name, e.damage,
				e.healed));
		}
		e.damage = 0;
		e.healed = 0;
		e.regenerated = 0;
		e.nextRegenTick = Integer.MIN_VALUE;
		e.belowFullSince = Integer.MIN_VALUE;
	}

	/**
	 * The id the npc on this index wears now, seen without a splat or a bar reading -
	 * the plugin says it each tick - so a rule that restores a phase's damage acts on
	 * the tick of the change, before the next attack is judged against the count.
	 * Ignored for an index nothing has been recorded against.
	 */
	void noteId(int index, int npcId, int tick)
	{
		if (npcId > 0 && byIndex.containsKey(index))
		{
			entry(index, npcId, tick);
		}
	}

	/** What changed since last asked, for the debug log. */
	List<String> drainNotes()
	{
		if (notes.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<String> out = new ArrayList<>(notes);
		notes.clear();
		return out;
	}

	/** Full hitpoints from the tables, used until the bar says otherwise. Ignored when not positive. */
	void setTableMaxHp(int index, int npcId, int maxHp, int tick)
	{
		setTableMaxHp(index, npcId, maxHp, tick, false);
	}

	/** As above, {@code raidScaled} when the table figure was scaled for the raid it stands in (T-146). */
	void setTableMaxHp(int index, int npcId, int maxHp, int tick, boolean raidScaled)
	{
		setTableMaxHp(index, npcId, maxHp, tick, raidScaled, false);
	}

	/** As above, {@code mayBeShort} when the table figure may be short of the real one (T-146: the Tombs). */
	void setTableMaxHp(int index, int npcId, int maxHp, int tick, boolean raidScaled, boolean mayBeShort)
	{
		if (maxHp > 0)
		{
			final Entry e = entry(index, npcId, tick);
			e.tableMaxHp = maxHp;
			e.raidScaled = raidScaled;
			e.fullMayBeShort = mayBeShort;
		}
	}

	/**
	 * T-146: the most hitpoints an npc can have left for its overhead health bar to read {@code ratio} of {@code scale}
	 * ({@code Actor.getHealthRatio}, {@code getHealthScale}) at {@code maxHp} full; -1 when it says nothing (no bar, or
	 * no full hitpoints known). The bar shows a share rounded up: any hitpoints at all show at least one step, and full
	 * shows every step - the reading RuneLite's opponent info takes of it, reversed for the upper end. The upper end,
	 * because a count that errs to more left is the safe side for a cap.
	 */
	static int mostLeftForRatio(int ratio, int scale, int maxHp)
	{
		if (ratio < 0 || scale <= 0 || maxHp <= 0)
		{
			return -1;
		}
		if (ratio == 0)
		{
			return 0;
		}
		if (scale == 1 || ratio >= scale)
		{
			return maxHp;
		}
		return Math.min(maxHp, (maxHp * ratio - 1) / (scale - 1));
	}

	/**
	 * T-161: the least hitpoints an npc can have left for its overhead bar to read {@code ratio} of {@code scale} at
	 * {@code maxHp} full; -1 when it says nothing. The other end of {@link #mostLeftForRatio}: RuneLite's opponent info
	 * takes the same lower bound (any hitpoints show at least one step; full shows every step).
	 */
	static int leastLeftForRatio(int ratio, int scale, int maxHp)
	{
		if (ratio < 0 || scale <= 0 || maxHp <= 0)
		{
			return -1;
		}
		if (ratio == 0)
		{
			return 0;
		}
		if (scale == 1 || ratio >= scale)
		{
			return scale == 1 ? 1 : maxHp;
		}
		return Math.max(1, (maxHp * (ratio - 1) + scale - 2) / (scale - 1));
	}

	/**
	 * T-161: a tick on the fight's target, alive: below full, it regains a hitpoint every {@link #REGEN_TICKS}, the first
	 * {@link #REGEN_FIRST_TICKS} after it was first found below full; back at full, the timer stops. Counted as healed,
	 * so the count, and the cap at what is left, follow it. Returns what it gave back on this tick.
	 *
	 * <p>T-164: a boss with a bar only once the bar has shown it regaining one ({@link #hud}), and from then on at the
	 * bar's own ticks. Not every npc regenerates: the Leviathan's six kills on 2026-11-03 took exactly its 900, its bar
	 * never rising, while Venenatis's rose by 1 every 20 ticks (Eric's log of 2026-11-03 15:32). An npc without a bar can't
	 * show it, so it is taken to regenerate, as Cerberus does (T-161).
	 */
	int regenerate(int index, int tick)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return 0;
		}
		if (e.settleDue)
		{
			final int before = e.regenerated;
			settleGap(index, e, tick);
			return e.regenerated - before;
		}
		final int max = fullHp(e);
		if (max <= 0 || max - e.damage + e.healed >= max)
		{
			e.nextRegenTick = Integer.MIN_VALUE;
			e.belowFullSince = Integer.MIN_VALUE;
			return 0;
		}
		if (e.belowFullSince == Integer.MIN_VALUE)
		{
			e.belowFullSince = tick;
		}
		if (e.hudMax > 0 && !e.regenSeen)
		{
			return 0;
		}
		if (e.nextRegenTick == Integer.MIN_VALUE)
		{
			e.nextRegenTick = tick + REGEN_FIRST_TICKS;
			return 0;
		}
		int gave = 0;
		while (tick >= e.nextRegenTick && max - e.damage + e.healed < max)
		{
			e.healed++;
			e.regenerated++;
			e.nextRegenTick += REGEN_TICKS;
			gave++;
		}
		return gave;
	}

	/** T-164: the most a reading with nothing counted since can rise and be taken for regeneration: two of its ticks. */
	static final int MAX_REGEN_STEP = 2;

	/**
	 * T-164: the bar, read with no splat counted since its last reading, shows more left than that one less what was
	 * given back since: the npc regained it. Credited as regenerated (with any of its 20-tick steps since it was first
	 * found below full that fell on a tick with a splat, so the bar never showed them on their own), and the timer set to
	 * the bar's tick. Outside a raid only (the plugin's {@code regenerates}), where a bar can fill for other reasons (the
	 * P2 Warden's poise, T-153).
	 *
	 * <p>Read after a long gap (a hold through the player's death, an idle pause, a logout), it is settled at the tick's
	 * end instead ({@link #settleGap}), once that tick's splats are counted: the bar shows a splat before the count has
	 * it (T-154), and the player's first hit on the return is on that tick.
	 */
	private void regainedByBar(Entry e, int npcId, int hp, int base, int tick)
	{
		if (e.hudMax != base || e.hudHp < 0 || e.hudId != npcId || e.hudTick == Integer.MIN_VALUE || e.settleDue)
		{
			return;
		}
		if (tick - e.hudTick > REGEN_TICKS)
		{
			e.settleDue = true;
			e.gapHp = e.hudHp;
			e.gapDamage = e.damageAtHud;
			e.gapHealed = e.healedAtHud;
			e.gapTick = e.hudTick;
			return;
		}
		if (e.damage != e.damageAtHud)
		{
			return;
		}
		final int gained = hp - (e.hudHp + (e.healed - e.healedAtHud));
		if (gained > 0 && gained <= MAX_REGEN_STEP)
		{
			credit(e, gained, e.hudTick, tick);
		}
	}

	/**
	 * T-164: a long gap between readings, settled: the steps the timer owes are given back, and what the bar shows beyond
	 * them is regained too (the game's ticks may not run while logged out: Venenatis rose 173 -> 195 across a logout of
	 * 181 ticks, [18978] to [19319] of Eric's 15:32 log), or, short of them, was taken by someone else while the player
	 * was away (hers fell 418 -> 405 across the player's death, [22359] to [22699], with 17 steps due).
	 */
	private void settleGap(int index, Entry e, int tick)
	{
		e.settleDue = false;
		final int max = fullHp(e);
		final int owedBefore = e.regenerated;
		if (e.regenSeen && e.nextRegenTick != Integer.MIN_VALUE)
		{
			while (tick >= e.nextRegenTick && max - e.damage + e.healed < max)
			{
				e.healed++;
				e.regenerated++;
				e.nextRegenTick += REGEN_TICKS;
			}
		}
		final int counted = (e.damage - e.gapDamage) - (e.healed - e.gapHealed);
		final int gained = e.hudHp - (e.gapHp - counted);
		if (notes.size() < MAX_NOTES)
		{
			notes.add(String.format("HP SETTLED: npc index %d's bar %d on tick %d against %d on tick %d, %d counted since: %d of "
					+ "its 20-tick steps given back, and %s", index, e.hudHp, tick, e.gapHp, e.gapTick, e.damage - e.gapDamage,
				e.regenerated - owedBefore, gained > 0 ? gained + " more regained" : gained < 0 ? -gained
					+ " taken by someone else while the player was away" : "nothing else"));
		}
		if (gained < 0)
		{
			e.damage -= gained;
			e.unseen -= gained;
		}
		else if (gained > 0)
		{
			// The rise across the gap is regained, but says nothing of the timer's phase: one known stands, and one not yet
			// seen is still waiting for the bar's first rise on its own, its earlier steps counted from here.
			e.healed += gained;
			e.regenerated += gained;
			if (!e.regenSeen)
			{
				e.belowFullSince = tick;
			}
		}
	}

	/**
	 * T-164: {@code gained} regained between a reading on {@code lastRead} and one on {@code tick}, with, the first time,
	 * its earlier steps 20 ticks apart back from this one, since it was first below full and up to that reading: each
	 * fell on a tick with a splat, or the bar would have shown it.
	 */
	private static void credit(Entry e, int gained, int lastRead, int tick)
	{
		int missed = 0;
		if (!e.regenSeen && e.belowFullSince != Integer.MIN_VALUE)
		{
			final int fromK = Math.max(1, (tick - lastRead + REGEN_TICKS - 1) / REGEN_TICKS);
			final int toK = (tick - e.belowFullSince) / REGEN_TICKS;
			missed = Math.max(0, toK - fromK + 1);
		}
		e.healed += gained + missed;
		e.regenerated += gained + missed;
		e.regenSeen = true;
		e.nextRegenTick = tick + REGEN_TICKS;
	}

	/** T-161: of {@link #healed}, what the count gave back for regeneration. */
	int regenerated(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? 0 : e.regenerated;
	}

	/**
	 * T-146: the npc's overhead bar read {@code ratio} of {@code scale}: where the count has more left than that can
	 * be, plus {@code extra} (a splat of the player's landing as it was read, which the reading may or may not show
	 * yet), the difference was taken where the count didn't see it - other players' damage from before the fight
	 * opened - and is counted now. Never raises the count; nothing once the boss bar has been read (T-154). Returns
	 * what it added.
	 */
	int boundByRatio(int index, int npcId, int ratio, int scale, int extra, int tick)
	{
		final Entry e = byIndex.get(index);
		// A full figure that may be short (the Tombs, without the boss bar) makes the bar's share of it too little. T-153:
		// with the boss bar too: what a Tombs boss's count holds isn't always what its overhead bar shows (Kephri's shield
		// splats take her first bar's 400 and aren't hitpoints), and the boss bar is read a tick later anyway.
		// T-154: nor on an npc whose boss bar has ever been read: it sees everything, others' damage included.
		if (e == null || e.hudMax > 0 || e.fullMayBeShort)
		{
			return 0;
		}
		final int most = mostLeftForRatio(ratio, scale, fullHp(e));
		final int counted = countedRemaining(index);
		if (most < 0 || counted < 0 || counted <= most + Math.max(0, extra))
		{
			return 0;
		}
		final int added = counted - most - Math.max(0, extra);
		entry(index, npcId, tick);
		e.damage += added;
		e.unseen += added;
		return added;
	}

	/** T-146: what only its overhead bar showed this npc had taken ({@link #boundByRatio}). */
	int unseenDamage(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? 0 : e.unseen;
	}

	void damage(int index, int npcId, int amount, int tick)
	{
		if (amount > 0)
		{
			entry(index, npcId, tick).damage += amount;
		}
	}

	void heal(int index, int npcId, int amount, int tick)
	{
		if (amount > 0)
		{
			entry(index, npcId, tick).healed += amount;
		}
	}

	/**
	 * A bar reading the game has said belongs to this npc. Refused when it cannot be a reading.
	 *
	 * <p>T-153: a new full on the bar is a pool of its own, and the count follows it: what was counted against the
	 * last one is set aside under its full, and the count of the new one is taken up where it was left (nothing, the
	 * first time). Kephri's first bar is 400 and her second 210 ([31325] of Eric's raid of 2026-11-02); Elidinis'
	 * Warden in phase two shows a 330 that her poise fills while the core is in, and her 6030 while it is out, three
	 * times over. Before, the count carried one pool's damage into the next: her kill reconciled 325 against 210.
	 */
	void hud(int index, int npcId, int hp, int base, int tick)
	{
		hud(index, npcId, hp, base, tick, false);
	}

	/** As above; {@code regenerates}: outside a raid, where a rise with nothing counted is the npc regaining (T-164). */
	void hud(int index, int npcId, int hp, int base, int tick, boolean regenerates)
	{
		if (base <= 0 || hp < 0 || hp > base)
		{
			return;
		}
		final Entry e = entry(index, npcId, tick);
		if (regenerates)
		{
			regainedByBar(e, npcId, hp, base, tick);
		}
		if (e.hudMax > 0 && e.hudMax != base)
		{
			switchPool(index, e, base, tick);
		}
		else if (e.hudMax == base && e.hudHp >= 0 && e.hudId != 0 && npcId != e.hudId)
		{
			restoredByBar(index, e, npcId, hp, tick);
		}
		e.hudHp = hp;
		e.hudMax = base;
		e.hudTick = tick;
		e.hudId = npcId;
		e.damageAtHud = e.damage;
		e.healedAtHud = e.healed;
	}

	/**
	 * T-153: the bar, read again as the npc changes form, shows more left than the last reading less what was counted
	 * since: the game gave the rest back, and the count takes it as healed. Akkha at 0 as 11793 comes back as his enraged
	 * 11794 with 214 of his 1070, a fifth ([32050]-[32051] of Eric's raid of 2026-11-02): his kill reconciled 1284
	 * taken against 1070. Only on a change of form, so a bar that fills as the player works it (the P2 Warden's poise)
	 * isn't taken for a heal, and only past {@link #barTolerance}.
	 */
	private void restoredByBar(int index, Entry e, int npcId, int hp, int tick)
	{
		final int counted = (e.damage - e.damageAtHud) - (e.healed - e.healedAtHud);
		final int restored = hp - (e.hudHp - counted);
		// Past the bar's tolerance: a splat counted a tick before the bar shows it isn't a heal.
		if (restored <= barTolerance(e.hudMax))
		{
			return;
		}
		e.healed += restored;
		if (notes.size() < MAX_NOTES)
		{
			notes.add(String.format("HP RESTORED: npc index %d went %d -> %d on tick %d with its bar at %d, %d more than the "
				+ "count left it; counted as healed", index, e.hudId, npcId, tick, hp, restored));
		}
	}

	private void switchPool(int index, Entry e, int toBase, int tick)
	{
		if (e.pools == null)
		{
			e.pools = new HashMap<>();
		}
		e.pools.put(e.hudMax, new int[]{e.damage, e.healed, e.unseen});
		final int[] back = e.pools.remove(toBase);
		e.damage = back == null ? 0 : back[0];
		e.healed = back == null ? 0 : back[1];
		e.unseen = back == null ? 0 : back[2];
		if (notes.size() < MAX_NOTES)
		{
			notes.add(String.format("HP POOL: npc index %d's bar went from %d full to %d on tick %d; the count follows it, "
				+ "%s", index, e.hudMax, toBase, tick, back == null ? "starting the new one at nothing taken"
				: "taking up the " + back[0] + " counted on it before"));
		}
	}

	private static boolean hudFresh(Entry e, int tick)
	{
		return e.hudMax > 0 && tick - e.hudTick <= HUD_FRESH_TICKS;
	}

	private static int fullHp(Entry e)
	{
		return e.hudMax > 0 ? e.hudMax : e.tableMaxHp;
	}

	/**
	 * T-153: full hitpoints that no attack can deal more than: the bar's, or the table's where that figure can't be
	 * short (see {@link RaidScaling#fullMayBeShort}); -1 otherwise.
	 */
	int trustedMaxHp(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null || e.hudMax <= 0 && e.fullMayBeShort ? -1 : fullHp(e);
	}

	/** Full hitpoints: the bar's if it has ever been read for this npc, otherwise the table's, otherwise -1. */
	int maxHp(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? -1 : fullHp(e);
	}

	/** What is left, or -1 when nothing knows. Never below zero. */
	int remaining(int index, int tick)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return -1;
		}
		if (hudFresh(e, tick))
		{
			return e.hudHp;
		}
		final int max = fullHp(e);
		if (max <= 0)
		{
			return -1;
		}
		return Math.max(0, Math.min(max, max - e.damage + e.healed));
	}

	/** What is left by the count alone, never the bar's reading; -1 when no full hitpoints are known. */
	int countedRemaining(int index)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return -1;
		}
		final int max = fullHp(e);
		return max <= 0 ? -1 : Math.max(0, Math.min(max, max - e.damage + e.healed));
	}

	/**
	 * Whether a bar reading can be taken as this target's. The bar names its npc
	 * by id only, so with several npcs of that id in the scene (adds) it may be
	 * showing another one. It is trusted when the target is the only one with the
	 * id, or when it agrees with the count to within {@link #barTolerance}.
	 */
	static boolean barIsTarget(int sameIdInScene, int barHp, int barBase, int counted)
	{
		if (sameIdInScene <= 1)
		{
			return true;
		}
		return counted >= 0 && Math.abs(barHp - counted) <= barTolerance(barBase);
	}

	/** How far the bar and the count may differ and still be the same npc: 2% of full hitpoints, at least 2. */
	static int barTolerance(int barBase)
	{
		return Math.max(2, barBase / 50);
	}

	/**
	 * What is left, for capping an attack's expected damage: {@link #remaining}, and
	 * where that was counted for a boss whose count is known to run low, its rule's
	 * margin on top (never past full). The Abyssal Sire took up to 27 more than 425
	 * after its first phase in Eric's 10 kills of 2026-10-27, so a cap at the count
	 * cut its last attacks to a few points when it still had twenty or more.
	 */
	int capRemaining(int index, int tick)
	{
		final int left = remaining(index, tick);
		final Entry e = byIndex.get(index);
		if (left < 0 || e == null || hudFresh(e, tick))
		{
			return left;
		}
		final BossRules.Boss boss = rules.forNpc(e.npcId);
		final int margin = boss == null ? 0 : Math.max(0, boss.countedHpMargin);
		return margin == 0 ? left : Math.min(fullHp(e), left + margin);
	}

	/** Which source {@link #remaining} answered from: "bar", "counted" or "unknown". */
	String source(int index, int tick)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return "unknown";
		}
		if (hudFresh(e, tick))
		{
			return "bar";
		}
		return fullHp(e) > 0 ? "counted" : "unknown";
	}

	/** Which source {@link #maxHp} answered from: "bar", "table", "raid-scaled" (T-146) or "unknown". */
	String maxSource(int index)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return "unknown";
		}
		return e.hudMax > 0 ? "bar" : e.tableMaxHp > 0 ? (e.raidScaled ? "raid-scaled" : "table") : "unknown";
	}

	/** The index of the most recently touched target that has worn this id, or -1. Bosses are alone in their instance. */
	private int latestIndexFor(int npcId)
	{
		int best = -1;
		int bestTick = Integer.MIN_VALUE;
		for (Map.Entry<Integer, Entry> m : byIndex.entrySet())
		{
			final Entry e = m.getValue();
			if (e.ids.contains(npcId) && (best < 0 || e.lastTouchedTick > bestTick))
			{
				best = m.getKey();
				bestTick = e.lastTouchedTick;
			}
		}
		return best;
	}

	/** Full hitpoints the bar has shown for an npc wearing this id, or -1. Picks between a boss's versions. */
	int observedMaxForId(int npcId)
	{
		final int index = latestIndexFor(npcId);
		return index < 0 ? -1 : byIndex.get(index).hudMax;
	}

	/** What the latest target wearing this id has left, or -1. Drives stats that move with hitpoints. */
	int remainingForId(int npcId, int tick)
	{
		final int index = latestIndexFor(npcId);
		return index < 0 ? -1 : remaining(index, tick);
	}

	int damageCounted(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? 0 : e.damage;
	}

	int healed(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? 0 : e.healed;
	}

	/** The full hitpoints the bar last showed for this npc, or -1 if it has never named it. */
	int lastHudMax(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? -1 : e.hudMax;
	}

	/** The last bar reading for this npc, or -1 if the bar has never named it. */
	int lastHudHp(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? -1 : e.hudHp;
	}

	/** The npc on this index has left the scene; the server may give its index to the next one. */
	/** The same target back under another index (T-088): what was counted on it goes with it. */
	void move(int fromIndex, int toIndex)
	{
		final Entry e = byIndex.remove(fromIndex);
		if (e != null)
		{
			byIndex.put(toIndex, e);
		}
	}

	void forget(int index)
	{
		byIndex.remove(index);
		// T-155: and anything parked for it: gone from the scene for good (dead), or found fresh on return.
		parked.remove(index);
	}

	/** T-155: a target's count set aside while its fight is over and it lives on ({@link #park}). */
	static final class Parked
	{
		final Entry entry;
		final int world;
		final int raid;
		// The plugin's own tally of damage on it, carried with the count.
		final int extra;

		Parked(Entry entry, int world, int raid, int extra)
		{
			this.entry = entry;
			this.world = world;
			this.raid = raid;
			this.extra = extra;
		}

		int damage()
		{
			return entry.damage;
		}
	}

	private static final int MAX_PARKED = 16;
	private final Map<Integer, Parked> parked = new HashMap<>();

	/**
	 * T-155: sets this target's count aside, where a fight on it ends with it alive in a raid ({@code raid}, the plugin's
	 * raid number), so the same npc fought again - the same index and id, on the same world, in the same raid - carries
	 * on from it ({@link #unpark}). In Eric's Chambers of Xeric of 2026-11-02, Vespula's portal left his scene with each
	 * of his three deaths there and was fought again on the same index: its kill reconciled 3 against 242, the four
	 * pieces 248. Olm's left claw likewise, across a death and across a turn to Olm's head. Only the one; anything parked
	 * from another raid goes.
	 */
	void park(int index, int world, int raid, int extra)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return;
		}
		parked.values().removeIf(p -> p.raid != raid);
		if (parked.size() >= MAX_PARKED)
		{
			parked.clear();
		}
		parked.put(index, new Parked(e, world, raid, extra));
	}

	/**
	 * T-155: the count {@link #park}ed for this npc, put back on its index, or null: none, or another npc (another id, a
	 * world hop, another raid). Taken either way, as the index now belongs to whatever is on it.
	 */
	Parked unpark(int index, int npcId, int world, int raid)
	{
		final Parked p = parked.remove(index);
		if (p == null || p.world != world || p.raid != raid || !p.entry.ids.contains(npcId))
		{
			return null;
		}
		// What was counted on it while no fight was open (a thrall's last splat), added to what was parked.
		final Entry since = byIndex.get(index);
		if (since != null && since != p.entry)
		{
			p.entry.damage += since.damage;
			p.entry.healed += since.healed;
			p.entry.unseen += since.unseen;
			p.entry.regenerated += since.regenerated;
		}
		byIndex.put(index, p.entry);
		return p;
	}

	void clear()
	{
		byIndex.clear();
	}
}

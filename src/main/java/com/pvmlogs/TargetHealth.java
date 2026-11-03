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
		if (base <= 0 || hp < 0 || hp > base)
		{
			return;
		}
		final Entry e = entry(index, npcId, tick);
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
	}

	void clear()
	{
		byIndex.clear();
	}
}

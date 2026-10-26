package com.pvmlogs;

import java.util.HashMap;
import java.util.HashSet;
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
		int damage;
		int healed;
		int hudHp = -1;
		int hudMax = -1;
		int hudTick = Integer.MIN_VALUE;
		int lastTouchedTick = Integer.MIN_VALUE;
	}

	private final Map<Integer, Entry> byIndex = new HashMap<>();

	private Entry entry(int index, int npcId, int tick)
	{
		final Entry e = byIndex.computeIfAbsent(index, i -> new Entry());
		e.npcId = npcId;
		e.ids.add(npcId);
		e.lastTouchedTick = Math.max(e.lastTouchedTick, tick);
		return e;
	}

	/** Full hitpoints from the tables, used until the bar says otherwise. Ignored when not positive. */
	void setTableMaxHp(int index, int npcId, int maxHp, int tick)
	{
		if (maxHp > 0)
		{
			entry(index, npcId, tick).tableMaxHp = maxHp;
		}
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

	/** A bar reading the game has said belongs to this npc. Refused when it cannot be a reading. */
	void hud(int index, int npcId, int hp, int base, int tick)
	{
		if (base <= 0 || hp < 0 || hp > base)
		{
			return;
		}
		final Entry e = entry(index, npcId, tick);
		e.hudHp = hp;
		e.hudMax = base;
		e.hudTick = tick;
	}

	private static boolean hudFresh(Entry e, int tick)
	{
		return e.hudMax > 0 && tick - e.hudTick <= HUD_FRESH_TICKS;
	}

	private static int fullHp(Entry e)
	{
		return e.hudMax > 0 ? e.hudMax : e.tableMaxHp;
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

	/** Which source {@link #maxHp} answered from: "bar", "table" or "unknown". */
	String maxSource(int index)
	{
		final Entry e = byIndex.get(index);
		if (e == null)
		{
			return "unknown";
		}
		return e.hudMax > 0 ? "bar" : e.tableMaxHp > 0 ? "table" : "unknown";
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

	/** The last bar reading for this npc, or -1 if the bar has never named it. */
	int lastHudHp(int index)
	{
		final Entry e = byIndex.get(index);
		return e == null ? -1 : e.hudHp;
	}

	/** The npc on this index has left the scene; the server may give its index to the next one. */
	void forget(int index)
	{
		byIndex.remove(index);
	}

	void clear()
	{
		byIndex.clear();
	}
}

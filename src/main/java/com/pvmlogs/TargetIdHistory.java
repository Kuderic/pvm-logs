package com.pvmlogs;

import java.util.HashMap;
import java.util.Map;

/**
 * The id the fight's target wore on each of the last few ticks.
 *
 * <p>A fight keeps the id it opened on, and a boss that changes id with its
 * phases is a different set of stats in each: the Abyssal Sire's last stage has half
 * the defences of the rest (T-020). An attack is judged by the stats of the id its
 * target wore on the tick it was thrown, and that tick can be a tick or two behind
 * the booking, by when the target may have changed again. So the id is kept per
 * tick, with the npc index it belonged to, and looked up on the attack's tick.
 *
 * <p>Plain bookkeeping, on the client thread, so it can be tested on its own.
 */
final class TargetIdHistory
{
	/** Longer than any booking lags its attack, which is a few ticks at most. */
	static final int KEEP_TICKS = 16;

	// tick -> {npc index, npc id}
	private final Map<Integer, int[]> byTick = new HashMap<>();

	/** The fight's target on this tick: its index and the id it wears. */
	void record(int tick, int index, int npcId)
	{
		if (npcId < 0)
		{
			return;
		}
		byTick.put(tick, new int[]{index, npcId});
		byTick.keySet().removeIf(t -> tick - t > KEEP_TICKS);
	}

	/**
	 * The id the npc on this index wore on this tick, or the fallback when nothing
	 * was recorded for it then: a tick too old, a fight opened later in the tick, or
	 * a different target.
	 */
	int idOn(int tick, int index, int fallback)
	{
		final int[] row = byTick.get(tick);
		return row != null && row[0] == index ? row[1] : fallback;
	}

	void clear()
	{
		byTick.clear();
	}
}

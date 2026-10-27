package com.pvmlogs;

import net.runelite.api.Skill;

/**
 * The boost an attack should have had on one stat, worked out from what the
 * player had rather than from a setting (T-015): the strongest boost among the
 * boosting items carried at the attack, and the highest boost seen on that stat
 * earlier in the fight - a pre-pot, or a dose drunk with none left.
 *
 * <p>"On hand" is the rule the gear check already uses. It keeps Execution about
 * how well the setup that was brought was played: not bringing a potion shows in
 * the kill time, not in execution. Never below zero, so the ideal is never below
 * the base level, and an attack below its base always costs.
 */
final class IdealBoost
{
	/** Where the ideal came from, for the debug log. */
	static final int NOTHING = -1;
	static final int SEEN_THIS_FIGHT = -2;

	/** One stat's ideal boost and its source: an item id, SEEN_THIS_FIGHT or NOTHING. */
	static final class Result
	{
		final int boost;
		final int source;

		Result(int boost, int source)
		{
			this.boost = boost;
			this.source = source;
		}
	}

	private IdealBoost()
	{
	}

	/**
	 * @param inventory  the item ids carried at the attack (any doses; -1 for empty slots)
	 * @param heartReady whether the hearts' shared cooldown is off, so a carried heart could be used
	 * @param peakSeen   the highest boost on this stat earlier in the fight, 0 if none
	 */
	static Result best(Skill skill, int base, int herblore, int[] inventory, boolean heartReady, int peakSeen)
	{
		int best = 0;
		int source = NOTHING;
		boolean bestDivine = false;
		if (inventory != null)
		{
			for (int id : inventory)
			{
				final BoostTable.Boost boost = id < 0 ? null : BoostTable.get(id);
				if (boost == null || boost.heart && !heartReady)
				{
					continue;
				}
				final int b = boost.boost(skill, base, herblore);
				// Between equal boosts the divine one holds it, so it is the one named.
				if (b > best || b == best && b > 0 && boost.divine && !bestDivine)
				{
					best = b;
					source = id;
					bestDivine = boost.divine;
				}
			}
		}
		if (peakSeen > best)
		{
			best = peakSeen;
			source = SEEN_THIS_FIGHT;
		}
		return new Result(Math.max(0, best), source);
	}
}

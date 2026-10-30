package com.pvmlogs;

import java.util.List;

/**
 * T-093: how many fights History keeps, by kind. A boss's kills and a slayer
 * trip's monster fights each have their own budget, so a trip can never push a
 * boss kill out: on Eric's PC on 2026-10-29, 335 Abyssal demon fights pushed
 * every older Vardorvis, Leviathan, Sire and Zulrah kill out of a single cap
 * of 500.
 *
 * <p>Pure: the plugin hands it the history list, newest first.
 */
final class HistoryBudget
{
	/**
	 * Boss fights kept: everything that isn't a monster (bosses, their groups'
	 * adds, raids). 2,000, measured with synthetic fights on the CI's JDK:
	 * about 1.4 KB a fight serialised (Eric's real file runs 1.8 KB: his 500 are
	 * 0.9 MB, so 2,000 are about 3.6 MB); loading 2,000 took 32 ms once at
	 * start, saving 35 ms off the client thread at each fight's end, and the
	 * side panel's History rebuild ({@link SidePanelHistory#allRooms} and
	 * {@link SidePanelHistory#bosses}) under 5 ms, as at 500. Four times the
	 * old cap of every kind together. A proposal, for Eric to approve.
	 */
	static final int BOSS_FIGHTS = 2000;
	/**
	 * Monster fights kept: 500, the old cap. A slayer trip saves a fight per
	 * target switch (Eric's demon trip: 335), so this keeps about the last trip
	 * or two whole. A proposal, for Eric to approve.
	 */
	static final int MONSTER_FIGHTS = 500;

	private HistoryBudget()
	{
	}

	/**
	 * Whether a fight counts against the monster budget: flagged a monster run
	 * when it was fought (T-089), or, for a fight saved before that flag
	 * existed, a monster by T-089's own test on its name and id. Most of Eric's
	 * 335 demon fights predate the flag.
	 */
	static boolean isMonster(Fight fight, BossRules rules)
	{
		return fight.isMonsterRoom()
			|| MonsterRooms.isMonster(fight.getTargetName(), fight.getTargetId(), fight.getRaidName() != null, rules);
	}

	/**
	 * Adds a finished fight, newest first, then drops the oldest fights of its
	 * own kind beyond that kind's budget. The other kind is never touched.
	 *
	 * @return how many fights were dropped
	 */
	static int add(List<Fight> history, Fight fight, BossRules rules, int bossBudget, int monsterBudget)
	{
		history.add(0, fight);
		final boolean monster = isMonster(fight, rules);
		final int budget = monster ? monsterBudget : bossBudget;
		int kept = 0;
		for (Fight f : history)
		{
			kept += isMonster(f, rules) == monster ? 1 : 0;
		}
		int dropped = 0;
		for (int i = history.size() - 1; i >= 0 && kept > budget; i--)
		{
			if (isMonster(history.get(i), rules) == monster)
			{
				history.remove(i);
				kept--;
				dropped++;
			}
		}
		return dropped;
	}
}

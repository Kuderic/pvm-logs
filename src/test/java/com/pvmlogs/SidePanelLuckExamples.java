package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T-194: History's Kills list as in Eric's screenshot (2026-11-09 21:25), newest first: rolled 35, 28, 92, 27, 9, 96,
 * 92, 94, 59, 70, 99, 26, 27 and 74. Fourteen Araxxor kills, each shaped as T-190's are, its damage set so it rolls
 * that number.
 */
final class SidePanelLuckExamples
{
	private SidePanelLuckExamples()
	{
	}

	static final int[] ROLLS = {35, 28, 92, 27, 9, 96, 92, 94, 59, 70, 99, 26, 27, 74};

	/** The fourteen kills, newest first, rolling {@link #ROLLS} from the top. */
	static List<Fight> history()
	{
		final double[] seconds = {88, 92, 84, 95, 99, 83, 86, 85, 90, 89, 82, 94, 93, 87};
		final int[] lost = {2, 3, 1, 3, 4, 1, 1, 2, 2, 1, 0, 3, 3, 1};
		final List<Fight> oldestFirst = new ArrayList<>();
		long end = SidePanelExamples.at(18, 0);
		for (int i = ROLLS.length - 1; i >= 0; i--)
		{
			end += (long) (seconds[i] * 1000) + 45_000L;
			oldestFirst.add(rolling(end, seconds[i], lost[i], ROLLS[i]));
		}
		final List<Fight> newestFirst = new ArrayList<>(oldestFirst);
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/** A kill whose damage rolls {@code roll}: the damage over expected found by halving. */
	private static Fight rolling(long end, double seconds, int lost, int roll)
	{
		int lo = -600;
		int hi = 600;
		while (lo < hi)
		{
			final int mid = Math.floorDiv(lo + hi, 2);
			if (roll(kill(end, seconds, mid, lost)) < roll)
			{
				lo = mid + 1;
			}
			else
			{
				hi = mid;
			}
		}
		final Fight f = kill(end, seconds, lo, lost);
		if (roll(f) != roll)
		{
			throw new IllegalStateException("no damage rolls " + roll);
		}
		return f;
	}

	private static int roll(Fight f)
	{
		final Encounter room = LedgerHistory.rooms(Collections.singletonList(f), "Araxxor").get(0);
		return SidePanelModel.roll(new LedgerRows.Numbers(LedgerRows.of(room)).percentile);
	}

	private static Fight kill(long end, double seconds, int luck, int lost)
	{
		final Fight f = new Fight("Araxxor", NpcIds.GOBLIN, 1, 1020, end - (long) (seconds * 1000), null, 0);
		final int ticks = (int) Math.round(seconds / 0.6);
		final int attacks = Math.max(1, (ticks - lost) / 4);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, 0.99);
			f.recordExpected(42, 0.84, 24, 160);
			f.recordAttackResolved(true, true, 0.99, 1.0, 0.012, 0);
		}
		for (int i = 0; i < lost; i++)
		{
			f.recordTickLost(false);
		}
		for (int i = attacks + lost; i < ticks; i++)
		{
			f.recordTickSpent();
		}
		final int landed = Math.max(1, (int) Math.round(attacks * 0.84));
		final int total = attacks * 24 + luck;
		for (int i = 0; i < landed; i++)
		{
			f.recordDamageDealt(i < landed - 1 ? total / landed : total - (landed - 1) * (total / landed), end, true);
		}
		f.recordThrallDamage(50);
		f.recordHitTaken(3, false, end);
		f.end(true, end);
		return f;
	}

	/** Their record, so History shows them with Celebrations on: the fourteen and no older kills. */
	static BossRecords records(List<Fight> history)
	{
		return SidePanelProgressExamples.records(history, "Araxxor", 1020, 0, new double[]{90}, new double[]{0.95});
	}
}

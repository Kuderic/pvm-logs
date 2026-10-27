package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Your usual time on a boss, for the overlay's " vs your average" row: the
 * median expected time (the clock with the luck taken out) of your recent kills
 * of it. Worked out from the saved fights with the same formulas as the rows,
 * so it compares like with like. No client in here.
 * <p>
 * Only kills recorded by a build whose expected side can be trusted count
 * (Eric, 2026-10-26: "from new kills only"). Older history has its expected
 * damage from older models - Vardorvis read 98% accuracy before T-001 - and an
 * average of those would be wrong. The mark of a new kill is a boost share:
 * only builds with T-010, which came after T-001's boss-stat fixes, save one.
 * The Analyzer (workstream 03) will re-score old kills properly.
 */
final class LedgerHistory
{
	private LedgerHistory()
	{
	}

	/** The median and quartiles of the sample's expected times, in seconds. */
	static final class Average
	{
		final double median;
		final double lowerQuartile;
		final double upperQuartile;
		final int kills;

		Average(double median, double lowerQuartile, double upperQuartile, int kills)
		{
			this.median = median;
			this.lowerQuartile = lowerQuartile;
			this.upperQuartile = upperQuartile;
			this.kills = kills;
		}
	}

	/**
	 * Your average on the room {@code roomName}, or null with fewer than
	 * {@link LedgerRows#AVERAGE_FROM_KILLS} kills to go on.
	 *
	 * @param history the saved fights, newest first, as the plugin keeps them
	 * @param current the fights of the room on show, which is left out of its own average
	 */
	static Average of(List<Fight> history, String roomName, Collection<Fight> current)
	{
		if (history == null || roomName == null)
		{
			return null;
		}
		final Set<Fight> exclude = Collections.newSetFromMap(new IdentityHashMap<>());
		if (current != null)
		{
			exclude.addAll(current);
		}
		final double[] times = new double[LedgerRows.AVERAGE_OF_LAST];
		int n = 0;
		final List<Encounter> rooms = rooms(history, roomName);
		for (int i = rooms.size() - 1; i >= 0 && n < times.length; i--)
		{
			final Encounter room = rooms.get(i);
			if (!counts(room, exclude))
			{
				continue;
			}
			final double expected = new LedgerRows.Numbers(LedgerRows.of(room)).expectedDuration;
			if (!Double.isNaN(expected))
			{
				times[n++] = expected;
			}
		}
		if (n < LedgerRows.AVERAGE_FROM_KILLS)
		{
			return null;
		}
		final double[] sorted = Arrays.copyOf(times, n);
		Arrays.sort(sorted);
		return new Average(quantile(sorted, 0.5), quantile(sorted, 0.25), quantile(sorted, 0.75), n);
	}

	/**
	 * The rooms named {@code roomName}, oldest first, rebuilt from the fights as
	 * the side panel rebuilds them: an ungrouped NPC is a room of its own, and a
	 * grouped boss's fights share one room until the kill closes it.
	 */
	static List<Encounter> rooms(List<Fight> history, String roomName)
	{
		final List<Encounter> rooms = new ArrayList<>();
		Encounter open = null;
		for (int i = history.size() - 1; i >= 0; i--)
		{
			final Fight fight = history.get(i);
			if (!roomName.equals(fight.encounterName()))
			{
				continue;
			}
			if (fight.getGroupName() == null || open == null)
			{
				open = new Encounter(roomName, null, fight.getStartMillis());
				rooms.add(open);
			}
			open.add(fight);
			if (fight.getGroupName() == null || fight.isClosedRoom())
			{
				open = null;
			}
		}
		return rooms;
	}

	/** A kill, outside a raid, recorded by a build with a trustworthy expected side, with no warning on it. */
	private static boolean counts(Encounter room, Set<Fight> exclude)
	{
		boolean scored = false;
		boolean killed = false;
		for (Fight fight : room.getFights())
		{
			if (exclude.contains(fight) || fight.getRaidName() != null || fight.getStatsWarning() != null)
			{
				return false;
			}
			if (fight.isScored())
			{
				if (fight.getBoostSamples() <= 0)
				{
					return false;
				}
				scored = true;
			}
			// A grouped boss is killed when its room closes on the loot; one of its parts dying is not the kill.
			killed |= fight.isClosedRoom() || (fight.getGroupName() == null && fight.isTargetDied());
		}
		return scored && killed;
	}

	/** Linear between the two nearest ranks. */
	static double quantile(double[] sorted, double p)
	{
		final double h = (sorted.length - 1) * p;
		final int lo = (int) Math.floor(h);
		final int hi = Math.min(sorted.length - 1, lo + 1);
		return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
	}
}

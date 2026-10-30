package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Your usual time on a boss, for the overlay's Summary: the median clock of
 * your recent kills of it, worked out from the saved fights with the same
 * formulas as the rows. No client in here.
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

	/**
	 * What makes two kills comparable (T-016): the same room, the same boss
	 * version, and both solo or both not. Vardorvis Awakened (1,400 HP) and
	 * post-quest (700 HP) share a name and an NPC id, so the version is told by
	 * the boss's full HP, the room's largest as the side panel takes it. Any
	 * other player's damage in the room makes it a group kill. Team size and
	 * invocations wait for 03's encounter log.
	 */
	static final class Like
	{
		final String name;
		final int maxHp;
		final boolean solo;

		Like(String name, int maxHp, boolean solo)
		{
			this.name = name;
			this.maxHp = maxHp;
			this.solo = solo;
		}

		static Like of(Encounter room)
		{
			return new Like(room.getName(), maxHp(room), solo(room));
		}

		boolean matches(Encounter room)
		{
			return maxHp == maxHp(room) && solo == solo(room);
		}

		private static int maxHp(Encounter room)
		{
			int hp = -1;
			for (Fight fight : room.getFights())
			{
				hp = Math.max(hp, fight.getMaxHp());
			}
			return hp;
		}

		private static boolean solo(Encounter room)
		{
			for (Fight fight : room.getFights())
			{
				if (fight.getOthersDamage() > 0)
				{
					return false;
				}
			}
			return true;
		}
	}

	/** A median and the quartiles round it, in seconds. */
	static final class Spread
	{
		final double median;
		final double lowerQuartile;
		final double upperQuartile;

		Spread(double median, double lowerQuartile, double upperQuartile)
		{
			this.median = median;
			this.lowerQuartile = lowerQuartile;
			this.upperQuartile = upperQuartile;
		}

		static Spread of(double[] sorted)
		{
			return new Spread(quantile(sorted, 0.5), quantile(sorted, 0.25), quantile(sorted, 0.75));
		}
	}

	/** Your usual kill: the clock's median and quartiles over your recent like kills. */
	static final class Average
	{
		final Spread actual;
		final int kills;

		Average(Spread actual, int kills)
		{
			this.actual = actual;
			this.kills = kills;
		}
	}

	/** Your average on kills like the room on show, which is left out of its own average. */
	static Average of(List<Fight> history, Encounter current)
	{
		return current == null ? null : of(history, Like.of(current), current.getFights());
	}

	/**
	 * Your average on kills like {@code like}, or null with fewer than
	 * {@link LedgerRows#AVERAGE_FROM_KILLS} of them to go on.
	 *
	 * @param history the saved fights, newest first, as the plugin keeps them
	 * @param current fights to leave out: the room on show
	 */
	static Average of(List<Fight> history, Like like, Collection<Fight> current)
	{
		if (history == null || like == null || like.name == null)
		{
			return null;
		}
		final Set<Fight> exclude = Collections.newSetFromMap(new IdentityHashMap<>());
		if (current != null)
		{
			exclude.addAll(current);
		}
		final double[] clocks = new double[LedgerRows.AVERAGE_OF_LAST];
		int n = 0;
		final List<Encounter> rooms = rooms(history, like.name);
		for (int i = rooms.size() - 1; i >= 0 && n < clocks.length; i--)
		{
			final Encounter room = rooms.get(i);
			if (!like.matches(room) || !counts(room, exclude))
			{
				continue;
			}
			// Kills whose luck is known, as before: the same kills the expected side could be trusted on.
			final LedgerRows.Numbers numbers = new LedgerRows.Numbers(LedgerRows.of(room));
			if (!Double.isNaN(numbers.luck))
			{
				clocks[n++] = numbers.clock;
			}
		}
		if (n < LedgerRows.AVERAGE_FROM_KILLS)
		{
			return null;
		}
		final double[] actual = Arrays.copyOf(clocks, n);
		Arrays.sort(actual);
		return new Average(Spread.of(actual), n);
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

	/**
	 * A kill, outside a raid, recorded by a build with a trustworthy expected
	 * side, with no warning on it: one of a boss's averages. Never a run of
	 * monsters (T-094): a run isn't a boss's kill, and its clock is a run's.
	 */
	static boolean counts(Encounter room, Set<Fight> exclude)
	{
		if (room.isMonsterRoom())
		{
			return false;
		}
		boolean scored = false;
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
		}
		return scored && isKill(room);
	}

	/**
	 * Whether a saved room is a kill. A grouped boss is killed when its room
	 * closes on the loot; one of its parts dying is not the kill, nor the room
	 * ending on the player's death or the boss leaving alive (T-100). A run of
	 * monsters (T-089) closes on its last fight whatever happened in it, so it
	 * is a kill only with a kill in it (T-094).
	 */
	static boolean isKill(Encounter room)
	{
		boolean killed = false;
		for (Fight fight : room.getFights())
		{
			// A room that ended without its kill (T-100: the player died, or the boss left alive) closes on a fight too.
			killed |= fight.isClosedRoomOnKill() || (fight.getGroupName() == null && fight.isTargetDied());
		}
		return killed && (!room.isMonsterRoom() || room.getKills() > 0);
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

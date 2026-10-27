package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Your average on a boss, from the saved fights: kills only, from builds that
 * save a boost share, outside raids, the latest 20, shown from 5.
 */
public class LedgerHistoryTest
{
	private static final String BOSS = "Vardorvis";
	/** Post-quest Vardorvis, solo: what the test kills are. */
	private static final LedgerHistory.Like LIKE = new LedgerHistory.Like(BOSS, 700, true);

	/**
	 * A kill with no luck in it (damage = expected), so its expected time is its
	 * clock: {@code seconds} of fighting, in ticks of 0.6 s.
	 */
	private static Fight kill(double seconds)
	{
		return fight(BOSS, seconds, true, true);
	}

	private static Fight fight(String name, double seconds, boolean newBuild, boolean died)
	{
		final Fight f = new Fight(name, NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		final int ticks = (int) Math.round(seconds / 0.6);
		final int attacks = Math.max(1, ticks / 4);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, newBuild ? 1.0 : -1);
			f.recordExpected(40, 0.5, 10);
		}
		for (int i = attacks; i < ticks; i++)
		{
			f.recordTickSpent();
		}
		f.recordDamageDealt(attacks * 10, 1L, true);
		f.end(died, 2L);
		return f;
	}

	/** Newest first, as the plugin keeps its history. */
	private static List<Fight> newestFirst(Fight... oldestFirst)
	{
		final List<Fight> list = new ArrayList<>(Arrays.asList(oldestFirst));
		Collections.reverse(list);
		return list;
	}

	@Test
	public void fiveNewKillsMakeAnAverage()
	{
		final LedgerHistory.Average a = LedgerHistory.of(
			newestFirst(kill(110), kill(120), kill(115), kill(130), kill(125)), LIKE, null);
		assertNotNull(a);
		assertEquals(5, a.kills);
		assertEquals(120, a.median, 0.7);
		assertEquals(115, a.lowerQuartile, 0.7);
		assertEquals(125, a.upperQuartile, 0.7);
	}

	@Test
	public void fourAreNotEnough()
	{
		assertNull(LedgerHistory.of(newestFirst(kill(110), kill(120), kill(115), kill(130)), LIKE, null));
	}

	@Test
	public void onlyKillsFromThisBuildCount()
	{
		// Eric, 2026-10-26: "from new kills only". Fights saved without a boost share are from before T-010, whose expected
		// side came from older boss stats.
		final List<Fight> history = newestFirst(fight(BOSS, 300, false, true), fight(BOSS, 300, false, true),
			kill(110), kill(120), kill(115), kill(130));
		assertNull(LedgerHistory.of(history, LIKE, null));
		history.add(0, kill(125));
		assertEquals(120, LedgerHistory.of(history, LIKE, null).median, 0.7);
	}

	@Test
	public void deathsOtherBossesRaidsAndWarnedFightsAreLeftOut()
	{
		final Fight died = fight(BOSS, 300, true, false);
		final Fight other = fight("Duke Sucellus", 300, true, true);
		final Fight raid = new Fight(BOSS, NpcIds.GOBLIN, 1, 700, 0L, RaidType.TOMBS_OF_AMASCUT, 1);
		raid.recordAttackMade(false, 1.0);
		raid.recordExpected(40, 0.5, 10);
		raid.recordDamageDealt(10, 1L, true);
		raid.end(true, 2L);
		final Fight warned = kill(300);
		warned.addStatsWarning("placeholder stats");
		final LedgerHistory.Average a = LedgerHistory.of(newestFirst(kill(110), died, kill(120), other, kill(115), raid,
			kill(130), warned, kill(125)), LIKE, null);
		assertEquals(5, a.kills);
		assertEquals(120, a.median, 0.7);
	}

	@Test
	public void theKillOnShowIsLeftOutOfItsOwnAverage()
	{
		final Fight now = kill(60);
		final List<Fight> history = newestFirst(kill(110), kill(120), kill(115), kill(130), kill(125), now);
		final LedgerHistory.Average a = LedgerHistory.of(history, LIKE, Collections.singletonList(now));
		assertEquals(5, a.kills);
		assertEquals(120, a.median, 0.7);
	}

	@Test
	public void onlyTheLatestTwentyCount()
	{
		final List<Fight> oldestFirst = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			oldestFirst.add(kill(300));
		}
		for (int i = 0; i < 20; i++)
		{
			oldestFirst.add(kill(120));
		}
		final LedgerHistory.Average a = LedgerHistory.of(newestFirst(oldestFirst.toArray(new Fight[0])), LIKE, null);
		assertEquals(20, a.kills);
		assertEquals(120, a.upperQuartile, 0.7);
	}

	@Test
	public void luckIsTakenOut()
	{
		// 120 s on the clock with 20% more damage than expected: at average luck it would have taken 144 s.
		final List<Fight> lucky = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			final Fight f = new Fight(BOSS, NpcIds.GOBLIN, 1, 700, 0L, null, 0);
			for (int a = 0; a < 50; a++)
			{
				f.recordAttackMade(false, 1.0);
				f.recordExpected(40, 0.5, 10);
			}
			for (int t = 50; t < 200; t++)
			{
				f.recordTickSpent();
			}
			f.recordDamageDealt(600, 1L, true);
			f.end(true, 2L);
			lucky.add(f);
		}
		assertEquals(144, LedgerHistory.of(lucky, LIKE, null).median, 0.01);
	}

	@Test
	public void aGroupedBossIsOneKillUntilItsRoomCloses()
	{
		// The Nightmare's fights share a room; the loot closes it. A part dying isn't the kill.
		final List<Fight> oldestFirst = new ArrayList<>();
		String name = null;
		for (int k = 0; k < 5; k++)
		{
			for (int part = 0; part < 2; part++)
			{
				final Fight f = new Fight("The Nightmare", NpcIds.NIGHTMARE, 1, 2400, 0L, null, 0);
				for (int a = 0; a < 25; a++)
				{
					f.recordAttackMade(false, 1.0);
					f.recordExpected(40, 0.5, 10);
				}
				for (int t = 25; t < 100; t++)
				{
					f.recordTickSpent();
				}
				f.recordDamageDealt(250, 1L, true);
				f.end(true, 2L);
				if (part == 1)
				{
					f.closeRoom();
				}
				oldestFirst.add(f);
				name = f.encounterName();
			}
		}
		final List<Fight> history = newestFirst(oldestFirst.toArray(new Fight[0]));
		assertEquals(5, LedgerHistory.rooms(history, name).size());
		// Two fights of 100 ticks each: 120 s a kill.
		assertEquals(120, LedgerHistory.of(history, new LedgerHistory.Like(name, 2400, true), null).median, 0.01);
	}

	@Test
	public void anAwakenedKillDoesntCountTowardAPostQuestAverage()
	{
		// Same name and NPC id; the boss's full HP tells the versions apart (T-016).
		final List<Fight> history = newestFirst(kill(110), kill(120), kill(115), kill(130), awakened(60), awakened(60),
			awakened(60));
		assertNull(LedgerHistory.of(history, LIKE, null));
		history.add(0, kill(125));
		assertEquals(120, LedgerHistory.of(history, LIKE, null).median, 0.7);
		assertNull(LedgerHistory.of(history, new LedgerHistory.Like(BOSS, 1400, true), null));
	}

	@Test
	public void aDuoKillDoesntCountTowardASoloAverage()
	{
		final List<Fight> history = newestFirst(kill(110), kill(120), kill(115), kill(130), duo(60), duo(60));
		assertNull(LedgerHistory.of(history, LIKE, null));
		history.add(0, kill(125));
		assertEquals(120, LedgerHistory.of(history, LIKE, null).median, 0.7);
		assertNull(LedgerHistory.of(history, new LedgerHistory.Like(BOSS, 700, false), null));
	}

	@Test
	public void theRoomOnShowSetsWhatCounts()
	{
		final Fight now = duo(60);
		final Encounter room = new Encounter(BOSS, null, 0L);
		room.add(now);
		final LedgerHistory.Like like = LedgerHistory.Like.of(room);
		assertEquals(700, like.maxHp);
		assertEquals(false, like.solo);
		final List<Fight> history = newestFirst(duo(100), duo(110), duo(120), duo(130), duo(140), kill(30), now);
		final LedgerHistory.Average a = LedgerHistory.of(history, room);
		assertEquals(5, a.kills);
		assertEquals(120, a.median, 0.7);
	}

	private static Fight awakened(double seconds)
	{
		final Fight f = kill(seconds);
		f.noteMaxHp(1400);
		return f;
	}

	private static Fight duo(double seconds)
	{
		final Fight f = kill(seconds);
		f.recordOthersDamage(50);
		return f;
	}

	@Test
	public void quantilesAreLinearBetweenRanks()
	{
		final double[] x = {100, 110, 120, 130};
		assertEquals(115, LedgerHistory.quantile(x, 0.5), 1e-9);
		assertEquals(107.5, LedgerHistory.quantile(x, 0.25), 1e-9);
		assertEquals(122.5, LedgerHistory.quantile(x, 0.75), 1e-9);
		assertEquals(100, LedgerHistory.quantile(new double[] {100}, 0.5), 1e-9);
	}

	@Test
	public void nothingToGoOn()
	{
		assertNull(LedgerHistory.of(Collections.emptyList(), LIKE, null));
		assertNull(LedgerHistory.of(null, LIKE, null));
	}
}

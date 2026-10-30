package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-093: a slayer trip never pushes a boss kill out of History. In the shape
 * of Eric's history.json of 2026-10-29 (nothing copied): 335 Abyssal demon
 * fights, most saved before T-089 flagged monster runs, had pushed every older
 * boss kill out of a single cap of 500.
 */
public class HistoryBudgetTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int DEMON = NpcID.KOUREND_ABYSSAL;

	private static Fight boss(int i)
	{
		final Fight f = new Fight("Vardorvis", NpcID.VARDORVIS, 1000 + i, 700, i * 1000L, null, 0);
		f.end(true, i * 1000L + 500);
		return f;
	}

	/** A demon fight; {@code flagged}: saved with T-089's monster flag, as fights since #105 are. */
	private static Fight demon(int i, boolean flagged)
	{
		final Fight f = new Fight("Abyssal demon", DEMON, 2000 + i, 150, i * 1000L, null, 0);
		if (flagged)
		{
			f.groupAsMonster();
		}
		f.end(true, i * 1000L + 500);
		return f;
	}

	private static long count(List<Fight> history, boolean monsters)
	{
		return history.stream().filter(f -> HistoryBudget.isMonster(f, RULES) == monsters).count();
	}

	@Test
	public void monstersAndBossesAreToldApartOldAndNew()
	{
		assertTrue(HistoryBudget.isMonster(demon(1, true), RULES));
		// Saved before the flag: still a monster by T-089's own test.
		assertTrue(HistoryBudget.isMonster(demon(1, false), RULES));
		assertTrue(HistoryBudget.isMonster(demon(1, false), null));
		assertFalse(HistoryBudget.isMonster(boss(1), RULES));
		// A group's add, a raid's fight and a boss with rules are the boss's.
		assertFalse(HistoryBudget.isMonster(new Fight("Ur-maggot larvae", NpcID.UR_MAGGOT_LARVAE, 1, 30, 0L, null, 0), RULES));
		assertFalse(HistoryBudget.isMonster(new Fight("Giant Mole", NpcID.MOLE_GIANT, 1, 200, 0L, null, 0), RULES));
		assertFalse(HistoryBudget.isMonster(new Fight("Skeletal Mystic", 7604, 1, 160, 0L, RaidType.CHAMBERS_OF_XERIC, 1),
			RULES));
	}

	@Test
	public void aSlayerTripAfterThreeHundredKillsKeepsThemAll()
	{
		final List<Fight> history = new ArrayList<>();
		for (int i = 0; i < 300; i++)
		{
			HistoryBudget.add(history, boss(i), RULES, HistoryBudget.BOSS_FIGHTS, HistoryBudget.MONSTER_FIGHTS);
		}
		for (int i = 0; i < 600; i++)
		{
			HistoryBudget.add(history, demon(300 + i, i % 2 == 0), RULES, HistoryBudget.BOSS_FIGHTS,
				HistoryBudget.MONSTER_FIGHTS);
		}
		assertEquals(300, count(history, false));
		assertEquals(HistoryBudget.MONSTER_FIGHTS, count(history, true));
		// The demons kept are the newest.
		assertEquals(899 * 1000L, history.get(0).getStartMillis());
		assertEquals(400 * 1000L, history.stream().filter(f -> HistoryBudget.isMonster(f, RULES))
			.mapToLong(Fight::getStartMillis).min().getAsLong());
	}

	@Test
	public void eachBudgetEvictsOnlyItsOwnKindOldestFirst()
	{
		final List<Fight> history = new ArrayList<>();
		// Budgets of 3 bosses and 2 monsters, fights interleaved.
		for (int i = 0; i < 6; i++)
		{
			HistoryBudget.add(history, boss(i * 2), RULES, 3, 2);
			HistoryBudget.add(history, demon(i * 2 + 1, false), RULES, 3, 2);
		}
		final List<Long> bosses = new ArrayList<>();
		final List<Long> demons = new ArrayList<>();
		for (Fight f : history)
		{
			(HistoryBudget.isMonster(f, RULES) ? demons : bosses).add(f.getStartMillis() / 1000);
		}
		assertEquals(Arrays.asList(10L, 8L, 6L), bosses);
		assertEquals(Arrays.asList(11L, 9L), demons);
		// Adding a boss drops the oldest boss and no demon.
		assertEquals(1, HistoryBudget.add(history, boss(12), RULES, 3, 2));
		assertEquals(2, count(history, true));
		assertEquals(3, count(history, false));
	}

	@Test
	public void anOldFileOverTheBudgetsLoadsIntact()
	{
		// A file saved under the old single cap, then a long trip: 700 old demons, unflagged, and 300 boss kills.
		final List<Fight> saved = new ArrayList<>();
		for (int i = 0; i < 700; i++)
		{
			saved.add(0, demon(i, false));
		}
		for (int i = 0; i < 300; i++)
		{
			saved.add(0, boss(700 + i));
		}
		final Gson gson = new Gson();
		final Fight[] loaded = gson.fromJson(gson.toJson(saved), Fight[].class);
		// As loadHistory does: everything the file holds.
		final List<Fight> history = new ArrayList<>(Arrays.asList(loaded));
		assertEquals(1000, history.size());
		// A boss kill added keeps every demon...
		HistoryBudget.add(history, boss(1000), RULES, HistoryBudget.BOSS_FIGHTS, HistoryBudget.MONSTER_FIGHTS);
		assertEquals(700, count(history, true));
		assertEquals(301, count(history, false));
		// ...and a demon added trims only the demons, oldest first.
		HistoryBudget.add(history, demon(1001, true), RULES, HistoryBudget.BOSS_FIGHTS, HistoryBudget.MONSTER_FIGHTS);
		assertEquals(HistoryBudget.MONSTER_FIGHTS, count(history, true));
		assertEquals(301, count(history, false));
	}
}

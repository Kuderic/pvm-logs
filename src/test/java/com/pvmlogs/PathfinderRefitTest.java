package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * T-079: the Leviathan's pathfinder. Inside it a ranged attack cannot miss, deals
 * at least a fifth of its max (wiki; Mod Ash), and - fitted, not sourced - takes
 * the better of two damage rolls. The player counts as inside only once inside
 * on the tick before the attack goes out as well.
 *
 * <p>The replay is Eric's 288 pathfinder shots of 2026-10-28/29 as counts of
 * (max, damage) - each ATTACK paired with the xp award on its thrown tick, none
 * a miss - read on his PC by the Orchestrator and this chat; no log lines are
 * copied. The blowpipe's are at its real max of 24: from 16:47 on the plugin
 * assumed dragon darts (28) where his are adamant (no hit above 24 in 112 shots).
 * One bow shot logged against a max of 28, a 41, was a shot booked across a
 * switch and is left out.
 */
public class PathfinderRefitTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int LEVI = 12214;
	private static final int PATHFINDER = 12220;

	/** {max, damage, count, damage, count, ...}: the bow of faerdhinen's shots judged inside. */
	private static final int[][] BOW = {
		{39, 15, 1},
		{40, 0, 1, 31, 1, 32, 1, 33, 1, 40, 1},
		{43, 9, 1, 20, 1, 27, 1, 37, 1, 39, 1, 42, 1},
		{44, 0, 6, 3, 1, 4, 1, 6, 1, 7, 2, 9, 1, 10, 4, 11, 1, 12, 4, 13, 1, 15, 1, 16, 3, 17, 4, 18, 3, 19, 3, 20, 2,
			21, 6, 22, 1, 23, 4, 24, 5, 25, 7, 26, 6, 27, 6, 28, 6, 29, 3, 30, 1, 31, 3, 32, 1, 33, 3, 34, 5, 35, 5, 36, 4,
			37, 5, 38, 4, 39, 9, 40, 3, 41, 8, 42, 7, 43, 7, 44, 7},
	};

	/** The blazing blowpipe's, at its max of 24. */
	private static final int[][] BLOWPIPE = {
		{24, 0, 3, 2, 1, 4, 1, 5, 3, 6, 3, 7, 5, 8, 1, 10, 4, 11, 5, 12, 3, 13, 6, 14, 4, 15, 2, 16, 7, 17, 8, 18, 3, 19, 8,
			20, 11, 21, 10, 22, 13, 23, 9, 24, 11},
	};

	private static List<BossRules.ModifierRule> pathfinder(String style)
	{
		final BossRules.Boss boss = RULES.forNpc(LEVI);
		assertNotNull(boss);
		final ModifierTracker tracker = new ModifierTracker(null);
		return tracker.applying(1, boss.damageModifiers, LEVI, style, 500, false,
			(id, range) -> id == PATHFINDER && range == 1);
	}

	/** The same rules without the two rolls: T-057's, a minimum alone. */
	private static List<BossRules.ModifierRule> withoutRolls(List<BossRules.ModifierRule> mods)
	{
		final List<BossRules.ModifierRule> out = new ArrayList<>();
		final Gson gson = new Gson();
		for (BossRules.ModifierRule m : mods)
		{
			final BossRules.ModifierRule copy = gson.fromJson(gson.toJson(m), BossRules.ModifierRule.class);
			copy.rolls = null;
			out.add(copy);
		}
		return out;
	}

	@Test
	public void theRangedRuleIsTwoRollsAtAFifthAndCannotMiss()
	{
		final List<BossRules.ModifierRule> ranged = pathfinder("RANGED");
		assertEquals(1, ranged.size());
		assertEquals(2, ModifierTracker.rolls(ranged));
		assertEquals(1.0, ModifierTracker.accuracy(ranged, 0.6), 1e-12);
		assertEquals(8, ModifierTracker.damage(ranged, 0, 44, true));
		// The max is the weapon's: two rolls cannot go above it (T-056's MAX EXCEEDED check stays as it was).
		assertEquals(44, ModifierTracker.max(ranged, 44));
		// Magic keeps the wiki's doubling and one roll.
		assertEquals(1, ModifierTracker.rolls(pathfinder("MAGIC")));
		assertEquals(88, ModifierTracker.max(pathfinder("MAGIC"), 44));
	}

	/** The moments against every pair of rolls, enumerated. */
	@Test
	public void twoRollsAgainstBruteForce()
	{
		final List<BossRules.ModifierRule> ranged = pathfinder("RANGED");
		for (int max : new int[]{24, 40, 44})
		{
			double mean = 0;
			double second = 0;
			final double each = 1.0 / ((max + 1) * (max + 1));
			for (int a = 0; a <= max; a++)
			{
				for (int b = 0; b <= max; b++)
				{
					final double v = ModifierTracker.damage(ranged, Math.max(a, b), max, true);
					mean += each * v;
					second += each * v * v;
				}
			}
			final DamageMoments m = ModifierTracker.moments(ranged, new int[]{max}, 1.0, Integer.MAX_VALUE);
			assertEquals("mean at " + max, mean, m.mean, 1e-9);
			assertEquals("variance at " + max, second - mean * mean, m.variance(), 1e-9);
		}
		// 44: the better of two rolls, a fifth at least, averages 29.60 where the minimum alone gave 22.80.
		assertEquals(29.60, ModifierTracker.moments(pathfinder("RANGED"), new int[]{44}, 1.0, Integer.MAX_VALUE).mean,
			0.01);
		assertEquals(22.80, ModifierTracker.moments(withoutRolls(pathfinder("RANGED")), new int[]{44}, 1.0,
			Integer.MAX_VALUE).mean, 0.01);
	}

	/** The chance of the best of n rolls sums to one, and is the plain uniform for one. */
	@Test
	public void rollChanceIsADistribution()
	{
		for (int n = 1; n <= 3; n++)
		{
			double sum = 0;
			for (int r = 0; r <= 44; r++)
			{
				sum += ModifierTracker.rollChance(r, 44, n);
			}
			assertEquals(1.0, sum, 1e-12);
		}
		assertEquals(1.0 / 45, ModifierTracker.rollChance(7, 44, 1), 1e-15);
		assertEquals((2.0 * 44 + 1) / (45.0 * 45), ModifierTracker.rollChance(44, 44, 2), 1e-15);
	}

	// ---- in and out of range at the throw ----

	@Test
	public void insideCountsFromTheSecondTick()
	{
		int since = DamageModifiers.NOT_INSIDE;
		// Outside at 99, in at 100: not yet. Still in at 101: inside.
		since = DamageModifiers.since(since, 98, false, 99);
		assertFalse(DamageModifiers.dwelt(since, 99));
		since = DamageModifiers.since(since, 99, true, 100);
		assertFalse(DamageModifiers.dwelt(since, 100));
		since = DamageModifiers.since(since, 100, true, 101);
		assertTrue(DamageModifiers.dwelt(since, 101));
		assertTrue(DamageModifiers.dwelt(DamageModifiers.since(since, 101, true, 102), 102));
		// Out for a tick: out, and in again counts from the tick after.
		since = DamageModifiers.since(since, 102, false, 103);
		assertFalse(DamageModifiers.dwelt(since, 103));
		since = DamageModifiers.since(since, 103, true, 104);
		assertFalse(DamageModifiers.dwelt(since, 104));
		// A tick not asked about is not known to have been inside.
		since = DamageModifiers.since(since, 104, true, 106);
		assertFalse(DamageModifiers.dwelt(since, 106));
		assertTrue(DamageModifiers.dwelt(DamageModifiers.since(since, 106, true, 107), 107));
	}

	@Test
	public void outsideTakesNothing()
	{
		final BossRules.Boss boss = RULES.forNpc(LEVI);
		final ModifierTracker tracker = new ModifierTracker(null);
		assertTrue(tracker.applying(1, boss.damageModifiers, LEVI, "RANGED", 500, false, (id, range) -> false).isEmpty());
	}

	// ---- Eric's shots ----

	private static double[] replay(int[][] rows, List<BossRules.ModifierRule> mods)
	{
		double actual = 0;
		double expected = 0;
		double variance = 0;
		int shots = 0;
		for (int[] row : rows)
		{
			final DamageMoments m = ModifierTracker.moments(mods, new int[]{row[0]}, ModifierTracker.accuracy(mods, 0.7),
				Integer.MAX_VALUE);
			for (int i = 1; i < row.length; i += 2)
			{
				actual += (double) row[i] * row[i + 1];
				expected += m.mean * row[i + 1];
				variance += m.variance() * row[i + 1];
				shots += row[i + 1];
			}
		}
		return new double[]{shots, actual, expected, (actual - expected) / Math.sqrt(variance)};
	}

	@Test
	public void ericsShotsAreCalibratedWithTwoRolls()
	{
		final List<BossRules.ModifierRule> ranged = pathfinder("RANGED");
		final double[] bow = replay(BOW, ranged);
		final double[] blowpipe = replay(BLOWPIPE, ranged);
		assertEquals(166, (int) bow[0]);
		assertEquals(121, (int) blowpipe[0]);
		// Within noise: the bow -1.9 sd, the blowpipe +0.9.
		assertEquals(-1.86, bow[3], 0.01);
		assertEquals(0.89, blowpipe[3], 0.01);
		// The bow's 7 misses cannot have been inside, where nothing misses: first shots of an enrage, which the tick of
		// dwell judges outside. Without them it is -0.3.
		assertEquals(-0.33, replay(withoutMisses(BOW), ranged)[3], 0.01);
		// T-057's minimum alone was far under: +5.7 and +7.1 sd.
		assertTrue(replay(BOW, withoutRolls(ranged))[3] > 5);
		assertTrue(replay(BLOWPIPE, withoutRolls(ranged))[3] > 5);
	}

	private static int[][] withoutMisses(int[][] rows)
	{
		final int[][] out = new int[rows.length][];
		for (int i = 0; i < rows.length; i++)
		{
			final List<Integer> kept = new ArrayList<>();
			kept.add(rows[i][0]);
			for (int j = 1; j < rows[i].length; j += 2)
			{
				if (rows[i][j] > 0)
				{
					kept.add(rows[i][j]);
					kept.add(rows[i][j + 1]);
				}
			}
			out[i] = kept.stream().mapToInt(Integer::intValue).toArray();
		}
		return out;
	}
}

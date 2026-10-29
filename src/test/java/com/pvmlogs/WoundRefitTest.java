package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * T-083: a ranged shot into the stunned Leviathan's wound cannot miss and deals
 * at least ~65% of the max (wiki), drawn from that minimum up to the max as the
 * better of two rolls (fitted, not sourced) rather than raised to it.
 *
 * <p>The replays are Eric's wound shots as counts of (max, damage), read on his
 * PC; no log lines are copied. The 17 his T-078 build booked "into the wound"
 * (each paired with the xp award on its thrown tick), and the 73 at a known max
 * from 2026-10-28 16:21 on, each the shot landing 3 ticks before the special it
 * sets off (in the T-078 build's logs every special but none follows a booked
 * wound shot by exactly 3 ticks).
 */
public class WoundRefitTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int LEVI = 12214;
	private static final int INDEX = 7;

	/** {max, damage, count, ...}: the 17 shots T-078's build booked into the wound. */
	private static final int[][] BOOKED = {
		{40, 37, 1},
		{44, 32, 2, 34, 1, 35, 1, 38, 2, 39, 2, 40, 3, 41, 2, 42, 1, 43, 1, 44, 1},
	};

	/** The 73 that set off a special, at a known max. */
	private static final int[][] SPECIALS = {
		{37, 28, 1, 36, 1},
		{39, 34, 1},
		{40, 37, 1},
		{44, 28, 1, 30, 4, 31, 3, 32, 8, 33, 2, 34, 2, 35, 3, 36, 2, 37, 4, 38, 7, 39, 4, 40, 5, 41, 3, 42, 5, 43, 6,
			44, 10},
	};

	private static List<BossRules.ModifierRule> wound(String style)
	{
		final BossRules.Boss boss = RULES.forNpc(LEVI);
		assertNotNull(boss);
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onPlayerSpell(INDEX, "The Leviathan", boss.damageModifiers, "SHADOW_RUSH", 100);
		final List<BossRules.ModifierRule> mods = tracker.applying(INDEX, boss.damageModifiers, LEVI, style, 105, true,
			(id, range) -> false);
		assertEquals(1, mods.size());
		assertEquals("into the wound", mods.get(0).name);
		return mods;
	}

	/** The same rule as T-057 had it: one roll, raised to the minimum. */
	private static List<BossRules.ModifierRule> asBefore(List<BossRules.ModifierRule> mods)
	{
		final Gson gson = new Gson();
		final List<BossRules.ModifierRule> out = new ArrayList<>();
		for (BossRules.ModifierRule m : mods)
		{
			final BossRules.ModifierRule copy = gson.fromJson(gson.toJson(m), BossRules.ModifierRule.class);
			copy.rolls = null;
			copy.rollsFromMin = null;
			out.add(copy);
		}
		return out;
	}

	@Test
	public void theRangedRuleIsTwoDrawsFromTheMinimum()
	{
		final List<BossRules.ModifierRule> ranged = wound("RANGED");
		assertEquals(1.0, ModifierTracker.accuracy(ranged, 0.6), 1e-12);
		assertEquals(2, ModifierTracker.rolls(ranged));
		assertEquals(28, ModifierTracker.rollFloor(ranged, 44));
		assertEquals(44, ModifierTracker.max(ranged, 44));
		// Nothing changes where no modifier draws from the minimum.
		assertEquals(0, ModifierTracker.rollFloor(asBefore(ranged), 44));
		// Magic stays doubled, one roll from 0.
		final List<BossRules.ModifierRule> magic = wound("MAGIC");
		assertEquals(1, ModifierTracker.rolls(magic));
		assertEquals(0, ModifierTracker.rollFloor(magic, 44));
		assertEquals(88, ModifierTracker.max(magic, 44));
	}

	/** The moments against every pair of draws from the minimum to the max, enumerated; and one draw. */
	@Test
	public void drawsFromTheMinimumAgainstBruteForce()
	{
		final List<BossRules.ModifierRule> ranged = wound("RANGED");
		final List<BossRules.ModifierRule> oneDraw = asBefore(ranged);
		oneDraw.get(0).rollsFromMin = true;
		for (int max : new int[]{37, 40, 44})
		{
			final int lo = (int) Math.floor(0.65 * max);
			double mean = 0;
			double second = 0;
			double oneMean = 0;
			final int k = max - lo + 1;
			for (int a = lo; a <= max; a++)
			{
				oneMean += (double) a / k;
				for (int b = lo; b <= max; b++)
				{
					final double v = ModifierTracker.damage(ranged, Math.max(a, b), max, true);
					mean += v / (k * k);
					second += v * v / (k * k);
				}
			}
			final DamageMoments m = ModifierTracker.moments(ranged, new int[]{max}, 1.0, Integer.MAX_VALUE);
			assertEquals("mean at " + max, mean, m.mean, 1e-9);
			assertEquals("variance at " + max, second - mean * mean, m.variance(), 1e-9);
			assertEquals("one draw at " + max, oneMean,
				ModifierTracker.moments(oneDraw, new int[]{max}, 1.0, Integer.MAX_VALUE).mean, 1e-9);
		}
		// At 44: 38.82, where T-057's max(roll, 28) gave 31.02 and one draw from 28 gives 36.
		assertEquals(38.82, ModifierTracker.moments(ranged, new int[]{44}, 1.0, Integer.MAX_VALUE).mean, 0.01);
		assertEquals(36.0, ModifierTracker.moments(oneDraw, new int[]{44}, 1.0, Integer.MAX_VALUE).mean, 1e-9);
		assertEquals(1396.0 / 45, ModifierTracker.moments(asBefore(ranged), new int[]{44}, 1.0, Integer.MAX_VALUE).mean,
			1e-9);
	}

	private static double[] replay(int[][] rows, List<BossRules.ModifierRule> mods)
	{
		double actual = 0;
		double expected = 0;
		double variance = 0;
		int shots = 0;
		for (int[] row : rows)
		{
			final DamageMoments m = ModifierTracker.moments(mods, new int[]{row[0]}, 1.0, Integer.MAX_VALUE);
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
	public void ericsWoundShotsAreCalibrated()
	{
		final List<BossRules.ModifierRule> ranged = wound("RANGED");
		final double[] booked = replay(BOOKED, ranged);
		assertEquals(17, (int) booked[0]);
		assertEquals(655, (int) booked[1]);
		// 655 against 656.6: -0.1 sd.
		assertEquals(-0.10, booked[3], 0.01);
		final double[] specials = replay(SPECIALS, ranged);
		assertEquals(73, (int) specials[0]);
		// 2,744 against 2,814: -2.1 sd, the one place a 60% minimum would fit better (see boss-rules.json).
		assertEquals(-2.07, specials[3], 0.01);
		// T-057's max(roll, minimum) was far under on both: +6 and +12 sd.
		assertTrue(replay(BOOKED, asBefore(ranged))[3] > 5);
		assertTrue(replay(SPECIALS, asBefore(ranged))[3] > 10);
	}
}

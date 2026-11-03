package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Random;
import org.junit.Test;

// The fallback for a boss the model gets wrong: a fight whose hits are too far from expected to be luck says so, and an
// ordinary run of good or bad luck never does.
public class StatsCheckTest
{
	private static Fight fight(int attacks, int landed, double accuracy, int maxHit)
	{
		final Fight f = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		for (int i = 0; i < attacks; i++)
		{
			f.recordExpected(maxHit, accuracy, accuracy * maxHit / 2.0);
			f.recordDamageDealt(i < landed ? 20 : 0, i, i < landed);
		}
		return f;
	}

	/**
	 * The first Vardorvis kill: 42 attacks at an expected 97.7% (his defence read as
	 * 0), 26 landed. More than ten standard deviations short.
	 */
	@Test
	public void theVardorvisKillTrips()
	{
		final Fight f = fight(42, 26, 0.977, 40);
		final double z = StatsCheck.zScore(f.getHits(), f.getSumExpectedLanded(), f.getSumExpectedLandedVariance(),
			f.getExpectedTargetSamples());
		assertTrue("z = " + z, z < -10);
		final String verdict = StatsCheck.accuracyVerdict(f);
		assertNotNull(verdict);
		assertTrue(verdict, verdict.contains("landed 26 of 42"));
	}

	/** Every kill in Eric's first Vardorvis trip trips it, from history.json's own counts. */
	@Test
	public void everyKillOfThatTripTrips()
	{
		final int[][] attacksAndHits = {{42, 26}, {42, 26}, {55, 43}, {37, 25}, {28, 22}, {31, 24}, {44, 32}};
		for (int[] k : attacksAndHits)
		{
			assertNotNull(k[0] + "/" + k[1], StatsCheck.accuracyVerdict(fight(k[0], k[1], 0.977, 45)));
		}
	}

	/** With his defence right (54% at full hitpoints, 68% at none), the same counts are luck. */
	@Test
	public void theSameKillWithHisRealDefenceIsLuck()
	{
		assertNull(StatsCheck.accuracyVerdict(fight(42, 26, 0.61, 40)));
	}

	@Test
	public void tooFewAttacksCannotTell()
	{
		final Fight f = fight(StatsCheck.MIN_ATTACKS - 1, 0, 0.99, 40);
		assertTrue(Double.isNaN(StatsCheck.zScore(f.getHits(), f.getSumExpectedLanded(),
			f.getSumExpectedLandedVariance(), f.getExpectedTargetSamples())));
		assertNull(StatsCheck.accuracyVerdict(f));
	}

	/** A successful roll deals 0 to max, so one in (max + 1) of them is a zero that reads as a miss. */
	@Test
	public void aSuccessfulZeroIsPushedToOne()
	{
		// T-151: OSRS Wiki, Successful hit: "If the roll is from a player, then 0s are pushed up to 1" (since 2024-05-29),
		// so every successful roll lands a splat.
		assertEquals(0.8, Fight.landedChance(0.8, 3), 1e-12);
		assertEquals(0.8, Fight.landedChance(0.8, 0), 1e-12);
		assertEquals(1.0, Fight.landedChance(1.3, 40), 1e-12);
	}

	/**
	 * A weak weapon over a long fight: max hit 3, 80% accurate, 400 attacks. A successful roll of 0 deals 1, so about
	 * 320 land, the accuracy. Taking the zeros off as misses, as before T-151, expected 240: a z near +9.
	 */
	@Test
	public void aWeakWeaponLandsItsAccuracy()
	{
		final Random random = new Random(7);
		final Fight f = new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
		for (int i = 0; i < 400; i++)
		{
			f.recordExpected(3, 0.8, 1.2);
			final int damage = random.nextDouble() < 0.8 ? Math.max(1, random.nextInt(4)) : 0;
			f.recordDamageDealt(damage, i, damage > 0);
		}
		assertNull(StatsCheck.accuracyVerdict(f));
		final double oldRule = 400 * 0.8 * 3 / 4.0;
		final double naive = (f.getHits() - oldRule) / Math.sqrt(oldRule * (1 - oldRule / 400));
		assertTrue("the old rule would have read z = " + naive, naive > 4);
	}

	/** Many fights of honest luck, of every length, accuracy and max hit: at four standard deviations, none trip. */
	@Test
	public void honestLuckDoesNotTrip()
	{
		// T-151: SplittableRandom, whose draws in sequence are independent. java.util.Random's aren't quite, and with every
		// success now landing (a 0 pushed to 1) its stream tripped three honest fights in this seed's 3,000.
		final java.util.SplittableRandom random = new java.util.SplittableRandom(20261026);
		int tripped = 0;
		for (int trial = 0; trial < 3000; trial++)
		{
			final int attacks = StatsCheck.MIN_ATTACKS + random.nextInt(400);
			final double accuracy = 0.2 + random.nextDouble() * 0.79;
			final int maxHit = 1 + random.nextInt(70);
			final Fight f = new Fight("x", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
			for (int i = 0; i < attacks; i++)
			{
				f.recordExpected(maxHit, accuracy, accuracy * maxHit / 2.0);
				final int damage = random.nextDouble() < accuracy ? Math.max(1, random.nextInt(maxHit + 1)) : 0;
				f.recordDamageDealt(damage, i, damage > 0);
			}
			if (StatsCheck.accuracyVerdict(f) != null)
			{
				tripped++;
			}
		}
		// One in about 15,000 by luck alone; 3,000 fights should see none, and two would mean the test is miscalibrated.
		assertTrue("tripped " + tripped, tripped <= 1);
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.pvmlogs.CombatCalc.OrdinaryShape;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-156: a player's successful 0 is pushed up to 1 (OSRS Wiki, Successful hit: "If the roll is from a player, then 0s
 * are pushed up to 1", since 2024-05-29), so a roll from 0 to max averages max / 2 + 1 / (max + 1), with its variance.
 * #178 (T-151) put the push in the landed chance; this is the damage side. Where the DPS calc pushes: each accurate
 * hitsplat, after the gear's multipliers, before the target's own share (the Corporeal Beast's halving) and its
 * transforms (caps, a boss's multipliers); not the dragon or burning claws' specials.
 */
public class SuccessfulZeroDamageTest
{
	private static final int NONE = Integer.MAX_VALUE;

	// The brute force: every roll 0..max alike, a 0 dealing 1, each held to the cap; a miss deals 0.
	private static double[] bruteForce(int max, double accuracy, int cap)
	{
		double mean = 0;
		double second = 0;
		for (int r = 0; r <= max; r++)
		{
			final double v = Math.min(cap, Math.max(1, r));
			mean += accuracy * v / (max + 1);
			second += accuracy * v * v / (max + 1);
		}
		return new double[]{mean, second - mean * mean};
	}

	private static void assertBruteForce(int max, double accuracy, int cap, DamageMoments got)
	{
		final double[] want = bruteForce(max, accuracy, cap);
		assertEquals("mean, max " + max, want[0], got.mean, 1e-12);
		assertEquals("variance, max " + max, want[1], got.variance(), 1e-9);
	}

	@Test
	public void aMaxOfTenFortyAndOne()
	{
		// A max of 10: 5 + 1/11, about 1.8% more; at 40, 20 + 1/41, about 0.1%.
		final DamageMoments ten = CombatCalc.ordinaryMoments(10, 1.0, 1, NONE);
		assertEquals(5 + 1.0 / 11, ten.mean, 1e-12);
		assertEquals(0.018, ten.mean / 5 - 1, 0.001);
		assertBruteForce(10, 1.0, NONE, ten);
		final DamageMoments forty = CombatCalc.ordinaryMoments(40, 0.8, 1, NONE);
		assertEquals(0.8 * (20 + 1.0 / 41), forty.mean, 1e-12);
		assertBruteForce(40, 0.8, NONE, forty);
		// A max of 1 always deals 1 when it lands: no variance but the accuracy roll's.
		final DamageMoments one = CombatCalc.ordinaryMoments(1, 0.6, 1, NONE);
		assertEquals(0.6, one.mean, 1e-12);
		assertEquals(0.6 * 0.4, one.variance(), 1e-12);
		// The variance is less than the old roll's: the 0 and the 1 are one value now.
		assertTrue(ten.variance() < DamageMoments.uniform(10).variance());
	}

	@Test
	public void aMultiHitAttackPushesEachHitsplat()
	{
		// A scythe's 51, 25, 12, each its own roll and its own 0.
		final DamageMoments scythe = CombatCalc.ordinaryMoments(51, 0.7, 3, NONE);
		assertEquals(0.7 * (51 / 2.0 + 1.0 / 52 + 25 / 2.0 + 1.0 / 26 + 12 / 2.0 + 1.0 / 13), scythe.mean, 1e-12);
		final double[] a = bruteForce(51, 0.7, NONE);
		final double[] b = bruteForce(25, 0.7, NONE);
		final double[] c = bruteForce(12, 0.7, NONE);
		assertEquals(a[1] + b[1] + c[1], scythe.variance(), 1e-9);
		// A dragon dagger's two hits, each its own.
		final DamageMoments dds = CombatCalc.specMoments(new int[]{30, 30}, 0.65, 0, 0, NONE, false);
		assertEquals(2 * 0.65 * (15 + 1.0 / 31), dds.mean, 1e-12);
		// A floor above 0 has no 0: the Voidwaker's half-max floor, unchanged.
		assertEquals(CombatCalc.cappedAverage(25, 50, NONE),
			CombatCalc.specMoments(new int[]{50}, 1.0, 0.5, 0, NONE, false).mean, 1e-12);
	}

	@Test
	public void aCappedLanding()
	{
		// 12 left: everything above collapses onto 12, the 0 onto 1.
		final DamageMoments capped = CombatCalc.ordinaryMoments(46, 0.8, 1, 12);
		assertEquals(0.8 * (CombatCalc.cappedAverage(46, 12) + 1.0 / 47), capped.mean, 1e-12);
		assertBruteForce(46, 0.8, 12, capped);
		// Re-capped as it lands (LandingCap): from 35 to 25 left, the pushed roll capped at 25.
		final DamageMoments then = DamageMoments.successful(0, 63, 35).landing(0.842);
		final double[] at = LandingCap.recap(63, 0.842, then.mean, then.variance(), 35, 25);
		assertBruteForce(63, 0.842, 25, new DamageMoments(at[0], at[1] + at[0] * at[0]));
		// Nothing left lets no 1 through.
		assertEquals(0, DamageMoments.successful(0, 46, 0).mean, 0);
	}

	@Test
	public void aGuaranteedMaxIsUnchanged()
	{
		final BossRules.ModifierRule max = new BossRules.ModifierRule();
		max.name = "guaranteed max";
		max.guaranteedMax = true;
		final List<BossRules.ModifierRule> mods = Collections.singletonList(max);
		final DamageMoments shot = ModifierTracker.moments(mods, new int[]{29}, 1.0, NONE, true);
		assertEquals(29, shot.mean, 1e-12);
		assertEquals(0, shot.variance(), 1e-9);
		assertEquals(29, ModifierTracker.moments(mods, new int[]{29}, 1.0, NONE, false).mean, 1e-12);
	}

	@Test
	public void theWardensPoiseBandIsUnchanged()
	{
		// T-152's band, 15% to 35% of a max of 4: 0 to 1, its own distribution (#175), not pushed.
		final BossRules.ModifierRule poise = new BossRules.ModifierRule();
		poise.name = "poise";
		poise.rollMinPercent = 15;
		poise.rollMaxPercent = 35;
		final List<BossRules.ModifierRule> mods = Collections.singletonList(poise);
		assertEquals(ModifierTracker.moments(mods, new int[]{4}, 1.0, NONE, false).mean,
			ModifierTracker.moments(mods, new int[]{4}, 1.0, NONE, true).mean, 0);
		assertEquals(0.5, ModifierTracker.moments(mods, new int[]{4}, 1.0, NONE, true).mean, 1e-12);
	}

	@Test
	public void aBossMultiplierActsOnThePushedOne()
	{
		// Pushed before the boss's transforms, as the DPS calc pushes: a x5 makes the 1 a 5, a halving makes it 0.
		final BossRules.ModifierRule five = new BossRules.ModifierRule();
		five.name = "x5";
		five.multiply = new int[]{5, 1};
		assertEquals(5 * (10 + 1.0 / 21),
			ModifierTracker.moments(Collections.singletonList(five), new int[]{20}, 1.0, NONE, true).mean, 1e-12);
		final BossRules.ModifierRule half = new BossRules.ModifierRule();
		half.name = "halved";
		half.multiply = new int[]{1, 2};
		final List<BossRules.ModifierRule> halved = Collections.singletonList(half);
		assertEquals(ModifierTracker.moments(halved, new int[]{20}, 1.0, NONE, false).mean,
			ModifierTracker.moments(halved, new int[]{20}, 1.0, NONE, true).mean, 1e-12);
	}

	@Test
	public void nothingIsPushedWhereTheTargetsShareTakesTheOneBack()
	{
		// The Corporeal Beast halves after the push, so a 1 is 0 again: the calc's halved max, its roll as before.
		assertEquals(0.8 * 20, CombatCalc.shapeMoments(OrdinaryShape.ONE, 40, 0.8, 1, NONE, false).mean, 1e-12);
		assertEquals(0.8 * (20 + 1.0 / 41), CombatCalc.shapeMoments(OrdinaryShape.ONE, 40, 0.8, 1, NONE, true).mean,
			1e-12);
	}

	@Test
	public void theClawsAreAsTheirPagesGiveThem()
	{
		// The claws' cascade: no push (the DPS calc leaves it out), so its figures are T-032's.
		final double p = 0.7;
		final DamageMoments claws = CombatCalc.clawsMoments(40, p, NONE);
		assertEquals(CombatCalc.clawsAverage(40, p, NONE), claws.mean, 0);
		// The cascade's first outcome alone (a = 1): 40, 20, 10, 10 from one roll, 0..80 split, averaging 40.
		assertEquals(CombatCalc.cappedAverage(40, NONE) + CombatCalc.cappedAverage(20, NONE)
			+ CombatCalc.cappedAverage(10, NONE) * 2, CombatCalc.clawsMoments(40, 1.0, NONE).mean, 1e-12);
	}

	@Test
	public void aMagicRollOfZeroDealsOne()
	{
		// Fire Wave 20: 0..20, the 0 dealing 1.
		final MagicDamage m = new MagicDamage();
		m.base = 20;
		m.percent = 0;
		assertEquals(10 + 1.0 / 21, m.expectedOnHit(1.0, NONE, NONE), 1e-12);
		assertBruteForce(20, 1.0, NONE, m.onHit(1.0, NONE, NONE));
	}
}

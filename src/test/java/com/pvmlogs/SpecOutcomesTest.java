package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;
import java.util.Random;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-050: expected damage and its variance averaged over the player's own
 * draining specials' outcomes. The figures come from a small model of the game's
 * own: an attack rolls its accuracy against the target's defence roll (the real
 * {@link CombatCalc#hitChanceFrom}) and its damage evenly from 0 to its max.
 */
public class SpecOutcomesTest
{
	private static final int ATTACK_ROLL = 30_000;
	private static final int DEFENCE_BONUS = 100;
	private static final int MAX = 50;
	private static final int HAMMER_MAX = 75;
	private static final int NONE = Integer.MAX_VALUE;

	static double accuracy(int defence)
	{
		return CombatCalc.hitChanceFrom(ATTACK_ROLL, (defence + 9) * (DEFENCE_BONUS + 64));
	}

	// An ordinary attack at a state: mean and variance of an even roll 0..MAX behind the accuracy roll.
	static double[] attackAt(DrainRule.State s)
	{
		return roll(accuracy(s.defence), MAX);
	}

	static double[] roll(double p, int max)
	{
		final double mean = p * max / 2.0;
		final double m2 = p * max * (2.0 * max + 1) / 6.0;
		return new double[]{mean, m2 - mean * mean};
	}

	static DrainRule.Target boss(int defence, int floor)
	{
		return new DrainRule.Target(defence, 100, 0, 200, 200, 100, floor, 0, false, false, false, false, 0);
	}

	// The warhammer's outcomes at a state, from the same model.
	static List<SpecOutcomes.Outcome> hammerAt(DrainRule.State s)
	{
		final double p = accuracy(s.defence);
		final double[] f = roll(p, HAMMER_MAX);
		return SpecOutcomes.outcomes(SpecialWeapon.DRAGON_WARHAMMER, p, p, f[0], f[1], HAMMER_MAX, NONE);
	}

	// The godsword's: an even roll 0..max, a landed 0 pushed to 1, so the drain and the damage are the same number.
	static List<SpecOutcomes.Outcome> godswordAt(DrainRule.State s, int max)
	{
		final double p = accuracy(s.defence);
		double mean = 0;
		double m2 = 0;
		for (int roll = 0; roll <= max; roll++)
		{
			final int dealt = Math.max(1, roll);
			mean += dealt / (max + 1.0);
			m2 += dealt * (double) dealt / (max + 1.0);
		}
		return SpecOutcomes.outcomes(SpecialWeapon.BANDOS_GODSWORD, p, p, p * mean, p * m2 - p * mean * p * mean, max,
			NONE);
	}

	private static double booked(DrainRule.State s)
	{
		return attackAt(s)[0];
	}

	// ---- One hammer, in closed form ----

	@Test
	public void oneHammerHitOrMissInClosedForm()
	{
		final DrainRule.Target t = boss(200, 0);
		final DrainRule.State base = DrainRule.State.of(t);
		final DrainRule.State hit = DrainRule.apply(base, SpecialWeapon.DRAGON_WARHAMMER, 1, true, t).after;
		assertEquals(140, hit.defence);
		final double p = accuracy(200);
		final double[] hammer = roll(p, HAMMER_MAX);
		final int n = 20;

		for (boolean landed : new boolean[]{true, false})
		{
			final SpecOutcomes o = new SpecOutcomes(t, base);
			o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
			final DrainRule.State real = landed ? hit : base;
			for (int i = 0; i < n; i++)
			{
				o.attack(SpecOutcomesTest::attackAt, attackAt(real)[0], attackAt(real)[1]);
			}
			// E over the two branches.
			final double[] a1 = attackAt(hit);
			final double[] a0 = attackAt(base);
			final double hitMean = hammer[0] / p;
			final double hitVar = (hammer[1] + hammer[0] * hammer[0]) / p - hitMean * hitMean;
			final double e1 = hitMean + n * a1[0];
			final double e0 = n * a0[0];
			final double e = p * e1 + (1 - p) * e0;
			assertEquals(e, o.expected(), 1e-9);
			// V: the branches' own variance and the spread of their means.
			final double v = p * (hitVar + n * a1[1]) + (1 - p) * (n * a0[1]) + p * e1 * e1 + (1 - p) * e0 * e0 - e * e;
			assertEquals(v, o.variance(), 1e-6);
			assertTrue(o.branched());
			assertEquals(2, o.states());
			// The part of luck that came from the hammer: a landed one is good luck, a missed one bad.
			final double realized = hammer[0] + n * attackAt(real)[0];
			assertEquals(realized - e, o.specLuckDamage(), 1e-9);
			assertEquals(landed, o.specLuckDamage() > 0);
			// What the drain was worth: the attacks as made, less with nothing drained.
			assertEquals(n * (attackAt(real)[0] - a0[0]), o.drainValueDamage(), 1e-9);
			assertEquals(p * n * (a1[0] - a0[0]), o.drainValueExpected(), 1e-9);
		}
	}

	@Test
	public void twoHammers()
	{
		final DrainRule.Target t = boss(200, 0);
		final DrainRule.State base = DrainRule.State.of(t);
		final SpecOutcomes o = new SpecOutcomes(t, base);
		final double p0 = accuracy(200);
		final double[] h0 = roll(p0, HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, h0[0], h0[1], 0);
		// The second is booked as it was really thrown: after a landed first.
		final DrainRule.State one = DrainRule.apply(base, SpecialWeapon.DRAGON_WARHAMMER, 1, true, t).after;
		final double[] h1 = roll(accuracy(one.defence), HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, h1[0], h1[1], 0);
		o.attack(SpecOutcomesTest::attackAt, booked(one), attackAt(one)[1]);
		// 200 -> 140 -> 98; three states: neither, one (either order), both.
		assertEquals(3, o.states());
		final double q = accuracy(140);
		double e = 0;
		final DrainRule.State two = DrainRule.apply(one, SpecialWeapon.DRAGON_WARHAMMER, 1, true, t).after;
		assertEquals(98, two.defence);
		// Branch by branch: the second hammer's own chance and damage come from the state the first left.
		e += (1 - p0) * (1 - p0) * (0 + 0 + attackAt(base)[0]);
		e += (1 - p0) * p0 * (roll(p0, HAMMER_MAX)[0] / p0 + attackAt(one)[0]);
		e += p0 * (1 - q) * (h0[0] / p0 + attackAt(one)[0]);
		e += p0 * q * (h0[0] / p0 + roll(q, HAMMER_MAX)[0] / q + attackAt(two)[0]);
		assertEquals(e, o.expected(), 1e-9);
		assertEquals((1 - p0) * (1 - p0), o.probabilities().get(base), 1e-12);
		assertEquals(p0 * q, o.probabilities().get(two), 1e-12);
	}

	@Test
	public void aGodswordDrainsByItsDamage()
	{
		final DrainRule.Target t = boss(200, 0);
		final DrainRule.State base = DrainRule.State.of(t);
		final SpecOutcomes o = new SpecOutcomes(t, base);
		final int max = 20;
		final List<SpecOutcomes.Outcome> at = godswordAt(base, max);
		double mean = 0;
		double m2 = 0;
		for (SpecOutcomes.Outcome x : at)
		{
			mean += x.p * x.mean;
			m2 += x.p * x.m2;
		}
		o.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, max), mean, m2 - mean * mean, 0);
		// A miss leaves 200; a hit of d leaves 200 - d, d 1..20 (a rolled 0 lands as 1).
		assertEquals(21, o.states());
		final double p = accuracy(200);
		assertEquals(1 - p, o.probabilities().get(base), 1e-12);
		final DrainRule.State one = new DrainRule.State(199, 100, 0, 200, 200, 100);
		assertEquals(p * 2 / (max + 1.0), o.probabilities().get(one), 1e-12);
		// The special's own figures are its own: E and V of the godsword alone.
		assertEquals(mean, o.expected(), 1e-9);
		assertEquals(m2 - mean * mean, o.variance(), 1e-6);
		o.attack(SpecOutcomesTest::attackAt, booked(base), attackAt(base)[1]);
		double e = mean + (1 - p) * attackAt(base)[0];
		for (int d = 1; d <= max; d++)
		{
			e += p * (d == 1 ? 2 : 1) / (max + 1.0) * attackAt(new DrainRule.State(200 - d, 100, 0, 200, 200, 100))[0];
		}
		assertEquals(e, o.expected(), 1e-9);
	}

	@Test
	public void outcomesKeepTheSpecialsMeanAndSecondMoment()
	{
		for (SpecialWeapon w : new SpecialWeapon[]{SpecialWeapon.DRAGON_WARHAMMER, SpecialWeapon.BANDOS_GODSWORD,
			SpecialWeapon.TONALZTICS_OF_RALOS, SpecialWeapon.EMBERLIGHT, SpecialWeapon.SEERCULL})
		{
			// Consistent figures: a glaive's accuracy of 0.4 lands one of two 64% of the time.
			final double p = 0.64;
			final double mean = 21.4;
			final double variance = 600;
			final List<SpecOutcomes.Outcome> out = SpecOutcomes.outcomes(w, p, 0.4, mean, variance, 60, 45);
			double sp = 0;
			double sm = 0;
			double s2 = 0;
			for (SpecOutcomes.Outcome x : out)
			{
				sp += x.p;
				sm += x.p * x.mean;
				s2 += x.p * x.m2;
			}
			assertEquals(w.name(), 1, sp, 1e-12);
			assertEquals(w.name(), mean, sm, 1e-9);
			assertEquals(w.name(), variance + mean * mean, s2, 1e-6);
		}
	}

	// ---- What doesn't branch ----

	@Test
	public void aSpecialAtTheFloorNeitherBranchesNorAddsDrainValue()
	{
		// Drained to its floor before the fight: the hammer can take nothing more.
		final DrainRule.Target t = boss(200, 140);
		final DrainRule.State floor = new DrainRule.State(140, 100, 0, 200, 200, 100);
		final SpecOutcomes with = new SpecOutcomes(t, floor);
		final SpecOutcomes without = new SpecOutcomes(t, floor);
		final double[] hammer = roll(accuracy(140), HAMMER_MAX);
		with.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
		without.attack(null, hammer[0], hammer[1]);
		for (int i = 0; i < 10; i++)
		{
			with.attack(SpecOutcomesTest::attackAt, booked(floor), attackAt(floor)[1]);
			without.attack(SpecOutcomesTest::attackAt, booked(floor), attackAt(floor)[1]);
		}
		assertFalse(with.branched());
		assertEquals(1, with.states());
		assertEquals(0, with.expectedShift(), 0);
		assertEquals(0, with.varianceShift(), 0);
		assertEquals(0, with.specLuckDamage(), 0);
		// The drain value is the earlier drains', the same as without the hammer.
		// (the hammer's own expectation at the floor against at its base is part of it, as it is of every attack's).
		assertEquals(without.drainValueDamage() + (hammer[0] - roll(accuracy(200), HAMMER_MAX)[0]), with.drainValueDamage(),
			1e-9);
		assertEquals(with.drainValueDamage(), with.drainValueExpected(), 1e-12);

		// A floor at the base: nothing to drain, so no value at all.
		final DrainRule.Target locked = boss(200, 200);
		final SpecOutcomes o = new SpecOutcomes(locked, DrainRule.State.of(locked));
		final double[] atBase = roll(accuracy(200), HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, atBase[0], atBase[1], 0);
		o.attack(SpecOutcomesTest::attackAt, attackAt(DrainRule.State.of(locked))[0], 1);
		assertFalse(o.branched());
		assertEquals(0, o.drainValueDamage(), 1e-12);
	}

	@Test
	public void attacksAloneChangeNothing()
	{
		final DrainRule.Target t = boss(200, 0);
		final SpecOutcomes o = new SpecOutcomes(t, DrainRule.State.of(t));
		for (int i = 0; i < 50; i++)
		{
			o.attack(SpecOutcomesTest::attackAt, attackAt(DrainRule.State.of(t))[0], attackAt(DrainRule.State.of(t))[1]);
		}
		assertFalse(o.branched());
		assertEquals(0.0, o.expectedShift(), 0);
		assertEquals(0.0, o.varianceShift(), 0);
		assertEquals(0.0, o.drainValueDamage(), 1e-9);
	}

	@Test
	public void aPartyDrainAppliesToEveryStateAsItHappened()
	{
		final DrainRule.Target t = boss(200, 0);
		final DrainRule.State base = DrainRule.State.of(t);
		final SpecOutcomes o = new SpecOutcomes(t, base);
		final double[] hammer = roll(accuracy(200), HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
		final double before = o.probabilities().get(base);
		// A party member's godsword for 30, after the hammer: both states lose 30, their chances unchanged.
		o.drainAsHappened(SpecialWeapon.BANDOS_GODSWORD, 30, true, 1);
		assertEquals(2, o.states());
		assertEquals(before, o.probabilities().get(new DrainRule.State(170, 100, 0, 200, 200, 100)), 1e-12);
		assertTrue(o.probabilities().containsKey(new DrainRule.State(110, 100, 0, 200, 200, 100)));
		// Alone, it is not luck: no branch, no shift.
		final SpecOutcomes alone = new SpecOutcomes(t, base);
		alone.drainAsHappened(SpecialWeapon.BANDOS_GODSWORD, 30, true, 1);
		alone.attack(SpecOutcomesTest::attackAt, booked(new DrainRule.State(170, 100, 0, 200, 200, 100)), 50);
		assertFalse(alone.branched());
		assertEquals(0, alone.specLuckDamage(), 0);
		assertTrue(alone.drainValueDamage() > 0);
	}

	@Test
	public void resetsAndRegenerationApplyToEveryState()
	{
		final DrainRule.Target t = new DrainRule.Target(200, 100, 0, 200, 200, 100, 0, 0, false, false, false, false, 10);
		final DrainRule.State base = DrainRule.State.of(t);
		final SpecOutcomes o = new SpecOutcomes(t, base);
		final double[] hammer = roll(accuracy(200), HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 100);
		o.tick(109);
		assertTrue(o.probabilities().containsKey(new DrainRule.State(140, 100, 0, 200, 200, 100)));
		o.tick(110);
		assertTrue(o.probabilities().containsKey(new DrainRule.State(141, 100, 0, 200, 200, 100)));
		assertTrue(o.probabilities().containsKey(base));
		o.reset(t);
		assertEquals(1, o.states());
		// The outcomes still count: the reset only ends their effect on the attacks after it.
		assertTrue(o.branched());
	}

	// ---- Monte Carlo ----

	@Test
	public void monteCarloAgrees()
	{
		// A hammer, then a godsword, then 30 attacks, on a boss with a floor of 135: after a landed hammer (140) the godsword
		// meets it.
		final DrainRule.Target t = boss(200, 135);
		final DrainRule.State base = DrainRule.State.of(t);
		final int godswordMax = 12;
		final int attacks = 30;
		final SpecOutcomes o = new SpecOutcomes(t, base);
		final double[] hammer = roll(accuracy(200), HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
		o.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, godswordMax), 5, 30, 0);
		for (int i = 0; i < attacks; i++)
		{
			o.attack(SpecOutcomesTest::attackAt, 10, 100);
		}
		assertEquals(0, o.merges());

		final Random random = new Random(50);
		final int runs = 400_000;
		double sum = 0;
		double sum2 = 0;
		for (int run = 0; run < runs; run++)
		{
			DrainRule.State s = base;
			double damage = 0;
			// The hammer: its accuracy, then an even roll; a success drains, whatever it rolled (a landed 0 is a 1).
			if (random.nextDouble() < accuracy(s.defence))
			{
				damage += random.nextInt(HAMMER_MAX + 1);
				s = DrainRule.apply(s, SpecialWeapon.DRAGON_WARHAMMER, 1, true, t).after;
			}
			if (random.nextDouble() < accuracy(s.defence))
			{
				final int dealt = Math.max(1, random.nextInt(godswordMax + 1));
				damage += dealt;
				s = DrainRule.apply(s, SpecialWeapon.BANDOS_GODSWORD, dealt, true, t).after;
			}
			for (int i = 0; i < attacks; i++)
			{
				if (random.nextDouble() < accuracy(s.defence))
				{
					damage += random.nextInt(MAX + 1);
				}
			}
			sum += damage;
			sum2 += damage * damage;
		}
		final double mean = sum / runs;
		final double variance = sum2 / runs - mean * mean;
		// Standard errors: the mean's is sqrt(V / n), about 0.05 here; the variance's under 1%.
		assertEquals(mean, o.expected(), 0.5);
		assertEquals(variance, o.variance(), 0.02 * variance);
	}

	@Test
	public void theCapsCost()
	{
		// Two godswords with a 60 max and a hammer between them on a high-Defence boss: well over the cap.
		final DrainRule.Target t = boss(300, 0);
		final DrainRule.State base = DrainRule.State.of(t);
		final SpecOutcomes capped = new SpecOutcomes(t, base);
		final SpecOutcomes whole = new SpecOutcomes(t, base, 100_000);
		for (SpecOutcomes o : new SpecOutcomes[]{capped, whole})
		{
			o.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, 60), 25, 300, 0);
			final double[] hammer = roll(accuracy(250), HAMMER_MAX);
			o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
			o.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, 60), 25, 300, 0);
			for (int i = 0; i < 100; i++)
			{
				o.attack(SpecOutcomesTest::attackAt, 10, 100);
			}
		}
		assertEquals(SpecOutcomes.MAX_STATES, capped.states());
		assertTrue(whole.states() > SpecOutcomes.MAX_STATES);
		final double expectedError = Math.abs(capped.expected() - whole.expected()) / whole.expected();
		final double varianceError = Math.abs(capped.variance() - whole.variance()) / whole.variance();
		System.out.printf("T-050 cap: %d states kept of %d, %d merges, widest %d levels; E %.2f vs %.2f (%.4f%%), V %.1f vs"
				+ " %.1f (%.3f%%)%n", capped.states(), whole.states(), capped.merges(), capped.worstMerge(), capped.expected(),
			whole.expected(), 100 * expectedError, capped.variance(), whole.variance(), 100 * varianceError);
		assertTrue(expectedError < 0.001);
		assertTrue(varianceError < 0.01);
	}

	@Test
	public void whatItCostsAtTheCap()
	{
		// The outcomes' own work at a full 32 states (prints, never fails on time): an attack, and a godsword branching
		// every state over 61 damages. The figures themselves are CombatCalc's, one figuresAt per state a tick, timed in
		// game by PerfProbe's "spec outcomes (T-050)" slot.
		final DrainRule.Target t = boss(300, 0);
		final SpecOutcomes o = new SpecOutcomes(t, DrainRule.State.of(t));
		o.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, 60), 25, 300, 0);
		final double[] hammer = roll(accuracy(250), HAMMER_MAX);
		o.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
		assertEquals(SpecOutcomes.MAX_STATES, o.states());
		final PerfProbe.Timer attack = new PerfProbe.Timer(2000);
		final PerfProbe.Timer special = new PerfProbe.Timer(200);
		for (int i = 0; i < 2300; i++)
		{
			final long start = System.nanoTime();
			o.attack(SpecOutcomesTest::attackAt, 10, 100);
			if (i >= 300)
			{
				attack.add(System.nanoTime() - start);
			}
		}
		for (int i = 0; i < 300; i++)
		{
			final SpecOutcomes copy = new SpecOutcomes(t, DrainRule.State.of(t));
			copy.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, 60), 25, 300, 0);
			copy.special(SpecialWeapon.DRAGON_WARHAMMER, SpecOutcomesTest::hammerAt, hammer[0], hammer[1], 0);
			final long start = System.nanoTime();
			copy.special(SpecialWeapon.BANDOS_GODSWORD, s -> godswordAt(s, 60), 25, 300, 0);
			if (i >= 100)
			{
				special.add(System.nanoTime() - start);
			}
		}
		System.out.println("T-050 cost: " + PerfProbe.timerSummary("an attack at 32 states", attack) + "; "
			+ PerfProbe.timerSummary("a godsword branching 32 states", special));
	}
}

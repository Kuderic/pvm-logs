package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-028: each attack's damage moments, checked against the distribution
 * written out value by value (a PMF over 0..max), and their means against the
 * expected averages the plugin had before (the formulas as they stood on master
 * b9c39ec, restated here).
 */
public class DamageMomentsTest
{
	private static final double EPS = 1e-9;

	// ---- A PMF, the single source the closed forms are checked against. ----

	/** P(damage = d), d = 0..length-1. */
	private static double[] pmfRoll(int min, int max, int cap)
	{
		final double[] p = new double[Math.max(max, 0) + 1];
		if (max <= 0 || max < min)
		{
			p[0] = 1;
			return p;
		}
		final int lo = Math.max(0, min);
		for (int roll = lo; roll <= max; roll++)
		{
			p[Math.min(roll, cap)] += 1.0 / (max - lo + 1);
		}
		return p;
	}

	private static double[] landing(double[] roll, double chance)
	{
		final double[] p = new double[roll.length];
		for (int d = 0; d < roll.length; d++)
		{
			p[d] = chance * roll[d];
		}
		p[0] += 1 - chance;
		return p;
	}

	private static double[] convolve(double[] a, double[] b)
	{
		final double[] c = new double[a.length + b.length - 1];
		for (int i = 0; i < a.length; i++)
		{
			for (int j = 0; j < b.length; j++)
			{
				c[i + j] += a[i] * b[j];
			}
		}
		return c;
	}

	private static double[] cappedAt(double[] p, int cap)
	{
		final double[] q = new double[Math.min(p.length, cap + 1)];
		for (int d = 0; d < p.length; d++)
		{
			q[Math.min(d, cap)] += p[d];
		}
		return q;
	}

	private static double[] mix(double w, double[] a, double[] b)
	{
		final double[] c = new double[Math.max(a.length, b.length)];
		for (int d = 0; d < a.length; d++)
		{
			c[d] += w * a[d];
		}
		for (int d = 0; d < b.length; d++)
		{
			c[d] += (1 - w) * b[d];
		}
		return c;
	}

	private static double mean(double[] p)
	{
		double m = 0;
		for (int d = 0; d < p.length; d++)
		{
			m += d * p[d];
		}
		return m;
	}

	private static double variance(double[] p)
	{
		final double m = mean(p);
		double v = 0;
		for (int d = 0; d < p.length; d++)
		{
			v += (d - m) * (d - m) * p[d];
		}
		return v;
	}

	private static void assertMatches(String what, double[] pmf, DamageMoments moments)
	{
		assertEquals(what + " mean", mean(pmf), moments.mean, 1e-7);
		assertEquals(what + " variance", variance(pmf), moments.variance(), 1e-6);
	}

	// ---- Rolls ----

	@Test
	public void aCappedRollMatchesItsPmf()
	{
		for (int max : new int[]{1, 2, 7, 30, 61})
		{
			for (int cap : new int[]{0, 1, 5, 29, 61, Integer.MAX_VALUE})
			{
				for (int min : new int[]{0, 3, 9})
				{
					if (min > max)
					{
						continue;
					}
					final String what = min + ".." + max + " cap " + cap;
					assertMatches(what, pmfRoll(min, max, cap), DamageMoments.capped(min, max, cap));
					assertEquals(what, CombatCalc.cappedAverage(min, max, cap), DamageMoments.capped(min, max, cap).mean, 0);
				}
			}
		}
	}

	@Test
	public void theKnownCase()
	{
		// Hit chance p, uniform 0..M: Var = p·M(M+2)/12 + p(1−p)(M/2)².
		final double p = 0.62;
		final int m = 47;
		final DamageMoments attack = DamageMoments.uniform(m).landing(p);
		assertEquals(p * m * (m + 2) / 12.0 + p * (1 - p) * (m / 2.0) * (m / 2.0), attack.variance(), EPS);
		assertMatches("ordinary", landing(pmfRoll(0, m, Integer.MAX_VALUE), p), attack);
	}

	// ---- Attacks, against today's averages and their PMFs ----

	@Test
	public void anOrdinaryHit()
	{
		final double p = 0.72;
		final int max = 46;
		final DamageMoments m = CombatCalc.ordinaryMoments(max, p, 1, Integer.MAX_VALUE);
		assertEquals(p * (max / 2.0), m.mean, 0);
		assertMatches("ordinary", landing(pmfRoll(0, max, Integer.MAX_VALUE), p), m);
	}

	@Test
	public void theCappedLastHit()
	{
		// 12 left on the target: everything above collapses onto 12, as overkillCap has it.
		final double p = 0.8;
		final int max = 46;
		final int cap = 12;
		final DamageMoments m = CombatCalc.ordinaryMoments(max, p, 1, cap);
		assertEquals(p * CombatCalc.cappedAverage(max, cap), m.mean, 0);
		assertMatches("capped", landing(pmfRoll(0, max, cap), p), m);
		assertTrue(m.variance() < CombatCalc.ordinaryMoments(max, p, 1, Integer.MAX_VALUE).variance());
	}

	@Test
	public void aScytheSwing()
	{
		// Three hits on a 3x3, each its own accuracy and damage roll, the max halving rounded down: 51, 25, 12.
		final double p = 0.7;
		final DamageMoments m = CombatCalc.ordinaryMoments(51, p, 3, Integer.MAX_VALUE);
		assertEquals(p * (51 / 2.0) + p * (25 / 2.0) + p * (12 / 2.0), m.mean, 1e-12);
		final double[] pmf = convolve(convolve(landing(pmfRoll(0, 51, Integer.MAX_VALUE), p),
			landing(pmfRoll(0, 25, Integer.MAX_VALUE), p)), landing(pmfRoll(0, 12, Integer.MAX_VALUE), p));
		assertMatches("scythe", pmf, m);
		// Capped, each hitsplat is capped on its own, as the average has always had it.
		final DamageMoments capped = CombatCalc.ordinaryMoments(51, p, 3, 20);
		final double[] cappedPmf = convolve(convolve(landing(pmfRoll(0, 51, 20), p), landing(pmfRoll(0, 25, 20), p)),
			landing(pmfRoll(0, 12, 20), p));
		assertMatches("capped scythe", cappedPmf, capped);
	}

	@Test
	public void rubyBolts()
	{
		// A proc's fixed hit replaces the attack: a mixture of the two.
		final double proc = 0.066;
		final int ruby = 100;
		final double p = 0.75;
		final DamageMoments ordinary = CombatCalc.ordinaryMoments(48, p, 1, Integer.MAX_VALUE);
		final DamageMoments m = CombatCalc.rubyMoments(proc, ruby, ordinary);
		assertEquals(proc * ruby + (1.0 - proc) * ordinary.mean, m.mean, 0);
		final double[] fixed = new double[ruby + 1];
		fixed[ruby] = 1;
		assertMatches("ruby", mix(proc, fixed, landing(pmfRoll(0, 48, Integer.MAX_VALUE), p)), m);
	}

	@Test
	public void onyxBolts()
	{
		final double proc = 0.11;
		final DamageMoments ordinary = CombatCalc.ordinaryMoments(50, 0.7, 1, Integer.MAX_VALUE);
		final DamageMoments m = CombatCalc.onyxMoments(proc, ordinary);
		assertEquals(ordinary.mean * (1.0 + 0.20 * proc), m.mean, 0);
		assertEquals((1 - proc) * ordinary.second + proc * 1.44 * ordinary.second, m.second, 1e-9);
	}

	@Test
	public void aTwoHitSpecialRollsEachHitUnlessItSharesOne()
	{
		// A dragon dagger's two hits roll accuracy apart; an abyssal dagger's land together or not at all.
		final int[] maxima = {30, 30};
		final double p = 0.65;
		final DamageMoments apart = CombatCalc.specMoments(maxima, p, 0, 0, Integer.MAX_VALUE, false);
		final DamageMoments shared = CombatCalc.specMoments(maxima, p, 0, 0, Integer.MAX_VALUE, true);
		assertEquals(CombatCalc.specAverage(maxima, p, 0, Integer.MAX_VALUE), apart.mean, 0);
		assertEquals(apart.mean, shared.mean, 0);
		final double[] one = landing(pmfRoll(0, 30, Integer.MAX_VALUE), p);
		assertMatches("apart", convolve(one, one), apart);
		final double[] both = convolve(pmfRoll(0, 30, Integer.MAX_VALUE), pmfRoll(0, 30, Integer.MAX_VALUE));
		assertMatches("shared", landing(both, p), shared);
		assertTrue(shared.variance() > apart.variance());
	}

	@Test
	public void theClawsCascade()
	{
		final int max = 40;
		final double p = 0.6;
		final DamageMoments m = CombatCalc.clawsMoments(max, p, Integer.MAX_VALUE);
		// Today's average, as master worked it out: per outcome the sum of its hitsplats' averages.
		final double[][] shapes = {{1, 0.5, 0.25, 0.25}, {1, 0.5, 0.25}, {1, 0.5}, {1.25}};
		double expected = 0;
		double reached = 1;
		for (double[] shape : shapes)
		{
			double damage = 0;
			for (double share : shape)
			{
				damage += CombatCalc.cappedAverage(0, (int) (max * share), Integer.MAX_VALUE);
			}
			expected += reached * p * damage;
			reached *= 1 - p;
		}
		expected += reached * Math.min(1.0, Integer.MAX_VALUE);
		assertEquals(expected, m.mean, 1e-12);
		assertEquals(CombatCalc.clawsAverage(max, p, Integer.MAX_VALUE), m.mean, 0);
		// The hitsplats of one roll move together, so the spread is wider than if each rolled its own.
		double second = 0;
		reached = 1;
		for (double[] shape : shapes)
		{
			DamageMoments independent = DamageMoments.ZERO;
			for (double share : shape)
			{
				independent = independent.plus(DamageMoments.capped(0, (int) (max * share), Integer.MAX_VALUE));
			}
			second += reached * p * independent.second;
			reached *= 1 - p;
		}
		second += reached * 2.0;
		assertTrue(m.variance() > second - m.mean * m.mean);
		// Certain to land on the first roll: one outcome, whose four hitsplats' deviations add.
		final DamageMoments sure = CombatCalc.clawsMoments(max, 1.0, Integer.MAX_VALUE);
		final double sd = DamageMoments.capped(0, 40, Integer.MAX_VALUE).sd() + DamageMoments.capped(0, 20, Integer.MAX_VALUE).sd()
			+ 2 * DamageMoments.capped(0, 10, Integer.MAX_VALUE).sd();
		assertEquals(sd * sd, sure.variance(), 1e-9);
	}

	@Test
	public void burningClawsAndTheKisten()
	{
		assertEquals(CombatCalc.burningClawsAverage(44, 0.55, Integer.MAX_VALUE),
			CombatCalc.burningClawsMoments(44, 0.55, Integer.MAX_VALUE).mean, 0);
		assertTrue(CombatCalc.burningClawsMoments(44, 0.55, Integer.MAX_VALUE).variance() > 0);
		// Every roll misses: 0, 1 or 2 at 20/40/40, which is mean 1.2 and variance 2 - 1.44.
		assertEquals(0.56, CombatCalc.burningClawsMoments(44, 0, Integer.MAX_VALUE).variance(), 1e-12);
		assertEquals(1.0, CombatCalc.clawsMoments(44, 0, Integer.MAX_VALUE).variance(), 1e-12);
		assertEquals(CombatCalc.crimsonKistenAverage(40, 0.5, Integer.MAX_VALUE),
			CombatCalc.crimsonKistenMoments(40, 0.5, Integer.MAX_VALUE).mean, 0);
		assertTrue(CombatCalc.crimsonKistenMoments(40, 0.5, Integer.MAX_VALUE).variance() > 0);
	}

	// ---- Magic (T-026's formula) ----

	private static MagicDamage spell(int base, double percent)
	{
		final MagicDamage m = new MagicDamage();
		m.base = base;
		m.percent = percent;
		return m;
	}

	@Test
	public void aMagicHitsMomentsAreItsWalks()
	{
		// Sunfire's minimum: rolls 2..30 alike.
		final MagicDamage sunfire = spell(24, 20);
		sunfire.tomePercent = 10;
		sunfire.tenthMinimum = true;
		final DamageMoments onHit = sunfire.onHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE);
		assertEquals(sunfire.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), onHit.mean, 0);
		assertMatches("sunfire", pmfRoll(2, 30, Integer.MAX_VALUE), onHit);
		// Landing with the accuracy roll in front: the known case's shape.
		assertMatches("sunfire landing", landing(pmfRoll(2, 30, Integer.MAX_VALUE), 0.6), onHit.landing(0.6));
		// Under a cap, as overkillCap holds the attack.
		assertMatches("sunfire capped", pmfRoll(2, 30, 12), sunfire.onHit(1.0, Integer.MAX_VALUE, 12));
	}

	@Test
	public void twinflamesSecondHitMovesWithTheFirst()
	{
		// Fire Wave 20 at +10%: rolls 0..22, each with its 40% second splat. One value per roll, written out.
		final MagicDamage m = spell(20, 10);
		m.twinflame = true;
		final double[] pmf = new double[31];
		for (int rolled = 0; rolled <= 22; rolled++)
		{
			pmf[rolled + rolled * 40 / 100] += 1.0 / 23;
		}
		assertMatches("twinflame", pmf, m.onHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	@Test
	public void ahrimsIsAMixtureOnTheRoll()
	{
		final MagicDamage m = spell(20, 0);
		m.ahrims = true;
		final double[] pmf = new double[27];
		for (int rolled = 0; rolled <= 20; rolled++)
		{
			pmf[rolled] += 0.75 / 21;
			pmf[rolled * 130 / 100] += 0.25 / 21;
		}
		final DamageMoments onHit = m.onHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE);
		assertEquals(m.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), onHit.mean, 0);
		assertMatches("ahrims", pmf, onHit);
	}

	@Test
	public void theMarkWithThePurgingStaff()
	{
		// Every roll 0..33 with half of it added: the walk's values, alike.
		final MagicDamage m = spell(30, 10);
		m.markPercent = 50;
		final double[] pmf = new double[50];
		for (int rolled = 0; rolled <= 33; rolled++)
		{
			pmf[m.afterMark(rolled)] += 1.0 / 34;
		}
		assertMatches("mark", pmf, m.onHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	// ---- The percentile ----

	@Test
	public void thePercentile()
	{
		assertEquals(0.5, DamageMoments.phi(0), 1e-7);
		assertEquals(0.975, DamageMoments.phi(1.959964), 1e-6);
		assertEquals(0.025, DamageMoments.phi(-1.959964), 1e-6);
		assertEquals(0.8413447, DamageMoments.phi(1), 1e-6);
		// 100 below expectation over a spread of 50 is two deviations down.
		assertEquals(DamageMoments.phi(-2), DamageMoments.percentile(900, 1000, 2500), 0);
		assertEquals(-1, DamageMoments.percentile(900, 1000, -1), 0);
		assertEquals(-1, DamageMoments.percentile(900, 1000, 0), 0);
	}
}

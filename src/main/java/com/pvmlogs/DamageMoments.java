package com.pvmlogs;

/**
 * The mean and second moment of one attack's damage (T-028), built beside the
 * expected average from the same distribution, so the variance a luck
 * percentile divides by can never describe a different attack from the average
 * it is measured against.
 *
 * <p>Each piece mirrors one of {@link CombatCalc}'s average functions: a roll
 * from a floor to a max, capped, like {@code cappedAverage}; an accuracy roll in
 * front of it; independent hitsplats summed (a scythe); hitsplats split from one
 * roll, which move together (the claws); and mixtures of outcomes (bolt procs,
 * the claws' cascade, the kisten's successes). The mean is worked out with the
 * same arithmetic as the average it mirrors, so the two agree to the last bit.
 *
 * <p>Immutable. {@code mean} and {@code second} are E[X] and E[X²].
 */
final class DamageMoments
{
	static final DamageMoments ZERO = new DamageMoments(0, 0);

	final double mean;
	final double second;

	DamageMoments(double mean, double second)
	{
		this.mean = mean;
		this.second = second;
	}

	/** Var[X], never below zero: a rounding hair under it is a constant. */
	double variance()
	{
		return Math.max(0, second - mean * mean);
	}

	double sd()
	{
		return Math.sqrt(variance());
	}

	/** One value, always: a ruby bolt's proc, a cap's collapse. */
	static DamageMoments fixed(double value)
	{
		return new DamageMoments(value, value * value);
	}

	/**
	 * A roll from 0 to {@code max} inclusive, every whole value alike, uncapped.
	 * The max may carry a fraction (a chance-based damage bonus folded in as its
	 * average); the forms are the discrete ones and agree with them exactly at a
	 * whole max: mean max/2, E[X²] = max(2max+1)/6.
	 */
	static DamageMoments uniform(double max)
	{
		if (max <= 0)
		{
			return ZERO;
		}
		return new DamageMoments(max / 2.0, max * (2 * max + 1) / 6.0);
	}

	/** {@link CombatCalc#cappedAverage(int, int)}'s roll: 0 to trueMax, everything above the cap landing on it. */
	static DamageMoments capped(int trueMax, int cap)
	{
		return capped(0, trueMax, cap);
	}

	/** {@link CombatCalc#cappedAverage(int, int, int)}'s roll: min to trueMax, capped. */
	static DamageMoments capped(int min, int trueMax, int cap)
	{
		if (trueMax <= 0 || trueMax < min)
		{
			return ZERO;
		}
		final double mean = CombatCalc.cappedAverage(min, trueMax, cap);
		final int lo = Math.max(0, min);
		final double squares = cappedSquares(trueMax, cap) - (lo == 0 ? 0 : cappedSquares(lo - 1, cap));
		return new DamageMoments(mean, squares / (trueMax - lo + 1));
	}

	// The sum over every roll from 0 to max of min(roll, cap)².
	private static double cappedSquares(int max, int cap)
	{
		if (max <= 0)
		{
			return 0;
		}
		final long c = Math.max(0, Math.min(cap, max));
		final double below = c * (c + 1) * (2 * c + 1) / 6.0;
		return below + (double) (max - c) * c * c;
	}

	/** Landed with this chance, else nothing: one accuracy roll in front of the damage. */
	DamageMoments landing(double chance)
	{
		return new DamageMoments(chance * mean, chance * second);
	}

	/** This and an independent other, summed: two hitsplats with their own rolls. */
	DamageMoments plus(DamageMoments other)
	{
		final double m = mean + other.mean;
		return new DamageMoments(m, variance() + other.variance() + m * m);
	}

	/**
	 * Hitsplats split from one roll, which rise and fall together: their
	 * standard deviations add rather than their variances. Exact for shares of a
	 * continuous roll; with a cap or the rounding, the most they can covary.
	 */
	static DamageMoments together(DamageMoments[] parts)
	{
		double m = 0;
		double sd = 0;
		for (DamageMoments part : parts)
		{
			m += part.mean;
			sd += part.sd();
		}
		return new DamageMoments(m, sd * sd + m * m);
	}

	/** This with weight w, for building a mixture: the outcomes' weights sum to one. */
	DamageMoments weighted(double w)
	{
		return new DamageMoments(w * mean, w * second);
	}

	/** Two weighted parts of one mixture, added (not independent: only one of them happens). */
	DamageMoments or(DamageMoments otherWeighted)
	{
		return new DamageMoments(mean + otherWeighted.mean, second + otherWeighted.second);
	}

	/** Every value scaled by k, as an onyx proc's 20% would. */
	DamageMoments times(double k)
	{
		return new DamageMoments(k * mean, k * k * second);
	}

	/**
	 * The share of results like these that would have dealt less than
	 * {@code actual}: Φ((actual − expected) / √variance), the metrics spec's
	 * normal form (§1.4). -1 when the variance is unknown or nothing.
	 */
	static double percentile(double actual, double expected, double variance)
	{
		final double z = z(actual, expected, variance);
		return Double.isNaN(z) ? -1 : phi(z);
	}

	/**
	 * Below this a variance is none (T-103). A fight of guaranteed max hits
	 * (T-098) has none at all, but the sums leave a floating-point remainder: in
	 * Eric's 22:32 log one read about 2e-12, and A 55 against E 58 came out at
	 * z −1,989,533. Remainders like that sit around 1e-12; the smallest real
	 * variance of an attack that can roll anything is far above the floor (a max
	 * hit of 1 that lands 99% of the time has 0.25 per landed roll), so 1e-6
	 * keeps six orders of magnitude from each.
	 */
	static final double VARIANCE_FLOOR = 1e-6;

	/** (actual − expected) / √variance, or NaN when the variance is unknown or under {@link #VARIANCE_FLOOR}. */
	static double z(double actual, double expected, double variance)
	{
		if (!(variance > VARIANCE_FLOOR) || expected < 0)
		{
			return Double.NaN;
		}
		return (actual - expected) / Math.sqrt(variance);
	}

	/**
	 * The standard normal CDF, by Abramowitz and Stegun 7.1.26 for erf (error
	 * under 1.5e-7): far finer than a percentile shown in whole points needs.
	 */
	static double phi(double z)
	{
		final double x = Math.abs(z) / Math.sqrt(2);
		final double t = 1 / (1 + 0.3275911 * x);
		final double erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741
			+ t * (-1.453152027 + t * 1.061405429)))) * Math.exp(-x * x);
		return z >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
	}

	@Override
	public String toString()
	{
		return String.format("mean %.2f, var %.2f", mean, variance());
	}
}

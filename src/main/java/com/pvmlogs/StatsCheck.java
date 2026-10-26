package com.pvmlogs;

/**
 * The fallback for a boss the plugin does not model correctly: notice from the
 * fight itself that the expected figures cannot be right.
 *
 * <p>Each attack lands a non-zero splat with the probability the model expected
 * (see {@link Fight#landedChance}), so the number landed over a fight has mean
 * {@code sum(p)} and variance {@code sum(p(1-p))}. Bad luck moves the count a couple
 * of standard deviations; wrong target stats move it a dozen. The Vardorvis kill
 * that showed this landed 26 of 42 attacks against about 40 expected, more than ten
 * standard deviations short, because his defence was read as 0.
 *
 * <p>When it trips, the fight keeps every number but carries a warning that its
 * expected side is suspect, which the overlay, the CSV and the log all show. It
 * says nothing about which stat is wrong; it says "do not believe the luck figure
 * for this boss until someone looks".
 */
final class StatsCheck
{
	/** Fewer attacks than this cannot tell a stats problem from a bad trip. */
	static final int MIN_ATTACKS = 15;
	/** Four standard deviations: about one fight in 15,000 by luck alone. */
	static final double Z_LIMIT = 4.0;

	private StatsCheck()
	{
	}

	/** Standard deviations between landed and expected, or NaN when there is too little to judge. */
	static double zScore(int landed, double sumExpected, double sumVariance, int attacks)
	{
		if (attacks < MIN_ATTACKS || sumVariance <= 0)
		{
			return Double.NaN;
		}
		// A floor on the variance: an expected accuracy of 99.9% on every attack makes it vanish, and the test would then
		// fire on a single miss. One attack's worth of doubt keeps it honest.
		final double variance = Math.max(sumVariance, 0.25);
		return (landed - sumExpected) / Math.sqrt(variance);
	}

	/** The verdict on a fight so far: its hits against the landed count its attacks were expected to make. */
	static String accuracyVerdict(Fight fight)
	{
		return accuracyVerdict(fight.getHits(), fight.getSumExpectedLanded(), fight.getSumExpectedLandedVariance(),
			fight.getExpectedTargetSamples());
	}

	/** A warning for a fight whose landed attacks are too far from expected to be luck, or null. */
	static String accuracyVerdict(int landed, double sumExpected, double sumVariance, int attacks)
	{
		final double z = zScore(landed, sumExpected, sumVariance, attacks);
		if (Double.isNaN(z) || Math.abs(z) < Z_LIMIT)
		{
			return null;
		}
		return String.format("landed %d of %d attacks against %.1f expected (z = %.1f): this boss's stats look wrong, "
			+ "so its expected damage and luck are not reliable", landed, attacks, sumExpected, z);
	}
}

package com.pvmlogs;

/**
 * T-103: an attack's expected damage is capped at the hitpoints left when it
 * lands, not when it was thrown. Its figures come from the throw's row (T-065,
 * T-086: the loadout, stats, defence and modifiers it was thrown with), and so
 * did the cap, at what was left then; a thrall's, another player's or a DoT
 * splat in between leaves less. Eric's 22:32 log, [513]-[516]: a crossbow shot
 * thrown at a Mirrorback araxyte with 29 left, a thrall's 3 landing first, and
 * the shot's guaranteed max (T-098) dealing the 26 left against an expected 29.
 *
 * <p>Re-capped from the figures the row gave rather than worked out again: the
 * calculator's state at the throw is gone by the landing. A certain hit (no
 * variance: a guaranteed max) is simply the less of its expectation and what
 * is left. Any other is moved by the difference the two caps make to one
 * uniform roll from 0 to its max that lands at its chance, which is exact for
 * an ordinary single roll and an approximation for a special, a multi-hit or a
 * magic walk - on a killing blow only, where the cap bites.
 *
 * <p>Pure.
 */
final class LandingCap
{
	private LandingCap()
	{
	}

	/**
	 * The attack's expected mean and variance capped at {@code capNow}, the
	 * hitpoints left just before it landed, from figures capped at
	 * {@code capThen}, those left when it was thrown; null when nothing changes:
	 * either is unknown (0 or less), or no less is left now.
	 */
	static double[] recap(int maxHit, double chances, double mean, double variance, int capThen, int capNow)
	{
		if (capThen <= 0 || capNow <= 0 || capNow >= capThen || mean < 0)
		{
			return null;
		}
		if (!(variance > DamageMoments.VARIANCE_FLOOR) || maxHit <= 0 || chances < 0)
		{
			return mean <= capNow ? null : new double[]{capNow, Math.max(0, variance)};
		}
		final DamageMoments then = DamageMoments.capped(maxHit, capThen).landing(chances);
		final DamageMoments now = DamageMoments.capped(maxHit, capNow).landing(chances);
		final double m = Math.max(0, Math.min(capNow, mean + now.mean - then.mean));
		final double v = Math.max(0, variance + now.variance() - then.variance());
		return new double[]{m, v};
	}
}

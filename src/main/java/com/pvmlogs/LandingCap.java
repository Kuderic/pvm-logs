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
	 * {@code capNow} is unknown (less than 0), {@code capThen} is 0, or no less is
	 * left now (unless the mean is above what is left, which no cap at it
	 * allows). {@code capNow} 0, nothing left, caps it at nothing (T-115).
	 * T-153: {@code capThen} unknown (less than 0) is figures read with no cap at
	 * all - the target not counted yet, or its count run out - which {@code capNow}
	 * still caps: a shot thrown before its fight opened, at a 4-hitpoint Baboon
	 * Thrall, was expected to deal 17.2 ([32428] of Eric's raid of 2026-11-02).
	 */
	static double[] recap(int maxHit, double chances, double mean, double variance, int capThen, int capNow)
	{
		if (mean < 0)
		{
			return null;
		}
		if (capNow == 0)
		{
			// Nothing left (T-115): dead before it landed, so it can deal nothing, whatever its figures were read at.
			return mean > 0 || variance > 0 ? new double[]{0, 0} : null;
		}
		if (capNow < 0)
		{
			return null;
		}
		// T-153: figures expecting more than is left weren't capped at it, whatever capThen says: a fight's opening blow is
		// scored from the calculator's own figures, which a Baboon Thrall's 4 hadn't capped ([32383]: 10.97 on 4 left).
		if (capThen < 0 || capThen > 0 && mean > capNow && capNow >= capThen)
		{
			if (maxHit > 0 && maxHit <= capNow && mean <= capNow)
			{
				return null;
			}
			capThen = Math.max(maxHit, capNow + 1);
		}
		if (capThen == 0 || capNow >= capThen)
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

	/**
	 * T-158: figures read with a cap of {@code capThen} - another npc's hitpoints, not the target's - taken back to what
	 * they are with none, for {@link #recap} to cap at what the target had as the attack landed. Unchanged where they
	 * weren't capped below the max ({@code capThen} unknown, 0 or at least {@code maxHit}: the calculator reads a count
	 * run out as no cap) or where the attack is certain (no variance to move).
	 */
	static double[] uncapped(int maxHit, double chances, double mean, double variance, int capThen)
	{
		if (mean < 0 || maxHit <= 0 || capThen <= 0 || capThen >= maxHit || chances < 0
			|| !(variance > DamageMoments.VARIANCE_FLOOR))
		{
			return new double[]{mean, variance};
		}
		final DamageMoments then = DamageMoments.capped(maxHit, capThen).landing(chances);
		final DamageMoments none = DamageMoments.capped(maxHit, maxHit).landing(chances);
		return new double[]{Math.max(0, mean + none.mean - then.mean),
			Math.max(0, variance + none.variance() - then.variance())};
	}
}

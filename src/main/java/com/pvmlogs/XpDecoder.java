package com.pvmlogs;

/**
 * Works out how much damage one attack dealt from the experience it paid.
 *
 * <p>The game pays combat experience per point of damage, at a rate the style
 * decides, scaled by the target's PvM bonus. Melee and ranged on a single skill
 * pay 4 a point, magic 2 a point plus the spell's base, shared melee 1.33 to
 * each of attack, strength and defence, long range 2 and 2, defensive casting 1
 * to defence; hitpoints is always 1.33. Hitpoints alone is ambiguous at small
 * hits - the client sees whole experience, so +2 is a one or a two - but the
 * combat skill pins it: at 4 a point consecutive damages are four apart, and
 * rounding moves a drop by less than one.
 *
 * <p>Which skills moved says which split was paid, so the style never has to be
 * read off the weapon. Magic's own skill is not used: its base experience
 * depends on the spell, and hitpoints (with defence, when casting defensively)
 * already settles the damage.
 *
 * <p>Thrall damage pays no experience at all, which is what makes this a witness
 * for whose hitsplat is whose - see issue #1.
 */
final class XpDecoder
{
	/** A skill that did not report this tick, as opposed to one that reported nothing gained. */
	static final int UNKNOWN = -1;

	// The client is sent whole experience while the server keeps tenths, so a drop reads up to just under one point
	// over the exact award, or one under it once the award's own tenths are floored. A little slack each way.
	private static final double SLACK_UNDER = 1.1;
	private static final double SLACK_OVER = 1.0;

	// Past anything one attack can deal, including a full claw special on a raid boss.
	static final int MAX_DAMAGE = 400;

	private static final double SHARED = 4.0 / 3.0;

	private XpDecoder()
	{
	}

	/**
	 * The range of damage one attack could have dealt and paid exactly these
	 * drops, as {@code {lowest, highest}}; null when no damage fits them, which
	 * means the multiplier is wrong or the drops are not one attack's.
	 *
	 * <p>Each argument is that skill's gain this tick, 0 if it reported none and
	 * {@link #UNKNOWN} if it could not report (a skill at 200m with no fake drop).
	 */
	static int[] damageRange(int attack, int strength, int defence, int ranged, int magic, int hitpoints,
		double multiplier)
	{
		final double m = multiplier > 0 ? multiplier : 1.0;
		// The constraints in force: a gain and the experience it pays per point of damage.
		final double[] rate = new double[3];
		final int[] gain = new int[3];
		int n = 0;

		final boolean meleeMoved = attack > 0 || strength > 0;
		if (magic > 0)
		{
			// Defensive casting pays defence 1 a point; magic itself carries the spell's base and is left out.
			if (defence > 0)
			{
				rate[n] = 1.0;
				gain[n++] = defence;
			}
		}
		else if (ranged > 0)
		{
			if (defence > 0)
			{
				rate[n] = 2.0;
				gain[n++] = ranged;
				rate[n] = 2.0;
				gain[n++] = defence;
			}
			else
			{
				rate[n] = 4.0;
				gain[n++] = ranged;
			}
		}
		else if (meleeMoved || defence > 0)
		{
			final int moved = (attack > 0 ? 1 : 0) + (strength > 0 ? 1 : 0) + (defence > 0 ? 1 : 0);
			if (moved == 3)
			{
				rate[n] = SHARED;
				gain[n++] = attack;
				rate[n] = SHARED;
				gain[n++] = strength;
				rate[n] = SHARED;
				gain[n++] = defence;
			}
			else if (moved == 1)
			{
				rate[n] = 4.0;
				gain[n++] = Math.max(attack, Math.max(strength, defence));
			}
			// Two of the three is no style the game has; let hitpoints decide alone.
		}

		final boolean useHitpoints = hitpoints != UNKNOWN;
		final boolean anyMoved = attack > 0 || strength > 0 || defence > 0 || ranged > 0 || magic > 0 || hitpoints > 0;
		if (!anyMoved || (n == 0 && !useHitpoints))
		{
			// Either no attack paid anything this tick, or nothing that pays per point of damage could report. A cast
			// that moved magic and not hitpoints falls through: that is a splash, and the loop finds exactly zero.
			return null;
		}

		int lowest = -1;
		int highest = -1;
		for (int d = 0; d <= MAX_DAMAGE; d++)
		{
			boolean fits = !useHitpoints || within(hitpoints, SHARED * d * m);
			for (int i = 0; fits && i < n; i++)
			{
				fits = within(gain[i], rate[i] * d * m);
			}
			if (fits)
			{
				if (lowest < 0)
				{
					lowest = d;
				}
				highest = d;
			}
		}
		return lowest < 0 ? null : new int[]{lowest, highest};
	}

	private static boolean within(int observed, double exact)
	{
		return observed >= exact - SLACK_UNDER && observed <= exact + SLACK_OVER;
	}

	/**
	 * The PvM experience bonus, from the wiki's formula:
	 * {@code 1 + floor(39 * avgLevel * (avgDefBonus + strBonus + atkBonus) / 200000) / 40},
	 * where avgLevel averages attack, strength, defence and hitpoints (capped at
	 * 2000) and avgDefBonus the stab, slash and crush defences, both floored.
	 * Checked against the wiki's listed bonuses: General Graardor +32.5%,
	 * Corporeal Beast +55%.
	 */
	static double bonusMultiplier(int attackLevel, int strengthLevel, int defenceLevel, int hitpoints,
		int defStab, int defSlash, int defCrush, int attackBonus, int strengthBonus)
	{
		final long averageLevel = Math.floorDiv(
			(long) attackLevel + strengthLevel + defenceLevel + Math.min(hitpoints, 2000), 4);
		final long averageDefence = Math.floorDiv((long) defStab + defSlash + defCrush, 3);
		final long steps = Math.floorDiv(39L * averageLevel * (averageDefence + strengthBonus + attackBonus), 200_000L);
		return 1.0 + Math.max(0, steps) / 40.0;
	}

	/**
	 * Monsters whose bonus the game sets by hand rather than by the formula. The
	 * wiki names the Great Olm: no bonus despite its stats.
	 */
	static double overrideFor(String name)
	{
		if (name != null && name.startsWith("Great Olm"))
		{
			return 1.0;
		}
		return -1;
	}
}

package com.pvmlogs;

/**
 * A magic attack's damage, step by step as the wiki's Maximum magic hit page
 * gives it (read 2026-10-27), with a floor after every step:
 *
 * <pre>
 * BaseDamageModifier = floor(BaseMax + ChaosGauntlets + Charge)
 * PrimaryMagicDamage = floor(BDM x (1 + magic damage % + void + salve + smoke/twinflame + virtus + prayer))
 *                      + floor(BDM x ElementalWeakness)
 * PreHitRoll         = floor(floor(floor(PMD x (1 + Slayer)) x others) x (1 + Tome))
 * HitRoll            = an integer from the minimum to PreHitRoll, on a successful hit
 * PostHitRoll        = floor((HitRoll + floor(floor(HitRoll x Mark) x DemonbaneEff)) x (1 + Ahrim's))
 * </pre>
 *
 * <p>"Others" are the multipliers the page does not place (the dragon hunter
 * wand, the revenant sceptres in the Wilderness), kept where the page puts the
 * sceptre: after the slayer helm, before the tome.
 *
 * <p>The minimum is sunfire runes' or a searing-paged tome of fire's: 10% of the
 * max before the tome, "The +10% damage bonus from the tome of fire is not
 * included when calculating the minimum hit" (wiki, Tome of Fire).
 *
 * <p>Mark of Darkness and Ahrim's act on the ROLLED hit, so the average is not
 * the max over two: every roll from the minimum to the max is walked, and
 * the expected damage is their mean. The twinflame staff's second spell is 40%
 * of what the first dealt, rounded down, landing a tick later (wiki, Twinflame
 * staff); both splats count towards the attack.
 *
 * <p>Left out, as everywhere else in the plugin: the page's rolled 0 that a
 * successful hit turns into a 1.
 *
 * <p>Pure arithmetic, so it is tested on its own; CombatCalc fills it in from
 * the gear, the spell and the target.
 */
final class MagicDamage
{
	/** The spell's or the powered staff's base max hit. */
	int base;
	/** Added to the base: chaos gauntlets on a bolt spell (+3), Charge on a god spell (+10). */
	int flat;
	/** The additive magic damage %: worn gear (shadow-scaled and capped), void, salve, smoke or twinflame staff, virtus, prayer. */
	double percent;
	/** Elemental weakness, in percent of the base damage modifier. */
	int weaknessPercent;
	/** The slayer helm (i) or black mask (i) on task: 15, or 0. */
	int slayerPercent;
	/** Any other damage multiplier on the max (dragon hunter wand, revenant weapons, leafy targets), 1 for none. */
	double others = 1.0;
	/** A charged tome on its own element's spells: 10, or 0. */
	int tomePercent;
	/** Sunfire runes or a searing-paged tome of fire: a successful hit deals at least a tenth of the max before the tome. */
	boolean tenthMinimum;
	/** Mark of Darkness on a demonbane spell: 25, or 50 with a purging staff; 0 without the Mark. */
	int markPercent;
	/** The target's demonbane effectiveness, in percent: 100 for most demons. */
	int effectivenessPercent = 100;
	/** Full Ahrim's with the amulet of the damned: a 25% chance of 30% more on the rolled hit. */
	boolean ahrims;
	/** The twinflame staff on a bolt, blast or wave: a second hit of 40% of the first. */
	boolean twinflame;

	private static final double EPSILON = 1e-9;

	int baseDamageModifier()
	{
		return Math.max(0, base + flat);
	}

	int primaryMagicDamage()
	{
		final int bdm = baseDamageModifier();
		return (int) Math.floor(bdm * (100.0 + percent) / 100.0 + EPSILON)
			+ (int) Math.floor(bdm * weaknessPercent / 100.0 + EPSILON);
	}

	/** The max before the tome, which the minimum hit is a tenth of. */
	int beforeTome()
	{
		final int slayered = primaryMagicDamage() * (100 + slayerPercent) / 100;
		return (int) Math.floor(slayered * others + EPSILON);
	}

	/** The top of the roll. */
	int preHitRoll()
	{
		return beforeTome() * (100 + tomePercent) / 100;
	}

	/** The bottom of a successful hit's roll. */
	int minHit()
	{
		return tenthMinimum ? Math.min(preHitRoll(), beforeTome() / 10) : 0;
	}

	/** What a rolled hit becomes once Mark of Darkness has added to it. */
	int afterMark(int rolled)
	{
		if (markPercent <= 0)
		{
			return rolled;
		}
		final int added = rolled * markPercent / 100;
		return rolled + added * effectivenessPercent / 100;
	}

	private static int ahrimsProc(int damage)
	{
		return damage * 130 / 100;
	}

	private static int twinflameSecond(int first)
	{
		return first * 40 / 100;
	}

	/** The most the first splat can deal, Ahrim's proc included. */
	int maxHit()
	{
		final int top = afterMark(preHitRoll());
		return ahrims ? ahrimsProc(top) : top;
	}

	/** The most the twinflame staff's second splat can deal, or 0 without one. */
	int secondMaxHit()
	{
		return twinflame ? twinflameSecond(maxHit()) : 0;
	}

	/** The most the whole attack can deal. */
	int attackMaxHit()
	{
		return maxHit() + secondMaxHit();
	}

	/**
	 * What a successful hit deals on average: every roll from the minimum to the
	 * max, each through the Mark and Ahrim's, each splat scaled by the target's
	 * mitigation and held to the per-splat cap, the attack held to what the
	 * target has left.
	 *
	 * @param mitigation the target's share of each splat, 1 for most
	 * @param splatCap   the most one splat may deal (Verzik's first phase), or Integer.MAX_VALUE
	 * @param attackCap  the most the attack may deal (what the target has left), or Integer.MAX_VALUE
	 */
	double expectedOnHit(double mitigation, int splatCap, int attackCap)
	{
		final int top = preHitRoll();
		final int bottom = minHit();
		if (top <= 0)
		{
			return 0;
		}
		double total = 0;
		for (int rolled = bottom; rolled <= top; rolled++)
		{
			final int marked = afterMark(rolled);
			if (ahrims)
			{
				total += 0.75 * landed(marked, mitigation, splatCap, attackCap)
					+ 0.25 * landed(ahrimsProc(marked), mitigation, splatCap, attackCap);
			}
			else
			{
				total += landed(marked, mitigation, splatCap, attackCap);
			}
		}
		return total / (top - bottom + 1);
	}

	private double landed(int first, double mitigation, int splatCap, int attackCap)
	{
		final int one = Math.min(splatCap, mitigated(first, mitigation));
		final int two = twinflame ? Math.min(splatCap, mitigated(twinflameSecond(first), mitigation)) : 0;
		return Math.min(attackCap, (long) one + two);
	}

	private static int mitigated(int damage, double mitigation)
	{
		return mitigation == 1.0 ? damage : (int) (damage * mitigation);
	}

	/** One line on how the max was reached, for the debug log. */
	String describe()
	{
		final StringBuilder line = new StringBuilder();
		line.append("base ").append(base);
		if (flat != 0)
		{
			line.append(String.format(" %+d", flat));
		}
		line.append(String.format(", magic dmg %+.1f%%", percent));
		if (weaknessPercent > 0)
		{
			line.append(", weakness +").append(weaknessPercent).append('%');
		}
		line.append(" -> ").append(primaryMagicDamage());
		if (slayerPercent > 0)
		{
			line.append(", slayer +").append(slayerPercent).append('%');
		}
		if (others != 1.0)
		{
			line.append(String.format(", other x%.3f", others));
		}
		if (tomePercent > 0)
		{
			line.append(", tome +").append(tomePercent).append('%');
		}
		line.append(" -> roll ").append(minHit()).append('-').append(preHitRoll());
		if (markPercent > 0)
		{
			line.append(", mark +").append(markPercent).append('%');
			if (effectivenessPercent != 100)
			{
				line.append(" x ").append(effectivenessPercent).append("% effective");
			}
		}
		if (ahrims)
		{
			line.append(", ahrim's 25% for +30%");
		}
		line.append(" -> max ").append(maxHit());
		if (twinflame)
		{
			line.append(" + ").append(secondMaxHit()).append(" (twinflame second hit)");
		}
		return line.toString();
	}
}

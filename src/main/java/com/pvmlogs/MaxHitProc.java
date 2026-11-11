package com.pvmlogs;

import java.util.Locale;

/**
 * T-212: a rare effect that sets the top of an attack's damage above its own max: an enchanted bolt's proc (ruby,
 * diamond, onyx, dragonstone) or the keris's crit. Shown on its own row under the attack's max hit, with its chance and
 * its hit, so "max hit" reads as what the attack itself rolls to. What it scores is unchanged: the distribution the
 * expected damage comes from has always had the proc's branch in it.
 */
final class MaxHitProc
{
	/** Short, for an overlay row: "ruby", "diamond", "onyx", "dragonstone", "keris crit". */
	final String name;
	/** Its chance per attack, the hard Kandarin diary's tenth included. */
	final double chance;
	/** It comes with every landed hit (the Zaryte crossbow's special) rather than at {@link #chance}. */
	final boolean onEveryHit;
	/** The most it deals, as the max hit is worked out (mitigation, a boss's modifiers, the cap); -1 when it needs a target. */
	final int hit;
	/** What it is when there's no target to work the hit out against: "20% hp". */
	final String rule;

	MaxHitProc(String name, double chance, boolean onEveryHit, int hit, String rule)
	{
		this.name = name;
		this.chance = chance;
		this.onEveryHit = onEveryHit;
		this.hit = hit;
		this.rule = rule;
	}

	/** "6.6%", "6%", "2%" (the keris's 1 in 51), or "on hit" for one that comes with every landed hit. */
	String chanceLabel()
	{
		return onEveryHit ? "on hit" : percent(chance);
	}

	/** The right side of its row: the hit, or its rule when there's no hit to give. */
	String hitLabel()
	{
		return hit >= 0 ? String.valueOf(hit) : rule;
	}

	/** One decimal, dropped when it's a zero: 6.6%, 6%, 12.1%. */
	static String percent(double chance)
	{
		final String one = String.format(Locale.ROOT, "%.1f", chance * 100);
		return (one.endsWith(".0") ? one.substring(0, one.length() - 2) : one) + "%";
	}

	/** For the debug log: {@code ruby proc 100 at 6.6%}. */
	String logLabel()
	{
		return name + (name.endsWith("crit") ? " " : " proc ") + hitLabel() + " at " + chanceLabel();
	}

	@Override
	public String toString()
	{
		return logLabel();
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof MaxHitProc))
		{
			return false;
		}
		final MaxHitProc p = (MaxHitProc) o;
		return name.equals(p.name) && chance == p.chance && onEveryHit == p.onEveryHit && hit == p.hit
			&& java.util.Objects.equals(rule, p.rule);
	}

	@Override
	public int hashCode()
	{
		return java.util.Objects.hash(name, chance, onEveryHit, hit, rule);
	}
}

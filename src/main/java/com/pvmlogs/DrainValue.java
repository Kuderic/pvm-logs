package com.pvmlogs;

/**
 * T-050's drain value and spec luck in seconds, the way luck turns damage into
 * time ({@code LedgerRows.Numbers}): over the scored fights' clock, at the rate
 * the expected and the other damage (thrall, DoT, reflect) come in. Positive is
 * time saved.
 */
final class DrainValue
{
	private DrainValue()
	{
	}

	/** {@code damage} as seconds over {@code scoredTicks}; NaN where there is no rate or nothing is known. */
	static double seconds(double damage, int scoredTicks, double expected, int other)
	{
		final double rate = expected + Math.max(0, other);
		if (Double.isNaN(damage) || scoredTicks <= 0 || expected <= 0 || rate <= 0)
		{
			return Double.NaN;
		}
		return scoredTicks * LedgerRows.SECONDS_PER_TICK * damage / rate;
	}
}

package com.pvmlogs;

import java.util.Arrays;

/**
 * Swing's copy of the damage chart's and tick strip's series (T-036; spec 5.1).
 * Each snapshot carries the series from the index the last one stopped at, a
 * few ticks back to catch a late splat; {@link #apply} writes them in. A
 * snapshot from index 0 is a new scope, and starts the copy over. Swing's
 * thread only.
 */
final class SidePanelChartData
{
	private float[] damage = new float[256];
	private float[] expected = new float[256];
	private float[] variance = new float[256];
	private byte[] states = new byte[256];
	private byte[] styles = new byte[256];
	private int length;
	private PanelSnapshot.Chart last;
	/** T-046: bumped by every snapshot that brings series, and by every one that starts them over. */
	private int version;
	private int scopes;

	void apply(PanelSnapshot.Chart c)
	{
		if (c == null)
		{
			return;
		}
		version++;
		if (c.from == 0)
		{
			length = 0;
			scopes++;
		}
		if (c.length > damage.length)
		{
			final int size = Math.max(c.length, damage.length * 2);
			damage = Arrays.copyOf(damage, size);
			expected = Arrays.copyOf(expected, size);
			variance = Arrays.copyOf(variance, size);
			states = Arrays.copyOf(states, size);
			styles = Arrays.copyOf(styles, size);
		}
		final int n = c.length - c.from;
		System.arraycopy(c.damage, 0, damage, c.from, n);
		System.arraycopy(c.expected, 0, expected, c.from, n);
		System.arraycopy(c.variance, 0, variance, c.from, n);
		System.arraycopy(c.states, 0, states, c.from, n);
		System.arraycopy(c.styles, 0, styles, c.from, n);
		length = c.length;
		last = c;
	}

	int length()
	{
		return length;
	}

	/** T-112: the tick hovered on the chart or the strip, -1 for none; shared, so both draw the same moment. */
	private int hover = -1;

	int hover()
	{
		return hover < length ? hover : -1;
	}

	/** T-112: sets the tick hovered; whether it changed. */
	boolean setHover(int tick)
	{
		final int h = tick < 0 || tick >= length ? -1 : tick;
		if (h == hover)
		{
			return false;
		}
		hover = h;
		return true;
	}

	/** Changes with every snapshot applied: the chart's and strip's images are behind when theirs differs (T-046). */
	int version()
	{
		return version;
	}

	/** Changes when a snapshot starts the series over (a new scope): the images are drawn again from the start. */
	int scopes()
	{
		return scopes;
	}

	float damage(int i)
	{
		return damage[i];
	}

	float expected(int i)
	{
		return expected[i];
	}

	float variance(int i)
	{
		return variance[i];
	}

	byte state(int i)
	{
		return states[i];
	}

	byte style(int i)
	{
		return styles[i];
	}

	/** The latest snapshot's chart: colours, legend and what to hide. */
	PanelSnapshot.Chart meta()
	{
		return last;
	}
}

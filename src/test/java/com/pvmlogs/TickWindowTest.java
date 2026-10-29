package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Eric's 13:20 Zulrah log (2026-10-29), nothing copied: no melee weapon held all
 * kill, yet 14 splats that came with no projectile of his were booked as melee
 * blows. {@code now - Integer.MIN_VALUE} overflows to a negative, so "never"
 * passed every "within N ticks" test.
 */
public class TickWindowTest
{
	@Test
	public void neverIsNotRecently()
	{
		// The booking path's melee grace of 1, a fight's first ticks and a long session's.
		for (int now : new int[]{0, 131, 7609, 1_000_000})
		{
			assertFalse("tick " + now, PvmPerformancePlugin.withinTicks(now, Integer.MIN_VALUE, 1));
			assertFalse("tick " + now, PvmPerformancePlugin.withinTicks(now, Integer.MIN_VALUE, 5));
		}
		// As ints, the old test passed: this is the bug.
		assertTrue(131 - Integer.MIN_VALUE <= 1);
	}

	@Test
	public void recentIsUnchanged()
	{
		// Melee held on 130: a splat on 131 is within the grace, one on 132 is not.
		assertTrue(PvmPerformancePlugin.withinTicks(131, 130, 1));
		assertTrue(PvmPerformancePlugin.withinTicks(130, 130, 1));
		assertFalse(PvmPerformancePlugin.withinTicks(132, 130, 1));
		// A cast booked on 129 with a lag of 0: a cast landing on 130 is not that one's.
		assertFalse(PvmPerformancePlugin.withinTicks(130, 129, 0));
		assertTrue(PvmPerformancePlugin.withinTicks(129, 129, 0));
	}
}

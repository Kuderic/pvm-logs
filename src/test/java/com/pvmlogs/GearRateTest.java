package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.pvmlogs.CombatCalc.OrdinaryShape;
import java.util.Collections;
import org.junit.Test;

/**
 * T-159: the overlay's DPS is the gear's rate, not held to the hitpoints the target has left; scoring keeps the cap.
 * Eric's goblin ([4232] of 2026-11-02 20:17): max 21, accuracy 0.99, a blowpipe (2 ticks): 4.27 a hit at 5 hp left, DPS
 * 3.56, falling with every hit, where the gear's 10.4 a hit is 8.7 a second.
 */
public class GearRateTest
{
	private static final int NONE = Integer.MAX_VALUE;

	private static double average(int hpLeft, boolean gearRate)
	{
		return CombatCalc.shapeMoments(OrdinaryShape.ONE, 21, 0.99, 1, CombatCalc.capFor(NONE, hpLeft, gearRate)).mean;
	}

	@Test
	public void theGoblinsDpsDoesNotMoveWithItsHpLeft()
	{
		final double full = average(5, true);
		for (int left : new int[]{5, 4, 2, 1})
		{
			assertEquals(full, average(left, true), 0);
		}
		// The gear's 10.4 a hit (T-156's pushed 0 in), 8.7 a second at the blowpipe's 2 ticks.
		assertEquals(0.99 * (21 / 2.0 + 1.0 / 22), full, 1e-12);
		assertEquals(8.7, CombatCalc.dpsFrom(full, 2), 0.05);
	}

	@Test
	public void theScoredAverageStillCapsAtTheHpLeft()
	{
		// The log's 4.27 at 5 left (build 1e73c42), 4.32 with T-156's pushed 0; about 1.9 at 2.
		assertEquals(0.99 * (CombatCalc.cappedAverage(21, 5) + 1.0 / 22), average(5, false), 1e-12);
		assertEquals(4.32, average(5, false), 0.005);
		assertTrue(average(2, false) < 2.0);
		assertTrue(average(2, false) < average(5, false));
		assertEquals(NONE, CombatCalc.capFor(NONE, NONE, false));
		assertEquals(5, CombatCalc.capFor(NONE, 5, false));
	}

	@Test
	public void aBossCapStillAppliesToTheGearsRate()
	{
		// Verzik's first phase: 10 a hitsplat at any hp (3 for some styles; T-049). The gear's rate keeps it.
		assertEquals(10, CombatCalc.capFor(10, 2000, true));
		assertEquals(10, CombatCalc.capFor(10, 4, true));
		assertEquals(4, CombatCalc.capFor(10, 4, false));
		final double gear = CombatCalc.shapeMoments(OrdinaryShape.ONE, 40, 0.8, 1, CombatCalc.capFor(10, 4, true)).mean;
		assertEquals(0.8 * (CombatCalc.cappedAverage(40, 10) + 1.0 / 41), gear, 1e-12);
	}

	@Test
	public void aBossModifiersWalkTakesTheSameCap()
	{
		// A boss with modifiers (its own walk) is held the same way: the gear's rate to its cap, scoring to the hp left too.
		final double gear = ModifierTracker.moments(Collections.emptyList(), new int[]{21}, 0.99,
			CombatCalc.capFor(NONE, 2, true), true).mean;
		final double scored = ModifierTracker.moments(Collections.emptyList(), new int[]{21}, 0.99,
			CombatCalc.capFor(NONE, 2, false), true).mean;
		assertEquals(average(2, true), gear, 1e-12);
		assertTrue(scored < gear);
	}
}

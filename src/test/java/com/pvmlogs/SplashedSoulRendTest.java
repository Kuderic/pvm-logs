package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Test;

/**
 * T-145, from Eric's logs of 2026-10-31 (read in place, nothing copied). A Soul Rend that splashed paid no experience
 * and showed no splat, and its projectile was never taken as his, so nothing booked it: [2586]-[2597] of the 18:30
 * log, two of them thrown on [2586] and [2591], read twelve ticks LOST. One that landed pays its experience on its
 * throw tick and is booked by that, as the one thrown on [1567] whose 7 landed on [1572].
 */
public class SplashedSoulRendTest
{
	private static final int LAG = 2;
	private static final int EYE = 3;

	@Test
	public void aSplashedSoulRendIsBookedOnceAsAMiss()
	{
		// No experience at all, or none since its throw: splashed.
		assertTrue(PvmPerformancePlugin.splashedSpecial(2586, Integer.MIN_VALUE));
		assertTrue(PvmPerformancePlugin.splashedSpecial(2591, 2577));
		// The energy fell on [1567] and its experience came then: it landed, and is booked by the drop as before.
		assertFalse(PvmPerformancePlugin.splashedSpecial(1567, 1567));
		// The energy can fall a tick ahead of the attack (specialWentOutFor): experience the tick after is its own.
		assertFalse(PvmPerformancePlugin.splashedSpecial(1566, 1567));
		// Booked on the tick after its throw, a tick's lag: it scores as the special its energy fall says it was.
		final Deque<Integer> unspent = new ArrayDeque<>();
		unspent.add(2586);
		assertEquals(2586, PvmPerformancePlugin.unspentSpecialFor(unspent, 2587));
		// Only the Eye of Ayak's special leaves nothing to book a miss by.
		for (SpecialAttack s : SpecialAttack.values())
		{
			assertEquals(s.name(), s == SpecialAttack.EYE_OF_AYAK, s.missShowsNothing());
		}
	}

	@Test
	public void itsFiveTicksAreSpent()
	{
		// At speed 5, from its throw on [2586].
		assertEquals(5, PvmPerformancePlugin.speedAfter(EYE, SpecialAttack.EYE_OF_AYAK, false));
		// Its energy fall proves the throw as experience would, also before the fight has any due tick ([2586]'s
		// "overdue since -2147483648").
		for (int now = 2586; now <= 2590; now++)
		{
			assertTrue("tick " + now, PvmPerformancePlugin.throwProvenByXp(now, Integer.MIN_VALUE, 2586, 5));
		}
		assertFalse(PvmPerformancePlugin.throwProvenByXp(2591, Integer.MIN_VALUE, 2586, 5));
		// Nothing thrown proves nothing.
		assertFalse(PvmPerformancePlugin.throwProvenByXp(2586, Integer.MIN_VALUE, Integer.MIN_VALUE, 5));
	}

	@Test
	public void twoInARowAsAt2586And2591()
	{
		// Before: nothing booked either, the due tick never set, every tick from [2586] to [2597] LOST.
		int lostBefore = 0;
		for (int now = 2586; now <= 2597; now++)
		{
			final boolean overdue = PvmPerformancePlugin.attackOverdue(now, Integer.MIN_VALUE, LAG);
			lostBefore += overdue && !PvmPerformancePlugin.throwProvenByXp(now, Integer.MIN_VALUE, Integer.MIN_VALUE, EYE)
				? 1 : 0;
		}
		assertEquals(12, lostBefore);
		// After: each throw proved by its energy fall, each booked the tick after (due its throw + 5), the staff shot
		// thrown on [2596] booked from its projectile on [2598].
		int lostAfter = 0;
		int due = Integer.MIN_VALUE;
		int proof = Integer.MIN_VALUE;
		for (int now = 2586; now <= 2597; now++)
		{
			if (now == 2586 || now == 2591)
			{
				proof = now;
			}
			if (now == 2587 || now == 2592)
			{
				due = now - 1 + PvmPerformancePlugin.speedAfter(EYE, SpecialAttack.EYE_OF_AYAK, false);
			}
			final boolean overdue = PvmPerformancePlugin.attackOverdue(now, due, LAG);
			lostAfter += overdue && !PvmPerformancePlugin.throwProvenByXp(now, due, proof,
				PvmPerformancePlugin.speedAfter(EYE, SpecialAttack.EYE_OF_AYAK, false)) ? 1 : 0;
		}
		assertEquals(0, lostAfter);
		// Each claims its own energy fall, the second's not taken by the first.
		final Deque<Integer> unspent = new ArrayDeque<>();
		unspent.add(2586);
		unspent.add(2591);
		assertEquals(2586, PvmPerformancePlugin.unspentSpecialFor(unspent, 2587));
		unspent.remove(2586);
		assertEquals(2591, PvmPerformancePlugin.unspentSpecialFor(unspent, 2592));
	}
}

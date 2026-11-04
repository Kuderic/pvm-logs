package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-161: an npc below full regains a hitpoint every {@link TargetHealth#REGEN_TICKS}, and the count follows it.
 * Cerberus's kills each took 3 to 7 more than her 600 in Eric's log of 2026-11-02 (build {@code 1e73c42}), every splat
 * paid for in experience.
 */
public class RegenerationTest
{
	private static final int CERBERUS = 5862;
	private static final int INDEX = 1525;

	// [9724]-[9799] of the 11-02 log: every splat on her in the kill that reconciled 604 against 600, by tick. The 8, 8,
	// 16 and 11, 11, 22 are two specials' splats, paid 32 and 44 in experience.
	private static final int[][] SPLATS = {
		{9724, 16}, {9728, 20}, {9735, 26}, {9739, 42}, {9743, 7}, {9747, 49}, {9751, 50}, {9755, 22}, {9759, 61},
		{9763, 8}, {9763, 8}, {9764, 16}, {9767, 11}, {9767, 11}, {9768, 22}, {9771, 7}, {9775, 39}, {9779, 48},
		{9783, 34}, {9790, 55}, {9794, 39}, {9799, 13},
	};

	@Test
	public void herKillReconcilesWithWhatSheRegained()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, CERBERUS, 600, 9724);
		int splat = 0;
		int leftBeforeLast = -1;
		for (int tick = 9724; tick <= 9799; tick++)
		{
			while (splat < SPLATS.length && SPLATS[splat][0] == tick)
			{
				if (splat == SPLATS.length - 1)
				{
					leftBeforeLast = h.countedRemaining(INDEX);
				}
				h.damage(INDEX, CERBERUS, SPLATS[splat][1], tick);
				splat++;
			}
			// The plugin asks at each tick's end while she's alive; the killing blow's tick she's dead.
			if (tick < 9799)
			{
				h.regenerate(INDEX, tick);
			}
		}
		assertEquals(604, h.damageCounted(INDEX));
		// 10 ticks after her first damage, then every 20: 9734, 9754, 9774, 9794.
		assertEquals(4, h.regenerated(INDEX));
		assertEquals(4, h.healed(INDEX));
		assertEquals("the count matches her full hitpoints", 600, h.damageCounted(INDEX) - h.healed(INDEX));
		// And the last blow, a 13, dealt exactly what she had left: before, the count had 9.
		assertEquals(13, leftBeforeLast);
		assertEquals(0, h.countedRemaining(INDEX));
	}

	@Test
	public void onlyBelowFullAndTheTimerStopsAtFull()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, CERBERUS, 600, 0);
		assertEquals("full: nothing", 0, h.regenerate(INDEX, 5));
		h.damage(INDEX, CERBERUS, 2, 100);
		assertEquals("the timer starts", 0, h.regenerate(INDEX, 100));
		assertEquals(0, h.regenerate(INDEX, 109));
		assertEquals(1, h.regenerate(INDEX, 110));
		assertEquals(599, h.remaining(INDEX, 110));
		assertEquals(0, h.regenerate(INDEX, 129));
		assertEquals(1, h.regenerate(INDEX, 130));
		assertEquals(600, h.remaining(INDEX, 130));
		// Back at full: nothing more, and the next damage starts the timer again.
		assertEquals(0, h.regenerate(INDEX, 200));
		assertEquals(2, h.regenerated(INDEX));
		h.damage(INDEX, CERBERUS, 10, 300);
		assertEquals(0, h.regenerate(INDEX, 300));
		assertEquals(0, h.regenerate(INDEX, 309));
		assertEquals(1, h.regenerate(INDEX, 310));
	}

	@Test
	public void ticksNotAskedAboutAreCaughtUpNeverPastFull()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, CERBERUS, 600, 0);
		h.damage(INDEX, CERBERUS, 3, 100);
		h.regenerate(INDEX, 100);
		// Not her fight for a while (a trip to an add's own fight): what she regained meanwhile, up to full.
		assertEquals(3, h.regenerate(INDEX, 1000));
		assertEquals(600, h.remaining(INDEX, 1000));
	}

	@Test
	public void theBossBarSeesItInstead()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, CERBERUS, 600, 0);
		h.hud(INDEX, CERBERUS, 550, 600, 1);
		h.damage(INDEX, CERBERUS, 50, 1);
		h.regenerate(INDEX, 1);
		assertEquals(0, h.regenerate(INDEX, 100));
		assertEquals(0, h.regenerated(INDEX));
	}

	@Test
	public void theOverheadBarsLeastIsTheOtherEndOfItsMost()
	{
		// Cerberus's bar has 120 steps ([11406] of the 11-02 log: 82/120).
		assertEquals(1, TargetHealth.leastLeftForRatio(1, 120, 600));
		assertEquals(6, TargetHealth.leastLeftForRatio(2, 120, 600));
		assertEquals(600, TargetHealth.leastLeftForRatio(120, 120, 600));
		assertEquals(0, TargetHealth.leastLeftForRatio(0, 120, 600));
		assertEquals(-1, TargetHealth.leastLeftForRatio(-1, 120, 600));
		for (int[] bar : new int[][]{{120, 600}, {30, 30}, {30, 850}, {255, 1020}})
		{
			final int scale = bar[0];
			final int max = bar[1];
			for (int hp = 1; hp <= max; hp++)
			{
				// The game's share, rounded up: any hitpoints show a step, full shows them all.
				final int ratio = hp >= max ? scale : hp * (scale - 1) / max + 1;
				assertTrue(hp + " of " + max + " at " + ratio + "/" + scale,
					TargetHealth.leastLeftForRatio(ratio, scale, max) <= hp
						&& hp <= TargetHealth.mostLeftForRatio(ratio, scale, max));
			}
		}
	}

	@Test
	public void aKillingBlowsFiguresKeepTheCapTheyWereReadAt()
	{
		// [9799] of the 11-02 log: a melee blow read at 9 left (avg 7.35 of a max 61), booked by its killing splat. Taken at
		// the booking, the count had run out (-1), and the landing cap read the figures as uncapped: 7.35 -> 0.00.
		assertEquals(0.0, LandingCap.recap(61, 0.89, 7.35, 10.4, -1, 9)[0], 1e-9);
		// At the cap they were read at, they stand.
		assertEquals(null, LandingCap.recap(61, 0.89, 7.35, 10.4, 9, 9));
	}
}

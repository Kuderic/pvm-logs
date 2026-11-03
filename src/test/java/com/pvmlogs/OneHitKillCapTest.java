package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-158, from Eric's log of 2026-11-02 20:17 (build 1e73c42, read in place, nothing copied): Blazing blowpipe darts
 * one-shotting undead cows (8 hitpoints). A dart thrown while the last cow was still the target is scored from that
 * tick's row, read for the last cow. Where that cow was dead its row's cap read 0, "capped at nothing", and the landing
 * cap passed the attack over: [7036], an expected 13.7 on an 8-hitpoint cow that died to an 8. Where it read -1 (a count
 * run out), it was capped: [7038], 13.70 -> 6.97. The log prints chances to two places (0.99), so its 6.97 and 5.51
 * are matched to within 0.05.
 */
public class OneHitKillCapTest
{
	private static final int MAX = 27;
	private static final double CHANCES = 0.99;
	private static final double AVG = 13.70;
	private static final double VAR = 68.9;

	@Test
	public void aRowReadForAnotherNpcIsTold()
	{
		final double[] shown = new double[PvmPerformancePlugin.EXP_WIDTH];
		// [7036]: the row of tick 7034 was the last cow's (index 30764); the fight is on 30761.
		assertTrue(PvmPerformancePlugin.rowOfAnotherNpc(shown, 30764, 30761));
		assertFalse(PvmPerformancePlugin.rowOfAnotherNpc(shown, 30761, 30761));
		// Unknown: as before.
		assertFalse(PvmPerformancePlugin.rowOfAnotherNpc(shown, null, 30761));
		assertFalse(PvmPerformancePlugin.rowOfAnotherNpc(null, 30764, 30761));
		// An aimed row (T-107) names its own index.
		final double[] aimed = new double[PvmPerformancePlugin.EXP_WIDTH + 1];
		aimed[PvmPerformancePlugin.EXP_WIDTH] = 30761;
		assertFalse(PvmPerformancePlugin.rowOfAnotherNpc(aimed, 30764, 30761));
		aimed[PvmPerformancePlugin.EXP_WIDTH] = 30765;
		assertTrue(PvmPerformancePlugin.rowOfAnotherNpc(aimed, 30761, 30761));
	}

	@Test
	public void aOneHitKillOnAFightOpenedByItsSplatIsCappedWithAnotherNpcsRow()
	{
		// [7036]: the last cow's row capped at 0 (dead), its figures uncapped all the same (the calculator reads a count run
		// out as no cap). Before: capThen 0, and the landing cap returned nothing.
		assertEquals(null, LandingCap.recap(MAX, CHANCES, AVG, VAR, 0, 8));
		// Now: another npc's row is taken back to no cap (here unchanged), and capped at the 8 this cow had as it landed.
		final double[] uncapped = LandingCap.uncapped(MAX, CHANCES, AVG, VAR, 0);
		assertArrayEquals(new double[]{AVG, VAR}, uncapped, 1e-9);
		final double[] capped = LandingCap.recap(MAX, CHANCES, uncapped[0], uncapped[1], -1, 8);
		assertNotNull(capped);
		assertEquals(6.97, capped[0], 0.05);
		assertTrue(capped[0] <= 8);
	}

	@Test
	public void aRowCappedAtAnotherNpcsFewHitpointsIsUncappedFirst()
	{
		// The last cow with 2 left: its row's figures capped at 2. The new cow has 8: they're taken back to no cap, then
		// capped at 8 - the same as figures read for it.
		final DamageMoments atTwo = DamageMoments.capped(MAX, 2).landing(CHANCES);
		final double[] back = LandingCap.uncapped(MAX, CHANCES, atTwo.mean, atTwo.variance(), 2);
		final DamageMoments none = DamageMoments.capped(MAX, MAX).landing(CHANCES);
		assertEquals(none.mean, back[0], 1e-9);
		final double[] atEight = LandingCap.recap(MAX, CHANCES, back[0], back[1], -1, 8);
		assertEquals(DamageMoments.capped(MAX, 8).landing(CHANCES).mean, atEight[0], 1e-9);
	}

	@Test
	public void aRowReadForThisNpcIsCappedAsBefore()
	{
		// [7031]: read at -1 (the count of this cow not yet started), landing on 6 left: 13.70 -> 5.51.
		final double[] capped = LandingCap.recap(MAX, CHANCES, AVG, VAR, -1, 6);
		assertNotNull(capped);
		assertEquals(5.51, capped[0], 0.05);
		// A certain hit's figures aren't moved by the un-capping.
		assertArrayEquals(new double[]{5, 0}, LandingCap.uncapped(MAX, 1, 5, 0, 5), 1e-9);
	}
}

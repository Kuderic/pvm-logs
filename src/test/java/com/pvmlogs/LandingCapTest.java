package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.MissingFormatArgumentException;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-103, from Eric's 22:32 log (build 1f79693, read in place, nothing
 * copied): at [513]-[516] a crossbow shot's guaranteed max (T-098) on a
 * Mirrorback araxyte was capped at the 29 left when it was thrown, though a
 * thrall's 3 had left 26 when it landed, and the fight read A 55, E 58, sd 0.0,
 * z −1,989,533.
 */
public class LandingCapTest
{
	@Test
	public void aGuaranteedMaxAfterAThrallsSplatIsWhatWasLeft()
	{
		// [512]: the first shot, thrown with 58 left: its max, 29. [513] it lands; [514] the thrall's 3; [515] the second
		// shot's figures are the throw's (29 left), and [516] it lands on 26.
		assertNull(LandingCap.recap(29, 1.0, 29.0, 0, 58, 58));
		final double[] second = LandingCap.recap(29, 1.0, 29.0, 0, 29, 26);
		assertEquals(26.0, second[0], 1e-9);
		assertEquals(0.0, second[1], 1e-9);
		// So the fight reads A = E.
		final Fight fight = new Fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, 63711, 58, 0, null, 0);
		fight.recordAttackMade(false);
		fight.recordExpected(29, 1.0, 29.0, 0);
		fight.recordDamageDealt(29, 1, true);
		fight.recordAttackMade(false);
		fight.recordExpected(29, 1.0, second[0], second[1]);
		fight.recordDamageDealt(26, 2, true);
		assertEquals(fight.getDamageDealt(), fight.getSumExpectedAverageHit(), 1e-9);
	}

	@Test
	public void anOrdinaryKillingBlowAfterAnotherPlayersSplat()
	{
		// An axe blow (max 63, lands 84.2%) thrown with 35 left; another player's 10 lands first, leaving 25.
		final double chances = 0.842;
		final DamageMoments then = DamageMoments.capped(63, 35).landing(chances);
		final double[] at = LandingCap.recap(63, chances, then.mean, then.variance(), 35, 25);
		// For one uniform roll the re-cap is exact: the roll capped at 25.
		final DamageMoments now = DamageMoments.capped(63, 25).landing(chances);
		assertEquals(now.mean, at[0], 1e-9);
		assertEquals(now.variance(), at[1], 1e-9);
		assertEquals(chances * CombatCalc.cappedAverage(63, 25), at[0], 1e-9);
		assertTrue(at[0] < then.mean);
	}

	@Test
	public void nothingChangesWhenNoLessIsLeftOrEitherIsUnknown()
	{
		assertNull(LandingCap.recap(63, 0.8, 20, 300, 35, 35));
		assertNull(LandingCap.recap(63, 0.8, 20, 300, 35, 40));
		assertNull(LandingCap.recap(63, 0.8, 20, 300, -1, 25));
		assertNull(LandingCap.recap(63, 0.8, 20, 300, 35, -1));
		// A certain hit already under what is left.
		assertNull(LandingCap.recap(29, 1.0, 20.0, 0, 40, 30));
		// Never past what is left, nor below nothing.
		final double[] tiny = LandingCap.recap(63, 0.9, 1.0, 2.0, 35, 1);
		assertTrue(tiny[0] >= 0 && tiny[0] <= 1);
		assertTrue(tiny[1] >= 0);
	}

	@Test
	public void aVarianceUnderTheFloorGivesNoZ()
	{
		assertTrue(Double.isNaN(DamageMoments.z(55, 58, 2.3e-12)));
		assertTrue(Double.isNaN(DamageMoments.z(55, 58, DamageMoments.VARIANCE_FLOOR)));
		assertEquals(-1, DamageMoments.percentile(55, 58, 2.3e-12), 0);
		// The smallest real attack's variance is far above it.
		assertFalse(Double.isNaN(DamageMoments.z(55, 58, 0.25)));
		// The LUCK line of [519], before the re-cap.
		final Fight fight = new Fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, 63711, 58, 0, null, 0);
		fight.recordAttackMade(false);
		fight.recordExpected(29, 1.0, 29.0, 0);
		fight.recordDamageDealt(29, 1, true);
		fight.recordAttackMade(false);
		fight.recordExpected(29, 1.0, 29.0, 0);
		fight.recordDamageDealt(26, 2, true);
		final String line = PvmPerformancePlugin.luckLine(fight);
		assertTrue(line, line.contains("z unknown, percentile unknown"));
		assertEquals(-1, fight.luckPercentile(), 0);
	}

	@Test
	public void theIdealBelowActualLineFormats()
	{
		// It had three arguments for four placeholders, so it never printed: "BAD LOG FORMAT" at [515].
		try
		{
			String.format(PvmPerformancePlugin.IDEAL_BELOW_ACTUAL, 13671, 25.0, 26.0);
			throw new AssertionError("three arguments should not format");
		}
		catch (MissingFormatArgumentException expected)
		{
			// as it was
		}
		final String line = String.format(PvmPerformancePlugin.IDEAL_BELOW_ACTUAL, 13671, 13.64, 26.0, true);
		assertTrue(line, line.startsWith("IDEAL BELOW ACTUAL on npc 13671: ideal 13.64, actual 26.00 (prayed=true)"));
	}

	@Test
	public void theClampAtTheLastBitIsNotLogged()
	{
		// [515]: the guaranteed max with 26 left, the actual walked over the 29 worn and the ideal over the Book of the
		// Dead's 31: 26.00000000000001 against 26.0. The same number.
		assertFalse(PvmPerformancePlugin.idealBelowActual(26.0, 26.00000000000001));
		assertFalse(PvmPerformancePlugin.idealBelowActual(26.0, 26.0));
		// A real shortfall still is, and an unknown ideal is not.
		assertTrue(PvmPerformancePlugin.idealBelowActual(13.64, 26.0));
		assertFalse(PvmPerformancePlugin.idealBelowActual(-1, 26.0));
	}
}

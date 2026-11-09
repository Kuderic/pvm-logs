package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-184: Kephri's guardian scarabs were swatted as weak adds. T-180's rule read their table's 40 hitpoints; in the raid
 * they're 99 to 110. Eric's raid of 2026-11-08 17:20 (build {@code d541db9}, read in place): five guardians swatted, at
 * [29101], [29139], [29269], [29311] and [29356]. He wants their fights, and the overlay's DPS against them.
 */
public class KephriGuardiansAreFightsTest
{
	private static PvmPerformancePlugin.Swat swat(int id, int hp, boolean bossFightOpen)
	{
		return PvmPerformancePlugin.weakAddSwat(true, id, false, hp, bossFightOpen);
	}

	@Test
	public void aGuardianScarabIsAFightAtAnyRaidLevel()
	{
		for (int guardian : new int[]{NpcID.TOA_KEPHRI_GUARDIAN_MELEE, NpcID.TOA_KEPHRI_GUARDIAN_RANGED,
			NpcID.TOA_KEPHRI_GUARDIAN_MAGE})
		{
			// Scaled to 99 at raid level 325, path level 1, as RAID HP read it in the 11-04 raid.
			assertEquals(99, RaidScaling.tombsHitpoints(40, guardian, 325, 1, 1));
			assertEquals(PvmPerformancePlugin.Swat.NONE, swat(guardian, 99, true));
			// And even where the raid leaves it at its table's 40 (raid level 0): it scales, so it's never weak.
			assertEquals(40, RaidScaling.tombsHitpoints(40, guardian, 0, 0, 1));
			assertEquals(PvmPerformancePlugin.Swat.NONE, swat(guardian, 40, true));
			assertEquals(PvmPerformancePlugin.Swat.NONE, swat(guardian, 40, false));
		}
	}

	@Test
	public void eggsAgileScarabsAndSwarmsAreStillSwatted()
	{
		// Unscaled: 35, 30 and 10 at every raid level.
		assertEquals(35, RaidScaling.tombsHitpoints(35, NpcID.KEPHRI_EGG_HATCH, 350, 2, 1));
		assertEquals(30, RaidScaling.tombsHitpoints(30, NpcID.TOA_KEPHRI_SCARAB_RANGEKITE, 350, 2, 1));
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(NpcID.KEPHRI_EGG_HATCH, 35, true));
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(NpcID.TOA_KEPHRI_SCARAB_RANGEKITE, 30, true));
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(NpcID.TOA_KEPHRI_SHIELD_SCARAB, 10, true));
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(NpcID.KEPHRI_EGG_HATCH, 35, false));
	}

	@Test
	public void aScavengerBeastIsStillSwatted()
	{
		assertTrue(!RaidScaling.scalesHitpoints(NpcID.RAIDS_SCAVENGER_BEAST_A));
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(NpcID.RAIDS_SCAVENGER_BEAST_A, 30, false));
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(NpcID.RAIDS_SCAVENGER_BEAST_B, 30, true));
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-180: Scavenger beasts and a boss's weak adds open no fight of their own. From Eric's log of 2026-11-06 11:38 (build
 * {@code 7eca1b6}, read in place): 45 Scavenger beast fights in five Chambers runs, 47 of Kephri's eggs, agile scarabs and
 * swarms, and 12 of Hespori's flowers, each one or two hits and each taking over the Fight tab.
 */
public class WeakAddsOpenNoFightTest
{
	private static PvmPerformancePlugin.Swat swat(boolean inRaid, int id, boolean monster, int hp, boolean bossFightOpen)
	{
		return PvmPerformancePlugin.weakAddSwat(inRaid, id, monster, hp, bossFightOpen);
	}

	@Test
	public void aThirtyHpAddHitDuringABossFightOpensNothing()
	{
		// Kephri's agile scarab (30 at Eric's raid level) and egg (35), hit with her fight open: swatted, her fight open.
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(true, NpcID.TOA_KEPHRI_SCARAB_RANGEKITE, false, 30, true));
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(true, NpcID.KEPHRI_EGG_HATCH, false, 35, true));
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(true, NpcID.TOA_KEPHRI_SHIELD_SCARAB, false, 10, true));
		// With no fight open (before her, or between her pieces), a boss's listed add opens none either.
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.KEPHRI_EGG_HATCH, false, 35, false));
		assertTrue(EncounterGroup.isBossesAdd(NpcID.KEPHRI_EGG_HATCH));
		assertTrue(EncounterGroup.isBossEncounter(NpcID.TOA_KEPHRI_GUARDIAN_MELEE));
	}

	@Test
	public void aScavengerBeastInARaidOpensNothing()
	{
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.RAIDS_SCAVENGER_BEAST_A, true, 30, false));
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.RAIDS_SCAVENGER_BEAST_B, true, 30, true));
	}

	@Test
	public void aGoblinOutsideARaidWithNoBossFightIsStillAFight()
	{
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(false, NpcID.GOBLIN_UNARMED_MELEE_3, true, 5, false));
		// Hit during a boss's fight, it's swatted in passing like an add.
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(false, NpcID.GOBLIN_UNARMED_MELEE_3, true, 5, true));
	}

	@Test
	public void theBaboonsRoomsMonstersAreStillFights()
	{
		// Grouped, with no boss: even with a fight open, each is its own (the room has no boss to swat them into).
		assertFalse(EncounterGroup.isBossesAdd(NpcID.TOA_PATH_APMEKEN_BABOON_THRALL));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.TOA_PATH_APMEKEN_BABOON_THRALL, false, 4, true));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.TOA_PATH_APMEKEN_BABOON_THRALL, false, 4, false));
	}

	@Test
	public void anAddOverTheThresholdIsStillAFight()
	{
		// Araxxor's mirrorback (58), Kephri's guardian scarab (110), Akkha's shadow (70 in the table).
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(false, NpcID.ARAXXOR_MINION_EGG_MIRRORBACK, false, 58, true));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.TOA_KEPHRI_GUARDIAN_MELEE, false, 110, true));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.AKKHA_SHADOW, false, 70, true));
		// Unknown hitpoints: as before.
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.KEPHRI_EGG_HATCH, false, -1, true));
	}

	@Test
	public void hesporisFlowersAreSwattedIntoHerFight()
	{
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(false, NpcID.HESPORI_HEALER_ACTIVE, false, 10, true));
		// The first two, before her fight opened ([543], [547]): no fight, the trip's time.
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(false, NpcID.HESPORI_HEALER_ACTIVE, false, 10, false));
	}

	@Test
	public void aBossIsNeverAWeakAdd()
	{
		// A boss's own form, or a boss alone (not a monster, not grouped), is never swatted.
		assertFalse(EncounterGroup.isBossesAdd(NpcID.HESPORI));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(false, NpcID.HESPORI, false, 300, true));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(false, 9999_9, false, 30, true));
	}
}

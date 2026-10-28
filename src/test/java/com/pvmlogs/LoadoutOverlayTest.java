package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * T-022: the visibility rule the loadout overlay shares with the Ledger. Its
 * settings migration is in {@link SettingsMigrationsTest} since T-040.
 */
public class LoadoutOverlayTest
{
	// ---- shown when the Ledger is: a fight on show, for "Keep last fight up (s)", bosses only (T-022, T-040) ----

	private static Fight fight()
	{
		return new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
	}

	@Test
	public void bothOverlaysHideUnderBossesOnlyOffABoss()
	{
		assertFalse(PvmPerformanceOverlay.onShow(fight(), 60, true, false, 0L));
		assertTrue(PvmPerformanceOverlay.onShow(fight(), 60, true, true, 0L));
		assertTrue(PvmPerformanceOverlay.onShow(fight(), 60, false, false, 0L));
	}

	@Test
	public void aFinishedFightStaysUpForTheHoldSeconds()
	{
		final Fight f = fight();
		f.end(true, 10_000L);
		assertTrue(PvmPerformanceOverlay.onShow(f, 6, false, false, 10_000L + 6_000L));
		assertFalse(PvmPerformanceOverlay.onShow(f, 6, false, false, 10_000L + 6_001L));
		// The default, 60 s: the old 100-tick timeout's minute.
		assertTrue(PvmPerformanceOverlay.onShow(f, 60, false, false, 10_000L + 60_000L));
		assertFalse(PvmPerformanceOverlay.onShow(f, 60, false, false, 10_000L + 60_001L));
	}

	@Test
	public void holdZeroKeepsTheFightUpUntilTheNextOne()
	{
		final Fight f = fight();
		f.end(true, 10_000L);
		assertTrue(PvmPerformanceOverlay.onShow(f, 0, false, false, 10_000L + 3_600_000L));
		// Still under "bosses only".
		assertFalse(PvmPerformanceOverlay.onShow(f, 0, true, false, 10_000L + 1L));
	}

	@Test
	public void nothingToShowWithoutAFight()
	{
		assertFalse(PvmPerformanceOverlay.onShow(null, 60, false, false, 0L));
	}

	// ---- T-027: "bosses only" asks of the room too ----

	@Test
	public void aBossAddInTheBossRoomCountsAsTheBoss()
	{
		// Eric, 2026-10-27: hitting a respiratory system hid both boxes. Its name isn't a boss's, its room's is.
		final Fight lung = new Fight("Respiratory system", net.runelite.api.gameval.NpcID.ABYSSALSIRE_LUNG, 2, 50, 0L,
			null, 0);
		assertEquals("Abyssal Sire", lung.encounterName());
		assertTrue(PvmPerformanceOverlay.isBossFight(lung));
		// Both overlays go by onShow, so both stay up under "bosses only".
		assertTrue(PvmPerformanceOverlay.onShow(lung, 60, true, PvmPerformanceOverlay.isBossFight(lung), 0L));
		// The spawns and scions too.
		assertTrue(PvmPerformanceOverlay.isBossFight(new Fight("Spawn", net.runelite.api.gameval.NpcID.ABYSSALSIRE_SPAWN,
			3, 15, 0L, null, 0)));
	}

	@Test
	public void aLoneNonBossStillHides()
	{
		final Fight goblin = new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
		assertFalse(PvmPerformanceOverlay.isBossFight(goblin));
		assertFalse(PvmPerformanceOverlay.onShow(goblin, 60, true, PvmPerformanceOverlay.isBossFight(goblin), 0L));
		assertFalse(PvmPerformanceOverlay.isBossFight(null));
	}

	@Test
	public void aBossByItsOwnNameStillCounts()
	{
		assertTrue(PvmPerformanceOverlay.isBossFight(new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0)));
		assertTrue(PvmPerformancePlugin.isBossName("Abyssal Sire"));
		assertFalse(PvmPerformancePlugin.isBossName("Respiratory system"));
		assertFalse(PvmPerformancePlugin.isBossName(null));
	}
}

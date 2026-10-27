package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** T-022: the loadout overlay's settings migration, and the visibility rule it shares with the Ledger. */
public class LoadoutOverlayTest
{
	/** Settings in a map, as the config would hold them as strings. */
	private static final class MapSettings implements LoadoutOverlay.Settings
	{
		final Map<String, String> values = new HashMap<>();

		public String get(String key)
		{
			return values.get(key);
		}

		public void set(String key, boolean value)
		{
			values.put(key, String.valueOf(value));
		}

		public void unset(String key)
		{
			values.remove(key);
		}
	}

	@Test
	public void loadoutOnlyOnBecomesTheLedgerHiddenAndTheLoadoutOverlayShown()
	{
		final MapSettings s = new MapSettings();
		s.values.put("overlayExpectedOnly", "true");
		s.values.put("showOverlay", "true");
		s.values.put("overlayShowLoadout", "false");
		LoadoutOverlay.migrateLoadoutOnly(s);
		assertEquals("false", s.values.get("showOverlay"));
		assertEquals("true", s.values.get("overlayShowLoadout"));
		assertNull(s.values.get("overlayExpectedOnly"));
	}

	@Test
	public void loadoutOnlyOffOnlyDropsTheOldKey()
	{
		final MapSettings s = new MapSettings();
		s.values.put("overlayExpectedOnly", "false");
		s.values.put("overlayShowLoadout", "false");
		LoadoutOverlay.migrateLoadoutOnly(s);
		assertEquals("false", s.values.get("overlayShowLoadout"));
		assertNull(s.values.get("showOverlay"));
		assertFalse(s.values.containsKey("overlayExpectedOnly"));
	}

	@Test
	public void neverSetChangesNothingAndRunningTwiceIsTheSame()
	{
		final MapSettings s = new MapSettings();
		s.values.put("showOverlay", "true");
		LoadoutOverlay.migrateLoadoutOnly(s);
		assertEquals(1, s.values.size());
		s.values.put("overlayExpectedOnly", "true");
		LoadoutOverlay.migrateLoadoutOnly(s);
		// The player turns the Ledger back on: a second start must not hide it again.
		s.values.put("showOverlay", "true");
		LoadoutOverlay.migrateLoadoutOnly(s);
		assertEquals("true", s.values.get("showOverlay"));
	}

	@Test
	public void theKeysAreTheConfigsOwn()
	{
		// The migration writes the same keys the config reads, so a rename there must show up here.
		assertEquals("showOverlay", LoadoutOverlay.SHOW_OVERLAY_KEY);
		assertEquals("overlayShowLoadout", LoadoutOverlay.SHOW_LOADOUT_KEY);
	}

	// ---- shown when the Ledger is: a fight on show, until the timeout, bosses only (T-022) ----

	private static Fight fight()
	{
		return new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
	}

	@Test
	public void bothOverlaysHideUnderBossesOnlyOffABoss()
	{
		assertFalse(PvmPerformanceOverlay.onShow(fight(), 100, true, false, 0L));
		assertTrue(PvmPerformanceOverlay.onShow(fight(), 100, true, true, 0L));
		assertTrue(PvmPerformanceOverlay.onShow(fight(), 100, false, false, 0L));
	}

	@Test
	public void aFinishedFightStaysUpUntilTheTimeout()
	{
		final Fight f = fight();
		f.end(true, 10_000L);
		assertTrue(PvmPerformanceOverlay.onShow(f, 10, false, false, 10_000L + 6_000L));
		assertFalse(PvmPerformanceOverlay.onShow(f, 10, false, false, 10_000L + 6_001L));
	}

	@Test
	public void nothingToShowWithoutAFight()
	{
		assertFalse(PvmPerformanceOverlay.onShow(null, 100, false, false, 0L));
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
		assertTrue(PvmPerformanceOverlay.onShow(lung, 100, true, PvmPerformanceOverlay.isBossFight(lung), 0L));
		// The spawns and scions too.
		assertTrue(PvmPerformanceOverlay.isBossFight(new Fight("Spawn", net.runelite.api.gameval.NpcID.ABYSSALSIRE_SPAWN,
			3, 15, 0L, null, 0)));
	}

	@Test
	public void aLoneNonBossStillHides()
	{
		final Fight goblin = new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
		assertFalse(PvmPerformanceOverlay.isBossFight(goblin));
		assertFalse(PvmPerformanceOverlay.onShow(goblin, 100, true, PvmPerformanceOverlay.isBossFight(goblin), 0L));
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

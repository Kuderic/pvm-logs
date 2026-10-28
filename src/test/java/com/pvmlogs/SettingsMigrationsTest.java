package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The one-time moves of saved settings (T-022's "Loadout only", T-040's fight
 * timeout and overlay scope). Values are held as RuneLite saves them: strings,
 * enums by name. As RuneLite writes every declared default on load, the new
 * keys are already there at their defaults when a migration runs; each test
 * starts from that.
 */
public class SettingsMigrationsTest
{
	/** Settings in a map, as the config holds them. */
	private static final class MapSettings implements SettingsMigrations.Settings
	{
		final Map<String, String> values = new HashMap<>();

		public String get(String key)
		{
			return values.get(key);
		}

		public void set(String key, String value)
		{
			values.put(key, value);
		}

		public void unset(String key)
		{
			values.remove(key);
		}
	}

	/** What RuneLite has saved by startUp on a T-040 build: every declared setting at its default. */
	private static MapSettings afterLoad()
	{
		final MapSettings s = new MapSettings();
		s.values.put("showOverlay", "true");
		s.values.put("overlayShowLoadout", "true");
		s.values.put("overlayBossesOnly", "true");
		s.values.put("overlayHoldSeconds", "60");
		s.values.put("overlayCovers", "FIGHT");
		return s;
	}

	// ---- T-022: "Loadout only" ----

	@Test
	public void loadoutOnlyOnBecomesTheLedgerHiddenAndTheLoadoutOverlayShown()
	{
		final MapSettings s = new MapSettings();
		s.values.put("overlayExpectedOnly", "true");
		s.values.put("showOverlay", "true");
		s.values.put("overlayShowLoadout", "false");
		SettingsMigrations.migrateLoadoutOnly(s);
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
		SettingsMigrations.migrateLoadoutOnly(s);
		assertEquals("false", s.values.get("overlayShowLoadout"));
		assertNull(s.values.get("showOverlay"));
		assertFalse(s.values.containsKey("overlayExpectedOnly"));
	}

	@Test
	public void loadoutOnlyNeverSetChangesNothingAndRunningTwiceIsTheSame()
	{
		final MapSettings s = new MapSettings();
		s.values.put("showOverlay", "true");
		SettingsMigrations.migrateLoadoutOnly(s);
		assertEquals(1, s.values.size());
		s.values.put("overlayExpectedOnly", "true");
		SettingsMigrations.migrateLoadoutOnly(s);
		// The player turns the Ledger back on: a second start must not hide it again.
		s.values.put("showOverlay", "true");
		SettingsMigrations.migrateLoadoutOnly(s);
		assertEquals("true", s.values.get("showOverlay"));
	}

	// ---- T-040: "Fight timeout (ticks)" → "Keep last fight up (s)" ----

	private static String holdFrom(String ticks)
	{
		final MapSettings s = afterLoad();
		s.values.put("fightTimeoutTicks", ticks);
		SettingsMigrations.migrateFightTimeout(s);
		assertFalse(s.values.containsKey("fightTimeoutTicks"));
		return s.values.get("overlayHoldSeconds");
	}

	@Test
	public void theOldDefaultTimeoutIsTheNewDefaultHold()
	{
		assertEquals("60", holdFrom("100"));
	}

	@Test
	public void aChangedTimeoutCarriesOverInSeconds()
	{
		// 0.6 s a tick, rounded half up: 30 ticks 18 s, 25 ticks 15 s, 1 tick 0.6 s → 1 s.
		assertEquals("18", holdFrom("30"));
		assertEquals("15", holdFrom("25"));
		assertEquals("1", holdFrom("1"));
		assertEquals("150", holdFrom("250"));
	}

	@Test
	public void aTimeoutOutsideTheRangeIsKeptInIt()
	{
		// 0 ticks would now mean "until the next fight", which nobody asked for: at least a second.
		assertEquals("1", holdFrom("0"));
		assertEquals("1", holdFrom("-5"));
		assertEquals("600", holdFrom("5000"));
	}

	@Test
	public void aTimeoutThatIsntANumberLeavesTheDefault()
	{
		assertEquals("60", holdFrom("lots"));
	}

	@Test
	public void noOldTimeoutChangesNothing()
	{
		final MapSettings s = afterLoad();
		s.values.put("overlayHoldSeconds", "0");
		SettingsMigrations.migrateFightTimeout(s);
		// Already migrated, and the player chose 0 since: a later start leaves it alone.
		assertEquals("0", s.values.get("overlayHoldSeconds"));
	}

	// ---- T-040: "Overlay: whole trip" + "Inside a raid, show" → "Covers" ----

	private static String coversFrom(String trip, String raid)
	{
		final MapSettings s = afterLoad();
		if (trip != null)
		{
			s.values.put("overlaySessionTotals", trip);
		}
		if (raid != null)
		{
			s.values.put("raidScope", raid);
		}
		SettingsMigrations.migrateCovers(s);
		assertFalse(s.values.containsKey("overlaySessionTotals"));
		assertFalse(s.values.containsKey("raidScope"));
		return s.values.get("overlayCovers");
	}

	@Test
	public void eachOldScopeBecomesItsCovers()
	{
		// The old defaults, as every existing user has them saved.
		assertEquals("FIGHT", coversFrom("false", "ROOM"));
		assertEquals("RAID", coversFrom("false", "RAID"));
		// Whole trip won over the raid scope before, and does here.
		assertEquals("TRIP", coversFrom("true", "ROOM"));
		assertEquals("TRIP", coversFrom("true", "RAID"));
		// Only one of the two saved.
		assertEquals("TRIP", coversFrom("true", null));
		assertEquals("RAID", coversFrom(null, "RAID"));
		assertEquals("FIGHT", coversFrom(null, "ROOM"));
	}

	@Test
	public void eachCoversNameIsAnEnumConstant()
	{
		for (String trip : new String[]{"true", "false"})
		{
			for (String raid : new String[]{"ROOM", "RAID"})
			{
				OverlayCovers.valueOf(coversFrom(trip, raid));
			}
		}
	}

	@Test
	public void noOldScopeChangesNothing()
	{
		final MapSettings s = afterLoad();
		s.values.put("overlayCovers", "TRIP");
		SettingsMigrations.migrateCovers(s);
		assertEquals("TRIP", s.values.get("overlayCovers"));
	}

	// ---- all of them ----

	@Test
	public void anArodabEraConfigMigratesInOneStartAndASecondDoesNothing()
	{
		// Arodab's keys at chosen values, with T-040's new ones at their defaults as RuneLite writes them on load.
		final MapSettings s = afterLoad();
		s.values.put("overlayExpectedOnly", "true");
		s.values.put("fightTimeoutTicks", "50");
		s.values.put("overlaySessionTotals", "false");
		s.values.put("raidScope", "RAID");
		SettingsMigrations.run(s);
		assertEquals("false", s.values.get("showOverlay"));
		assertEquals("true", s.values.get("overlayShowLoadout"));
		assertEquals("30", s.values.get("overlayHoldSeconds"));
		assertEquals("RAID", s.values.get("overlayCovers"));
		for (String old : new String[]{"overlayExpectedOnly", "fightTimeoutTicks", "overlaySessionTotals", "raidScope"})
		{
			assertFalse(old, s.values.containsKey(old));
		}
		final Map<String, String> once = new HashMap<>(s.values);
		SettingsMigrations.run(s);
		assertEquals(once, s.values);
	}

	@Test
	public void theKeysAreTheConfigsOwn()
	{
		// The migrations write the keys the config reads, so a rename there must show up here.
		assertEquals("showOverlay", SettingsMigrations.SHOW_OVERLAY_KEY);
		assertEquals("overlayShowLoadout", SettingsMigrations.SHOW_LOADOUT_KEY);
		assertEquals("overlayHoldSeconds", SettingsMigrations.HOLD_SECONDS_KEY);
		assertEquals("overlayCovers", SettingsMigrations.COVERS_KEY);
		assertTrue(SettingsLayoutTest.keys().containsAll(java.util.Arrays.asList(SettingsMigrations.SHOW_OVERLAY_KEY,
			SettingsMigrations.SHOW_LOADOUT_KEY, SettingsMigrations.HOLD_SECONDS_KEY, SettingsMigrations.COVERS_KEY)));
	}
}

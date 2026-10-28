package com.pvmlogs;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The one-time moves of saved settings. T-060 dropped the three there were (see {@link SettingsMigrations}); this keeps
 * the harness for the next one. Values are held as RuneLite saves them: strings, enums by name.
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

	@Test
	public void aFreshConfigIsLeftAsItIs()
	{
		// What RuneLite has saved by startUp: every declared setting at its default.
		final MapSettings s = new MapSettings();
		s.values.put("showOverlay", "true");
		s.values.put("overlayShowLoadout", "true");
		s.values.put("overlayBossesOnly", "true");
		s.values.put("overlayHoldSeconds", "60");
		s.values.put("overlayCovers", "FIGHT");
		final Map<String, String> before = new HashMap<>(s.values);
		SettingsMigrations.run(s);
		assertEquals(before, s.values);
	}
}

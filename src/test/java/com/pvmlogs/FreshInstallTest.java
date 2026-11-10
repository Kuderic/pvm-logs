package com.pvmlogs;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.config.ConfigItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * What a player gets on a fresh RuneLite profile (T-067): the plugin installed from the hub with nothing saved. RuneLite
 * then writes every setting's default into the profile before {@code startUp}, so the defaults are what they get.
 *
 * <p>{@link SettingsLayoutTest#theDefaults()} checks each default by name. This pins the whole set by key, so a setting
 * added later fails here until its default for new installs is decided and written down.
 */
public class FreshInstallTest
{
	/** The defaults decided for new installs, by key, as RuneLite saves them (enums by name). */
	private static Map<String, String> decided()
	{
		final Map<String, String> d = new TreeMap<>();
		// Overlays
		d.put("showOverlay", "true");
		// "Show DPS overlay" (the key is the old loadout overlay's, T-073).
		d.put("overlayShowLoadout", "true");
		// T-073: "Always show DPS overlay" off; it simplifies after 60 s without an attack.
		d.put("overlayAlwaysDps", "false");
		d.put("overlayDpsIdleSeconds", "60");
		// Bosses only: on for new installs (Eric, 2026-10-27, T-040).
		d.put("overlayBossesOnly", "true");
		d.put("overlayHoldSeconds", "60");
		d.put("overlayCovers", "FIGHT");
		// Fight overlay
		d.put("overlayLuck", "true");
		// "Under Execution": Costing only for new installs (T-073, Eric 2026-10-28).
		d.put("overlayParts", "COSTING");
		d.put("overlayDetails", "true");
		// Side panel: live and charts on; larger and smooth text off, so it looks as it always has (T-053).
		d.put("panelLive", "true");
		d.put("panelCharts", "true");
		d.put("panelLargerText", "false");
		d.put("panelSmoothFont", "false");
		// T-124: on by default, as RuneLite's DPS Counter shares when enabled (flagged for Eric in the brief).
		d.put("shareWithParty", "true");
		// Data & debugging: no debug file is written until the player turns it on.
		d.put("debugLogging", "false");
		return d;
	}

	/** Every setting's default, by key, as a fresh profile gets it. */
	private static Map<String, String> defaults() throws Exception
	{
		final PvmPerformanceConfig fresh = new PvmPerformanceConfig()
		{
		};
		final Map<String, String> out = new TreeMap<>();
		for (Method m : PvmPerformanceConfig.class.getDeclaredMethods())
		{
			final ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				final Object value = m.invoke(fresh);
				out.put(item.keyName(), value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value));
			}
		}
		return out;
	}

	@Test
	public void everySettingHasADefault()
	{
		final List<String> missing = new ArrayList<>();
		for (Method m : PvmPerformanceConfig.class.getDeclaredMethods())
		{
			if (m.getAnnotation(ConfigItem.class) != null && !m.isDefault())
			{
				missing.add(m.getName());
			}
		}
		assertEquals("settings without a default leave a fresh profile with nothing saved", new ArrayList<String>(),
			missing);
	}

	@Test
	public void aFreshProfileGetsTheDecidedDefaults() throws Exception
	{
		assertEquals(decided(), defaults());
	}

	/** Nothing saved, so nothing to move: the migrations leave a fresh profile exactly as RuneLite wrote it. */
	@Test
	public void aFreshProfileNeedsNoMigration() throws Exception
	{
		final Map<String, String> saved = new LinkedHashMap<>(defaults());
		final List<String> writes = new ArrayList<>();
		SettingsMigrations.run(new SettingsMigrations.Settings()
		{
			public String get(String key)
			{
				return saved.get(key);
			}

			public void set(String key, String value)
			{
				writes.add("set " + key);
			}

			public void unset(String key)
			{
				writes.add("unset " + key);
			}
		});
		assertTrue("a fresh profile was changed: " + writes, writes.isEmpty());
	}
}

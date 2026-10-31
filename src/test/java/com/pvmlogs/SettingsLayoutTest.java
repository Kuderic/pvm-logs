package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import org.junit.Test;

/**
 * T-040: the settings panel as approved. Four sections, every setting in one,
 * the kept keys kept, the removed ones no longer read, and the defaults.
 */
public class SettingsLayoutTest
{
	/** Every setting's key, in the panel's order (section, then position). */
	static List<String> keys()
	{
		final Map<String, Integer> sections = sections().entrySet().stream()
			.collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().position()));
		return items().stream()
			.sorted(Comparator.comparingInt((ConfigItem i) -> sections.get(i.section())).thenComparingInt(ConfigItem::position))
			.map(ConfigItem::keyName)
			.collect(Collectors.toList());
	}

	private static List<ConfigItem> items()
	{
		final List<ConfigItem> items = new ArrayList<>();
		for (Method m : PvmPerformanceConfig.class.getDeclaredMethods())
		{
			final ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				items.add(item);
			}
		}
		return items;
	}

	/** The sections by their key (the constant's value). */
	private static Map<String, ConfigSection> sections()
	{
		final Map<String, ConfigSection> sections = new LinkedHashMap<>();
		for (Field f : PvmPerformanceConfig.class.getDeclaredFields())
		{
			final ConfigSection section = f.getAnnotation(ConfigSection.class);
			if (section != null)
			{
				try
				{
					sections.put((String) f.get(null), section);
				}
				catch (IllegalAccessException e)
				{
					throw new AssertionError(e);
				}
			}
		}
		return sections;
	}

	@Test
	public void fourSectionsInOrderAndOnlyDataClosed()
	{
		final List<ConfigSection> sorted = new ArrayList<>(sections().values());
		sorted.sort(Comparator.comparingInt(ConfigSection::position));
		assertEquals(Arrays.asList("Overlays", "Fight overlay", "Side panel", "Data & debugging"),
			sorted.stream().map(ConfigSection::name).collect(Collectors.toList()));
		assertEquals(Arrays.asList(false, false, false, true),
			sorted.stream().map(ConfigSection::closedByDefault).collect(Collectors.toList()));
	}

	@Test
	public void everySettingSitsInASection()
	{
		final Map<String, ConfigSection> sections = sections();
		for (ConfigItem item : items())
		{
			assertTrue(item.keyName(), sections.containsKey(item.section()));
		}
	}

	@Test
	public void theSettingsInTheirOrder()
	{
		// Every kept setting keeps its key, so users keep their values (T-040 proposal, section 5).
		assertEquals(Arrays.asList(
			"showOverlay", "overlayShowLoadout", "overlayAlwaysDps", "overlayDpsIdleSeconds", "overlayBossesOnly",
			"overlayHoldSeconds",
			"overlayCovers", "overlayLuck", "overlayParts", "overlayDetails",
			"panelLive", "panelCharts", "panelLargerText", "panelSmoothFont", "shareWithParty",
			"debugLogging"), keys());
	}

	/** T-125: sharing sits with the Party tab it feeds, in an open section, not in Data & debugging. */
	@Test
	public void sharingIsInTheSidePanelSection()
	{
		final ConfigItem share = items().stream().filter(i -> i.keyName().equals("shareWithParty")).findFirst().get();
		assertEquals(PvmPerformanceConfig.SIDE_PANEL_SECTION, share.section());
		assertFalse(sections().get(share.section()).closedByDefault());
	}

	@Test
	public void noTwoSettingsShareASlot()
	{
		final List<String> slots = items().stream().map(i -> i.section() + "/" + i.position()).collect(Collectors.toList());
		assertEquals(slots.size(), slots.stream().distinct().count());
	}

	@Test
	public void theNamesAsProposed()
	{
		final Map<String, String> names = items().stream().collect(Collectors.toMap(ConfigItem::keyName, ConfigItem::name));
		assertEquals("Show fight overlay", names.get("showOverlay"));
		// T-073: the loadout overlay is the DPS overlay; its key stays.
		assertEquals("Show DPS overlay", names.get("overlayShowLoadout"));
		assertEquals("Always show DPS overlay", names.get("overlayAlwaysDps"));
		assertEquals("DPS overlay simplifies after (s)", names.get("overlayDpsIdleSeconds"));
		assertEquals("Bosses only", names.get("overlayBossesOnly"));
		assertEquals("Keep last fight up (s)", names.get("overlayHoldSeconds"));
		assertEquals("Covers", names.get("overlayCovers"));
		assertEquals("Luck", names.get("overlayLuck"));
		assertEquals("Under Execution", names.get("overlayParts"));
		assertEquals("Summary after the fight", names.get("overlayDetails"));
		assertEquals("Update during fights", names.get("panelLive"));
		assertEquals("Charts", names.get("panelCharts"));
		// T-053.
		assertEquals("Larger text", names.get("panelLargerText"));
		assertEquals("Smooth font", names.get("panelSmoothFont"));
		assertEquals("Debug logging", names.get("debugLogging"));
		// T-124.
		assertEquals("Share with my RuneLite party", names.get("shareWithParty"));
		// Eric's standing rule: no "exp" in a label.
		for (String name : names.values())
		{
			assertFalse(name, name.toLowerCase().matches(".*\\bexp\\b.*"));
		}
	}

	@Test
	public void theDefaults()
	{
		final PvmPerformanceConfig d = new PvmPerformanceConfig()
		{
		};
		assertTrue(d.showOverlay());
		assertTrue(d.overlayShowLoadout());
		// On for new installs (Eric, 2026-10-27).
		assertTrue(d.overlayBossesOnly());
		assertEquals(60, d.overlayHoldSeconds());
		assertEquals(OverlayCovers.FIGHT, d.overlayCovers());
		assertTrue(d.overlayLuck());
		// T-073: Costing only for new installs (Eric, 2026-10-28; the brief said Off).
		assertEquals(ExecutionParts.COSTING, d.overlayParts());
		assertFalse(d.overlayAlwaysDps());
		assertEquals(60, d.overlayDpsIdleSeconds());
		assertTrue(d.overlayDetails());
		assertTrue(d.panelLive());
		assertTrue(d.panelCharts());
		// T-053: both off, so the panel looks as it always has.
		assertFalse(d.panelLargerText());
		assertFalse(d.panelSmoothFont());
		assertFalse(d.debugLogging());
		// T-124: on, as RuneLite's DPS Counter shares when it's on (flagged for Eric).
		assertTrue(d.shareWithParty());
	}

	@Test
	public void theHoldIsBoundedZeroToTenMinutes() throws NoSuchMethodException
	{
		final Range range = PvmPerformanceConfig.class.getMethod("overlayHoldSeconds").getAnnotation(Range.class);
		assertEquals(0, range.min());
		assertEquals(PvmPerformanceConfig.MAX_HOLD_SECONDS, range.max());
		assertEquals(600, range.max());
	}

	@Test
	public void theCoversChoicesReadAsProposed()
	{
		assertEquals(Arrays.asList("This fight", "Whole raid", "Whole trip"),
			Arrays.stream(OverlayCovers.values()).map(OverlayCovers::toString).collect(Collectors.toList()));
	}

	@Test
	public void theRemovedKeysAreNoLongerRead() throws IOException
	{
		// Removed settings (T-022, T-040): nothing reads their keys. SettingsMigrations moved them until T-060 gave the
		// plugin its own settings group, where they were never saved.
		final List<String> removed = Arrays.asList("fightTimeoutTicks", "overlaySessionTotals", "raidScope",
			"overlayExpectedOnly");
		for (String key : removed)
		{
			assertFalse(key, keys().contains(key));
			for (Method m : PvmPerformanceConfig.class.getDeclaredMethods())
			{
				assertFalse(key, m.getName().equals(key));
			}
		}
		final List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()))
			{
				if (file.getFileName().toString().equals("SettingsMigrations.java"))
				{
					continue;
				}
				final String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				for (String key : removed)
				{
					if (source.contains("\"" + key + "\"") || source.contains("." + key + "()"))
					{
						offenders.add(file.getFileName() + ": " + key);
					}
				}
			}
		}
		assertEquals(new ArrayList<String>(), offenders);
	}
}

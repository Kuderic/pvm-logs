package com.pvmlogs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(PvmPerformanceConfig.GROUP)
public interface PvmPerformanceConfig extends Config
{
	/**
	 * Settings group. Arodab's original, so users of his Plugin Hub entry keep their settings on update.
	 */
	String GROUP = "pvmperformance";
	/**
	 * The folder under {@code ~/.runelite/} where Arodab's plugin, and this one until T-009, kept history, CSV
	 * exports, debug logs and cached data. Hub plugins now get their files through RuneLite's {@code Filepath}, which
	 * lives in {@code ~/.runelite/plugin-data/<INTERNAL_NAME>/}. The descriptor names this as its legacy folder, so
	 * RuneLite moves it there the first time the plugin asks for its folder, and nobody's history is lost.
	 */
	String DATA_DIR = "pvm-performance-tracker";
	/**
	 * The Plugin Hub entry's internal name: Arodab's, since the plan is to release on his entry. RuneLite names the
	 * plugin's data folder after it ({@code ~/.runelite/plugin-data/<this>/}).
	 */
	String INTERNAL_NAME = "pvm-performance-tracker";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show the live performance overlay while fighting",
		position = 1
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayBossesOnly",
		name = "Overlay: bosses only",
		description = "Only show the overlays while fighting a boss (from the hiscores list)",
		position = 2
	)
	default boolean overlayBossesOnly()
	{
		return false;
	}

	// The overlay's content settings share position 3, so they sit together and in name order (T-014).
	// "Loadout only" (key overlayExpectedOnly) is gone (T-022): "Show overlay" off with the loadout overlay on does the
	// same. LoadoutOverlay.migrateLoadoutOnly moves it once.
	@ConfigItem(
		keyName = "overlayShowLoadout",
		name = "Show loadout overlay",
		description = "Show a second box with your attack's expected DPS, max hit, accuracy and spec against the "
			+ "target, before, during and after the fight. Drag it anywhere",
		position = 1
	)
	default boolean overlayShowLoadout()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayLuck",
		name = "Show luck",
		description = "Show how many seconds the rolls saved or cost: your damage against what your attacks were "
			+ "expected to deal",
		position = 3
	)
	default boolean overlayLuck()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayParts",
		name = "Execution parts",
		description = "Under Execution, list uptime, prayer, gear and stat boost with the seconds each cost, then the "
			+ "damage you took and the time spent eating. Costing only lists the parts that cost real time",
		position = 3
	)
	default ExecutionParts overlayParts()
	{
		return ExecutionParts.ALL;
	}

	@ConfigItem(
		keyName = "overlayDetails",
		name = "Details after the fight",
		description = "Once the fight ends, add your average time, hits against expected, thrall, recoil and "
			+ "burn/poison damage, what the boss healed, and a breakdown of the damage you took",
		position = 3
	)
	default boolean overlayDetails()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlaySessionTotals",
		name = "Overlay: whole trip",
		description = "Keep the overlay running across kills instead of resetting each fight, "
			+ "so a whole trip can be judged rather than one kill. Reset it from the side panel",
		position = 4
	)
	default boolean overlaySessionTotals()
	{
		return false;
	}

	@ConfigItem(
		keyName = "raidScope",
		name = "Inside a raid, show",
		description = "Whether the overlay reports the room being fought or the whole raid so far",
		position = 5
	)
	default RaidScope raidScope()
	{
		return RaidScope.ROOM;
	}

	@ConfigItem(
		keyName = "fightTimeoutTicks",
		name = "Fight timeout (ticks)",
		description = "End the current fight after this many game ticks with no damage dealt. Also how long a finished "
			+ "fight stays on the overlay: at 100 that is a minute after the kill",
		position = 11
	)
	default int fightTimeoutTicks()
	{
		// Ten was too eager. A fight is not over because nothing landed for six seconds - a safespot, a phase, a walk back
		// into range - and every fight it ended early took its ticks with it.
		return 100;
	}

	@ConfigItem(
		keyName = "debugLogging",
		name = "Debug logging",
		description = "Stream everything the tracker sees - fights, attacks, hitsplats, every tick and why it counted - "
			+ "to a file beside the CSV exports, for as long as it is on. You usually do not need this: the side panel's "
			+ "Save debug log button writes out the last few thousand lines whenever you ask, with no warning needed. "
			+ "Use this one for a session spent chasing something, or when the client is crashing",
		position = 12
	)
	default boolean debugLogging()
	{
		return false;
	}

	// The side panel's settings (side-panel-design.md §7, T-033). Folded sections are remembered under hidden
	// "panelFold.<id>" keys in this group, which the settings panel doesn't list.
	@ConfigSection(
		name = "Side panel",
		description = "The side panel's Fight, History and Trip tabs",
		position = 13
	)
	String SIDE_PANEL_SECTION = "sidePanel";

	@ConfigItem(
		keyName = "panelLive",
		name = "Update during fights",
		description = "Update the side panel every tick while you fight. Off: the Fight tab fills in when the fight "
			+ "ends, for slower PCs",
		position = 1,
		section = SIDE_PANEL_SECTION
	)
	default boolean panelLive()
	{
		return true;
	}

	// Added by T-035 ahead of T-033; the key stays.
	@ConfigItem(
		keyName = "panelCharts",
		name = "Charts",
		description = "The side panel's damage chart and tick strip. Off: they are not shown, and nothing is recorded for "
			+ "them either",
		position = 2,
		section = SIDE_PANEL_SECTION
	)
	default boolean panelCharts()
	{
		return true;
	}
}

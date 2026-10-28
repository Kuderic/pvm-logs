package com.pvmlogs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

/**
 * The settings, by module (T-040): the two overlays together, then the fight
 * overlay's own, the side panel, and a closed Data &amp; debugging section.
 * <p>
 * Keys are users' saved values: a kept setting keeps its key whatever it is
 * now called. A setting whose meaning changed gets a new key, and
 * {@link SettingsMigrations} carries the old value over once. RuneLite writes
 * every setting's default into the saved config when the plugin loads, so a
 * changed default reaches new installs only.
 */
@ConfigGroup(PvmPerformanceConfig.GROUP)
public interface PvmPerformanceConfig extends Config
{
	/**
	 * The name a player sees: the plugin list, the side panel's tooltip, dialog titles, chat messages. Luck vs Skill
	 * since T-060, its own Plugin Hub entry. It began as a fork of Arodab's PvM Performance Tracker.
	 */
	String DISPLAY_NAME = "Luck vs Skill";
	/**
	 * Settings group (T-060). Not Arodab's {@code pvmperformance}: that would share settings with his plugin for
	 * anyone who has both. Users' saved settings live under it, so it never changes after release.
	 */
	String GROUP = "luckvsskill";
	/**
	 * The Plugin Hub entry's internal name (T-060). RuneLite names the plugin's data folder after it
	 * ({@code ~/.runelite/plugin-data/<this>/}), so it never changes after release either. There is no
	 * {@code legacyDataDirectory}: Arodab's folder stays his plugin's.
	 */
	String INTERNAL_NAME = "luck-vs-skill";

	/** How long a finished fight stays on the overlays at most (T-040): 600 s. */
	int MAX_HOLD_SECONDS = 600;

	// ---- Overlays: what applies to both boxes ----

	@ConfigSection(
		name = "Overlays",
		description = "The two boxes over the game: the fight overlay and the loadout overlay",
		position = 0
	)
	String OVERLAYS_SECTION = "overlays";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show fight overlay",
		description = "The box with Luck, Damage/expected, DPS and Execution for the fight you're in, and its Summary "
			+ "once it ends",
		position = 1,
		section = OVERLAYS_SECTION
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayShowLoadout",
		name = "Show loadout overlay",
		description = "A second box with your attack's expected DPS, max hit, accuracy and spec against the target, "
			+ "before, during and after the fight. Drag it anywhere",
		position = 2,
		section = OVERLAYS_SECTION
	)
	default boolean overlayShowLoadout()
	{
		return true;
	}

	// On for new installs (Eric, 2026-10-27, T-040). Everyone who has run the plugin already has false saved, as RuneLite
	// writes the defaults on load, so they keep it.
	@ConfigItem(
		keyName = "overlayBossesOnly",
		name = "Bosses only",
		description = "Show the overlays only while fighting a boss, or anything in a boss's room. The side panel and "
			+ "your history still record every fight",
		position = 3,
		section = OVERLAYS_SECTION
	)
	default boolean overlayBossesOnly()
	{
		return true;
	}

	// The second job of the old "Fight timeout (ticks)" (T-040). Its first, ending a quiet fight, is the plugin's
	// FIGHT_TIMEOUT_TICKS now.
	@Range(min = 0, max = MAX_HOLD_SECONDS)
	@ConfigItem(
		keyName = "overlayHoldSeconds",
		name = "Keep last fight up (s)",
		description = "How long a finished fight stays on the overlays. 0 keeps it up until your next fight",
		position = 4,
		section = OVERLAYS_SECTION
	)
	default int overlayHoldSeconds()
	{
		return 60;
	}

	// ---- Fight overlay: what it adds up, and which rows it shows ----

	@ConfigSection(
		name = "Fight overlay",
		description = "What the fight overlay adds up, and which rows it shows",
		position = 1
	)
	String FIGHT_OVERLAY_SECTION = "fightOverlay";

	// Arodab's "Overlay: whole trip" and "Inside a raid, show" as one choice (T-040).
	@ConfigItem(
		keyName = "overlayCovers",
		name = "Covers",
		description = "This fight: the fight you're in, with a boss's adds and phases (in a raid, the room). Whole raid: "
			+ "the raid so far. Whole trip: every fight since the trip started; start a new one from the side panel",
		position = 1,
		section = FIGHT_OVERLAY_SECTION
	)
	default OverlayCovers overlayCovers()
	{
		return OverlayCovers.FIGHT;
	}

	@ConfigItem(
		keyName = "overlayLuck",
		name = "Luck",
		description = "Whether the rolls went your way: top or bottom N% of fights like this, and the seconds they saved "
			+ "or cost",
		position = 2,
		section = FIGHT_OVERLAY_SECTION
	)
	default boolean overlayLuck()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayParts",
		name = "Under Execution",
		description = "The rows under Execution: uptime, prayer, gear and stat boost with the seconds each cost, then "
			+ "damage taken and eating. Costing only lists the parts that cost real time. Off leaves Execution alone",
		position = 3,
		section = FIGHT_OVERLAY_SECTION
	)
	default ExecutionParts overlayParts()
	{
		return ExecutionParts.ALL;
	}

	@ConfigItem(
		keyName = "overlayDetails",
		name = "Summary after the fight",
		description = "Once the fight ends: your average time, hits against expected, thrall, recoil and burn damage, "
			+ "what the boss healed, and the damage you took",
		position = 4,
		section = FIGHT_OVERLAY_SECTION
	)
	default boolean overlayDetails()
	{
		return true;
	}

	// ---- Side panel (side-panel-design.md §7, T-033) ----

	// Folded sections are remembered under hidden "panelFold.<id>" keys in this group, which the settings panel doesn't
	// list.
	@ConfigSection(
		name = "Side panel",
		description = "The side panel's Fight, History and Trip tabs",
		position = 2
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

	// T-053 (side panel chat): the side panel's text. Read when the panel opens.
	@ConfigItem(
		keyName = "panelLargerText",
		name = "Larger text",
		description = "Bigger text in the side panel. Sharp at 100% and 200% scaling.",
		position = 3,
		section = SIDE_PANEL_SECTION
	)
	default boolean panelLargerText()
	{
		return false;
	}

	@ConfigItem(
		keyName = "panelSmoothFont",
		name = "Smooth font",
		description = "Draws the side panel in your system's font, so it stays sharp at 125% or 150% scaling.",
		position = 4,
		section = SIDE_PANEL_SECTION
	)
	default boolean panelSmoothFont()
	{
		return false;
	}

	// ---- Data & debugging ----

	@ConfigSection(
		name = "Data & debugging",
		description = "Files for chasing a problem",
		position = 3,
		closedByDefault = true
	)
	String DATA_SECTION = "data";

	@ConfigItem(
		keyName = "debugLogging",
		name = "Debug logging",
		description = "Stream everything the tracker sees - fights, attacks, hitsplats, every tick and why it counted - "
			+ "to a file beside the CSV exports, for as long as it is on. You usually do not need this: the side panel's "
			+ "Save debug log button writes out the last few thousand lines whenever you ask, with no warning needed. "
			+ "Use this one for a session spent chasing something, or when the client is crashing",
		position = 1,
		section = DATA_SECTION
	)
	default boolean debugLogging()
	{
		return false;
	}
}

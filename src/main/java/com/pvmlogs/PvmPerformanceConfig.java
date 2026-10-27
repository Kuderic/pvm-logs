package com.pvmlogs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

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
		description = "Only show the overlay while fighting a boss (from the hiscores list)",
		position = 2
	)
	default boolean overlayBossesOnly()
	{
		return false;
	}

	// The overlay's content settings share position 3, so they sit together and in name order (T-014).
	@ConfigItem(
		keyName = "overlayExpectedOnly",
		name = "Loadout only",
		description = "Show only what the loadout does against the target - max hit, accuracy, expected DPS - "
			+ "and nothing from the fight",
		position = 3
	)
	default boolean overlayExpectedOnly()
	{
		// The key is Arodab's "Expected figures only", relabelled, so players who set it keep it.
		return false;
	}

	@ConfigItem(
		keyName = "overlayShowLoadout",
		name = "Show loadout before the first attack",
		description = "Once you target something, and until your first attack, show your max hit, accuracy and "
			+ "expected DPS against it. Off shows the name alone",
		position = 3
	)
	default boolean overlayShowLoadout()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayLuck",
		name = "Show luck",
		description = "Show how many seconds the rolls saved or cost, against your expected damage, and after "
			+ "the fight how long the same play would have taken at average luck",
		position = 3
	)
	default boolean overlayLuck()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayParts",
		name = "Execution parts",
		description = "Under Execution, list uptime, stat boost, prayer and gear with the seconds each cost. "
			+ "Costing only lists those that cost real time",
		position = 3
	)
	default ExecutionParts overlayParts()
	{
		return ExecutionParts.ALL;
	}

	@ConfigItem(
		keyName = "overlayDetails",
		name = "Details after the fight",
		description = "Once the fight ends, add duration against expected, hits, thrall and burn/poison damage, "
			+ "what the boss healed and ticks spent eating",
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
		keyName = "blowpipeDart",
		name = "Blowpipe dart",
		description = "Which dart your blowpipe is loaded with. A blowpipe keeps its darts "
			+ "inside itself and the game does not say which they are, so the max hit is "
			+ "well short without this",
		position = 6
	)
	default BlowpipeDart blowpipeDart()
	{
		return BlowpipeDart.DRAGON;
	}

	@ConfigItem(
		keyName = "meleeBoostGoal",
		name = "Melee boost",
		description = "The melee boost you mean to be holding. Efficiency compares what your "
			+ "attacks were set up for against what this would have given. Overloads and "
			+ "smelling salts are detected on their own and raise it while they run",
		position = 6
	)
	default MeleeBoost meleeBoostGoal()
	{
		return MeleeBoost.SUPER_COMBAT;
	}

	@ConfigItem(
		keyName = "rangedBoostGoal",
		name = "Ranged boost",
		description = "The ranged boost you mean to be holding",
		position = 6
	)
	default RangedBoost rangedBoostGoal()
	{
		return RangedBoost.RANGING;
	}

	@ConfigItem(
		keyName = "magicBoostGoal",
		name = "Magic boost",
		description = "The magic boost you mean to be holding",
		position = 6
	)
	default MagicBoost magicBoostGoal()
	{
		return MagicBoost.SATURATED_HEART;
	}

	@ConfigItem(
		keyName = "meleePrayerGoal",
		name = "Melee prayer",
		description = "The melee prayer you mean to hold up. Efficiency compares the damage "
			+ "your attacks were set up for against what this prayer would have given",
		position = 7
	)
	default PrayerChoice meleePrayerGoal()
	{
		return PrayerChoice.PIETY;
	}

	@ConfigItem(
		keyName = "rangedPrayerGoal",
		name = "Ranged prayer",
		description = "The ranged prayer you mean to hold up",
		position = 8
	)
	default PrayerChoice rangedPrayerGoal()
	{
		return PrayerChoice.RIGOUR;
	}

	@ConfigItem(
		keyName = "magicPrayerGoal",
		name = "Magic prayer",
		description = "The magic prayer you mean to hold up",
		position = 9
	)
	default PrayerChoice magicPrayerGoal()
	{
		return PrayerChoice.AUGURY;
	}

	@ConfigItem(
		keyName = "slayerHelmetOffTask",
		name = "Slayer helmet off-task",
		description = "Wearing a slayer helmet while not on task, so its bonus does not count",
		position = 10
	)
	default boolean slayerHelmetOffTask()
	{
		return false;
	}

	@ConfigItem(
		keyName = "deliberateItems",
		name = "Worn on purpose",
		description = "Items you wear for something the damage figures can't see, such as healing. While you wear one, "
			+ "a better-damage item for its slot isn't counted as a missed switch. Item names, separated by commas",
		position = 10
	)
	default String deliberateItems()
	{
		// Worn for its healing, at a known cost in damage against a torture. Clear this to have that cost counted.
		return "Amulet of blood fury";
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
}

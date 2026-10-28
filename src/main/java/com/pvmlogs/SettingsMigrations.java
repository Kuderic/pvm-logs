package com.pvmlogs;

import net.runelite.client.config.ConfigManager;

/**
 * One-time moves of saved settings whose key changed, run in the plugin's
 * {@code startUp} before the overlays are added.
 * <p>
 * Each goes by the old key being saved, then unsets it, which is also what
 * makes it run once. It never asks whether the new key is set: RuneLite writes
 * every declared setting's default into the saved config when the plugin loads,
 * before {@code startUp} (1.13.4's {@code PluginManager} →
 * {@code ConfigManager.setDefaultConfiguration(config, false)}), so the new key
 * is always there by then. For the same reason the old keys are saved for
 * everyone who ever ran the plugin, at their old defaults when never changed;
 * each migration maps those defaults to the new ones.
 */
final class SettingsMigrations
{
	/** The removed "Loadout only" (Arodab's "Expected figures only"), T-022. */
	static final String LOADOUT_ONLY_KEY = "overlayExpectedOnly";
	static final String SHOW_OVERLAY_KEY = "showOverlay";
	static final String SHOW_LOADOUT_KEY = "overlayShowLoadout";
	/** The removed "Fight timeout (ticks)", T-040. */
	static final String FIGHT_TIMEOUT_KEY = "fightTimeoutTicks";
	static final String HOLD_SECONDS_KEY = "overlayHoldSeconds";
	/** The removed "Overlay: whole trip" and "Inside a raid, show", T-040. */
	static final String SESSION_TOTALS_KEY = "overlaySessionTotals";
	static final String RAID_SCOPE_KEY = "raidScope";
	static final String COVERS_KEY = "overlayCovers";

	private SettingsMigrations()
	{
	}

	/** The saved settings the migrations read and write, so they can be tested without a {@link ConfigManager}. */
	interface Settings
	{
		/** The saved value as RuneLite stores it (enums by name), or null when nothing is saved. */
		String get(String key);

		void set(String key, String value);

		void unset(String key);

		static Settings of(ConfigManager configManager)
		{
			return new Settings()
			{
				public String get(String key)
				{
					return configManager.getConfiguration(PvmPerformanceConfig.GROUP, key);
				}

				public void set(String key, String value)
				{
					configManager.setConfiguration(PvmPerformanceConfig.GROUP, key, value);
				}

				public void unset(String key)
				{
					configManager.unsetConfiguration(PvmPerformanceConfig.GROUP, key);
				}
			};
		}
	}

	/** Every migration, oldest first. */
	static void run(Settings settings)
	{
		migrateLoadoutOnly(settings);
		migrateFightTimeout(settings);
		migrateCovers(settings);
	}

	/**
	 * "Loadout only" (T-022): it showed the loadout and nothing from the fight,
	 * which is now the fight overlay hidden and the loadout overlay shown. Off,
	 * or never set, only the old key goes.
	 */
	static void migrateLoadoutOnly(Settings settings)
	{
		final String old = settings.get(LOADOUT_ONLY_KEY);
		if (old == null)
		{
			return;
		}
		if (Boolean.parseBoolean(old))
		{
			settings.set(SHOW_OVERLAY_KEY, "false");
			settings.set(SHOW_LOADOUT_KEY, "true");
		}
		settings.unset(LOADOUT_ONLY_KEY);
	}

	/**
	 * "Fight timeout (ticks)" (T-040) did two jobs. Ending a quiet fight is now
	 * the plugin's fixed {@code FIGHT_TIMEOUT_TICKS}; how long a finished fight
	 * stays up is "Keep last fight up (s)". The saved ticks become those seconds,
	 * 0.6 a tick, rounded, kept between 1 and {@link PvmPerformanceConfig#MAX_HOLD_SECONDS}:
	 * 100 (the old default) is 60, the new default. A value that isn't a number
	 * leaves the new setting at its default.
	 */
	static void migrateFightTimeout(Settings settings)
	{
		final String old = settings.get(FIGHT_TIMEOUT_KEY);
		if (old == null)
		{
			return;
		}
		try
		{
			final long ticks = Long.parseLong(old.trim());
			final long seconds = Math.round(ticks * LedgerRows.SECONDS_PER_TICK);
			settings.set(HOLD_SECONDS_KEY, String.valueOf(Math.max(1, Math.min(PvmPerformanceConfig.MAX_HOLD_SECONDS,
				seconds))));
		}
		catch (NumberFormatException e)
		{
			// Nothing to carry over.
		}
		settings.unset(FIGHT_TIMEOUT_KEY);
	}

	/**
	 * "Overlay: whole trip" and "Inside a raid, show" (T-040) as one choice. The
	 * trip won over the raid before, so it does here: whole trip on is
	 * {@link OverlayCovers#TRIP}; else a whole-raid scope is {@link OverlayCovers#RAID};
	 * else {@link OverlayCovers#FIGHT}. Runs when either old key is saved; both go.
	 */
	static void migrateCovers(Settings settings)
	{
		final String trip = settings.get(SESSION_TOTALS_KEY);
		final String raid = settings.get(RAID_SCOPE_KEY);
		if (trip == null && raid == null)
		{
			return;
		}
		final OverlayCovers covers = Boolean.parseBoolean(trip) ? OverlayCovers.TRIP
			: "RAID".equals(raid) ? OverlayCovers.RAID : OverlayCovers.FIGHT;
		settings.set(COVERS_KEY, covers.name());
		settings.unset(SESSION_TOTALS_KEY);
		settings.unset(RAID_SCOPE_KEY);
	}
}

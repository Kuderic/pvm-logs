package com.pvmlogs;

import net.runelite.client.config.ConfigManager;

/**
 * One-time moves of saved settings whose key changed, run in the plugin's
 * {@code startUp} before the overlays are added. There are none yet.
 * <p>
 * T-060 dropped the three it had (T-022's "Loadout only", T-040's fight
 * timeout and overlay scope). Each read an old key in Arodab's settings group,
 * and the plugin's group is its own since then, so none of them could ever
 * find anything to move.
 * <p>
 * How to write one, when a released setting's meaning changes: go by the old
 * key being saved, then unset it, which is also what makes it run once. Never
 * ask whether the new key is set: RuneLite writes every declared setting's
 * default into the saved config when the plugin loads, before {@code startUp}
 * (1.13.4's {@code PluginManager} →
 * {@code ConfigManager.setDefaultConfiguration(config, false)}), so the new key
 * is always there by then. For the same reason the old key is saved for
 * everyone who ever ran a version that declared it, at its old default when
 * never changed; map that default to the new one.
 */
final class SettingsMigrations
{
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

	/** Every migration, oldest first. None yet. */
	static void run(Settings settings)
	{
	}
}

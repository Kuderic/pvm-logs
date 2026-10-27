package com.pvmlogs;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The loadout against the target, in a box of its own (T-022; Eric,
 * 2026-10-27: "split the weapon dps section of the panel into a new panel"):
 * the attack's name and expected DPS, then max hit, accuracy and spec. No title
 * (Eric): the attack row heads it. {@code ! Boss data unsure} goes on top when
 * the boss's data is suspect, as the accuracy is what that gets wrong.
 * <p>
 * Shown whenever the Ledger has a fight to show (before the first attack,
 * during it, and after it until the fight timeout), under the same "bosses
 * only" rule, and placed and hidden on its own. The rows are
 * {@link LedgerRows#loadout(LedgerRows.Loadout, boolean)} and drawn through
 * {@link PvmPerformanceOverlay#lines}, so they look as they did in the Ledger.
 * The background is never set.
 */
class LoadoutOverlay extends OverlayPanel
{
	/** The key of the removed "Loadout only" setting (Arodab's "Expected figures only"), read once to migrate it. */
	static final String LOADOUT_ONLY_KEY = "overlayExpectedOnly";
	static final String SHOW_OVERLAY_KEY = "showOverlay";
	static final String SHOW_LOADOUT_KEY = "overlayShowLoadout";

	private final PvmPerformancePlugin plugin;
	private final PvmPerformanceConfig config;
	private final Client client;

	// Built once a tick: the figures change only when the gear, the target or the levels do, which the plugin reads a
	// tick at a time, and render runs 50+ times a second.
	private final PvmPerformanceOverlay.OncePerKey<PvmPerformanceOverlay.Lines> lines =
		new PvmPerformanceOverlay.OncePerKey<>();

	@Inject
	LoadoutOverlay(PvmPerformancePlugin plugin, PvmPerformanceConfig config, Client client)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		// Stacks under the Ledger by default; the player can drag it anywhere.
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.overlayShowLoadout())
		{
			return null;
		}
		final Fight fight = plugin.getDisplayFight();
		if (!PvmPerformanceOverlay.onShow(fight, config, System.currentTimeMillis()))
		{
			return null;
		}
		final Font font = graphics.getFont();
		final int saved = PvmPerformanceOverlay.savedWidth(getPreferredSize());
		final List<Object> key = Arrays.asList(client.getTickCount(), fight, font, saved);
		final PvmPerformanceOverlay.Lines built = lines.get(key, () -> PvmPerformanceOverlay.lines(graphics,
			LedgerRows.loadout(loadout(plugin), plugin.getStatsWarning(fight) != null), getPreferredSize()));
		if (built == null)
		{
			return null;
		}
		panelComponent.getChildren().addAll(built.components);
		panelComponent.setPreferredSize(new Dimension(built.width, 0));
		return super.render(graphics);
	}

	/** The plugin's figures for the loadout against the current target. */
	static LedgerRows.Loadout loadout(PvmPerformancePlugin plugin)
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = plugin.getAttackName();
		l.maxHit = plugin.getExpectedMaxHit();
		l.accuracy = plugin.getExpectedAccuracy();
		l.dps = plugin.getExpectedDps();
		if (plugin.getSpecialAttack() != null)
		{
			l.specMaxHit = plugin.getExpectedSpecMaxHit();
			l.specAccuracy = plugin.getExpectedSpecAccuracy();
			l.specAverage = plugin.getExpectedSpecAverageHit();
		}
		return l;
	}

	/** The settings the migration reads and writes, so it can be tested without a {@link ConfigManager}. */
	interface Settings
	{
		String get(String key);

		void set(String key, boolean value);

		void unset(String key);

		static Settings of(ConfigManager configManager)
		{
			return new Settings()
			{
				public String get(String key)
				{
					return configManager.getConfiguration(PvmPerformanceConfig.GROUP, key);
				}

				public void set(String key, boolean value)
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

	/**
	 * Moves the removed "Loadout only" setting, once (T-022): it showed the
	 * loadout and nothing from the fight, which is now the Ledger hidden and the
	 * loadout overlay shown. Off, or never set, only the old key goes.
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
			settings.set(SHOW_OVERLAY_KEY, false);
			settings.set(SHOW_LOADOUT_KEY, true);
		}
		settings.unset(LOADOUT_ONLY_KEY);
	}
}

package com.pvmlogs;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
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
	private final PvmPerformancePlugin plugin;
	private final PvmPerformanceConfig config;
	private final Client client;

	// Built once a tick: the figures change only when the gear, the target or the levels do, which the plugin reads a
	// tick at a time, and render runs 50+ times a second.
	private final PvmPerformanceOverlay.OncePerKey<PvmPerformanceOverlay.Lines> lines =
		new PvmPerformanceOverlay.OncePerKey<>();
	// And drawn from what it drew last time until those lines change (T-047).
	private final OverlayCache drawn = new OverlayCache();

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
		// Timed while Debug logging is on (T-045).
		final PerfProbe perf = plugin == null ? null : plugin.getPerf();
		final long started = PerfProbe.start(perf);
		final Dimension size = draw(graphics);
		PerfProbe.stop(perf, PerfProbe.Slot.LOADOUT_OVERLAY, started);
		return size;
	}

	private Dimension draw(Graphics2D graphics)
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
		return drawn.draw(graphics, built.look(getPreferredSize()), PvmPerformanceOverlay.background(this),
			g -> paint(g, built));
	}

	/** The panel drawn the ordinary way: what {@link OverlayCache} records, or falls back to. */
	private Dimension paint(Graphics2D g, PvmPerformanceOverlay.Lines built)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().addAll(built.components);
		panelComponent.setPreferredSize(new Dimension(built.width, 0));
		return super.render(g);
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
			l.specExtra = plugin.getExpectedSpecExtra();
			// Every extra the plugin counts today is a burn (T-030).
			l.specExtraKind = "burn";
		}
		return l;
	}
}

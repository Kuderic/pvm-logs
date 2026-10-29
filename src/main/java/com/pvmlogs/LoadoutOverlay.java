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
 * The DPS overlay (T-073; T-022's loadout overlay, Eric 2026-10-27: "split the
 * weapon dps section of the panel into a new panel"): {@code vs} the monster
 * the figures are for, the attack's name, its expected DPS, then max hit,
 * accuracy and spec. {@code ! Boss data unsure} goes on top when the boss's data
 * is suspect, as the accuracy is what that gets wrong.
 * <p>
 * Shown whenever the fight overlay has a fight to show (before the first
 * attack, during it, and after it for "Keep last fight up"), under the same
 * "bosses only" rule, and placed and hidden on its own. With "Always show DPS
 * overlay" on it stays up whatever those say, against the last monster fought;
 * and once the player has gone "DPS overlay simplifies after (s)" without
 * attacking, it shows only the attack, max hit and spec, worked out with no
 * monster, until the next attack. Before any fight it shows that too.
 * <p>
 * The class keeps its name: RuneLite saves an overlay's place under it. The
 * rows are {@link LedgerRows#loadout(LedgerRows.Loadout, boolean)}, drawn
 * through {@link PvmPerformanceOverlay#lines}. The background is never set.
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
		final boolean always = config.overlayAlwaysDps();
		if (!shows(fight, always, config, System.currentTimeMillis()))
		{
			return null;
		}
		final Font font = graphics.getFont();
		final int saved = PvmPerformanceOverlay.savedWidth(getPreferredSize());
		final int tick = client.getTickCount();
		final boolean idle = idleView(fight, always, tick, plugin.getLastAttackSeenTick(), config.overlayDpsIdleSeconds());
		final List<Object> key = Arrays.asList(tick, fight, idle, font, saved);
		final PvmPerformanceOverlay.Lines built = lines.get(key, () -> PvmPerformanceOverlay.lines(graphics,
			LedgerRows.loadout(idle ? idleLoadout(plugin) : loadout(plugin, fight),
				!idle && fight != null && plugin.getStatsWarning(fight) != null), getPreferredSize()));
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

	/**
	 * Whether the DPS overlay is up (T-073): always, with "Always show DPS
	 * overlay" on; otherwise when the fight overlay has a fight to show.
	 */
	static boolean shows(Fight fight, boolean always, PvmPerformanceConfig config, long now)
	{
		return always || PvmPerformanceOverlay.onShow(fight, config, now);
	}

	/**
	 * Whether the DPS overlay shows its idle rows (T-073): only with "Always
	 * show" on, after a fight, once it has gone idle. Before any fight there's no
	 * monster anyway, and the plugin's figures already have none.
	 */
	static boolean idleView(Fight fight, boolean always, int tick, int lastAttackTick, int idleSeconds)
	{
		return always && fight != null && idle(tick, lastAttackTick, idleSeconds);
	}

	/**
	 * Whether the DPS overlay has gone idle (T-073): {@code idleSeconds} or more
	 * since the player's last attack (60 s is 100 ticks), or no attack seen yet.
	 * 0 never.
	 */
	static boolean idle(int tick, int lastAttackTick, int idleSeconds)
	{
		if (idleSeconds <= 0)
		{
			return false;
		}
		if (lastAttackTick == Integer.MIN_VALUE)
		{
			return true;
		}
		final int ticks = (int) Math.round(idleSeconds / LedgerRows.SECONDS_PER_TICK);
		return tick - lastAttackTick >= ticks;
	}

	/**
	 * The plugin's figures against the fight on show's target, named on the
	 * {@code vs} line. With no fight, the plugin's figures are already worked
	 * out with no monster, and there's no line.
	 */
	static LedgerRows.Loadout loadout(PvmPerformancePlugin plugin, Fight fight)
	{
		final LedgerRows.Loadout l = loadout(plugin);
		l.versus = versus(fight);
		return l;
	}

	/**
	 * The monster the figures are for (T-073): the fight's own target, which the
	 * plugin works them out against, so in a grouped room the part being fought
	 * ("Dusk", not "Grotesque Guardians"). Null with no fight.
	 */
	static String versus(Fight fight)
	{
		return fight == null ? null : fight.getTargetName();
	}

	/** Idle (T-073): the attack's name, max hit and the spec's max, all with no monster; nothing that needs one. */
	static LedgerRows.Loadout idleLoadout(PvmPerformancePlugin plugin)
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = plugin.getAttackName();
		// The plugin works these out on the tick the overlay goes idle; until it has, the last figures stand in.
		l.maxHit = plugin.getIdleMaxHit() > 0 ? plugin.getIdleMaxHit() : plugin.getExpectedMaxHit();
		if (plugin.getSpecialAttack() != null)
		{
			l.specMaxHit = plugin.getIdleSpecMaxHit() > 0 ? plugin.getIdleSpecMaxHit() : plugin.getExpectedSpecMaxHit();
		}
		return l;
	}

	/** The plugin's figures for the loadout against the current target, as the side panel's Loadout card reads them too. */
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

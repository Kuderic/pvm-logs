package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.util.Text;

/**
 * The live overlay ("Ledger", T-014). What it says is {@link LedgerRows}; this
 * class only picks the scope and state, turns the rows into line components in
 * the player's own fonts, and sizes the panel.
 * <p>
 * The background is never set: RuneLite gives the panel the player's "Overlay
 * color" only while it keeps the standard one.
 */
class PvmPerformanceOverlay extends OverlayPanel
{
	/** RuneLite's standard overlay width; narrower and the rows wrap. */
	static final int MIN_WIDTH = 129;
	/** Past this a long name is better wrapped than allowed to cross the screen. */
	static final int MAX_WIDTH = 400;
	/** The panel's 4 px border each side plus a 12 px gap between the columns. RuneLite's +14 left only 6. */
	static final int PADDING = 20;
	/** PanelComponent's border, both sides together: a line gets the panel's width less this. */
	static final int BORDER = 8;
	/** The least gap a shortened name keeps before the figure beside it. */
	static final int MIN_GAP = 6;

	private final PvmPerformancePlugin plugin;
	private final PvmPerformanceConfig config;
	private final Client client;

	// The panel's lines, built once a tick rather than every frame (T-016): render runs 50+ times a second and the numbers
	// change at most a few times a tick. Built again whenever the key says something on show may have changed.
	private final OncePerKey<Lines> lines = new OncePerKey<>();

	// Your average on the room on show, kept until the room or the history changes: it reads up to 500 saved fights, and
	// render runs every frame.
	private Encounter averageRoom;
	private int averageHistorySize = -1;
	private Fight averageHistoryHead;
	private LedgerHistory.Average average;

	@Inject
	PvmPerformanceOverlay(PvmPerformancePlugin plugin, PvmPerformanceConfig config, Client client)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}
		final Fight fight = plugin.getDisplayFight();
		if (fight == null)
		{
			return null;
		}
		// A finished fight is left up so the kill can be read, until the fight timeout says the fighting is over - the same
		// clock that ended the fight itself.
		if (fight.isEnded()
			&& System.currentTimeMillis() - fight.getEndMillis() > config.fightTimeoutTicks() * 600L)
		{
			return null;
		}
		if (config.overlayBossesOnly() && !plugin.isBoss(fight))
		{
			return null;
		}

		final Encounter room = plugin.getDisplayEncounter();
		final Font font = graphics.getFont();
		// Everything that can change what's on show. The tick covers the loadout, the warning and the clock; the fight's
		// own totals cover a projectile booked or a splat landed between ticks; the rest are the player's choices. The
		// saved width goes in as a number: RuneLite may resize the Dimension it hands back in place.
		final List<Object> key = Arrays.asList(client.getTickCount(), fight, fight.isEnded(), fight.getAttacksMade(),
			fight.getDamageDealt(), fight.getThrallDamage(), fight.getDotDamage(), fight.getHealed(), room,
			config.overlayExpectedOnly(), config.overlaySessionTotals(), config.raidScope(), config.overlayShowLoadout(),
			config.overlayLuck(), config.overlayParts(), config.overlayDetails(), font, savedWidth(getPreferredSize()));
		final Lines built = lines.get(key, () -> build(graphics, fight, room));
		if (built == null)
		{
			return null;
		}
		panelComponent.getChildren().addAll(built.components);
		panelComponent.setPreferredSize(new Dimension(built.width, 0));
		return super.render(graphics);
	}

	/** What one build makes: the line components in order, and the panel's width for them. */
	static final class Lines
	{
		final List<LineComponent> components;
		final int width;

		Lines(List<LineComponent> components, int width)
		{
			this.components = Collections.unmodifiableList(components);
			this.width = width;
		}
	}

	/** The lines for the fight and room on show, or null when there's nothing to show. */
	private Lines build(Graphics2D graphics, Fight fight, Encounter room)
	{
		final boolean warning = plugin.getStatsWarning(fight) != null;
		LedgerRows.Scope scope = null;
		final LedgerRows.State state;
		if (config.overlayExpectedOnly())
		{
			state = LedgerRows.State.loadoutOnly(fight.encounterName(), loadout(), warning);
		}
		else if (isBeforeFirstAttack(fight, room))
		{
			state = LedgerRows.State.preAttack(fight.encounterName(), loadout(), warning);
		}
		else
		{
			final boolean running = !fight.isEnded();
			final SessionTotals session = config.overlaySessionTotals() ? plugin.getSession() : null;
			final Raid raid = session == null && config.raidScope() == RaidScope.RAID ? plugin.getCurrentRaid() : null;
			if (session != null)
			{
				scope = LedgerRows.of(session);
				state = LedgerRows.State.trip(session.getKills(), running, warning).withLoadout(loadout());
			}
			else if (raid != null)
			{
				scope = LedgerRows.of(raid);
				state = LedgerRows.State.raid(raid.getName(), raid.getEncounters().size(), running, warning)
					.withLoadout(loadout());
			}
			else if (room != null)
			{
				scope = LedgerRows.of(room);
				final boolean killed = fight.isEnded() && room.isKilled();
				state = LedgerRows.State.fight(room.getName(), fight.isEnded(), room.isKilled(), warning)
					.withLoadout(loadout())
					.withAverage(killed && room.getRaid() == null ? average(room) : null);
			}
			else
			{
				return null;
			}
		}

		final List<LedgerRows.Row> rows = LedgerRows.build(scope, state, settings());
		if (rows.isEmpty())
		{
			return null;
		}
		final FontRoles fonts = FontRoles.of(graphics.getFont());
		final List<LineComponent> components = new ArrayList<>(rows.size());
		// A width the player dragged the panel to wins over ours (OverlayPanel.render), and a row wider than it wraps
		// onto a second line, so the panel grows taller (Eric, 2026-10-26, "Abyssal tentacle (or)"). The attack's name
		// is shortened to fit instead.
		final int panelWidth = savedWidth(getPreferredSize());
		int widest = 0;
		for (LedgerRows.Row given : rows)
		{
			final Font left = fonts.get(given.leftFont);
			final Font right = fonts.get(given.rightFont);
			final FontMetrics leftMetrics = graphics.getFontMetrics(left);
			final FontMetrics rightMetrics = graphics.getFontMetrics(right);
			final LedgerRows.Row row = fit(given, t -> leftMetrics.stringWidth(Text.removeTags(t)),
				rightMetrics.stringWidth(Text.removeTags(given.right)), panelWidth);
			widest = Math.max(widest, leftMetrics.stringWidth(Text.removeTags(row.left))
				+ rightMetrics.stringWidth(Text.removeTags(row.right)));
			components.add(LineComponent.builder()
				.left(row.left)
				.right(row.right)
				.leftFont(left)
				.rightFont(right)
				.leftColor(new Color(row.leftColour.rgb))
				.rightColor(new Color(row.rightColour.rgb))
				.build());
		}
		return new Lines(components, width(widest));
	}

	/**
	 * One value, kept until its key changes. The overlay's lines are one: built
	 * on the first frame of a tick, reused by the rest of that tick's frames.
	 */
	static final class OncePerKey<V>
	{
		private boolean held;
		private Object key;
		private V value;
		private int builds;

		V get(Object key, Supplier<V> build)
		{
			if (!held || !Objects.equals(key, this.key))
			{
				this.key = key;
				value = build.get();
				held = true;
				builds++;
			}
			return value;
		}

		/** How many times it has built, for tests. */
		int builds()
		{
			return builds;
		}
	}

	/** Your average on this room, from the saved fights; worked out again only when the room or the history changes. */
	private LedgerHistory.Average average(Encounter room)
	{
		final List<Fight> history = plugin.getHistory();
		// The iterator is a snapshot of the list, so a history cleared from the side panel mid-frame can't throw here.
		final Iterator<Fight> newest = history.iterator();
		final Fight head = newest.hasNext() ? newest.next() : null;
		final int size = history.size();
		if (room != averageRoom || size != averageHistorySize || head != averageHistoryHead)
		{
			averageRoom = room;
			averageHistorySize = size;
			averageHistoryHead = head;
			average = LedgerHistory.of(new ArrayList<>(history), room);
		}
		return average;
	}

	/** The width the player saved for the panel, or {@link #MAX_WIDTH} when they haven't resized it. */
	static int savedWidth(Dimension saved)
	{
		return saved == null || saved.width <= 0 ? MAX_WIDTH : saved.width;
	}

	/**
	 * The row as it fits a panel {@code panelWidth} wide. A row with a short
	 * form (the attack's name) that's too wide tries the short form, then cuts it
	 * with "…"; any other row is left to RuneLite, which wraps it.
	 */
	static LedgerRows.Row fit(LedgerRows.Row row, ToIntFunction<String> leftWidth, int rightWidth, int panelWidth)
	{
		final int room = panelWidth - BORDER - rightWidth - (row.right.isEmpty() ? 0 : MIN_GAP);
		if (row.shortLeft == null || leftWidth.applyAsInt(row.left) <= room)
		{
			return row;
		}
		if (leftWidth.applyAsInt(row.shortLeft) <= room)
		{
			return row.withLeft(row.shortLeft);
		}
		return row.withLeft(LedgerRows.cut(row.shortLeft, leftWidth, room));
	}

	/** The widest row's two sides plus the padding, held to the standard width and the cap. */
	static int width(int widestRow)
	{
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widestRow + PADDING));
	}

	/**
	 * Whether the fight on show hasn't started in its own room yet, so the
	 * loadout against its target is what's worth showing. Asked of the fight, not
	 * the room on show: until the new room has an attempt, the room on show is
	 * the last one. A fight on a new add in a room already under way is not "before
	 * the first attack", so a room's adds don't blink the panel back to the
	 * loadout one by one.
	 */
	static boolean isBeforeFirstAttack(Fight fight, Encounter room)
	{
		if (fight == null || fight.isEnded())
		{
			return false;
		}
		final boolean ownRoomUnderway = room != null && room.hasAttempts() && containsSame(room, fight);
		return !ownRoomUnderway;
	}

	private static boolean containsSame(Encounter room, Fight fight)
	{
		for (Fight f : room.getFights())
		{
			if (f == fight)
			{
				return true;
			}
		}
		return false;
	}

	private LedgerRows.Loadout loadout()
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

	private LedgerRows.Settings settings()
	{
		final LedgerRows.Settings s = new LedgerRows.Settings();
		s.showLoadout = config.overlayShowLoadout();
		s.luck = config.overlayLuck();
		s.parts = config.overlayParts() == null ? ExecutionParts.ALL : config.overlayParts();
		s.details = config.overlayDetails();
		return s;
	}

	/**
	 * A figure with what it cost beside it, e.g. "84% (-1.2%)": the cost as a
	 * share of the damage the attacks would have been set up to deal. Nothing is
	 * added when there was no cost or no figure to price it against. The overlay
	 * prices its parts in seconds now (T-014); kept for T-010's test and any
	 * other view that wants the share.
	 */
	static String withCost(String figure, double costShare)
	{
		return costShare >= 0.0005 ? figure + String.format(" (-%.1f%%)", costShare * 100) : figure;
	}

	/**
	 * The three fonts a row can use, picked from the player's interface font
	 * (spec section 6). Our overlay sits TOP_LEFT, so RuneLite hands render() the
	 * "Interface font" setting, which may be any family at any size.
	 */
	static final class FontRoles
	{
		final Font body;
		final Font emphasis;
		final Font detail;

		private FontRoles(Font body, Font emphasis, Font detail)
		{
			this.body = body;
			this.emphasis = emphasis;
			this.detail = detail;
		}

		Font get(LedgerRows.FontRole role)
		{
			switch (role)
			{
				case EMPHASIS:
					return emphasis;
				case DETAIL:
					return detail;
				default:
					return body;
			}
		}

		static FontRoles of(Font body)
		{
			final Font regular = FontManager.getRunescapeFont();
			final Font small = FontManager.getRunescapeSmallFont();
			if (body.getSize() == 16)
			{
				// Regular and Bold share a family; isBold tells them apart.
				if (body.getFamily().equals(regular.getFamily()))
				{
					return body.isBold()
						? new FontRoles(body, body, regular)
						: new FontRoles(body, FontManager.getRunescapeBoldFont(), small);
				}
				if (body.getFamily().equals(small.getFamily()))
				{
					return new FontRoles(body, body, body);
				}
			}
			return new FontRoles(body, body.deriveFont(Font.BOLD), body);
		}
	}
}

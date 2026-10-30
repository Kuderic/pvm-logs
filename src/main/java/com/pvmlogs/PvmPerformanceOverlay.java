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
import net.runelite.client.ui.overlay.components.ComponentConstants;
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
	// And drawn from what it drew last time until those lines change (T-047).
	private final OverlayCache drawn = new OverlayCache();

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
		// Timed while Debug logging is on (T-045).
		final PerfProbe perf = plugin == null ? null : plugin.getPerf();
		final long started = PerfProbe.start(perf);
		final Dimension size = draw(graphics);
		PerfProbe.stop(perf, PerfProbe.Slot.OVERLAY, started);
		return size;
	}

	private Dimension draw(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}
		final Fight fight = plugin.getDisplayFight();
		final Encounter room = plugin.getDisplayEncounter();
		if (!onShow(fight, config, System.currentTimeMillis())
			&& !holdsRun(fight, room, config.overlayBossesOnly()))
		{
			return null;
		}

		final Font font = graphics.getFont();
		// Everything that can change what's on show. The tick covers the warning and the clock; the fight's
		// own totals cover a projectile booked or a splat landed between ticks; the rest are the player's choices. The
		// saved width goes in as a number: RuneLite may resize the Dimension it hands back in place.
		final List<Object> key = Arrays.asList(client.getTickCount(), fight, fight.isEnded(), fight.getAttacksMade(),
			fight.getDamageDealt(), fight.getThrallDamage(), fight.getDotDamage(), fight.getHealed(), room,
			config.overlayCovers(), config.overlayLuck(), config.overlayParts(),
			config.overlayDetails(), font, savedWidth(getPreferredSize()));
		final Lines built = lines.get(key, () -> build(graphics, fight, room));
		if (built == null)
		{
			return null;
		}
		return drawn.draw(graphics, built.look(getPreferredSize()), background(this), g -> paint(g, built));
	}

	/** The panel drawn the ordinary way: what {@link OverlayCache} records, or falls back to. */
	private Dimension paint(Graphics2D g, Lines built)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().addAll(built.components);
		panelComponent.setPreferredSize(new Dimension(built.width, 0));
		return super.render(g);
	}

	/**
	 * The colour {@code OverlayPanel.render} draws an overlay's background in:
	 * the player's "Overlay color" while the panel keeps RuneLite's standard
	 * colour, which ours always do.
	 */
	static Color background(OverlayPanel overlay)
	{
		final Color own = overlay.getPanelComponent().getBackgroundColor();
		final Color preferred = overlay.getPreferredColor();
		return preferred != null && ComponentConstants.STANDARD_BACKGROUND_COLOR.equals(own) ? preferred : own;
	}

	/** What one build makes: the line components in order, the rows they show, and the panel's width for them. */
	static final class Lines
	{
		final List<LineComponent> components;
		/** The rows as fitted to the width, which is what the box shows. */
		final List<LedgerRows.Row> rows;
		final int width;

		Lines(List<LineComponent> components, List<LedgerRows.Row> rows, int width)
		{
			this.components = Collections.unmodifiableList(components);
			this.rows = Collections.unmodifiableList(rows);
			this.width = width;
		}

		/** Everything about these lines that changes the box's pixels, for {@link OverlayCache}. */
		Object look(Dimension saved)
		{
			return Arrays.asList(rows, width, saved == null ? 0 : saved.width, saved == null ? 0 : saved.height);
		}
	}

	/** The lines for the fight and room on show, or null when there's nothing to show. */
	private Lines build(Graphics2D graphics, Fight fight, Encounter room)
	{
		final boolean warning = plugin.getStatsWarning(fight) != null;
		LedgerRows.Scope scope = null;
		final LedgerRows.State state;
		if (isBeforeFirstAttack(fight, room))
		{
			// A raid's room takes the raid's colour (Eric, 2026-10-28).
			final int raidRgb = fight.getRaidName() == null ? -1 : BossColours.of(fight.getRaidName());
			state = LedgerRows.State.preAttack(fight.encounterName()).withTitleRgb(raidRgb);
		}
		else
		{
			// T-070: the after-fight view waits for the room's kill, not an add's or one titan's death.
			final boolean over = isOver(fight, room);
			final boolean running = !over;
			final OverlayCovers covers = config.overlayCovers();
			final SessionTotals session = covers == OverlayCovers.TRIP ? plugin.getSession() : null;
			final Raid raid = covers == OverlayCovers.RAID ? plugin.getCurrentRaid() : null;
			final LedgerRows.LiveDrain drain = liveDrain(fight);
			if (session != null)
			{
				scope = LedgerRows.of(session);
				state = LedgerRows.State.trip(session.getKills(), running, warning).withDrain(drain);
			}
			else if (raid != null)
			{
				scope = LedgerRows.of(raid);
				state = LedgerRows.State.raid(raid.getName(), raid.getEncounters().size(), running, warning)
					.withDrain(drain);
			}
			else if (room != null)
			{
				scope = LedgerRows.of(room);
				state = shownRoomState(fight, room, warning, this::average, drain, System.currentTimeMillis());
			}
			else
			{
				return null;
			}
		}

		final List<LedgerRows.Row> rows = LedgerRows.build(scope, state, settings());
		return lines(graphics, rows, getPreferredSize());
	}

	/**
	 * Rows as line components in the player's fonts, and the panel's width for
	 * them, or null for no rows. Both overlays draw through this (T-022), so the
	 * loadout looks the same in its own box as it did in the Ledger.
	 */
	static Lines lines(Graphics2D graphics, List<LedgerRows.Row> rows, Dimension saved)
	{
		if (rows.isEmpty())
		{
			return null;
		}
		final FontRoles fonts = FontRoles.of(graphics.getFont());
		final List<LineComponent> components = new ArrayList<>(rows.size());
		final List<LedgerRows.Row> fitted = new ArrayList<>(rows.size());
		// A width the player dragged the panel to wins over ours (OverlayPanel.render), and a row wider than it wraps
		// onto a second line, so the panel grows taller (Eric, 2026-10-26, "Abyssal tentacle (or)"). The attack's name
		// is shortened to fit instead.
		final int panelWidth = savedWidth(saved);
		int widest = 0;
		for (LedgerRows.Row given : rows)
		{
			final Font left = fonts.get(given.leftFont);
			final Font right = fonts.get(given.rightFont);
			final FontMetrics leftMetrics = graphics.getFontMetrics(left);
			final FontMetrics rightMetrics = graphics.getFontMetrics(right);
			final LedgerRows.Row row = fit(given, t -> leftMetrics.stringWidth(Text.removeTags(t)),
				t -> rightMetrics.stringWidth(Text.removeTags(t)), panelWidth);
			fitted.add(row);
			widest = Math.max(widest, leftMetrics.stringWidth(Text.removeTags(row.left))
				+ rightMetrics.stringWidth(Text.removeTags(row.right)));
			components.add(LineComponent.builder()
				.left(row.drawnLeft())
				.right(row.right)
				.leftFont(left)
				.rightFont(right)
				.leftColor(new Color(row.leftRgb()))
				.rightColor(new Color(row.rightColour.rgb))
				.build());
		}
		return new Lines(components, fitted, width(widest));
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

	/**
	 * The running fight's target as T-049 tracks it, for the live Defence row
	 * (T-051), or null: the fight has ended, nothing is tracked, the target is
	 * immune, or nothing has been taken off it yet. Read once a tick, when the
	 * rows are built.
	 */
	private LedgerRows.LiveDrain liveDrain(Fight fight)
	{
		if (fight == null || fight.isEnded())
		{
			return null;
		}
		return liveDrain(plugin.getTrackedDrain(fight.getTargetIndex()));
	}

	/** {@link #liveDrain(Fight)} from the tracker's figures. */
	static LedgerRows.LiveDrain liveDrain(DrainTracker.Tracked t)
	{
		if (t == null || t.target.immune)
		{
			return null;
		}
		final LedgerRows.LiveDrain live = new LedgerRows.LiveDrain(t.state.defence, t.target.defence,
			t.target.defenceFloor, t.state.magic, t.target.magic, t.target.magicFloor);
		return live.drained() ? live : null;
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
	static LedgerRows.Row fit(LedgerRows.Row row, ToIntFunction<String> leftWidth, ToIntFunction<String> rightWidth,
		int panelWidth)
	{
		// A long figure with a short form (T-029: Luck's band without its seconds) gives way first.
		if (row.shortRight != null)
		{
			final int line = panelWidth - BORDER - MIN_GAP;
			return leftWidth.applyAsInt(row.left) + rightWidth.applyAsInt(row.right) <= line
				? row : row.withRight(row.shortRight);
		}
		return fit(row, leftWidth, rightWidth.applyAsInt(row.right), panelWidth);
	}

	/** {@link #fit(LedgerRows.Row, ToIntFunction, ToIntFunction, int)} for a row with a fixed right side. */
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
		return row.withLeft(LedgerRows.cutKeepingCount(row.shortLeft, leftWidth, room));
	}

	/** The widest row's two sides plus the padding, held to the standard width and the cap. */
	static int width(int widestRow)
	{
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widestRow + PADDING));
	}

	/**
	 * Whether either overlay has a fight to show (T-022: the Ledger and the
	 * loadout overlay both go by this). A finished fight is left up so the kill
	 * can be read, for "Keep last fight up (s)" (T-040; until the next fight at
	 * 0); and "bosses only" hides both off a boss.
	 */
	static boolean onShow(Fight fight, PvmPerformanceConfig config, long now)
	{
		return onShow(fight, config.overlayHoldSeconds(), config.overlayBossesOnly(), isBossFight(fight), now);
	}

	/**
	 * Whether a fight is a boss's for "bosses only": its target is on the boss
	 * list, or its room is (T-027). A boss's adds are in the boss's room but have
	 * names of their own: the Sire's respiratory systems hid both overlays the
	 * moment one was hit (Eric, 2026-10-27).
	 */
	static boolean isBossFight(Fight fight)
	{
		return fight != null && (PvmPerformancePlugin.isBossName(fight.getTargetName())
			|| PvmPerformancePlugin.isBossName(fight.encounterName()));
	}

	/** {@link #onShow(Fight, PvmPerformanceConfig, long)} with the answers read out. */
	static boolean onShow(Fight fight, int holdSeconds, boolean bossesOnly, boolean boss, long now)
	{
		if (fight == null)
		{
			return false;
		}
		// 0 holds it until the next fight takes its place.
		if (fight.isEnded() && holdSeconds > 0 && now - fight.getEndMillis() > holdSeconds * 1000L)
		{
			return false;
		}
		return !bossesOnly || boss;
	}

	/**
	 * The room view's state (T-070): running until the room is over; then
	 * {@code (dead)} only for the room's kill, and ` your average` only then, and
	 * only outside a raid. {@code average} is asked only for a killed room.
	 */
	static LedgerRows.State roomState(Fight fight, Encounter room, boolean warning,
		java.util.function.Function<Encounter, LedgerHistory.Average> average)
	{
		final boolean over = isOver(fight, room);
		final boolean killed = over && room.isKilled();
		return LedgerRows.State.fight(room.getName(), over, killed, warning)
			.withAverage(killed && room.getRaid() == null ? average.apply(room) : null)
			// A raid's room takes the raid's colour (Eric, 2026-10-28).
			.withTitleRgb(BossColours.of(room.getRaid()));
	}

	/**
	 * The state for the room on show: a run of monsters' light view (T-094), or
	 * the room view with its drains. A run leaves the drains out: its drain specs
	 * are rare.
	 */
	static LedgerRows.State shownRoomState(Fight fight, Encounter room, boolean warning,
		java.util.function.Function<Encounter, LedgerHistory.Average> average, LedgerRows.LiveDrain drain, long now)
	{
		return room.isMonsterRoom() ? runState(room, warning, now)
			: roomState(fight, room, warning, average).withDrain(drain);
	}

	/**
	 * A run of monsters' state (T-094): running through its respawn waits and
	 * its fights' ends, over only once the run is ({@link Encounter#isOver()},
	 * which for a run is T-089's {@code endMonsterRoom} having run). Its clock is
	 * the run's own, from its start: it goes on through the waits between kills,
	 * so kills/hr is the rate you're getting them at, and stops at the last
	 * attack once the run has ended (T-089 ends it there, not when the pause ran
	 * out). {@code now} is the wall clock.
	 */
	static LedgerRows.State runState(Encounter room, boolean warning, long now)
	{
		final boolean over = room.isOver();
		final long millis = over ? room.durationMillis() : Math.max(0, now - room.getStartMillis());
		return LedgerRows.State.run(room.getName(), room.getKills(), millis / 1000.0, over, warning);
	}

	/**
	 * Whether a run of monsters keeps the overlay up past "Keep last fight up"
	 * (T-094): while the run on show is running, a fight in it that has ended is
	 * a kill or a switch, and the next one is coming. "Bosses only" still hides it.
	 */
	static boolean holdsRun(Fight fight, Encounter room, boolean bossesOnly)
	{
		return !bossesOnly && fight != null && room != null && room.isMonsterRoom() && !room.isOver()
			&& containsSame(room, fight);
	}

	/**
	 * Whether the overlay shows the after-fight view (T-070): the fight on show
	 * has ended and so has its room ({@link Encounter#isOver()}). A boss's add or
	 * one titan dying ends its own fight while the room runs on; until the
	 * room's kill, that finished fight reads as running, with the room's figures.
	 * With no room on show, the fight alone decides, as before.
	 */
	static boolean isOver(Fight fight, Encounter room)
	{
		return fight != null && fight.isEnded() && (room == null || room.isOver());
	}

	/**
	 * Whether the fight on show hasn't started in its own room yet, so the
	 * Ledger shows its title alone (T-022). Asked of the fight, not
	 * the room on show: until the new room has an attempt, the room on show is
	 * the last one. A fight on a new add in a room already under way is not "before
	 * the first attack", so a room's adds don't blink the panel back to its
	 * title one by one.
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

	private LedgerRows.Settings settings()
	{
		final LedgerRows.Settings s = new LedgerRows.Settings();
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
		/** T-073: a main row's figure, the interface font's regular weight. */
		final Font value;

		private FontRoles(Font body, Font emphasis, Font detail, Font value)
		{
			this.body = body;
			this.emphasis = emphasis;
			this.detail = detail;
			this.value = value;
		}

		Font get(LedgerRows.FontRole role)
		{
			switch (role)
			{
				case EMPHASIS:
					return emphasis;
				case DETAIL:
					return detail;
				case VALUE:
					return value;
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
					// With Bold as the interface font, the figures still read regular beside the bold labels (T-073).
					return body.isBold()
						? new FontRoles(body, body, regular, regular)
						: new FontRoles(body, FontManager.getRunescapeBoldFont(), small, body);
				}
				if (body.getFamily().equals(small.getFamily()))
				{
					return new FontRoles(body, body, body, body);
				}
			}
			return new FontRoles(body, body.deriveFont(Font.BOLD), body, body.deriveFont(Font.PLAIN));
		}
	}
}

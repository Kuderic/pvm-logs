package com.pvmlogs;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * The side panel (T-033, {@code claude/specs/side-panel-design.md} rev 1): tabs
 * Fight · History · Trip. History and Trip are interim until T-034.
 * <p>
 * The snapshot pipeline (spec 5.1): the plugin calls {@link #onTick()} at the
 * end of every {@code onGameTick}, on the client thread. Only while the panel
 * is open, and only when something on show changed, it reads the plugin into a
 * {@link SidePanelModel.Input}, builds an immutable {@link PanelSnapshot}, and
 * hands it to Swing's thread, which updates the components it has. Nothing here
 * reads the scope classes from Swing's thread.
 */
class PvmPerformancePanel extends PluginPanel
{
	/** The settings key prefix a folded section is remembered under (spec section 7). */
	static final String FOLD_KEY = "panelFold.";

	private final PvmPerformancePlugin plugin;
	private final PvmPerformanceConfig config;
	private final ClientThread clientThread;

	private final SidePanelFightTab fightTab;
	private final SidePanelHistoryTab historyTab;
	private final SidePanelTripTab tripTab;
	private final MaterialTabGroup tabs;
	private final MaterialTab fightButton;
	private final MaterialTab historyButton;
	/** The strain probe (T-045): times apply and the repaints; null in tests. */
	private final PerfProbe perf;

	// Swing's thread writes these, the client thread reads them.
	private volatile boolean active;
	private volatile boolean dirty = true;
	/** The room/raid switch as the player left it; null until they touch it, so the overlay's setting decides. */
	private volatile Boolean wholeRaid;
	/** A room picked in the whole raid's list, by index, and the raid's room count when it was picked; -1 for none. */
	private volatile int raidRoom = -1;
	private volatile int raidRoomsAtPick = -1;

	// Client thread only.
	private List<Object> lastKey;
	private List<Object> lastShape;
	private Encounter averageRoom;
	private int averageHistorySize = -1;
	private Fight averageHistoryHead;
	private LedgerHistory.Average average;
	private LedgerHistory.Spread luckRemoved;
	// The chart's scope and how much of its series the last snapshot sent (spec 5.1): the next sends from a few ticks back.
	private Object chartScope;
	private int chartSent;
	/** The Damage rows' sums, kept between snapshots so a long fight's build costs what a short one's does (T-046). */
	private final SidePanelWeaponRows weaponRows = new SidePanelWeaponRows();

	// Swing's thread only.
	private PanelSnapshot shown;
	/** Each tab's last part, kept so a change of the text settings builds it again at once (T-053). */
	private PanelSnapshot.FightTab lastFight;
	private PanelSnapshot.TripTab lastTrip;
	/** How many times the text settings were applied to built tabs, for tests (T-053). */
	private int restyles;

	/** When this panel, and so this RuneLite session's plugin, started: History's "later" card goes by it. */
	private final long sessionStart = System.currentTimeMillis();
	/** The boss rules, for version names ("Post-quest"); null in tests. */
	private final MonsterStatsProvider monsterStats;
	/** The Damage rows' icons, from RuneLite's item and sprite managers; none in tests. */
	private final SidePanelIcons icons;
	/** Ticks the chart resends behind the last one sent, for a splat that lands a tick late. */
	static final int CHART_RESEND = 4;
	/**
	 * T-046: while a fight runs, a snapshot is built and applied at most once
	 * every this many ticks (3 s). The fight ending, opening the panel, a tab
	 * switch and a fold update at once.
	 */
	static final int UPDATE_TICKS = 5;
	/** The tabs, as {@link #visibleTab} holds them. */
	static final int FIGHT_TAB = 0;
	static final int HISTORY_TAB = 1;
	static final int TRIP_TAB = 2;

	/** The tab on show (Swing's thread writes it): only its part of a snapshot is built (T-046). */
	private volatile int visibleTab = FIGHT_TAB;
	// Client thread only: ticks seen, and the tick the last snapshot was built on.
	private long ticks;
	private long builtAt = Long.MIN_VALUE / 2;

	// The trip's saved rooms, kept until the history or the trip changes (client thread).
	private List<SidePanelHistory.KillRow> tripSaved = java.util.Collections.emptyList();
	private List<Object> tripSavedKey;
	/** T-044: the avoidable attack names at the trip's saved fights' bosses, worked out with {@link #tripSaved}. */
	private java.util.Set<String> tripAvoidable = java.util.Collections.emptySet();

	PvmPerformancePanel(PvmPerformancePlugin plugin)
	{
		this(plugin, instance(plugin, PvmPerformanceConfig.class), instance(plugin, ConfigManager.class),
			instance(plugin, ClientThread.class));
	}

	/** With every dependency given: tests and renders pass nulls and drive {@link #apply} themselves. */
	PvmPerformancePanel(PvmPerformancePlugin plugin, PvmPerformanceConfig config, ConfigManager configManager,
		ClientThread clientThread)
	{
		this(plugin, config, configManager, clientThread, null);
	}

	/** {@code historySource} null: the plugin's saved fights (tests and renders pass fixtures). */
	PvmPerformancePanel(PvmPerformancePlugin plugin, PvmPerformanceConfig config, ConfigManager configManager,
		ClientThread clientThread, SidePanelHistoryTab.Source historySource)
	{
		super(false);
		this.plugin = plugin;
		this.config = config;
		this.clientThread = clientThread;
		this.monsterStats = instance(plugin, MonsterStatsProvider.class);
		this.icons = icons(plugin);
		// The boss icons' sprites (T-054). Tests and renders give their own, so only a running client's replaces them.
		if (icons != SidePanelIcons.NONE)
		{
			SidePanelBossIcons.setSource(icons);
		}
		this.perf = plugin == null ? null : plugin.getPerf();

		setBackground(SidePanelStyle.PANEL);
		setLayout(new BorderLayout());
		// The text settings before anything is built (T-053), so nothing is built twice.
		SidePanelStyle.setText(config != null && config.panelLargerText(), config != null && config.panelSmoothFont());

		final SidePanelSection.Folds stored = configManager == null ? new SidePanelSection.MemoryFolds()
			: new ConfigFolds(configManager);
		// A fold updates at once (T-046): an unfolded section shows this tick's figures, not those of up to 3 s ago.
		final SidePanelSection.Folds folds = new SidePanelSection.Folds()
		{
			@Override
			public boolean isFolded(String id)
			{
				return stored.isFolded(id);
			}

			@Override
			public void setFolded(String id, boolean folded)
			{
				stored.setFolded(id, folded);
				requestUpdate();
			}
		};
		final SidePanelFightTab.Actions actions = new Actions();
		fightTab = new SidePanelFightTab(actions, folds, false, icons);
		historyTab = new SidePanelHistoryTab(historySource != null ? historySource : plugin == null ? null
			: new HistorySource(), actions, folds);
		tripTab = new SidePanelTripTab(actions, folds);
		fightTab.setPerf(perf);
		historyTab.setPerf(perf);
		tripTab.setPerf(perf);

		final JPanel display = new JPanel(new BorderLayout());
		display.setBackground(SidePanelStyle.PANEL);
		tabs = new MaterialTabGroup(display);
		tabs.setBorder(new EmptyBorder(4, SidePanelStyle.SIDE_BORDER, 0, SidePanelStyle.SIDE_BORDER));
		tabs.setBackground(SidePanelStyle.PANEL);
		final MaterialTab fight = new MaterialTab("Fight", tabs, scroll(fightTab, perf));
		final MaterialTab history = new MaterialTab("History", tabs, scroll(historyTab, perf));
		final MaterialTab trip = new MaterialTab("Trip", tabs, scroll(tripTab, perf));
		fight.setOnSelectEvent(() -> show(FIGHT_TAB));
		history.setOnSelectEvent(() ->
		{
			historyTab.refresh();
			return show(HISTORY_TAB);
		});
		trip.setOnSelectEvent(() -> show(TRIP_TAB));
		fightButton = fight;
		historyButton = history;
		tabs.addTab(fight);
		tabs.addTab(history);
		tabs.addTab(trip);
		// Opens on Fight, and never switches on its own (spec 3).
		tabs.select(fight);

		add(tabs, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// Until the first snapshot arrives: the empty state, so the tab is never blank.
		apply(new PanelSnapshot(SidePanelModel.fight(new SidePanelModel.Input())));
	}

	private static <T> T instance(PvmPerformancePlugin plugin, Class<T> type)
	{
		return plugin == null || plugin.getInjector() == null ? null : plugin.getInjector().getInstance(type);
	}

	/**
	 * A tab's content in its own scroll pane, vertical as needed and never horizontal (spec section 1). The scroll pane is
	 * the tab's validate root: each layout pass it runs is timed (T-046).
	 */
	private static JScrollPane scroll(JComponent view, PerfProbe perf)
	{
		final JScrollPane scroll = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
		{
			@Override
			public void validate()
			{
				final boolean pass = !isValid();
				final long started = PerfProbe.start(perf);
				super.validate();
				if (started != 0L)
				{
					perf.stopSwing(PerfProbe.SwingSlot.LAYOUT, started);
					if (pass)
					{
						perf.layoutPassed();
					}
				}
			}

			/** The first invalidation since the last pass reaches here: with the probe on, its stack says what asked. */
			@Override
			public void invalidate()
			{
				final boolean first = isValid();
				super.invalidate();
				if (first && perf != null && perf.isOn())
				{
					perf.layoutAsked(new Throwable().getStackTrace());
				}
			}
		};
		scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(SidePanelStyle.PANEL);
		return scroll;
	}

	/** A tab was picked (Swing's thread): only its part is built from now on, starting at once. */
	private boolean show(int tab)
	{
		if (visibleTab != tab)
		{
			visibleTab = tab;
			requestUpdate();
		}
		return true;
	}

	/** The next snapshot is built whatever the interval or its key says, as soon as the client thread can (T-046). */
	private void requestUpdate()
	{
		dirty = true;
		if (clientThread != null && active)
		{
			clientThread.invokeLater(this::onTick);
		}
	}

	int visibleTab()
	{
		return visibleTab;
	}

	// ---- open and closed ----

	@Override
	public void onActivate()
	{
		// The settings are a panel of their own in RuneLite's sidebar, so a change there is seen when this one opens again.
		if (config != null)
		{
			setText(config.panelLargerText(), config.panelSmoothFont());
		}
		active = true;
		dirty = true;
		// One full snapshot now rather than at the next tick, which never comes on the login screen.
		if (clientThread != null)
		{
			clientThread.invokeLater(this::onTick);
		}
		historyTab.refresh();
	}

	@Override
	public void onDeactivate()
	{
		active = false;
	}

	boolean isActive()
	{
		return active;
	}

	// ---- the plugin's calls ----

	/**
	 * Called by the plugin after a fight is saved, history is loaded or cleared:
	 * the next snapshot is built whatever its key says, and the History tab reads
	 * the list again.
	 */
	void refresh()
	{
		dirty = true;
		SwingUtilities.invokeLater(historyTab::refresh);
	}

	void setStatus(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			fightTab.setStatus(text);
			historyTab.setStatus(text);
		});
	}

	/**
	 * The end of {@code onGameTick}, on the client thread (spec 5.1 step 1). Does
	 * nothing while the panel is closed or on the History tab, or when nothing on
	 * show changed; while a fight runs, builds at most every {@link #UPDATE_TICKS}
	 * ticks (T-046), and with "Update during fights" off not until its end. Only
	 * the visible tab's part of the snapshot is built.
	 */
	void onTick()
	{
		ticks++;
		final int tab = visibleTab;
		if (!active || plugin == null || tab == HISTORY_TAB)
		{
			// History changes only when a fight is saved, which refresh() handles; a switch back builds at once.
			return;
		}
		final Fight fight = plugin.getDisplayFight();
		final Encounter display = plugin.getDisplayEncounter();
		final Raid raid = plugin.getCurrentRaid();
		final boolean whole = raid != null && wholeRaid();
		final Encounter room = roomOnShow(display, raid, whole);
		final boolean pre = fight != null && PvmPerformanceOverlay.isBeforeFirstAttack(fight, display);
		final List<Fight> history = plugin.getHistory();
		final Iterator<Fight> newest = history.iterator();
		final Fight head = newest.hasNext() ? newest.next() : null;

		final SessionTotals trip = plugin.getSession();
		final List<Object> shape = Arrays.asList(fight, fight != null && fight.isEnded(), pre, room, raid, whole,
			history.size(), head, trip.getStartMillis(), tab);
		final boolean force = dirty;
		final boolean fighting = fight != null && !fight.isEnded() && !pre;
		final boolean sameShape = shape.equals(lastShape);
		if (!due(force, fighting, sameShape, config == null || config.panelLive(), ticks - builtAt))
		{
			return;
		}
		// A running fight's clock changes every tick, so its key always differs: only out of a fight is it worth comparing.
		final List<Object> key = fighting && sameShape && !force ? null : key(shape, fight, room, raid, whole, pre);
		if (key != null && !force && key.equals(lastKey))
		{
			return;
		}
		dirty = false;
		lastKey = key;
		lastShape = shape;
		builtAt = ticks;

		final long building = PerfProbe.start(perf);
		final PanelSnapshot snapshot;
		if (tab == TRIP_TAB)
		{
			snapshot = new PanelSnapshot(null, SidePanelModel.trip(readTrip(trip, history, head, fighting ? display : null)));
		}
		else
		{
			snapshot = new PanelSnapshot(SidePanelModel.fight(read(fight, display, room, raid, whole, pre, history)), null);
		}
		PerfProbe.stop(perf, PerfProbe.Slot.PANEL_BUILD, building);
		SwingUtilities.invokeLater(() -> apply(snapshot));
	}

	/**
	 * Whether this tick builds a snapshot, before its key is compared (T-046). Out of a fight, on a change of what's on
	 * show (a fight starting or ending, a tab), or when forced (opening, a fold): yes. The same fight still running: with
	 * "Update during fights" off, not until it ends; on, every {@link #UPDATE_TICKS} ticks.
	 */
	static boolean due(boolean force, boolean fighting, boolean sameShape, boolean live, long ticksSinceBuild)
	{
		if (force || !fighting || !sameShape)
		{
			return true;
		}
		return live && ticksSinceBuild >= UPDATE_TICKS;
	}

	/** Whether the switch reads Whole raid: the player's last choice, else whether the overlay covers the whole raid. */
	private boolean wholeRaid()
	{
		final Boolean choice = wholeRaid;
		if (choice != null)
		{
			return choice;
		}
		return config != null && config.overlayCovers() == OverlayCovers.RAID;
	}

	/** The room the Fight tab is about: a room picked from the raid's list while it's still the raid on show, else the display room. */
	private Encounter roomOnShow(Encounter display, Raid raid, boolean whole)
	{
		final int pick = raidRoom;
		if (raid == null || whole || pick < 0)
		{
			return display;
		}
		final List<Encounter> rooms = raid.getEncounters();
		// A new room starting puts the panel back on it.
		if (rooms.size() != raidRoomsAtPick || pick >= rooms.size())
		{
			raidRoom = -1;
			return display;
		}
		return rooms.get(pick);
	}

	/** Everything that can change what the Fight tab shows; a running fight's clock changes it every tick. */
	private List<Object> key(List<Object> shape, Fight fight, Encounter room, Raid raid, boolean whole, boolean pre)
	{
		final List<Object> key = new ArrayList<>(shape);
		key.add(fight == null ? null : plugin.getStatsWarning(fight));
		if (pre)
		{
			final LedgerRows.Loadout l = LoadoutOverlay.loadout(plugin);
			key.addAll(Arrays.asList(l.attack, l.maxHit, l.accuracy, l.dps, l.specMaxHit, l.specAccuracy, l.specAverage,
				l.specExtra));
		}
		else
		{
			final LedgerRows.Scope s = whole ? LedgerRows.of(raid) : room == null ? null : LedgerRows.of(room);
			if (s != null)
			{
				key.addAll(Arrays.asList(s.combatTicks(), s.damage(), s.expectedDamage(), s.thrallDamage(), s.dotDamage(),
					s.reflectDamage(), s.damageTaken(), s.dotTaken(), s.attacksMade(), s.ticksLost(), s.healed(),
					room == null ? 0 : room.getFights().size()));
			}
		}
		final SessionTotals t = plugin.getSession();
		key.addAll(Arrays.asList(t.getCombatTicks(), t.getDamageDealt(), t.getSumExpectedAverageHit(), t.getAttacksMade(),
			t.getDamageTaken(), t.getThrallDamage(), t.getDotDamage(), t.getReflectDamage(), t.getTicksLost(), t.getKills(),
			t.getFights()));
		return key;
	}

	/** The plugin's state as the model's input; client thread only. */
	private SidePanelModel.Input read(Fight fight, Encounter display, Encounter room, Raid raid, boolean whole, boolean pre,
		List<Fight> history)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.historyFights = history.size();
		in.wholeRaid = raid == null ? null : whole;
		if (fight == null)
		{
			in.kind = SidePanelModel.Kind.EMPTY;
			return in;
		}
		if (pre)
		{
			in.kind = SidePanelModel.Kind.PRE_ATTACK;
			in.name = fight.encounterName();
			in.raidType = raid == null ? null : raid.getType();
			in.maxHp = fight.getMaxHp();
			in.version = version(java.util.Collections.singletonList(fight), in.maxHp);
			in.warning = plugin.getStatsWarning(fight);
			in.loadout = LoadoutOverlay.loadout(plugin);
			in.specName = plugin.getSpecialAttack() == null ? null : plugin.getSpecialAttack().getDisplayName();
			if (display != null && !contains(display, fight))
			{
				in.lastFight = SidePanelModel.lastFight(display, true);
			}
			return in;
		}
		if (whole)
		{
			in.kind = SidePanelModel.Kind.RAID;
			SidePanelModel.fillRaid(in, raid);
			charts(in, raid, FightTimeline.raid(raid, plugin.getTimelines()));
			in.ended = fight.isEnded();
			in.warning = plugin.getStatsWarning(fight);
			// T-044: only an ended scope shows them, so they're looked up once it has.
			if (in.ended)
			{
				in.avoidableAttacks = avoidableAttacks(raidFights(raid));
			}
			return in;
		}
		if (room == null)
		{
			in.kind = SidePanelModel.Kind.EMPTY;
			return in;
		}
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.version = version(room.getFights(), in.maxHp);
		charts(in, room, FightTimeline.room(room, plugin.getTimelines()));
		final boolean current = contains(room, fight);
		in.ended = !current || fight.isEnded();
		in.killed = in.ended && room.isKilled();
		if (!in.ended)
		{
			// T-052: the target's tracked Defence for the header's live line, read with the rest of the build.
			in.liveDefence = SidePanelModel.liveDefence(plugin.getTrackedDrain(fight.getTargetIndex()));
		}
		if (in.ended)
		{
			in.avoidableAttacks = avoidableAttacks(room.getFights());
		}
		in.warning = current ? plugin.getStatsWarning(fight) : room.getStatsWarning();
		long end = 0;
		for (Fight f : room.getFights())
		{
			end = Math.max(end, f.getEndMillis());
		}
		in.endMillis = end;
		if (in.killed && room.getRaid() == null && in.warning == null)
		{
			averages(history, room);
			in.average = average;
			in.luckRemoved = luckRemoved;
		}
		return in;
	}

	/**
	 * The scope's timeline view for the Damage rows, chart and strip (T-036), and
	 * the index its series are sent from: a few ticks behind the last snapshot's
	 * end on the same scope, else 0, which starts Swing's copy over. Client thread.
	 */
	private void charts(SidePanelModel.Input in, Object scope, FightTimeline.View view)
	{
		in.charts = config == null || config.panelCharts();
		if (!in.charts || view.states.length == 0)
		{
			in.view = null;
			chartScope = null;
			chartSent = 0;
			return;
		}
		in.view = view;
		final Object key = Arrays.asList(scope, view.startTick);
		in.weaponRows = weaponRows.rows(key, view);
		in.chartFrom = key.equals(chartScope) ? Math.max(0, Math.min(chartSent, view.states.length) - CHART_RESEND) : 0;
		chartScope = key;
		chartSent = view.states.length;
	}

	/** RuneLite's item images and spell sprites, delivered on Swing's thread; none without a plugin. */
	private static SidePanelIcons icons(PvmPerformancePlugin plugin)
	{
		final net.runelite.client.game.ItemManager items = instance(plugin, net.runelite.client.game.ItemManager.class);
		final net.runelite.client.game.SpriteManager sprites = instance(plugin, net.runelite.client.game.SpriteManager.class);
		if (items == null || sprites == null)
		{
			return SidePanelIcons.NONE;
		}
		return new SidePanelIcons()
		{
			@Override
			public void item(int itemId, java.util.function.Consumer<java.awt.image.BufferedImage> done)
			{
				final net.runelite.client.util.AsyncBufferedImage image = items.getImage(itemId);
				image.onLoaded(() -> SwingUtilities.invokeLater(() -> done.accept(image)));
				done.accept(image);
			}

			@Override
			public void sprite(int spriteId, java.util.function.Consumer<java.awt.image.BufferedImage> done)
			{
				sprites.getSpriteAsync(spriteId, 0, img -> SwingUtilities.invokeLater(() -> done.accept(img)));
			}
		};
	}

	/** T-044: the attack names the boss rules call avoidable at these fights' bosses; empty without rules. */
	private java.util.Set<String> avoidableAttacks(java.util.Collection<Fight> fights)
	{
		return SidePanelModel.avoidableAttacks(monsterStats == null ? null : monsterStats.rules(), fights);
	}

	private static List<Fight> raidFights(Raid raid)
	{
		final List<Fight> fights = new ArrayList<>();
		for (Encounter e : raid.getEncounters())
		{
			fights.addAll(e.getFights());
		}
		return fights;
	}

	/** The boss version's name for these fights at this full HP, from the boss rules; null where they don't say. */
	private String version(List<Fight> fights, int maxHp)
	{
		return monsterStats == null ? null : SidePanelModel.versionName(monsterStats.rules(), fights, maxHp);
	}

	/** The trip's figures and rooms (spec 3.3); client thread. The saved rooms are rebuilt only when the history or trip change. */
	private SidePanelModel.Input readTrip(SessionTotals trip, List<Fight> history, Fight head, Encounter running)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = LedgerRows.of(trip);
		in.tripSince = trip.getStartMillis();
		in.hitsTaken = trip.getHitsTaken();
		// The trip records thrall, DoT and reflect on scored fights only (T-025), so its other damage is the scored kind too.
		in.scoredThrall = in.thrall = Math.max(0, trip.getThrallDamage());
		in.scoredDot = in.dot = Math.max(0, trip.getDotDamage());
		in.scoredReflect = in.reflect = Math.max(0, trip.getReflectDamage());
		// T-044: the trip's avoidable figures sum its ended ruled fights, shown, as on the overlay, while no fight runs.
		in.ended = running == null;
		in.takenByAttack = trip.getDamageTakenByAttack();
		// T-052: what the trip's drains added and the specials' share of luck (T-050's sums over its ended fights).
		final SidePanelModel.Drain drains = new SidePanelModel.Drain();
		drains.valueDamage = trip.getDrainValueDamage();
		drains.valueSeconds = trip.luckSeconds(drains.valueDamage);
		drains.specLuckDamage = trip.getSpecLuckDamage();
		drains.specLuckSeconds = trip.luckSeconds(drains.specLuckDamage);
		in.drain = drains;
		final List<Object> key = Arrays.asList(history.size(), head, trip.getStartMillis());
		if (!key.equals(tripSavedKey))
		{
			tripSavedKey = key;
			tripSaved = SidePanelHistory.tripSaved(history, trip.getStartMillis(), System.currentTimeMillis(),
				java.time.ZoneId.systemDefault());
			final List<Fight> tripFights = new ArrayList<>();
			for (Fight f : history)
			{
				if (f.getStartMillis() >= trip.getStartMillis())
				{
					tripFights.add(f);
				}
			}
			tripAvoidable = avoidableAttacks(tripFights);
		}
		in.avoidableAttacks = tripAvoidable;
		final List<PanelSnapshot.TripRow> rows = new ArrayList<>();
		for (SidePanelHistory.KillRow r : SidePanelHistory.tripRooms(tripSaved, running))
		{
			rows.add(SidePanelModel.tripRow(r));
		}
		in.tripRows = rows;
		return in;
	}

	/** Your average and luck removed on this room, worked out again only when the room or the history changes. */
	private void averages(List<Fight> history, Encounter room)
	{
		final Iterator<Fight> newest = history.iterator();
		final Fight head = newest.hasNext() ? newest.next() : null;
		if (room != averageRoom || history.size() != averageHistorySize || head != averageHistoryHead)
		{
			averageRoom = room;
			averageHistorySize = history.size();
			averageHistoryHead = head;
			final List<Fight> copy = new ArrayList<>(history);
			average = LedgerHistory.of(copy, room);
			luckRemoved = average == null ? null : SidePanelModel.luckRemoved(copy, room);
		}
	}

	private static boolean contains(Encounter room, Fight fight)
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

	// ---- Swing's thread ----

	/**
	 * The "Larger text" and "Smooth font" settings (T-053), Swing's thread. When they changed: the fonts and line heights
	 * are made once, each tab is built again once from its last snapshot in them, every cached image holding text is
	 * dropped, and the panel is laid out and repainted once. Nothing happens when they're as they were.
	 */
	boolean setText(boolean larger, boolean smooth)
	{
		if (!SidePanelStyle.setText(larger, smooth))
		{
			return false;
		}
		restyles++;
		fightTab.restyle(lastFight);
		tripTab.restyle(lastTrip);
		historyTab.restyle();
		revalidate();
		repaint();
		return true;
	}

	int restyles()
	{
		return restyles;
	}

	/** Shows a snapshot (spec 5.1 step 2). Swing's thread only. */
	void apply(PanelSnapshot snapshot)
	{
		final long started = PerfProbe.start(perf);
		shown = snapshot;
		if (snapshot.fight != null)
		{
			lastFight = snapshot.fight;
		}
		if (snapshot.trip != null)
		{
			lastTrip = snapshot.trip;
		}
		if (snapshot.fight != null)
		{
			fightTab.apply(snapshot.fight);
		}
		tripTab.apply(snapshot.trip);
		if (started != 0L)
		{
			perf.stopSwing(PerfProbe.SwingSlot.APPLY, started);
			// The repaints apply asked for (T-045): Swing paints them in one pass queued during apply, so a marker queued now
			// runs after it. A repaint from a section paints from that section, never through this panel's paintChildren.
			// The wait can include other plugins' Swing work queued in between: an upper bound.
			final long queued = System.nanoTime();
			SwingUtilities.invokeLater(() -> perf.stopSwing(PerfProbe.SwingSlot.PAINT_AFTER_APPLY, queued));
		}
	}

	/** A full paint of the panel (T-045): opening it, resizing, a tab switch. Per-tick repaints are timed in {@link #apply}. */
	@Override
	protected void paintChildren(java.awt.Graphics g)
	{
		final long started = PerfProbe.start(perf);
		super.paintChildren(g);
		if (started != 0L)
		{
			perf.stopSwing(PerfProbe.SwingSlot.PAINT_FULL, started);
		}
	}

	/**
	 * The panel's state for the strain probe (T-045), as {@link PerfProbe#panelState}: closed, or open on which tab and with
	 * which panel settings. Read on the client thread; the tab is Swing's, and a tick's read can be one switch behind.
	 */
	int perfState()
	{
		final int tab = fightButton.isSelected() ? 0 : historyButton.isSelected() ? 1 : 2;
		return PerfProbe.panelState(active, tab, config == null || config.panelLive(),
			config == null || config.panelCharts());
	}

	PanelSnapshot shown()
	{
		return shown;
	}

	SidePanelFightTab fightTab()
	{
		return fightTab;
	}

	MaterialTabGroup tabs()
	{
		return tabs;
	}

	SidePanelHistoryTab historyTab()
	{
		return historyTab;
	}

	SidePanelTripTab tripTab()
	{
		return tripTab;
	}

	/** The History tab's saved fights and what goes with them, from the plugin; read on Swing's thread. */
	private final class HistorySource implements SidePanelHistoryTab.Source
	{
		@Override
		public List<Fight> history()
		{
			return plugin.getHistory();
		}

		@Override
		public SidePanelHistory.Versions versions()
		{
			return new SidePanelHistory.Versions()
			{
				@Override
				public String name(Encounter room, int maxHp)
				{
					return version(room.getFights(), maxHp);
				}

				@Override
				public java.util.Set<String> avoidable(Encounter room)
				{
					return avoidableAttacks(room.getFights());
				}
			};
		}

		@Override
		public long sessionStart()
		{
			return sessionStart;
		}

		@Override
		public long now()
		{
			return System.currentTimeMillis();
		}

		@Override
		public java.time.ZoneId zone()
		{
			return java.time.ZoneId.systemDefault();
		}

		@Override
		public void exportNpc(String name)
		{
			plugin.exportNpc(name);
		}

		@Override
		public void charts(Encounter room, java.util.function.Consumer<FightTimeline.View> done)
		{
			// The store is the client thread's: the view is built there and handed back to Swing's.
			if (clientThread == null || (config != null && !config.panelCharts()))
			{
				done.accept(FightTimeline.View.EMPTY);
				return;
			}
			clientThread.invokeLater(() ->
			{
				final FightTimeline.View view = FightTimeline.room(room, plugin.getTimelines());
				SwingUtilities.invokeLater(() -> done.accept(view));
			});
		}

		@Override
		public SidePanelIcons icons()
		{
			return icons;
		}
	}

	/** The buttons' actions, passed on to the plugin. */
	private final class Actions implements SidePanelFightTab.Actions
	{
		@Override
		public void exportAll()
		{
			if (plugin != null)
			{
				plugin.exportAll(historyTab.bossesOnly());
			}
		}

		@Override
		public void newTrip()
		{
			if (plugin != null)
			{
				plugin.resetSession();
				dirty = true;
			}
		}

		@Override
		public void saveDebugLog()
		{
			if (plugin != null)
			{
				plugin.saveDebugLog();
			}
		}

		@Override
		public void clearHistory()
		{
			if (plugin != null)
			{
				plugin.clearHistory();
			}
			refresh();
		}

		@Override
		public void wholeRaid(boolean whole)
		{
			wholeRaid = whole;
			raidRoom = -1;
			dirty = true;
			poke();
		}

		@Override
		public void showRaidRoom(int index)
		{
			final Raid raid = plugin == null ? null : plugin.getCurrentRaid();
			raidRoomsAtPick = raid == null ? -1 : raid.getEncounters().size();
			raidRoom = index;
			wholeRaid = false;
			dirty = true;
			poke();
		}

		/** A choice made between ticks shows at once rather than on the next tick. */
		private void poke()
		{
			if (clientThread != null)
			{
				clientThread.invokeLater(PvmPerformancePanel.this::onTick);
			}
		}
	}

	/** Folded sections, kept in the settings group under hidden keys (spec section 7). */
	private static final class ConfigFolds implements SidePanelSection.Folds
	{
		private final ConfigManager configManager;

		ConfigFolds(ConfigManager configManager)
		{
			this.configManager = configManager;
		}

		@Override
		public boolean isFolded(String id)
		{
			return "true".equals(configManager.getConfiguration(PvmPerformanceConfig.GROUP, FOLD_KEY + id));
		}

		@Override
		public void setFolded(String id, boolean folded)
		{
			if (folded)
			{
				configManager.setConfiguration(PvmPerformanceConfig.GROUP, FOLD_KEY + id, "true");
			}
			else
			{
				configManager.unsetConfiguration(PvmPerformanceConfig.GROUP, FOLD_KEY + id);
			}
		}
	}
}

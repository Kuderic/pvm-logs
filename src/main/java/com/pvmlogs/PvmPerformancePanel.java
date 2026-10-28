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

	// Swing's thread only.
	private PanelSnapshot shown;

	/** When this panel, and so this RuneLite session's plugin, started: History's "later" card goes by it. */
	private final long sessionStart = System.currentTimeMillis();
	/** The boss rules, for version names ("Post-quest"); null in tests. */
	private final MonsterStatsProvider monsterStats;
	/** The Damage rows' icons, from RuneLite's item and sprite managers; none in tests. */
	private final SidePanelIcons icons;
	/** Ticks the chart resends behind the last one sent, for a splat that lands a tick late. */
	static final int CHART_RESEND = 4;

	// The trip's saved rooms, kept until the history or the trip changes (client thread).
	private List<SidePanelHistory.KillRow> tripSaved = java.util.Collections.emptyList();
	private List<Object> tripSavedKey;

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

		setBackground(SidePanelStyle.PANEL);
		setLayout(new BorderLayout());

		final SidePanelSection.Folds folds = configManager == null ? new SidePanelSection.MemoryFolds()
			: new ConfigFolds(configManager);
		final SidePanelFightTab.Actions actions = new Actions();
		fightTab = new SidePanelFightTab(actions, folds, false, icons);
		historyTab = new SidePanelHistoryTab(historySource != null ? historySource : plugin == null ? null
			: new HistorySource(), actions, folds);
		tripTab = new SidePanelTripTab(actions, folds);

		final JPanel display = new JPanel(new BorderLayout());
		display.setBackground(SidePanelStyle.PANEL);
		tabs = new MaterialTabGroup(display);
		tabs.setBorder(new EmptyBorder(4, SidePanelStyle.SIDE_BORDER, 0, SidePanelStyle.SIDE_BORDER));
		tabs.setBackground(SidePanelStyle.PANEL);
		final MaterialTab fight = new MaterialTab("Fight", tabs, scroll(fightTab));
		final MaterialTab history = new MaterialTab("History", tabs, scroll(historyTab));
		final MaterialTab trip = new MaterialTab("Trip", tabs, scroll(tripTab));
		history.setOnSelectEvent(() ->
		{
			historyTab.refresh();
			return true;
		});
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

	/** A tab's content in its own scroll pane, vertical as needed and never horizontal (spec section 1). */
	private static JScrollPane scroll(JComponent view)
	{
		final JScrollPane scroll = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(SidePanelStyle.PANEL);
		return scroll;
	}

	// ---- open and closed ----

	@Override
	public void onActivate()
	{
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
	 * nothing while the panel is closed, or when nothing on show changed this
	 * tick; with "Update during fights" off, a running fight's figures wait for
	 * its end.
	 */
	void onTick()
	{
		if (!active || plugin == null)
		{
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
			history.size(), head, trip.getStartMillis());
		final List<Object> key = key(shape, fight, room, raid, whole, pre);
		final boolean force = dirty;
		if (!force && key.equals(lastKey))
		{
			return;
		}
		final boolean fighting = fight != null && !fight.isEnded() && !pre;
		if (!force && fighting && config != null && !config.panelLive() && shape.equals(lastShape))
		{
			return;
		}
		dirty = false;
		lastKey = key;
		lastShape = shape;

		final SidePanelModel.Input in = read(fight, display, room, raid, whole, pre, history);
		final boolean running = fight != null && !fight.isEnded() && !pre;
		final PanelSnapshot snapshot = new PanelSnapshot(SidePanelModel.fight(in),
			SidePanelModel.trip(readTrip(trip, history, head, running ? display : null)));
		SwingUtilities.invokeLater(() -> apply(snapshot));
	}

	/** Whether the switch reads Whole raid: the player's last choice, else the overlay's raid-scope setting. */
	private boolean wholeRaid()
	{
		final Boolean choice = wholeRaid;
		if (choice != null)
		{
			return choice;
		}
		return config != null && config.raidScope() == RaidScope.RAID;
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
		final List<Object> key = Arrays.asList(history.size(), head, trip.getStartMillis());
		if (!key.equals(tripSavedKey))
		{
			tripSavedKey = key;
			tripSaved = SidePanelHistory.tripSaved(history, trip.getStartMillis(), System.currentTimeMillis(),
				java.time.ZoneId.systemDefault());
		}
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

	/** Shows a snapshot (spec 5.1 step 2). Swing's thread only. */
	void apply(PanelSnapshot snapshot)
	{
		shown = snapshot;
		fightTab.apply(snapshot.fight);
		tripTab.apply(snapshot.trip);
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
			return (room, maxHp) -> version(room.getFights(), maxHp);
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

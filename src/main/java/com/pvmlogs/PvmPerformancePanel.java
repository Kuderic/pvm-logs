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

	// Swing's thread only.
	private PanelSnapshot shown;

	PvmPerformancePanel(PvmPerformancePlugin plugin)
	{
		this(plugin, instance(plugin, PvmPerformanceConfig.class), instance(plugin, ConfigManager.class),
			instance(plugin, ClientThread.class));
	}

	/** With every dependency given: tests and renders pass nulls and drive {@link #apply} themselves. */
	PvmPerformancePanel(PvmPerformancePlugin plugin, PvmPerformanceConfig config, ConfigManager configManager,
		ClientThread clientThread)
	{
		super(false);
		this.plugin = plugin;
		this.config = config;
		this.clientThread = clientThread;

		setBackground(SidePanelStyle.PANEL);
		setLayout(new BorderLayout());

		final SidePanelSection.Folds folds = configManager == null ? new SidePanelSection.MemoryFolds()
			: new ConfigFolds(configManager);
		final SidePanelFightTab.Actions actions = new Actions();
		fightTab = new SidePanelFightTab(actions, folds);
		historyTab = new SidePanelHistoryTab(plugin, actions);
		tripTab = new SidePanelTripTab(actions);

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

		final List<Object> shape = Arrays.asList(fight, fight != null && fight.isEnded(), pre, room, raid, whole,
			history.size(), head);
		final List<Object> key = key(shape, fight, room, raid, whole, pre);
		final boolean force = dirty;
		if (!force && key.equals(lastKey))
		{
			return;
		}
		final boolean running = fight != null && !fight.isEnded() && !pre;
		if (!force && running && config != null && !config.panelLive() && shape.equals(lastShape))
		{
			return;
		}
		dirty = false;
		lastKey = key;
		lastShape = shape;

		final SidePanelModel.Input in = read(fight, display, room, raid, whole, pre, history);
		final PanelSnapshot snapshot = new PanelSnapshot(SidePanelModel.fight(in));
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

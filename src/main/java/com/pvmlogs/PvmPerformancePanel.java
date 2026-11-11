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
	/** T-176: the party, on the Fight tab (it was the Party tab, T-125). */
	private final SidePanelPartySection partySection;
	private final MaterialTabGroup tabs;
	private final MaterialTab fightButton;
	private final MaterialTab historyButton;
	/** The strain probe (T-045): times apply and the repaints; null in tests. */
	private final PerfProbe perf;
	/** T-133: the dev-only timing of Swing's whole repaint pass; null unless asked for. */
	private final SidePanelRepaintTiming repaintTiming;

	// Swing's thread writes these, the client thread reads them.
	private volatile boolean active;
	private volatile boolean dirty = true;
	/** T-128: the member selected on the Fight tab's meter (Swing writes, the client thread reads), or none. */
	private volatile long partySelected = SidePanelParty.YOU_ID;
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
	/** T-196: the Fight tab's folds, for tests of the merged sections' keys. */
	private SidePanelSection.Folds fightFolds;

	SidePanelSection.Folds fightFolds()
	{
		return fightFolds;
	}

	/** T-130 review: where the Fight tab's copy of the chart was last sent to. */
	private final ChartBook chartBook = new ChartBook();
	/** The Damage rows' sums, kept between snapshots so a long fight's build costs what a short one's does (T-046). */
	private final SidePanelWeaponRows weaponRows = new SidePanelWeaponRows();
	/** T-095: the trip's rooms less its runs, as one room, and what it was built from. */
	private Encounter tripLuck;
	private List<Object> tripLuckKey;

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
	/**
	 * T-176: the sections folded until they're opened, each kept open under its own {@link #OPENED} key. T-205: none now;
	 * the party's Damage over time ({@code partyChart}) is Party's Timeline, and its key is read through {@link #MERGED}.
	 */
	static final java.util.Set<String> FOLDED_BY_DEFAULT = java.util.Collections.emptySet();
	static final String OPENED = ".opened";
	/**
	 * T-188: the Fight tab's sections fold below the kill card until opened, each kept open under its own
	 * {@link #OPENED} key; the keys they had stay as they were, for the other tabs. T-194 (Eric, 21:32: "I like having
	 * them open, at least for damage"): Damage isn't one, so it opens by default and keeps its own key, folded or open as
	 * you leave it. T-202: in every view, whatever Celebrations is; Damage over time, Ticks and Details aren't sections
	 * any more, so their keys are read only through {@link #MERGED}. T-205 (Eric: "open party by default when player is
	 * in a party"): Party isn't one either; it keeps its own key, as Damage does.
	 */
	static final java.util.Set<String> FOLDED_UNDER_CARD = java.util.Collections.unmodifiableSet(
		new java.util.HashSet<>(java.util.Arrays.asList("curve", "budget", "execution", "targets", "defence", "later",
			"taken")));
	/**
	 * T-196: the sections merged under the card, by the section that holds them now: Damage over time ({@code chart}) in
	 * Damage, Ticks in Your play ({@code execution}), Details' hits in Luck ({@code curve}); T-205: the party's Damage
	 * over time ({@code partyChart}) in Party. Each kept its fold key ({@code <id>.opened}, T-188, T-176): one left open
	 * opens the section it's in, and folding or opening that section writes the old key too, so the old one never
	 * overrules it again.
	 */
	static final java.util.Map<String, java.util.List<String>> MERGED;

	static
	{
		final java.util.Map<String, java.util.List<String>> m = new java.util.HashMap<>();
		m.put("damage", java.util.Collections.singletonList("chart"));
		m.put("execution", java.util.Collections.singletonList("ticks"));
		m.put("curve", java.util.Collections.singletonList("details"));
		m.put(SidePanelPartySection.PARTY, java.util.Collections.singletonList(SidePanelPartySection.PARTY_CHART));
		MERGED = java.util.Collections.unmodifiableMap(m);
	}

	/**
	 * T-199: the key that says Damage has been opened once on this build. T-188 to T-193 kept Damage under the card
	 * with its own {@code damage.opened} key and never read {@code panelFold.damage}; T-194 went back to that shared key,
	 * so a fold saved there by an earlier build (before T-188, or with Celebrations off) folded Damage again.
	 */
	static final String DAMAGE_REOPENED = "damage.reopened";

	/**
	 * T-199 (Eric: "Damage tab should be open by default"): Damage opens once on this build, even where an earlier one
	 * saved it folded; after that, its fold is remembered as usual. Returns whether it opened it now.
	 */
	static boolean reopenDamageOnce(SidePanelSection.Folds stored)
	{
		if (stored.isFolded(DAMAGE_REOPENED))
		{
			return false;
		}
		stored.setFolded("damage", false);
		stored.setFolded(DAMAGE_REOPENED, true);
		return true;
	}

	/**
	 * T-205: the key that says Party has been opened once on this build. T-188 to T-204 kept Party under the card, open
	 * only under {@code party.opened}; T-205 reads its own key, {@code party}, which a build before T-188 may have
	 * saved folded.
	 */
	static final String PARTY_REOPENED = "party.reopened";

	/**
	 * T-205 (Eric: "open party by default when player is in a party"): Party opens once on this build, as Damage did in
	 * T-199, even where an earlier build saved it folded; after that, its fold is remembered as usual. Returns whether
	 * it opened it now.
	 */
	static boolean reopenPartyOnce(SidePanelSection.Folds stored)
	{
		if (stored.isFolded(PARTY_REOPENED))
		{
			return false;
		}
		stored.setFolded(SidePanelPartySection.PARTY, false);
		stored.setFolded(PARTY_REOPENED, true);
		return true;
	}

	/** T-188: Celebrations for tests and renders, which have no config; null reads the setting. */
	private Boolean celebrations;

	/** The tabs, as {@link #visibleTab} holds them. T-176: the Party tab's gone; the party is on the Fight tab. */
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
	/** T-119: the trip's drain specials and the timelines' expectations of them, rebuilt with {@link #tripSaved}. */
	private java.util.Map<String, int[]> tripSpecs = java.util.Collections.emptyMap();
	private List<SidePanelModel.WeaponRow> tripSpecRows = java.util.Collections.emptyList();

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
		reopenDamageOnce(stored);
		reopenPartyOnce(stored);
		// A fold updates at once (T-046): an unfolded section shows this tick's figures, not those of up to 3 s ago.
		final SidePanelSection.Folds folds = new SidePanelSection.Folds()
		{
			@Override
			public boolean isFolded(String id)
			{
				// T-176: a section folded by default is folded until it's opened, which is kept under its own key.
				return FOLDED_BY_DEFAULT.contains(id) ? !stored.isFolded(id + OPENED) : stored.isFolded(id);
			}

			@Override
			public void setFolded(String id, boolean folded)
			{
				if (FOLDED_BY_DEFAULT.contains(id))
				{
					stored.setFolded(id + OPENED, !folded);
				}
				else
				{
					stored.setFolded(id, folded);
				}
				requestUpdate();
			}
		};
		// T-188: the Fight tab's own: its sections fold under the card until they're opened (T-202: Celebrations or not).
		final SidePanelSection.Folds fightFolds = new SidePanelSection.Folds()
		{
			@Override
			public boolean isFolded(String id)
			{
				// T-196: a section merged into this one, left open before, opens it (an opened Damage over time opens
				// Damage with its chart).
				if (mergedOpened(id))
				{
					return false;
				}
				return underCard(id) ? !stored.isFolded(id + OPENED) : folds.isFolded(id);
			}

			@Override
			public void setFolded(String id, boolean folded)
			{
				// T-196: folding or opening it settles the merged sections' keys too, so the old ones never overrule it.
				for (String old : MERGED.getOrDefault(id, java.util.Collections.emptyList()))
				{
					stored.setFolded(old + OPENED, !folded);
				}
				if (underCard(id))
				{
					stored.setFolded(id + OPENED, !folded);
					requestUpdate();
				}
				else
				{
					folds.setFolded(id, folded);
				}
			}

			private boolean mergedOpened(String id)
			{
				for (String old : MERGED.getOrDefault(id, java.util.Collections.emptyList()))
				{
					if (stored.isFolded(old + OPENED))
					{
						return true;
					}
				}
				return false;
			}
		};
		this.fightFolds = fightFolds;
		final SidePanelFightTab.Actions actions = new Actions();
		fightTab = new SidePanelFightTab(actions, fightFolds, false, icons);
		historyTab = new SidePanelHistoryTab(historySource != null ? historySource : plugin == null ? null
			: new HistorySource(), actions, folds);
		tripTab = new SidePanelTripTab(actions, folds);
		// T-130: the member selected on the meter. T-176: the party's blocks, placed by the Fight tab.
		partySection = new SidePanelPartySection(actions, fightFolds, icons, id ->
		{
			partySelected = id;
			requestUpdate();
		});
		fightTab.setParty(partySection);
		// T-114: the Calibration report button only with Debug logging on.
		historyTab.setDebug(config != null && config.debugLogging());
		fightTab.setPerf(perf);
		historyTab.setPerf(perf);
		tripTab.setPerf(perf);
		// T-133: Swing's whole repaint pass timed, in a dev client started with -PrepaintTiming only.
		repaintTiming = SidePanelRepaintTiming.installIfAsked(perf);

		final JPanel display = new JPanel(new BorderLayout());
		display.setBackground(SidePanelStyle.PANEL);
		tabs = new MaterialTabGroup(display);
		tabs.setBorder(new EmptyBorder(4, SidePanelStyle.SIDE_BORDER, 0, SidePanelStyle.SIDE_BORDER));
		tabs.setBackground(SidePanelStyle.PANEL);
		// T-176: three tabs again, so RuneLite's own gaps (T-125 closed them up for a fourth).
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
			// T-114: Debug logging shows the Calibration report button; a change refreshes History once.
			historyTab.setDebug(config.debugLogging());
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
		// T-068 part 2 (T-070): over by the overlay's rule, the room's, so an add's or one titan's death isn't the end.
		final boolean over = PvmPerformanceOverlay.isOver(fight, display);
		// T-125: the party's board changes whatever the player is doing.
		final PartyBoard board = plugin.getPartyBoard();
		final boolean fighting = fight != null && !over && !pre;
		final boolean onFight = tab == FIGHT_TAB;
		final List<Object> shape = Arrays.asList(fight, over, pre, room, raid, whole,
			history.size(), head, trip.getStartMillis(), tab,
			// T-130 review: during your fight, a member's update waits for the next snapshot (every 5 ticks), as your own
			// figures do: members' updates come at their own ticks, so a 4-man party's would build nearly every tick.
			// T-176: the party is the Fight tab's; the Trip tab never builds for it.
			board == null || fighting || !onFight ? 0L : board.revision(), partySelected,
			// T-129: joining or leaving a party, or a member coming or going, rebuilds the party's block at once, in or out
			// of a fight. T-167: with each member's picture and name, from the same read. One read of the members a tick,
			// as the Party tab had: out of a party, none (isInParty is a field).
			onFight ? partyState() : null);
		final boolean force = dirty;
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
			final SidePanelModel.Input in = read(fight, display, room, raid, whole, pre, history);
			// T-176: the party with the Fight tab, in a party or with the board holding anyone (the dev loopback's echo
			// too); out of one, an empty party, so the party's block goes.
			// A read that clashed with a member coming or going (rare) keeps what's shown, and reads again next tick.
			final PanelSnapshot.PartyTab party = partyMembers != null || board != null && !board.isEmpty()
				? SidePanelParty.party(readParty(fight, display, room, raid, whole, pre, false))
				: partyClashed ? null : SidePanelParty.party(new SidePanelParty.Input());
			snapshot = new PanelSnapshot(SidePanelModel.fight(in), null, party);
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
					room == null ? 0 : room.getFights().size(), room == null || whole ? 0 : room.getKills()));
			}
		}
		final SessionTotals t = plugin.getSession();
		key.addAll(Arrays.asList(t.getCombatTicks(), t.getDamageDealt(), t.getSumExpectedAverageHit(), t.getAttacksMade(),
			t.getDamageTaken(), t.getThrallDamage(), t.getDotDamage(), t.getReflectDamage(), t.getTicksLost(), t.getKills(),
			t.getFights()));
		return key;
	}

	/** T-188: T-187's standing as the card reads it ({@code claude/specs/t187-t188-record-api.md}); null for none. */
	static SidePanelModel.Standing standing(BossRecords.Standing r)
	{
		if (r == null)
		{
			return null;
		}
		final SidePanelModel.Standing s = new SidePanelModel.Standing();
		s.previousBestClock = r.previousBestClock;
		s.newPb = r.newPb;
		s.previousBestLuckRemoved = r.previousBestLuckRemoved;
		s.newBestLuckRemoved = r.newBestLuckRemoved;
		s.rank = r.rank;
		s.ranked = r.ranked;
		s.grade = r.grade == null ? null : r.grade.name();
		s.basis = r.basis;
		s.ticksToNext = r.ticksToNext;
		s.next = r.next == null ? null : r.next.name();
		s.streak = r.streak;
		s.flawlessStreak = r.flawlessStreak;
		// T-193: what this kill earned, by T-192's shared rules, and the game's count it read them at.
		s.badges = r.badges;
		s.kc = r.kc;
		return s;
	}

	/**
	 * T-188: whether Celebrations is on: the setting, else what a test set. T-202 (Eric: "Why does the celebrations
	 * setting move damage over time out from damage?"): it's the kill-end moment and nothing else, the die roll and the
	 * flash ({@code KillCard.moment}); the tab, its folds and the record are the same either way.
	 */
	boolean celebrations()
	{
		return celebrations != null ? celebrations : config != null && config.panelCelebrations();
	}

	/** T-188: for tests and renders, which have no config. */
	void setCelebrations(boolean on)
	{
		celebrations = on;
	}

	/** T-188: a Fight tab section folded under the kill card by default (T-202: whatever Celebrations is). */
	private static boolean underCard(String id)
	{
		return FOLDED_UNDER_CARD.contains(id);
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
			// As the overlay's raid view: over when the fight on show and its room are (T-070).
			in.ended = PvmPerformanceOverlay.isOver(fight, display);
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
		in.celebrations = celebrations();
		if (in.drain != null)
		{
			// T-142: a Defence drain helps magic too where the boss rolls magic defence off its Defence.
			in.drain.magicUsesDefence = SidePanelModel.magicUsesDefence(rules(), room.getFights());
		}
		// T-109: what the room's damage was, named by the boss rules.
		SidePanelModel.fillRoomDamageSources(in, java.util.Collections.singletonList(room), rules());
		if (in.run)
		{
			// T-095: the run's own clock, the overlay's title's.
			in.runSeconds = SidePanelModel.runSeconds(room, System.currentTimeMillis());
		}
		in.version = version(room.getFights(), in.maxHp);
		charts(in, room, FightTimeline.room(room, plugin.getTimelines()));
		if (in.view != null && in.bossOnly != null)
		{
			// T-099: each side of the Damage card's switch over its own fights' timelines.
			in.bossOnly.view = FightTimeline.room(in.bossOnly.part, plugin.getTimelines());
			in.addsOnly.view = FightTimeline.room(in.addsOnly.part, plugin.getTimelines());
		}
		final boolean current = contains(room, fight);
		in.ended = roomEnded(room, fight);
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
		if (in.killed)
		{
			// T-188: the game's kill count the kill message gave, for the card's corner.
			for (Fight f : room.getFights())
			{
				in.kc = Math.max(in.kc, f.getContext() == null ? 0 : f.getContext().kc);
			}
			// T-188: the kill's place in its boss's record (T-187): a lookup, null until the kill's history save has run.
			final BossRecords records = plugin == null ? null : plugin.getRecords();
			in.standing = standing(records == null ? null : records.standing(room));
		}
		// T-095: a run has no "your average": comparing runs' clocks says nothing.
		if (in.killed && room.getRaid() == null && in.warning == null && !in.run)
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
			chartBook.reset();
			return;
		}
		in.view = view;
		final Object key = Arrays.asList(scope, view.startTick);
		in.weaponRows = weaponRows.rows(key, view);
		in.chartFrom = chartBook.from(key, view.states.length);
	}

	/**
	 * T-036: a copy of the chart on Swing's side, and what it has been sent: each snapshot sends the series from a few
	 * ticks behind the last one's end on the same scope, else from 0, which starts the copy over. T-130 review: one per
	 * copy. Client thread.
	 */
	static final class ChartBook
	{
		private Object scope;
		private int sent;

		/** The index this snapshot sends from, for a scope {@code key} whose series is {@code length} long now. */
		int from(Object key, int length)
		{
			final int from = key.equals(scope) ? Math.max(0, Math.min(sent, length) - CHART_RESEND) : 0;
			scope = key;
			sent = length;
			return from;
		}

		void reset()
		{
			scope = null;
			sent = 0;
		}
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

	/** T-119: the trip's own drain specials by weapon, {thrown, landed}, summed over its saved fights' drain records. */
	static java.util.Map<String, int[]> tripSpecs(List<Fight> fights)
	{
		final java.util.Map<String, int[]> out = new java.util.LinkedHashMap<>();
		for (Fight f : fights)
		{
			final DrainRecord r = f.getDrainRecord();
			if (r == null || r.immune || r.specs == null)
			{
				continue;
			}
			for (java.util.Map.Entry<String, int[]> e : r.specs.entrySet())
			{
				final int[] sum = out.computeIfAbsent(e.getKey(), k -> new int[2]);
				sum[0] += e.getValue()[0];
				sum[1] += e.getValue()[1];
			}
		}
		return out;
	}

	/** T-123: the fights of the trip's saved rooms, newest room first. */
	static List<Fight> tripFights(List<SidePanelHistory.KillRow> saved)
	{
		final List<Fight> out = new ArrayList<>();
		for (SidePanelHistory.KillRow r : saved)
		{
			out.addAll(r.room.getFights());
		}
		return out;
	}

	// ---- T-125: the party (on the Fight tab since T-176) ----

	/** Each member's colour, by the order they were first seen, kept while the board has anyone on it. Client thread. */
	private final java.util.Map<Long, Integer> partySlots = new java.util.HashMap<>();
	/** The members seen on the scope on show, so one who leaves stays as a muted row. Client thread. */
	private final java.util.Map<Long, SidePanelParty.Member> partySeen = new java.util.LinkedHashMap<>();
	private Object partySeenScope;
	/** Item names for the members' attack rows, looked up once each. Client thread. */
	private final java.util.Map<Integer, String> itemNames = new java.util.HashMap<>();

	/**
	 * T-125: the party's input. The player's own row is {@link PartyShare#build}'s, the same as what they send; the
	 * members' are the board's, for the room on show (by its name, updated since it started) or summed over the raid.
	 * {@code light}: the Fight tab's line only, without attack rows or series. Client thread.
	 */
	/** T-209: the party members in any of these rooms' fights; null when none was fought in a party. */
	private java.util.Set<Long> inFight(List<Encounter> rooms)
	{
		java.util.Set<Long> all = null;
		for (Encounter r : rooms)
		{
			final java.util.Set<Long> seen = plugin.partyInFight(r);
			if (seen != null)
			{
				all = all == null ? new java.util.HashSet<>() : all;
				all.addAll(seen);
			}
		}
		return all;
	}

	private SidePanelParty.Input readParty(Fight fight, Encounter display, Encounter room, Raid raid, boolean whole,
		boolean pre, boolean light)
	{
		final SidePanelParty.Input in = new SidePanelParty.Input();
		final net.runelite.client.party.PartyService party = instance(plugin, net.runelite.client.party.PartyService.class);
		in.inParty = party != null && party.isInParty();
		// T-129, T-130: the party's size, and which party it is.
		in.partySize = in.inParty ? party.getMembers().size() : 0;
		in.partyKey = in.inParty ? party.getPartyId() : 0;
		final PartyBoard board = plugin.getPartyBoard();
		if (board == null)
		{
			return in;
		}
		final List<PartyBoard.View> views = board.view(id ->
		{
			final net.runelite.client.party.PartyMember m = memberById(party, id);
			return m == null ? null : m.getDisplayName();
		});
		in.shared = views.size();
		if (!light && in.inParty && partyMembers != null)
		{
			// T-167: the members who haven't shared, from this tick's read for the party key (no read of its own).
			final java.util.Set<Long> onBoard = new java.util.HashSet<>();
			for (PartyBoard.View v : views)
			{
				onBoard.add(v.memberId);
			}
			final net.runelite.client.party.PartyMember me = localMember(party);
			final List<SidePanelParty.Unshared> all = new ArrayList<>(partyMembers.size());
			for (net.runelite.client.party.PartyMember m : partyMembers)
			{
				all.add(new SidePanelParty.Unshared(m.getMemberId(), m.getDisplayName(), m.getAvatar()));
			}
			in.unshared.addAll(SidePanelParty.unshared(all, me == null ? Long.MIN_VALUE : me.getMemberId(), onBoard));
		}
		if (views.isEmpty())
		{
			partySlots.clear();
		}
		for (PartyBoard.View v : views)
		{
			partySlots.computeIfAbsent(v.memberId, k -> partySlots.size());
		}
		in.wholeRaid = raid == null ? null : whole;
		final boolean raidScope = whole && raid != null;
		if (fight == null || pre && !raidScope || !raidScope && room == null)
		{
			return in;
		}
		final net.runelite.client.game.ItemManager items = light ? null
			: instance(plugin, net.runelite.client.game.ItemManager.class);
		in.itemNames = id -> itemNames.computeIfAbsent(id, k ->
		{
			try
			{
				return items == null ? "item " + k : items.getItemComposition(k).getName();
			}
			catch (RuntimeException e)
			{
				return "item " + k;
			}
		});
		final LedgerRows.Numbers n = new LedgerRows.Numbers(raidScope ? LedgerRows.of(raid) : LedgerRows.of(room));
		in.seconds = n.clock;
		in.live = raidScope ? !PvmPerformanceOverlay.isOver(fight, display) : !roomEnded(room, fight);
		final List<Encounter> rooms = raidScope ? raid.getEncounters() : java.util.Collections.singletonList(room);
		in.scope = raidScope ? raid.getName() : room.getName();
		// T-209: who of the party was in the fight, for others' split (and T-208's rows): over the raid, any room of it.
		in.inFight = inFight(rooms);
		// The player's own figures, as they send them.
		final List<LuckVsSkillUpdate> mine = new ArrayList<>();
		for (Encounter e : rooms)
		{
			final boolean running = in.live && e == rooms.get(rooms.size() - 1);
			mine.add(PartyShare.build(running ? LuckVsSkillUpdate.LIVE : LuckVsSkillUpdate.ROOM_END, e, light
				? java.util.Collections.<FightTimeline.Attack>emptyList() : FightTimeline.room(e, plugin.getTimelines()).attacks,
				0));
			for (Fight f : e.getFights())
			{
				in.othersDamage += f.getOthersDamage();
			}
		}
		// The player's own row has an id of its own: the dev loopback's echo is a member with the player's party id.
		final SidePanelParty.Member you = new SidePanelParty.Member(SidePanelParty.YOU_ID, SidePanelParty.YOU_NAME, true,
			raidScope ? SidePanelParty.sum(mine) : mine.get(0));
		final net.runelite.client.party.PartyMember me = localMember(party);
		you.avatar = me == null ? null : me.getAvatar();
		// T-176: your Fight panels are the Fight tab's own, under the party: no second build of them here.
		if (!light)
		{
			final int[][] series = SidePanelParty.series((raidScope ? FightTimeline.raid(raid, plugin.getTimelines())
				: FightTimeline.room(room, plugin.getTimelines())).damage);
			you.ticks = series[0];
			you.damage = series[1];
		}
		in.members.add(you);
		// The party's: the room on show by its name, sent since it started; or their raid's rooms summed.
		final long since = rooms.get(0).getStartMillis();
		final Object scopeKey = Arrays.asList(in.scope, since, raidScope);
		if (!scopeKey.equals(partySeenScope))
		{
			partySeen.clear();
			partySeenScope = scopeKey;
		}
		final java.util.Set<Long> present = new java.util.HashSet<>();
		for (PartyBoard.View v : views)
		{
			final SidePanelParty.Member m = member(v, raidScope, in.scope, since, light);
			if (m == null)
			{
				continue;
			}
			m.slot = partySlots.getOrDefault(v.memberId, 0);
			// T-169: the loopback's echo of your own figures: shown, never counted.
			m.echo = SidePanelParty.echo(v.memberId, v.name, me == null ? Long.MIN_VALUE : me.getMemberId());
			final net.runelite.client.party.PartyMember pm = memberById(party, v.memberId);
			m.avatar = pm == null ? null : pm.getAvatar();
			if (!light && !raidScope)
			{
				// T-130 review: every member's, so a click shows theirs at once, never the compact breakdown first.
				// A room's are built once per message; a live member's are the live update's few cards.
				memberPanels.attach(m, v.fights, in.itemNames);
			}
			in.members.add(m);
			present.add(v.memberId);
			partySeen.put(v.memberId, m);
		}
		// One who left the party with the scope still on show stays, muted, with their last figures.
		for (SidePanelParty.Member gone : partySeen.values())
		{
			if (!present.contains(gone.id))
			{
				gone.left = true;
				in.members.add(gone);
			}
		}
		return in;
	}

	/**
	 * T-167: the party's members as the last party key read them (the Fight tab's, each tick it shows), for its list of
	 * members who haven't shared; null out of a party, or after a read that clashed. Client thread.
	 */
	private List<net.runelite.client.party.PartyMember> partyMembers;
	/** T-176: this tick's read of the members clashed with one coming or going: the party's block stays as it was. */
	private boolean partyClashed;

	/**
	 * T-129: whether the player is in a party, which, and its members, as they stand; two reads of PartyService. T-167:
	 * with each member's picture and name, so one arriving later rebuilds the tab, and the members kept for its list.
	 */
	private List<Object> partyState()
	{
		partyMembers = null;
		partyClashed = false;
		final net.runelite.client.party.PartyService party = instance(plugin, net.runelite.client.party.PartyService.class);
		if (party == null || !party.isInParty())
		{
			return partyState(false, 0, java.util.Collections.<Long>emptyList());
		}
		final List<net.runelite.client.party.PartyMember> members = copy(party.getMembers());
		if (members == null)
		{
			// A member came or went mid-read: a change, so the tab builds now and reads them again next tick.
			partyClashed = true;
			return changing();
		}
		partyMembers = members;
		final List<Long> ids = new ArrayList<>(members.size());
		final List<Object> looks = new ArrayList<>(members.size() * 2);
		for (net.runelite.client.party.PartyMember m : members)
		{
			ids.add(m.getMemberId());
			looks.add(look(m.getAvatar(), m.getDisplayName()));
		}
		return partyState(true, party.getPartyId(), ids, looks);
	}

	/**
	 * T-167: a member's picture and name in the party key, without holding either: the picture's identity, the name's
	 * hash (the key is never written anywhere, but a name needn't sit in it).
	 */
	static List<Integer> look(java.awt.image.BufferedImage avatar, String name)
	{
		return Arrays.asList(avatar == null ? 0 : System.identityHashCode(avatar), name == null ? 0 : name.hashCode());
	}

	/** T-167: the party key with each member's {@link #look}, so a picture or name arriving later rebuilds the tab. */
	static List<Object> partyState(boolean inParty, long partyId, List<Long> members, List<Object> looks)
	{
		final List<Object> out = new ArrayList<>(partyState(inParty, partyId, members));
		if (inParty)
		{
			out.add(new ArrayList<>(looks));
		}
		return out;
	}

	/**
	 * T-130 review: the party's members as they stand, or null when the list changed while it was read. In RuneLite
	 * 1.13.4 {@code getMembers()} is a plain ArrayList, which {@code onUserJoin} and {@code onUserPart} change on OkHttp's
	 * thread (WSClient posts there), so walking it from the client thread can throw.
	 */
	static <T> List<T> copy(List<T> members)
	{
		try
		{
			final List<T> out = new ArrayList<>(members.size());
			for (T m : members)
			{
				out.add(m);
			}
			return out;
		}
		catch (java.util.ConcurrentModificationException e)
		{
			return null;
		}
	}

	/** T-130 review: PartyService's lookups walk the same list: null on a clash, and the next tick builds again. */
	private net.runelite.client.party.PartyMember memberById(net.runelite.client.party.PartyService party, long id)
	{
		try
		{
			return party == null ? null : party.getMemberById(id);
		}
		catch (java.util.ConcurrentModificationException e)
		{
			dirty = true;
			return null;
		}
	}

	private net.runelite.client.party.PartyMember localMember(net.runelite.client.party.PartyService party)
	{
		try
		{
			return party == null ? null : party.getLocalMember();
		}
		catch (java.util.ConcurrentModificationException e)
		{
			dirty = true;
			return null;
		}
	}

	/** T-130 review: a party key equal to no other, so the tick that hit a member list mid-change builds. */
	static List<Object> changing()
	{
		return java.util.Collections.<Object>singletonList(new Object());
	}

	/** T-129: the party key: whether in one, which, and who's in it; equal while none of those changed. */
	static List<Object> partyState(boolean inParty, long partyId, List<Long> members)
	{
		if (!inParty)
		{
			return java.util.Collections.emptyList();
		}
		final List<Object> out = new ArrayList<>(members.size() + 1);
		out.add(partyId);
		out.addAll(members);
		return out;
	}

	/** T-128, T-130 review: members' Fight panels, a room's built once per message. Client thread. */
	private final SidePanelMemberFight.Panels memberPanels = new SidePanelMemberFight.Panels();

	/** T-125: a member's figures for the scope on show; null when they have none for it. */
	private static SidePanelParty.Member member(PartyBoard.View v, boolean raidScope, String scope, long since,
		boolean light)
	{
		final LuckVsSkillUpdate figures;
		final List<List<PartyBoard.Point>> series = new ArrayList<>();
		if (raidScope)
		{
			final List<LuckVsSkillUpdate> rooms = new ArrayList<>(v.raidRooms);
			if (v.live != null && v.live.rn != null)
			{
				rooms.add(v.live);
			}
			if (rooms.isEmpty())
			{
				return null;
			}
			figures = SidePanelParty.sum(rooms);
			for (LuckVsSkillUpdate u : rooms)
			{
				series.add(v.series.getOrDefault(u.roomKey(), java.util.Collections.<PartyBoard.Point>emptyList()));
			}
		}
		else
		{
			if (v.updatedMillis < since)
			{
				return null;
			}
			figures = v.live != null && scope.equals(v.live.r) ? v.live : v.lastRoom != null && scope.equals(v.lastRoom.r)
				? v.lastRoom : null;
			if (figures == null)
			{
				return null;
			}
			series.add(v.series.getOrDefault(figures.roomKey(), java.util.Collections.<PartyBoard.Point>emptyList()));
		}
		final SidePanelParty.Member m = new SidePanelParty.Member(v.memberId, v.name, false, figures);
		if (!light)
		{
			final int[][] s = SidePanelParty.concat(series);
			m.ticks = s[0];
			m.damage = s[1];
		}
		return m;
	}

	/** The trip's figures and rooms (spec 3.3); client thread. The saved rooms are rebuilt only when the history or trip change. */
	private SidePanelModel.Input readTrip(SessionTotals trip, List<Fight> history, Fight head, Encounter running)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = LedgerRows.of(trip);
		in.tripSince = trip.getStartMillis();
		in.hitsTaken = trip.getHitsTaken();
		in.missedHitsTaken = trip.getMissedHitsTaken();
		// The trip records thrall, DoT and reflect on scored fights only (T-025), so its other damage is the scored kind too.
		in.scoredThrall = in.thrall = Math.max(0, trip.getThrallDamage());
		// T-204: the cannon's, on scored fights, as the thrall's.
		in.scoredCannon = Math.max(0, trip.getCannonDamage());
		in.scoredDot = in.dot = Math.max(0, trip.getDotDamage());
		// T-063: by kind; DoT booked before T-062 is the remainder.
		in.scoredDotByKind = in.dotByKind = trip.getDotDamageByKind();
		in.scoredReflect = in.reflect = Math.max(0, trip.getReflectDamage());
		// T-123: the room on now is the trip's only if it started since; a run of monsters (T-089), its part since.
		final Encounter mine = SidePanelHistory.sinceTrip(running, trip.getStartMillis());
		// T-044: the trip's avoidable figures sum its ended ruled fights, shown, as on the overlay, while no fight of the
		// trip runs.
		in.ended = mine == null;
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
			// T-123: the fights of the trip's rooms, so the avoidable names and the drain specials are the rows' own.
			final List<Fight> tripFights = tripFights(tripSaved);
			tripAvoidable = avoidableAttacks(tripFights);
			// T-119: the trip's drain specials, thrown and landed, and what this session's timelines expected of them.
			tripSpecs = tripSpecs(tripFights);
			tripSpecRows = clientThread == null || plugin == null || (config != null && !config.panelCharts())
				? java.util.Collections.<SidePanelModel.WeaponRow>emptyList()
				: SidePanelModel.specials(SidePanelModel.weaponRows(FightTimeline.view(tripFights, plugin.getTimelines())));
		}
		in.avoidableAttacks = tripAvoidable;
		drains.specs = tripSpecs;
		drains.specRows = tripSpecRows;
		final List<PanelSnapshot.TripRow> rows = new ArrayList<>();
		final List<SidePanelHistory.KillRow> rooms = SidePanelHistory.tripRooms(tripSaved, mine);
		for (SidePanelHistory.KillRow r : rooms)
		{
			rows.add(SidePanelModel.tripRow(r));
		}
		in.tripRows = rows;
		in.luckScope = tripLuckScope(rooms);
		// T-109: the bosses' rooms' damage over the trip's saved rooms; shown while no fight runs.
		final List<Encounter> saved = new ArrayList<>();
		for (SidePanelHistory.KillRow r : tripSaved)
		{
			saved.add(r.room);
		}
		SidePanelModel.fillTripRoomDamage(in, saved, rules());
		return in;
	}

	/** T-109: the boss rules, from the plugin's own MonsterStatsProvider; null without a plugin or before they load. */
	private BossRules rules()
	{
		final MonsterStatsProvider stats = instance(plugin, MonsterStatsProvider.class);
		return stats == null ? null : stats.rules();
	}

	/**
	 * T-095: the scope the Trip tab's Luck and Execution are over when the trip has runs in it: its rooms less the runs,
	 * one room of their fights; null when it has no run, so they're the trip's own figures, as before. Rebuilt when the
	 * trip's rooms change (a fight saved, a room started), which isn't every tick; while a boss is fought, its room is
	 * in it as it stands.
	 */
	private LedgerRows.Scope tripLuckScope(List<SidePanelHistory.KillRow> rooms)
	{
		boolean runs = false;
		for (SidePanelHistory.KillRow r : rooms)
		{
			runs |= r.room != null && r.room.isMonsterRoom();
		}
		if (!runs)
		{
			tripLuckKey = null;
			tripLuck = null;
			return null;
		}
		// Only the rooms that aren't runs: a run being fought changes nothing here.
		final List<Object> key = new ArrayList<>();
		for (SidePanelHistory.KillRow r : rooms)
		{
			if (r.room != null && !r.room.isMonsterRoom())
			{
				key.add(r.room);
				key.add(r.room.getFights().size());
			}
		}
		if (!key.equals(tripLuckKey))
		{
			tripLuckKey = key;
			tripLuck = SidePanelModel.withoutRuns(rooms);
		}
		return LedgerRows.of(tripLuck);
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

	/**
	 * T-068 part 2 (T-067's finding D-3): whether the Fight tab shows the room after its fight. A room other than the
	 * running fight's (one picked from the raid's list) is; the running fight's room is over only by T-070's rule, as
	 * the overlay has it. A dead respiratory system or one titan down ends that fight, not the room: the panel stays
	 * live, and the after-fight cards, "your average" and "luck removed" wait for the boss.
	 */
	static boolean roomEnded(Encounter room, Fight fight)
	{
		return !contains(room, fight) || PvmPerformanceOverlay.isOver(fight, room);
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
		partySection.restyle();
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
		if (repaintTiming != null)
		{
			repaintTiming.applying(snapshot.trip != null ? "Trip" : "Fight");
		}
		shown = snapshot;
		if (snapshot.fight != null)
		{
			lastFight = snapshot.fight;
		}
		if (snapshot.trip != null)
		{
			lastTrip = snapshot.trip;
		}
		// T-176: the party first, so the Fight tab places its blocks as this snapshot has them.
		partySection.apply(snapshot.party);
		if (snapshot.fight != null)
		{
			fightTab.apply(snapshot.fight);
		}
		else if (snapshot.party != null)
		{
			// The party alone (tests): the Fight tab places its blocks again with the last fight it had.
			fightTab.partyChanged();
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

	/** T-176: the party's blocks on the Fight tab. */
	SidePanelPartySection partySection()
	{
		return partySection;
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

				@Override
				public boolean magicUsesDefence(Encounter room)
				{
					return SidePanelModel.magicUsesDefence(rules(), room.getFights());
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
		public BossRecords.View record(LedgerHistory.Like like)
		{
			// T-190: an immutable copy, made when History refreshes (its tab opened, a boss or version picked).
			final BossRecords records = plugin.getRecords();
			return records == null ? null : records.view(like);
		}

		@Override
		public BossRules rules()
		{
			// T-102: the rules the history budget classes fights by, from the plugin's own MonsterStatsProvider.
			return PvmPerformancePanel.this.rules();
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

	// ---- T-111: the calibration report ----

	private static final java.time.format.DateTimeFormatter CALIBRATION_STAMP =
		java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** T-111: the report's file name, {@code calibration-20261030-011500.txt}. */
	static String calibrationFile(java.time.LocalDateTime when)
	{
		return "calibration-" + CALIBRATION_STAMP.format(when) + ".txt";
	}

	/** T-111: how many groups the report flagged: the lines under its {@code FLAGGED:} heading. */
	static int calibrationFlags(String report)
	{
		int flags = 0;
		boolean in = false;
		for (String line : report.split("\n"))
		{
			if (line.startsWith("FLAGGED"))
			{
				in = line.equals("FLAGGED:");
				continue;
			}
			if (in && line.startsWith("  "))
			{
				flags++;
			}
			else if (in)
			{
				break;
			}
		}
		return flags;
	}

	/**
	 * T-111: the status line once it's saved: {@code Saved calibration report (3 flags)}. The file's dated name ({@link
	 * #calibrationFile}) is too long for the line at 1×, so it isn't repeated there.
	 */
	static String calibrationStatus(int flags)
	{
		return "Saved calibration report (" + (flags == 0 ? "nothing flagged" : flags == 1 ? "1 flag" : flags + " flags")
			+ ")";
	}

	/**
	 * T-111: the report built and saved on RuneLite's executor, never Swing's thread or the client thread: {@code
	 * report} reads a copy of the history, and the file goes to the plugin's data folder through {@code Filepath}.
	 * Nothing is sent anywhere. The status line says what came of it.
	 */
	static void saveCalibration(java.util.concurrent.Executor executor, java.util.function.Supplier<String> report,
		java.util.function.Supplier<net.runelite.client.util.Filepath> folder,
		java.util.function.Consumer<String> status)
	{
		saveCalibration(executor, report, () -> null, folder, status, null);
	}

	/**
	 * T-114: as T-111's, and then the findings: {@code summary} (T-113's plain sentences) is read on the executor too,
	 * and {@code saved} is given the file's name and the sentences, to show on Swing's thread. Without {@code saved},
	 * T-111's status line.
	 */
	static void saveCalibration(java.util.concurrent.Executor executor, java.util.function.Supplier<String> report,
		java.util.function.Supplier<java.util.List<String>> summary,
		java.util.function.Supplier<net.runelite.client.util.Filepath> folder,
		java.util.function.Consumer<String> status, java.util.function.BiConsumer<String, java.util.List<String>> saved)
	{
		status.accept("Building the calibration report…");
		executor.execute(() ->
		{
			try
			{
				final net.runelite.client.util.Filepath dir = folder.get();
				if (dir == null)
				{
					status.accept("Calibration report failed: no data folder.");
					return;
				}
				final String text = report.get();
				dir.createDirectories();
				final String name = calibrationFile(java.time.LocalDateTime.now());
				try (java.io.Writer w = dir.joinSegment(name).openBufferedWriter())
				{
					w.write(text);
					w.write("\n");
				}
				if (saved == null)
				{
					status.accept(calibrationStatus(calibrationFlags(text)));
					return;
				}
				final java.util.List<String> lines = summary.get();
				saved.accept(name, lines == null ? java.util.Collections.<String>emptyList() : lines);
			}
			catch (java.io.IOException | RuntimeException e)
			{
				status.accept("Calibration report failed: " + e.getMessage());
			}
		});
	}

	/** The buttons' actions, passed on to the plugin. */
	private final class Actions implements SidePanelFightTab.Actions
	{
		@Override
		public void calibrationReport()
		{
			final java.util.concurrent.ScheduledExecutorService executor = instance(plugin,
				java.util.concurrent.ScheduledExecutorService.class);
			if (plugin == null || executor == null)
			{
				return;
			}
			// T-114: the findings in plain words in the panel, and the file named.
			saveCalibration(executor, plugin::calibrationReport, plugin::calibrationSummary, plugin::getDataDirectory,
				PvmPerformancePanel.this::setStatus, (file, lines) -> SwingUtilities.invokeLater(() ->
					historyTab.showCalibration(file, lines)));
		}

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

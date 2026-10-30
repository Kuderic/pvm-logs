package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.FightTab;
import com.pvmlogs.PanelSnapshot.Text;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Fight tab (T-033; spec 3.1 and section 6). {@link #apply} is the only
 * way in: it rebuilds the components when the snapshot's
 * {@link FightTab#structure()} changes (a state change, a section appearing, a
 * row more) and otherwise only copies texts and colours onto the components it
 * has, so a tick never costs a {@code removeAll()} (spec 5.1). Swing's thread
 * only.
 */
final class SidePanelFightTab extends JPanel implements Scrollable
{
	/** T-046: the tab paints in software and reaches the screen as one image; see {@link SidePanelBuffer}. */
	private final SidePanelBuffer buffer = new SidePanelBuffer();

	/** For tests: the tab's cached image. */
	SidePanelBuffer buffer()
	{
		return buffer;
	}

	/** The strain probe's timers for the buffer (T-046); null in tests. */
	void setPerf(PerfProbe perf)
	{
		buffer.setPerf(perf);
		this.perf = perf;
	}

	/** Its children's repaints come through {@link #paint}, so they're buffered too. */
	@Override
	public boolean isPaintingOrigin()
	{
		return isOpaque();
	}

	@Override
	public void paint(java.awt.Graphics g)
	{
		buffer.paint(this, g, super::paint);
	}

	/** What the tab's buttons do; the panel passes them on to the plugin. */
	interface Actions
	{
		void exportAll();

		void newTrip();

		void saveDebugLog();

		void clearHistory();

		/** T-111: builds the calibration report off Swing's and the client's threads, and saves it. */
		default void calibrationReport()
		{
		}

		/** The room/raid switch, true for Whole raid. */
		void wholeRaid(boolean whole);

		/** A room picked in the whole raid's Rooms list: This room, on it. */
		void showRaidRoom(int index);
	}

	/** Every action a no-op, for tests and renders. */
	static final Actions NO_ACTIONS = new Actions()
	{
		public void exportAll()
		{
		}

		public void newTrip()
		{
		}

		public void saveDebugLog()
		{
		}

		public void clearHistory()
		{
		}

		public void wholeRaid(boolean whole)
		{
		}

		public void showRaidRoom(int index)
		{
		}
	};

	private final Actions actions;
	/** The strain probe (T-046), handed to the chart and strip when they're built; null in tests. */
	private PerfProbe perf;
	/** Where the Damage rows' icons come from (T-036). */
	private final SidePanelIcons icons;
	/** Swing's copy of the chart's series, extended a tick at a time (spec 5.1); kept across rebuilds. */
	private final SidePanelChartData chartData = new SidePanelChartData();
	/**
	 * T-099: the Damage card's switch, Boss, Adds or All ({@link FightTab#byTarget}), for the session: in memory, as
	 * the room/raid switch is, and the same in the live tab and a kill opened from History.
	 */
	private static volatile int target = SidePanelModel.DEFAULT_TARGET;
	/** T-099: the last snapshot, shown again with the other view when the switch is clicked. */
	private FightTab last;
	private final SidePanelSection.Folds folds;
	private final List<Consumer<FightTab>> updaters = new ArrayList<>();
	private String structure;
	private int builds;
	/** Whether this build's values have reserved their widest digits (T-046). */
	private boolean reserved;
	/** The status line under the empty state's tools ("Exported 12 fights"), kept across rebuilds. */
	private String status = "";
	private SidePanelText statusLine;

	SidePanelFightTab(Actions actions, SidePanelSection.Folds folds)
	{
		this(actions, folds, false);
	}

	/**
	 * {@code nested}: inside another tab (the History tab's saved kill view,
	 * T-034), which has its own border, so this one has none.
	 */
	SidePanelFightTab(Actions actions, SidePanelSection.Folds folds, boolean nested)
	{
		this(actions, folds, nested, SidePanelIcons.NONE);
	}

	SidePanelFightTab(Actions actions, SidePanelSection.Folds folds, boolean nested, SidePanelIcons icons)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.actions = actions;
		this.folds = folds;
		this.icons = icons == null ? SidePanelIcons.NONE : icons;
		setBackground(SidePanelStyle.PANEL);
		setBorder(nested ? new EmptyBorder(0, 0, 0, 0)
			: new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));
		setOpaque(!nested);
	}

	/** Shows the snapshot: new components only when its structure differs from the last one's. */
	void apply(FightTab snapshot)
	{
		last = snapshot;
		// T-099: the Damage card and chart of the view the switch is on; the switch's choice is in the structure.
		final FightTab tab = snapshot.showing(target);
		// The chart's tail goes into the copy every snapshot, whatever else changes, so no tick is skipped.
		chartData.apply(tab.chart);
		final String s = tab.structure();
		if (!s.equals(structure))
		{
			final long started = PerfProbe.start(perf);
			structure = s;
			rebuild(tab);
			reserved = false;
			if (started != 0L)
			{
				perf.stopSwing(PerfProbe.SwingSlot.REBUILD, started);
			}
		}
		else if (!reserved && tab.state == PanelSnapshot.State.LIVE && getWidth() > 0)
		{
			// The first update of a running fight after a build (T-046): every value takes room for its widest digits now,
			// in one layout pass, so none asks again until it gains a digit.
			reserved = true;
			reserveDigits(this);
		}
		for (Consumer<FightTab> u : updaters)
		{
			u.accept(tab);
		}
		// One paint pass for the whole update (T-046 follow-up): Swing paints each dirty component with no dirty ancestor
		// in a pass of its own, each ending in its own copy to the window, about 17 an update. With the tab dirty, every
		// child's repaint joins its pass.
		repaint();
	}

	private static void reserveDigits(java.awt.Container c)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelText)
			{
				((SidePanelText) k).reserveDigits();
			}
			else if (k instanceof SidePanelParts.Chip)
			{
				((SidePanelParts.Chip) k).reserveDigits();
			}
			else if (k instanceof java.awt.Container)
			{
				reserveDigits((java.awt.Container) k);
			}
		}
	}

	/**
	 * The text settings changed (T-053): the cached image goes, and the last snapshot is built again, once, in the new
	 * fonts and line heights. Nothing is rebuilt before a snapshot has come.
	 */
	void restyle(FightTab last)
	{
		buffer.drop();
		structure = null;
		if (last != null)
		{
			apply(last);
		}
	}

	/** How many times the components were built, for tests: once per structure, never per tick. */
	int builds()
	{
		return builds;
	}

	void setStatus(String text)
	{
		status = text == null ? "" : text;
		if (statusLine != null)
		{
			statusLine.set(Text.small(status, SidePanelStyle.SECONDARY));
		}
	}

	private void rebuild(FightTab tab)
	{
		builds++;
		removeAll();
		updaters.clear();
		statusLine = null;
		switch (tab.state)
		{
			case EMPTY:
				empty(tab);
				break;
			case PRE_ATTACK:
				raidSwitch(tab);
				header(tab);
				banner(tab);
				loadout(tab);
				lastFight(tab);
				if (tab.lastFight != null)
				{
					add(new SidePanelParts.Paragraph("The fight's numbers replace this once you attack.",
						SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH));
				}
				break;
			default:
				// One order for both: the live order, with the after-fight sections slotted in (spec 3.1).
				raidSwitch(tab);
				header(tab);
				banner(tab);
				unscored(tab);
				listSection(tab, "targets", "Targets", tab.targets, t -> t.targets, false);
				listSection(tab, "rooms", "Rooms", tab.rooms, t -> t.rooms, true);
				budget(tab);
				tiles(tab);
				curve(tab);
				defence(tab);
				execution(tab);
				damage(tab);
				later(tab);
				chart(tab);
				strip(tab);
				rowsSection(tab, "details", "Details", tab.details, t -> t.details);
				rowsSection(tab, "taken", "Damage taken", tab.taken, t -> t.taken);
				break;
		}
		revalidate();
		repaint();
	}

	// ---- sections ----

	private void raidSwitch(FightTab tab)
	{
		if (tab.wholeRaid == null)
		{
			return;
		}
		final JPanel row = new JPanel(new GridLayout(1, 2, 0, 0));
		row.setOpaque(false);
		final Segment room = new Segment("This room", () -> actions.wholeRaid(false));
		final Segment raid = new Segment("Whole raid", () -> actions.wholeRaid(true));
		row.add(room);
		row.add(raid);
		add(row);
		updaters.add(t ->
		{
			final boolean whole = Boolean.TRUE.equals(t.wholeRaid);
			raid.select(whole);
			room.select(!whole);
		});
	}

	/**
	 * One side of the room/raid switch (spec 3.1): selected is {@code #3C3C3C}
	 * with white text, the other no fill and Secondary. Drawn by the panel rather
	 * than a look-and-feel toggle, whose own fills would win.
	 */
	static final class Segment extends JPanel
	{
		private final SidePanelText label;
		private boolean selected;

		Segment(String text, Runnable onClick)
		{
			super(new java.awt.GridBagLayout());
			setBorder(new EmptyBorder(4, 4, 3, 4));
			label = new SidePanelText(Text.small(text, SidePanelStyle.SECONDARY), false);
			add(label);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (!selected)
					{
						onClick.run();
					}
				}
			});
			select(false);
		}

		void select(boolean on)
		{
			selected = on;
			setOpaque(on);
			setBackground(on ? SidePanelStyle.SELECTED : SidePanelStyle.PANEL);
			label.set(Text.small(label.getText(), on ? SidePanelStyle.VALUE : SidePanelStyle.SECONDARY));
			repaint();
		}

		boolean isSelected()
		{
			return selected;
		}
	}

	private void header(FightTab tab)
	{
		final JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		final JPanel left = SidePanelParts.stack(4);
		final SidePanelText name = new SidePanelText(tab.header.name, false);
		final SidePanelText sub = new SidePanelText(tab.header.sub, false);
		sub.setShort(tab.header.subShort);
		left.add(name);
		left.add(sub);
		// T-052: the target's tracked Defence while the fight runs, from the first drain; the structure says whether.
		final SidePanelText defence = tab.header.defence.isEmpty() ? null : new SidePanelText(tab.header.defence, false);
		if (defence != null)
		{
			left.add(defence);
		}
		final SidePanelText clock = new SidePanelText(tab.header.clock, true);
		// The boss's game icon before its name (T-054), only when it has one; the structure says whether.
		final SidePanelBossIcons.View icon = tab.header.icon == SidePanelBossIcons.NONE ? null
			: new SidePanelBossIcons.View(tab.header.icon, SidePanelBossIcons.HEADER_BOX);
		if (icon != null)
		{
			header.add(icon, BorderLayout.WEST);
		}
		header.add(left, BorderLayout.CENTER);
		header.add(clock, BorderLayout.EAST);
		add(header);
		updaters.add(t ->
		{
			name.set(t.header.name, t.header.suffix);
			sub.set(t.header.sub);
			sub.setShort(t.header.subShort);
			clock.set(t.header.clock);
			if (icon != null)
			{
				icon.set(t.header.icon);
			}
			if (defence != null)
			{
				defence.set(t.header.defence);
			}
		});
	}

	private void banner(FightTab tab)
	{
		if (tab.banner == null)
		{
			return;
		}
		final JPanel b = SidePanelParts.banner();
		b.add(new SidePanelText(Text.title(LedgerRows.WARNING_TEXT, SidePanelStyle.WARNING), false));
		final SidePanelParts.Paragraph text = new SidePanelParts.Paragraph(tab.banner.text, SidePanelStyle.SECONDARY,
			SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14);
		b.add(text);
		final JButton save = button("Save debug log", SidePanelStyle.VALUE);
		save.setToolTipText("Write the last few thousand lines of what the tracker saw to a file, with the loadout and "
			+ "settings that produced them");
		save.addActionListener(e -> actions.saveDebugLog());
		b.add(save);
		add(b);
		updaters.add(t ->
		{
			text.set(t.banner.text, SidePanelStyle.SECONDARY);
			b.setToolTipText(t.banner.reason);
		});
	}

	private void unscored(FightTab tab)
	{
		if (tab.unscoredNote == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(0);
		card.setBackground(SidePanelStyle.NOTE_CARD);
		final SidePanelParts.Paragraph text = new SidePanelParts.Paragraph(tab.unscoredNote, SidePanelStyle.BODY,
			SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14);
		card.add(text);
		add(card);
		updaters.add(t -> text.set(t.unscoredNote, SidePanelStyle.BODY));
	}

	private void listSection(FightTab tab, String id, String title, PanelSnapshot.ListRows rows,
		java.util.function.Function<FightTab, PanelSnapshot.ListRows> get, boolean clickable)
	{
		if (rows == null)
		{
			return;
		}
		final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);
		final List<SidePanelParts.ListRowView> views = new ArrayList<>();
		for (int i = 0; i < rows.rows.size(); i++)
		{
			final SidePanelParts.ListRowView v = new SidePanelParts.ListRowView(rows.rows.get(i));
			if (clickable)
			{
				final int index = i;
				selectable(v, () -> actions.showRaidRoom(index));
			}
			views.add(v);
			list.add(v);
		}
		final SidePanelSection section = new SidePanelSection(id, title, rows.hint, list, folds);
		add(section);
		updaters.add(t ->
		{
			final PanelSnapshot.ListRows now = get.apply(t);
			section.setHint(now.hint);
			for (int i = 0; i < views.size(); i++)
			{
				views.get(i).set(now.rows.get(i));
			}
		});
	}

	private void budget(FightTab tab)
	{
		if (tab.budget == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(6);
		final SidePanelTimeBudgetBar bar = new SidePanelTimeBudgetBar(tab.budget);
		card.add(bar);
		final List<SidePanelParts.Swatch> swatches = new ArrayList<>();
		final List<SidePanelParts.Line> lines = new ArrayList<>();
		for (PanelSnapshot.BudgetRow r : tab.budget.rows)
		{
			final JPanel row = new JPanel(new BorderLayout(1, 0));
			row.setOpaque(false);
			final SidePanelParts.Swatch sw = new SidePanelParts.Swatch(r.swatch);
			final SidePanelParts.Line line = new SidePanelParts.Line(r.label, r.value);
			line.left.cutSuffixFirst();
			row.add(sw, BorderLayout.WEST);
			row.add(line, BorderLayout.CENTER);
			swatches.add(sw);
			lines.add(line);
			card.add(row);
		}
		final List<SidePanelParts.Line> average = new ArrayList<>();
		for (int i = 0; i < tab.budget.average.size(); i++)
		{
			final SidePanelParts.Line line = new SidePanelParts.Line(tab.budget.average.get(i));
			if (i == 0)
			{
				line.setBorder(javax.swing.BorderFactory.createCompoundBorder(
					javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, SidePanelStyle.DIVIDER), new EmptyBorder(5, 0, 0, 0)));
			}
			average.add(line);
			card.add(line);
		}
		final SidePanelParts.Paragraph note = tab.budget.note == null ? null
			: new SidePanelParts.Paragraph(tab.budget.note, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
			SidePanelStyle.CONTENT_WIDTH - 14);
		if (note != null)
		{
			card.add(note);
		}
		add(new SidePanelSection("budget", "Where the time went", Text.NONE, card, folds));
		updaters.add(t ->
		{
			bar.set(t.budget);
			for (int i = 0; i < lines.size(); i++)
			{
				final PanelSnapshot.BudgetRow r = t.budget.rows.get(i);
				swatches.get(i).set(r.swatch, r.swatch == PanelSnapshot.Fill.LUCK_FASTER ? t.budget.luckSavedColour : null);
				lines.get(i).set(r.label, r.hint.isEmpty() ? null : new Text(" " + r.hint.text, r.hint.colour,
					r.hint.type), r.value);
			}
			for (int i = 0; i < average.size(); i++)
			{
				average.get(i).set(t.budget.average.get(i));
			}
			if (note != null)
			{
				note.set(t.budget.note, SidePanelStyle.MUTED);
			}
		});
	}

	private void tiles(FightTab tab)
	{
		if (tab.tiles == null)
		{
			return;
		}
		final JPanel grid = new JPanel(new GridLayout(2, 2, SidePanelStyle.TILE_GAP, SidePanelStyle.TILE_GAP));
		grid.setOpaque(false);
		final List<SidePanelStatTile> views = new ArrayList<>();
		for (PanelSnapshot.Tile t : tab.tiles)
		{
			final SidePanelStatTile v = new SidePanelStatTile(t);
			views.add(v);
			grid.add(v);
		}
		add(grid);
		updaters.add(t ->
		{
			for (int i = 0; i < views.size(); i++)
			{
				views.get(i).set(t.tiles.get(i));
			}
		});
	}

	private void curve(FightTab tab)
	{
		if (tab.noCurve != null)
		{
			final JPanel card = SidePanelParts.card(0);
			final SidePanelParts.Paragraph text = new SidePanelParts.Paragraph(tab.noCurve, SidePanelStyle.MUTED,
				SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14);
			card.add(text);
			final SidePanelSection section = new SidePanelSection("curve", "Luck", tab.noCurveHint, card, folds);
			add(section);
			updaters.add(t -> section.setHint(t.noCurveHint));
			return;
		}
		if (tab.curve == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(5);
		final SidePanelLuckCurve curve = new SidePanelLuckCurve(tab.curve.z);
		card.add(curve);
		final JPanel legend = new JPanel(new LegendCells());
		legend.setOpaque(false);
		legend.add(new SidePanelText(Text.small("bottom 25%", SidePanelStyle.COST), false));
		final SidePanelText middle = new SidePanelText(Text.small("middle half", SidePanelStyle.SECONDARY), false);
		legend.add(centred(middle));
		legend.add(new SidePanelText(Text.small("top 25%", SidePanelStyle.BETTER), true));
		card.add(legend);
		final SidePanelParts.Paragraph caption = new SidePanelParts.Paragraph(tab.curve.caption, SidePanelStyle.SECONDARY,
			SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14);
		card.add(caption);
		final SidePanelSection section = new SidePanelSection("curve", "Luck", tab.curve.headerValue, card, folds);
		add(section);
		updaters.add(t ->
		{
			curve.set(t.curve.z);
			caption.set(t.curve.caption, SidePanelStyle.SECONDARY);
			section.setHint(t.curve.headerValue);
		});
	}

	/**
	 * The curve legend's three words (T-053): three equal cells, as a {@code GridLayout(1, 3)} lays them, except that a
	 * side word wider than its cell (larger text, the system font) keeps its whole width, and the middle one moves clear
	 * of it. With the RuneScape fonts every word fits, so it lays out exactly as the grid did.
	 */
	static final class LegendCells implements java.awt.LayoutManager
	{
		public void addLayoutComponent(String name, java.awt.Component c)
		{
		}

		public void removeLayoutComponent(java.awt.Component c)
		{
		}

		public java.awt.Dimension preferredLayoutSize(java.awt.Container parent)
		{
			int w = 0;
			int h = 0;
			for (java.awt.Component c : parent.getComponents())
			{
				final java.awt.Dimension d = c.getPreferredSize();
				w = Math.max(w, d.width);
				h = Math.max(h, d.height);
			}
			final java.awt.Insets in = parent.getInsets();
			return new java.awt.Dimension(w * 3 + in.left + in.right, h + in.top + in.bottom);
		}

		public java.awt.Dimension minimumLayoutSize(java.awt.Container parent)
		{
			return preferredLayoutSize(parent);
		}

		public void layoutContainer(java.awt.Container parent)
		{
			if (parent.getComponentCount() != 3)
			{
				return;
			}
			final java.awt.Insets in = parent.getInsets();
			final int w = parent.getWidth() - in.left - in.right;
			final int h = parent.getHeight() - in.top - in.bottom;
			// As GridLayout: equal cells, the odd pixels split either side.
			final int cell = w / 3;
			final int x0 = in.left + (w - cell * 3) / 2;
			final int end = x0 + cell * 3;
			final java.awt.Component left = parent.getComponent(0);
			final java.awt.Component middle = parent.getComponent(1);
			final java.awt.Component right = parent.getComponent(2);
			final int lw = Math.max(cell, left.getPreferredSize().width);
			final int rw = Math.max(cell, right.getPreferredSize().width);
			left.setBounds(x0, in.top, lw, h);
			right.setBounds(end - rw, in.top, rw, h);
			// The middle word's own width, centred in the middle cell, moved right of the left word or left of the right one.
			final int mw = middle.getPreferredSize().width;
			int mx = x0 + cell;
			final int textX = mx + (cell - mw) / 2;
			final int leftEnd = x0 + left.getPreferredSize().width + 4;
			final int rightStart = end - right.getPreferredSize().width - 4;
			if (textX < leftEnd)
			{
				mx += leftEnd - textX;
			}
			else if (textX + mw > rightStart)
			{
				mx -= textX + mw - rightStart;
			}
			middle.setBounds(mx, in.top, cell, h);
		}
	}

	/** A text centred in its cell: the curve legend's middle word. */
	private static JComponent centred(SidePanelText text)
	{
		final JPanel p = new JPanel(new java.awt.GridBagLayout());
		p.setOpaque(false);
		p.add(text);
		return p;
	}

	private void execution(FightTab tab)
	{
		if (tab.execution == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(7);
		final List<SidePanelPartBar> bars = new ArrayList<>();
		for (PanelSnapshot.Part p : tab.execution.parts)
		{
			final SidePanelPartBar bar = new SidePanelPartBar(p);
			bars.add(bar);
			card.add(bar);
		}
		final SidePanelSection section = new SidePanelSection("execution", "Execution", tab.execution.hint, card, folds);
		add(section);
		updaters.add(t ->
		{
			section.setHint(t.execution.hint);
			for (int i = 0; i < bars.size(); i++)
			{
				bars.get(i).set(t.execution.parts.get(i));
			}
		});
	}

	/** Damage over time (T-036): the chart in a card, its two ends' times under it, and a legend. */
	private void chart(FightTab tab)
	{
		if (tab.chart == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(5);
		final SidePanelDamageChart chart = new SidePanelDamageChart(chartData);
		chart.setPerf(perf);
		card.add(chart);
		final SidePanelParts.Line ends = new SidePanelParts.Line(Text.small("0:00", SidePanelStyle.MUTED),
			Text.small(tab.chart.end, SidePanelStyle.MUTED));
		card.add(ends);
		final SidePanelParts.Legend legend = new SidePanelParts.Legend(SidePanelStyle.CONTENT_WIDTH - 14);
		card.add(legend);
		// T-099: the switch's view, when it isn't All.
		final String on = tab.byTarget == null || SidePanelModel.ALL.equals(tab.byTarget.get(tab.target).label) ? ""
			: " · " + tab.byTarget.get(tab.target).label.toLowerCase(java.util.Locale.ENGLISH);
		add(new SidePanelSection("chart", "Damage over time", Text.small("weapon" + on, SidePanelStyle.SECONDARY), card,
			folds));
		updaters.add(t ->
		{
			ends.set(new PanelSnapshot.Row(Text.small("0:00", SidePanelStyle.MUTED), Text.small(t.chart.end,
				SidePanelStyle.MUTED)));
			final List<SidePanelParts.Legend.Item> items = new ArrayList<>(3);
			items.add(new SidePanelParts.Legend.Item(t.chart.actual, false, t.chart.youLegend, SidePanelStyle.SECONDARY));
			if (t.chart.expectedShown)
			{
				items.add(new SidePanelParts.Legend.Item(SidePanelStyle.SECONDARY, false, t.chart.expectedLegend,
					SidePanelStyle.SECONDARY));
				if (t.chart.rangeShown)
				{
					items.add(new SidePanelParts.Legend.Item(new Color(0x4A4A4A), false, "typical range",
						SidePanelStyle.SECONDARY));
				}
			}
			else
			{
				items.add(new SidePanelParts.Legend.Item(null, false, "expected hidden", SidePanelStyle.MUTED));
			}
			legend.set(items);
			chart.update();
		});
	}

	/** Ticks (T-036): the tick strip in a card and its legend with counts. */
	private void strip(FightTab tab)
	{
		if (tab.chart == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(5);
		final SidePanelTickStrip strip = new SidePanelTickStrip(chartData);
		strip.setPerf(perf);
		card.add(strip);
		final SidePanelParts.Legend legend = new SidePanelParts.Legend(SidePanelStyle.CONTENT_WIDTH - 14);
		card.add(legend);
		add(new SidePanelSection("ticks", "Ticks", Text.small("36 s a row", SidePanelStyle.SECONDARY), card, folds));
		final int[] rows = {SidePanelTickStrip.rows(chartData.length())};
		updaters.add(t ->
		{
			final List<SidePanelParts.Legend.Item> items = new ArrayList<>(4);
			items.add(new SidePanelParts.Legend.Item(t.chart.attackColour, false, "attack", SidePanelStyle.SECONDARY));
			items.add(new SidePanelParts.Legend.Item(SidePanelStyle.towardWhite(t.chart.attackColour, 0.7), false, "spec",
				SidePanelStyle.SECONDARY));
			if (t.chart.lost >= 0)
			{
				items.add(new SidePanelParts.Legend.Item(SidePanelStyle.COST, false, "lost " + t.chart.lost,
					SidePanelStyle.SECONDARY));
			}
			if (t.chart.eating >= 0)
			{
				items.add(new SidePanelParts.Legend.Item(SidePanelStyle.EATING, false, "eating " + t.chart.eating,
					SidePanelStyle.SECONDARY));
			}
			// T-081: only where a boss's mechanic forced some, so nothing changes for the rest.
			if (t.chart.mechanics > 0)
			{
				items.add(new SidePanelParts.Legend.Item(SidePanelStyle.MECHANICS, false, SidePanelModel.MECHANICS + " "
					+ t.chart.mechanics, SidePanelStyle.SECONDARY));
			}
			legend.set(items);
			// A new row of ticks every 36 s: the card grows.
			final int now = SidePanelTickStrip.rows(chartData.length());
			if (now != rows[0])
			{
				rows[0] = now;
				SidePanelParts.relayout(strip);
			}
			strip.update();
		});
	}

	/** A saved kill's dashed card in the damage rows' place (spec 3.2), with the canvas's title. */
	private void later(FightTab tab)
	{
		if (tab.later == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(0);
		card.setBackground(new Color(0x232323));
		card.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			javax.swing.BorderFactory.createDashedBorder(SidePanelStyle.SELECTED, 1f, 3f, 2f, false),
			new EmptyBorder(SidePanelStyle.CARD_PAD_V - 1, SidePanelStyle.CARD_PAD_H - 1, SidePanelStyle.CARD_PAD_V - 1,
				SidePanelStyle.CARD_PAD_H - 1)));
		final SidePanelParts.Paragraph text = new SidePanelParts.Paragraph(tab.later, SidePanelStyle.SECONDARY,
			SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14);
		card.add(text);
		add(new SidePanelSection("later", "Damage, charts, ticks", Text.NONE, card, folds));
		updaters.add(t -> text.set(t.later, SidePanelStyle.SECONDARY));
	}

	private void damage(FightTab tab)
	{
		if (tab.damage == null && tab.byTarget == null)
		{
			return;
		}
		final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);
		final List<SidePanelParts.SourceRowView> rows = new ArrayList<>();
		// T-085: the weapon rows' sub-lines in one form, the longest that fits them all.
		final SidePanelText.FormGroup forms = new SidePanelText.FormGroup();
		for (PanelSnapshot.Source s : tab.damage == null ? java.util.Collections.<PanelSnapshot.Source>emptyList()
			: tab.damage.rows)
		{
			final SidePanelParts.SourceRowView v = new SidePanelParts.SourceRowView(s, icons, forms);
			rows.add(v);
			list.add(v);
		}
		final String empty = tab.byTarget == null ? null : tab.byTarget.get(tab.target).empty;
		if (empty != null)
		{
			// T-105: a side with nothing scored (the Sire's respiratory systems, Zulrah's snakelings) says so.
			final JPanel card = SidePanelParts.card(0);
			card.add(new SidePanelParts.Paragraph(empty, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
				SidePanelStyle.CONTENT_WIDTH - 14));
			list.add(card);
		}
		// With the switch the text hint is empty from the start, so it reserves no width beside it.
		final SidePanelSection section = new SidePanelSection("damage", "Damage", tab.damage == null
			|| tab.byTarget != null ? Text.NONE : tab.damage.hint, list, folds);
		// T-105: the switch is the header's hint, boss · adds · all; its tooltip holds the damage, time and the note.
		final TargetSwitch words = tab.byTarget == null ? null : new TargetSwitch(tab, this::pick);
		if (words != null)
		{
			section.setHintComponent(words);
		}
		add(section);
		updaters.add(t ->
		{
			if (words != null)
			{
				words.set(t.target, t.byTarget.get(t.target).note);
			}
			else
			{
				section.setHint(t.damage.hint);
			}
			for (int i = 0; i < rows.size(); i++)
			{
				rows.get(i).set(t.damage.rows.get(i));
			}
		});
	}

	/**
	 * T-105: the Damage card's switch in its header, {@code boss · adds · all} in small lower-case text like the other
	 * hints: the picked word bright, the others and the dots muted, no fill. Hovering a word that isn't picked brings it
	 * to the secondary grey, with a hand cursor; a click on it picks that view. A hover is a colour change, painted in
	 * place: the words keep their widths, so it never asks for layout (T-046).
	 */
	static final class TargetSwitch extends JPanel
	{
		static final String DOT = " · ";
		private final List<SidePanelText> words = new ArrayList<>();
		private final List<SidePanelText> dots = new ArrayList<>();
		private int picked;
		private int hover = -1;

		TargetSwitch(FightTab tab, java.util.function.IntConsumer onPick)
		{
			setOpaque(false);
			setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS));
			picked = tab.target;
			// Clicks between the words are the switch's, not the header's fold.
			final MouseAdapter absorb = new MouseAdapter()
			{
			};
			addMouseListener(absorb);
			for (int i = 0; i < tab.byTarget.size(); i++)
			{
				if (i > 0)
				{
					final SidePanelText dot = new SidePanelText(Text.small(DOT, SidePanelStyle.MUTED), false);
					dot.addMouseListener(absorb);
					dots.add(dot);
					add(dot);
				}
				final int view = i;
				final SidePanelText word = new SidePanelText(Text.small(tab.byTarget.get(i).label.toLowerCase(
					java.util.Locale.ENGLISH), SidePanelStyle.MUTED), false);
				word.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseEntered(MouseEvent e)
					{
						hover = view;
						paintWords();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						if (hover == view)
						{
							hover = -1;
							paintWords();
						}
					}

					@Override
					public void mouseClicked(MouseEvent e)
					{
						if (view != picked)
						{
							onPick.accept(view);
						}
					}
				});
				words.add(word);
				add(word);
			}
			set(tab.target, tab.byTarget.get(tab.target).note);
		}

		void set(int picked, String tooltip)
		{
			this.picked = picked;
			setToolTipText(tooltip);
			for (SidePanelText w : words)
			{
				w.setToolTipText(tooltip);
			}
			for (SidePanelText d : dots)
			{
				d.setToolTipText(tooltip);
			}
			paintWords();
		}

		private void paintWords()
		{
			for (int i = 0; i < words.size(); i++)
			{
				final SidePanelText w = words.get(i);
				final Color c = i == picked ? SidePanelStyle.VALUE : i == hover ? SidePanelStyle.SECONDARY
					: SidePanelStyle.MUTED;
				w.set(Text.small(w.getText(), c));
				w.setCursor(i == picked ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
		}

		/** The words as shown, for tests. */
		List<SidePanelText> words()
		{
			return words;
		}

		/** The word hovered, -1 for none: for tests and renders. */
		void hover(int view)
		{
			hover = view;
			paintWords();
		}
	}

	/** T-099: the switch clicked: every tab shows that view from now on, this one at once. */
	void pick(int view)
	{
		target = view;
		if (last != null)
		{
			apply(last);
		}
	}

	/** T-099: the switch's view, for tests: {@link SidePanelModel#DEFAULT_TARGET} until one is clicked. */
	static int target()
	{
		return target;
	}

	/** T-099: back to the default, for tests. */
	static void resetTarget()
	{
		useTarget(SidePanelModel.DEFAULT_TARGET);
	}

	/** T-099: the switch on this view before any tab is shown, for the renders. */
	static void useTarget(int view)
	{
		target = view;
	}

	/**
	 * T-052: the Defence card after a fight with tracked drains: its rows (the path, the floor, the specials thrown and
	 * landed, the party's drains), then what the drains added and the specials' share of luck in words.
	 */
	/**
	 * The Defence card (T-052; T-076, Eric's pick of three layouts): {@code Drained 76 (30%)} over a bar from the boss's
	 * base Defence to its level now, the floor ticked, then the labelled rows.
	 */
	private void defence(FightTab tab)
	{
		if (tab.defence == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(6);
		final SidePanelDrainBar bar = tab.defence.drop == null ? null : new SidePanelDrainBar(tab.defence.drop);
		if (bar != null)
		{
			card.add(bar);
		}
		final SidePanelParts.RowsCard rows = flat(defenceRows(tab.defence));
		card.add(rows);
		final SidePanelSection section = new SidePanelSection("defence", "Defence", tab.defence.hint, card, folds);
		add(section);
		updaters.add(t ->
		{
			section.setHint(t.defence.hint);
			if (bar != null)
			{
				bar.set(t.defence.drop);
			}
			rows.set(defenceRows(t.defence));
		});
	}

	/** The card's rows: the facts, then its two figures. */
	static List<PanelSnapshot.Row> defenceRows(PanelSnapshot.Defence d)
	{
		final List<PanelSnapshot.Row> rows = new ArrayList<>(d.rows);
		if (d.worth != null)
		{
			rows.add(d.worth.row());
		}
		if (d.specLuck != null)
		{
			rows.add(d.specLuck.row());
		}
		return rows;
	}

	private void rowsSection(FightTab tab, String id, String title, PanelSnapshot.Rows rows,
		java.util.function.Function<FightTab, PanelSnapshot.Rows> get)
	{
		if (rows == null)
		{
			return;
		}
		final SidePanelParts.RowsCard card = new SidePanelParts.RowsCard(rows.rows, 5);
		final SidePanelSection section = new SidePanelSection(id, title, rows.hint, card, folds);
		add(section);
		updaters.add(t ->
		{
			final PanelSnapshot.Rows now = get.apply(t);
			section.setHint(now.hint);
			card.set(now.rows);
		});
	}

	private void loadout(FightTab tab)
	{
		if (tab.loadout == null)
		{
			return;
		}
		final JPanel card = SidePanelParts.card(6);
		final SidePanelText name = new SidePanelText(tab.loadout.name, false);
		card.add(name);
		final SidePanelParts.RowsCard rows = flat(tab.loadout.rows);
		card.add(rows);
		final SidePanelText spec;
		final SidePanelParts.RowsCard specRows;
		if (tab.loadout.specName != null)
		{
			spec = new SidePanelText(tab.loadout.specName, false);
			final JPanel specHead = SidePanelParts.stack(0);
			specHead.setBorder(javax.swing.BorderFactory.createCompoundBorder(
				javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, SidePanelStyle.DIVIDER), new EmptyBorder(6, 0, 0, 0)));
			specHead.add(spec);
			card.add(specHead);
			specRows = flat(tab.loadout.specRows);
			card.add(specRows);
		}
		else
		{
			spec = null;
			specRows = null;
		}
		add(new SidePanelSection("loadout", "Loadout", Text.NONE, card, folds));
		updaters.add(t ->
		{
			name.set(t.loadout.name);
			rows.set(t.loadout.rows);
			if (spec != null)
			{
				spec.set(t.loadout.specName);
				specRows.set(t.loadout.specRows);
			}
		});
	}

	/** Rows with no card of their own, inside another card. */
	private static SidePanelParts.RowsCard flat(List<PanelSnapshot.Row> rows)
	{
		final SidePanelParts.RowsCard c = new SidePanelParts.RowsCard(rows, 5);
		c.setOpaque(false);
		c.setBorder(new EmptyBorder(0, 0, 0, 0));
		return c;
	}

	private void lastFight(FightTab tab)
	{
		if (tab.lastFight == null)
		{
			return;
		}
		final SidePanelParts.ListRowView row = new SidePanelParts.ListRowView(tab.lastFight);
		add(new SidePanelSection("lastFight", "Last fight", Text.NONE, row, folds));
		updaters.add(t -> row.set(t.lastFight));
	}

	private void empty(FightTab tab)
	{
		final JPanel first = SidePanelParts.card(6);
		first.add(new SidePanelText(Text.title("No fight yet", SidePanelStyle.VALUE), false));
		first.add(new SidePanelParts.Paragraph(SidePanelModel.NO_FIGHT_TEXT, SidePanelStyle.SECONDARY,
			SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14));
		add(first);

		final JPanel history = SidePanelParts.card(0);
		final SidePanelParts.Paragraph historyText = new SidePanelParts.Paragraph(tab.empty.history,
			SidePanelStyle.SECONDARY, SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14);
		history.add(historyText);
		add(new SidePanelSection("emptyHistory", "History", Text.NONE, history, folds));

		final JPanel tools = SidePanelParts.stack(4);
		final ToolButtons buttons = new ToolButtons(actions, this);
		tools.add(buttons);
		statusLine = new SidePanelText(Text.small(status, SidePanelStyle.SECONDARY), false);
		tools.add(statusLine);
		add(new SidePanelSection("tools", "Tools", Text.NONE, tools, folds));
		updaters.add(t ->
		{
			historyText.set(t.empty.history, SidePanelStyle.SECONDARY);
			buttons.setHistory(t.empty.hasHistory);
		});
	}

	/** The Tools grid (spec 3.2): Export CSV, New trip, Save log, Clear…, the last asking first. */
	static final class ToolButtons extends JPanel
	{
		private final JButton export;
		private final JButton clear;

		/** The question Clear… asks before it clears; RuneLite's yes/no dialog in the client. */
		static final String CLEAR_QUESTION = "Clear all saved kills? This can't be undone.";

		ToolButtons(Actions actions, Component parent)
		{
			this(actions, () -> JOptionPane.showConfirmDialog(parent, CLEAR_QUESTION, PvmPerformanceConfig.DISPLAY_NAME,
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
		}

		/** {@code confirm} asks the question and says whether the answer was yes (spec 3.2: Clear asks first). */
		ToolButtons(Actions actions, java.util.function.BooleanSupplier confirm)
		{
			super(new GridLayout(2, 2, SidePanelStyle.TILE_GAP, SidePanelStyle.TILE_GAP));
			setOpaque(false);
			export = button("Export CSV", SidePanelStyle.VALUE);
			export.setToolTipText("Export every saved fight to a CSV file");
			export.addActionListener(e -> actions.exportAll());
			final JButton trip = button("New trip", SidePanelStyle.VALUE);
			trip.setToolTipText("Start the trip totals over. Saved fights are untouched");
			trip.addActionListener(e -> actions.newTrip());
			final JButton log = button("Save log", SidePanelStyle.VALUE);
			log.setToolTipText("Write the last few thousand lines of what the tracker saw to a file, with the loadout and "
				+ "settings that produced them. Press it when a figure looks wrong");
			log.addActionListener(e -> actions.saveDebugLog());
			clear = button("Clear…", SidePanelStyle.DEAD);
			clear.setToolTipText("Discard every saved fight");
			clear.addActionListener(e ->
			{
				if (confirm.getAsBoolean())
				{
					actions.clearHistory();
				}
			});
			add(export);
			add(trip);
			add(log);
			add(clear);
		}

		/** Presses Clear…, as a click would: for tests. */
		void pressClear()
		{
			clear.doClick();
		}

		/** Export and Clear need something saved (spec 3.2). */
		void setHistory(boolean any)
		{
			export.setEnabled(any);
			clear.setEnabled(any);
		}
	}

	static JButton button(String text, Color colour)
	{
		final JButton b = new JButton(text);
		b.setFont(SidePanelStyle.Type.SMALL.font());
		b.setForeground(colour);
		b.setFocusPainted(false);
		b.setMargin(new Insets(4, 4, 3, 4));
		return b;
	}

	/** Hover and click on a list row; the row's own fill is the card's. */
	private static void selectable(JPanel row, Runnable onClick)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(SidePanelStyle.SELECTED);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(SidePanelStyle.CARD);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}
		});
	}

	// ---- Scrollable: the tab is as wide as the scroll pane's view, never wider ----

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return orientation == SwingConstants.VERTICAL ? visibleRect.height - 16 : visibleRect.width;
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}
}

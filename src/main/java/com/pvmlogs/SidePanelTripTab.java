package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import com.pvmlogs.PanelSnapshot.TripTab;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Trip tab (T-034; spec 3.3, state 9): the trip's clock, tiles and
 * execution over {@link LedgerRows#of(SessionTotals)}, every room since the
 * trip started, damage taken, other damage and New trip. Fed by the snapshot
 * pipeline like the Fight tab: components are built when the snapshot's
 * structure changes, and only updated otherwise.
 */
final class SidePanelTripTab extends JPanel implements Scrollable
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

	private final SidePanelFightTab.Actions actions;
	private final SidePanelSection.Folds folds;
	private final List<Consumer<TripTab>> updaters = new ArrayList<>();
	private String structure;
	private int builds;

	SidePanelTripTab(SidePanelFightTab.Actions actions, SidePanelSection.Folds folds)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.actions = actions;
		this.folds = folds;
		setBackground(SidePanelStyle.PANEL);
		setBorder(new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));
	}

	void apply(TripTab tab)
	{
		if (tab == null)
		{
			return;
		}
		final String s = tab.structure();
		if (!s.equals(structure))
		{
			structure = s;
			rebuild(tab);
		}
		for (Consumer<TripTab> u : updaters)
		{
			u.accept(tab);
		}
		// One paint pass for the whole update (T-046 follow-up), as the Fight tab does.
		repaint();
	}

	/** The text settings changed (T-053): as the Fight tab's {@code restyle}. */
	void restyle(TripTab last)
	{
		buffer.drop();
		structure = null;
		apply(last);
	}

	int builds()
	{
		return builds;
	}

	private void rebuild(TripTab tab)
	{
		builds++;
		removeAll();
		updaters.clear();
		header();
		if (tab.tiles != null)
		{
			tiles(tab);
		}
		if (tab.execution != null)
		{
			execution(tab);
		}
		rooms(tab);
		if (tab.taken != null)
		{
			rows("tripTaken", "Damage taken", tab.taken, t -> t.taken);
		}
		if (tab.other != null)
		{
			rows("tripOther", "Other damage", tab.other, t -> t.other);
		}
		if (tab.drains != null)
		{
			// T-052: what the trip's defence drains added, and the specials' share of luck.
			rows("tripDrains", "Defence drains", tab.drains, t -> t.drains);
		}
		final JButton newTrip = SidePanelFightTab.button("New trip", SidePanelStyle.VALUE);
		newTrip.setToolTipText("Start the trip totals over. Saved fights are untouched");
		newTrip.addActionListener(e -> actions.newTrip());
		add(newTrip);
		add(new SidePanelParts.Paragraph("Starts the trip's totals over. Saved kills stay in History.",
			SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH));
		revalidate();
		repaint();
	}

	private void header()
	{
		final JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		final JPanel left = SidePanelParts.stack(4);
		final SidePanelText name = new SidePanelText(Text.title("Trip", SidePanelStyle.VALUE), false);
		final SidePanelText sub = new SidePanelText(Text.small("", SidePanelStyle.SECONDARY), false);
		left.add(name);
		left.add(sub);
		final SidePanelText clock = new SidePanelText(SidePanelStyle.Type.CLOCK, true);
		header.add(left, BorderLayout.CENTER);
		header.add(clock, BorderLayout.EAST);
		add(header);
		updaters.add(t ->
		{
			name.set(t.header.name);
			sub.set(t.header.sub);
			sub.setShort(t.header.subShort);
			clock.set(t.header.clock);
		});
	}

	private void tiles(TripTab tab)
	{
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

	private void execution(TripTab tab)
	{
		final JPanel card = SidePanelParts.card(7);
		final List<SidePanelPartBar> bars = new ArrayList<>();
		for (PanelSnapshot.Part p : tab.execution.parts)
		{
			final SidePanelPartBar bar = new SidePanelPartBar(p);
			bars.add(bar);
			card.add(bar);
		}
		// T-095: over the boss kills alone when the trip has runs, as its tile says.
		final SidePanelSection section = new SidePanelSection("tripExecution", tab.bossesOnly()
			? SidePanelModel.EXECUTION_NO_RUNS : "Execution", tab.execution.hint, card, folds);
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

	/** "This trip": a row per room, rebuilt only when their number changes (the structure says so). */
	private void rooms(TripTab tab)
	{
		final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);
		final List<RowView> views = new ArrayList<>();
		if (tab.rooms.isEmpty())
		{
			final JPanel card = SidePanelParts.card(0);
			card.add(new SidePanelText(Text.small(SidePanelModel.NO_TRIP_FIGHTS, SidePanelStyle.SECONDARY), false));
			list.add(card);
		}
		for (int i = 0; i < tab.rooms.size(); i++)
		{
			final RowView v = new RowView();
			views.add(v);
			list.add(v);
		}
		final SidePanelSection section = new SidePanelSection("tripRooms", "This trip", tab.roomsHint, list, folds);
		add(section);
		updaters.add(t ->
		{
			section.setHint(t.roomsHint);
			// The clocks' and chips' columns as wide as their widest, so they line up and the names keep the rest.
			int clockWidth = 0;
			int chipWidth = 0;
			for (int i = 0; i < views.size(); i++)
			{
				views.get(i).set(t.rooms.get(i));
				clockWidth = Math.max(clockWidth, views.get(i).clockWidth());
				chipWidth = Math.max(chipWidth, views.get(i).chipWidth());
			}
			for (RowView v : views)
			{
				v.setColumns(clockWidth, chipWidth);
			}
		});
	}

	/** A trip row: name (grey with "(ended)" when not a kill), clock, chip. */
	private static final class RowView extends JPanel
	{
		private final SidePanelText name = new SidePanelText(SidePanelStyle.Type.BODY, false);
		private final SidePanelText clock = new SidePanelText(SidePanelStyle.Type.TITLE, true);
		private final SidePanelParts.Chip chip = new SidePanelParts.Chip();
		private final JPanel chipBox = new JPanel(new BorderLayout());
		/** The boss's icon (T-054); its box is kept, empty, for a room without one, so the names line up. */
		private final SidePanelBossIcons.View icon = new SidePanelBossIcons.View(SidePanelBossIcons.NONE,
			SidePanelBossIcons.ROW_BOX);

		RowView()
		{
			super(new BorderLayout(4, 0));
			setBackground(SidePanelStyle.CARD);
			setBorder(new EmptyBorder(5, SidePanelStyle.CARD_PAD_H, 5, 6));
			add(icon, BorderLayout.WEST);
			add(name, BorderLayout.CENTER);
			final JPanel right = new JPanel(new BorderLayout(4, 0));
			right.setOpaque(false);
			right.add(clock, BorderLayout.CENTER);
			chipBox.setOpaque(false);
			chipBox.add(chip, BorderLayout.EAST);
			right.add(chipBox, BorderLayout.EAST);
			add(right, BorderLayout.EAST);
		}

		void set(PanelSnapshot.TripRow r)
		{
			final java.awt.Color c = r.ended ? SidePanelStyle.SECONDARY : SidePanelStyle.VALUE;
			// T-095: a run's kills follow its name as the suffix does, so a short name keeps them.
			name.set(Text.body(r.name, c), r.ended ? Text.small(r.died ? SidePanelModel.DIED : SidePanelModel.ENDED,
				SidePanelStyle.SECONDARY)
				: r.suffix.isEmpty() ? null : Text.body(r.suffix, c));
			clock.set(Text.title(r.clock, c));
			chip.set(r.chip, r.chipFill);
			icon.set(r.icon);
		}

		int chipWidth()
		{
			return chip.getPreferredSize().width;
		}

		int clockWidth()
		{
			return SidePanelStyle.metrics(clock, clock.getFont()).stringWidth(clock.getText());
		}

		void setColumns(int clockWidth, int chipWidth)
		{
			// Columns only widen (T-046): a running room's clock ticking over needs no layout until it outgrows them.
			if (chipWidth > chipBox.getPreferredSize().width || clockWidth > clock.getPreferredSize().width)
			{
				chipBox.setPreferredSize(new Dimension(Math.max(chipWidth, chipBox.getPreferredSize().width),
					SidePanelStyle.Type.SMALL.lineHeight() + 2));
				clock.setPreferredSize(new Dimension(Math.max(clockWidth, clock.getPreferredSize().width),
					SidePanelStyle.Type.TITLE.lineHeight()));
				SidePanelParts.relayout(this);
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(SidePanelStyle.CONTENT_WIDTH, SidePanelKillRow.rowHeight());
		}
	}

	private void rows(String id, String title, PanelSnapshot.Rows rows,
		java.util.function.Function<TripTab, PanelSnapshot.Rows> get)
	{
		// T-112: one card, or a small card a group (Grouping.CARDS).
		final javax.swing.JComponent card = SidePanelParts.rowsView(rows.rows, 5);
		final SidePanelSection section = new SidePanelSection(id, title, rows.hint, card, folds);
		add(section);
		updaters.add(t ->
		{
			final PanelSnapshot.Rows now = get.apply(t);
			section.setHint(now.hint);
			((SidePanelParts.RowsView) card).set(now.rows);
		});
	}

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

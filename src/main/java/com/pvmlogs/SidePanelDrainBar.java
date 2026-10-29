package com.pvmlogs;

import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * T-076: the Defence card's drop as an execution part is drawn ({@link SidePanelPartBar}): {@code Drained 76 (30%)}
 * above a 5 px bar of the boss's base Defence, what's left in a neutral grey, what the room drained in Better, and a
 * tick at the floor where the boss has one.
 */
final class SidePanelDrainBar extends JPanel
{
	private final SidePanelParts.Line line;
	private final Bar bar = new Bar();

	SidePanelDrainBar(PanelSnapshot.Drop d)
	{
		super(new SidePanelStack(3));
		setOpaque(false);
		line = new SidePanelParts.Line(d.label, d.value);
		add(line);
		add(bar);
		set(d);
	}

	void set(PanelSnapshot.Drop d)
	{
		line.set(new PanelSnapshot.Row(d.label, d.value));
		bar.set(d.start, d.end, d.base, d.floor);
	}

	/** The bar, with room above and below it for the floor's tick. */
	static final class Bar extends JComponent
	{
		static final int HEIGHT = SidePanelPartBar.Bar.HEIGHT;
		/** How far the floor's tick stands out of the bar, above and below. */
		static final int TICK = 2;
		private int start = -1;
		private int end = -1;
		private int base = -1;
		private int floor;

		void set(int newStart, int newEnd, int newBase, int newFloor)
		{
			if (newStart != start || newEnd != end || newBase != base || newFloor != floor)
			{
				start = newStart;
				end = newEnd;
				base = newBase;
				floor = newFloor;
				repaint();
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(SidePanelStyle.CONTENT_WIDTH, HEIGHT + 2 * TICK);
		}

		private int x(int level, int w)
		{
			return (int) Math.round(w * Math.max(0, Math.min(base, level)) / (double) base);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final int w = getWidth();
			g.setColor(SidePanelStyle.TRACK);
			g.fillRect(0, TICK, w, HEIGHT);
			if (base <= 0 || start < 0 || end < 0)
			{
				return;
			}
			final int e = x(end, w);
			final int s = x(start, w);
			g.setColor(SidePanelStyle.UNSCORED);
			g.fillRect(0, TICK, e, HEIGHT);
			g.setColor(SidePanelStyle.BETTER);
			g.fillRect(e, TICK, Math.max(0, s - e), HEIGHT);
			if (floor > 0 && floor < base)
			{
				final int f = Math.min(w - 1, x(floor, w));
				g.setColor(SidePanelStyle.VALUE);
				g.fillRect(f, 0, 1, HEIGHT + 2 * TICK);
			}
		}
	}
}

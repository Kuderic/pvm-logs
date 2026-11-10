package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * An execution part (spec 3.1): its label and {@code NN% (-N s)} above a 5 px
 * bar, kept in the Kept fill and lost in Cost when it's costing, else Minor
 * cost. An unknown share draws an empty track.
 */
final class SidePanelPartBar extends JPanel
{
	private final SidePanelParts.Line line;
	private final Bar bar = new Bar();
	/** T-196: what the part cost, under the bar, in plain words; null for a part built without one. */
	private final SidePanelText note;

	SidePanelPartBar(PanelSnapshot.Part p)
	{
		super(new SidePanelStack(3));
		setOpaque(false);
		line = new SidePanelParts.Line(p.label, p.value);
		add(line);
		add(bar);
		note = p.note == null ? null : new SidePanelText(p.note, false);
		if (note != null)
		{
			add(note);
		}
		set(p);
	}

	void set(PanelSnapshot.Part p)
	{
		line.set(new PanelSnapshot.Row(p.label, p.value));
		bar.set(p.kept, p.lost);
		if (note != null && p.note != null)
		{
			note.set(p.note);
			// No cost, no line: it takes no room.
			if (note.isVisible() == p.note.text.isEmpty())
			{
				note.setVisible(!p.note.text.isEmpty());
			}
		}
	}

	/** T-196: the note under the bar, for tests; null without one. */
	String noteText()
	{
		return note == null ? null : note.getText();
	}

	String valueText()
	{
		return line.right.getText();
	}

	/** The 5 px bar itself. */
	static final class Bar extends JComponent
	{
		static final int HEIGHT = 5;
		private double kept = Double.NaN;
		private Color lost = SidePanelStyle.MINOR_COST;

		void set(double newKept, Color newLost)
		{
			if (Double.compare(newKept, kept) != 0 || !newLost.equals(lost))
			{
				kept = newKept;
				lost = newLost;
				repaint();
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(SidePanelStyle.CONTENT_WIDTH, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final int w = getWidth();
			g.setColor(SidePanelStyle.TRACK);
			g.fillRect(0, 0, w, HEIGHT);
			if (Double.isNaN(kept))
			{
				return;
			}
			final int k = (int) Math.round(w * kept);
			g.setColor(SidePanelStyle.KEPT);
			g.fillRect(0, 0, k, HEIGHT);
			g.setColor(lost);
			g.fillRect(k, 0, w - k, HEIGHT);
		}
	}
}

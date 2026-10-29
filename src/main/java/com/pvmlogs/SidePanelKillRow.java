package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * A kill in a list (spec 3.2): the clock in bold, the date, the luck chip and
 * execution on the right. Selectable: hover and selected are {@code #3C3C3C},
 * and the selected row has a 2 px Accent edge on its left.
 */
final class SidePanelKillRow extends JPanel
{
	static final int EDGE = 2;

	private boolean selected;
	private boolean hover;

	/** A History row: clock, date, chip, execution. {@code onClick} null for none. */
	static SidePanelKillRow history(SidePanelHistory.KillRow row, Runnable onClick)
	{
		final SidePanelKillRow r = new SidePanelKillRow(onClick);
		final JPanel left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);
		// The kill's time right-aligned in a column as wide as its widest digits (Eric, 2026-10-29), so 1:57 under 2:16
		// ends where it does and the dates after them start in line. SidePanelText asks for its own width, so the column
		// is the box around it.
		final SidePanelText clock = new SidePanelText(Text.title(row.clock, SidePanelStyle.VALUE), true);
		final JPanel clockBox = new JPanel(new BorderLayout());
		clockBox.setOpaque(false);
		clockBox.add(clock, BorderLayout.CENTER);
		clockBox.setPreferredSize(new Dimension(Math.max(SidePanelStyle.columnWidth(SidePanelStyle.Type.TITLE, "0:00", 40),
			SidePanelText.reservedWidth(SidePanelStyle.metrics(clock, clock.getFont()), row.clock)),
			SidePanelStyle.Type.TITLE.lineHeight()));
		left.add(clockBox, BorderLayout.WEST);
		left.add(new SidePanelText(Text.small(row.date, SidePanelStyle.SECONDARY), false), BorderLayout.CENTER);
		r.add(left, BorderLayout.CENTER);
		r.add(right(row, true), BorderLayout.EAST);
		return r;
	}

	/** The chip and, on History, the execution in a fixed column so the rows line up. */
	private static JPanel right(SidePanelHistory.KillRow row, boolean execution)
	{
		final JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setOpaque(false);
		final SidePanelParts.Chip chip = new SidePanelParts.Chip();
		chip.set(row.chip, row.chipFill);
		right.add(chip, BorderLayout.CENTER);
		if (execution)
		{
			final SidePanelText exec = new SidePanelText(Text.small(row.execution, SidePanelStyle.BODY), true);
			exec.setPreferredSize(new Dimension(SidePanelStyle.columnWidth(SidePanelStyle.Type.SMALL, "100%", 30),
				SidePanelStyle.Type.SMALL.lineHeight()));
			right.add(exec, BorderLayout.EAST);
		}
		return right;
	}

	private SidePanelKillRow(Runnable onClick)
	{
		super(new BorderLayout(6, 0));
		setOpaque(false);
		setBorder(new EmptyBorder(5, 6 + EDGE, 5, 6));
		if (onClick != null)
		{
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}

				@Override
				public void mouseClicked(MouseEvent e)
				{
					onClick.run();
				}
			});
		}
	}

	void setSelected(boolean s)
	{
		selected = s;
		repaint();
	}

	boolean isSelected()
	{
		return selected;
	}

	/** 26 with the RuneScape fonts: a title line and 5 px above and below (T-053). */
	static int rowHeight()
	{
		return Math.max(26, SidePanelStyle.Type.TITLE.lineHeight() + 10);
	}

	@Override
	public Dimension getPreferredSize()
	{
		final Dimension d = super.getPreferredSize();
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, Math.max(d.height, rowHeight()));
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(selected || hover ? SidePanelStyle.SELECTED : SidePanelStyle.CARD);
		g.fillRect(0, 0, getWidth(), getHeight());
		if (selected)
		{
			g.setColor(SidePanelStyle.ACCENT);
			g.fillRect(0, 0, EDGE, getHeight());
		}
	}
}

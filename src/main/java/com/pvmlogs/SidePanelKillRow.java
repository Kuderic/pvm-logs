package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * A kill in a list (spec 3.2): the date on the left; the clock in bold, the
 * luck chip and execution on the right. Selectable: hover and selected are {@code #3C3C3C},
 * and the selected row has a 2 px Accent edge on its left.
 */
final class SidePanelKillRow extends JPanel
{
	static final int EDGE = 2;

	private boolean selected;
	private boolean hover;

	/**
	 * A History row: the date on the left; the kill's time, the luck chip and the execution on the right, under the
	 * header's {@code time · luck · execution} (Eric, 2026-10-29). Each of the three is right-aligned in a column of
	 * its own, so the rows line up. {@code onClick} null for none.
	 */
	static SidePanelKillRow history(SidePanelHistory.KillRow row, Runnable onClick)
	{
		final SidePanelKillRow r = new SidePanelKillRow(onClick);
		final SidePanelText date = new SidePanelText(Text.small(row.date, SidePanelStyle.SECONDARY), false);
		date.setShort(dateShort(row.date));
		r.add(date, BorderLayout.CENTER);
		final JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setOpaque(false);
		final SidePanelText clock = new SidePanelText(Text.title(row.clock, SidePanelStyle.VALUE), true);
		// Measured as its shape with every digit a 0, so 2:16 and 1:57 take exactly the width 0:00 takes.
		right.add(new Column(clock, () -> SidePanelText.reservedWidth(SidePanelStyle.metrics(clock, clock.getFont()),
			longer("0:00", row.clock.replaceAll("[0-9]", "0")))), BorderLayout.WEST);
		right.add(chipAndExecution(row), BorderLayout.CENTER);
		r.add(right, BorderLayout.EAST);
		return r;
	}

	/**
	 * Where the date doesn't fit, it gives up its time of day for an older kill ({@code 26 Oct 9:40 pm} to {@code 26
	 * Oct}), and its am or pm for today's ({@code 12:43 pm} to {@code 12:43}).
	 */
	static String dateShort(String date)
	{
		final String[] words = date.split(" ");
		if (words.length > 2 && !words[0].isEmpty() && Character.isDigit(words[0].charAt(0)) && !words[1].isEmpty()
			&& Character.isLetter(words[1].charAt(0)))
		{
			return words[0] + " " + words[1];
		}
		if (words.length == 2 && (words[1].equals("am") || words[1].equals("pm")))
		{
			return words[0];
		}
		return null;
	}

	private static String longer(String a, String b)
	{
		return b.length() > a.length() ? b : a;
	}

	/** The widest chip a column keeps room for: a quartile, or seconds for a kill with no percentile. */
	private static final String[] CHIP_SAMPLES = {"bottom 25%", "14 s slower"};

	/** The chip and the execution, each right-aligned in a fixed column so the rows line up. */
	private static JPanel chipAndExecution(SidePanelHistory.KillRow row)
	{
		final JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setOpaque(false);
		final SidePanelParts.Chip chip = new SidePanelParts.Chip();
		chip.set(row.chip, row.chipFill);
		right.add(new Column(chip, () ->
		{
			int w = chip.getPreferredSize().width;
			for (String sample : CHIP_SAMPLES)
			{
				// The chip's padding is 8 (SidePanelParts.Chip).
				w = Math.max(w, SidePanelText.reservedWidth(SidePanelStyle.metrics(chip, chip.getFont()), sample) + 8);
			}
			return w;
		}), BorderLayout.CENTER);
		final SidePanelText exec = new SidePanelText(Text.small(row.execution, SidePanelStyle.BODY), true);
		right.add(new Column(exec, () -> Math.max(SidePanelStyle.columnWidth(SidePanelStyle.Type.SMALL, "100%", 30),
			exec.getPreferredSize().width)), BorderLayout.EAST);
		return right;
	}

	/**
	 * A column holding {@code c} against its right edge, as wide as {@code width} says when it is laid out: measured in
	 * the fonts of the moment, so T-053's text settings resize it. {@code SidePanelText} and the chip ask for their own
	 * widths whatever is set on them, so the column is a box around them.
	 */
	private static final class Column extends JPanel
	{
		private final JComponent c;
		private final java.util.function.IntSupplier width;

		Column(JComponent c, java.util.function.IntSupplier width)
		{
			super(new BorderLayout());
			setOpaque(false);
			this.c = c;
			this.width = width;
			add(c, c instanceof SidePanelText ? BorderLayout.CENTER : BorderLayout.EAST);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(width.getAsInt(), c.getPreferredSize().height);
		}
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

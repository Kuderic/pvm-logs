package com.pvmlogs;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;

/**
 * T-196 (the clarity pass): Damage's share bar, each source's share of your damage in its colour, and their short
 * names under it: the biggest on the left, the rest on the right ({@code tentacle}, {@code crossbow · thrall}).
 * Painted at a fixed height, so a new snapshot repaints it and lays nothing out.
 */
final class SidePanelShareBar extends JComponent
{
	static final int BAR_H = 8;
	private List<PanelSnapshot.Source> rows = Collections.emptyList();

	SidePanelShareBar(List<PanelSnapshot.Source> rows)
	{
		setOpaque(false);
		set(rows);
	}

	void set(List<PanelSnapshot.Source> now)
	{
		final List<PanelSnapshot.Source> shown = new ArrayList<>();
		for (PanelSnapshot.Source r : now)
		{
			if (r.shareFraction > 0)
			{
				shown.add(r);
			}
		}
		shown.sort((a, b) -> Double.compare(b.shareFraction, a.shareFraction));
		rows = shown;
		repaint();
	}

	/** The names under the bar: the biggest, then the rest joined. */
	static String[] names(List<PanelSnapshot.Source> rows)
	{
		if (rows.isEmpty())
		{
			return new String[]{"", ""};
		}
		final List<String> rest = new ArrayList<>();
		for (int i = 1; i < rows.size(); i++)
		{
			rest.add(SidePanelModel.shortName(rows.get(i).name));
		}
		return new String[]{SidePanelModel.shortName(rows.get(0).name), String.join(" · ", rest)};
	}

	List<PanelSnapshot.Source> rows()
	{
		return rows;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, BAR_H + 3 + SidePanelStyle.Type.SMALL.lineHeight());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		SidePanelStyle.textHints(g);
		final int w = getWidth();
		double total = 0;
		for (PanelSnapshot.Source r : rows)
		{
			total += r.shareFraction;
		}
		g.setColor(SidePanelStyle.TRACK);
		g.fillRect(0, 0, w, BAR_H);
		double acc = 0;
		int x = 0;
		for (int i = 0; i < rows.size(); i++)
		{
			acc += rows.get(i).shareFraction;
			final int end = i == rows.size() - 1 ? w : (int) Math.round(acc / Math.max(1e-9, total) * w);
			g.setColor(rows.get(i).colour);
			g.fillRect(x, 0, Math.max(0, end - x), BAR_H);
			x = end;
		}
		final String[] names = names(rows);
		g.setFont(SidePanelStyle.Type.SMALL.font());
		final FontMetrics fm = g.getFontMetrics();
		g.setColor(SidePanelStyle.SECONDARY);
		final int base = BAR_H + 3 + SidePanelStyle.Type.SMALL.lineHeight() - fm.getDescent();
		final String left = LedgerRows.cut(names[0], fm::stringWidth, w / 2);
		g.drawString(left, 0, base);
		final int room = w - fm.stringWidth(left) - 8;
		final String right = LedgerRows.cut(names[1], fm::stringWidth, Math.max(0, room));
		g.drawString(right, w - fm.stringWidth(right), base);
		g.dispose();
	}
}

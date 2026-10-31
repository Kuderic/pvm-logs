package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;

/**
 * T-125: the Party tab's Damage over time. A line a member in their colour, the player's own bold and white on top,
 * {@code others} dashed grey. Hovering draws T-112's line at that moment and a readout of every member's damage there,
 * painted over the chart: a hover never lays anything out (T-046). Drilled into a member, the other lines fade.
 */
final class SidePanelPartyChart extends JComponent
{
	static final int HEIGHT = 96;

	private List<PanelSnapshot.PartyRow> rows = Collections.emptyList();
	private int ticks = 1;
	private String clock = "";
	private long focus = Long.MIN_VALUE;
	private int hover = -1;

	SidePanelPartyChart()
	{
		setOpaque(false);
		final MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				setHover(tickAt(e.getX()));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setHover(-1);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	void set(PanelSnapshot.PartyTab t, long focus)
	{
		this.rows = t.rows;
		this.ticks = t.ticks;
		this.clock = t.clock;
		this.focus = focus;
		repaint();
	}

	/** For tests and renders: the tick hovered; whether it changed. */
	boolean setHover(int tick)
	{
		final int h = tick < 0 ? -1 : Math.min(tick, ticks);
		if (h == hover)
		{
			return false;
		}
		hover = h;
		repaint();
		return true;
	}

	private int tickAt(int x)
	{
		final int w = Math.max(1, getWidth() - 1);
		return x < 0 || x > getWidth() ? -1 : (int) Math.round((double) x / w * ticks);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH - 2 * SidePanelStyle.CARD_PAD_H, HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		if (rows.isEmpty())
		{
			return;
		}
		final Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		SidePanelStyle.textHints(g);
		final Font small = SidePanelStyle.Type.SMALL.font();
		final FontMetrics fs = SidePanelStyle.metrics(g, small);
		final int w = getWidth();
		final int h = getHeight();
		final int plotH = h - fs.getHeight() - 2;
		int max = 1;
		for (PanelSnapshot.PartyRow r : rows)
		{
			for (int d : r.seriesDamage)
			{
				max = Math.max(max, d);
			}
		}
		// Recessive grid: the top and the baseline; the top value and the clock's ends.
		g.setColor(SidePanelStyle.DIVIDER);
		g.drawLine(0, 0, w, 0);
		g.drawLine(0, plotH - 1, w, plotH - 1);
		g.setFont(small);
		g.setColor(SidePanelStyle.MUTED);
		g.drawString(SidePanelParty.compact(max), 2, fs.getAscent() + 1);
		g.drawString(LedgerRows.duration(0), 0, h - fs.getDescent());
		g.drawString(clock, w - fs.stringWidth(clock), h - fs.getDescent());
		final List<PanelSnapshot.PartyRow> order = new ArrayList<>(rows);
		// Others underneath, the player on top.
		order.sort(Comparator.comparingInt((PanelSnapshot.PartyRow r) -> r.others ? 0 : r.you ? 2 : 1));
		for (PanelSnapshot.PartyRow r : order)
		{
			if (r.seriesTicks.length < 2)
			{
				continue;
			}
			final boolean faded = focus != Long.MIN_VALUE && focus != r.id;
			g.setStroke(r.others ? new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
				new float[]{3f, 3f}, 0f) : new BasicStroke(r.you ? 2.2f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			final Color c = r.left ? SidePanelStyle.alpha(r.colour, 0.5) : r.colour;
			g.setColor(faded ? SidePanelStyle.alpha(c, 0.25) : c);
			g.draw(path(r, max, w, plotH));
		}
		if (hover >= 0)
		{
			readout(g, fs, w, plotH);
		}
		g.dispose();
	}

	private Path2D path(PanelSnapshot.PartyRow r, int max, int w, int plotH)
	{
		final Path2D p = new Path2D.Double();
		for (int i = 0; i < r.seriesTicks.length; i++)
		{
			final double x = (double) r.seriesTicks[i] / ticks * (w - 1);
			final double y = plotH - 2 - (double) r.seriesDamage[i] / max * (plotH - 4);
			if (i == 0)
			{
				p.moveTo(x, y);
			}
			else
			{
				p.lineTo(x, y);
			}
		}
		return p;
	}

	/** T-112's hover line, and a box of every member's damage at that moment beside it. */
	private void readout(Graphics2D g, FontMetrics fs, int w, int plotH)
	{
		final int x = (int) Math.round((double) hover / ticks * (w - 1));
		g.setStroke(new BasicStroke(1f));
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.VALUE, 0.6));
		g.drawLine(x, 0, x, plotH - 1);
		final List<String[]> lines = new ArrayList<>();
		final List<Color> colours = new ArrayList<>();
		lines.add(new String[]{"at " + LedgerRows.duration(hover * LedgerRows.SECONDS_PER_TICK), ""});
		colours.add(null);
		final List<PanelSnapshot.PartyRow> byDamage = new ArrayList<>();
		for (PanelSnapshot.PartyRow r : rows)
		{
			if (!r.others)
			{
				byDamage.add(r);
			}
		}
		byDamage.sort(Comparator.comparingInt((PanelSnapshot.PartyRow r) -> -r.damageAt(hover)));
		for (PanelSnapshot.PartyRow r : byDamage)
		{
			lines.add(new String[]{r.name, LedgerRows.number(r.damageAt(hover))});
			colours.add(r.colour);
		}
		int nameW = 0;
		int valueW = 0;
		for (String[] l : lines)
		{
			nameW = Math.max(nameW, fs.stringWidth(l[0]));
			valueW = Math.max(valueW, fs.stringWidth(l[1]));
		}
		final int lh = SidePanelStyle.Type.SMALL.lineHeight();
		final int bw = 4 + 8 + nameW + 8 + valueW + 4;
		final int bh = 3 + lines.size() * lh + 2;
		final int bx = x + 6 + bw <= w ? x + 6 : Math.max(0, x - 6 - bw);
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.PANEL, 0.92));
		g.fillRect(bx, 1, bw, Math.min(bh, plotH - 2));
		g.setColor(SidePanelStyle.DIVIDER);
		g.drawRect(bx, 1, bw - 1, Math.min(bh, plotH - 2) - 1);
		for (int i = 0; i < lines.size(); i++)
		{
			final int base = 3 + i * lh + fs.getAscent();
			if (base > plotH - 2)
			{
				break;
			}
			if (colours.get(i) != null)
			{
				g.setColor(colours.get(i));
				g.fillRect(bx + 4, base - fs.getAscent() + 2, 6, 6);
			}
			g.setColor(i == 0 ? SidePanelStyle.SECONDARY : SidePanelStyle.BODY);
			g.drawString(lines.get(i)[0], bx + (i == 0 ? 4 : 12), base);
			g.setColor(SidePanelStyle.VALUE);
			g.drawString(lines.get(i)[1], bx + bw - 4 - fs.stringWidth(lines.get(i)[1]), base);
		}
	}
}

package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;

/**
 * "Where the time went" (spec 3.1 and 4): the fight as one bar, best play then
 * each part's seconds, luck's cost striped and the time on unscored adds grey.
 * A solid white tick marks your real time, labelled with the clock above the
 * bar (T-042). When the rolls saved time the bar runs on to the time at average
 * luck, labelled at its end, and the stretch past the tick is dimmed under
 * light stripes: time you didn't spend. Your average is a dashed grey marker
 * hanging below the bar, its clock under it. Shapes only, so it stays sharp at
 * 2× (spec section 1).
 */
final class SidePanelTimeBudgetBar extends JComponent
{
	/** The clock and end labels' baseline, above the bar. */
	static final int LABEL_BASELINE = 10;
	static final int BAR_TOP = 16;
	static final int BAR_HEIGHT = 16;
	static final int HEIGHT = 48;
	/** The tick: 2 px wide, from a little above the bar to a little below it. */
	static final int TICK_WIDTH = 2;
	static final int TICK_TOP = BAR_TOP - 3;
	static final int TICK_BOTTOM = BAR_TOP + BAR_HEIGHT + 2;
	/** Space between a label and the tick, or between two labels. */
	static final int GAP = 4;
	/** Between your clock and the end's label when they share one. */
	static final String JOIN = " · ";

	private PanelSnapshot.TimeBudget budget;
	private String markerLabel;

	SidePanelTimeBudgetBar(PanelSnapshot.TimeBudget budget)
	{
		setOpaque(false);
		setFont(SidePanelStyle.Type.SMALL.font());
		set(budget);
	}

	void set(PanelSnapshot.TimeBudget b)
	{
		budget = b;
		markerLabel = Double.isNaN(b.marker) ? null : "your average " + LedgerRows.duration(b.marker);
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, HEIGHT);
	}

	/** Where your real time falls on a bar {@code width} wide: T ÷ (T + L) of it, the end when luck didn't save time. */
	static double tickX(PanelSnapshot.TimeBudget b, double width)
	{
		return b.total <= 0 ? width : Math.min(1, b.clock / b.total) * width;
	}

	/** Where the clock and end labels go on a bar {@code width} wide (T-042): they never overlap. */
	static final class Labels
	{
		/** Your clock over the tick, right-aligned to its left; null when it isn't labelled on its own. */
		final String clock;
		final double clockX;
		/** The bar's end, right-aligned to it: the time at average luck, or both with your clock first; null for none. */
		final String end;
		final double endX;
		/** Whether {@link #end} starts with your clock (the two shared one label). */
		final boolean joined;

		Labels(String clock, double clockX, String end, double endX, boolean joined)
		{
			this.clock = clock;
			this.clockX = clockX;
			this.end = end;
			this.endX = endX;
			this.joined = joined;
		}
	}

	static Labels labels(PanelSnapshot.TimeBudget b, double width, FontMetrics fm)
	{
		final double tick = tickX(b, width);
		final int cw = fm.stringWidth(b.clockLabel);
		if (b.endLabel == null)
		{
			// Luck cost time or none: the tick is the bar's end, and so is its label.
			return new Labels(b.clockLabel, width - cw, null, 0, false);
		}
		// Both apart: your clock to the left of the tick, the time at average luck at the end, clear of each other.
		final int ew = fm.stringWidth(b.endLabel);
		final double cx = tick - GAP - cw;
		final double ex = width - ew;
		if (cx >= 0 && cx + cw + GAP <= ex)
		{
			return new Labels(b.clockLabel, cx, b.endLabel, ex, false);
		}
		// Too close (luck saved a little): one label at the end, your clock first.
		final String both = b.clockLabel + JOIN + b.endLabel;
		final int bw = fm.stringWidth(both);
		if (bw <= width)
		{
			return new Labels(null, 0, both, width - bw, true);
		}
		// Never at 213 px; the end's label alone and an unlabelled tick.
		return new Labels(null, 0, b.endLabel, Math.max(0, ex), false);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		if (budget == null || budget.total <= 0)
		{
			return;
		}
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final double width = getWidth();
		final double scale = width / budget.total;
		double x = 0;
		for (PanelSnapshot.Segment s : budget.segments)
		{
			final double w = s.seconds * scale;
			if (w > 0)
			{
				SidePanelParts.paint(g2, new Rectangle2D.Double(x, BAR_TOP, w, BAR_HEIGHT), s.fill, null);
			}
			x += w;
		}
		final double tick = tickX(budget, width);
		if (budget.luckSaved > 0 && tick < width)
		{
			saved(g2, new Rectangle2D.Double(tick, BAR_TOP, width - tick, BAR_HEIGHT), budget.luckSavedColour);
		}
		// The average marker, only when it falls on the bar; the row under it says it either way. Dashed and grey, hanging
		// below the bar, so it never reads as the tick.
		final boolean marker = markerLabel != null && budget.marker >= 0 && budget.marker <= budget.total;
		if (marker)
		{
			final double mx = budget.marker * scale;
			g2.setColor(SidePanelStyle.AVERAGE_MARKER);
			g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 2f}, 0f));
			g2.draw(new Line2D.Double(mx, BAR_TOP, mx, BAR_TOP + BAR_HEIGHT + 6));
		}
		// Your real time: solid white, over everything on the bar.
		g2.setColor(SidePanelStyle.CLOCK_TICK);
		final double tx = Math.max(0, Math.min(width - TICK_WIDTH, tick - TICK_WIDTH / 2.0));
		g2.fill(new Rectangle2D.Double(tx, TICK_TOP, TICK_WIDTH, TICK_BOTTOM - TICK_TOP));

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setFont(getFont());
		final FontMetrics fm = g2.getFontMetrics();
		final Labels l = labels(budget, width, fm);
		if (l.clock != null)
		{
			g2.setColor(SidePanelStyle.VALUE);
			g2.drawString(l.clock, (int) Math.round(l.clockX), LABEL_BASELINE);
		}
		if (l.end != null)
		{
			int ex = (int) Math.round(l.endX);
			if (l.joined)
			{
				// Your clock in the tick's white, the rest as the end's label.
				g2.setColor(SidePanelStyle.VALUE);
				g2.drawString(budget.clockLabel, ex, LABEL_BASELINE);
				ex += fm.stringWidth(budget.clockLabel);
			}
			g2.setColor(SidePanelStyle.SECONDARY);
			g2.drawString(l.joined ? l.end.substring(budget.clockLabel.length()) : l.end, ex, LABEL_BASELINE);
		}
		if (marker)
		{
			final double mx = budget.marker * scale;
			final int w = fm.stringWidth(markerLabel);
			// Right-aligned to the marker, as on the canvas, but never off either edge.
			final int mtx = (int) Math.max(0, Math.min(width - w, Math.round(mx - 2.5 - w)));
			g2.setColor(SidePanelStyle.SECONDARY);
			g2.drawString(markerLabel, mtx, HEIGHT - 1);
		}
		g2.dispose();
	}

	/**
	 * The time luck saved (T-042): what's under {@code area} dimmed to about
	 * 40%, then light stripes in {@code stripe} (Better, or the average grey).
	 * No outline. The legend's swatch paints best play's colour first and then
	 * this, so it matches the bar.
	 */
	static void saved(Graphics2D g, Shape area, Color stripe)
	{
		final Shape clip = g.getClip();
		g.setColor(SidePanelStyle.SAVED_DIM);
		g.fill(area);
		g.clip(area);
		g.setColor(SidePanelStyle.alpha(stripe == null ? SidePanelStyle.BETTER : stripe, SidePanelStyle.SAVED_STRIPE_ALPHA));
		g.setStroke(new BasicStroke(1.5f));
		final Rectangle2D b = area.getBounds2D();
		final double h = b.getHeight();
		for (double x = b.getX() - h; x < b.getMaxX() + h; x += 4 * Math.sqrt(2))
		{
			g.draw(new Line2D.Double(x, b.getMaxY(), x + h, b.getY()));
		}
		g.setClip(clip);
	}
}

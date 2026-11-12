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
 * luck, labelled at its end: the losses stay inside your time, and the best
 * play luck saved goes past the tick, dimmed under light stripes: time you
 * didn't spend. Your average is a dashed grey marker
 * hanging below the bar, its clock under it. Shapes only, so it stays sharp at
 * 2× (spec section 1).
 *
 * <p>T-214 (Eric: "Where the time went score bar changes what makes up the bar when you expand it, can we keep a
 * consistent design here"): {@link #paintBar} draws the segments for the open section and, 6 px tall with no tick,
 * labels or marker, for its folded line: one list, one painter, two heights.
 */
final class SidePanelTimeBudgetBar extends JComponent
{
	static final int BAR_HEIGHT = 16;
	/** The tick: 2 px wide, from a little above the bar to a little below it. */
	static final int TICK_WIDTH = 2;

	// T-053: the label rows above and below the bar are Small's line height (12 with the RuneScape fonts, which gives
	// T-042's 10 / 16 / 48), so larger or smoother text has room.

	/** The clock and end labels' baseline, above the bar. */
	static int labelBaseline()
	{
		return SidePanelStyle.Type.SMALL.lineHeight() - 2;
	}

	static int barTop()
	{
		return SidePanelStyle.Type.SMALL.lineHeight() + 4;
	}

	static int height()
	{
		return barTop() + BAR_HEIGHT + SidePanelStyle.Type.SMALL.lineHeight() + 4;
	}

	static int tickTop()
	{
		return barTop() - 3;
	}

	static int tickBottom()
	{
		return barTop() + BAR_HEIGHT + 2;
	}

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

	/** The text settings changed (T-053): Small's new font, and the label rows' new height. */
	void restyle()
	{
		setFont(SidePanelStyle.Type.SMALL.font());
		revalidate();
		repaint();
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
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, height());
	}

	/**
	 * Where your real time falls on a bar {@code width} wide: where luck's saved stretch starts (T ÷ (T + L) of it, to the
	 * pixel), the end when luck didn't save time.
	 */
	static double tickX(PanelSnapshot.TimeBudget b, double width)
	{
		if (b.total <= 0)
		{
			return width;
		}
		final int saved = savedIndex(b);
		if (saved < 0)
		{
			return Math.min(1, b.clock / b.total) * width;
		}
		return edges(b, (int) Math.round(width))[saved];
	}

	/** T-214: the luck-saved stretch's index, past the tick; -1 for none. */
	private static int savedIndex(PanelSnapshot.TimeBudget b)
	{
		if (b.luckSaved <= 0)
		{
			return -1;
		}
		for (int i = b.segments.size() - 1; i >= 0; i--)
		{
			if (b.segments.get(i).fill == PanelSnapshot.Fill.LUCK_FASTER)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * T-214: the segments' edges on a bar {@code width} px wide, in whole pixels: edge i is segment i's left, the last
	 * the bar's end. Each segment its share of the length; one the legend lists that would be under a pixel is 1 px, in
	 * its place (a 1-tick idle in a long fight still shows at 6 px), the pixels it takes come off the widest.
	 */
	static int[] edges(PanelSnapshot.TimeBudget b, int width)
	{
		final int n = b.segments.size();
		final double scale = b.total > 0 ? width / b.total : 0;
		final double[] want = new double[n];
		final int[] px = new int[n];
		int used = 0;
		double length = 0;
		for (int i = 0; i < n; i++)
		{
			final PanelSnapshot.Segment s = b.segments.get(i);
			final double w = Double.isNaN(s.seconds) || s.seconds <= 0 ? 0 : s.seconds * scale;
			length += w;
			want[i] = s.listed && w > 0 && w < 1 ? 1 : w;
			px[i] = (int) Math.floor(want[i]);
			used += px[i];
		}
		// The pixels left over go to the largest remainders; too many (the 1 px stretches) come off the widest.
		int left = (int) Math.round(Math.min(width, length)) - used;
		while (left > 0)
		{
			int best = -1;
			for (int i = 0; i < n; i++)
			{
				if (want[i] - px[i] > 0 && (best < 0 || want[i] - px[i] > want[best] - px[best]))
				{
					best = i;
				}
			}
			if (best < 0)
			{
				break;
			}
			px[best]++;
			left--;
		}
		while (left < 0)
		{
			int widest = 0;
			for (int i = 1; i < n; i++)
			{
				widest = px[i] > px[widest] ? i : widest;
			}
			if (n == 0 || px[widest] <= 1)
			{
				break;
			}
			px[widest]--;
			left++;
		}
		final int[] edges = new int[n + 1];
		for (int i = 0; i < n; i++)
		{
			edges[i + 1] = edges[i] + px[i];
		}
		return edges;
	}

	/**
	 * T-214: the bar's segments in {@code w} × {@code h} at ({@code x}, {@code y}), each in its fill, luck's in its violet
	 * ({@link PanelSnapshot.TimeBudget#luckStripe}): the open section's bar and, small, its folded line's. Returns the
	 * fills drawn, in order, for tests.
	 */
	static java.util.List<PanelSnapshot.Fill> paintBar(Graphics2D g, PanelSnapshot.TimeBudget b, int x, int y, int w, int h)
	{
		final java.util.List<PanelSnapshot.Fill> drawn = new java.util.ArrayList<>(b.segments.size());
		final int[] edges = edges(b, w);
		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(SidePanelStyle.TRACK);
		g.fillRect(x, y, w, h);
		for (int i = 0; i < b.segments.size(); i++)
		{
			final int sw = edges[i + 1] - edges[i];
			if (sw <= 0)
			{
				continue;
			}
			final PanelSnapshot.Fill fill = b.segments.get(i).fill;
			SidePanelParts.paint(g, new Rectangle2D.Double(x + edges[i], y, sw, h), fill, fill.luck() ? b.luckStripe : null);
			drawn.add(fill);
		}
		if (aa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
		return drawn;
	}

	/** T-214: the fills last painted, in order, for tests. */
	java.util.List<PanelSnapshot.Fill> drawn = java.util.Collections.emptyList();

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
		final double width = getWidth();
		final double scale = width / budget.total;
		drawn = paintBar(g2, budget, 0, barTop(), getWidth(), BAR_HEIGHT);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final double tick = tickX(budget, width);
		// The average marker, only when it falls on the bar; the row under it says it either way. Dashed and grey, hanging
		// below the bar, so it never reads as the tick.
		final boolean marker = markerLabel != null && budget.marker >= 0 && budget.marker <= budget.total;
		if (marker)
		{
			final double mx = budget.marker * scale;
			g2.setColor(SidePanelStyle.AVERAGE_MARKER);
			g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 2f}, 0f));
			g2.draw(new Line2D.Double(mx, barTop(), mx, barTop() + BAR_HEIGHT + 6));
		}
		// Your real time: solid white, over everything on the bar.
		g2.setColor(SidePanelStyle.CLOCK_TICK);
		final double tx = Math.max(0, Math.min(width - TICK_WIDTH, tick - TICK_WIDTH / 2.0));
		g2.fill(new Rectangle2D.Double(tx, tickTop(), TICK_WIDTH, tickBottom() - tickTop()));

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		SidePanelStyle.textHints(g2);
		g2.setFont(getFont());
		final FontMetrics fm = SidePanelStyle.metrics(g2, getFont());
		final Labels l = labels(budget, width, fm);
		if (l.clock != null)
		{
			g2.setColor(SidePanelStyle.VALUE);
			g2.drawString(l.clock, (int) Math.round(l.clockX), labelBaseline());
		}
		if (l.end != null)
		{
			int ex = (int) Math.round(l.endX);
			if (l.joined)
			{
				// Your clock in the tick's white, the rest as the end's label.
				g2.setColor(SidePanelStyle.VALUE);
				g2.drawString(budget.clockLabel, ex, labelBaseline());
				ex += fm.stringWidth(budget.clockLabel);
			}
			g2.setColor(SidePanelStyle.SECONDARY);
			g2.drawString(l.joined ? l.end.substring(budget.clockLabel.length()) : l.end, ex, labelBaseline());
		}
		if (marker)
		{
			final double mx = budget.marker * scale;
			final int w = fm.stringWidth(markerLabel);
			// Right-aligned to the marker, as on the canvas, but never off either edge.
			final int mtx = (int) Math.max(0, Math.min(width - w, Math.round(mx - 2.5 - w)));
			g2.setColor(SidePanelStyle.SECONDARY);
			g2.drawString(markerLabel, mtx, height() - fm.getDescent());
		}
		g2.dispose();
	}

	/**
	 * The time luck saved (T-042): what's under {@code area} dimmed to about
	 * 40%, then light stripes in {@code stripe} (T-214: luck's violet).
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

package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;

/**
 * "Where the time went" (spec 3.1 and 4): the fight's clock as one bar, best
 * play then each part's seconds, luck's cost striped and the time on unscored
 * adds grey. When the rolls saved time the bar runs past the clock by that
 * much, outlined. Your average is a dashed marker with its clock under it.
 * Shapes only, so it stays sharp at 2× (spec section 1).
 */
final class SidePanelTimeBudgetBar extends JComponent
{
	static final int HEIGHT = 40;
	private static final int BAR_TOP = 8;
	private static final int BAR_HEIGHT = 16;

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

	@Override
	protected void paintComponent(Graphics g)
	{
		if (budget == null || budget.total <= 0)
		{
			return;
		}
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final double scale = getWidth() / budget.total;
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
		if (budget.luckSaved > 0)
		{
			SidePanelParts.paint(g2, new Rectangle2D.Double(budget.clock * scale, BAR_TOP, budget.luckSaved * scale,
				BAR_HEIGHT), PanelSnapshot.Fill.LUCK_FASTER, budget.luckSavedColour);
		}
		// The average marker, only when it falls on the bar; the row under it says it either way.
		if (markerLabel != null && budget.marker >= 0 && budget.marker <= budget.total)
		{
			final double mx = budget.marker * scale;
			g2.setColor(SidePanelStyle.VALUE);
			g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 2f}, 0f));
			g2.draw(new Line2D.Double(mx, 2, mx, BAR_TOP + BAR_HEIGHT + 6));
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g2.setFont(getFont());
			final FontMetrics fm = g2.getFontMetrics();
			final int w = fm.stringWidth(markerLabel);
			// Right-aligned to the marker, as on the canvas, but never off the left edge.
			final int tx = (int) Math.max(0, Math.min(getWidth() - w, Math.round(mx - 2.5 - w)));
			g2.setColor(SidePanelStyle.SECONDARY);
			g2.drawString(markerLabel, tx, HEIGHT - 1);
		}
		g2.dispose();
	}
}

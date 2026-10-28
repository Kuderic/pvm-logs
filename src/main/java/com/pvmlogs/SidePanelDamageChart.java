package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;

/**
 * Damage over time (T-036; spec 3.1): the scope's cumulative weapon damage as
 * a 2 px step line in luck's colour, what the same attacks were expected to
 * deal as a dashed line, and the typical range round it (expected ± √variance,
 * where about 68% of fights with these attacks fall) as a faint band. Expected
 * and the range aren't drawn under the stats warning; the range isn't when the
 * spread isn't known. Shapes only, so it stays sharp at 2× (spec section 1).
 */
final class SidePanelDamageChart extends JComponent
{
	static final int HEIGHT = 84;
	private static final float[] DASH = {4f, 3f};

	private final SidePanelChartData data;

	SidePanelDamageChart(SidePanelChartData data)
	{
		this.data = data;
		setOpaque(false);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		final PanelSnapshot.Chart meta = data.meta();
		final int n = data.length();
		if (meta == null || n == 0)
		{
			return;
		}
		final boolean expectedShown = meta.expectedShown;
		final boolean range = meta.rangeShown;
		double top = 1;
		for (int i = 0; i < n; i++)
		{
			top = Math.max(top, data.damage(i));
			if (expectedShown)
			{
				top = Math.max(top, data.expected(i) + (range ? Math.sqrt(Math.max(0, data.variance(i))) : 0));
			}
		}
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final double w = getWidth();
		final double h = getHeight() - 1;
		final double xs = n == 1 ? 0 : w / (n - 1);
		final double ys = h / top;

		if (range)
		{
			final Path2D.Double band = new Path2D.Double();
			for (int i = 0; i < n; i++)
			{
				final double y = h - (data.expected(i) + Math.sqrt(Math.max(0, data.variance(i)))) * ys;
				if (i == 0)
				{
					band.moveTo(0, y);
				}
				else
				{
					band.lineTo(i * xs, y);
				}
			}
			for (int i = n - 1; i >= 0; i--)
			{
				band.lineTo(i * xs, h - Math.max(0, data.expected(i) - Math.sqrt(Math.max(0, data.variance(i)))) * ys);
			}
			band.closePath();
			g2.setColor(SidePanelStyle.alpha(SidePanelStyle.SECONDARY, 0.16));
			g2.fill(band);
		}
		if (expectedShown)
		{
			final Path2D.Double line = new Path2D.Double();
			for (int i = 0; i < n; i++)
			{
				final double y = h - data.expected(i) * ys;
				if (i == 0)
				{
					line.moveTo(0, y);
				}
				else
				{
					line.lineTo(i * xs, y);
				}
			}
			g2.setColor(SidePanelStyle.SECONDARY);
			g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, DASH, 0f));
			g2.draw(line);
		}
		// Damage arrives in steps: a step line, flat until a splat lands.
		final Path2D.Double you = new Path2D.Double();
		you.moveTo(0, h - data.damage(0) * ys);
		for (int i = 1; i < n; i++)
		{
			you.lineTo(i * xs, h - data.damage(i - 1) * ys);
			you.lineTo(i * xs, h - data.damage(i) * ys);
		}
		g2.setColor(meta.actual);
		g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		g2.draw(you);
		g2.dispose();
	}
}

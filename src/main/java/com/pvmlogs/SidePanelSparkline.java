package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;

/**
 * The boss card's sparkline (spec 3.2): the last like kills, oldest left; the
 * raw clock in grey, the luck-removed clock in blue over its middle half as a
 * faint band, and a dot on each line's newest end. Up is slower. Shapes only,
 * so it stays sharp at 2× (spec section 1).
 */
final class SidePanelSparkline extends JComponent
{
	static final int HEIGHT = 44;
	static final Color CLOCK = new Color(0x8A8A8A);
	private static final int PAD = 4;

	private SidePanelHistory.Spark spark;

	SidePanelSparkline(SidePanelHistory.Spark spark)
	{
		setOpaque(false);
		this.spark = spark;
	}

	void set(SidePanelHistory.Spark s)
	{
		spark = s;
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
		if (spark == null || spark.clock.length < 2)
		{
			return;
		}
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for (int i = 0; i < spark.clock.length; i++)
		{
			lo = Math.min(lo, Math.min(spark.clock[i], spark.star[i]));
			hi = Math.max(hi, Math.max(spark.clock[i], spark.star[i]));
		}
		if (hi - lo < 1)
		{
			hi += 0.5;
			lo -= 0.5;
		}
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int w = getWidth();
		final double bandTop = y(spark.bandHigh, lo, hi);
		final double bandBottom = y(spark.bandLow, lo, hi);
		g2.setColor(SidePanelStyle.alpha(SidePanelHistory.LUCK_REMOVED, 0.18));
		g2.fill(new Rectangle2D.Double(0, bandTop, w, Math.max(1, bandBottom - bandTop)));
		line(g2, spark.clock, lo, hi, w, CLOCK, 1.5f);
		line(g2, spark.star, lo, hi, w, SidePanelHistory.LUCK_REMOVED, 2f);
		final int last = spark.clock.length - 1;
		dot(g2, x(last, w), y(spark.clock[last], lo, hi), SidePanelStyle.VALUE);
		dot(g2, x(last, w), y(spark.star[last], lo, hi), SidePanelHistory.LUCK_REMOVED);
		g2.dispose();
	}

	private double x(int i, int w)
	{
		return PAD + (w - 2.0 * PAD) * i / (spark.clock.length - 1);
	}

	/** Up is slower: the longest clock at the top. */
	private static double y(double seconds, double lo, double hi)
	{
		return PAD + (HEIGHT - 2.0 * PAD) * (1 - (seconds - lo) / (hi - lo));
	}

	private void line(Graphics2D g, double[] values, double lo, double hi, int w, Color c, float width)
	{
		final Path2D.Double p = new Path2D.Double();
		for (int i = 0; i < values.length; i++)
		{
			if (i == 0)
			{
				p.moveTo(x(i, w), y(values[i], lo, hi));
			}
			else
			{
				p.lineTo(x(i, w), y(values[i], lo, hi));
			}
		}
		g.setColor(c);
		g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(p);
	}

	private static void dot(Graphics2D g, double x, double y, Color c)
	{
		g.setColor(c);
		g.fill(new Ellipse2D.Double(x - 2.5, y - 2.5, 5, 5));
	}
}

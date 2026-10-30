package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import javax.swing.JComponent;

/**
 * The luck curve after the fight (spec 3.1, from direction C): a standard
 * normal curve over z from -3 to 3, its bottom quarter, middle half and top
 * quarter filled, and a 2 px white marker where this fight's damage sits.
 * Painted, never typed: the fonts have no curve or marker glyphs.
 */
final class SidePanelLuckCurve extends JComponent
{
	static final int HEIGHT = 46;
	/** Φ⁻¹(0.25) and Φ⁻¹(0.75): the middle half's edges. */
	static final double QUARTILE_Z = 0.6744897501960817;
	private static final double Z_RANGE = 3;
	private static final int BASE = 44;
	private static final int PEAK = 4;

	private double z;

	SidePanelLuckCurve(double z)
	{
		setOpaque(false);
		this.z = z;
	}

	void set(double newZ)
	{
		if (Double.compare(newZ, z) != 0)
		{
			z = newZ;
			repaint();
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, HEIGHT);
	}

	/** The curve's height at z, from the base line up: the normal density scaled to the peak. */
	private static double y(double z)
	{
		return BASE - (BASE - PEAK) * Math.exp(-z * z / 2);
	}

	/** Where z falls across the width. */
	static double x(double z, int width)
	{
		return (z + Z_RANGE) / (2 * Z_RANGE) * width;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int w = getWidth();
		band(g2, -Z_RANGE, -QUARTILE_Z, w, SidePanelStyle.alpha(SidePanelStyle.COST, 0.35));
		band(g2, -QUARTILE_Z, QUARTILE_Z, w, SidePanelStyle.alpha(SidePanelStyle.MUTED, 0.45));
		band(g2, QUARTILE_Z, Z_RANGE, w, SidePanelStyle.alpha(SidePanelStyle.BETTER, 0.30));
		// T-112: the tail the label counts, from the marker to the edge, in a brighter tone of each band it covers.
		final double[] tail = tail(z);
		tailBand(g2, tail, -Z_RANGE, -QUARTILE_Z, w, SidePanelStyle.alpha(SidePanelStyle.COST, 0.75));
		tailBand(g2, tail, -QUARTILE_Z, QUARTILE_Z, w, SidePanelStyle.alpha(SidePanelStyle.SECONDARY, 0.55));
		tailBand(g2, tail, QUARTILE_Z, Z_RANGE, w, SidePanelStyle.alpha(SidePanelStyle.BETTER, 0.70));
		final double mx = x(Math.max(-SidePanelModel.CURVE_Z_LIMIT, Math.min(SidePanelModel.CURVE_Z_LIMIT, z)), w);
		g2.setColor(SidePanelStyle.VALUE);
		g2.setStroke(new BasicStroke(2f));
		g2.draw(new Line2D.Double(mx, 2, mx, BASE));
		g2.dispose();
	}

	/**
	 * T-112: the tail the label counts, {from, to} in z: right of the marker for a fight at or above the middle (the
	 * share that would have dealt more), left of it below (the share that would have dealt less).
	 */
	static double[] tail(double z)
	{
		final double m = Math.max(-SidePanelModel.CURVE_Z_LIMIT, Math.min(SidePanelModel.CURVE_Z_LIMIT, z));
		return m >= 0 ? new double[]{m, Z_RANGE} : new double[]{-Z_RANGE, m};
	}

	private static void tailBand(Graphics2D g, double[] tail, double from, double to, int width, java.awt.Color fill)
	{
		final double a = Math.max(from, tail[0]);
		final double b = Math.min(to, tail[1]);
		if (b > a)
		{
			band(g, a, b, width, fill);
		}
	}

	private static void band(Graphics2D g, double from, double to, int width, java.awt.Color fill)
	{
		final Path2D.Double p = new Path2D.Double();
		p.moveTo(x(from, width), BASE);
		final int steps = 24;
		for (int i = 0; i <= steps; i++)
		{
			final double z = from + (to - from) * i / steps;
			p.lineTo(x(z, width), y(z));
		}
		p.lineTo(x(to, width), BASE);
		p.closePath();
		g.setColor(fill);
		g.fill(p);
	}
}

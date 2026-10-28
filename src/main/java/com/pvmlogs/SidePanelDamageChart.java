package com.pvmlogs;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Damage over time (T-036; spec 3.1): the scope's cumulative weapon damage as
 * a 2 px step line in luck's colour, what the same attacks were expected to
 * deal as a dashed line, and the typical range round it (expected ± √variance,
 * where about 68% of fights with these attacks fall) as a faint band. Expected
 * and the range aren't drawn under the stats warning; the range isn't when the
 * spread isn't known.
 *
 * <p>T-046: drawn into an image at the screen's scale, with at most one point
 * per pixel column, so what it costs is capped by the chart's width rather
 * than the fight's length. The time axis stretches as the fight grows, so each
 * update draws the image again (a few hundred points); painting copies it. The
 * image itself is made again only on a resize or a new scale.
 */
final class SidePanelDamageChart extends JComponent
{
	static final int HEIGHT = 84;
	private static final Color BAND = SidePanelStyle.alpha(SidePanelStyle.SECONDARY, 0.16);

	private final SidePanelChartData data;
	private PerfProbe perf;

	private BufferedImage image;
	private Graphics2D drawing;
	private double scale;
	private AffineTransform unscale;
	private BasicStroke line;
	private BasicStroke dashed;
	private int drawnVersion = -1;
	// The points, in device pixels, kept between updates: the step line has two a column, the band two a column.
	private int[] youX = new int[0];
	private int[] youY = new int[0];
	private int[] expectedX = new int[0];
	private int[] expectedY = new int[0];
	private int[] bandX = new int[0];
	private int[] bandY = new int[0];
	/** Points the last drawing used per line, for tests. */
	private int points;

	SidePanelDamageChart(SidePanelChartData data)
	{
		this.data = data;
		setOpaque(false);
	}

	void setPerf(PerfProbe perf)
	{
		this.perf = perf;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, HEIGHT);
	}

	/** The series changed (an update): the image is drawn again now. Swing's thread. */
	void update()
	{
		if (image != null && fits(scale))
		{
			final long started = PerfProbe.start(perf);
			draw();
			if (started != 0L)
			{
				perf.stopSwing(PerfProbe.SwingSlot.CHART_DRAW, started);
			}
		}
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		if (getWidth() <= 0 || getHeight() <= 0)
		{
			return;
		}
		final Graphics2D g2 = (Graphics2D) g;
		final double s = g2.getTransform().getScaleX();
		if (image == null || s != scale || !fits(s))
		{
			rebuild(s);
		}
		else if (drawnVersion != data.version())
		{
			draw();
		}
		g2.drawImage(image, unscale, null);
	}

	private boolean fits(double s)
	{
		return image != null && image.getWidth() == width(s) && image.getHeight() == height(s);
	}

	private int width(double s)
	{
		return Math.max(1, (int) Math.ceil(getWidth() * s));
	}

	private int height(double s)
	{
		return Math.max(1, (int) Math.ceil(getHeight() * s));
	}

	/** A new image and point arrays at scale {@code s}: a resize, a new scale, or the first paint. */
	private void rebuild(double s)
	{
		if (drawing != null)
		{
			drawing.dispose();
		}
		scale = s;
		unscale = AffineTransform.getScaleInstance(1 / s, 1 / s);
		image = new BufferedImage(width(s), height(s), BufferedImage.TYPE_INT_ARGB);
		drawing = image.createGraphics();
		drawing.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		line = new BasicStroke((float) (2 * s), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
		dashed = new BasicStroke((float) (1.5 * s), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
			new float[]{(float) (4 * s), (float) (3 * s)}, 0f);
		final int columns = image.getWidth();
		youX = new int[2 * columns];
		youY = new int[2 * columns];
		expectedX = new int[columns];
		expectedY = new int[columns];
		bandX = new int[2 * columns];
		bandY = new int[2 * columns];
		draw();
	}

	/** Draws the chart into the image: a point per column at most. */
	private void draw()
	{
		drawnVersion = data.version();
		drawing.setComposite(AlphaComposite.Clear);
		drawing.fillRect(0, 0, image.getWidth(), image.getHeight());
		drawing.setComposite(AlphaComposite.SrcOver);
		final PanelSnapshot.Chart meta = data.meta();
		final int n = data.length();
		points = 0;
		if (meta == null || n == 0)
		{
			return;
		}
		final boolean expectedShown = meta.expectedShown;
		final boolean range = meta.rangeShown;
		final int k = Math.min(n, image.getWidth());
		points = k;
		double top = 1;
		for (int j = 0; j < k; j++)
		{
			final int i = tick(j, k, n);
			top = Math.max(top, data.damage(i));
			if (expectedShown)
			{
				top = Math.max(top, data.expected(i) + (range ? Math.sqrt(Math.max(0, data.variance(i))) : 0));
			}
		}
		final double w = getWidth() * scale;
		final double h = (getHeight() - 1) * scale;
		final double xs = n == 1 ? 0 : w / (n - 1);
		final double ys = h / top;

		if (range)
		{
			for (int j = 0; j < k; j++)
			{
				final int i = tick(j, k, n);
				final double spread = Math.sqrt(Math.max(0, data.variance(i)));
				bandX[j] = (int) Math.round(i * xs);
				bandY[j] = (int) Math.round(h - (data.expected(i) + spread) * ys);
				bandX[2 * k - 1 - j] = bandX[j];
				bandY[2 * k - 1 - j] = (int) Math.round(h - Math.max(0, data.expected(i) - spread) * ys);
			}
			drawing.setColor(BAND);
			drawing.fillPolygon(bandX, bandY, 2 * k);
		}
		if (expectedShown)
		{
			for (int j = 0; j < k; j++)
			{
				final int i = tick(j, k, n);
				expectedX[j] = (int) Math.round(i * xs);
				expectedY[j] = (int) Math.round(h - data.expected(i) * ys);
			}
			drawing.setColor(SidePanelStyle.SECONDARY);
			drawing.setStroke(dashed);
			drawing.drawPolyline(expectedX, expectedY, k);
		}
		// Damage arrives in steps: a step line, flat until a splat lands.
		int m = 0;
		int previous = 0;
		for (int j = 0; j < k; j++)
		{
			final int i = tick(j, k, n);
			final int x = (int) Math.round(i * xs);
			final int y = (int) Math.round(h - data.damage(i) * ys);
			if (j > 0)
			{
				youX[m] = x;
				youY[m] = previous;
				m++;
			}
			youX[m] = x;
			youY[m] = y;
			m++;
			previous = y;
		}
		drawing.setColor(meta.actual);
		drawing.setStroke(line);
		drawing.drawPolyline(youX, youY, m);
	}

	/** The tick point {@code j} of {@code k} stands for, spread evenly over the {@code n} ticks. */
	private static int tick(int j, int k, int n)
	{
		return k <= 1 ? n - 1 : (int) Math.round((double) j * (n - 1) / (k - 1));
	}

	/** Points the last drawing used per line; for tests. */
	int points()
	{
		return points;
	}
}

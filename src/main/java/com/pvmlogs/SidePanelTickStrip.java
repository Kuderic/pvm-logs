package com.pvmlogs;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Every tick of the fight (T-036; spec 3.1): 60 to a row (36 s), each 3 × 9
 * px with half a pixel between, rows 4 px apart. An attack is its style's
 * colour, a special its lighter colour, a cooldown tick Track, a lost tick
 * Cost, an eating tick the time budget's neutral eating grey (T-038: eating
 * isn't a cost), an unscored add's tick grey. Ticks the
 * fight didn't count (before the first attack, between phases) are left empty.
 *
 * <p>T-046: the ticks live in an image at the screen's scale, whole-pixel
 * rectangles without anti-aliasing. An update draws only the ticks it brought
 * (and the few behind them it resends); painting copies the image. The image
 * is drawn again from the start only on a new scope, a resize or a new scale.
 */
final class SidePanelTickStrip extends JComponent
{
	static final int PER_ROW = 60;
	static final double TICK = 3;
	static final double GAP = 0.5;
	static final int TICK_HEIGHT = 9;
	static final int ROW_GAP = 4;

	/** The styles by ordinal, and a special's lighter colour per style, made once. */
	private static final FightTimeline.Style[] STYLES = FightTimeline.Style.values();
	private static final Color[] SPECIAL = new Color[STYLES.length];
	private static final Composite CLEAR = AlphaComposite.Clear;
	private static final Composite OVER = AlphaComposite.SrcOver;

	static
	{
		for (FightTimeline.Style s : STYLES)
		{
			SPECIAL[s.ordinal()] = SidePanelModel.styleColour(s, true);
		}
	}

	private final SidePanelChartData data;
	private PerfProbe perf;

	// The image and what's in it: its scale, how many ticks it holds, and the series' version and scope it was drawn from.
	private BufferedImage image;
	private Graphics2D drawing;
	private double scale;
	private AffineTransform unscale;
	private int drawn;
	private int drawnVersion = -1;
	private int drawnScope = -1;
	/** Ticks drawn into the image, for tests: what an update cost. */
	private int ticksDrawn;

	SidePanelTickStrip(SidePanelChartData data)
	{
		this.data = data;
		setOpaque(false);
	}

	void setPerf(PerfProbe perf)
	{
		this.perf = perf;
	}

	static int rows(int ticks)
	{
		return Math.max(1, (ticks + PER_ROW - 1) / PER_ROW);
	}

	@Override
	public Dimension getPreferredSize()
	{
		final int rows = rows(data.length());
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, rows * TICK_HEIGHT + (rows - 1) * ROW_GAP);
	}

	/** The series changed (an update): the new ticks go into the image now. Swing's thread. */
	void update()
	{
		if (image != null && fits(scale))
		{
			final long started = PerfProbe.start(perf);
			sync();
			if (started != 0L)
			{
				perf.stopSwing(PerfProbe.SwingSlot.STRIP_DRAW, started);
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
			sync();
		}
		// Device pixels one to one: the image is at the screen's scale.
		g2.drawImage(image, unscale, null);
		hoverCell(g2);
	}

	/** T-112: the tick under ({@code x}, {@code y}), or -1 between rows or past the last tick. */
	int tickAt(int x, int y)
	{
		final int col = (int) Math.floor(x / (TICK + GAP));
		final int row = (int) Math.floor((double) y / (TICK_HEIGHT + ROW_GAP));
		if (x < 0 || y < 0 || col >= PER_ROW || y - row * (TICK_HEIGHT + ROW_GAP) >= TICK_HEIGHT)
		{
			return -1;
		}
		final int i = row * PER_ROW + col;
		return i < data.length() ? i : -1;
	}

	/** T-112: the hovered tick, shared with the chart, outlined over the cached image. */
	private void hoverCell(Graphics2D g)
	{
		final int i = data.hover();
		if (i < 0)
		{
			return;
		}
		final double x = (i % PER_ROW) * (TICK + GAP);
		final double y = (i / PER_ROW) * (TICK_HEIGHT + ROW_GAP);
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setColor(SidePanelStyle.VALUE);
		g2.setStroke(new java.awt.BasicStroke(1f));
		g2.draw(new java.awt.geom.Rectangle2D.Double(x - 0.5, y - 0.5, TICK + 1, TICK_HEIGHT + 1));
		g2.dispose();
	}

	/** Whether the image is the component's size at scale {@code s}. */
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

	/** A new image at scale {@code s}, every tick drawn into it: a resize, a new scale, or the first paint. */
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
		drawn = 0;
		drawnScope = data.scopes();
		sync();
	}

	/** Draws what changed since the image was last brought up to date. */
	private void sync()
	{
		final int n = data.length();
		int from;
		if (drawnScope != data.scopes())
		{
			// A new scope: the old ticks go.
			drawnScope = data.scopes();
			drawing.setComposite(CLEAR);
			drawing.fillRect(0, 0, image.getWidth(), image.getHeight());
			drawing.setComposite(OVER);
			from = 0;
		}
		else
		{
			// Each snapshot resends the few ticks behind the last one, for a splat that landed late.
			from = Math.max(0, Math.min(drawn, n) - PvmPerformancePanel.CHART_RESEND);
		}
		for (int i = from; i < n; i++)
		{
			draw(i);
		}
		// Ticks past the end (a shorter series on the same scope) are cleared.
		for (int i = n; i < drawn; i++)
		{
			clear(i);
		}
		ticksDrawn += Math.max(0, n - from);
		drawn = n;
		drawnVersion = data.version();
	}

	private void draw(int i)
	{
		clear(i);
		final byte state = data.state(i);
		if (state == FightTimeline.NONE)
		{
			return;
		}
		drawing.setColor(colour(state, data.style(i)));
		fill(i);
	}

	private void clear(int i)
	{
		drawing.setComposite(CLEAR);
		fill(i);
		drawing.setComposite(OVER);
	}

	/** Tick {@code i}'s rectangle in whole device pixels. */
	private void fill(int i)
	{
		final double x = (i % PER_ROW) * (TICK + GAP);
		final double y = (i / PER_ROW) * (TICK_HEIGHT + ROW_GAP);
		final int x0 = (int) Math.round(x * scale);
		final int x1 = (int) Math.round((x + TICK) * scale);
		final int y0 = (int) Math.round(y * scale);
		final int y1 = (int) Math.round((y + TICK_HEIGHT) * scale);
		drawing.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
	}

	/** How many ticks updates have drawn into the image; for tests. */
	int ticksDrawn()
	{
		return ticksDrawn;
	}

	/** A tick's colour by its state, and for an attack its style (spec section 2). */
	static Color colour(byte state, byte style)
	{
		final FightTimeline.Style s = style <= 0 ? FightTimeline.Style.UNKNOWN : STYLES[style - 1];
		switch (state)
		{
			case FightTimeline.ATTACK:
				return SidePanelModel.styleColour(s, false);
			case FightTimeline.SPECIAL:
				return SPECIAL[s.ordinal()];
			case FightTimeline.LOST:
				return SidePanelStyle.COST;
			case FightTimeline.EATING:
				// Neutral since T-038: the plugin can't tell why you ate, so it isn't one of Execution's costs.
				return SidePanelStyle.EATING;
			case FightTimeline.UNSCORED:
				return SidePanelStyle.UNSCORED;
			case FightTimeline.MECHANIC:
				// T-081: neutral, apart from lost and eating: a boss's mechanic forced it.
				return SidePanelStyle.MECHANICS;
			default:
				return SidePanelStyle.TRACK;
		}
	}
}

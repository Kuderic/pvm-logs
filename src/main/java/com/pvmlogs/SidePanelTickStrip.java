package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;

/**
 * Every tick of the fight (T-036; spec 3.1): 60 to a row (36 s), each 3 × 9
 * px with half a pixel between, rows 4 px apart. An attack is its style's
 * colour, a special its lighter colour, a cooldown tick Track, a lost tick
 * Cost, an eating tick Cost stripes, an unscored add's tick grey. Ticks the
 * fight didn't count (before the first attack, between phases) are left empty.
 */
final class SidePanelTickStrip extends JComponent
{
	static final int PER_ROW = 60;
	static final double TICK = 3;
	static final double GAP = 0.5;
	static final int TICK_HEIGHT = 9;
	static final int ROW_GAP = 4;

	private final SidePanelChartData data;

	SidePanelTickStrip(SidePanelChartData data)
	{
		this.data = data;
		setOpaque(false);
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

	@Override
	protected void paintComponent(Graphics g)
	{
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int n = data.length();
		for (int i = 0; i < n; i++)
		{
			final byte state = data.state(i);
			if (state == FightTimeline.NONE)
			{
				continue;
			}
			final double x = (i % PER_ROW) * (TICK + GAP);
			final double y = (i / PER_ROW) * (TICK_HEIGHT + ROW_GAP);
			final Rectangle2D.Double r = new Rectangle2D.Double(x, y, TICK, TICK_HEIGHT);
			if (state == FightTimeline.EATING)
			{
				SidePanelParts.stripes(g2, r, SidePanelStyle.COST_STRIPE_GROUND, SidePanelStyle.COST);
				continue;
			}
			g2.setColor(colour(state, data.style(i)));
			g2.fill(r);
		}
		g2.dispose();
	}

	/** A tick's colour by its state, and for an attack its style (spec section 2). */
	static Color colour(byte state, byte style)
	{
		final FightTimeline.Style s = style <= 0 ? FightTimeline.Style.UNKNOWN : FightTimeline.Style.values()[style - 1];
		switch (state)
		{
			case FightTimeline.ATTACK:
				return SidePanelModel.styleColour(s, false);
			case FightTimeline.SPECIAL:
				return SidePanelModel.styleColour(s, true);
			case FightTimeline.LOST:
				return SidePanelStyle.COST;
			case FightTimeline.UNSCORED:
				return SidePanelStyle.UNSCORED;
			default:
				return SidePanelStyle.TRACK;
		}
	}
}

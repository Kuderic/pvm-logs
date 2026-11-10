package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.KillCard;
import com.pvmlogs.PanelSnapshot.Text;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * T-188 (Eric, 2026-11-09: "let's choose A for all of them"): the kill card in place of the Fight tab's title and four
 * tiles, drawn as the design canvas has it. Painted, not laid out: its height is fixed by its state and rows
 * ({@link KillCard#structure()}), so a value changing repaints it and never asks for layout (T-046). The boss icon and
 * the weapon's sprite arrive later and repaint their boxes. Swing's thread only.
 */
final class SidePanelKillCard extends JComponent
{
	// The bands, px at 1×, from the canvas (225 px wide, RuneScape's fonts).
	static final int EDGE = SidePanelStyle.TILE_EDGE;
	static final int PAD_TOP = 6;
	static final int PAD_X = 7;
	static final int PAD_BOTTOM = 7;
	static final int ICON = 22;
	static final int GAP = 8;
	static final int TILE_GAP = 4;
	static final int TILE_PAD_X = 6;
	static final int TILE_PAD_TOP = 4;
	static final int TILE_PAD_BOTTOM = 5;
	static final int SPRITE_W = 36;
	static final int SPRITE_H = 32;
	static final int TIP_ICON = 12;

	/** Measures a tip's width before the card has a graphics of its own: the RuneScape fonts' metrics are fixed. */
	private static final Graphics2D MEASURE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();

	/**
	 * T-189: the kill-end moment, once: {@link #SETTLE} frames of the die rolling to its number, then the grade and PB
	 * chips flash; {@link #FRAMES} frames of {@link #FRAME_MS} ms (0.6 s), one {@link Timer}, nothing per tick.
	 */
	static final int FRAMES = 12;
	static final int SETTLE = 8;
	static final int FRAME_MS = 50;
	/** The numbers the die shows as it rolls, before it settles. */
	private static final int[] ROLLS = {7, 63, 31, 95, 12, 70, 44, 23};
	static final int BADGE_H = 15;

	private KillCard card;
	private final SidePanelIcons icons;
	private javax.swing.Timer timer;
	/** The moment's frame, 0 to {@link #FRAMES} - 1; -1 at rest. */
	private int frame = -1;
	/** Frames stepped, for tests. */
	private int stepped;
	/** Whether the card is on screen; a test can stand in for Swing's {@code isShowing}. */
	java.util.function.BooleanSupplier showing = this::isShowing;
	private BufferedImage weaponImage;
	private int weaponAsked = Integer.MIN_VALUE;
	/** T-196: where the blocks and the speed track were drawn, for their tooltips. */
	private final Rectangle speedBox = new Rectangle();
	private final Rectangle playBox = new Rectangle();
	private final Rectangle luckBox = new Rectangle();
	private final Rectangle fixBox = new Rectangle();
	private final Rectangle speedTrackBox = new Rectangle();
	/** The height last measured, and for which card and text mode: measuring lays the card out without painting it. */
	private KillCard measured;
	private int measuredFor = Integer.MIN_VALUE;
	private int measuredHeight;
	private final List<Rectangle> badgeBoxes = new ArrayList<>(4);
	private int paints;

	SidePanelKillCard(KillCard card, SidePanelIcons icons)
	{
		this.icons = icons == null ? SidePanelIcons.NONE : icons;
		setOpaque(false);
		// Registers the card for tooltips; the text is per tile (getToolTipText(MouseEvent)).
		setToolTipText("");
		set(card);
	}

	/**
	 * The next snapshot's card, same structure: repainted in place, never laid out. The one exception is a tip that
	 * takes another line, which only a kill's own snapshot changes.
	 */
	void set(KillCard c)
	{
		final int before = card == null ? -1 : height();
		card = c;
		if (before >= 0 && before != height())
		{
			revalidate();
		}
		final int asked = c.weaponSprite >= 0 ? -2 - c.weaponSprite : c.weaponItem;
		if (asked != weaponAsked)
		{
			weaponAsked = asked;
			weaponImage = null;
			if (c.weaponSprite >= 0)
			{
				icons.sprite(c.weaponSprite, img -> arrived(asked, img));
			}
			else if (c.weaponItem >= 0)
			{
				icons.item(c.weaponItem, img -> arrived(asked, img));
			}
		}
		if (c.icon != SidePanelBossIcons.NONE)
		{
			SidePanelBossIcons.load(c.icon, this::repaint);
		}
		repaint();
	}

	private void arrived(int asked, BufferedImage img)
	{
		if (asked == weaponAsked)
		{
			weaponImage = img;
			repaint();
		}
	}

	KillCard card()
	{
		return card;
	}

	/** Paints so far, for tests. */
	int paints()
	{
		return paints;
	}

	// ---- the height, from the state and the type's line heights ----

	private static int small()
	{
		return SidePanelStyle.Type.SMALL.lineHeight();
	}

	private static int body()
	{
		return SidePanelStyle.Type.BODY.lineHeight();
	}

	private static int title()
	{
		return SidePanelStyle.Type.TITLE.lineHeight();
	}

	/** The big figure's line: the clock's font, its line cut to the digits' height as the canvas has it. */
	private static int big()
	{
		return SidePanelStyle.Type.CLOCK.lineHeight() - 4;
	}

	static int headerHeight()
	{
		return Math.max(ICON, title() + 2 + small());
	}

	/** T-196: the card's height: its layout run without painting, at the panel's content width. */
	static int height(KillCard c)
	{
		final Graphics2D g = (Graphics2D) MEASURE.create();
		try
		{
			// Nothing drawn: an empty clip.
			g.setClip(0, 0, 0, 0);
			return new SidePanelKillCard.Layout(g, c, SidePanelStyle.CONTENT_WIDTH, false, -1, null).run();
		}
		finally
		{
			g.dispose();
		}
	}

	/** This card's height, measured once per card and text mode. */
	private int height()
	{
		final int mode = SidePanelStyle.Type.SMALL.lineHeight() * 1000 + SidePanelStyle.Type.CLOCK.lineHeight();
		if (measured != card || measuredFor != mode)
		{
			measured = card;
			measuredFor = mode;
			measuredHeight = height(card);
		}
		return measuredHeight;
	}

	static int badgeHeight()
	{
		return Math.max(BADGE_H, small() + 4);
	}

	/** A badge's width: its mark, its name, the padding. */
	static int badgeWidth(PanelSnapshot.Badge b)
	{
		return 5 + 7 + 4 + SidePanelStyle.metrics(MEASURE, SidePanelStyle.Type.SMALL.font()).stringWidth(b.name) + 6;
	}

	/** The rows the badges take, flowed left to right in this width. */
	static int badgeRows(List<PanelSnapshot.Badge> badges, int room)
	{
		int rows = 1;
		int x = 0;
		for (PanelSnapshot.Badge b : badges)
		{
			final int w = badgeWidth(b);
			if (x > 0 && x + w > room)
			{
				rows++;
				x = 0;
			}
			x += w + 4;
		}
		return rows;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, height());
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, height());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, height());
	}

	// ---- tooltips ----

	@Override
	public String getToolTipText(MouseEvent e)
	{
		final KillCard c = card;
		for (int i = 0; i < badgeBoxes.size(); i++)
		{
			if (badgeBoxes.get(i).contains(e.getPoint()) && i < c.badges.size())
			{
				return c.badges.get(i).tip;
			}
		}
		if (speedTrackBox.contains(e.getPoint()))
		{
			return SPEED_TRACK_TIP;
		}
		if (playBox.contains(e.getPoint()))
		{
			return c.live ? "Time off cooldown with no attack so far. Avoidable eating counts once the fight ends."
				: "Your grade, from your play only (never luck), against your own last kills of this boss. "
				+ SidePanelModel.TICK_NOTE;
		}
		if (luckBox.contains(e.getPoint()))
		{
			return c.live ? "Luck rolls with every hit; its number comes when the fight ends."
				: c.luckWords != null ? "<html>" + c.luckWords + "<br>How your damage rolls went against the odds, 1 to"
				+ " 100: luck, not skill.</html>" : "How your damage rolls went against the odds, 1 to 100: luck, not skill.";
		}
		return null;
	}

	/** T-196: what the speed track's ends are. */
	static final String SPEED_TRACK_TIP = "<html>From your best to your slow end: 9 in 10 of your recent kills were faster"
		+ " than the slow end,<br>so one bad kill doesn't squash the track. The short mark is your usual (the median).</html>";

	// ---- painting ----

	@Override
	protected void paintComponent(Graphics g0)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		SidePanelStyle.textHints(g);
		try
		{
			paintCard(g);
		}
		finally
		{
			g.dispose();
		}
		paints++;
	}

	private void paintCard(Graphics2D g)
	{
		badgeBoxes.clear();
		new Layout(g, card, getWidth(), true, frame, this).run();
	}

	/** T-189: a badge: a gold mark and its name, on the panel's ground with a quiet edge. */
	static void paintBadge(Graphics2D g, PanelSnapshot.Badge b, int x, int y, int w)
	{
		final int h = badgeHeight();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(x, y, w, h);
		g.setColor(SidePanelStyle.TRACK);
		g.drawRect(x, y, w - 1, h - 1);
		mark(g, b.mark, x + 5, y + (h - 8) / 2);
		draw(g, Text.small(b.name, SidePanelStyle.BODY), x + 5 + 7 + 4, y + (h - small()) / 2, w - 16);
	}

	/** T-189: each badge's mark, 7 px wide, in gold. */
	static void mark(Graphics2D g, PanelSnapshot.Badge.Mark m, int x, int y)
	{
		g.setColor(SidePanelStyle.GOLD);
		switch (m)
		{
			case FLAWLESS:
				// A shield.
				g.drawRect(x, y, 6, 3);
				g.drawLine(x, y + 4, x + 3, y + 7);
				g.drawLine(x + 6, y + 4, x + 3, y + 7);
				break;
			case CLEAN:
				// A tick.
				g.drawLine(x, y + 4, x + 2, y + 6);
				g.drawLine(x + 2, y + 6, x + 6, y + 1);
				g.drawLine(x, y + 5, x + 2, y + 7);
				g.drawLine(x + 2, y + 7, x + 6, y + 2);
				break;
			case STREAK:
				// A flame.
				g.fillRect(x + 3, y, 1, 2);
				g.fillRect(x + 2, y + 2, 3, 2);
				g.fillRect(x + 1, y + 4, 5, 3);
				g.fillRect(x + 2, y + 7, 3, 1);
				break;
			default:
				// A medal.
				g.fillRect(x + 1, y, 2, 3);
				g.fillRect(x + 4, y, 2, 3);
				g.fillOval(x, y + 2, 7, 6);
				break;
		}
	}

	// ---- T-189: the kill-end moment ----

	/** The kill has just happened on this card: roll the die to its number, then flash the chips. Once. */
	void celebrate()
	{
		if (card.live)
		{
			return;
		}
		stop();
		frame = 0;
		timer = new javax.swing.Timer(FRAME_MS, e -> step());
		timer.start();
		repaint();
	}

	/** One frame; hidden (another tab, the panel closed), the moment ends at once on the settled card. */
	void step()
	{
		if (frame < 0)
		{
			return;
		}
		if (!showing.getAsBoolean())
		{
			stop();
			frame = -1;
			repaint();
			return;
		}
		stepped++;
		frame++;
		if (frame >= FRAMES)
		{
			stop();
			frame = -1;
		}
		repaint();
	}

	private void stop()
	{
		if (timer != null)
		{
			timer.stop();
			timer = null;
		}
	}

	/** Whether the moment is playing, for tests. */
	boolean celebrating()
	{
		return frame >= 0;
	}

	boolean timerRunning()
	{
		return timer != null;
	}

	int stepped()
	{
		return stepped;
	}

	@Override
	public void removeNotify()
	{
		stop();
		frame = -1;
		super.removeNotify();
	}

	/** Rolling: the die's number not yet settled. */
	private boolean rolling()
	{
		return frame >= 0 && frame < SETTLE;
	}

	/** The chips flash once the die has settled. */
	private boolean flashing()
	{
		return frame >= SETTLE;
	}

	// ---- T-196: the blocks, laid out once to measure and again to paint ----

	/**
	 * T-196: the card top to bottom. Run without painting (an empty clip, no owner) it measures the height; run on the
	 * card it paints and notes where the blocks are for their tooltips. One routine, so the two can't disagree.
	 */
	static final class Layout
	{
		static final int RULE_ABOVE = 7;
		static final int RULE_BELOW = 6;
		static final int LINE = 3;
		static final int TRACK_H = 4;
		static final int LUCK_TRACK_H = 8;
		static final int STRIP_H = 8;
		static final int CHIP_PAD = 4;

		private final Graphics2D g;
		private final KillCard c;
		private final int w;
		private final boolean paint;
		private final int frame;
		private final SidePanelKillCard owner;
		private final int left;
		private final int right;
		private int y;

		Layout(Graphics2D g, KillCard c, int w, boolean paint, int frame, SidePanelKillCard owner)
		{
			this.g = g;
			this.c = c;
			this.w = w;
			this.paint = paint;
			this.frame = frame;
			this.owner = owner;
			left = PAD_X;
			right = w - PAD_X;
		}

		private boolean rolling()
		{
			return frame >= 0 && frame < SETTLE;
		}

		private boolean flashing()
		{
			return frame >= SETTLE;
		}

		private int room()
		{
			return right - left;
		}

		int run()
		{
			y = EDGE + PAD_TOP;
			if (paint)
			{
				g.setColor(SidePanelStyle.CARD);
				g.fillRect(0, 0, w, 10_000);
				g.setColor(c.tint);
				g.fillRect(0, 0, w, EDGE);
			}
			header();
			if (c.live)
			{
				rule();
				liveClock();
				rule();
				box(owner == null ? null : owner.playBox, this::livePlay);
				rule();
				box(owner == null ? null : owner.luckBox, this::liveLuck);
				if (c.weapon != null)
				{
					rule();
					weapon();
				}
			}
			else
			{
				rule();
				box(owner == null ? null : owner.speedBox, this::speed);
				rule();
				box(owner == null ? null : owner.playBox, this::play);
				rule();
				box(owner == null ? null : owner.luckBox, this::luck);
				if (c.fix != null)
				{
					rule();
					box(owner == null ? null : owner.fixBox, this::fix);
				}
				badges();
			}
			return y + PAD_BOTTOM;
		}

		/** A block, its box noted for its tooltip. */
		private void box(Rectangle r, Runnable block)
		{
			final int top = y;
			block.run();
			if (r != null)
			{
				r.setBounds(0, top, w, y - top);
			}
		}

		private void rule()
		{
			y += RULE_ABOVE;
			if (paint)
			{
				g.setColor(SidePanelStyle.DIVIDER_ON_CARD);
				g.fillRect(left, y, room(), 1);
			}
			y += 1 + RULE_BELOW;
		}

		/** A block's name, {@code Speed}, in the body's size and grey; a die beside Luck's. */
		private void label(String name, boolean die)
		{
			text(Text.body(name, SidePanelStyle.SECONDARY), left, y, room());
			if (die && paint)
			{
				die(g, right - 10, y + (body() - 12) / 2, 10, 12, SidePanelStyle.LUCK);
			}
			y += body() + LINE;
		}

		private int text(Text t, int x, int top, int room)
		{
			return paint ? draw(g, t, x, top, room) : Math.min(room, width(g, t.type, t.text));
		}

		private int textRight(Text t, int r, int top, int room)
		{
			final int tw = Math.min(room, width(g, t.type, t.text));
			text(t, r - tw, top, tw);
			return tw;
		}

		/** Words wrapped to {@code room}, at most {@code max} lines, small; returns the lines drawn. */
		private int words(String s, Color colour, int x, int room, int max)
		{
			final List<String> lines = wrap(g, s, room, max);
			for (int i = 0; i < lines.size(); i++)
			{
				text(Text.small(lines.get(i), colour), x, y, room);
				y += small() + (i < lines.size() - 1 ? 2 : 0);
			}
			return lines.size();
		}

		// ---- the header ----

		private void header()
		{
			final int nameX = left + ICON + 6;
			if (paint && c.icon != SidePanelBossIcons.NONE)
			{
				SidePanelBossIcons.draw(g, c.icon, left, y + (headerHeight() - ICON) / 2, ICON);
			}
			int cornerW = 0;
			if (c.live)
			{
				final int tw = width(g, SidePanelStyle.Type.SMALL, c.corner);
				textRight(Text.small(c.corner, SidePanelStyle.SECONDARY), right, y, w);
				if (paint)
				{
					g.setColor(SidePanelStyle.DEAD);
					g.fillRect(right - tw - 9, y + small() / 2 - 2, 5, 5);
				}
				cornerW = tw + 10;
			}
			else if (!c.corner.isEmpty())
			{
				cornerW = width(g, SidePanelStyle.Type.SMALL, c.corner) + 6;
				textRight(Text.small(c.corner, SidePanelStyle.SECONDARY), right, y, w);
			}
			text(Text.title(c.boss, c.tint), nameX, y, right - cornerW - nameX);
			text(Text.small(fitted(g, c.sub, c.subShort, right - nameX), SidePanelStyle.SECONDARY), nameX, y + title() + 2,
				right - nameX);
			y += headerHeight();
		}

		// ---- after the kill ----

		/** Speed: how fast was I? The clock, against your best, the track from best to slow end, the rank. */
		private void speed()
		{
			label("Speed", false);
			final int clockW = text(new Text(c.clock, SidePanelStyle.VALUE, SidePanelStyle.Type.CLOCK), left - 1, y - 2,
				room());
			final int sideH = body() + 2 + small();
			final int rowH = Math.max(big(), sideH);
			final int sy = y + (rowH - sideH) / 2;
			final int sideRoom = right - left - clockW - 8;
			if (!c.versus.text.isEmpty())
			{
				if (c.newPb)
				{
					// NEW PB: in the boss's colour, in a box edged with it.
					final int tw = Math.min(sideRoom - 10, width(g, c.versus.type, c.versus.text));
					if (paint)
					{
						g.setColor(flashing() ? c.tint : SidePanelStyle.PANEL);
						g.fillRect(right - tw - 10, sy - 2, tw + 10, body() + 3);
						g.setColor(c.tint);
						g.drawRect(right - tw - 10, sy - 2, tw + 9, body() + 2);
					}
					textRight(flashing() ? new Text(c.versus.text, SidePanelStyle.CARD, c.versus.type) : c.versus,
						right - 5, sy, sideRoom);
				}
				else
				{
					textRight(c.versus, right, sy, sideRoom);
				}
				if (c.versusWhat != null)
				{
					textRight(Text.small(c.versusWhat, SidePanelStyle.SECONDARY), right, sy + body() + 2, sideRoom);
				}
			}
			y += rowH + 5;
			if (c.speedTrack())
			{
				speedTrack();
			}
			if (c.rank != null)
			{
				text(Text.small(c.rank, SidePanelStyle.BODY), left, y, room());
				y += small();
			}
		}

		/** From your best to your slow end, your usual ticked, this kill marked; the ends and the tick named under it. */
		private void speedTrack()
		{
			final double from = c.trackBest;
			final double to = c.trackSlow;
			final int top = y + 2;
			final int span = room() - 2;
			if (paint)
			{
				g.setColor(SidePanelStyle.TRACK);
				g.fillRect(left, top, room(), TRACK_H);
				final int at = left + pos(c.trackClock, from, to, span);
				g.setColor(SidePanelStyle.SPEED_FILL);
				g.fillRect(left, top, Math.max(1, at - left), TRACK_H);
				if (!Double.isNaN(c.trackUsual))
				{
					g.setColor(SidePanelStyle.SECONDARY);
					g.fillRect(left + pos(c.trackUsual, from, to, span), top - 2, 1, TRACK_H + 4);
				}
				g.setColor(SidePanelStyle.VALUE);
				g.fillRect(at, top - 3, 2, TRACK_H + 6);
			}
			if (owner != null)
			{
				owner.speedTrackBox.setBounds(left, y, room(), TRACK_H + 4 + small() + 2);
			}
			y += TRACK_H + 6;
			final String best = "best " + SidePanelModel.clockOf(from);
			final String slow = "slow " + SidePanelModel.clockOf(to);
			final int bw = text(Text.small(best, SidePanelStyle.SECONDARY), left, y, room());
			final int sw = textRight(Text.small(slow, SidePanelStyle.SECONDARY), right, y, room());
			if (!Double.isNaN(c.trackUsual))
			{
				// The usual's name under its tick, where it clears both ends.
				final String usual = "usual " + SidePanelModel.clockOf(c.trackUsual);
				final int uw = width(g, SidePanelStyle.Type.SMALL, usual);
				final int ux = Math.max(left + bw + 6, Math.min(right - sw - 6 - uw,
					left + pos(c.trackUsual, from, to, span) - uw / 2));
				if (ux >= left + bw + 6 && ux + uw <= right - sw - 6)
				{
					text(Text.small(usual, SidePanelStyle.SECONDARY), ux, y, uw);
				}
			}
			y += small() + 4;
		}

		private int pos(double v, double from, double to, int span)
		{
			final double f = Math.max(0, Math.min(1, (v - from) / (to - from)));
			return (int) Math.round(f * span);
		}

		/** Your play: how well did I play? The grade, the share of a perfect fight, the loss, the zones. */
		private void play()
		{
			label("Your play", false);
			int textX = left;
			final int top = y;
			if (c.grade != null)
			{
				// The grade as the hero chip, edged in its colour; it waits for the die at the kill.
				final int gw = width(g, SidePanelStyle.Type.CLOCK, "S") + 8;
				final int gh = big() + 4;
				if (paint)
				{
					final boolean flash = flashing();
					g.setColor(flash ? c.gradeColour : SidePanelStyle.PANEL);
					g.fillRect(left, top, gw, gh);
					g.setColor(c.gradeColour);
					g.drawRect(left, top, gw - 1, gh - 1);
					final String shown = rolling() ? "·" : c.grade;
					final int tw = width(g, SidePanelStyle.Type.CLOCK, shown);
					draw(g, new Text(shown, flash ? SidePanelStyle.CARD : rolling() ? SidePanelStyle.MUTED : c.gradeColour,
						SidePanelStyle.Type.CLOCK), left + (gw - tw) / 2, top, gw);
				}
				textX = left + gw + 6;
			}
			final int textRoom = right - textX;
			int ty = top;
			if (c.play != null)
			{
				text(Text.body(c.play, SidePanelStyle.VALUE), textX, ty, textRoom);
				ty += body() + 1;
				final int saved = y;
				y = ty;
				words(SidePanelModel.PERFECT, SidePanelStyle.SECONDARY, textX, textRoom, 3);
				ty = y;
				y = saved;
			}
			y = Math.max(ty, c.grade != null ? top + big() + 4 : ty) + 4;
			if (c.lost != null)
			{
				if (paint)
				{
					g.setColor(SidePanelStyle.COST);
					g.fillRect(left, y + (small() - 7) / 2, 7, 7);
				}
				final int lx = left + 7 + 4;
				final int lw = text(Text.small(c.lost, SidePanelStyle.COST), lx, y, right - lx);
				if (c.lostWhy != null)
				{
					final int wx = lx + lw + 4;
					if (width(g, SidePanelStyle.Type.SMALL, c.lostWhy) <= right - wx)
					{
						text(Text.small(c.lostWhy, SidePanelStyle.SECONDARY), wx, y, right - wx);
					}
					else
					{
						y += small() + 2;
						words(c.lostWhy, SidePanelStyle.SECONDARY, lx, right - lx, 2);
						y -= small();
					}
				}
				y += small() + 5;
			}
			else if (c.play != null)
			{
				text(Text.small("no time lost", SidePanelStyle.MUTED), left, y, room());
				y += small() + 5;
			}
			if (!Double.isNaN(c.share))
			{
				zones();
			}
			if (c.better != null)
			{
				words(c.better, SidePanelStyle.BODY, left, room(), 2);
				y += 2;
			}
			if (c.next != null)
			{
				words(c.next, SidePanelStyle.SECONDARY, left, room(), 2);
			}
		}

		/** Your last 50 kills in C, B, A and S (10/60/25/5%), your marker where this one sits. */
		private void zones()
		{
			final int h = small() + 2;
			final double[] widths = {0.10, 0.60, 0.25, 0.05};
			final String[] letters = {"C", "B", "A", "S"};
			final Color[] fills = {SidePanelStyle.ZONE_C, SidePanelStyle.ZONE_B, SidePanelStyle.ZONE_A, SidePanelStyle.ZONE_S};
			if (paint)
			{
				int x = left;
				double acc = 0;
				for (int i = 0; i < 4; i++)
				{
					acc += widths[i];
					final int end = i == 3 ? right : left + (int) Math.round(acc * room());
					g.setColor(fills[i]);
					g.fillRect(x, y, end - x, h);
					final int tw = width(g, SidePanelStyle.Type.SMALL, letters[i]);
					if (end - x >= tw + 2)
					{
						draw(g, Text.small(letters[i], SidePanelStyle.VALUE), x + (end - x - tw) / 2, y + 1, end - x);
					}
					x = end;
				}
				final int at = left + (int) Math.round(Math.max(0, Math.min(1, c.share)) * (room() - 2));
				g.setColor(SidePanelStyle.CARD);
				g.fillRect(at - 1, y - 3, 4, h + 6);
				g.setColor(SidePanelStyle.VALUE);
				g.fillRect(at, y - 3, 2, h + 6);
			}
			y += h + 4;
			text(Text.small("your worst", SidePanelStyle.SECONDARY), left, y, room());
			textRight(Text.small("your best", SidePanelStyle.SECONDARY), right, y, room());
			y += small() + 4;
		}

		/** Luck: how lucky was I? The roll with its strength, its 1 to 100 track, what it did in words. */
		private void luck()
		{
			label("Luck", true);
			if (c.rolled > 0)
			{
				final int roll = rolling() ? ROLLS[Math.max(0, frame) % ROLLS.length] : c.rolled;
				final SidePanelModel.LuckStrength strength = SidePanelModel.strength(c.rolled);
				final Color ink = SidePanelModel.ink(strength);
				final Color shown = rolling() ? SidePanelStyle.alpha(ink, 0.6) : ink;
				final int pw = text(Text.title("rolled " + roll + " of 100", shown), left, y, room());
				// The strength as a chip, in T-194's: dim, normal or bright.
				final String word = SidePanelModel.strengthWord(strength);
				final int cw = width(g, SidePanelStyle.Type.SMALL, word) + 2 * CHIP_PAD;
				final int cx = left + pw + 6;
				if (cx + cw <= right && !rolling())
				{
					final double p = c.rolled / 100.0;
					if (paint)
					{
						g.setColor(SidePanelModel.luckChipFill(p));
						g.fillRect(cx, y + (title() - small() - 2) / 2, cw, small() + 2);
					}
					text(Text.small(word, SidePanelModel.luckChipInk(p)), cx + CHIP_PAD, y + (title() - small()) / 2, cw);
				}
				y += title() + 5;
				luckTrack(roll, shown);
			}
			else if (c.luckNote != null)
			{
				text(Text.small(c.luckNote, SidePanelStyle.MUTED), left, y, room());
				y += small() + 4;
			}
			if (c.luckSentence != null)
			{
				words(c.luckSentence, SidePanelStyle.BODY, left, room(), 3);
				y += 2;
			}
			if (c.averageLuck != null)
			{
				words(c.averageLuck, SidePanelStyle.SECONDARY, left, room(), 2);
			}
			if (c.newBestAverageLuck)
			{
				// The gold star, only on a kill that beats your best with average luck.
				y += 3;
				if (paint)
				{
					star(g, left, y + (small() - 7) / 2, SidePanelStyle.GOLD);
				}
				final String s = "new best with average luck" + (c.averageLuckWas == null ? "" : " · " + c.averageLuckWas);
				text(Text.small(s, SidePanelStyle.GOLD), left + 7 + 4, y, right - left - 11);
				y += small();
			}
		}

		/** The 1 to 100 track in its three strengths, the roll marked; its ends and middle named under it. */
		private void luckTrack(int roll, Color ink)
		{
			if (paint)
			{
				final int a = left + (int) Math.round(0.25 * room());
				final int b = left + (int) Math.round(0.75 * room());
				g.setColor(SidePanelStyle.LUCK_TRACK_DIM);
				g.fillRect(left, y, a - left, LUCK_TRACK_H);
				g.setColor(SidePanelStyle.LUCK_TRACK_NORMAL);
				g.fillRect(a, y, b - a, LUCK_TRACK_H);
				g.setColor(SidePanelStyle.LUCK_TRACK_BRIGHT);
				g.fillRect(b, y, right - b, LUCK_TRACK_H);
				final int at = left + (int) Math.round((Math.max(1, Math.min(100, roll)) - 1) / 99.0 * (room() - 2));
				g.setColor(SidePanelStyle.CARD);
				g.fillRect(at - 1, y - 3, 4, LUCK_TRACK_H + 6);
				g.setColor(SidePanelStyle.VALUE);
				g.fillRect(at, y - 3, 2, LUCK_TRACK_H + 6);
			}
			y += LUCK_TRACK_H + 5;
			final int lw = text(Text.small("1 unlucky", SidePanelStyle.SECONDARY), left, y, room());
			final int rw = textRight(Text.small("lucky 100", SidePanelStyle.SECONDARY), right, y, room());
			final int mw = width(g, SidePanelStyle.Type.SMALL, "average");
			final int mx = left + (room() - mw) / 2;
			if (mx >= left + lw + 4 && mx + mw <= right - rw - 4)
			{
				text(Text.small("average", SidePanelStyle.SECONDARY), mx, y, mw);
			}
			y += small() + 5;
		}

		/** Fix next: what should I fix? One sentence from the biggest loss. */
		private void fix()
		{
			label("Fix next", false);
			if (paint)
			{
				g.setColor(SidePanelStyle.COST);
				g.fillRect(left, y, TIP_ICON, TIP_ICON);
				g.setColor(SidePanelStyle.CARD);
				g.fillRect(left + 5, y + 2, 2, 5);
				g.fillRect(left + 5, y + 8, 2, 2);
			}
			final int tx = left + TIP_ICON + 6;
			final int top = y;
			words(c.fix, SidePanelStyle.BODY, tx, right - tx, 3);
			y = Math.max(y, top + TIP_ICON);
		}

		private void badges()
		{
			if (c.badges.isEmpty())
			{
				return;
			}
			y += 9;
			int x = left;
			for (PanelSnapshot.Badge b : c.badges)
			{
				final int bw = badgeWidth(b);
				if (x > left && x + bw > right)
				{
					x = left;
					y += badgeHeight() + 4;
				}
				if (paint)
				{
					paintBadge(g, b, x, y, bw);
				}
				if (owner != null)
				{
					owner.badgeBoxes.add(new Rectangle(x, y, bw, badgeHeight()));
				}
				x += bw + 4;
			}
			y += badgeHeight();
		}

		// ---- live ----

		private void liveClock()
		{
			text(new Text(c.clock, SidePanelStyle.VALUE, SidePanelStyle.Type.CLOCK), left - 1, y - 2, room());
			textRight(Text.small("fight time", SidePanelStyle.SECONDARY), right, y + (big() - small()) / 2, room());
			y += big();
		}

		/** Your play so far: idle time against the clock, the share attacking, the fight so far with its idle moments. */
		private void livePlay()
		{
			label("Your play so far", false);
			final int baseline = y + body() - g.getFontMetrics(SidePanelStyle.Type.TITLE.font()).getDescent();
			int x = left;
			x += drawOn(Text.small("idle", SidePanelStyle.SECONDARY), x, baseline) + 4;
			final boolean anyIdle = c.idle != null && !c.idle.equals("0 s");
			x += drawOn(Text.title(c.idle == null ? "0 s" : c.idle, anyIdle ? SidePanelStyle.COST : SidePanelStyle.VALUE),
				x, baseline) + 4;
			drawOn(Text.small(c.idleOf == null ? "" : c.idleOf, SidePanelStyle.SECONDARY), x, baseline);
			if (c.play != null)
			{
				textRight(Text.small(c.play, SidePanelStyle.BODY), right, y + body() - small(), room());
			}
			y += body() + 4;
			if (c.strip != null && c.strip.length > 0)
			{
				if (paint)
				{
					g.setColor(SidePanelStyle.KEPT);
					g.fillRect(left, y, room(), STRIP_H);
					final int n = c.strip.length;
					for (int i = 0; i < n; i++)
					{
						if (c.strip[i] == 0)
						{
							continue;
						}
						final int x0 = left + (int) ((long) i * room() / n);
						final int x1 = Math.max(x0 + 2, left + (int) ((long) (i + 1) * room() / n));
						g.setColor(c.strip[i] == KillCard.STRIP_IDLE ? SidePanelStyle.COST : SidePanelStyle.EATING);
						g.fillRect(x0, y, Math.min(x1, right) - x0, STRIP_H);
					}
				}
				y += STRIP_H + 3;
				text(Text.small("0:00", SidePanelStyle.SECONDARY), left, y, room());
				textRight(Text.small("now", SidePanelStyle.SECONDARY), right, y, room());
				y += small();
			}
		}

		/** Text on a baseline, for a line of mixed sizes; returns its width. */
		private int drawOn(Text t, int x, int baseline)
		{
			if (paint)
			{
				g.setFont(t.type.font());
				g.setColor(t.colour);
				g.drawString(t.text, x, baseline);
			}
			return width(g, t.type, t.text);
		}

		/** Luck so far: its strength in words, no number; the damage against average rolls. */
		private void liveLuck()
		{
			label("Luck so far", true);
			final int lw = text(c.luckSoFar, left, y, room());
			textRight(Text.small("roll at the kill", SidePanelStyle.SECONDARY), right,
				y + lineHeight(c.luckSoFar.type) - small(), right - left - lw - 6);
			y += lineHeight(c.luckSoFar.type) + 3;
			if (c.dealt != null)
			{
				words(c.dealt, SidePanelStyle.BODY, left, room(), 2);
			}
		}

		/** The weapon in hand: its sprite, its name, its damage a second. */
		private void weapon()
		{
			label("Weapon", false);
			if (paint)
			{
				final BufferedImage img = owner == null ? null : owner.weaponImage;
				if (img != null)
				{
					final int iw = Math.min(SPRITE_W, img.getWidth());
					final int ih = Math.min(SPRITE_H, img.getHeight());
					g.drawImage(img, left + (SPRITE_W - iw) / 2, y + (SPRITE_H - ih) / 2, iw, ih, null);
				}
				else
				{
					g.setColor(SidePanelStyle.PANEL);
					g.fillRect(left, y, SPRITE_W, SPRITE_H);
				}
			}
			final int x = left + SPRITE_W + 7;
			final int top = y + (SPRITE_H - body() - 3 - small()) / 2;
			text(Text.body(c.weapon, SidePanelStyle.VALUE), x, top, right - x);
			if (c.weaponRate != null)
			{
				text(Text.small(c.weaponRate, SidePanelStyle.SECONDARY), x, top + body() + 3, right - x);
			}
			y += SPRITE_H;
		}
	}

	// ---- shapes ----

	/** A die, outlined in its colour: an eight-sided one, as the canvas draws it. */
	static void die(Graphics2D g, int x, int y, int w, int h, Color colour)
	{
		g.setColor(colour);
		final int mx = x + w / 2;
		final int my = y + h * 7 / 18;
		final int[] px = {mx, x + w - 1, mx, x};
		final int[] py = {y, my, y + h - 1, my};
		g.drawPolygon(px, py, 4);
		final int cy = y + h * 10 / 18;
		g.drawLine(x, my, mx, cy);
		g.drawLine(mx, cy, x + w - 1, my);
		g.drawLine(mx, cy, mx, y + h - 1);
	}

	/** The star, 7 px: gold, or the card's dark while it flashes. */
	static void star(Graphics2D g, int x, int y, Color colour)
	{
		g.setColor(colour);
		g.fillRect(x + 3, y, 1, 2);
		g.fillRect(x, y + 2, 7, 1);
		g.fillRect(x + 1, y + 3, 5, 1);
		g.fillRect(x + 2, y + 4, 3, 1);
		g.fillRect(x + 1, y + 5, 2, 1);
		g.fillRect(x + 4, y + 5, 2, 1);
		g.fillRect(x + 1, y + 6, 1, 1);
		g.fillRect(x + 5, y + 6, 1, 1);
	}

	// ---- text ----

	private static int lineHeight(SidePanelStyle.Type t)
	{
		return t == SidePanelStyle.Type.CLOCK ? big() : t.lineHeight();
	}

	private static int width(Graphics2D g, SidePanelStyle.Type t, String s)
	{
		return g.getFontMetrics(t.font()).stringWidth(s);
	}

	/** Draws a text with its line's top at {@code top}, cut with "…" to {@code room}; returns the width drawn. */
	private static int draw(Graphics2D g, Text t, int x, int top, int room)
	{
		if (t == null || t.text.isEmpty() || room <= 0)
		{
			return 0;
		}
		final FontMetrics fm = g.getFontMetrics(t.type.font());
		final String shown = LedgerRows.cut(t.text, fm::stringWidth, room);
		g.setFont(t.type.font());
		g.setColor(t.colour);
		final int baseline = top + lineHeight(t.type) - fm.getDescent() + (t.type == SidePanelStyle.Type.CLOCK ? 2 : 0);
		g.drawString(shown, x, baseline);
		return fm.stringWidth(shown);
	}

	/** Draws a text right-aligned to {@code right}; returns its width. */
	private static int drawRight(Graphics2D g, Text t, int right, int top, int room)
	{
		final int tw = Math.min(room, width(g, t.type, t.text));
		return draw(g, t, right - tw, top, tw);
	}

	/** The first of a text and its shorter forms that fits. */
	private static String fitted(Graphics2D g, String text, List<String> shorter, int room)
	{
		if (width(g, SidePanelStyle.Type.SMALL, text) <= room)
		{
			return text;
		}
		for (String s : shorter)
		{
			if (width(g, SidePanelStyle.Type.SMALL, s) <= room)
			{
				return s;
			}
		}
		return text;
	}

	/**
	 * A line that doesn't fit drops its tail: a basis its {@code of last N} ({@code top 12% of last 50} → {@code top
	 * 12%}), live luck its {@code so far}.
	 */
	private static Text fit(Graphics2D g, Text t, int room)
	{
		if (t.text.isEmpty() || width(g, t.type, t.text) <= room)
		{
			return t;
		}
		final String shorter = t.text.replaceFirst(" of (last )?\\d+$", "").replaceFirst(" so far$", "");
		return new Text(shorter, t.colour, t.type);
	}

	/** The words on at most {@code max} lines of {@code room}; what's left goes on the last, cut with "…". */
	static List<String> wrap(Graphics2D g, String text, int room, int max)
	{
		final FontMetrics fm = g.getFontMetrics(SidePanelStyle.Type.SMALL.font());
		final List<String> lines = new ArrayList<>(max);
		final String[] words = text.split(" ");
		String line = "";
		int i = 0;
		for (; i < words.length; i++)
		{
			final String next = line.isEmpty() ? words[i] : line + " " + words[i];
			if (line.isEmpty() || fm.stringWidth(next) <= room)
			{
				line = next;
				continue;
			}
			if (lines.size() == max - 1)
			{
				break;
			}
			lines.add(line);
			line = words[i];
		}
		final StringBuilder last = new StringBuilder(line);
		for (int k = i; k < words.length; k++)
		{
			last.append(' ').append(words[k]);
		}
		lines.add(LedgerRows.cut(last.toString(), fm::stringWidth, room));
		return lines;
	}
}

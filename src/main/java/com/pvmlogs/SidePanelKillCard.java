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
 * ({@link KillCard#structure()}), so a value changing repaints it and never asks for layout (T-046). The boss icon
 * arrives later and repaints its box. Swing's thread only.
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
	/** T-196: where the blocks and the speed track were drawn, for their tooltips. */
	private final Rectangle speedBox = new Rectangle();
	private final Rectangle playBox = new Rectangle();
	private final Rectangle luckBox = new Rectangle();
	/** T-198: the grade chip's box as painted, for its picture test. */
	final Rectangle gradeBox = new Rectangle();
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
		if (c.icon != SidePanelBossIcons.NONE)
		{
			SidePanelBossIcons.load(c.icon, this::repaint);
		}
		repaint();
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

	/** T-198: the card's blocks by their names, top to bottom, for tests. */
	static List<String> blocks(KillCard c)
	{
		final Graphics2D g = (Graphics2D) MEASURE.create();
		try
		{
			g.setClip(0, 0, 0, 0);
			final Layout layout = new Layout(g, c, SidePanelStyle.CONTENT_WIDTH, false, -1, null);
			layout.labels = new ArrayList<>(4);
			layout.run();
			return layout.labels;
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
				return SidePanelStyle.wrapTip(c.badges.get(i).tip);
			}
		}
		if (speedBox.contains(e.getPoint()))
		{
			return SidePanelStyle.wrapTip(c.live ? "Fight time so far." : c.timeTip);
		}
		if (playBox.contains(e.getPoint()))
		{
			return SidePanelStyle.wrapTip(c.skillTip);
		}
		if (luckBox.contains(e.getPoint()))
		{
			return SidePanelStyle.wrapTip(c.luckTip);
		}
		return null;
	}

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
		static final int STRIP_H = 8;

		private final Graphics2D g;
		private final KillCard c;
		private final int w;
		private final boolean paint;
		private final int frame;
		private final SidePanelKillCard owner;
		private final int left;
		private final int right;
		private int y;
		/** The blocks' names as they're laid out, for tests; null otherwise. */
		List<String> labels;

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
			rule();
			tiles();
			if (c.live && c.strip != null && c.strip.length > 0)
			{
				strip();
			}
			if (!c.live)
			{
				badges();
			}
			return y + PAD_BOTTOM;
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

		// ---- T-198: three tiles, glance first, words on hover ----

		static final int TILE_GAP_X = 4;
		static final int TILE_PAD = 5;

		/** The tiles' value line: the clock's figure, and room around a chip. */
		static int valueLine()
		{
			return big() + 4;
		}

		static int tileHeight()
		{
			return TILE_PAD_TOP + small() + 2 + valueLine() + 2 + small() + TILE_PAD_BOTTOM;
		}

		/**
		 * Time across the card (the clock wants the room), then Skill and Luck side by side: each a word, a number with
		 * its scale and one short line.
		 */
		private void tiles()
		{
			timeRow();
			y += 6;
			final int tileW = (room() - TILE_GAP_X) / 2;
			final int h = tileHeight();
			final Rectangle skill = new Rectangle(left, y, tileW, h);
			final Rectangle luck = new Rectangle(left + tileW + TILE_GAP_X, y, right - left - tileW - TILE_GAP_X, h);
			if (owner != null)
			{
				owner.playBox.setBounds(skill);
				owner.luckBox.setBounds(luck);
			}
			skillTile(skill);
			luckTile(luck);
			y += h;
		}

		/** Time: the clock, large; your usual beside it; the gold PB chip above that on a new best. */
		private void timeRow()
		{
			final int top = y;
			final int clockW = text(new Text(c.clock, SidePanelStyle.VALUE, SidePanelStyle.Type.CLOCK), left - 1, y - 2,
				room());
			final int sideRoom = right - left - clockW - 8;
			if (c.newPb)
			{
				final int tw = width(g, SidePanelStyle.Type.SMALL, "PB");
				final int cx = right - tw - 6;
				if (paint)
				{
					g.setColor(flashing() ? SidePanelStyle.GOLD : SidePanelStyle.PANEL);
					g.fillRect(cx, y, tw + 6, small() + 2);
					g.setColor(SidePanelStyle.GOLD);
					g.drawRect(cx, y, tw + 5, small() + 1);
				}
				text(Text.small("PB", flashing() ? SidePanelStyle.CARD : SidePanelStyle.GOLD), cx + 3, y + 1, tw);
			}
			if (c.usual != null)
			{
				textRight(Text.small("usual " + c.usual, SidePanelStyle.SECONDARY), right, y + big() - small(), sideRoom);
			}
			y += big();
			if (owner != null)
			{
				owner.speedBox.setBounds(0, top, w, y - top);
			}
		}

		/** A tile's ground and its one-word name; returns the value line's top. */
		private int tile(Rectangle r, String name)
		{
			if (paint)
			{
				g.setColor(SidePanelStyle.PANEL);
				g.fillRect(r.x, r.y, r.width, r.height);
			}
			if (labels != null)
			{
				labels.add(name);
			}
			text(Text.small(name, SidePanelStyle.SECONDARY), r.x + TILE_PAD, r.y + TILE_PAD_TOP, r.width - 2 * TILE_PAD);
			return r.y + TILE_PAD_TOP + small() + 2;
		}

		/** Skill: the grade chip and the share of a perfect fight (live: attacking); the time lost under it, orange. */
		private void skillTile(Rectangle r)
		{
			final int top = tile(r, "Skill");
			final int x0 = r.x + TILE_PAD;
			final int room = r.width - 2 * TILE_PAD;
			int x = x0;
			if (c.grade != null)
			{
				final int gw = gradeChipWidth(g);
				final int gh = valueLine();
				if (owner != null)
				{
					owner.gradeBox.setBounds(x, top, gw, gh);
				}
				if (paint)
				{
					paintGradeChip(g, c, x, top, gw, gh, rolling(), flashing());
				}
				x += gw + 4;
			}
			if (c.skill != null)
			{
				text(Text.title(c.skill, SidePanelStyle.VALUE), x, top + (valueLine() - title()) / 2, x0 + room - x);
			}
			final Text bottom = c.lost != null ? Text.small(c.lost, SidePanelStyle.COST)
				: c.skill != null ? Text.small("±0 s", SidePanelStyle.MUTED) : Text.NONE;
			text(bottom, x0, top + valueLine() + 2, room);
		}

		/** Luck: the roll in its strength, luck's time beside it, the 1 to 100 track under it; live, one word. */
		private void luckTile(Rectangle r)
		{
			final int top = tile(r, "Luck");
			final int x = r.x + TILE_PAD;
			final int room = r.width - 2 * TILE_PAD;
			if (paint)
			{
				die(g, r.x + r.width - TILE_PAD - 9, r.y + TILE_PAD_TOP + (small() - 11) / 2, 9, 11, SidePanelStyle.LUCK);
			}
			if (c.live || c.rolled <= 0)
			{
				// Live: the die still rolling, and its word so far.
				final Text word = c.luckWord.text.isEmpty() ? Text.small("rolling", SidePanelStyle.SECONDARY) : c.luckWord;
				text(word, x, top + (valueLine() - lineHeight(word.type)) / 2, room);
				return;
			}
			final int roll = rolling() ? ROLLS[Math.max(0, frame) % ROLLS.length] : c.rolled;
			final Color ink = SidePanelModel.ink(SidePanelModel.strength(c.rolled));
			final Color shown = rolling() ? SidePanelStyle.alpha(ink, 0.6) : ink;
			final int nw = text(new Text(String.valueOf(roll), shown, SidePanelStyle.Type.CLOCK), x - 1,
				top + (valueLine() - big()) / 2 - 2, room + 1);
			if (c.luckTime != null && !rolling())
			{
				textRight(Text.small(c.luckTime, SidePanelStyle.LUCK), x + room, top + valueLine() - small() - 1,
					room - nw - 3);
			}
			// The scale: a thin 1 to 100 track in its three strengths, "1" and "100" at its ends, the roll marked.
			final int ly = top + valueLine() + 2;
			final int w1 = text(Text.small("1", SidePanelStyle.SECONDARY), x, ly, room);
			final int w100 = textRight(Text.small("100", SidePanelStyle.SECONDARY), x + room, ly, room);
			final int tx = x + w1 + 3;
			final int tw = room - w1 - w100 - 6;
			if (paint && tw > 4)
			{
				final int ty = ly + small() / 2 - 2;
				final int a = tx + (int) Math.round(0.25 * tw);
				final int b = tx + (int) Math.round(0.75 * tw);
				g.setColor(SidePanelStyle.LUCK_TRACK_DIM);
				g.fillRect(tx, ty, a - tx, 4);
				g.setColor(SidePanelStyle.LUCK_TRACK_NORMAL);
				g.fillRect(a, ty, b - a, 4);
				g.setColor(SidePanelStyle.LUCK_TRACK_BRIGHT);
				g.fillRect(b, ty, tx + tw - b, 4);
				final int at = tx + (int) Math.round((Math.max(1, Math.min(100, roll)) - 1) / 99.0 * (tw - 2));
				g.setColor(SidePanelStyle.VALUE);
				g.fillRect(at, ty - 2, 2, 8);
			}
		}

		/**
		 * T-198 ("the B icon looks broken": the B filled its box and ran into its white edge): the grade chip's width,
		 * the widest of S, A, B and C in the title's font and room either side, so no letter touches the edge.
		 */
		static int gradeChipWidth(Graphics2D g)
		{
			int widest = 0;
			for (String letter : new String[]{"S", "A", "B", "C"})
			{
				widest = Math.max(widest, width(g, SidePanelStyle.Type.TITLE, letter));
			}
			return widest + 2 * GRADE_PAD;
		}

		static final int GRADE_PAD = 4;

		/** The grade chip: edged in its colour, its letter centred on its own drawn shape; it waits for the die. */
		static void paintGradeChip(Graphics2D g, KillCard c, int x, int y, int w, int h, boolean rolling, boolean flash)
		{
			g.setColor(flash ? c.gradeColour : SidePanelStyle.CARD);
			g.fillRect(x, y, w, h);
			g.setColor(c.gradeColour);
			g.drawRect(x, y, w - 1, h - 1);
			final String shown = rolling ? "·" : c.grade;
			final java.awt.Font font = SidePanelStyle.Type.TITLE.font();
			final java.awt.geom.Rectangle2D shape = font.createGlyphVector(g.getFontRenderContext(), shown).getVisualBounds();
			g.setFont(font);
			g.setColor(flash ? SidePanelStyle.CARD : rolling ? SidePanelStyle.MUTED : c.gradeColour);
			g.drawString(shown, (float) (x + (w - shape.getWidth()) / 2 - shape.getX()),
				(float) (y + (h - shape.getHeight()) / 2 - shape.getY()));
		}

		/** The first of a text and its short form that fits. */
		private String fitWords(String text, String shorter, int room)
		{
			return width(g, SidePanelStyle.Type.SMALL, text) <= room ? text : shorter;
		}

		/** Live: the fight so far, a thin bar, its idle moments orange. */
		private void strip()
		{
			y += 6;
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
			if (owner != null)
			{
				// The strip's words are Skill's.
				owner.playBox.add(new Rectangle(left, y, room(), STRIP_H));
			}
			y += STRIP_H;
		}

		/** The badges as icon chips: their names and what they read on hover. */
		private void badges()
		{
			if (c.badges.isEmpty())
			{
				return;
			}
			y += 6;
			int x = left;
			final int size = badgeHeight();
			for (PanelSnapshot.Badge b : c.badges)
			{
				if (paint)
				{
					g.setColor(SidePanelStyle.PANEL);
					g.fillRect(x, y, size, size);
					g.setColor(SidePanelStyle.TRACK);
					g.drawRect(x, y, size - 1, size - 1);
					mark(g, b.mark, x + (size - 7) / 2, y + (size - 8) / 2);
				}
				if (labels != null)
				{
					labels.add(b.name);
				}
				if (owner != null)
				{
					owner.badgeBoxes.add(new Rectangle(x, y, size, size));
				}
				x += size + 4;
			}
			y += size;
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

package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * T-186's two prototype directions, test scope only, drawn from {@link SidePanelFun.Facts}:
 * <ul>
 * <li>{@link KillCard} (direction A): one card in place of the title and the four tiles: the clock big, against
 * your PB; the skill grade from execution; luck as a rolled percentile, apart from it; the one part that cost most;
 * the badges earned. Live, the same card holds the clock against your PB and usual, and execution and luck so far.</li>
 * <li>{@link Strip} (direction B): today's layout kept, one card under the title: PB, usual, the grade chip and luck's
 * roll.</li>
 * </ul>
 * Painted, not laid out: a fixed height, so the clock and the count-up change pixels and never the layout. The
 * count-up is one {@link Timer}, {@link #COUNT_UP_MS} long, stopped when it ends or the card stops showing.
 */
final class SidePanelFunCard
{
	private SidePanelFunCard()
	{
	}

	static final int WIDTH = SidePanelStyle.CONTENT_WIDTH;
	/** The count-up's length and frame: 20 frames over 0.6 s, then nothing. */
	static final int COUNT_UP_MS = 600;
	static final int FRAME_MS = 30;

	static final Color S_INK = new Color(0xF2C14E);
	static final Color S_FILL = new Color(0x4A3B12);
	/** Luck's own colour, apart from skill's: a dice blue. */
	static final Color LUCK = new Color(0x8FB3D9);

	static Color gradeInk(SidePanelFun.Grade g)
	{
		switch (g)
		{
			case S:
				return S_INK;
			case A:
				return SidePanelStyle.BETTER;
			case B:
				return SidePanelStyle.VALUE;
			default:
				return SidePanelStyle.SECONDARY;
		}
	}

	static Color gradeFill(SidePanelFun.Grade g)
	{
		switch (g)
		{
			case S:
				return S_FILL;
			case A:
				return SidePanelStyle.CHIP_BETTER;
			default:
				return SidePanelStyle.CHIP_NEUTRAL;
		}
	}

	/** {@code 80th}, {@code 1st}, {@code 22nd}. */
	static String ordinal(double share)
	{
		final int p = (int) Math.max(1, Math.min(99, Math.round(share * 100)));
		final int t = p % 100;
		final String s = t >= 11 && t <= 13 ? "th" : p % 10 == 1 ? "st" : p % 10 == 2 ? "nd" : p % 10 == 3 ? "rd" : "th";
		return p + s;
	}

	/** {@code +21 s}, {@code -3 s}. */
	static String delta(double seconds)
	{
		final long r = LedgerRows.roundHalfAway(seconds);
		return (r > 0 ? "+" : r < 0 ? "-" : "") + Math.abs(r) + " s";
	}

	/** The parts both directions share. */
	abstract static class Card extends JComponent
	{
		SidePanelFun.Facts facts;
		final String boss;
		final Color tint;
		/** The count-up's progress, 0 to 1; 1 when still. */
		double shown = 1;
		private Timer timer;
		private long started;
		int frames;

		Card(String boss, SidePanelFun.Facts facts)
		{
			this.boss = boss;
			this.facts = facts;
			this.tint = new Color(BossColours.of(boss));
			setOpaque(false);
		}

		void set(SidePanelFun.Facts f)
		{
			facts = f;
			repaint();
		}

		/** The kill's moment: count the clock up and flash the grade, once. */
		void countUp()
		{
			stop();
			shown = 0;
			started = System.nanoTime();
			timer = new Timer(FRAME_MS, e -> frame());
			timer.start();
		}

		void frame()
		{
			// Hidden (another tab, the panel closed, the client minimised): stop, and show the end.
			if (!isShowing())
			{
				stop();
				shown = 1;
				return;
			}
			frames++;
			shown = Math.min(1, (System.nanoTime() - started) / 1e6 / COUNT_UP_MS);
			if (shown >= 1)
			{
				stop();
			}
			repaint();
		}

		void stop()
		{
			if (timer != null)
			{
				timer.stop();
				timer = null;
			}
		}

		boolean counting()
		{
			return timer != null;
		}

		@Override
		public void removeNotify()
		{
			stop();
			shown = 1;
			super.removeNotify();
		}

		static Graphics2D pixels(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			return g2;
		}

		static int text(Graphics2D g, SidePanelStyle.Type type, String s, Color c, int x, int baseline)
		{
			g.setFont(type.font());
			g.setColor(c);
			g.drawString(s, x, baseline);
			return g.getFontMetrics().stringWidth(s);
		}

		static int width(Graphics2D g, SidePanelStyle.Type type, String s)
		{
			return g.getFontMetrics(type.font()).stringWidth(s);
		}

		static void right(Graphics2D g, SidePanelStyle.Type type, String s, Color c, int rightX, int baseline)
		{
			text(g, type, s, c, rightX - width(g, type, s), baseline);
		}

		static int ascent(Graphics2D g, SidePanelStyle.Type type)
		{
			final FontMetrics m = g.getFontMetrics(type.font());
			return m.getAscent();
		}

		/** The grade's chip: its letter in a square, {@code size} px, flashed white while counting. */
		void chip(Graphics2D g, SidePanelFun.Grade grade, int x, int y, int size, SidePanelStyle.Type type)
		{
			final boolean flash = shown < 1 && shown > 0.6;
			g.setColor(flash ? SidePanelStyle.towardWhite(gradeFill(grade), 0.35) : gradeFill(grade));
			g.fillRoundRect(x, y, size, size, 4, 4);
			g.setColor(gradeInk(grade));
			g.drawRoundRect(x, y, size - 1, size - 1, 4, 4);
			final String s = grade.name();
			final FontMetrics m = g.getFontMetrics(type.font());
			text(g, type, s, gradeInk(grade), x + (size - m.stringWidth(s)) / 2 + 1,
				y + (size + m.getAscent() - m.getDescent()) / 2);
		}

		/** A die face, {@code size} px, its pips from the luck's percentile: one for a bottom roll, six for a top one. */
		static void die(Graphics2D g, double percentile, int x, int y, int size)
		{
			g.setColor(SidePanelStyle.TRACK);
			g.fillRoundRect(x, y, size, size, 3, 3);
			g.setColor(LUCK);
			g.drawRoundRect(x, y, size - 1, size - 1, 3, 3);
			final int face = Double.isNaN(percentile) ? 3 : (int) Math.max(1, Math.min(6, Math.ceil(percentile * 6)));
			final int[][] at = {{}, {4}, {0, 8}, {0, 4, 8}, {0, 2, 6, 8}, {0, 2, 4, 6, 8}, {0, 2, 3, 5, 6, 8}};
			final int step = (size - 4) / 3;
			for (int p : at[face])
			{
				final int px = x + 2 + (p % 3) * step + (step - 2) / 2;
				final int py = y + 2 + (p / 3) * step + (step - 2) / 2;
				g.fillRect(px, py, 2, 2);
			}
		}

		void card(Graphics2D g, int h)
		{
			g.setColor(SidePanelStyle.CARD);
			g.fillRect(0, 0, getWidth(), h);
			g.setColor(tint);
			g.fillRect(0, 0, getWidth(), SidePanelStyle.TILE_EDGE);
		}
	}

	/** Direction A: the kill card, in place of the title and the tiles. */
	static final class KillCard extends Card
	{
		// The rows' bands, px from the top: each its own, so nothing overlaps whatever the numbers.
		static final int TITLE_TOP = 5;
		static final int CLOCK_TOP = 24;
		/** The clock's block: the clock beside three small lines (PB, usual, rank). */
		static final int CLOCK_BLOCK = 44;
		static final int LIVE_BLOCK = 32;
		static final int BAR = 10;
		static final int CHIP = 26;
		static final int SMALL_ROW = 15;
		static final int TIP_ROW = 18;
		static final int BADGE_ROW = 18;
		private final String when;

		KillCard(String boss, String when, SidePanelFun.Facts facts)
		{
			super(boss, facts);
			this.when = when;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(WIDTH, height(facts));
		}

		static int height(SidePanelFun.Facts f)
		{
			if (f.live)
			{
				return CLOCK_TOP + LIVE_BLOCK + BAR + 6 + CHIP + 3 + SMALL_ROW + 3;
			}
			return CLOCK_TOP + CLOCK_BLOCK + 6 + CHIP + 3 + SMALL_ROW + TIP_ROW + (f.badges.isEmpty() ? 0 : BADGE_ROW) + 4;
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = pixels(g0);
			final int w = getWidth();
			final int padX = SidePanelStyle.CARD_PAD_H;
			final SidePanelFun.Facts f = facts;
			card(g, height(f));
			final int small = SidePanelStyle.Type.SMALL.lineHeight();
			// The boss in its colour; the kill count, or "live", on the right.
			int base = TITLE_TOP + ascent(g, SidePanelStyle.Type.TITLE);
			text(g, SidePanelStyle.Type.TITLE, boss, tint, padX, base);
			right(g, SidePanelStyle.Type.SMALL, when, SidePanelStyle.SECONDARY, w - padX, base);
			// The clock, big: counted up at the kill.
			int y = CLOCK_TOP;
			text(g, SidePanelStyle.Type.CLOCK, clock(f.clock * shown), SidePanelStyle.VALUE,
				padX - 1, y + SidePanelStyle.Type.CLOCK.lineHeight() - 6);
			// Beside it: against your PB, your usual, and where it ranks.
			final int col = padX + 90;
			final int l1 = y + small - 2;
			final int l2 = l1 + small + 3;
			final int l3 = l2 + small + 3;
			if (!f.live && f.newPb)
			{
				g.setColor(S_FILL);
				g.fillRoundRect(col - 3, l1 - small + 1, width(g, SidePanelStyle.Type.SMALL, "NEW PB") + 6, small + 2, 3, 3);
				final int x = col + 4 + text(g, SidePanelStyle.Type.SMALL, "NEW PB", S_INK, col, l1);
				text(g, SidePanelStyle.Type.SMALL, "was " + clock(f.pb), SidePanelStyle.SECONDARY,
					x, l1);
			}
			else if (!Double.isNaN(f.pb))
			{
				final int x = col + text(g, SidePanelStyle.Type.SMALL, "PB " + clock(f.pb) + "  ",
					SidePanelStyle.SECONDARY, col, l1);
				if (!f.live)
				{
					text(g, SidePanelStyle.Type.SMALL, delta(f.clock - f.pb), SidePanelStyle.COST, x, l1);
				}
			}
			if (!Double.isNaN(f.usual))
			{
				final int x = col + text(g, SidePanelStyle.Type.SMALL, "usual " + SidePanelStyle.roundedDuration(f.usual)
					+ "  ", SidePanelStyle.SECONDARY, col, l2);
				if (!f.live)
				{
					final double d = f.clock - f.usual;
					text(g, SidePanelStyle.Type.SMALL, delta(d), d <= 0 ? SidePanelStyle.BETTER : SidePanelStyle.COST, x, l2);
				}
			}
			if (!f.live && f.ranked > 1)
			{
				text(g, SidePanelStyle.Type.SMALL, ordinalRank(f.rank) + " fastest of " + LedgerRows.number(f.ranked),
					SidePanelStyle.MUTED, col, l3);
			}
			y += f.live ? LIVE_BLOCK : CLOCK_BLOCK;
			if (f.live)
			{
				// Live: your clock along a bar to past your usual, your PB and usual marked. Your own time only.
				final double end = Math.max(Double.isNaN(f.usual) ? f.clock : f.usual, f.clock) * 1.15;
				final int bw = w - 2 * padX;
				g.setColor(SidePanelStyle.TRACK);
				g.fillRect(padX, y + 2, bw, 4);
				g.setColor(SidePanelStyle.SECONDARY);
				g.fillRect(padX, y + 2, (int) Math.round(bw * Math.min(1, f.clock / end)), 4);
				mark(g, f.pb, end, padX, bw, y, S_INK);
				mark(g, f.usual, end, padX, bw, y, SidePanelStyle.VALUE);
				y += BAR;
			}
			// Skill and luck, side by side and apart: the grade from execution, the roll from the dice.
			g.setColor(SidePanelStyle.DIVIDER);
			g.fillRect(padX, y, w - 2 * padX, 1);
			y += 6;
			final int half = w / 2 + 6;
			if (f.grade != null)
			{
				chip(g, f.grade, padX, y, CHIP, SidePanelStyle.Type.TITLE);
				final int tx = padX + CHIP + 6;
				text(g, SidePanelStyle.Type.SMALL, f.live ? "skill so far" : "skill", SidePanelStyle.MUTED, tx, y + small - 3);
				text(g, SidePanelStyle.Type.TITLE, LedgerRows.partPercent(f.execution), SidePanelStyle.VALUE, tx,
					y + CHIP - 2);
			}
			die(g, f.luckPercentile, half, y + 5, 16);
			final int lx = half + 22;
			text(g, SidePanelStyle.Type.SMALL, f.live ? "luck so far" : "luck", SidePanelStyle.MUTED, lx, y + small - 3);
			if (!Double.isNaN(f.luckPercentile))
			{
				text(g, SidePanelStyle.Type.TITLE, ordinal(f.luckPercentile), LUCK, lx, y + CHIP - 2);
			}
			y += CHIP + 3;
			// Under each: what the grade is against; what luck did to the clock.
			if (f.grade != null)
			{
				// "bottom 13% of last 16" where it fits before luck's line, else "bottom 13%".
				final String basis = width(g, SidePanelStyle.Type.SMALL, f.gradeBasis) <= half - padX - 6 ? f.gradeBasis
					: f.gradeBasis.replaceFirst(" of last \\d+$", "");
				text(g, SidePanelStyle.Type.SMALL, basis, SidePanelStyle.MUTED, padX, y + small);
			}
			if (!Double.isNaN(f.luckSeconds))
			{
				final long t = LedgerRows.ticks(Math.abs(f.luckSeconds));
				final String s = LedgerRows.ticksWord(t) + (f.luckSeconds >= 0 ? " saved" : " lost");
				text(g, SidePanelStyle.Type.SMALL, s, f.luckSeconds >= 0 ? SidePanelStyle.BETTER : SidePanelStyle.COST,
					half, y + small);
			}
			y += SMALL_ROW;
			if (f.live)
			{
				g.dispose();
				return;
			}
			// The one part that cost most, in points.
			if (f.tipPart != null && f.tipPoints > 0.0005)
			{
				g.setColor(SidePanelStyle.NOTE_CARD);
				g.fillRect(padX, y + 1, w - 2 * padX, small + 4);
				text(g, SidePanelStyle.Type.SMALL, "Most lost: " + f.tipPart + ", " + points(f.tipPoints) + " points",
					SidePanelStyle.BODY, padX + 4, y + small + 1);
			}
			y += TIP_ROW;
			// Badges: only the rare ones.
			int x = padX;
			for (String b : f.badges)
			{
				final int bw = width(g, SidePanelStyle.Type.SMALL, b) + 8;
				g.setColor(S_FILL);
				g.fillRoundRect(x, y + 1, bw, small + 3, 4, 4);
				text(g, SidePanelStyle.Type.SMALL, b, S_INK, x + 4, y + small + 1);
				x += bw + 4;
			}
			g.dispose();
		}

		private static void mark(Graphics2D g, double at, double end, int x0, int bw, int y, Color c)
		{
			if (Double.isNaN(at))
			{
				return;
			}
			g.setColor(c);
			g.fillRect(x0 + (int) Math.round(bw * Math.min(1, at / end)) - 1, y, 2, 8);
		}
	}

	/** A clock in seconds as the title writes it: whole ticks, {@link LedgerRows#clock(int)}. */
	static String clock(double seconds)
	{
		return LedgerRows.clock((int) Math.round(seconds / LedgerRows.SECONDS_PER_TICK));
	}

	/** {@code 1st}, {@code 42nd}, {@code 113th}. */
	static String ordinalRank(int n)
	{
		final int t = n % 100;
		return LedgerRows.number(n) + (t >= 11 && t <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd"
			: n % 10 == 3 ? "rd" : "th");
	}

	/** {@code 0.6}, {@code 12}: as Execution's sum writes points. */
	static String points(double share)
	{
		final double p = share * 100;
		return p < 10 ? String.format(java.util.Locale.ROOT, "%.1f", p) : String.valueOf(Math.round(p));
	}

	/** Direction B: one card under today's title, the tiles and the rest as they are. */
	static final class Strip extends Card
	{
		static final int HEIGHT = 36;

		Strip(String boss, SidePanelFun.Facts facts)
		{
			super(boss, facts);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(WIDTH, HEIGHT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = pixels(g0);
			final int w = getWidth();
			final int padX = SidePanelStyle.CARD_PAD_H;
			final SidePanelFun.Facts f = facts;
			card(g, HEIGHT);
			final int small = SidePanelStyle.Type.SMALL.lineHeight();
			final int y1 = SidePanelStyle.TILE_EDGE + 3 + small;
			final int y2 = y1 + small + 3;
			// Left: the clock against your PB and usual.
			if (!f.live && f.newPb)
			{
				text(g, SidePanelStyle.Type.SMALL, "NEW PB", S_INK, padX, y1);
			}
			else if (!Double.isNaN(f.pb))
			{
				final int x = padX + text(g, SidePanelStyle.Type.SMALL, "PB " + clock(f.pb) + " ",
					SidePanelStyle.SECONDARY, padX, y1);
				if (!f.live)
				{
					text(g, SidePanelStyle.Type.SMALL, delta(f.clock - f.pb), SidePanelStyle.COST, x, y1);
				}
			}
			if (!f.live && f.ranked > 1)
			{
				text(g, SidePanelStyle.Type.SMALL, ordinalRank(f.rank) + " of " + LedgerRows.number(f.ranked),
					SidePanelStyle.MUTED, padX, y2);
			}
			else if (!Double.isNaN(f.usual))
			{
				text(g, SidePanelStyle.Type.SMALL, "usual " + SidePanelStyle.roundedDuration(f.usual),
					SidePanelStyle.MUTED, padX, y2);
			}
			// Middle: the grade chip and what it's against.
			final int cx = 84;
			if (f.grade != null)
			{
				chip(g, f.grade, cx, SidePanelStyle.TILE_EDGE + 5, 18, SidePanelStyle.Type.TITLE);
				text(g, SidePanelStyle.Type.SMALL, f.live ? "so far" : "skill", SidePanelStyle.MUTED, cx + 22, y1);
				final String top = f.gradeBasis == null ? "" : f.gradeBasis.replaceFirst(" of last \\d+$", "")
					.replaceFirst("^fixed bands.*", "");
				text(g, SidePanelStyle.Type.SMALL, top, SidePanelStyle.SECONDARY, cx + 22, y2);
			}
			// Right: luck's roll.
			die(g, f.luckPercentile, w - padX - 46, SidePanelStyle.TILE_EDGE + 6, 13);
			if (!Double.isNaN(f.luckPercentile))
			{
				right(g, SidePanelStyle.Type.SMALL, ordinal(f.luckPercentile), LUCK, w - padX, y1);
				right(g, SidePanelStyle.Type.SMALL, "luck", SidePanelStyle.MUTED, w - padX, y2);
			}
			g.dispose();
		}
	}
}

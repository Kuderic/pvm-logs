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

	private KillCard card;
	private final SidePanelIcons icons;
	private BufferedImage weaponImage;
	private int weaponAsked = Integer.MIN_VALUE;
	/** Where the skill and luck tiles were drawn, for their tooltips. */
	private final Rectangle skillTile = new Rectangle();
	private final Rectangle luckTile = new Rectangle();
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
		final int before = card == null ? -1 : height(card);
		card = c;
		if (before >= 0 && before != height(c))
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

	/** The clock and the three lines beside it. */
	static int clockHeight(boolean live)
	{
		final int side = live ? body() + 3 + small() : small() + 3 + body() + 3 + small();
		return Math.max(big(), side);
	}

	static int tileHeight(boolean live)
	{
		return TILE_PAD_TOP + body() + 4 + big() + 4 + small() + (live ? 0 : 3 + small()) + TILE_PAD_BOTTOM;
	}

	static int height(KillCard c)
	{
		int h = EDGE + PAD_TOP + headerHeight() + GAP + clockHeight(c.live) + GAP + tileHeight(c.live);
		if (c.live)
		{
			h += GAP + SPRITE_H;
		}
		else
		{
			if (c.luckRemoved != null)
			{
				h += 7 + body();
			}
			if (c.tip != null)
			{
				final int lines = tipLines(c.tip, SidePanelStyle.CONTENT_WIDTH - 2 * PAD_X - TIP_ICON - 6);
				h += 7 + 1 + 6 + Math.max(TIP_ICON, lines * small() + (lines - 1) * 2);
			}
		}
		return h + PAD_BOTTOM;
	}

	/** The tip's lines at this width, one or two, as {@link #wrap} breaks it. */
	static int tipLines(String tip, int room)
	{
		return SidePanelStyle.metrics(MEASURE, SidePanelStyle.Type.SMALL.font()).stringWidth(tip) <= room ? 1 : 2;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH, height(card));
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, height(card));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, height(card));
	}

	// ---- tooltips ----

	@Override
	public String getToolTipText(MouseEvent e)
	{
		if (skillTile.contains(e.getPoint()))
		{
			if (card.live)
			{
				return "Ticks lost so far: off cooldown with no attack. Execution so far, without avoidable eating,"
					+ " which counts once the fight ends.";
			}
			return card.gradeTip != null ? "<html>Your grade, from Execution only, against your own kills of this boss: "
				+ card.gradeTip + ".</html>" : "Your grade, from Execution only, against your own kills of this boss.";
		}
		if (luckTile.contains(e.getPoint()))
		{
			return card.live ? "Luck rolls with every hit; its number comes when the fight ends."
				: "How your damage rolls went against the odds, 1 to 100: luck, not skill. " + SidePanelModel.TICK_NOTE;
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
		final KillCard c = card;
		final int w = getWidth();
		final int h = height(c);
		g.setColor(SidePanelStyle.CARD);
		g.fillRect(0, 0, w, h);
		g.setColor(c.tint);
		g.fillRect(0, 0, w, EDGE);
		final int left = PAD_X;
		final int right = w - PAD_X;
		int y = EDGE + PAD_TOP;

		// The boss: its icon, its name in its colour, the line under it; the kill count or "live" on the right.
		final int nameX = left + ICON + 6;
		if (c.icon != SidePanelBossIcons.NONE)
		{
			SidePanelBossIcons.draw(g, c.icon, left, y + (headerHeight() - ICON) / 2, ICON);
		}
		int cornerW = 0;
		if (c.live)
		{
			final int tw = width(g, SidePanelStyle.Type.SMALL, c.corner);
			drawRight(g, Text.small(c.corner, SidePanelStyle.SECONDARY), right, y, w);
			g.setColor(SidePanelStyle.DEAD);
			g.fillRect(right - tw - 9, y + small() / 2 - 2, 5, 5);
			cornerW = tw + 10;
		}
		else if (!c.corner.isEmpty())
		{
			cornerW = width(g, SidePanelStyle.Type.SMALL, c.corner) + 6;
			drawRight(g, Text.small(c.corner, SidePanelStyle.SECONDARY), right, y, w);
		}
		draw(g, Text.title(c.boss, c.tint), nameX, y, right - cornerW - nameX);
		draw(g, Text.small(fitted(g, c.sub, c.subShort, right - nameX), SidePanelStyle.SECONDARY), nameX,
			y + title() + 2, right - nameX);
		y += headerHeight() + GAP;

		// The clock, big; beside it damage against expected live, your PB, the time against it and the rank after.
		final int clockH = clockHeight(c.live);
		draw(g, new Text(c.clock, SidePanelStyle.VALUE, SidePanelStyle.Type.CLOCK), left - 1,
			y + (clockH - big()) / 2 - 2, right - left);
		int sy = y + (clockH - (c.live ? body() + 3 + small() : small() + 3 + body() + 3 + small())) / 2;
		final List<Text> side = new ArrayList<>(3);
		side.add(c.side1);
		side.add(c.side2);
		if (!c.live)
		{
			side.add(c.side3);
		}
		for (int i = 0; i < side.size(); i++)
		{
			final Text t = side.get(i);
			final int lh = t.type == SidePanelStyle.Type.SMALL ? small() : body();
			if (i == 0 && c.newPb)
			{
				// NEW PB: in the boss's colour, in a box edged with it.
				final int tw = width(g, t.type, t.text);
				g.setColor(SidePanelStyle.PANEL);
				g.fillRect(right - tw - 10, sy - 2, tw + 10, lh + 3);
				g.setColor(c.tint);
				g.drawRect(right - tw - 10, sy - 2, tw + 9, lh + 2);
				drawRight(g, t, right - 5, sy, w);
			}
			else
			{
				drawRight(g, t, right, sy, w);
			}
			sy += lh + 3;
		}
		y += clockH + GAP;

		// Skill and luck, side by side and apart.
		final int tileW = (right - left - TILE_GAP) / 2;
		final int tileH = tileHeight(c.live);
		skillTile.setBounds(left, y, tileW, tileH);
		luckTile.setBounds(left + tileW + TILE_GAP, y, right - left - tileW - TILE_GAP, tileH);
		paintSkill(g, c, skillTile);
		paintLuck(g, c, luckTile);
		y += tileH;

		if (c.live)
		{
			// The weapon in hand: its sprite, its name, its DPS against expected.
			y += GAP;
			paintWeapon(g, c, left, y, right);
			return;
		}
		if (c.luckRemoved != null)
		{
			y += 7;
			final int x = left + draw(g, Text.body("luck removed", SidePanelStyle.SECONDARY), left, y, right - left) + 6;
			draw(g, Text.title(c.luckRemoved, SidePanelStyle.VALUE), x, y, right - x);
			if (c.luckRemovedPb != null)
			{
				if (c.newLuckRemovedPb)
				{
					// The gold PB, the only one: the luck-removed time is the skill's PB.
					final int ww = drawRight(g, Text.small(c.luckRemovedPb, SidePanelStyle.SECONDARY), right,
						y + (body() - small()), w);
					final int chipW = 7 + 3 + width(g, SidePanelStyle.Type.SMALL, "PB") + 8;
					final int cx = right - ww - 4 - chipW;
					g.setColor(SidePanelStyle.PANEL);
					g.fillRect(cx, y + 1, chipW, body() - 1);
					g.setColor(SidePanelStyle.GOLD);
					g.drawRect(cx, y + 1, chipW - 1, body() - 2);
					star(g, cx + 4, y + (body() - 7) / 2 + 1);
					draw(g, Text.small("PB", SidePanelStyle.GOLD), cx + 4 + 7 + 3, y + (body() - small()), chipW);
				}
				else
				{
					drawRight(g, Text.small(c.luckRemovedPb, SidePanelStyle.SECONDARY), right, y + (body() - small()), w);
				}
			}
			y += body();
		}
		if (c.tip != null)
		{
			y += 7;
			g.setColor(SidePanelStyle.TRACK);
			g.fillRect(left, y, right - left, 1);
			y += 1 + 6;
			// An orange mark, then the tip on up to two lines.
			g.setColor(SidePanelStyle.COST);
			g.fillRect(left, y, TIP_ICON, TIP_ICON);
			g.setColor(SidePanelStyle.CARD);
			g.fillRect(left + 5, y + 2, 2, 5);
			g.fillRect(left + 5, y + 8, 2, 2);
			final int tx = left + TIP_ICON + 6;
			final List<String> lines = wrap(g, c.tip, right - tx, 2);
			for (int i = 0; i < lines.size(); i++)
			{
				draw(g, Text.small(lines.get(i), SidePanelStyle.BODY), tx, y + i * (small() + 2), right - tx);
			}
		}
	}

	private void paintSkill(Graphics2D g, KillCard c, Rectangle r)
	{
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(r.x, r.y, r.width, r.height);
		final int x = r.x + TILE_PAD_X;
		final int right = r.x + r.width - TILE_PAD_X;
		final int room = right - x;
		int y = r.y + TILE_PAD_TOP;
		draw(g, Text.body("Skill", SidePanelStyle.SECONDARY), x, y, room);
		if (!c.live)
		{
			drawRight(g, Text.small(c.grade != null ? "grade" : "execution", SidePanelStyle.MUTED), right,
				y + (body() - small()) / 2, room);
		}
		y += body() + 4;
		final int bigW = draw(g, c.big, x, y - 2, room);
		paintCaption(g, c.bigCaption, x + bigW + 6, y, right - x - bigW - 6);
		y += big() + 4;
		draw(g, fit(g, c.skill1, room), x, y, room);
		if (!c.live)
		{
			draw(g, fit(g, c.skill2, room), x, y + small() + 3, room);
		}
	}

	private void paintLuck(Graphics2D g, KillCard c, Rectangle r)
	{
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(r.x, r.y, r.width, r.height);
		final int x = r.x + TILE_PAD_X;
		final int right = r.x + r.width - TILE_PAD_X;
		final int room = right - x;
		int y = r.y + TILE_PAD_TOP;
		draw(g, Text.body("Luck", SidePanelStyle.SECONDARY), x, y, room);
		if (!c.live)
		{
			die(g, right - 10, y + (body() - 12) / 2, 10, 12, SidePanelStyle.LUCK);
		}
		y += body() + 4;
		if (c.live)
		{
			// Rolling: no number until the fight ends.
			die(g, x, y + (big() - 16) / 2, 14, 16, SidePanelStyle.SECONDARY);
			draw(g, Text.title("rolling", SidePanelStyle.SECONDARY), x + 19, y + (big() - title()) / 2, room - 19);
		}
		else if (c.rolled > 0)
		{
			final int bw = draw(g, new Text(String.valueOf(c.rolled), SidePanelStyle.LUCK, SidePanelStyle.Type.CLOCK), x,
				y - 2, room);
			final List<Text> cap = new ArrayList<>(2);
			cap.add(Text.small("rolled", SidePanelStyle.SECONDARY));
			cap.add(Text.small("of 100", SidePanelStyle.SECONDARY));
			paintCaption(g, cap, x + bw + 4, y, right - x - bw - 4);
		}
		else
		{
			draw(g, Text.title("no roll", SidePanelStyle.MUTED), x, y + (big() - title()) / 2, room);
		}
		y += big() + 4;
		draw(g, fit(g, c.luckLine, room), x, y, room);
	}

	/** One or two short lines beside a big figure, its baseline at the figure's foot. */
	private void paintCaption(Graphics2D g, List<Text> lines, int x, int y, int room)
	{
		if (lines.isEmpty() || room <= 0)
		{
			return;
		}
		int total = 0;
		for (Text t : lines)
		{
			total += lineHeight(t.type);
		}
		total += lines.size() - 1;
		int ly = y + big() - total;
		for (Text t : lines)
		{
			draw(g, t, x, ly, room);
			ly += lineHeight(t.type) + 1;
		}
	}

	private void paintWeapon(Graphics2D g, KillCard c, int left, int y, int right)
	{
		if (c.weapon == null)
		{
			return;
		}
		if (weaponImage != null)
		{
			final int iw = Math.min(SPRITE_W, weaponImage.getWidth());
			final int ih = Math.min(SPRITE_H, weaponImage.getHeight());
			g.drawImage(weaponImage, left + (SPRITE_W - iw) / 2, y + (SPRITE_H - ih) / 2, iw, ih, null);
		}
		else
		{
			g.setColor(SidePanelStyle.PANEL);
			g.fillRect(left, y, SPRITE_W, SPRITE_H);
		}
		final int x = left + SPRITE_W + 7;
		final int top = y + (SPRITE_H - body() - 3 - small()) / 2;
		draw(g, Text.body(c.weapon, SidePanelStyle.VALUE), x, top, right - x);
		if (c.weaponDps != null)
		{
			draw(g, Text.small(c.weaponDps, SidePanelStyle.SECONDARY), x, top + body() + 3, right - x);
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

	/** The gold star, 7 px. */
	static void star(Graphics2D g, int x, int y)
	{
		g.setColor(SidePanelStyle.GOLD);
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

package com.pvmlogs;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;
import javax.swing.JComponent;

/**
 * T-125: the Party tab's meter. One row a member, biggest damage first, a bar behind each filled to its share of the
 * top member's damage in the member's colour with a solid edge, the player's own row bold and white, {@code others}
 * last in grey; the value columns on the right, by the header's switch. One component paints every row: a board change
 * is one repaint, never a layout, and a hover only repaints (T-046). Clicking a member opens their breakdown.
 */
final class SidePanelPartyMeter extends JComponent
{
	/** Between the value columns. */
	static final int GAP = 7;
	/** Left of the name: past the bar's edge. */
	static final int NAME_X = 6;

	private List<PanelSnapshot.PartyRow> rows = Collections.emptyList();
	private SidePanelParty.Metric metric = SidePanelParty.Metric.DMG;
	private int hover = -1;
	/** How many value columns the last paint fitted: the DPS column goes first where a name would be cut to nothing. */
	private int shownColumns;
	private int paints;
	/** Names the last paint had to cut, for the fit tests. */
	private int cutNames;

	SidePanelPartyMeter(LongConsumer onOpen)
	{
		setOpaque(false);
		final MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				hover(rowAt(e.getY()));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover(-1);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				final int i = rowAt(e.getY());
				if (i >= 0 && !rows.get(i).others)
				{
					onOpen.accept(rows.get(i).id);
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		// Each row says what it is on hover; the meter itself carries no tooltip until then.
		setToolTipText("");
	}

	/** New figures: a repaint; a layout only when the number of rows changed (the tab rebuilds for that anyway). */
	void set(List<PanelSnapshot.PartyRow> rows)
	{
		final boolean count = rows.size() != this.rows.size();
		this.rows = rows;
		if (count)
		{
			revalidate();
		}
		repaint();
	}

	void setMetric(SidePanelParty.Metric metric)
	{
		if (this.metric != metric)
		{
			this.metric = metric;
			repaint();
		}
	}

	SidePanelParty.Metric metric()
	{
		return metric;
	}

	static int rowHeight()
	{
		return SidePanelStyle.Type.BODY.lineHeight() + 5;
	}

	private int rowAt(int y)
	{
		final int i = y / rowHeight();
		return y < 0 || i >= rows.size() ? -1 : i;
	}

	private void hover(int i)
	{
		if (i != hover)
		{
			hover = i;
			setCursor(i >= 0 && !rows.get(i).others ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
			repaint();
		}
	}

	@Override
	public String getToolTipText(MouseEvent e)
	{
		final int i = rowAt(e.getY());
		if (i < 0)
		{
			return null;
		}
		final PanelSnapshot.PartyRow r = rows.get(i);
		if (r.others)
		{
			return SidePanelParty.OTHERS_NOTE;
		}
		return (r.you ? "You" : r.name) + ": " + r.dmg[0] + " damage, " + r.dmg[1] + " dps, " + r.dmg[2]
			+ " of the party's. Click for the breakdown.";
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH - 2 * SidePanelStyle.CARD_PAD_H, rows.size() * rowHeight());
	}

	/** For tests: how many value columns fitted at the last paint, and how many paints there were. */
	int shownColumns()
	{
		return shownColumns;
	}

	int paints()
	{
		return paints;
	}

	int cutNames()
	{
		return cutNames;
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		paints++;
		if (rows.isEmpty())
		{
			return;
		}
		final Graphics2D g = (Graphics2D) g0.create();
		SidePanelStyle.textHints(g);
		final int w = getWidth();
		final int rh = rowHeight();
		final Font body = SidePanelStyle.Type.BODY.font();
		final Font bold = SidePanelStyle.Type.TITLE.font();
		final Font small = SidePanelStyle.Type.SMALL.font();
		final FontMetrics fb = SidePanelStyle.metrics(g, body);
		final FontMetrics fs = SidePanelStyle.metrics(g, small);
		// The columns: each as wide as its widest value.
		final int columns = rows.get(0).values(metric).length;
		final int[] cw = new int[columns];
		for (PanelSnapshot.PartyRow r : rows)
		{
			final String[] v = r.values(metric);
			for (int c = 0; c < columns; c++)
			{
				cw[c] = Math.max(cw[c], fs.stringWidth(v[c]));
			}
		}
		// Where the longest name wouldn't fit beside the columns, the middle one (DPS) gives way first; a name longer
		// still is cut.
		final boolean[] show = new boolean[columns];
		java.util.Arrays.fill(show, true);
		int longest = 0;
		for (PanelSnapshot.PartyRow r : rows)
		{
			longest = Math.max(longest, SidePanelStyle.metrics(g, r.you ? bold : body).stringWidth(r.name
				+ (r.left ? " (left)" : "")));
		}
		// T-128: with any Discord picture on the board, every member's row keeps a picture's room, so names line up.
		final boolean pictures = anyPicture();
		final int picture = pictures ? pictureSize() : 0;
		final int nameX = NAME_X + (pictures ? picture + 4 : 0);
		if (columns == 3 && w - nameX - right(cw, show) - 2 < longest)
		{
			show[1] = false;
		}
		shownColumns = 0;
		for (boolean s : show)
		{
			shownColumns += s ? 1 : 0;
		}
		final int nameRoom = w - nameX - right(cw, show) - 2;
		cutNames = 0;
		final int smallBase = (rh + fs.getAscent() - fs.getDescent()) / 2;
		final int bodyBase = (rh + fb.getAscent() - fb.getDescent()) / 2;
		for (int i = 0; i < rows.size(); i++)
		{
			final PanelSnapshot.PartyRow r = rows.get(i);
			final int y = i * rh;
			final boolean muted = r.others || r.left;
			// The bar behind the row: the member's colour dimmed under the text, a solid edge on the left.
			g.setColor(SidePanelStyle.alpha(r.colour, r.left ? 0.18 : r.you ? 0.30 : r.others ? 0.45 : 0.42));
			g.fillRect(0, y + 1, (int) Math.round(w * r.fill), rh - 2);
			g.setColor(r.left ? SidePanelStyle.alpha(r.colour, 0.5) : r.colour);
			g.fillRect(0, y + 1, 2, rh - 2);
			if (i == hover && !r.others)
			{
				g.setColor(SidePanelStyle.alpha(SidePanelStyle.VALUE, 0.45));
				g.drawRect(0, y + 1, w - 1, rh - 3);
			}
			g.setFont(r.you ? bold : body);
			final FontMetrics fn = SidePanelStyle.metrics(g, r.you ? bold : body);
			g.setColor(muted ? SidePanelStyle.MUTED : r.you ? SidePanelStyle.VALUE : SidePanelStyle.BODY);
			final String name = r.name + (r.left ? " (left)" : "");
			final String shown = cut(name, fn, nameRoom);
			cutNames += shown.equals(name) ? 0 : 1;
			g.drawString(shown, nameX, y + bodyBase);
			if (pictures && !r.others)
			{
				picture(g, r, NAME_X - 1, y + (rh - picture) / 2, picture);
			}
			g.setFont(small);
			final String[] v = r.values(metric);
			int x = w - 3;
			for (int c = columns - 1; c >= 0; c--)
			{
				if (!show[c])
				{
					continue;
				}
				g.setColor(colour(r, c, columns));
				g.drawString(v[c], x - fs.stringWidth(v[c]), y + smallBase);
				x -= cw[c] + GAP;
			}
		}
		g.dispose();
	}

	/** A value's colour: luck by its band, the share secondary, the rest white; others and a member who left muted. */
	private Color colour(PanelSnapshot.PartyRow r, int c, int columns)
	{
		if (r.others || r.left)
		{
			return SidePanelStyle.MUTED;
		}
		if (metric == SidePanelParty.Metric.LUCK)
		{
			return r.luckColour;
		}
		return c == columns - 1 && metric == SidePanelParty.Metric.DMG ? SidePanelStyle.SECONDARY : SidePanelStyle.VALUE;
	}

	/** T-128: a round picture beside the name, its height a row's less a margin. */
	static int pictureSize()
	{
		return Math.max(8, rowHeight() - 6);
	}

	private boolean anyPicture()
	{
		for (PanelSnapshot.PartyRow r : rows)
		{
			if (r.avatar != null)
			{
				return true;
			}
		}
		return false;
	}

	/** T-128: pictures scaled to the size drawn at, once per picture and size (the Discord plugin's are 256 px or so). */
	private final java.util.Map<java.awt.image.BufferedImage, java.awt.image.BufferedImage> scaled =
		new java.util.WeakHashMap<>();
	private int scaledFor;

	/** The member's picture in a circle, or their colour as a round swatch without one. */
	private void picture(Graphics2D g, PanelSnapshot.PartyRow r, int x, int y, int size)
	{
		final Graphics2D c = (Graphics2D) g.create();
		c.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		if (r.avatar == null)
		{
			c.setColor(r.left ? SidePanelStyle.alpha(r.colour, 0.5) : r.colour);
			c.fillOval(x, y, size, size);
			c.dispose();
			return;
		}
		// Scaled to the pixels it lands on, so it's sharp at 2× too.
		final int px = (int) Math.round(size * Math.max(1, g.getTransform().getScaleX()));
		if (px != scaledFor)
		{
			scaled.clear();
			scaledFor = px;
		}
		final java.awt.image.BufferedImage img = scaled.computeIfAbsent(r.avatar, a -> scale(a, px));
		c.setClip(new java.awt.geom.Ellipse2D.Double(x, y, size, size));
		c.drawImage(img, x, y, size, size, null);
		c.setClip(null);
		c.dispose();
	}

	/** A picture scaled to {@code px} square, smoothly. */
	static java.awt.image.BufferedImage scale(java.awt.image.BufferedImage src, int px)
	{
		final java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(px, px,
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
			java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, px, px, null);
		g.dispose();
		return out;
	}

	/** For tests: pictures scaled so far. */
	int scaledPictures()
	{
		return scaled.size();
	}

	private static int right(int[] cw, boolean[] show)
	{
		int r = 3;
		for (int c = 0; c < cw.length; c++)
		{
			r += show[c] ? cw[c] + GAP : 0;
		}
		return r;
	}

	/** The name cut with LedgerRows' mark to what the columns leave. */
	static String cut(String s, FontMetrics f, int room)
	{
		if (f.stringWidth(s) <= room)
		{
			return s;
		}
		String t = s;
		while (t.length() > 1 && f.stringWidth(t + LedgerRows.CUT) > room)
		{
			t = t.substring(0, t.length() - 1);
		}
		return t + LedgerRows.CUT;
	}
}

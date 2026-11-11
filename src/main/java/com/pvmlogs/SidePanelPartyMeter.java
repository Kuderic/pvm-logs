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
 * T-125: the party's meter (on the Fight tab since T-176). One row a member, a bar behind each filled to its share of
 * the top member's damage in the member's colour with a solid edge, the player's own row bold and white, the group of
 * members not running Luck vs Skill (or {@code others}) last in grey. One component paints every row: a board change is
 * one repaint, never a layout, and a hover only repaints (T-046). Clicking a member opens their breakdown.
 * <p>
 * T-208 (Eric: "is there a way to show 3 numbers at the forefront ... dmg, luck, skill?"): every sharing member's row
 * shows all three at a glance, painted: the damage; luck as a mini track in its three strengths with the marker (white
 * after the fight, in its strength's ink live); skill as the grade chip after the fight where the grade is known (yours:
 * a member's isn't shared) and the Skill % otherwise, as the card has it. The share is the bar; DPS, share, luck's words
 * and skill are on the row's hover. The header's switch sorts the rows by damage, luck or skill.
 */
final class SidePanelPartyMeter extends JComponent
{
	/** Between the value columns. */
	static final int GAP = 7;
	/** Left of the name: past the bar's edge. */
	static final int NAME_X = 6;

	private List<PanelSnapshot.PartyRow> rows = Collections.emptyList();
	/** T-208: the rows in the order shown: the members by the switch's figure, the group and listed rows after. */
	private List<PanelSnapshot.PartyRow> shown = Collections.emptyList();
	/** T-208: your grade (from your kill card) and its colour; null without one. Members' grades aren't shared. */
	private String yourGrade;
	private Color yourGradeColour;
	private SidePanelParty.Metric metric = SidePanelParty.Metric.DMG;
	private int hover = -1;
	/** T-130: the member whose Fight panels show under the chart, outlined in their colour; you by default. */
	private long selected = SidePanelParty.YOU_ID;
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
				if (i >= 0 && !shown.get(i).others)
				{
					onOpen.accept(shown.get(i).id);
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
		this.shown = order(rows, metric);
		if (count)
		{
			revalidate();
		}
		repaint();
	}

	/** T-208: your grade's chip on your row after the fight; null for none. A repaint. */
	void setYourGrade(String grade, Color colour)
	{
		if (!java.util.Objects.equals(grade, yourGrade) || !java.util.Objects.equals(colour, yourGradeColour))
		{
			yourGrade = grade;
			yourGradeColour = colour;
			repaint();
		}
	}

	/**
	 * T-208: the rows in the order shown. The members by {@code m}'s figure, highest first (damage; luck's percentile;
	 * the Skill share), those without one after them, you first among equals; then the group, others and listed rows.
	 */
	static List<PanelSnapshot.PartyRow> order(List<PanelSnapshot.PartyRow> rows, SidePanelParty.Metric m)
	{
		final List<PanelSnapshot.PartyRow> members = new java.util.ArrayList<>();
		final List<PanelSnapshot.PartyRow> rest = new java.util.ArrayList<>();
		for (PanelSnapshot.PartyRow r : rows)
		{
			(r.others ? rest : members).add(r);
		}
		if (m != SidePanelParty.Metric.DMG)
		{
			// A stable sort: equals keep the damage order the model gave them.
			members.sort(java.util.Comparator.comparingDouble((PanelSnapshot.PartyRow r) ->
			{
				final double v = m == SidePanelParty.Metric.LUCK ? r.luckP : r.skillShare;
				return Double.isNaN(v) || v < 0 ? Double.POSITIVE_INFINITY : -v;
			}));
		}
		members.addAll(rest);
		return members;
	}

	/** T-208: the rows as shown, for tests. */
	List<PanelSnapshot.PartyRow> shown()
	{
		return shown;
	}

	/** T-130: the selected member: a repaint. */
	void setSelected(long id)
	{
		if (id != selected)
		{
			selected = id;
			repaint();
		}
	}

	long selected()
	{
		return selected;
	}

	void setMetric(SidePanelParty.Metric metric)
	{
		if (this.metric != metric)
		{
			this.metric = metric;
			this.shown = order(rows, metric);
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
		return y < 0 || i >= shown.size() ? -1 : i;
	}

	private void hover(int i)
	{
		if (i != hover)
		{
			hover = i;
			setCursor(i >= 0 && !shown.get(i).others ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
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
		final PanelSnapshot.PartyRow r = shown.get(i);
		if (r.others)
		{
			// T-167: others, others taken as members', or a member listed without figures: each says what it is.
			return SidePanelStyle.tip(r.tip != null ? r.tip : SidePanelParty.OTHERS_NOTE);
		}
		return SidePanelStyle.tip(rowTip(r, r.you ? yourGrade : null));
	}

	/** T-208: what a member's row doesn't show: DPS and share, luck in words, Skill % and grade. */
	static String rowTip(PanelSnapshot.PartyRow r, String grade)
	{
		final StringBuilder b = new StringBuilder();
		b.append(r.you ? "You" : r.name).append(": ").append(r.dmg[0]).append(" damage, ").append(r.dmg[2])
			.append(" of the party's, ").append(r.dmg[1]).append(" dps.");
		if (!r.luckWords.isEmpty())
		{
			b.append("<br>Luck: ").append(r.luckWords).append(r.live ? " so far." : ".");
		}
		if (!r.skill.isEmpty())
		{
			b.append("<br>Skill: ").append(r.skill).append(r.live ? " so far" : "")
				.append(grade != null && !r.live ? ", grade " + grade : "").append(".");
		}
		return b.append("<br>Click for their Fight panels below.").toString();
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
		lastTracks.clear();
		lastSkills.clear();
		final int w = getWidth();
		final int rh = rowHeight();
		final Font body = SidePanelStyle.Type.BODY.font();
		final Font bold = SidePanelStyle.Type.TITLE.font();
		final Font small = SidePanelStyle.Type.SMALL.font();
		final FontMetrics fb = SidePanelStyle.metrics(g, body);
		final FontMetrics fs = SidePanelStyle.metrics(g, small);
		// T-208: three figures on the right of each member's row: damage, luck's mini track, skill (chip or %), each
		// column as wide as its widest; the group and others rows have their damage in the first column alone.
		int dmgW = 0;
		for (PanelSnapshot.PartyRow r : shown)
		{
			if (!r.listed)
			{
				dmgW = Math.max(dmgW, fs.stringWidth(r.dmg[0]));
			}
		}
		final int skillW = Math.max(fs.stringWidth("100%"), chipWidth(g));
		final int trackW = TRACK_W;
		final int right = 3 + skillW + GAP + trackW + GAP + dmgW;
		// T-128: with any Discord picture on the board, every member's row keeps a picture's room, so names line up.
		final boolean pictures = anyPicture();
		final int picture = pictures ? pictureSize() : 0;
		final int nameX = NAME_X + (pictures ? picture + 4 : 0);
		shownColumns = 3;
		final int nameRoom = w - nameX - right - 4;
		cutNames = 0;
		final int smallBase = (rh + fs.getAscent() - fs.getDescent()) / 2;
		final int bodyBase = (rh + fb.getAscent() - fb.getDescent()) / 2;
		for (int i = 0; i < shown.size(); i++)
		{
			final PanelSnapshot.PartyRow r = shown.get(i);
			final int y = i * rh;
			final boolean muted = r.others && !r.person() || r.left;
			// The bar behind the row: the member's colour dimmed under the text, a solid edge on the left.
			g.setColor(SidePanelStyle.alpha(r.colour, r.left ? 0.18 : r.you ? 0.30 : r.others ? 0.45 : 0.42));
			g.fillRect(0, y + 1, (int) Math.round(w * r.fill), rh - 2);
			g.setColor(r.left ? SidePanelStyle.alpha(r.colour, 0.5) : r.colour);
			g.fillRect(0, y + 1, 2, rh - 2);
			if (r.id == selected && !r.others)
			{
				// T-130: the selected member, outlined in their colour.
				g.setColor(r.colour);
				g.drawRect(0, y + 1, w - 1, rh - 3);
				g.drawRect(1, y + 2, w - 3, rh - 5);
			}
			else if (i == hover && !r.others)
			{
				g.setColor(SidePanelStyle.alpha(SidePanelStyle.VALUE, 0.45));
				g.drawRect(0, y + 1, w - 1, rh - 3);
			}
			g.setFont(r.you ? bold : body);
			final FontMetrics fn = SidePanelStyle.metrics(g, r.you ? bold : body);
			g.setColor(muted ? SidePanelStyle.MUTED : r.you ? SidePanelStyle.VALUE : SidePanelStyle.BODY);
			final String name = r.name + (r.left ? " (left)" : "");
			// T-167: a listed member's row has no values: the name and its hint take the row. T-208: the group's row has its
			// damage alone, in the damage column, so its name has the same room as a member's.
			final int room = r.listed ? w - nameX - 3 : nameRoom;
			final String shownName = cut(name, fn, room);
			cutNames += shownName.equals(name) ? 0 : 1;
			g.drawString(shownName, nameX, y + bodyBase);
			if (r.hint != null && shownName.equals(name))
			{
				// T-167: muted after the name, its shorter form where it doesn't fit; never cut, and never at the name's cost.
				g.setFont(small);
				final int x0 = nameX + fn.stringWidth(name) + fs.stringWidth(" ");
				final String hint = fit(r.hint, r.hintShort, fs, room - (x0 - nameX));
				if (!hint.isEmpty())
				{
					g.setColor(SidePanelStyle.MUTED);
					g.drawString(hint, x0, y + smallBase);
				}
				lastHints.put(r.id, hint);
			}
			if (pictures && r.person())
			{
				picture(g, r, NAME_X - 1, y + (rh - picture) / 2, picture);
			}
			if (r.listed)
			{
				continue;
			}
			g.setFont(small);
			final int skillX = w - 3 - skillW;
			final int trackX = skillX - GAP - trackW;
			final int dmgRight = trackX - GAP;
			g.setColor(muted || r.others ? SidePanelStyle.MUTED : SidePanelStyle.VALUE);
			g.drawString(r.dmg[0], dmgRight - fs.stringWidth(r.dmg[0]), y + smallBase);
			if (r.others)
			{
				continue;
			}
			// Luck: the mini track in its three strengths, the marker where luck sits.
			if (!Double.isNaN(r.luckP))
			{
				miniTrack(g, trackX, y + rh / 2 - 2, trackW, r.luckP, r.live ? SidePanelModel.luckInk(r.luckP)
					: SidePanelStyle.VALUE, r.left);
				lastTracks.put(r.id, new java.awt.Rectangle(trackX, y + rh / 2 - 2, trackW, 4));
			}
			// Skill: the grade chip after the fight where the grade is known; the Skill % otherwise.
			final String grade = r.you && !r.live ? yourGrade : null;
			if (grade != null)
			{
				final int ch = Math.min(rh - 2, fs.getHeight() + 2);
				final int cy = y + (rh - ch) / 2;
				final int cw = chipWidth(g);
				final int cx = w - 3 - cw;
				g.setColor(yourGradeColour);
				g.drawRect(cx, cy, cw - 1, ch - 1);
				g.drawString(grade, cx + (cw - fs.stringWidth(grade)) / 2, y + smallBase);
				lastSkills.put(r.id, "grade " + grade);
			}
			else if (!r.skill.isEmpty())
			{
				g.setColor(muted ? SidePanelStyle.MUTED : SidePanelStyle.VALUE);
				g.drawString(r.skill, w - 3 - fs.stringWidth(r.skill), y + smallBase);
				lastSkills.put(r.id, r.skill);
			}
		}
		g.dispose();
	}

	/** T-208: luck's mini track, as Luck's folded summary has it: three strengths, the marker 2 px over and under. */
	static final int TRACK_W = 28;

	private static void miniTrack(Graphics2D g, int x, int y, int w, double p, Color marker, boolean muted)
	{
		final int a = x + w / 4;
		final int b = x + w * 3 / 4;
		final float alpha = muted ? 0.5f : 1f;
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.LUCK_TRACK_DIM, alpha));
		g.fillRect(x, y, a - x, 4);
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.LUCK_TRACK_NORMAL, alpha));
		g.fillRect(a, y, b - a, 4);
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.LUCK_TRACK_BRIGHT, alpha));
		g.fillRect(b, y, x + w - b, 4);
		final int mx = x + (int) Math.round(Math.max(0, Math.min(1, p)) * (w - 2));
		g.setColor(marker);
		g.fillRect(mx, y - 2, 2, 8);
	}

	/** T-208: the grade chip's width: the widest of S, A, B and C in small text, 3 px either side. */
	private static int chipWidth(Graphics2D g)
	{
		final FontMetrics fs = SidePanelStyle.metrics(g, SidePanelStyle.Type.SMALL.font());
		int widest = 0;
		for (String letter : new String[]{"S", "A", "B", "C"})
		{
			widest = Math.max(widest, fs.stringWidth(letter));
		}
		return widest + 6;
	}

	/** T-208: what each member's row painted at the last paint, by id, for tests: the track's box, the skill shown. */
	private final java.util.Map<Long, java.awt.Rectangle> lastTracks = new java.util.HashMap<>();
	private final java.util.Map<Long, String> lastSkills = new java.util.HashMap<>();

	java.awt.Rectangle lastTrack(long id)
	{
		return lastTracks.get(id);
	}

	String lastSkill(long id)
	{
		return lastSkills.get(id);
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

	/** T-167: the hint each row drew at the last paint, by id, for the fit tests; empty where none fitted. */
	private final java.util.Map<Long, String> lastHints = new java.util.HashMap<>();

	String lastHint(long id)
	{
		return lastHints.get(id);
	}

	/** T-167: {@code hint}, or the first of its shorter forms that fits {@code room}; empty where none does. */
	static String fit(String hint, List<String> shorter, FontMetrics f, int room)
	{
		if (f.stringWidth(hint) <= room)
		{
			return hint;
		}
		for (String s : shorter)
		{
			if (f.stringWidth(s) <= room)
			{
				return s;
			}
		}
		return "";
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

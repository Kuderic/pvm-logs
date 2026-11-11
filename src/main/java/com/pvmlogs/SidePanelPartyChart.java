package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;

/**
 * T-125: the party's Damage over time (on the Fight tab since T-176). A line a member in their colour, the player's own bold and white on top,
 * {@code others} dashed grey. Hovering draws T-112's line at that moment and a readout of every member's damage there,
 * painted over the chart: a hover never lays anything out (T-046). Drilled into a member, the other lines fade.
 *
 * <p>T-216 (Eric: "Post fight clicking them shouldn't just change the sort, it should change what's displayed in the
 * graphs"): the meter's switch picks what it draws: damage over time; luck so far on the 1 to 100 scale, over luck's
 * three strengths as bands; or Skill so far. Members not running Luck vs Skill have damage only: their line is an
 * estimate from the other players' splats, solid but lighter.
 */
final class SidePanelPartyChart extends JComponent
{
	static final int HEIGHT = 96;

	private List<PanelSnapshot.PartyRow> rows = Collections.emptyList();
	private int ticks = 1;
	private String clock = "";
	private long focus = Long.MIN_VALUE;
	private int hover = -1;
	private SidePanelParty.Metric metric = SidePanelParty.Metric.DMG;
	/** T-216: what the last paint drew from, so an update with no new point is no repaint. */
	private String drawnFrom = "";
	private int paints;
	/** T-216: the sets that asked for a repaint (a new point, a switch, a new focus), for tests. */
	private int changes;

	int changes()
	{
		return changes;
	}
	/** T-216: the rows whose lines the last paint drew, by id, for tests. */
	private final List<Long> drawn = new ArrayList<>();

	SidePanelPartyChart()
	{
		setOpaque(false);
		final MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				setHover(tickAt(e.getX()));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setHover(-1);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	void set(PanelSnapshot.PartyTab t, long focus)
	{
		set(t, focus, metric);
	}

	/** T-216: the tab's rows, the member in focus and what the switch picked; a repaint only on a new point or a switch. */
	void set(PanelSnapshot.PartyTab t, long focus, SidePanelParty.Metric metric)
	{
		this.rows = t.rows;
		this.ticks = t.ticks;
		this.clock = t.clock;
		this.focus = focus;
		this.metric = metric;
		final String from = signature();
		if (!from.equals(drawnFrom))
		{
			drawnFrom = from;
			changes++;
			repaint();
		}
	}

	private String signature()
	{
		final StringBuilder b = new StringBuilder().append(metric).append('|').append(ticks).append('|').append(focus)
			.append('|').append(clock);
		for (PanelSnapshot.PartyRow r : rows)
		{
			final int n = r.seriesTicks.length;
			b.append('|').append(r.id).append(':').append(n).append(':').append(n == 0 ? 0 : r.seriesDamage[n - 1])
				.append(':').append(r.metricTicks.length).append(':').append(r.left);
		}
		return b.toString();
	}

	SidePanelParty.Metric metric()
	{
		return metric;
	}

	/** T-130: the member drawn bright, the others faded; Long.MIN_VALUE for none. */
	long focus()
	{
		return focus;
	}

	/** For tests: the paints so far, and the rows the last one drew a line for. */
	int paints()
	{
		return paints;
	}

	List<Long> drawn()
	{
		return new ArrayList<>(drawn);
	}

	/** For tests and renders: the tick hovered; whether it changed. */
	boolean setHover(int tick)
	{
		final int h = tick < 0 ? -1 : Math.min(tick, ticks);
		if (h == hover)
		{
			return false;
		}
		hover = h;
		repaint();
		return true;
	}

	private int tickAt(int x)
	{
		final int w = Math.max(1, getWidth() - 1);
		return x < 0 || x > getWidth() ? -1 : (int) Math.round((double) x / w * ticks);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(SidePanelStyle.CONTENT_WIDTH - 2 * SidePanelStyle.CARD_PAD_H, HEIGHT);
	}

	/** T-216: the Skill scale's floor: a 5% step under the lowest Skill drawn, at most 90%. */
	private double skillFloor()
	{
		double lo = 1;
		for (PanelSnapshot.PartyRow r : rows)
		{
			for (int i = 0; i < r.seriesSkill.length && i < r.metricTicks.length; i++)
			{
				if (!Float.isNaN(r.seriesSkill[i]))
				{
					lo = Math.min(lo, r.seriesSkill[i]);
				}
			}
		}
		return Math.max(0, Math.min(0.9, Math.floor((lo - 0.01) * 20) / 20));
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		paints++;
		drawn.clear();
		if (rows.isEmpty())
		{
			return;
		}
		final Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		SidePanelStyle.textHints(g);
		final Font small = SidePanelStyle.Type.SMALL.font();
		final FontMetrics fs = SidePanelStyle.metrics(g, small);
		final int w = getWidth();
		final int h = getHeight();
		final int plotH = h - fs.getHeight() - 2;
		final boolean luck = metric == SidePanelParty.Metric.LUCK;
		final boolean skill = metric == SidePanelParty.Metric.EXEC;
		int max = 1;
		for (PanelSnapshot.PartyRow r : rows)
		{
			for (int d : r.seriesDamage)
			{
				max = Math.max(max, d);
			}
		}
		final double floor = skill ? skillFloor() : 0;
		if (luck)
		{
			// Luck's three strengths as bands behind the lines: the bottom quarter dim, the middle half, the top quarter.
			bands(g, w, plotH);
		}
		// Recessive grid: the top and the baseline; the scale's ends and the clock's.
		g.setColor(SidePanelStyle.DIVIDER);
		g.drawLine(0, 0, w, 0);
		g.drawLine(0, plotH - 1, w, plotH - 1);
		g.setFont(small);
		g.setColor(SidePanelStyle.MUTED);
		final String top = luck ? "top" : skill ? "100%" : SidePanelParty.compact(max);
		g.drawString(top, 2, fs.getAscent() + 1);
		if (luck || skill)
		{
			final String bottom = luck ? "bottom" : LedgerRows.partPercent(floor);
			g.drawString(bottom, 2, plotH - 3);
		}
		g.drawString(LedgerRows.duration(0), 0, h - fs.getDescent());
		g.drawString(clock, w - fs.stringWidth(clock), h - fs.getDescent());
		final List<PanelSnapshot.PartyRow> order = new ArrayList<>(rows);
		// Others underneath, the player on top.
		order.sort(Comparator.comparingInt((PanelSnapshot.PartyRow r) -> r.others ? 0 : r.you ? 2 : 1));
		for (PanelSnapshot.PartyRow r : order)
		{
			final Path2D path = luck || skill ? metricPath(r, w, plotH, floor) : r.seriesTicks.length < 2 ? null
				: path(r, max, w, plotH);
			if (path == null)
			{
				continue;
			}
			drawn.add(r.id);
			final boolean faded = focus != Long.MIN_VALUE && focus != r.id;
			// T-216: the members not running Luck vs Skill, solid but lighter: an estimate. The plain others stay dashed.
			g.setStroke(r.others && !r.estimate ? new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
				new float[]{3f, 3f}, 0f) : new BasicStroke(r.you ? 2.2f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Color c = r.left ? SidePanelStyle.alpha(r.colour, 0.5) : r.colour;
			c = r.estimate ? SidePanelStyle.alpha(c, ESTIMATE_ALPHA) : c;
			g.setColor(faded ? SidePanelStyle.alpha(c, 0.25) : c);
			g.draw(path);
		}
		if (hover >= 0)
		{
			readout(g, fs, w, plotH);
		}
		g.dispose();
	}

	/** T-216: how much of its colour an estimated line keeps: solid, lighter than a member's own. */
	static final double ESTIMATE_ALPHA = 0.55;

	/** T-216: luck's bands, its three strengths as the track has them, faint behind the lines. */
	private static void bands(Graphics2D g, int w, int plotH)
	{
		final double[] at = {0, 0.25, 0.75, 1};
		final Color[] c = {SidePanelStyle.LUCK_TRACK_DIM, SidePanelStyle.LUCK_TRACK_NORMAL, SidePanelStyle.LUCK_TRACK_BRIGHT};
		for (int i = 0; i < 3; i++)
		{
			final int y0 = (int) Math.round(y(at[i + 1], plotH, 0));
			final int y1 = (int) Math.round(y(at[i], plotH, 0));
			g.setColor(SidePanelStyle.alpha(c[i], BAND_ALPHA));
			g.fillRect(0, y0, w, Math.max(1, y1 - y0));
		}
	}

	static final double BAND_ALPHA = 0.28;

	/** A value 0 to 1 (from {@code floor} up) at its height on the plot. */
	private static double y(double v, int plotH, double floor)
	{
		final double f = floor >= 1 ? 0 : (v - floor) / (1 - floor);
		return plotH - 2 - Math.max(0, Math.min(1, f)) * (plotH - 4);
	}

	private Path2D path(PanelSnapshot.PartyRow r, int max, int w, int plotH)
	{
		final Path2D p = new Path2D.Double();
		for (int i = 0; i < r.seriesTicks.length; i++)
		{
			final double x = (double) r.seriesTicks[i] / ticks * (w - 1);
			final double y = plotH - 2 - (double) r.seriesDamage[i] / max * (plotH - 4);
			if (i == 0)
			{
				p.moveTo(x, y);
			}
			else
			{
				p.lineTo(x, y);
			}
		}
		return p;
	}

	/** T-216: a row's luck or Skill line, broken where it isn't known; null without two points of it. */
	private Path2D metricPath(PanelSnapshot.PartyRow r, int w, int plotH, double floor)
	{
		final float[] v = metric == SidePanelParty.Metric.LUCK ? r.seriesLuck : r.seriesSkill;
		final Path2D p = new Path2D.Double();
		boolean down = false;
		int points = 0;
		for (int i = 0; i < r.metricTicks.length && i < v.length; i++)
		{
			if (Float.isNaN(v[i]))
			{
				down = false;
				continue;
			}
			final double x = (double) r.metricTicks[i] / ticks * (w - 1);
			final double y = y(v[i], plotH, floor);
			if (down)
			{
				p.lineTo(x, y);
			}
			else
			{
				p.moveTo(x, y);
				down = true;
			}
			points++;
		}
		return points < 2 ? null : p;
	}

	/** The hover readout's value for a row at {@code tick}, for the metric picked; null where it has none. */
	private String valueAt(PanelSnapshot.PartyRow r, int tick)
	{
		if (metric == SidePanelParty.Metric.DMG)
		{
			return (r.estimate ? SidePanelParty.APPROX : "") + LedgerRows.number(r.damageAt(tick));
		}
		final double v = r.metricAt(metric, tick);
		if (Double.isNaN(v))
		{
			return null;
		}
		return metric == SidePanelParty.Metric.LUCK ? SidePanelModel.luckText(v) : LedgerRows.partPercent(v);
	}

	/** T-216: the readout's lines at {@code tick}: {label, value}, the members by the metric, highest first. For tests. */
	List<String[]> readoutAt(int tick)
	{
		final List<PanelSnapshot.PartyRow> shown = new ArrayList<>();
		final List<String> without = new ArrayList<>();
		for (PanelSnapshot.PartyRow r : rows)
		{
			if (r.listed || r.others && !r.estimate)
			{
				continue;
			}
			if (valueAt(r, tick) != null)
			{
				shown.add(r);
			}
			else if (r.estimate)
			{
				without.add(r.name);
			}
		}
		shown.sort(Comparator.comparingDouble((PanelSnapshot.PartyRow r) -> metric == SidePanelParty.Metric.DMG
			? -r.damageAt(tick) : -r.metricAt(metric, tick)));
		final List<String[]> out = new ArrayList<>();
		out.add(new String[]{"at " + LedgerRows.duration(tick * LedgerRows.SECONDS_PER_TICK), ""});
		for (PanelSnapshot.PartyRow r : shown)
		{
			out.add(new String[]{r.name, valueAt(r, tick), String.valueOf(r.id)});
		}
		for (String name : without)
		{
			// T-216: the members not running Luck vs Skill have no luck or skill: said, not left out silently.
			out.add(new String[]{name + ": no " + metric.word(false), "", null});
		}
		return out;
	}

	/** T-112's hover line, and a box of every member's figure at that moment beside it. */
	private void readout(Graphics2D g, FontMetrics fs, int w, int plotH)
	{
		final int x = (int) Math.round((double) hover / ticks * (w - 1));
		g.setStroke(new BasicStroke(1f));
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.VALUE, 0.6));
		g.drawLine(x, 0, x, plotH - 1);
		final List<String[]> lines = readoutAt(hover);
		final List<Color> colours = new ArrayList<>();
		for (String[] l : lines)
		{
			Color c = null;
			if (l.length > 2 && l[2] != null)
			{
				for (PanelSnapshot.PartyRow r : rows)
				{
					if (String.valueOf(r.id).equals(l[2]))
					{
						c = r.estimate ? SidePanelStyle.alpha(r.colour, ESTIMATE_ALPHA) : r.colour;
					}
				}
			}
			colours.add(c);
		}
		int nameW = 0;
		int valueW = 0;
		for (String[] l : lines)
		{
			nameW = Math.max(nameW, fs.stringWidth(l[0]));
			valueW = Math.max(valueW, fs.stringWidth(l[1]));
		}
		final int lh = SidePanelStyle.Type.SMALL.lineHeight();
		final int bw = Math.min(w, 4 + 8 + nameW + 8 + valueW + 4);
		final int bh = 3 + lines.size() * lh + 2;
		final int bx = x + 6 + bw <= w ? x + 6 : Math.max(0, x - 6 - bw);
		g.setColor(SidePanelStyle.alpha(SidePanelStyle.PANEL, 0.92));
		g.fillRect(bx, 1, bw, Math.min(bh, plotH - 2));
		g.setColor(SidePanelStyle.DIVIDER);
		g.drawRect(bx, 1, bw - 1, Math.min(bh, plotH - 2) - 1);
		for (int i = 0; i < lines.size(); i++)
		{
			final int base = 3 + i * lh + fs.getAscent();
			if (base > plotH - 2)
			{
				break;
			}
			final boolean note = lines.get(i).length > 2 && lines.get(i)[2] == null;
			if (colours.get(i) != null)
			{
				g.setColor(colours.get(i));
				g.fillRect(bx + 4, base - fs.getAscent() + 2, 6, 6);
			}
			g.setColor(i == 0 ? SidePanelStyle.SECONDARY : note ? SidePanelStyle.MUTED : SidePanelStyle.BODY);
			g.drawString(lines.get(i)[0], bx + (i == 0 || note ? 4 : 12), base);
			g.setColor(SidePanelStyle.VALUE);
			g.drawString(lines.get(i)[1], bx + bw - 4 - fs.stringWidth(lines.get(i)[1]), base);
		}
	}
}

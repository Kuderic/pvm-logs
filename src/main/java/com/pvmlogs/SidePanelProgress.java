package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * T-190 (Eric, 2026-11-09: direction A for History's top): one boss version's progress on History's boss card, from
 * T-187's record ({@link SidePanelHistory.Progress}): the personal bests, the trend with average luck, how the grades
 * spread, the A-or-better streak and badges, in place of the usual's four tiles. Built when History refreshes (its tab
 * opened, a boss or version picked), never per tick.
 */
final class SidePanelProgress
{
	private SidePanelProgress()
	{
	}

	/** The blocks, top to bottom, for the boss card. */
	static JPanel block(SidePanelHistory.Progress p, int width)
	{
		final JPanel out = SidePanelParts.stack(6);
		if (p.note != null)
		{
			out.add(new SidePanelParts.Paragraph(p.note, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL, width));
		}
		if (!p.bests.isEmpty())
		{
			final JPanel bests = SidePanelParts.stack(3);
			bests.add(new SidePanelText(Text.title("Personal bests", SidePanelStyle.VALUE), false));
			for (PanelSnapshot.Row r : p.bests)
			{
				bests.add(new SidePanelParts.Line(r));
			}
			out.add(bests);
		}
		if (p.trendTitle != null)
		{
			// T-199: a block of its own, after a new block's gap, so it never reads as a fourth best: its small header,
			// then the two medians and the change.
			final JPanel trend = SidePanelParts.stack(3);
			trend.setBorder(new javax.swing.border.EmptyBorder(4, 0, 0, 0));
			trend.add(new SidePanelText(Text.small(p.trendTitle, SidePanelStyle.SECONDARY), false));
			final SidePanelParts.Line line = new SidePanelParts.Line(p.trendMedians, p.trend);
			line.setTip(p.trendTip);
			trend.add(line);
			out.add(trend);
		}
		final JPanel grades = SidePanelParts.stack(3);
		grades.add(new SidePanelParts.Line(Text.small("Grades", SidePanelStyle.SECONDARY), Text.small(
			p.kills < 10 ? "fixed bands" : "against your last 50", SidePanelStyle.MUTED)));
		grades.add(new GradeBar(p.grades));
		out.add(grades);
		out.add(new SidePanelParts.Line(Text.small("A or better streak", SidePanelStyle.BODY), Text.small(p.streak,
			SidePanelStyle.SECONDARY)));
		if (!p.badges.isEmpty())
		{
			out.add(new Badges(p.badges, width));
		}
		return out;
	}

	/** T-190: the grades' spread, S to C, as one bar: each grade's share in its colour, its letter and count on it. */
	static final class GradeBar extends JComponent
	{
		static final int HEIGHT = 16;
		private final int[] counts;

		GradeBar(int[] counts)
		{
			this.counts = counts.clone();
			setOpaque(false);
			final StringBuilder tip = new StringBuilder("<html>Your grades over the kills tracked:");
			final String[] names = {"S", "A", "B", "C"};
			for (int i = 0; i < 4; i++)
			{
				tip.append(i == 0 ? " " : ", ").append(names[i]).append(' ').append(counts[i]);
			}
			setToolTipText(tip.append(".</html>").toString());
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(0, HEIGHT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = (Graphics2D) g0.create();
			SidePanelStyle.textHints(g);
			final String[] names = {"S", "A", "B", "C"};
			int total = 0;
			for (int c : counts)
			{
				total += Math.max(0, c);
			}
			final int w = getWidth();
			g.setColor(SidePanelStyle.TRACK);
			g.fillRect(0, 0, w, HEIGHT);
			if (total == 0)
			{
				g.dispose();
				return;
			}
			g.setFont(SidePanelStyle.Type.SMALL.font());
			final FontMetrics fm = g.getFontMetrics();
			int x = 0;
			int left = w;
			int share = total;
			for (int i = 0; i < 4; i++)
			{
				if (counts[i] <= 0)
				{
					continue;
				}
				// Each segment its share, the last taking what's left, so the bar ends flush.
				final int sw = share == counts[i] ? left : (int) Math.round((double) w * counts[i] / total);
				final Color c = SidePanelModel.gradeColour(names[i]);
				g.setColor(c);
				g.fillRect(x, 0, Math.max(1, sw - 1), HEIGHT);
				final String label = names[i] + " " + counts[i];
				final String shown = fm.stringWidth(label) + 4 <= sw ? label : fm.stringWidth(names[i]) + 4 <= sw
					? names[i] : "";
				g.setColor(SidePanelStyle.CARD);
				g.drawString(shown, x + 3, (HEIGHT + fm.getAscent() - fm.getDescent()) / 2);
				x += sw;
				left -= sw;
				share -= counts[i];
			}
			g.dispose();
		}
	}

	/** T-190: the badges earned, flowed onto as many rows as they need, each with its tooltip. */
	static final class Badges extends JComponent
	{
		private final List<PanelSnapshot.Badge> badges;
		private final int width;
		private final List<Rectangle> boxes = new ArrayList<>();

		Badges(List<PanelSnapshot.Badge> badges, int width)
		{
			this.badges = new ArrayList<>(badges);
			this.width = width;
			setOpaque(false);
			setToolTipText("");
		}

		@Override
		public Dimension getPreferredSize()
		{
			final int rows = SidePanelKillCard.badgeRows(badges, width);
			return new Dimension(0, rows * SidePanelKillCard.badgeHeight() + (rows - 1) * 4);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			for (int i = 0; i < boxes.size(); i++)
			{
				if (boxes.get(i).contains(e.getPoint()))
				{
					return badges.get(i).tip;
				}
			}
			return null;
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = (Graphics2D) g0.create();
			SidePanelStyle.textHints(g);
			boxes.clear();
			int x = 0;
			int y = 0;
			for (PanelSnapshot.Badge b : badges)
			{
				final int bw = SidePanelKillCard.badgeWidth(b);
				if (x > 0 && x + bw > getWidth())
				{
					x = 0;
					y += SidePanelKillCard.badgeHeight() + 4;
				}
				SidePanelKillCard.paintBadge(g, b, x, y, bw);
				boxes.add(new Rectangle(x, y, bw, SidePanelKillCard.badgeHeight()));
				x += bw + 4;
			}
			g.dispose();
		}
	}
}

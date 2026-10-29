package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Eric's tweaks of 2026-10-29: History's boss list says {@code 13 kills · 1:58}, the kill rows' dates are
 * right-aligned against the luck chip, and a damage source's icon has no box behind it, so its share bar shows through.
 */
public class SidePanelHistoryTweaksTest
{
	@Test
	public void theBossListSaysKills()
	{
		final List<SidePanelHistory.BossEntry> bosses = SidePanelHistory.bosses(SidePanelHistoryExamples.history(), false, "");
		final SidePanelHistory.BossEntry vardorvis = bosses.get(0);
		assertEquals("Vardorvis", vardorvis.name);
		assertTrue(vardorvis.right(), vardorvis.right().matches(vardorvis.kills + " kills · \\d+:\\d\\d"));
		assertEquals("1 kill · 1:49", new SidePanelHistory.BossEntry("Zulrah", 1, "1:49", new ArrayList<>(), true).right());
		assertEquals("3 kills", new SidePanelHistory.BossEntry("Abyssal demon", 3, null, new ArrayList<>(), false).right());
	}

	private static SidePanelKillRow row(String clock, String chip, String execution)
	{
		final SidePanelHistory.KillRow k = new SidePanelHistory.KillRow(null, clock, "12:40 pm",
			PanelSnapshot.Text.small(chip, SidePanelStyle.SECONDARY), SidePanelStyle.CHIP_NEUTRAL, execution, null, false);
		final SidePanelKillRow r = SidePanelKillRow.history(k, null);
		r.setSize(SidePanelStyle.CONTENT_WIDTH, SidePanelKillRow.rowHeight());
		SidePanelPictures.layout(r);
		return r;
	}

	private static List<SidePanelText> texts(Container c)
	{
		final List<SidePanelText> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelText)
			{
				out.add((SidePanelText) k);
			}
			if (k instanceof Container)
			{
				out.addAll(texts((Container) k));
			}
		}
		return out;
	}

	private static int x(SidePanelText t, Container root)
	{
		return javax.swing.SwingUtilities.convertPoint(t.getParent(), t.getLocation(), root).x;
	}

	/** Where {@code t}'s drawn pixels start and end, in its own coordinates. */
	private static int[] drawn(SidePanelText t)
	{
		final BufferedImage img = new BufferedImage(t.getWidth(), t.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		t.paint(g);
		g.dispose();
		int first = -1;
		int last = -1;
		for (int px = 0; px < img.getWidth(); px++)
		{
			for (int py = 0; py < img.getHeight(); py++)
			{
				if ((img.getRGB(px, py) >>> 24) != 0)
				{
					first = first < 0 ? px : first;
					last = px;
				}
			}
		}
		return new int[]{first, last};
	}

	@Test
	public void theKillTimeOnTheLeftAndTheDateAgainstTheLuckChip()
	{
		final SidePanelKillRow wide = row("2:16", "bottom 14%", "84%");
		final SidePanelKillRow narrow = row("1:57", "average", "100%");
		final List<SidePanelText> w = texts(wide);
		final List<SidePanelText> n = texts(narrow);
		// The time in bold on the left, then the date, the chip and the execution.
		assertEquals("2:16", w.get(0).getText());
		assertEquals("12:40 pm", w.get(1).getText());
		assertEquals("84%", w.get(2).getText());
		assertEquals(x(w.get(0), wide), x(n.get(0), narrow));
		assertTrue(drawn(w.get(0))[0] <= 3);
		// The date right-aligned and ending in line on every row, whatever the chip's or the execution's width.
		assertEquals(x(w.get(1), wide) + w.get(1).getWidth(), x(n.get(1), narrow) + n.get(1).getWidth());
		assertEquals(x(w.get(2), wide) + w.get(2).getWidth(), x(n.get(2), narrow) + n.get(2).getWidth());
		final int[] d = drawn(n.get(1));
		assertTrue("drawn at " + d[0] + ".." + d[1] + " of " + n.get(1).getWidth(), d[1] >= n.get(1).getWidth() - 3
			&& d[0] > 0);
		assertTrue(x(n.get(1), narrow) + d[0] > SidePanelStyle.CONTENT_WIDTH / 4);
	}

	@Test
	public void anOlderKillsDateGivesUpItsTimeOfDayFirst()
	{
		final SidePanelHistory.KillRow k = new SidePanelHistory.KillRow(null, "2:16", "26 Oct 9:40 pm",
			PanelSnapshot.Text.small("bottom 14%", SidePanelStyle.COST), SidePanelStyle.CHIP_COST, "84%", null, false);
		final SidePanelKillRow r = SidePanelKillRow.history(k, null);
		r.setSize(SidePanelStyle.CONTENT_WIDTH, SidePanelKillRow.rowHeight());
		SidePanelPictures.layout(r);
		final SidePanelText date = texts(r).get(1);
		drawn(date);
		assertTrue(date.lastShown(), date.lastShown().equals("26 Oct 9:40 pm") || date.lastShown().equals("26 Oct"));
	}

	@Test
	public void theDatesShortForms()
	{
		assertEquals("26 Oct", SidePanelKillRow.dateShort("26 Oct 9:40 pm"));
		assertEquals("12:43", SidePanelKillRow.dateShort("12:43 pm"));
		assertEquals(null, SidePanelKillRow.dateShort("today"));
	}

	@Test
	public void theColumnsFollowTheTextSettings()
	{
		// Built with the RuneScape fonts, then Smooth: the columns are measured again, so the dates still end in line.
		final SidePanelKillRow wide = row("2:16", "bottom 14%", "84%");
		final SidePanelKillRow narrow = row("1:57", "average", "93%");
		SidePanelStyle.setText(false, true);
		try
		{
			for (SidePanelKillRow r : new SidePanelKillRow[]{wide, narrow})
			{
				for (SidePanelText t : texts(r))
				{
					t.restyle();
				}
				SidePanelPictures.layout(r);
			}
			final List<SidePanelText> w = texts(wide);
			final List<SidePanelText> n = texts(narrow);
			assertEquals(x(w.get(1), wide) + w.get(1).getWidth(), x(n.get(1), narrow) + n.get(1).getWidth());
		}
		finally
		{
			SidePanelStyle.setText(false, false);
		}
	}

	@Test
	public void aSourcesIconHasNoBoxBehindIt()
	{
		final PanelSnapshot.Source s = new PanelSnapshot.Source("Abyssal tentacle", "48 attacks", "612", "100%", 1.0,
			SidePanelStyle.MELEE);
		final SidePanelParts.SourceRowView view = new SidePanelParts.SourceRowView(s);
		view.setSize(SidePanelStyle.CONTENT_WIDTH, view.getPreferredSize().height);
		SidePanelPictures.layout(view);
		final BufferedImage img = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		view.printAll(g);
		g.dispose();
		// A corner of the icon box (away from its swatch) shows the full share bar, the same as beside the icon.
		final Color inIcon = new Color(img.getRGB(4, 4));
		final Color bar = new Color(img.getRGB(view.getWidth() / 2, 1));
		assertEquals(bar, inIcon);
		assertNotEquals(new Color(0x2A2A2A), inIcon);
	}
}

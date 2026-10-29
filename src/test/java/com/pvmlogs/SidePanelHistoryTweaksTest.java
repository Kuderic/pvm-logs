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
 * Eric's tweaks of 2026-10-29: History's boss list says {@code 13 kills · 1:58}, the kill rows' times are
 * right-aligned in one column, and a damage source's icon has no box behind it, so its share bar shows through.
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

	private static SidePanelKillRow row(String clock)
	{
		final SidePanelHistory.KillRow k = new SidePanelHistory.KillRow(null, clock, "12:40 pm",
			PanelSnapshot.Text.small("average", SidePanelStyle.SECONDARY), SidePanelStyle.CHIP_NEUTRAL, "93%", null, false);
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

	@Test
	public void killTimesAreRightAlignedInOneColumn()
	{
		final SidePanelKillRow wide = row("2:16");
		final SidePanelKillRow narrow = row("1:57");
		final SidePanelText wideClock = texts(wide).get(0);
		final SidePanelText narrowClock = texts(narrow).get(0);
		final SidePanelText wideDate = texts(wide).get(1);
		final SidePanelText narrowDate = texts(narrow).get(1);
		assertEquals("12:40 pm", wideDate.getText());
		// The same column, so the dates start in line.
		assertEquals(x(wideClock, wide) + wideClock.getWidth(), x(narrowClock, narrow) + narrowClock.getWidth());
		assertEquals(x(wideDate, wide), x(narrowDate, narrow));
		// And the times drawn against its right edge.
		final BufferedImage img = new BufferedImage(narrowClock.getWidth(), narrowClock.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		narrowClock.paint(g);
		g.dispose();
		int last = -1;
		int first = -1;
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
		assertTrue("drawn at " + first + ".." + last + " of " + img.getWidth(), last >= img.getWidth() - 3);
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

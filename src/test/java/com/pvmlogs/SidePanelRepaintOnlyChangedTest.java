package com.pvmlogs;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T-223: after an update that changed only values, the Fight tab asks for one pixel and leaves the rest to each value's
 * own repaint ({@link SidePanelFightTab#joins}). That's right only if every pixel the update changed lies in an area some
 * component asked to repaint. A Hydra kill through the panel: each update's whole tab drawn before and after, every
 * changed pixel checked against the repaints asked for (recorded by a {@link RepaintManager} that keeps what it's told,
 * which Swing's own drops while nothing is on screen).
 */
public class SidePanelRepaintOnlyChangedTest
{
	/** Every repaint asked for, in the asking component's coordinates. */
	static final class Recorder extends RepaintManager
	{
		final List<JComponent> who = new ArrayList<>();
		final List<Rectangle> where = new ArrayList<>();

		@Override
		public void addDirtyRegion(JComponent c, int x, int y, int w, int h)
		{
			who.add(c);
			where.add(new Rectangle(x, y, w, h));
		}

		void clear()
		{
			who.clear();
			where.clear();
		}

		/** What was asked, in {@code tab}'s coordinates; the tab's own one-pixel join left out. */
		List<Rectangle> in(JComponent tab)
		{
			final List<Rectangle> out = new ArrayList<>();
			for (int i = 0; i < who.size(); i++)
			{
				final JComponent c = who.get(i);
				if (c == tab && where.get(i).width == 1 && where.get(i).height == 1)
				{
					continue;
				}
				if (c == tab || SwingUtilities.isDescendingFrom(c, tab))
				{
					out.add(SwingUtilities.convertRectangle(c, where.get(i), tab));
				}
			}
			return out;
		}
	}

	@Test
	public void everyPixelAValuesOnlyUpdateChangesIsInAnAreaAskedToRepaint()
	{
		final RepaintManager before = RepaintManager.currentManager((JComponent) null);
		final Recorder recorder = new Recorder();
		RepaintManager.setCurrentManager(recorder);
		try
		{
			final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
			final SidePanelWeaponRows rows = new SidePanelWeaponRows();
			final PvmPerformancePanel.ChartBook book = new PvmPerformancePanel.ChartBook();
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setCelebrations(true);
			p.setSize(242, 1000);
			final SidePanelFightTab tab = p.fightTab();
			h.run(6);
			p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, book.from(h.room, h.view().states.length), false)));
			SidePanelHydraLagTest.settle(tab);
			int checked = 0;
			int whole = 0;
			while (h.tick < 212)
			{
				// T-223's cadence: 3 ticks, so updates land where a booking would put them as well as at 5.
				h.run(3);
				final PanelSnapshot snap = SidePanelHydraLagTest.snapshot(h.input(rows, book.from(h.room,
					h.view().states.length), false));
				final BufferedImage was = draw(tab);
				final int joins = tab.joins();
				recorder.clear();
				p.apply(snap);
				if (tab.joins() == joins)
				{
					// A rebuild or a layout: the whole tab repaints, as before.
					SidePanelHydraLagTest.settle(tab);
					whole++;
					continue;
				}
				// Nothing laid out, as in game when nothing asked for it.
				checked += check(was, draw(tab), recorder.in(tab), tab, "tick " + h.tick);
			}
			assertTrue("values-only updates checked: " + checked, checked >= 40);
			assertTrue("whole repaints: " + whole, whole <= 10);
		}
		finally
		{
			RepaintManager.setCurrentManager(before);
		}
	}

	@Test
	public void inAPartyToo()
	{
		final RepaintManager before = RepaintManager.currentManager((JComponent) null);
		final Recorder recorder = new Recorder();
		RepaintManager.setCurrentManager(recorder);
		try
		{
			final SidePanelPartyLagTest.Party party = new SidePanelPartyLagTest.Party();
			party.run(130);
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setSize(242, 1400);
			final SidePanelFightTab tab = p.fightTab();
			p.apply(party.snapshot());
			SidePanelHydraLagTest.settle(tab);
			int checked = 0;
			for (int i = 0; i < 40; i++)
			{
				party.run(3);
				final PanelSnapshot snap = party.snapshot();
				final BufferedImage was = draw(tab);
				final int joins = tab.joins();
				recorder.clear();
				p.apply(snap);
				if (tab.joins() == joins)
				{
					SidePanelHydraLagTest.settle(tab);
					continue;
				}
				checked += check(was, draw(tab), recorder.in(tab), tab, "update " + i);
			}
			assertTrue("values-only updates checked: " + checked, checked >= 20);
		}
		finally
		{
			RepaintManager.setCurrentManager(before);
		}
	}

	/** Every changed pixel in an area asked for: 1. */
	static int check(BufferedImage was, BufferedImage now, List<Rectangle> asked, JComponent tab, String when)
	{
		assertEquals(was.getHeight(), now.getHeight());
		for (int y = 0; y < now.getHeight(); y++)
		{
			for (int x = 0; x < now.getWidth(); x++)
			{
				if (was.getRGB(x, y) != now.getRGB(x, y) && !inside(asked, x, y))
				{
					fail(String.format("%s: pixel %d,%d changed with no repaint asked over it (%s); asked: %s", when, x, y,
						SwingUtilities.getDeepestComponentAt(tab, x, y).getClass().getSimpleName(), asked));
				}
			}
		}
		return 1;
	}

	@Test
	public void aRebuildStillRepaintsTheWholeTab()
	{
		final RepaintManager before = RepaintManager.currentManager((JComponent) null);
		final Recorder recorder = new Recorder();
		RepaintManager.setCurrentManager(recorder);
		try
		{
			final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setSize(242, 1000);
			final SidePanelFightTab tab = p.fightTab();
			h.run(6);
			final SidePanelWeaponRows rows = new SidePanelWeaponRows();
			final PvmPerformancePanel.ChartBook book = new PvmPerformancePanel.ChartBook();
			recorder.clear();
			p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, book.from(h.room, h.view().states.length), false)));
			boolean whole = false;
			for (int i = 0; i < recorder.who.size(); i++)
			{
				whole |= recorder.who.get(i) == tab && recorder.where.get(i).width == tab.getWidth()
					&& recorder.where.get(i).height == tab.getHeight();
			}
			assertTrue(whole);
			assertEquals(0, tab.joins());
		}
		finally
		{
			RepaintManager.setCurrentManager(before);
		}
	}

	@Test
	public void theRepaintAfterApplyIsSplitAtTheTabsPaint()
	{
		// Apply ended at 100, the tab's paint ran 103 to 104, the marker at 110: 3 before, 6 after.
		final long[] split = PvmPerformancePanel.repaintSplit(100, 103, 104, 110);
		assertEquals(3, split[0]);
		assertEquals(6, split[1]);
		// The last paint was before this apply: no split.
		assertEquals(null, PvmPerformancePanel.repaintSplit(100, 90, 95, 110));
	}

	@Test
	public void aBufferedPaintNotesWhenItRan()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setSize(242, 1000);
		h.run(30);
		p.apply(SidePanelHydraLagTest.snapshot(h.input(new SidePanelWeaponRows(), 0, false)));
		SidePanelHydraLagTest.settle(p.fightTab());
		final long t0 = System.nanoTime();
		draw(p.fightTab());
		assertTrue(SidePanelBuffer.lastStarted() - t0 >= 0);
		assertTrue(SidePanelBuffer.lastEnded() - SidePanelBuffer.lastStarted() >= 0);
	}

	static boolean inside(List<Rectangle> rs, int x, int y)
	{
		for (Rectangle r : rs)
		{
			if (r.contains(x, y))
			{
				return true;
			}
		}
		return false;
	}

	static BufferedImage draw(JComponent tab)
	{
		final BufferedImage img = new BufferedImage(tab.getWidth(), tab.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		tab.paint(g);
		g.dispose();
		return img;
	}
}

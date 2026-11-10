package com.pvmlogs;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T-186: what the prototypes cost against today's Fight tab (T-046, T-133): the same ten-minute fight, a snapshot
 * every 5 ticks, on today's tab, direction A (the kill card in place of the title and tiles, sections folded) and
 * direction B (the strip under the title); then the count-up's frames at the kill. Printed side by side; no timing
 * asserts (CI's timings aren't Eric's), only that the cards never rebuild the tab or ask for layout.
 */
public class SidePanelFunLagTest
{
	@Test
	public void theCardsCostWhatTheTilesDo()
	{
		final SidePanelPartyLagTest.Live live = new SidePanelPartyLagTest.Live(27);
		live.run(130);
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final SidePanelFun.Past past = SidePanelFunPictures.araxxorPast();
		final PvmPerformancePanel today = panel();
		final PvmPerformancePanel a = panel();
		final PvmPerformancePanel b = panel();
		final PvmPerformancePanel open = panel();
		int sent = live.view().states.length;
		SidePanelModel.Input in = live.input(rows, 0);
		final PanelSnapshot first = new PanelSnapshot(SidePanelModel.fight(in), null);
		today.apply(first);
		a.apply(first);
		b.apply(first);
		open.apply(first);
		final SidePanelFun.Facts f0 = SidePanelFun.of(new LedgerRows.Numbers(in.scope), past, null, 0, null, true);
		final SidePanelFunCard.KillCard card = SidePanelFunPictures.directionA(a, "Verzik Vitur", "live", f0);
		final SidePanelFunCard.Strip strip = SidePanelFunPictures.directionB(b, "Verzik Vitur", f0);
		final SidePanelFunCard.KillCard openCard = SidePanelFunPictures.directionA(open, "Verzik Vitur", "live", f0);
		SidePanelFunPictures.openAll(open.fightTab());
		final PvmPerformancePanel[] panels = {today, a, b, open};
		final JComponent[] tabs = {today.fightTab(), a.fightTab(), b.fightTab(), open.fightTab()};
		for (JComponent t : tabs)
		{
			settle(t);
		}
		final int[] builds = {today.fightTab().builds(), a.fightTab().builds(), b.fightTab().builds(),
			open.fightTab().builds()};
		final List<List<Long>> apply = lists();
		final List<List<Long>> paint = lists();
		final List<Long> facts = new ArrayList<>();
		final int[] layouts = new int[4];
		int updates = 0;
		while (live.tick < 1000)
		{
			live.run(PvmPerformancePanel.UPDATE_TICKS);
			final int from = Math.max(0, sent - PvmPerformancePanel.CHART_RESEND);
			sent = live.view().states.length;
			in = live.input(rows, from);
			final PanelSnapshot snap = new PanelSnapshot(SidePanelModel.fight(in), null);
			updates++;
			for (int i = 0; i < 4; i++)
			{
				final int before = SidePanelParts.relayouts;
				final long t0 = System.nanoTime();
				panels[i].apply(snap);
				if (i > 0)
				{
					final long f0n = System.nanoTime();
					final SidePanelFun.Facts f = SidePanelFun.of(new LedgerRows.Numbers(in.scope), past, null, 0, null,
						true);
					facts.add(System.nanoTime() - f0n);
					if (i == 1)
					{
						card.set(f);
					}
					else if (i == 2)
					{
						strip.set(f);
					}
					else
					{
						openCard.set(f);
					}
				}
				apply.get(i).add(System.nanoTime() - t0);
				if (SidePanelParts.relayouts != before)
				{
					layouts[i]++;
					settle(tabs[i]);
				}
				paint.get(i).add(paint(tabs[i], null));
			}
		}
		for (int i = 0; i < 4; i++)
		{
			assertEquals("no rebuild while the fight runs", builds[i], panels[i].fightTab().builds());
		}
		// The count-up: 20 frames, each a repaint of the card's box through the tab's buffer.
		final List<Long> frame = new ArrayList<>();
		final List<Long> frameWhole = new ArrayList<>();
		final Rectangle box = card.getBounds();
		for (int k = 0; k <= 20; k++)
		{
			card.shown = k / 20.0;
			frame.add(paint(a.fightTab(), box));
			frameWhole.add(paint(card, null));
		}
		final String report = String.format("T-186, %d updates over ten minutes, a live fight:%n"
				+ "  today's tab:       apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layouts %d%n"
				+ "  A, the kill card:  apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layouts %d%n"
				+ "  B, the strip:      apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layouts %d%n"
				+ "  A, sections open:  apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layouts %d%n"
				+ "  the facts alone (grade over 50, PB and rank over 198): p50 %.3f p99 %.3f ms%n"
				+ "  count-up frame (the card's box through the tab's buffer): p50 %.2f p99 %.2f ms; the card alone p50 %.2f ms",
			updates, ms(apply.get(0), .5), ms(apply.get(0), .99), ms(paint.get(0), .5), ms(paint.get(0), .99), layouts[0],
			ms(apply.get(1), .5), ms(apply.get(1), .99), ms(paint.get(1), .5), ms(paint.get(1), .99), layouts[1],
			ms(apply.get(2), .5), ms(apply.get(2), .99), ms(paint.get(2), .5), ms(paint.get(2), .99), layouts[2],
			ms(apply.get(3), .5), ms(apply.get(3), .99), ms(paint.get(3), .5), ms(paint.get(3), .99), layouts[3],
			ms(facts, .5), ms(facts, .99), ms(frame, .5), ms(frame, .99), ms(frameWhole, .5));
		System.out.println(report);
		assertTrue(updates >= 170);
		// The strip is painted at a fixed height: it adds no layout pass to today's. A's counts are the prototype's, printed
		// only: the title and tiles it took out still get their updates, detached and never laid out, so each value change
		// asks again (31 with every section open); its folded sections' hidden bodies do the same (36 folded). A real build
		// has no title or tiles to update, and should skip a folded section's body until it opens.
		assertTrue("B " + layouts[2] + " against " + layouts[0], layouts[2] <= layouts[0]);
		assertTrue("A open " + layouts[3] + " against folded " + layouts[1], layouts[3] <= layouts[1]);
	}

	/** After the kill: today's tab against A's (the sections folded) and B's, one paint each, warmed. */
	@Test
	public void afterTheKillAFoldedTabPaintsLess()
	{
		final SidePanelModel.Input in = SidePanelFunPictures.araxxorKill();
		final SidePanelFun.Facts f = SidePanelFunPictures.facts(in, SidePanelFunPictures.araxxorPast(), 555, false);
		final PvmPerformancePanel today = SidePanelFunPictures.today(in);
		final PvmPerformancePanel a = SidePanelFunPictures.today(in);
		SidePanelFunPictures.directionA(a, "Araxxor", "KC 555", f);
		final PvmPerformancePanel b = SidePanelFunPictures.today(in);
		SidePanelFunPictures.directionB(b, "Araxxor", f);
		final JComponent[] tabs = {today.fightTab(), a.fightTab(), b.fightTab()};
		final List<List<Long>> paint = lists();
		for (JComponent t : tabs)
		{
			settle(t);
		}
		for (int k = 0; k < 200; k++)
		{
			for (int i = 0; i < 3; i++)
			{
				paint.get(i).add(paint(tabs[i], null));
			}
		}
		System.out.println(String.format("T-186, after the kill, a whole paint (200, warmed):%n"
				+ "  today's tab %d px tall: p50 %.2f p99 %.2f ms%n"
				+ "  A %d px tall: p50 %.2f p99 %.2f ms%n"
				+ "  B %d px tall: p50 %.2f p99 %.2f ms",
			tabs[0].getHeight(), ms(paint.get(0), .5), ms(paint.get(0), .99),
			tabs[1].getHeight(), ms(paint.get(1), .5), ms(paint.get(1), .99),
			tabs[2].getHeight(), ms(paint.get(2), .5), ms(paint.get(2), .99)));
		assertTrue(tabs[1].getHeight() < tabs[0].getHeight());
	}

	/** The count-up never runs hidden: a card that isn't showing stops at its first frame and shows the end. */
	@Test
	public void theCountUpStopsWhenHidden() throws Exception
	{
		final SidePanelFunCard.KillCard card = new SidePanelFunCard.KillCard("Araxxor", "KC 555",
			SidePanelFunPictures.facts(SidePanelFunPictures.araxxorKill(), SidePanelFunPictures.araxxorPast(), 555,
				false));
		javax.swing.SwingUtilities.invokeAndWait(card::countUp);
		assertTrue(card.counting());
		final long end = System.nanoTime() + 2_000_000_000L;
		while (card.counting() && System.nanoTime() < end)
		{
			Thread.sleep(10);
		}
		assertFalse(card.counting());
		assertEquals(1.0, card.shown, 0);
		assertEquals(0, card.frames);
	}

	private static PvmPerformancePanel panel()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setSize(242, 1000);
		return p;
	}

	private static List<List<Long>> lists()
	{
		final List<List<Long>> l = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			l.add(new ArrayList<>());
		}
		return l;
	}

	private static void settle(JComponent tab)
	{
		tab.setSize(242, 10_000);
		SidePanelPictures.layout(tab);
		tab.setSize(242, tab.getPreferredSize().height);
		SidePanelPictures.layout(tab);
	}

	/** A paint of {@code c}, clipped to {@code clip} when given, as a child's repaint comes through the tab; ns. */
	private static long paint(JComponent c, Rectangle clip)
	{
		final BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		if (clip != null)
		{
			g.setClip(clip);
		}
		final long t0 = System.nanoTime();
		c.paint(g);
		final long took = System.nanoTime() - t0;
		g.dispose();
		return took;
	}

	private static double ms(List<Long> ns, double q)
	{
		final List<Long> s = new ArrayList<>(ns);
		Collections.sort(s);
		return s.get(Math.min(s.size() - 1, (int) Math.floor(q * s.size()))) / 1e6;
	}
}

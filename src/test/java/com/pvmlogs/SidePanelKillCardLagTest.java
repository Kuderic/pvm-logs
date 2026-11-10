package com.pvmlogs;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T-188 (T-046, T-133): the kill card costs no more than today's title and tiles. The same ten-minute fight, a
 * snapshot every 5 ticks, on today's tab (Celebrations off) and with the card and the sections folded under it; then
 * a whole paint after a kill. Printed side by side; no timing asserts (CI's timings aren't Eric's), only that the card
 * never rebuilds the tab and adds no layout pass.
 */
public class SidePanelKillCardLagTest
{
	@Test
	public void theCardCostsWhatTheTitleAndTilesDo()
	{
		final SidePanelPartyLagTest.Live live = new SidePanelPartyLagTest.Live(27);
		live.run(130);
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel today = panel(false);
		final PvmPerformancePanel card = panel(true);
		int sent = live.view().states.length;
		today.apply(snapshot(live.input(rows, 0), false));
		card.apply(snapshot(live.input(rows, 0), true));
		final JComponent[] tabs = {today.fightTab(), card.fightTab()};
		final PvmPerformancePanel[] panels = {today, card};
		for (JComponent t : tabs)
		{
			settle(t);
		}
		final int[] builds = {today.fightTab().builds(), card.fightTab().builds()};
		final List<List<Long>> apply = lists();
		final List<List<Long>> paint = lists();
		final List<Long> build = new ArrayList<>();
		final int[] layouts = new int[2];
		int updates = 0;
		while (live.tick < 1000)
		{
			live.run(PvmPerformancePanel.UPDATE_TICKS);
			final int from = Math.max(0, sent - PvmPerformancePanel.CHART_RESEND);
			sent = live.view().states.length;
			updates++;
			for (int i = 0; i < 2; i++)
			{
				final long b0 = System.nanoTime();
				final PanelSnapshot snap = snapshot(live.input(rows, from), i == 1);
				if (i == 1)
				{
					build.add(System.nanoTime() - b0);
				}
				final int before = SidePanelParts.relayouts;
				final long t0 = System.nanoTime();
				panels[i].apply(snap);
				apply.get(i).add(System.nanoTime() - t0);
				if (SidePanelParts.relayouts != before)
				{
					layouts[i]++;
					settle(tabs[i]);
				}
				paint.get(i).add(paint(tabs[i]));
			}
		}
		// After a kill: a whole paint of each, warmed.
		final PvmPerformancePanel todayKill = panel(false);
		todayKill.apply(snapshot(SidePanelKillCardExamples.off(), false));
		final PvmPerformancePanel cardKill = panel(true);
		cardKill.apply(snapshot(SidePanelKillCardExamples.araxxorKill(), true));
		settle(todayKill.fightTab());
		settle(cardKill.fightTab());
		final List<List<Long>> kill = lists();
		for (int k = 0; k < 200; k++)
		{
			kill.get(0).add(paint(todayKill.fightTab()));
			kill.get(1).add(paint(cardKill.fightTab()));
		}
		System.out.println(String.format("T-188, %d updates over ten minutes, a live fight:%n"
				+ "  today's tab:          apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layouts %d%n"
				+ "  the card, folded below: apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layouts %d%n"
				+ "  the snapshot's build with the card: p50 %.2f p99 %.2f ms%n"
				+ "after a kill, a whole paint (200, warmed): today's %d px p50 %.2f p99 %.2f ms; the card's %d px p50 %.2f"
				+ " p99 %.2f ms",
			updates, ms(apply.get(0), .5), ms(apply.get(0), .99), ms(paint.get(0), .5), ms(paint.get(0), .99), layouts[0],
			ms(apply.get(1), .5), ms(apply.get(1), .99), ms(paint.get(1), .5), ms(paint.get(1), .99), layouts[1],
			ms(build, .5), ms(build, .99), todayKill.fightTab().getHeight(), ms(kill.get(0), .5), ms(kill.get(0), .99),
			cardKill.fightTab().getHeight(), ms(kill.get(1), .5), ms(kill.get(1), .99)));
		assertTrue(updates >= 170);
		for (int i = 0; i < 2; i++)
		{
			assertEquals("no rebuild while the fight runs", builds[i], panels[i].fightTab().builds());
		}
		// The card is painted at a fixed height and the folded bodies wait: no more layout passes than today's.
		assertTrue("card " + layouts[1] + " against today's " + layouts[0], layouts[1] <= layouts[0]);
	}

	private static PanelSnapshot snapshot(SidePanelModel.Input in, boolean celebrations)
	{
		in.celebrations = celebrations;
		return new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input()));
	}

	private static PvmPerformancePanel panel(boolean celebrations)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(celebrations);
		p.setSize(242, 1000);
		return p;
	}

	private static List<List<Long>> lists()
	{
		final List<List<Long>> l = new ArrayList<>();
		l.add(new ArrayList<>());
		l.add(new ArrayList<>());
		return l;
	}

	private static void settle(JComponent tab)
	{
		tab.setSize(242, 10_000);
		SidePanelPictures.layout(tab);
		tab.setSize(242, tab.getPreferredSize().height);
		SidePanelPictures.layout(tab);
	}

	/** A whole paint of the tab, through its buffer as on screen; ns. */
	private static long paint(JComponent tab)
	{
		final BufferedImage img = new BufferedImage(Math.max(1, tab.getWidth()), Math.max(1, tab.getHeight()),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		final long t0 = System.nanoTime();
		tab.paint(g);
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

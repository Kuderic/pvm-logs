package com.pvmlogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * T-223: an Alchemical Hydra kill's updates on the Fight tab in a real window (run under Xvfb or on a desktop), with
 * {@link SidePanelRepaintTiming} installed: the time from each apply's end to a marker queued behind its repaint, Swing's
 * whole passes, and the area painted (the tab's own request with the changed values' repaints merged into it).
 * {@code xvfb-run java -cp <test classpath> com.pvmlogs.SidePanelRepaintHydraRepro [-v]}.
 */
public final class SidePanelRepaintHydraRepro
{
	public static void main(String[] args) throws Exception
	{
		verbose = args.length > 0 && "-v".equals(args[0]);
		final SidePanelRepaintTiming timing = new SidePanelRepaintTiming(null, null);
		SwingUtilities.invokeAndWait(() -> javax.swing.RepaintManager.setCurrentManager(timing));
		for (int r = 0; r < 4; r++)
		{
			run(timing);
		}
		System.exit(0);
	}

	static void run(SidePanelRepaintTiming timing) throws Exception
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel.ChartBook book = new PvmPerformancePanel.ChartBook();
		final PvmPerformancePanel[] panel = new PvmPerformancePanel[1];
		final JFrame[] frame = new JFrame[1];
		SwingUtilities.invokeAndWait(() ->
		{
			panel[0] = new PvmPerformancePanel(null, null, null, null);
			panel[0].setCelebrations(true);
			frame[0] = new JFrame("repro");
			frame[0].getContentPane().add(panel[0], BorderLayout.CENTER);
			frame[0].setSize(new Dimension(260, 1000));
			frame[0].setVisible(true);
		});
		h.run(6);
		final List<Long> after = new ArrayList<>();
		final List<Long> passes = new ArrayList<>();
		final List<Long> area = new ArrayList<>();
		final List<Long> unions = new ArrayList<>();
		while (h.tick < 212)
		{
			h.run(PvmPerformancePanel.UPDATE_TICKS);
			final PanelSnapshot s = SidePanelHydraLagTest.snapshot(SidePanelCopy.freeze(h.input(rows,
				book.from(h.room, h.view().states.length), false)));
			final int before = timing.passes().size();
			final long[] ended = new long[1];
			final long[] marker = new long[1];
			SwingUtilities.invokeAndWait(() ->
			{
				timing.applying("Fight");
				panel[0].apply(s);
				ended[0] = System.nanoTime();
				final javax.swing.JComponent tab = panel[0].fightTab();
				final java.awt.Rectangle u = new java.awt.Rectangle();
				final List<String> who = new ArrayList<>();
				walk(tab, tab, u, who);
				final java.awt.Rectangle own = javax.swing.RepaintManager.currentManager(tab).getDirtyRegion(tab);
				if (own != null && !own.isEmpty())
				{
					add(u, own);
				}
				final java.awt.Rectangle vis = tab.getVisibleRect();
				final java.awt.Rectangle ui = u.intersection(vis);
				if (verbose)
				{
					System.out.println("painted " + u + " of visible " + vis + ": " + who);
				}
				unions.add((long) (ui.isEmpty() ? 0 : ui.width * ui.height) * 1_000_000L);
				SwingUtilities.invokeLater(() -> marker[0] = System.nanoTime());
			});
			Thread.sleep(60);
			after.add(marker[0] - ended[0]);
			long sum = 0;
			long px = 0;
			for (int k = before; k < timing.passes().size(); k++)
			{
				final SidePanelRepaintTiming.Pass p = timing.passes().get(k);
				sum += p.totalNanos();
				final long[] t = p.dirty.get("SidePanelFightTab");
				px = Math.max(px, t == null ? 0 : t[1]);
			}
			passes.add(sum);
			area.add(px * 1_000_000L);
		}
		SwingUtilities.invokeAndWait(() -> frame[0].dispose());
		System.out.println(String.format("Hydra Fight tab: repaint after apply p50 %.2f p90 %.2f ms; passes p50 %.2f p90 %.2f"
			+ " ms; the tab's own request p50 %.0f px; painted (with the changed values merged) p50 %.0f p90 %.0f px",
			q(after, .5), q(after, .9), q(passes, .5), q(passes, .9), q(area, .5), q(unions, .5), q(unions, .9)));
	}

	static boolean verbose;

	static void walk(java.awt.Container c, javax.swing.JComponent tab, java.awt.Rectangle u, List<String> who)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof javax.swing.JComponent && k.isVisible())
			{
				final java.awt.Rectangle r = javax.swing.RepaintManager.currentManager(k).getDirtyRegion((javax.swing.JComponent) k);
				if (r != null && !r.isEmpty())
				{
					final java.awt.Rectangle t = javax.swing.SwingUtilities.convertRectangle(k, r, tab);
					add(u, t);
					who.add(k.getClass().getSimpleName() + "@" + t.y + "+" + t.height);
				}
			}
			if (k instanceof java.awt.Container) walk((java.awt.Container) k, tab, u, who);
		}
	}

	static void add(java.awt.Rectangle u, java.awt.Rectangle r)
	{
		if (u.isEmpty())
		{
			u.setBounds(r);
		}
		else
		{
			u.add(r);
		}
	}

	static double q(List<Long> ns, double p)
	{
		final List<Long> s = new ArrayList<>(ns.subList(5, ns.size()));
		Collections.sort(s);
		return s.get((int) Math.min(s.size() - 1, Math.floor(p * s.size()))) / 1e6;
	}
}

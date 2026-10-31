package com.pvmlogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * T-133: the side panel in a real window (run under Xvfb or on a desktop), fed a snapshot every 5 ticks of the same fight
 * on the Fight tab and then on the Party tab, with {@link SidePanelRepaintTiming} installed: each apply's repaint passes,
 * and the time from an apply's end to a marker queued behind them, as PerfProbe's "repaint after apply".
 */
public final class SidePanelRepaintRepro
{
	public static void main(String[] args) throws Exception
	{
		final boolean verbose = args.length > 0 && "-v".equals(args[0]);
		final SidePanelRepaintTiming timing = new SidePanelRepaintTiming(null, verbose ? System.out::println : null);
		SwingUtilities.invokeAndWait(() -> javax.swing.RepaintManager.setCurrentManager(timing));
		for (int tab : new int[]{0, 3, 0, 3})
		{
			run(tab, timing);
		}
		System.exit(0);
	}

	private static void run(int tab, SidePanelRepaintTiming timing) throws Exception
	{
		final SidePanelPartyLagTest.Party party = new SidePanelPartyLagTest.Party();
		party.run(130);
		final PvmPerformancePanel[] panel = new PvmPerformancePanel[1];
		final JFrame[] frame = new JFrame[1];
		SwingUtilities.invokeAndWait(() ->
		{
			panel[0] = new PvmPerformancePanel(null, null, null, null);
			frame[0] = new JFrame("repro");
			frame[0].getContentPane().add(panel[0], BorderLayout.CENTER);
			frame[0].setSize(new Dimension(260, 1000));
			frame[0].setVisible(true);
			panel[0].apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, null));
			panel[0].tabs().select(panel[0].tabs().getTab(tab));
		});
		final List<Long> after = new ArrayList<>();
		final List<Long> passTotals = new ArrayList<>();
		final List<Long> copies = new ArrayList<>();
		for (int i = 0; i < 60; i++)
		{
			party.run(PvmPerformancePanel.UPDATE_TICKS);
			final PanelSnapshot s = tab == 3 ? party.partySnapshot() : party.fightSnapshot();
			final int before = timing.passes().size();
			final SidePanelBuffer buffer = tab == 3 ? panel[0].partyTab().buffer() : panel[0].fightTab().buffer();
			final int paintsBefore = buffer.paints();
			final long[] ended = new long[1];
			final long[] marker = new long[1];
			SwingUtilities.invokeAndWait(() ->
			{
				timing.applying(tab == 3 ? "Party" : "Fight");
				panel[0].apply(s);
				ended[0] = System.nanoTime();
				SwingUtilities.invokeLater(() -> marker[0] = System.nanoTime());
			});
			Thread.sleep(150);
			after.add(marker[0] - ended[0]);
			copies.add((long) (buffer.paints() - paintsBefore) * 1_000_000L);
			long sum = 0;
			for (int k = before; k < timing.passes().size(); k++)
			{
				sum += timing.passes().get(k).totalNanos();
			}
			passTotals.add(sum);
		}
		SwingUtilities.invokeAndWait(() -> frame[0].dispose());
		System.out.println(String.format("%s tab: repaint after apply p50 %.2f p90 %.2f ms; Swing's passes after an apply"
				+ " p50 %.2f p90 %.2f ms; buffered paints (copies to the window) an apply p50 %.0f p90 %.0f",
			tab == 3 ? "Party" : "Fight", q(after, 0.5), q(after, 0.9), q(passTotals, 0.5), q(passTotals, 0.9),
			q(copies, 0.5), q(copies, 0.9)));
	}

	private static double q(List<Long> ns, double p)
	{
		final List<Long> s = new ArrayList<>(ns.subList(10, ns.size()));
		Collections.sort(s);
		return s.get((int) Math.min(s.size() - 1, Math.floor(p * s.size()))) / 1e6;
	}
}

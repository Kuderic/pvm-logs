package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * T-081: the idle time a boss's mechanic forced (T-074's mechanics ticks) in the side panel: a {@code mechanics} line
 * in the details, a neutral stretch of its own in the time budget, and its own colour and legend entry in the tick
 * strip. Nothing new for a boss without allowances, or an old kill.
 */
public class SidePanelMechanicsTest
{
	private static final int FORCED = 7 + 4 + 7 + 4 + 7;

	private static List<String> rows(PanelSnapshot.Rows rows)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : rows.rows)
		{
			out.add(r.toString());
		}
		return out;
	}

	private static double seconds(PanelSnapshot.TimeBudget b, PanelSnapshot.Fill fill)
	{
		double s = 0;
		for (PanelSnapshot.Segment seg : b.segments)
		{
			if (seg.fill == fill)
			{
				s += seg.seconds;
			}
		}
		return s;
	}

	private static PanelSnapshot.BudgetRow budgetRow(PanelSnapshot.TimeBudget b, PanelSnapshot.Fill fill)
	{
		for (PanelSnapshot.BudgetRow r : b.rows)
		{
			if (r.swatch == fill)
			{
				return r;
			}
		}
		return null;
	}

	@Test
	public void theLeviathanKillsDetailsSayMechanics()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.leviathanKill());
		final List<String> details = rows(tab.details);
		// 29 ticks, 17.4 s: beside eating, neutral.
		assertTrue(details.toString(), details.contains("mechanics | 17 s"));
		assertEquals(details.indexOf("eating | 2 s") + 1, details.indexOf("mechanics | 17 s"));
	}

	@Test
	public void theTimeBudgetGivesItANeutralStretchOfItsOwn()
	{
		final SidePanelModel.Input in = SidePanelExamples.leviathanKill();
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
		assertEquals(FORCED * LedgerRows.SECONDS_PER_TICK, seconds(b, PanelSnapshot.Fill.MECHANICS), 1e-9);
		final PanelSnapshot.BudgetRow row = budgetRow(b, PanelSnapshot.Fill.MECHANICS);
		assertNotNull(row);
		assertEquals("mechanics", row.label.text);
		assertEquals("+17 s", row.value.text);
		assertEquals(SidePanelStyle.BODY, row.value.colour);
		// Right after eating's row.
		assertEquals(PanelSnapshot.Fill.EATING, b.rows.get(b.rows.indexOf(row) - 1).swatch);
		// Up to the tick, the stretches still add up to the clock.
		double sum = 0;
		for (PanelSnapshot.Segment seg : b.segments)
		{
			sum += seg.seconds;
		}
		assertEquals(b.total, sum, 1e-6);
	}

	@Test
	public void bestPlayIsTheAttackingTimeAsBefore()
	{
		// Best play is time spent attacking, with no losses, at average luck: the forced time comes out of it, as eating's
		// does, and nothing else moves.
		final SidePanelModel.Input in = SidePanelExamples.leviathanKill();
		final PanelSnapshot.TimeBudget with = SidePanelModel.fight(in).budget;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final double kept = seconds(with, PanelSnapshot.Fill.KEPT);
		final double costs = seconds(with, PanelSnapshot.Fill.COST) + seconds(with, PanelSnapshot.Fill.MINOR_COST)
			+ seconds(with, PanelSnapshot.Fill.EATING);
		assertEquals(n.clock + Math.max(0, n.luck) - Math.max(0, -n.luck) - costs - n.mechanicsCost, kept, 1e-6);
	}

	@Test
	public void theStripHasItsOwnColourAndLegendEntry()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.leviathanKill());
		assertEquals(FORCED, tab.chart.mechanics);
		int drawn = 0;
		for (byte state : tab.chart.states)
		{
			drawn += state == FightTimeline.MECHANIC ? 1 : 0;
		}
		assertEquals(FORCED, drawn);
		final java.awt.Color c = SidePanelTickStrip.colour(FightTimeline.MECHANIC, (byte) 0);
		assertEquals(SidePanelStyle.MECHANICS, c);
		assertFalse(c.equals(SidePanelStyle.EATING) || c.equals(SidePanelStyle.COST) || c.equals(SidePanelStyle.TRACK));
		assertTrue(legend(SidePanelExamples.leviathanKill()).contains("mechanics " + FORCED));
	}

	@Test
	public void nothingNewForABossWithoutAllowances()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		assertFalse(rows(tab.details).toString(), rows(tab.details).stream().anyMatch(r -> r.startsWith("mechanics")));
		assertEquals(null, budgetRow(tab.budget, PanelSnapshot.Fill.MECHANICS));
		assertFalse(tab.budget.segments.stream().anyMatch(seg -> seg.fill == PanelSnapshot.Fill.MECHANICS));
		assertEquals(0, tab.chart.mechanics);
		assertFalse(legend(in).stream().anyMatch(t -> t.startsWith("mechanics")));
	}

	@Test
	public void nothingNewForAnOldKill()
	{
		// A kill saved before T-074, opened from History: no mechanics anywhere.
		final SidePanelHistoryTab.Source source = SidePanelPictures.source();
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		final List<Fight> h = source.history();
		tab.open(h.get(h.size() - 1));
		assertTrue(tab.isShowingKill());
		for (String t : texts(tab))
		{
			assertFalse(t, t.contains("mechanics"));
		}
	}

	@Test
	public void builtOnceAndUpdatedInPlace()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.leviathanKill())));
		final SidePanelFightTab tab = panel.fightTab();
		SidePanelPictures.draw(panel.tabs(), tab, 1);
		final int builds = tab.builds();
		final int layouts = SidePanelParts.relayouts;
		for (int i = 0; i < 10; i++)
		{
			panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.leviathanKill())));
		}
		assertEquals(builds, tab.builds());
		assertEquals(layouts, SidePanelParts.relayouts);
	}

	/** The tick strip's legend words. */
	private static List<String> legend(SidePanelModel.Input in)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		final List<String> out = new ArrayList<>();
		for (SidePanelParts.Legend l : all(panel.fightTab(), SidePanelParts.Legend.class))
		{
			for (SidePanelParts.Legend.Item item : l.items())
			{
				out.add(item.text);
			}
		}
		return out;
	}

	private static <T> List<T> all(Container c, Class<T> type)
	{
		final List<T> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (type.isInstance(k))
			{
				out.add(type.cast(k));
			}
			if (k instanceof Container)
			{
				out.addAll(all((Container) k, type));
			}
		}
		return out;
	}

	private static List<String> texts(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (SidePanelText t : all(c, SidePanelText.class))
		{
			out.add(t.getText());
		}
		return out;
	}
}

package com.pvmlogs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-225 (Eric, 2026-11-27: "just make it something along the lines of expected damage vs actual"): the chart's legend
 * under the card reads {@code actual 152 · expected 140}, a member's chart their name for the first; {@code expected
 * hidden} with the line off; never "average rolls" or "as thrown". Where the line's expected isn't the figure above,
 * the chart's hover says why.
 */
public class SidePanelChartLegendTest
{
	@After
	public void normalText()
	{
		SidePanelStyle.setText(false, false);
	}

	@Test
	public void yoursReadsActualAndExpected()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.dead());
		final List<String> words = legend(tab);
		assertEquals(2, words.size());
		assertTrue(words.toString(), words.get(0).matches("actual [\\d,]+"));
		assertTrue(words.toString(), words.get(1).matches("expected [\\d,]+"));
		assertNull(tab.chart.expectedNote);
	}

	@Test
	public void aMembersReadsTheirNameAndExpected()
	{
		final PanelSnapshot.FightTab hers = SidePanelPartyExamples.toaYouAndBrindle().members.get(2).fight;
		final List<String> words = legend(hers);
		assertTrue(words.toString(), words.get(0).matches("Brindle [\\d,]+"));
		assertTrue(words.toString(), words.get(1).matches("expected [\\d,]+"));
	}

	@Test
	public void withTheLineOffItSaysExpectedHidden()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		in.warning = "Boss data unsure";
		final List<String> words = legend(SidePanelModel.fight(in));
		assertTrue(words.toString(), words.get(0).matches("actual [\\d,]+"));
		assertEquals(SidePanelFightTab.EXPECTED_HIDDEN, words.get(1));
	}

	@Test
	public void noAsThrownTheHoverSaysWhyTheFiguresDiffer()
	{
		// The recorded Sire kill: the chart's expected (588) isn't the figure above (576), the specials' luck between.
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.sireEmberlight());
		final PvmPerformancePanel panel = panel(tab);
		final List<String> words = words(panel);
		assertEquals(Arrays.asList("actual 446", "expected 588"), words);
		final SidePanelDamageChart chart = all(panel.fightTab(), SidePanelDamageChart.class).get(0);
		assertEquals(SidePanelStyle.tip(SidePanelModel.EXPECTED_NOTE), chart.getToolTipText());
		// Where they agree, no hover.
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		SidePanelPictures.openAll(panel.fightTab());
		for (SidePanelDamageChart c : all(panel.fightTab(), SidePanelDamageChart.class))
		{
			assertNull(c.getToolTipText());
		}
	}

	@Test
	public void noAverageRollsOrThrownAnywhereOnTheTab()
	{
		for (SidePanelModel.Input in : Arrays.asList(SidePanelExamples.dead(), SidePanelExamples.sireEmberlight(),
			SidePanelKillCardExamples.araxxorKill(), SidePanelExamples.takenMidFight()))
		{
			final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
			final String all = String.valueOf(tab.chart == null ? "" : tab.chart.youLegend + tab.chart.expectedLegend)
				+ (tab.card == null ? "" : tab.card.luckTip + tab.card.dpsTip);
			assertFalse(all, all.toLowerCase().contains("average rolls"));
			assertFalse(all, all.contains("thrown"));
		}
		assertFalse(SidePanelFightTab.BUDGET_TIP.contains("average rolls"));
	}

	@Test
	public void fourDigitFiguresFitTheCardAtNormalAndLarger()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			final String size = larger ? "Larger" : "Normal";
			// Yours on one line.
			assertEquals(size, SidePanelStyle.Type.SMALL.lineHeight(), legend("actual 9,999").getPreferredSize().height);
			// A member's with a 12-character name: each item within the width, so at most a line each, none cut.
			final SidePanelParts.Legend hers = legend("Brindle Moss 9,999");
			assertTrue(size, hers.getPreferredSize().height <= 2 * SidePanelStyle.Type.SMALL.lineHeight() + 3);
			final java.awt.FontMetrics fm = SidePanelStyle.metrics(hers, SidePanelStyle.Type.SMALL.font());
			for (SidePanelParts.Legend.Item item : hers.items())
			{
				assertTrue(size + ": " + item.text, 8 + 3 + fm.stringWidth(item.text) <= SidePanelStyle.CONTENT_WIDTH - 14);
			}
		}
	}

	/** The chart legend's two items, as the tab sets them, at the card's width. */
	static SidePanelParts.Legend legend(String first)
	{
		final SidePanelParts.Legend legend = new SidePanelParts.Legend(SidePanelStyle.CONTENT_WIDTH - 14);
		legend.set(Arrays.asList(new SidePanelParts.Legend.Item(Color.WHITE, false, first, Color.WHITE),
			new SidePanelParts.Legend.Item(Color.GRAY, false, "expected 9,999", Color.GRAY)));
		return legend;
	}

	static List<String> legend(PanelSnapshot.FightTab tab)
	{
		return words(panel(tab));
	}

	static PvmPerformancePanel panel(PanelSnapshot.FightTab tab)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(tab));
		SidePanelPictures.openAll(panel.fightTab());
		return panel;
	}

	/** The chart's legend: the one under the Damage chart (the strip's has attacking, idle, ...). */
	static List<String> words(PvmPerformancePanel panel)
	{
		for (SidePanelParts.Legend l : all(panel.fightTab(), SidePanelParts.Legend.class))
		{
			final List<String> out = new ArrayList<>();
			for (SidePanelParts.Legend.Item item : l.items())
			{
				out.add(item.text);
			}
			if (!out.isEmpty() && out.get(out.size() - 1).startsWith("expected"))
			{
				return out;
			}
		}
		throw new AssertionError("no chart legend");
	}

	static <T> List<T> all(Container c, Class<T> type)
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
}

package com.pvmlogs;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-190: History's top as progress, read from T-187's record (kills history has dropped included): the bests, the
 * luck-removed trend, the grades' spread, the streak and badges; the kill rows with grade and roll.
 */
public class SidePanelProgressTest
{
	private static final LedgerHistory.Like VARDORVIS = new LedgerHistory.Like("Vardorvis", 700, true);

	@Test
	public void theTopReadsTheRecordNotHistoryAlone()
	{
		final BossRecords records = SidePanelProgressExamples.vardorvis();
		final SidePanelHistory.Progress p = SidePanelHistory.progress(records.view(VARDORVIS));
		assertNotNull(p);
		// 13 counted kills in history (the 26 Oct one too), and three it no longer holds.
		assertEquals(16, p.kills);
		// The fastest is an older kill's, 1:43, which history doesn't have.
		assertEquals("fastest", p.bests.get(0).left.text);
		assertEquals("1:43", p.bests.get(0).right.text);
		assertTrue(p.bests.get(0).hint.text, p.bests.get(0).hint.text.matches(" rolled \\d+"));
		assertEquals(SidePanelStyle.LUCK, p.bests.get(0).hint.colour);
		// The luck-removed best carries the only gold; no DPS best.
		assertEquals(" " + SidePanelHistory.SKILL_PB, p.bests.get(1).hint.text);
		assertEquals(SidePanelStyle.GOLD, p.bests.get(1).hint.colour);
		assertEquals(3, p.bests.size());
		for (PanelSnapshot.Row r : p.bests)
		{
			assertTrue(r.left.text, !r.left.text.toLowerCase().contains("dps"));
		}
		int graded = 0;
		for (int g : p.grades)
		{
			graded += g;
		}
		assertEquals(p.kills, graded);
		assertNull(p.note);
	}

	@Test
	public void underTenKillsTheFixedBandsNoteShows()
	{
		final BossRecords records = SidePanelProgressExamples.records(SidePanelHistoryExamples.history().subList(0, 8),
			"Vardorvis", 700, 0, new double[]{120}, new double[]{0.95});
		final SidePanelHistory.Progress p = SidePanelHistory.progress(records.view(VARDORVIS));
		assertTrue(p.kills < 10);
		assertEquals("Grades by fixed bands until 10 kills (" + p.kills + " so far)", p.note);
	}

	@Test
	public void theTrendComparesTheNewestHalfWithTheOlder()
	{
		final SidePanelHistory.Progress p = SidePanelHistory.progress(SidePanelProgressExamples.araxxor().view(
			new LedgerHistory.Like("Araxxor", 1020, true)));
		assertEquals("Luck removed, last " + SidePanelHistory.TREND_OF, p.trendTitle);
		assertTrue(p.trend.text, p.trend.text.matches("\\d+ s (faster|slower)|even"));
		assertEquals(209, p.kills);
		// A farmed boss's badges: the longest streak and the kills tracked, by hundreds.
		assertTrue(p.badges.toString(), p.badges.toString().contains("200 kills tracked"));
	}

	@Test
	public void theKillRowsCarryGradeAndRoll()
	{
		final BossRecords records = SidePanelProgressExamples.vardorvis();
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(SidePanelHistoryExamples.history(),
			"Vardorvis", SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE, records::view);
		final List<String> grades = Arrays.asList("S", "A", "B", "C");
		int graded = 0;
		for (SidePanelHistory.KillRow r : rows)
		{
			if (r.grade != null)
			{
				graded++;
				assertTrue(r.grade, grades.contains(r.grade));
				// Luck as a violet roll; the 26 Oct kill, from before T-028, keeps its seconds.
				if (r.chip.text.startsWith("rolled"))
				{
					assertEquals(SidePanelStyle.LUCK, r.chip.colour);
					assertTrue(r.chip.text, r.chip.text.matches("rolled \\d+"));
				}
				else
				{
					assertTrue(r.chip.text, r.chip.text.matches("\\d+ s (faster|slower)"));
				}
			}
		}
		// Every counted kill is graded.
		assertEquals(13, graded);
		// Celebrations off: no record asked, no grades.
		for (SidePanelHistory.KillRow r : SidePanelHistory.kills(SidePanelHistoryExamples.history(), "Vardorvis",
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE))
		{
			assertNull(r.grade);
		}
	}

	@Test
	public void historyOpensOnProgressWithCelebrationsAndAsTodayWithout()
	{
		final PvmPerformancePanel on = new PvmPerformancePanel(null, null, null, null, SidePanelProgressExamples.source(
			SidePanelHistoryExamples.history(), SidePanelProgressExamples.vardorvis()));
		on.historyTab().refresh();
		assertTrue(texts(on.historyTab()), texts(on.historyTab()).contains("Personal bests"));
		assertTrue(texts(on.historyTab()), texts(on.historyTab()).contains("16 kills tracked"));
		final PvmPerformancePanel off = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		off.historyTab().refresh();
		assertTrue(!texts(off.historyTab()).contains("Personal bests"));
		assertTrue(texts(off.historyTab()).contains("13 kills"));
	}

	private static String texts(java.awt.Container c)
	{
		final StringBuilder b = new StringBuilder();
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelText)
			{
				b.append(((SidePanelText) k).getText()).append('|');
			}
			if (k instanceof java.awt.Container)
			{
				b.append(texts((java.awt.Container) k));
			}
		}
		return b.toString();
	}
}

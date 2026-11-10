package com.pvmlogs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-188: the kill card reads T-187's record through {@code PvmPerformancePanel.standing}, the API agreed in
 * {@code claude/specs/t187-t188-record-api.md}: the PB before the kill, the rank, the grade with its basis and the
 * ticks to the next, the luck-removed PB, and the streak (T-189).
 */
public class SidePanelRecordWiringTest
{
	private static final LedgerHistory.Like LIKE = new LedgerHistory.Like("Araxxor", 1020, true);

	private static BossRecords records() throws IOException
	{
		final Path tmp = Files.createTempDirectory("records");
		return new BossRecords(Filepath.Unchecked.getRooted(tmp), new Gson());
	}

	@Test
	public void theRecordsStandingFillsTheCard() throws IOException
	{
		final BossRecords records = records();
		// Twelve kills before: 1:30 to 1:52, all at 96% execution; the best luck-removed 1:35.
		for (int i = 0; i < 12; i++)
		{
			records.record(new BossRecords.KillFigures(LIKE, (i + 1) * 1000L, 90 + 2 * i, 0.96, 95 + 2 * i, 0.5, null));
		}
		// This kill: 1:26, execution 98.7%, luck removed 1:36.
		records.record(new BossRecords.KillFigures(LIKE, 20_000L, 86.4, 0.987, 96.2, 0.81, null));
		final BossRecords.Standing r = records.standing(new Encounter("Araxxor", null, 20_000L));
		final SidePanelModel.Standing st = PvmPerformancePanel.standing(r);
		assertEquals(90, st.previousBestClock, 1e-9);
		assertTrue(st.newPb);
		assertEquals(1, st.rank);
		assertEquals(13, st.ranked);
		assertEquals(r.grade.name(), st.grade);
		assertEquals(r.basis, st.basis);
		assertEquals(95, st.previousBestLuckRemoved, 1e-9);
		assertFalse(st.newBestLuckRemoved);
		assertEquals(r.streak, st.streak);

		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		in.standing = st;
		final PanelSnapshot.KillCard c = SidePanelModel.fight(in).card;
		assertTrue(c.newPb);
		assertEquals("NEW PB", c.side1.text);
		assertEquals("4 s faster", c.side2.text);
		assertEquals("fastest of 13", c.side3.text);
		assertEquals(r.grade.name(), c.grade);
		assertEquals(r.basis, c.skill2.text);
		assertEquals("PB 1:35", c.luckRemovedPb);
		// The record's own words for the ticks to the next grade, as the card's tooltip.
		if (r.tooltip() != null)
		{
			assertEquals(r.tooltip(), c.gradeTip);
		}
	}

	@Test
	public void noStandingIsNoStanding() throws IOException
	{
		assertNull(PvmPerformancePanel.standing(null));
		assertNull(records().standing(new Encounter("Araxxor", null, 1L)));
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-068 (T-067's finding D-1): the Damage taken card shows its total only while
 * the fight runs; hits, biggest hit and "without protection" come once it has
 * ended, as the overlay keeps them for its Summary. The trip waits for no fight
 * to run.
 */
public class SidePanelTakenAfterFightTest
{
	private static List<String> labels(PanelSnapshot.Rows rows)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : rows.rows)
		{
			out.add(r.left.text);
		}
		return out;
	}

	@Test
	public void midFightTheTotalOnly()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.takenMidFight());
		assertNotNull(tab.taken);
		assertEquals("263", tab.taken.hint.text);
		assertEquals(Collections.singletonList(SidePanelModel.AFTER_THE_FIGHT), labels(tab.taken));
		assertEquals(SidePanelStyle.MUTED, tab.taken.rows.get(0).left.colour);
	}

	@Test
	public void afterTheFightTheRows()
	{
		final List<String> rows = labels(SidePanelModel.fight(SidePanelExamples.ruledKill(true)).taken);
		assertTrue(rows.toString(), rows.containsAll(Arrays.asList("hits", "biggest hit", "without protection")));
		assertTrue(rows.toString(), !rows.contains(SidePanelModel.AFTER_THE_FIGHT));
	}

	@Test
	public void theTripWaitsForNoFightToRun()
	{
		final PanelSnapshot.TripTab running = SidePanelModel.trip(SidePanelExamples.tripWhileFighting());
		assertEquals("1,404", running.taken.hint.text);
		assertEquals(Collections.singletonList(SidePanelModel.AFTER_THE_FIGHT), labels(running.taken));
		final List<String> idle = labels(SidePanelModel.trip(SidePanelExamples.trip()).taken);
		assertTrue(idle.toString(), idle.containsAll(Arrays.asList("hits", "biggest hit", "without protection")));
	}

	@Test
	public void theFightsEndIsOneStructureChange()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.takenMidFight())));
		final SidePanelFightTab tab = panel.fightTab();
		final int builds = tab.builds();
		// Five ticks on, more damage taken: the total moves in place, the card's structure holds.
		final SidePanelModel.Input later = SidePanelExamples.takenMidFight();
		((LedgerExamples.FakeScope) later.scope).damageTaken += 30;
		((LedgerExamples.FakeScope) later.scope).biggestHitTaken += 9;
		later.hitsTaken += 2;
		panel.apply(new PanelSnapshot(SidePanelModel.fight(later)));
		assertEquals(builds, tab.builds());
		assertEquals("293", SidePanelModel.fight(later).taken.hint.text);
		// The fight ends: the rows come, with the after-fight sections it already rebuilds for.
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.ruledKill(true))));
		assertEquals(builds + 1, tab.builds());
	}

	@Test
	public void theOverlayAgrees()
	{
		// The overlay's live rows hold no hits, biggest hit or without protection; its Summary, after the fight, does.
		final SidePanelModel.Input mid = SidePanelExamples.takenMidFight();
		final List<LedgerRows.Row> live = LedgerRows.build(mid.scope, LedgerRows.State.fight("Vardorvis", false, false,
			false), new LedgerRows.Settings());
		for (LedgerRows.Row r : live)
		{
			final String label = String.valueOf(r.left).trim();
			assertTrue(label, !label.equals("hits") && !label.equals("biggest hit") && !label.equals("without protection"));
		}
	}
}

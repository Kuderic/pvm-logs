package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-165: a melee blow is scored from its throw's row. Each of the 9 {@code MAX EXCEEDED} lines that followed
 * {@code potted=false} in Eric's logs of 2026-11-02 and 11-03 had "PRAYER at the throw up, when the attack was seen not
 * up": Piety flicked off on the tick after the swing, and the figures read on that tick had no Piety ([13777] of the 11-02
 * log: thrown on 13776, a 57 against the 51 read on 13777; with Piety the same swing's average was 27.12, not 22.17).
 */
public class MeleeFromThrowTest
{
	private static final int CERBERUS = 5862;

	@Test
	public void aBlowSeenAfterItsThrowIsOne()
	{
		assertTrue(PvmPerformancePlugin.isMeleeBlow(false, false, false, false, 13776, 13777));
		assertFalse("a blow seen on its own tick reads the same tick either way",
			PvmPerformancePlugin.isMeleeBlow(false, false, false, false, 13777, 13777));
		assertFalse("a shot keeps its own rule", PvmPerformancePlugin.isMeleeBlow(true, false, false, false, 13776, 13778));
		assertFalse("a cast", PvmPerformancePlugin.isMeleeBlow(false, false, true, false, 13776, 13779));
		assertFalse("an attack booked from its drop", PvmPerformancePlugin.isMeleeBlow(false, true, false, false, 13776, 13777));
		assertFalse("a mechanic", PvmPerformancePlugin.isMeleeBlow(false, false, false, true, 13776, 13777));
		assertFalse("no throw known", PvmPerformancePlugin.isMeleeBlow(false, false, false, false, Integer.MIN_VALUE, 13777));
	}

	@Test
	public void itIsScoredFromTheThrowsTick()
	{
		// Scored as a punish is (T-086): the throw's tick where it has figures, else the tick it was seen on.
		assertEquals(13776, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, true, 13776, 13777));
		assertEquals(13777, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, false, 13776, 13777));
		// A switch since the throw still wins (T-065, T-140).
		assertEquals(13775, PvmPerformancePlugin.scoringTick(13775, false, true, true, 13776, 13777));
	}

	@Test
	public void fromThatRowWhenItIsTheTargets()
	{
		final double[] row = row(CERBERUS, 0.87);
		assertTrue(PvmPerformancePlugin.scoresFromRow(false, false, true, false, row, CERBERUS));
		assertFalse("another npc's row", PvmPerformancePlugin.scoresFromRow(false, false, true, false, row(6610, 0.5),
			CERBERUS));
		assertFalse("a row with no stats", PvmPerformancePlugin.scoresFromRow(false, false, true, false, row(CERBERUS, -1),
			CERBERUS));
		assertFalse("none", PvmPerformancePlugin.scoresFromRow(false, false, true, false, null, CERBERUS));
	}

	private static double[] row(int npcId, double accuracy)
	{
		final double[] row = new double[PvmPerformancePlugin.EXP_WIDTH];
		row[PvmPerformancePlugin.EXP_NPC_ID] = npcId;
		row[PvmPerformancePlugin.EXP_ACCURACY] = accuracy;
		return row;
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

// T-020: an attack is scored with the id its target wore on the attack's tick, which can be behind the booking.
public class TargetIdHistoryTest
{
	private static final int INDEX = 24468;

	@Test
	public void theIdOnAnAttacksTickIsTheOneItWoreThen()
	{
		final TargetIdHistory ids = new TargetIdHistory();
		ids.record(100, INDEX, 5891);
		ids.record(101, INDEX, 5891);
		ids.record(102, INDEX, 5908);
		// Booked on 102 for an attack thrown on 101: stage 1's stats, not stage 2's.
		assertEquals(5891, ids.idOn(101, INDEX, 5908));
		assertEquals(5908, ids.idOn(102, INDEX, -1));
	}

	@Test
	public void aTickNotRecordedOrAnotherTargetFallsBack()
	{
		final TargetIdHistory ids = new TargetIdHistory();
		ids.record(100, INDEX, 5891);
		assertEquals(5908, ids.idOn(99, INDEX, 5908));
		assertEquals(5908, ids.idOn(100, INDEX + 1, 5908));
		ids.record(101, INDEX, -1);
		assertEquals(42, ids.idOn(101, INDEX, 42));
	}

	@Test
	public void onlyTheLastFewTicksAreKept()
	{
		final TargetIdHistory ids = new TargetIdHistory();
		ids.record(100, INDEX, 5886);
		ids.record(100 + TargetIdHistory.KEEP_TICKS, INDEX, 5889);
		assertEquals(5886, ids.idOn(100, INDEX, -1));
		ids.record(101 + TargetIdHistory.KEEP_TICKS, INDEX, 5889);
		assertEquals(-1, ids.idOn(100, INDEX, -1));
		ids.clear();
		assertEquals(-1, ids.idOn(101 + TargetIdHistory.KEEP_TICKS, INDEX, -1));
	}
}

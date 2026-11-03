package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-154, from Eric's log of 2026-11-02 (build ea86ada, read in place, nothing copied): the overhead bar, read as a
 * fight opened on the player's own splat, already showed that splat while the count didn't, so it was counted twice.
 * At Araxxor [2301]: his 25 on a count of 944, the overhead bar at 108/120 (at most 925), "19 were taken unseen", and
 * then the 25 too. 48 of his 49 Araxxor kills reconciled over full by exactly their "seen only on its bar".
 */
public class OpeningSplatTest
{
	private static final int INDEX = 44180;
	private static final int ARAXXOR = NpcID.ARAXXOR;

	private static TargetHealth araxxorAt944()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, ARAXXOR, 1020, 2000);
		health.damage(INDEX, ARAXXOR, 76, 2100);
		assertEquals(944, health.countedRemaining(INDEX));
		return health;
	}

	@Test
	public void aFightOpenedByThePlayersOwnSplatAddsNothingUnseen()
	{
		assertEquals(925, TargetHealth.mostLeftForRatio(108, 120, 1020));
		// Before: read as the fight opened, before his 25 was counted: 19 "unseen", and the 25 on top.
		final TargetHealth before = araxxorAt944();
		assertEquals(19, before.boundByRatio(INDEX, ARAXXOR, 108, 120, 0, 2301));
		// Now: read at the tick's end, once the 25 is counted: 919, which the bar's 925 allows.
		final TargetHealth now = araxxorAt944();
		now.damage(INDEX, ARAXXOR, 25, 2301);
		assertEquals(0, now.boundByRatio(INDEX, ARAXXOR, 108, 120, 0, 2301));
		assertEquals(919, now.countedRemaining(INDEX));
		// A fight his splat opens reads at the tick's end; one opened otherwise (a target switch, a projectile) at once.
		assertTrue(PvmPerformancePlugin.openedBySplat(INDEX, 2301, INDEX, 2301));
		assertFalse(PvmPerformancePlugin.openedBySplat(INDEX, 2300, INDEX, 2301));
		assertFalse(PvmPerformancePlugin.openedBySplat(99, 2301, INDEX, 2301));
	}

	@Test
	public void aTargetHurtBeforeTheFightStillGetsItsBound()
	{
		// T-146's Scavenger beast, [5570] of the 11-01 log: its fight opened on a target switch, its bar at 1 of 30.
		final int beast = NpcID.RAIDS_SCAVENGER_BEAST_A;
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(43277, beast, 30, 5570);
		assertEquals(29, health.boundByRatio(43277, beast, 1, 30, 0, 5570));
		assertEquals(1, health.remaining(43277, 5570));
	}

	@Test
	public void aBossWhoseBarHasBeenReadGetsNone()
	{
		final TargetHealth health = araxxorAt944();
		health.hud(INDEX, ARAXXOR, 944, 1020, 2200);
		// Long after that reading, out of date: still nothing from the overhead bar.
		assertEquals(0, health.boundByRatio(INDEX, ARAXXOR, 100, 120, 0, 2400));
		assertEquals(0, health.unseenDamage(INDEX));
	}

	@Test
	public void aSplatLaterInTheSameTickIsAllowedForAtTheTicksEnd()
	{
		// A 30-hitpoint monster with no boss bar: the player's 10 and a thrall's 3 land on one tick, and the bar shows
		// both as the first is handled (17 of 30 left: 17/30).
		final int beast = NpcID.RAIDS_SCAVENGER_BEAST_A;
		final TargetHealth mid = new TargetHealth();
		mid.setTableMaxHp(1, beast, 30, 0);
		// Read as the player's splat lands, allowing only for it: the thrall's 3 reads as unseen.
		assertEquals(3, mid.boundByRatio(1, beast, 17, 30, 10, 5));
		// At the tick's end, both counted: nothing.
		final TargetHealth end = new TargetHealth();
		end.setTableMaxHp(1, beast, 30, 0);
		end.damage(1, beast, 10, 5);
		end.damage(1, beast, 3, 5);
		assertEquals(0, end.boundByRatio(1, beast, 17, 30, 0, 5));
	}
}

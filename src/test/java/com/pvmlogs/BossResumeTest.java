package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-164: a boss fought again after the player's death, an idle pause, a trip or a logout carries on its fight and count.
 * Venenatis (npc 6610, 850) from Eric's log of 2026-11-03 15:32 (build {@code 36fa5ef}): four kills, among others, split
 * where the fight ended at a death or an idle close and opened again on the same index from 850.
 */
public class BossResumeTest
{
	private static final int VEN = 6610;
	private static final int WORLD = 574;

	private static BossAway held(BossAway.Cause cause, boolean died, int index, int tick)
	{
		return new BossAway(VEN, index, "Venenatis", WORLD, tick, tick * 600L, true, -1, cause, died);
	}

	@Test
	public void aBossLeftHurtIsHeldNeverAnAddARunOrARaid()
	{
		assertTrue(BossAway.resumable(false, false, false, false, true));
		assertFalse("in a raid the room goes on by itself", BossAway.resumable(true, false, false, false, true));
		assertFalse("a run of monsters", BossAway.resumable(false, true, false, false, true));
		assertFalse("an add: its boss's room is what's resumed", BossAway.resumable(false, false, true, false, true));
		assertFalse("dead", BossAway.resumable(false, false, false, true, true));
		assertFalse("never hurt: nothing to carry on", BossAway.resumable(false, false, false, false, false));
	}

	@Test
	public void afterTheDeathTheSameIndexAndWorldWithinTheWindow()
	{
		// [5250]: died with 855 counted on index 34185; back on it at [5806], 556 ticks later, its bar at 38 of 850.
		final BossAway away = held(BossAway.Cause.PLAYER_DIED, true, 34185, 5250);
		assertTrue(away.isBack(VEN, 34185, WORLD, false));
		assertFalse(away.timedOut(5806, 5806 * 600L));
		assertTrue(away.resumableAfterLeaving());
		// Another index (a fresh one after her death), another world: not her.
		assertFalse(away.isBack(VEN, 57041, WORLD, false));
		assertFalse(away.isBack(VEN, 34185, 557, false));
		// Outside the window, by the tick or by the clock (a logout, whose ticks may not run).
		assertTrue(away.timedOut(5250 + BossAway.TIMEOUT_TICKS + 1, 5251 * 600L));
		assertTrue(away.timedOut(5300, 5250 * 600L + BossAway.TIMEOUT_MILLIS + 1));
	}

	@Test
	public void anIdlePauseCountsFromItsLastActivity()
	{
		// [18978] out of view (a burrow), [19114] idle 100 ticks: paused from the last activity, so the gap isn't fight time.
		final BossAway burrow = new BossAway(VEN, 34772, "Venenatis", 557, 18978, 18978 * 600L, false, -1);
		final BossAway paused = burrow.pausedFrom(19014, 19014 * 600L, BossAway.Cause.IDLE);
		assertTrue(paused.playerLeft);
		assertEquals(BossAway.Cause.IDLE, paused.cause);
		assertEquals("from whichever came first", 18978, paused.tick);
		assertFalse(paused.playerDied);
		// The player dying later in a pause keeps where it began; in a burrow, from the death.
		assertEquals(18978, paused.withPlayerDied(19100, 19100 * 600L).tick);
		assertTrue(paused.withPlayerDied(19100, 19100 * 600L).playerDied);
		assertEquals(19100, burrow.withPlayerDied(19100, 19100 * 600L).tick);
	}

	@Test
	public void aBossInAnInstanceTheplayerLeftIsNotResumed()
	{
		final BossAway trip = new BossAway(5862, 777, "Cerberus", 482, 1548, 1548 * 600L, true, -1);
		trip.instanced = true;
		assertFalse(trip.resumableAfterLeaving());
		assertFalse(trip.pausedFrom(1540, 1540 * 600L, BossAway.Cause.IDLE).resumableAfterLeaving());
		// Gone from view while the player stayed (a burrow) is still there.
		final BossAway burrow = new BossAway(5862, 777, "Cerberus", 482, 1548, 1548 * 600L, false, -1);
		burrow.instanced = true;
		assertTrue(burrow.resumableAfterLeaving());
	}

	@Test
	public void aCountParkedAtALogoutComesBackOnTheSameNpcAndWorld()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(34772, VEN, 850, 18756);
		h.damage(34772, VEN, 688, 18950);
		h.park(34772, 557, PvmPerformancePlugin.HELD_OUTSIDE_RAID, 641);
		h.clear();
		assertEquals(-1, h.remaining(34772, 19318));
		final TargetHealth.Parked kept = h.unpark(34772, VEN, 557, PvmPerformancePlugin.HELD_OUTSIDE_RAID);
		assertNotNull(kept);
		assertEquals(641, kept.extra);
		assertEquals(162, h.remaining(34772, 19318));
		// Another world: not hers.
		h.park(34772, 557, PvmPerformancePlugin.HELD_OUTSIDE_RAID, 0);
		assertNull(h.unpark(34772, VEN, 558, PvmPerformancePlugin.HELD_OUTSIDE_RAID));
	}

	@Test
	public void aLongGapOnTheBarIsSettled()
	{
		// Her bar rose by 1 every 20 ticks; [22359] 418 at the death, [22699] 405 on the return with 17 steps due: 30 taken
		// by someone else meanwhile.
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(39812, VEN, 850, 22203);
		h.damage(39812, VEN, 431, 22355);
		h.hud(39812, VEN, 419, 850, 22355, true);
		h.regenerate(39812, 22355);
		// The bar seen rising on its own: the phase.
		h.hud(39812, VEN, 420, 850, 22359, true);
		assertTrue(h.regenerated(39812) >= 1);
		final int before = h.regenerated(39812);
		final int damageBefore = h.damageCounted(39812);
		// Held: no ticks asked about. The return's reading, settled at that tick's end.
		h.hud(39812, VEN, 405, 850, 22699, true);
		h.regenerate(39812, 22699);
		assertEquals("its 20-tick steps through the gap", 17, h.regenerated(39812) - before);
		assertEquals("the rest taken by someone else", 32, h.damageCounted(39812) - damageBefore);
		assertEquals(32, h.unseenDamage(39812));
		assertEquals(405, h.countedRemaining(39812));
	}

	@Test
	public void aRiseAcrossALogoutBeyondTheStepsIsRegained()
	{
		// [18978] 173 to [19319] 195 across a logout: 22 regained over 341 ticks the game counted as fewer.
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(34772, VEN, 850, 18756);
		h.damage(34772, VEN, 677, 18970);
		h.hud(34772, VEN, 173, 850, 18970, true);
		h.regenerate(34772, 18970);
		h.hud(34772, VEN, 195, 850, 19319, true);
		h.regenerate(34772, 19319);
		assertEquals(22, h.regenerated(34772));
		assertEquals(195, h.countedRemaining(34772));
	}
}

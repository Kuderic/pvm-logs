package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-171: after Eric's death in a duo Tombs (2026-11-04, build {@code 8e328d6}) his thrall's splats at Zebak were booked
 * as his shots. The death wasn't it: the same happened in the Zebak fight before it ([74396] onwards). His thrall stood
 * beside him at range, and its projectiles, first seen two ticks before their splats, were predicted to land there
 * ([75005] "lands tick 75007", its 3 on 75007). The window of the first-seen tick and the next missed every one.
 */
public class ThrallAtRangeTest
{
	private static final int THRALL = 37677;
	private static final int PARTNERS = 37731;
	private static final int ZEBAK = 37055;
	private static final int GHOST = NpcID.ARCEUUS_THRALL_GHOST_GREATER;

	private static ThrallTracker summoned(int tick)
	{
		final ThrallTracker t = new ThrallTracker();
		t.onThrallSpawned(THRALL, GHOST, 1, tick);
		t.onResurrectionVarbit(true, tick + 5);
		return t;
	}

	private static ThrallTracker.Evidence splat(int amount, int tick, ThrallTracker.PlayerSplat player)
	{
		final ThrallTracker.Evidence e = new ThrallTracker.Evidence();
		e.amount = amount;
		e.tick = tick;
		e.targetIndex = ZEBAK;
		e.player = player;
		e.playerAccuracy = 0.71;
		e.playerMaxHit = 44;
		return e;
	}

	@Test
	public void aProjectilePredictedTwoTicksOnLandsThere()
	{
		assertArrayEquals(new int[]{75006, 75007}, ThrallTracker.window(75005, 75007));
		// Seen on its splat's tick, or predicted a tick on (T-003's measure): that tick and the next, as before.
		assertArrayEquals(new int[]{203, 204}, ThrallTracker.window(203, 203));
		assertArrayEquals(new int[]{203, 204}, ThrallTracker.window(203, 204));
		assertArrayEquals(new int[]{102, 103}, ThrallTracker.window(100, 103));
	}

	@Test
	public void itsThreeIsTheThrallsNotAnUnseenShot()
	{
		// [74819] claimed beside him; [75005] its projectile, lands 75007; [75007] a 3, judged his at rate 0.001.
		final ThrallTracker t = summoned(74819);
		t.onThrallProjectile(THRALL, ZEBAK, 75001, 75003);
		t.noteThrallSplat(ZEBAK, 75003);
		t.onThrallProjectile(THRALL, ZEBAK, 75005, 75007);
		assertTrue(t.projectileDueOn(ZEBAK, 75007));
		assertEquals(ThrallTracker.RATE_THRALL_PROJECTILE, t.thrallRate(ZEBAK, 75007), 0);
		assertTrue(t.probability(splat(3, 75007, ThrallTracker.PlayerSplat.UNEXPLAINED)) > 0.9);
		// Not before its window.
		assertFalse(t.projectileDueOn(ZEBAK, 75005));
		// The same-tick re-judging looks for its splat on its landing tick.
		assertEquals(Collections.singletonList(ZEBAK), t.unclaimedProjectileTargets(75007));
		assertTrue(t.unclaimedProjectileTargets(75005).isEmpty());
	}

	@Test
	public void theDeathKeepsTheModelAndTheNewThrallIsClaimedNotThePartners()
	{
		// [74540] died; [74569] his thrall despawned; [74819] he raised one beside him; [74822] a second of the same id
		// spawned 5 tiles away, his partner's.
		final ThrallTracker t = new ThrallTracker();
		t.onThrallSpawned(64596, GHOST, 1, 74467);
		t.onResurrectionVarbit(true, 74472);
		assertEquals(64596, t.ownedIndex());
		t.onThrallDespawned(64596, 74569);
		t.onResurrectionVarbit(false, 74570);
		t.onThrallSpawned(THRALL, GHOST, 1, 74819);
		t.onThrallSpawned(PARTNERS, GHOST, 5, 74822);
		t.onResurrectionVarbit(true, 74824);
		assertEquals(THRALL, t.ownedIndex());
		// The partner's thrall's projectiles open nothing.
		t.onThrallProjectile(PARTNERS, ZEBAK, 74830, 74832);
		assertFalse(t.projectileDueOn(ZEBAK, 74832));
	}
}

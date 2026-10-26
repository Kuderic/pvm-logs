package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

public class ThrallTrackerTest
{
	private static final int THRALL = 77;
	private static final int BOSS = 5;
	private static final int GREATER = NpcID.ARCEUUS_THRALL_GHOST_GREATER;

	private static ThrallTracker summoned(int tick)
	{
		final ThrallTracker t = new ThrallTracker();
		t.onResurrectionVarbit(true, tick);
		t.onThrallSpawned(THRALL, GREATER, 1, tick);
		return t;
	}

	private static ThrallTracker.Evidence splat(int amount, int tick, ThrallTracker.PlayerSplat player)
	{
		final ThrallTracker.Evidence e = new ThrallTracker.Evidence();
		e.amount = amount;
		e.tick = tick;
		e.targetIndex = BOSS;
		e.player = player;
		e.playerAccuracy = 0.8;
		e.playerMaxHit = 50;
		return e;
	}

	@Test
	public void withoutAThrallNothingIsEverItsOwn()
	{
		final ThrallTracker t = new ThrallTracker();
		assertEquals(0, t.probability(splat(2, 10, ThrallTracker.PlayerSplat.UNEXPLAINED)), 0);
	}

	@Test
	public void ourThrallIsTheOneThatSpawnsBesideUsAsTheVarbitComesUp()
	{
		final ThrallTracker t = summoned(10);
		assertTrue(t.hasThrall());
		assertEquals(3, t.maxHit());
	}

	@Test
	public void theOrderOfSpawnAndVarbitDoesNotMatter()
	{
		final ThrallTracker t = new ThrallTracker();
		t.onThrallSpawned(THRALL, GREATER, 1, 10);
		t.onResurrectionVarbit(true, 10);
		assertTrue(t.hasThrall());
	}

	@Test
	public void someoneElsesThrallAcrossTheRoomIsNotOurs()
	{
		final ThrallTracker t = new ThrallTracker();
		t.onResurrectionVarbit(true, 10);
		t.onThrallSpawned(THRALL, GREATER, 6, 10);
		assertFalse(t.hasThrall());
	}

	@Test
	public void aSplatOverItsMaxIsNeverTheThralls()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		assertEquals(0, t.probability(splat(4, 21, ThrallTracker.PlayerSplat.UNEXPLAINED)), 0);
	}

	@Test
	public void aSmallSplatWhileItsAttackIsDueAndTheWeaponIsOnCooldownIsTheThralls()
	{
		// The issue as reported: the player is between swings and the thrall's splat lands.
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		assertTrue(t.probability(splat(2, 21, ThrallTracker.PlayerSplat.UNEXPLAINED)) > 0.95);
	}

	@Test
	public void soIsItsZero()
	{
		// A quarter of a greater thrall's splats are zeros, which no experience can tell apart from a miss.
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		assertTrue(t.probability(splat(0, 21, ThrallTracker.PlayerSplat.UNEXPLAINED)) > 0.9);
	}

	@Test
	public void aMissWhenTheThrallIsNotDueIsThePlayers()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		t.noteThrallSplat(BOSS, 21);
		assertTrue(t.probability(splat(0, 23, ThrallTracker.PlayerSplat.MELEE_POSSIBLE)) < 0.5);
	}

	@Test
	public void itCannotLandTwoInsideItsCycle()
	{
		final ThrallTracker t = summoned(10);
		t.noteThrallSplat(BOSS, 20);
		assertTrue(t.probability(splat(1, 21, ThrallTracker.PlayerSplat.MELEE_POSSIBLE)) < 0.2);
	}

	@Test
	public void blindToItsAttacksTheCycleCarriesTheCall()
	{
		// No animation or projectile seen at all: only splats already judged its own, every four ticks.
		final ThrallTracker t = summoned(10);
		t.noteThrallSplat(BOSS, 12);
		t.noteThrallSplat(BOSS, 16);
		t.noteThrallSplat(BOSS, 20);
		assertEquals(0, t.lockedPhase());
		assertTrue("on its phase", t.probability(splat(2, 24, ThrallTracker.PlayerSplat.MELEE_POSSIBLE)) > 0.5);
		assertTrue("off its phase", t.probability(splat(2, 26, ThrallTracker.PlayerSplat.MELEE_POSSIBLE)) < 0.5);
	}

	@Test
	public void aProjectileNarrowsTheWindowToItsLanding()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		t.onThrallProjectile(THRALL, BOSS, 24, 20);
		assertEquals(ThrallTracker.RATE_THRALL_DUE, t.thrallRate(BOSS, 24), 0);
		assertTrue(t.thrallRate(BOSS, 21) < ThrallTracker.RATE_THRALL_DUE);
	}

	@Test
	public void experienceNothingPaidForOutweighsAPlayerProjectileDue()
	{
		// The thrall's attack went unseen and the player's bolt is due: only the experience can say the 2 is not theirs.
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 5);
		final ThrallTracker.Evidence e = splat(2, 15, ThrallTracker.PlayerSplat.PROJECTILE_DUE);
		e.xpTrusted = true;
		e.xp = XpLedger.Coverage.UNCOVERED;
		assertTrue(t.probability(e) > 0.9);
	}

	@Test
	public void andASplatThatFinishesAnAttackExactlyIsThePlayersEvenWithTheThrallDue()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		final ThrallTracker.Evidence e = splat(2, 21, ThrallTracker.PlayerSplat.PROJECTILE_DUE);
		e.xpTrusted = true;
		e.xp = XpLedger.Coverage.EXACT;
		assertTrue(t.probability(e) < 0.5);
	}

	@Test
	public void untrustedExperienceIsIgnored()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		final ThrallTracker.Evidence trusted = splat(2, 21, ThrallTracker.PlayerSplat.PROJECTILE_DUE);
		final ThrallTracker.Evidence untrusted = splat(2, 21, ThrallTracker.PlayerSplat.PROJECTILE_DUE);
		trusted.xp = untrusted.xp = XpLedger.Coverage.EXACT;
		trusted.xpTrusted = true;
		assertTrue(t.probability(untrusted) > t.probability(trusted));
	}

	@Test
	public void aScythesSmallThirdHitIsNotTakenForTheThrall()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 18);
		t.noteThrallSplat(BOSS, 19);
		assertTrue(t.probability(splat(2, 21, ThrallTracker.PlayerSplat.SAME_TICK_BURST)) < 0.5);
	}

	@Test
	public void itsSplatSpendsTheAttackItCameFrom()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallAttackAnimation(THRALL, BOSS, 20);
		t.noteThrallSplat(BOSS, 21);
		assertTrue(t.dueAttacks().isEmpty());
	}

	@Test
	public void aRecastReplacesItEvenThoughTheVarbitNeverDrops()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallSpawned(THRALL + 1, NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR, 1, 100);
		t.onSummonCast(100);
		assertEquals(THRALL + 1, t.ownedIndex());
		assertEquals(2, t.maxHit());
		// The old one going afterwards does not take the new one with it.
		t.onThrallDespawned(THRALL, 100);
		assertEquals(THRALL + 1, t.ownedIndex());
	}

	@Test
	public void releasedWhenItDespawns()
	{
		final ThrallTracker t = summoned(10);
		t.onThrallDespawned(THRALL, 50);
		assertFalse(t.hasThrall());
	}

	@Test
	public void reclaimedWhenItReappearsBesideUsWhileStillActive()
	{
		// A new scene loads every npc afresh, so ours comes back under a new index with the varbit never having dropped.
		final ThrallTracker t = summoned(10);
		t.onThrallDespawned(THRALL, 300);
		t.onThrallSpawned(THRALL + 1, GREATER, 1, 400);
		assertTrue(t.hasThrall());
		assertEquals(THRALL + 1, t.ownedIndex());
	}
}

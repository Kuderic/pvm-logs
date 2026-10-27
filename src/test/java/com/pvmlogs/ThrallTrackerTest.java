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
	// A zombie by default: it fires nothing, so its animation and its cycle are all there is to go on. The ghost, which
	// is judged by its projectiles, has tests of its own below.
	private static final int GREATER = NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER;
	private static final int GHOST = NpcID.ARCEUUS_THRALL_GHOST_GREATER;

	private static ThrallTracker summoned(int tick)
	{
		return summoned(tick, GREATER);
	}

	private static ThrallTracker summoned(int tick, int id)
	{
		final ThrallTracker t = new ThrallTracker();
		t.onResurrectionVarbit(true, tick);
		t.onThrallSpawned(THRALL, id, 1, tick);
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
	public void aProjectileMakesItsSplatDueOnTheTickItIsFirstSeen()
	{
		// Vardorvis: animation on 202, projectile first seen on 203, splat on 203. The predicted landing is not used.
		final ThrallTracker t = summoned(190, GHOST);
		t.onThrallAttackAnimation(THRALL, -1, 202);
		t.onThrallProjectile(THRALL, BOSS, 203);
		assertEquals(ThrallTracker.RATE_THRALL_PROJECTILE, t.thrallRate(BOSS, 203), 0);
		assertEquals("one attack, one window", 1, t.dueAttacks().size());
		assertEquals(ThrallTracker.RATE_THRALL_PROJECTILE, t.thrallRate(BOSS, 204), 0);
		assertTrue(t.thrallRate(BOSS, 205) < ThrallTracker.RATE_THRALL_DUE);
	}

	@Test
	public void aProjectileIsOursOnlyIfItCameFromOurThrall()
	{
		final ThrallTracker t = summoned(190, GHOST);
		t.onThrallProjectile(-1, BOSS, 203);
		assertTrue("no thrall it can be pinned on", t.dueAttacks().isEmpty());
		t.onThrallProjectile(THRALL + 9, BOSS, 207);
		assertTrue("someone else's", t.dueAttacks().isEmpty());
		t.onThrallProjectile(THRALL, BOSS, 211);
		assertEquals("ours", 1, t.dueAttacks().size());
	}

	@Test
	public void theVarbitMayComeUpTicksAfterTheSpawn()
	{
		// 16:08 log: the ghost spawned around 190 and the varbit only came up on 194, as it finished rising.
		final ThrallTracker t = new ThrallTracker();
		t.onThrallSpawned(THRALL, GHOST, 1, 190);
		t.tick(190);
		t.tick(193);
		t.onResurrectionVarbit(true, 194);
		assertTrue(t.hasThrall());
		assertEquals(THRALL, t.ownedIndex());
	}

	@Test
	public void aProjectileSeenBeforeItsThrallIsClaimedStillBringsItsSplat()
	{
		// 16:08 log, tick 194: the ghost's first projectile came ahead of the varbit, and its 1 went to the player.
		final ThrallTracker t = new ThrallTracker();
		t.onThrallSpawned(THRALL, GHOST, 1, 190);
		t.onThrallProjectile(THRALL, BOSS, 194);
		t.onResurrectionVarbit(true, 194);
		assertEquals(ThrallTracker.RATE_THRALL_PROJECTILE, t.thrallRate(BOSS, 194), 0);
		assertTrue(t.probability(splat(1, 194, ThrallTracker.PlayerSplat.ON_CADENCE)) > 0.9);
	}

	@Test
	public void aRecastsProjectileFiredBeforeItsClaimIsHeldForIt()
	{
		final ThrallTracker t = summoned(10, GHOST);
		t.onThrallSpawned(THRALL + 1, GHOST, 1, 100);
		t.onThrallProjectile(THRALL + 1, BOSS, 101);
		assertTrue("not ours yet", t.dueAttacks().isEmpty());
		t.onSummonCast(101);
		assertEquals(THRALL + 1, t.ownedIndex());
		assertEquals(1, t.dueAttacks().size());
	}

	@Test
	public void aGhostsSplatWithNoProjectileIsNotItsOwn()
	{
		// 197 in the Vardorvis log: a player's miss, before the ghost had fired at all.
		final ThrallTracker t = summoned(194, GHOST);
		assertTrue(t.probability(splat(0, 197, ThrallTracker.PlayerSplat.ON_CADENCE)) < 0.5);
		// And once its projectiles are being seen, a splat without one is all but impossible.
		t.onThrallProjectile(THRALL, BOSS, 199);
		t.noteThrallSplat(BOSS, 199);
		assertEquals(ThrallTracker.RATE_THRALL_NO_PROJECTILE, t.thrallRate(BOSS, 202), 0);
		assertTrue(t.probability(splat(3, 202, ThrallTracker.PlayerSplat.UNEXPLAINED)) < 0.5);
	}

	@Test
	public void aGhostsProjectileOutweighsThePlayersCadenceForASmallSplat()
	{
		final ThrallTracker t = summoned(194, GHOST);
		t.onThrallProjectile(THRALL, BOSS, 199);
		assertTrue(t.probability(splat(3, 199, ThrallTracker.PlayerSplat.ON_CADENCE)) > 0.9);
	}

	@Test
	public void theWeaponsCooldownDecidesWhatTheBlowCouldBe()
	{
		// A 4-tick weapon thrown on 196 is next due on 200; its blow lands the tick after it is thrown.
		final int due = 200;
		final AttackSplats.State none = AttackSplats.State.NONE;
		assertEquals(ThrallTracker.PlayerSplat.IN_COOLDOWN,
			ThrallTracker.classifyPlayer(none, false, true, 199, due, false));
		assertEquals(ThrallTracker.PlayerSplat.IN_COOLDOWN,
			ThrallTracker.classifyPlayer(none, false, true, 200, due, false));
		assertEquals(ThrallTracker.PlayerSplat.ON_CADENCE,
			ThrallTracker.classifyPlayer(none, false, true, 201, due, false));
		assertEquals(ThrallTracker.PlayerSplat.LATE,
			ThrallTracker.classifyPlayer(none, false, true, 205, due, false));
		assertEquals(ThrallTracker.PlayerSplat.LATE,
			ThrallTracker.classifyPlayer(none, false, true, 202, due, false));
		// A projectile's own tick is a tick less exact.
		assertEquals(ThrallTracker.PlayerSplat.ON_CADENCE,
			ThrallTracker.classifyPlayer(none, false, true, 201, due, true));
		assertEquals(ThrallTracker.PlayerSplat.ON_CADENCE,
			ThrallTracker.classifyPlayer(none, false, true, 200, due, true));
		// What the player has in flight, or already landing, comes first.
		assertEquals(ThrallTracker.PlayerSplat.SAME_TICK_BURST,
			ThrallTracker.classifyPlayer(AttackSplats.State.ROOM_THIS_TICK, true, true, 199, due, false));
		assertEquals(ThrallTracker.PlayerSplat.PROJECTILE_DUE,
			ThrallTracker.classifyPlayer(none, true, true, 199, due, false));
		assertEquals(ThrallTracker.PlayerSplat.UNEXPLAINED,
			ThrallTracker.classifyPlayer(none, false, false, 205, due, false));
		assertEquals(ThrallTracker.PlayerSplat.MELEE_POSSIBLE,
			ThrallTracker.classifyPlayer(none, false, true, 205, Integer.MIN_VALUE, false));
	}

	@Test
	public void aSplatBeyondWhatTheAttackDealsIsNotItsOwn()
	{
		// T-017. 19:34 log, 439: the tentacle's blow thrown on 438 landed, and a second 0 beside it read as on cadence,
		// because that blow is only booked once the tick ends.
		final int due = 438;
		assertEquals(ThrallTracker.PlayerSplat.EXTRA_SPLAT,
			ThrallTracker.classifyPlayer(AttackSplats.State.SPENT_THIS_TICK, false, true, 439, due, false));
		assertEquals(ThrallTracker.PlayerSplat.ON_CADENCE,
			ThrallTracker.classifyPlayer(AttackSplats.State.NONE, false, true, 439, due, false));
		// Another attack's projectile can still be due beside it.
		assertEquals(ThrallTracker.PlayerSplat.PROJECTILE_DUE,
			ThrallTracker.classifyPlayer(AttackSplats.State.SPENT_THIS_TICK, true, true, 439, due, false));
		// The tick after: splats known to come late are due, a special's may be.
		assertEquals(ThrallTracker.PlayerSplat.LATE_SPLAT_DUE,
			ThrallTracker.classifyPlayer(AttackSplats.State.LATE_DUE, false, true, 440, 442, false));
		assertEquals(ThrallTracker.PlayerSplat.BURST_CONTINUING,
			ThrallTracker.classifyPlayer(AttackSplats.State.LATE_MAYBE, false, true, 440, 442, false));
		// An extra is as unlikely to be the player's as a blow inside the cooldown.
		assertEquals(ThrallTracker.PlayerSplat.IN_COOLDOWN.rate, ThrallTracker.PlayerSplat.EXTRA_SPLAT.rate, 0);
	}

	@Test
	public void aSplatInsideTheWeaponsCooldownGoesToTheThrallEvenUnseen()
	{
		// 199 and 203 in the Vardorvis log were booked as blows inside the tentacle's cooldown.
		final ThrallTracker t = summoned(190);
		assertTrue(t.probability(splat(0, 203, ThrallTracker.PlayerSplat.IN_COOLDOWN)) > 0.5);
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

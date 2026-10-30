package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import net.runelite.api.GameState;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-088: a boss gone from view alive is not a finished fight. Synthetic, in the
 * shape of Eric's Giant Mole kill of 2026-10-29 ([11616]-[12729] of the 13:50
 * log, nothing copied): a warhammer spec and a teleport, a return to the same
 * mole under a new index, two burrows, then a world hop to a different mole.
 */
public class BossOutOfViewTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int MOLE = NpcID.MOLE_GIANT;
	private static final int WORLD = 420;

	private static BossAway away(int index, int tick, boolean playerLeft)
	{
		return new BossAway(MOLE, index, "Giant Mole", WORLD, tick, 0L, playerLeft, AnimationID.MOLE_BURROW_DOWN);
	}

	@Test
	public void theMoleIsOnePerWorldWithABurrowWindow()
	{
		assertEquals(5779, MOLE);
		assertEquals(3314, AnimationID.MOLE_BURROW_DOWN);
		assertEquals(3315, AnimationID.MOLE_BURROW_UP);
		final BossRules.Boss mole = RULES.forNpc(MOLE);
		assertNotNull(mole);
		assertTrue(mole.onePerWorld);
		final BossRules.StateRule burrowed = mole.damageModifiers.states.get(0);
		assertEquals("burrowed", burrowed.name);
		assertEquals(AnimationID.MOLE_BURROW_DOWN, burrowed.startOnBossAnim[0]);
		assertEquals(AnimationID.MOLE_BURROW_UP, burrowed.endOnBossAnim[0]);
		assertEquals("burrowed", mole.uptimeAllowances.get(0).state);
		// No other boss is one per world yet.
		assertFalse(RULES.forNpc(NpcID.SNAKEBOSS_BOSS_RANGED).onePerWorld);
		assertFalse(RULES.forNpc(NpcID.MAGGOT_KING).onePerWorld);
	}

	@Test
	public void aTripIsTheLoadingScreenABurrowIsNot()
	{
		// The teleport at [11640]: the area loads as she despawns, or the tick before.
		assertTrue(BossAway.playerLeft(GameState.LOADING, Integer.MIN_VALUE, 11640));
		assertTrue(BossAway.playerLeft(GameState.LOGGED_IN, 11640, 11640));
		assertTrue(BossAway.playerLeft(GameState.LOGGED_IN, 11639, 11640));
		// A hop or a logout.
		assertTrue(BossAway.playerLeft(GameState.HOPPING, Integer.MIN_VALUE, 12023));
		assertTrue(BossAway.playerLeft(GameState.LOGIN_SCREEN, Integer.MIN_VALUE, 12023));
		// The burrows at [11829], [11866], [11921]: logged in, nothing loading, the last load long before.
		assertFalse(BossAway.playerLeft(GameState.LOGGED_IN, Integer.MIN_VALUE, 11829));
		assertFalse(BossAway.playerLeft(GameState.LOGGED_IN, 11700, 11866));
	}

	@Test
	public void theSameMoleComesBack()
	{
		// After the trip, on the same world, under a new index (54797 -> 57647): the same mole, as Eric says.
		final BossAway trip = away(54797, 11640, true);
		assertTrue(trip.isBack(MOLE, 57647, WORLD, true));
		// A boss of which a world can have several is the same only on its own index.
		assertFalse(trip.isBack(MOLE, 57647, WORLD, false));
		assertTrue(trip.isBack(MOLE, 54797, WORLD, false));
		// After a burrow, on the same index (57647 throughout).
		assertTrue(away(57647, 11829, false).isBack(MOLE, 57647, WORLD, false));
		// A world hop is a different mole ([12008]: index 50823), and so is another npc.
		assertFalse(trip.isBack(MOLE, 50823, WORLD + 1, true));
		assertFalse(trip.isBack(NpcID.MAGGOT_KING, 57647, WORLD, true));
	}

	@Test
	public void aFightThatNeverResumesEndsAtTheTimeout()
	{
		final BossAway gone = away(57647, 11921, false);
		assertFalse(gone.timedOut(11921 + BossAway.TIMEOUT_TICKS));
		assertTrue(gone.timedOut(11921 + BossAway.TIMEOUT_TICKS + 1));
	}

	@Test
	public void aFreshMoleOnReturnIsANewFight()
	{
		// Back to her with 50 of 200 left; a hit of 20 puts the overhead bar at 30 of 200, about 4 of 30 segments.
		assertFalse(BossAway.freshOnReturn(4, 30, 50, 200));
		assertFalse(BossAway.freshOnReturn(5, 30, 50, 200));
		// Another player's kill and her respawn: 180 of 200 after the same hit.
		assertTrue(BossAway.freshOnReturn(27, 30, 50, 200));
		// No bar shown, or nothing known: no verdict.
		assertFalse(BossAway.freshOnReturn(-1, -1, 50, 200));
		assertFalse(BossAway.freshOnReturn(27, 30, -1, 200));
		assertFalse(BossAway.freshOnReturn(27, 30, 50, -1));
	}

	@Test
	public void aTripIsResumedWithItsHitpointsAndLeftOutOfTheKillsTime()
	{
		// [11616]: the fight opens on 54797; 32 dealt before the teleport.
		final Fight fight = new Fight("Giant Mole", MOLE, 54797, 200, 0L, null, 0);
		fight.recordAttackMade(true, 1, false);
		fight.recordDamageDealt(32, 10_000L, true);
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(54797, MOLE, 200, 11616);
		health.damage(54797, MOLE, 32, 11623);
		assertEquals(168, health.remaining(54797, 11623));
		// Back after the trip, 101 ticks (about a minute) later, under 57647.
		health.move(54797, 57647);
		fight.resumeAfterTrip(57647, 60_600L);
		assertEquals(57647, fight.getTargetIndex());
		assertEquals(1, fight.getTrips());
		assertEquals(168, health.remaining(57647, 11741));
		assertEquals(-1, health.remaining(54797, 11741));
		// 118 more, as the log's second fight dealt: one fight of 150 against her 200, not two against 200 each.
		fight.recordAttackMade(true, 1, false);
		fight.recordDamageDealt(118, 120_000L, true);
		health.damage(57647, MOLE, 118, 11827);
		assertEquals(150, fight.getDamageDealt());
		assertEquals(50, health.remaining(57647, 11827));
		// The kill's time leaves the trip out.
		fight.end(true, 200_000L);
		assertEquals(200_000L - 60_600L, fight.durationMillis());
		assertEquals(60_600L, fight.getAwayMillis());
	}

	@Test
	public void theDrainGoesWithHerAndRegeneratesThroughTheGap()
	{
		// [11623]: the warhammer takes her Defence 200 -> 140. The second spec at [11747] drained 200 -> 140 again: the
		// first was forgotten with the index.
		final DrainTracker tracker = new DrainTracker(null);
		final DrainRule.Target mole = new DrainRule.Target(200, 200, 80, 200, 200, 1, 0, 0, false, false, false, false, 100);
		tracker.track(54797, MOLE, "Giant Mole", mole);
		tracker.ownThrow(11622, SpecialWeapon.DRAGON_WARHAMMER, 54797, MOLE, "Giant Mole", 11623, mole, false);
		tracker.ownSplat(54797, 11623, 32);
		tracker.tick(11623);
		assertEquals(140, tracker.tracked(54797).state.defence);
		// Kept while she is out of view, regenerating a level each 100 ticks, then moved to her new index.
		for (int t = 11624; t <= 11741; t++)
		{
			tracker.tick(t);
		}
		tracker.move(54797, 57647);
		assertNull(tracker.tracked(54797));
		assertEquals(141, tracker.tracked(57647).state.defence);
		// A second warhammer now drains from 141, not from a fresh 200.
		tracker.ownThrow(11746, SpecialWeapon.DRAGON_WARHAMMER, 57647, MOLE, "Giant Mole", 11747, mole, false);
		tracker.ownSplat(57647, 11747, 41);
		tracker.tick(11747);
		assertEquals(141 - 141 * 3 / 10, tracker.tracked(57647).state.defence);
	}

	@Test
	public void aBurrowsIdleTicksCountOnlyInsideItsWindow()
	{
		// The burrow at [11829]: the window opens on her 3314 and stays open while she is gone; the chase to the next hit at
		// [11863] is idle ticks the allowance takes up to its cap, and an idle tick outside any window is not counted at all.
		final MechanicAllowances mechanics = new MechanicAllowances(null);
		final java.util.List<BossRules.UptimeAllowance> allowances = RULES.forNpc(MOLE).uptimeAllowances;
		for (int t = 11826; t <= 11863; t++)
		{
			final int tick = t;
			mechanics.observe(tick, allowances, state -> tick >= 11826 ? new int[]{11826, 11926} : null);
		}
		assertFalse(mechanics.covers(11820));
		assertTrue(mechanics.covers(11840));
		int excused = 0;
		for (int t = 11829; t < 11863; t++)
		{
			excused += mechanics.excuse(t) != null ? 1 : 0;
		}
		assertEquals(34, excused);
	}
}

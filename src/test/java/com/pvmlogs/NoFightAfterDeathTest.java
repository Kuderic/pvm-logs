package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-127, from Eric's 22:28 log (build 4205249, read in place, nothing
 * copied): he died at Vardorvis on [20621] (ActorDeath), the fight and room
 * closed, then that tick's swing - XP on [20621], its 39 landing on [20622] -
 * opened a fresh fight on the same Vardorvis, saved at its despawn on [20628]
 * as a one-attack, 3-second fight.
 */
public class NoFightAfterDeathTest
{
	private static final int DEATH = 20621;

	@Test
	public void noFightOpensOnTheDeathTickNorAfterUntilTheRespawn()
	{
		final PlayerDeath death = new PlayerDeath();
		assertTrue(death.opensFights(DEATH - 1));
		assertTrue(death.died(DEATH));
		// [20621]: the swing's XP, and the tick handler's look at the target, come after the death.
		assertFalse(death.opensFights(DEATH));
		// A hitpoints reading still above 0 on the death tick is the old life's, not a respawn.
		assertFalse(death.hitpoints(17, DEATH));
		assertFalse(death.opensFights(DEATH));
		// [20622]: the 39 lands; [20628]: Vardorvis despawns with the respawn's loading screen. Still dead throughout.
		for (int tick = DEATH + 1; tick <= DEATH + 7; tick++)
		{
			assertFalse("tick " + tick, death.opensFights(tick));
		}
		// With no respawn seen at all, the safety cap opens them again, so a missed reading can't shut them for good.
		assertFalse(death.opensFights(DEATH + PlayerDeath.STUCK_TICKS));
		assertTrue(death.opensFights(DEATH + PlayerDeath.STUCK_TICKS + 1));
		// The second signal on a later tick acts no more.
		assertFalse(death.hitpoints(0, DEATH + 1));
		assertEquals(DEATH, death.tick());
	}

	@Test
	public void theRespawnLetsTheNextAttemptOpenItsOwnRoom()
	{
		final PlayerDeath death = new PlayerDeath();
		death.died(DEATH);
		// The respawn: hitpoints back above 0 on a later tick.
		assertFalse(death.hitpoints(99, DEATH + 9));
		assertTrue(death.opensFights(DEATH + 9));
		assertFalse(death.isDead());
		// The room closed at the death without its kill (T-100), so the next attempt's fight is a room of its own.
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final Fight dying = new Fight("Vardorvis", NpcID.VARDORVIS, 44015, 700, 0L, null, 0);
		dying.recordAttackMade(false);
		room.add(dying);
		assertEquals(PlayerDeath.Outcome.ROOM_ENDS, PlayerDeath.outcome(false, room, false));
		dying.end(false, 1L);
		room.closeWithoutKill(1L, "died");
		final Fight next = new Fight("Vardorvis", NpcID.VARDORVIS, 50000, 700, 2L, null, 0);
		assertFalse(PvmPerformancePlugin.switchesWithinRoom(room, next, NpcID.VARDORVIS));
		assertFalse(LedgerHistory.isKill(room));
		// A second death in the new life is one again.
		assertTrue(death.died(DEATH + 500));
		assertFalse(death.opensFights(DEATH + 500));
	}

	@Test
	public void aKillOnTheTickOfTheDeathStandsAsAKill()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final Fight fight = new Fight("Vardorvis", NpcID.VARDORVIS, 44015, 700, 0L, null, 0);
		fight.recordAttackMade(false);
		room.add(fight);
		// The boss already shows dead as the player dies: the fight is left for its own death to end, as a kill.
		assertEquals(PlayerDeath.Outcome.KILL_STANDS, PlayerDeath.outcome(false, room, true));
		// As T-100 has it otherwise: not dead, the room ends without its kill; in a raid it goes on; nothing open, nothing.
		assertEquals(PlayerDeath.Outcome.ROOM_ENDS, PlayerDeath.outcome(false, room, false));
		assertEquals(PlayerDeath.Outcome.RAID_GOES_ON, PlayerDeath.outcome(true, room, true));
		assertEquals(PlayerDeath.Outcome.NO_ROOM, PlayerDeath.outcome(false, null, true));
		// A run of monsters ends at the death, its kills kept, whatever its last target did.
		final Encounter run = new Encounter("Abyssal demon", null, 0L);
		final Fight demon = new Fight("Abyssal demon", 415, 1, 150, 0L, null, 0);
		demon.groupAsMonster();
		demon.recordAttackMade(false);
		run.add(demon);
		assertEquals(PlayerDeath.Outcome.RUN_ENDS, PlayerDeath.outcome(false, run, true));
		// The fight then ends on the target's death, which is a kill whoever died beside it.
		fight.markPlayerDied();
		fight.end(true, 5L);
		assertTrue(fight.isTargetDied());
		assertTrue(LedgerHistory.isKill(room));
	}

	@Test
	public void theOldOutcomeReadsAsBefore()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final Fight fight = new Fight("Vardorvis", NpcID.VARDORVIS, 44015, 700, 0L, null, 0);
		fight.recordAttackMade(false);
		room.add(fight);
		assertEquals(PlayerDeath.outcome(false, room, false), PlayerDeath.outcome(false, room));
	}
}

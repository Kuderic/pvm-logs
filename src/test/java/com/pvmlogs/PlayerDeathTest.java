package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-100: dying ends the kill, and a fresh boss after a trip is a new kill.
 * Eric's Araxxor log of 2026-10-29 21:40 (build aee9461, read in place,
 * nothing copied): he died at [2272], the respawn's loading screen at [2279]
 * was read as a trip, and his next attempt's Araxxor (a new index) joined the
 * room, which closed at [2927] with 10 fights as one kill.
 *
 * <p>Replayed through the rules the plugin calls - {@link PlayerDeath},
 * {@link PvmPerformancePlugin#opensRoom}, {@link Encounter#freshPartAfterLeaving},
 * {@link Encounter#closeWithoutKill} - and the history's own room rebuild. The
 * plugin itself needs a client.
 */
public class PlayerDeathTest
{
	private static final int ARAXXOR = NpcID.ARAXXOR;
	private static final int MIRRORBACK = NpcID.ARAXXOR_MINION_MIRRORBACK;

	/** The rooms a sequence of events makes, as the plugin's openEncounterFor and death handler do. */
	private static final class Replay
	{
		final List<Encounter> rooms = new ArrayList<>();
		final List<Fight> history = new ArrayList<>();
		final PlayerDeath death = new PlayerDeath();
		Encounter room;
		Fight open;
		long now;
		RaidType raid;

		Replay attack(int npcId, int index)
		{
			now += 600;
			endOpen(false);
			open = new Fight("target", npcId, index, 1020, now, raid, raid == null ? 0 : 1);
			open.recordAttackMade(false);
			if (room != null && room.freshPartAfterLeaving(open))
			{
				room.closeWithoutKill(now, "left");
			}
			if (PvmPerformancePlugin.opensRoom(room, open))
			{
				if (room != null && !room.isEnded())
				{
					room.end(now);
				}
				room = new Encounter(open.encounterName(), raid, now);
				rooms.add(room);
			}
			room.add(open);
			return this;
		}

		/** A monster, grouped as a run of its kind (T-089). */
		Replay attackMonster(String name, int npcId, int index)
		{
			now += 600;
			endOpen(false);
			open = new Fight(name, npcId, index, 150, now, null, 0);
			open.groupAsMonster();
			open.recordAttackMade(false);
			if (PvmPerformancePlugin.opensRoom(room, open))
			{
				room = new Encounter(open.encounterName(), null, now);
				rooms.add(room);
			}
			room.add(open);
			return this;
		}

		/** The open fight's target despawns alive with a loading screen (T-088's trip), unless the player is dead. */
		Replay tripAway()
		{
			if (open != null && !open.isEnded() && PlayerDeath.holdsOnDespawn(death.isDead())
				&& BossAway.playerLeft(GameState.LOADING, 100, 100))
			{
				room.noteLeftAlive(open.getTargetIndex(), open.getTargetId());
			}
			return this;
		}

		/** The held part back after the trip, on {@code index}: T-088 resumes the same fight. */
		Replay backOn(int index)
		{
			room.noteBack(open.getTargetIndex());
			return this;
		}

		/** The fight's target died and the room closed on its kill. */
		Replay kill()
		{
			now += 600;
			endOpen(true);
			room.end(now);
			open.closeRoom();
			return this;
		}

		Replay die()
		{
			now += 600;
			if (!death.died(0))
			{
				return this;
			}
			switch (PlayerDeath.outcome(raid != null, room))
			{
				case RAID_GOES_ON:
					open.markPlayerDied();
					return this;
				case RUN_ENDS:
					endOpen(false);
					room.endMonsterRoom(now);
					return this;
				case ROOM_ENDS:
					endOpen(false);
					room.closeWithoutKill(now, "died").markPlayerDied();
					return this;
				default:
					return this;
			}
		}

		Replay respawn()
		{
			death.hitpoints(99, 0);
			return this;
		}

		private void endOpen(boolean died)
		{
			if (open != null && !open.isEnded())
			{
				open.end(died, now);
				history.add(0, open);
			}
		}
	}

	@Test
	public void theHitThatTookTheLastHitpointsIsTheDeath()
	{
		// [2268]-[2272]: TAKEN 16 at hp 65, 5 at 49, 44 at 44. Only the last reaches 0, and the next reading is the respawn.
		final PlayerDeath death = new PlayerDeath();
		assertFalse(death.hitpoints(65 - 16, 2268));
		assertFalse(death.hitpoints(49 - 5, 2271));
		assertTrue(death.hitpoints(44 - 44, 2272));
		assertEquals(2272, death.tick());
		// RuneLite's ActorDeath on the same death acts nothing more.
		assertFalse(death.died(2273));
		assertTrue(death.isDead());
		death.hitpoints(99, 2290);
		assertFalse(death.isDead());
		// A later death is a new one.
		assertTrue(death.died(3000));
	}

	@Test
	public void aDeathInABossRoomClosesItWithoutTheKill()
	{
		final Replay r = new Replay()
			.attack(ARAXXOR, 49486).attack(NpcID.ARAXXOR_MINION_EXPLODE, 51204).attack(ARAXXOR, 49486)
			.attack(MIRRORBACK, 52337).attack(ARAXXOR, 49486)
			.die().respawn()
			// [2754]: his next attempt's Araxxor, a new instance.
			.attack(ARAXXOR, 63826).attack(MIRRORBACK, 64000).attack(ARAXXOR, 63826).kill();
		assertEquals(2, r.rooms.size());
		final Encounter died = r.rooms.get(0);
		assertEquals(5, died.getFights().size());
		assertTrue(died.isEnded());
		assertTrue(died.isOver());
		assertFalse(died.isKilled());
		final Fight last = died.getFights().get(4);
		assertEquals("died", last.getRoomEnd());
		assertTrue(last.isPlayerDied());
		assertFalse(last.isClosedRoomOnKill());
		final Encounter kill = r.rooms.get(1);
		assertEquals(3, kill.getFights().size());
		assertTrue(kill.isKilled());
		// History rebuilds the same two rooms, the first not a kill.
		final List<Encounter> rebuilt = LedgerHistory.rooms(r.history, "Araxxor");
		assertEquals(2, rebuilt.size());
		assertEquals(5, rebuilt.get(0).getFights().size());
		assertFalse(LedgerHistory.isKill(rebuilt.get(0)));
		assertTrue(LedgerHistory.isKill(rebuilt.get(1)));
		assertFalse(rebuilt.get(0).isKilled());
	}

	@Test
	public void theDeathWinsOverTheLoadingScreenAfterIt()
	{
		// [2272] the death, [2279] the respawn's loading screen: no trip, no hold, and no part left alive to wait for.
		final Replay r = new Replay().attack(ARAXXOR, 49486).die().tripAway();
		assertFalse(PlayerDeath.holdsOnDespawn(true));
		assertTrue(PlayerDeath.holdsOnDespawn(false));
		assertTrue(r.open.isEnded());
		assertFalse(r.open.isTargetDied());
		assertTrue(r.room.isEnded());
		// The next Araxxor opens a room of its own whatever its index: the died one takes nothing more.
		r.respawn().attack(ARAXXOR, 49486);
		assertEquals(2, r.rooms.size());
	}

	@Test
	public void theSameNpcAfterATripIsOneRoomAFreshOneIsTwo()
	{
		// Teleported out alive, back to the same index (an instance kept, or the Giant Mole after the bank): one room.
		final Replay same = new Replay().attack(ARAXXOR, 100).tripAway().backOn(100).attack(MIRRORBACK, 200)
			.attack(ARAXXOR, 100).kill();
		assertEquals(1, same.rooms.size());
		assertTrue(same.rooms.get(0).isKilled());
		// Teleported out alive, then a fresh Araxxor under another index: the old room ends as left, not a kill.
		final Replay fresh = new Replay().attack(ARAXXOR, 100).attack(MIRRORBACK, 200).attack(ARAXXOR, 100).tripAway()
			.attack(ARAXXOR, 300).kill();
		assertEquals(2, fresh.rooms.size());
		assertFalse(fresh.rooms.get(0).isKilled());
		assertEquals("left", fresh.rooms.get(0).getFights().get(2).getRoomEnd());
		assertTrue(fresh.rooms.get(1).isKilled());
		assertEquals(2, LedgerHistory.rooms(fresh.history, "Araxxor").size());
	}

	@Test
	public void onlyABossPartThatLeftOnATripMakesTheNextANewAttempt()
	{
		// An add fought on the return is not a fresh boss: Araxxor's araxytes come and go within one attempt.
		final Replay add = new Replay().attack(ARAXXOR, 100).tripAway().attack(MIRRORBACK, 200);
		assertEquals(1, add.rooms.size());
		// An add that left on a trip is not the boss leaving.
		final Replay addLeft = new Replay().attack(ARAXXOR, 100).attack(MIRRORBACK, 200).tripAway().attack(ARAXXOR, 100);
		assertEquals(1, addLeft.rooms.size());
		// Without a trip, a part under another index is the same kill: the Sire's phases, the Guardians, Zulrah's forms.
		final Replay sire = new Replay().attack(NpcID.ABYSSALSIRE_SIRE_WANDERING, 100)
			.attack(NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 101);
		assertEquals(1, sire.rooms.size());
		final Replay zulrah = new Replay().attack(NpcID.SNAKEBOSS_BOSS_RANGED, 100).attack(NpcID.SNAKEBOSS_BOSS_MAGIC, 101);
		assertEquals(1, zulrah.rooms.size());
	}

	@Test
	public void aRaidGoesOnThroughADeath()
	{
		// A guess, no raid log yet: the team can still kill the Wardens, so the room is neither ended nor lost.
		final Replay r = new Replay();
		r.raid = RaidType.TOMBS_OF_AMASCUT;
		r.attack(NpcID.TOA_WARDEN_TUMEKEN_PHASE3, 100).die();
		assertEquals(PlayerDeath.Outcome.RAID_GOES_ON, PlayerDeath.outcome(true, r.room));
		assertFalse(r.room.isEnded());
		assertFalse(r.open.isEnded());
		assertTrue(r.open.isPlayerDied());
		assertNull(r.open.getRoomEnd());
		// No trip in a raid makes a new attempt either: a boss part leaving and another index is the next phase.
		r.tripAway().attack(NpcID.TOA_WARDEN_TUMEKEN_PHASE3, 101);
		assertEquals(1, r.rooms.size());
	}

	@Test
	public void aDeathEndsARunOfMonstersWithItsKills()
	{
		final Replay r = new Replay().attackMonster("Abyssal demon", NpcID.KOUREND_ABYSSAL, 1);
		r.open.recordKill();
		r.attackMonster("Abyssal demon", NpcID.KOUREND_ABYSSAL, 2);
		assertEquals(PlayerDeath.Outcome.RUN_ENDS, PlayerDeath.outcome(false, r.room));
		r.die();
		assertTrue(r.room.isEnded());
		assertEquals(1, r.room.getKills());
		assertTrue(r.room.isKilled());
		// The next demon after the respawn is a new run.
		r.respawn().attackMonster("Abyssal demon", NpcID.KOUREND_ABYSSAL, 3);
		assertEquals(2, r.rooms.size());
	}

	@Test
	public void nothingOpenIsOnlyTheLine()
	{
		assertEquals(PlayerDeath.Outcome.NO_ROOM, PlayerDeath.outcome(false, null));
		final Encounter ended = new Encounter("Araxxor", null, 0);
		ended.end(1);
		assertEquals(PlayerDeath.Outcome.NO_ROOM, PlayerDeath.outcome(false, ended));
	}

	@Test
	public void oldHistoryLoadsUnchanged()
	{
		// A kill and its room boundary saved before T-100: no roomEnd, no playerDied.
		final Gson gson = new Gson();
		final Fight kill = new Fight("Araxxor", ARAXXOR, 1, 1020, 1000, null, 0);
		kill.recordAttackMade(false);
		kill.end(true, 2000);
		kill.closeRoom();
		final JsonObject json = gson.toJsonTree(kill).getAsJsonObject();
		json.remove("roomEnd");
		json.remove("playerDied");
		assertFalse(json.has("roomEnd"));
		final Fight loaded = gson.fromJson(json, Fight.class);
		assertNull(loaded.getRoomEnd());
		assertFalse(loaded.isPlayerDied());
		assertTrue(loaded.isClosedRoom());
		assertTrue(loaded.isClosedRoomOnKill());
		final Encounter room = new Encounter("Araxxor", null, 1000);
		room.add(loaded);
		assertTrue(room.isKilled());
		assertTrue(LedgerHistory.isKill(room));
		// And the new fields round-trip.
		final Fight died = new Fight("Araxxor", ARAXXOR, 2, 1020, 3000, null, 0);
		died.recordAttackMade(false);
		died.end(false, 4000);
		died.closeRoomWithoutKill("died");
		died.markPlayerDied();
		final Fight back = gson.fromJson(gson.toJson(died), Fight.class);
		assertEquals("died", back.getRoomEnd());
		assertTrue(back.isPlayerDied());
		assertFalse(back.isClosedRoomOnKill());
		final List<Fight> saved = Arrays.asList(back, loaded);
		assertEquals(2, LedgerHistory.rooms(saved, "Araxxor").size());
		assertSame(back, LedgerHistory.rooms(saved, "Araxxor").get(1).getFights().get(0));
	}
}

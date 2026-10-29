package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * T-089: monsters that aren't bosses, fought back to back, are one room. A run
 * in the shape of Eric's Hallowfell demon trip of 2026-10-29 - targets
 * switched, the axe splashing the demons beside the target, splashed ones
 * dying while not the target, loot on each - through the plugin's rules
 * ({@link MonsterRunReplay}), and a boss beside it still one fight per kill.
 */
public class MonsterRoomTest
{
	private static final String DEMON = "Abyssal demon";
	// The Catacombs' demons in the log are 7241; the wilderness slayer cave's 415 share the name.
	private static final int CATACOMBS_DEMON = 7241;
	private static final int DEMON_ID = 415;
	private static final String GREATER = "Greater abyssal demon";
	private static final int GREATER_ID = 7410;
	private static final String VORKATH = "Vorkath";
	private static final int VORKATH_ID = 8061;
	private static final long MINUTE = 60_000;

	// ---- what's a boss ----

	@Test
	public void bossesAreTheHiscoresListTheRulesTheGroupsAndRaids()
	{
		final BossRules rules = MonsterRunReplay.RULES;
		assertTrue(MonsterRooms.isMonster(DEMON, CATACOMBS_DEMON, false, rules));
		// A superior slayer monster is a monster: its own kind, set aside the run's way (see below).
		assertTrue(MonsterRooms.isMonster(GREATER, GREATER_ID, false, rules));
		// In boss-rules.json.
		assertFalse(MonsterRooms.isMonster(VORKATH, VORKATH_ID, false, rules));
		// On the hiscores' list, ids unknown to everything else: a god wars general, a wilderness boss, demi-bosses.
		assertFalse(MonsterRooms.isMonster("Kree'arra", 3162, false, null));
		assertFalse(MonsterRooms.isMonster("Callisto", 6609, false, null));
		assertFalse(MonsterRooms.isMonster("Scorpia", 6615, false, null));
		assertFalse(MonsterRooms.isMonster("Skotizo", 7286, false, null));
		assertFalse(MonsterRooms.isMonster("Obor", 7416, false, null));
		assertFalse(MonsterRooms.isMonster("Thermonuclear smoke devil", 499, false, null));
		// An EncounterGroup group.
		assertFalse(MonsterRooms.isMonster("Kraken", net.runelite.api.gameval.NpcID.SLAYER_KRAKEN_BOSS, false, null));
		// The ones the list names otherwise.
		assertFalse(MonsterRooms.isMonster("Dharok the Wretched", 1673, false, null));
		assertFalse(MonsterRooms.isMonster("Crystalline Hunllef", 9021, false, null));
		assertFalse(MonsterRooms.isMonster("The Mimic", 8633, false, null));
		// A raid's every room is the raid's.
		assertFalse(MonsterRooms.isMonster(DEMON, CATACOMBS_DEMON, true, rules));
	}

	@Test
	public void aKindIsItsNameWhateverTheId()
	{
		final Fight onCatacombs = new Fight(DEMON, CATACOMBS_DEMON, 1, 150, 0, null, 0);
		onCatacombs.groupAsMonster();
		assertTrue(MonsterRooms.sameKind(onCatacombs, "Abyssal demon"));
		assertTrue(MonsterRooms.sameKind(onCatacombs, "abyssal demon "));
		assertFalse(MonsterRooms.sameKind(onCatacombs, GREATER));
		assertEquals(DEMON, onCatacombs.encounterName());
		// A boss's fight is never a monster's.
		final Fight onVorkath = new Fight(VORKATH, VORKATH_ID, 2, 750, 0, null, 0);
		assertFalse(MonsterRooms.sameKind(onVorkath, VORKATH));
		assertNull(onVorkath.getGroupName());
	}

	// ---- one room for the run ----

	/** Three demons round the player, as at [13849]: one hit, two splashed, all three dead. */
	private static MonsterRunReplay threeDemons()
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(26).splash(DEMON, 2, 49).expect(24.7, 369.6);
		run.attack().hit(30).splash(DEMON, 2, 20).splash(DEMON, 3, 10).expect(24.7, 369.6);
		// Switched to the second, which the axe had already taken 69 off.
		run.fight(DEMON, CATACOMBS_DEMON, 2, 150).attack().hit(81).expect(24.7, 369.6);
		run.dies(2).loot(CATACOMBS_DEMON, 2);
		// Back to the first: the third dies to the splash, not the target.
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(94).splash(DEMON, 3, 140).expect(24.7, 369.6);
		run.dies(3).loot(CATACOMBS_DEMON, 3);
		run.dies(1).loot(CATACOMBS_DEMON, 1);
		return run;
	}

	@Test
	public void switchingDeathsAndLootDoNotSplitTheRun()
	{
		final MonsterRunReplay run = threeDemons();
		assertEquals(1, run.fought().size());
		final Encounter room = run.room;
		assertEquals(DEMON, room.getName());
		assertTrue(room.isMonsterRoom());
		assertEquals(3, room.getFights().size());
		// Every death and drop on the way: the room is still open, and the overlay reads it as running.
		assertFalse(room.isEnded());
		assertFalse(room.isOver());
		assertFalse(room.isKilled());
	}

	@Test
	public void eachMonstersHitpointsAreCarriedAcrossSwitches()
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(26).splash(DEMON, 2, 49);
		run.fight(DEMON, CATACOMBS_DEMON, 2, 150);
		// What the splash took off it is still counted: the plugin's figures read 101 left, not a full 150.
		assertEquals(49, (int) run.damageByIndex.get(2));
		assertEquals(26, (int) run.damageByIndex.get(1));
		assertEquals(1, run.startedHurt);
		// A boss in between is not a monster's switch: everything is counted afresh.
		run.attack().hit(10);
		run.fight(VORKATH, VORKATH_ID, 9, 750);
		assertTrue(run.damageByIndex.isEmpty());
	}

	@Test
	public void theRoomsDamageAndExpectedIncludeTheSplashAndItsKillsTheSplashed()
	{
		final MonsterRunReplay run = threeDemons();
		final Encounter room = run.room;
		assertEquals(26 + 49 + 30 + 20 + 10 + 81 + 94 + 140, room.getDamageDealt());
		assertEquals(4 * 24.7, LedgerRows.of(room).expectedDamage(), 1e-9);
		// The third died to splash while the first was the target, and counts once though its loot came too.
		assertEquals(3, room.getKills());
		assertEquals(4, room.getAttempts());
	}

	@Test
	public void theRunEndsAtItsLastAttackOnAPause()
	{
		final MonsterRunReplay run = threeDemons();
		final long lastAttack = run.room.getLastAttackMillis();
		run.now = lastAttack + MINUTE - 1;
		run.waits(0);
		assertFalse(run.room.isEnded());
		run.waits(1);
		assertTrue(run.room.isEnded());
		assertEquals(lastAttack, run.room.getEndMillis());
		assertTrue(run.room.isOver());
		assertTrue(run.room.isKilled());
		// Its last fight with an attack carries the boundary into the history.
		assertTrue(run.history.get(0).isClosedRoom());
		// The next demon is a new run.
		run.fight(DEMON, CATACOMBS_DEMON, 4, 150).attack().hit(5);
		assertEquals(2, run.fought().size());
	}

	@Test
	public void theRunEndsWhenThePlayerLeaves()
	{
		final MonsterRunReplay run = threeDemons();
		run.fight(DEMON, CATACOMBS_DEMON, 4, 150).attack().hit(12);
		run.teleports();
		assertTrue(run.room.isEnded());
		assertTrue(run.open.isEnded());
		assertEquals(run.room.getLastAttackMillis(), run.room.getEndMillis());
		assertTrue(MonsterRooms.leftArea(new WorldPoint(1700, 10100, 0), new WorldPoint(1700, 10100, 1)));
		assertFalse(MonsterRooms.leftArea(new WorldPoint(1700, 10100, 0), new WorldPoint(1702, 10098, 0)));
		assertFalse(MonsterRooms.leftArea(null, new WorldPoint(1702, 10098, 0)));
	}

	@Test
	public void anotherKindForASwingSetsTheRunAsideAndItGoesOn()
	{
		// As at [16570]: one swing at a Reanimated abyssal mid-trip.
		final MonsterRunReplay run = threeDemons();
		final Encounter demons = run.room;
		run.fight(DEMON, CATACOMBS_DEMON, 4, 150).attack().hit(44).splash(DEMON, 5, 20);
		run.fight("Reanimated abyssal", 7038, 40, 35).attack().hit(4).splash(DEMON, 5, 0);
		assertSame(demons, run.setAside);
		assertFalse(demons.isEnded());
		run.dies(40);
		run.fight(DEMON, CATACOMBS_DEMON, 5, 150);
		assertSame(demons, run.room);
		assertNull(run.setAside);
		// The demon splashed before the other kind was fought kept what it had taken.
		assertEquals(20, (int) run.damageByIndex.get(5));
		run.attack().hit(30);
		final List<Encounter> fought = run.fought();
		assertEquals(2, fought.size());
		assertEquals(5, demons.getFights().size());
		assertTrue(fought.get(1).isEnded());
	}

	@Test
	public void anotherKindForLongerThanThePauseEndsTheRunAtItsLastAttack()
	{
		final MonsterRunReplay run = threeDemons();
		final Encounter demons = run.room;
		final long lastDemonAttack = demons.getLastAttackMillis();
		run.fight(GREATER, GREATER_ID, 50, 390);
		for (int i = 0; i < 20; i++)
		{
			run.attack().hit(20).waits(3_000);
		}
		assertTrue(demons.isEnded());
		assertEquals(lastDemonAttack, demons.getEndMillis());
		assertNull(run.setAside);
		// Demons again: a run of their own.
		run.fight(DEMON, CATACOMBS_DEMON, 6, 150).attack().hit(10);
		assertEquals(3, run.fought().size());
	}

	@Test
	public void aBossEndsTheRunAndStaysOneFightPerKill()
	{
		final MonsterRunReplay run = threeDemons();
		final Encounter demons = run.room;
		final long lastDemonAttack = demons.getLastAttackMillis();
		run.fight(VORKATH, VORKATH_ID, 9, 750).attack().hit(700).attack().hit(50);
		assertTrue(demons.isEnded());
		assertEquals(lastDemonAttack, demons.getEndMillis());
		run.dies(9).loot(VORKATH_ID, 9);
		final Encounter first = run.room;
		assertTrue(first.isEnded());
		assertEquals(1, first.getFights().size());
		assertNull(first.getFights().get(0).getGroupName());
		assertFalse(first.isMonsterRoom());
		assertEquals(0, first.getKills());
		// The second kill is a room and a fight of its own.
		run.fight(VORKATH, VORKATH_ID, 10, 750).attack().hit(750);
		run.dies(10);
		assertEquals(3, run.fought().size());
		assertTrue(run.room != first);
		assertEquals(1, run.room.getFights().size());
	}

	@Test
	public void aBossSetsNoRunAsideAndEndsOneThatWas()
	{
		final MonsterRunReplay run = threeDemons();
		final Encounter demons = run.room;
		run.fight(GREATER, GREATER_ID, 50, 390).attack().hit(20);
		assertSame(demons, run.setAside);
		run.fight(VORKATH, VORKATH_ID, 9, 750).attack().hit(10);
		assertNull(run.setAside);
		assertTrue(demons.isEnded());
		assertEquals(3, run.fought().size());
	}

	// ---- uptime ----

	@Test
	public void movingToTheNextMonsterIsNotLostTime()
	{
		final Fight demon = new Fight(DEMON, CATACOMBS_DEMON, 1, 150, 0, null, 0);
		demon.groupAsMonster();
		// Targeted after a kill and being walked to: nothing thrown at it yet.
		assertTrue(MonsterRooms.switchingTick(demon, true, false, DEMON, 1));
		// But before anything in the room has been attacked, the tick is the walk in, which counts for nothing anyway.
		assertFalse(MonsterRooms.switchingTick(demon, false, false, DEMON, 1));
		demon.recordAttackMade(false);
		// Attacking it, idle: lost.
		assertFalse(MonsterRooms.switchingTick(demon, true, false, DEMON, 1));
		assertFalse(MonsterRooms.switchingTick(demon, true, false, null, -1));
		// Already aimed at the next demon: moving.
		assertTrue(MonsterRooms.switchingTick(demon, true, false, DEMON, 2));
		// Aimed at another kind: lost, as anywhere else.
		assertFalse(MonsterRooms.switchingTick(demon, true, false, GREATER, 2));
		// Eating is eating.
		assertFalse(MonsterRooms.switchingTick(demon, true, true, DEMON, 2));
		// A boss's fight keeps today's rules.
		final Fight vorkath = new Fight(VORKATH, VORKATH_ID, 9, 750, 0, null, 0);
		assertFalse(MonsterRooms.switchingTick(vorkath, true, false, VORKATH, 9));
	}

	// ---- History ----

	@Test
	public void historyShowsTheRunAsOneRoomWithItsKills()
	{
		final MonsterRunReplay run = threeDemons();
		run.waits(MINUTE);
		final List<Encounter> rooms = SidePanelHistory.allRooms(run.history);
		assertEquals(1, rooms.size());
		final Encounter rebuilt = rooms.get(0);
		assertEquals(DEMON, rebuilt.getName());
		assertEquals(3, rebuilt.getKills());
		assertTrue(rebuilt.isKilled());
		assertEquals(LedgerHistory.rooms(run.history, DEMON).size(), 1);
		// Two runs are two rooms.
		run.fight(DEMON, CATACOMBS_DEMON, 7, 150).attack().hit(150);
		run.dies(7).waits(MINUTE);
		assertEquals(2, SidePanelHistory.allRooms(run.history).size());
		assertEquals(1, LedgerHistory.rooms(run.history, DEMON).get(1).getKills());
	}

	@Test
	public void historySavedBeforeShowsAFightPerMonsterAsItDid()
	{
		// A fight saved before T-089 carries neither field: no group, no run, no kills.
		final Gson gson = new Gson();
		final Fight old = gson.fromJson("{\"targetName\":\"Abyssal demon\",\"targetId\":7241,\"targetIndex\":1,"
			+ "\"maxHp\":150,\"attempts\":1,\"ended\":true,\"targetDied\":true}", Fight.class);
		assertFalse(old.isMonsterRoom());
		assertEquals(0, old.getKills());
		assertNull(old.getGroupName());
		final Fight older = gson.fromJson(gson.toJson(old), Fight.class);
		final List<Encounter> rooms = SidePanelHistory.allRooms(java.util.Arrays.asList(old, older));
		assertEquals(2, rooms.size());
		assertTrue(SidePanelHistory.isKill(rooms.get(0)));
		// A new fight's run, kills and group go into the file and come back.
		final MonsterRunReplay run = threeDemons();
		run.waits(MINUTE);
		final Fight[] back = gson.fromJson(gson.toJson(run.history), Fight[].class);
		assertTrue(back[0].isMonsterRoom());
		assertEquals(DEMON, back[0].getGroupName());
		int kills = 0;
		for (Fight f : back)
		{
			kills += f.getKills();
		}
		assertEquals(3, kills);
		assertEquals(1, SidePanelHistory.allRooms(java.util.Arrays.asList(back)).size());
	}
}

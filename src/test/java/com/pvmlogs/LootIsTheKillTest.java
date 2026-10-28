package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-055: whose loot is a boss's kill. Only a boss's own drop ends its fight from
 * another NPC and closes its room; an add's ends only the add's own fight.
 */
public class LootIsTheKillTest
{
	private static Fight fight(int npcId, int index)
	{
		return new Fight("target", npcId, index, 100, 0L, null, 0);
	}

	private static Encounter room(int... npcIds)
	{
		final Encounter room = new Encounter("room", null, 0L);
		int index = 1;
		for (int id : npcIds)
		{
			room.add(fight(id, index++));
		}
		return room;
	}

	@Test
	public void theSiresAddsAreNotItsKill()
	{
		for (int id : new int[]{NpcID.ABYSSALSIRE_SCION, NpcID.ABYSSALSIRE_SCION_DYING, NpcID.ABYSSALSIRE_SPAWN,
			NpcID.ABYSSALSIRE_SPAWN_DYING, NpcID.ABYSSALSIRE_LUNG, NpcID.ABYSSALSIRE_LUNG_DYING})
		{
			assertFalse(EncounterGroup.lootIsTheKill(id));
		}
		for (int id : new int[]{5886, 5887, 5888, 5889, 5890, 5891, 5908})
		{
			assertTrue(EncounterGroup.lootIsTheKill(id));
		}
	}

	/** A scion's drop, mid-kill: the Sire's fight stays open, and so does its room. */
	@Test
	public void anAddsDropEndsNothingOfTheBosss()
	{
		final Fight sire = fight(5890, 24479);
		assertFalse(PvmPerformancePlugin.lootEndsFight(sire, NpcID.ABYSSALSIRE_SCION, 34631));
		assertFalse(PvmPerformancePlugin.lootEndsFight(sire, NpcID.ABYSSALSIRE_SPAWN, 36146));
		final Encounter room = room(5886, NpcID.ABYSSALSIRE_LUNG, NpcID.ABYSSALSIRE_SCION, 5890);
		assertFalse(room.closesOnLootFrom(NpcID.ABYSSALSIRE_SCION));
		assertFalse(room.closesOnLootFrom(NpcID.ABYSSALSIRE_SPAWN));
	}

	/** An add's drop still ends the add's own fight, when that is the one open. */
	@Test
	public void anAddsDropEndsItsOwnFight()
	{
		assertTrue(PvmPerformancePlugin.lootEndsFight(fight(NpcID.ABYSSALSIRE_SPAWN, 36147),
			NpcID.ABYSSALSIRE_SPAWN, 36147));
		// Another spawn's does not.
		assertFalse(PvmPerformancePlugin.lootEndsFight(fight(NpcID.ABYSSALSIRE_SPAWN, 36147),
			NpcID.ABYSSALSIRE_SPAWN, 36146));
	}

	/** The Sire's own drop, from its last form: it ends whatever fight in the room is open and closes the room. */
	@Test
	public void theRoomClosesOnTheSiresOwnLoot()
	{
		assertTrue(PvmPerformancePlugin.lootEndsFight(fight(5891, 24479), 5908, 24479));
		// Even with a spawn's fight open: the boss died.
		assertTrue(PvmPerformancePlugin.lootEndsFight(fight(NpcID.ABYSSALSIRE_SPAWN, 36147), 5908, 24479));
		final Encounter room = room(5886, NpcID.ABYSSALSIRE_LUNG, 5890, 5891);
		assertTrue(room.closesOnLootFrom(5908));
		room.end(1000L);
		assertFalse(room.closesOnLootFrom(5908));
		// An ended fight is not ended again.
		final Fight done = fight(5891, 24479);
		done.end(true, 0L);
		assertFalse(PvmPerformancePlugin.lootEndsFight(done, 5908, 24479));
		assertFalse(PvmPerformancePlugin.lootEndsFight(null, 5908, 24479));
	}

	/** The other groups outside the raids: their adds' drops are not the kill, their bosses' are. */
	@Test
	public void theOtherGroupsAdds()
	{
		// Yama and his Judge.
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.YAMA_JUDGE_OF_YAMA));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.LEAGUE6_JUDGE_OF_YAMA));
		assertFalse(PvmPerformancePlugin.lootEndsFight(fight(NpcID.YAMA, 5), NpcID.YAMA_JUDGE_OF_YAMA, 6));
		assertTrue(PvmPerformancePlugin.lootEndsFight(fight(NpcID.YAMA_JUDGE_OF_YAMA, 6), NpcID.YAMA, 5));
		assertTrue(room(NpcID.YAMA_JUDGE_OF_YAMA).closesOnLootFrom(NpcID.YAMA));
		// Hespori's flowers.
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.HESPORI_HEALER_ACTIVE));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.TOBQUEST_HESPORI_HEALER_INACTIVE));
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.HESPORI));
		// Phosani's adds and the totems.
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.NIGHTMARE_CHALLENGE_PARASITE));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.NIGHTMARE_CHALLENGE_SLEEPWALKER));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.NIGHTMARE_TOTEM_3_CHARGED));
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.NIGHTMARE_CHALLENGE_PHASE_05));
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.NIGHTMARE_PHASE_03));
		// The Hueycoatl has no adds: its drop is the kill whichever part it comes from.
		for (int id : new int[]{NpcID.HUEY_HEAD, NpcID.HUEY_HEAD_DEFEATED, NpcID.HUEY_TAIL, NpcID.HUEY_TAIL_BROKEN,
			NpcID.HUEY_BODY_PART, NpcID.HUEY_BODY_PART_BROKEN})
		{
			assertTrue(EncounterGroup.lootIsTheKill(id));
		}
		assertTrue(PvmPerformancePlugin.lootEndsFight(fight(NpcID.HUEY_BODY_PART, 3), NpcID.HUEY_HEAD_DEFEATED, 1));
		assertTrue(room(NpcID.HUEY_BODY_PART).closesOnLootFrom(NpcID.HUEY_HEAD_DEFEATED));
	}

	/**
	 * Bosses that stand alone: their own loot ends their fight and closes their
	 * room, and loot from anything else never does (Vardorvis, a Dagannoth King
	 * beside the others, the Kraken beside a tentacle, Vet'ion beside a hound).
	 */
	@Test
	public void ungroupedBossesOnlyEndOnTheirOwnLoot()
	{
		assertTrue(PvmPerformancePlugin.lootEndsFight(fight(12223, 9), 12223, 9));
		assertTrue(room(12223).closesOnLootFrom(12223));
		assertFalse(PvmPerformancePlugin.lootEndsFight(fight(NpcID.DAGCAVE_RANGED_BOSS, 1),
			NpcID.DAGCAVE_MELEE_BOSS, 2));
		assertFalse(room(NpcID.DAGCAVE_RANGED_BOSS).closesOnLootFrom(NpcID.DAGCAVE_MELEE_BOSS));
		assertFalse(room(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE).closesOnLootFrom(NpcID.SLAYER_KRAKEN_BOSS));
		assertFalse(PvmPerformancePlugin.lootEndsFight(fight(NpcID.VETION, 1), NpcID.VETION_HELLHOUND_JNR, 2));
		assertFalse(PvmPerformancePlugin.lootEndsFight(fight(NpcID.SNAKEBOSS_BOSS_MAGIC, 1),
			NpcID.SNAKEBOSS_MINION_MELEE, 2));
		assertFalse(PvmPerformancePlugin.lootEndsFight(fight(NpcID.RT_FIRE_QUEEN, 1), NpcID.RT_ICE_KING, 2));
		assertTrue(room(NpcID.SMOKE_DEVIL_BOSS).closesOnLootFrom(NpcID.SMOKE_DEVIL_BOSS));
	}
}

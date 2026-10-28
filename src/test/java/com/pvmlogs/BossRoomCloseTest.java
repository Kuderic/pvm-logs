package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.hiscore.HiscoreSkill;
import org.junit.Test;

/**
 * T-061: the Grotesque Guardians, the Royal Titans and the Kraken are one room
 * per kill, closed by the boss's own loot or, where no loot event comes, by the
 * last part going down; Hespori's room closes at its death.
 *
 * <p>Replayed through the plugin's own rules - its room rule
 * ({@link PvmPerformancePlugin#opensRoom}), the loot rules
 * ({@link PvmPerformancePlugin#lootEndsFight}, {@link Encounter#closesOnLootFrom}),
 * the death rule ({@link Encounter#closesOnDeathOf}) and the real group tables.
 * The plugin itself needs a client. The event sequences are written from the
 * wiki and RuneLite's LootManager, not from a log: none of these kills has been
 * logged yet.
 */
public class BossRoomCloseTest
{
	/** The rooms a sequence of events makes, as the plugin opens and closes them. */
	private static final class Replay
	{
		final List<Encounter> rooms = new ArrayList<>();
		Encounter room;
		Fight open;
		long now;

		/** The player attacks an NPC: a fight opens on it, in the open room or a new one. */
		Replay attack(int npcId, int index)
		{
			now += 600;
			if (open != null && !open.isEnded())
			{
				open.end(false, now);
			}
			open = new Fight("target", npcId, index, 100, now, null, 0);
			open.recordAttackMade(false);
			if (PvmPerformancePlugin.opensRoom(room, open))
			{
				if (room != null && !room.isEnded())
				{
					room.end(now);
				}
				room = new Encounter(open.encounterName(), null, now);
				rooms.add(room);
			}
			room.add(open);
			return this;
		}

		/** The NPC drops loot. */
		Replay loot(int npcId, int index)
		{
			now += 600;
			if (PvmPerformancePlugin.lootEndsFight(open, npcId, index))
			{
				open.end(true, now);
			}
			if (room != null && room.closesOnLootFrom(npcId))
			{
				room.end(now);
			}
			return this;
		}

		/** The NPC despawns dead ({@code fell} false) or changes into its beaten form. */
		Replay down(int npcId, int index, boolean fell)
		{
			now += 600;
			if (open != null && !open.isEnded() && open.getTargetIndex() == index)
			{
				open.end(true, now);
			}
			if (room != null && room.closesOnDeathOf(npcId, index, fell))
			{
				room.end(now);
			}
			return this;
		}
	}

	// ---- the groups ----

	@Test
	public void theGrotesqueGuardiansAreOneGroup()
	{
		for (int id = NpcID.GARGBOSS_DUSK_SPAWN; id <= NpcID.GARGBOSS_DUSK_PHASE1_FLYTRANSITION; id++)
		{
			assertEquals(String.valueOf(id), "Grotesque Guardians", EncounterGroup.of(id));
		}
		for (int id = NpcID.GARGBOSS_DUSK_PHASE2_ATTACKING; id <= NpcID.GARGBOSS_DUSK_DEATH; id++)
		{
			assertEquals(String.valueOf(id), "Grotesque Guardians", EncounterGroup.of(id));
		}
		// The Chambers' guardians are another group.
		assertEquals("Guardians", EncounterGroup.of(NpcID.RAIDS_STONEGUARDIANS_LEFT));
		assertFalse(EncounterGroup.sameGroup(NpcID.GARGBOSS_DUSK_PHASE4, NpcID.RAIDS_STONEGUARDIANS_LEFT));
		// Both of them are the kill: neither is an add.
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.GARGBOSS_DAWN_PHASE3));
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.GARGBOSS_DUSK_DEATH));
		assertNull(EncounterGroup.deathClose(NpcID.GARGBOSS_DUSK_PHASE4));
	}

	@Test
	public void theRoyalTitansAreOneGroup()
	{
		for (int id : new int[]{NpcID.RT_FIRE_QUEEN, NpcID.RT_ICE_KING, NpcID.RT_FIRE_QUEEN_INACTIVE,
			NpcID.RT_ICE_KING_INACTIVE, NpcID.RT_SUMMON_ELEMENTAL_FIRE, NpcID.RT_SUMMON_ELEMENTAL_ICE})
		{
			assertEquals(String.valueOf(id), "The Royal Titans", EncounterGroup.of(id));
		}
		// The elementals are adds, and unscored; the titans are neither.
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.RT_SUMMON_ELEMENTAL_FIRE));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.RT_SUMMON_ELEMENTAL_ICE));
		assertTrue(EncounterGroup.isUnscored(NpcID.RT_SUMMON_ELEMENTAL_FIRE));
		assertTrue(EncounterGroup.isUnscored(NpcID.RT_SUMMON_ELEMENTAL_ICE));
		assertFalse(EncounterGroup.isUnscored(NpcID.RT_FIRE_QUEEN));
		assertFalse(EncounterGroup.isUnscored(NpcID.RT_ICE_KING));
		// A beaten titan is a beaten form, and cannot be fought.
		assertTrue(EncounterGroup.isDefeated(NpcID.RT_FIRE_QUEEN_INACTIVE));
		assertTrue(EncounterGroup.isDefeated(NpcID.RT_ICE_KING_INACTIVE));
		assertTrue(EncounterGroup.isUnattackable(NpcID.RT_FIRE_QUEEN_INACTIVE));
		assertTrue(EncounterGroup.isUnattackable(NpcID.RT_ICE_KING_INACTIVE));
		assertFalse(EncounterGroup.isDefeated(NpcID.RT_FIRE_QUEEN));
	}

	@Test
	public void theKrakenIsOneGroup()
	{
		for (int id : new int[]{NpcID.SLAYER_KRAKEN_BOSS, NpcID.SLAYER_KRAKEN_BOSS_WHIRLPOOL,
			NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, NpcID.SLAYER_KRAKEN_BOSS_TENTACLE_WHIRLPOOL})
		{
			assertEquals(String.valueOf(id), "Kraken", EncounterGroup.of(id));
		}
		// The cave kraken is an ordinary slayer monster.
		assertNull(EncounterGroup.of(NpcID.SLAYER_KRAKEN));
		assertNull(EncounterGroup.of(NpcID.SLAYER_KRAKEN_SUB));
		// The tentacles are adds, but scored: they are fought like any monster.
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE_WHIRLPOOL));
		assertFalse(EncounterGroup.isUnscored(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE));
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.SLAYER_KRAKEN_BOSS));
		assertNull(EncounterGroup.deathClose(NpcID.SLAYER_KRAKEN_BOSS));
	}

	@Test
	public void hesporiClosesOnItsOwnDeath()
	{
		assertEquals("Hespori", EncounterGroup.of(NpcID.HESPORI));
		final EncounterGroup.DeathClose rule = EncounterGroup.deathClose(NpcID.HESPORI);
		assertEquals(1, rule.deaths);
		assertTrue(rule.counts(NpcID.HESPORI, false));
		assertFalse(rule.counts(NpcID.HESPORI_HEALER_ACTIVE, false));
		assertFalse(rule.counts(NpcID.HESPORI_HEALER_INACTIVE, false));
	}

	/** The group names are the hiscores', so each room shows its boss's icon. */
	@Test
	public void eachRoomShowsItsBosssIcon()
	{
		assertEquals(HiscoreSkill.GROTESQUE_GUARDIANS, SidePanelBossIcons.skill("Grotesque Guardians", null));
		assertEquals(HiscoreSkill.THE_ROYAL_TITANS, SidePanelBossIcons.skill("The Royal Titans", null));
		assertEquals(HiscoreSkill.KRAKEN, SidePanelBossIcons.skill("Kraken", null));
		assertEquals(HiscoreSkill.HESPORI, SidePanelBossIcons.skill("Hespori", null));
	}

	// ---- the kills ----

	/** Dawn and Dusk, switched between across the phases: one room, closed by Dusk's drop. */
	@Test
	public void aGuardiansKillIsOneRoomClosedByDusksLoot()
	{
		final int dusk = 101;
		final int dawn = 102;
		final Replay r = new Replay()
			.attack(NpcID.GARGBOSS_DUSK_PHASE1_DEFENSIVE, dusk)
			.attack(NpcID.GARGBOSS_DAWN_PHASE1, dawn)
			.attack(NpcID.GARGBOSS_DUSK_PHASE2_ATTACKING, dusk)
			.attack(NpcID.GARGBOSS_DAWN_PHASE3, dawn)
			.attack(NpcID.GARGBOSS_DUSK_PHASE3_DEFENSIVE, dusk)
			.attack(NpcID.GARGBOSS_DAWN_PHASE3, dawn)
			// Dawn dies: the room goes on.
			.down(NpcID.GARGBOSS_DAWN_DEATH, dawn, false)
			.attack(NpcID.GARGBOSS_DUSK_PHASE4, dusk);
		assertEquals(1, r.rooms.size());
		assertFalse(r.room.isEnded());
		r.loot(NpcID.GARGBOSS_DUSK_DEATH, dusk);
		assertTrue(r.room.isEnded());
		assertTrue(r.open.isTargetDied());
		assertEquals("Grotesque Guardians", r.room.getName());
		assertEquals(7, r.room.getFights().size());
		// The next kill is a room of its own.
		r.attack(NpcID.GARGBOSS_DUSK_PHASE1_DEFENSIVE, 201);
		assertEquals(2, r.rooms.size());
	}

	/** Both titans and their elementals: one room, closed as the second titan falls; two kills, two rooms. */
	@Test
	public void eachRoyalTitansKillIsOneRoomClosedByTheSecondTitan()
	{
		final Replay r = new Replay();
		titansKill(r, 11, 12, true);
		assertEquals(1, r.rooms.size());
		assertTrue(r.room.isEnded());
		assertEquals("The Royal Titans", r.room.getName());
		titansKill(r, 21, 22, true);
		assertEquals(2, r.rooms.size());
		assertTrue(r.room.isEnded());
		assertEquals(r.rooms.get(0).getFights().size(), r.rooms.get(1).getFights().size());
	}

	/** If a titan despawns dead instead of lying beaten, the second death closes the room just the same. */
	@Test
	public void titansThatDespawnDeadCloseTheRoomToo()
	{
		final Replay r = new Replay();
		titansKill(r, 11, 12, false);
		assertTrue(r.room.isEnded());
		titansKill(r, 21, 22, false);
		assertEquals(2, r.rooms.size());
		assertTrue(r.room.isEnded());
	}

	private static void titansKill(Replay r, int branda, int eldric, boolean fall)
	{
		r.attack(NpcID.RT_FIRE_QUEEN, branda)
			.attack(NpcID.RT_SUMMON_ELEMENTAL_ICE, 900)
			.down(NpcID.RT_SUMMON_ELEMENTAL_ICE, 900, false)
			.attack(NpcID.RT_ICE_KING, eldric)
			.attack(NpcID.RT_FIRE_QUEEN, branda);
		r.down(fall ? NpcID.RT_FIRE_QUEEN_INACTIVE : NpcID.RT_FIRE_QUEEN, branda, fall);
		// One titan down is half the kill.
		assertFalse(r.room.isEnded());
		r.attack(NpcID.RT_ICE_KING, eldric);
		r.down(fall ? NpcID.RT_ICE_KING_INACTIVE : NpcID.RT_ICE_KING, eldric, fall);
		assertTrue(r.open.isTargetDied());
	}

	/** The same titan counted twice - fallen, then its body despawning - is still one titan. */
	@Test
	public void oneTitanTwiceIsOneTitan()
	{
		final Replay r = new Replay()
			.attack(NpcID.RT_FIRE_QUEEN, 11)
			.down(NpcID.RT_FIRE_QUEEN_INACTIVE, 11, true)
			.down(NpcID.RT_FIRE_QUEEN_INACTIVE, 11, false)
			.down(NpcID.RT_FIRE_QUEEN, 11, false);
		assertFalse(r.room.isEnded());
	}

	/** The tentacles, then the Kraken: one room, closed by the Kraken's loot. */
	@Test
	public void aKrakenKillIsOneRoomClosedByTheKrakensLoot()
	{
		final int kraken = 50;
		final Replay r = new Replay()
			.attack(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE_WHIRLPOOL, 51)
			.attack(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, 52)
			.attack(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, 53)
			.down(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, 53, false)
			.attack(NpcID.SLAYER_KRAKEN_BOSS_WHIRLPOOL, kraken)
			.attack(NpcID.SLAYER_KRAKEN_BOSS, kraken)
			.attack(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, 54);
		// A tentacle's drop, were there one, closes nothing.
		r.loot(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, 54);
		assertFalse(r.room.isEnded());
		r.attack(NpcID.SLAYER_KRAKEN_BOSS, kraken)
			.down(NpcID.SLAYER_KRAKEN_BOSS, kraken, false);
		// Its death alone doesn't close it: its loot does.
		assertFalse(r.room.isEnded());
		r.loot(NpcID.SLAYER_KRAKEN_BOSS, kraken);
		assertEquals(1, r.rooms.size());
		assertTrue(r.room.isEnded());
		assertEquals("Kraken", r.room.getName());
		r.attack(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE_WHIRLPOOL, 61);
		assertEquals(2, r.rooms.size());
	}

	/** Hespori and its flowers: the flowers' deaths close nothing, Hespori's closes the room; two kills, two rooms. */
	@Test
	public void eachHesporiKillClosesAtItsDeath()
	{
		final Replay r = new Replay();
		for (int kill = 0; kill < 2; kill++)
		{
			final int hespori = 70 + kill * 10;
			r.attack(NpcID.HESPORI, hespori)
				.attack(NpcID.HESPORI_HEALER_ACTIVE, hespori + 1)
				.down(NpcID.HESPORI_HEALER_ACTIVE, hespori + 1, false)
				.attack(NpcID.HESPORI_HEALER_ACTIVE, hespori + 2)
				.down(NpcID.HESPORI_HEALER_INACTIVE, hespori + 2, false)
				.attack(NpcID.HESPORI, hespori);
			assertFalse(r.room.isEnded());
			r.down(NpcID.HESPORI, hespori, false);
			assertTrue(r.room.isEnded());
			assertTrue(r.open.isTargetDied());
		}
		assertEquals(2, r.rooms.size());
		for (Encounter room : r.rooms)
		{
			assertEquals("Hespori", room.getName());
			assertEquals(4, room.getFights().size());
		}
	}

	/** A death in a room it doesn't belong to closes nothing, nor does a group that closes on loot. */
	@Test
	public void aDeathOutsideTheRoomClosesNothing()
	{
		final Replay r = new Replay().attack(NpcID.GARGBOSS_DUSK_PHASE4, 1);
		r.down(NpcID.HESPORI, 2, false);
		r.down(NpcID.GARGBOSS_DUSK_PHASE4, 1, false);
		assertFalse(r.room.isEnded());
	}

	/** Fights saved before T-061 kept the group they were saved with: no group, so a room each, as they were. */
	@Test
	public void roomsSavedBeforeStayAsSaved()
	{
		final Gson gson = new Gson();
		final Fight dawn = gson.fromJson("{\"targetName\":\"Dawn\",\"targetId\":7852,\"targetIndex\":2,\"attempts\":4,"
			+ "\"startMillis\":1000}", Fight.class);
		final Fight dusk = gson.fromJson("{\"targetName\":\"Dusk\",\"targetId\":7888,\"targetIndex\":1,\"attempts\":4,"
			+ "\"startMillis\":2000}", Fight.class);
		assertNull(dawn.getGroupName());
		assertEquals("Dawn", dawn.encounterName());
		assertEquals("Dusk", dusk.encounterName());
		final Encounter room = new Encounter(dawn.encounterName(), null, 1000L);
		room.add(dawn);
		assertTrue(PvmPerformancePlugin.opensRoom(room, dusk));
		// And one saved with its group still reads as it was.
		final Fight saved = gson.fromJson("{\"targetName\":\"Hespori\",\"targetId\":8583,\"groupName\":\"Hespori\","
			+ "\"attempts\":4}", Fight.class);
		assertEquals("Hespori", saved.encounterName());
		// A fight made now carries the new group.
		assertEquals("Grotesque Guardians", new Fight("Dusk", NpcID.GARGBOSS_DUSK_PHASE4, 1, 100, 0L, null, 0)
			.encounterName());
	}
}

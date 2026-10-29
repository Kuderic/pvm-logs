package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-070: the after-fight view waits for the room's kill. An add's death, one
 * titan down or Dawn falling ends that fight while the room runs on: the
 * overlay reads as running (no "(dead)", no Summary, no " your average") until
 * the room's kill. Ungrouped bosses are unchanged.
 */
public class RoomOverTest
{
	/** An average to hand the room view, so a test can see whether it was asked for. */
	private static final LedgerHistory.Average AVERAGE = new LedgerHistory.Average(
		new LedgerHistory.Spread(119, 115, 125), 10);

	private final List<Encounter> askedForAverage = new ArrayList<>();
	private final Function<Encounter, LedgerHistory.Average> average = room ->
	{
		askedForAverage.add(room);
		return AVERAGE;
	};

	private static Fight fight(String name, int npcId, int index, RaidType raid)
	{
		final Fight f = new Fight(name, npcId, index, 300, 0L, raid, raid == null ? 0 : 1);
		f.recordAttackMade(true, 1.0);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(20, 0L, true);
		f.recordExpected(30, 0.8, 18, 100);
		for (int tick = 0; tick < 5; tick++)
		{
			f.recordTickSpent(true);
		}
		return f;
	}

	private static Fight fight(String name, int npcId, int index)
	{
		return fight(name, npcId, index, null);
	}

	private LedgerRows.State state(Fight onShow, Encounter room)
	{
		return PvmPerformanceOverlay.roomState(onShow, room, false, average);
	}

	private List<LedgerRows.Row> rows(Fight onShow, Encounter room)
	{
		return LedgerRows.build(LedgerRows.of(room), state(onShow, room), new LedgerRows.Settings());
	}

	/** The room view reads as running: no "(dead)" or "(ended)", no Summary, no average asked for. */
	private void assertRunning(Fight onShow, Encounter room)
	{
		final LedgerRows.State s = state(onShow, room);
		assertFalse(s.ended);
		assertFalse(s.killed);
		assertNull(s.average);
		final List<LedgerRows.Row> rows = rows(onShow, room);
		assertEquals(room.getName(), rows.get(0).left);
		assertFalse(rows.stream().anyMatch(r -> r.left.equals("Summary") || r.left.equals(" your average")));
		assertTrue(askedForAverage.isEmpty());
	}

	/** The room view after its kill: "(dead)", the Summary and " your average". */
	private void assertKilled(Fight onShow, Encounter room)
	{
		final LedgerRows.State s = state(onShow, room);
		assertTrue(s.ended);
		assertTrue(s.killed);
		final List<LedgerRows.Row> rows = rows(onShow, room);
		assertEquals(room.getName() + " (dead)", rows.get(0).left);
		assertTrue(rows.stream().anyMatch(r -> r.left.equals("Summary")));
		assertTrue(rows.stream().anyMatch(r -> r.left.equals(" your average")));
	}

	// ---- the Abyssal Sire: a respiratory system dead ----

	@Test
	public void aSireRoomWithARespiratorySystemDeadRunsOnUntilTheSireDies()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 1);
		sire.end(false, 5L);
		final Fight lung = fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 2);
		lung.end(true, 10L);
		room.add(sire);
		room.add(lung);
		assertFalse(room.isKilled());
		assertFalse(room.isOver());
		assertRunning(lung, room);

		// Phase 2 on: the Sire's own fight, then its death.
		final Fight phase2 = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, 1);
		room.add(phase2);
		assertRunning(phase2, room);
		phase2.end(true, 20L);
		assertTrue(room.isKilled());
		assertTrue(room.isOver());
		assertKilled(phase2, room);
	}

	@Test
	public void aSireRoomClosedOnItsLootIsKilled()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, 1);
		room.add(sire);
		// A spawn's death, and its drop, are not the kill (T-055).
		final Fight spawn = fight("Spawn", NpcID.ABYSSALSIRE_SPAWN, 3);
		spawn.end(true, 10L);
		room.add(spawn);
		assertFalse(room.closesOnLootFrom(NpcID.ABYSSALSIRE_SPAWN));
		assertRunning(spawn, room);
		// The Sire's drop closes the room on the fight it ended (PvmPerformancePlugin.closeRoom).
		sire.end(false, 20L);
		assertTrue(room.closesOnLootFrom(NpcID.ABYSSALSIRE_SIRE_APOCALYPSE));
		spawn.closeRoom();
		room.end(20L);
		assertKilled(spawn, room);
	}

	// ---- the Royal Titans: one titan down ----

	@Test
	public void aTitansRoomWithOneTitanDownRunsOnUntilTheSecond()
	{
		final Encounter room = new Encounter("The Royal Titans", null, 0L);
		final Fight king = fight("Branda the Ice King", NpcID.RT_ICE_KING, 2);
		king.end(false, 5L);
		final Fight queen = fight("Eldric the Fire Queen", NpcID.RT_FIRE_QUEEN, 1);
		queen.end(true, 10L);
		room.add(king);
		room.add(queen);
		assertFalse(room.closesOnDeathOf(NpcID.RT_FIRE_QUEEN, 1, false));
		assertFalse(room.isKilled());
		assertFalse(room.isOver());
		assertRunning(queen, room);

		final Fight kingAgain = fight("Branda the Ice King", NpcID.RT_ICE_KING, 2);
		room.add(kingAgain);
		kingAgain.end(true, 20L);
		// The second titan down closes the room (T-061), on the fight it ended.
		assertTrue(room.closesOnDeathOf(NpcID.RT_ICE_KING, 2, false));
		kingAgain.closeRoom();
		room.end(20L);
		assertKilled(kingAgain, room);
	}

	@Test
	public void aTitanThePlayerAloneFoughtDyingIsNotTheKillInADuo()
	{
		// A partner on the other titan: every titan the player fought is dead, and the room still runs.
		final Encounter room = new Encounter("The Royal Titans", null, 0L);
		final Fight queen = fight("Eldric the Fire Queen", NpcID.RT_FIRE_QUEEN, 1);
		queen.end(true, 10L);
		room.add(queen);
		assertFalse(room.isKilled());
		assertRunning(queen, room);
	}

	// ---- the Grotesque Guardians: Dawn down ----

	@Test
	public void aGuardiansRoomWithDawnDownRunsOnUntilDusk()
	{
		final Encounter room = new Encounter("Grotesque Guardians", null, 0L);
		final Fight dusk = fight("Dusk", NpcID.GARGBOSS_DUSK_PHASE1_DEFENSIVE, 1);
		dusk.end(false, 5L);
		final Fight dawn = fight("Dawn", NpcID.GARGBOSS_DAWN_PHASE3, 2);
		dawn.end(true, 10L);
		room.add(dusk);
		room.add(dawn);
		assertFalse(room.isKilled());
		assertFalse(room.isOver());
		assertRunning(dawn, room);

		final Fight duskAgain = fight("Dusk", NpcID.GARGBOSS_DUSK_PHASE4, 1);
		room.add(duskAgain);
		duskAgain.end(true, 20L);
		// Outside a raid a separate group's kill is its loot.
		assertFalse(room.isKilled());
		assertTrue(room.closesOnLootFrom(NpcID.GARGBOSS_DUSK_DEATH));
		duskAgain.closeRoom();
		room.end(20L);
		assertKilled(duskAgain, room);
	}

	// ---- leaving a room ----

	@Test
	public void aGroupedRoomTheNextRoomEndedIsOverButNotKilled()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight lung = fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 2);
		lung.end(true, 10L);
		room.add(lung);
		room.end(30L);
		assertTrue(room.isOver());
		assertFalse(room.isKilled());
		final LedgerRows.State s = state(lung, room);
		assertTrue(s.ended);
		assertFalse(s.killed);
		assertNull(s.average);
		assertEquals("Abyssal Sire (ended)", rows(lung, room).get(0).left);
	}

	// ---- ungrouped bosses: unchanged ----

	@Test
	public void anUngroupedBossIsItsFightAsBefore()
	{
		final Encounter room = new Encounter("Vorkath", null, 0L);
		final Fight f = fight("Vorkath", NpcIds.GOBLIN, 1);
		room.add(f);
		assertFalse(room.isOver());
		assertRunning(f, room);
		f.end(true, 10L);
		assertTrue(room.isOver());
		assertTrue(room.isKilled());
		assertKilled(f, room);

		final Encounter left = new Encounter("Vorkath", null, 0L);
		final Fight walkedOff = fight("Vorkath", NpcIds.GOBLIN, 1);
		walkedOff.end(false, 10L);
		left.add(walkedOff);
		assertTrue(left.isOver());
		assertFalse(left.isKilled());
		assertEquals("Vorkath (ended)", rows(walkedOff, left).get(0).left);
	}

	@Test
	public void withNoRoomTheFightAloneDecides()
	{
		final Fight f = fight("Vorkath", NpcIds.GOBLIN, 1);
		assertFalse(PvmPerformanceOverlay.isOver(f, null));
		f.end(true, 10L);
		assertTrue(PvmPerformanceOverlay.isOver(f, null));
		assertFalse(PvmPerformanceOverlay.isOver(null, null));
	}

	// ---- raids: no loot, so the kill is the deaths ----

	@Test
	public void inARaidASeparateGroupIsKilledWhenEveryOneFoughtIsDead()
	{
		final RaidType cox = RaidType.CHAMBERS_OF_XERIC;
		final Encounter room = new Encounter("Vanguards", cox, 0L);
		final Fight melee = fight("Vanguard", NpcID.RAIDS_VANGUARD_MELEE, 1, cox);
		melee.end(true, 10L);
		final Fight ranged = fight("Vanguard", NpcID.RAIDS_VANGUARD_RANGED, 2, cox);
		ranged.end(false, 12L);
		room.add(melee);
		room.add(ranged);
		assertFalse(room.isKilled());
		assertFalse(room.isOver());
		final Fight rangedAgain = fight("Vanguard", NpcID.RAIDS_VANGUARD_RANGED, 2, cox);
		room.add(rangedAgain);
		assertFalse(room.isKilled());
		rangedAgain.end(true, 20L);
		assertTrue(room.isKilled());
		assertTrue(room.isOver());
		// The room view after a raid room's kill: "(dead)", and no average inside a raid.
		final LedgerRows.State s = state(rangedAgain, room);
		assertTrue(s.ended && s.killed);
		assertNull(s.average);
		assertTrue(askedForAverage.isEmpty());
	}

	@Test
	public void aRaidRoomRebuiltFromItsFightsKnowsItIsARaids()
	{
		// The CSV export rebuilds rooms from the saved fights, with no raid type on the room.
		final RaidType cox = RaidType.CHAMBERS_OF_XERIC;
		final Encounter rebuilt = new Encounter("Vanguards", null, 0L);
		for (int index = 1; index <= 3; index++)
		{
			final Fight vanguard = fight("Vanguard", NpcID.RAIDS_VANGUARD_MAGIC, index, cox);
			vanguard.end(true, 10L * index);
			rebuilt.add(vanguard);
		}
		assertTrue(rebuilt.isKilled());
	}

	@Test
	public void inARaidAOneBossRoomIsKilledByTheBossNotItsAdds()
	{
		final RaidType toa = RaidType.TOMBS_OF_AMASCUT;
		final Encounter room = new Encounter("Akkha", toa, 0L);
		final Fight akkha = fight("Akkha", NpcID.AKKHA_MELEE, 1, toa);
		akkha.end(false, 5L);
		final Fight shadow = fight("Akkha's Shadow", NpcID.AKKHA_SHADOW, 2, toa);
		shadow.end(true, 10L);
		room.add(akkha);
		room.add(shadow);
		assertFalse(room.isKilled());
		assertRunning(shadow, room);
		final Fight enraged = fight("Akkha", NpcID.AKKHA_ENRAGE, 1, toa);
		room.add(enraged);
		enraged.end(true, 20L);
		assertTrue(room.isKilled());
	}

	// ---- the history's averages count rooms, not adds ----

	@Test
	public void theAveragesCountRoomsNotAdds()
	{
		// Newest first, as the plugin keeps history: two Sire kills, each with a respiratory system dead before it.
		final List<Fight> history = new ArrayList<>();
		for (int kill = 0; kill < 2; kill++)
		{
			final Fight lung = fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 2);
			lung.end(true, 10L);
			final Fight sire = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, 1);
			sire.end(true, 20L);
			sire.closeRoom();
			history.add(0, lung);
			history.add(0, sire);
		}
		final List<Encounter> rooms = LedgerHistory.rooms(history, "Abyssal Sire");
		assertEquals(2, rooms.size());
		for (Encounter room : rooms)
		{
			assertEquals(2, room.getFights().size());
			assertTrue(room.isKilled());
			assertTrue(LedgerHistory.counts(room, Collections.emptySet()));
		}
		// A room whose only death was an add is no kill, and doesn't count.
		final Fight lung = fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 2);
		lung.end(true, 10L);
		final Encounter addOnly = new Encounter("Abyssal Sire", null, 0L);
		addOnly.add(lung);
		assertFalse(addOnly.isKilled());
		assertFalse(LedgerHistory.counts(addOnly, Collections.emptySet()));
		assertEquals(Arrays.asList(), askedForAverage);
	}
}

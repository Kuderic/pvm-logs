package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Test;

/**
 * T-028: the damage variance summed per fight, room, raid and trip, scored
 * attacks only, and unknown (never nought) where an attack carried none.
 */
public class DamageVarianceScopesTest
{
	private static Fight fight(String name, int npcId)
	{
		return new Fight(name, npcId, 1, 425, 0L, null, 0);
	}

	@Test
	public void aFightSumsItsAttacks()
	{
		final Fight f = fight("Abyssal Sire", NpcIds.GOBLIN);
		f.recordExpected(40, 0.7, 14, 150);
		f.recordExpected(40, 0.7, 14, 160);
		f.recordDamageDealt(20, 1L, true);
		assertEquals(310, f.damageVariance(), 1e-9);
		assertEquals(DamageMoments.phi((20 - 28) / Math.sqrt(310)), f.luckPercentile(), 1e-12);
		// An attack with no figure leaves the whole fight's spread unknown.
		f.recordExpected(40, 0.7, 14);
		assertEquals(-1, f.damageVariance(), 0);
		assertEquals(-1, f.luckPercentile(), 0);
		// So does a fight with nothing scored.
		assertEquals(-1, fight("Goblin", NpcIds.GOBLIN).damageVariance(), 0);
	}

	@Test
	public void aRoomSumsItsScoredFightsAndARaidItsRooms()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = fight("Abyssal Sire", NpcIds.GOBLIN);
		sire.recordExpected(40, 0.7, 14, 150);
		final Fight phase = fight("Abyssal Sire", NpcIds.GOBLIN);
		phase.recordExpected(50, 0.6, 15, 200);
		final Fight respiratory = fight("Respiratory system", net.runelite.api.gameval.NpcID.ABYSSALSIRE_LUNG);
		respiratory.recordExpected(40, 0.7, 14, 999);
		// Attacked nothing: stays out rather than making the room's spread unknown.
		final Fight looked = fight("Abyssal Sire", NpcIds.GOBLIN);
		room.add(sire);
		room.add(phase);
		room.add(respiratory);
		room.add(looked);
		assertEquals(350, room.sumExpectedDamageVariance(), 1e-9);

		final Encounter second = new Encounter("Abyssal Sire", null, 0L);
		final Fight other = fight("Abyssal Sire", NpcIds.GOBLIN);
		other.recordExpected(40, 0.7, 14, 50);
		second.add(other);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		raid.add(second);
		assertEquals(400, raid.sumExpectedDamageVariance(), 1e-9);

		// One scored fight with no figure, and the room's and raid's spreads are unknown.
		other.recordExpected(40, 0.7, 14);
		assertEquals(-1, second.sumExpectedDamageVariance(), 0);
		assertEquals(-1, raid.sumExpectedDamageVariance(), 0);
	}

	@Test
	public void theTripSumsLikeAFight()
	{
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(-1, trip.damageVariance(), 0);
		trip.recordExpected(0.7, 14, 150);
		trip.recordExpected(0.7, 14, 160);
		assertEquals(310, trip.damageVariance(), 1e-9);
		assertEquals(310, trip.getSumExpectedDamageVariance(), 1e-9);
		trip.reset(1L);
		assertEquals(-1, trip.damageVariance(), 0);
		assertEquals(0, trip.getSumExpectedDamageVariance(), 0);
	}

	@Test
	public void aFightSavedBeforeT028LoadsWithItsSpreadUnknown()
	{
		// history.json as an older build wrote it: none of T-028's fields.
		final String saved = "{\"targetName\":\"Vardorvis\",\"targetId\":12223,\"maxHp\":700,\"damageDealt\":700,"
			+ "\"attempts\":40,\"hits\":30,\"sumExpectedAverageHit\":680.5,\"expectedTargetSamples\":40,\"ended\":true}";
		final Fight loaded = new Gson().fromJson(saved, Fight.class);
		assertEquals(700, loaded.getDamageDealt());
		assertEquals(680.5, loaded.getSumExpectedAverageHit(), 0);
		assertEquals(-1, loaded.damageVariance(), 0);
		assertEquals(-1, loaded.luckPercentile(), 0);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(loaded);
		assertEquals(-1, room.sumExpectedDamageVariance(), 0);
	}

	@Test
	public void theLuckLine()
	{
		final Fight f = fight("Abyssal Sire", NpcIds.GOBLIN);
		f.recordExpected(40, 0.7, 50, 100);
		f.recordDamageDealt(40, 1L, true);
		final String line = PvmPerformancePlugin.luckLine(f);
		assertTrue(line, line.startsWith("LUCK Abyssal Sire (npc "));
		assertTrue(line, line.endsWith(": A 40, E 50.0, sd 10.0, z -1.00, percentile 15.9%"));
		f.recordExpected(40, 0.7, 50);
		assertTrue(PvmPerformancePlugin.luckLine(f), PvmPerformancePlugin.luckLine(f).endsWith("sd unknown, z unknown, percentile unknown"));
	}
}

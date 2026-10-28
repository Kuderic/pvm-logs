package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * T-041: the saved figures. Nothing is readable while a fight runs (Jagex's
 * guidelines: nothing live about boss attacks); a room, a raid and the trip count
 * a fight's figures only once it has ended; old history loads with none.
 */
public class AvoidableFiguresTest
{
	private static final Gson GSON = new Gson();

	private static Map<String, Integer> byAttack(int melee, int axe, int bleed)
	{
		final Map<String, Integer> m = new LinkedHashMap<>();
		m.put("melee", melee);
		m.put("axe", axe);
		m.put("bleed", bleed);
		return m;
	}

	/** Eric's 17:03 kill 4, as the harness reads it: 70 avoidable, all 9 eating ticks theirs, line 29 from this kill. */
	private static Fight kill(boolean end)
	{
		final Fight f = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		f.recordAttackMade(true);
		f.recordAvoidable(1, 70.0, byAttack(102, 40, 30), 9.0, 29, AvoidableEating.LINE_THIS_KILL);
		if (end)
		{
			f.end(true, 111_000L);
		}
		return f;
	}

	@Test
	public void nothingIsReadableWhileTheFightRuns()
	{
		final Fight running = kill(false);
		assertFalse(running.hasAvoidable());
		assertEquals(0, running.getAttackRulesVersion());
		assertEquals(-1, running.getDamageTakenAvoidable());
		assertEquals(-1, running.getAvoidableEatingTicks(), 0);
		assertEquals(-1, running.getEatLineHp());
		assertNull(running.getEatLineSource());
		assertTrue(running.getDamageTakenByAttack().isEmpty());
		running.end(true, 111_000L);
		assertTrue(running.hasAvoidable());
		assertEquals(1, running.getAttackRulesVersion());
		assertEquals(70, running.getDamageTakenAvoidable());
		assertEquals(9.0, running.getAvoidableEatingTicks(), 0);
		assertEquals(29, running.getEatLineHp());
		assertEquals(AvoidableEating.LINE_THIS_KILL, running.getEatLineSource());
		assertEquals(Integer.valueOf(40), running.getDamageTakenByAttack().get("axe"));
	}

	/** A fight at a boss with no attack rules has none, ended or not. */
	@Test
	public void noRulesNoFigures()
	{
		final Fight f = new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
		f.recordAvoidable(0, 10, byAttack(1, 2, 3), 2, 30, AvoidableEating.LINE_DEFAULT);
		f.end(true, 1000L);
		assertFalse(f.hasAvoidable());
		assertEquals(-1, f.getDamageTakenAvoidable());
		assertEquals(0, f.getAttackRulesVersion());
	}

	@Test
	public void theFiguresAreRoundedAsSaved()
	{
		final Fight f = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		f.recordAvoidable(1, 133.5, byAttack(1, 2, 3), 18.04, 29, AvoidableEating.LINE_THIS_KILL);
		f.end(true, 1000L);
		assertEquals(134, f.getDamageTakenAvoidable());
		assertEquals(18.0, f.getAvoidableEatingTicks(), 0);
	}

	/** A room counts its ended fights only; with none ended, -1. */
	@Test
	public void aRoomCountsEndedFightsOnly()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(kill(false));
		assertEquals(-1, room.getDamageTakenAvoidable());
		assertEquals(-1, room.getAvoidableEatingTicks(), 0);
		assertTrue(room.getDamageTakenByAttack().isEmpty());
		assertEquals(0, room.getAttackRulesVersion());
		room.add(kill(true));
		room.add(new Fight("Goblin", NpcIds.GOBLIN, 2, 5, 0L, null, 0));
		assertEquals(70, room.getDamageTakenAvoidable());
		assertEquals(9.0, room.getAvoidableEatingTicks(), 0);
		assertEquals(1, room.getAttackRulesVersion());
		room.add(kill(true));
		assertEquals(140, room.getDamageTakenAvoidable());
		assertEquals(18.0, room.getAvoidableEatingTicks(), 0);
		assertEquals(Integer.valueOf(80), room.getDamageTakenByAttack().get("axe"));
	}

	@Test
	public void aRaidSumsItsRooms()
	{
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		final Encounter none = new Encounter("Kephri", RaidType.TOMBS_OF_AMASCUT, 0L);
		none.add(new Fight("Kephri", NpcIds.GOBLIN, 1, 80, 0L, RaidType.TOMBS_OF_AMASCUT, 1));
		raid.add(none);
		assertEquals(-1, raid.getDamageTakenAvoidable());
		assertEquals(-1, raid.getAvoidableEatingTicks(), 0);
		final Encounter ruled = new Encounter("Vardorvis", null, 0L);
		ruled.add(kill(true));
		raid.add(ruled);
		raid.add(ruled);
		assertEquals(140, raid.getDamageTakenAvoidable());
		assertEquals(18.0, raid.getAvoidableEatingTicks(), 0);
		assertEquals(Integer.valueOf(204), raid.getDamageTakenByAttack().get("melee"));
		assertEquals(1, raid.getAttackRulesVersion());
	}

	/** The trip takes a fight's figures as it ends, scored fights only, and forgets them on a reset. */
	@Test
	public void theTripAddsEachFightAsItEnds()
	{
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(-1, trip.getDamageTakenAvoidable());
		assertEquals(-1, trip.getAvoidableEatingTicks(), 0);
		// Not ended: nothing to add, even if the plugin were to try.
		trip.recordFightEnded(false, kill(false), 0L);
		assertEquals(-1, trip.getDamageTakenAvoidable());
		trip.recordFightEnded(true, kill(true), 0L);
		trip.recordFightEnded(true, kill(true), 0L);
		assertEquals(140, trip.getDamageTakenAvoidable());
		assertEquals(18.0, trip.getAvoidableEatingTicks(), 0);
		assertEquals(Integer.valueOf(60), trip.getDamageTakenByAttack().get("bleed"));
		assertEquals(2, trip.getRuledFights());
		assertEquals(1, trip.getAttackRulesVersion());
		trip.reset(0L);
		assertEquals(-1, trip.getDamageTakenAvoidable());
		assertTrue(trip.getDamageTakenByAttack().isEmpty());
	}

	/** The Sire's respiratory systems are unscored: their figures, if they ever have any, stay out of the trip. */
	@Test
	public void unscoredFightsStayOutOfTheTrip()
	{
		final SessionTotals trip = new SessionTotals(0L);
		final Fight system = new Fight("Respiratory system", 5914, 1, 50, 0L, null, 0);
		system.recordAvoidable(1, 5, byAttack(5, 0, 0), 1, 30, AvoidableEating.LINE_DEFAULT);
		system.end(true, 1000L);
		assertFalse(system.isScored());
		trip.recordFightEnded(true, system, 0L);
		assertEquals(-1, trip.getDamageTakenAvoidable());
	}

	/** Saved and read back, the figures stay; a fight saved before T-041 has none and shows nothing. */
	@Test
	public void historyRoundTripsAndOldKillsLoad()
	{
		final Fight[] back = GSON.fromJson(GSON.toJson(new Fight[]{kill(true)}), Fight[].class);
		assertEquals(70, back[0].getDamageTakenAvoidable());
		assertEquals(9.0, back[0].getAvoidableEatingTicks(), 0);
		assertEquals(29, back[0].getEatLineHp());
		assertEquals(AvoidableEating.LINE_THIS_KILL, back[0].getEatLineSource());
		assertEquals(Integer.valueOf(102), back[0].getDamageTakenByAttack().get("melee"));
		assertEquals(1, back[0].getAttackRulesVersion());

		final String old = "[{\"targetName\":\"Vardorvis\",\"targetId\":12223,\"targetIndex\":1,\"maxHp\":700,"
			+ "\"startMillis\":0,\"damageDealt\":650,\"damageTaken\":172,\"ended\":true,\"targetDied\":true,"
			+ "\"endMillis\":111000,\"attempts\":40}]";
		final Fight[] loaded = GSON.fromJson(old, Fight[].class);
		assertEquals(172, loaded[0].getDamageTaken());
		assertFalse(loaded[0].hasAvoidable());
		assertEquals(0, loaded[0].getAttackRulesVersion());
		assertEquals(-1, loaded[0].getDamageTakenAvoidable());
		assertEquals(-1, loaded[0].getAvoidableEatingTicks(), 0);
		assertTrue(loaded[0].getDamageTakenByAttack().isEmpty());
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(loaded[0]);
		assertEquals(-1, room.getDamageTakenAvoidable());
	}

	/** The end-of-fight debug line, with everything the replay worked from. */
	@Test
	public void theAvoidableLine() throws Exception
	{
		final AttackClassifier names = new AttackClassifier(AvoidableReplayTest.vardorvis());
		names.onHit(100, 14, 16, "melee", java.util.Collections.emptyList());
		names.onHit(102, 3, 67, "melee", java.util.Collections.emptyList());
		names.settleAll();
		final AvoidableEating.Result result = new AvoidableEating.Result(3, 4, 0, 29, AvoidableEating.LINE_THIS_KILL, 20,
			9.0);
		final Fight f = kill(true);
		assertEquals("AVOIDABLE Vardorvis (npc 12223), rules v1: taken 17, avoidable 17.0 (axe 14, bleed 3); eats 3, "
				+ "replay with 4, without 0; line 29 hp (this kill) of 99, food 20; eating 0 ticks, avoidable 9.0",
			PvmPerformancePlugin.avoidableLine(f, names, result, 99));
	}

	/** The two columns at the end; blank with no rules. */
	@Test
	public void theCsvsLastTwoColumns()
	{
		final String header = PvmPerformancePlugin.CSV_HEADER.trim();
		assertTrue(header.endsWith(",reflectDamage,damageTakenAvoidable,avoidableEatingTicks"));
		final String[] ruled = PvmPerformancePlugin.csvRow(kill(true)).trim().split(",", -1);
		assertEquals(Arrays.asList("70", "9.0"), Arrays.asList(ruled[ruled.length - 2], ruled[ruled.length - 1]));
		final Fight goblin = new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
		goblin.end(true, 1000L);
		final String[] none = PvmPerformancePlugin.csvRow(goblin).trim().split(",", -1);
		assertEquals(Arrays.asList("", ""), Arrays.asList(none[none.length - 2], none[none.length - 1]));
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(kill(true));
		final String[] roomRow = PvmPerformancePlugin.csvRoomRow(null, 0, room).trim().split(",", -1);
		assertEquals("70", roomRow[roomRow.length - 2]);
		assertEquals("9.0", roomRow[roomRow.length - 1]);
	}
}

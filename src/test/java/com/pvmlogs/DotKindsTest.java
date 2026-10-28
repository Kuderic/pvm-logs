package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.HitsplatID;
import org.junit.Test;

/**
 * T-062: burn, poison, venom and bleed recorded by kind, dealt and taken; summed
 * per scope; saved; an old kill's total as one DoT line; the overlay's rows.
 */
public class DotKindsTest
{
	private static final Gson GSON = new Gson();

	private static Map<String, Integer> map(Object... kindThenAmount)
	{
		final Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < kindThenAmount.length; i += 2)
		{
			m.put((String) kindThenAmount[i], (Integer) kindThenAmount[i + 1]);
		}
		return m;
	}

	/** A fight on Zulrah with venom and burn dealt, poison and venom taken. */
	private static Fight zulrah()
	{
		final Fight f = new Fight("Zulrah", 2042, 1, 500, 0L, null, 0);
		f.recordAttackMade(false, 1.0);
		f.recordDotDamage(6, HitsplatID.VENOM);
		f.recordDotDamage(8, HitsplatID.VENOM);
		f.recordDotDamage(10, HitsplatID.BURN);
		f.recordDotTaken(5, HitsplatID.POISON, 1L);
		f.recordDotTaken(6, HitsplatID.VENOM, 2L);
		f.recordDotTaken(4, HitsplatID.POISON, 3L);
		return f;
	}

	// ---- recording ----

	@Test
	public void eachKindIsRecordedDealtAndTakenBesideTheTotals()
	{
		final Fight f = zulrah();
		assertEquals(map("venom", 14, "burn", 10), f.getDotDamageByKind());
		assertEquals(24, f.getDotDamage());
		assertEquals(map("poison", 9, "venom", 6), f.getDotTakenByKind());
		assertEquals(15, f.getDotTaken());
		final Fight bleed = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		bleed.recordDotTaken(7, HitsplatID.BLEED, 1L);
		assertEquals(map("bleed", 7), bleed.getDotTakenByKind());
	}

	@Test
	public void aSplatOfNothingAddsNoKind()
	{
		final Fight f = new Fight("Zulrah", 2042, 1, 500, 0L, null, 0);
		f.recordDotDamage(0, HitsplatID.VENOM);
		f.recordDotTaken(0, HitsplatID.POISON, 1L);
		assertTrue(f.getDotDamageByKind().isEmpty());
		assertTrue(f.getDotTakenByKind().isEmpty());
	}

	@Test
	public void theTakenSplatPathRecordsTheKindOnTheFightAndTheTrip()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		final SessionTotals trip = new SessionTotals(0L);
		assertTrue(f.isScored());
		assertEquals(HitsplatKind.DAMAGE_OVER_TIME, taken.onSplat(HitsplatID.BLEED, 9, 10, true, f, trip, 1L));
		taken.onSplat(HitsplatID.POISON, 4, 11, true, f, trip, 2L);
		assertEquals(map("bleed", 9, "poison", 4), f.getDotTakenByKind());
		assertEquals(map("bleed", 9, "poison", 4), trip.getDotTakenByKind());
		assertEquals(13, trip.getDotTaken());
	}

	@Test
	public void theTripRecordsByKind()
	{
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordDotDamage(10, HitsplatID.BURN);
		trip.recordDotDamage(20, HitsplatID.BURN);
		trip.recordDotTaken(5, HitsplatID.VENOM);
		assertEquals(map("burn", 30), trip.getDotDamageByKind());
		assertEquals(30, trip.getDotDamage());
		assertEquals(map("venom", 5), trip.getDotTakenByKind());
		trip.reset(1L);
		assertTrue(trip.getDotDamageByKind().isEmpty());
		assertTrue(trip.getDotTakenByKind().isEmpty());
	}

	// ---- sums ----

	@Test
	public void roomAndRaidSumTheKinds()
	{
		final Encounter room = new Encounter("Zulrah", null, 0L);
		room.add(zulrah());
		room.add(zulrah());
		assertEquals(map("venom", 28, "burn", 20), room.getDotDamageByKind());
		assertEquals(map("poison", 18, "venom", 12), room.getDotTakenByKind());
		final Encounter other = new Encounter("Zebak", RaidType.TOMBS_OF_AMASCUT, 0L);
		final Fight zebak = new Fight("Zebak", NpcIds.GOBLIN, 2, 700, 0L, RaidType.TOMBS_OF_AMASCUT, 1);
		zebak.recordDotDamage(12, HitsplatID.POISON);
		zebak.recordDotTaken(3, HitsplatID.POISON, 1L);
		other.add(zebak);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		raid.add(other);
		assertEquals(map("venom", 28, "burn", 20, "poison", 12), raid.getDotDamageByKind());
		assertEquals(map("poison", 21, "venom", 12), raid.getDotTakenByKind());
		assertEquals(60, raid.getDotDamage());
	}

	@Test
	public void theScoredSumsLeaveOutUnscoredFights()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = new Fight("Abyssal Sire", 5886, 1, 400, 0L, null, 0);
		sire.recordDotDamage(10, HitsplatID.BURN);
		final Fight system = new Fight("Respiratory system", 5914, 2, 50, 0L, null, 0);
		system.recordDotDamage(6, HitsplatID.BURN);
		system.recordDotDamage(4, HitsplatID.POISON);
		assertFalse(system.isScored());
		room.add(sire);
		room.add(system);
		assertEquals(map("burn", 16, "poison", 4), room.getDotDamageByKind());
		assertEquals(map("burn", 10), room.getScoredDotDamageByKind());
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(map("burn", 10), raid.getScoredDotDamageByKind());
	}

	@Test
	public void everyScopeHandsTheRowsItsKinds()
	{
		final Encounter room = new Encounter("Zulrah", null, 0L);
		room.add(zulrah());
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordDotDamage(6, HitsplatID.VENOM);
		trip.recordDotDamage(8, HitsplatID.VENOM);
		trip.recordDotDamage(10, HitsplatID.BURN);
		trip.recordDotTaken(9, HitsplatID.POISON);
		trip.recordDotTaken(6, HitsplatID.VENOM);
		for (LedgerRows.Scope scope : Arrays.asList(LedgerRows.of(room), LedgerRows.of(raid), LedgerRows.of(trip)))
		{
			assertEquals(map("venom", 14, "burn", 10), scope.dotDamageByKind());
			assertEquals(map("poison", 9, "venom", 6), scope.dotTakenByKind());
		}
	}

	// ---- saving, and old kills ----

	@Test
	public void historyKeepsTheKinds()
	{
		final Fight f = zulrah();
		f.end(true, 1000L);
		final Fight[] back = GSON.fromJson(GSON.toJson(new Fight[]{f}), Fight[].class);
		assertEquals(map("venom", 14, "burn", 10), back[0].getDotDamageByKind());
		assertEquals(map("poison", 9, "venom", 6), back[0].getDotTakenByKind());
		assertEquals(24, back[0].getDotDamage());
	}

	/** A kill saved before T-062: its totals, and no kinds. */
	private static Fight oldKill()
	{
		final String old = "[{\"targetName\":\"Vardorvis\",\"targetId\":12223,\"targetIndex\":1,\"maxHp\":700,"
			+ "\"startMillis\":0,\"damageDealt\":650,\"dotDamage\":40,\"dotTaken\":27,\"ended\":true,\"targetDied\":true,"
			+ "\"endMillis\":111000,\"attempts\":40}]";
		return GSON.fromJson(old, Fight[].class)[0];
	}

	@Test
	public void anOldKillReadsAsItsTotalsWithNoKinds()
	{
		final Fight f = oldKill();
		assertEquals(40, f.getDotDamage());
		assertTrue(f.getDotDamageByKind().isEmpty());
		assertTrue(f.getDotTakenByKind().isEmpty());
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(f);
		final List<LedgerRows.Row> rows = after(LedgerRows.of(room));
		assertTrue(rows.contains(d(" DoT dmg", "40")));
		assertTrue(rows.contains(d(" DoT taken", "27")));
		assertNone(rows, " burn dmg", " poison dmg", " venom dmg", " bleed dmg", " burn/poison dmg", " burn/poison taken");
	}

	@Test
	public void aRoomMixingOldAndNewKillsShowsTheKindsThenTheRest()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(oldKill());
		final Fight f = new Fight("Vardorvis", 12223, 2, 700, 0L, null, 0);
		f.recordAttackMade(false, 1.0);
		f.recordDotDamage(20, HitsplatID.BURN);
		f.recordDotTaken(9, HitsplatID.BLEED, 1L);
		room.add(f);
		final List<String> dot = dotRows(after(LedgerRows.of(room)));
		assertEquals(Arrays.asList(" burn dmg 20", " DoT dmg 40", " bleed taken 9", " DoT taken 27"), dot);
	}

	@Test
	public void aTripMixingOldAndNewKillsShowsTheKindsThenTheRest()
	{
		// The trip lives in memory, so its "old" part is DoT booked with no kind.
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordDotDamage(40);
		trip.recordDotDamage(15, HitsplatID.VENOM);
		trip.recordDotDamage(5, HitsplatID.BURN);
		trip.recordDotTaken(27);
		trip.recordDotTaken(4, HitsplatID.POISON);
		final List<LedgerRows.Row> rows = LedgerRows.build(LedgerRows.of(trip), LedgerRows.State.trip(2, false, false),
			new LedgerRows.Settings());
		assertEquals(Arrays.asList(" burn dmg 5", " venom dmg 15", " DoT dmg 40", " poison taken 4", " DoT taken 27"),
			dotRows(rows));
	}

	// ---- the rows ----

	@Test
	public void theRowsGoInTheBriefsOrderAndAKindWithNothingHasNone()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.dotDamage = 70;
		s.dotDamageByKind.put("bleed", 10);
		s.dotDamageByKind.put("venom", 20);
		s.dotDamageByKind.put("burn", 40);
		s.dotDamageByKind.put("poison", 0);
		s.dotTaken = 31;
		s.dotTakenByKind.put("venom", 6);
		s.dotTakenByKind.put("poison", 25);
		final List<String> dot = dotRows(after(s));
		assertEquals(Arrays.asList(" burn dmg 40", " venom dmg 20", " bleed dmg 10", " poison taken 25",
			" venom taken 6"), dot);
	}

	@Test
	public void theRowsSitWhereBurnPoisonDidInTheSummary()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.dotDamage = 30;
		s.dotDamageByKind.put("venom", 30);
		s.dotTaken = 25;
		s.dotTakenByKind.put("poison", 25);
		s.biggestHitTaken = 41;
		final List<LedgerRows.Row> rows = after(s);
		final List<String> left = new ArrayList<>();
		for (LedgerRows.Row row : rows)
		{
			left.add(row.left);
		}
		// Dealt after thrall and before the boss's heals; taken after the heals and before the biggest hit.
		assertEquals(left.indexOf(" thrall dmg") + 1, left.indexOf(" venom dmg"));
		assertEquals(left.indexOf(" venom dmg") + 1, left.indexOf(" boss healed"));
		assertEquals(left.indexOf(" boss healed") + 1, left.indexOf(" poison taken"));
		assertEquals(left.indexOf(" poison taken") + 1, left.indexOf(" biggest hit taken"));
	}

	@Test
	public void noDotRowsWhileTheFightRuns()
	{
		final Encounter room = new Encounter("Zulrah", null, 0L);
		room.add(zulrah());
		final List<LedgerRows.Row> rows = LedgerRows.build(LedgerRows.of(room),
			LedgerRows.State.fight("Zulrah", false, false, false), new LedgerRows.Settings());
		assertTrue(dotRows(rows).isEmpty());
		assertNone(rows, " DoT dmg", " DoT taken");
	}

	@Test
	public void aKindWithNoNameInTheOrderFollowsByName()
	{
		final Map<String, Integer> lines = DotKinds.split(map("zz", 2, "venom", 3, "corrosion", 4), 12,
			DotKinds.REMAINDER);
		assertEquals(map("venom", 3, "corrosion", 4, "zz", 2, "DoT", 3), lines);
		assertTrue(DotKinds.split(null, 0, DotKinds.REMAINDER).isEmpty());
		// Kinds over the total (not expected) leave no negative remainder.
		assertEquals(map("burn", 5), DotKinds.split(map("burn", 5), 3, DotKinds.REMAINDER));
	}

	// ---- the debug line ----

	@Test
	public void theDebugLineNamesTheSplit()
	{
		assertEquals("DOT Zulrah: dealt burn 10, venom 14; taken poison 9, venom 6", DotKinds.debugLine(zulrah()));
		assertEquals("DOT Vardorvis: dealt other 40; taken other 27", DotKinds.debugLine(oldKill()));
		final Fight onlyTaken = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		onlyTaken.recordDotTaken(7, HitsplatID.BLEED, 1L);
		assertEquals("DOT Vardorvis: dealt none; taken bleed 7", DotKinds.debugLine(onlyTaken));
		assertNull(DotKinds.debugLine(new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0)));
	}

	// ---- helpers ----

	private static List<LedgerRows.Row> after(LedgerRows.Scope scope)
	{
		return LedgerRows.build(scope, LedgerRows.State.fight("Zulrah", true, true, false), new LedgerRows.Settings());
	}

	private static LedgerRows.Row d(String left, String right)
	{
		return new LedgerRows.Row(left, right, LedgerRows.FontRole.DETAIL, LedgerRows.FontRole.DETAIL,
			LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.NEUTRAL, null, null);
	}

	/** The DoT rows as "label value", in order. */
	private static List<String> dotRows(List<LedgerRows.Row> rows)
	{
		final List<String> out = new ArrayList<>();
		for (LedgerRows.Row row : rows)
		{
			if (row.left.endsWith(" dmg") && !row.left.equals(" thrall dmg") && !row.left.equals(" recoil/veng dmg")
				|| row.left.endsWith(" taken") && !row.left.equals(" damage taken")
				&& !row.left.equals(" biggest hit taken"))
			{
				out.add(row.left + " " + row.right);
			}
		}
		return out;
	}

	private static void assertNone(List<LedgerRows.Row> rows, String... labels)
	{
		for (String label : labels)
		{
			assertFalse(label, rows.stream().anyMatch(row -> row.left.equals(label)));
		}
	}
}

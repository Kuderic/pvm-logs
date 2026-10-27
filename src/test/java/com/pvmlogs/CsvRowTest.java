package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import java.util.Collections;
import org.junit.Test;

// Every row must have as many columns as the header names, at every level.
public class CsvRowTest
{
	private static final int COLUMNS = PvmPerformancePlugin.CSV_HEADER.trim().split(",", -1).length;

	private static Fight fight()
	{
		final Fight f = new Fight("Some \"quoted\" npc", NpcIds.GOBLIN, 1, 100, 0L, RaidType.TOMBS_OF_AMASCUT, 2);
		f.recordAttackMade(true);
		f.recordAttackResolved(true, true, 10, 12);
		f.recordDamageDealt(7, 600L, true);
		f.recordExpected(20, 0.75, 6.5);
		f.recordTickLost(false);
		f.end(true, 1200L);
		return f;
	}

	private static int columns(String row)
	{
		// Quoted fields hold no commas here, so a plain split is enough.
		return row.trim().split(",", -1).length;
	}

	@Test
	public void theHeaderNamesEveryColumnOnce()
	{
		// attempts and targets are both there now: one throw of chinchompas is one attempt and several targets.
		assertEquals(36, COLUMNS);
	}

	@Test
	public void aPhaseRowMatchesTheHeader()
	{
		assertEquals(COLUMNS, columns("fight,x,1,\"room\"," + PvmPerformancePlugin.csvRow(fight())));
	}

	@Test
	public void aRoomRowMatchesTheHeader()
	{
		final Encounter room = new Encounter("Kephri", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(fight());
		assertEquals(COLUMNS, columns(PvmPerformancePlugin.csvRoomRow("Tombs of Amascut", 2, room)));
	}

	@Test
	public void aRaidRowMatchesTheHeader()
	{
		final Encounter room = new Encounter("Kephri", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(fight());
		assertEquals(COLUMNS,
			columns(PvmPerformancePlugin.csvRaidRow("Tombs of Amascut", 2, Collections.singletonList(room))));
	}

	@Test
	public void aRoomWithNothingToReportStillFillsItsColumns()
	{
		// An empty room divides by zero in several places if it is not careful.
		final Encounter empty = new Encounter("Vanguards", null, 0L);
		assertEquals(COLUMNS, columns(PvmPerformancePlugin.csvRoomRow(null, 0, empty)));
	}

	@Test
	public void quotesInAnNpcNameDoNotSplitTheRow()
	{
		assertEquals(COLUMNS, columns("fight,,,\"\"," + PvmPerformancePlugin.csvRow(fight())));
	}

	/** A stats warning is a sentence with commas in it, and still one column. */
	@Test
	public void aWarningWithCommasIsOneColumn()
	{
		final Fight f = fight();
		f.addStatsWarning("landed 26 of 42 attacks against 40.1 expected (z = -13.0): this boss's stats look wrong, "
			+ "so its \"expected\" damage and luck are not reliable");
		f.recordDotDamage(12);
		f.recordHeal(30);
		final String row = PvmPerformancePlugin.csvRow(f);
		assertEquals(COLUMNS, columns("fight,x,1,\"room\"," + row));
		final String[] cells = row.trim().split(",", -1);
		assertEquals("12", cells[cells.length - 3]);
		assertEquals("30", cells[cells.length - 2]);
		org.junit.Assert.assertTrue(cells[cells.length - 1].startsWith("\"landed 26 of 42"));
	}

	@Test
	public void noWarningIsAnEmptyColumn()
	{
		assertEquals("", PvmPerformancePlugin.csvText(null));
		assertEquals("", PvmPerformancePlugin.csvText(""));
	}

	/** T-010: the boost share and both costs, as percentages, in their named columns. */
	@Test
	public void theBoostShareAndCostsHaveTheirColumns()
	{
		final java.util.List<String> header = java.util.Arrays.asList(PvmPerformancePlugin.CSV_HEADER.trim().split(",", -1));
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordAttackMade(false, 0.84);
		f.recordAttackResolved(true, false, 9.5, 10.0, 0.3, 0.12);
		f.end(true, 1200L);
		final String[] cells = ("fight,,,\"\"," + PvmPerformancePlugin.csvRow(f)).trim().split(",", -1);
		assertEquals("84.0", cells[header.indexOf("boostPct")]);
		assertEquals("3.00", cells[header.indexOf("boostCostPct")]);
		assertEquals("1.20", cells[header.indexOf("switchCostPct")]);
		// The old count stays, for fights saved before the share.
		assertEquals("0", cells[header.indexOf("attacksPotted")]);
	}

	@Test
	public void aFightFromBeforeTheShareLeavesItBlank()
	{
		final java.util.List<String> header = java.util.Arrays.asList(PvmPerformancePlugin.CSV_HEADER.trim().split(",", -1));
		final String[] cells = ("fight,x,1,\"room\"," + PvmPerformancePlugin.csvRow(fight())).trim().split(",", -1);
		assertEquals("", cells[header.indexOf("boostPct")]);
		assertEquals("1", cells[header.indexOf("attacksPotted")]);
	}
}

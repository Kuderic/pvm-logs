package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Test;

/** T-187: the per-boss record (records.json) that outlives history's budget, and the grade it holds. */
public class BossRecordsTest
{
	private static final Gson GSON = new Gson();
	private static final String BOSS = "Vardorvis";
	private static final LedgerHistory.Like LIKE = new LedgerHistory.Like(BOSS, 700, true);
	private static final long NOW = 1_762_700_000_000L;

	private Path tmp;
	private Filepath dir;

	@Before
	public void setUp() throws IOException
	{
		tmp = Files.createTempDirectory("records");
		// A test-only way in: the plugin's Filepath comes from RuneLite.
		dir = Filepath.Unchecked.getRooted(tmp);
	}

	/**
	 * A counted kill started at {@code start}: {@code ticks} of fighting, {@code lost} of them idle (so its execution is
	 * about 1 - lost / ticks), its damage {@code luck} off what was expected.
	 */
	private static Fight kill(long start, int ticks, int lost, int luck)
	{
		final Fight f = new Fight(BOSS, NpcIds.GOBLIN, 1, 700, start, null, 0);
		final int attacks = Math.max(1, (ticks - lost) / 4);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, 1.0);
			f.recordExpected(40, 0.5, 10);
			f.recordAttackResolved(true, true, 10, 10);
		}
		for (int i = attacks; i < ticks - lost; i++)
		{
			f.recordTickSpent();
		}
		for (int i = 0; i < lost; i++)
		{
			f.recordTickLost(false);
		}
		f.recordDamageDealt(attacks * 10 + luck, start + 1, true);
		f.end(true, start + 2);
		return f;
	}

	/** 60 kills, oldest first: clocks 150 to 209 ticks in a shuffled order, executions from their idle ticks. */
	private static List<Fight> sixtyOldestFirst()
	{
		final List<Fight> kills = new ArrayList<>();
		for (int i = 0; i < 60; i++)
		{
			final int ticks = 150 + (i * 37) % 60;
			final int lost = (i * 7) % 11;
			kills.add(kill((i + 1) * 1_000_000L, ticks, lost, (i % 5 - 2) * 10));
		}
		return kills;
	}

	private static List<Fight> newestFirst(List<Fight> oldestFirst)
	{
		final List<Fight> list = new ArrayList<>(oldestFirst);
		Collections.reverse(list);
		return list;
	}

	@Test
	public void seedingSixtyKillsGivesTheBestsRanksAndGrades()
	{
		final List<Fight> oldest = sixtyOldestFirst();
		final BossRecords records = new BossRecords(dir, GSON);
		assertTrue(records.noteHistory(newestFirst(oldest), 0));
		final BossRecords.View v = records.view(LIKE);
		assertNotNull(v);
		assertEquals(60, v.kills);
		assertEquals(1_000_000L, v.firstMillis);
		// The fastest: 150 ticks, 90 s (kill 1, i = 0), with its luck.
		assertEquals(90.0, v.fastest.value, 1e-9);
		assertEquals(1_000_000L, v.fastest.millis);
		assertEquals(BossRecords.roll(BossRecords.KillFigures.of(room(oldest.get(0))).percentile), v.fastest.roll());
		// Every kill's clock is ranked: 150..209 ticks, one each, so 180 ticks (108 s) is the 31st fastest of 60.
		assertEquals(1, v.rank(90.0));
		assertEquals(31, v.rank(108.0));
		assertEquals(61, v.rank(200.0));
		// The best execution and luck-removed time are the kills', from Numbers as the side panel reads them.
		double bestExecution = 0;
		double bestLuckRemoved = Double.MAX_VALUE;
		for (Fight f : oldest)
		{
			final BossRecords.KillFigures k = BossRecords.KillFigures.of(room(f));
			bestExecution = Math.max(bestExecution, k.execution);
			bestLuckRemoved = Math.min(bestLuckRemoved, k.luckRemoved);
		}
		assertEquals(bestExecution, v.execution.value, 1e-12);
		assertEquals(bestLuckRemoved, v.luckRemoved.value, 1e-9);
		// Every kill graded once; the trend has them all, oldest first.
		assertEquals(60, v.grades[0] + v.grades[1] + v.grades[2] + v.grades[3]);
		assertEquals(60, v.luckRemovedTrend.length);
		assertEquals(1_000_000L, (long) v.luckRemovedTrend[0][0]);
		assertNotNull(v.gradeOf(1_000_000L));
		// The newest kill's standing, worked out against the 50 before it.
		assertNotNull(v.last);
		assertEquals(60, v.last.ranked);
		assertNotNull(v.last.grade);
		assertTrue(v.last.basis, v.last.basis.endsWith("of last 50"));
		assertTrue(v.longestStreak >= v.streak);
	}

	private static Encounter room(Fight f)
	{
		final Encounter room = new Encounter(BOSS, null, f.getStartMillis());
		room.add(f);
		return room;
	}

	@Test
	public void aNewKillUpdatesTheRecordOnceAndItsStandingIsALookup()
	{
		final List<Fight> oldest = sixtyOldestFirst();
		final BossRecords records = new BossRecords(dir, GSON);
		records.noteHistory(newestFirst(oldest), 0);
		assertFalse("the same history again: nothing new", records.noteHistory(newestFirst(oldest), 0));
		records.recordedLines();
		final Fight pb = kill(70_000_000L, 140, 0, 0);
		final Encounter shown = room(pb);
		assertNull("not recorded until history is saved", records.standing(shown));
		oldest.add(pb);
		assertTrue(records.noteHistory(newestFirst(oldest), PvmPerformancePlugin.RECORDS_RECENT_FIGHTS));
		assertFalse(records.noteHistory(newestFirst(oldest), PvmPerformancePlugin.RECORDS_RECENT_FIGHTS));
		final BossRecords.View v = records.view(LIKE);
		assertEquals(61, v.kills);
		assertEquals(84.0, v.fastest.value, 1e-9);
		// The card's room (the live one, not the rebuilt one) finds it by its start.
		final BossRecords.Standing s = records.standing(shown);
		assertNotNull(s);
		assertEquals(70_000_000L, s.startMillis);
		assertTrue(s.newPb);
		assertEquals(90.0, s.previousBestClock, 1e-9);
		assertEquals(84.0, s.clock, 1e-9);
		assertEquals(1, s.rank);
		assertEquals(61, s.ranked);
		assertEquals(-1, s.flawlessStreak);
		final List<String> lines = records.recordedLines();
		assertEquals(1, lines.size());
		assertTrue(lines.get(0), lines.get(0).startsWith("RECORD Vardorvis (700 hp, solo): kill 61, 1st fastest of 61 "
			+ "(84.0 s, a new PB), grade "));
	}

	@Test
	public void theRecordSurvivesHistorysBudgetDroppingTheOldest()
	{
		final List<Fight> oldest = sixtyOldestFirst();
		final BossRecords records = new BossRecords(dir, GSON);
		records.noteHistory(newestFirst(oldest), 0);
		// The budget drops the oldest 30 (the 90 s best among them); a new kill comes.
		final List<Fight> kept = new ArrayList<>(oldest.subList(30, 60));
		kept.add(kill(80_000_000L, 200, 2, 0));
		records.noteHistory(newestFirst(kept), PvmPerformancePlugin.RECORDS_RECENT_FIGHTS);
		final BossRecords.View v = records.view(LIKE);
		assertEquals(61, v.kills);
		assertEquals(90.0, v.fastest.value, 1e-9);
		assertEquals(1_000_000L, v.firstMillis);
		// 200 ticks: the 50 of 150 to 199 faster.
		assertEquals(51, v.last.rank);
	}

	@Test
	public void itSavesAndLoadsBackWithALastGoodCopy() throws IOException
	{
		final List<Fight> history = newestFirst(sixtyOldestFirst());
		final BossRecords records = new BossRecords(dir, GSON);
		assertTrue(records.load(history, NOW).contains("seeded 1 bosses"));
		// A kill whose execution and luck aren't known: NaN is written and read back (plain Gson would refuse it).
		final LedgerHistory.Like party = new LedgerHistory.Like(BOSS, 700, false);
		records.record(new BossRecords.KillFigures(party, 5_000L, 100, Double.NaN, Double.NaN, Double.NaN, null));
		records.save();
		final BossRecords again = new BossRecords(dir, GSON);
		final String line = again.load(history, NOW);
		assertEquals("RECORDS loaded 2 bosses from records.json", line);
		assertEquals(60, again.view(LIKE).kills);
		final BossRecords.View p = again.view(party);
		assertTrue(Double.isNaN(p.luckRemoved.value));
		assertNull(p.last.grade);
		assertNull(p.last.tooltip());
		assertEquals(0, p.last.roll());
		// Each key's newest standing comes back with it.
		assertNotNull(again.standing(room(history.get(0))));
		assertArrayEquals(Files.readAllBytes(tmp.resolve(BossRecords.FILE)),
			Files.readAllBytes(tmp.resolve(BossRecords.LAST_GOOD)));
		assertFalse(Files.exists(tmp.resolve(BossRecords.TEMP)));
	}

	@Test
	public void anUnreadableFileIsMovedAsideAndReseededNeverOverwritten() throws IOException
	{
		final byte[] broken = "{\"version\":1,\"records\":[{\"na".getBytes(StandardCharsets.UTF_8);
		Files.write(tmp.resolve(BossRecords.FILE), broken);
		final BossRecords records = new BossRecords(dir, GSON);
		final String line = records.load(newestFirst(sixtyOldestFirst()), NOW);
		assertTrue(line, line.contains("didn't read, moved aside as records-unreadable-"));
		assertTrue(line, line.contains("seeded 1 bosses"));
		assertEquals(60, records.view(LIKE).kills);
		records.save();
		final List<String> aside;
		try (Stream<Path> files = Files.list(tmp))
		{
			aside = files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(BossRecords.UNREADABLE_PREFIX))
				.collect(Collectors.toList());
		}
		assertEquals(1, aside.size());
		assertEquals(BossRecords.UNREADABLE_PREFIX + HistoryStore.stamp(NOW) + ".json", aside.get(0));
		assertArrayEquals(broken, Files.readAllBytes(tmp.resolve(aside.get(0))));
		// An empty file (a crash) is the same, and the last good copy a good load kept is loaded.
		new BossRecords(dir, GSON).load(new ArrayList<>(), NOW + 30_000);
		Files.write(tmp.resolve(BossRecords.FILE), new byte[0]);
		assertTrue(new BossRecords(dir, GSON).load(new ArrayList<>(), NOW + 60_000).contains("loaded 1 bosses from "
			+ BossRecords.LAST_GOOD));
	}

	@Test
	public void seedingReadsTheHistoryBackupsDeduplicated() throws IOException
	{
		final List<Fight> oldest = sixtyOldestFirst();
		// History has the newest 40; a backup has the oldest 30 (10 overlap).
		final List<Fight> history = newestFirst(oldest.subList(20, 60));
		Files.write(tmp.resolve("history.json.bak-20261031-190624"),
			GSON.toJson(newestFirst(oldest.subList(0, 30))).getBytes(StandardCharsets.UTF_8));
		// Not a backup: left alone.
		Files.write(tmp.resolve("history-unreadable-20261105-224300.json"), new byte[0]);
		final BossRecords records = new BossRecords(dir, GSON);
		final String line = records.load(history, NOW);
		assertTrue(line, line.contains("1 backups, 20 fights read from them"));
		assertEquals(60, records.view(LIKE).kills);
		assertEquals(90.0, records.view(LIKE).fastest.value, 1e-9);
		assertTrue(BossRecords.isBackup("history-before-t178.json.bak"));
		assertFalse(BossRecords.isBackup("history-last-good.json"));
	}

	@Test
	public void theFixedBandsBelowTenKills()
	{
		final double[] five = {0.95, 0.96, 0.97, 0.98, 0.99};
		assertEquals(BossRecords.Grade.S, BossRecords.grade(0.996, 100, five).grade);
		assertEquals(BossRecords.Grade.A, BossRecords.grade(0.98, 100, five).grade);
		assertEquals(BossRecords.Grade.B, BossRecords.grade(0.95, 100, five).grade);
		assertEquals(BossRecords.Grade.C, BossRecords.grade(0.80, 100, five).grade);
		final BossRecords.Graded a = BossRecords.grade(0.98, 100, five);
		assertEquals("fixed bands (5 kills)", a.basis);
		// 1.5 points short of S's 99.5%, over 167 combat ticks: 2.5 ticks, so 3.
		assertEquals(BossRecords.Grade.S, a.next);
		assertEquals(3, a.ticksToNext);
		assertEquals("3 ticks from an S", a.tooltip());
		final BossRecords.Graded s = BossRecords.grade(0.999, 100, five);
		assertNull(s.next);
		assertEquals(0, s.ticksToNext);
		assertNull(s.tooltip());
		assertEquals("fixed bands (0 kills)", BossRecords.grade(0.999, 100, new double[0]).basis);
		assertEquals("2 ticks from a B", BossRecords.grade(0.89, 120, five).tooltip());
		assertNull(BossRecords.grade(Double.NaN, 100, five));
	}

	@Test
	public void againstTheLastFiftyTheTopFivePercentIsAnS()
	{
		// 50 earlier kills at 0.900, 0.902 ... 0.998.
		final double[] fifty = new double[50];
		for (int i = 0; i < 50; i++)
		{
			fifty[i] = 0.900 + 0.002 * i;
		}
		final BossRecords.Graded best = BossRecords.grade(0.999, 120, fifty);
		assertEquals(BossRecords.Grade.S, best.grade);
		assertEquals("best of last 50", best.basis);
		// 48 below: 96%, an S.
		final BossRecords.Graded s = BossRecords.grade(0.995, 120, fifty);
		assertEquals(BossRecords.Grade.S, s.grade);
		assertEquals("top 4% of last 50", s.basis);
		// 47 below: 94%, an A, one tick (0.5 points over 200 ticks) from an S.
		final BossRecords.Graded a = BossRecords.grade(0.993, 120, fifty);
		assertEquals(BossRecords.Grade.A, a.grade);
		assertEquals("top 6% of last 50", a.basis);
		assertEquals(1, a.ticksToNext);
		assertEquals("1 tick from an S", a.tooltip());
		// 35 below is 70%, an A; 34 is 68%, a B.
		assertEquals(BossRecords.Grade.A, BossRecords.grade(0.9685, 120, fifty).grade);
		final BossRecords.Graded b = BossRecords.grade(0.9665, 120, fifty);
		assertEquals(BossRecords.Grade.B, b.grade);
		assertEquals("top 32% of last 50", b.basis);
		// 5 below is 10%, a B; 4 is 8%, a C.
		assertEquals(BossRecords.Grade.B, BossRecords.grade(0.9085, 120, fifty).grade);
		final BossRecords.Graded c = BossRecords.grade(0.9065, 120, fifty);
		assertEquals(BossRecords.Grade.C, c.grade);
		assertEquals("bottom 8% of last 50", c.basis);
		assertEquals("lowest of last 50", BossRecords.grade(0.80, 120, fifty).basis);
		// A tie counts half: level with the 48th (0.994) is 47.5 below, 95%, an S.
		assertEquals(BossRecords.Grade.S, BossRecords.grade(0.900 + 0.002 * 47, 120, fifty).grade);
		// Only the last 50 are read.
		final double[] sixty = new double[60];
		for (int i = 0; i < 10; i++)
		{
			sixty[i] = 1.0;
		}
		System.arraycopy(fifty, 0, sixty, 10, 50);
		assertEquals("best of last 50", BossRecords.grade(0.999, 120, sixty).basis);
		// T-186's prototype's words for its Cerberus case.
		final double[] cerberus = new double[20];
		for (int i = 0; i < cerberus.length; i++)
		{
			cerberus[i] = 0.70 + i * 0.01;
		}
		assertEquals(BossRecords.Grade.S, BossRecords.grade(0.90, 300, cerberus).grade);
		assertEquals("top 20% of last 20", BossRecords.grade(0.855, 300, cerberus).basis);
		assertEquals("bottom 3% of last 20", BossRecords.grade(0.70, 300, cerberus).basis);
	}

	@Test
	public void theStreaksTheSpreadAndTheRoll()
	{
		final BossRecords records = new BossRecords(dir, GSON);
		// Ten kills at 99.6% under the fixed bands: ten S's in a row.
		for (int i = 0; i < 10; i++)
		{
			records.record(new BossRecords.KillFigures(LIKE, (i + 1) * 1000L, 100, 0.996, 98, 0.2, null));
		}
		BossRecords.View v = records.view(LIKE);
		assertEquals(10, v.streak);
		assertEquals(10, v.longestStreak);
		assertEquals(20, v.fastest.roll());
		assertEquals(-1, v.flawlessStreak);
		assertArrayEquals(new int[]{10, 0, 0, 0}, v.grades);
		// The lowest of the last 10: the streak ends, the longest stays.
		records.record(new BossRecords.KillFigures(LIKE, 20_000L, 100, 0.90, 98, 0.5, true));
		v = records.view(LIKE);
		assertEquals(0, v.streak);
		assertEquals(10, v.longestStreak);
		assertEquals(1, v.flawlessStreak);
		assertEquals(BossRecords.Grade.C, v.last.grade);
		assertEquals("lowest of last 10", v.last.basis);
		assertEquals(0, v.last.streak);
		assertEquals(1, v.last.flawlessStreak);
		assertEquals(50, v.last.roll());
		assertArrayEquals(new int[]{10, 0, 0, 1}, v.grades);
		assertEquals(BossRecords.Grade.C, v.gradeOf(20_000L));
		// Older or the same: left alone.
		assertFalse(records.record(new BossRecords.KillFigures(LIKE, 20_000L, 90, 0.99, 98, 0.5, true)));
		assertEquals(0, BossRecords.roll(Double.NaN));
		assertEquals("12th", BossRecords.ordinal(12));
		assertEquals("21st", BossRecords.ordinal(21));
	}
}

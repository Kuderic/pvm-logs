package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/**
 * T-187: the per-boss record (records.json) that outlives history's budget, and the grade it holds; T-192: its badge
 * counts; T-195: the grade only against kills scored the same way.
 */
public class BossRecordsTest
{
	private static final Gson GSON = new Gson();
	private static final String BOSS = "Vardorvis";
	private static final LedgerHistory.Like LIKE = new LedgerHistory.Like(BOSS, 700, true);
	private static final long NOW = 1_762_700_000_000L;
	/** Cerberus, as boss-rules.json lists her. */
	private static final int CERBERUS_ID = 5862;

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
		assertEquals(BossRecords.roll(BossRecords.KillFigures.of(room(oldest.get(0)), null).percentile), v.fastest.roll());
		// Every kill's clock is ranked: 150..209 ticks, one each, so 180 ticks (108 s) is the 31st fastest of 60.
		assertEquals(1, v.rank(90.0));
		assertEquals(31, v.rank(108.0));
		assertEquals(61, v.rank(200.0));
		// The best execution and luck-removed time are the kills', from Numbers as the side panel reads them.
		double bestExecution = 0;
		double bestLuckRemoved = Double.MAX_VALUE;
		for (Fight f : oldest)
		{
			final BossRecords.KillFigures k = BossRecords.KillFigures.of(room(f), null);
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

	// ---- T-192: badge counts ----

	private static BossRecords.KillFigures figures(long start, double execution, Boolean flawless, int kc, boolean clean)
	{
		return new BossRecords.KillFigures(LIKE, start, 100, execution, 98, 0.5, flawless, kc, clean);
	}

	private static int count(BossRecords.View v, Badges.Badge b)
	{
		return v.badgeCount(b);
	}

	@Test
	public void aRunOfSevenAsCountsOneThreeInARow()
	{
		final BossRecords records = new BossRecords(dir, GSON);
		long t = 1000;
		// Seven at 99.6% under the fixed bands: one run of 7.
		for (int i = 0; i < 7; i++)
		{
			records.record(figures(t += 1000, 0.996, null, 0, false));
		}
		BossRecords.View v = records.view(LIKE);
		assertEquals(1, count(v, Badges.Badge.THREE_IN_A_ROW));
		assertEquals(7, v.longestStreak);
		// Only the run's third kill earned it; the 7th's card names the run from its streak.
		assertFalse(v.last.badges.contains(Badges.Badge.THREE_IN_A_ROW));
		assertEquals(7, v.last.streak);
		assertTrue(records.standing(room(kill(4000, 100, 0, 0))).badges.contains(Badges.Badge.THREE_IN_A_ROW));
		// A B breaks it; three more A's are a second run.
		records.record(figures(t += 1000, 0.95, null, 0, false));
		assertFalse(records.view(LIKE).last.badges.contains(Badges.Badge.THREE_IN_A_ROW));
		for (int i = 0; i < 2; i++)
		{
			records.record(figures(t += 1000, 0.996, null, 0, false));
		}
		assertEquals(1, count(records.view(LIKE), Badges.Badge.THREE_IN_A_ROW));
		records.record(figures(t += 1000, 0.999, null, 0, false));
		v = records.view(LIKE);
		assertEquals(2, count(v, Badges.Badge.THREE_IN_A_ROW));
		assertEquals(7, v.longestStreak);
		assertEquals(3, v.streak);
	}

	@Test
	public void flawlessIsNeverCountedWithoutAttackRules()
	{
		final BossRecords records = new BossRecords(dir, GSON);
		// No rules (null): never Flawless, and absent from the counts.
		records.record(figures(1000, 0.99, null, 0, false));
		assertFalse(records.view(LIKE).badgeCounts.containsKey(Badges.Badge.FLAWLESS));
		assertFalse(records.view(LIKE).last.badges.contains(Badges.Badge.FLAWLESS));
		// With rules: counted when earned.
		records.record(figures(2000, 0.99, false, 0, false));
		assertFalse(records.view(LIKE).badgeCounts.containsKey(Badges.Badge.FLAWLESS));
		records.record(figures(3000, 0.99, true, 0, false));
		records.record(figures(4000, 0.99, true, 0, false));
		final BossRecords.View v = records.view(LIKE);
		assertEquals(2, count(v, Badges.Badge.FLAWLESS));
		assertEquals(2, v.flawlessStreak);
		assertTrue(v.last.badges.contains(Badges.Badge.FLAWLESS));
	}

	@Test
	public void aNewKillAddsToTheCountsOnce()
	{
		final List<Fight> oldest = sixtyOldestFirst();
		final BossRecords records = new BossRecords(dir, GSON);
		records.noteHistory(newestFirst(oldest), 0);
		records.recordedLines();
		final BossRecords.View before = records.view(LIKE);
		// The fixture's clean runs: no idle tick (i = 0, 11, 22, 33, 44, 55) and a whole setup.
		assertEquals(6, count(before, Badges.Badge.CLEAN_RUN));
		assertEquals(0, count(before, Badges.Badge.HUNDREDTH_KILL));
		// The 100th by the game's count, clean: one of each, once however often history is saved.
		final Fight hundredth = kill(90_000_000L, 160, 0, 0);
		hundredth.context().kc = 100;
		oldest.add(hundredth);
		for (int i = 0; i < 3; i++)
		{
			records.noteHistory(newestFirst(oldest), PvmPerformancePlugin.RECORDS_RECENT_FIGHTS);
		}
		final BossRecords.View v = records.view(LIKE);
		assertEquals(1, count(v, Badges.Badge.HUNDREDTH_KILL));
		assertEquals(7, count(v, Badges.Badge.CLEAN_RUN));
		final BossRecords.Standing s = records.standing(room(hundredth));
		assertEquals(100, s.kc);
		assertTrue(s.badges.containsAll(java.util.EnumSet.of(Badges.Badge.HUNDREDTH_KILL, Badges.Badge.CLEAN_RUN)));
		final List<String> lines = records.recordedLines();
		assertEquals(1, lines.size());
		assertTrue(lines.get(0), lines.get(0).contains(", badges 100th kill, Clean run"));
	}

	@Test
	public void aVersionOneFileIsBackfilledFromHistoryAndTheBackupsKeepingTheRest() throws IOException
	{
		final List<Fight> oldest = sixtyOldestFirst();
		oldest.get(9).context().kc = 100;
		oldest.get(49).context().kc = 200;
		// Seeded and saved as #213 saved it: version 1, no counts.
		final BossRecords seeded = new BossRecords(dir, GSON);
		seeded.noteHistory(newestFirst(oldest), 0);
		final BossRecords.View expected = seeded.view(LIKE);
		seeded.save();
		final JsonObject file = new JsonParser().parse(new String(Files.readAllBytes(tmp.resolve(BossRecords.FILE)),
			StandardCharsets.UTF_8)).getAsJsonObject();
		file.addProperty("version", 1);
		for (JsonElement r : file.getAsJsonArray("records"))
		{
			r.getAsJsonObject().remove("badges");
			r.getAsJsonObject().getAsJsonObject("last").remove("badges");
			r.getAsJsonObject().getAsJsonObject("last").remove("kc");
		}
		Files.write(tmp.resolve(BossRecords.FILE), file.toString().getBytes(StandardCharsets.UTF_8));
		// History has dropped the oldest 20 since; a backup holds the oldest 30.
		final List<Fight> history = newestFirst(oldest.subList(20, 60));
		Files.write(tmp.resolve("history.json.bak-20261031-190624"),
			GSON.toJson(newestFirst(oldest.subList(0, 30))).getBytes(StandardCharsets.UTF_8));
		final BossRecords loaded = new BossRecords(dir, GSON);
		final String line = loaded.load(history, NOW);
		final BossRecords.View v = loaded.view(LIKE);
		assertEquals(expected.badgeCounts, v.badgeCounts);
		assertEquals(2, count(v, Badges.Badge.HUNDREDTH_KILL));
		assertEquals(6, count(v, Badges.Badge.CLEAN_RUN));
		assertEquals("RECORDS loaded 1 bosses from records.json; badges counted for 1 bosses from 60 kills (history and 1 "
			+ "backups): 100th kill 2, Flawless 0, Clean run 6, 3 A's in a row " + count(expected, Badges.Badge.THREE_IN_A_ROW)
			+ "; scoring stamped for 1 bosses",
			line);
		// Nothing else moved: the bests, the streaks, the rank, the grades.
		assertEquals(expected.kills, v.kills);
		assertEquals(expected.fastest.value, v.fastest.value, 0);
		assertEquals(expected.luckRemoved.value, v.luckRemoved.value, 0);
		assertEquals(expected.execution.value, v.execution.value, 0);
		assertEquals(expected.streak, v.streak);
		assertEquals(expected.longestStreak, v.longestStreak);
		assertArrayEquals(expected.grades, v.grades);
		assertEquals(expected.rank(100), v.rank(100));
		// The newest kill's badges came back with it.
		assertEquals(expected.last.badges, loaded.standing(room(oldest.get(59))).badges);
		// Saved as version 2: the next load counts nothing again.
		loaded.save();
		assertEquals("RECORDS loaded 1 bosses from records.json", new BossRecords(dir, GSON).load(history, NOW));
	}

	// ---- T-195: the grade compares only kills scored the same way ----

	private static BossRecords.KillFigures scored(LedgerHistory.Like like, long start, double clock, double execution,
		String stamp)
	{
		return new BossRecords.KillFigures(like, start, clock, execution, 98, 0.5, null, 0, false, stamp);
	}

	@Test
	public void aRulesChangeStartsTheWindowAgainWithFixedBandsUnderTen()
	{
		final BossRecords records = new BossRecords(dir, GSON);
		// 20 kills scored the old way, at 0.80 to 0.89: Cerberus before her stall was excused.
		for (int i = 0; i < 20; i++)
		{
			records.record(scored(LIKE, (i + 1) * 1000L, 100, 0.80 + 0.005 * i, "old"));
		}
		// The first kill scored the new way, at 0.93: against the old ones it would be the best of 20, an S.
		assertEquals(BossRecords.Grade.S, records.view(LIKE).grade(0.93, 100).grade);
		records.record(scored(LIKE, 30_000L, 100, 0.93, "new"));
		BossRecords.Standing s = records.view(LIKE).last;
		// Against no kill scored its way: the fixed bands, which call 93% a B.
		assertEquals(BossRecords.Grade.B, s.grade);
		assertEquals("fixed bands (0 kills since the rules changed)", s.basis);
		for (int i = 0; i < 3; i++)
		{
			records.record(scored(LIKE, 31_000L + i * 1000L, 100, 0.94, "new"));
		}
		assertEquals("fixed bands (3 kills since the rules changed)", records.view(LIKE).last.basis);
		// From 10 kills scored the new way, their own spread.
		for (int i = 0; i < 6; i++)
		{
			records.record(scored(LIKE, 40_000L + i * 1000L, 100, 0.95 + 0.005 * i, "new"));
		}
		records.record(scored(LIKE, 50_000L, 100, 0.999, "new"));
		assertEquals("best of last 10", records.view(LIKE).last.basis);
		// The view grades a kill not yet recorded the same way.
		assertEquals("lowest of last 11", records.view(LIKE).grade(0.90, 100).basis);
	}

	@Test
	public void otherBossesWindowsAreUntouched()
	{
		final LedgerHistory.Like other = new LedgerHistory.Like("Araxxor", 1020, true);
		final BossRecords records = new BossRecords(dir, GSON);
		for (int i = 0; i < 12; i++)
		{
			records.record(scored(other, (i + 1) * 1000L, 90, 0.90 + 0.005 * i, "araxxor"));
			records.record(scored(LIKE, (i + 1) * 1000L + 500, 100, 0.90, "old"));
		}
		records.record(scored(LIKE, 20_000L, 100, 0.95, "new"));
		records.record(scored(other, 21_000L, 90, 0.999, "araxxor"));
		assertEquals("best of last 12", records.view(other).last.basis);
		assertEquals("fixed bands (0 kills since the rules changed)", records.view(LIKE).last.basis);
	}

	@Test
	public void theStreakRestartsAtTheChange()
	{
		final BossRecords records = new BossRecords(dir, GSON);
		for (int i = 0; i < 4; i++)
		{
			records.record(scored(LIKE, (i + 1) * 1000L, 100, 0.997, "old"));
		}
		assertEquals(4, records.view(LIKE).streak);
		// An S under the new scoring: a streak of 1, not 5; the best stays 4.
		records.record(scored(LIKE, 10_000L, 100, 0.999, "new"));
		BossRecords.View v = records.view(LIKE);
		assertEquals(1, v.streak);
		assertEquals(4, v.longestStreak);
		records.record(scored(LIKE, 11_000L, 100, 0.999, "new"));
		records.record(scored(LIKE, 12_000L, 100, 0.999, "new"));
		v = records.view(LIKE);
		assertEquals(3, v.streak);
		// Its own "3 A's in a row", the second run counted.
		assertEquals(2, v.badgeCount(Badges.Badge.THREE_IN_A_ROW));
		assertTrue(v.last.badges.contains(Badges.Badge.THREE_IN_A_ROW));
	}

	@Test
	public void theBestsAndTheRankIgnoreTheStamp()
	{
		final BossRecords records = new BossRecords(dir, GSON);
		records.record(scored(LIKE, 1000L, 90, 0.99, "old"));
		records.record(scored(LIKE, 2000L, 120, 0.80, "old"));
		records.record(scored(LIKE, 3000L, 100, 0.95, "new"));
		final BossRecords.View v = records.view(LIKE);
		assertEquals(3, v.kills);
		assertEquals(90, v.fastest.value, 0.31);
		assertEquals(0.99, v.execution.value, 1e-12);
		assertEquals(2, v.last.rank);
		assertEquals(3, v.last.ranked);
		assertFalse(v.last.newPb);
		assertEquals(90, v.last.previousBestClock, 0.31);
	}

	@Test
	public void theStampReadsOnlyWhatScoresExecution()
	{
		final BossRules.Boss cerberus = new BossRules.Boss();
		cerberus.name = "Cerberus";
		final String none = BossRecords.scoringStamp(null);
		assertEquals(none, BossRecords.scoringStamp(cerberus));
		assertTrue(none, none.startsWith("e" + BossRecords.EXECUTION_FORMULA + "-"));
		// Notes, xp and hitpoints margins don't score execution.
		cerberus.notes = "a note";
		cerberus.xpBonus = 0.1;
		cerberus.countedHpMargin = 3;
		assertEquals(none, BossRecords.scoringStamp(cerberus));
		// A stall does (T-191), and a change to it.
		cerberus.stall = new BossRules.StallRule();
		cerberus.stall.holdAboveHp = 400;
		final String stalled = BossRecords.scoringStamp(cerberus);
		assertFalse(stalled.equals(none));
		cerberus.stall.holdAboveHp = 380;
		assertFalse(stalled.equals(BossRecords.scoringStamp(cerberus)));
		// So do an allowance and attack rules.
		final BossRules.Boss leviathan = new BossRules.Boss();
		leviathan.uptimeAllowances = new ArrayList<>(Collections.singletonList(new BossRules.UptimeAllowance()));
		assertFalse(none.equals(BossRecords.scoringStamp(leviathan)));
		// The bundled rules: Cerberus's stamp isn't the formula's alone, and is the same twice.
		final BossRules rules = BossRules.load(GSON, null);
		final java.util.function.IntFunction<String> stamps = BossRecords.stamps(rules);
		final int cerberusId = CERBERUS_ID;
		assertEquals(stamps.apply(cerberusId), stamps.apply(cerberusId));
		assertFalse(none.equals(stamps.apply(cerberusId)));
		assertEquals(none, stamps.apply(-1));
	}

	private static Fight cerberusKill(long start, int ticks, int lost, String build)
	{
		final Fight f = kill(start, ticks, lost, 0);
		final Fight c = new Fight("Cerberus", CERBERUS_ID, 1, 600, start, null, 0);
		// The same kill on Cerberus: rebuilt by its JSON, with its name and build.
		final com.google.gson.JsonObject o = GSON.toJsonTree(f).getAsJsonObject();
		o.add("targetName", GSON.toJsonTree(c.getTargetName()));
		o.add("targetId", GSON.toJsonTree(c.getTargetId()));
		o.add("maxHp", GSON.toJsonTree(600));
		final Fight out = GSON.fromJson(o, Fight.class);
		if (build != null)
		{
			out.context().build = build;
		}
		return out;
	}

	@Test
	public void aFileBeforeStampsIsStampedAndCerberusRegradedFromHerRulesChange() throws IOException
	{
		// Cerberus: 30 kills on a build before T-191 (executions about 0.80 to 0.86), then 13 on one with it (0.93 up).
		final List<Fight> oldest = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			oldest.add(cerberusKill((i + 1) * 1_000_000L, 150, 22 + i % 8, "build 510dfe5 (master, clean, committed "
				+ "2026-11-08 22:40, built 2026-11-08 22:41)"));
		}
		for (int i = 0; i < 13; i++)
		{
			oldest.add(cerberusKill(40_000_000L + i * 1_000_000L, 150, i % 9, "build e853e64 (master, clean, committed "
				+ "2026-11-09 20:42, built 2026-11-09 21:20)"));
		}
		// And Vardorvis, on the old build: only stamped.
		final List<Fight> vardorvis = sixtyOldestFirst();
		final List<Fight> all = new ArrayList<>(oldest);
		all.addAll(vardorvis);
		all.sort((a, b) -> Long.compare(a.getStartMillis(), b.getStartMillis()));
		// Recorded as #218 did, without stamps: every kill against every earlier one.
		final BossRecords before = new BossRecords(dir, GSON, id -> "unstamped");
		before.noteHistory(newestFirst(all), 0);
		final LedgerHistory.Like cerberus = new LedgerHistory.Like("Cerberus", 600, true);
		final BossRecords.View was = before.view(cerberus);
		final BossRecords.View vardorvisWas = before.view(LIKE);
		assertTrue("all A or better against the old kills", was.streak >= 10);
		before.save();
		final JsonObject file = new JsonParser().parse(new String(Files.readAllBytes(tmp.resolve(BossRecords.FILE)),
			StandardCharsets.UTF_8)).getAsJsonObject();
		file.addProperty("version", 2);
		for (JsonElement r : file.getAsJsonArray("records"))
		{
			r.getAsJsonObject().remove("executionStamps");
			r.getAsJsonObject().remove("streakStamp");
		}
		Files.write(tmp.resolve(BossRecords.FILE), file.toString().getBytes(StandardCharsets.UTF_8));

		final BossRecords loaded = new BossRecords(dir, GSON, BossRecords.stamps(BossRules.load(GSON, null)));
		final String line = loaded.load(newestFirst(all), NOW);
		assertTrue(line, line.contains("scoring stamped for 2 bosses; Cerberus (600 hp, solo) regraded: its 13 kills "
			+ "since its scoring changed grade S "));
		final BossRecords.View v = loaded.view(cerberus);
		// The 13 graded against each other alone: the first 10 by the fixed bands, then their own spread.
		assertTrue(line, v.streak < was.streak);
		assertTrue(v.last.basis, v.last.basis.endsWith("of last 12"));
		assertEquals(was.kills, v.grades[0] + v.grades[1] + v.grades[2] + v.grades[3]);
		// Kept: the kills, the bests, the rank.
		assertEquals(was.kills, v.kills);
		assertEquals(was.fastest.value, v.fastest.value, 0);
		assertEquals(was.execution.value, v.execution.value, 0);
		assertEquals(was.rank(90), v.rank(90));
		// Vardorvis: stamped only, his grades as they were.
		final BossRecords.View vv = loaded.view(LIKE);
		assertArrayEquals(vardorvisWas.grades, vv.grades);
		assertEquals(vardorvisWas.streak, vv.streak);
		assertEquals(vardorvisWas.last.basis, vv.last.basis);
		// A new Vardorvis kill grades against his whole window, as before.
		final Fight next = kill(200_000_000L, 160, 3, 0);
		all.add(next);
		loaded.noteHistory(newestFirst(all), PvmPerformancePlugin.RECORDS_RECENT_FIGHTS);
		assertTrue(loaded.view(LIKE).last.basis, loaded.view(LIKE).last.basis.endsWith("of last 50"));
		// Saved as version 3: the next load stamps nothing again.
		loaded.save();
		assertEquals("RECORDS loaded 2 bosses from records.json",
			new BossRecords(dir, GSON, BossRecords.stamps(BossRules.load(GSON, null))).load(newestFirst(all), NOW));
	}
}

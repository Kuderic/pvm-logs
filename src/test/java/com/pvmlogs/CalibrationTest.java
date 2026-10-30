package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-110, T-113: the calibration report over a saved history, by boss, target
 * and weapon, split by build; only recorded builds are flagged, and the
 * findings come in plain sentences.
 */
public class CalibrationTest
{
	private static final Gson GSON = new Gson();
	/** As BuildInfo.describe() writes it into the R1 record. */
	private static final String BUILD_A = "build aaaa111 (master, clean, committed 2026-10-30 06:00, built 2026-10-30 06:10)";
	private static final String BUILD_B = "build bbbb222 (master, clean, committed 2026-10-30 07:00, built 2026-10-30 07:10)";

	private static long clock;

	/**
	 * A solo kill of ten attacks of 10 expected (variance 20 each): E 100, sd
	 * about 14, dealing {@code actual}; on {@code build}, or before R1 when null.
	 */
	private static Fight kill(String name, int id, int actual, String build)
	{
		final Fight f = new Fight(name, id, 1, 700, clock += 1000, null, 0);
		for (int i = 0; i < 10; i++)
		{
			f.recordAttackMade(false, 1.0);
			f.recordExpected(40, 0.5, 10, 20);
		}
		f.recordDamageDealt(actual, clock + 1, true);
		f.end(true, clock + 2);
		if (build != null)
		{
			f.context().build = build;
		}
		return f;
	}

	/** Twelve Vardorvis kills 30 to 52 over their 100 expected (z about 2 to 3.7): far past three standard errors. */
	private static List<Fight> biased(String build)
	{
		final List<Fight> out = new ArrayList<>();
		IntStream.range(0, 12).forEach(i -> out.add(kill("Vardorvis", NpcID.VARDORVIS, 130 + 2 * i, build)));
		return out;
	}

	/** Twelve Vardorvis kills as far under as over. */
	private static List<Fight> honest(String build)
	{
		final List<Fight> out = new ArrayList<>();
		IntStream.range(0, 12).forEach(i -> out.add(kill("Vardorvis", NpcID.VARDORVIS, i % 2 == 0 ? 80 + i : 120 - i,
			build)));
		return out;
	}

	/** Newest first, as the plugin keeps it. */
	@SafeVarargs
	private static List<Fight> newestFirst(List<Fight>... oldestFirst)
	{
		final List<Fight> list = new ArrayList<>();
		for (List<Fight> part : oldestFirst)
		{
			list.addAll(part);
		}
		Collections.reverse(list);
		return list;
	}

	private static Calibration.Group group(List<Calibration.Group> groups, String dimension, String subject,
		String build)
	{
		for (Calibration.Group g : groups)
		{
			if (g.dimension.equals(dimension) && g.subject().equals(subject)
				&& (build == null ? g.build == null : build.equals(g.build)))
			{
				return g;
			}
		}
		return null;
	}

	@Test
	public void buildsAreToldApartByTheirCommit()
	{
		assertEquals("aaaa111", Calibration.buildKey(BUILD_A));
		// The same commit built twice is one build: the build line's times don't split it.
		assertEquals("aaaa111", Calibration.buildKey(
			"build aaaa111 (master, clean, committed 2026-10-30 06:00, built 2026-10-30 09:45)"));
		assertEquals("aaaa111 dirty", Calibration.buildKey("build aaaa111 (t-113/x, dirty: 3 files changed)"));
		assertEquals("abc1234", Calibration.buildKey("abc1234 (master, clean)"));
		assertEquals("release 1.0", Calibration.buildKey("release 1.0"));
		assertNull(Calibration.buildKey("build unknown (no build info)"));
		assertNull(Calibration.buildKey(null));
	}

	@Test
	public void aBiasedBossOnARecordedBuildIsFlaggedAndAnHonestOneIsNot()
	{
		final List<Fight> vorkath = new ArrayList<>();
		IntStream.range(0, 12).forEach(i -> vorkath.add(kill("Vorkath", 8061, i % 2 == 0 ? 80 + i : 120 - i, BUILD_A)));
		final List<Fight> history = newestFirst(biased(BUILD_A), vorkath);
		final List<Calibration.Group> groups = Calibration.groups(history, null);
		final Calibration.Group vard = group(groups, "boss", "Vardorvis", "aaaa111");
		assertNotNull(vard);
		assertEquals(12, vard.kills);
		assertEquals(1692, vard.actual);
		assertEquals(1200, vard.expected, 1e-6);
		assertEquals(1.41, vard.ratio(), 0.01);
		assertTrue(vard.flagged());
		// Every kill sits in the top tenths of luck's percentile.
		final int[] d = vard.deciles();
		assertEquals(12, IntStream.of(d).sum());
		assertEquals(0, IntStream.of(d).limit(9).sum());
		final Calibration.Group vork = group(groups, "boss", "Vorkath", "aaaa111");
		assertEquals(12, IntStream.of(vork.deciles()).sum());
		assertEquals(0, vork.meanZ(), 0.1);
		assertFalse(vork.flagged());

		assertEquals(Collections.singletonList("Vardorvis: you dealt 41% more than the plugin expected over 12 kills."
			+ " More than luck explains, so likely a scoring error. (build aaaa111)"), Calibration.summary(history, null));
		final String report = Calibration.report(history, null);
		final String[] lines = report.split("\n");
		assertTrue(lines[0], lines[0].startsWith("CALIBRATION over 24 saved fights: 24 counted kills on recorded builds,"
			+ " 0 older."));
		assertEquals("IN SHORT:", lines[1]);
		assertTrue(lines[2], lines[2].startsWith("  Vardorvis: you dealt 41% more"));
		assertEquals("FLAGGED:", lines[3]);
		assertTrue(lines[4], lines[4].startsWith("  boss Vardorvis on build aaaa111: 12 kills, 12 fights, A 1692 / E 1200"
			+ " = 1.41, mean z +"));
		assertEquals("BOSSES (recorded builds):", lines[5]);
		assertTrue(report.contains("  boss Vorkath on build aaaa111: 12 kills"));
		assertFalse(report.contains(Calibration.OLDER));
		// T-111's button still counts one flag.
		assertEquals(1, PvmPerformancePanel.calibrationFlags(report));
	}

	@Test
	public void killsBeforeR1AreNeverFlagged()
	{
		final List<Fight> history = newestFirst(biased(null));
		final Calibration.Group vard = group(Calibration.groups(history, null), "boss", "Vardorvis", null);
		assertTrue("as far off as the flagged one above", vard.off());
		assertFalse(vard.flagged());
		final String report = Calibration.report(history, null);
		assertTrue(report.contains("FLAGGED: none"));
		assertEquals(0, PvmPerformancePanel.calibrationFlags(report));
		assertTrue(report.contains(Calibration.OLDER + "\n  bosses:\n    boss Vardorvis: 12 kills"));
		assertTrue(report.contains("; no kills on a recorded build yet"));
		assertFalse(report.contains("BOSSES (recorded builds):"));
		assertEquals(Collections.singletonList("Nothing to judge yet: none of your saved kills are on a build that records"
			+ " itself. Older kills are listed in the report but never flagged."), Calibration.summary(history, null));
	}

	@Test
	public void aBossOffOnOneBuildAndBackInLineOnALaterOneShowsAsFixed()
	{
		final List<Fight> history = newestFirst(biased(BUILD_A), honest(BUILD_B));
		final List<Calibration.Group> groups = Calibration.groups(history, null);
		final Calibration.Group a = group(groups, "boss", "Vardorvis", "aaaa111");
		final Calibration.Group b = group(groups, "boss", "Vardorvis", "bbbb222");
		assertTrue(a.flagged());
		assertFalse(b.flagged());
		assertTrue(Calibration.fixedLater(a, groups));
		final String report = Calibration.report(history, null);
		// Still flagged in the table, where it says the later build put it right.
		assertEquals(1, PvmPerformancePanel.calibrationFlags(report));
		assertTrue(report, report.contains("; back in line on build bbbb222 (12 kills, mean z "));
		// Nothing for the player to worry about.
		assertEquals(Collections.singletonList("Nothing looks off: over 24 kills on current builds, your damage matches"
			+ " what the plugin expected."), Calibration.summary(history, null));
	}

	@Test
	public void aBossStillOffOnALaterBuildSaysSo()
	{
		final List<Fight> history = newestFirst(biased(BUILD_A), biased(BUILD_B));
		final String report = Calibration.report(history, null);
		assertEquals(2, PvmPerformancePanel.calibrationFlags(report));
		assertTrue(report.contains("boss Vardorvis on build aaaa111: 12 kills"));
		assertTrue(report.contains("; still off on build bbbb222 (12 kills, mean z +"));
		final List<String> summary = Calibration.summary(history, null);
		assertEquals(2, summary.size());
		assertTrue(summary.get(1), summary.get(1).endsWith("(build bbbb222)"));
	}

	@Test
	public void olderKillsSayHowALaterBuildReads()
	{
		final List<Fight> history = newestFirst(biased(null), honest(BUILD_B));
		final String report = Calibration.report(history, null);
		assertTrue(report.contains("FLAGGED: none"));
		assertTrue(report, report.contains("    boss Vardorvis: 12 kills, 12 fights, A 1692 / E 1200 = 1.41, mean z +"));
		assertTrue(report.contains("; back in line on build bbbb222 (12 kills"));
		// Fewer than ten on the new build: too few to tell.
		final List<Fight> old = biased(null);
		final List<Fight> few = new ArrayList<>(honest(BUILD_B).subList(0, 4));
		final String early = Calibration.report(newestFirst(old, few), null);
		assertTrue(early, early.contains("; build bbbb222 has 4 kills so far, too few to tell"));
		assertEquals(Collections.singletonList("Nothing looks off so far: over 4 kills on current builds, your damage"
			+ " matches what the plugin expected. A boss needs 10 kills on one build before it can be judged."),
			Calibration.summary(newestFirst(old, few), null));
	}

	@Test
	public void certainHitsOnALaterBuildSayTheirAOverE()
	{
		// Eric's history, 2026-10-30: Mirrorbacks before T-098 read A/E 1.37; on 824b2f2 every hit is a certain max, so
		// there's no z, and 12 fights read A 685 / E 685.
		final Calibration.Group old = new Calibration.Group("target", "Araxxor", "Mirrorback Araxyte", null);
		old.fights = 12;
		old.actual = 1370;
		old.expected = 1000;
		IntStream.range(0, 12).forEach(i -> old.z.add(1.5 + 0.1 * i));
		old.seen(100);
		final Calibration.Group now = new Calibration.Group("target", "Araxxor", "Mirrorback Araxyte", "824b2f2");
		now.fights = 12;
		now.actual = 685;
		now.expected = 685;
		now.seen(200);
		assertTrue(old.off());
		assertEquals("; A/E 1.00 on build 824b2f2 (12 fights, certain hits with no luck to test)",
			Calibration.laterNote(old, Arrays.asList(old, now)));
	}

	@Test
	public void tooFewKillsAreNeverFlagged()
	{
		final List<Fight> history = newestFirst(new ArrayList<>(biased(BUILD_A).subList(0, Calibration.FLAG_MIN - 1)));
		final Calibration.Group vard = group(Calibration.groups(history, null), "boss", "Vardorvis", "aaaa111");
		assertTrue(Math.abs(vard.meanZ()) > Calibration.FLAG_SE * vard.standardError());
		assertFalse(vard.flagged());
		assertTrue(Calibration.report(history, null).contains("FLAGGED: none"));
	}

	private static Calibration.Group made(String dimension, String boss, String part, int count, long actual,
		double expected, double... z)
	{
		final Calibration.Group g = new Calibration.Group(dimension, boss, part, "abc1234");
		if ("boss".equals(dimension))
		{
			g.kills = count;
		}
		g.fights = count;
		g.actual = actual;
		g.expected = expected;
		for (double v : z)
		{
			g.z.add(v);
		}
		return g;
	}

	@Test
	public void eachFlagReadsAsAPlainSentence()
	{
		assertEquals("The Leviathan: you dealt 8% more than the plugin expected over 54 kills. More than luck explains,"
			+ " so likely a scoring error. (build abc1234)",
			Calibration.sentence(made("boss", "The Leviathan", null, 54, 10800, 10000, 0.7, 0.8)));
		assertEquals("Araxxor's Mirrorback araxytes: you dealt 37% more than the plugin expected over 65 fights. More than"
			+ " luck explains, so likely a scoring error. (build abc1234)",
			Calibration.sentence(made("target", "Araxxor", "Mirrorback Araxyte", 65, 1370, 1000, 2.0, 1.9)));
		assertEquals("Araxxor itself (not its adds): you dealt 12% less than the plugin expected over 40 fights. Less than"
			+ " luck explains, so likely a scoring error. (build abc1234)",
			Calibration.sentence(made("target", "Araxxor", "Araxxor", 40, 880, 1000, -0.6, -0.5)));
		assertEquals("Vardorvis, Soulreaper axe: you dealt slightly more than the plugin expected over 30 fights. More"
			+ " than luck explains, so likely a scoring error. (build abc1234)",
			Calibration.sentence(made("weapon", "Vardorvis", "Soulreaper axe", 30, 1002, 1000, 0.4, 0.5)));
		assertEquals("Acidic araxytes", Calibration.pluralName("Acidic Araxyte"));
		assertEquals("Respiratory systems", Calibration.pluralName("Respiratory system"));
	}

	@Test
	public void weaponsComeFromTheR1Record()
	{
		final List<Fight> fights = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> fights.add(kill("Vardorvis", NpcID.VARDORVIS, 100, null)));
		for (int i = 0; i < 2; i++)
		{
			final Fight f = kill("Vardorvis", NpcID.VARDORVIS, 100, BUILD_A);
			final FightContext.Weapon w = new FightContext.Weapon();
			w.attacks = 10;
			w.damage = 100;
			w.expected = 100;
			w.variance = 200;
			f.context().weapons = new LinkedHashMap<>();
			f.context().weapons.put("Soulreaper axe", w);
			fights.add(f);
		}
		final List<Fight> history = newestFirst(fights);
		final List<Calibration.Group> groups = Calibration.groups(history, null);
		assertEquals(3, group(groups, "boss", "Vardorvis", null).kills);
		assertEquals(2, group(groups, "boss", "Vardorvis", "aaaa111").kills);
		assertEquals(3, group(groups, "weapon", "Vardorvis / " + Calibration.UNKNOWN_WEAPON, null).fights);
		final Calibration.Group axe = group(groups, "weapon", "Vardorvis / Soulreaper axe", "aaaa111");
		assertEquals(2, axe.fights);
		assertEquals(200, axe.actual);
		final String report = Calibration.report(history, null);
		assertTrue(report, report.contains("WEAPONS (recorded builds):\n  weapon Vardorvis / Soulreaper axe on build"
			+ " aaaa111: 2 fights"));
		// The "unknown" row and a boss's only target repeat the boss's line, so the report leaves them out.
		assertFalse(report.contains("/ unknown"));
		assertFalse(report.contains("target Vardorvis / Vardorvis"));
	}

	@Test
	public void aBossWithAddsGetsOneRowPerTargetByFight()
	{
		final List<Fight> fights = new ArrayList<>();
		for (int k = 0; k < 2; k++)
		{
			final Fight boss = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, clock += 1000, null, 0);
			boss.recordAttackMade(false, 1.0);
			boss.recordExpected(60, 0.8, 25, 400);
			boss.recordDamageDealt(30, clock + 1, true);
			boss.end(false, clock + 2);
			final Fight add = new Fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 2, 58, clock + 3, null, 0);
			add.recordAttackMade(false, 1.0);
			add.recordExpected(60, 0.8, 25, 400);
			add.recordDamageDealt(40, clock + 4, true);
			add.end(true, clock + 5);
			final Fight last = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, clock + 6, null, 0);
			last.recordAttackMade(false, 1.0);
			last.recordExpected(60, 0.8, 25, 400);
			last.recordDamageDealt(20, clock + 7, true);
			last.end(true, clock + 8);
			last.closeRoom();
			boss.context().build = BUILD_A;
			fights.addAll(Arrays.asList(boss, add, last));
		}
		final List<Calibration.Group> groups = Calibration.groups(newestFirst(fights), null);
		final Calibration.Group araxxor = group(groups, "boss", "Araxxor", "aaaa111");
		assertEquals(2, araxxor.kills);
		assertEquals(6, araxxor.fights);
		assertEquals(180, araxxor.actual);
		assertEquals(4, group(groups, "target", "Araxxor / Araxxor", "aaaa111").fights);
		assertEquals(80, group(groups, "target", "Araxxor / Acidic Araxyte", "aaaa111").actual);
		final String report = Calibration.report(newestFirst(fights), null);
		assertTrue(report, report.contains("TARGETS (recorded builds, bosses with adds):\n  target Araxxor / Araxxor on"
			+ " build aaaa111: 4 fights"));
	}

	@Test
	public void anAddSavedBeforeItsBossWasGroupedIsAnOlderTargetNotABoss()
	{
		final Fight add = new Fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, 2, 58, 0L, null, 0);
		add.recordAttackMade(false, 1.0);
		add.recordExpected(60, 0.8, 25, 400);
		add.recordDamageDealt(50, 1L, true);
		add.end(true, 2L);
		// As saved before T-091: no group.
		final JsonObject json = GSON.toJsonTree(add).getAsJsonObject();
		json.remove("groupName");
		final Fight old = GSON.fromJson(json, Fight.class);
		assertNull(old.getGroupName());
		assertTrue(Calibration.isOldAdd(old));
		final List<Calibration.Group> groups = Calibration.groups(Collections.singletonList(old), null);
		assertNull(group(groups, "boss", "Mirrorback Araxyte", null));
		final Calibration.Group target = group(groups, "target", "Araxxor / Mirrorback Araxyte", null);
		assertNotNull(target);
		assertTrue(target.older());
		assertEquals(50, target.actual);
		assertFalse(Calibration.isOldAdd(kill("Vardorvis", NpcID.VARDORVIS, 100, null)));
	}
}

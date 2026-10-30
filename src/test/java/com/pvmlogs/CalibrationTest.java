package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/** T-110: the calibration report over a saved history, by boss, target, weapon and build. */
public class CalibrationTest
{
	private static final Gson GSON = new Gson();

	/** A solo kill of ten attacks of 10 expected (variance 20 each): E 100, sd about 14, dealing {@code actual}. */
	private static Fight kill(String name, int id, int actual)
	{
		final Fight f = new Fight(name, id, 1, 700, 0L, null, 0);
		for (int i = 0; i < 10; i++)
		{
			f.recordAttackMade(false, 1.0);
			f.recordExpected(40, 0.5, 10, 20);
		}
		f.recordDamageDealt(actual, 1L, true);
		f.end(true, 2L);
		return f;
	}

	/** Newest first, as the plugin keeps it. */
	private static List<Fight> newestFirst(List<Fight> oldestFirst)
	{
		final List<Fight> list = new ArrayList<>(oldestFirst);
		Collections.reverse(list);
		return list;
	}

	private static Calibration.Group group(List<Calibration.Group> groups, String dimension, String name)
	{
		for (Calibration.Group g : groups)
		{
			if (g.dimension.equals(dimension) && g.name.equals(name))
			{
				return g;
			}
		}
		return null;
	}

	@Test
	public void aBiasedBossIsFlaggedAndAnHonestOneIsNot()
	{
		final List<Fight> fights = new ArrayList<>();
		// Vardorvis: every kill 30 to 52 over its 100 expected, so z about 2 to 3.7: far past three standard errors.
		IntStream.range(0, 12).forEach(i -> fights.add(kill("Vardorvis", NpcID.VARDORVIS, 130 + 2 * i)));
		// Vorkath: as far under as over.
		IntStream.range(0, 12).forEach(i -> fights.add(kill("Vorkath", 8061, i % 2 == 0 ? 80 + i : 120 - i)));
		final List<Fight> history = newestFirst(fights);
		final List<Calibration.Group> groups = Calibration.groups(history, null);
		final Calibration.Group vard = group(groups, "boss", "Vardorvis");
		assertNotNull(vard);
		assertEquals(12, vard.kills);
		assertEquals(12, vard.n());
		assertEquals(1692, vard.actual);
		assertEquals(1200, vard.expected, 1e-6);
		assertEquals(1.41, vard.ratio(), 0.01);
		assertTrue(vard.meanZ() > 2);
		assertTrue(vard.flagged());
		// Every kill sits in the top tenths of luck's percentile.
		final int[] d = vard.deciles();
		assertEquals(12, IntStream.of(d).sum());
		assertEquals(0, IntStream.of(d).limit(9).sum());
		final Calibration.Group vork = group(groups, "boss", "Vorkath");
		assertEquals(12, IntStream.of(vork.deciles()).sum());
		assertEquals(0, vork.meanZ(), 0.1);
		assertFalse(vork.flagged());

		final String report = Calibration.report(history, null);
		final String[] lines = report.split("\n");
		assertTrue(lines[0], lines[0].startsWith("CALIBRATION over 24 saved fights, 24 counted kills"));
		assertEquals("FLAGGED:", lines[1]);
		assertTrue(lines[2], lines[2].startsWith("  boss Vardorvis: 12 kills, 12 fights, A 1692 / E 1200 = 1.41, mean z +"));
		assertEquals("BOSSES:", lines[3]);
		assertTrue(report.contains("  boss Vorkath: 12 kills"));
		assertTrue(lines.length <= Calibration.MAX_LINES);
	}

	@Test
	public void tooFewKillsAreNeverFlagged()
	{
		final List<Fight> fights = new ArrayList<>();
		IntStream.range(0, Calibration.FLAG_MIN - 1).forEach(i -> fights.add(kill("Vardorvis", NpcID.VARDORVIS, 150 + i)));
		final Calibration.Group vard = group(Calibration.groups(newestFirst(fights), null), "boss", "Vardorvis");
		assertTrue(Math.abs(vard.meanZ()) > Calibration.FLAG_SE * vard.standardError());
		assertFalse(vard.flagged());
		assertTrue(Calibration.report(newestFirst(fights), null).contains("FLAGGED: none"));
	}

	@Test
	public void oldFightsGoUnderBeforeR1AndAnUnknownWeapon()
	{
		final List<Fight> fights = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> fights.add(kill("Vardorvis", NpcID.VARDORVIS, 100)));
		for (int i = 0; i < 2; i++)
		{
			final Fight f = kill("Vardorvis", NpcID.VARDORVIS, 100);
			f.context().build = "abc1234 (master, clean)";
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
		assertEquals(3, group(groups, "build", "Vardorvis / " + Calibration.BEFORE_R1).kills);
		assertEquals(2, group(groups, "build", "Vardorvis / abc1234 (master, clean)").kills);
		assertEquals(3, group(groups, "weapon", "Vardorvis / " + Calibration.UNKNOWN_WEAPON).fights);
		final Calibration.Group axe = group(groups, "weapon", "Vardorvis / Soulreaper axe");
		assertEquals(2, axe.fights);
		assertEquals(200, axe.actual);
		final String report = Calibration.report(history, null);
		assertTrue(report.contains("WEAPONS (R1's record):\n  weapon Vardorvis / Soulreaper axe: 2 fights"));
		assertTrue(report.contains("BUILDS (bosses killed on more than one):"));
		assertTrue(report.contains("build Vardorvis / before R1: 3 kills"));
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
			final Fight boss = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0L, null, 0);
			boss.recordAttackMade(false, 1.0);
			boss.recordExpected(60, 0.8, 25, 400);
			boss.recordDamageDealt(30, 1L, true);
			boss.end(false, 2L);
			final Fight add = new Fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 2, 58, 3L, null, 0);
			add.recordAttackMade(false, 1.0);
			add.recordExpected(60, 0.8, 25, 400);
			add.recordDamageDealt(40, 4L, true);
			add.end(true, 5L);
			final Fight last = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 6L, null, 0);
			last.recordAttackMade(false, 1.0);
			last.recordExpected(60, 0.8, 25, 400);
			last.recordDamageDealt(20, 7L, true);
			last.end(true, 8L);
			last.closeRoom();
			fights.add(boss);
			fights.add(add);
			fights.add(last);
		}
		final List<Calibration.Group> groups = Calibration.groups(newestFirst(fights), null);
		final Calibration.Group araxxor = group(groups, "boss", "Araxxor");
		assertEquals(2, araxxor.kills);
		assertEquals(6, araxxor.fights);
		assertEquals(180, araxxor.actual);
		assertEquals(4, group(groups, "target", "Araxxor / Araxxor").fights);
		assertEquals(2, group(groups, "target", "Araxxor / Acidic Araxyte").fights);
		assertEquals(80, group(groups, "target", "Araxxor / Acidic Araxyte").actual);
	}

	@Test
	public void anAddSavedBeforeItsBossWasGroupedIsATargetNotABoss()
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
		assertNull(group(groups, "boss", "Mirrorback Araxyte"));
		final Calibration.Group target = group(groups, "target", "Araxxor / Mirrorback Araxyte");
		assertNotNull(target);
		assertEquals(1, target.fights);
		assertEquals(50, target.actual);
		assertFalse(Calibration.isOldAdd(kill("Vardorvis", NpcID.VARDORVIS, 100)));
	}
}

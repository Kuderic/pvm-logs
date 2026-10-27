package com.pvmlogs;

import static com.pvmlogs.LedgerRows.Colour.BETTER;
import static com.pvmlogs.LedgerRows.Colour.COST;
import static com.pvmlogs.LedgerRows.Colour.DEAD;
import static com.pvmlogs.LedgerRows.Colour.NEUTRAL;
import static com.pvmlogs.LedgerRows.Colour.WARNING;
import static com.pvmlogs.LedgerRows.FontRole.BODY;
import static com.pvmlogs.LedgerRows.FontRole.DETAIL;
import static com.pvmlogs.LedgerRows.FontRole.EMPHASIS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pvmlogs.LedgerExamples.FakeScope;
import com.pvmlogs.LedgerRows.Colour;
import com.pvmlogs.LedgerRows.Row;
import com.pvmlogs.LedgerRows.Settings;
import com.pvmlogs.LedgerRows.State;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The overlay's rows for every state in the spec (claude/specs/overlay-design.md
 * section 4), from the spec's worked examples (section 8): the same rows the
 * design canvas draws.
 */
public class LedgerRowsTest
{
	private static Row r(String left, String right, LedgerRows.FontRole lf, LedgerRows.FontRole rf, Colour lc, Colour rc)
	{
		return new Row(left, right, lf, rf, lc, rc);
	}

	private static Row b(String left, String right)
	{
		return r(left, right, BODY, BODY, NEUTRAL, NEUTRAL);
	}

	private static Row d(String left, String right, Colour rc)
	{
		return r(left, right, DETAIL, DETAIL, NEUTRAL, rc);
	}

	private static Row d(String left, String right)
	{
		return d(left, right, NEUTRAL);
	}

	private static Row title(String left, String clock)
	{
		return r(left, clock, EMPHASIS, BODY, NEUTRAL, NEUTRAL);
	}

	private static Row emph(String left, String right, Colour rc)
	{
		return r(left, right, BODY, EMPHASIS, NEUTRAL, rc);
	}

	private static List<Row> build(LedgerRows.Scope scope, State state)
	{
		return LedgerRows.build(scope, state, new Settings());
	}

	private static void assertRows(List<Row> expected, List<Row> actual)
	{
		assertEquals(String.join("\n", lines(expected)), String.join("\n", lines(actual)));
	}

	private static String[] lines(List<Row> rows)
	{
		return rows.stream().map(Row::toString).toArray(String[]::new);
	}

	// ---- every state ----

	@Test
	public void beforeTheFirstAttack()
	{
		assertRows(Arrays.asList(
			title("Vardorvis", ""),
			b("Max hit", "49"),
			b("Accuracy", "54%"),
			b("Expected DPS", "5.5"),
			d(" spec", "50 max · 71%")),
			build(null, State.preAttack("Vardorvis", LedgerExamples.loadout())));
	}

	@Test
	public void beforeTheFirstAttackHidesUnknownsAndTheSpecRowWithoutASpecWeapon()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.maxHit = 41;
		assertRows(Arrays.asList(title("Duke Sucellus", ""), b("Max hit", "41")),
			build(null, State.preAttack("Duke Sucellus", l)));
	}

	@Test
	public void showLoadoutOffLeavesTheNameAlone()
	{
		final Settings s = new Settings();
		s.showLoadout = false;
		assertRows(Arrays.asList(title("Vardorvis", "")),
			LedgerRows.build(null, State.preAttack("Vardorvis", LedgerExamples.loadout()), s));
	}

	@Test
	public void loadoutOnlyShowsTheLoadoutEvenWithShowLoadoutOff()
	{
		final Settings s = new Settings();
		s.showLoadout = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(),
			State.loadoutOnly("Vardorvis", LedgerExamples.loadout()), s);
		assertEquals(5, rows.size());
		assertEquals(b("Max hit", "49"), rows.get(1));
	}

	@Test
	public void duringTheFight()
	{
		// Eric's fight at 1:13: luck -7.5 s is 10% of the clock, so Cost; uptime's 6 s costs too, stat boost's 1.3 s
		// is under 2% of 73.2 s and stays white.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			emph("Luck", "-8 s", COST),
			emph("Damage / expected", "370 / 418", COST),
			b("DPS", "5.1"),
			b("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" stat boost", "84% (-1 s)"),
			d(" prayer", "100%"),
			d(" gear", "100%")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)));
	}

	@Test
	public void dead()
	{
		// Eric's kill. 688 + thrall 80 - healed 68 = 700, the boss's HP.
		assertRows(Arrays.asList(
			r("Vardorvis (dead)", "2:16", EMPHASIS, BODY, DEAD, NEUTRAL),
			emph("Luck", "-14 s", COST),
			emph("Damage / expected", "688 / 778", COST),
			b("DPS", "5.0"),
			b("Execution", "84%"),
			d(" uptime", "86% (-19 s)", COST),
			d(" stat boost", "85% (-2 s)"),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			b("", ""),
			b("Duration / expected", "2:16 / 2:02"),
			d(" hits / expected", "32 / 30.2"),
			d(" thrall dmg", "80"),
			d(" boss healed", "68"),
			d(" eating", "7 ticks")),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)));
	}

	@Test
	public void endedWithoutAKill()
	{
		assertRows(Arrays.asList(
			title("Vardorvis (ended)", "2:20"),
			emph("Luck", "-15 s", COST),
			emph("Damage / expected", "721 / 812", COST),
			b("DPS", "5.1"),
			b("Execution", "93%"),
			d(" uptime", "94% (-8 s)", COST),
			d(" stat boost", "95% (-1 s)"),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			b("", ""),
			b("Duration / expected", "2:20 / 2:05"),
			d(" hits / expected", "26 / 33.7"),
			d(" burn/poison dmg", "40"),
			d(" boss healed", "71")),
			build(LedgerExamples.ended(), State.fight("Vardorvis", true, false, false)));
	}

	@Test
	public void tripBetweenKills()
	{
		// -13.5 s over 12:40 is 1.8% of the clock: under 2%, so neutral. The stat boost's 17 s is over 2% and costs.
		// The trip keeps no heal (only thrall and DoT were added for it), so there's no "boss healed" row.
		assertRows(Arrays.asList(
			title("Trip · 6 kills", "12:40"),
			emph("Luck", "-14 s", NEUTRAL),
			emph("Damage / expected", "4,120 / 4,201", NEUTRAL),
			b("DPS", "5.4"),
			b("Execution", "92%"),
			d(" uptime", "95% (-38 s)", COST),
			d(" stat boost", "88% (-17 s)", COST),
			d(" prayer", "99% (-3 s)"),
			d(" gear", "99% (-2 s)"),
			b("", ""),
			b("Duration / expected", "12:40 / 12:26"),
			d(" hits / expected", "161 / 158.7"),
			d(" thrall dmg", "290"),
			d(" burn/poison dmg", "60"),
			d(" eating", "11 ticks")),
			build(LedgerExamples.trip(), State.trip(6, false, false)));
	}

	@Test
	public void tripDuringAFightHasNoDetails()
	{
		final List<Row> rows = build(LedgerExamples.trip(), State.trip(6, true, false));
		assertEquals(9, rows.size());
		assertFalse(rows.stream().anyMatch(Row::isBlank));
	}

	@Test
	public void raidBetweenRooms()
	{
		assertRows(Arrays.asList(
			title("ToA · 3 rooms", "9:12"),
			emph("Luck", "+13 s", BETTER),
			emph("Damage / expected", "8,410 / 8,200", BETTER),
			b("DPS", "15.2"),
			b("Execution", "86%"),
			d(" uptime", "90% (-55 s)", COST),
			d(" stat boost", "97% (-6 s)"),
			d(" prayer", "94% (-12 s)", COST),
			d(" gear", "99% (-2 s)"),
			b("", ""),
			b("Duration / expected", "9:12 / 9:25"),
			d(" hits / expected", "212 / 205.3"),
			d(" thrall dmg", "410")),
			build(LedgerExamples.raid(), State.raid("Tombs of Amascut", 3, false, false)));
	}

	@Test
	public void raidTitlesUseTheShortName()
	{
		assertEquals("CoX", LedgerRows.raidShortName("Chambers of Xeric"));
		assertEquals("ToB", LedgerRows.raidShortName("Theatre of Blood"));
		assertEquals("ToA", LedgerRows.raidShortName("Tombs of Amascut"));
		assertEquals("Something new", LedgerRows.raidShortName("Something new"));
		assertEquals(title("CoX · 1 room", "9:12"),
			build(LedgerExamples.raid(), State.raid("Chambers of Xeric", 1, true, false)).get(0));
	}

	@Test
	public void statsUnreliable()
	{
		// The warning takes Luck's place, and damage shows without an expected figure.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL),
			d(" luck hidden until it's fixed", ""),
			emph("Damage", "370", NEUTRAL),
			b("DPS", "5.1"),
			b("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" stat boost", "84% (-1 s)"),
			d(" prayer", "100%"),
			d(" gear", "100%")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true)));
	}

	@Test
	public void statsUnreliableHidesTheExpectedDurationToo()
	{
		final List<Row> rows = build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, true));
		assertTrue(rows.contains(b("Duration / expected", "2:16 / —")));
	}

	@Test
	public void noMonsterData()
	{
		// Nothing to expect from: Luck and Execution read a dash, uptime still counts.
		assertRows(Arrays.asList(
			title("New boss", "0:48"),
			emph("Luck", "—", NEUTRAL),
			emph("Damage", "212", NEUTRAL),
			d(" no monster data yet", ""),
			b("DPS", "4.4"),
			b("Execution", "—"),
			d(" uptime", "95% (-2 s)", COST)),
			build(LedgerExamples.noData(), State.fight("New boss", false, false, false)));
	}

	@Test
	public void scopeFromBeforeT010ShowsOneSetupRow()
	{
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			emph("Luck", "-8 s", COST),
			emph("Damage / expected", "370 / 418", COST),
			b("DPS", "5.1"),
			b("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" setup", "98% (-1 s)")),
			build(LedgerExamples.midFightBeforeT010(), State.fight("Vardorvis", false, false, false)));
	}

	// ---- the loadout ----

	@Test
	public void theLoadoutSitsAtTheBottomWhileFighting()
	{
		// Eric, 2026-10-26: the panel only comes up once the fight does, so the loadout goes under the fight's rows.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			emph("Luck", "-8 s", COST),
			emph("Damage / expected", "370 / 418", COST),
			b("DPS", "5.1"),
			b("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" stat boost", "84% (-1 s)"),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			b("", ""),
			b("Max hit", "49"),
			b("Accuracy", "54%"),
			b("Expected DPS", "5.5"),
			d(" spec", "50 max \u00b7 71%")),
			build(LedgerExamples.midFight(),
				State.fight("Vardorvis", false, false, false).withLoadout(LedgerExamples.loadout())));
	}

	@Test
	public void underTheWarningTheLoadoutSitsBelowIt()
	{
		final List<Row> rows = build(LedgerExamples.midFight(),
			State.fight("Vardorvis", false, false, true).withLoadout(LedgerExamples.loadout()));
		assertEquals(r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL), rows.get(1));
		assertEquals(b("Accuracy", "54%"), rows.get(rows.size() - 3));
	}

	@Test
	public void theDetailsReplaceTheLoadoutOnceTheFightEnds()
	{
		final List<Row> rows = build(LedgerExamples.kill(),
			State.fight("Vardorvis", true, true, false).withLoadout(LedgerExamples.loadout()));
		assertFalse(rows.stream().anyMatch(row -> row.left.equals("Max hit")));
		assertEquals(d(" eating", "7 ticks"), rows.get(rows.size() - 1));
	}

	@Test
	public void aTripOrRaidShowsTheLoadoutOnlyWhileAFightRuns()
	{
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		assertTrue(build(LedgerExamples.trip(), State.trip(6, true, false).withLoadout(l)).contains(b("Max hit", "49")));
		assertFalse(build(LedgerExamples.trip(), State.trip(6, false, false).withLoadout(l)).contains(b("Max hit", "49")));
		assertTrue(build(LedgerExamples.raid(), State.raid("Tombs of Amascut", 3, true, false).withLoadout(l))
			.contains(b("Max hit", "49")));
	}

	@Test
	public void showLoadoutOffLeavesItOutOfTheFight()
	{
		final Settings s = new Settings();
		s.showLoadout = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.midFight(),
			State.fight("Vardorvis", false, false, false).withLoadout(LedgerExamples.loadout()), s);
		assertEquals(d(" gear", "100%"), rows.get(rows.size() - 1));
	}

	@Test
	public void aLoadoutWithNothingKnownAddsNoBlankRow()
	{
		final List<Row> rows = build(LedgerExamples.midFight(),
			State.fight("Vardorvis", false, false, false).withLoadout(new LedgerRows.Loadout()));
		assertEquals(d(" gear", "100%"), rows.get(rows.size() - 1));
	}

	@Test
	public void theWarningShowsOverTheLoadoutBeforeTheFirstAttack()
	{
		// The accuracy is the figure bad boss data gets wrong (Vardorvis read 98% before T-001).
		assertRows(Arrays.asList(
			title("Vardorvis", ""),
			r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL),
			b("Max hit", "49"),
			b("Accuracy", "54%"),
			b("Expected DPS", "5.5"),
			d(" spec", "50 max \u00b7 71%")),
			build(null, State.preAttack("Vardorvis", LedgerExamples.loadout(), true)));
	}

	@Test
	public void andOverLoadoutOnly()
	{
		final List<Row> rows = build(null, State.loadoutOnly("Vardorvis", LedgerExamples.loadout(), true));
		assertEquals(r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL), rows.get(1));
		assertEquals(6, rows.size());
	}

	@Test
	public void withShowLoadoutOffThereIsNothingToWarnAbout()
	{
		final Settings s = new Settings();
		s.showLoadout = false;
		assertRows(Arrays.asList(title("Vardorvis", "")),
			LedgerRows.build(null, State.preAttack("Vardorvis", LedgerExamples.loadout(), true), s));
	}

	// ---- settings ----

	@Test
	public void luckOffHidesLuckAndTheExpectedDuration()
	{
		final Settings s = new Settings();
		s.luck = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false), s);
		assertFalse(rows.stream().anyMatch(row -> row.left.equals("Luck")));
		assertTrue(rows.contains(b("Duration", "2:16")));
		// Damage keeps its colour: it says how the fight is going on its own.
		assertTrue(rows.contains(emph("Damage / expected", "688 / 778", COST)));
	}

	@Test
	public void costingOnlyListsThePartsThatCostTime()
	{
		final Settings s = new Settings();
		s.parts = ExecutionParts.COSTING;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false), s);
		assertEquals(Arrays.asList(b("Execution", "84%"), d(" uptime", "86% (-19 s)", COST)),
			rows.subList(4, rows.size()));
	}

	@Test
	public void costingOnlyWithNothingCostingSaysSo()
	{
		final FakeScope clean = LedgerExamples.kill();
		clean.ticksLost = 0;
		clean.setup(1, 1, 0, 0);
		final Settings s = new Settings();
		s.parts = ExecutionParts.COSTING;
		final List<Row> rows = LedgerRows.build(clean, State.fight("Vardorvis", false, false, false), s);
		assertEquals(d(" no losses", ""), rows.get(rows.size() - 1));
	}

	@Test
	public void partsOffEndsAtExecution()
	{
		final Settings s = new Settings();
		s.parts = ExecutionParts.OFF;
		final List<Row> rows = LedgerRows.build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false), s);
		assertEquals(b("Execution", "90%"), rows.get(rows.size() - 1));
	}

	@Test
	public void detailsOffLeavesTheTopBlockAfterTheKill()
	{
		final Settings s = new Settings();
		s.details = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false), s);
		assertEquals(9, rows.size());
		assertEquals(d(" gear", "100%"), rows.get(8));
	}

	@Test
	public void noDetailsWhileTheFightRuns()
	{
		assertEquals(9, build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false)).size());
	}

	// ---- section 8's numbers ----

	@Test
	public void numbersMatchTheSpecsArithmetic()
	{
		final LedgerRows.Numbers kill = new LedgerRows.Numbers(LedgerExamples.kill());
		assertEquals(136.8, kill.clock, 1e-9);
		// 136.8 x (688 - 778) / (778 + 80)
		assertEquals(-14.35, kill.luck, 0.01);
		assertEquals(122.45, kill.expectedDuration, 0.01);
		assertEquals(5.03, kill.dps, 0.01);
		// 86% x 0.98 = 84%
		assertEquals(0.8424, kill.execution, 0.0005);
		assertEquals(19.2, kill.uptimeCost, 1e-9);
		assertEquals(2.352, kill.setupCost, 1e-9);

		assertEquals(-7.54, new LedgerRows.Numbers(LedgerExamples.midFight()).luck, 0.01);
		assertEquals(-15.0, new LedgerRows.Numbers(LedgerExamples.ended()).luck, 0.01);
		assertEquals(-13.53, new LedgerRows.Numbers(LedgerExamples.trip()).luck, 0.01);
		assertEquals(13.46, new LedgerRows.Numbers(LedgerExamples.raid()).luck, 0.01);
		assertTrue(Double.isNaN(new LedgerRows.Numbers(LedgerExamples.noData()).luck));
	}

	@Test
	public void setupSecondsSplitWithPrayerTakingTheRest()
	{
		final LedgerRows.Numbers raid = new LedgerRows.Numbers(LedgerExamples.raid());
		assertEquals(19.872, raid.setupCost, 1e-9);
		assertEquals(6, raid.boostCost, 1e-9);
		assertEquals(2, raid.gearCost, 1e-9);
		assertEquals(11.872, raid.prayerCost, 1e-9);
	}

	@Test
	public void boostAndGearCostsOverTheSetupLossAreScaledToFitAndPrayerGetsNothing()
	{
		final FakeScope s = LedgerExamples.kill();
		s.efficiency = 0.98;
		s.boostCostShare = 0.03;
		s.switchCostShare = 0.01;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertEquals(n.setupCost * 0.75, n.boostCost, 1e-9);
		assertEquals(n.setupCost * 0.25, n.gearCost, 1e-9);
		assertEquals(0, n.prayerCost, 1e-9);
	}

	@Test
	public void luckIsUnknownWithoutAClockOrAnExpectedSide()
	{
		final FakeScope s = LedgerExamples.kill();
		s.combatTicks = 0;
		assertTrue(Double.isNaN(new LedgerRows.Numbers(s).luck));
		assertTrue(Double.isNaN(new LedgerRows.Numbers(s).dps));
		final FakeScope t = LedgerExamples.kill();
		t.expectedDamage = 0;
		assertTrue(Double.isNaN(new LedgerRows.Numbers(t).luck));
	}

	// ---- thresholds ----

	@Test
	public void luckColourNeedsASecondAndTwoPercentOfTheClock()
	{
		// Short fight: the second is the bar.
		assertEquals(NEUTRAL, LedgerRows.luckColour(0.99, 20));
		assertEquals(BETTER, LedgerRows.luckColour(1.0, 20));
		assertEquals(COST, LedgerRows.luckColour(-1.0, 20));
		// Long fight: 2% of 100 s is 2 s.
		assertEquals(NEUTRAL, LedgerRows.luckColour(1.99, 100));
		assertEquals(BETTER, LedgerRows.luckColour(2.0, 100));
		assertEquals(NEUTRAL, LedgerRows.luckColour(-1.99, 100));
		assertEquals(COST, LedgerRows.luckColour(-2.0, 100));
		assertEquals(NEUTRAL, LedgerRows.luckColour(Double.NaN, 100));
	}

	@Test
	public void aPartCostsAtASecondAndTwoPercentOfTheClock()
	{
		assertFalse(LedgerRows.isCosting(0.99, 10));
		assertTrue(LedgerRows.isCosting(1.0, 10));
		// Eric's kill: uptime's 19.2 s is over 2% of 136.8 s (2.7 s), stat boost's 2.4 s isn't.
		assertTrue(LedgerRows.isCosting(19.2, 136.8));
		assertFalse(LedgerRows.isCosting(2.352, 136.8));
		assertFalse(LedgerRows.isCosting(Double.NaN, 10));
	}

	@Test
	public void percentileBandsAreSixtyAndForty()
	{
		assertEquals(BETTER, LedgerRows.percentileColour(0.60));
		assertEquals(NEUTRAL, LedgerRows.percentileColour(0.59));
		assertEquals(NEUTRAL, LedgerRows.percentileColour(0.41));
		assertEquals(COST, LedgerRows.percentileColour(0.40));
	}

	// ---- formats ----

	@Test
	public void partValueAddsSecondsOnlyFromOne()
	{
		assertEquals("86% (-19 s)", LedgerRows.partValue(0.8596, 19.2));
		assertEquals("85% (-2 s)", LedgerRows.partValue(0.85, 2.352));
		assertEquals("86% (-1 s)", LedgerRows.partValue(0.86, 0.5));
		assertEquals("86%", LedgerRows.partValue(0.86, 0.49));
		assertEquals("100%", LedgerRows.partValue(1, 0));
	}

	@Test
	public void aPartNeverRoundsUpToAHundredUnlessItIsOne()
	{
		// Orchestrator, PR #14 review: a part that cost something mustn't read as perfect.
		assertEquals("99%", LedgerRows.partPercent(0.996));
		assertEquals("99%", LedgerRows.partPercent(0.9949));
		assertEquals("100%", LedgerRows.partPercent(1));
		assertEquals("50%", LedgerRows.partPercent(0.5));
		assertEquals("0%", LedgerRows.partPercent(0));
		assertEquals("99% (-1 s)", LedgerRows.partValue(0.997, 0.6));
		// Prayed 279 of 280: 99.6%.
		final FakeScope s = LedgerExamples.trip();
		s.attacksMade = 280;
		s.attacksPrayed = 279;
		assertTrue(build(s, State.trip(6, true, false)).contains(d(" prayer", "99% (-3 s)")));
	}

	@Test
	public void executionNeverRoundsUpToAHundredEither()
	{
		// Eric, 2026-10-26: one tick lost in 300 is 99.7% uptime, so Execution reads 99%, not 100%.
		final FakeScope s = LedgerExamples.kill();
		s.combatTicks = 300;
		s.ticksLost = 1;
		s.setup(1, 1, 0, 0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(b("Execution", "99%")));
		final FakeScope clean = LedgerExamples.kill();
		clean.ticksLost = 0;
		clean.setup(1, 1, 0, 0);
		assertTrue(build(clean, State.fight("Vardorvis", false, false, false)).contains(b("Execution", "100%")));
	}

	@Test
	public void secondsRoundHalfAwayFromZeroAndNeverReadMinusZero()
	{
		assertEquals("+14 s", LedgerRows.seconds(14.4));
		assertEquals("-14 s", LedgerRows.seconds(-14.35));
		assertEquals("-8 s", LedgerRows.seconds(-7.54));
		assertEquals("+13 s", LedgerRows.seconds(13.46));
		assertEquals("+3 s", LedgerRows.seconds(2.5));
		assertEquals("-3 s", LedgerRows.seconds(-2.5));
		assertEquals("+1 s", LedgerRows.seconds(0.5));
		assertEquals("-1 s", LedgerRows.seconds(-0.5));
		assertEquals("0 s", LedgerRows.seconds(0.49));
		assertEquals("0 s", LedgerRows.seconds(-0.49));
		assertEquals("0 s", LedgerRows.seconds(-0.0));
		assertEquals("0 s", LedgerRows.seconds(0));
	}

	@Test
	public void clocks()
	{
		// Whole seconds, counted up, while fighting and once ended alike: no tenths.
		assertEquals("1:13", LedgerRows.clock(122));
		assertEquals("0:00", LedgerRows.clock(0));
		assertEquals("0:00", LedgerRows.clock(1));
		assertEquals("0:01", LedgerRows.clock(2));
		assertEquals("2:16", LedgerRows.clock(228));
		assertEquals("2:01", LedgerRows.clock(202));
		// m:ss, h:mm:ss past an hour.
		assertEquals("12:40", LedgerRows.clock(1267));
		assertEquals("59:59", LedgerRows.clock(5999));
		assertEquals("1:00:00", LedgerRows.clock(6000));
		assertEquals("1:05:03", LedgerRows.clock(6505));
		// Expected times: whole seconds, cut down like the clock.
		assertEquals("2:02", LedgerRows.duration(122.45));
		assertEquals("12:26", LedgerRows.duration(746.68));
		assertEquals("0:00", LedgerRows.duration(-3));
	}

	@Test
	public void titleAndClockPerState()
	{
		assertEquals(title("Vardorvis", "1:13"),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)).get(0));
		assertEquals(r("Vardorvis (dead)", "2:16", EMPHASIS, BODY, DEAD, NEUTRAL),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)).get(0));
		assertEquals(title("Vardorvis (ended)", "2:16"),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, false, false)).get(0));
		// Not dead while it runs, whatever the room says.
		assertEquals(title("Vardorvis", "2:16"),
			build(LedgerExamples.kill(), State.fight("Vardorvis", false, true, false)).get(0));
		assertEquals(title("Trip · 1 kill", "12:40"),
			build(LedgerExamples.trip(), State.trip(1, true, false)).get(0));
		assertEquals(title("Trip · 0 kills", "12:40"),
			build(LedgerExamples.trip(), State.trip(0, false, false)).get(0));
		assertEquals(title("Vardorvis", ""), build(null, State.preAttack("Vardorvis", null)).get(0));
	}

	@Test
	public void damageUsesThousandsSeparatorsAndOneOnEach()
	{
		final FakeScope s = LedgerExamples.trip();
		s.expectedDamage = 4200.5;
		assertTrue(build(s, State.trip(6, true, false)).contains(emph("Damage / expected", "4,120 / 4,201", NEUTRAL)));
	}

	@Test
	public void eatingCountsOneTickSingular()
	{
		final FakeScope s = LedgerExamples.kill();
		s.ticksLostEating = 1;
		assertTrue(build(s, State.fight("Vardorvis", true, true, false)).contains(d(" eating", "1 tick")));
	}

	@Test
	public void noScopeNoRows()
	{
		assertTrue(build(null, State.fight("Vardorvis", false, false, false)).isEmpty());
	}

	@Test
	public void unknownGlyphIsTheEmDash()
	{
		assertEquals("—", LedgerRows.UNKNOWN);
	}
}

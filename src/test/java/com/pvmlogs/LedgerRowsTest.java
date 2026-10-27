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
import static org.junit.Assert.assertNull;
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
		return r(left, clock, EMPHASIS, EMPHASIS, NEUTRAL, NEUTRAL);
	}

	/** Luck and Damage: bold label, bold value (Eric, 2026-10-26: the main rows' labels are bold). */
	private static Row emph(String left, String right, Colour rc)
	{
		return r(left, right, EMPHASIS, EMPHASIS, NEUTRAL, rc);
	}

	/** DPS, Execution and the loadout's attack: bold label, bold value (Eric, 2026-10-26). */
	private static Row main(String left, String right)
	{
		return r(left, right, EMPHASIS, EMPHASIS, NEUTRAL, NEUTRAL);
	}

	/** The loadout's main row: the attack's name and its expected DPS beside it. */
	private static final Row TENTACLE = attack("Abyssal tentacle", "5.5 DPS", "Abyssal tentacle");

	/** "Damage/expected", which falls back to "Damage" on a panel too narrow for it. */
	private static Row damage(String figures, Colour rc)
	{
		return new Row("Damage/expected", figures, EMPHASIS, EMPHASIS, NEUTRAL, rc, "Damage");
	}

	/** The loadout's main row: the attack's name, its DPS, and the short form the overlay may fall back to. */
	private static Row attack(String name, String dps, String shortName)
	{
		return new Row(name, dps, EMPHASIS, EMPHASIS, NEUTRAL, NEUTRAL, shortName);
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
	public void beforeTheFirstAttackTheLedgerIsItsTitleAlone()
	{
		// T-022: the loadout overlay carries the figures.
		assertRows(Arrays.asList(title("Vardorvis", "")), build(null, State.preAttack("Vardorvis")));
	}

	@Test
	public void theLoadoutOverlaysRows()
	{
		assertRows(Arrays.asList(
			TENTACLE,
			d(" max hit", "49"),
			d(" accuracy", "54%"),
			d(" spec", "50 max · 71%"),
			d(" spec damage", "17.8")),
			LedgerRows.loadout(LedgerExamples.loadout(), false));
	}

	@Test
	public void theLoadoutHidesUnknownsAndTheSpecRowWithoutASpecWeapon()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.maxHit = 41;
		assertRows(Arrays.asList(main("Loadout", ""), d(" max hit", "41")), LedgerRows.loadout(l, false));
	}

	@Test
	public void duringTheFight()
	{
		// Eric's fight at 1:13: luck -7.5 s is 10% of the clock, so Cost; uptime's 6 s costs too, stat boost's 1.3 s
		// is under 2% of 73.2 s and stays white.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			emph("Luck", "8 s slower", COST),
			damage("370/418", COST),
			main("DPS", "5.1"),
			main("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "84% (-1 s)"),
			d(" damage taken", "112")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)));
	}

	@Test
	public void dead()
	{
		// Eric's kill. 688 + thrall 80 - healed 68 = 700, the boss's HP.
		assertRows(Arrays.asList(
			r("Vardorvis (dead)", "2:16", EMPHASIS, EMPHASIS, DEAD, NEUTRAL),
			emph("Luck", "14 s slower", COST),
			damage("688/778", COST),
			main("DPS", "5.0"),
			main("Execution", "84%"),
			d(" uptime", "86% (-19 s)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "85% (-2 s)"),
			d(" damage taken", "248"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "32/30.2"),
			d(" thrall dmg", "80"),
			d(" boss healed", "68"),
			d(" eating time", "4 s")),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)));
	}

	@Test
	public void endedWithoutAKill()
	{
		assertRows(Arrays.asList(
			title("Vardorvis (ended)", "2:20"),
			emph("Luck", "15 s slower", COST),
			damage("721/812", COST),
			main("DPS", "5.1"),
			main("Execution", "93%"),
			d(" uptime", "94% (-8 s)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "95% (-1 s)"),
			d(" damage taken", "310"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "26/33.7"),
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
			emph("Luck", "14 s slower", NEUTRAL),
			damage("4,120/4,201", NEUTRAL),
			main("DPS", "5.4"),
			main("Execution", "92%"),
			d(" uptime", "95% (-38 s)", COST),
			d(" prayer", "99% (-3 s)"),
			d(" gear", "99% (-2 s)"),
			d(" stat boost", "88% (-17 s)", COST),
			d(" damage taken", "1,404"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "161/158.7"),
			d(" thrall dmg", "290"),
			d(" burn/poison dmg", "60"),
			d(" eating time", "7 s")),
			build(LedgerExamples.trip(), State.trip(6, false, false)));
	}

	@Test
	public void tripDuringAFightHasNoDetails()
	{
		final List<Row> rows = build(LedgerExamples.trip(), State.trip(6, true, false));
		assertEquals(10, rows.size());
		assertFalse(rows.stream().anyMatch(Row::isBlank));
	}

	@Test
	public void raidBetweenRooms()
	{
		assertRows(Arrays.asList(
			title("ToA · 3 rooms", "9:12"),
			emph("Luck", "13 s faster", BETTER),
			damage("8,410/8,200", BETTER),
			main("DPS", "15.2"),
			main("Execution", "86%"),
			d(" uptime", "90% (-55 s)", COST),
			d(" prayer", "98% (-12 s)", COST),
			d(" gear", "99% (-2 s)"),
			d(" stat boost", "97% (-6 s)"),
			d(" damage taken", "980"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "212/205.3"),
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
			main("DPS", "5.1"),
			main("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "84% (-1 s)"),
			d(" damage taken", "112")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true)));
	}

	@Test
	public void theDetailsHaveNoDurationRow()
	{
		// Eric, 2026-10-26: the clock is in the title and Luck right under it, so "Duration / expected" only repeated them.
		final List<Row> rows = build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false));
		assertFalse(rows.stream().anyMatch(row -> row.left.startsWith("Duration")));
		assertEquals(b("", ""), rows.get(10));
		assertEquals(main("Summary", ""), rows.get(11));
		assertEquals(d(" hits/expected", "32/30.2"), rows.get(12));
	}

	// ---- gear (Eric, 2026-10-26) ----

	@Test
	public void gearIsTheDamageShareOfTheBestGearOnHandNotACountOfAttacks()
	{
		// Blood fury worn over a carried torture: 46 of 49 attacks could have switched, which read "6%". What it cost was
		// 2 s of a 2:16 kill, about 1.7% of the damage the setup could have dealt.
		final FakeScope s = LedgerExamples.kill();
		s.attacksSwitched = 3;
		s.setup(0.98, 0.85, 0.352, 2.0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" gear", "98% (-2 s)")));
	}

	@Test
	public void gearReadsAHundredOnlyWithNothingLost()
	{
		final FakeScope s = LedgerExamples.kill();
		s.attacksSwitched = 0;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" gear", "100%")));
	}

	// ---- drained (T-016, Eric's option A) ----

	@Test
	public void mostlyBelowBaseReadsDrained()
	{
		// 25 of 49 attacks below base: more than half. The seconds still carry the cost.
		final FakeScope s = LedgerExamples.kill();
		s.attacksDrained = 25;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" stat boost", "drained (-2 s)")));
	}

	@Test
	public void exactlyHalfDrainedKeepsThePercentage()
	{
		final FakeScope s = LedgerExamples.kill();
		s.attacksMade = 50;
		s.attacksPrayed = 50;
		s.attacksSwitched = 50;
		s.attacksDrained = 25;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" stat boost", "85% (-2 s)")));
	}

	@Test
	public void drainedWithUnderASecondOfCostIsTheWordAloneAndNeutral()
	{
		final FakeScope s = LedgerExamples.kill();
		s.attacksDrained = 40;
		s.setup(0.998, 0.0, 0.2352, 0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" stat boost", "drained")));
	}

	@Test
	public void drainedAndCostingIsInCostColour()
	{
		// Brewed down most of the fight at 1:13: 3.4 s is over 2% of 73.2 s.
		final FakeScope s = LedgerExamples.midFight();
		s.attacksDrained = 12;
		s.setup(0.95, 0.0, 3.36, 0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false))
			.contains(d(" stat boost", "drained (-3 s)", COST)));
	}

	@Test
	public void drainedLeavesTheSetupRowAlone()
	{
		final FakeScope s = LedgerExamples.midFightBeforeT010();
		s.attacksDrained = 18;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" setup", "98% (-1 s)")));
	}

	/** Ten attacks, six of them below base, each set up for 90% of what it could have been and all of that the boost. */
	private static Fight drainedFight()
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		for (int i = 0; i < 10; i++)
		{
			f.recordAttackMade(false, 0.0, i < 6);
			f.recordAttackResolved(true, true, 9, 10, 1, 0);
			f.recordExpected(40, 0.5, 10);
		}
		f.recordDamageDealt(100, 1L, true);
		return f;
	}

	@Test
	public void roomRaidAndTripScopesAllCarryTheDrainCount()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(drainedFight());
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		final Encounter raidRoom = new Encounter("Vardorvis", RaidType.TOMBS_OF_AMASCUT, 0L);
		raidRoom.add(drainedFight());
		raid.add(raidRoom);
		final SessionTotals trip = new SessionTotals(0L);
		for (int i = 0; i < 10; i++)
		{
			trip.recordAttackMade(false, 0.0, i < 6);
			trip.recordAttackResolved(true, true, 9, 10, 1, 0);
			trip.recordExpected(0.5, 10);
		}
		trip.recordAttempt(100, true, 1L);
		// 10 ticks is 6 s; the boost cost 10% of the setup, 0.6 s, which shows as a second.
		final Row drained = d(" stat boost", "drained (-1 s)");
		assertEquals(6, LedgerRows.of(room).attacksDrained());
		assertTrue(build(LedgerRows.of(room), State.fight("Vardorvis", false, false, false)).contains(drained));
		assertEquals(6, LedgerRows.of(raid).attacksDrained());
		assertTrue(build(LedgerRows.of(raid), State.raid("Tombs of Amascut", 1, true, false)).contains(drained));
		assertEquals(6, LedgerRows.of(trip).attacksDrained());
		assertTrue(build(LedgerRows.of(trip), State.trip(0, true, false)).contains(drained));
	}

	// ---- your average (Eric, 2026-10-26) ----

	private static List<Row> deadWith(LedgerHistory.Average average)
	{
		return build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false).withAverage(average));
	}

	/** Your average: the clock's median and quartiles. */
	private static LedgerHistory.Average average(double clock, double clockLow, double clockHigh)
	{
		return new LedgerHistory.Average(new LedgerHistory.Spread(clock, clockLow, clockHigh), 12);
	}

	@Test
	public void yourAverageHeadsTheSummary()
	{
		// Eric, 2026-10-26: " vs your average 1:59" made no sense as a heading; 2026-10-27: no " this kill",
		// " luck removed" or " luckier than" with it. The median clock, cut to whole seconds like the title's.
		final List<Row> rows = deadWith(average(119.9, 115, 125));
		assertEquals(b("", ""), rows.get(10));
		assertEquals(main("Summary", ""), rows.get(11));
		assertEquals(d(" your average", "1:59"), rows.get(12));
		assertEquals(d(" hits/expected", "32/30.2"), rows.get(13));
		assertFalse(rows.stream().anyMatch(row -> row.left.equals(" this kill") || row.left.equals(" luck removed")
			|| row.left.equals(" luckier than")));
	}

	@Test
	public void theAverageOnlyFollowsAKill()
	{
		final LedgerHistory.Average a = average(119, 115, 125);
		final List<Row> ended = build(LedgerExamples.kill(), State.fight("Vardorvis", true, false, false).withAverage(a));
		final List<Row> warned = build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, true).withAverage(a));
		final List<Row> running = build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false).withAverage(a));
		for (List<Row> rows : Arrays.asList(ended, warned, running, deadWith(null)))
		{
			assertFalse(rows.stream().anyMatch(row -> row.left.equals(" your average")));
		}
	}

	@Test
	public void noDetailsToShowAddsNoBlankRow()
	{
		final FakeScope s = LedgerExamples.noData();
		final List<Row> rows = build(s, State.fight("New boss", true, true, false));
		assertEquals(d(" damage taken", "36"), rows.get(rows.size() - 1));
		assertEquals(d(" uptime", "95% (-2 s)", COST), rows.get(rows.size() - 2));
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
			main("DPS", "4.4"),
			main("Execution", "—"),
			d(" uptime", "95% (-2 s)", COST),
			d(" damage taken", "36")),
			build(LedgerExamples.noData(), State.fight("New boss", false, false, false)));
	}

	@Test
	public void scopeFromBeforeT010ShowsOneSetupRow()
	{
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			emph("Luck", "8 s slower", COST),
			damage("370/418", COST),
			main("DPS", "5.1"),
			main("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" setup", "98% (-1 s)"),
			d(" damage taken", "112")),
			build(LedgerExamples.midFightBeforeT010(), State.fight("Vardorvis", false, false, false)));
	}

	// ---- the loadout ----

	@Test
	public void theLoadoutSitsAtTheBottomWhileFighting()
	{
		// T-022: no loadout under the fight's rows; it has an overlay of its own.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			emph("Luck", "8 s slower", COST),
			damage("370/418", COST),
			main("DPS", "5.1"),
			main("Execution", "90%"),
			d(" uptime", "92% (-6 s)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "84% (-1 s)"),
			d(" damage taken", "112")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)));
	}

	@Test
	public void theLedgerKeepsItsOwnWarning()
	{
		final List<Row> rows = build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true));
		assertEquals(r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL), rows.get(1));
		assertEquals(d(" damage taken", "112"), rows.get(rows.size() - 1));
	}

	@Test
	public void noLedgerStateHasLoadoutRows()
	{
		// T-022: the loadout moved to its own overlay, in every state.
		final List<List<Row>> states = Arrays.asList(
			build(null, State.preAttack("Vardorvis")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true)),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)),
			build(LedgerExamples.ended(), State.fight("Vardorvis", true, false, false)),
			build(LedgerExamples.trip(), State.trip(6, true, false)),
			build(LedgerExamples.trip(), State.trip(6, false, false)),
			build(LedgerExamples.raid(), State.raid("Tombs of Amascut", 3, true, false)),
			build(LedgerExamples.raid(), State.raid("Tombs of Amascut", 3, false, false)));
		for (List<Row> rows : states)
		{
			assertFalse(rows.stream().anyMatch(row -> row.left.equals(TENTACLE.left) || row.left.equals("Loadout")
				|| row.left.equals(" max hit") || row.left.equals(" accuracy") || row.left.equals(" spec")));
		}
	}

	@Test
	public void theExpectedDpsSitsBesideTheAttackWithTheSpecsExpectedDamageUnder()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Purging staff (Fire Surge)";
		l.dps = 6.1;
		assertEquals(Arrays.asList(attack("Purging staff (Fire Surge)", "6.1 DPS", "Fire Surge")),
			LedgerRows.loadout(l, false));
		final LedgerRows.Loadout claws = new LedgerRows.Loadout();
		claws.attack = "Burning claws";
		claws.maxHit = 41;
		claws.specMaxHit = 110;
		claws.specAccuracy = 0.62;
		claws.specAverage = 33.4;
		assertTrue(LedgerRows.loadout(claws, false).containsAll(Arrays.asList(d(" spec", "110 max \u00b7 62%"),
			d(" spec damage", "33.4"))));
	}

	@Test
	public void prayerIsTheDamageShareLikeGear()
	{
		// Eric, 2026-10-26: Chivalry up all fight with Piety unlocked counted as "0%" prayed. Now it's the damage the
		// setup kept against the best prayer, the remainder of the loss after boost and gear: here all 2% of it.
		final FakeScope s = LedgerExamples.kill();
		s.attacksPrayed = 0;
		s.setup(0.98, 1.0, 0, 0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" prayer", "98% (-2 s)")));
	}

	@Test
	public void damageTakenIsTheLastRowUnderExecution()
	{
		// Eric, 2026-10-26: under Execution, the damage you took.
		final List<Row> rows = build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false));
		assertEquals(d(" stat boost", "85% (-2 s)"), rows.get(8));
		assertEquals(d(" damage taken", "248"), rows.get(9));
		final Settings off = new Settings();
		off.parts = ExecutionParts.OFF;
		assertFalse(LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false), off).stream()
			.anyMatch(row -> row.left.equals(" damage taken")));
	}

	@Test
	public void theRaidAndTripAddUpDamageTaken()
	{
		final Fight f = new Fight("Zebak", NpcIds.GOBLIN, 1, 700, 0L, RaidType.TOMBS_OF_AMASCUT, 1);
		f.recordAttackMade(false, 1.0);
		f.recordDamageTaken(31, 1L);
		f.recordDamageTaken(12, 2L);
		final Encounter room = new Encounter("Zebak", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(f);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(43, LedgerRows.of(raid).damageTaken());
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordDamageTaken(31);
		trip.recordDamageTaken(-5);
		assertEquals(31, LedgerRows.of(trip).damageTaken());
		trip.reset(1L);
		assertEquals(0, trip.getDamageTaken());
	}

	@Test
	public void thePartsAreInEricsOrder()
	{
		final List<Row> rows = build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false));
		assertEquals(Arrays.asList(" uptime", " prayer", " gear", " stat boost"),
			Arrays.asList(rows.get(5).left, rows.get(6).left, rows.get(7).left, rows.get(8).left));
	}

	@Test
	public void aLoadoutWithNothingKnownShowsNothingNotEvenTheWarning()
	{
		assertTrue(LedgerRows.loadout(new LedgerRows.Loadout(), true).isEmpty());
	}

	@Test
	public void theWarningHeadsTheLoadoutOverlay()
	{
		// The accuracy is the figure bad boss data gets wrong (Vardorvis read 98% before T-001).
		assertRows(Arrays.asList(
			r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL),
			TENTACLE,
			d(" max hit", "49"),
			d(" accuracy", "54%"),
			d(" spec", "50 max \u00b7 71%"),
			d(" spec damage", "17.8")),
			LedgerRows.loadout(LedgerExamples.loadout(), true));
	}

	// ---- settings ----

	@Test
	public void luckOffHidesLuck()
	{
		final Settings s = new Settings();
		s.luck = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false), s);
		assertFalse(rows.stream().anyMatch(row -> row.left.equals("Luck")));
		// Damage keeps its colour: it says how the fight is going on its own.
		assertTrue(rows.contains(damage("688/778", COST)));
	}

	@Test
	public void costingOnlyListsThePartsThatCostTime()
	{
		final Settings s = new Settings();
		s.parts = ExecutionParts.COSTING;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false), s);
		assertEquals(Arrays.asList(main("Execution", "84%"), d(" uptime", "86% (-19 s)", COST), d(" damage taken", "248")),
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
		assertEquals(d(" no losses", ""), rows.get(rows.size() - 2));
		assertEquals(d(" damage taken", "248"), rows.get(rows.size() - 1));
	}

	@Test
	public void partsOffEndsAtExecution()
	{
		final Settings s = new Settings();
		s.parts = ExecutionParts.OFF;
		final List<Row> rows = LedgerRows.build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false), s);
		assertEquals(main("Execution", "90%"), rows.get(rows.size() - 1));
	}

	@Test
	public void detailsOffLeavesTheTopBlockAfterTheKill()
	{
		final Settings s = new Settings();
		s.details = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false), s);
		assertEquals(10, rows.size());
		assertEquals(d(" stat boost", "85% (-2 s)"), rows.get(8));
		assertEquals(d(" damage taken", "248"), rows.get(9));
	}

	@Test
	public void noDetailsWhileTheFightRuns()
	{
		assertEquals(10, build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false)).size());
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
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(main("Execution", "99%")));
		final FakeScope clean = LedgerExamples.kill();
		clean.ticksLost = 0;
		clean.setup(1, 1, 0, 0);
		assertTrue(build(clean, State.fight("Vardorvis", false, false, false)).contains(main("Execution", "100%")));
	}

	@Test
	public void luckAndTheAverageReadInWordsNotSigns()
	{
		// Eric, 2026-10-26: "+10 s" read as ten seconds added to the kill. Positive is time saved.
		assertEquals("14 s faster", LedgerRows.fasterOrSlower(14.4));
		assertEquals("14 s slower", LedgerRows.fasterOrSlower(-14.35));
		assertEquals("8 s slower", LedgerRows.fasterOrSlower(-7.54));
		assertEquals("13 s faster", LedgerRows.fasterOrSlower(13.46));
		// Half away from zero.
		assertEquals("3 s faster", LedgerRows.fasterOrSlower(2.5));
		assertEquals("3 s slower", LedgerRows.fasterOrSlower(-2.5));
		assertEquals("1 s faster", LedgerRows.fasterOrSlower(0.5));
		assertEquals("1 s slower", LedgerRows.fasterOrSlower(-0.5));
		assertEquals("even", LedgerRows.fasterOrSlower(0.49));
		assertEquals("even", LedgerRows.fasterOrSlower(-0.49));
		assertEquals("even", LedgerRows.fasterOrSlower(-0.0));
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
		assertEquals(r("Vardorvis (dead)", "2:16", EMPHASIS, EMPHASIS, DEAD, NEUTRAL),
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
		assertEquals(title("Vardorvis", ""), build(null, State.preAttack("Vardorvis")).get(0));
	}

	@Test
	public void damageUsesThousandsSeparatorsAndOneOnEach()
	{
		final FakeScope s = LedgerExamples.trip();
		s.expectedDamage = 4200.5;
		assertTrue(build(s, State.trip(6, true, false)).contains(damage("4,120/4,201", NEUTRAL)));
	}

	@Test
	public void eatingTimeIsInSeconds()
	{
		// Eric, 2026-10-27: in seconds, like everything else. One tick is 0.6 s, rounded half away from zero.
		final FakeScope s = LedgerExamples.kill();
		s.ticksLostEating = 1;
		assertTrue(build(s, State.fight("Vardorvis", true, true, false)).contains(d(" eating time", "1 s")));
		s.ticksLostEating = 7;
		assertTrue(build(s, State.fight("Vardorvis", true, true, false)).contains(d(" eating time", "4 s")));
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

	// ---- the attack's name (Eric, 2026-10-26: "Abyssal tentacle (or)" made the panel wrap) ----

	@Test
	public void theAttacksNameDropsTheItemsVariantTag()
	{
		assertEquals("Abyssal tentacle", LedgerRows.attackLabel("Abyssal tentacle (or)"));
		assertEquals("Bow of faerdhinen", LedgerRows.attackLabel("Bow of faerdhinen (c)"));
		assertEquals("Dragon dagger", LedgerRows.attackLabel("Dragon dagger (p++)"));
		assertEquals("Trident of the swamp", LedgerRows.attackLabel("Trident of the swamp (e)"));
		// A spell is capitalised, so it stays; a variant tag before it still goes.
		assertEquals("Purging staff (Fire Surge)", LedgerRows.attackLabel("Purging staff (Fire Surge)"));
		assertEquals("Kodai wand (Ice Barrage)", LedgerRows.attackLabel("Kodai wand (or) (Ice Barrage)"));
		assertEquals("Twisted bow", LedgerRows.attackLabel("Twisted bow"));
		assertNull(LedgerRows.attackLabel(null));
		assertNull(LedgerRows.attackLabel(" (or)"));
	}

	@Test
	public void theLoadoutRowShowsTheCleanName()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Abyssal tentacle (or)";
		l.dps = 5.5;
		assertEquals(Arrays.asList(TENTACLE), LedgerRows.loadout(l, false));
	}

	@Test
	public void theShortFormOfAStaffAndSpellIsTheSpell()
	{
		assertEquals("Fire Surge", LedgerRows.spellOr("Harmonised nightmare staff (Fire Surge)"));
		assertEquals("Sanguine scythe of vitur", LedgerRows.spellOr("Sanguine scythe of vitur"));
	}

	@Test
	public void aNameIsCutWithAnEllipsisUntilItFits()
	{
		// 7 px a character, the ellipsis included.
		final java.util.function.ToIntFunction<String> width = t -> t.length() * 7;
		assertEquals("Sanguine scythe of vitur", LedgerRows.cut("Sanguine scythe of vitur", width, 168));
		assertEquals("Sanguine scythe\u2026", LedgerRows.cut("Sanguine scythe of vitur", width, 112));
		// No space left before the ellipsis.
		assertEquals("Sanguine\u2026", LedgerRows.cut("Sanguine scythe of vitur", width, 69));
		assertEquals("\u2026", LedgerRows.cut("Sanguine scythe of vitur", width, 7));
		assertEquals("", LedgerRows.cut("Sanguine scythe of vitur", width, 6));
	}

	// ---- Summary (Eric, 2026-10-26) ----

	@Test
	public void theDetailsSitUnderSummary()
	{
		final List<Row> rows = build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false));
		final int summary = rows.indexOf(main("Summary", ""));
		assertEquals(b("", ""), rows.get(summary - 1));
		assertEquals(d(" hits/expected", "32/30.2"), rows.get(summary + 1));
		// None while the fight runs.
		assertFalse(build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false))
			.contains(main("Summary", "")));
	}

	// ---- the luck % (Eric, 2026-10-27: "add back in the luck %") ----

	// ---- damage taken, after the fight (T-019) ----

	@Test
	public void theSummaryBreaksDownTheDamageTaken()
	{
		final FakeScope s = LedgerExamples.kill();
		s.dotTaken = 27;
		s.biggestHitTaken = 41;
		s.damageTakenUnprotected = 85;
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		final int healed = rows.indexOf(d(" boss healed", "68"));
		assertEquals(Arrays.asList(d(" burn/poison taken", "27"), d(" biggest hit taken", "41"),
			d(" without protection", "85"), d(" eating time", "4 s")), rows.subList(healed + 1, healed + 5));
		// The live total under Execution reads as before: hits only since T-018.
		assertTrue(rows.contains(d(" damage taken", "248")));
	}

	@Test
	public void eachDamageTakenRowShowsOnlyWhenAboveNothing()
	{
		final FakeScope s = LedgerExamples.kill();
		s.biggestHitTaken = 10;
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		assertTrue(rows.contains(d(" biggest hit taken", "10")));
		assertFalse(rows.stream().anyMatch(row -> row.left.equals(" burn/poison taken")
			|| row.left.equals(" without protection")));
		// Never while the fight runs.
		s.dotTaken = 27;
		s.damageTakenUnprotected = 85;
		assertFalse(build(s, State.fight("Vardorvis", false, false, false)).stream()
			.anyMatch(row -> row.left.endsWith(" taken") && !row.left.equals(" damage taken")
				|| row.left.equals(" without protection")));
	}

	@Test
	public void roomRaidAndTripScopesAllCarryTheDamageTakenBreakdown()
	{
		final Fight f = new Fight("Zebak", NpcIds.GOBLIN, 1, 700, 0L, RaidType.TOMBS_OF_AMASCUT, 1);
		f.recordAttackMade(false, 1.0);
		f.recordHitTaken(31, true, 1L);
		f.recordHitTaken(12, false, 2L);
		f.recordDotTaken(6, 3L);
		final Encounter room = new Encounter("Zebak", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(f);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordHitTaken(31, true);
		trip.recordHitTaken(12, false);
		trip.recordDotTaken(6);
		for (LedgerRows.Scope scope : Arrays.asList(LedgerRows.of(room), LedgerRows.of(raid), LedgerRows.of(trip)))
		{
			assertEquals(43, scope.damageTaken());
			assertEquals(6, scope.dotTaken());
			assertEquals(31, scope.biggestHitTaken());
			assertEquals(31, scope.damageTakenUnprotected());
		}
	}

	// ---- T-023: reflected damage in O, and rates over the scored fights ----

	@Test
	public void reflectedDamageIsLuckNeutralLikeThrallAndDot()
	{
		// Eric's kill: 688 dealt against 778 expected, thrall 80. With 20 of recoil the same gap is spread over more of the
		// boss's HP that wasn't the weapon's rolls, so it costs fewer seconds.
		final FakeScope kill = LedgerExamples.kill();
		final double without = new LedgerRows.Numbers(kill).luck;
		kill.reflectDamage = 20;
		final double with = new LedgerRows.Numbers(kill).luck;
		assertEquals(136.8 * (688 - 778) / (778 + 80 + 20.0), with, 1e-9);
		assertTrue(with > without);
	}

	@Test
	public void recoilAndVengeanceGetASummaryRowNextToTheThrall()
	{
		final FakeScope kill = LedgerExamples.kill();
		kill.reflectDamage = 12;
		final List<Row> rows = build(kill, State.fight("Vardorvis", true, true, false));
		final int thrall = rows.indexOf(d(" thrall dmg", "80"));
		assertEquals(d(" recoil/veng dmg", "12"), rows.get(thrall + 1));
		assertFalse(build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)).stream()
			.anyMatch(row -> row.left.equals(" recoil/veng dmg")));
	}

	@Test
	public void luckAndDpsAreRatesOverTheScoredFightsButTheClockIsTheWholeRoom()
	{
		// The Sire's case (T-020): 186 ticks in the room, 120 of them on the scored Sire, 444 dealt against 612 expected,
		// 8 of burn. Over the whole room the gap would read 31 s; over the scored time it's 19.5 s.
		final FakeScope sire = LedgerExamples.kill();
		sire.combatTicks = 186;
		sire.scoredCombatTicks = 120;
		sire.damage = 444;
		sire.expectedDamage = 612;
		sire.thrallDamage = 0;
		sire.dotDamage = 8;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(sire);
		assertEquals(72 * (444 - 612) / 620.0, n.luck, 1e-9);
		assertEquals(444 / 72.0, n.dps, 1e-9);
		assertEquals(111.6, n.clock, 1e-9);
		final List<Row> rows = build(sire, State.fight("Abyssal Sire", true, true, false));
		assertEquals(title("Abyssal Sire", "1:51").left + " (dead)", rows.get(0).left);
		assertEquals("1:51", rows.get(0).right);
		assertEquals("20 s slower", rows.get(1).right);
		assertTrue(rows.contains(main("DPS", "6.2")));
	}

	@Test
	public void uptimeStillCountsEveryTickInTheRoom()
	{
		// Idle on a respiratory system is still idle: the lost ticks are over the whole room's.
		final FakeScope sire = LedgerExamples.kill();
		sire.combatTicks = 186;
		sire.scoredCombatTicks = 120;
		final LedgerRows.Numbers whole = new LedgerRows.Numbers(sire);
		sire.scoredCombatTicks = -1;
		assertEquals(new LedgerRows.Numbers(sire).uptime, whole.uptime, 1e-12);
		assertEquals(new LedgerRows.Numbers(sire).uptimeCost, whole.uptimeCost, 1e-12);
	}

	@Test
	public void aRealRoomScoresOverItsScoredFightsOnly()
	{
		final Encounter room = new Encounter("Hespori", null, 0L);
		final Fight scored = new Fight("Hespori", NpcIds.GOBLIN, 1, 425, 0L, null, 0);
		for (int i = 0; i < 10; i++)
		{
			scored.recordAttackMade(false, 1.0);
			scored.recordExpected(40, 0.5, 10);
		}
		scored.recordDamageDealt(80, 1L, true);
		// Any id on EncounterGroup's unscored list: here a Hespori flower.
		final Fight unscored = new Fight("Flower", net.runelite.api.gameval.NpcID.HESPORI_HEALER_ACTIVE, 2, 50, 0L, null, 0);
		assertFalse(unscored.isScored());
		unscored.recordAttackMade(false, 1.0);
		for (int i = 0; i < 4; i++)
		{
			unscored.recordTickSpent();
		}
		room.add(scored);
		room.add(unscored);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(5, unscored.getCombatTicks());
		assertEquals(room.getCombatTicks() - 5, room.getScoredCombatTicks());
		assertEquals(scored.getCombatTicks(), LedgerRows.of(room).scoredCombatTicks());
		assertEquals(scored.getCombatTicks(), LedgerRows.of(raid).scoredCombatTicks());
		assertTrue(LedgerRows.of(room).combatTicks() > LedgerRows.of(room).scoredCombatTicks());
	}

	// ---- T-025: O on scored fights only; the trip timed on scored ticks ----

	@Test
	public void thrallDamageOnAnUnscoredAddLeavesOUnchanged()
	{
		final Fight scored = new Fight("Hespori", NpcIds.GOBLIN, 1, 300, 0L, null, 0);
		for (int i = 0; i < 10; i++)
		{
			scored.recordAttackMade(false, 1.0);
			scored.recordExpected(40, 0.5, 10);
		}
		scored.recordDamageDealt(80, 1L, true);
		scored.recordThrallDamage(12);
		final Fight flower = new Fight("Flower", net.runelite.api.gameval.NpcID.HESPORI_HEALER_ACTIVE, 2, 50, 0L, null,
			0);
		flower.recordAttackMade(false, 1.0);
		final Encounter room = new Encounter("Hespori", null, 0L);
		room.add(scored);
		room.add(flower);
		final double before = new LedgerRows.Numbers(LedgerRows.of(room)).luck;
		// A thrall hitting the flower: the room's thrall damage grows, its O doesn't.
		flower.recordThrallDamage(30);
		flower.recordDotDamage(5);
		final LedgerRows.Scope after = LedgerRows.of(room);
		assertEquals(12, after.scoredOtherDamage());
		assertEquals(42, after.thrallDamage());
		assertEquals(before, new LedgerRows.Numbers(after).luck, 1e-12);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(12, LedgerRows.of(raid).scoredOtherDamage());
		// The Summary still shows the whole room's.
		assertTrue(build(after, State.fight("Hespori", true, false, false)).contains(d(" thrall dmg", "42")));
	}

	@Test
	public void theTripTimesLuckAndDpsOnScoredTicksAndTheClockOnAll()
	{
		final SessionTotals trip = new SessionTotals(0L);
		for (int i = 0; i < 20; i++)
		{
			trip.recordAttackMade(false, 1.0);
			trip.recordExpected(0.5, 10);
		}
		trip.recordAttempt(80, true, 1L);
		// Ten ticks on an unscored add.
		for (int i = 0; i < 10; i++)
		{
			trip.recordTickSpent(false);
		}
		final LedgerRows.Scope s = LedgerRows.of(trip);
		assertEquals(30, s.combatTicks());
		assertEquals(20, s.scoredCombatTicks());
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertEquals(18.0, n.clock, 1e-9);
		// Twelve scored seconds, not eighteen, carry the gap.
		final double expected = s.expectedDamage();
		assertEquals(12.0 * (80 - expected) / expected, n.luck, 1e-9);
		assertEquals(80 / 12.0, n.dps, 1e-9);
	}

	// ---- T-030: the spec as two rows, with what it deals beyond its hits ----

	@Test
	public void burningClawsShowTheirBurnInTheSpecDamage()
	{
		final LedgerRows.Loadout claws = new LedgerRows.Loadout();
		claws.attack = "Burning claws";
		claws.maxHit = 41;
		claws.specMaxHit = 48;
		claws.specAccuracy = 0.71;
		claws.specAverage = 17.8;
		claws.specExtra = 8.2;
		claws.specExtraKind = "burn";
		final List<Row> rows = LedgerRows.loadout(claws, false);
		assertEquals(d(" spec", "48 max \u00b7 71%"), rows.get(rows.size() - 2));
		// The total, burn and all, then the burn named.
		assertEquals(d(" spec damage", "26.0 (8.2 burn)"), rows.get(rows.size() - 1));
	}

	@Test
	public void aSpecWithNothingExtraHasNoBracket()
	{
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		l.specExtra = 0;
		assertEquals("17.8", LedgerRows.specDamage(l));
		l.specExtra = -1;
		assertEquals("17.8", LedgerRows.specDamage(l));
		// No average known: no damage row, the max and chance still show.
		l.specAverage = -1;
		final List<Row> rows = LedgerRows.loadout(l, false);
		assertEquals(d(" spec", "50 max \u00b7 71%"), rows.get(rows.size() - 1));
	}

	@Test
	public void burningClawsExpectedBurnByHand()
	{
		// Wiki: each damaging hitsplat burns at 15% / 30% / 45% by which roll connected first; 10 a burn.
		// Damaging hitsplats: 3 when the first roll connects (25-25-50), 2 on the second (50-50-0), 1 on the third.
		// At a per-roll accuracy a = 0.6:
		//   first:  a              = 0.6    x 3 x 0.15 x 10 = 2.70
		//   second: (1 - a) a      = 0.24   x 2 x 0.30 x 10 = 1.44
		//   third:  (1 - a)^2 a    = 0.096  x 1 x 0.45 x 10 = 0.432
		//   all miss: no burn.                         total = 4.572
		assertEquals(4.572, CombatCalc.burningClawsBurnAverage(0.6), 1e-9);
		assertEquals(4.5, CombatCalc.burningClawsBurnAverage(1.0), 1e-9);
		assertEquals(0.0, CombatCalc.burningClawsBurnAverage(0.0), 1e-9);
	}

	// ---- T-029: Luck as top/bottom percent ----

	@Test
	public void eachBandAndItsColour()
	{
		assertEquals("bottom 20%", LedgerRows.band(0.20));
		assertEquals("average", LedgerRows.band(0.25));
		assertEquals("average", LedgerRows.band(0.5));
		assertEquals("average", LedgerRows.band(0.75));
		assertEquals("top 25%", LedgerRows.band(0.7501));
		assertEquals("top 10%", LedgerRows.band(0.9));
		assertEquals(COST, LedgerRows.percentileBand(0.20));
		assertEquals(NEUTRAL, LedgerRows.percentileBand(0.5));
		assertEquals(BETTER, LedgerRows.percentileBand(0.9));
	}

	@Test
	public void neverTopOrBottomZero()
	{
		assertEquals("bottom 1%", LedgerRows.band(0.003));
		assertEquals("bottom 1%", LedgerRows.band(0.0));
		assertEquals("top 1%", LedgerRows.band(0.999));
		assertEquals("top 1%", LedgerRows.band(1.0));
	}

	@Test
	public void luckReadsItsBandThenItsSecondsAndTakesItsColour()
	{
		// Eric's kill: 688 dealt against 778 expected. With a spread of 11,436, z = -90 / 106.9 = -0.842, so a fifth of
		// fights with these attacks dealt less: the bottom 20%.
		final FakeScope kill = LedgerExamples.kill();
		kill.damageVariance = 11436;
		final List<Row> rows = build(kill, State.fight("Vardorvis", true, true, false));
		assertEquals("bottom 20% (14 s slower)", rows.get(1).right);
		assertEquals(COST, rows.get(1).rightColour);
		// Damage/expected takes the same colour.
		assertEquals(COST, rows.get(2).rightColour);
		// The short form for a narrow panel is the band alone.
		assertEquals("bottom 20%", rows.get(1).shortRight);
		// A middling fight: "average", neutral, even though its seconds would have coloured it.
		kill.damageVariance = 200_000;
		final List<Row> middling = build(kill, State.fight("Vardorvis", true, true, false));
		assertEquals("average (14 s slower)", middling.get(1).right);
		assertEquals(NEUTRAL, middling.get(1).rightColour);
	}

	@Test
	public void noBracketWhenTheSecondsRoundToNone()
	{
		final FakeScope close = LedgerExamples.midFight();
		close.damage = 417;
		close.damageVariance = 4000;
		assertEquals("average", build(close, State.fight("Vardorvis", false, false, false)).get(1).right);
	}

	@Test
	public void unknownVarianceFallsBackToTheSeconds()
	{
		final FakeScope kill = LedgerExamples.kill();
		kill.damageVariance = -1;
		final List<Row> rows = build(kill, State.fight("Vardorvis", true, true, false));
		assertEquals(emph("Luck", "14 s slower", COST), rows.get(1));
		assertNull(rows.get(1).shortRight);
	}

	@Test
	public void theSireKillReadsInTheBottom()
	{
		// T-023's numbers: 444 dealt against 612 expected over 28 attacks. A spread of about 20 an attack gives
		// V = 28 x 400 = 11,200: z = -168 / 105.8 = -1.59, about 1 fight in 18 dealt less.
		final FakeScope sire = LedgerExamples.kill();
		sire.combatTicks = 186;
		sire.scoredCombatTicks = 120;
		sire.damage = 444;
		sire.expectedDamage = 612;
		sire.thrallDamage = 0;
		sire.dotDamage = 8;
		sire.damageVariance = 11_200;
		assertEquals("bottom 6% (20 s slower)", build(sire, State.fight("Abyssal Sire", true, true, false)).get(1).right);
	}

	@Test
	public void roomRaidAndTripSumTheirVariance()
	{
		final Fight a = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		a.recordExpected(40, 0.7, 14, 150);
		a.recordDamageDealt(10, 1L, true);
		final Fight b = new Fight("Vardorvis", NpcIds.GOBLIN, 2, 700, 0L, null, 0);
		b.recordExpected(40, 0.7, 14, 250);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(a);
		room.add(b);
		assertEquals(400, LedgerRows.of(room).damageVariance(), 1e-9);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(400, LedgerRows.of(raid).damageVariance(), 1e-9);
		// 10 dealt against 28 expected over a spread of 400: z = -0.9.
		assertEquals(DamageMoments.phi(-0.9), new LedgerRows.Numbers(LedgerRows.of(room)).percentile, 1e-12);
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(-1, LedgerRows.of(trip).damageVariance(), 0);
	}
}

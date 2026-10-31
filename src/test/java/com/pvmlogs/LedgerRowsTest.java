package com.pvmlogs;

import static com.pvmlogs.LedgerRows.Colour.BETTER;
import static com.pvmlogs.LedgerRows.Colour.COST;
import static com.pvmlogs.LedgerRows.Colour.DEAD;
import static com.pvmlogs.LedgerRows.Colour.NEUTRAL;
import static com.pvmlogs.LedgerRows.Colour.WARNING;
import static com.pvmlogs.LedgerRows.FontRole.BODY;
import static com.pvmlogs.LedgerRows.FontRole.DETAIL;
import static com.pvmlogs.LedgerRows.FontRole.EMPHASIS;
import static com.pvmlogs.LedgerRows.FontRole.VALUE;
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
		return new Row(left, right, lf, rf, lc, rc, null, ticksShort(right));
	}

	/**
	 * T-117: the short form a row with ticks or a setup part's damage in it carries for a box saved too narrow: {@code 86% (-25t)},
	 * {@code 97% (-45)}, {@code 7t}; null for anything else.
	 */
	static String ticksShort(String right)
	{
		final java.util.regex.Matcher cost = java.util.regex.Pattern.compile("(.*) \\(-([\\d,]+) ticks?\\)").matcher(right);
		if (cost.matches())
		{
			return cost.group(1) + " (-" + cost.group(2) + "t)";
		}
		// T-117: a setup part's damage, "97% (-45)".
		final java.util.regex.Matcher dmg = java.util.regex.Pattern.compile("(.*) \\(-([\\d,]+) dmg\\)").matcher(right);
		if (dmg.matches())
		{
			return dmg.group(1) + " (-" + dmg.group(2) + ")";
		}
		final java.util.regex.Matcher bare = java.util.regex.Pattern.compile("([\\d,]+) ticks?").matcher(right);
		return bare.matches() ? bare.group(1) + "t" : null;
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

	/**
	 * The title: a boss's name in its colour (Eric, 2026-10-28), killed or not; a raid's in the raid's; anything
	 * else white.
	 */
	private static Row title(String left, String clock)
	{
		final String name = left.replaceAll(" \\((dead|ended)\\)$", "");
		int rgb = BossColours.of(name);
		if (name.startsWith("CoX \u00b7"))
		{
			rgb = BossColours.of("Chambers of Xeric");
		}
		else if (name.startsWith("ToA \u00b7"))
		{
			rgb = BossColours.of("Tombs of Amascut");
		}
		return rgb < 0 ? r(left, clock, EMPHASIS, EMPHASIS, NEUTRAL, NEUTRAL)
			: new Row(left, clock, EMPHASIS, EMPHASIS, LedgerRows.Colour.BOSS, NEUTRAL, null, null, rgb);
	}

	/** Luck and Damage: bold label, regular value (T-073; Eric, 2026-10-26: the main rows' labels are bold). */
	private static Row emph(String left, String right, Colour rc)
	{
		return r(left, right, EMPHASIS, VALUE, NEUTRAL, rc);
	}

	/** Execution, the Summary's head and the DPS overlay's DPS: bold label, regular value (T-073). */
	private static Row main(String left, String right)
	{
		return r(left, right, EMPHASIS, VALUE, NEUTRAL, NEUTRAL);
	}

	/** The DPS overlay's attack row: the attack's name alone (T-073), its DPS on the row under it. */
	private static final Row TENTACLE = attack("Abyssal tentacle", "", "Abyssal tentacle");
	private static final Row TENTACLE_DPS = r("DPS", "5.5", EMPHASIS, VALUE, NEUTRAL, NEUTRAL);

	/** "Damage/expected", which falls back to "Damage" on a panel too narrow for it. */
	private static Row damage(String figures, Colour rc)
	{
		return new Row("Damage/expected", figures, EMPHASIS, VALUE, NEUTRAL, rc, "Damage");
	}

	/** The DPS overlay's attack row: the attack's name, and the short form the overlay may cut it to. */
	private static Row attack(String name, String right, String shortName)
	{
		return new Row(name, right, EMPHASIS, VALUE, NEUTRAL, NEUTRAL, shortName);
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
			TENTACLE_DPS,
			d(" max hit", "49"),
			d(" accuracy", "54%"),
			main("Spec", ""),
			d(" max hit", "50"),
			d(" accuracy", "71%"),
			d(" expected", "17.8 dmg")),
			LedgerRows.loadout(LedgerExamples.loadout(), false));
	}

	@Test
	public void theLoadoutHidesUnknownsAndTheSpecRowWithoutASpecWeapon()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.maxHit = 41;
		assertRows(Arrays.asList(attack("Loadout", "", "Loadout"), d(" max hit", "41")), LedgerRows.loadout(l, false));
	}

	@Test
	public void duringTheFight()
	{
		// Eric's fight at 1:13: luck -7.5 s is 10% of the clock, so Cost; uptime's 5 s costs too, stat boost's 1.3 s
		// is under 2% of 73.2 s and stays white. Of the 10 ticks lost, 2 were eating (T-038): uptime counts the 8 idle,
		// and eating has its own line, neutral.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			damage("370/418", COST),
			emph("Luck", "8 s slower", COST),
			main("Execution", "92%"),
			d(" uptime", "93% (-8 ticks)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "98% (-9 dmg)"),
			d(" damage taken", "112"),
			d(" eating", "2 ticks")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)));
	}

	@Test
	public void dead()
	{
		// Eric's kill. 688 + thrall 80 - healed 68 = 700, the boss's HP.
		assertRows(Arrays.asList(
			title("Vardorvis (dead)", "2:16"),
			damage("688/778", COST),
			emph("Luck", "14 s slower", COST),
			main("Execution", "87%"),
			d(" uptime", "89% (-25 ticks)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "98% (-16 dmg)"),
			d(" damage taken", "248"),
			d(" eating", "7 ticks"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "32/30.2"),
			d(" thrall dmg", "80"),
			d(" boss healed", "68")),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)));
	}

	@Test
	public void endedWithoutAKill()
	{
		assertRows(Arrays.asList(
			title("Vardorvis (ended)", "2:20"),
			damage("721/812", COST),
			emph("Luck", "15 s slower", COST),
			main("Execution", "93%"),
			d(" uptime", "94% (-14 ticks)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "99% (-8 dmg)"),
			d(" damage taken", "310"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "26/33.7"),
			d(" burn dmg", "40"),
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
			damage("4,120/4,201", NEUTRAL),
			emph("Luck", "14 s slower", NEUTRAL),
			main("Execution", "93%"),
			d(" uptime", "96% (-52 ticks)", COST),
			d(" prayer", "99% (-16 dmg)"),
			d(" gear", "99% (-12 dmg)"),
			d(" stat boost", "98% (-102 dmg)", COST),
			d(" damage taken", "1,404"),
			d(" eating", "11 ticks"),
			b("", ""),
			main("Summary", ""),
			d(" hits/expected", "161/158.7"),
			d(" thrall dmg", "290"),
			d(" burn dmg", "20"),
			d(" venom dmg", "40")),
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
			damage("8,410/8,200", BETTER),
			emph("Luck", "13 s faster", BETTER),
			main("Execution", "86%"),
			d(" uptime", "90% (-92 ticks)", COST),
			d(" prayer", "98% (-204 dmg)", COST),
			d(" gear", "99% (-34 dmg)"),
			d(" stat boost", "99% (-103 dmg)"),
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
			emph("Damage", "370", NEUTRAL),
			r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL),
			d(" luck hidden until it's fixed", ""),
			main("Execution", "92%"),
			d(" uptime", "93% (-8 ticks)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "98% (-9 dmg)"),
			d(" damage taken", "112"),
			d(" eating", "2 ticks")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true)));
	}

	// ---- T-073: no DPS row; bold labels, regular figures ----

	@Test
	public void noDpsRowInAnyScope()
	{
		for (List<Row> rows : Arrays.asList(
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)),
			build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false)),
			build(LedgerExamples.trip(), State.trip(6, false, false)),
			build(LedgerExamples.raid(), State.raid("Tombs of Amascut", 3, false, false))))
		{
			assertFalse(rows.stream().anyMatch(row -> row.left.equals("DPS")));
		}
	}

	@Test
	public void everyMainRowIsBoldOnTheLeftAndRegularOnTheRight()
	{
		final List<Row> rows = build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false));
		for (Row row : rows)
		{
			if (row.leftFont == EMPHASIS && row != rows.get(0))
			{
				assertEquals(row.left, VALUE, row.rightFont);
			}
		}
		// The title keeps its bold clock, as the box's heading (LedgerRows.TITLE_CLOCK).
		assertEquals(EMPHASIS, rows.get(0).rightFont);
		assertEquals(LedgerRows.TITLE_CLOCK, rows.get(0).rightFont);
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
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" gear", "98% (-14 dmg)")));
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
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" stat boost", "drained (-16 dmg)")));
	}

	@Test
	public void exactlyHalfDrainedKeepsThePercentage()
	{
		final FakeScope s = LedgerExamples.kill();
		s.attacksMade = 50;
		s.attacksPrayed = 50;
		s.attacksSwitched = 50;
		s.attacksDrained = 25;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" stat boost", "98% (-16 dmg)")));
	}

	@Test
	public void drainedWithLittleCostIsNeutralAndNoCostIsTheWordAlone()
	{
		final FakeScope s = LedgerExamples.kill();
		s.attacksDrained = 40;
		// T-117: 0.24 s of cost is 2 damage, which shows, in the plain colour.
		s.setup(0.998, 0.0, 0.2352, 0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" stat boost", "drained (-2 dmg)")));
		// Under half a damage: the word alone.
		s.setup(0.9995, 0.0, 0.05, 0);
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
			.contains(d(" stat boost", "drained (-22 dmg)", COST)));
	}

	@Test
	public void drainedLeavesTheSetupRowAlone()
	{
		final FakeScope s = LedgerExamples.midFightBeforeT010();
		s.attacksDrained = 18;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" setup", "98% (-9 dmg)")));
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
		// The boost cost 1 of each attack's ideal 10 (T-117: in damage, 10 over the ten attacks).
		final Row drained = d(" stat boost", "drained (-10 dmg)");
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
		assertEquals(d(" uptime", "95% (-4 ticks)", COST), rows.get(rows.size() - 2));
	}

	@Test
	public void noMonsterData()
	{
		// Nothing to expect from: Luck and Execution read a dash, uptime still counts.
		assertRows(Arrays.asList(
			title("New boss", "0:48"),
			emph("Damage", "212", NEUTRAL),
			d(" no monster data yet", ""),
			emph("Luck", "—", NEUTRAL),
			main("Execution", "—"),
			d(" uptime", "95% (-4 ticks)", COST),
			d(" damage taken", "36")),
			build(LedgerExamples.noData(), State.fight("New boss", false, false, false)));
	}

	@Test
	public void scopeFromBeforeT010ShowsOneSetupRow()
	{
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			damage("370/418", COST),
			emph("Luck", "8 s slower", COST),
			main("Execution", "92%"),
			d(" uptime", "93% (-8 ticks)", COST),
			d(" setup", "98% (-9 dmg)"),
			d(" damage taken", "112"),
			d(" eating", "2 ticks")),
			build(LedgerExamples.midFightBeforeT010(), State.fight("Vardorvis", false, false, false)));
	}

	// ---- the loadout ----

	@Test
	public void theLoadoutSitsAtTheBottomWhileFighting()
	{
		// T-022: no loadout under the fight's rows; it has an overlay of its own.
		assertRows(Arrays.asList(
			title("Vardorvis", "1:13"),
			damage("370/418", COST),
			emph("Luck", "8 s slower", COST),
			main("Execution", "92%"),
			d(" uptime", "93% (-8 ticks)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "98% (-9 dmg)"),
			d(" damage taken", "112"),
			d(" eating", "2 ticks")),
			build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false)));
	}

	@Test
	public void theLedgerKeepsItsOwnWarning()
	{
		final List<Row> rows = build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true));
		assertEquals(r("! Boss data unsure", "", BODY, BODY, WARNING, NEUTRAL), rows.get(2));
		assertEquals(d(" damage taken", "112"), rows.get(rows.size() - 2));
		assertEquals(d(" eating", "2 ticks"), rows.get(rows.size() - 1));
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
				|| row.left.equals(" max hit") || row.left.equals(" accuracy") || row.left.equals(" spec")
				|| row.left.equals(LedgerRows.SPEC) || row.left.equals(LedgerRows.EXPECTED)));
		}
	}

	@Test
	public void theExpectedDpsSitsBesideTheAttackWithTheSpecsExpectedDamageUnder()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Fire Surge";
		l.dps = 6.1;
		assertEquals(Arrays.asList(attack("Fire Surge", "", "Fire Surge"), main("DPS", "6.1")),
			LedgerRows.loadout(l, false));
		final LedgerRows.Loadout claws = new LedgerRows.Loadout();
		claws.attack = "Burning claws";
		claws.maxHit = 41;
		claws.specMaxHit = 110;
		claws.specAccuracy = 0.62;
		claws.specAverage = 33.4;
		// T-077: the spec's own section.
		final List<Row> rows = LedgerRows.loadout(claws, false);
		assertEquals(Arrays.asList(main("Spec", ""), d(" max hit", "110"), d(" accuracy", "62%"), d(" expected", "33.4 dmg")),
			rows.subList(rows.size() - 4, rows.size()));
	}

	@Test
	public void prayerIsTheDamageShareLikeGear()
	{
		// Eric, 2026-10-26: Chivalry up all fight with Piety unlocked counted as "0%" prayed. Now it's the damage the
		// setup kept against the best prayer, the remainder of the loss after boost and gear: here all 2% of it.
		final FakeScope s = LedgerExamples.kill();
		s.attacksPrayed = 0;
		s.setup(0.98, 1.0, 0, 0);
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" prayer", "98% (-16 dmg)")));
	}

	@Test
	public void damageTakenIsTheLastRowUnderExecution()
	{
		// Eric, 2026-10-26: under Execution, the damage you took.
		final List<Row> rows = build(LedgerExamples.kill(), State.fight("Vardorvis", false, false, false));
		assertEquals(d(" stat boost", "98% (-16 dmg)"), rows.get(7));
		assertEquals(d(" damage taken", "248"), rows.get(8));
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
			Arrays.asList(rows.get(4).left, rows.get(5).left, rows.get(6).left, rows.get(7).left));
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
			TENTACLE_DPS,
			d(" max hit", "49"),
			d(" accuracy", "54%"),
			main("Spec", ""),
			d(" max hit", "50"),
			d(" accuracy", "71%"),
			d(" expected", "17.8 dmg")),
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
		assertEquals(Arrays.asList(main("Execution", "87%"), d(" uptime", "89% (-25 ticks)", COST), d(" damage taken", "248"),
			d(" eating", "7 ticks")), rows.subList(3, rows.size()));
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
		assertEquals(d(" no time lost", ""), rows.get(rows.size() - 2));
		assertEquals(d(" damage taken", "248"), rows.get(rows.size() - 1));
	}

	@Test
	public void partsOffEndsAtExecution()
	{
		final Settings s = new Settings();
		s.parts = ExecutionParts.OFF;
		final List<Row> rows = LedgerRows.build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false), s);
		// Eating goes with damage taken (Eric, 2026-10-27, T-038): Off hides both.
		assertEquals(main("Execution", "92%"), rows.get(rows.size() - 1));
	}

	@Test
	public void detailsOffLeavesTheTopBlockAfterTheKill()
	{
		final Settings s = new Settings();
		s.details = false;
		final List<Row> rows = LedgerRows.build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false), s);
		assertEquals(10, rows.size());
		assertEquals(d(" stat boost", "98% (-16 dmg)"), rows.get(7));
		assertEquals(d(" damage taken", "248"), rows.get(8));
		assertEquals(d(" eating", "7 ticks"), rows.get(9));
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
		// T-038: uptime counts the 25 idle ticks, not the 7 eating. 89.0% x 0.98 = 87.3%, up from 84%.
		assertEquals((1 - 25.0 / 228) * 0.98, kill.execution, 1e-9);
		assertEquals(15.0, kill.uptimeCost, 1e-9);
		assertEquals(4.2, kill.eatingCost, 1e-9);
		// The setup's seconds don't move: it applies to the time spent attacking either way.
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
		// Eric's kill: uptime's 15 s is over 2% of 136.8 s (2.7 s), stat boost's 2.4 s isn't.
		assertTrue(LedgerRows.isCosting(15.0, 136.8));
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
	public void partValueAddsTicksOnlyFromOne()
	{
		assertEquals("86% (-32 ticks)", LedgerRows.partValue(0.8596, 19.2));
		assertEquals("85% (-4 ticks)", LedgerRows.partValue(0.85, 2.352));
		assertEquals("86% (-1 tick)", LedgerRows.partValue(0.86, 0.5));
		// T-117: from half a tick, 0.3 s.
		assertEquals("86%", LedgerRows.partValue(0.86, 0.29));
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
		assertEquals("99% (-1 tick)", LedgerRows.partValue(0.997, 0.6));
		// Prayed 279 of 280: 99.6%.
		final FakeScope s = LedgerExamples.trip();
		s.attacksMade = 280;
		s.attacksPrayed = 279;
		assertTrue(build(s, State.trip(6, true, false)).contains(d(" prayer", "99% (-16 dmg)")));
	}

	@Test
	public void executionNeverRoundsUpToAHundredEither()
	{
		// Eric, 2026-10-26: one tick lost in 300 is 99.7% uptime, so Execution reads 99%, not 100%.
		final FakeScope s = LedgerExamples.kill();
		s.combatTicks = 300;
		s.ticksLost = 1;
		s.ticksLostEating = 0;
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
		assertEquals(title("Vardorvis (dead)", "2:16"),
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
	public void eatingTimeIsInTicks()
	{
		// Eric, 2026-10-27: in seconds, like everything else. One tick is 0.6 s, rounded half away from zero, which is a
		// second: the row shows from there (T-038).
		final FakeScope s = LedgerExamples.kill();
		s.ticksLostEating = 1;
		assertTrue(build(s, State.fight("Vardorvis", true, true, false)).contains(d(" eating", "1 tick")));
		s.ticksLostEating = 7;
		assertTrue(build(s, State.fight("Vardorvis", true, true, false)).contains(d(" eating", "7 ticks")));
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" eating", "7 ticks")));
		// Never in the Summary any more.
		assertFalse(build(s, State.fight("Vardorvis", true, true, false)).stream()
			.anyMatch(row -> row.left.startsWith(" eating time")));
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
		// T-071: a kill saved as weapon (Spell) shows the spell, variant tag and all.
		assertEquals("Fire Surge", LedgerRows.attackLabel("Purging staff (Fire Surge)"));
		assertEquals("Ice Barrage", LedgerRows.attackLabel("Kodai wand (or) (Ice Barrage)"));
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
		assertEquals(Arrays.asList(TENTACLE, TENTACLE_DPS), LedgerRows.loadout(l, false));
	}

	@Test
	public void theAttacksNameHasNoShorterFormThanItself()
	{
		// T-071: the spell alone was the short form of staff (Spell); now it is the name, and a name is cut as it is.
		final LedgerRows.Loadout scythe = new LedgerRows.Loadout();
		scythe.attack = "Sanguine scythe of vitur";
		scythe.dps = 9.0;
		assertEquals("Sanguine scythe of vitur", LedgerRows.loadout(scythe, false).get(0).shortLeft);
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
		s.dotTakenByKind.put("bleed", 27);
		s.biggestHitTaken = 41;
		s.damageTakenUnprotected = 85;
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		final int healed = rows.indexOf(d(" boss healed", "68"));
		assertEquals(Arrays.asList(d(" bleed taken", "27"), d(" largest hit taken", "41"),
			d(" off-prayer damage", "85")), rows.subList(healed + 1, rows.size()));
		// The live total under Execution reads as before: hits only since T-018.
		assertTrue(rows.contains(d(" damage taken", "248")));
	}

	@Test
	public void eachDamageTakenRowShowsOnlyWhenAboveNothing()
	{
		final FakeScope s = LedgerExamples.kill();
		s.biggestHitTaken = 10;
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		assertTrue(rows.contains(d(" largest hit taken", "10")));
		assertFalse(rows.stream().anyMatch(row -> row.left.endsWith(" taken") && !row.left.equals(" damage taken")
			&& !row.left.equals(" largest hit taken") || row.left.equals(" off-prayer damage")));
		// Never while the fight runs.
		s.dotTaken = 27;
		s.damageTakenUnprotected = 85;
		assertFalse(build(s, State.fight("Vardorvis", false, false, false)).stream()
			.anyMatch(row -> row.left.endsWith(" taken") && !row.left.equals(" damage taken")
				|| row.left.equals(" off-prayer damage")));
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
		assertEquals("20 s slower", rows.get(2).right);
		// T-073: no DPS row on the overlay; the side panel's tile reads Numbers.dps.
		assertFalse(rows.stream().anyMatch(row -> row.left.equals("DPS")));
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
		assertEquals(d(" accuracy", "71%"), rows.get(rows.size() - 2));
		// The total, burn and all, then the burn named; on a narrow box the total alone (T-077).
		assertEquals(new Row(" expected", "26.0 dmg (8.2 burn)", DETAIL, DETAIL, NEUTRAL, NEUTRAL, null, "26.0 dmg"),
			rows.get(rows.size() - 1));
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
		assertEquals(d(" accuracy", "71%"), rows.get(rows.size() - 1));
		assertEquals(main("Spec", ""), rows.get(rows.size() - 3));
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
		// Luck sits under Damage/expected (Eric, 2026-10-28).
		assertEquals("bottom 20% (14 s slower)", rows.get(2).right);
		assertEquals(COST, rows.get(2).rightColour);
		// Damage/expected takes the same colour.
		assertEquals(COST, rows.get(1).rightColour);
		// The short form for a narrow panel is the band alone.
		assertEquals("bottom 20%", rows.get(2).shortRight);
		// A middling fight: "average", neutral, even though its seconds would have coloured it.
		kill.damageVariance = 200_000;
		final List<Row> middling = build(kill, State.fight("Vardorvis", true, true, false));
		assertEquals("average (14 s slower)", middling.get(2).right);
		assertEquals(NEUTRAL, middling.get(2).rightColour);
	}

	@Test
	public void noBracketWhenTheSecondsRoundToNone()
	{
		final FakeScope close = LedgerExamples.midFight();
		close.damage = 417;
		close.damageVariance = 4000;
		assertEquals("average", build(close, State.fight("Vardorvis", false, false, false)).get(2).right);
	}

	@Test
	public void unknownVarianceFallsBackToTheSeconds()
	{
		final FakeScope kill = LedgerExamples.kill();
		kill.damageVariance = -1;
		final List<Row> rows = build(kill, State.fight("Vardorvis", true, true, false));
		assertEquals(emph("Luck", "14 s slower", COST), rows.get(2));
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
		assertEquals("bottom 6% (20 s slower)", build(sire, State.fight("Abyssal Sire", true, true, false)).get(2).right);
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

	// ---- T-038: Execution without eating; eating as its own line ----

	@Test
	public void aFightWhoseOnlyLostTicksAreEatingHasFullUptime()
	{
		final FakeScope s = LedgerExamples.kill();
		s.ticksLost = 7;
		s.ticksLostEating = 7;
		s.setup(0.98, 0.85, 2.352, 0);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertEquals(1.0, n.uptime, 0);
		assertEquals(0, n.uptimeCost, 0);
		assertEquals(4.2, n.eatingCost, 1e-9);
		assertEquals(0.98, n.execution, 1e-9);
		final List<Row> rows = build(s, State.fight("Vardorvis", false, false, false));
		assertTrue(rows.contains(main("Execution", "98%")));
		assertTrue(rows.contains(d(" uptime", "100%")));
		assertTrue(rows.contains(d(" eating", "7 ticks")));
	}

	@Test
	public void withNoEatingEveryNumberIsAsBefore()
	{
		// The ended fight: 14 ticks lost, none of them eating. Today's formulas, written out.
		final FakeScope s = LedgerExamples.ended();
		assertEquals(0, s.ticksLostEating);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertEquals(1 - 14.0 / 234, n.uptime, 1e-12);
		assertEquals(14 * 0.6, n.uptimeCost, 1e-12);
		assertEquals(0, n.eatingCost, 0);
		assertEquals((234 * 0.6 - 14 * 0.6) * (1 - 0.99), n.setupCost, 1e-12);
		assertEquals((1 - 14.0 / 234) * 0.99, n.execution, 1e-12);
		assertFalse(build(s, State.fight("Vardorvis", true, false, false)).stream()
			.anyMatch(row -> row.left.equals(LedgerRows.EATING)));
	}

	@Test
	public void eatingIsNeverACostHoweverLong()
	{
		// 60 ticks eating is 36 s, a quarter of the clock: still neutral, as the plugin can't tell why you ate.
		final FakeScope s = LedgerExamples.kill();
		s.ticksLost = 60;
		s.ticksLostEating = 60;
		assertTrue(build(s, State.fight("Vardorvis", false, false, false)).contains(d(" eating", "60 ticks")));
	}

	@Test
	public void eatingFollowsDamageTakenUnderCostingOnly()
	{
		// Costing only still lists damage taken, and eating with it.
		final Settings costing = new Settings();
		costing.parts = ExecutionParts.COSTING;
		final List<Row> rows = LedgerRows.build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, false),
			costing);
		assertEquals(d(" eating", "2 ticks"), rows.get(rows.size() - 1));
	}

	/** A fight of {@code attacks} attacks, {@code spent} cooldown ticks, then {@code idle} and {@code eating} lost ticks, fed to the trip too. */
	private static Fight ticks(int attacks, int spent, int idle, int eating, SessionTotals trip)
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false);
			trip.recordAttackMade(false);
		}
		for (int i = 0; i < spent; i++)
		{
			f.recordTickSpent();
			trip.recordTickSpent(true);
		}
		for (int i = 0; i < idle; i++)
		{
			f.recordTickLost(false);
			trip.recordTickLost(false, true);
		}
		for (int i = 0; i < eating; i++)
		{
			f.recordTickLost(true);
			trip.recordTickLost(true, true);
		}
		return f;
	}

	@Test
	public void aRoomARaidAndATripLeaveEatingOutOfUptime()
	{
		final SessionTotals trip = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		// 10 + 4 + 3 + 2 = 19 ticks and 5 + 0 + 1 + 4 = 10: 29 ticks, 4 idle, 6 eating.
		room.add(ticks(10, 4, 3, 2, trip));
		room.add(ticks(5, 0, 1, 4, trip));
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		for (LedgerRows.Scope scope : Arrays.asList(LedgerRows.of(room), LedgerRows.of(raid), LedgerRows.of(trip)))
		{
			assertEquals(29, scope.combatTicks());
			final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
			assertEquals(1 - 4.0 / 29, n.uptime, 1e-12);
			assertEquals(4 * 0.6, n.uptimeCost, 1e-12);
			assertEquals(6 * 0.6, n.eatingCost, 1e-12);
		}
	}

	// ---- T-043: avoidable eating in Execution, after the fight only ----

	/** 16:03 kill 4 (T-039's figures, 08-boss-stats.md §3.4): 21.6 avoidable of 27 eating ticks, 216 combat ticks, 263 taken, 141 avoidable. */
	private static FakeScope kill4()
	{
		final FakeScope s = LedgerExamples.kill();
		s.combatTicks = 216;
		s.ticksLost = 25 + 27;
		s.ticksLostEating = 27;
		s.damageTaken = 263;
		s.avoidableEatingTicks = 21.6;
		s.damageTakenAvoidable = 141;
		return s.setup(0.98, 0.85, 2.352, 0);
	}

	/** Damage taken after a ruled fight: its avoidable part, and the taken figure alone as the narrow form. */
	private static Row taken(String right, String shortRight)
	{
		return new Row(" damage taken", right, DETAIL, DETAIL, NEUTRAL, NEUTRAL, null, shortRight);
	}

	@Test
	public void avoidableEatingIsExecutionsFifthPartAfterTheKill()
	{
		final FakeScope s = kill4();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertEquals(21.6 * 0.6, n.avoidableEatingCost, 1e-9);
		assertEquals(0.9, n.avoidableEatingShare, 1e-12);
		assertEquals(n.executionLive * 0.9, n.execution, 1e-12);
		// The setup's seconds don't move: the avoidable eating is part of the eating they already leave out.
		assertEquals((216 * 0.6 - 25 * 0.6 - 27 * 0.6) * (1 - 0.98), n.setupCost, 1e-9);

		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		final int execution = rows.indexOf(main("Execution", LedgerRows.partPercent(n.execution)));
		assertTrue(rows.toString(), execution > 0);
		// uptime, then the fifth part (Cost: 13 s is over 2% of 129.6 s), then prayer, gear, stat boost, damage taken,
		// eating.
		assertEquals(Arrays.asList(
			d(" uptime", LedgerRows.withTicks(LedgerRows.partPercent(n.uptime), n.uptimeCost), COST),
			d(" avoidable eating", "90% (-22 ticks)", COST),
			d(" prayer", "100%"),
			d(" gear", "100%"),
			d(" stat boost", "98% (-16 dmg)"),
			taken("263 · 141 avoidable", "263"),
			// 16.2 s of eating, 13.0 of it avoidable: the neutral row keeps the rest.
			d(" eating", "5 ticks")), rows.subList(execution + 1, execution + 8));
	}

	@Test
	public void midFightNothingNewAndExecutionAsBefore()
	{
		// A scope that already reports a figure (a room whose earlier fight ended) still shows nothing while running.
		final FakeScope s = kill4();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		final List<Row> rows = build(s, State.fight("Vardorvis", false, false, false));
		assertTrue(rows.contains(main("Execution", LedgerRows.partPercent(n.executionLive))));
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.AVOIDABLE_EATING)));
		assertTrue(rows.contains(d(" damage taken", "263")));
		// All the eating, as T-038 left it: 16.2 s.
		assertTrue(rows.contains(d(" eating", "27 ticks")));
		// The trip and the raid while a fight runs, too.
		assertFalse(build(s, State.trip(3, true, false)).stream().anyMatch(r -> r.left.equals(LedgerRows.AVOIDABLE_EATING)));
		assertFalse(build(s, State.raid("Tombs of Amascut", 2, true, false)).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.AVOIDABLE_EATING)));
	}

	@Test
	public void anUnruledBossOrAnOldKillIsUnchanged()
	{
		final FakeScope s = kill4();
		s.avoidableEatingTicks = -1;
		s.damageTakenAvoidable = -1;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertTrue(Double.isNaN(n.avoidableEatingCost));
		assertTrue(Double.isNaN(n.avoidableEatingShare));
		assertEquals(n.executionLive, n.execution, 0);
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		assertTrue(rows.contains(main("Execution", LedgerRows.partPercent(n.executionLive))));
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.AVOIDABLE_EATING)));
		assertTrue(rows.contains(d(" damage taken", "263")));
		assertTrue(rows.contains(d(" eating", "27 ticks")));
	}

	@Test
	public void noneAvoidableReadsAHundredAndNoCost()
	{
		final FakeScope s = kill4();
		s.avoidableEatingTicks = 0;
		s.damageTakenAvoidable = 0;
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		assertTrue(rows.contains(d(" avoidable eating", "100%")));
		assertTrue(rows.contains(taken("263 · 0 avoidable", "263")));
		assertTrue(rows.contains(d(" eating", "27 ticks")));
		// And under Costing only, a part that cost nothing isn't listed.
		final Settings costing = new Settings();
		costing.parts = ExecutionParts.COSTING;
		assertFalse(LedgerRows.build(s, State.fight("Vardorvis", true, true, false), costing).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.AVOIDABLE_EATING)));
	}

	@Test
	public void avoidableEatingFollowsTheRowSettings()
	{
		final Settings costing = new Settings();
		costing.parts = ExecutionParts.COSTING;
		assertTrue(LedgerRows.build(kill4(), State.fight("Vardorvis", true, true, false), costing)
			.contains(d(" avoidable eating", "90% (-22 ticks)", COST)));
		final Settings off = new Settings();
		off.parts = ExecutionParts.OFF;
		final List<Row> rows = LedgerRows.build(kill4(), State.fight("Vardorvis", true, true, false), off);
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.AVOIDABLE_EATING) || r.left.equals(" damage taken")
			|| r.left.equals(LedgerRows.EATING)));
		// Execution still counts it with the rows hidden.
		final LedgerRows.Numbers n = new LedgerRows.Numbers(kill4());
		assertTrue(rows.contains(main("Execution", LedgerRows.partPercent(n.execution))));
	}

	@Test
	public void aNarrowPanelDropsTheAvoidablePartOfDamageTaken()
	{
		final Row taken = build(kill4(), State.fight("Vardorvis", true, true, false)).stream()
			.filter(r -> r.left.equals(" damage taken")).findFirst().get();
		assertEquals("263", taken.shortRight);
		final Row fitted = PvmPerformanceOverlay.fit(taken, t -> t.length() * 7, t -> t.length() * 7, 120);
		assertEquals("263", fitted.right);
	}

	/** A fight of {@code ticks} combat ticks, {@code eating} of them eating, ended, with T-041's figures when ruled. */
	private static Fight ruled(int ticks, int eating, double avoidableTicks, int avoidableHp, boolean rules,
		SessionTotals trip)
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordAttackMade(false);
		trip.recordAttackMade(false);
		for (int i = 1; i < ticks - eating; i++)
		{
			f.recordTickSpent();
			trip.recordTickSpent(true);
		}
		for (int i = 0; i < eating; i++)
		{
			f.recordTickLost(true);
			trip.recordTickLost(true, true);
		}
		f.recordDamageTaken(avoidableHp * 2, 1L);
		if (rules)
		{
			f.recordAvoidable(1, avoidableHp, java.util.Collections.singletonMap("axe", avoidableHp), avoidableTicks, 40,
				"default");
		}
		f.end(true, 1L);
		trip.recordFightEnded(true, f, 1L);
		return f;
	}

	@Test
	public void aRoomARaidAndATripSumTheirRuledFightsOnly()
	{
		final SessionTotals trip = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		// Two ruled kills (10 and 6 avoidable eating ticks) and one unruled fight, which says nothing.
		room.add(ruled(100, 20, 10, 50, true, trip));
		room.add(ruled(100, 10, 6, 30, true, trip));
		room.add(ruled(50, 5, 0, 0, false, trip));
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		for (LedgerRows.Scope scope : Arrays.asList(LedgerRows.of(room), LedgerRows.of(raid), LedgerRows.of(trip)))
		{
			assertEquals(16, scope.avoidableEatingTicks(), 1e-9);
			assertEquals(80, scope.damageTakenAvoidable());
			final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
			assertEquals(16 * 0.6, n.avoidableEatingCost, 1e-9);
			assertEquals(1 - 16.0 / scope.combatTicks(), n.avoidableEatingShare, 1e-12);
		}
		// A room with no ruled fight has no figure.
		final Encounter unruled = new Encounter("Goblin", null, 0L);
		unruled.add(ruled(50, 5, 0, 0, false, new SessionTotals(0L)));
		assertTrue(Double.isNaN(new LedgerRows.Numbers(LedgerRows.of(unruled)).avoidableEatingCost));
	}

	// ---- T-051: tracked defence, live and after the kill ----

	/** Vardorvis-like numbers on a drainable boss: Defence 215, floor 0; Magic 150. */
	private static LedgerRows.LiveDrain live(int defence, int floor, int magic)
	{
		return new LedgerRows.LiveDrain(defence, 215, floor, magic, 150, 0);
	}

	private static List<Row> running(FakeScope s, LedgerRows.LiveDrain drain)
	{
		return build(s, State.fight("Vardorvis", false, false, false).withDrain(drain));
	}

	/** The rows right after Damage/expected. */
	private static List<Row> afterDamage(List<Row> rows, int count)
	{
		for (int i = 0; i < rows.size(); i++)
		{
			if (rows.get(i).left.equals("Damage/expected"))
			{
				return rows.subList(i + 1, i + 1 + count);
			}
		}
		throw new AssertionError(rows.toString());
	}

	@Test
	public void liveNothingUntilADrainIsTracked()
	{
		assertFalse(running(LedgerExamples.midFight(), null).stream().anyMatch(r -> r.left.equals(LedgerRows.DEFENCE)));
		// Tracked but nothing taken off yet (or all of it back): still nothing.
		assertFalse(running(LedgerExamples.midFight(), live(215, 0, 150)).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.DEFENCE)));
	}

	@Test
	public void liveTrackedOverBaseUnderDamageExpected()
	{
		final List<Row> rows = running(LedgerExamples.midFight(), live(145, 0, 150));
		// The defence row stays under Damage/expected; Luck follows it (Eric, 2026-10-28).
		assertEquals(d(" defence", "145 / 215"), afterDamage(rows, 1).get(0));
		assertEquals("Luck", afterDamage(rows, 2).get(1).left);
	}

	@Test
	public void liveAtTheFloorAndMagicWhenDrained()
	{
		final List<Row> rows = running(LedgerExamples.midFight(), live(150, 150, 120));
		assertEquals(Arrays.asList(d(" defence", "150 (floor)"), d(" magic", "120 / 150")), afterDamage(rows, 2));
		assertEquals("0 (floor)", LedgerRows.level(0, 215, 0));
	}

	@Test
	public void liveShowsTheLiveRowEvenWhereTheScopeHasAnEndedFightsPath()
	{
		final FakeScope s = drained();
		final List<Row> rows = running(s, live(180, 0, 150));
		assertEquals(d(" defence", "180 / 215"), afterDamage(rows, 1).get(0));
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.SPECS)));
	}

	/** The kill after two hammers, one landed: 215 to 145, the drains worth 182 damage (14 s), the specs 65 (5 s). */
	private static FakeScope drained()
	{
		final FakeScope s = LedgerExamples.kill();
		s.defenceStart = 215;
		s.defenceEnd = 145;
		s.magicStart = 150;
		s.magicEnd = 150;
		s.secondsPerDamage = 14.0 / 182;
		s.drainValueDamage = 182;
		s.specLuckDamage = 65;
		return s;
	}

	@Test
	public void afterTheKillThePathTheWorthAndTheSpecs()
	{
		final List<Row> rows = build(drained(), State.fight("Vardorvis", true, true, false));
		// T-075: in words, as Luck is, the worth on a row of its own under the path.
		assertEquals(Arrays.asList(d(" defence", "215 \u2192 145"), d(" drains", "14 s faster")), afterDamage(rows, 2));
		// Under Luck: 5 s is over a second and 2% of the 136.8 s clock, so Better.
		final int luck = rows.indexOf(rows.stream().filter(r -> r.left.equals("Luck")).findFirst().get());
		assertEquals(d(" specs", "5 s faster", BETTER), rows.get(luck + 1));
	}

	@Test
	public void specsThatCostTimeReadMinus()
	{
		final FakeScope s = drained();
		s.specLuckDamage = -65;
		assertTrue(build(s, State.fight("Vardorvis", true, true, false)).contains(d(" specs", "5 s slower", COST)));
	}

	@Test
	public void noOwnSpecialsNoSpecsRow()
	{
		// The party drained it: the drains are worth time, but no one's luck.
		final FakeScope s = drained();
		s.specLuckDamage = 0;
		final List<Row> rows = build(s, State.fight("Vardorvis", true, true, false));
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.SPECS)));
		assertTrue(rows.contains(d(" drains", "14 s faster")));
		// Under half a second: hidden too.
		s.specLuckDamage = 6;
		assertFalse(build(s, State.fight("Vardorvis", true, true, false)).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.SPECS)));
	}

	@Test
	public void magicDrainedAfterTheKill()
	{
		final FakeScope s = drained();
		s.magicEnd = 120;
		assertEquals(Arrays.asList(d(" defence", "215 \u2192 145"), d(" magic", "150 \u2192 120"),
			d(" drains", "14 s faster")), afterDamage(build(s, State.fight("Vardorvis", true, true, false)), 3));
		// Magic alone drained: its path, then the worth.
		s.defenceEnd = 215;
		assertEquals(Arrays.asList(d(" magic", "150 \u2192 120"), d(" drains", "14 s faster")),
			afterDamage(build(s, State.fight("Vardorvis", true, true, false)), 2));
	}

	@Test
	public void anImmuneOrUntrackedBossOrAnOldKillShowsNothing()
	{
		// Old kill, or nothing drainable: every figure unknown.
		final List<Row> old = build(LedgerExamples.kill(), State.fight("Vardorvis", true, true, false));
		assertFalse(old.stream().anyMatch(r -> r.left.equals(LedgerRows.DEFENCE) || r.left.equals(LedgerRows.SPECS)
			|| r.left.equals(LedgerRows.MAGIC)));
		// An immune boss: its Defence never moves and the drains are worth nothing.
		final FakeScope immune = drained();
		immune.defenceEnd = 215;
		immune.drainValueDamage = 0;
		immune.specLuckDamage = 0;
		assertFalse(build(immune, State.fight("Vardorvis", true, true, false)).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.DEFENCE) || r.left.equals(LedgerRows.SPECS)));
		// Live: the tracker's immune target gives no readout.
		final DrainRule.Target target = new DrainRule.Target(215, 150, 0, 0, 0, 0, 215, 150, true, false, false, false);
		final DrainTracker.Tracked t = new DrainTracker.Tracked(5, 12223, "Vardorvis", target);
		assertNull(PvmPerformanceOverlay.liveDrain(t));
		assertNull(PvmPerformanceOverlay.liveDrain((DrainTracker.Tracked) null));
	}

	@Test
	public void theTrackersFiguresBecomeTheLiveRow()
	{
		final DrainRule.Target target = new DrainRule.Target(215, 150, 0, 0, 0, 0, 0, 0, false, false, false, false);
		final DrainTracker.Tracked t = new DrainTracker.Tracked(5, 12223, "Vardorvis", target);
		// Not yet drained.
		assertNull(PvmPerformanceOverlay.liveDrain(t));
		t.state = new DrainRule.State(145, 150, 0, 0, 0, 0);
		final LedgerRows.LiveDrain live = PvmPerformanceOverlay.liveDrain(t);
		assertEquals(145, live.defence);
		assertEquals(215, live.defenceBase);
		assertEquals(0, live.defenceFloor);
	}

	@Test
	public void underTheWarningNothingOfTheDrains()
	{
		final List<Row> rows = build(drained(), State.fight("Vardorvis", true, true, true));
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.DEFENCE) || r.left.equals(LedgerRows.SPECS)));
		assertFalse(build(LedgerExamples.midFight(), State.fight("Vardorvis", false, false, true)
			.withDrain(live(145, 0, 150))).stream().anyMatch(r -> r.left.equals(LedgerRows.DEFENCE)));
	}

	@Test
	public void specsFollowTheLuckSetting()
	{
		final Settings noLuck = new Settings();
		noLuck.luck = false;
		final List<Row> rows = LedgerRows.build(drained(), State.fight("Vardorvis", true, true, false), noLuck);
		assertFalse(rows.stream().anyMatch(r -> r.left.equals(LedgerRows.SPECS)));
		// The Defence rows aren't luck: they stay.
		assertTrue(rows.contains(d(" defence", "215 \u2192 145")));
		assertTrue(rows.contains(d(" drains", "14 s faster")));
	}

	@Test
	public void theDrainsInWordsDontWidenTheBoxInAnyFont()
	{
		// T-075: "215 → 145 · 14 s faster" on one row would have widened the box in the Small font; on two rows the box is
		// as wide as the same kill without drains, and a narrow saved width leaves their figures whole.
		final FakeScope plain = drained();
		plain.defenceStart = -1;
		plain.defenceEnd = -1;
		plain.magicStart = -1;
		plain.magicEnd = -1;
		plain.drainValueDamage = Double.NaN;
		plain.specLuckDamage = Double.NaN;
		final java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
			.createGraphics();
		for (java.awt.Font font : Arrays.asList(net.runelite.client.ui.FontManager.getRunescapeFont(),
			net.runelite.client.ui.FontManager.getRunescapeBoldFont(), net.runelite.client.ui.FontManager.getRunescapeSmallFont()))
		{
			g.setFont(font);
			final int with = PvmPerformanceOverlay.lines(g, build(drained(), State.fight("Vardorvis", true, true, false)),
				null).width;
			final int without = PvmPerformanceOverlay.lines(g, build(plain, State.fight("Vardorvis", true, true, false)),
				null).width;
			assertEquals(font.getName(), without, with);
			final List<Row> narrow = PvmPerformanceOverlay.lines(g, build(drained(), State.fight("Vardorvis", true, true,
				false)), new java.awt.Dimension(140, 0)).rows;
			assertTrue(narrow.contains(d(" drains", "14 s faster")));
			assertTrue(narrow.contains(d(" defence", "215 \u2192 145")));
		}
	}

	@Test
	public void aRaidOrATripShowsTheDrainsWorth()
	{
		final FakeScope s = drained();
		s.defenceStart = -1;
		s.defenceEnd = -1;
		s.magicStart = -1;
		s.magicEnd = -1;
		s.drainValueDamage = 520;
		for (State state : Arrays.asList(State.trip(3, false, false), State.raid("Tombs of Amascut", 3, false, false)))
		{
			final List<Row> rows = build(s, state);
			assertTrue(rows.toString(), rows.contains(d(" defence drains", "40 s faster")));
			assertTrue(rows.contains(d(" specs", "5 s faster", BETTER)));
		}
		// While a fight runs: only the live row, if any.
		assertFalse(build(s, State.trip(3, true, false)).stream().anyMatch(r -> r.left.equals(LedgerRows.DEFENCE_DRAINS)));
	}

	/** A scored fight on Defence 215 that ended at {@code end}, worth {@code value} damage, {@code specLuck} of it luck. */
	private static Fight drainedFight(int end, double value, double specLuck, SessionTotals trip)
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordAttackMade(false);
		trip.recordAttackMade(false);
		for (int i = 0; i < 99; i++)
		{
			f.recordTickSpent();
			trip.recordTickSpent(true);
		}
		f.recordExpected(40, 0.7, 14, 150);
		trip.recordExpected(0.7, 14, 150);
		final DrainRecord r = new DrainRecord();
		r.defenceBase = 215;
		r.defenceStart = 215;
		r.defenceEnd = end;
		r.magicBase = 150;
		r.magicStart = 150;
		r.magicEnd = 150;
		f.recordDrain(r);
		// No variance shift; spec luck is -expectedShift.
		f.recordSpecOutcomes(-specLuck, 0, value, value - specLuck);
		f.end(true, 1L);
		trip.recordFightEnded(true, f, 1L);
		return f;
	}

	@Test
	public void aRoomARaidAndATripSumTheDrainsAndTheSpecs()
	{
		final SessionTotals trip = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(drainedFight(180, 100, 20, trip));
		room.add(drainedFight(145, 82, -5, trip));
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		for (LedgerRows.Scope scope : Arrays.asList(LedgerRows.of(room), LedgerRows.of(raid), LedgerRows.of(trip)))
		{
			assertEquals(182, scope.drainValueDamage(), 1e-9);
			assertEquals(15, scope.specLuckDamage(), 1e-9);
			// The seconds as luck turns damage into time: the scored clock over expected plus other damage.
			final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
			assertEquals(n.scoredClock * 182 / (n.expected + n.other), scope.luckSeconds(182), 1e-9);
		}
		// The room's path: the first fight's start to the last fight's end.
		assertEquals(215, LedgerRows.of(room).defenceStart());
		assertEquals(145, LedgerRows.of(room).defenceEnd());
		assertEquals(-1, LedgerRows.of(raid).defenceStart());
	}
}

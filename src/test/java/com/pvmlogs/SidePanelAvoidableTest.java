package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-044: avoidable eating and damage taken by attack in the side panel, after a
 * ruled fight only. Figures: Eric's 16:03 kill 4 as T-041 replays it (#46).
 */
public class SidePanelAvoidableTest
{
	private static PanelSnapshot.FightTab tab(SidePanelModel.Input in)
	{
		return SidePanelModel.fight(in);
	}

	private static LedgerRows.Numbers numbers(SidePanelModel.Input in)
	{
		return new LedgerRows.Numbers(in.scope);
	}

	private static List<String> rowLabels(PanelSnapshot.TimeBudget b)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.BudgetRow r : b.rows)
		{
			out.add(r.label.text + " " + r.value.text);
		}
		return out;
	}

	// ---- 1. Where the time went ----

	@Test
	public void theBudgetSplitsEatingIntoAvoidableAndTheRest()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		final LedgerRows.Numbers n = numbers(in);
		assertEquals(10.8, n.avoidableEatingCost, 1e-9);
		assertEquals(16.2, n.eatingCost, 1e-9);
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		// Ca, a cost, then the rest of the eating, neutral, both after uptime.
		final List<PanelSnapshot.Segment> s = b.segments;
		assertEquals(PanelSnapshot.Fill.KEPT, s.get(0).fill);
		assertTrue(s.get(2).fill == PanelSnapshot.Fill.COST || s.get(2).fill == PanelSnapshot.Fill.MINOR_COST);
		assertEquals(10.8, s.get(2).seconds, 1e-9);
		assertEquals(PanelSnapshot.Fill.EATING, s.get(3).fill);
		assertEquals(16.2 - 10.8, s.get(3).seconds, 1e-9);
		final List<String> rows = rowLabels(b);
		assertTrue(rows.toString(), rows.contains("avoidable eating +18 ticks"));
		assertTrue(rows.toString(), rows.contains("eating +9 ticks"));
		assertTrue(rows.indexOf("avoidable eating +18 ticks") < rows.indexOf("eating +9 ticks"));
		// The row is in Cost when it's costing, as the other parts are.
		for (PanelSnapshot.BudgetRow r : b.rows)
		{
			if (r.label.text.equals("avoidable eating"))
			{
				assertEquals(LedgerRows.isCosting(10.8, n.clock) ? SidePanelStyle.COST : SidePanelStyle.BODY, r.value.colour);
			}
		}
		// Best play doesn't move: the same as without the split.
		assertEquals(tab(SidePanelExamples.unruledKill()).budget.rows.get(0).value.text, b.rows.get(0).value.text);
		assertEquals(b.total, b.sum(), 1e-9);
	}

	@Test
	public void whenLuckSavedTimeBothStayInsideYourTime()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		// Damage well above expected: luck saved time.
		((LedgerExamples.FakeScope) in.scope).damage = 900;
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertTrue(b.luckSaved > 0);
		final List<PanelSnapshot.Segment> s = b.segments;
		// Everything before the last segment adds up to your clock; the last is the best play luck saved.
		double before = 0;
		for (int i = 0; i < s.size() - 1; i++)
		{
			before += s.get(i).seconds;
		}
		assertEquals(b.clock, before, 1e-9);
		assertEquals(PanelSnapshot.Fill.KEPT, s.get(s.size() - 1).fill);
	}

	// ---- 2. The Execution card, 3. the tile ----

	@Test
	public void executionGainsAFifthBarAfterUptime()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		final LedgerRows.Numbers n = numbers(in);
		final PanelSnapshot.Parts parts = tab(in).execution;
		assertEquals(5, parts.parts.size());
		assertEquals("uptime", parts.parts.get(0).label.text);
		assertEquals("avoidable eating", parts.parts.get(1).label.text);
		assertEquals(LedgerRows.withSeconds(LedgerRows.partPercent(1 - 18.0 / 216), 10.8), parts.parts.get(1).value.text);
		// The card's % is n.execution, which has it.
		assertEquals(LedgerRows.partPercent(n.execution), parts.hint.text);
		assertTrue(n.execution < n.executionLive);
	}

	@Test
	public void theTilesSecondsLostIncludeAvoidableEating()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		final LedgerRows.Numbers n = numbers(in);
		final PanelSnapshot.Tile execution = tab(in).tiles.get(3);
		assertEquals(LedgerRows.partPercent(n.execution), execution.value.text);
		final double lost = SidePanelModel.lostSeconds(n, false) + 10.8;
		assertEquals(SidePanelStyle.ticks(lost) + " lost", execution.sub.text);
		assertEquals(lost, SidePanelModel.lostSeconds(n, true), 1e-9);
	}

	// ---- 4. Damage taken ----

	@Test
	public void damageTakenIsByAttackBiggestFirstEachAvoidableOneMarked()
	{
		final PanelSnapshot.Rows taken = tab(SidePanelExamples.ruledKill(true)).taken;
		// T-112: the rows under their subheadings, the subheadings left out.
		final List<PanelSnapshot.Row> facts = facts(taken.rows);
		final List<String> rows = new ArrayList<>();
		for (PanelSnapshot.Row r : facts)
		{
			rows.add(r.toString());
		}
		assertEquals("263", taken.hint.text);
		// Biggest first; among the two 30s, the order they were taken in. Prayed melee isn't avoidable; off prayer is.
		assertEquals("melee | 122", rows.get(0));
		assertEquals("spike [avoidable] | 49", rows.get(1));
		assertEquals("melee off prayer [avoidable] | 30", rows.get(2));
		assertEquals("bleed [avoidable] | 30", rows.get(3));
		assertEquals("axe [avoidable] | 26", rows.get(4));
		assertEquals("head [avoidable] | 6", rows.get(5));
		// Then the avoidable part: 134 of 263.
		assertEquals("avoidable | 134 (51%)", rows.get(6));
		assertEquals(SidePanelStyle.COST, facts.get(6).right.colour);
		// The words carry it, and Cost colours them.
		assertEquals(SidePanelStyle.COST, facts.get(1).hint.colour);
		// The rows from before follow.
		assertTrue(rows.get(7).startsWith("times hit | 21"));
	}

	@Test
	public void unnamedShowsOnlyWhenThereIsSome()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		final Map<String, Integer> by = new LinkedHashMap<>(in.takenByAttack);
		by.put("unnamed", 0);
		in.takenByAttack = by;
		for (PanelSnapshot.Row r : tab(in).taken.rows)
		{
			assertFalse(r.toString(), r.left.text.equals("unnamed"));
		}
		by.put("unnamed", 12);
		boolean seen = false;
		for (PanelSnapshot.Row r : tab(in).taken.rows)
		{
			seen |= r.toString().equals("unnamed | 12");
		}
		assertTrue(seen);
	}

	// ---- 6. Nothing new mid-fight; unruled unchanged ----

	@Test
	public void nothingNewWhileTheScopeRuns()
	{
		// A room still running that holds an ended ruled fight reports the figures; they wait (Jagex's rule).
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(false);
		final LedgerRows.Numbers n = numbers(in);
		assertFalse(Double.isNaN(n.avoidableEatingCost));
		final PanelSnapshot.FightTab t = tab(in);
		assertEquals(PanelSnapshot.State.LIVE, t.state);
		assertNull(t.budget);
		assertEquals(4, t.execution.parts.size());
		assertEquals(LedgerRows.partPercent(n.executionLive), t.execution.hint.text);
		assertEquals(LedgerRows.partPercent(n.executionLive), t.tiles.get(3).value.text);
		assertEquals(SidePanelStyle.ticks(SidePanelModel.lostSeconds(n, false)) + " lost", t.tiles.get(3).sub.text);
		for (PanelSnapshot.Row r : t.taken.rows)
		{
			assertTrue(r.toString(), r.hint.isEmpty());
			assertFalse(r.toString(), r.left.text.equals("avoidable") || r.left.text.equals("melee"));
		}
	}

	@Test
	public void anUnruledKillIsUnchanged()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelExamples.unruledKill());
		assertEquals(4, t.execution.parts.size());
		final List<String> rows = rowLabels(t.budget);
		assertTrue(rows.toString(), rows.contains("eating +27 ticks"));
		for (String r : rows)
		{
			assertFalse(r, r.startsWith("avoidable"));
		}
		assertEquals("times hit | 21", t.taken.rows.get(0).toString());
		// Every eating second is one stretch, as T-038 drew it.
		int eating = 0;
		for (PanelSnapshot.Segment s : t.budget.segments)
		{
			eating += s.fill == PanelSnapshot.Fill.EATING ? 1 : 0;
		}
		assertEquals(1, eating);
	}

	// ---- 4. The trip's sums ----

	@Test
	public void theTripSumsTheAttacks()
	{
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordFightEnded(false, ruled(122, 26, 30, 18.0, 100), 100_000L);
		trip.recordFightEnded(false, ruled(80, 14, 10, 6.0, 40), 200_000L);
		assertEquals(140, trip.getDamageTakenAvoidable());
		assertEquals(24.0, trip.getAvoidableEatingTicks(), 1e-9);
		// The trip's other totals come from the plugin's events as they happen; these stand in for them.
		final LedgerExamples.FakeScope scope = LedgerExamples.kill();
		scope.damageTaken = 282;
		scope.damageTakenAvoidable = trip.getDamageTakenAvoidable();
		scope.avoidableEatingTicks = trip.getAvoidableEatingTicks();
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = scope;
		in.ended = true;
		in.takenByAttack = trip.getDamageTakenByAttack();
		in.avoidableAttacks = SidePanelModel.avoidableAttacks(AvoidableReplayTest.vardorvis());
		final PanelSnapshot.TripTab t = SidePanelModel.trip(in);
		final List<String> rows = new ArrayList<>();
		for (PanelSnapshot.Row r : facts(t.taken.rows))
		{
			rows.add(r.toString());
		}
		assertEquals(rows.toString(), "melee | 202", rows.get(0));
		// Equal totals keep the order they were first taken in.
		assertEquals("axe [avoidable] | 40", rows.get(1));
		assertEquals("bleed [avoidable] | 40", rows.get(2));
		assertEquals("avoidable | 140 (50%)", rows.get(3));
		// A fight running on the trip: the split waits for it to end, as on the overlay.
		in.ended = false;
		for (PanelSnapshot.Row r : SidePanelModel.trip(in).taken.rows)
		{
			assertFalse(r.toString(), r.left.text.equals("avoidable"));
		}
	}

	/** T-112: the rows without their subheadings. */
	private static List<PanelSnapshot.Row> facts(List<PanelSnapshot.Row> rows)
	{
		final List<PanelSnapshot.Row> out = new ArrayList<>();
		for (PanelSnapshot.Row r : rows)
		{
			if (!r.heading)
			{
				out.add(r);
			}
		}
		return out;
	}

	/** An ended fight at Vardorvis with T-041's figures: melee, axe and bleed taken, the avoidable part and its eating. */
	private static Fight ruled(int melee, int axe, int bleed, double eatingTicks, int avoidable)
	{
		final Fight f = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		f.recordAttackMade(true);
		for (int i = 0; i < 300; i++)
		{
			f.recordTickSpent();
		}
		for (int i = 0; i < 30; i++)
		{
			f.recordTickLost(true);
		}
		f.recordHitTaken(melee + axe + bleed, false, 0L);
		final Map<String, Integer> by = new LinkedHashMap<>();
		by.put("melee", melee);
		by.put("axe", axe);
		by.put("bleed", bleed);
		f.recordAvoidable(1, avoidable, by, eatingTicks, 29, AvoidableEating.LINE_THIS_KILL);
		f.end(true, 100_000L);
		return f;
	}

	// ---- 5. History ----

	@Test
	public void historysExecutionIsOverRuledKillsOnlyOnceThereAreAny()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final LedgerHistory.Like like = LedgerHistory.Like.of(SidePanelHistory.kills(h, "Vardorvis",
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE).get(0).room);
		// None ruled: over every kill, as before.
		final SidePanelHistory.Usual all = SidePanelHistory.usual(h, like);
		assertEquals(all.kills.size(), all.executionKills);

		// Three of the newest ruled: no median yet, "from 5 kills (3 so far)".
		final List<Fight> vard = new ArrayList<>();
		for (Fight f : h)
		{
			if (f.getTargetName().equals("Vardorvis"))
			{
				vard.add(f);
			}
		}
		for (int i = 0; i < 3; i++)
		{
			vard.get(i).recordAvoidable(1, 20, new LinkedHashMap<>(), 2.0, 29, AvoidableEating.LINE_THIS_KILL);
		}
		final SidePanelHistory.Usual three = SidePanelHistory.usual(h, like);
		assertEquals(3, three.executionKills);
		assertTrue(Double.isNaN(three.execution));
		final List<PanelSnapshot.Tile> tiles = SidePanelHistory.tiles(three, three.kills.size());
		assertEquals(LedgerRows.UNKNOWN, tiles.get(2).value.text);
		assertEquals("from 5 kills (3 so far)", tiles.get(2).sub.text);
		// The other tiles still read over every like kill.
		assertFalse(LedgerRows.UNKNOWN.equals(tiles.get(0).value.text));

		// Six ruled: the median of those six only.
		for (int i = 3; i < 6; i++)
		{
			vard.get(i).recordAvoidable(1, 20, new LinkedHashMap<>(), 2.0, 29, AvoidableEating.LINE_THIS_KILL);
		}
		final SidePanelHistory.Usual six = SidePanelHistory.usual(h, like);
		assertEquals(6, six.executionKills);
		final double[] exec = new double[6];
		for (int i = 0; i < 6; i++)
		{
			exec[i] = new LedgerRows.Numbers(LedgerRows.of(roomOf(six.kills, vard.get(i)))).execution;
		}
		java.util.Arrays.sort(exec);
		assertEquals(LedgerHistory.quantile(exec, 0.5), six.execution, 1e-9);
		assertEquals("median", SidePanelHistory.tiles(six, six.kills.size()).get(2).sub.text);
	}

	private static Encounter roomOf(List<Encounter> rooms, Fight f)
	{
		for (Encounter e : rooms)
		{
			if (e.getFights().contains(f))
			{
				return e;
			}
		}
		throw new AssertionError("no room for " + f);
	}
}

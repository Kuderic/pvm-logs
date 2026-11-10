package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-033 item 6: the side panel's figures are the overlay's. Each state is built
 * from the overlay's own fixture scopes ({@link LedgerExamples}) and every
 * tile, part and word is checked against what {@link LedgerRows} puts on the
 * overlay for the same scope, so the two can't drift apart. Then the time
 * budget's arithmetic (spec section 4) and the luck-removed average.
 */
public class SidePanelModelTest
{
	private static PanelSnapshot.FightTab tab(SidePanelModel.Input in)
	{
		return SidePanelModel.fight(in);
	}

	/** The overlay's rows for this scope and state, with every setting on. */
	private static List<LedgerRows.Row> overlay(LedgerRows.Scope s, LedgerRows.State state)
	{
		return LedgerRows.build(s, state, new LedgerRows.Settings());
	}

	private static LedgerRows.Row row(List<LedgerRows.Row> rows, String left)
	{
		for (LedgerRows.Row r : rows)
		{
			if (r.left.equals(left))
			{
				return r;
			}
		}
		throw new AssertionError("no overlay row " + left + " in " + rows);
	}

	private static PanelSnapshot.Part part(PanelSnapshot.FightTab t, String label)
	{
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			if (p.label.text.equals(label))
			{
				return p;
			}
		}
		throw new AssertionError("no part " + label);
	}

	private static LedgerRows.Numbers n0(SidePanelModel.Input in)
	{
		return new LedgerRows.Numbers(in.scope);
	}

	/** T-199's words for the overlay's parts, on Skill's bars. */
	private static final String[][] PART_WORDS = {{"uptime", "Uptime"}, {"prayer", "Offensive prayers"},
		{"gear", "Gear"}, {"stat boost", "Boosts"}};

	/**
	 * The figures say what the overlay says. T-202: no tiles in any view; a room's are on its card, and every view's
	 * sections carry theirs in their headers' lines.
	 */
	private static void sameAsOverlay(SidePanelModel.Input in, LedgerRows.State state)
	{
		final PanelSnapshot.FightTab t = tab(in);
		assertNull(t.tiles);
		final List<LedgerRows.Row> rows = overlay(in.scope, state);
		final LedgerRows.Numbers n = n0(in);

		// The overlay's "bottom 20% (14 s slower)": the band is Luck's line (after the fight) and the card's word.
		final LedgerRows.Row luck = row(rows, "Luck");
		if (!Double.isNaN(n.percentile))
		{
			assertTrue(luck.right, luck.right.startsWith(LedgerRows.band(n.percentile) + " ("));
			if (t.curve != null)
			{
				assertEquals(SidePanelModel.luckText(n.percentile), t.summaries.get("curve").text);
				// T-194: in luck's violet by the roll's strength; the overlay keeps its band colours.
				assertEquals(SidePanelModel.luckInk(n.percentile), t.summaries.get("curve").colour);
			}
		}

		// Damage's line holds the overlay's total.
		final String damage = row(rows, "Damage/expected").right.split(" ")[0];
		assertTrue(t.summaries.get("damage").text, t.summaries.get("damage").text.replace(",", "")
			.contains(damage.replace(",", "").split("/")[0]));

		// The card's DPS reads the same Numbers the overlay's rows do (T-200's rate).
		if (t.card != null)
		{
			final SidePanelModel.Rate r = SidePanelModel.rate(n, in);
			assertEquals(Double.isNaN(r.dps) ? null : LedgerRows.oneDecimal(r.dps), t.card.dps);
		}
		assertEquals(row(rows, "Skill").right, t.execution.hint.text);

		for (String[] words : PART_WORDS)
		{
			final LedgerRows.Row r = row(rows, " " + words[0]);
			final PanelSnapshot.Part p = part(t, words[1]);
			// T-117: the overlay prices the setup parts in damage (`97% (-45 dmg)`); the share before it is the same.
			assertEquals(words[0], r.right.split(" \\(")[0], p.value.text);
		}
	}

	@Test
	public void liveFiguresAreTheOverlays()
	{
		sameAsOverlay(SidePanelExamples.live(), LedgerRows.State.fight("Vardorvis", false, false, false));
	}

	@Test
	public void killFiguresAreTheOverlays()
	{
		sameAsOverlay(SidePanelExamples.dead(), LedgerRows.State.fight("Vardorvis", true, true, false));
	}

	@Test
	public void endedFiguresAreTheOverlays()
	{
		sameAsOverlay(SidePanelExamples.ended(), LedgerRows.State.fight("Vardorvis", true, false, false));
	}

	@Test
	public void raidFiguresAreTheOverlays()
	{
		sameAsOverlay(SidePanelExamples.raid(), LedgerRows.State.raid("Tombs of Amascut", 3, false, false));
	}

	@Test
	public void killReadsAsTheCanvasHasIt()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelExamples.dead());
		assertEquals(PanelSnapshot.State.DEAD, t.state);
		assertEquals(" (dead)", t.header.suffix.text);
		assertEquals(SidePanelStyle.DEAD, t.header.suffix.colour);
		assertEquals("700 HP · solo · 3:31 pm", t.header.sub.text);
		assertEquals("2:16", t.header.clock.text);
		// T-202: no tiles; their figures are the card's and the sections' lines.
		assertNull(t.tiles);
		assertEquals("bottom 20%", t.summaries.get("curve").text);
		assertEquals("-14 s", t.card.luckTime);
		assertEquals("Thrall", t.damage.rows.get(2).name);
		assertEquals("80", t.damage.rows.get(2).damage);
		// T-134: the DPS counts the thrall; its hover keeps the weapon's alone.
		assertTrue(t.card.dpsTip, t.card.dpsTip.contains("weapon 5.0, thrall 0.6"));
		// Uptime's 15 idle seconds and stat boost 2.4 s: 17.4 s, rounded. Eating's 4.2 s isn't one of Execution's parts
		// (T-038).
		assertEquals("87%", t.card.skill);
		assertEquals("-17 s", t.card.lost);
		// Luck's line takes its colour: bottom 20%, luck's dim violet (T-194).
		assertEquals(SidePanelStyle.LUCK_DIM, t.summaries.get("curve").colour);
		assertNotNull(t.curve);
		assertEquals("unlucky", t.curve.headerValue.text);
		assertNull(t.loadout);
	}

	@Test
	public void theOverlaysPartOrder()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelExamples.live());
		final List<String> labels = new ArrayList<>();
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			labels.add(p.label.text);
		}
		// T-199's words.
		assertEquals(Arrays.asList("Uptime", "Offensive prayers", "Gear", "Boosts"), labels);
	}

	// ---- the time budget (spec section 4) ----

	@Test
	public void ericsKillsBestPlayIs141()
	{
		// B = 136.8 - 14.35 - 19.2 - 2.35 = 100.9 s: 1:41 (spec section 4's check).
		final PanelSnapshot.TimeBudget b = tab(SidePanelExamples.dead()).budget;
		assertNotNull(b);
		assertEquals("perfect", b.rows.get(0).label.text);
		assertEquals("1:41", b.rows.get(0).value.text);
	}

	@Test
	public void theBudgetSumsToTheClockWhenLuckCostTime()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertTrue(n.luck < 0);
		assertEquals(n.clock, b.sum(), 1e-9);
		assertEquals(n.clock, b.total, 1e-9);
		assertEquals(0, b.luckSaved, 0);
		// Best play, then uptime's 15 idle seconds, eating's 4.2 (T-038: 19.2 s between them, as before), stat boost
		// 2.35 s, then luck's 14.35 s: 157 + 23.4 + 6.5 + 3.7 + 22 px of 213. Best play doesn't move.
		final double[] px = new double[b.segments.size()];
		for (int i = 0; i < px.length; i++)
		{
			px[i] = b.segments.get(i).seconds / b.total * 213;
		}
		assertEquals(157, px[0], 0.6);
		assertEquals(23.4, px[1], 0.1);
		assertEquals(PanelSnapshot.Fill.EATING, b.segments.get(2).fill);
		assertEquals(6.5, px[2], 0.1);
		assertEquals(3.7, px[3], 0.1);
		assertEquals(22, px[6], 0.6);
	}

	@Test
	public void theBudgetRunsPastTheClockWhenLuckSavedTime()
	{
		final SidePanelModel.Input in = SidePanelExamples.raid();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertTrue(n.luck > 0);
		assertEquals(n.clock + n.luck, b.sum(), 1e-9);
		assertEquals(n.clock + n.luck, b.total, 1e-9);
		assertEquals(n.luck, b.luckSaved, 1e-9);
		// The raid example: 552 + 13 - 75 = 490 s, 8:10 (spec section 4).
		assertEquals("8:10", b.rows.get(0).value.text);
		assertEquals(SidePanelModel.RAID_NO_AVERAGE, b.note);
		assertTrue(b.average.isEmpty());
	}

	@Test
	public void theBudgetCountsUnscoredAdds()
	{
		final SidePanelModel.Input in = SidePanelExamples.unscoredAdds();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertEquals(n.clock, b.sum(), 1e-9);
		final PanelSnapshot.Segment adds = b.segments.get(b.segments.size() - 1);
		assertEquals(PanelSnapshot.Fill.UNSCORED, adds.fill);
		assertEquals(n.clock - n.scoredClock, adds.seconds, 1e-9);
		final PanelSnapshot.BudgetRow last = b.rows.get(b.rows.size() - 1);
		assertEquals("adds", last.label.text);
		assertEquals("-31 s", last.value.text);
		assertEquals("perfect", b.rows.get(0).label.text);
	}

	@Test
	public void averageAfterAKillOnly()
	{
		final PanelSnapshot.TimeBudget dead = tab(SidePanelExamples.dead()).budget;
		assertEquals(2, dead.average.size());
		// T-196's plain rows: your usual, and this kill's clock with average luck.
		assertEquals("usual", dead.average.get(0).left.text);
		assertEquals("1:54", dead.average.get(0).right.text);
		assertEquals("with average luck", dead.average.get(1).left.text);
		assertEquals("2:02", dead.average.get(1).right.text);
		assertEquals(114, dead.marker, 0);

		final PanelSnapshot.TimeBudget ended = tab(SidePanelExamples.ended()).budget;
		assertTrue(ended.average.isEmpty());
		assertEquals(SidePanelModel.NOT_A_KILL, ended.note);
		assertTrue(Double.isNaN(ended.marker));
	}

	@Test
	public void luckInTheBudgetUsesTheTilesWords()
	{
		// T-199's sign rule: + is time gained, - time lost.
		final PanelSnapshot.TimeBudget dead = tab(SidePanelExamples.dead()).budget;
		final PanelSnapshot.BudgetRow slower = luckRow(dead);
		assertEquals("luck", slower.label.text);
		assertEquals("-14 s", slower.value.text);
		// T-194: in the Luck tile's colour, the roll's strength (rolled 20, dim).
		assertEquals(SidePanelStyle.LUCK_DIM, slower.value.colour);

		// The raid's luck saved 13 s, in the top 15%.
		final PanelSnapshot.BudgetRow faster = luckRow(tab(SidePanelExamples.raid()).budget);
		assertEquals("luck", faster.label.text);
		assertEquals("+13 s", faster.value.text);
		assertEquals(PanelSnapshot.Fill.LUCK_FASTER, faster.swatch);

		// In the middle half: still "luck", its swatch the average one.
		final SidePanelModel.Input mid = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) mid.scope).damage = 770;
		final PanelSnapshot.BudgetRow average = luckRow(tab(mid).budget);
		assertEquals("luck", average.label.text);
		assertEquals(PanelSnapshot.Fill.LUCK_AVERAGE, average.swatch);
	}

	private static PanelSnapshot.BudgetRow luckRow(PanelSnapshot.TimeBudget b)
	{
		for (PanelSnapshot.BudgetRow r : b.rows)
		{
			if (r.label.text.startsWith("luck"))
			{
				return r;
			}
		}
		throw new AssertionError("no luck row");
	}

	// ---- the guardrails (spec section 8) ----

	@Test
	public void nothingAgainstYourAverageWhileFighting()
	{
		final PanelSnapshot.FightTab live = tab(SidePanelExamples.live());
		assertEquals(PanelSnapshot.State.LIVE, live.state);
		assertNull(live.budget);
		assertNull(live.curve);
	}

	@Test
	public void bossHealedAfterTheFightOnly()
	{
		final SidePanelModel.Input in = SidePanelExamples.live();
		((LedgerExamples.FakeScope) in.scope).healed = 40;
		assertNull(tab(in).clear.healed);
		// T-202: in Damage, under its share bar (Details is gone).
		final PanelSnapshot.Row healed = tab(SidePanelExamples.dead()).clear.healed;
		assertEquals("Boss healed", healed.left.text);
		assertEquals("68", healed.right.text);
	}

	@Test
	public void theWarningHidesLuckAndExpected()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelExamples.unsure());
		assertNotNull(t.banner);
		assertNull(t.tiles);
		assertEquals("unsure", t.card.luckWord.text);
		assertNull(t.card.expDps);
		assertEquals("418", t.summaries.get("damage").text);
		assertNull(t.curve);
		assertNull(t.noCurve);
	}

	@Test
	public void unscoredAddsReadOnTheBoss()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelExamples.unscoredAdds());
		assertNotNull(t.unscoredNote);
		assertTrue(t.unscoredNote, t.unscoredNote.contains("(1:21)"));
		assertEquals(2, t.targets.rows.size());
		assertEquals("not scored", t.targets.rows.get(1).chip.text);
		assertTrue(t.card.dpsTip, t.card.dpsTip.contains("burn 0.1"));
	}

	@Test
	public void preAttackShowsTheLoadoutAndTheLastFight()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelExamples.preAttack());
		assertEquals(PanelSnapshot.State.PRE_ATTACK, t.state);
		assertEquals("0:00", t.header.clock.text);
		assertEquals(SidePanelStyle.UNSCORED, t.header.clock.colour);
		assertNull(t.tiles);
		assertEquals("Abyssal tentacle", t.loadout.name.text);
		assertEquals("Burning claws", t.loadout.specName.text);
		// T-030's two rows, as the loadout overlay has them.
		assertEquals("48 max · 71%", t.loadout.specRows.get(0).right.text);
		assertEquals("26.0 (8.2 burn)", t.loadout.specRows.get(1).right.text);
		assertEquals("bottom 20%", t.lastFight.chip.text);
	}

	@Test
	public void beforeTheVarianceTheSecondsStandIn()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) in.scope).damageVariance = -1;
		final PanelSnapshot.FightTab t = tab(in);
		assertEquals("no spread", t.card.luckWord.text);
		assertEquals("-14 s", t.card.luckTime);
		assertNull(t.curve);
		assertEquals(SidePanelModel.NO_CURVE_TEXT, t.noCurve);
	}

	@Test
	public void structureChangesOnlyWithTheSections()
	{
		final SidePanelModel.Input a = SidePanelExamples.live();
		final SidePanelModel.Input b = SidePanelExamples.live();
		((LedgerExamples.FakeScope) b.scope).combatTicks += 1;
		b.scoredThrall += 12;
		assertEquals(tab(a).structure(), tab(b).structure());
		assertFalse(tab(a).structure().equals(tab(SidePanelExamples.dead()).structure()));
	}

	// ---- luck removed: the same kills LedgerHistory takes your average from ----

	private static final String BOSS = "Vardorvis";

	/** A kill of {@code seconds}, its damage {@code luck} off what was expected. */
	private static Fight kill(double seconds, int luck, boolean newBuild, boolean died)
	{
		final Fight f = new Fight(BOSS, NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		final int ticks = (int) Math.round(seconds / 0.6);
		final int attacks = Math.max(1, ticks / 4);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, newBuild ? 1.0 : -1);
			f.recordExpected(40, 0.5, 10);
		}
		for (int i = attacks; i < ticks; i++)
		{
			f.recordTickSpent();
		}
		f.recordDamageDealt(attacks * 10 + luck, 1L, true);
		f.end(died, 2L);
		return f;
	}

	private static List<Fight> newestFirst(Fight... oldestFirst)
	{
		final List<Fight> list = new ArrayList<>(Arrays.asList(oldestFirst));
		Collections.reverse(list);
		return list;
	}

	private static Encounter current()
	{
		final Encounter room = new Encounter(BOSS, null, 0L);
		room.add(kill(100, 0, true, true));
		return room;
	}

	@Test
	public void luckRemovedTakesTheSameKillsAsYourAverage()
	{
		// No luck in any of them, so each kill's T* is its clock and the two spreads must be the same: an old-build kill and a
		// fight that wasn't a kill are left out of both.
		final List<Fight> history = newestFirst(kill(110, 0, true, true), kill(300, 0, false, true),
			kill(120, 0, true, true), kill(50, 0, true, false), kill(115, 0, true, true), kill(130, 0, true, true),
			kill(125, 0, true, true));
		final Encounter room = current();
		final LedgerHistory.Average a = LedgerHistory.of(history, room);
		final LedgerHistory.Spread r = SidePanelModel.luckRemoved(history, room);
		assertNotNull(a);
		assertNotNull(r);
		assertEquals(a.actual.median, r.median, 1e-9);
		assertEquals(a.actual.lowerQuartile, r.lowerQuartile, 1e-9);
		assertEquals(a.actual.upperQuartile, r.upperQuartile, 1e-9);
	}

	@Test
	public void luckRemovedIsTheClockPlusLuck()
	{
		final List<Fight> history = newestFirst(kill(120, -50, true, true), kill(120, -50, true, true),
			kill(120, -50, true, true), kill(120, -50, true, true), kill(120, -50, true, true));
		final LedgerHistory.Spread r = SidePanelModel.luckRemoved(history, current());
		assertNotNull(r);
		final double star = new LedgerRows.Numbers(LedgerRows.of(LedgerHistory.rooms(history, BOSS).get(0)))
			.expectedDuration;
		assertTrue(star < 120);
		assertEquals(star, r.median, 1e-9);
	}

	@Test
	public void luckRemovedNeedsFiveKills()
	{
		assertNull(SidePanelModel.luckRemoved(newestFirst(kill(110, 0, true, true), kill(120, 0, true, true),
			kill(115, 0, true, true), kill(130, 0, true, true)), current()));
	}

	// ---- T-038: eating as its own stretch of the time budget ----

	@Test
	public void eatingIsItsOwnNeutralStretchAfterUptime()
	{
		final PanelSnapshot.TimeBudget b = tab(SidePanelExamples.dead()).budget;
		// Best play stays 1:41: 136.8 - 14.3 - (15.0 + 4.2 + 2.4) = 100.9 s.
		assertEquals("1:41", b.rows.get(0).value.text);
		assertEquals("idle", b.rows.get(1).label.text);
		assertEquals("-15 s", b.rows.get(1).value.text);
		final PanelSnapshot.BudgetRow eating = b.rows.get(2);
		assertEquals(PanelSnapshot.Fill.EATING, eating.swatch);
		assertEquals("eating", eating.label.text);
		assertEquals("-4.2 s", eating.value.text);
		assertEquals(SidePanelStyle.BODY, eating.value.colour);
		assertEquals(4.2, b.segments.get(2).seconds, 1e-9);
	}

	@Test
	public void theBudgetStillAddsUpWithEatingWhenLuckSavedTime()
	{
		final SidePanelModel.Input in = SidePanelExamples.raid();
		((LedgerExamples.FakeScope) in.scope).ticksLostEating = 30;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertTrue(n.luck > 0);
		assertEquals(18, n.eatingCost, 1e-9);
		assertEquals(n.clock + n.luck, b.sum(), 1e-9);
		// Best play doesn't move: the 18 s came out of uptime's.
		assertEquals("8:10", b.rows.get(0).value.text);
	}

	@Test
	public void noEatingNoStretch()
	{
		final SidePanelModel.Input in = SidePanelExamples.raid();
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertFalse(b.rows.stream().anyMatch(r -> r.swatch == PanelSnapshot.Fill.EATING));
		assertEquals(0, b.segments.get(2).seconds, 0);
	}

	@Test
	public void eatingShowsWhenUptimeHasNoRow()
	{
		// Orchestrator's review of #39: 0 idle and 7 eating ticks. Uptime's 0 s has no row; eating's 4 s still does.
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.ticksLost = 7;
		s.ticksLostEating = 7;
		final PanelSnapshot.TimeBudget b = tab(in).budget;
		assertFalse(b.rows.stream().anyMatch(r -> r.label.text.equals("idle")));
		final PanelSnapshot.BudgetRow eating = b.rows.get(1);
		assertEquals(PanelSnapshot.Fill.EATING, eating.swatch);
		assertEquals("eating", eating.label.text);
		assertEquals("-4.2 s", eating.value.text);
		assertEquals(0, b.segments.get(1).seconds, 0);
		assertEquals(4.2, b.segments.get(2).seconds, 1e-9);
		assertEquals(new LedgerRows.Numbers(s).clock, b.sum(), 1e-9);
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-126 (Eric, 2026-10-31: "It doesn't make sense that execution % is lower
 * than the average of its parts"): Execution 95% under uptime 99%, prayer
 * 99%, gear 97% and stat boost 97% was right, as every part's loss comes off
 * it. Stat boost's % is now the damage it kept, as prayer's and gear's are
 * (option C), and {@link LedgerRows#executionLosses} gives each part's loss so
 * that 100% minus them is Execution, shown as the side panel's tooltip
 * (option A).
 */
public class ExecutionAddsUpTest
{
	/** Eric's Vardorvis card (00:37): about 600 ideal, 2 damage to prayer, 17 to gear and 5 to the boost; one tick idle. */
	private static LedgerExamples.FakeScope erics()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.ticksLost = 2;
		s.ticksLostEating = 0;
		s.idealSetupDamage = 600;
		s.efficiency = 1 - 24 / 600.0;
		s.boostShare = 0.97;
		s.boostCostShare = 5 / 600.0;
		s.switchCostShare = 17 / 600.0;
		return s;
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
		return null;
	}

	private static double sum(List<LedgerRows.Loss> losses)
	{
		double total = 0;
		for (LedgerRows.Loss loss : losses)
		{
			total += loss.points;
		}
		return total;
	}

	// ---- C: stat boost's % is the damage kept ----

	@Test
	public void statBoostReadsTheDamageItKeptLikePrayerAndGear()
	{
		final LedgerRows.Numbers n = new LedgerRows.Numbers(erics());
		// 5 of 600: 99.2% kept. Before, 97%: the share of a full boost held.
		assertEquals(1 - 5 / 600.0, n.boostShare, 1e-9);
		assertEquals(1 - n.boostDamage / 600, n.boostShare, 1e-9);
		assertEquals(1 - n.gearDamage / 600, n.gearShare, 1e-9);
		assertEquals(1 - n.prayerDamage / 600, n.prayerShare, 1e-9);
		final List<LedgerRows.Row> rows = LedgerRows.build(erics(), LedgerRows.State.fight("Vardorvis", true, true,
			false), new LedgerRows.Settings());
		assertEquals("99% (-5 dmg)", row(rows, " stat boost").right);
		assertEquals("97% (-17 dmg)", row(rows, " gear").right);
		assertEquals("99% (-2 dmg)", row(rows, " prayer").right);
	}

	@Test
	public void drainedStaysAWord()
	{
		final LedgerExamples.FakeScope s = erics();
		s.attacksDrained = s.attacksMade;
		final List<LedgerRows.Row> rows = LedgerRows.build(s, LedgerRows.State.fight("Vardorvis", true, true, false),
			new LedgerRows.Settings());
		assertEquals("drained (-5 dmg)", row(rows, " stat boost").right);
	}

	@Test
	public void theOverlayAndThePanelAgreeOnStatBoost()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		in.scope = erics();
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		final List<LedgerRows.Row> rows = LedgerRows.build(in.scope, LedgerRows.State.fight("Vardorvis", true, true,
			false), new LedgerRows.Settings());
		for (PanelSnapshot.Part p : tab.execution.parts)
		{
			if (p.label.text.equals("stat boost"))
			{
				assertEquals(row(rows, " stat boost").right, p.value.text);
				return;
			}
		}
		throw new AssertionError("no stat boost part");
	}

	// ---- A: the losses add up ----

	@Test
	public void ericsCardAddsUp()
	{
		final LedgerRows.Numbers n = new LedgerRows.Numbers(erics());
		final List<LedgerRows.Loss> losses = LedgerRows.executionLosses(n, false);
		assertEquals("[uptime, prayer, gear, stat boost]", labels(losses));
		assertEquals(1 - n.executionLive, sum(losses), 1e-12);
		assertEquals("95%", LedgerRows.partPercent(n.executionLive));
		assertEquals("100% − uptime 0.9% − prayer 0.3% − gear 2.8% − stat boost 0.8% = 95%",
			LedgerRows.executionSum(n, false));
	}

	private static String labels(List<LedgerRows.Loss> losses)
	{
		final StringBuilder b = new StringBuilder("[");
		for (LedgerRows.Loss l : losses)
		{
			b.append(b.length() > 1 ? ", " : "").append(l.label);
		}
		return b.append(']').toString();
	}

	/** A fight at Vardorvis: 10 attacks each ideally 10, 8.5 expected, the boost costing 1 and gear 0.5; 2 of 20 ticks idle. */
	private static Fight fight(boolean ruled)
	{
		final Fight f = new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0);
		for (int i = 0; i < 10; i++)
		{
			f.recordAttackMade(false, 0.8);
			f.recordAttackResolved(true, false, 8.5, 10, 1, 0.5);
			f.recordExpected(59, 0.5, 8.5, 10);
			f.recordTickSpent(true);
		}
		for (int i = 0; i < 8; i++)
		{
			f.recordTickSpent(true);
		}
		f.recordTickLost(false, true);
		f.recordTickLost(false, true);
		f.end(false, 12_000L);
		if (ruled)
		{
			// Two of the twenty ticks were eating its avoidable hits cost.
			f.recordAvoidable(1, 30, Collections.singletonMap("axe", 30), 2.0, 40, "this kill");
		}
		return f;
	}

	@Test
	public void theLossesAddUpOnARealRoomWithAndWithoutAvoidableEating()
	{
		for (boolean ruled : new boolean[]{false, true})
		{
			final Encounter room = new Encounter("Vardorvis", null, 0L);
			room.add(fight(ruled));
			final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(room));
			assertTrue(n.uptime < 1);
			assertTrue(n.efficiency < 1);
			final List<LedgerRows.Loss> live = LedgerRows.executionLosses(n, false);
			assertEquals(1 - n.executionLive, sum(live), 1e-12);
			final List<LedgerRows.Loss> after = LedgerRows.executionLosses(n);
			assertEquals(1 - n.execution, sum(after), 1e-12);
			assertEquals(ruled, labels(after).contains("avoidable eating"));
			if (ruled)
			{
				assertTrue(n.execution < n.executionLive);
				assertEquals("[uptime, avoidable eating, prayer, gear, stat boost]", labels(after));
			}
		}
	}

	@Test
	public void anOldKillHasOneSetupLoss()
	{
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerExamples.midFightBeforeT010());
		assertTrue(!n.split);
		final List<LedgerRows.Loss> losses = LedgerRows.executionLosses(n, false);
		assertEquals("[uptime, setup]", labels(losses));
		assertEquals(1 - n.executionLive, sum(losses), 1e-12);
	}

	@Test
	public void theSumsFormat()
	{
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerExamples.kill());
		final String sum = LedgerRows.executionSum(n, false);
		assertTrue(sum, sum.startsWith("100% − uptime "));
		assertTrue(sum, sum.endsWith(" = " + LedgerRows.partPercent(n.executionLive)));
		// Parts that cost nothing (here prayer and gear) are left out.
		assertTrue(sum, !sum.contains("prayer") && !sum.contains("gear"));
		// From 10 points, whole: a fight 80% up.
		final LedgerExamples.FakeScope idle = LedgerExamples.kill();
		idle.ticksLost = 46;
		idle.ticksLostEating = 0;
		assertTrue(LedgerRows.executionSum(new LedgerRows.Numbers(idle), false),
			LedgerRows.executionSum(new LedgerRows.Numbers(idle), false).contains("uptime 20%"));
		// Unknown: none.
		final LedgerExamples.FakeScope unknown = LedgerExamples.kill();
		unknown.efficiency = -1;
		assertNull(LedgerRows.executionSum(new LedgerRows.Numbers(unknown), false));
	}

	// ---- the tooltip ----

	@Test
	public void theExecutionTileAndCardCarryTheSum()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		in.scope = erics();
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		final String sum = LedgerRows.executionSum(new LedgerRows.Numbers(in.scope), false);
		assertEquals("<html>" + sum + "<br>" + SidePanelModel.TICK_NOTE + "</html>", tab.execution.tooltip);
		// The card's figure is the sum's.
		assertTrue(sum.endsWith(" = " + tab.execution.hint.text));
	}
}

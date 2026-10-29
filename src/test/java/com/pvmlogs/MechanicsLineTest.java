package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pvmlogs.LedgerExamples.FakeScope;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * T-080: the idle time a boss's mechanic forced (T-074) as a neutral
 * {@code  mechanics  N s} line beside eating, and out of the setup's time.
 */
public class MechanicsLineTest
{
	private static LedgerRows.Row d(String left, String right)
	{
		return new LedgerRows.Row(left, right, LedgerRows.FontRole.DETAIL, LedgerRows.FontRole.DETAIL,
			LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.NEUTRAL);
	}

	private static List<LedgerRows.Row> rows(LedgerRows.Scope scope, ExecutionParts parts, boolean ended)
	{
		final LedgerRows.Settings settings = new LedgerRows.Settings();
		settings.parts = parts;
		return LedgerRows.build(scope, LedgerRows.State.fight("The Leviathan", ended, ended, false), settings);
	}

	/** Eric's kill's figures, at the Leviathan: 10 ticks held off by the stun and specials. */
	private static FakeScope leviathan()
	{
		final FakeScope s = LedgerExamples.kill();
		s.ticksMechanics = 10;
		return s;
	}

	@Test
	public void aLeviathanKillShowsItsMechanicsBesideEatingLiveAndAfter()
	{
		for (boolean ended : new boolean[]{false, true})
		{
			final List<LedgerRows.Row> rows = rows(leviathan(), ExecutionParts.ALL, ended);
			final int eating = rows.indexOf(d(" eating", "4 s"));
			assertTrue(rows.toString(), eating > 0);
			assertEquals(d(" mechanics", "6 s"), rows.get(eating + 1));
		}
	}

	@Test
	public void costingOnlyStillShowsItAndOffHidesIt()
	{
		assertTrue(rows(leviathan(), ExecutionParts.COSTING, true).contains(d(" mechanics", "6 s")));
		assertFalse(rows(leviathan(), ExecutionParts.OFF, true).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.MECHANICS)));
	}

	@Test
	public void aBossWithoutAllowancesOrAnOldKillHasNoLine()
	{
		assertFalse(rows(LedgerExamples.kill(), ExecutionParts.ALL, true).stream()
			.anyMatch(r -> r.left.equals(LedgerRows.MECHANICS)));
		// One tick is 0.6 s, which rounds to a second: the line shows.
		final FakeScope one = LedgerExamples.kill();
		one.ticksMechanics = 1;
		assertTrue(rows(one, ExecutionParts.ALL, true).contains(d(" mechanics", "1 s")));
	}

	@Test
	public void mechanicsTimeIsOutOfTheSetupCostAndNeverACost()
	{
		final LedgerRows.Numbers before = new LedgerRows.Numbers(LedgerExamples.kill());
		final LedgerRows.Numbers after = new LedgerRows.Numbers(leviathan());
		assertEquals(6.0, after.mechanicsCost, 1e-9);
		assertEquals(0, before.mechanicsCost, 0);
		// Charged at the setup's efficiency before; out of the setup's time now.
		assertEquals(before.setupCost - 6.0 * (1 - after.efficiency), after.setupCost, 1e-9);
		assertEquals(before.uptimeCost, after.uptimeCost, 0);
		assertEquals(before.eatingCost, after.eatingCost, 0);
		// Neutral, never coloured.
		final LedgerRows.Row line = rows(leviathan(), ExecutionParts.ALL, true).stream()
			.filter(r -> r.left.equals(LedgerRows.MECHANICS)).findFirst().get();
		assertEquals(LedgerRows.Colour.NEUTRAL, line.rightColour);
	}

	@Test
	public void roomRaidAndTripCarryTheMechanicsTicks()
	{
		final Fight f = new Fight("The Leviathan", NpcIds.GOBLIN, 1, 1000, 0L, null, 0);
		f.recordAttackMade(false, 1.0);
		for (int i = 0; i < 5; i++)
		{
			f.recordTickMechanic(true);
		}
		final Encounter room = new Encounter("The Leviathan", null, 0L);
		room.add(f);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		final SessionTotals trip = new SessionTotals(0L);
		for (int i = 0; i < 5; i++)
		{
			trip.recordTickMechanic(true);
		}
		for (LedgerRows.Scope scope : Arrays.asList(LedgerRows.of(room), LedgerRows.of(raid), LedgerRows.of(trip)))
		{
			assertEquals(5, scope.ticksMechanics());
			assertEquals(3.0, new LedgerRows.Numbers(scope).mechanicsCost, 1e-9);
		}
	}
}

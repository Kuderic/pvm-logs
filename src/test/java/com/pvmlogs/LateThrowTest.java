package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * T-210: a throw the experience proves came late leaves its late ticks overdue, not its cooldown. Eric's Alchemical Hydra
 * log of 2026-11-11 ({@code pvm-debug-20261111-013332.log}, build {@code dd4fb1a}, read in place), KC 1: thrown 1579,
 * next due 1584, the next throw on 1586 (2 late) booked on 1588; ticks 1586 and 1587 were logged "on cooldown, the throw
 * on 1586 proven by its XP", 24 such ticks a kill.
 */
public class LateThrowTest
{
	private static final int DUE = 1584;
	private static final int LAG = 2;
	private static final int SPEED = 5;

	/** What the tick loop does with a tick: true when it's the proven throw's cooldown, false when it's overdue. */
	private static boolean cooldown(int now, int due, int proof)
	{
		if (!PvmPerformancePlugin.attackOverdue(now, due, LAG))
		{
			return true;
		}
		return PvmPerformancePlugin.throwProvenByXp(now, due, proof, SPEED)
			&& PvmPerformancePlugin.cooldownSinceThrow(now, LAG, due, proof);
	}

	@Test
	public void anOnTimeThrowProvenByXpIsCooldownAsBefore()
	{
		// Thrown on its due tick, booked late (a cast booked from its splat 3 ticks on, T-136): the ticks until its booking
		// are its cooldown.
		assertTrue(cooldown(DUE + LAG, DUE, DUE));
		assertTrue(cooldown(DUE + LAG + 1, DUE, DUE));
		// Before the due tick's booking lag, every tick is cooldown whatever the proof.
		assertTrue(cooldown(DUE + 1, DUE, DUE + 2));
	}

	@Test
	public void aThrowTwoTicksLateLeavesTwoTicksOverdue()
	{
		// 1586 and 1587 judge the attack ticks 1584 and 1585, which went by with nothing thrown.
		assertFalse(cooldown(1586, DUE, 1586));
		assertFalse(cooldown(1587, DUE, 1586));
		// From 1588 the judged tick is the throw's own or after it: cooldown while its booking is still to come.
		assertTrue(cooldown(1588, DUE, 1586));
		// Five late: the three ticks before the XP were overdue already; the two after it are now too.
		int overdue = 0;
		for (int now = DUE + LAG; now < 1589 + LAG; now++)
		{
			overdue += cooldown(now, DUE, now >= 1589 ? 1589 : Integer.MIN_VALUE) ? 0 : 1;
		}
		assertEquals(5, overdue);
	}

	@Test
	public void theOverdueTicksAreJudgedAsAnyOverdueTickEatingFirst()
	{
		// A shark (3 ticks) eaten in the cycle: the late ticks inside its delay are eating, the rest idle.
		assertTrue(FoodDelay.isEatingTick(1586, DUE, LAG, 3));
		assertTrue(FoodDelay.isEatingTick(1587, DUE, LAG, 3));
		assertFalse(FoodDelay.isEatingTick(1587, DUE, LAG, 1));
		// The fight's first throw has no due tick, so nothing in it is late: cooldown as before.
		assertTrue(cooldown(1586, Integer.MIN_VALUE, 1586));
		assertTrue(PvmPerformancePlugin.cooldownSinceThrow(1586, LAG, Integer.MIN_VALUE, 1586));
	}

	@Test
	public void withoutXpTheTicksBeforeTheBookingAreOverdueAlready()
	{
		// A zero splat or a splash pays nothing: no proof, so every tick from the due tick's booking lag is overdue until a
		// booking moves the due tick. The late ticks were never hidden on this path.
		for (int now = DUE + LAG; now < 1588; now++)
		{
			assertFalse(cooldown(now, DUE, Integer.MIN_VALUE));
		}
	}

	@Test
	public void theWeaponRowsTimeDropsTheHiddenTicks()
	{
		// The crossbow thrown on 1579 and 1586, drawn from 1579: its own tick, 4 of cooldown, then 1584 and 1585.
		final byte a = FightTimeline.ATTACK;
		final byte c = FightTimeline.COOLDOWN;
		final byte l = FightTimeline.LOST;
		final byte[] before = {a, c, c, c, c, c, c, a, c, c, c, c};
		final byte[] after = {a, c, c, c, c, l, l, a, c, c, c, c};
		// Before, the first throw held 7 ticks, its speed and the 2 late ones: its rate read 5/7 of the gear's.
		assertEquals(7, SidePanelWeaponRows.ticks(view(before), 0));
		// Now the late ticks are idle, nobody's: the throw holds its speed, and its rate is the per-attack one.
		assertEquals(SPEED, SidePanelWeaponRows.ticks(view(after), 0));
		assertEquals(SPEED, SidePanelWeaponRows.ticks(view(after), 1));
	}

	private static FightTimeline.View view(byte[] states)
	{
		final List<FightTimeline.Attack> attacks = Arrays.asList(new FightTimeline.Attack(1579),
			new FightTimeline.Attack(1586));
		for (FightTimeline.Attack k : attacks)
		{
			k.mean = 10;
			k.speed = SPEED;
		}
		return new FightTimeline.View(1579, states, new float[states.length], new float[states.length],
			new float[states.length], attacks);
	}

	@Test
	public void theFormulaVersionIsBumpedSoTheGradeWindowStartsAgain()
	{
		assertEquals(2, BossRecords.EXECUTION_FORMULA);
		assertTrue(BossRecords.scoringStamp(null).startsWith("e2-"));
		// A record scored the old way: the first kill scored the new way grades by the fixed bands, "since the rules
		// changed"; the bests and the rank keep every kill.
		final BossRecords records = new BossRecords(null, new com.google.gson.Gson());
		final LedgerHistory.Like hydra = new LedgerHistory.Like("Alchemical Hydra", 1100, true);
		for (int i = 0; i < 12; i++)
		{
			records.record(new BossRecords.KillFigures(hydra, (i + 1) * 1000L, 160, 0.95, 150, 0.5, null, 0, false,
				"e1-213fb010"));
		}
		records.record(new BossRecords.KillFigures(hydra, 20_000L, 150, 0.86, 150, 0.5, null, 0, false,
			BossRecords.scoringStamp(null)));
		final BossRecords.View v = records.view(hydra);
		assertEquals("fixed bands (0 kills since the rules changed)", v.last.basis);
		assertEquals(13, v.kills);
		assertEquals(1, v.last.rank);
	}

	@Test
	public void theDpsLineSaysWhatItDividesBy()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final String tip = SidePanelModel.fight(in).card.dpsTip;
		assertTrue(tip, tip.contains("Per second of the whole fight, eating and idle included."));
		// The expected rate while attacking: what the kill's expected comes to over the seconds left once the idle,
		// eating and mechanic seconds are out, never less than the whole fight's.
		final SidePanelModel.Rate r = SidePanelModel.rate(n, in);
		final double attacking = r.seconds - Math.max(0, n.uptimeCost) - Math.max(0, n.eatingCost)
			- Math.max(0, n.mechanicsCost);
		assertTrue(attacking > 0 && attacking <= r.seconds);
		assertTrue(tip, tip.contains("While attacking: exp " + LedgerRows.oneDecimal((n.expected + Math.max(0, n.other))
			/ attacking) + ", the gear's rate (the infobox's)."));
		// Under the boss-data warning, expected is hidden: only what it divides by.
		assertEquals("Per second of the whole fight, eating and idle included.", SidePanelModel.whatItDividesBy(n, r,
			true));
	}
}

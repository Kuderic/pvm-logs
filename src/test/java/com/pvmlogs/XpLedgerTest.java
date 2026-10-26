package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Skill;
import org.junit.Test;

public class XpLedgerTest
{
	private static final int NPC = 1234;

	/** One ranged attack on {@code tick} that dealt {@code damage} at this multiplier. */
	private static void rangedAttack(XpLedger ledger, int tick, int damage, double m)
	{
		ledger.add(Skill.RANGED, (int) Math.floor(4 * damage * m));
		ledger.add(Skill.HITPOINTS, (int) Math.floor(4.0 / 3.0 * damage * m));
		ledger.close(tick, NPC, 1.0, null);
	}

	@Test
	public void aSplatThatFinishesTheAttackExactlyIsCovered()
	{
		final XpLedger ledger = new XpLedger();
		rangedAttack(ledger, 10, 40, 1.0);
		assertEquals(XpLedger.Coverage.EXACT, ledger.coverage(40, 12));
		// A two could be part of a multi-hit attack still landing, so it is only partly covered.
		assertEquals(XpLedger.Coverage.PARTIAL, ledger.coverage(2, 12));
	}

	@Test
	public void nothingLandsOnTheTickItIsThrown()
	{
		final XpLedger ledger = new XpLedger();
		rangedAttack(ledger, 10, 40, 1.0);
		assertEquals(XpLedger.Coverage.UNCOVERED, ledger.coverage(2, 10));
	}

	@Test
	public void onceTheAttackHasLandedASmallSplatIsUncovered()
	{
		final XpLedger ledger = new XpLedger();
		rangedAttack(ledger, 10, 40, 1.0);
		ledger.spend(40, 12, true);
		assertEquals(XpLedger.Coverage.UNCOVERED, ledger.coverage(2, 13));
	}

	@Test
	public void withNoAttackPaidForAnySplatWithDamageIsUncovered()
	{
		assertEquals(XpLedger.Coverage.UNCOVERED, new XpLedger().coverage(3, 50));
	}

	@Test
	public void aZeroSaysNothing()
	{
		assertEquals(XpLedger.Coverage.NO_INFO, new XpLedger().coverage(0, 50));
	}

	@Test
	public void aScythesThirdHitFinishesItsAttack()
	{
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.STRENGTH, 4 * 47);
		ledger.add(Skill.HITPOINTS, 62);
		ledger.close(10, NPC, 1.0, null);
		ledger.spend(30, 11, true);
		ledger.spend(15, 11, true);
		assertEquals(XpLedger.Coverage.EXACT, ledger.coverage(2, 11));
	}

	@Test
	public void itTrustsItselfOnlyWhileItsChecksPass()
	{
		final XpLedger ledger = new XpLedger();
		assertFalse("no evidence yet", ledger.trusted());
		rangedAttack(ledger, 10, 40, 1.0);
		ledger.spend(40, 12, true);
		rangedAttack(ledger, 15, 35, 1.0);
		ledger.spend(35, 17, true);
		assertFalse("checked only once each attack has finished landing", ledger.trusted());
		ledger.expire(30);
		assertTrue(ledger.trusted());
		// Big hits nothing paid for - a place that pays no experience.
		ledger.spend(50, 40, true);
		ledger.spend(45, 45, true);
		ledger.spend(44, 50, true);
		assertFalse(ledger.trusted());
	}

	@Test
	public void anAttackWhoseDropsDecodeToNothingFailsItsCheck()
	{
		// The Vardorvis case: read at x1.70, drops paid at x1.55 decode to nothing, and that is itself the evidence.
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.STRENGTH, 180);
		ledger.add(Skill.HITPOINTS, 60);
		ledger.close(10, NPC, 1.70, null);
		ledger.spend(29, 12, true);
		// One attack is too little to calibrate from, so its failure stands.
		ledger.expire(10 + XpLedger.WINDOW_TICKS + 1);
		assertEquals("0/1 NOT trusted", ledger.trustSummary());
	}

	@Test
	public void aWrongStartingMultiplierIsCorrectedAndTheChecksRerun()
	{
		final XpLedger ledger = new XpLedger();
		// Three claw specials on Vardorvis as logged: 29, 41 and 42 damage, paid at x1.55, read at x1.70.
		final int[][] specs = {{180, 60, 29}, {254, 84, 41}, {260, 87, 42}};
		int tick = 180;
		for (int[] spec : specs)
		{
			ledger.add(Skill.STRENGTH, spec[0]);
			ledger.add(Skill.HITPOINTS, spec[1]);
			assertFalse("undecodable at the wrong multiplier", ledger.close(tick, NPC, 1.70, null).decoded());
			ledger.spend(spec[2], tick + 1, true);
			tick += 4;
		}
		ledger.expire(tick + XpLedger.WINDOW_TICKS);
		assertEquals(1.55, ledger.multiplierFor(NPC, 1.70), 1e-9);
		assertTrue(ledger.trustSummary(), ledger.trusted());
	}

	@Test
	public void aRightStartingMultiplierIsConfirmedNotChanged()
	{
		final XpLedger ledger = new XpLedger();
		final int[][] specs = {{180, 60, 29}, {254, 84, 41}};
		int tick = 180;
		for (int[] spec : specs)
		{
			ledger.add(Skill.STRENGTH, spec[0]);
			ledger.add(Skill.HITPOINTS, spec[1]);
			assertTrue(ledger.close(tick, NPC, 1.55, null).decoded());
			ledger.spend(spec[2], tick + 1, true);
			tick += 4;
		}
		ledger.expire(tick + XpLedger.WINDOW_TICKS);
		assertEquals(1.55, ledger.multiplierFor(NPC, 1.0), 1e-9);
		assertTrue(ledger.trusted());
	}

	@Test
	public void splatsThatCouldBeTheThrallsTeachItNothing()
	{
		final XpLedger ledger = new XpLedger();
		rangedAttack(ledger, 10, 40, 1.0);
		ledger.spend(38, 12, true);
		ledger.spend(2, 12, false);
		rangedAttack(ledger, 15, 35, 1.0);
		ledger.spend(35, 17, true);
		ledger.expire(30);
		assertEquals("only the clean attack is checked", "1/1 NOT trusted", ledger.trustSummary());
	}

	@Test
	public void itLearnsAMultiplierTheFormulaGotWrong()
	{
		final XpLedger ledger = new XpLedger();
		// True bonus +20%, formula says none.
		int tick = 10;
		for (int i = 0; i < 4; i++)
		{
			rangedAttack(ledger, tick, 50, 1.2);
			ledger.spend(50, tick + 2, true);
			tick += 5;
		}
		ledger.expire(tick + 20);
		assertEquals(1.2, ledger.multiplierFor(NPC, 1.0), 1e-9);
	}

	@Test
	public void expiredAwardsStopCovering()
	{
		final XpLedger ledger = new XpLedger();
		rangedAttack(ledger, 10, 40, 1.0);
		ledger.expire(10 + XpLedger.WINDOW_TICKS + 1);
		assertEquals(XpLedger.Coverage.UNCOVERED, ledger.coverage(2, 10 + XpLedger.WINDOW_TICKS + 1));
	}
}

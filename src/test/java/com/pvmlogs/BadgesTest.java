package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

/**
 * T-192: the four badge rules in main code, on {@code SidePanelBadgesTest}'s cases (T-189): the Araxxor kill at 555,
 * which earns none.
 */
public class BadgesTest
{
	private static LedgerExamples.FakeScope scope()
	{
		return (LedgerExamples.FakeScope) SidePanelKillCardExamples.araxxorKillInput().scope;
	}

	private static Set<Badges.Badge> earned(LedgerExamples.FakeScope s, int kc, int streak)
	{
		return Badges.earned(new LedgerRows.Numbers(s), s.damageTakenAvoidable(), kc, streak);
	}

	@Test
	public void theAraxxorKillAt555EarnsNone()
	{
		assertTrue(earned(scope(), 555, 1).isEmpty());
	}

	@Test
	public void everyHundredthKillByTheGamesCount()
	{
		assertEquals(EnumSet.of(Badges.Badge.HUNDREDTH_KILL), earned(scope(), 600, 1));
		assertTrue(earned(scope(), 601, 1).isEmpty());
		// Unknown (0) is never a badge.
		assertFalse(Badges.hundredth(0));
	}

	@Test
	public void flawlessOnlyWhereAttackRulesCanTellIt()
	{
		// Araxxor has no attack rules: no avoidable figure, so no Flawless however little was taken.
		final LedgerExamples.FakeScope unruled = scope();
		unruled.damageTakenAvoidable = 0;
		assertFalse(Badges.ruled(new LedgerRows.Numbers(unruled)));
		assertFalse(earned(unruled, 555, 1).contains(Badges.Badge.FLAWLESS));
		// With rules and nothing avoidable taken: Flawless.
		final LedgerExamples.FakeScope ruled = scope();
		ruled.avoidableEatingTicks = 0;
		ruled.damageTakenAvoidable = 0;
		assertTrue(earned(ruled, 555, 1).contains(Badges.Badge.FLAWLESS));
		// With rules and an avoidable hit: none.
		ruled.damageTakenAvoidable = 12;
		assertFalse(earned(ruled, 555, 1).contains(Badges.Badge.FLAWLESS));
	}

	@Test
	public void cleanRunIsNoIdleTickAndAPerfectSetup()
	{
		final LedgerExamples.FakeScope s = scope();
		// The kill had one idle tick: no Clean run.
		assertFalse(earned(s, 555, 1).contains(Badges.Badge.CLEAN_RUN));
		// Eating isn't idling: two ticks of it and no idle tick, with the setup at 99.96%.
		s.ticksLost = 2;
		s.ticksLostEating = 2;
		s.efficiency = 0.9996;
		s.boostCostShare = 0.0003;
		s.switchCostShare = 0;
		assertTrue(earned(s, 555, 1).contains(Badges.Badge.CLEAN_RUN));
		s.efficiency = 0.9990;
		assertFalse(earned(s, 555, 1).contains(Badges.Badge.CLEAN_RUN));
	}

	@Test
	public void threeAsInARowFromTheRecordsStreak()
	{
		assertTrue(earned(scope(), 555, 2).isEmpty());
		assertEquals(EnumSet.of(Badges.Badge.THREE_IN_A_ROW), earned(scope(), 555, 3));
		// Once per run: the 5th of a run doesn't earn it again (the card names the run from the record's streak).
		assertTrue(earned(scope(), 555, 5).isEmpty());
		// No record yet: no streak to read.
		assertTrue(earned(scope(), 555, 0).isEmpty());
	}

	@Test
	public void inTheCardsOrder()
	{
		final LedgerExamples.FakeScope s = scope();
		s.avoidableEatingTicks = 0;
		s.damageTakenAvoidable = 0;
		assertEquals("[HUNDREDTH_KILL, FLAWLESS, THREE_IN_A_ROW]", earned(s, 600, 3).toString());
		assertEquals("3 A's in a row", Badges.Badge.THREE_IN_A_ROW.title);
	}
}

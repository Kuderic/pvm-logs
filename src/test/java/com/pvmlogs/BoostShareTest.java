package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import com.google.gson.Gson;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * T-010: the boost as a share of the full effect instead of an all-or-nothing
 * count, and what the missing boost and missed switches cost as shares of the
 * damage the attacks would have been set up to deal.
 */
public class BoostShareTest
{
	private static final int SUPER_COMBAT_99 = MeleeBoost.SUPER_COMBAT.boost(Skill.STRENGTH, 99);

	private static Fight fight()
	{
		return new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
	}

	@Test
	public void aFullDoseIsAllOfIt()
	{
		assertEquals(19, SUPER_COMBAT_99);
		assertEquals(1.0, CombatCalc.boostShare(118, 99, SUPER_COMBAT_99), 1e-9);
	}

	@Test
	public void aDoseDecayedByALevelIsMostOfOneNotNone()
	{
		// The bug: isPotted read 117 of 118 as unpotted, so a minute into a super combat every attack counted as none.
		assertEquals(18 / 19.0, CombatCalc.boostShare(117, 99, SUPER_COMBAT_99), 1e-9);
		assertEquals(10 / 19.0, CombatCalc.boostShare(109, 99, SUPER_COMBAT_99), 1e-9);
	}

	@Test
	public void noBoostOrADrainedStatIsNoneOfIt()
	{
		assertEquals(0.0, CombatCalc.boostShare(99, 99, SUPER_COMBAT_99), 1e-9);
		assertEquals(0.0, CombatCalc.boostShare(90, 99, SUPER_COMBAT_99), 1e-9);
	}

	@Test
	public void moreThanTheGoalIsCappedAtAll()
	{
		// maxBoost is raised to whatever is really up, so this only happens if a caller passes the goal alone.
		assertEquals(1.0, CombatCalc.boostShare(125, 99, SUPER_COMBAT_99), 1e-9);
	}

	@Test
	public void withNoBoostExpectedAStatAtItsBaseHoldsAllOfIt()
	{
		// Super strength leaves attack's goal at nothing; attack at its base is not marked down. The same call isPotted made.
		assertEquals(0, MeleeBoost.SUPER_STRENGTH.boost(Skill.ATTACK, 99));
		assertEquals(1.0, CombatCalc.boostShare(99, 99, 0), 1e-9);
		assertEquals(0.0, CombatCalc.boostShare(95, 99, 0), 1e-9);
	}

	@Test
	public void theFightsShareIsTheMeanOverAttacks()
	{
		final Fight f = fight();
		f.recordAttackMade(true, 1.0);
		f.recordAttackMade(false, 18 / 19.0);
		f.recordAttackMade(false, 0.5);
		assertEquals((1.0 + 18 / 19.0 + 0.5) / 3, f.boostShare(), 1e-9);
		// The old count is still kept beside it.
		assertEquals(1, f.getAttacksPotted());
	}

	@Test
	public void anAttackWhoseShareWasNotReadDoesNotCount()
	{
		final Fight f = fight();
		f.recordAttackMade(false, 0.5);
		f.recordAttackMade(false, -1);
		assertEquals(0.5, f.boostShare(), 1e-9);
		assertEquals(2, f.getAttacksMade());
	}

	@Test
	public void aFightSavedBeforeT010LoadsWithItsCountAndNoShare()
	{
		// history.json from before: Gson fills what it finds and leaves the new fields at zero.
		final String saved = "{\"targetName\":\"Vardorvis\",\"targetId\":12223,\"attacksMade\":30,\"attacksPrayed\":30,"
			+ "\"attacksPotted\":5,\"attacksSwitched\":3,\"sumActualSetup\":441.0,\"sumIdealSetup\":450.0}";
		final Fight old = new Gson().fromJson(saved, Fight.class);
		assertEquals(5, old.getAttacksPotted());
		assertEquals(-1, old.boostShare(), 1e-9);
		assertEquals(0.98, old.efficiency(), 1e-9);
		// No cost was recorded, so none is claimed.
		assertEquals(0.0, old.boostCostShare(), 1e-9);
		assertEquals(0.0, old.switchCostShare(), 1e-9);
	}

	@Test
	public void theNewFieldsSurviveASaveAndLoad()
	{
		final Fight f = fight();
		f.recordAttackMade(false, 0.9);
		f.recordAttackResolved(true, false, 9.8, 10.0, 0.15, 0.05);
		final Gson gson = new Gson();
		final Fight back = gson.fromJson(gson.toJson(f), Fight.class);
		assertEquals(0.9, back.boostShare(), 1e-9);
		assertEquals(0.015, back.boostCostShare(), 1e-9);
		assertEquals(0.005, back.switchCostShare(), 1e-9);
	}

	@Test
	public void theCostsAreSharesOfTheIdealSoTheyAddUpToTheEfficiencyGap()
	{
		// Ideal 10 a hit; the attack lost 0.15 to the boost and 0.05 to a switch, and the other 0.05 to the prayer.
		final Fight f = fight();
		f.recordAttackMade(false, 0.9);
		f.recordAttackResolved(false, false, 9.75, 10.0, 0.15, 0.05);
		assertEquals(0.975, f.efficiency(), 1e-9);
		assertEquals(0.015, f.boostCostShare(), 1e-9);
		assertEquals(0.005, f.switchCostShare(), 1e-9);
		final double prayer = 1 - f.efficiency() - f.boostCostShare() - f.switchCostShare();
		assertEquals(0.005, prayer, 1e-9);
	}

	@Test
	public void costsAreCountedOnlyWhereTheSetupPairIs()
	{
		// An attack against a target with no stats has no ideal; pricing its boost against nothing would inflate the share.
		final Fight f = fight();
		f.recordAttackMade(false, 0.5);
		f.recordAttackResolved(false, false, -1, -1, 3.0, 2.0);
		f.recordAttackMade(false, 0.5);
		f.recordAttackResolved(false, true, 9.0, 10.0, 1.0, 0);
		assertEquals(0.1, f.boostCostShare(), 1e-9);
		assertEquals(0.0, f.switchCostShare(), 1e-9);
	}

	@Test
	public void roomsRaidsAndTheSessionAddUpTheSameWay()
	{
		final Fight a = fight();
		a.recordAttackMade(false, 1.0);
		a.recordAttackResolved(true, true, 10.0, 10.0, 0, 0);
		final Fight b = fight();
		b.recordAttackMade(false, 0.5);
		b.recordAttackResolved(true, false, 8.0, 10.0, 1.0, 0.5);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(a);
		room.add(b);
		assertEquals(0.75, room.boostShare(), 1e-9);
		assertEquals(0.05, room.boostCostShare(), 1e-9);
		assertEquals(0.025, room.switchCostShare(), 1e-9);

		final Raid raid = new Raid("Tombs of Amascut", 1, 0L);
		raid.add(room);
		assertEquals(0.75, raid.boostShare(), 1e-9);
		assertEquals(0.05, raid.boostCostShare(), 1e-9);
		assertEquals(0.025, raid.switchCostShare(), 1e-9);

		final SessionTotals session = new SessionTotals(0L);
		session.recordAttackMade(false, 1.0);
		session.recordAttackResolved(true, true, 10.0, 10.0, 0, 0);
		session.recordAttackMade(false, 0.5);
		session.recordAttackResolved(true, false, 8.0, 10.0, 1.0, 0.5);
		assertEquals(0.75, session.boostShare(), 1e-9);
		assertEquals(0.05, session.boostCostShare(), 1e-9);
		assertEquals(0.025, session.switchCostShare(), 1e-9);
		session.reset(0L);
		assertEquals(-1, session.boostShare(), 1e-9);
		assertEquals(-1, session.boostCostShare(), 1e-9);
	}

	@Test
	public void theOverlayShowsTheCostBesideTheFigure()
	{
		assertEquals("84% (-1.2%)", PvmPerformanceOverlay.withCost("84%", 0.012));
		assertEquals("3/28 (-0.4%)", PvmPerformanceOverlay.withCost("3/28", 0.004));
		// Nothing lost, or nothing to price against: no bracket.
		assertEquals("100%", PvmPerformanceOverlay.withCost("100%", 0));
		assertEquals("28/28", PvmPerformanceOverlay.withCost("28/28", -1));
	}
}

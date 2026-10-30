package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import net.runelite.api.GameState;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-097: the Giant Mole's chase allowance follows how far she went. Nothing
 * counts while she is out of view; once she is back, the run to her is excused
 * for as long as running into the weapon's reach takes, plus a margin.
 */
public class MoleChaseTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int MARGIN = RULES.forNpc(NpcID.MOLE_GIANT).chaseByDistance.margin;

	@Test
	public void theRuleIsInTheMolesData()
	{
		final BossRules.Boss mole = RULES.forNpc(NpcID.MOLE_GIANT);
		assertNotNull(mole.chaseByDistance);
		assertEquals(3, mole.chaseByDistance.margin);
		// The fixed 50 + 10 is gone: the allowance on her window is the dig alone, while she is still in view.
		assertEquals(5, mole.uptimeAllowances.get(0).maxTicks);
		assertEquals(0, mole.uptimeAllowances.get(0).afterTicks);
	}

	@Test
	public void aNearBurrow()
	{
		// She comes up 3 tiles off; the axe reaches 1: one tick's run, plus the margin.
		assertEquals(1 + MARGIN, BossAway.chaseAllowance(3, 1, MARGIN));
		// Already within reach: the margin alone.
		assertEquals(MARGIN, BossAway.chaseAllowance(1, 1, MARGIN));
		assertEquals(MARGIN, BossAway.chaseAllowance(0, 1, MARGIN));
	}

	@Test
	public void aFarBurrow()
	{
		// 25 tiles off with melee: 24 tiles to run, 12 ticks, plus the margin. However long she was out of view, which
		// counted nothing, the allowance is the run from where the player stood when she came back.
		assertEquals(12 + MARGIN, BossAway.chaseAllowance(25, 1, MARGIN));
		assertEquals(BossAway.chaseAllowance(25, 1, MARGIN), new BossAway.Chase(11900, 25, 1,
			BossAway.chaseAllowance(25, 1, MARGIN)).allowance);
	}

	@Test
	public void aSlowPlayerLosesWhatIsBeyondTheRun()
	{
		final BossAway.Chase chase = new BossAway.Chase(11860, 3, 1, BossAway.chaseAllowance(3, 1, MARGIN));
		for (int i = 0; i < chase.allowance; i++)
		{
			assertTrue(chase.excuse());
		}
		assertFalse(chase.excuse());
		assertFalse(chase.excuse());
		assertEquals(chase.allowance, chase.excused);
		assertEquals(2, chase.lost);
	}

	@Test
	public void meleeRunsFurtherThanRangedOrMagic()
	{
		// Reach, from the wiki's Attack range.
		assertEquals(1, CombatCalc.reachOf(AttackType.SLASH, WeaponCategory.AXE, CombatStyle.AGGRESSIVE, false));
		assertEquals(2, CombatCalc.reachOf(AttackType.SLASH, WeaponCategory.POLEARM, CombatStyle.AGGRESSIVE, false));
		assertEquals(7, CombatCalc.reachOf(AttackType.RANGED, WeaponCategory.CROSSBOW, CombatStyle.RAPID, false));
		assertEquals(9, CombatCalc.reachOf(AttackType.RANGED, WeaponCategory.CROSSBOW, CombatStyle.LONGRANGE, false));
		assertEquals(4, CombatCalc.reachOf(AttackType.RANGED, WeaponCategory.THROWN, CombatStyle.RAPID, false));
		assertEquals(9, CombatCalc.reachOf(AttackType.RANGED, WeaponCategory.CHINCHOMPAS, CombatStyle.RAPID, false));
		assertEquals(10, CombatCalc.reachOf(AttackType.RANGED, WeaponCategory.CHINCHOMPAS, CombatStyle.LONGRANGE, false));
		assertEquals(10, CombatCalc.reachOf(AttackType.MAGIC, WeaponCategory.STAFF, CombatStyle.AUTOCAST, false));
		assertEquals(7, CombatCalc.reachOf(AttackType.MAGIC, WeaponCategory.POWERED_STAFF, CombatStyle.ACCURATE, true));
		assertEquals(9, CombatCalc.reachOf(AttackType.MAGIC, WeaponCategory.POWERED_STAFF, CombatStyle.LONGRANGE, true));
		// She comes up 8 tiles off: the axe runs 4 ticks, the crossbow 1, a spell none.
		assertEquals(4 + MARGIN, BossAway.chaseAllowance(8, 1, MARGIN));
		assertEquals(1 + MARGIN, BossAway.chaseAllowance(8, 7, MARGIN));
		assertEquals(MARGIN, BossAway.chaseAllowance(8, 10, MARGIN));
	}

	@Test
	public void aTripAwayIsUnchanged()
	{
		// A trip is still told by the loading screen and still pauses the fight; the chase is a burrow's alone.
		assertTrue(BossAway.playerLeft(GameState.LOADING, Integer.MIN_VALUE, 11640));
		assertFalse(BossAway.playerLeft(GameState.LOGGED_IN, Integer.MIN_VALUE, 11829));
	}
}

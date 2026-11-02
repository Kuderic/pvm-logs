package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-146, from Eric's Chambers of Xeric raid of 2026-11-01 13:02 (build 8201462, read in place, nothing copied), in a
 * party of three: teammates' damage before his fight opened went unseen, so a killing blow wasn't capped ([5570]-[5574],
 * a Scavenger beast at 1 of 30, his 1 against an expected 18.6); and raid monsters' hitpoints were counted from the
 * table (Tekton 300 against a bar of 590, the Olm's claws 600 against 1200, the Guardian 250).
 */
public class HpLeftUnseenTest
{
	private static final int BEAST = NpcID.RAIDS_SCAVENGER_BEAST_A;
	private static final int INDEX = 43277;

	@Test
	public void aKillingBlowOnATargetHurtBeforeTheFightIsCapped()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, BEAST, 30, 5570);
		// [5570]: the fight opens with the beast's overhead bar at one step of 30: at most 1 left. The count had 30.
		assertEquals(29, health.boundByRatio(INDEX, BEAST, 1, 30, 0, 5570));
		assertEquals(1, health.remaining(INDEX, 5570));
		assertEquals(29, health.unseenDamage(INDEX));
		// [5571]: his Zombie axe's 1 lands, the bar then empty: at most 0 left, and the 1 itself, which the count had.
		assertEquals(0, health.boundByRatio(INDEX, BEAST, 0, 30, 1, 5571));
		// Its expected, read at 30 left (max 45, 0.93, 18.59), capped at the 1 it had: at most 1.
		final double[] capped = LandingCap.recap(45, 0.93, 18.59, 120.8, 30, 1);
		assertNotNull(capped);
		assertTrue(capped[0] <= 1.0);
		// Before: judged against the 30, uncapped.
		assertEquals(null, LandingCap.recap(45, 0.93, 18.59, 120.8, 30, 30));
	}

	@Test
	public void theOverheadBarGivesTheMostThatCanBeLeft()
	{
		// A 30-step bar on a 30 hitpoints monster: one step a hitpoint.
		assertEquals(1, TargetHealth.mostLeftForRatio(1, 30, 30));
		assertEquals(29, TargetHealth.mostLeftForRatio(29, 30, 30));
		assertEquals(30, TargetHealth.mostLeftForRatio(30, 30, 30));
		assertEquals(0, TargetHealth.mostLeftForRatio(0, 30, 30));
		// On more hitpoints, a step is several: past the first, each of the 29 is 600/29, so 15 steps is at most 310.
		assertEquals(310, TargetHealth.mostLeftForRatio(15, 30, 600));
		// No bar, or no full hitpoints: nothing said.
		assertEquals(-1, TargetHealth.mostLeftForRatio(-1, 30, 30));
		assertEquals(-1, TargetHealth.mostLeftForRatio(10, 30, -1));
	}

	@Test
	public void theBarOnlyEverLowersTheCountAndAllowsForTheLandingSplat()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, BEAST, 30, 0);
		health.damage(INDEX, BEAST, 20, 1);
		// The bar at most 15 while the count says 10: the count stands.
		assertEquals(0, health.boundByRatio(INDEX, BEAST, 15, 30, 0, 2));
		assertEquals(10, health.remaining(INDEX, 2));
		// A splat of 6 landing as the bar reads at most 4: 10 is allowed (it may not show yet), so the count stands.
		assertEquals(0, health.boundByRatio(INDEX, BEAST, 4, 30, 6, 3));
	}

	@Test
	public void aTargetWhoseBossBarIsReadIsUnchanged()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, NpcID.RAIDS_TEKTON_WAITING, 600, 5190, true);
		health.hud(INDEX, NpcID.RAIDS_TEKTON_WAITING, 551, 590, 5191);
		// The boss bar is read: the overhead one changes nothing, and the bar's figures stand.
		assertEquals(0, health.boundByRatio(INDEX, NpcID.RAIDS_TEKTON_WAITING, 1, 30, 0, 5192));
		assertEquals(551, health.remaining(INDEX, 5192));
		assertEquals("bar", health.maxSource(INDEX));
	}

	@Test
	public void chambersHitpointsScaleWithTheParty()
	{
		// Tekton and the Olm's claws in a party of three: the bar's 590 and 1200 (the count errs a little high: no combat
		// level term).
		assertEquals(600, RaidScaling.chambersHitpoints(300, 3, false, false));
		assertEquals(1200, RaidScaling.chambersHitpoints(600, 3, false, false));
		// Solo, the table's; a duo doubles it (the talk page's Tekton 600); four is three times.
		assertEquals(300, RaidScaling.chambersHitpoints(300, 1, false, false));
		assertEquals(600, RaidScaling.chambersHitpoints(300, 2, false, false));
		assertEquals(900, RaidScaling.chambersHitpoints(300, 4, false, false));
		// The Guardian by the same factor: 500 for three, over the day's 482 and 262.
		assertEquals(500, RaidScaling.chambersHitpoints(250, 3, false, false));
		// Challenge mode half as much again (Tekton 450 solo), not twice where the data's version already is.
		assertEquals(450, RaidScaling.chambersHitpoints(300, 1, true, false));
		assertEquals(450, RaidScaling.chambersHitpoints(450, 1, true, true));
		assertEquals(375, RaidScaling.chambersHitpoints(250, 1, true, false));
	}

	@Test
	public void scavengerBeastsDontScale()
	{
		// 14 kills of 22-30 on the target against the table's 30, in a party of three.
		assertTrue(!RaidScaling.scalesHitpoints(NpcID.RAIDS_SCAVENGER_BEAST_A));
		assertTrue(!RaidScaling.scalesHitpoints(NpcID.RAIDS_SCAVENGER_BEAST_B));
		// Everything else in the raid does.
		assertTrue(RaidScaling.scalesHitpoints(NpcID.RAIDS_TEKTON_WAITING));
		assertTrue(RaidScaling.scalesHitpoints(NpcID.RAIDS_ICEDEMON_COMBAT));
		assertTrue(RaidScaling.scalesHitpoints(NpcID.RAIDS_STONEGUARDIANS_LEFT));
	}

	@Test
	public void aFullFigureThatMayBeShortTakesNoBoundFromTheOverheadBar()
	{
		// A Tombs monster without the boss bar, in a party: its full figure is short (no party size), so a share of its
		// overhead bar is a share of too little. The count stands.
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, BEAST, 100, 0, true, true);
		health.damage(INDEX, BEAST, 20, 1);
		assertEquals(0, health.boundByRatio(INDEX, BEAST, 5, 30, 0, 2));
		assertEquals(80, health.remaining(INDEX, 2));
		// The same reading on a full figure that isn't short brings the count down.
		final TargetHealth chambers = new TargetHealth();
		chambers.setTableMaxHp(INDEX, BEAST, 100, 0, true, false);
		chambers.damage(INDEX, BEAST, 20, 1);
		assertTrue(chambers.boundByRatio(INDEX, BEAST, 5, 30, 0, 2) > 0);
	}

	@Test
	public void tombsHitpointsScaleWithTheRaidLevel()
	{
		// 2% per five raid levels: 300 is +120%, 150 is +60%; 0 leaves it.
		assertEquals(220, RaidScaling.tombsHitpoints(100, 300));
		assertEquals(160, RaidScaling.tombsHitpoints(100, 150));
		assertEquals(100, RaidScaling.tombsHitpoints(100, 0));
	}

	@Test
	public void reconcileNamesAScaledFigure()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, NpcID.RAIDS_TEKTON_WAITING, 600, 0, true);
		assertEquals("raid-scaled", health.maxSource(INDEX));
		health.setTableMaxHp(INDEX + 1, BEAST, 30, 0);
		assertEquals("table", health.maxSource(INDEX + 1));
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-170: a bow shot after a Soulflame horn Entice was booked to the horn. Eric's duo Tombs of 2026-11-04 (build
 * {@code 8e328d6}): the horn held to Entice his partner, a switch back to the Bow of Faerdhinen, and the shot thrown on
 * the switch's tick scored "as held on tick 72669, before a switch" (max 21, avg 1.10) against a 33 ([72672]; [74124]
 * the same, 31 against 23). The shot's Ranged experience came on its throw.
 */
public class SoulflameHornTest
{
	@Test
	public void aMeleeWeaponThrowsNoShotAndABowStrikesNoBlow()
	{
		assertFalse("the horn, held before the switch, can't have shot it",
			PvmPerformancePlugin.couldHaveThrown(true, false, true));
		assertTrue("a bow before a blowpipe (T-065) still can", PvmPerformancePlugin.couldHaveThrown(true, false, false));
		assertFalse("a bow before a melee switch can't have struck a blow",
			PvmPerformancePlugin.couldHaveThrown(false, true, false));
		assertTrue(PvmPerformancePlugin.couldHaveThrown(false, true, true));
		assertTrue("a cast is neither", PvmPerformancePlugin.couldHaveThrown(false, false, true));
		assertTrue("the tab not settled: as before", PvmPerformancePlugin.couldHaveThrown(true, false, null));
	}

	@Test
	public void enticeIsTheHornsFallAndNoOtherWeapons()
	{
		assertTrue(PvmPerformancePlugin.isEntice(ItemID.SOULFLAME_HORN, ItemID.BOW_OF_FAERDHINEN_INFINITE, false));
		// The switch back made on the Entice's own tick: the bow in hand, the horn on the tick before.
		assertTrue(PvmPerformancePlugin.isEntice(ItemID.BOW_OF_FAERDHINEN_INFINITE, ItemID.SOULFLAME_HORN, false));
		// A switch from the horn to a weapon with a special of its own: that weapon's special.
		assertFalse(PvmPerformancePlugin.isEntice(ItemID.DRAGON_WARHAMMER, ItemID.SOULFLAME_HORN, true));
		assertFalse(PvmPerformancePlugin.isEntice(ItemID.DRAGON_WARHAMMER, ItemID.DRAGON_WARHAMMER, true));
	}
}

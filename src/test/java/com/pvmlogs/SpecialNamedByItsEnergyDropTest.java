package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-185: a Dragon warhammer special thrown on the tick of the switch was named after the Burning claws held before it.
 * Eric's Araxxor kill 559 (log of 2026-11-09 15:33, build {@code 510dfe5}, read in place): [6672] the switch to the
 * warhammer and its special on one tick (GEAR worn: Dragon warhammer; DRAIN Dragon Warhammer, hit 71 at [6673]); [6678]
 * the claws' special. The Damage card showed "Burning claws (special)" twice, and SPEC LUCK said "0 of them my draining
 * specials".
 */
public class SpecialNamedByItsEnergyDropTest
{
	private static final int CLAWS = ItemID.BONE_CLAWS;
	private static final int DWH = ItemID.DRAGON_WARHAMMER;

	@Test
	public void eachSpecialIsNamedByItsOwnWeapon()
	{
		// The weapons held by tick: the claws to [6671], the warhammer on [6672]-[6677] (worn on the switch tick), then the
		// claws again.
		final Map<Integer, Integer> held = new HashMap<>();
		for (int tick = 6660; tick <= 6671; tick++)
		{
			held.put(tick, CLAWS);
		}
		for (int tick = 6672; tick <= 6677; tick++)
		{
			held.put(tick, DWH);
		}
		held.put(6678, CLAWS);
		// The energy falls, each with the weapon in hand as it fell.
		final Map<Integer, Integer> byDrop = new HashMap<>();
		byDrop.put(6672, DWH);
		byDrop.put(6678, CLAWS);
		// The switch on the throw's own tick: the ordinary row's rule reads the tick before it, the claws.
		final int switchTick = 6672;
		final int beforeSwitch = held.get(PvmPerformancePlugin.heldTick(switchTick, 6672));
		assertEquals("the cause: the tick before the switch", CLAWS, beforeSwitch);
		// The special's weapon is its drop's.
		assertEquals(DWH, PvmPerformancePlugin.specialWeapon(byDrop, 6672, beforeSwitch));
		assertEquals(SpecialWeapon.DRAGON_WARHAMMER, DrainLuck.weaponFor(
			PvmPerformancePlugin.specialWeapon(byDrop, 6672, beforeSwitch)));
		// The claws' special after the switch back ([6678]): the claws.
		assertEquals(CLAWS, PvmPerformancePlugin.specialWeapon(byDrop, 6678, held.get(6678)));
	}

	@Test
	public void aSpecialThrownATickAfterTheSwitchIsUnchanged()
	{
		final Map<Integer, Integer> byDrop = new HashMap<>();
		byDrop.put(6673, DWH);
		// The switch on [6672], the special on [6673]: held and dropped agree.
		assertEquals(DWH, PvmPerformancePlugin.specialWeapon(byDrop, 6673, DWH));
	}

	@Test
	public void theEyeOfAyaksSoulRendFromThePurgingStaffsRowIsStillTheEye()
	{
		// T-166: the Soul Rend scored from the Purging staff's row; the Eye in hand as its energy fell.
		final Map<Integer, Integer> byDrop = new HashMap<>();
		byDrop.put(7608, ItemID.EYE_OF_AYAK);
		assertEquals(ItemID.EYE_OF_AYAK, PvmPerformancePlugin.specialWeapon(byDrop, 7608, ItemID.PURGING_STAFF));
	}

	@Test
	public void withNoWeaponKeptForTheDropTheHeldOneStands()
	{
		final Map<Integer, Integer> byDrop = new HashMap<>();
		assertEquals(CLAWS, PvmPerformancePlugin.specialWeapon(byDrop, 6672, CLAWS));
		byDrop.put(6672, -1);
		assertEquals(CLAWS, PvmPerformancePlugin.specialWeapon(byDrop, 6672, CLAWS));
		assertNull(DrainLuck.weaponFor(CLAWS));
	}
}

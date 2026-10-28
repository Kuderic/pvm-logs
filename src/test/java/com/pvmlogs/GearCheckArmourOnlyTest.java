package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-058: the gear check judges armour only (Eric, 2026-10-28: "only use armour
 * gear"). The weapon and its ammo are held: arrows a bow of faerdhinen cannot
 * fire still read +49 ranged strength over a Rada's blessing, and were scored a
 * better loadout. The scorer here is a ranged-strength sum, so a slot is only
 * "better" by its bonus, which is what made the arrows look like an upgrade.
 */
public class GearCheckArmourOnlyTest
{
	private static final Map<Integer, Integer> RANGED_STRENGTH = new HashMap<>();

	static
	{
		RANGED_STRENGTH.put(ItemID.RUNE_ARROW, 49);
		RANGED_STRENGTH.put(ItemID.ZEAH_BLESSING_ELITE, 2);
		RANGED_STRENGTH.put(ItemID.DRAGON_BOLTS, 122);
		RANGED_STRENGTH.put(ItemID.DRAGON_BOLTS_ENCHANTED_RUBY, 122);
		RANGED_STRENGTH.put(ItemID.MASORI_BODY, 2);
		RANGED_STRENGTH.put(ItemID.CRYSTAL_CHESTPLATE_ITHELL, 0);
	}

	private static GearSearch.Score rangedStrength()
	{
		return gear ->
		{
			double total = 0;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				total += RANGED_STRENGTH.getOrDefault(gear.id(slot), 0);
			}
			return total;
		};
	}

	private static GearSearch.Candidate carried(EquipmentInventorySlot slot, int itemId)
	{
		return new GearSearch.Candidate(slot, itemId);
	}

	// Eric's Leviathan setup: a recoloured bow of faerdhinen with recoloured crystal armour and a Rada's blessing.
	private static Loadout leviathan()
	{
		return Loadout.worn(null, null)
			.with(EquipmentInventorySlot.WEAPON, ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL)
			.with(EquipmentInventorySlot.HEAD, ItemID.CRYSTAL_HELMET_ITHELL)
			.with(EquipmentInventorySlot.BODY, ItemID.CRYSTAL_CHESTPLATE_ITHELL)
			.with(EquipmentInventorySlot.LEGS, ItemID.CRYSTAL_PLATELEGS_ITHELL)
			.with(EquipmentInventorySlot.AMMO, ItemID.ZEAH_BLESSING_ELITE);
	}

	@Test
	public void runeArrowsInTheBagAreNoUpgradeForTheBow()
	{
		final Loadout worn = leviathan();
		final Loadout best = GearSearch.best(worn, Arrays.asList(carried(EquipmentInventorySlot.AMMO, ItemID.RUNE_ARROW)),
			false, rangedStrength());
		// Nothing better on hand, returned by identity: the gear part reads 100%.
		assertSame(worn, best);
		assertEquals(ItemID.ZEAH_BLESSING_ELITE, best.id(EquipmentInventorySlot.AMMO));
	}

	@Test
	public void aCrossbowsBetterBoltsAreNotSwappedIn()
	{
		final Loadout worn = Loadout.worn(null, null)
			.with(EquipmentInventorySlot.WEAPON, ItemID.ZARYTE_XBOW)
			.with(EquipmentInventorySlot.AMMO, ItemID.DRAGON_BOLTS);
		assertSame(worn, GearSearch.best(worn,
			Arrays.asList(carried(EquipmentInventorySlot.AMMO, ItemID.DRAGON_BOLTS_ENCHANTED_RUBY)), false,
			// Ruby bolts scored higher, as an enchanted bolt's proc would.
			gear -> gear.id(EquipmentInventorySlot.AMMO) == ItemID.DRAGON_BOLTS_ENCHANTED_RUBY ? 2 : 1));
	}

	@Test
	public void aRealArmourUpgradeIsStillFound()
	{
		final Loadout worn = leviathan();
		final List<GearSearch.Candidate> bag = Arrays.asList(carried(EquipmentInventorySlot.AMMO, ItemID.RUNE_ARROW),
			carried(EquipmentInventorySlot.BODY, ItemID.MASORI_BODY),
			carried(EquipmentInventorySlot.WEAPON, ItemID.ZARYTE_XBOW));
		final Loadout best = GearSearch.best(worn, bag, false, rangedStrength());
		assertEquals(ItemID.MASORI_BODY, best.id(EquipmentInventorySlot.BODY));
		// The weapon and the ammo as worn.
		assertEquals(ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL, best.id(EquipmentInventorySlot.WEAPON));
		assertEquals(ItemID.ZEAH_BLESSING_ELITE, best.id(EquipmentInventorySlot.AMMO));
	}

	@Test
	public void theHeldSlots()
	{
		assertTrue(GearSearch.heldWithTheWeapon(EquipmentInventorySlot.WEAPON));
		assertTrue(GearSearch.heldWithTheWeapon(EquipmentInventorySlot.AMMO));
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (slot != EquipmentInventorySlot.WEAPON && slot != EquipmentInventorySlot.AMMO)
			{
				assertTrue(slot.name(), !GearSearch.heldWithTheWeapon(slot));
			}
		}
	}

	@Test
	public void theGearIdealLine()
	{
		assertEquals("GEAR IDEAL BODY Crystal body -> Masori body expected 17.10 vs worn 16.32",
			PvmPerformancePlugin.gearIdealLine("BODY Crystal body -> Masori body", 17.1, 16.32));
		assertEquals("GEAR IDEAL the gear worn (nothing better carried) expected 16.32 vs worn 16.32",
			PvmPerformancePlugin.gearIdealLine(null, 16.32, 16.32));
	}
}

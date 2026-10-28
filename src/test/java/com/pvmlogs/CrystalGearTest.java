package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-056: crystal armour by item id, so every recolour counts with every crystal
 * bow and bow of faerdhinen (wiki, Crystal armour: 5% accuracy for the helm, 15%
 * for the body, 10% for the legs, half of each in damage; "recolouring only
 * changes appearance"), and the other variants the audit found.
 */
public class CrystalGearTest
{
	private static final int[][] ACTIVE = {
		{ItemID.CRYSTAL_HELMET, ItemID.CRYSTAL_CHESTPLATE, ItemID.CRYSTAL_PLATELEGS},
		{ItemID.CRYSTAL_HELMET_HEFIN, ItemID.CRYSTAL_CHESTPLATE_HEFIN, ItemID.CRYSTAL_PLATELEGS_HEFIN},
		{ItemID.CRYSTAL_HELMET_ITHELL, ItemID.CRYSTAL_CHESTPLATE_ITHELL, ItemID.CRYSTAL_PLATELEGS_ITHELL},
		{ItemID.CRYSTAL_HELMET_IORWERTH, ItemID.CRYSTAL_CHESTPLATE_IORWERTH, ItemID.CRYSTAL_PLATELEGS_IORWERTH},
		{ItemID.CRYSTAL_HELMET_TRAHAEARN, ItemID.CRYSTAL_CHESTPLATE_TRAHAEARN, ItemID.CRYSTAL_PLATELEGS_TRAHAEARN},
		{ItemID.CRYSTAL_HELMET_CADARN, ItemID.CRYSTAL_CHESTPLATE_CADARN, ItemID.CRYSTAL_PLATELEGS_CADARN},
		{ItemID.CRYSTAL_HELMET_CRWYS, ItemID.CRYSTAL_CHESTPLATE_CRWYS, ItemID.CRYSTAL_PLATELEGS_CRWYS},
		{ItemID.CRYSTAL_HELMET_AMLODD, ItemID.CRYSTAL_CHESTPLATE_AMLODD, ItemID.CRYSTAL_PLATELEGS_AMLODD},
		{ItemID.CRYSTAL_HELMET_DEADMAN, ItemID.CRYSTAL_CHESTPLATE_DEADMAN, ItemID.CRYSTAL_PLATELEGS_DEADMAN},
		{ItemID.BR_CRYSTAL_HELMET, ItemID.BR_CRYSTAL_CHESTPLATE, ItemID.BR_CRYSTAL_PLATELEGS},
	};

	private static final int[][] INACTIVE = {
		{ItemID.CRYSTAL_HELMET_INACTIVE, ItemID.CRYSTAL_CHESTPLATE_INACTIVE, ItemID.CRYSTAL_PLATELEGS_INACTIVE},
		{ItemID.CRYSTAL_HELMET_INACTIVE_HEFIN, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_HEFIN,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_HEFIN},
		{ItemID.CRYSTAL_HELMET_INACTIVE_ITHELL, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_ITHELL,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_ITHELL},
		{ItemID.CRYSTAL_HELMET_INACTIVE_IORWERTH, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_IORWERTH,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_IORWERTH},
		{ItemID.CRYSTAL_HELMET_INACTIVE_TRAHAEARN, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_TRAHAEARN,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_TRAHAEARN},
		{ItemID.CRYSTAL_HELMET_INACTIVE_CADARN, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_CADARN,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_CADARN},
		{ItemID.CRYSTAL_HELMET_INACTIVE_CRWYS, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_CRWYS,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_CRWYS},
		{ItemID.CRYSTAL_HELMET_INACTIVE_AMLODD, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_AMLODD,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_AMLODD},
		{ItemID.CRYSTAL_HELMET_INACTIVE_DEADMAN, ItemID.CRYSTAL_CHESTPLATE_INACTIVE_DEADMAN,
			ItemID.CRYSTAL_PLATELEGS_INACTIVE_DEADMAN},
	};

	private static final int[] BOWS = {
		ItemID.CRYSTAL_BOW, ItemID.CRYSTAL_BOW_2500,
		ItemID.BOW_OF_FAERDHINEN, ItemID.BOW_OF_FAERDHINEN_INFINITE,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL, ItemID.BOW_OF_FAERDHINEN_INFINITE_IORWERTH,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_TRAHAEARN, ItemID.BOW_OF_FAERDHINEN_INFINITE_CADARN,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_CRWYS, ItemID.BOW_OF_FAERDHINEN_INFINITE_MEILYR,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_AMLODD, ItemID.BOW_OF_FAERDHINEN_INFINITE_DEADMAN,
		ItemID.BR_BOW_OF_FAERDHINEN,
	};

	private static Loadout wearing(int bow, int helm, int body, int legs)
	{
		return Loadout.worn(null, null).with(EquipmentInventorySlot.WEAPON, bow)
			.with(EquipmentInventorySlot.HEAD, helm).with(EquipmentInventorySlot.BODY, body)
			.with(EquipmentInventorySlot.LEGS, legs);
	}

	@Test
	public void everyActiveSetCountsWithEveryBow()
	{
		for (int bow : BOWS)
		{
			for (int[] set : ACTIVE)
			{
				final GearBonus b = GearBonusCalc.crystalBonus(wearing(bow, set[0], set[1], set[2]));
				assertEquals("bow " + bow + ", helm " + set[0], 1.30, b.getAccuracy(), 1e-9);
				assertEquals(1.15, b.getDamage(), 1e-9);
				assertEquals(1.05, GearBonusCalc.crystalBonus(wearing(bow, set[0], -1, -1)).getAccuracy(), 1e-9);
				assertEquals(1.15, GearBonusCalc.crystalBonus(wearing(bow, -1, set[1], -1)).getAccuracy(), 1e-9);
				assertEquals(1.10, GearBonusCalc.crystalBonus(wearing(bow, -1, -1, set[2])).getAccuracy(), 1e-9);
				assertEquals(1.05, GearBonusCalc.crystalBonus(wearing(bow, -1, -1, set[2])).getDamage(), 1e-9);
			}
		}
	}

	@Test
	public void inactivePiecesGiveNothing()
	{
		for (int bow : BOWS)
		{
			for (int[] set : INACTIVE)
			{
				assertTrue(GearBonusCalc.crystalBonus(wearing(bow, set[0], set[1], set[2])).isNone());
			}
		}
	}

	/** Not with an inactive bow, a historical or imbued crystal bow, or any other bow. */
	@Test
	public void onlyTheCrystalBows()
	{
		final int[] set = ACTIVE[8];
		for (int bow : new int[]{ItemID.CRYSTAL_BOW_INACTIVE, ItemID.BOW_OF_FAERDHINEN_INACTIVE,
			ItemID.BOW_OF_FAERDHINEN_INFINITE_DUMMY, ItemID.ROVING_CRYSTAL_BOW_NEW, ItemID.NZONE_CRYSTAL_BOW_NEW,
			ItemID.TWISTED_BOW, -1})
		{
			assertTrue("bow " + bow, GearBonusCalc.crystalBonus(wearing(bow, set[0], set[1], set[2])).isNone());
		}
	}

	/**
	 * Eric's Leviathan kill of 2026-10-28: the Deadman set and a bow of faerdhinen
	 * (c), read at a max of 39 without the armour, then a 41 landed. With it the max
	 * is ⌊39 × 1.15⌋ = 44, which covers the 41.
	 */
	@Test
	public void theLeviathanKillsMaxCoversItsHit()
	{
		final GearBonus b = GearBonusCalc.crystalBonus(wearing(ItemID.BOW_OF_FAERDHINEN_INFINITE,
			ItemID.CRYSTAL_HELMET_DEADMAN, ItemID.CRYSTAL_CHESTPLATE_DEADMAN, ItemID.CRYSTAL_PLATELEGS_DEADMAN));
		final int max = (int) (39 * b.getDamage());
		assertEquals(44, max);
		assertTrue(max >= 41);
	}

	/** Every bow of faerdhinen and crystal bow makes its own arrows: none of them reads the ammo slot. */
	@Test
	public void everyCrystalBowIsAmmoless()
	{
		for (int bow : BOWS)
		{
			assertTrue("bow " + bow, CombatCalc.isAmmoless(bow));
		}
		assertTrue(CombatCalc.isAmmoless(ItemID.WILD_CAVE_WEBWEAVER_CHARGED));
		assertFalse(CombatCalc.isAmmoless(ItemID.TWISTED_BOW));
	}

	/** The other variants the audit added. */
	@Test
	public void theAuditedVariants()
	{
		final Loadout br = Loadout.worn(null, null).with(EquipmentInventorySlot.HEAD, ItemID.BR_INQUISITORS_HELM)
			.with(EquipmentInventorySlot.BODY, ItemID.BR_INQUISITORS_BODY)
			.with(EquipmentInventorySlot.LEGS, ItemID.BR_INQUISITORS_SKIRT);
		assertEquals(1.025, GearBonusCalc.inquisitorsBonus(AttackType.CRUSH, br).getAccuracy(), 1e-9);
		final Loadout ornament = Loadout.worn(null, null).with(EquipmentInventorySlot.WEAPON, ItemID.TZHAAR_MAUL)
			.with(EquipmentInventorySlot.AMULET, ItemID.JEWL_BESERKER_NECKLACE_ORNAMENT);
		assertEquals(1.2, GearBonusCalc.obsidianBonus(AttackType.CRUSH, ornament).getDamage(), 1e-9);
		assertEquals(1.6, GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.AGRITH_SILVERLIGHT_DYED).getDamage(),
			1e-9);
		assertTrue(GearBonusCalc.isTwistedBow(ItemID.DEADMAN_BLIGHTED_TWISTED_BOW));
		assertTrue(GearBonusCalc.isTwistedBow(ItemID.TWISTED_BOW));
	}
}

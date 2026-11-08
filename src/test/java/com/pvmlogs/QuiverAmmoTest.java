package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemEquipmentStats;
import org.junit.Test;

/**
 * T-182: the ammo in Dizana's quiver's own slot is fired when the ammo slot holds nothing the weapon fires. OSRS Wiki,
 * Dizana's quiver, read 2026-11-08: "the applicable type shot depending on the player's currently equipped ranged
 * weapon", "with the ammo in the actual ammo slot prioritised", and a god blessing can sit in the slot beside it. Eric's
 * Olm of 11-06 (runs 4 and 5 of pvm-debug-20261106-113840.log, build 7eca1b6): a Dragon hunter crossbow (t), the quiver,
 * Rada's blessing 4 in the slot, scored as if it fired nothing: a max of 21 with 52s, 57s and ruby procs of 100 landing.
 */
public class QuiverAmmoTest
{
	private static final int FIRE_CAPE = 6570;
	private static final int ANGUISH = ItemID.ZENYTE_NECKLACE_ENCHANTED;
	private static final int BLESSING = ItemID.ZEAH_BLESSING_ELITE;
	private static final int RUBY_DRAGON = ItemID.DRAGON_BOLTS_ENCHANTED_RUBY;
	private static final int DIAMOND_DRAGON = ItemID.DRAGON_BOLTS_ENCHANTED_DIAMOND;
	private static final int DRAGONSTONE_DRAGON = ItemID.DRAGON_BOLTS_ENCHANTED_DRAGONSTONE;
	private static final int DHCB = ItemID.DRAGONHUNTER_XBOW_VORKATH;
	private static final int TBOW = ItemID.TWISTED_BOW;

	private static final Map<Integer, String> NAMES = new HashMap<>();
	private static final Map<Integer, ItemEquipmentStats> STATS = new HashMap<>();

	static
	{
		NAMES.put(ItemID.DIZANAS_QUIVER_CHARGED, "Dizana's quiver");
		NAMES.put(FIRE_CAPE, "Fire cape");
		NAMES.put(ANGUISH, "Necklace of anguish");
		NAMES.put(BLESSING, "Rada's blessing 4");
		NAMES.put(RUBY_DRAGON, "Ruby dragon bolts (e)");
		NAMES.put(DIAMOND_DRAGON, "Diamond dragon bolts (e)");
		NAMES.put(DRAGONSTONE_DRAGON, "Dragonstone dragon bolts (e)");
		NAMES.put(ItemID.DRAGON_ARROW, "Dragon arrow");
		NAMES.put(DHCB, "Dragon hunter crossbow (t)");
		NAMES.put(TBOW, "Twisted bow");
		// Dragon bolts' +122 ranged strength and the anguish's +5; the others are stand-in figures that make each item's
		// share visible in a sum, not their game stats.
		STATS.put(RUBY_DRAGON, stats(0, 122));
		STATS.put(DIAMOND_DRAGON, stats(0, 122));
		STATS.put(ItemID.DRAGON_ARROW, stats(0, 60));
		STATS.put(ANGUISH, stats(15, 5));
		STATS.put(BLESSING, stats(1, 1000));
		STATS.put(DHCB, stats(95, 0));
	}

	private static ItemEquipmentStats stats(int arange, int rstr)
	{
		return ItemEquipmentStats.builder().arange(arange).rstr(rstr).build();
	}

	private static final IntFunction<String> NAMER = NAMES::get;
	private static final IntFunction<ItemEquipmentStats> STATS_OF = STATS::get;

	private static Loadout gear(int cape, int weapon, int ammo, int quiver)
	{
		return Loadout.named(NAMER).with(EquipmentInventorySlot.CAPE, cape)
			.with(EquipmentInventorySlot.AMULET, ANGUISH)
			.with(EquipmentInventorySlot.WEAPON, weapon)
			.with(EquipmentInventorySlot.AMMO, ammo)
			.withQuiverAmmo(quiver);
	}

	private static int rangedStrength(Loadout gear, WeaponCategory weapon)
	{
		return CombatCalc.wornBonus(gear, true, gear.firesFromQuiver(weapon), STATS_OF, ItemEquipmentStats::getRstr);
	}

	@Test
	public void aCrossbowWithBoltsInTheQuiverAndABlessingInTheSlotFiresTheBolts()
	{
		final Loadout olm = gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, BLESSING, RUBY_DRAGON);
		assertTrue(olm.firesFromQuiver(WeaponCategory.CROSSBOW));
		assertEquals(RUBY_DRAGON, olm.firedAmmo(WeaponCategory.CROSSBOW));
		assertEquals("Ruby dragon bolts (e)", olm.firedAmmoName(WeaponCategory.CROSSBOW));
		// The bolts' strength and the blessing's own both count, as they would with the bolts in the slot.
		assertEquals(122 + 1000 + 5, rangedStrength(olm, WeaponCategory.CROSSBOW));
		final Loadout slotBolts = gear(FIRE_CAPE, DHCB, RUBY_DRAGON, -1);
		assertEquals(122 + 5, rangedStrength(slotBolts, WeaponCategory.CROSSBOW));
		// So the max is the bolts': an effective strength of 158 reaches 47 with them and 17 without.
		assertEquals(47, CombatCalc.maxHitFromStrength(158, 122 + 5));
		assertEquals(17, CombatCalc.maxHitFromStrength(158, 5));
		// The effects come with them: the ruby bolt, and Dizana's Sunfire for bolts from either slot.
		assertEquals(EnchantedBolt.RUBY, EnchantedBolt.forAmmoName(olm.firedAmmoName(WeaponCategory.CROSSBOW)));
		assertTrue(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_CHARGED, olm.firedAmmoName(WeaponCategory.CROSSBOW),
			WeaponCategory.CROSSBOW));
		// The gear line names the quiver's ammo.
		assertTrue(CombatCalc.describe(olm), CombatCalc.describe(olm).endsWith(
			"ammo Rada's blessing 4 #" + BLESSING + ", quiver Ruby dragon bolts (e) #" + RUBY_DRAGON));
	}

	@Test
	public void boltsInTheSlotAreFiredFirst()
	{
		final Loadout both = gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, DIAMOND_DRAGON, RUBY_DRAGON);
		assertFalse(both.firesFromQuiver(WeaponCategory.CROSSBOW));
		assertEquals(DIAMOND_DRAGON, both.firedAmmo(WeaponCategory.CROSSBOW));
		assertEquals(EnchantedBolt.DIAMOND, EnchantedBolt.forAmmoName(both.firedAmmoName(WeaponCategory.CROSSBOW)));
		// Only the slot's bolts count, not the quiver's as well.
		assertEquals(122 + 5, rangedStrength(both, WeaponCategory.CROSSBOW));
	}

	@Test
	public void aBowWithArrowsInTheQuiverWorksTheSame()
	{
		final Loadout blessed = gear(ItemID.DIZANAS_QUIVER_CHARGED, TBOW, BLESSING, ItemID.DRAGON_ARROW);
		assertTrue(blessed.firesFromQuiver(WeaponCategory.BOW));
		assertEquals(ItemID.DRAGON_ARROW, blessed.firedAmmo(WeaponCategory.BOW));
		assertEquals(60 + 1000 + 5, rangedStrength(blessed, WeaponCategory.BOW));
		// Bolts in the slot are carried for a crossbow: the bow fires the quiver's arrows, and the bolts' strength isn't the
		// shot's (labelled a guess in wornBonus).
		final Loadout bolts = gear(ItemID.DIZANAS_QUIVER_CHARGED, TBOW, RUBY_DRAGON, ItemID.DRAGON_ARROW);
		assertTrue(bolts.firesFromQuiver(WeaponCategory.BOW));
		assertEquals(60 + 5, rangedStrength(bolts, WeaponCategory.BOW));
		// And the crossbow, switched to, fires the slot's bolts.
		assertFalse(bolts.firesFromQuiver(WeaponCategory.CROSSBOW));
		assertEquals(RUBY_DRAGON, bolts.firedAmmo(WeaponCategory.CROSSBOW));
	}

	@Test
	public void ammoTheWeaponDoesntFireStaysInTheQuiver()
	{
		// Arrows in the quiver don't feed a crossbow: the slot's blessing is all there is, as before.
		final Loadout arrows = gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, BLESSING, ItemID.DRAGON_ARROW);
		assertFalse(arrows.firesFromQuiver(WeaponCategory.CROSSBOW));
		assertEquals(BLESSING, arrows.firedAmmo(WeaponCategory.CROSSBOW));
		assertEquals(1000 + 5, rangedStrength(arrows, WeaponCategory.CROSSBOW));
		// Nor a thrown weapon or melee.
		assertFalse(gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, BLESSING, RUBY_DRAGON).firesFromQuiver(WeaponCategory.THROWN));
		// An empty quiver.
		assertEquals(-1, gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, BLESSING, -1).quiverAmmo());
	}

	@Test
	public void noQuiverIsUnchanged()
	{
		// The varp still holding bolts from a quiver taken off: no quiver worn, no second slot.
		final Loadout fireCape = gear(FIRE_CAPE, DHCB, BLESSING, RUBY_DRAGON);
		assertEquals(-1, fireCape.quiverAmmo());
		assertNull(fireCape.quiverAmmoName());
		assertFalse(fireCape.firesFromQuiver(WeaponCategory.CROSSBOW));
		assertEquals(BLESSING, fireCape.firedAmmo(WeaponCategory.CROSSBOW));
		assertFalse(CombatCalc.describe(fireCape).contains("quiver"));
		// Every slot summed as the two loops did, the ammo slot left out only where it doesn't feed the weapon.
		assertEquals(1000 + 5, rangedStrength(fireCape, WeaponCategory.CROSSBOW));
		assertEquals(5, CombatCalc.wornBonus(fireCape, false, false, STATS_OF, ItemEquipmentStats::getRstr));
		assertEquals(95 + 15 + 1, CombatCalc.wornBonus(fireCape, true, false, STATS_OF,
			CombatCalc.attackBonusOf(AttackType.RANGED)));
	}

	@Test
	public void theQuiverIsPartOfTheLoadout()
	{
		final Loadout a = gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, BLESSING, RUBY_DRAGON);
		assertFalse(a.sameItems(a.withQuiverAmmo(DIAMOND_DRAGON)));
		assertTrue(a.sameItems(gear(ItemID.DIZANAS_QUIVER_CHARGED, DHCB, BLESSING, RUBY_DRAGON)));
		// A swapped slot keeps the quiver's ammo: the gear search's candidates fire what the worn gear fires.
		assertEquals(RUBY_DRAGON, a.with(EquipmentInventorySlot.HEAD, 1).quiverAmmo());
		// Every quiver with the slot: uncharged, charged, blessed and the max cape, and their trouver versions.
		for (int cape : new int[]{ItemID.DIZANAS_QUIVER_UNCHARGED, ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER,
			ItemID.DIZANAS_QUIVER_CHARGED, ItemID.DIZANAS_QUIVER_CHARGED_TROUVER, ItemID.DIZANAS_QUIVER_INFINITE,
			ItemID.DIZANAS_QUIVER_INFINITE_TROUVER, ItemID.SKILLCAPE_MAX_DIZANAS, ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER})
		{
			assertTrue(String.valueOf(cape), Loadout.hasQuiverSlot(cape));
		}
		assertFalse(Loadout.hasQuiverSlot(FIRE_CAPE));
	}

	@Test
	public void dragonsBreath()
	{
		assertEquals(EnchantedBolt.DRAGONSTONE, EnchantedBolt.forAmmoName("Dragonstone dragon bolts (e)"));
		assertEquals(EnchantedBolt.DRAGONSTONE, EnchantedBolt.forAmmoName("Dragonstone bolts (e)"));
		assertEquals(0.06, EnchantedBolt.DRAGONSTONE.chance(false), 1e-12);
		// A fifth of the visible Ranged level, rounded down: 22 at 112, 24 at 120.
		assertEquals(22, EnchantedBolt.dragonstoneDamage(112));
		assertEquals(24, EnchantedBolt.dragonstoneDamage(120));
		// The brute force: landed with p, a roll of 0..40 (the 0 pushed to 1), plus 22 with the proc's chance.
		final double p = 0.7;
		final double c = 0.06;
		final int extra = 22;
		final int max = 40;
		double mean = 0;
		double second = 0;
		for (int r = 0; r <= max; r++)
		{
			final double d = Math.max(1, r);
			mean += p / (max + 1) * (d + c * extra);
			second += p / (max + 1) * ((1 - c) * d * d + c * (d + extra) * (d + extra));
		}
		final DamageMoments ordinary = CombatCalc.ordinaryMoments(max, p, 1, Integer.MAX_VALUE);
		final DamageMoments got = CombatCalc.dragonstoneMoments(c, extra, p, ordinary);
		assertEquals(mean, got.mean, 1e-9);
		assertEquals(second, got.second, 1e-9);
	}
}

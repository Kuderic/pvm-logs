package com.pvmlogs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

// A set of worn items, by slot, and the ammo in a worn Dizana's quiver (T-182).
final class Loadout
{
	private static final int SLOTS = EquipmentInventorySlot.AMMO.getSlotIdx() + 1;

	// An item's name from its id: the item manager's in the client, a table in a test.
	private final IntFunction<String> namer;
	private final int[] ids;
	private final String[] names = new String[SLOTS];
	// The quiver's ammo as the game's varp holds it, -1 for none; only read with a quiver worn (quiverAmmo).
	private final int quiver;
	private String quiverName;

	private Loadout(IntFunction<String> namer, int[] ids, int quiver)
	{
		this.namer = namer;
		this.ids = ids;
		this.quiver = quiver;
	}

	/** What the player is wearing, or an empty loadout if that cannot be read. */
	static Loadout worn(ItemManager itemManager, ItemContainer equipment)
	{
		return worn(itemManager, equipment, -1);
	}

	/** As above, with the item in a Dizana's quiver's own ammo slot (-1 for none). */
	static Loadout worn(ItemManager itemManager, ItemContainer equipment, int quiverAmmo)
	{
		final int[] ids = new int[SLOTS];
		Arrays.fill(ids, -1);
		if (equipment != null)
		{
			for (int slot = 0; slot < SLOTS; slot++)
			{
				final Item item = equipment.getItem(slot);
				ids[slot] = item == null ? -1 : item.getId();
			}
		}
		return new Loadout(itemManager == null ? null : id -> itemManager.getItemComposition(id).getName(), ids,
			quiverAmmo);
	}

	/** Nothing worn, with names from this table: for tests, which have no item manager. */
	static Loadout named(IntFunction<String> namer)
	{
		final int[] ids = new int[SLOTS];
		Arrays.fill(ids, -1);
		return new Loadout(namer, ids, -1);
	}

	/**
	 * This loadout with one slot swapped, leaving this one untouched: a search
	 * walks candidates by making these, so it must not disturb what it came
	 * from.
	 */
	Loadout with(EquipmentInventorySlot slot, int itemId)
	{
		final int idx = slot.getSlotIdx();
		if (idx < 0 || idx >= SLOTS || ids[idx] == itemId)
		{
			return this;
		}
		final int[] swapped = ids.clone();
		swapped[idx] = itemId;
		return new Loadout(namer, swapped, quiver);
	}

	/** This loadout with this item (-1 for none) in the quiver's own ammo slot. */
	Loadout withQuiverAmmo(int itemId)
	{
		return itemId == quiver ? this : new Loadout(namer, ids, itemId);
	}

	/**
	 * Whether two loadouts hold the same item in every slot. Compared by
	 * contents, not identity: the worn loadout is rebuilt every tick, so two
	 * objects describing the same gear are the normal case.
	 */
	boolean sameItems(Loadout other)
	{
		return other != null && Arrays.equals(ids, other.ids) && quiver == other.quiver;
	}

	int id(EquipmentInventorySlot slot)
	{
		final int idx = slot.getSlotIdx();
		return idx < 0 || idx >= SLOTS ? -1 : ids[idx];
	}

	String name(EquipmentInventorySlot slot)
	{
		final int idx = slot.getSlotIdx();
		if (idx < 0 || idx >= SLOTS)
		{
			return null;
		}
		if (names[idx] == null)
		{
			final int id = ids[idx];
			names[idx] = nameOf(id);
		}
		return names[idx].isEmpty() ? null : names[idx];
	}

	private String nameOf(int id)
	{
		final String name = id < 0 || namer == null ? null : namer.apply(id);
		return name == null ? "" : name;
	}

	/**
	 * T-182: the Dizana's quivers with their own ammo slot, and the max cape made from one, each with its trouver version
	 * (gameval names). OSRS Wiki, Dizana's quiver, read 2026-11-08: the quiver "can only be filled with arrows or bolts",
	 * its slot there "if the quiver is in the inventory or equipped"; the uncharged and locked versions open and fill too.
	 * RuneLite's own ammo plugin tells a quiver by an item param rather than ids; ids are what a test can build.
	 */
	private static final Set<Integer> QUIVER_CAPES = new HashSet<>(Arrays.asList(
		ItemID.DIZANAS_QUIVER_UNCHARGED, ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER,
		ItemID.DIZANAS_QUIVER_CHARGED, ItemID.DIZANAS_QUIVER_CHARGED_TROUVER,
		ItemID.DIZANAS_QUIVER_INFINITE, ItemID.DIZANAS_QUIVER_INFINITE_TROUVER,
		ItemID.SKILLCAPE_MAX_DIZANAS, ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER));

	/** Whether this cape is a Dizana's quiver with its own ammo slot. */
	static boolean hasQuiverSlot(int capeId)
	{
		return QUIVER_CAPES.contains(capeId);
	}

	/** T-182: the item in the worn quiver's own ammo slot, or -1 when none is there or no quiver is worn. */
	int quiverAmmo()
	{
		return quiver > 0 && hasQuiverSlot(id(EquipmentInventorySlot.CAPE)) ? quiver : -1;
	}

	/** {@link #quiverAmmo()}'s name, or null. */
	String quiverAmmoName()
	{
		final int id = quiverAmmo();
		if (id < 0)
		{
			return null;
		}
		if (quiverName == null)
		{
			quiverName = nameOf(id);
		}
		return quiverName.isEmpty() ? null : quiverName;
	}

	/**
	 * T-182: whether this weapon fires this ammo: bolts from a crossbow, arrows from a bow. The quiver holds only those
	 * two, so they are the only kinds that choose between its slot and the equipment's.
	 */
	static boolean fires(WeaponCategory weapon, String ammoName)
	{
		if (ammoName == null)
		{
			return false;
		}
		final String ammo = ammoName.toLowerCase();
		return weapon == WeaponCategory.CROSSBOW && ammo.contains("bolt")
			|| weapon == WeaponCategory.BOW && ammo.contains("arrow");
	}

	/**
	 * T-182: whether this weapon fires the quiver's ammo rather than the ammo slot's. OSRS Wiki, Dizana's quiver, read
	 * 2026-11-08: "the applicable type shot depending on the player's currently equipped ranged weapon", "with the ammo in
	 * the actual ammo slot prioritised", and the quiver's ammo can be paired with "a god blessing" in the slot. So: the
	 * quiver's, when it holds what the weapon fires and the slot doesn't. Whether the weapon fires ammo at all (thrown,
	 * ammoless bows) is the caller's to ask first.
	 */
	boolean firesFromQuiver(WeaponCategory weapon)
	{
		return quiverAmmo() >= 0 && fires(weapon, quiverAmmoName()) && !fires(weapon, name(EquipmentInventorySlot.AMMO));
	}

	/** T-182: the ammo this weapon fires: the quiver's (see {@link #firesFromQuiver}) or else the ammo slot's. */
	int firedAmmo(WeaponCategory weapon)
	{
		return firesFromQuiver(weapon) ? quiverAmmo() : id(EquipmentInventorySlot.AMMO);
	}

	/** {@link #firedAmmo}'s name, or null. */
	String firedAmmoName(WeaponCategory weapon)
	{
		return firesFromQuiver(weapon) ? quiverAmmoName() : name(EquipmentInventorySlot.AMMO);
	}
}

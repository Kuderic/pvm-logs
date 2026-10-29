package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-071 (Eric, 2026-10-28: "Bow of Faerdhinen (Shadow Rush) becomes Shadow
 * Rush"): a cast is named by its spell alone, on the loadout and the Damage
 * card, for new kills and for kills saved as {@code weapon (Spell)}. Weapons
 * without a spell, and their variant tags, are as they were.
 *
 * <p>The name is {@link CombatCalc#attackName(String, String)}, called with the
 * spell the plugin read: a manual cast over the autocast, none for a powered
 * staff's own attack. That choice needs a client and is unchanged by T-071, so
 * these call the name with what it would be given.
 */
public class SpellNamedAloneTest
{
	@Test
	public void aManualCastWithABowInHandIsTheSpell()
	{
		assertEquals("Shadow Rush", CombatCalc.attackName("Bow of faerdhinen (c)", Spell.SHADOW_RUSH.getDisplayName()));
	}

	@Test
	public void anAutocastOnAStaffIsTheSpell()
	{
		assertEquals("Fire Surge", CombatCalc.attackName("Harmonised nightmare staff", Spell.FIRE_SURGE.getDisplayName()));
	}

	@Test
	public void aPoweredStaffsOwnAttackIsTheStaff()
	{
		assertEquals("Tumeken's shadow", CombatCalc.attackName("Tumeken's shadow", null));
		assertEquals("Sanguinesti staff", CombatCalc.attackName("Sanguinesti staff", null));
		assertEquals("Unarmed", CombatCalc.attackName(null, null));
	}

	@Test
	public void aKillSavedAsWeaponAndSpellShowsTheSpell()
	{
		assertEquals("Shadow Rush", LedgerRows.attackLabel("Bow of Faerdhinen (c) (Shadow Rush)"));
		assertEquals("Ice Barrage", LedgerRows.attackLabel("Kodai wand (Ice Barrage)"));
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Bow of Faerdhinen (c) (Shadow Rush)";
		l.dps = 4.2;
		assertEquals("Shadow Rush", LedgerRows.loadout(l, false).get(0).left);
	}

	@Test
	public void variantTagsAreNotSpells()
	{
		assertEquals("Abyssal tentacle", LedgerRows.attackLabel("Abyssal tentacle (or)"));
		assertEquals("Bow of faerdhinen", LedgerRows.attackLabel("Bow of faerdhinen (c)"));
		// Capitalised but not a spell the plugin knows: left as it is.
		assertEquals("Ocellus (The Blade)", LedgerRows.attackLabel("Ocellus (The Blade)"));
		assertEquals("Shadow Rush", LedgerRows.attackLabel("Shadow Rush"));
		assertNull(LedgerRows.attackLabel(null));
	}

	// ---- the Damage card ----

	private static FightTimeline.Attack attack(String key, int itemId, Spell spell, int damage)
	{
		final FightTimeline.Attack a = new FightTimeline.Attack(0);
		a.key = key;
		a.itemId = itemId;
		a.spell = spell;
		a.style = spell == null ? FightTimeline.Style.RANGED : FightTimeline.Style.MAGIC;
		a.damage = damage;
		a.mean = 10;
		return a;
	}

	private static List<SidePanelModel.WeaponRow> rows(FightTimeline.Attack... attacks)
	{
		final Map<String, SidePanelModel.WeaponRow> rows = new LinkedHashMap<>();
		for (FightTimeline.Attack a : attacks)
		{
			SidePanelWeaponRows.add(rows, a);
		}
		return new ArrayList<>(rows.values());
	}

	@Test
	public void anOldKillsCastIsTheSpellOnTheDamageCard()
	{
		final List<SidePanelModel.WeaponRow> rows = rows(
			attack("Bow of Faerdhinen (c) (Shadow Rush)", ItemID.BOW_OF_FAERDHINEN_INFINITE, Spell.SHADOW_RUSH, 14),
			attack("Bow of Faerdhinen (c)", ItemID.BOW_OF_FAERDHINEN_INFINITE, null, 30));
		assertEquals(2, rows.size());
		assertEquals("Shadow Rush", rows.get(0).name);
		assertEquals(Spell.SHADOW_RUSH, rows.get(0).spell);
		assertEquals("Bow of Faerdhinen", rows.get(1).name);
	}

	@Test
	public void twoStavesCastingOneSpellAreOneRow()
	{
		// A new kill: both are booked as the spell.
		final List<SidePanelModel.WeaponRow> now = rows(
			attack("Fire Surge", ItemID.NIGHTMARE_STAFF_HARMONISED, Spell.FIRE_SURGE, 20),
			attack("Fire Surge", ItemID.PURGING_STAFF, Spell.FIRE_SURGE, 25));
		assertEquals(1, now.size());
		assertEquals(2, now.get(0).attacks);
		assertEquals(45, now.get(0).damage);
		// An old kill, booked under each staff: merged by the name shown.
		final List<SidePanelModel.WeaponRow> old = rows(
			attack("Harmonised nightmare staff (Fire Surge)", ItemID.NIGHTMARE_STAFF_HARMONISED, Spell.FIRE_SURGE, 20),
			attack("Purging staff (Fire Surge)", ItemID.PURGING_STAFF, Spell.FIRE_SURGE, 25),
			attack("Fire Surge", ItemID.PURGING_STAFF, Spell.FIRE_SURGE, 5));
		assertEquals(1, old.size());
		assertEquals("Fire Surge", old.get(0).name);
		assertEquals(3, old.get(0).attacks);
		assertEquals(50, old.get(0).damage);
		assertEquals(30.0, old.get(0).expected, 1e-9);
	}

	@Test
	public void aSpecialStaysItsOwnRow()
	{
		final FightTimeline.Attack spec = attack("Bow of Faerdhinen (c)", ItemID.BOW_OF_FAERDHINEN_INFINITE, null, 40);
		spec.special = true;
		final List<SidePanelModel.WeaponRow> rows = rows(
			attack("Bow of Faerdhinen (c)", ItemID.BOW_OF_FAERDHINEN_INFINITE, null, 30), spec);
		assertEquals(2, rows.size());
	}
}

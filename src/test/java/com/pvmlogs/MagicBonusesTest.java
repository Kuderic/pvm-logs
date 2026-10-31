package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * T-026: demonbane, Mark of Darkness, the purging staff, and the magic bonuses
 * that were missing, each from its wiki page (read 2026-10-27). The gear is by
 * item id; the client is a stand-in that answers the magic level, the worn
 * weapon and the inventory.
 */
public class MagicBonusesTest
{
	private static final double DELTA = 1e-9;
	private static final int DUKE = 12191;
	private static final int VOID_FLARE = 14179;
	private static final int SIRE = 5886;

	private final Map<Integer, Item[]> containers = new HashMap<>();
	private GearBonusCalc calc;
	private MonsterStatsProvider monsters;

	private Client client()
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getRealSkillLevel":
					case "getBoostedSkillLevel":
						return args[0] == Skill.MAGIC ? 99 : 1;
					case "getItemContainer":
						final Item[] items = args[0] instanceof Integer ? containers.get((Integer) args[0]) : null;
						return items == null ? null : container(items);
					default:
						break;
				}
				final Class<?> type = method.getReturnType();
				if (type == int.class)
				{
					return 0;
				}
				if (type == boolean.class)
				{
					return false;
				}
				if (type == long.class)
				{
					return 0L;
				}
				return null;
			});
	}

	private static ItemContainer container(Item[] items)
	{
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
			new Class<?>[]{ItemContainer.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getItems":
						return items;
					case "getItem":
						final int slot = (Integer) args[0];
						return slot < items.length ? items[slot] : null;
					default:
						return method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false
							: null;
				}
			});
	}

	private void wield(int weapon)
	{
		final Item[] worn = new Item[14];
		worn[EquipmentInventorySlot.WEAPON.getSlotIdx()] = new Item(weapon, 1);
		containers.put(InventoryID.WORN, worn);
	}

	@Before
	public void setUp() throws Exception
	{
		calc = new GearBonusCalc(client());
		monsters = SirePhaseStatsTest.provider();
	}

	private static Loadout gear(int weapon)
	{
		return Loadout.worn(null, null).with(EquipmentInventorySlot.WEAPON, weapon);
	}

	private static MagicDamage darkDemonbane()
	{
		// Dark Demonbane's 30 and the purging staff's own +10% magic damage, as CombatCalc fills them in.
		final MagicDamage m = new MagicDamage();
		m.base = 30;
		m.percent = 10;
		return m;
	}

	private void markUp(int weapon)
	{
		wield(weapon);
		calc.onGameMessage("You have placed a Mark of Darkness upon yourself.", 100);
	}

	// ---- demonbane spells -------------------------------------------------------------------------------------------

	/**
	 * The four states. Wiki (Demonbane spells, Purging staff): 20% accuracy bare;
	 * the purging staff alone doubles it to 40%; the Mark doubles it to 40% and adds
	 * 25% damage; the Mark with the staff 80% and 50%. Master gave the staff alone
	 * 20%.
	 */
	@Test
	public void demonbaneSpellsFourStates()
	{
		assertEquals(1.2, GearBonusCalc.demonbaneSpellAccuracy(false, false).getAccuracy(), DELTA);
		assertEquals(1.4, GearBonusCalc.demonbaneSpellAccuracy(false, true).getAccuracy(), DELTA);
		assertEquals(1.4, GearBonusCalc.demonbaneSpellAccuracy(true, false).getAccuracy(), DELTA);
		assertEquals(1.8, GearBonusCalc.demonbaneSpellAccuracy(true, true).getAccuracy(), DELTA);
		assertEquals(0, GearBonusCalc.markDamagePercent(false, false));
		assertEquals(0, GearBonusCalc.markDamagePercent(false, true));
		assertEquals(25, GearBonusCalc.markDamagePercent(true, false));
		assertEquals(50, GearBonusCalc.markDamagePercent(true, true));
		// The damage is the Mark's, on the roll: never a multiplier on the spell's accuracy figure.
		assertEquals(1.0, GearBonusCalc.demonbaneSpellAccuracy(true, true).getDamage(), DELTA);
	}

	@Test
	public void withoutTheMarkThePurgingStaffAddsNoDamage()
	{
		final MagicDamage m = darkDemonbane();
		calc.fillMagic(m, gear(ItemID.PURGING_STAFF), monsters.get(SIRE), SIRE, Spell.DARK_DEMONBANE, false);
		assertEquals(0, m.markPercent);
		assertEquals(33, m.maxHit());
	}

	@Test
	public void theMarkWithoutThePurgingStaffAddsAQuarter()
	{
		markUp(ItemID.KODAI_WAND);
		final MagicDamage m = darkDemonbane();
		calc.fillMagic(m, gear(ItemID.KODAI_WAND), monsters.get(SIRE), SIRE, Spell.DARK_DEMONBANE, false);
		assertEquals(25, m.markPercent);
		assertEquals(33 + 8, m.maxHit());
	}

	@Test
	public void theMarkWithThePurgingStaffAddsHalf()
	{
		markUp(ItemID.PURGING_STAFF);
		final MagicDamage m = darkDemonbane();
		final List<String> why = calc.fillMagic(m, gear(ItemID.PURGING_STAFF), monsters.get(SIRE), SIRE,
			Spell.DARK_DEMONBANE, false);
		assertEquals(50, m.markPercent);
		assertEquals(49, m.maxHit());
		assertTrue(why.toString(), why.toString().contains("demonbane: mark UP, purging staff, accuracy +80%"));
	}

	/**
	 * The brief's two: Duke Sucellus at 70% and a Void Flare at 200%, on a Mark +
	 * purging staff Dark Demonbane. Accuracy 1 + 0.8 x 0.7 = 1.56 and 1 + 0.8 x 2 =
	 * 2.6; the max 33 + floor(16 x 0.7) = 44 and 33 + 32 = 65.
	 */
	@Test
	public void dukeAndTheVoidFlareScaleTheMark()
	{
		markUp(ItemID.PURGING_STAFF);
		final MagicDamage duke = darkDemonbane();
		calc.fillMagic(duke, gear(ItemID.PURGING_STAFF), monsters.get(DUKE), DUKE, Spell.DARK_DEMONBANE, false);
		assertEquals(70, duke.effectivenessPercent);
		assertEquals(44, duke.maxHit());
		final MagicDamage flare = darkDemonbane();
		calc.fillMagic(flare, gear(ItemID.PURGING_STAFF), monsters.get(VOID_FLARE), VOID_FLARE, Spell.DARK_DEMONBANE,
			false);
		assertEquals(200, flare.effectivenessPercent);
		assertEquals(65, flare.maxHit());

		final GearBonus raw = GearBonusCalc.demonbaneSpellAccuracy(true, true);
		assertEquals(1.56, GearBonusCalc.scaleByDemonbaneEffectiveness(raw, 70).getAccuracy(), DELTA);
		assertEquals(2.6, GearBonusCalc.scaleByDemonbaneEffectiveness(raw, 200).getAccuracy(), DELTA);
	}

	/**
	 * T-135: Yama with the Mark, as Eric cast on 2026-10-31 (pvm-debug-20261031-140202): Dark Demonbane from the purging
	 * staff, scored at max 39 with the Mark unseen ([7588]), against hits of 60, 58 and a max-hit splat of 61 ([7591]-
	 * [7606]). With the Mark at Yama's 120%: +50% x 1.2 = +60% damage.
	 */
	@Test
	public void yamaWithTheMark()
	{
		final int yama = net.runelite.api.gameval.NpcID.YAMA;
		wield(ItemID.PURGING_STAFF);
		final MagicDamage bare = darkDemonbane();
		bare.percent = 30;
		calc.fillMagic(bare, gear(ItemID.PURGING_STAFF), monsters.get(yama), yama, Spell.DARK_DEMONBANE, false);
		assertEquals(39, bare.maxHit());
		markUp(ItemID.PURGING_STAFF);
		final MagicDamage marked = darkDemonbane();
		marked.percent = 30;
		calc.fillMagic(marked, gear(ItemID.PURGING_STAFF), monsters.get(yama), yama, Spell.DARK_DEMONBANE, false);
		assertEquals(120, marked.effectivenessPercent);
		assertEquals(50, marked.markPercent);
		// The 61 at [7606] is the max-hit splat (type 43): the Mark's max to the point.
		assertEquals(61, marked.maxHit());
	}

	/**
	 * T-135: a Void Flare with the Mark, at Eric's figures: max 39 bare ([7943]: "max 39, acc 0.870"), and his first hit
	 * on each phase 1-2 flare a max-hit splat of 77 ([8095], [8240], [8248], [9797]), the DPS calc's guaranteed max
	 * with the Mark: 39 + trunc(trunc(39 x 50%) x 200%) = 77.
	 */
	@Test
	public void aVoidFlareWithTheMark()
	{
		wield(ItemID.PURGING_STAFF);
		final MagicDamage bare = darkDemonbane();
		bare.percent = 30;
		calc.fillMagic(bare, gear(ItemID.PURGING_STAFF), monsters.get(VOID_FLARE), VOID_FLARE, Spell.DARK_DEMONBANE, false);
		assertEquals(39, bare.maxHit());
		markUp(ItemID.PURGING_STAFF);
		final MagicDamage marked = darkDemonbane();
		marked.percent = 30;
		calc.fillMagic(marked, gear(ItemID.PURGING_STAFF), monsters.get(VOID_FLARE), VOID_FLARE, Spell.DARK_DEMONBANE,
			false);
		System.out.println("T-135 flare max with the Mark: " + marked.maxHit());
		assertEquals(77, marked.maxHit());
	}

	/** T-135: the Mark read from its cast when no message said so; nothing while it is up already. */
	@Test
	public void theMarkFromItsCast()
	{
		wield(ItemID.PURGING_STAFF);
		assertFalse(calc.hasMarkOfDarkness());
		final String line = calc.markFromCast(7572, 7573);
		assertNotNull(line);
		assertTrue(line, line.startsWith("MARK up: cast on tick 7572"));
		assertTrue(line, line.contains("from the +70 Magic cast on tick 7572 with no thrall"));
		assertTrue(calc.hasMarkOfDarkness());
		assertNull(calc.markFromCast(7600, 7601));
	}

	/** Not a demon, no demonbane: the Mark changes nothing. */
	@Test
	public void theMarkDoesNothingOffDemons()
	{
		markUp(ItemID.PURGING_STAFF);
		final MagicDamage m = darkDemonbane();
		calc.fillMagic(m, gear(ItemID.PURGING_STAFF), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.DARK_DEMONBANE,
			false);
		assertEquals(0, m.markPercent);
	}

	/** The wiki's whole table (Demon (attribute), "List of monsters"), by id and by name. */
	@Test
	public void demonbaneEffectivenessTable()
	{
		assertEquals(25, GearBonusCalc.demonbaneEffectiveness(16665, null));
		assertEquals(70, GearBonusCalc.demonbaneEffectiveness(DUKE, null));
		assertEquals(70, GearBonusCalc.demonbaneEffectiveness(12195, null));
		assertEquals(115, GearBonusCalc.demonbaneEffectiveness(7584, null));
		assertEquals(80, GearBonusCalc.demonbaneEffectiveness(16674, null));
		assertEquals(80, GearBonusCalc.demonbaneEffectiveness(16684, null));
		assertEquals(120, GearBonusCalc.demonbaneEffectiveness(14176, null));
		assertEquals(200, GearBonusCalc.demonbaneEffectiveness(VOID_FLARE, null));
		// A form the ids miss still matches by the monster data's name, whatever its case.
		assertEquals(80, GearBonusCalc.demonbaneEffectiveness(-1, "Ocellus"));
		assertEquals(25, GearBonusCalc.demonbaneEffectiveness(-1, "Dreadnoth the Jellied"));
		assertEquals(200, GearBonusCalc.demonbaneEffectiveness(-1, "void flare"));
		assertEquals(115, GearBonusCalc.demonbaneEffectiveness(-1, "Ice demon"));
		assertEquals(100, GearBonusCalc.demonbaneEffectiveness(SIRE, "Abyssal Sire"));
		assertEquals(100, GearBonusCalc.demonbaneEffectiveness(-1, null));
	}

	// ---- demonbane weapons ------------------------------------------------------------------------------------------

	/** Arclight is +70% only while charged; spent, it is Arclight (inactive), which works as Darklight (+60% damage). */
	@Test
	public void arclightOnlyWhenCharged()
	{
		final GearBonus charged = GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.ARCLIGHT);
		assertEquals(1.7, charged.getAccuracy(), DELTA);
		assertEquals(1.7, charged.getDamage(), DELTA);
		final GearBonus spent = GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.ARCLIGHT_INACTIVE);
		assertEquals(1.0, spent.getAccuracy(), DELTA);
		assertEquals(1.6, spent.getDamage(), DELTA);
		assertEquals(1.7, GearBonusCalc.demonbaneWeapon(AttackType.STAB, ItemID.EMBERLIGHT).getDamage(), DELTA);
		assertEquals(1.6, GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.DARKLIGHT).getDamage(), DELTA);
		assertEquals(1.6, GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.SILVERLIGHT).getDamage(), DELTA);
		assertEquals(1.05, GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.BONE_CLAWS).getAccuracy(), DELTA);
		assertEquals(1.3, GearBonusCalc.demonbaneWeapon(AttackType.RANGED, ItemID.SCORCHING_BOW).getAccuracy(), DELTA);
		assertTrue(GearBonusCalc.demonbaneWeapon(AttackType.MAGIC, ItemID.ARCLIGHT).isNone());
		assertTrue(GearBonusCalc.demonbaneWeapon(AttackType.SLASH, ItemID.SCORCHING_BOW).isNone());
		// Emberlight on Duke: 49%.
		assertEquals(1.49, GearBonusCalc.scaleByDemonbaneEffectiveness(charged, 70).getDamage(), DELTA);
	}

	// ---- Mark of Darkness's state ------------------------------------------------------------------------------------

	/** Y x 3 ticks for base Magic Y, five times that with a purging staff: 2:58 and 14:51 at 99 (wiki). */
	@Test
	public void markDuration()
	{
		assertEquals(297, GearBonusCalc.markDurationTicks(99, false));
		assertEquals(1485, GearBonusCalc.markDurationTicks(99, true));
		assertEquals(297 * 0.6, 178.2, DELTA);
		assertEquals(1485 * 0.6, 891.0, DELTA);
	}

	@Test
	public void theMarkFromItsMessages()
	{
		assertFalse(calc.hasMarkOfDarkness());
		assertTrue(calc.describeBuffs(),
			calc.describeBuffs().startsWith("mark of darkness down (assumed down: not seen since the plugin started)"));
		wield(ItemID.PURGING_STAFF);
		assertEquals("MARK up: cast on tick 100 (purging staff, x5, base Magic 99), lasts 1485 ticks, to tick 1585",
			calc.onGameMessage("You have placed a Mark of Darkness upon yourself.", 100));
		assertTrue(calc.hasMarkOfDarkness());
		// A recast before it ends refreshes it (allowed since 14 May 2025).
		wield(ItemID.KODAI_WAND);
		assertEquals("MARK refreshed: cast on tick 400 (no purging staff, base Magic 99), lasts 297 ticks, to tick 697",
			calc.onGameMessage("You have placed a Mark of Darkness upon yourself.", 400));
		assertEquals("MARK ending: warning on tick 690, ends tick 700 (estimated 697)",
			calc.onGameMessage("Your Mark of Darkness is about to run out.", 690));
		assertEquals("MARK down: faded on tick 700 (expected 700)",
			calc.onGameMessage("Your Mark of Darkness has faded away.", 700));
		assertFalse(calc.hasMarkOfDarkness());
		assertNull(calc.onGameMessage("You feel a little strange.", 701));
	}

	/** A fade the client never showed: taken as expired a few ticks after its expected end. */
	@Test
	public void theMarkExpiresByItsDurationWhenTheFadeIsMissed()
	{
		wield(ItemID.KODAI_WAND);
		calc.onGameMessage("You have placed a Mark of Darkness upon yourself.", 100);
		assertTrue(calc.tickBuffs(397 + TimedBuff.GRACE_TICKS).isEmpty());
		assertTrue(calc.hasMarkOfDarkness());
		final List<String> lines = calc.tickBuffs(398 + TimedBuff.GRACE_TICKS);
		assertEquals(1, lines.size());
		assertTrue(lines.get(0), lines.get(0).startsWith("MARK down: no fade message"));
		assertFalse(calc.hasMarkOfDarkness());
	}

	/** After a login, a hop or a restart it is assumed down, and says so. */
	@Test
	public void aLoginForgetsTheMark()
	{
		wield(ItemID.KODAI_WAND);
		calc.onGameMessage("You have placed a Mark of Darkness upon yourself.", 100);
		final List<String> lines = calc.resetBuffs("logged out");
		assertEquals("[MARK down: assumed down: logged out]", lines.toString());
		assertTrue(calc.describeBuffs(), calc.describeBuffs().startsWith("mark of darkness down (assumed down: logged out)"));
		assertTrue(calc.resetBuffs("hopped").isEmpty());
	}

	// ---- the bonuses that were missing ------------------------------------------------------------------------------

	/** Charge: +10 to a god spell, only with the matching god cape; seven minutes from its message (wiki, Charge). */
	@Test
	public void chargeNeedsTheMatchingCape()
	{
		final Loadout sara = Loadout.worn(null, null).with(EquipmentInventorySlot.CAPE, ItemID.SARADOMIN_CAPE);
		final MagicDamage down = spellOf(20);
		calc.fillMagic(down, sara, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.SARADOMIN_STRIKE, false);
		assertEquals(20, down.maxHit());
		assertEquals("CHARGE up: cast on tick 5, lasts 700 ticks, to tick 705",
			calc.onGameMessage("You feel charged with magic power.", 5));
		final MagicDamage up = spellOf(20);
		calc.fillMagic(up, sara, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.SARADOMIN_STRIKE, false);
		assertEquals(30, up.maxHit());
		final MagicDamage wrongCape = spellOf(20);
		calc.fillMagic(wrongCape, sara, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FLAMES_OF_ZAMORAK, false);
		assertEquals(20, wrongCape.maxHit());
		assertTrue(GearBonusCalc.chargeCapeFits(Spell.CLAWS_OF_GUTHIX, ItemID.MA2_GUTHIX_CAPE));
		assertTrue(GearBonusCalc.chargeCapeFits(Spell.FLAMES_OF_ZAMORAK, ItemID.SKILLCAPE_MAX_ZAMORAK));
		assertFalse(GearBonusCalc.chargeCapeFits(Spell.FIRE_SURGE, ItemID.ZAMORAK_CAPE));
		assertEquals("CHARGE down: faded on tick 705 (expected 705)",
			calc.onGameMessage("Your magical charge fades away.", 705));
	}

	/** Chaos gauntlets: +3 on bolt spells, before magic damage (wiki); nothing on a blast. */
	@Test
	public void chaosGauntletsOnBoltsOnly()
	{
		final Loadout gloves = Loadout.worn(null, null).with(EquipmentInventorySlot.GLOVES, ItemID.GAUNTLETS_OF_CHAOS);
		final MagicDamage bolt = spellOf(12);
		calc.fillMagic(bolt, gloves, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_BOLT, false);
		assertEquals(15, bolt.maxHit());
		final MagicDamage blast = spellOf(16);
		calc.fillMagic(blast, gloves, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_BLAST, false);
		assertEquals(16, blast.maxHit());
	}

	/**
	 * The smoke battlestaff's 10% on standard spells joins the magic damage %
	 * (wiki, Maximum magic hit): Fire Surge 24 at +20% worn is floor(24 x 1.3) = 31.
	 * As a separate multiplier, which it was, floor(28 x 1.1) = 30.
	 */
	@Test
	public void smokeStaffIsAddedToMagicDamage()
	{
		final MagicDamage m = spellOf(24);
		m.percent = 20;
		calc.fillMagic(m, gear(ItemID.SMOKE_BATTLESTAFF), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_SURGE,
			false);
		assertEquals(30.0, m.percent, DELTA);
		assertEquals(31, m.maxHit());
		// Not on the ancients.
		final MagicDamage ancient = spellOf(30);
		calc.fillMagic(ancient, gear(ItemID.SMOKE_BATTLESTAFF), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN,
			Spell.ICE_BARRAGE, false);
		assertEquals(0.0, ancient.percent, DELTA);
	}

	/** The twinflame staff: +10% on standard spells, and its second spell on bolts, blasts and waves only. */
	@Test
	public void twinflameStaff()
	{
		final MagicDamage wave = spellOf(20);
		calc.fillMagic(wave, gear(ItemID.TWINFLAME_STAFF), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_WAVE,
			false);
		assertTrue(wave.twinflame);
		assertEquals(22, wave.maxHit());
		assertEquals(30, wave.attackMaxHit());
		final MagicDamage surge = spellOf(24);
		calc.fillMagic(surge, gear(ItemID.TWINFLAME_STAFF), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_SURGE,
			false);
		assertFalse(surge.twinflame);
		assertEquals(10.0, surge.percent, DELTA);
	}

	/** The charged tomes, on their own element's standard spells only; an empty tome does nothing. */
	@Test
	public void tomes()
	{
		assertEquals(10, GearBonusCalc.tomePercent(Spell.FIRE_SURGE, ItemID.TOME_OF_FIRE));
		assertEquals(10, GearBonusCalc.tomePercent(Spell.FIRE_BOLT, ItemID.BR_TOME_OF_FIRE));
		assertEquals(0, GearBonusCalc.tomePercent(Spell.FIRE_SURGE, ItemID.TOME_OF_FIRE_UNCHARGED));
		assertEquals(10, GearBonusCalc.tomePercent(Spell.WATER_WAVE, ItemID.TOME_OF_WATER));
		assertEquals(0, GearBonusCalc.tomePercent(Spell.WATER_WAVE, ItemID.TOME_OF_WATER_UNCHARGED));
		assertEquals(10, GearBonusCalc.tomePercent(Spell.EARTH_BLAST, ItemID.TOME_OF_EARTH));
		assertEquals(0, GearBonusCalc.tomePercent(Spell.EARTH_BLAST, ItemID.TOME_OF_EARTH_UNCHARGED));
		// Fire runes are not fire spells: Iban Blast, Flames of Zamorak and the demonbane spells get nothing.
		assertEquals(0, GearBonusCalc.tomePercent(Spell.IBAN_BLAST, ItemID.TOME_OF_FIRE));
		assertEquals(0, GearBonusCalc.tomePercent(Spell.FLAMES_OF_ZAMORAK, ItemID.TOME_OF_FIRE));
		assertEquals(0, GearBonusCalc.tomePercent(Spell.DARK_DEMONBANE, ItemID.TOME_OF_FIRE));
		assertEquals(0, GearBonusCalc.tomePercent(Spell.WATER_WAVE, ItemID.TOME_OF_FIRE));
		assertEquals(0, GearBonusCalc.tomePercent(null, ItemID.TOME_OF_FIRE));
	}

	/** Sunfire runes carried: fire spells' successful hits deal at least a tenth of the max (wiki, Sunfire rune). */
	@Test
	public void sunfireRunesInTheInventory()
	{
		final MagicDamage before = spellOf(24);
		calc.fillMagic(before, Loadout.worn(null, null), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_SURGE,
			false);
		assertFalse(before.tenthMinimum);
		containers.put(InventoryID.INV, new Item[]{new Item(ItemID.SUNFIRERUNE, 100)});
		final MagicDamage fire = spellOf(24);
		calc.fillMagic(fire, Loadout.worn(null, null), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_SURGE,
			false);
		assertTrue(fire.tenthMinimum);
		assertEquals(2, fire.minHit());
		// Not on other elements, nor on spells that only cost fire runes.
		final MagicDamage water = spellOf(22);
		calc.fillMagic(water, Loadout.worn(null, null), monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.WATER_SURGE,
			false);
		assertFalse(water.tenthMinimum);
		final MagicDamage demonbane = spellOf(30);
		calc.fillMagic(demonbane, Loadout.worn(null, null), monsters.get(SIRE), SIRE, Spell.DARK_DEMONBANE, false);
		assertFalse(demonbane.tenthMinimum);
	}

	/** The tome's Check message, matched loosely until a log shows its wording. */
	@Test
	public void searingPagesFromTheTomesMessage()
	{
		final Loadout tome = Loadout.worn(null, null).with(EquipmentInventorySlot.SHIELD, ItemID.TOME_OF_FIRE);
		final MagicDamage burnt = spellOf(24);
		calc.fillMagic(burnt, tome, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_SURGE, false);
		assertFalse(burnt.tenthMinimum);
		assertTrue(calc.onGameMessage("Your tome currently holds 800 charges from searing pages.", 1)
			.startsWith("TOME pages: searing"));
		final MagicDamage searing = spellOf(24);
		calc.fillMagic(searing, tome, monsters.get(NpcIds.GOBLIN), NpcIds.GOBLIN, Spell.FIRE_SURGE, false);
		assertTrue(searing.tenthMinimum);
		assertEquals(10, searing.tomePercent);
	}

	/** Magic Dart follows the magic level; from a slayer's staff (e) on task it is floor(Magic / 6) + 13 (wiki). */
	@Test
	public void magicDart()
	{
		assertEquals(16, Spell.MAGIC_DART.maxHitAt(60));
		assertEquals(19, Spell.MAGIC_DART.maxHitAt(99));
		assertEquals(20, Spell.MAGIC_DART.maxHitAt(100));
		assertEquals(25, Spell.enchantedSlayerStaffDart(75));
		assertEquals(29, Spell.enchantedSlayerStaffDart(99));
		assertEquals(31, Spell.enchantedSlayerStaffDart(108));
	}

	private static MagicDamage spellOf(int base)
	{
		final MagicDamage m = new MagicDamage();
		m.base = base;
		return m;
	}
}

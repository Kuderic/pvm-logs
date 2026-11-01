package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;

// Resolves the gear effects that multiply the attack roll and the max hit. Multipliers and stacking rules follow the
// LlemonDuck dps-calculator (BSD-2), (c) Paul Norton, with the newer weapons and the raid-specific rules taken from the
// wiki.
@Singleton
class GearBonusCalc
{
	private static final GearBonus SALVE_16 = GearBonus.symmetric(7.0 / 6.0);
	private static final GearBonus SALVE_15 = GearBonus.symmetric(1.15);
	private static final GearBonus SALVE_20 = GearBonus.symmetric(6.0 / 5.0);
	private static final GearBonus BLACK_MASK_MELEE = GearBonus.symmetric(7.0 / 6.0);
	private static final GearBonus BLACK_MASK_RANGED_MAGIC = GearBonus.symmetric(1.15);

	/**
	 * Demonbane effectiveness, in percent, for the demons that are not at 100%:
	 * the wiki's Demon (attribute) page, "List of monsters" (read 2026-10-27, the
	 * whole table). It scales the bonus of demonbane weapons and of Mark of
	 * Darkness alike: Emberlight's 70% is 49% on Duke Sucellus. Keyed by npc id
	 * and by the monster data's name, so a form the ids miss still matches.
	 *
	 * <p>Dreadnoth's jellies are not on the wiki's list; the DPS calculator gives
	 * them Dreadnoth's 25% (weirdgloop/osrs-dps-calc, BaseCalc, as data).
	 */
	private static final Map<Integer, Integer> EFFECTIVENESS_BY_ID = new HashMap<>();
	private static final Map<String, Integer> EFFECTIVENESS_BY_NAME = new HashMap<>();

	static
	{
		effectiveness(25, "Dreadnoth the Jellied", NpcID.TFA_JELLY_BOSS, NpcID.TFA_JELLY_MINION);
		effectiveness(70, "Duke Sucellus", NpcID.DUKE_SUCELLUS_AWAKE, NpcID.DUKE_SUCELLUS_AWAKE_QUEST,
			NpcID.DUKE_SUCELLUS_ASLEEP, NpcID.DUKE_SUCELLUS_ASLEEP_QUEST, NpcID.DUKE_SUCELLUS_INACTIVE,
			NpcID.DUKE_SUCELLUS_INACTIVE_QUEST);
		effectiveness(115, "Ice demon", NpcID.RAIDS_ICEDEMON_NONCOMBAT, NpcID.RAIDS_ICEDEMON_COMBAT);
		effectiveness(80, "Ocellus", NpcID.TFA_OCELLUS_INTRO_MET, NpcID.TFA_OCELLUS_P1, NpcID.TFA_OCELLUS_P2,
			NpcID.TFA_OCELLUS_P3);
		effectiveness(80, "Xarth", NpcID.TFA_XARTH);
		effectiveness(120, "Yama", NpcID.YAMA);
		effectiveness(200, "Void Flare", NpcID.YAMA_VOIDFLARE);
	}

	private static void effectiveness(int percent, String name, int... npcIds)
	{
		EFFECTIVENESS_BY_NAME.put(name.toLowerCase(Locale.ROOT), percent);
		for (int id : npcIds)
		{
			EFFECTIVENESS_BY_ID.put(id, percent);
		}
	}

	/** A demon's demonbane effectiveness in percent: by id, then by name, else 100. */
	static int demonbaneEffectiveness(int npcId, String name)
	{
		final Integer byId = EFFECTIVENESS_BY_ID.get(npcId);
		if (byId != null)
		{
			return byId;
		}
		final Integer byName = name == null ? null : EFFECTIVENESS_BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
		return byName == null ? 100 : byName;
	}

	private final Client client;
	// Mark of Darkness and Charge, from their chat messages and their durations. No varbit carries either: the ones
	// named for them only say whether the buff bar shows them (T-026).
	private final TimedBuff mark = new TimedBuff("MARK");
	private final TimedBuff charge = new TimedBuff("CHARGE");
	// Whether the tome of fire holds searing pages, from its Check message; null until one is seen.
	private Boolean tomeSearing;

	@Inject
	GearBonusCalc(Client client)
	{
		this.client = client;
	}

	/**
	 * The combined multipliers for this loadout against this target. A null
	 * target skips the effects that depend on what is being fought; the npc id
	 * is for the demonbane effectiveness table.
	 *
	 * <p>For magic, only the accuracy of salve, the slayer helm, the amulet of
	 * avarice, demonbane, void, the tomes, the smoke and twinflame staves and
	 * Ahrim's is in here: their damage has its own place in the magic formula,
	 * and {@link #fillMagic} puts it there (T-026). What is left in the damage
	 * figure for magic is what that formula does not place - the dragon hunter
	 * wand, the revenant sceptres, leafy targets.
	 */
	GearBonus compute(Loadout gear, AttackStyle style, MonsterStatsProvider.MonsterStats npc, int npcId, Spell spell,
		boolean onSlayerTask)
	{
		if (gear == null)
		{
			return GearBonus.NONE;
		}
		final AttackType type = style.getAttackType();
		final boolean magic = type == AttackType.MAGIC;

		GearBonus total = GearBonus.NONE;

		// Salve and the slayer helm deliberately do not stack; salve takes priority. The dragon hunter wand is the exception
		// to that: alone among the three dragonbane weapons its effect does not stack with salve at all, though it does stack
		// with the slayer helm. The wand is kept as much the larger of the two; which of them the game actually drops is not
		// documented. UNTESTED IN GAME - the case is a magic attack at Vorkath.
		final GearBonus salve = wandSuppressesSalve(type, npc, gear)
			? GearBonus.NONE
			: salveBonus(type, npc, gear);
		final GearBonus blackMask = salve.isNone() ? blackMaskBonus(type, gear, onSlayerTask) : GearBonus.NONE;
		final GearBonus dragonHunter = dragonHunterBonus(type, npc, gear);
		final GearBonus demonbane = demonbaneBonus(type, npc, npcId, spell, gear);

		if (magic)
		{
			// The magic formula places the damage of these itself (MagicDamage); only their accuracy multiplies here.
			total = total.combine(accuracyOnly(salve)).combine(accuracyOnly(blackMask)).combine(dragonHunter)
				.combine(accuracyOnly(demonbane));
			total = total.combine(accuracyOnly(voidBonus(type, gear)));
			total = total.combine(accuracyOnly(avariceBonus(npc, gear)));
			total = total.combine(accuracyOnly(tomeBonus(type, spell, gear)));
			total = total.combine(accuracyOnly(smokeStaffBonus(type, spell, gear)));
			total = total.combine(accuracyOnly(twinflameBonus(type, spell, gear)));
			total = total.combine(revenantBonus(type, gear));
			total = total.combine(leafyBonus(type, npc, spell, gear));
			return total;
		}

		total = total.combine(salve);
		// The dragon hunter crossbow and scorching bow ADD their damage to the black mask's rather than multiplying, so those
		// pairs are folded together first.
		if (!blackMask.isNone() && stacksAdditivelyWithBlackMask(gear))
		{
			total = total.combine(addDamage(blackMask, dragonHunter.combine(demonbane)));
		}
		else
		{
			total = total.combine(blackMask).combine(dragonHunter).combine(demonbane);
		}

		total = total.combine(voidBonus(type, gear));
		total = total.combine(crystalBonus(gear));
		total = total.combine(inquisitorsBonus(type, gear));
		total = total.combine(obsidianBonus(type, gear));
		total = total.combine(dharoksBonus(type, gear));
		total = total.combine(avariceBonus(npc, gear));
		total = total.combine(kerisBonus(type, npc, gear));
		total = total.combine(twistedBowBonus(type, npc, gear));
		total = total.combine(revenantBonus(type, gear));
		total = total.combine(leafyBonus(type, npc, spell, gear));
		total = total.combine(vampyreBaneBonus(type, npc, gear));
		total = total.combine(chinchompaBonus(style, gear));
		return total;
	}

	private static GearBonus accuracyOnly(GearBonus bonus)
	{
		return bonus.isNone() || bonus.getAccuracy() == 1.0 ? GearBonus.NONE : GearBonus.of(bonus.getAccuracy(), 1.0);
	}

	private GearBonus salveBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		final String amulet = gear.name(EquipmentInventorySlot.AMULET);
		if (amulet == null || !amulet.startsWith("Salve amulet"))
		{
			return GearBonus.NONE;
		}
		if (npc == null || !npc.hasAttribute("undead"))
		{
			return GearBonus.NONE;
		}
		// "(e)" and "(ei)" are enhanced; "(i)" and "(ei)" are imbued. Only imbued does anything for ranged and magic, so
		// plain enhanced is melee only.
		final boolean enhanced = amulet.contains("(e");
		final boolean imbued = amulet.contains("i)");
		if (type.isMelee())
		{
			return enhanced ? SALVE_20 : SALVE_16;
		}
		if (!imbued)
		{
			return GearBonus.NONE;
		}
		if (enhanced)
		{
			return SALVE_20;
		}
		return type == AttackType.MAGIC ? SALVE_15 : SALVE_16;
	}

	private GearBonus blackMaskBonus(AttackType type, Loadout gear, boolean onSlayerTask)
	{
		if (!onSlayerTask)
		{
			return GearBonus.NONE;
		}
		final String head = gear.name(EquipmentInventorySlot.HEAD);
		if (head == null || !(head.startsWith("Black mask") || head.toLowerCase().contains("slayer helmet")))
		{
			return GearBonus.NONE;
		}
		if (type.isMelee())
		{
			return BLACK_MASK_MELEE;
		}
		// Only the imbued versions do anything for ranged and magic.
		return head.endsWith("(i)") ? BLACK_MASK_RANGED_MAGIC : GearBonus.NONE;
	}

	/** No void set for this style. */
	static final int VOID_NONE = 0;
	/** Void knight top and robe, with the helm the style asks for. */
	static final int VOID_REGULAR = 1;
	/** The elite top and robe, which are worth more on ranged and on magic damage. */
	static final int VOID_ELITE = 2;

	/**
	 * Which void set is worn for this style, or {@link #VOID_NONE}.
	 *
	 * <p>The multipliers themselves are NOT here, and that is the point. Void
	 * scales the EFFECTIVE LEVEL - trunc(level * 11 / 10), and 9/8 for elite
	 * ranged damage - before the roll and the max hit are worked out from it.
	 * Applied to the finished figure instead, as it was, the arithmetic lands a
	 * point either side: a blowpipe read 33 where the game gives 34. See
	 * {@code CombatCalc.voidScaled}, which is where it is applied now.
	 */
	static int voidTier(AttackType type, Loadout gear)
	{
		// The body and legs first: without them nothing else needs naming.
		final String body = gear.name(EquipmentInventorySlot.BODY);
		final String legs = gear.name(EquipmentInventorySlot.LEGS);
		if (body == null || legs == null)
		{
			return VOID_NONE;
		}
		final String gloves = gear.name(EquipmentInventorySlot.GLOVES);
		final String head = gear.name(EquipmentInventorySlot.HEAD);
		if (gloves == null || head == null)
		{
			return VOID_NONE;
		}
		final boolean elite = body.startsWith("Elite void top") && legs.startsWith("Elite void robe");
		final boolean regular = body.startsWith("Void knight top") && legs.startsWith("Void knight robe");
		if ((!elite && !regular) || !gloves.startsWith("Void knight gloves"))
		{
			return VOID_NONE;
		}
		// The helm has to match the style being used for the set to do anything.
		final boolean helm = type == AttackType.MAGIC ? head.startsWith("Void mage helm")
			: type == AttackType.RANGED ? head.startsWith("Void ranger helm")
			: head.startsWith("Void melee helm");
		return !helm ? VOID_NONE : elite ? VOID_ELITE : VOID_REGULAR;
	}

	private GearBonus voidBonus(AttackType type, Loadout gear)
	{
		// Everything void does to the accuracy and to melee and ranged damage is a scaling of the effective level and lives
		// in CombatCalc now. What is left here is the elite set's 5% MAGIC damage, which is a damage bonus rather than a
		// level, and so belongs with the other multipliers.
		return type == AttackType.MAGIC && voidTier(type, gear) == VOID_ELITE
			? GearBonus.of(1.0, 1.05) : GearBonus.NONE;
	}

	/**
	 * The bows crystal armour works with (T-056): the crystal bow and every active
	 * bow of faerdhinen, by item id, so the colours count too. A name test missed
	 * them: "Bow of faerdhinen (c)" matched, the recoloured armour did not. Not
	 * the inactive bows, which cannot attack, nor the historical and imbued crystal
	 * bows (4212-4223, 11748-11758), which the wiki and the DPS calculator leave
	 * out. The Deadman and BR copies are in: they are the same bow, and Eric's
	 * Deadman-coloured armour is worn outside Deadman.
	 */
	static final Set<Integer> CRYSTAL_BOWS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.CRYSTAL_BOW, ItemID.CRYSTAL_BOW_2500,
		ItemID.BOW_OF_FAERDHINEN, ItemID.BOW_OF_FAERDHINEN_INFINITE,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL, ItemID.BOW_OF_FAERDHINEN_INFINITE_IORWERTH,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_TRAHAEARN, ItemID.BOW_OF_FAERDHINEN_INFINITE_CADARN,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_CRWYS, ItemID.BOW_OF_FAERDHINEN_INFINITE_MEILYR,
		ItemID.BOW_OF_FAERDHINEN_INFINITE_AMLODD, ItemID.BOW_OF_FAERDHINEN_INFINITE_DEADMAN,
		ItemID.BR_BOW_OF_FAERDHINEN)));

	/** Every active crystal helm: the plain one, the seven elf-clan colours, Deadman and BR. Never the inactive. */
	static final Set<Integer> CRYSTAL_HELMS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.CRYSTAL_HELMET, ItemID.CRYSTAL_HELMET_HEFIN, ItemID.CRYSTAL_HELMET_ITHELL,
		ItemID.CRYSTAL_HELMET_IORWERTH, ItemID.CRYSTAL_HELMET_TRAHAEARN, ItemID.CRYSTAL_HELMET_CADARN,
		ItemID.CRYSTAL_HELMET_CRWYS, ItemID.CRYSTAL_HELMET_AMLODD, ItemID.CRYSTAL_HELMET_DEADMAN,
		ItemID.BR_CRYSTAL_HELMET)));

	/** Every active crystal body. */
	static final Set<Integer> CRYSTAL_BODIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.CRYSTAL_CHESTPLATE, ItemID.CRYSTAL_CHESTPLATE_HEFIN, ItemID.CRYSTAL_CHESTPLATE_ITHELL,
		ItemID.CRYSTAL_CHESTPLATE_IORWERTH, ItemID.CRYSTAL_CHESTPLATE_TRAHAEARN, ItemID.CRYSTAL_CHESTPLATE_CADARN,
		ItemID.CRYSTAL_CHESTPLATE_CRWYS, ItemID.CRYSTAL_CHESTPLATE_AMLODD, ItemID.CRYSTAL_CHESTPLATE_DEADMAN,
		ItemID.BR_CRYSTAL_CHESTPLATE)));

	/** Every active pair of crystal legs. */
	static final Set<Integer> CRYSTAL_LEGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.CRYSTAL_PLATELEGS, ItemID.CRYSTAL_PLATELEGS_HEFIN, ItemID.CRYSTAL_PLATELEGS_ITHELL,
		ItemID.CRYSTAL_PLATELEGS_IORWERTH, ItemID.CRYSTAL_PLATELEGS_TRAHAEARN, ItemID.CRYSTAL_PLATELEGS_CADARN,
		ItemID.CRYSTAL_PLATELEGS_CRWYS, ItemID.CRYSTAL_PLATELEGS_AMLODD, ItemID.CRYSTAL_PLATELEGS_DEADMAN,
		ItemID.BR_CRYSTAL_PLATELEGS)));

	/**
	 * Crystal armour only does anything alongside a crystal bow or bow of
	 * faerdhinen: 5% accuracy for the helm, 15% for the body, 10% for the legs,
	 * and half of each in damage (wiki, Crystal armour). Recolouring "only
	 * changes appearance".
	 */
	static GearBonus crystalBonus(Loadout gear)
	{
		if (!CRYSTAL_BOWS.contains(gear.id(EquipmentInventorySlot.WEAPON)))
		{
			return GearBonus.NONE;
		}
		double accuracy = 0.0;
		if (CRYSTAL_HELMS.contains(gear.id(EquipmentInventorySlot.HEAD)))
		{
			accuracy += 0.05;
		}
		if (CRYSTAL_BODIES.contains(gear.id(EquipmentInventorySlot.BODY)))
		{
			accuracy += 0.15;
		}
		if (CRYSTAL_LEGS.contains(gear.id(EquipmentInventorySlot.LEGS)))
		{
			accuracy += 0.10;
		}
		// No set bonus: each piece stands alone, and damage gains half of accuracy.
		return accuracy == 0.0 ? GearBonus.NONE : GearBonus.of(1.0 + accuracy, 1.0 + accuracy / 2.0);
	}

	static GearBonus inquisitorsBonus(AttackType type, Loadout gear)
	{
		if (type != AttackType.CRUSH)
		{
			return GearBonus.NONE;
		}
		// The July 2026 update folded the old set effect into the hauberk and plateskirt, so the pieces are purely additive:
		// 0.5% for the helm, 1% each for the other two.
		double bonus = 0.0;
		// The BR copies are the same pieces (T-056).
		final int head = gear.id(EquipmentInventorySlot.HEAD);
		final int body = gear.id(EquipmentInventorySlot.BODY);
		final int legs = gear.id(EquipmentInventorySlot.LEGS);
		if (head == ItemID.INQUISITORS_HELM || head == ItemID.BR_INQUISITORS_HELM)
		{
			bonus += 0.005;
		}
		if (body == ItemID.INQUISITORS_BODY || body == ItemID.BR_INQUISITORS_BODY)
		{
			bonus += 0.01;
		}
		if (legs == ItemID.INQUISITORS_SKIRT || legs == ItemID.BR_INQUISITORS_SKIRT)
		{
			bonus += 0.01;
		}
		return bonus == 0.0 ? GearBonus.NONE : GearBonus.symmetric(1.0 + bonus);
	}

	static GearBonus obsidianBonus(AttackType type, Loadout gear)
	{
		if (!type.isMelee() || !isObsidianWeapon(gear.id(EquipmentInventorySlot.WEAPON)))
		{
			return GearBonus.NONE;
		}
		GearBonus total = GearBonus.NONE;
		if (gear.id(EquipmentInventorySlot.HEAD) == ItemID.OBSIDIAN_HELMET
			&& gear.id(EquipmentInventorySlot.BODY) == ItemID.OBSIDIAN_PLATEBODY
			&& gear.id(EquipmentInventorySlot.LEGS) == ItemID.OBSIDIAN_PLATELEGS)
		{
			total = total.combine(GearBonus.symmetric(1.1));
		}
		// The ornamented necklace (or) too (T-056).
		final int amulet = gear.id(EquipmentInventorySlot.AMULET);
		if (amulet == ItemID.JEWL_BESERKER_NECKLACE || amulet == ItemID.JEWL_BESERKER_NECKLACE_ORNAMENT)
		{
			total = total.combine(GearBonus.of(1.0, 1.2));
		}
		return total;
	}

	private static boolean isObsidianWeapon(int weapon)
	{
		return weapon == ItemID.TZHAAR_SPLITSWORD
			|| weapon == ItemID.TZHAAR_MACE
			|| weapon == ItemID.TZHAAR_MAUL
			|| weapon == ItemID.TZHAAR_MAUL_T;
	}

	/**
	 * Whether the dragon hunter wand is taking salve's place for this attack.
	 *
	 * <p>Wiki (Dragonbane weapons): the crossbow and the lance both stack with
	 * salve amulets, but "the dragon hunter wand's effect stacks multiplicatively
	 * with the Slayer helm (i) or Void Knight equipment, but not the salve amulet
	 * (i)". Multiplying them, which is what this used to do, overstates a magic
	 * attack at Vorkath by a fifth.
	 */
	private boolean wandSuppressesSalve(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		return type == AttackType.MAGIC
			&& npc != null
			&& npc.hasAttribute("dragon")
			&& gear.id(EquipmentInventorySlot.WEAPON) == ItemID.DRAGONHUNTER_WAND;
	}

	private GearBonus dragonHunterBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("dragon"))
		{
			return GearBonus.NONE;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (type.isMelee() && weapon == ItemID.DRAGONHUNTER_LANCE)
		{
			return GearBonus.symmetric(1.2);
		}
		if (type == AttackType.RANGED && isDragonHunterCrossbow(weapon))
		{
			return GearBonus.of(1.30, 1.25);
		}
		if (type == AttackType.MAGIC && weapon == ItemID.DRAGONHUNTER_WAND)
		{
			// 75% and 40%, the buffed wand. The reference carries its launch figures, 50% and 20%, which read 51 in game where
			// the wiki gives 58. The lance and the crossbow were checked at the same time and both match.
			return GearBonus.of(1.75, 1.40);
		}
		return GearBonus.NONE;
	}

	// The Deadman copy is the same bow (T-056).
	static boolean isTwistedBow(int weapon)
	{
		return weapon == ItemID.TWISTED_BOW || weapon == ItemID.DEADMAN_BLIGHTED_TWISTED_BOW;
	}

	private static boolean isPurgingStaff(int weapon)
	{
		return weapon == ItemID.PURGING_STAFF || weapon == ItemID.BR_PURGING_STAFF;
	}

	private static boolean isDragonHunterCrossbow(int weapon)
	{
		return weapon == ItemID.DRAGONHUNTER_XBOW
			|| weapon == ItemID.DRAGONHUNTER_XBOW_KBD
			|| weapon == ItemID.DRAGONHUNTER_XBOW_VORKATH;
	}

	/** Whether the equipped weapon is one that adds to the black mask instead of multiplying. */
	private boolean stacksAdditivelyWithBlackMask(Loadout gear)
	{
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		return isDragonHunterCrossbow(weapon) || weapon == ItemID.SCORCHING_BOW;
	}

	/** Sums two damage bonuses rather than multiplying them; accuracy still multiplies. */
	private static GearBonus addDamage(GearBonus a, GearBonus b)
	{
		if (b.isNone())
		{
			return a;
		}
		return GearBonus.of(
			a.getAccuracy() * b.getAccuracy(),
			1.0 + (a.getDamage() - 1.0) + (b.getDamage() - 1.0));
	}

	/**
	 * Dharok's set effect: the lower the wearer's hitpoints the harder they hit.
	 * Needs the full set, greataxe included.
	 */
	private GearBonus dharoksBonus(AttackType type, Loadout gear)
	{
		if (!type.isMelee())
		{
			return GearBonus.NONE;
		}
		final boolean set = startsWith(gear.name(EquipmentInventorySlot.HEAD), "Dharok's helm")
			&& startsWith(gear.name(EquipmentInventorySlot.BODY), "Dharok's platebody")
			&& startsWith(gear.name(EquipmentInventorySlot.LEGS), "Dharok's platelegs")
			&& startsWith(gear.name(EquipmentInventorySlot.WEAPON), "Dharok's greataxe");
		if (!set)
		{
			return GearBonus.NONE;
		}
		final int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
		final int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		final int missing = Math.max(0, maxHp - currentHp);
		return GearBonus.of(1.0, 1.0 + (missing / 100.0) * (maxHp / 100.0));
	}

	/** Amulet of avarice: 20% against revenants, which carry no attribute tag. */
	private GearBonus avariceBonus(MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.getName().startsWith("Revenant"))
		{
			return GearBonus.NONE;
		}
		return "Amulet of avarice".equals(gear.name(EquipmentInventorySlot.AMULET))
			? GearBonus.symmetric(1.2) : GearBonus.NONE;
	}

	/**
	 * Demonbane weapons and spells, scaled by the target's demonbane
	 * effectiveness: Duke Sucellus resists at 70% while Yama and the ice demon
	 * take more than the full amount.
	 */
	private GearBonus demonbaneBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, int npcId, Spell spell,
		Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("demon"))
		{
			return GearBonus.NONE;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		final GearBonus raw = type == AttackType.MAGIC
			? (spell != null && spell.isDemonbane() ? demonbaneSpellAccuracy(mark.isUp(), isPurgingStaff(weapon))
				: GearBonus.NONE)
			: demonbaneWeapon(type, weapon);
		return scaleByDemonbaneEffectiveness(raw, demonbaneEffectiveness(npcId, npc.getName()));
	}

	/**
	 * A demonbane weapon's own bonus against a demon, before effectiveness. Wiki,
	 * Demon (attribute) and the weapons' pages (read 2026-10-27): Arclight and
	 * Emberlight +70% accuracy and damage, Arclight "only when charged" - spent, it
	 * becomes Arclight (inactive), which "functions identically to Darklight";
	 * Silverlight and Darklight +60% damage; the scorching bow +30%; burning claws
	 * +5%. By item id, so the charged and spent Arclight are told apart.
	 */
	static GearBonus demonbaneWeapon(AttackType type, int weapon)
	{
		if (type.isMelee())
		{
			switch (weapon)
			{
				case ItemID.ARCLIGHT:
				case ItemID.EMBERLIGHT:
					return GearBonus.symmetric(1.7);
				case ItemID.ARCLIGHT_INACTIVE:
				case ItemID.DARKLIGHT:
				case ItemID.SILVERLIGHT:
				// The black-dyed Silverlight from Shadow of the Storm: the same sword (T-056).
				case ItemID.AGRITH_SILVERLIGHT_DYED:
					return GearBonus.of(1.0, 1.6);
				case ItemID.BONE_CLAWS:
				case ItemID.BR_BONE_CLAWS:
					return GearBonus.symmetric(1.05);
				default:
					return GearBonus.NONE;
			}
		}
		return type == AttackType.RANGED && weapon == ItemID.SCORCHING_BOW ? GearBonus.symmetric(1.3) : GearBonus.NONE;
	}

	/**
	 * A demonbane spell's accuracy against a demon, before effectiveness. Wiki,
	 * Demonbane spells and Purging staff (read 2026-10-27): 20% by default; the
	 * purging staff "doubles the base accuracy bonus of demonbane spells from 20%
	 * to 40%"; Mark of Darkness doubles it to 40%, and with the purging staff to
	 * 80%. Master gave the staff without the Mark 20%. The damage is the Mark's
	 * alone and is applied to the rolled hit: {@link #markDamagePercent}.
	 */
	static GearBonus demonbaneSpellAccuracy(boolean markUp, boolean purgingStaff)
	{
		final double bonus = (markUp ? 0.40 : 0.20) * (purgingStaff ? 2 : 1);
		return GearBonus.of(1.0 + bonus, 1.0);
	}

	/**
	 * What Mark of Darkness adds to a demonbane spell's rolled hit, in percent:
	 * 25, or 50 with a purging staff, and nothing without the Mark (wiki, Mark of
	 * Darkness; "calculated after the player's own magic damage bonuses").
	 */
	static int markDamagePercent(boolean markUp, boolean purgingStaff)
	{
		return !markUp ? 0 : purgingStaff ? 50 : 25;
	}

	/** Scales only the bonus part, so a 70% bonus at 70% effectiveness becomes 49%. */
	static GearBonus scaleByDemonbaneEffectiveness(GearBonus raw, int effectivenessPercent)
	{
		if (raw.isNone() || effectivenessPercent == 100)
		{
			return raw;
		}
		final double effectiveness = effectivenessPercent / 100.0;
		return GearBonus.of(
			1.0 + (raw.getAccuracy() - 1.0) * effectiveness,
			1.0 + (raw.getDamage() - 1.0) * effectiveness);
	}

	// The keris family deals 33% more to kalphites and scabarites, with a 1/51 chance of tripling it. The triple is
	// reachable, so it counts towards the max hit, but averages out to a few percent.
	private GearBonus kerisBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("kalphite") || !type.isMelee())
		{
			return GearBonus.NONE;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (!isKeris(weapon))
		{
			return GearBonus.NONE;
		}
		final double damage = weapon == ItemID.KERIS_PARTISAN_AMASCUT ? 1.15 : 1.33;
		final double accuracy = weapon == ItemID.KERIS_PARTISAN_BREACH ? 1.33 : 1.0;
		// The crit triples whatever the passive already potted.
		return GearBonus.split(accuracy, damage * 3.0, damage * (1.0 + 2.0 / 51.0));
	}

	private static boolean isKeris(int weapon)
	{
		return weapon == ItemID.CONTACT_KERIS
			|| weapon == ItemID.CONTACT_KERIS_P
			|| weapon == ItemID.CONTACT_KERIS_P_
			|| weapon == ItemID.CONTACT_KERIS_P__
			|| weapon == ItemID.KERIS_PARTISAN
			|| weapon == ItemID.KERIS_PARTISAN_BREACH
			|| weapon == ItemID.KERIS_PARTISAN_CORRUPTION
			|| weapon == ItemID.KERIS_PARTISAN_SUN
			|| weapon == ItemID.KERIS_PARTISAN_AMASCUT;
	}

	// Osmumten's fang has no entry here on purpose: both of its effects need arithmetic a multiplier cannot express, and
	// both live in CombatCalc.

	/**
	 * The twisted bow scales off the target's magic and turns into a penalty
	 * against low-magic targets. Magic is capped at 250, or 350 inside the
	 * Chambers; accuracy caps at 140% and damage at 250%.
	 */
	private GearBonus twistedBowBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || type != AttackType.RANGED
			|| !isTwistedBow(gear.id(EquipmentInventorySlot.WEAPON)))
		{
			return GearBonus.NONE;
		}
		final int cap = inChambersOfXeric() ? 350 : 250;
		final int magic = Math.min(cap, Math.max(npc.getMagicLevel(), npc.getOffensiveMagic()));
		return GearBonus.of(
			Math.min(1.40, twistedBowFactor(magic, true)),
			Math.min(2.50, twistedBowFactor(magic, false)));
	}

	/** Both curves have the same shape with different constants. */
	private static double twistedBowFactor(int magic, boolean accuracy)
	{
		final double base = accuracy ? 140.0 : 250.0;
		final double sub = accuracy ? 10.0 : 14.0;
		final double linear = (3.0 * magic - sub) / 100.0;
		final double quadratic = Math.pow((3.0 * magic) / 10.0 - (10.0 * sub), 2.0) / 100.0;
		return (base + linear - quadratic) / 100.0;
	}

	/**
	 * The charged tomes, on their own element's standard spells. Wiki (Tome of
	 * Fire, Tome of Water, Tome of Earth, read 2026-10-27): +10% damage against
	 * NPCs for fire and earth, +10% accuracy and damage for water, "stacking
	 * multiplicatively with Magic damage bonuses"; only the standard spellbook's
	 * elemental spells, so not Iban Blast, Flames of Zamorak or the demonbane spells.
	 * An empty tome (its own item id) does nothing. The damage goes in the magic
	 * formula's tome step ({@link #tomePercent}); this is the accuracy.
	 */
	private GearBonus tomeBonus(AttackType type, Spell spell, Loadout gear)
	{
		if (type != AttackType.MAGIC || tomePercent(spell, gear.id(EquipmentInventorySlot.SHIELD)) == 0)
		{
			return GearBonus.NONE;
		}
		return "water".equals(spell.element()) ? GearBonus.symmetric(1.1) : GearBonus.of(1.0, 1.1);
	}

	/** A charged tome's damage on this spell, in percent: 10 on its own element, else 0. */
	static int tomePercent(Spell spell, int offHand)
	{
		final String element = spell == null ? null : spell.element();
		if (element == null)
		{
			return 0;
		}
		switch (offHand)
		{
			case ItemID.TOME_OF_FIRE:
			case ItemID.BR_TOME_OF_FIRE:
				return "fire".equals(element) ? 10 : 0;
			case ItemID.TOME_OF_WATER:
				return "water".equals(element) ? 10 : 0;
			case ItemID.TOME_OF_EARTH:
				return "earth".equals(element) ? 10 : 0;
			default:
				return 0;
		}
	}

	private static final Set<Integer> SMOKE_STAVES = new HashSet<>(Arrays.asList(
		ItemID.SMOKE_BATTLESTAFF, ItemID.MYSTIC_SMOKE_BATTLESTAFF));

	/**
	 * The smoke battlestaff and mystic smoke staff: "a 10% increase in accuracy
	 * and damage when casting spells from the standard spellbook" (wiki). The
	 * damage is added to the magic damage % ({@link #fillMagic}); this is the
	 * accuracy.
	 */
	private GearBonus smokeStaffBonus(AttackType type, Spell spell, Loadout gear)
	{
		return type == AttackType.MAGIC && spell != null && spell.getSpellbook() == Spellbook.STANDARD
			&& SMOKE_STAVES.contains(gear.id(EquipmentInventorySlot.WEAPON)) ? GearBonus.symmetric(1.1) : GearBonus.NONE;
	}

	/**
	 * The twinflame staff: "a 10% increase in accuracy and damage when casting
	 * spells from the standard spellbook" (wiki), added to the magic damage % as
	 * the smoke staff's is (wiki, Maximum magic hit). Its second spell is
	 * {@link MagicDamage#twinflame}.
	 */
	private GearBonus twinflameBonus(AttackType type, Spell spell, Loadout gear)
	{
		return type == AttackType.MAGIC && spell != null && spell.getSpellbook() == Spellbook.STANDARD
			&& gear.id(EquipmentInventorySlot.WEAPON) == ItemID.TWINFLAME_STAFF ? GearBonus.symmetric(1.1) : GearBonus.NONE;
	}

	// The revenant weapons and their upgrades, which add 50% accuracy and damage against any NPC in the Wilderness and
	// nothing outside it.
	private GearBonus revenantBonus(AttackType type, Loadout gear)
	{
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (weapon == null)
		{
			return GearBonus.NONE;
		}
		// The sceptre can bash and the mace and bow can manual cast, so the style has to match the weapon for the passive to
		// apply.
		final boolean matches;
		switch (type)
		{
			case MAGIC:
				matches = weapon.startsWith("Thammaron's sceptre") || weapon.startsWith("Accursed sceptre");
				break;
			case RANGED:
				matches = weapon.startsWith("Craw's bow") || weapon.startsWith("Webweaver bow");
				break;
			default:
				matches = weapon.startsWith("Viggora's chainmace") || weapon.startsWith("Ursine chainmace");
				break;
		}
		return matches && inWilderness() ? GearBonus.symmetric(1.5) : GearBonus.NONE;
	}

	/**
	 * Leafy monsters are immune to everything but leaf-bladed weapons, broad
	 * ammo and magic dart, so anything else zeroes the damage. Only the
	 * leaf-bladed battleaxe carries a bonus on top.
	 */
	private GearBonus leafyBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Spell spell, Loadout gear)
	{
		if (npc == null || !npc.hasAttribute("leafy"))
		{
			return GearBonus.NONE;
		}
		switch (type)
		{
			case MAGIC:
				return spell == Spell.MAGIC_DART ? GearBonus.NONE : GearBonus.symmetric(0.0);
			case RANGED:
				// "Broad bolts", "Amethyst broad bolts", "Broad arrows".
				final String ammo = gear.name(EquipmentInventorySlot.AMMO);
				return ammo != null && ammo.toLowerCase().contains("broad")
					? GearBonus.NONE : GearBonus.symmetric(0.0);
			default:
				final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
				if (weapon == null || !weapon.startsWith("Leaf-bladed"))
				{
					return GearBonus.symmetric(0.0);
				}
				// Damage only. The wiki gives 17.5% damage and names no accuracy bonus, where this had it on both.
				return weapon.equals("Leaf-bladed battleaxe")
					? GearBonus.of(1.0, 1.175) : GearBonus.NONE;
		}
	}

	/**
	 * Vampyrebane weapons. The monster data tags vampyres by tier rather than
	 * with one "vampyre" tag, and the tier decides what can hurt them:
	 * blisterwood works on all three, the ivandis flail only up to tier 2.
	 */
	private GearBonus vampyreBaneBonus(AttackType type, MonsterStatsProvider.MonsterStats npc, Loadout gear)
	{
		if (npc == null || !type.isMelee())
		{
			return GearBonus.NONE;
		}
		final boolean tier3 = npc.hasAttribute("vampyre3");
		if (!tier3 && !npc.hasAttribute("vampyre1") && !npc.hasAttribute("vampyre2"))
		{
			return GearBonus.NONE;
		}
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (weapon == null)
		{
			return GearBonus.NONE;
		}
		if (weapon.startsWith("Blisterwood flail"))
		{
			return GearBonus.of(1.05, 1.25);
		}
		if (weapon.startsWith("Blisterwood sickle"))
		{
			return GearBonus.of(1.05, 1.15);
		}
		if (weapon.startsWith("Ivandis flail"))
		{
			return tier3 ? GearBonus.NONE : GearBonus.of(1.0, 1.20);
		}
		return GearBonus.NONE;
	}

	/**
	 * Full ahrim's plus the amulet of the damned gives a 25% chance of 30% extra
	 * damage, not a flat 30% as the reference models. Since October 2024 it
	 * applies to manual casts too, so the style is not checked beyond magic. Its
	 * place is after the roll ({@link MagicDamage#ahrims}).
	 */
	private static boolean ahrimsSet(Loadout gear)
	{
		// The amulet first: it is the rarest piece, so most loadouts are settled without naming the rest.
		return startsWith(gear.name(EquipmentInventorySlot.AMULET), "Amulet of the damned")
			&& startsWith(gear.name(EquipmentInventorySlot.WEAPON), "Ahrim's staff")
			&& startsWith(gear.name(EquipmentInventorySlot.HEAD), "Ahrim's hood")
			&& startsWith(gear.name(EquipmentInventorySlot.BODY), "Ahrim's robetop")
			&& startsWith(gear.name(EquipmentInventorySlot.LEGS), "Ahrim's robeskirt");
	}

	/**
	 * Chinchompa accuracy depends on distance: each fuse length is at its best
	 * in one band and worse in the others.
	 */
	private GearBonus chinchompaBonus(AttackStyle style, Loadout gear)
	{
		// "Chinchompa", "Red chinchompa", "Black chinchompa".
		final String weapon = gear.name(EquipmentInventorySlot.WEAPON);
		if (style.getAttackType() != AttackType.RANGED || weapon == null
			|| !weapon.toLowerCase().contains("chinchompa"))
		{
			return GearBonus.NONE;
		}
		final int distance = targetDistance();
		if (distance < 0)
		{
			return GearBonus.NONE;
		}
		// Short band is 1-3 tiles, medium 4-6, long beyond that.
		final int band = distance <= 3 ? 0 : distance <= 6 ? 1 : 2;
		final int fuse = style.getCombatStyle() == CombatStyle.ACCURATE ? 0
			: style.getCombatStyle() == CombatStyle.RAPID ? 1 : 2;
		final double[][] accuracy = {
			{1.0, 0.75, 0.5},
			{0.75, 1.0, 0.75},
			{0.5, 0.75, 1.0},
		};
		return GearBonus.of(accuracy[band][fuse], 1.0);
	}

	/** Tiles between the player and whatever they are attacking, or -1 if nothing. */
	private int targetDistance()
	{
		final Player player = client.getLocalPlayer();
		if (player == null)
		{
			return -1;
		}
		final Actor target = player.getInteracting();
		if (target == null)
		{
			return -1;
		}
		return player.getWorldLocation().distanceTo(target.getWorldLocation());
	}

	/**
	 * Extra magic damage from Virtus casting Ancient Magicks: 3% per piece on
	 * top of the 2% each carries as a plain stat, so a full set is 15% not 6%.
	 */
	double virtusAncientDamagePercent(Loadout gear, Spell spell)
	{
		if (spell == null || spell.getSpellbook() != Spellbook.ANCIENT || gear == null)
		{
			return 0.0;
		}
		double percent = 0.0;
		if (isVirtus(gear.id(EquipmentInventorySlot.HEAD), ItemID.VIRTUS_MASK, ItemID.VIRTUS_MASK_ORNAMENT))
		{
			percent += 3.0;
		}
		if (isVirtus(gear.id(EquipmentInventorySlot.BODY), ItemID.VIRTUS_TOP, ItemID.VIRTUS_TOP_ORNAMENT))
		{
			percent += 3.0;
		}
		if (isVirtus(gear.id(EquipmentInventorySlot.LEGS), ItemID.VIRTUS_LEGS, ItemID.VIRTUS_LEGS_ORNAMENT))
		{
			percent += 3.0;
		}
		return percent;
	}

	private static boolean isVirtus(int worn, int plain, int ornament)
	{
		return worn == plain || worn == ornament;
	}

	/**
	 * Tumeken's Shadow multiplies the magic accuracy and damage of the rest of
	 * the loadout, by 3 normally and 4 inside the Tombs. 1 when not equipped.
	 */
	int shadowMultiplier(Loadout gear)
	{
		if (gear == null)
		{
			return 1;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		if (weapon != ItemID.TUMEKENS_SHADOW && weapon != ItemID.DEADMAN_BLIGHTED_TUMEKENS_SHADOW)
		{
			return 1;
		}
		return inTombsOfAmascut() ? 4 : 3;
	}

	boolean inChambersOfXeric()
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
	}

	/**
	 * Whether the player is in a Tombs of Amascut raid, from the party status
	 * varbit rather than the raid level: the level is never cleared on leaving,
	 * so reading it scaled every NPC in the game once a raid had been done. This
	 * also reads set in the lobby, which is harmless.
	 */

	// ---- magic (T-026) ---------------------------------------------------------------------------------------------

	private static final Set<Integer> SARADOMIN_CAPES = new HashSet<>(Arrays.asList(ItemID.SARADOMIN_CAPE,
		ItemID.MA2_SARADOMIN_CAPE, ItemID.BR_MA2_SARADOMIN_CAPE, ItemID.SKILLCAPE_MAX_SARADOMIN,
		ItemID.SKILLCAPE_MAX_SARADOMIN2, ItemID.SKILLCAPE_MAX_SARADOMIN2_TROUVER));
	private static final Set<Integer> GUTHIX_CAPES = new HashSet<>(Arrays.asList(ItemID.GUTHIX_CAPE,
		ItemID.MA2_GUTHIX_CAPE, ItemID.BR_MA2_GUTHIX_CAPE, ItemID.SKILLCAPE_MAX_GUTHIX, ItemID.SKILLCAPE_MAX_GUTHIX2,
		ItemID.SKILLCAPE_MAX_GUTHIX2_TROUVER));
	private static final Set<Integer> ZAMORAK_CAPES = new HashSet<>(Arrays.asList(ItemID.ZAMORAK_CAPE,
		ItemID.MA2_ZAMORAK_CAPE, ItemID.BR_MA2_ZAMORAK_CAPE, ItemID.SKILLCAPE_MAX_ZAMORAK,
		ItemID.SKILLCAPE_MAX_ZAMORAK2, ItemID.SKILLCAPE_MAX_ZAMORAK2_TROUVER));

	/**
	 * Whether this cape lets Charge empower this god spell: "The respective god
	 * cape must be worn" (wiki, Charge) - the cape, its imbued version or the god
	 * max cape.
	 */
	static boolean chargeCapeFits(Spell spell, int cape)
	{
		if (spell == Spell.SARADOMIN_STRIKE)
		{
			return SARADOMIN_CAPES.contains(cape);
		}
		if (spell == Spell.CLAWS_OF_GUTHIX)
		{
			return GUTHIX_CAPES.contains(cape);
		}
		return spell == Spell.FLAMES_OF_ZAMORAK && ZAMORAK_CAPES.contains(cape);
	}

	/**
	 * What the gear, the buffs and the target put into a magic attack's damage,
	 * each in its place in the wiki's formula ({@link MagicDamage}), with a reason
	 * for each one that applied, for the debug log. The caller has filled in the
	 * base, the worn magic damage, virtus, the prayer and elemental weakness.
	 *
	 * @param spell the spell being cast, or null for a powered staff's own attack
	 */
	List<String> fillMagic(MagicDamage m, Loadout gear, MonsterStatsProvider.MonsterStats npc, int npcId, Spell spell,
		boolean onSlayerTask)
	{
		final List<String> why = new ArrayList<>();
		if (gear == null)
		{
			return why;
		}
		final int weapon = gear.id(EquipmentInventorySlot.WEAPON);
		final boolean standard = spell != null && spell.getSpellbook() == Spellbook.STANDARD;

		if (spell != null && spell.isBolt() && gear.id(EquipmentInventorySlot.GLOVES) == ItemID.GAUNTLETS_OF_CHAOS)
		{
			m.flat += 3;
			why.add("chaos gauntlets +3 (bolt spell)");
		}
		final String element = spell == null ? null : spell.element();
		if (element != null && elementalAmuletFits(gear.id(EquipmentInventorySlot.AMULET), element))
		{
			// "added before magic damage and elemental weakness modifiers are applied" (wiki, Amulet of fire), as the chaos
			// gauntlets' +3 is (T-087: Eric's Maggot King kills, whose Twinflame first hits reached 48 against the 45 the
			// plugin had as their max).
			m.flat += 2;
			why.add(gear.id(EquipmentInventorySlot.AMULET) == ItemID.ELEMENTAL_AMULET
				? "elemental amulet +2 (" + element + " spell)" : "amulet of " + element + " +2");
		}
		if (spell != null && spell.isGodSpell())
		{
			if (!charge.isUp())
			{
				why.add("Charge down: god spell at its usual max");
			}
			else if (chargeCapeFits(spell, gear.id(EquipmentInventorySlot.CAPE)))
			{
				m.flat += 10;
				why.add("Charge +10 (up, matching god cape)");
			}
			else
			{
				why.add("Charge up but no matching god cape: no bonus");
			}
		}

		final GearBonus salve = wandSuppressesSalve(AttackType.MAGIC, npc, gear)
			? GearBonus.NONE : salveBonus(AttackType.MAGIC, npc, gear);
		// The slayer helm only counts without salve or avarice against what they work on (wiki, Maximum magic hit).
		final boolean avarice = !avariceBonus(npc, gear).isNone();
		if (!salve.isNone())
		{
			final int percent = (int) Math.round((salve.getDamage() - 1.0) * 100);
			m.percent += percent;
			why.add("salve +" + percent + "% (undead, added to magic damage)");
		}
		else if (avarice)
		{
			m.percent += 20;
			why.add("amulet of avarice +20% (revenant, added to magic damage; Forinthry surge not modelled)");
		}
		else if (!blackMaskBonus(AttackType.MAGIC, gear, onSlayerTask).isNone())
		{
			m.slayerPercent = 15;
			why.add("slayer helm (i) +15% (on task)");
		}
		if (voidTier(AttackType.MAGIC, gear) == VOID_ELITE)
		{
			m.percent += 5;
			why.add("elite void +5% (added to magic damage)");
		}
		if (standard && SMOKE_STAVES.contains(weapon))
		{
			m.percent += 10;
			why.add("smoke staff +10% (standard spellbook)");
		}
		if (standard && weapon == ItemID.TWINFLAME_STAFF)
		{
			m.percent += 10;
			why.add("twinflame staff +10% (standard spellbook)");
			if (spell.twinflameFiresTwice())
			{
				m.twinflame = true;
				why.add("twinflame second hit, 40% of the first (bolt, blast or wave)");
			}
		}

		final int offHand = gear.id(EquipmentInventorySlot.SHIELD);
		final int tome = tomePercent(spell, offHand);
		if (tome > 0)
		{
			m.tomePercent = tome;
			why.add("tome +" + tome + "% (charged, " + spell.element() + " spell)");
		}
		if (spell != null && "fire".equals(spell.element()))
		{
			final boolean searing = Boolean.TRUE.equals(tomeSearing)
				&& (offHand == ItemID.TOME_OF_FIRE || offHand == ItemID.BR_TOME_OF_FIRE);
			if (carriesSunfireRunes() || searing)
			{
				m.tenthMinimum = true;
				why.add("min hit 10% of the max before the tome (" + (searing ? "searing pages" : "sunfire runes") + ")");
			}
		}

		if (spell != null && spell.isDemonbane() && npc != null && npc.hasAttribute("demon"))
		{
			final boolean purging = isPurgingStaff(weapon);
			m.markPercent = markDamagePercent(mark.isUp(), purging);
			m.effectivenessPercent = demonbaneEffectiveness(npcId, npc.getName());
			final GearBonus accuracy = scaleByDemonbaneEffectiveness(demonbaneSpellAccuracy(mark.isUp(), purging),
				m.effectivenessPercent);
			why.add(String.format("demonbane: mark %s, %s, accuracy %+.0f%%, damage %+d%% on the roll%s",
				mark.isUp() ? "UP" : "down", purging ? "purging staff" : "no purging staff",
				(accuracy.getAccuracy() - 1.0) * 100, m.markPercent * m.effectivenessPercent / 100,
				m.effectivenessPercent == 100 ? "" : " (" + m.effectivenessPercent + "% effective)"));
		}
		if (spell == Spell.MAGIC_DART && weapon == ItemID.SLAYER_STAFF_ENCHANTED)
		{
			why.add(onSlayerTask ? "slayer's staff (e) on task: Magic Dart max floor(Magic / 6) + 13"
				: "slayer's staff (e) off task: the ordinary Magic Dart");
		}
		if (ahrimsSet(gear))
		{
			m.ahrims = true;
			why.add("ahrim's + damned: 25% chance of +30%");
		}
		return why;
	}

	/**
	 * Whether this amulet adds +2 to a spell of this element (T-087; OSRS Wiki,
	 * Amulet of fire and Elemental amulet, read 2026-10-29: each of the four adds
	 * "+2 max hit" to its element's spells; the elemental amulet "combines the
	 * effects of all four").
	 */
	static boolean elementalAmuletFits(int amulet, String element)
	{
		if (element == null)
		{
			return false;
		}
		switch (amulet)
		{
			case ItemID.ELEMENTAL_AMULET:
				return true;
			case ItemID.AMULET_OF_FIRE:
				return "fire".equals(element);
			case ItemID.AMULET_OF_WATER:
				return "water".equals(element);
			case ItemID.AMULET_OF_EARTH:
				return "earth".equals(element);
			case ItemID.AMULET_OF_AIR:
				return "air".equals(element);
			default:
				return false;
		}
	}

	/**
	 * Whether sunfire runes are on hand: in the inventory, or in a rune pouch
	 * carried there. "Any successful hit with offensive fire spells will deal a
	 * minimum of 10% of the player's max hit", and they are used even with an
	 * infinite fire source equipped (wiki, Sunfire rune). The pouch is read from
	 * its varbits, mapped to items by the game's own enum as RuneLite's rune pouch
	 * overlay does; that a pouched rune counts is an inference, as it is how the
	 * game pays for any spell.
	 */
	boolean carriesSunfireRunes()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return false;
		}
		boolean pouch = false;
		for (Item item : inventory.getItems())
		{
			final int id = item == null ? -1 : item.getId();
			if (id == ItemID.SUNFIRERUNE)
			{
				return true;
			}
			pouch |= id == ItemID.BH_RUNE_POUCH || id == ItemID.BH_RUNE_POUCH_TROUVER
				|| id == ItemID.DIVINE_RUNE_POUCH || id == ItemID.DIVINE_RUNE_POUCH_TROUVER;
		}
		if (!pouch)
		{
			return false;
		}
		final EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runes == null)
		{
			return false;
		}
		for (int[] slot : POUCH_SLOTS)
		{
			final int type = client.getVarbitValue(slot[0]);
			if (type > 0 && client.getVarbitValue(slot[1]) > 0 && runes.getIntValue(type) == ItemID.SUNFIRERUNE)
			{
				return true;
			}
		}
		return false;
	}

	private static final int[][] POUCH_SLOTS = {
		{VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_QUANTITY_1},
		{VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_QUANTITY_2},
		{VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_QUANTITY_3},
		{VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_QUANTITY_4},
		{VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_QUANTITY_5},
		{VarbitID.RUNE_POUCH_TYPE_6, VarbitID.RUNE_POUCH_QUANTITY_6},
	};

	// ---- Mark of Darkness and Charge, from their messages (T-026) -------------------------------------------------

	/** Charge lasts seven minutes (wiki, Charge). */
	static final int CHARGE_TICKS = 700;
	/** The Mark's warning comes this many ticks before it ends (wiki, Mark of Darkness). */
	static final int MARK_WARNING_TICKS = 10;

	/**
	 * How long Mark of Darkness lasts: {@code Y x 3} ticks for a base Magic level
	 * of Y, five times that when cast with a purging staff equipped (wiki: 2:58 at
	 * 99, 14:51 with the staff). Switching weapons afterwards keeps the duration.
	 */
	static int markDurationTicks(int baseMagic, boolean purgingStaff)
	{
		return baseMagic * 3 * (purgingStaff ? 5 : 1);
	}

	/**
	 * A game message, read for the Mark, Charge and the tome's pages. Returns the
	 * line to log, or null when it said nothing about them. The messages are the
	 * wiki's (Mark of Darkness, Charge); the tome's Check wording is not in it and
	 * is matched loosely (any message naming searing or burnt pages and a tome),
	 * which is a guess until a log shows the real text.
	 */
	String onGameMessage(String message, int tick)
	{
		if (message == null)
		{
			return null;
		}
		if (message.startsWith("You have placed a Mark of Darkness upon yourself"))
		{
			markMessageTick = tick;
			final boolean purging = isPurgingStaff(wornId(EquipmentInventorySlot.WEAPON));
			final int baseMagic = client.getRealSkillLevel(Skill.MAGIC);
			return mark.cast(tick, markDurationTicks(baseMagic, purging),
				(purging ? "purging staff, x5" : "no purging staff") + ", base Magic " + baseMagic);
		}
		if (message.startsWith("Your Mark of Darkness is about to run out"))
		{
			return mark.warned(tick, MARK_WARNING_TICKS);
		}
		if (message.startsWith("Your Mark of Darkness has faded away"))
		{
			return mark.faded(tick);
		}
		if (message.startsWith("You feel charged with magic power"))
		{
			return charge.cast(tick, CHARGE_TICKS, "");
		}
		if (message.startsWith("Your magical charge fades away"))
		{
			return charge.faded(tick);
		}
		final String lower = message.toLowerCase(Locale.ROOT);
		if (lower.contains("tome") && (lower.contains("searing page") || lower.contains("burnt page")))
		{
			tomeSearing = lower.contains("searing page");
			return "TOME pages: " + (tomeSearing ? "searing" : "burnt") + ", from \"" + message + "\"";
		}
		return null;
	}

	/** Each tick: a buff whose fade was never shown is let go once its duration is well past. */
	List<String> tickBuffs(int tick)
	{
		final List<String> lines = new ArrayList<>(2);
		addIfNotNull(lines, mark.tick(tick));
		addIfNotNull(lines, charge.tick(tick));
		return lines;
	}

	/** A login, a hop or a restart: neither buff can be known, so both are assumed down until seen again. */
	List<String> resetBuffs(String why)
	{
		final List<String> lines = new ArrayList<>(2);
		addIfNotNull(lines, mark.reset(why));
		addIfNotNull(lines, charge.reset(why));
		return lines;
	}

	private static void addIfNotNull(List<String> lines, String line)
	{
		if (line != null)
		{
			lines.add(line);
		}
	}

	boolean hasMarkOfDarkness()
	{
		return mark.isUp();
	}

	// T-139: the tick the Mark's own message was last seen, so the fallback doesn't take the same cast again.
	private int markMessageTick = Integer.MIN_VALUE;

	/** T-139: whether a purging staff is worn now: kept with a cast on its tick, as the Mark's x5 is the cast's. */
	boolean wearsPurgingStaff()
	{
		return isPurgingStaff(wornId(EquipmentInventorySlot.WEAPON));
	}

	// T-135, T-139: a cast tick whose Magic may hold the Mark's 70, waiting a tick for a thrall to spawn: its Magic,
	// whether a thrall spawned on it, and whether a purging staff was worn on it.
	private int markCastTick = Integer.MIN_VALUE;
	private int markCastMagic;
	private boolean markCastSpawn;
	private boolean markCastPurging;

	/**
	 * T-135, T-139: the Mark of Darkness from its cast's experience when no message said so, at each tick's close.
	 * A cast tick whose Magic may hold the Mark's 70 waits a tick. At T+1's close, a thrall spawned for it on T, or on
	 * T+1 with no thrall cast of its own (a guess: a thrall spawning a tick late), and {@link XpLedger#markBeside} says
	 * whether the 70 was the Mark: a lone 70 with no thrall; a 158 that raised one, a Greater thrall and the Mark.
	 *
	 * @param castMagic the Magic this tick's casts paid ({@link XpLedger#castMagic})
	 * @param castNote this tick's cast line ({@link XpLedger#castNote}), for its own thrall cast
	 * @param thrallSpawned whether a thrall spawned on this tick
	 * @return the MARK line, or null when nothing changed
	 */
	String markFromCasts(int tick, int castMagic, String castNote, boolean thrallSpawned)
	{
		String line = null;
		if (markCastTick == tick - 1)
		{
			final boolean ownThrall = castNote != null && castNote.contains("thrall");
			final String beside = XpLedger.markBeside(markCastMagic, markCastSpawn || thrallSpawned && !ownThrall);
			if (beside != null)
			{
				line = markFromCast(markCastTick, tick, markCastPurging, markCastMagic, beside);
			}
			markCastTick = Integer.MIN_VALUE;
		}
		if (castMagic > 0 && (XpLedger.markBeside(castMagic, false) != null
			|| XpLedger.markBeside(castMagic, true) != null))
		{
			markCastTick = tick;
			markCastMagic = castMagic;
			markCastSpawn = thrallSpawned;
			markCastPurging = wearsPurgingStaff();
		}
		return line;
	}

	/**
	 * T-135: the Mark of Darkness read from its cast's experience when no message said so (see the plugin's
	 * {@code inferMark} and {@link XpLedger#markBeside}). The message is the better signal (its duration starts on
	 * its tick); this is the fallback.
	 *
	 * <p>T-139: a recast while the Mark is up refreshes it, as the message's path does (Eric's 18:00 log: the +158 at
	 * [1799] kept it up past the [734] cast's end, his staff hitting up to 60 on Yama after it). Only a cast the
	 * message already took is left alone. The x5 is the purging staff's on the cast's tick, {@code purgingOnCast}:
	 * the wiki has the staff "equipped" as the Mark is cast, and switching afterwards keeps the duration (the 18:30
	 * log: the staff at [84], the Emberlight by [85], the Mark still up at [449]).
	 *
	 * @param magic the cast's Magic, for the line
	 * @param beside what else the tick cast ("" when the Mark alone), for the line
	 */
	String markFromCast(int castTick, int tick, boolean purgingOnCast, int magic, String beside)
	{
		if (markMessageTick >= castTick && markMessageTick <= tick)
		{
			return null;
		}
		final int baseMagic = client.getRealSkillLevel(Skill.MAGIC);
		final String from = beside == null || beside.isEmpty()
			? "from the +" + magic + " Magic cast on tick " + castTick + " with no thrall"
			: "from a +" + magic + " drop on tick " + castTick + ", " + beside + " + the Mark";
		final String line = mark.cast(castTick, markDurationTicks(baseMagic, purgingOnCast),
			(purgingOnCast ? "purging staff on the cast's tick, x5" : "no purging staff on the cast's tick")
				+ ", base Magic " + baseMagic + "; " + from + ": no message seen");
		mark.tick(tick);
		return line;
	}

	/** Mark of Darkness and Charge in a few words, for the SETUP line. */
	String describeBuffs()
	{
		return "mark of darkness " + mark.describe() + "; charge " + charge.describe();
	}

	private int wornId(EquipmentInventorySlot slot)
	{
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		final Item item = worn == null ? null : worn.getItem(slot.getSlotIdx());
		return item == null ? -1 : item.getId();
	}

	boolean inTombsOfAmascut()
	{
		return client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) > 0;
	}

	/** The raid level, or 0 when the Tombs are not actually being fought. */
	int tombsRaidLevel()
	{
		return inTombsOfAmascut() ? client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) : 0;
	}

	private boolean inWilderness()
	{
		return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
	}

	private static boolean startsWith(String name, String prefix)
	{
		return name != null && name.startsWith(prefix);
	}
}

package com.pvmlogs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Every item that boosts a combat stat the player attacks with - Attack,
 * Strength, Ranged or Magic - with the boost it gives (T-015). The ideal boost
 * is the strongest of these on hand, so the player no longer picks a potion in
 * the settings.
 *
 * <p>Boosts are {@code floor(base * percent / 100) + flat}, from the base
 * (unboosted) level. The figures are RuneLite's item-stats table
 * (runelite-client {@code plugins/itemstats/ItemStatChanges}, BSD-2,
 * Copyright (c) 2016-2018 RuneLite contributors) checked against the OSRS
 * Wiki's temporary skill boost tables (2026-10-26). Left out on purpose: beers,
 * kebabs and other foods with a level or two and a drawback, spicy stew (random),
 * and the smouldering heart (the wiki gives no formula).
 */
final class BoostTable
{
	/** One item's boosts. A stat it leaves alone has percent and flat of zero. */
	static final class Boost
	{
		private final int[] percent = new int[4];
		private final int[] flat = new int[4];
		// Divine potions hold the boost for five minutes, so between two equal boosts the divine one is the better to have.
		final boolean divine;
		// The hearts share one cooldown (IMBUED_HEART_TIMER) and count only when it is off.
		final boolean heart;
		// The moonlight potion's boost depends on the Herblore level.
		final boolean moonlight;

		private Boost(boolean divine, boolean heart, boolean moonlight)
		{
			this.divine = divine;
			this.heart = heart;
			this.moonlight = moonlight;
		}

		private Boost set(Skill skill, int pct, int add)
		{
			percent[slot(skill)] = pct;
			flat[slot(skill)] = add;
			return this;
		}

		/** The boost to this stat from its base level, or 0 if the item does nothing for it. */
		int boost(Skill skill, int base, int herblore)
		{
			final int i = slot(skill);
			if (i < 0)
			{
				return 0;
			}
			if (moonlight && (skill == Skill.ATTACK || skill == Skill.STRENGTH))
			{
				// Wiki: 5 + 15% from Herblore 45 (Attack) or 55 (Strength), else 3 + 10%.
				final boolean strong = herblore >= (skill == Skill.ATTACK ? 45 : 55);
				return strong ? base * 15 / 100 + 5 : base * 10 / 100 + 3;
			}
			return percent[i] == 0 && flat[i] == 0 ? 0 : base * percent[i] / 100 + flat[i];
		}
	}

	private static int slot(Skill skill)
	{
		switch (skill)
		{
			case ATTACK:
				return 0;
			case STRENGTH:
				return 1;
			case RANGED:
				return 2;
			case MAGIC:
				return 3;
			default:
				return -1;
		}
	}

	private static final Map<Integer, Boost> TABLE;

	static
	{
		final Map<Integer, Boost> t = new HashMap<>();

		// Melee.
		add(t, plain().set(Skill.ATTACK, 10, 3), ItemID._1DOSE1ATTACK, ItemID._2DOSE1ATTACK, ItemID._3DOSE1ATTACK,
			ItemID._4DOSE1ATTACK, ItemID.BRUTAL_1DOSE1ATTACK, ItemID.BRUTAL_2DOSE1ATTACK);
		add(t, plain().set(Skill.STRENGTH, 10, 3), ItemID._1DOSE1STRENGTH, ItemID._2DOSE1STRENGTH, ItemID._3DOSE1STRENGTH,
			ItemID.STRENGTH4, ItemID.BRUTAL_1DOSE1STRENGTH, ItemID.BRUTAL_2DOSE1STRENGTH);
		add(t, plain().set(Skill.ATTACK, 10, 3).set(Skill.STRENGTH, 10, 3), ItemID._1DOSECOMBAT, ItemID._2DOSECOMBAT,
			ItemID._3DOSECOMBAT, ItemID._4DOSECOMBAT, ItemID._1DOSECOMBAT_DEADMAN_STARTER, ItemID._2DOSECOMBAT_DEADMAN_STARTER,
			ItemID._3DOSECOMBAT_DEADMAN_STARTER, ItemID._4DOSECOMBAT_DEADMAN_STARTER, ItemID.BRUTAL_1DOSECOMBAT,
			ItemID.BRUTAL_2DOSECOMBAT);
		add(t, plain().set(Skill.ATTACK, 15, 5), ItemID._1DOSE2ATTACK, ItemID._2DOSE2ATTACK, ItemID._3DOSE2ATTACK,
			ItemID._4DOSE2ATTACK, ItemID.BRUTAL_1DOSE2ATTACK, ItemID.BRUTAL_2DOSE2ATTACK);
		add(t, plain().set(Skill.STRENGTH, 15, 5), ItemID._1DOSE2STRENGTH, ItemID._2DOSE2STRENGTH, ItemID._3DOSE2STRENGTH,
			ItemID._4DOSE2STRENGTH, ItemID.BRUTAL_1DOSE2STRENGTH, ItemID.BRUTAL_2DOSE2STRENGTH);
		add(t, plain().set(Skill.ATTACK, 15, 5).set(Skill.STRENGTH, 15, 5), ItemID._1DOSE2COMBAT, ItemID._2DOSE2COMBAT,
			ItemID._3DOSE2COMBAT, ItemID._4DOSE2COMBAT, ItemID.BR_1DOSE2COMBAT, ItemID.BR_2DOSE2COMBAT, ItemID.BR_3DOSE2COMBAT,
			ItemID.BR_4DOSE2COMBAT, ItemID.VAMPYRIUM_FROTHY_POTION_1, ItemID.VAMPYRIUM_FROTHY_POTION_2,
			ItemID.VAMPYRIUM_FROTHY_POTION_3, ItemID.VAMPYRIUM_FROTHY_POTION_4);
		add(t, divine().set(Skill.ATTACK, 15, 5), ItemID._1DOSEDIVINEATTACK, ItemID._2DOSEDIVINEATTACK,
			ItemID._3DOSEDIVINEATTACK, ItemID._4DOSEDIVINEATTACK);
		add(t, divine().set(Skill.STRENGTH, 15, 5), ItemID._1DOSEDIVINESTRENGTH, ItemID._2DOSEDIVINESTRENGTH,
			ItemID._3DOSEDIVINESTRENGTH, ItemID._4DOSEDIVINESTRENGTH);
		add(t, divine().set(Skill.ATTACK, 15, 5).set(Skill.STRENGTH, 15, 5), ItemID._1DOSEDIVINECOMBAT,
			ItemID._2DOSEDIVINECOMBAT, ItemID._3DOSEDIVINECOMBAT, ItemID._4DOSEDIVINECOMBAT);
		// Zamorak brew: the strongest Attack boost outside a raid, 2 + 20%.
		add(t, plain().set(Skill.ATTACK, 20, 2).set(Skill.STRENGTH, 12, 2), ItemID._1DOSEPOTIONOFZAMORAK,
			ItemID._2DOSEPOTIONOFZAMORAK, ItemID._3DOSEPOTIONOFZAMORAK, ItemID._4DOSEPOTIONOFZAMORAK,
			ItemID.BRUTAL_1DOSEPOTIONOFZAMORAK, ItemID.BRUTAL_2DOSEPOTIONOFZAMORAK);
		add(t, plain().set(Skill.ATTACK, 15, 4), ItemID.BUTTERFLY_JAR_RUBY, ItemID.HUNTER_MIX_RUBY_1DOSE,
			ItemID.HUNTER_MIX_RUBY_2DOSE);
		add(t, plain().set(Skill.STRENGTH, 15, 4), ItemID.BUTTERFLY_JAR_WARLOCK, ItemID.HUNTER_MIX_WARLOCK_1DOSE,
			ItemID.HUNTER_MIX_WARLOCK_2DOSE);
		// Neypotzli only; Herblore decides its strength.
		add(t, new Boost(false, false, true), ItemID._1DOSEMOONLIGHTPOTION, ItemID._2DOSEMOONLIGHTPOTION,
			ItemID._3DOSEMOONLIGHTPOTION, ItemID._4DOSEMOONLIGHTPOTION);

		// Ranged.
		add(t, plain().set(Skill.RANGED, 10, 4), ItemID._1DOSERANGERSPOTION, ItemID._2DOSERANGERSPOTION,
			ItemID._3DOSERANGERSPOTION, ItemID._4DOSERANGERSPOTION, ItemID.BR_1DOSERANGERSPOTION, ItemID.BR_2DOSERANGERSPOTION,
			ItemID.BR_3DOSERANGERSPOTION, ItemID.BR_4DOSERANGERSPOTION, ItemID.BRUTAL_1DOSERANGERSPOTION,
			ItemID.BRUTAL_2DOSERANGERSPOTION, ItemID._1DOSEBASTION, ItemID._2DOSEBASTION, ItemID._3DOSEBASTION,
			ItemID._4DOSEBASTION, ItemID._1DOSEARMADYLBREW, ItemID._2DOSEARMADYLBREW, ItemID._3DOSEARMADYLBREW,
			ItemID._4DOSEARMADYLBREW);
		add(t, divine().set(Skill.RANGED, 10, 4), ItemID._1DOSEDIVINERANGE, ItemID._2DOSEDIVINERANGE,
			ItemID._3DOSEDIVINERANGE, ItemID._4DOSEDIVINERANGE, ItemID._1DOSEDIVINEBASTION, ItemID._2DOSEDIVINEBASTION,
			ItemID._3DOSEDIVINEBASTION, ItemID._4DOSEDIVINEBASTION);
		add(t, plain().set(Skill.RANGED, 15, 5), ItemID.NZONE1DOSE2RANGERSPOTION, ItemID.NZONE2DOSE2RANGERSPOTION,
			ItemID.NZONE3DOSE2RANGERSPOTION, ItemID.NZONE4DOSE2RANGERSPOTION);

		// Magic.
		add(t, plain().set(Skill.MAGIC, 0, 4), ItemID._1DOSE1MAGIC, ItemID._2DOSE1MAGIC, ItemID._3DOSE1MAGIC,
			ItemID._4DOSE1MAGIC, ItemID.BRUTAL_1DOSE1MAGIC, ItemID.BRUTAL_2DOSE1MAGIC, ItemID._1DOSEBATTLEMAGE,
			ItemID._2DOSEBATTLEMAGE, ItemID._3DOSEBATTLEMAGE, ItemID._4DOSEBATTLEMAGE);
		add(t, divine().set(Skill.MAGIC, 0, 4), ItemID._1DOSEDIVINEMAGIC, ItemID._2DOSEDIVINEMAGIC,
			ItemID._3DOSEDIVINEMAGIC, ItemID._4DOSEDIVINEMAGIC, ItemID._1DOSEDIVINEBATTLEMAGE, ItemID._2DOSEDIVINEBATTLEMAGE,
			ItemID._3DOSEDIVINEBATTLEMAGE, ItemID._4DOSEDIVINEBATTLEMAGE);
		add(t, plain().set(Skill.MAGIC, 0, 3), ItemID._1DOSEMAGICESS, ItemID._2DOSEMAGICESS, ItemID._3DOSEMAGICESS,
			ItemID._4DOSEMAGICESS, ItemID.BRUTAL_1DOSEMAGICESS, ItemID.BRUTAL_2DOSEMAGICESS);
		add(t, plain().set(Skill.MAGIC, 15, 5), ItemID.NZONE1DOSE2MAGICPOTION, ItemID.NZONE2DOSE2MAGICPOTION,
			ItemID.NZONE3DOSE2MAGICPOTION, ItemID.NZONE4DOSE2MAGICPOTION);
		add(t, plain().set(Skill.MAGIC, 5, 2), ItemID._1DOSEANCIENTBREW, ItemID._2DOSEANCIENTBREW,
			ItemID._3DOSEANCIENTBREW, ItemID._4DOSEANCIENTBREW, ItemID.BRUTAL_1DOSEANCIENTBREW, ItemID.BRUTAL_2DOSEANCIENTBREW);
		add(t, plain().set(Skill.MAGIC, 8, 3), ItemID._1DOSEFORGOTTENBREW, ItemID._2DOSEFORGOTTENBREW,
			ItemID._3DOSEFORGOTTENBREW, ItemID._4DOSEFORGOTTENBREW);
		add(t, heart().set(Skill.MAGIC, 10, 1), ItemID.IMBUED_HEART);
		add(t, heart().set(Skill.MAGIC, 10, 4), ItemID.SATURATED_HEART);

		// Several styles at once.
		add(t, plain().set(Skill.ATTACK, 15, 5).set(Skill.STRENGTH, 15, 5).set(Skill.RANGED, 15, 5).set(Skill.MAGIC, 15, 5),
			ItemID.NZONE1DOSEOVERLOADPOTION, ItemID.NZONE2DOSEOVERLOADPOTION, ItemID.NZONE3DOSEOVERLOADPOTION,
			ItemID.NZONE4DOSEOVERLOADPOTION, ItemID.SOUL_WARS_BUFF_POTION_1, ItemID.SOUL_WARS_BUFF_POTION_2,
			ItemID.SOUL_WARS_BUFF_POTION_3, ItemID.SOUL_WARS_BUFF_POTION_4);
		add(t, plain().set(Skill.ATTACK, 15, 8).set(Skill.STRENGTH, 15, 8).set(Skill.RANGED, 10, 7).set(Skill.MAGIC, 10, 1),
			ItemID.DEADMAN1DOSEOVERLOAD, ItemID.DEADMAN2DOSEOVERLOAD, ItemID.DEADMAN3DOSEOVERLOAD,
			ItemID.DEADMAN4DOSEOVERLOAD);
		add(t, plain().set(Skill.ATTACK, 15, 5).set(Skill.STRENGTH, 15, 5).set(Skill.RANGED, 10, 4).set(Skill.MAGIC, 10, 1),
			ItemID._1DOSE_CASTLEWARS_SKILL_POTION, ItemID._2DOSE_CASTLEWARS_SKILL_POTION,
			ItemID._3DOSE_CASTLEWARS_SKILL_POTION, ItemID._4DOSE_CASTLEWARS_SKILL_POTION);
		add(t, plain().set(Skill.ATTACK, 15, 4).set(Skill.STRENGTH, 15, 4).set(Skill.RANGED, 10, 4).set(Skill.MAGIC, 0, 4),
			ItemID.TOB_BANDAGES);
		// Tombs of Amascut: smelling salts.
		add(t, plain().set(Skill.ATTACK, 16, 11).set(Skill.STRENGTH, 16, 11).set(Skill.RANGED, 16, 11).set(Skill.MAGIC, 16, 11),
			ItemID.TOA_SUPPLY_STATS_1, ItemID.TOA_SUPPLY_STATS_2);
		// Chambers of Xeric, by tier: (-) 4 + 10%, plain 5 + 13%, (+) 6 + 16%.
		chambers(t, 10, 4, ItemID.RAIDS_VIAL_OVERLOAD_WEAK_1, ItemID.RAIDS_VIAL_OVERLOAD_WEAK_2,
			ItemID.RAIDS_VIAL_OVERLOAD_WEAK_3, ItemID.RAIDS_VIAL_OVERLOAD_WEAK_4, ItemID.RAIDS_VIAL_ELDER_WEAK_1,
			ItemID.RAIDS_VIAL_ELDER_WEAK_2, ItemID.RAIDS_VIAL_ELDER_WEAK_3, ItemID.RAIDS_VIAL_ELDER_WEAK_4,
			ItemID.RAIDS_VIAL_TWISTED_WEAK_1, ItemID.RAIDS_VIAL_TWISTED_WEAK_2, ItemID.RAIDS_VIAL_TWISTED_WEAK_3,
			ItemID.RAIDS_VIAL_TWISTED_WEAK_4, ItemID.RAIDS_VIAL_KODAI_WEAK_1, ItemID.RAIDS_VIAL_KODAI_WEAK_2,
			ItemID.RAIDS_VIAL_KODAI_WEAK_3, ItemID.RAIDS_VIAL_KODAI_WEAK_4);
		chambers(t, 13, 5, ItemID.RAIDS_VIAL_OVERLOAD_1, ItemID.RAIDS_VIAL_OVERLOAD_2, ItemID.RAIDS_VIAL_OVERLOAD_3,
			ItemID.RAIDS_VIAL_OVERLOAD_4, ItemID.RAIDS_VIAL_ELDER_1, ItemID.RAIDS_VIAL_ELDER_2, ItemID.RAIDS_VIAL_ELDER_3,
			ItemID.RAIDS_VIAL_ELDER_4, ItemID.RAIDS_VIAL_TWISTED_1, ItemID.RAIDS_VIAL_TWISTED_2, ItemID.RAIDS_VIAL_TWISTED_3,
			ItemID.RAIDS_VIAL_TWISTED_4, ItemID.RAIDS_VIAL_KODAI_1, ItemID.RAIDS_VIAL_KODAI_2, ItemID.RAIDS_VIAL_KODAI_3,
			ItemID.RAIDS_VIAL_KODAI_4);
		chambers(t, 16, 6, ItemID.RAIDS_VIAL_OVERLOAD_STRONG_1, ItemID.RAIDS_VIAL_OVERLOAD_STRONG_2,
			ItemID.RAIDS_VIAL_OVERLOAD_STRONG_3, ItemID.RAIDS_VIAL_OVERLOAD_STRONG_4, ItemID.RAIDS_VIAL_ELDER_STRONG_1,
			ItemID.RAIDS_VIAL_ELDER_STRONG_2, ItemID.RAIDS_VIAL_ELDER_STRONG_3, ItemID.RAIDS_VIAL_ELDER_STRONG_4,
			ItemID.RAIDS_VIAL_TWISTED_STRONG_1, ItemID.RAIDS_VIAL_TWISTED_STRONG_2, ItemID.RAIDS_VIAL_TWISTED_STRONG_3,
			ItemID.RAIDS_VIAL_TWISTED_STRONG_4, ItemID.RAIDS_VIAL_KODAI_STRONG_1, ItemID.RAIDS_VIAL_KODAI_STRONG_2,
			ItemID.RAIDS_VIAL_KODAI_STRONG_3, ItemID.RAIDS_VIAL_KODAI_STRONG_4);

		TABLE = Collections.unmodifiableMap(t);
	}

	// One tier of the Chambers potions, given as overloads, elders, twisteds and kodais, four doses each, in that order.
	private static void chambers(Map<Integer, Boost> t, int pct, int add, int... ids)
	{
		final Boost overload = plain().set(Skill.ATTACK, pct, add).set(Skill.STRENGTH, pct, add)
			.set(Skill.RANGED, pct, add).set(Skill.MAGIC, pct, add);
		final Boost elder = plain().set(Skill.ATTACK, pct, add).set(Skill.STRENGTH, pct, add);
		final Boost twisted = plain().set(Skill.RANGED, pct, add);
		final Boost kodai = plain().set(Skill.MAGIC, pct, add);
		final Boost[] kinds = {overload, elder, twisted, kodai};
		for (int i = 0; i < ids.length; i++)
		{
			t.put(ids[i], kinds[i / 4]);
		}
	}

	private static Boost plain()
	{
		return new Boost(false, false, false);
	}

	private static Boost divine()
	{
		return new Boost(true, false, false);
	}

	private static Boost heart()
	{
		return new Boost(false, true, false);
	}

	private static void add(Map<Integer, Boost> t, Boost boost, int... ids)
	{
		for (int id : ids)
		{
			final Boost previous = t.put(id, boost);
			if (previous != null)
			{
				throw new IllegalStateException("boost item listed twice: " + id);
			}
		}
	}

	private BoostTable()
	{
	}

	/** The item's boosts, or null if it boosts no combat stat. */
	static Boost get(int itemId)
	{
		return TABLE.get(itemId);
	}

	/** How many items the table knows, for the tests. */
	static int size()
	{
		return TABLE.size();
	}
}

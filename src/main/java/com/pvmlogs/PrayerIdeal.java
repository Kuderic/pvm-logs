package com.pvmlogs;

/**
 * The offensive prayer an attack should have had, worked out from what the
 * player can use rather than from a setting (T-015): the strongest prayer for
 * the style in the active prayer book that is unlocked and within the level
 * requirements.
 *
 * <p>Requirements are the OSRS Wiki's (2026-10-26). Levels are base levels: a
 * boosted Prayer does not open a higher prayer (an assumption, to confirm in
 * game).
 * <ul>
 * <li>Melee: Zeal 84 Prayer, 75 Attack and Strength, Horn of Bran (replaces
 * Piety); Piety 70 Prayer, 70 Defence, Knight Waves; Chivalry 60 Prayer, 65
 * Defence, Knight Waves; otherwise the best Attack prayer (Clarity 7, Improved
 * 16, Incredible Reflexes 34) with the best Strength prayer (Burst 4, Superhuman
 * 13, Ultimate Strength 31), which can be held together.</li>
 * <li>Ranged: Rigour 74 Prayer, 70 Defence, its scroll; Deadeye 62, its scroll
 * (replaces Eagle Eye); Eagle Eye 44; Hawk Eye 26; Sharp Eye 8.</li>
 * <li>Magic: Augury 77 Prayer, 70 Defence, its scroll; Mystic Vigour 63, its
 * scroll (replaces Mystic Might); Mystic Might 45; Mystic Lore 27; Mystic Will
 * 9.</li>
 * <li>Ruinous Powers (Leagues and Deadman only): Decimate 82, Annihilate 84,
 * Vaporise 86, else Ancient Strength 61, Ancient Sight 62, Ancient Will 63.
 * Intensify, accuracy only, is not considered.</li>
 * </ul>
 */
final class PrayerIdeal
{
	/** What the player can use, read from the client. */
	static final class Player
	{
		int prayer;
		int defence;
		int attack;
		int strength;
		boolean knightWaves;
		boolean zeal;
		boolean rigour;
		boolean augury;
		boolean deadeye;
		boolean mysticVigour;
		boolean ruinousBook;
	}

	/** A prayer (or two held together) with its multipliers, and why it was chosen. */
	static final class Goal
	{
		private final String name;
		private final double attackMultiplier;
		private final double strengthMultiplier;
		private final double magicDamagePercent;
		final String why;

		Goal(String name, double attack, double strength, double magicDamage, String why)
		{
			this.name = name;
			this.attackMultiplier = attack;
			this.strengthMultiplier = strength;
			this.magicDamagePercent = magicDamage;
			this.why = why;
		}

		Goal(PrayerChoice prayer, String why)
		{
			this(prayer.getDisplayName(), prayer.getAttackMultiplier(), prayer.getStrengthMultiplier(),
				prayer.getMagicDamagePercent(), why);
		}

		double getAttackMultiplier()
		{
			return attackMultiplier;
		}

		double getStrengthMultiplier()
		{
			return strengthMultiplier;
		}

		double getMagicDamagePercent()
		{
			return magicDamagePercent;
		}

		String getName()
		{
			return name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	enum Style
	{
		MELEE, RANGED, MAGIC
	}

	static final Goal NONE = new Goal(PrayerChoice.NONE, "no prayer usable");

	private PrayerIdeal()
	{
	}

	static Goal best(Style style, Player p)
	{
		if (p.ruinousBook)
		{
			return ruinous(style, p);
		}
		switch (style)
		{
			case RANGED:
				return ranged(p);
			case MAGIC:
				return magic(p);
			default:
				return melee(p);
		}
	}

	private static Goal melee(Player p)
	{
		if (p.zeal && p.prayer >= 84 && p.attack >= 75 && p.strength >= 75)
		{
			return new Goal(PrayerChoice.ZEAL, "unlocked; prayer " + p.prayer + ", attack " + p.attack + ", strength "
				+ p.strength);
		}
		final String waves = p.knightWaves ? "Knight Waves done" : "Knight Waves not done";
		// Zeal replaces Piety once unlocked, so a player who has it but not its levels falls to Chivalry.
		if (p.knightWaves && !p.zeal && p.prayer >= 70 && p.defence >= 70)
		{
			return new Goal(PrayerChoice.PIETY, waves + "; prayer " + p.prayer + ", defence " + p.defence);
		}
		if (p.knightWaves && p.prayer >= 60 && p.defence >= 65)
		{
			return new Goal(PrayerChoice.CHIVALRY, waves + (p.zeal ? ", Zeal unlocked but short of its levels" : "")
				+ "; prayer " + p.prayer + ", defence " + p.defence);
		}
		final PrayerChoice attack = p.prayer >= 34 ? PrayerChoice.INCREDIBLE_REFLEXES
			: p.prayer >= 16 ? PrayerChoice.IMPROVED_REFLEXES
			: p.prayer >= 7 ? PrayerChoice.CLARITY_OF_THOUGHT : null;
		final PrayerChoice strength = p.prayer >= 31 ? PrayerChoice.ULTIMATE_STRENGTH
			: p.prayer >= 13 ? PrayerChoice.SUPERHUMAN_STRENGTH
			: p.prayer >= 4 ? PrayerChoice.BURST_OF_STRENGTH : null;
		if (attack == null && strength == null)
		{
			return new Goal("None", 1.0, 1.0, 0, "prayer " + p.prayer + " opens no melee prayer");
		}
		final String name = strength == null ? attack.getDisplayName()
			: attack == null ? strength.getDisplayName()
			: strength.getDisplayName() + " + " + attack.getDisplayName();
		return new Goal(name, attack == null ? 1.0 : attack.getAttackMultiplier(),
			strength == null ? 1.0 : strength.getStrengthMultiplier(), 0,
			waves + "; prayer " + p.prayer + ", defence " + p.defence);
	}

	private static Goal ranged(Player p)
	{
		if (p.rigour && p.prayer >= 74 && p.defence >= 70)
		{
			return new Goal(PrayerChoice.RIGOUR, "unlocked; prayer " + p.prayer + ", defence " + p.defence);
		}
		final String why = (p.rigour ? "Rigour unlocked but short of its levels" : "Rigour not unlocked")
			+ "; prayer " + p.prayer + ", defence " + p.defence;
		if (p.deadeye && p.prayer >= 62)
		{
			return new Goal(PrayerChoice.DEADEYE, why);
		}
		// Deadeye replaces Eagle Eye once unlocked.
		if (!p.deadeye && p.prayer >= 44)
		{
			return new Goal(PrayerChoice.EAGLE_EYE, why);
		}
		if (p.prayer >= 26)
		{
			return new Goal(PrayerChoice.HAWK_EYE, why);
		}
		if (p.prayer >= 8)
		{
			return new Goal(PrayerChoice.SHARP_EYE, why);
		}
		return new Goal("None", 1.0, 1.0, 0, why);
	}

	private static Goal magic(Player p)
	{
		if (p.augury && p.prayer >= 77 && p.defence >= 70)
		{
			return new Goal(PrayerChoice.AUGURY, "unlocked; prayer " + p.prayer + ", defence " + p.defence);
		}
		final String why = (p.augury ? "Augury unlocked but short of its levels" : "Augury not unlocked")
			+ "; prayer " + p.prayer + ", defence " + p.defence;
		if (p.mysticVigour && p.prayer >= 63)
		{
			return new Goal(PrayerChoice.MYSTIC_VIGOUR, why);
		}
		// Mystic Vigour replaces Mystic Might once unlocked.
		if (!p.mysticVigour && p.prayer >= 45)
		{
			return new Goal(PrayerChoice.MYSTIC_MIGHT, why);
		}
		if (p.prayer >= 27)
		{
			return new Goal(PrayerChoice.MYSTIC_LORE, why);
		}
		if (p.prayer >= 9)
		{
			return new Goal(PrayerChoice.MYSTIC_WILL, why);
		}
		return new Goal("None", 1.0, 1.0, 0, why);
	}

	private static Goal ruinous(Style style, Player p)
	{
		final String why = "Ruinous Powers book; prayer " + p.prayer;
		switch (style)
		{
			case RANGED:
				return p.prayer >= 84 ? new Goal(PrayerChoice.ANNIHILATE, why)
					: p.prayer >= 62 ? new Goal(PrayerChoice.ANCIENT_SIGHT, why) : new Goal("None", 1.0, 1.0, 0, why);
			case MAGIC:
				return p.prayer >= 86 ? new Goal(PrayerChoice.VAPORISE, why)
					: p.prayer >= 63 ? new Goal(PrayerChoice.ANCIENT_WILL, why) : new Goal("None", 1.0, 1.0, 0, why);
			default:
				return p.prayer >= 82 ? new Goal(PrayerChoice.DECIMATE, why)
					: p.prayer >= 61 ? new Goal(PrayerChoice.ANCIENT_STRENGTH, why) : new Goal("None", 1.0, 1.0, 0, why);
		}
	}
}

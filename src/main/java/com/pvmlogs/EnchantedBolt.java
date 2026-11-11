package com.pvmlogs;

// The enchanted bolts whose effects change expected damage, with their PvM activation rates. The hard Kandarin diary
// raises every rate by a tenth.
enum EnchantedBolt
{
	/** Blood forfeit: 20% of the target's current health, capped, ignoring accuracy. */
	RUBY(0.06),
	/** Armour piercing: 15% higher max and a guaranteed hit. */
	DIAMOND(0.10),
	/** Life leech: 20% more damage, but not against the undead. */
	ONYX(0.11),
	/**
	 * T-182, Dragon's breath: on a hit, extra damage of a fifth of the visible Ranged level, rounded down; not against fiery
	 * monsters or dragons. OSRS Wiki, Enchanted bolts, read 2026-11-08: "dealing extra damage (Visible Ranged level * 20%,
	 * rounded down)", 6% in PvM, "negated by fiery monsters, such as dragons", and it doesn't bypass the accuracy roll.
	 */
	DRAGONSTONE(0.06);

	private static final int RUBY_DAMAGE_CAP = 100;

	private final double baseChance;

	EnchantedBolt(double baseChance)
	{
		this.baseChance = baseChance;
	}

	double chance(boolean kandarinHardDiary)
	{
		return kandarinHardDiary ? baseChance * 1.1 : baseChance;
	}

	/** T-212: its name on the overlay's proc row: "ruby", "diamond", "onyx", "dragonstone". */
	String shortName()
	{
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * T-212: what the proc deals, as a rule, for the overlay with no target to work it out against. Only the ruby's needs
	 * one (a fifth of the target's health); the others are worked out from the attack's own max and shown as a number.
	 */
	String rule()
	{
		switch (this)
		{
			case RUBY:
				return "20% hp";
			case DIAMOND:
				return "+15%";
			case ONYX:
				return "+20%";
			default:
				return "+ranged/5";
		}
	}

	static int rubyDamage(int targetCurrentHp)
	{
		return Math.min(RUBY_DAMAGE_CAP, targetCurrentHp / 5);
	}

	/** T-182: Dragon's breath's extra damage for this visible (boosted) Ranged level. */
	static int dragonstoneDamage(int visibleRangedLevel)
	{
		return Math.max(0, visibleRangedLevel) / 5;
	}

	/**
	 * The bolt fired (the ammo slot's or, T-182, a Dizana's quiver's), or null. Matches on name so the plain
	 * and dragon variants of each are both covered.
	 */
	static EnchantedBolt forAmmoName(String ammoName)
	{
		if (ammoName == null || !ammoName.endsWith("bolts (e)"))
		{
			return null;
		}
		if (ammoName.startsWith("Ruby"))
		{
			return RUBY;
		}
		if (ammoName.startsWith("Diamond"))
		{
			return DIAMOND;
		}
		if (ammoName.startsWith("Dragonstone"))
		{
			return DRAGONSTONE;
		}
		return ammoName.startsWith("Onyx") ? ONYX : null;
	}
}

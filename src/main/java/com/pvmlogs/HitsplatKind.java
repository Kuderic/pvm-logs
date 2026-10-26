package com.pvmlogs;

import net.runelite.api.HitsplatID;

/**
 * Where a hitsplat on an NPC came from, as far as its type can say.
 *
 * <p>{@code Hitsplat.isMine()} is true only for the player's own damage and block
 * types (and, unavoidably, our thrall's - see ThrallTracker). Everything else on
 * the boss used to be dropped unseen: burn from burning claws, poison, venom,
 * bleed, the boss healing, other players' hits. That made the trace blind to burn
 * and left the counted health drifting from the real one. Each kind is now kept
 * apart, counted where it belongs, and logged.
 *
 * <p>Damage over time carries no owner. Solo, every burn on the boss is the
 * player's; in a group it may be anyone's, so it is reported as damage over time
 * on the target and never folded into the player's weapon damage or hits.
 *
 * <p>Which types exist is RuneLite's {@link HitsplatID} (API 1.13.4). What the
 * non-damage ones mean is the wiki's Hitsplat page: disease drains stats but never
 * hitpoints, prayer drain is Phantom Muspah's prayer shield, sanity is the
 * Whisperer's, poise is the wardens' phase two. None of those touch the target's
 * hitpoints. CYAN_UP and CYAN_DOWN are taken, from their names, to be the wiki's
 * charge and uncharge splats (the Nightmare's totems); that pairing is not
 * verified, so each one is still logged by type.
 */
enum HitsplatKind
{
	/** The player's own weapon or spell (or our thrall, which shares the type). */
	PLAYER,
	/** Burn, poison, venom, bleed: damage over time with no owner on the splat. */
	DAMAGE_OVER_TIME,
	/** The NPC healing. */
	HEAL,
	/** Another player's hit. */
	OTHER_PLAYER,
	/** A splat that changes something other than hitpoints: a charge, poise, prayer, sanity, a stat drain. */
	NO_HEALTH_EFFECT,
	/**
	 * A type this plugin does not know. Counted against the target's health so the
	 * tally stays honest, logged by number so a new type can be identified from a
	 * debug log, and never credited to the player.
	 */
	UNCLASSIFIED;

	static HitsplatKind of(boolean mine, boolean others, int type)
	{
		if (mine)
		{
			return PLAYER;
		}
		switch (type)
		{
			case HitsplatID.BURN:
			case HitsplatID.POISON:
			case HitsplatID.VENOM:
			case HitsplatID.BLEED:
				return DAMAGE_OVER_TIME;
			case HitsplatID.HEAL:
				return HEAL;
			case HitsplatID.DISEASE:
			case HitsplatID.DISEASE_BLOCKED:
			case HitsplatID.CYAN_UP:
			case HitsplatID.CYAN_DOWN:
			case HitsplatID.PRAYER_DRAIN:
			case HitsplatID.SANITY_DRAIN:
			case HitsplatID.SANITY_RESTORE:
			case HitsplatID.DAMAGE_OTHER_POISE:
				return NO_HEALTH_EFFECT;
			default:
				return others ? OTHER_PLAYER : UNCLASSIFIED;
		}
	}

	/** Whether a splat of this kind takes the target's hitpoints down. */
	boolean damagesTarget()
	{
		return this == PLAYER || this == DAMAGE_OVER_TIME || this == OTHER_PLAYER || this == UNCLASSIFIED;
	}

	/** A short name for a hitsplat type, for the log and the breakdown. Unknown types read as their number. */
	static String typeName(int type)
	{
		switch (type)
		{
			case HitsplatID.BURN:
				return "burn";
			case HitsplatID.POISON:
				return "poison";
			case HitsplatID.VENOM:
				return "venom";
			case HitsplatID.BLEED:
				return "bleed";
			case HitsplatID.DISEASE:
				return "disease";
			case HitsplatID.HEAL:
				return "heal";
			case HitsplatID.DISEASE_BLOCKED:
				return "disease blocked";
			case HitsplatID.CYAN_UP:
				return "charge up";
			case HitsplatID.CYAN_DOWN:
				return "charge down";
			case HitsplatID.PRAYER_DRAIN:
				return "prayer drain";
			case HitsplatID.SANITY_DRAIN:
				return "sanity drain";
			case HitsplatID.SANITY_RESTORE:
				return "sanity restore";
			case HitsplatID.DAMAGE_OTHER_POISE:
				return "others' poise";
			default:
				return "type " + type;
		}
	}
}

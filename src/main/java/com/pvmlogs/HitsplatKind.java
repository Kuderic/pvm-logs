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
 */
enum HitsplatKind
{
	/** The player's own weapon or spell (or our thrall, which shares the type). */
	PLAYER,
	/** Burn, poison, venom, bleed, disease: damage over time with no owner on the splat. */
	DAMAGE_OVER_TIME,
	/** The NPC healing. */
	HEAL,
	/** Another player's hit. */
	OTHER_PLAYER,
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
			case HitsplatID.DISEASE:
				return DAMAGE_OVER_TIME;
			case HitsplatID.HEAL:
				return HEAL;
			default:
				return others ? OTHER_PLAYER : UNCLASSIFIED;
		}
	}

	/** A short name for a damage-over-time type, for the log and the breakdown. */
	static String dotName(int type)
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
			default:
				return "type " + type;
		}
	}
}

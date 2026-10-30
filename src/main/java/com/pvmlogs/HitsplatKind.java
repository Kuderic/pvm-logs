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
 * hitpoints.
 *
 * <p><b>The coloured damage types.</b> RuneLite names them by colour only
 * (cyan, orange, yellow, white), each with a mine, an others' and a max-hit
 * variant. The wiki's Hitsplat page lists exactly four other splat families that
 * come in those three variants and are not hitpoints: shield, armour, charge and
 * uncharge. Matched by the colour of the wiki's own images (measured
 * 2026-10-26): shield is cyan, armour orange, charge yellow, uncharge grey-white.
 * So cyan is shield damage (the Hueycoatl, Kephri, Verzik, the Nightmare,
 * Tempoross), orange is armour (Zalcano, the Smorks), yellow and white are the
 * Nightmare's totems being charged and uncharged, and the poise types are the
 * wardens'. This pairing is an inference from names and colours, not yet seen in
 * a log; every such splat is logged with its type so a fight can confirm it.
 * CYAN_UP and CYAN_DOWN, with no mine/others/max variants, are taken to be the
 * wiki's "alt charge" and "alt uncharge" (Kephri's scarab shield, the Palm of
 * Resourcefulness; alt charge also restores the Chambers' Vasa Nistirio, which
 * would be a heal - unverified, so they are logged and left out of the count).
 *
 * <p>The player's own shield, armour, charge and poise splats are the player's
 * attacks landing, and the expected side scores those attacks, so they stay the
 * player's weapon damage (Eric, 2026-10-26, T-011 item 3); they are kept out of
 * the target's hitpoints only.
 *
 * <p><b>Splats on the local player</b> ({@link #onPlayer}, T-018) mean something
 * else, because "mine" and "others'" are about who is looking, not who struck:
 * an NPC's hit on the player shows the same red and blue a player's own hit does.
 * What each type does to a player is the wiki's Hitsplat page (read 2026-10-27):
 * poison, venom, bleed (Vardorvis's axes) and burn (Yama) take hitpoints over
 * time; a heal restores them; disease drains other stats, corruption and prayer
 * drain (Frost Nagua) drain prayer, sanity is the Whisperer's, doom is a Fortis
 * Colosseum handicap counted in stacks, and a cyan shield splat on a player is an
 * attack the player was protected from (Moons of Peril, Yama). None of those take
 * hitpoints. The wiki's corrosion (the Shellbane Gryphon) does, but RuneLite has
 * no id for it yet: it arrives as an unknown type, which is logged by number and
 * counted nowhere until a log names it.
 */
enum HitsplatKind
{
	/** The player's own weapon or spell landing on hitpoints (or our thrall, which shares the type). */
	PLAYER,
	/**
	 * The player's attack landing on something other than hitpoints: a shield, an
	 * armour, a totem's charge, a warden's poise. Still the player's attack and its
	 * weapon damage; not the target's hitpoints. See the class notes.
	 */
	PLAYER_NOT_HITPOINTS,
	/** Burn, poison, venom, bleed: damage over time with no owner on the splat. */
	DAMAGE_OVER_TIME,
	/** The NPC healing. */
	HEAL,
	/** Another player's hit. */
	OTHER_PLAYER,
	/**
	 * T-106: the room's damage on the boss, a mechanic's or the boss's own: shown
	 * as another player's (type 17) with no other player there. It takes the
	 * target's hitpoints down and is nobody's. Only {@link RoomDamage#kindOf} gives
	 * it.
	 */
	ROOM,
	/** A splat that changes something other than hitpoints: a charge, poise, prayer, sanity, a stat drain. */
	NO_HEALTH_EFFECT,
	/**
	 * A type this plugin does not know. Counted against the target's health so the
	 * tally stays honest, logged by number so a new type can be identified from a
	 * debug log, and never credited to the player.
	 */
	UNCLASSIFIED,
	/**
	 * A hit on the local player: an attack's damage, or its 0. Only {@link #onPlayer}
	 * gives it. The game says nothing of where it came from, so a mechanic's typeless
	 * damage reads the same as a boss's swing.
	 */
	HIT_TAKEN;

	static HitsplatKind of(boolean mine, boolean others, int type)
	{
		if (mine)
		{
			return notHitpoints(type) ? PLAYER_NOT_HITPOINTS : PLAYER;
		}
		if (notHitpoints(type))
		{
			return NO_HEALTH_EFFECT;
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
				return NO_HEALTH_EFFECT;
			default:
				return others ? OTHER_PLAYER : UNCLASSIFIED;
		}
	}

	/**
	 * What a splat on the local player is (T-018): a hit, damage over time, a heal,
	 * no hitpoints effect, or a type not known here. See the class notes. A hit and
	 * damage over time take the player's hitpoints; a heal restores them; the rest
	 * are logged and counted nowhere.
	 */
	static HitsplatKind onPlayer(int type)
	{
		switch (type)
		{
			case HitsplatID.BLOCK_ME:
			case HitsplatID.BLOCK_OTHER:
			case HitsplatID.DAMAGE_ME:
			case HitsplatID.DAMAGE_OTHER:
			case HitsplatID.DAMAGE_MAX_ME:
				return HIT_TAKEN;
			case HitsplatID.POISON:
			case HitsplatID.VENOM:
			case HitsplatID.BURN:
			case HitsplatID.BLEED:
				return DAMAGE_OVER_TIME;
			case HitsplatID.HEAL:
				return HEAL;
			case HitsplatID.CORRUPTION:
			case HitsplatID.DISEASE:
			case HitsplatID.DISEASE_BLOCKED:
			case HitsplatID.CYAN_UP:
			case HitsplatID.CYAN_DOWN:
			case HitsplatID.PRAYER_DRAIN:
			case HitsplatID.SANITY_DRAIN:
			case HitsplatID.SANITY_RESTORE:
			case HitsplatID.DOOM:
				return NO_HEALTH_EFFECT;
			default:
				return notHitpoints(type) ? NO_HEALTH_EFFECT : UNCLASSIFIED;
		}
	}

	/** The kind of a splat on the local player, as the TAKEN line names it. */
	String takenName()
	{
		switch (this)
		{
			case HIT_TAKEN:
				return "hit";
			case DAMAGE_OVER_TIME:
				return "dot";
			case HEAL:
				return "heal";
			case NO_HEALTH_EFFECT:
				return "no hp";
			default:
				return "unknown";
		}
	}

	/** Shield, armour, charge, uncharge and poise, in every variant: see the class notes. */
	static boolean notHitpoints(int type)
	{
		switch (type)
		{
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_OTHER_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_OTHER_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_OTHER_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_OTHER_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_ME_POISE:
			case HitsplatID.DAMAGE_OTHER_POISE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return true;
			default:
				return false;
		}
	}

	/** Whether a splat of this kind takes the target's hitpoints down. */
	boolean damagesTarget()
	{
		return this == PLAYER || this == DAMAGE_OVER_TIME || this == OTHER_PLAYER || this == ROOM || this == UNCLASSIFIED;
	}

	/** A short name for a hitsplat type, for the log and the breakdown. Unknown types read as their number. */
	static String typeName(int type)
	{
		switch (type)
		{
			case HitsplatID.BLOCK_ME:
				return "zero";
			case HitsplatID.BLOCK_OTHER:
				return "others' zero";
			case HitsplatID.DAMAGE_ME:
				return "damage";
			case HitsplatID.DAMAGE_OTHER:
				return "others' damage";
			case HitsplatID.DAMAGE_MAX_ME:
				return "max hit";
			case HitsplatID.CORRUPTION:
				return "corruption";
			case HitsplatID.DOOM:
				return "doom";
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
				return "alt charge";
			case HitsplatID.CYAN_DOWN:
				return "alt uncharge";
			case HitsplatID.PRAYER_DRAIN:
				return "prayer drain";
			case HitsplatID.SANITY_DRAIN:
				return "sanity drain";
			case HitsplatID.SANITY_RESTORE:
				return "sanity restore";
			case HitsplatID.DAMAGE_OTHER_POISE:
				return "others' poise";
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
				return "shield";
			case HitsplatID.DAMAGE_OTHER_CYAN:
				return "others' shield";
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
				return "armour";
			case HitsplatID.DAMAGE_OTHER_ORANGE:
				return "others' armour";
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
				return "charge";
			case HitsplatID.DAMAGE_OTHER_YELLOW:
				return "others' charge";
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
				return "uncharge";
			case HitsplatID.DAMAGE_OTHER_WHITE:
				return "others' uncharge";
			case HitsplatID.DAMAGE_ME_POISE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return "poise";
			default:
				return "type " + type;
		}
	}
}

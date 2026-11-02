package com.pvmlogs;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;

// Raid scaling applied to a monster's levels, in one place because the model and the drain tracker need the same
// answer. The Chambers arithmetic is taken from the GearScape DPS calculator, the only working record of it; credit is
// owed in the submission. It is the mechanic that is reproduced, not their code.
final class RaidScaling
{
	// The few whose magic level is worth rolling against, and so is scaled with the rest of them.
	private static final Set<String> MAGIC_SCALED = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList("deathly ranger", "abyssal portal", "vespula", "vespine soldier")));

	// Olm's hands mitigate two thirds of an off-style hit, leaving a third.
	private static final double MITIGATED = 1.0 / 3.0;

	private RaidScaling()
	{
	}

	// The share of a hit that reaches the target, for the few that shrug most of it off.
	static double damageTaken(int npcId, AttackType type)
	{
		if (npcId == NpcID.OLM_HAND_RIGHT || npcId == NpcID.OLM_HAND_RIGHT_SPAWNING)
		{
			return type == AttackType.MAGIC ? 1 : MITIGATED;
		}
		if (npcId == NpcID.OLM_HAND_LEFT || npcId == NpcID.OLM_HAND_LEFT_SPAWNING)
		{
			return type.isMelee() ? 1 : MITIGATED;
		}
			// The Nightmare's totems take double from magic. Left out, expected damage would be half what lands.
		return EncounterGroup.isNightmareTotem(npcId) && type == AttackType.MAGIC ? 2 : 1;
	}

	/** A monster's defence level as the raid it stands in leaves it. */
	static int defence(Client client, int base, String name, int partyHitpoints)
	{
		final int toa = tombsRaidLevel(client);
		if (toa > 0)
		{
			return tombs(base, toa);
		}
		if (inChambers(client))
		{
			final int size = chambersPartySize(client);
			return chambers(base, size, partyHitpoints,
				defenceMultiplier(isChallengeMode(client), size, name));
		}
		return base;
	}

	// A monster's magic level as the raid leaves it.
	static int magic(Client client, int base, String name, int partyHitpoints)
	{
		if (!inChambers(client) || !scalesMagic(name))
		{
			return base;
		}
		final int size = chambersPartySize(client);
		// The mage hand rolls on half its magic, and the monster data ALREADY carries that: the two claws are identical in
		// every other stat and the right one is listed at 87 against the left's 175, which is that half. Halving here as
		// well quartered it, and a quartered defensive roll reads as accuracy the attack does not have - so the halving
		// lives in the data now, and the halving that used to sit here is gone with it.
		return chambers(base, size, partyHitpoints,
			defenceMultiplier(isChallengeMode(client), size, name));
	}

	/**
	 * T-146: a monster's full hitpoints as the raid it stands in leaves them, from its table figure (the solo, maxed
	 * one); the table's otherwise. The count starts from this where the boss bar isn't read.
	 * <ul>
	 * <li>Chambers: times one plus half the party, rounded down ({@link #chambersHitpoints}).</li>
	 * <li>Tombs: 2% more per five raid levels ({@link #tombsHitpoints}).</li>
	 * </ul>
	 */
	static int hitpoints(Client client, int base, int npcId, String version)
	{
		if (base <= 0 || !scalesHitpoints(npcId))
		{
			return base;
		}
		final int toa = tombsRaidLevel(client);
		if (toa > 0)
		{
			return tombsHitpoints(base, toa);
		}
		if (inChambers(client))
		{
			return chambersHitpoints(base, chambersPartySize(client), isChallengeMode(client),
				version != null && version.toLowerCase().contains("challenge"));
		}
		return base;
	}

	/**
	 * T-146: the Chambers. Sources:
	 * <ul>
	 * <li>The party factor, 1 + floor(T/2) for a team of T: OSRS Wiki, read 2026-11-01, Guardian (Chambers of Xeric):
	 * "The strengths and hit points of the guardian is dependent on the party size", its formula multiplying by
	 * (1 + floor(T/2)); Eric's raid of 2026-11-01 13:02, a party of three, read the boss bar at Tekton's 590 (table 300)
	 * and the Great Olm's claws' 1200 (table 600), twice each; Talk:Chambers of Xeric/Strategies (2020, not Jagex's):
	 * Tekton "300 solo to 600 duo", and Olm's stats "entirely determined by party size". The talk page's own reading
	 * for larger parties ("monsters add their base hp" per member) doesn't fit three's 590, so it isn't followed.</li>
	 * <li>The Guardian's own formula (151 x that factor, plus the party's average Mining times T) isn't applied: its
	 * text came through garbled, and it doesn't fit the same day's second raid, whose Guardian died at 262 counted
	 * ([11246]), under any party's figure by it. The common factor covers both of the day's Guardians (482 and more
	 * for three, 262), on the side of more left.</li>
	 * <li>The combat level term ("about 2 hp for each missing level", the same talk page: Tekton's 590 for 600) is left
	 * out, for want of a source: the count errs to more left by a few percent.</li>
	 * <li>Challenge mode: half as much again (the wiki infoboxes' Challenge Mode versions: Tekton 300 and 450, the
	 * Guardian 250 and 375), unless the monster data's version is already the Challenge Mode one.</li>
	 * </ul>
	 */
	static int chambersHitpoints(int base, int partySize, boolean challengeMode, boolean cmVersion)
	{
		final int party = Math.max(1, Math.min(100, partySize));
		int hp = base * (1 + party / 2);
		if (challengeMode && !cmVersion)
		{
			hp = hp * 3 / 2;
		}
		return hp;
	}

	/**
	 * T-146: whether a raid monster's hitpoints scale. The Chambers' Scavenger beasts don't: their 14 kills in Eric's
	 * raid of 2026-11-01 13:02, a party of three, counted 22 to 30 on the target against the table's 30, never over
	 * ([3944] 29, [5776] 29, [11425] 29), where every other kind there fits the party factor (the Ice demon 274 of 140,
	 * the Deathly mage and ranger 236 of 120, Tekton 590, the Muttadiles 492 to 498). The wiki's Scavenger beast page
	 * gives 30 and says nothing of party scaling; none of its Chambers pages names another that doesn't scale.
	 */
	static boolean scalesHitpoints(int npcId)
	{
		return npcId != NpcID.RAIDS_SCAVENGER_BEAST_A && npcId != NpcID.RAIDS_SCAVENGER_BEAST_B;
	}

	/**
	 * T-146: whether a raid monster's full hitpoints, counted from the table where the boss bar isn't read, may be short
	 * of its real figure: in the Tombs, whose party and path level aren't read. A share of its overhead bar is then a
	 * share of too little, and mustn't lower the count. In the Chambers the figure can only be long (no combat level
	 * term), which makes the bar's bound looser, the safe side.
	 */
	static boolean fullMayBeShort(Client client)
	{
		return tombsRaidLevel(client) > 0;
	}

	/**
	 * T-146: the Tombs. OSRS Wiki, read 2026-11-01, Tombs of Amascut: "for every 5 raid levels, an NPC's hitpoints,
	 * defence, accuracy, and damage are increased by 2% additively". Left out, for want of a reading: the party ("the
	 * 2nd-3rd member will add 90% of the base health of bosses each", "the 4th and beyond will add 60% each": no varbit
	 * here gives the party's size) and the path level ("+8%", then "+5%" a level). The bosses show the boss bar, which
	 * beats the count; a monster that doesn't is counted low on a path past level 0.
	 */
	static int tombsHitpoints(int base, int raidLevel)
	{
		return base * (100 + 2 * (raidLevel / 5)) / 100;
	}

	/**
	 * Tombs: 2% per five raid levels, additively, so 500 is +200%. Defence level
	 * only - magic does not move, and defence bonuses are armour.
	 */
	static int tombs(int base, int raidLevel)
	{
		return base * (100 + 2 * (raidLevel / 5)) / 100;
	}

		// Chambers: down by the party's hitpoints, up by its size, then multiplied for challenge mode. Each step floors and
		// the order changes the answer.
	static int chambers(int base, int partySize, int partyHitpoints, double cmMultiplier)
	{
		final int party = Math.max(1, Math.min(100, partySize));
		final int hp = Math.max(1, Math.min(99, partyHitpoints));
		final int hpTerm = Math.max(55, Math.min(99, 55 + 44 * hp / 99));

		int level = base * hpTerm / 99;
		final int sizeTerm = (int) Math.sqrt(party - 1) + 7 * (party - 1) / 10 + 100;
		level = level * sizeTerm / 100;
		return (int) (level * cmMultiplier);
	}

	/**
	 * Challenge mode raises defence by half, except Tekton - a fifth in a small
	 * team, more in a large one - and the glowing crystal, which it leaves.
	 */
	static double defenceMultiplier(boolean challengeMode, int partySize, String name)
	{
		if (!challengeMode)
		{
			return 1;
		}
		final String lower = name == null ? "" : name.toLowerCase();
		if (lower.startsWith("tekton"))
		{
			return partySize < 4 ? 1.2 : 1.35;
		}
		return lower.startsWith("glowing crystal") ? 1 : 1.5;
	}

	private static boolean scalesMagic(String name)
	{
		if (name == null)
		{
			return false;
		}
		final String lower = name.toLowerCase();
		if (lower.startsWith("tekton") || lower.startsWith("great olm"))
		{
				// Olm's head rolls on defence rather than magic, and both hands use it. The head cannot be told apart here -
				// the monster data names all three parts "Great Olm" and drops the version that separates them - so this
				// reads as true for the head as well. Harmless while nothing rolls against the head's magic, and the note is
				// left because it is the id, not the name, that will have to answer it.
			return !lower.contains("head");
		}
		for (String scaled : MAGIC_SCALED)
		{
			if (lower.startsWith(scaled))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The party size the raid is scaled for, which the Chambers can set higher
	 * than the number of people actually in it.
	 */
	private static int chambersPartySize(Client client)
	{
		final int scaled = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE_SCALED);
		final int actual = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
		return Math.max(1, Math.min(100, Math.max(scaled, actual)));
	}

	private static boolean isChallengeMode(Client client)
	{
		return client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1;
	}

	private static boolean inChambers(Client client)
	{
		return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
	}

	/**
	 * Reads in the Tombs lobby too, where nothing is fought. Set each tick by
	 * GearBonusCalc, which latches the raid on the way in: the varbit alone
	 * cannot answer it, since it is never cleared.
	 */
	private static int tombsLevel;

	static void setTombsRaidLevel(int level)
	{
		tombsLevel = level;
	}

	static int tombsRaidLevel(Client client)
	{
		return tombsLevel;
	}
}

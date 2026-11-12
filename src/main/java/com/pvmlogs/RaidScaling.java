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
	 * <li>Tombs (T-153): only the bosses and the adds that scale with them, by raid and path level
	 * ({@link #tombsHitpoints}); every other monster there keeps its table figure.</li>
	 * </ul>
	 */
	static int hitpoints(Client client, int base, int npcId, String version)
	{
		final int toa = tombsRaidLevel(client);
		if (toa > 0)
		{
			return tombsHitpoints(base, npcId, toa, tombsPathLevel(client, npcId), 1);
		}
		if (base <= 0 || !scalesHitpoints(npcId))
		{
			return base;
		}
		if (inChambers(client))
		{
			return chambersHitpoints(base, chambersPartySize(client), isChallengeMode(client),
				version != null && version.toLowerCase().contains("challenge"));
		}
		return base;
	}

	/** T-153: what {@link #hitpoints} read for this npc, for the debug log's {@code RAID HP} line; null outside the Tombs. */
	static String hitpointsBasis(Client client, int npcId)
	{
		final int toa = tombsRaidLevel(client);
		if (toa <= 0)
		{
			return null;
		}
		if (!tombsScales(npcId))
		{
			return String.format("raid level %d; doesn't scale in the Tombs, so %s", toa,
				npcId == NpcID.TOA_PATH_APMEKEN_BABOON_THRALL ? "the Baboon Thrall's measured " + BABOON_THRALL_HP
					: "the table's");
		}
		final StringBuilder slots = new StringBuilder();
		for (int varbit : PARTY_SLOTS)
		{
			slots.append(slots.length() == 0 ? "" : ",").append(client.getVarbitValue(varbit));
		}
		return String.format("raid level %d, path level %s, party taken as 1 (party slots %s)%s", toa,
			TOMBS_PATH_SCALED.contains(npcId) ? String.valueOf(tombsPathLevel(client, npcId)) : "none",
			slots, TOMBS_CORE_EJECTED.contains(npcId) ? ", from the core-ejected Warden's " + CORE_EJECTED_HP : "");
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
	 * of its real figure. T-153: in the Tombs, a monster that scales ({@link #tombsScales}), whose party isn't read:
	 * the count runs from a solo figure. A share of its overhead bar is then a share of too little, and mustn't lower
	 * the count. A Tombs monster that doesn't scale has its table's exactly (Eric's eggs on 35, baboons on 20 to 40). In
	 * the Chambers the figure can only be long (no combat level term), which makes the bar's bound looser, the safe side.
	 */
	static boolean fullMayBeShort(Client client, int npcId)
	{
		return tombsRaidLevel(client) > 0 && tombsScales(npcId);
	}

	// T-153: the Tombs' monsters whose hitpoints scale, by gameval id. The facts are the DPS calculator's (weirdgloop
	// osrs-dps-calc, src/lib/constants.ts and src/lib/scaling/TombsOfAmascut.ts, read 2026-11-02; the mechanic is
	// reproduced, not their code), and Eric's solo raid of 2026-11-02 at raid level 340 is the check: every boss bar
	// read matches (Zebak 1480, Kephri 400 and 210, Akkha 1070, Ba-Ba 1060, the Obelisk 610, Elidinis' Warden 330 and
	// 6030, Tumeken's 2080), Akkha's shadows died on 185 four times, and the eggs (35), the swarms (10), the agile
	// scarabs (30), the path's baboons (20 to 40) and the puzzle's scarab (12) died on their table figures.
	private static final Set<Integer> TOMBS_PATH_SCALED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		// Akkha, his shadows.
		NpcID.AKKHA_SPAWN, NpcID.AKKHA_MELEE, NpcID.AKKHA_RANGE, NpcID.AKKHA_MAGE, NpcID.AKKHA_ENRAGE_SPAWN,
		NpcID.AKKHA_ENRAGE_INITIAL, NpcID.AKKHA_ENRAGE, NpcID.AKKHA_ENRAGE_DUMMY,
		NpcID.AKKHA_SHADOW, NpcID.AKKHA_SHADOW_ENRAGE, NpcID.AKKHA_SHADOW_ENRAGE_DUMMY,
		// Ba-Ba.
		NpcID.TOA_BABA, NpcID.TOA_BABA_COFFIN, NpcID.TOA_BABA_DIGGING,
		// Kephri, and her overlords. TOA_KEPHRI_BOSS_WEAK isn't on the calculator's list; taken as her other forms are (a
		// guess: the log saw only her shielded 11719 and her 11721).
		NpcID.TOA_KEPHRI_BOSS_SHIELDED, NpcID.TOA_KEPHRI_BOSS_WEAK, NpcID.TOA_KEPHRI_BOSS_ENRAGE,
		NpcID.TOA_KEPHRI_GUARDIAN_MELEE, NpcID.TOA_KEPHRI_GUARDIAN_RANGED, NpcID.TOA_KEPHRI_GUARDIAN_MAGE,
		// Zebak.
		NpcID.TOA_ZEBAK, NpcID.TOA_ZEBAK_ENRAGED, NpcID.TOA_ZEBAK_DEAD)));
	// The Wardens: the raid level, no path level.
	private static final Set<Integer> TOMBS_RAID_SCALED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		NpcID.TOA_WARDENS_P1_OBELISK_NPC_INACTIVE, NpcID.TOA_WARDENS_P1_OBELISK_NPC, NpcID.TOA_WARDENS_P2_OBELISK_NPC,
		NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE,
		NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE,
		NpcID.TOA_WARDEN_ELIDINIS_PHASE3, NpcID.TOA_WARDEN_ELIDINIS_PHASE3_CHARGING,
		NpcID.TOA_WARDEN_TUMEKEN_PHASE3, NpcID.TOA_WARDEN_TUMEKEN_PHASE3_CHARGING)));
	// The Wardens with the core out: their own figure, and a tenth of the raid level's scaling.
	private static final Set<Integer> TOMBS_CORE_EJECTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED)));
	static final int CORE_EJECTED_HP = 4500;
	// Measured, not sourced: the Baboon Thrall's table figure is 2 (the monster data's), but every one of Eric's died on a
	// 4 - seven kills over his two raids of 2026-11-02, at raid levels 340 and 320 ([32422], [32449], [35826]). Here
	// until the monster data carries it (MonsterStatsProvider is T-152's); whether it is a 4 at every raid level, no log
	// says.
	static final int BABOON_THRALL_HP = 4;
	// The Tombs' party slots (TOA_CLIENT_P0 to P7), logged beside RAID HP so a party's raid can say what they hold. Not
	// read as the party's size: nothing has shown what they carry.
	private static final int[] PARTY_SLOTS = {VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2,
		VarbitID.TOA_CLIENT_P3, VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6,
		VarbitID.TOA_CLIENT_P7};

	/** T-153: whether a Tombs monster's hitpoints scale at all: the bosses, Akkha's shadows and Kephri's overlords. */
	static boolean tombsScales(int npcId)
	{
		return TOMBS_PATH_SCALED.contains(npcId) || TOMBS_RAID_SCALED.contains(npcId)
			|| TOMBS_CORE_EJECTED.contains(npcId);
	}

	/**
	 * T-153: the path level that scales this npc, from its path's varbit (gameval {@code TOA_CLIENT_CRONDIS_LEVEL} for
	 * Zebak, {@code _SCABARAS_} Kephri, {@code _HET_} Akkha, {@code _APMEKEN_} Ba-Ba); 0 for the Wardens and the rest.
	 * That these hold the path's level is read from their names; the bars fit it (Eric's raid: Zebak 1, Kephri 2, Akkha
	 * 2, Ba-Ba 3), and RAID HP logs it.
	 */
	static int tombsPathLevel(Client client, int npcId)
	{
		if (!TOMBS_PATH_SCALED.contains(npcId))
		{
			return 0;
		}
		final String group = EncounterGroup.of(npcId);
		final int varbit = "Zebak".equals(group) ? VarbitID.TOA_CLIENT_CRONDIS_LEVEL
			: "Kephri".equals(group) ? VarbitID.TOA_CLIENT_SCABARAS_LEVEL
			: "Akkha".equals(group) ? VarbitID.TOA_CLIENT_HET_LEVEL
			: "Ba-Ba".equals(group) ? VarbitID.TOA_CLIENT_APMEKEN_LEVEL : -1;
		return varbit < 0 ? 0 : Math.max(0, Math.min(6, client.getVarbitValue(varbit)));
	}

	/**
	 * T-153: a Tombs monster's full hitpoints. Sources:
	 * <ul>
	 * <li>OSRS Wiki, Tombs of Amascut, read 2026-11-02: "for every 5 raid levels, an NPC's hitpoints ... are increased by
	 * 2% additively"; path level: "The first path level will modify most enemy hitpoints and damage by +8%, with every
	 * additional level modifying these stats by +5%, up to six levels"; the party: "the 2nd-3rd member will add 90% of
	 * the base health of bosses each", "the 4th and beyond will add 60% each".</li>
	 * <li>OSRS Wiki, Core: "damage dealt to the core deals five times the damage towards the Warden".</li>
	 * <li>The DPS calculator's facts (above): which monsters scale; that each step rounds down; the core-ejected
	 * Wardens' own 4500 and a tenth of the raid level's scaling (6030 at 340, the bar's); and the rounding last: above
	 * 100, to the nearest 10 above 300, else to the nearest 5 (Akkha's shadows' 186 to 185, the overlords' 106 to 105,
	 * Kephri's 212 to 210, Akkha's 1066 to 1070).</li>
	 * </ul>
	 * Any other npc keeps {@code base}.
	 */
	static int tombsHitpoints(int base, int npcId, int raidLevel, int pathLevel, int partySize)
	{
		if (!tombsScales(npcId))
		{
			return npcId == NpcID.TOA_PATH_APMEKEN_BABOON_THRALL && base > 0 ? BABOON_THRALL_HP : base;
		}
		final boolean core = TOMBS_CORE_EJECTED.contains(npcId);
		int hp = core ? CORE_EJECTED_HP : base;
		if (hp <= 0)
		{
			return base;
		}
		final int level = Math.max(0, raidLevel);
		hp += hp * ((core ? 1 : 4) * level / 10) / 100;
		final int path = Math.max(0, Math.min(6, pathLevel));
		if (path >= 1 && TOMBS_PATH_SCALED.contains(npcId))
		{
			hp = hp * (100 + 3 + 5 * path) / 100;
		}
		final int party = Math.max(1, Math.min(8, partySize));
		if (party >= 2)
		{
			// Tenths: the 2nd and 3rd add 9 each, the 4th on 6 each.
			final int tenths = 9 * Math.min(2, party - 1) + 6 * Math.max(0, party - 3);
			hp = hp * (10 + tenths) / 10;
		}
		if (hp > 100)
		{
			final int to = hp > 300 ? 10 : 5;
			hp = (hp + to / 2) / to * to;
		}
		return hp;
	}

	/**
	 * Tombs: 2% per five raid levels, additively, so 500 is +200%. Defence level
	 * only - magic does not move, and defence bonuses are armour.
	 *
	 * <p>T-152: no longer what the expected figures use: the raid level scales the defence roll, not the level
	 * ({@link #tombsDefenceRoll}).
	 */
	static int tombs(int base, int raidLevel)
	{
		return base * (100 + 2 * (raidLevel / 5)) / 100;
	}

	/**
	 * T-152: the Tombs monsters whose defence roll the raid level scales: the bosses, Akkha's Shadows, the Obelisk and
	 * the Wardens. Not Kephri's overlord scarabs, and not the rooms' small monsters (baboons, eggs, the swarm), which the
	 * raid level doesn't scale at all (T-153: each dies on its table's hitpoints). From the DPS calc's facts
	 * (weirdgloop/osrs-dps-calc, read 2026-11-02: "toa multiplies rolled values, not stats, except for hp"; its ToA ids,
	 * less its KEPHRI_OVERLORD_IDS for the defence roll), by RuneLite's gameval names.
	 */
	private static final Set<Integer> TOMBS_DEFENCE_ROLL = new HashSet<>(Arrays.asList(
		NpcID.AKKHA_SPAWN, NpcID.AKKHA_MELEE, NpcID.AKKHA_RANGE, NpcID.AKKHA_MAGE, NpcID.AKKHA_ENRAGE_SPAWN,
		NpcID.AKKHA_ENRAGE_INITIAL, NpcID.AKKHA_ENRAGE, NpcID.AKKHA_ENRAGE_DUMMY,
		NpcID.AKKHA_SHADOW, NpcID.AKKHA_SHADOW_ENRAGE, NpcID.AKKHA_SHADOW_ENRAGE_DUMMY,
		NpcID.TOA_BABA, NpcID.TOA_BABA_COFFIN, NpcID.TOA_BABA_DIGGING,
		// T-221: and her middle form, as RAID HP takes it: TOA_KEPHRI_BOSS_WEAK isn't on the calculator's list (its data has
		// the Shielded and Aggressive entries, 11719 and 11721, and 11720 resolves by name to Aggressive). Left out, her
		// defence roll there was the table's at any raid level: Eric's 11-11 raid (level 350) read accuracy 0.822 on 11720
		// against 0.570 on 11719 with the same keris, gear and stab, the (250 + 350) / 250 = 2.4 between them.
		NpcID.TOA_KEPHRI_BOSS_SHIELDED, NpcID.TOA_KEPHRI_BOSS_WEAK, NpcID.TOA_KEPHRI_BOSS_ENRAGE,
		NpcID.TOA_ZEBAK, NpcID.TOA_ZEBAK_ENRAGED, NpcID.TOA_ZEBAK_DEAD,
		NpcID.TOA_WARDENS_P1_OBELISK_NPC_INACTIVE, NpcID.TOA_WARDENS_P1_OBELISK_NPC, NpcID.TOA_WARDENS_P2_OBELISK_NPC,
		NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE,
		NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE,
		NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED,
		NpcID.TOA_WARDEN_ELIDINIS_PHASE3, NpcID.TOA_WARDEN_TUMEKEN_PHASE3,
		NpcID.TOA_WARDEN_ELIDINIS_PHASE3_CHARGING, NpcID.TOA_WARDEN_TUMEKEN_PHASE3_CHARGING));

	/** T-152: whether the raid level scales this monster's defence roll ({@link #TOMBS_DEFENCE_ROLL}). */
	static boolean tombsScalesDefence(int npcId)
	{
		return TOMBS_DEFENCE_ROLL.contains(npcId);
	}

	/**
	 * T-152: a Tombs monster's defence roll at a raid level: times (250 + level) / 250, rounded down (2% per 5 levels,
	 * on the roll: the DPS calc's facts), for {@link #tombsScalesDefence}'s monsters; anything else as it is. The level
	 * the roll is made from is the monster's own, less drains.
	 */
	static int tombsDefenceRoll(int defenceRoll, int npcId, int raidLevel)
	{
		if (raidLevel <= 0 || !tombsScalesDefence(npcId))
		{
			return defenceRoll;
		}
		return (int) ((long) defenceRoll * (250 + raidLevel) / 250);
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

package com.pvmlogs;

import net.runelite.api.gameval.NpcID;

/**
 * T-172: the Wardens' phase 2 in its three parts, each with its own actual and expected damage in one unit, kept on the
 * fight's record ({@code Fight.wardensP2}, {@code history.json}; null for any other fight and for records saved before).
 * <ul>
 * <li>{@link #poise}: the player's hits on the warden while her core is in (npc 11753, 11754, 11756, 11757; splat types
 * 53 and 55). Not her hitpoints.</li>
 * <li>{@link #core}: his hits on the core (npc 11771, 11770) at their own value, scored at the hit's own value under the
 * core's rule (melee its max, ranged and magic rolled): no ×5.</li>
 * <li>{@link #passedOn}: the core's damage passed on to her, five times over (OSRS Wiki, Core): her hitpoints, recorded and
 * shown, not scored again, being the core's hits once more.</li>
 * </ul>
 * Which warden is damaged varies by run, so the parts go by npc id, never by her name. The fight's own A and E are poise
 * plus core, never the passed-on.
 */
final class WardensPhase2
{
	/** One part: the damage it dealt, what was expected of it, its variance, and the attacks scored to it. */
	static final class Part
	{
		int damage;
		double expected;
		double variance;
		int attacks;
	}

	Part poise = new Part();
	Part core = new Part();
	int passedOn;

	/** Which part an npc's figures belong to: the poise, the core, or none of phase 2's. */
	enum Kind
	{
		POISE,
		CORE
	}

	/**
	 * The part an npc's figures go to: the warden with her core in is the poise; the core itself, and the warden while
	 * it's out (whom the attacks on the core are scored against), the core. Null for anything else, the passed-on included,
	 * which {@link #passedOn} keeps.
	 */
	static Kind kindOf(int npcId)
	{
		switch (npcId)
		{
			case NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE:
			case NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE:
			case NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE:
			case NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE:
				return Kind.POISE;
			case NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED:
			case NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED:
			case NpcID.TOA_WARDEN_ELIDINIS_CORE:
			case NpcID.TOA_WARDEN_TUMEKEN_CORE:
				return Kind.CORE;
			default:
				return null;
		}
	}

	/** Whether an npc is the core itself (the one the player hits, named "Core" once its tags are stripped). */
	static boolean isCore(int npcId)
	{
		return npcId == NpcID.TOA_WARDEN_ELIDINIS_CORE || npcId == NpcID.TOA_WARDEN_TUMEKEN_CORE;
	}

	Part part(Kind kind)
	{
		return kind == Kind.POISE ? poise : core;
	}
}

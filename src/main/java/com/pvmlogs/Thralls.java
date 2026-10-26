package com.pvmlogs;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.SpotanimID;

/**
 * What a thrall is: its NPC ids and how hard each can hit. Every thrall attacks
 * on a four tick cycle, ignores accuracy and rolls 0 to its tier's max, and its
 * hitsplats carry the same type as the summoner's own, so isMine() cannot tell
 * them apart - see issue #1.
 *
 * <p>The imp ids are a cosmetic override from the Impish whistle (Leagues reward
 * shop, June 2026) and the Deadman ones another; both keep the thrall's stats.
 */
final class Thralls
{
	private static final Map<Integer, Integer> MAX_HIT_BY_ID;
	static
	{
		final Map<Integer, Integer> m = new HashMap<>();
		put(m, 1, NpcID.ARCEUUS_THRALL_GHOST_LESSER, NpcID.ARCEUUS_THRALL_SKELETON_LESSER,
			NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER, NpcID.THRALL_IMP_MAGIC_LESSER, NpcID.THRALL_IMP_RANGED_LESSER,
			NpcID.THRALL_IMP_MELEE_LESSER);
		put(m, 2, NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR, NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR,
			NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR, NpcID.THRALL_IMP_MAGIC_SUPERIOR, NpcID.THRALL_IMP_RANGED_SUPERIOR,
			NpcID.THRALL_IMP_MELEE_SUPERIOR);
		put(m, 3, NpcID.ARCEUUS_THRALL_GHOST_GREATER, NpcID.ARCEUUS_THRALL_SKELETON_GREATER,
			NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER, NpcID.THRALL_IMP_MAGIC_GREATER, NpcID.THRALL_IMP_RANGED_GREATER,
			NpcID.THRALL_IMP_MELEE_GREATER, NpcID.DEADMAN_THRALL_ZOMBIE_GREATER_ZUK,
			NpcID.DEADMAN_THRALL_SKELETAL_GREATER_PRINCESS, NpcID.DEADMAN_THRALL_GHOSTLY_GREATER_WISP);
		MAX_HIT_BY_ID = Collections.unmodifiableMap(m);
	}

	// The projectiles a thrall fires, for when one arrives without naming its source.
	static final Set<Integer> PROJECTILES = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
		SpotanimID.THRALL_RANGED_TRAVEL, SpotanimID.THRALL_MAGIC_TRAVEL,
		SpotanimID.LEAGUES_6_THRALL_IMP_MAGIC_ATTACK_VFX_PROJANIM,
		SpotanimID.LEAGUES_6_THRALL_IMP_RANGED_ATTACK_VFX_PROJANIM)));

	private Thralls()
	{
	}

	private static void put(Map<Integer, Integer> m, int maxHit, int... ids)
	{
		for (int id : ids)
		{
			m.put(id, maxHit);
		}
	}

	// The thralls that attack with a projectile: ghosts (magic) and skeletons (ranged), in every skin. Zombies hit in
	// melee and fire nothing.
	private static final Set<Integer> FIRES_PROJECTILE = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
		NpcID.ARCEUUS_THRALL_GHOST_LESSER, NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR, NpcID.ARCEUUS_THRALL_GHOST_GREATER,
		NpcID.ARCEUUS_THRALL_SKELETON_LESSER, NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR,
		NpcID.ARCEUUS_THRALL_SKELETON_GREATER, NpcID.THRALL_IMP_MAGIC_LESSER, NpcID.THRALL_IMP_MAGIC_SUPERIOR,
		NpcID.THRALL_IMP_MAGIC_GREATER, NpcID.THRALL_IMP_RANGED_LESSER, NpcID.THRALL_IMP_RANGED_SUPERIOR,
		NpcID.THRALL_IMP_RANGED_GREATER, NpcID.DEADMAN_THRALL_SKELETAL_GREATER_PRINCESS,
		NpcID.DEADMAN_THRALL_GHOSTLY_GREATER_WISP)));

	/** Whether every attack this thrall makes is a projectile, so a splat with none is not its own. */
	static boolean firesProjectile(int npcId)
	{
		return FIRES_PROJECTILE.contains(npcId);
	}

	static boolean isThrall(int npcId)
	{
		return MAX_HIT_BY_ID.containsKey(npcId);
	}

	/** The most one of its hitsplats can carry, or -1 if this is not a thrall. */
	static int maxHit(int npcId)
	{
		return MAX_HIT_BY_ID.getOrDefault(npcId, -1);
	}
}

package com.pvmlogs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * T-163, part 2: an add of the open boss fight's boss that no list knows. With
 * no group, every one of Venenatis's spiderlings hit ended her fight and opened
 * one of its own (Eric's log of 2026-11-03, from [14678]): 31 fights of her and
 * 46 of spiderlings over 7 kills. Part 1 grouped her; this keeps the boss's fight
 * open through an add the groups miss, as {@link EncounterGroup#addKeepsBossFight}
 * does for one they list.
 *
 * <p>While a boss's fight is open (outside a raid: a boss by the hiscores' list,
 * {@link EncounterGroup#ALSO_BOSSES}, a group or the boss rules), the boss alive
 * and in view, an NPC the player attacks is a provisional add when it is all of:
 * <ul>
 * <li>not a boss itself, nor the player's own reanimated creature ({@link #reanimated});</li>
 * <li>at most {@link #HP_SHARE_PERCENT}% of the boss's full hitpoints, both known;</li>
 * <li>named after the boss, or seen spawning after the fight opened within
 * {@link #RANGE} tiles of the boss;</li>
 * <li>attacked within {@link #RELEASE_TICKS} ticks of the player's last attack
 * on the boss.</li>
 * </ul>
 * Past that last, it is {@link Verdict#RELEASED}: the boss fight ends as it does
 * today on a switch, at the first attack on the add since the boss's last.
 *
 * <p>Spawns are recorded from {@code NpcSpawned} only while such a fight is open,
 * and judged only when one is attacked: nothing runs over every NPC on a tick.
 * {@code NpcSpawned} also fires for an NPC walking into view, which this can't
 * tell from a spawn (a guess that the range keeps most of those out).
 *
 * <p>Pure: the plugin feeds it what the game shows.
 */
final class UnlistedAdds
{
	/** The add's full hitpoints, at most this share of the boss's. */
	static final int HP_SHARE_PERCENT = 10;
	/** How near the boss it spawned, in tiles. */
	static final int RANGE = 15;
	/** How long after the player's last attack on the boss an add is still one. */
	static final int RELEASE_TICKS = 50;
	/** A cap on the spawns kept for one fight, so nothing grows without bound in a crowded place. */
	static final int MAX_SPAWNS = 256;

	enum Verdict
	{
		/** Not an add of the boss: the switch ends the boss fight, as it always has. */
		NOT_AN_ADD,
		/** A provisional add: the boss fight stays open, the attack on it unscored. */
		ADD,
		/** It would be one, but the boss went unattacked too long: the boss fight ends, at the switch. */
		RELEASED
	}

	/** Where and when an NPC spawned during the fight. */
	static final class Spawn
	{
		final int tick;
		final int distance;

		Spawn(int tick, int distance)
		{
			this.tick = tick;
			this.distance = distance;
		}
	}

	private final Map<Integer, Spawn> spawns = new HashMap<>();
	private final Set<Integer> logged = new HashSet<>();
	private final Set<Integer> accepted = new HashSet<>();
	private int bossAttackTick = Integer.MIN_VALUE;
	private long switchMillis = -1;
	private int switchTick = Integer.MIN_VALUE;

	/**
	 * The verdict on an NPC the player attacks while a fight is open.
	 *
	 * @param bossFightOpen a boss's fight is open, its boss alive and in view
	 * @param addIsBoss the NPC is a boss itself (or grouped, or in the boss rules)
	 * @param addHp its full hitpoints, or 0 or less when unknown
	 * @param bossHp the boss's full hitpoints, or 0 or less when unknown
	 * @param spawn where it spawned during the fight, or null
	 */
	static Verdict judge(boolean bossFightOpen, boolean addIsBoss, String addName, String bossName, int addHp, int bossHp,
		Spawn spawn, int tick, int lastBossAttackTick)
	{
		if (!bossFightOpen || addIsBoss || reanimated(addName) || addHp <= 0 || bossHp <= 0 || addHp * 100L > bossHp * (long) HP_SHARE_PERCENT)
		{
			return Verdict.NOT_AN_ADD;
		}
		if (!byName(addName, bossName) && !nearSpawn(spawn))
		{
			return Verdict.NOT_AN_ADD;
		}
		return lastBossAttackTick != Integer.MIN_VALUE && tick - lastBossAttackTick <= RELEASE_TICKS
			? Verdict.ADD : Verdict.RELEASED;
	}

	/** Whether the add's name holds the boss's, case aside: "Obor's ..." of Obor. */
	static boolean byName(String addName, String bossName)
	{
		final String add = normal(addName);
		final String boss = normal(bossName);
		return boss.length() >= 3 && !add.equals(boss) && add.contains(boss);
	}

	/**
	 * A creature the player raised from an ensouled head: "spawned by casting the spell Expert Reanimation on an ensouled
	 * hellhound head" (wiki, Reanimated hellhound), every one named "Reanimated ...". Cerberus drops the heads, and Eric
	 * raises them during the next kill (his logs of 2026-11-02 and 11-03: 16 Reanimated hellhound fights, 3 of them seen
	 * first after Cerberus's fight opened). Small and spawned beside the boss, but the player's own monster, not an add:
	 * its fight stays the monster's it always was.
	 */
	static boolean reanimated(String addName)
	{
		return normal(addName).startsWith("reanimated ");
	}

	static boolean nearSpawn(Spawn spawn)
	{
		return spawn != null && spawn.distance >= 0 && spawn.distance <= RANGE;
	}

	private static String normal(String name)
	{
		return name == null ? "" : name.replaceAll("<[^>]*>", "").replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
	}

	/** An NPC spawned during the open boss fight, {@code distance} tiles from the boss (negative: unknown). */
	void spawned(int npcIndex, int tick, int distance)
	{
		if (spawns.size() < MAX_SPAWNS || spawns.containsKey(npcIndex))
		{
			spawns.put(npcIndex, new Spawn(tick, distance));
		}
	}

	Spawn spawnOf(int npcIndex)
	{
		return spawns.get(npcIndex);
	}

	int spawnsKept()
	{
		return spawns.size();
	}

	/** The player attacked the boss: what the release counts from. */
	void bossAttacked(int tick)
	{
		bossAttackTick = Math.max(bossAttackTick, tick);
		switchMillis = -1;
		switchTick = Integer.MIN_VALUE;
	}

	int bossAttackTick()
	{
		return bossAttackTick;
	}

	/**
	 * The player attacked an add the verdict accepted. The first since the boss's
	 * last attack is the switch, where a release ends the boss's fight. Whether
	 * this add's line is still to be written, once a fight per add id.
	 */
	boolean addAttacked(int npcIndex, int npcId, int tick, long millis)
	{
		accepted.add(npcIndex);
		if (switchMillis < 0)
		{
			switchMillis = millis;
			switchTick = tick;
		}
		return logged.add(npcId);
	}

	/** The attack an add released at was the switch, if no accepted one came first: then it is the switch. */
	void releasedAt(int tick, long millis)
	{
		if (switchMillis < 0)
		{
			switchMillis = millis;
			switchTick = tick;
		}
	}

	/** Whether this index was taken as a provisional add in the open fight. */
	boolean isAccepted(int npcIndex)
	{
		return accepted.contains(npcIndex);
	}

	/** The time the boss's fight ends at on a release: the switch, as it would have without the rule. */
	long switchMillis()
	{
		return switchMillis;
	}

	int switchTick()
	{
		return switchTick;
	}

	/** A fight opened or ended: nothing carries to the next. */
	void reset(int openTick)
	{
		spawns.clear();
		logged.clear();
		accepted.clear();
		bossAttackTick = openTick;
		switchMillis = -1;
		switchTick = Integer.MIN_VALUE;
	}

	/** The log line, once a fight per add id. */
	static String line(String addName, int addId, String bossName, boolean byName, Spawn spawn, int addHp, int bossHp)
	{
		final StringBuilder b = new StringBuilder(String.format("UNLISTED ADD %s (npc %d) of %s: ", addName, addId, bossName));
		if (byName)
		{
			b.append("name");
			if (nearSpawn(spawn))
			{
				b.append(", ");
			}
		}
		if (nearSpawn(spawn))
		{
			b.append(String.format("spawned on %d, %d tiles away", spawn.tick, spawn.distance));
		}
		return b.append(String.format(", hp %d of %d", addHp, bossHp)).toString();
	}
}

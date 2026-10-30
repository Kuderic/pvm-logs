package com.pvmlogs;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * Monsters that aren't bosses, fought back to back, are one room (T-089): a
 * slayer task's run of Abyssal demons is one running fight on the overlay and
 * one row in History, rather than a fight per demon.
 *
 * <p>Eric's Hallowfell trip of 2026-10-29 is why. In multi-combat the axe hits
 * the demons around the target, and most of them died to that splash while not
 * the target: 53 fights on demons by [14471], ended 42 times by the next one
 * being hit, a median of one swing each, 32 drops each closing a "room" of one
 * fight, and every demon switched to started at full hitpoints whatever the
 * splash had already taken off it.
 *
 * <p>The rules, which the plugin asks here so a test can ask them too:
 * <ul>
 * <li>A boss keeps a fight per kill: anything on the hiscores' boss list, in
 * {@code boss-rules.json}, in an {@link EncounterGroup} group, the names
 * {@link EncounterGroup#isBossByName} adds, or fought in a raid. The rest is
 * a monster.</li>
 * <li>A monster's kind is its name, so every Abyssal demon is one kind whatever
 * its id.</li>
 * <li>The room ends at its last attack: when nothing of its kind has been
 * attacked for {@link #PAUSE_MILLIS}, when a boss is fought, when the player
 * leaves the area ({@link #leftArea}), and on a logout or hop. Another kind of
 * monster sets it aside rather than ending it ({@link #setsRunAside}): fought
 * for longer than the pause, the run has ended at its last attack all the
 * same; fought for a swing, the run goes on.</li>
 * </ul>
 */
final class MonsterRooms
{
	/**
	 * How long a run of monsters waits for its next attack (T-089): the
	 * overlay's hold, a minute. A respawn is inside it - the longest wait in
	 * Eric's demon trip, after the three demons around him had died, was 70 ticks
	 * (42 s) between attacks - and a bank trip is well past it.
	 */
	static final long PAUSE_MILLIS = 60_000;
	/**
	 * More tiles than this in one tick, or another plane, is the player leaving
	 * rather than walking (T-089): running covers two a tick, and a teleport, a
	 * death or a ladder moves the player much further at once.
	 */
	static final int LEAVE_TILES = 10;

	// The monsters of the room's kind the player's own damage reached, by npc index, until each dies or despawns: a death
	// among them is the room's kill, the ones a splash killed included. Not damage dealt by anyone else, nor a thrall's.
	private final Set<Integer> hitByMe = new HashSet<>();

	/**
	 * Whether this NPC is a monster rather than a boss (T-089).
	 *
	 * @param name   its name, tags stripped
	 * @param inRaid whether it is fought in a raid, where every room is the raid's
	 * @param rules  the boss rules in use, or null
	 */
	static boolean isMonster(String name, int npcId, boolean inRaid, BossRules rules)
	{
		return !inRaid && EncounterGroup.of(npcId) == null && (rules == null || rules.forNpc(npcId) == null)
			&& !PvmPerformancePlugin.isBossName(name) && !EncounterGroup.isBossByName(name);
	}

	/** Whether {@code name} is the kind this monster fight is on. */
	static boolean sameKind(Fight fight, String name)
	{
		return fight != null && fight.isMonsterRoom() && name != null
			&& kind(fight.getTargetName()).equals(kind(name));
	}

	private static String kind(String name)
	{
		return name.trim().toLowerCase();
	}

	/**
	 * Whether a fight on this NPC continues the run {@code fight} is in: the
	 * room is open and the NPC is a monster of the same kind.
	 */
	static boolean switchesWithin(Encounter room, Fight fight, String name, boolean monster)
	{
		return monster && room != null && !room.isEnded() && sameKind(fight, name);
	}

	/**
	 * Whether a fight opening on {@code next} sets the open run aside rather than
	 * ending it: the run has been fought, and {@code next} is a monster of
	 * another kind. The run goes on if its kind is fought again before its pause
	 * runs out ({@link #resumesRun}); a boss ends it. Eric's demon trip has the
	 * case: one swing at a Reanimated abyssal at [16570], in the middle of the
	 * demons, would otherwise have split the run in three.
	 */
	static boolean setsRunAside(Encounter run, Fight next)
	{
		return run != null && !run.isEnded() && run.isMonsterRoom() && run.isEngaged() && next.isMonsterRoom()
			&& !sameKind(run.getFights().get(0), next.getTargetName());
	}

	/** Whether a fight opening on {@code next} takes up the run set aside: a monster of its kind, before it ended. */
	static boolean resumesRun(Encounter run, Fight next)
	{
		return run != null && !run.isEnded() && next.isMonsterRoom() && sameKind(run.getFights().get(0),
			next.getTargetName());
	}

	/** Whether the run's pause has run out: nothing attacked in it for {@link #PAUSE_MILLIS}. */
	static boolean pauseEnds(Encounter room, long now)
	{
		return room != null && !room.isEnded() && room.isMonsterRoom() && room.getLastAttackMillis() > 0
			&& now - room.getLastAttackMillis() >= PAUSE_MILLIS;
	}

	/** Whether the player moved from {@code before} to {@code after} by leaving rather than walking. */
	static boolean leftArea(WorldPoint before, WorldPoint after)
	{
		return before != null && after != null
			&& (before.getPlane() != after.getPlane() || before.distanceTo2D(after) > LEAVE_TILES);
	}

	/**
	 * Whether an overdue tick in a run of monsters is the player moving to the
	 * next one, which is not lost time (T-089): before the fight's first attack
	 * (the next monster targeted after a kill, being walked to), or with the
	 * player already aimed at another monster of the kind. Eating is still
	 * eating. Before anything in the room has been attacked the tick counts for
	 * nothing anyway, and says so as it always has.
	 *
	 * @param aimedName  the name of the NPC the player is aimed at, or null
	 * @param aimedIndex its index, or -1
	 */
	static boolean switchingTick(Fight fight, boolean roomEngaged, boolean eating, String aimedName, int aimedIndex)
	{
		if (fight == null || !fight.isMonsterRoom() || !roomEngaged || eating)
		{
			return false;
		}
		return !fight.isAttacking()
			|| aimedIndex >= 0 && aimedIndex != fight.getTargetIndex() && sameKind(fight, aimedName);
	}

	/** The player's damage reached this monster of the room's kind. */
	void hit(int index)
	{
		hitByMe.add(index);
	}

	/** Whether this monster's death is the room's kill, taken once: its loot and its despawn both come. */
	boolean takeKill(int index)
	{
		return hitByMe.remove(index);
	}

	/** The index is free again: the server hands it to the next spawn. */
	void forget(int index)
	{
		hitByMe.remove(index);
	}

	void clear()
	{
		hitByMe.clear();
	}
}

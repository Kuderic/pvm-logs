package com.pvmlogs;

import net.runelite.api.GameState;

/**
 * T-088: the fought target gone from view alive. Its fight is held open,
 * not finished: a Giant Mole that burrows is still the kill being fought, and
 * one left behind on a trip to the bank is still there to come back to.
 *
 * <p>Two ways to leave, told apart by what the client shows when the target
 * despawns:
 * <ul>
 * <li><b>The player left</b> (a trip away): the client was loading a new area,
 * or was not logged in. The fight pauses: the time away is neither combat time
 * nor part of the kill's time.</li>
 * <li><b>The target left</b> (a burrow): the player stayed where they were.
 * The fight goes on. Nothing counts while it is gone, since no attack is
 * possible; once it is back, the run to it is excused by its length (T-097,
 * {@link Chase}).</li>
 * </ul>
 *
 * <p>Pure: the plugin hands it what the client shows.
 */
final class BossAway
{
	/**
	 * Ticks a held fight waits for its target before it ends as left, at the
	 * tick it was last seen: 1,000 ticks, ten minutes. A proposal: Eric's bank
	 * trip from the Giant Mole took 101 ticks (2026-10-29, [11640]-[11741]), and
	 * a restock can take several times that.
	 */
	static final int TIMEOUT_TICKS = 1000;

	final int npcId;
	final int npcIndex;
	final String name;
	final int world;
	final int tick;
	final long millis;
	/** The player left (a trip), rather than the target leaving the player's view (a burrow). */
	final boolean playerLeft;
	/** The target's last animation before it went, for the log. */
	final int lastAnim;

	BossAway(int npcId, int npcIndex, String name, int world, int tick, long millis, boolean playerLeft, int lastAnim)
	{
		this.npcId = npcId;
		this.npcIndex = npcIndex;
		this.name = name;
		this.world = world;
		this.tick = tick;
		this.millis = millis;
		this.playerLeft = playerLeft;
		this.lastAnim = lastAnim;
	}

	/**
	 * Whether a target despawning alive on {@code despawnTick} went because the
	 * player left: the client not logged in at the despawn, or a loading screen
	 * on that tick or the one before. Otherwise the player stayed and the target
	 * left them. A guess from how the client behaves, not yet seen in a log: the
	 * OUT OF VIEW line shows what it was decided on.
	 */
	static boolean playerLeft(GameState stateAtDespawn, int lastLoadingTick, int despawnTick)
	{
		return stateAtDespawn != GameState.LOGGED_IN
			|| lastLoadingTick != Integer.MIN_VALUE && despawnTick - lastLoadingTick >= 0 && despawnTick - lastLoadingTick <= 1;
	}

	/**
	 * Whether an npc seen now is the target come back: the same npc id on the
	 * same world, and either the same index (after a burrow) or, for a boss of
	 * which a world has only one, any index (after a trip, where the index
	 * changes). A world hop is always a different boss.
	 */
	boolean isBack(int npcId, int npcIndex, int world, boolean onePerWorld)
	{
		return npcId == this.npcId && world == this.world && (npcIndex == this.npcIndex || onePerWorld);
	}

	/** Whether it has been gone long enough to be left: {@link #TIMEOUT_TICKS} since it was last seen. */
	boolean timedOut(int now)
	{
		return now - tick > TIMEOUT_TICKS;
	}

	/**
	 * Whether the overhead health bar, read the first time it shows after a
	 * trip, says this is a fresh one: it shows more of the bar than the fight
	 * says is left, by more than the bar's own rounding and a tenth for luck.
	 * Another player's kill and her respawn, or a reset, while the player was
	 * away. Unknown readings say nothing.
	 *
	 * @param ratio     the bar's filled segments, -1 when not shown
	 * @param scale     its segments, 0 or less when not shown
	 * @param remaining the hitpoints the fight counts as left, -1 when unknown
	 * @param maxHp     the target's hitpoints, 0 or less when unknown
	 */
	static boolean freshOnReturn(int ratio, int scale, int remaining, int maxHp)
	{
		if (ratio < 0 || scale <= 0 || remaining < 0 || maxHp <= 0)
		{
			return false;
		}
		return (double) ratio / scale > (double) remaining / maxHp + 1.0 / scale + 0.1;
	}

	/**
	 * The idle ticks the run back to a boss is allowed (T-097): running covers
	 * 2 tiles a tick, so ⌈max(0, distance − reach) / 2⌉, plus the margin.
	 *
	 * @param distance tiles from the player to the nearest tile of the boss
	 * @param reach    the attack's reach in tiles
	 */
	static int chaseAllowance(int distance, int reach, int margin)
	{
		return (Math.max(0, distance - reach) + 1) / 2 + Math.max(0, margin);
	}

	/** One run back to a boss come back into view (T-097): its allowance, and the idle ticks it has excused and lost. */
	static final class Chase
	{
		final int tick;
		final int distance;
		final int reach;
		final int allowance;
		int excused;
		int lost;

		Chase(int tick, int distance, int reach, int allowance)
		{
			this.tick = tick;
			this.distance = distance;
			this.reach = reach;
			this.allowance = allowance;
		}

		/** An idle tick on the way: excused while the allowance lasts. */
		boolean excuse()
		{
			if (excused < allowance)
			{
				excused++;
				return true;
			}
			lost++;
			return false;
		}
	}

	/** What the OUT OF VIEW line says about why. */
	String why(GameState stateAtDespawn, int lastLoadingTick)
	{
		return playerLeft
			? String.format("the player left (game state %s, last loading screen %s): a trip, the fight pauses", stateAtDespawn,
				lastLoadingTick == Integer.MIN_VALUE ? "none" : "on " + lastLoadingTick)
			: String.format("the target left the player's view (its last animation %d, no loading screen): the fight goes on",
				lastAnim);
	}
}

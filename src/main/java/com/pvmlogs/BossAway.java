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
	/**
	 * T-164: the same window by the clock, ten minutes: a hold kept across a logout (when the game's ticks may not run)
	 * still runs out, so a boss left overnight is never resumed in the morning.
	 */
	static final long TIMEOUT_MILLIS = TIMEOUT_TICKS * 600L;

	/** T-164: why a fight is held, for the log and for what ends it. */
	enum Cause
	{
		/** T-088: its target despawned alive; {@link #playerLeft} says whether on a trip or a burrow. */
		OUT_OF_VIEW,
		/** T-164: the player died at it, outside a raid: held for the return, the death marked on the fight. */
		PLAYER_DIED,
		/** T-164: 100 ticks with no damage either way and nothing aimed at it: paused from its last activity. */
		IDLE,
		/** T-164: the player logged out mid-fight: paused from its last activity, the count kept for the login. */
		LOGGED_OUT
	}

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
	/** T-164: why it's held. */
	final Cause cause;
	/**
	 * T-164: the player died while it was held, or that's why it is: if the hold runs out, or another target is fought,
	 * the room ends as the death ended it before (T-100), not a kill.
	 */
	final boolean playerDied;
	/**
	 * T-164: the boss stood in an instance when it was left. Once the player has left one (a trip, a death, a logout) it
	 * is gone, and a boss found on return is a fresh one, so such a hold isn't resumed. Cerberus's 23-damage fight at
	 * [1540] of Eric's 11-03 07:00 log, a teleport out at [1548], and a kill of 606 from [1867] on the same index: the
	 * overhead bar can't tell a fresh Cerberus from one 23 down. A guess that her lair is an instance: the HOLD line
	 * logs it.
	 */
	boolean instanced;

	BossAway(int npcId, int npcIndex, String name, int world, int tick, long millis, boolean playerLeft, int lastAnim)
	{
		this(npcId, npcIndex, name, world, tick, millis, playerLeft, lastAnim, Cause.OUT_OF_VIEW, false);
	}

	BossAway(int npcId, int npcIndex, String name, int world, int tick, long millis, boolean playerLeft, int lastAnim,
		Cause cause, boolean playerDied)
	{
		this.npcId = npcId;
		this.npcIndex = npcIndex;
		this.name = name;
		this.world = world;
		this.tick = tick;
		this.millis = millis;
		this.playerLeft = playerLeft;
		this.lastAnim = lastAnim;
		this.cause = cause;
		this.playerDied = playerDied;
	}

	/**
	 * T-164: this hold, the player having died on {@code tick}/{@code millis}: a pause already (a trip, an idle pause)
	 * still counts from where it began; a burrow, whose time was the fight's, is paused from the death.
	 */
	BossAway withPlayerDied(int tick, long millis)
	{
		final BossAway died = playerLeft ? new BossAway(npcId, npcIndex, name, world, this.tick, this.millis, true, lastAnim,
			cause, true) : new BossAway(npcId, npcIndex, name, world, tick, millis, true, lastAnim, cause, true);
		died.instanced = instanced;
		return died;
	}

	/**
	 * T-164: this hold turned into a pause from {@code tick}/{@code millis}, the fight's last activity, for {@code why}:
	 * the time from then is left out of the fight, as a trip's is. A hold with a death in it keeps it.
	 */
	BossAway pausedFrom(int tick, long millis, Cause why)
	{
		final BossAway paused = new BossAway(npcId, npcIndex, name, world, Math.min(this.tick, tick),
			Math.min(this.millis, millis), true, lastAnim, why, playerDied);
		paused.instanced = instanced;
		return paused;
	}

	/** T-164: whether this hold can still be resumed: not once the player has left the instance its boss stood in. */
	boolean resumableAfterLeaving()
	{
		return !(instanced && playerLeft);
	}

	/**
	 * T-164: whether a fight on a boss can be held for the player's return rather than ended - after their death, an
	 * idle pause, or a logout. Outside a raid (a raid's room goes on by itself, T-100, T-155), never a run of monsters
	 * (T-089) or an add (the boss's room, not the add's, is what's resumed), and only with the boss alive and hurt:
	 * nothing counted on it is nothing to carry on.
	 */
	static boolean resumable(boolean inRaid, boolean monsterRoom, boolean add, boolean targetDead, boolean hurt)
	{
		return !inRaid && !monsterRoom && !add && !targetDead && hurt;
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

	/** T-164: as {@link #timedOut(int)}, or {@link #TIMEOUT_MILLIS} by the clock, whichever comes first. */
	boolean timedOut(int now, long nowMillis)
	{
		return timedOut(now) || nowMillis - millis > TIMEOUT_MILLIS;
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
		if (cause != Cause.OUT_OF_VIEW)
		{
			return describe();
		}
		return playerLeft
			? String.format("the player left (game state %s, last loading screen %s): a trip, the fight pauses", stateAtDespawn,
				lastLoadingTick == Integer.MIN_VALUE ? "none" : "on " + lastLoadingTick)
			: String.format("the target left the player's view (its last animation %d, no loading screen): the fight goes on",
				lastAnim);
	}

	/** T-164: the hold, for the HOLD and RESUME lines. */
	String describe()
	{
		switch (cause)
		{
			case PLAYER_DIED:
				return "the player died: held for the return, the death marked";
			case IDLE:
				return "idle with nothing aimed at it: paused from its last activity";
			case LOGGED_OUT:
				return "the player logged out: paused from its last activity, its count kept";
			default:
				return (playerLeft ? "a trip" : "out of the player's view") + (playerDied ? ", the player died during it" : "");
		}
	}
}

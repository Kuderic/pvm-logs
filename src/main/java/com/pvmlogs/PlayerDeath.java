package com.pvmlogs;

/**
 * T-100: the player's own death. Eric's Araxxor log of 2026-10-29 21:40: he
 * died at the boss, the respawn's loading screen was read as a trip (T-088),
 * and his next attempt's fresh Araxxor joined the room still open, so a failed
 * attempt and a kill were saved as one kill.
 *
 * <p>Two signals, whichever comes first: RuneLite's {@code ActorDeath} for the
 * local player, as RuneLite's own Death Indicator plugin uses it, or the
 * hitpoints reaching 0. Neither has been seen in a log yet: the
 * {@code PLAYER DIED} line names the signal and the tick, so the next death
 * shows which comes first and when.
 *
 * <p>Pure: the plugin hands it what the client shows.
 */
final class PlayerDeath
{
	/** What a death does to the room open. */
	enum Outcome
	{
		/** A boss's room ends there, without its kill; the next attempt opens a new one. */
		ROOM_ENDS,
		/** A run of monsters (T-089) ends there, at its last attack, its kills kept. */
		RUN_ENDS,
		/**
		 * In a raid the room goes on, since the team can still kill the boss: the
		 * death is marked and the player's ticks count as nothing until they
		 * attack again. A guess: no raid log yet.
		 */
		RAID_GOES_ON,
		/** Nothing open: only the line. */
		NO_ROOM
	}

	/** What a death does, in a raid or not, to the room open now ({@code room} null for none). */
	static Outcome outcome(boolean inRaid, Encounter room)
	{
		if (inRaid)
		{
			return Outcome.RAID_GOES_ON;
		}
		if (room == null || room.isEnded())
		{
			return Outcome.NO_ROOM;
		}
		return room.isMonsterRoom() ? Outcome.RUN_ENDS : Outcome.ROOM_ENDS;
	}

	/**
	 * Whether a target despawning alive is held for the player's return
	 * (T-088): never while the player is dead. The loading screen after a death
	 * is the respawn, not a trip.
	 */
	static boolean holdsOnDespawn(boolean playerDead)
	{
		return !playerDead;
	}

	private boolean dead;
	private int tick = Integer.MIN_VALUE;

	/**
	 * A death signal on {@code tick}: true the first time in this life, so the
	 * two signals act once between them.
	 */
	boolean died(int tick)
	{
		if (dead)
		{
			return false;
		}
		dead = true;
		this.tick = tick;
		return true;
	}

	/**
	 * The player's hitpoints as they change: 0 is a death signal (true the
	 * first time, as {@link #died}); above 0 after one is the respawn, a new life.
	 */
	boolean hitpoints(int level, int tick)
	{
		if (level <= 0)
		{
			return died(tick);
		}
		dead = false;
		return false;
	}

	boolean isDead()
	{
		return dead;
	}

	/** The tick of this life's death; {@code Integer.MIN_VALUE} before one. */
	int tick()
	{
		return tick;
	}

	/** A logout or hop: the next account's first reading starts a life. */
	void clear()
	{
		dead = false;
		tick = Integer.MIN_VALUE;
	}
}

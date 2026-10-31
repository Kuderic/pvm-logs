package com.pvmlogs;

/**
 * T-100: the player's own death. Eric's Araxxor log of 2026-10-29 21:40: he
 * died at the boss, the respawn's loading screen was read as a trip (T-088),
 * and his next attempt's fresh Araxxor joined the room still open, so a failed
 * attempt and a kill were saved as one kill.
 *
 * <p>Two signals, whichever comes first: RuneLite's {@code ActorDeath} for the
 * local player, as RuneLite's own Death Indicator plugin uses it, or the
 * hitpoints reaching 0. The {@code PLAYER DIED} line names the signal and the
 * tick: ActorDeath came first, on the killing hit's tick, in every death logged
 * so far (T-100's 22:32 log; three at Vardorvis in the 22:28 log of 2026-10-30).
 * The respawn, hitpoints above 0 again, is logged as {@code PLAYER RESPAWNED}
 * (T-127).
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
		NO_ROOM,
		/**
		 * T-127: the fight's target died on the same tick (the game shows an npc
		 * dead on its killing hit's tick): the kill stands, and the fight ends as
		 * the target's death ends it, the player marked as having died.
		 */
		KILL_STANDS
	}

	/** What a death does, in a raid or not, to the room open now ({@code room} null for none). */
	static Outcome outcome(boolean inRaid, Encounter room)
	{
		return outcome(inRaid, room, false);
	}

	/** As above, with whether the open fight's target is already dead (T-127): then the kill stands. */
	static Outcome outcome(boolean inRaid, Encounter room, boolean targetDead)
	{
		if (inRaid)
		{
			return Outcome.RAID_GOES_ON;
		}
		if (room == null || room.isEnded())
		{
			return Outcome.NO_ROOM;
		}
		if (targetDead && !room.isMonsterRoom())
		{
			return Outcome.KILL_STANDS;
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
	 * first time, as {@link #died}); above 0 after one is the respawn, a new life
	 * - but only on a later tick than the death (T-127): a reading still above 0
	 * on the death tick itself, if the client sends one after ActorDeath, is the
	 * old life's, not a respawn. A guess at the order: nothing in a log yet shows
	 * the hitpoints after a death.
	 */
	boolean hitpoints(int level, int tick)
	{
		if (level <= 0)
		{
			return died(tick);
		}
		if (dead && tick > this.tick)
		{
			dead = false;
		}
		return false;
	}

	/**
	 * T-127: whether a fight may open now. Not from the death until the respawn:
	 * the swing thrown on the death tick, its XP on that tick and its splat the
	 * next, opened a fresh fight on the same Vardorvis after the death had closed
	 * the last one (Eric's 22:28 log, [20621]).
	 */
	boolean opensFights(int now)
	{
		return !dead || now - tick > STUCK_TICKS;
	}

	/**
	 * A safety cap, not a measurement (T-127): past this many ticks after a
	 * death, fights open again even with no respawn seen, so a missed hitpoints
	 * reading can't keep them shut. The respawn's reading does come: in the 22:28
	 * log each of three deaths was taken as a new one, which needs the one before
	 * cleared by it. The respawn itself took about 7 ticks there ([20621] to the
	 * despawn on its loading screen at [20628]).
	 */
	static final int STUCK_TICKS = 50;

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

package com.pvmlogs;

/**
 * One special aimed at a target's stats, or a reset of them (T-049): what it was,
 * whose, when it was thrown and when it landed, and the stats before and after.
 * Saved with the fight (see {@link DrainRecord}), so plain fields for the history
 * file. Ticks are the game's, relative to nothing: compare them within a session
 * only.
 */
final class DrainEvent
{
	/**
	 * {@link #KIND_DRAIN} (applied, a miss included), {@link #KIND_SKIP} (nothing applied, see {@link #skip}),
	 * {@link #KIND_RESET} or {@link #KIND_REGEN} (levels coming back, one run of them: see {@link #regen}).
	 */
	static final String KIND_DRAIN = "drain";
	static final String KIND_SKIP = "skip";
	static final String KIND_RESET = "reset";
	static final String KIND_REGEN = "regen";

	String kind;
	/** RuneLite's {@code SpecialWeapon} name, e.g. {@code DRAGON_WARHAMMER}; the reason, for a reset; null for a regen. */
	String weapon;
	/** The player's own special, or a party member's. */
	boolean own;
	/**
	 * T-147: a special by a player outside the RuneLite party, seen from their animation and weapon rather than sent by
	 * a special counter. Never the player's own. False on an event saved before.
	 */
	boolean seen;
	/** For a regen, its first step. */
	int thrownTick;
	/** For a regen, its last step. */
	int landedTick;
	/** The splat's damage; for the Tonalztics of ralos, the glaives that landed; for a regen, its steps. */
	int hit;
	boolean landed;
	int defenceBefore;
	int defenceAfter;
	int magicBefore;
	int magicAfter;
	int magicDefenceBefore;
	int magicDefenceAfter;
	/** A floor, or immunity, stopped some or all of it. */
	boolean floored;
	/** Why nothing was applied, for a skip: immune, at the floor, no matching splat, not modelled, ... */
	String skip;

	DrainEvent()
	{
	}

	static DrainEvent drain(String weapon, boolean own, int thrownTick, int landedTick, int hit, boolean landed,
		DrainRule.State before, DrainRule.State after, boolean floored)
	{
		final DrainEvent e = new DrainEvent();
		e.kind = KIND_DRAIN;
		e.weapon = weapon;
		e.own = own;
		e.thrownTick = thrownTick;
		e.landedTick = landedTick;
		e.hit = hit;
		e.landed = landed;
		e.levels(before, after);
		e.floored = floored;
		return e;
	}

	static DrainEvent skip(String weapon, boolean own, int thrownTick, int landedTick, int hit, boolean landed,
		DrainRule.State at, String reason)
	{
		final DrainEvent e = drain(weapon, own, thrownTick, landedTick, hit, landed, at, at, false);
		e.kind = KIND_SKIP;
		e.skip = reason;
		return e;
	}

	static DrainEvent reset(String reason, int tick, DrainRule.State before, DrainRule.State after)
	{
		final DrainEvent e = drain(reason, false, tick, tick, 0, false, before, after, false);
		e.kind = KIND_RESET;
		return e;
	}

	/**
	 * Regeneration since the last drain, one step so far. Later steps with
	 * nothing in between extend it ({@link #extendRegen}), so a fight's events stay
	 * a handful even where a boss restores every few ticks.
	 */
	static DrainEvent regen(int tick, DrainRule.State before, DrainRule.State after)
	{
		final DrainEvent e = drain(null, false, tick, tick, 1, false, before, after, false);
		e.kind = KIND_REGEN;
		return e;
	}

	void extendRegen(int tick, DrainRule.State after)
	{
		landedTick = tick;
		hit++;
		defenceAfter = after.defence;
		magicAfter = after.magic;
		magicDefenceAfter = after.magicDefence;
	}

	boolean isRegen()
	{
		return KIND_REGEN.equals(kind);
	}

	private void levels(DrainRule.State before, DrainRule.State after)
	{
		defenceBefore = before.defence;
		defenceAfter = after.defence;
		magicBefore = before.magic;
		magicAfter = after.magic;
		magicDefenceBefore = before.magicDefence;
		magicDefenceAfter = after.magicDefence;
	}

	boolean isDrain()
	{
		return KIND_DRAIN.equals(kind);
	}

	boolean isSkip()
	{
		return KIND_SKIP.equals(kind);
	}

	boolean isReset()
	{
		return KIND_RESET.equals(kind);
	}
}

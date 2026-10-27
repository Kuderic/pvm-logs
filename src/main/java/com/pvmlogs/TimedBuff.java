package com.pvmlogs;

/**
 * A spell's buff that no varbit carries, kept from its chat messages and its
 * known duration (T-026): Mark of Darkness and Charge. The buff-bar varbits named
 * for them ({@code BUFF_MARK_OF_DARKNESS_DISABLED}, {@code BUFF_CHARGE_SPELL_DISABLED})
 * only say whether the bar shows them.
 *
 * <p>Up from the cast message; refreshed by a recast; down on the fade message.
 * The duration is the fallback for a fade the client never showed: once the
 * expected end is a few ticks past with no message, it is taken as down. A
 * warning message, where the spell has one, corrects the end. Not seen since the
 * plugin started or since the last login, it is assumed down, and says so.
 *
 * <p>Every change returns one line for the debug log ({@code MARK up: ...}).
 * Plain bookkeeping on the client thread, tested on its own.
 */
final class TimedBuff
{
	/** How long past its expected end a buff with no fade message is still believed. */
	static final int GRACE_TICKS = 3;

	private final String name;
	private boolean up;
	private int castTick = -1;
	private int endTick = -1;
	private String detail = "";
	private String why;

	TimedBuff(String name)
	{
		this.name = name;
		this.why = "assumed down: not seen since the plugin started";
	}

	boolean isUp()
	{
		return up;
	}

	int endTick()
	{
		return up ? endTick : -1;
	}

	/** Cast (or recast) on this tick, lasting this many ticks. */
	String cast(int tick, int durationTicks, String detail)
	{
		final boolean refresh = up;
		up = true;
		castTick = tick;
		endTick = tick + durationTicks;
		this.detail = detail == null ? "" : detail;
		why = "cast on tick " + tick;
		return String.format("%s %s: cast on tick %d%s, lasts %d ticks, to tick %d", name,
			refresh ? "refreshed" : "up", tick, this.detail.isEmpty() ? "" : " (" + this.detail + ")", durationTicks,
			endTick);
	}

	/** A warning that it ends in this many ticks: the end it gives beats the estimate. */
	String warned(int tick, int ticksLeft)
	{
		if (!up)
		{
			// Up after all, cast before anything here saw it.
			up = true;
			castTick = -1;
			detail = "";
			why = "warning seen on tick " + tick + " with no cast seen";
			endTick = tick + ticksLeft;
			return String.format("%s up: its end warning on tick %d with no cast seen; ends tick %d", name, tick,
				endTick);
		}
		final int was = endTick;
		endTick = tick + ticksLeft;
		return String.format("%s ending: warning on tick %d, ends tick %d (estimated %d)", name, tick, endTick, was);
	}

	/** The fade message. */
	String faded(int tick)
	{
		final String line = String.format("%s down: faded on tick %d%s", name, tick,
			up && endTick >= 0 ? String.format(" (expected %d)", endTick) : up ? "" : ", already down");
		up = false;
		why = "faded on tick " + tick;
		return line;
	}

	/** Each tick: down once the expected end is well past with no fade seen. Null when nothing changed. */
	String tick(int tick)
	{
		if (!up || endTick < 0 || tick <= endTick + GRACE_TICKS)
		{
			return null;
		}
		up = false;
		why = "expired on tick " + endTick + " by its duration, no fade message seen";
		return String.format("%s down: no fade message %d ticks after its expected end (tick %d), taken as expired",
			name, tick - endTick, endTick);
	}

	/** Forgotten, and assumed down: a login, a hop, the plugin starting. Null when it was down already. */
	String reset(String reason)
	{
		final boolean was = up;
		up = false;
		castTick = -1;
		endTick = -1;
		detail = "";
		why = "assumed down: " + reason;
		return was ? String.format("%s down: %s", name, why) : null;
	}

	/** The state in a few words, for the SETUP line. */
	String describe()
	{
		if (!up)
		{
			return "down (" + why + ")";
		}
		return "UP (" + why + (detail.isEmpty() ? "" : ", " + detail) + ", ends tick " + endTick + ")";
	}

	int castTick()
	{
		return castTick;
	}
}

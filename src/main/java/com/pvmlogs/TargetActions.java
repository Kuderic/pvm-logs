package com.pvmlogs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * What the fight's target did in the last few ticks: the animations it started
 * and the projectiles it sent, for the TAKEN line (T-018 item 3).
 *
 * <p>The game never says which attack a splat on the player came from, or its
 * style. Logging what the target was doing beside each splat gives a later,
 * per-boss task the raw facts to learn which animation and projectile is which
 * style. This is for the debug log only: nothing about boss behaviour reaches the
 * overlay (Jagex's guidelines).
 */
final class TargetActions
{
	/** How far back the TAKEN line looks. A projectile can fly four or five ticks. */
	static final int KEEP_TICKS = 6;

	private static final class Seen
	{
		final int tick;
		// The index of the fight target it was noted for, so the next fight's lines leave the last one's out.
		final int targetIndex;
		final String text;
		final int landsTick;
		// The projectile's identity, or null for an animation.
		final Long key;

		Seen(int tick, int targetIndex, String text, int landsTick, Long key)
		{
			this.tick = tick;
			this.targetIndex = targetIndex;
			this.text = text;
			this.landsTick = landsTick;
			this.key = key;
		}
	}

	private final Deque<Seen> seen = new ArrayDeque<>();
	// A projectile is reported every frame it moves; one entry each, keyed by what identifies it.
	private final Set<Long> projectileKeys = new HashSet<>();

	/** The target (npc index {@code targetIndex}) started an animation. Idle (-1) is not one. */
	void noteAnimation(int tick, int targetIndex, int animation)
	{
		if (animation < 0)
		{
			return;
		}
		add(new Seen(tick, targetIndex, "anim " + animation, Integer.MIN_VALUE, null));
	}

	/**
	 * A projectile from the target, or one aimed at the player from anywhere.
	 * Repeats of the same projectile (same id, start cycle and aim) are dropped.
	 *
	 * @param targetIndex the npc index of the fight's target when it was seen
	 * @param from where it came from, as the line should say it ("" for the target itself)
	 * @param at   what it was aimed at, as the line should say it
	 */
	void noteProjectile(int tick, int targetIndex, int id, int startCycle, String from, String at, int landsTick)
	{
		final long key = ((long) startCycle << 32) ^ ((long) id << 12) ^ (at == null ? 0 : at.hashCode());
		if (!projectileKeys.add(key))
		{
			return;
		}
		final StringBuilder text = new StringBuilder("proj ").append(id);
		if (from != null && !from.isEmpty())
		{
			text.append(" from ").append(from);
		}
		text.append(" at ").append(at);
		add(new Seen(tick, targetIndex, text.toString(), landsTick, key));
	}

	private void add(Seen entry)
	{
		seen.addLast(entry);
		prune(entry.tick);
	}

	// Drops what is too old to mention, and its projectile's key with it. A projectile still flying after that long
	// would be reported again; none flies that far.
	private void prune(int now)
	{
		for (Iterator<Seen> it = seen.iterator(); it.hasNext(); )
		{
			final Seen entry = it.next();
			if (now - entry.tick > KEEP_TICKS)
			{
				if (entry.key != null)
				{
					projectileKeys.remove(entry.key);
				}
				it.remove();
			}
		}
	}

	/**
	 * Everything seen for the target at {@code targetIndex} within {@link #KEEP_TICKS}
	 * before {@code tick}, oldest first, each with its tick relative to this one:
	 * {@code anim 10340 t-2, proj 2520 at me t-3 lands t+0}. Empty when nothing was
	 * seen.
	 */
	String describe(int tick, int targetIndex)
	{
		final StringBuilder out = new StringBuilder();
		for (Seen entry : seen)
		{
			final int ago = tick - entry.tick;
			if (entry.targetIndex != targetIndex || ago < 0 || ago > KEEP_TICKS)
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(entry.text).append(' ').append(relative(entry.tick - tick));
			if (entry.landsTick != Integer.MIN_VALUE)
			{
				out.append(" lands ").append(relative(entry.landsTick - tick));
			}
		}
		return out.toString();
	}

	private static String relative(int delta)
	{
		return delta < 0 ? "t" + delta : "t+" + delta;
	}

	void clear()
	{
		seen.clear();
		projectileKeys.clear();
	}
}

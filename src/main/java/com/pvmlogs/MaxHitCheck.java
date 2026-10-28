package com.pvmlogs;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A max-hit check (T-056), so a missing gear bonus shows up by itself: one of the
 * player's ordinary hits bigger than the max the plugin expected for that attack
 * means the expectation is missing something. The crystal armour the bow of
 * faerdhinen was not crediting showed as a 41 over a 39 max, and as a 99.9% Luck
 * that was not luck.
 *
 * <p>Pairs, per npc and tick, the player's splats with the attack the plugin
 * scored on that tick: a melee blow is scored by its own hitsplat, a projectile
 * when it lands, so the two arrive in either order and are checked at the end of
 * the tick. Only attacks whose max is one splat's are checked: specials and
 * multi-hit weapons are left out ({@code checkable} false). Pure: the plugin
 * feeds it, and the lines go to the logger.
 */
final class MaxHitCheck
{
	private static final class Scored
	{
		final Fight fight;
		final int npcIndex;
		final int max;
		final boolean checkable;
		final Supplier<String> describe;

		Scored(Fight fight, int npcIndex, int max, boolean checkable, Supplier<String> describe)
		{
			this.fight = fight;
			this.npcIndex = npcIndex;
			this.max = max;
			this.checkable = checkable;
			this.describe = describe;
		}
	}

	private static final class Splat
	{
		final int npcIndex;
		final int amount;

		Splat(int npcIndex, int amount)
		{
			this.npcIndex = npcIndex;
			this.amount = amount;
		}
	}

	private final Consumer<String> log;
	private final List<Scored> scored = new ArrayList<>(2);
	private final List<Splat> splats = new ArrayList<>(4);
	private final Map<Fight, Integer> exceeded = new IdentityHashMap<>();
	private int tick = Integer.MIN_VALUE;

	MaxHitCheck(Consumer<String> log)
	{
		this.log = log == null ? line -> { } : log;
	}

	/** One of the player's own splats on an npc (after the thrall's, recoil and delayed specials are set aside). */
	void splat(int npcIndex, int tick, int amount)
	{
		roll(tick);
		splats.add(new Splat(npcIndex, amount));
	}

	/**
	 * An attack scored on this tick against the npc: its expected max, whether that
	 * max is one splat's ({@code checkable}: not a special, not a multi-hit weapon),
	 * and the weapon and gear for the log line, only asked for when a hit exceeds it.
	 */
	void scored(Fight fight, int npcIndex, int tick, int max, boolean checkable, Supplier<String> describe)
	{
		roll(tick);
		scored.add(new Scored(fight, npcIndex, max, checkable, describe));
	}

	/** Checks what the tick brought; called once a tick, and on its own when a later tick's first call arrives. */
	void endTick(int now)
	{
		roll(now + 1);
	}

	// Settles the tick held so far once another begins.
	private void roll(int now)
	{
		if (now == tick)
		{
			return;
		}
		for (Scored s : scored)
		{
			if (!s.checkable || s.max <= 0)
			{
				continue;
			}
			for (Splat splat : splats)
			{
				if (splat.npcIndex == s.npcIndex && splat.amount > s.max)
				{
					exceeded.merge(s.fight, 1, Integer::sum);
					log.accept(String.format("MAX EXCEEDED %d > %d (%s)", splat.amount, s.max,
						s.describe == null ? "gear unknown" : s.describe.get()));
				}
			}
		}
		scored.clear();
		splats.clear();
		tick = now;
	}

	/** Hits above their expected max in this fight so far. */
	int exceeded(Fight fight)
	{
		return fight == null ? 0 : exceeded.getOrDefault(fight, 0);
	}

	/** A fight's count, once it has been read for the last time. */
	void forget(Fight fight)
	{
		exceeded.remove(fight);
	}
}

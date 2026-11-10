package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T-191: a stall the strategy guides teach, excused from uptime once the fight is over. Cerberus's souls come on her
 * 7th, 14th and 28th attacks once she is below 400 hp (OSRS Wiki, Cerberus/Strategies), so players hold her just above
 * 400 until her 7th attack (and kill her before her 14th) or until her 14th (and have until her 28th). Both are excused,
 * without judging which.
 *
 * <p>During the fight it only listens: her hits on the player, which give her attacks, and the player's idle ticks,
 * each with her hp left and the player's max hit then. Nothing here is read until {@link #settle}, as the fight ends:
 * her attack count is boss behaviour, so no live figure may move with it (the Jagex guardrail). Until then an idle tick
 * is lost, as any other.
 *
 * <p>Pure: one fight at a time, the plugin feeding it.
 */
final class StrategyStall
{
	/** One idle tick: the tick it was counted on, the tick it was judged on, her hp left and the player's max hit. */
	static final class Idle
	{
		final int countedTick;
		final int judgedTick;
		final int hpLeft;
		final int maxHit;

		Idle(int countedTick, int judgedTick, int hpLeft, int maxHit)
		{
			this.countedTick = countedTick;
			this.judgedTick = judgedTick;
			this.hpLeft = hpLeft;
			this.maxHit = maxHit;
		}
	}

	/** What the fight's end found: the idle ticks that were the stall, and a line for the debug log. */
	static final class Result
	{
		final List<Idle> excused;
		final int idle;
		final int attacks;
		final String line;

		Result(List<Idle> excused, int idle, int attacks, String line)
		{
			this.excused = excused;
			this.idle = idle;
			this.attacks = attacks;
			this.line = line;
		}
	}

	// A fight is a few minutes: far below these, which only stop a stuck fight growing without end.
	private static final int MAX_EVENTS = 2000;

	private final BossRules.StallRule rule;
	private final List<Integer> attackStarts = new ArrayList<>();
	private final List<Idle> idle = new ArrayList<>();

	StrategyStall(BossRules.StallRule rule)
	{
		this.rule = rule;
	}

	/** Whether the rule can excuse anything: a mark, a band and an attack to wait for. */
	static boolean usable(BossRules.StallRule rule)
	{
		return rule != null && rule.holdAboveHp > 0 && rule.hitsAbove > 0 && rule.beforeAttack > 0;
	}

	String name()
	{
		return rule.name == null ? "stall (strategy)" : rule.name;
	}

	/**
	 * A hit on the player from the fight's target, with what the target did before it (T-041's actions). The attack
	 * started when its projectile was seen, for one landing now; with none (a bite), the tick before the hit.
	 */
	void onHitOnPlayer(int tick, List<TargetActions.Action> actions)
	{
		if (attackStarts.size() >= MAX_EVENTS)
		{
			return;
		}
		int start = tick - 1;
		if (actions != null)
		{
			for (TargetActions.Action a : actions)
			{
				if (a.projectile && Math.abs(a.landsRelative) <= 1)
				{
					start = Math.min(start, tick + a.relativeTick);
				}
			}
		}
		attackStarts.add(start);
	}

	/** An idle tick the fight counted as lost: kept to be judged at the fight's end. */
	void idle(int countedTick, int judgedTick, int hpLeft, int maxHit)
	{
		if (idle.size() < MAX_EVENTS)
		{
			idle.add(new Idle(countedTick, judgedTick, hpLeft, maxHit));
		}
	}

	/**
	 * Her attacks, by the tick each started: hits on the player whose attacks started under {@code attackGapTicks}
	 * apart are one attack (the triple attack's three parts, 2 ticks apart), from the first of them.
	 */
	static List<Integer> attacks(List<Integer> starts, int gapTicks)
	{
		final List<Integer> sorted = new ArrayList<>(starts);
		Collections.sort(sorted);
		final List<Integer> out = new ArrayList<>();
		for (int start : sorted)
		{
			if (out.isEmpty() || start - out.get(out.size() - 1) >= Math.max(1, gapTicks))
			{
				out.add(start);
			}
		}
		return out;
	}

	/** How many of these attacks had started by this tick. */
	static int startedBy(List<Integer> attacks, int tick)
	{
		int n = 0;
		for (int start : attacks)
		{
			if (start <= tick)
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * Whether an idle tick is the stall: she is above the mark by no more than the player's max hits allow (attacking
	 * could take her under too early), and her attack the stall waits for at the latest hasn't started. Unknown hp or no
	 * max hit is never the stall.
	 */
	static boolean isStall(BossRules.StallRule rule, int hpLeft, int maxHit, int attacksStarted)
	{
		if (!usable(rule) || hpLeft < 0 || maxHit <= 0)
		{
			return false;
		}
		final int above = hpLeft - rule.holdAboveHp;
		return above > 0 && above <= rule.hitsAbove * maxHit && attacksStarted < rule.beforeAttack;
	}

	/** The fight is over: which idle ticks were the stall. Only now is any of it read. */
	Result settle()
	{
		final List<Integer> attacks = attacks(attackStarts, rule.attackGapTicks);
		final List<Idle> excused = new ArrayList<>();
		int first = Integer.MAX_VALUE;
		int last = Integer.MIN_VALUE;
		int hpLow = Integer.MAX_VALUE;
		int hpHigh = Integer.MIN_VALUE;
		for (Idle tick : idle)
		{
			if (isStall(rule, tick.hpLeft, tick.maxHit, startedBy(attacks, tick.judgedTick)))
			{
				excused.add(tick);
				first = Math.min(first, tick.judgedTick);
				last = Math.max(last, tick.judgedTick);
				hpLow = Math.min(hpLow, tick.hpLeft);
				hpHigh = Math.max(hpHigh, tick.hpLeft);
			}
		}
		final String line = excused.isEmpty()
			? String.format("STALL %s: none of %d idle ticks (her %d attacks counted; held above %d by a max hit, "
				+ "before her attack %d)", name(), idle.size(), attacks.size(), rule.holdAboveHp, rule.beforeAttack)
			: String.format("STALL %s: %d of %d idle ticks excused, %d-%d at %d-%d hp, her attacks %d-%d of %d counted "
					+ "(held above %d by a max hit, before her attack %d)", name(), excused.size(), idle.size(), first,
				last, hpLow, hpHigh, startedBy(attacks, first), startedBy(attacks, last), attacks.size(),
				rule.holdAboveHp, rule.beforeAttack);
		return new Result(excused, idle.size(), attacks.size(), line);
	}
}

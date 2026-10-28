package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * How much of a fight's eating the avoidable hits cost (T-041, T-039's hitpoints
 * replay, Eric's picks of 2026-10-27).
 *
 * <p>The fight's hitpoints are replayed twice from the start: once with every hit,
 * once without the avoidable part of them. Both replays eat whenever the
 * hitpoints fall to the player's own eating line, healing what the player's food
 * healed. The real food comes out of both, and everything else (blood fury,
 * regeneration) stays in. The eats the second replay skips are the avoidable
 * ones:
 *
 * <pre>
 * avoidable eating ticks = eating ticks x (eats with - eats without) / eats with
 * </pre>
 *
 * <p>The line is the median hitpoints just before each food in this fight, when it
 * had {@value #OWN_LINE_FROM_EATS} or more; else the median of the lines saved
 * on the last {@value #USUAL_OF_LAST} like kills that had their own; else
 * {@value #DEFAULT_LINE_PERCENT}% of max hitpoints. A player who eats to stay
 * full shows a high line, and is judged by it.
 *
 * <p>The plugin feeds the hitpoints once a tick, each splat on the player, each
 * food eaten, and the avoidable part of each hit once {@link AttackClassifier}
 * has named it. The line is only known once the fight is over, so the replays run
 * then, over a few numbers kept per tick. Pure, so it is under test without a
 * client; nothing in it is read while the fight runs.
 */
final class AvoidableEating
{
	static final int OWN_LINE_FROM_EATS = 3;
	static final int USUAL_OF_LAST = 20;
	static final int DEFAULT_LINE_PERCENT = 40;
	/** Where the line came from, as saved and logged. */
	static final String LINE_THIS_KILL = "this kill";
	static final String LINE_YOUR_USUAL = "your usual";
	static final String LINE_DEFAULT = "default";
	// An eat's heal is taken from the hitpoints over its tick and the next: the stat and the inventory may not change on
	// the same tick.
	private static final int FOOD_WINDOW_TICKS = 1;
	private static final int MAX_REPLAY_EATS = 10_000;

	/** What the replays came to. */
	static final class Result
	{
		final int eats;
		final int eatsWith;
		final int eatsWithout;
		final int line;
		final String lineSource;
		final int foodHeal;
		final double avoidableTicks;

		Result(int eats, int eatsWith, int eatsWithout, int line, String lineSource, int foodHeal, double avoidableTicks)
		{
			this.eats = eats;
			this.eatsWith = eatsWith;
			this.eatsWithout = eatsWithout;
			this.line = line;
			this.lineSource = lineSource;
			this.foodHeal = foodHeal;
			this.avoidableTicks = avoidableTicks;
		}
	}

	private final int startTick;
	private final int startHp;
	// By tick from the start: the hitpoints read at the end of the tick (-1 unread), damage taken, the avoidable part of
	// it, and food eaten.
	private int[] hp = new int[256];
	private int[] damage = new int[256];
	private double[] avoidable = new double[256];
	private int[] eats = new int[256];
	private int lastTick;

	/**
	 * @param startTick the tick {@code startHp} was read at the end of
	 * @param startHp   the player's hitpoints then
	 */
	AvoidableEating(int startTick, int startHp)
	{
		this.startTick = startTick;
		this.startHp = startHp;
		this.lastTick = startTick;
		Arrays.fill(hp, -1);
	}

	/** The player's hitpoints at the end of {@code tick}, the tick's splats and food in. Once a tick. */
	void hitpoints(int tick, int value)
	{
		final int i = index(tick);
		if (i > 0)
		{
			hp[i] = value;
		}
	}

	/** A splat on the player that took hitpoints: a hit or damage over time. */
	void damage(int tick, int amount)
	{
		final int i = index(tick);
		if (i > 0 && amount > 0)
		{
			damage[i] += amount;
		}
	}

	/** The avoidable part of a hit that landed on {@code tick}, once it is named. */
	void avoidable(int tick, double part)
	{
		final int i = index(tick);
		if (i > 0 && part > 0)
		{
			avoidable[i] += part;
		}
	}

	/** A food eaten on {@code tick} (one that holds the next attack back: not a potion). */
	void eat(int tick)
	{
		final int i = index(tick);
		if (i > 0)
		{
			eats[i]++;
		}
	}

	// The slot for a tick, growing the arrays to reach it; 0 or less is at or before the start, which nothing is fed for.
	private int index(int tick)
	{
		final int i = tick - startTick;
		if (i <= 0)
		{
			return i;
		}
		if (i >= hp.length)
		{
			final int size = Math.max(i + 1, hp.length * 2);
			final int old = hp.length;
			hp = Arrays.copyOf(hp, size);
			Arrays.fill(hp, old, size, -1);
			damage = Arrays.copyOf(damage, size);
			avoidable = Arrays.copyOf(avoidable, size);
			eats = Arrays.copyOf(eats, size);
		}
		lastTick = Math.max(lastTick, tick);
		return i;
	}

	// The stretch between two hitpoints readings: per tick when the plugin feeds it, wider in a log read back.
	private static final class Span
	{
		int damage;
		double avoidable;
		int eats;
		// Hitpoints gained beyond the damage: food, blood fury, regeneration. Negative is unexplained loss.
		int gain;
		int hpBefore;
		int hpAfter;
		boolean known;
		boolean food;
		int firstTick;
		int lastTick;
	}

	private List<Span> spans()
	{
		final List<Span> spans = new ArrayList<>();
		final int last = lastTick - startTick;
		int before = startHp;
		Span open = null;
		for (int i = 1; i <= last; i++)
		{
			if (open == null)
			{
				open = new Span();
				open.firstTick = i;
				open.hpBefore = before;
			}
			open.damage += damage[i];
			open.avoidable += avoidable[i];
			open.eats += eats[i];
			open.lastTick = i;
			if (hp[i] >= 0)
			{
				open.hpAfter = hp[i];
				open.gain = hp[i] - before + open.damage;
				open.known = true;
				before = hp[i];
				spans.add(open);
				open = null;
			}
		}
		if (open != null)
		{
			// After the last reading: the damage counts, nothing is known of any healing.
			open.hpAfter = before - open.damage;
			spans.add(open);
		}
		// A food's spans: the one it was eaten in, and the next if it starts on the tick after.
		for (int k = 0; k < spans.size(); k++)
		{
			final Span s = spans.get(k);
			if (s.eats == 0)
			{
				continue;
			}
			s.food = true;
			if (k + 1 < spans.size())
			{
				final Span next = spans.get(k + 1);
				if (next.firstTick <= eatTick(s) + FOOD_WINDOW_TICKS)
				{
					next.food = true;
				}
			}
		}
		return spans;
	}

	// The last tick in the span something was eaten on.
	private int eatTick(Span s)
	{
		for (int i = s.lastTick; i >= s.firstTick; i--)
		{
			if (eats[i] > 0)
			{
				return i;
			}
		}
		return s.lastTick;
	}

	/**
	 * The fight is over: the line, both replays and the ticks they charge.
	 *
	 * @param maxHp        the player's hitpoints level
	 * @param eatingTicks  the fight's ticks lost to eating (T-012)
	 * @param usualLine    the line of your usual like kills, or -1 for none (see {@link #usualLine}); asked only
	 *                     when this fight had too few eats for its own
	 */
	Result finish(int maxHp, int eatingTicks, IntSupplier usualLine)
	{
		final List<Span> spans = spans();
		// Each food's heal and the hitpoints just before it, a group of food spans at a time.
		final List<Integer> before = new ArrayList<>();
		final List<Integer> uncappedHeals = new ArrayList<>();
		int biggestHeal = 0;
		int realEats = 0;
		for (int k = 0; k < spans.size(); k++)
		{
			final Span first = spans.get(k);
			if (!first.food)
			{
				continue;
			}
			int heal = 0;
			int eaten = 0;
			int end = k;
			while (end < spans.size() && spans.get(end).food)
			{
				final Span s = spans.get(end);
				heal += s.known ? Math.max(0, s.gain) : 0;
				eaten += s.eats;
				end++;
			}
			final Span last = spans.get(end - 1);
			if (eaten > 0)
			{
				final int each = heal / eaten;
				for (int g = k; g < end; g++)
				{
					hitpointsBeforeFood(spans.get(g), before);
				}
				if (last.hpAfter < maxHp)
				{
					for (int e = 0; e < eaten; e++)
					{
						uncappedHeals.add(each);
					}
				}
				biggestHeal = Math.max(biggestHeal, each);
				realEats += eaten;
			}
			k = end - 1;
		}
		final int line;
		final String source;
		final int usual = before.size() >= OWN_LINE_FROM_EATS || usualLine == null ? -1 : usualLine.getAsInt();
		if (before.size() >= OWN_LINE_FROM_EATS)
		{
			line = median(before);
			source = LINE_THIS_KILL;
		}
		else if (usual >= 0)
		{
			line = usual;
			source = LINE_YOUR_USUAL;
		}
		else
		{
			line = maxHp * DEFAULT_LINE_PERCENT / 100;
			source = LINE_DEFAULT;
		}
		final int foodHeal = uncappedHeals.isEmpty() ? biggestHeal : median(uncappedHeals);
		if (realEats == 0 || foodHeal <= 0 || eatingTicks <= 0 || maxHp <= 0)
		{
			return new Result(realEats, 0, 0, line, source, foodHeal, 0);
		}
		// A line at or above full hitpoints would eat forever: such a player eats at the first point taken.
		final int at = Math.min(line, maxHp - 1);
		final int with = replay(spans, maxHp, at, foodHeal, false);
		final int without = replay(spans, maxHp, at, foodHeal, true);
		final double ticks = with <= 0 ? 0 : eatingTicks * (double) Math.max(0, with - without) / with;
		return new Result(realEats, with, without, line, source, foodHeal,
			Math.round(Math.min(eatingTicks, ticks) * 10) / 10.0);
	}

	/**
	 * The hitpoints just before each food eaten in the span: what it started at, less
	 * the damage on its ticks before the food's. Damage on the food's own tick is not
	 * taken off: which came first inside a tick is not known.
	 */
	private void hitpointsBeforeFood(Span s, List<Integer> out)
	{
		int taken = 0;
		for (int i = s.firstTick; i <= s.lastTick; i++)
		{
			for (int e = 0; e < eats[i]; e++)
			{
				out.add(s.hpBefore - taken);
			}
			taken += damage[i];
		}
	}

	/** Food eaten by a player who eats whenever the hitpoints are at or below the line. */
	private static int replay(List<Span> spans, int maxHp, int line, int foodHeal, boolean withoutAvoidable)
	{
		double hp = spans.isEmpty() ? maxHp : spans.get(0).hpBefore;
		int eaten = 0;
		for (Span s : spans)
		{
			hp -= s.damage - (withoutAvoidable ? s.avoidable : 0);
			if (!s.food && s.known)
			{
				hp += Math.max(0, s.gain);
			}
			hp = Math.min(maxHp, hp);
			while (hp <= line && eaten < MAX_REPLAY_EATS)
			{
				hp = Math.min(maxHp, hp + foodHeal);
				eaten++;
			}
		}
		return eaten;
	}

	/** The middle value, the lower-middle pair's mean rounded down for an even count. */
	static int median(List<Integer> values)
	{
		final List<Integer> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		final int n = sorted.size();
		return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
	}

	/**
	 * Your usual line: the median of the lines saved on the last
	 * {@value #USUAL_OF_LAST} kills like {@code like} whose line was their own
	 * ({@value #OWN_LINE_FROM_EATS}+ eats), or -1 with none. Read once, at a
	 * fight's end, and only when it had too few eats for its own.
	 *
	 * @param history the saved fights, newest first
	 * @param exclude fights to leave out: the one just ended
	 */
	static int usualLine(List<Fight> history, LedgerHistory.Like like, Collection<Fight> exclude)
	{
		if (history == null || like == null || like.name == null)
		{
			return -1;
		}
		final Set<Fight> skip = Collections.newSetFromMap(new IdentityHashMap<>());
		if (exclude != null)
		{
			skip.addAll(exclude);
		}
		final List<Integer> lines = new ArrayList<>();
		final List<Encounter> rooms = LedgerHistory.rooms(history, like.name);
		for (int r = rooms.size() - 1; r >= 0 && lines.size() < USUAL_OF_LAST; r--)
		{
			final Encounter room = rooms.get(r);
			if (!like.matches(room))
			{
				continue;
			}
			for (Fight fight : room.getFights())
			{
				if (!skip.contains(fight) && fight.getAttackRulesVersion() > 0
					&& LINE_THIS_KILL.equals(fight.getEatLineSource()) && fight.getEatLineHp() >= 0)
				{
					lines.add(fight.getEatLineHp());
					break;
				}
			}
		}
		return lines.isEmpty() ? -1 : median(lines);
	}
}

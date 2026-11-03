package com.pvmlogs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * T-160: what landed on a special's target over its landing window when it was booked as a splash (T-145), anyone's,
 * so a report that it "hit high" can be checked against the log. In Eric's duo Yama of 2026-11-02, two Soul Rends booked
 * as splashes had a 52 drawn as another player's four ticks after their throws ([16976], [17330]), and the thrall's
 * splat on their landing tick: one line per splash says so.
 *
 * <p>Pure bookkeeping: the plugin hands it each splat on an npc being watched, and relabels one as the thrall's when
 * it's judged so. A handful of watches at most, each a few ticks long.
 */
final class SplashCheck
{
	/** The window after the throw: the throw + 3 to + 7 ticks. */
	static final int FROM = 3;
	static final int TO = 7;

	static final class Splat
	{
		String source;
		final int amount;
		final int type;
		final int offset;

		Splat(String source, int amount, int type, int offset)
		{
			this.source = source;
			this.amount = amount;
			this.type = type;
			this.offset = offset;
		}
	}

	private static final class Watch
	{
		final String special;
		final int npcIndex;
		final int thrownTick;
		final List<Splat> splats = new ArrayList<>();

		Watch(String special, int npcIndex, int thrownTick)
		{
			this.special = special;
			this.npcIndex = npcIndex;
			this.thrownTick = thrownTick;
		}
	}

	private final List<Watch> watches = new ArrayList<>(2);

	/** A special thrown on {@code thrownTick} at this npc was booked as a splash: watch its window. */
	void watch(String special, int npcIndex, int thrownTick)
	{
		if (watches.size() >= 4)
		{
			watches.remove(0);
		}
		watches.add(new Watch(special, npcIndex, thrownTick));
	}

	/** A splat on an npc: {@code source} "mine", "others'" or "no owner", as the game marks it. */
	void splat(int npcIndex, int tick, int amount, int type, String source)
	{
		for (Watch w : watches)
		{
			final int offset = tick - w.thrownTick;
			if (w.npcIndex == npcIndex && offset >= FROM && offset <= TO)
			{
				w.splats.add(new Splat(source, amount, type, offset));
			}
		}
	}

	/** One of the player's splats on this tick was the thrall's: relabelled. */
	void thrall(int npcIndex, int tick, int amount)
	{
		for (Watch w : watches)
		{
			final int offset = tick - w.thrownTick;
			if (w.npcIndex != npcIndex)
			{
				continue;
			}
			for (Splat s : w.splats)
			{
				if (s.offset == offset && s.amount == amount && "mine".equals(s.source))
				{
					s.source = "thrall";
					break;
				}
			}
		}
	}

	/** The lines for the watches whose window is over by {@code tick}, which are dropped. */
	List<String> due(int tick)
	{
		final List<String> lines = new ArrayList<>(1);
		for (Iterator<Watch> it = watches.iterator(); it.hasNext(); )
		{
			final Watch w = it.next();
			if (tick - w.thrownTick > TO)
			{
				it.remove();
				lines.add(line(w.special, w.thrownTick, w.splats));
			}
		}
		return lines;
	}

	static String line(String special, int thrownTick, List<Splat> splats)
	{
		final StringBuilder b = new StringBuilder("SPLASH CHECK ").append(special).append(" thrown ").append(thrownTick)
			.append(':');
		boolean mine = false;
		for (Splat s : splats)
		{
			mine |= "mine".equals(s.source);
			b.append(' ').append(s.source).append(' ').append(s.amount).append(" (type ").append(s.type).append(") at +")
				.append(s.offset).append(';');
		}
		if (!mine)
		{
			b.append(" none of mine");
		}
		else
		{
			b.setLength(b.length() - 1);
		}
		return b.toString();
	}
}

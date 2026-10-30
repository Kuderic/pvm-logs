package com.pvmlogs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * T-074: downtime a boss forces on the player, excused from uptime. Each tick
 * the plugin shows it the boss's damage-modifier windows its
 * {@link BossRules.UptimeAllowance}s ride on; each idle tick it asks whether
 * one covers it. A covering window with budget left takes the tick as a neutral
 * "mechanics" tick; one past its cap leaves it lost, so standing idle through a
 * whole stun isn't free.
 *
 * <p>Pure: the windows come in through {@link WindowLookup}, the log through a
 * consumer. One fight at a time, from {@link #reset}.
 */
final class MechanicAllowances
{
	/** How far before a window's opening the cast that started it may be (the Leviathan's stun lands up to 5 later). */
	static final int CAST_LEAD_TICKS = 6;
	// Idle ticks are judged a booking lag behind the tick they are counted on, so a window's run is logged this long after
	// its cover has ended.
	private static final int FLUSH_DELAY_TICKS = 3;

	/** The window of a state open or set to open now, as {start, end}; null for none. */
	interface WindowLookup
	{
		int[] window(String state);
	}

	/** One window of one allowance: its cover, and what it has excused. */
	static final class Run
	{
		final BossRules.UptimeAllowance allowance;
		final int start;
		final int from;
		int end;
		int lastSeen;
		boolean gone;
		int excused;
		int stillLost;

		Run(BossRules.UptimeAllowance allowance, int start, int end, int from, int tick)
		{
			this.allowance = allowance;
			this.start = start;
			this.end = end;
			this.from = from;
			this.lastSeen = tick;
		}

		/** The last tick it covers: its window's, or the last it was seen open if it closed early, plus the after ticks. */
		int to()
		{
			final int lastOpen = gone ? Math.min(end - 1, lastSeen) : end - 1;
			return lastOpen + Math.max(0, allowance.afterTicks);
		}

		boolean covers(int tick)
		{
			return tick >= from && tick <= to();
		}

		String line()
		{
			return String.format("MECHANIC %s (window %d-%d, covering %d-%d): %d idle tick%s excused of %d allowed, %d still lost",
				allowance.state, start, Math.min(end - 1, gone ? lastSeen : end - 1), from, to(), excused,
				excused == 1 ? "" : "s", allowance.maxTicks, stillLost);
		}
	}

	private final Consumer<String> log;
	private final List<Run> runs = new ArrayList<>();
	private int excusedTotal;
	private int stillLostTotal;
	private int castTick = Integer.MIN_VALUE;

	MechanicAllowances(Consumer<String> log)
	{
		this.log = log == null ? line -> { } : log;
	}

	/** A new fight: nothing covered, nothing counted. */
	void reset()
	{
		runs.clear();
		excusedTotal = 0;
		stillLostTotal = 0;
		castTick = Integer.MIN_VALUE;
	}

	/** The player cast a spell that starts a state (the Leviathan's stun), for an allowance counted from the cast. */
	void noteCast(int tick)
	{
		castTick = tick;
	}

	/** Once a tick: the windows the allowances ride on, as they stand on {@code tick}. */
	void observe(int tick, List<BossRules.UptimeAllowance> allowances, WindowLookup windows)
	{
		if (allowances != null)
		{
			for (BossRules.UptimeAllowance allowance : allowances)
			{
				if (allowance == null || allowance.state == null || allowance.maxTicks <= 0)
				{
					continue;
				}
				final int[] window = windows == null ? null : windows.window(allowance.state);
				final Run run = latest(allowance);
				if (window != null && (run == null || run.start != window[0]))
				{
					final boolean cast = allowance.fromCast() && castTick != Integer.MIN_VALUE
						&& castTick <= window[0] && window[0] - castTick <= CAST_LEAD_TICKS;
					runs.add(new Run(allowance, window[0], window[1], cast ? castTick : window[0], tick));
				}
				else if (window != null)
				{
					run.end = window[1];
					run.lastSeen = tick;
				}
				else if (run != null && !run.gone)
				{
					run.gone = true;
				}
			}
		}
		flush(tick - FLUSH_DELAY_TICKS);
	}

	/**
	 * An idle tick, judged on {@code idleTick}: whether an allowance excuses it.
	 * The earliest covering window with budget left takes it; one covering it
	 * with none left counts it as still lost.
	 *
	 * @return the run that excused it, or null when it stays lost
	 */
	Run excuse(int idleTick)
	{
		Run over = null;
		for (Run run : runs)
		{
			if (!run.covers(idleTick))
			{
				continue;
			}
			if (run.excused < run.allowance.maxTicks)
			{
				run.excused++;
				excusedTotal++;
				return run;
			}
			over = over == null ? run : over;
		}
		if (over != null)
		{
			over.stillLost++;
			stillLostTotal++;
		}
		return null;
	}

	/** Whether any allowance's window covers this tick (T-088: the Mole's burrow, while she is out of view). */
	boolean covers(int idleTick)
	{
		for (Run run : runs)
		{
			if (run.covers(idleTick))
			{
				return true;
			}
		}
		return false;
	}

	/** The fight is over: every run still open is logged, then the totals. */
	void finish()
	{
		flush(Integer.MAX_VALUE);
		if (excusedTotal > 0 || stillLostTotal > 0)
		{
			log.accept(String.format("MECHANIC total: %d idle tick%s excused, %d still lost inside allowances", excusedTotal,
				excusedTotal == 1 ? "" : "s", stillLostTotal));
		}
	}

	int excusedTotal()
	{
		return excusedTotal;
	}

	int stillLostTotal()
	{
		return stillLostTotal;
	}

	private Run latest(BossRules.UptimeAllowance allowance)
	{
		for (int i = runs.size() - 1; i >= 0; i--)
		{
			if (runs.get(i).allowance == allowance)
			{
				return runs.get(i);
			}
		}
		return null;
	}

	// Logs and forgets the runs whose cover ended before this tick. A run with nothing to say is forgotten quietly.
	private void flush(int before)
	{
		for (Iterator<Run> it = runs.iterator(); it.hasNext(); )
		{
			final Run run = it.next();
			if (run.gone && run.to() < before || before == Integer.MAX_VALUE)
			{
				if (run.excused > 0 || run.stillLost > 0)
				{
					log.accept(run.line());
				}
				it.remove();
			}
		}
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/**
 * What the plugin costs the client during fights (T-045): frame intervals, the
 * plugin's own time on the client thread and the side panel's on Swing's. On
 * only while Debug logging is on; off, each timing point costs one boolean
 * check. Fixed-size arrays filled with {@code System.nanoTime} differences, so a
 * sample allocates nothing; a fight's report (the {@code PERF} block) is built
 * once, at its end.
 *
 * <p>Threads: the frame and client-thread timers are written on the client
 * thread only. The Swing timers are written on Swing's thread and read at the
 * report on the client thread, under a lock.
 */
final class PerfProbe
{
	/** What is timed. {@code nested} ones run inside another and are left out of the plugin's total. */
	enum Slot
	{
		// onGameTick, whole, and its parts.
		ON_GAME_TICK("onGameTick", false),
		REFRESH_EXPECTED("onGameTick: refreshExpected", true),
		SWITCH_CHECK("onGameTick: gear-switch check", true),
		PANEL_SNAPSHOT("onGameTick: side panel snapshot", true),
		// T-046 follow-up: inside the snapshot, the ticks that build one (every 5 during a fight), apart from the ticks that
		// only check whether to.
		PANEL_BUILD("onGameTick: side panel snapshot, the builds alone", true),
		TICK_REST("onGameTick: the rest", true),
		// The other handlers.
		ON_HITSPLAT_APPLIED("onHitsplatApplied", false),
		ON_PROJECTILE_MOVED("onProjectileMoved", false),
		ON_ANIMATION_CHANGED("onAnimationChanged", false),
		ON_GRAPHIC_CHANGED("onGraphicChanged", false),
		ON_STAT_CHANGED("onStatChanged", false),
		ON_FAKE_XP_DROP("onFakeXpDrop", false),
		ON_VARBIT_CHANGED("onVarbitChanged", false),
		ON_ITEM_CONTAINER_CHANGED("onItemContainerChanged", false),
		ON_MENU_OPTION_CLICKED("onMenuOptionClicked", false),
		ON_CHAT_MESSAGE("onChatMessage", false),
		ON_OVERHEAD_TEXT_CHANGED("onOverheadTextChanged", false),
		ON_NPC_SPAWNED("onNpcSpawned", false),
		ON_NPC_CHANGED("onNpcChanged", false),
		ON_NPC_DESPAWNED("onNpcDespawned", false),
		ON_NPC_LOOT_RECEIVED("onNpcLootReceived", false),
		ON_SPECIAL_COUNTER_UPDATE("onSpecialCounterUpdate", false),
		ON_GAME_OBJECT_SPAWNED("onGameObjectSpawned", false),
		ON_GAME_STATE_CHANGED("onGameStateChanged", false),
		ON_CONFIG_CHANGED("onConfigChanged", false),
		ON_COMMAND_EXECUTED("onCommandExecuted", false),
		// Inside the handlers above.
		TIMELINE("FightTimeline hooks", true),
		// T-050's outcome distribution: the tick's figures at each state, each booking, each tick's drains.
		DRAIN_OUTCOMES("spec outcomes (T-050)", true),
		// A frame's overlays.
		OVERLAY("overlay render", false),
		LOADOUT_OVERLAY("loadout overlay render", false);

		final String label;
		final boolean nested;

		Slot(String label, boolean nested)
		{
			this.label = label;
			this.nested = nested;
		}
	}

	/** On Swing's thread. */
	enum SwingSlot
	{
		APPLY("panel apply"),
		PAINT_AFTER_APPLY("panel repaint after apply"),
		PAINT_FULL("panel full paint (paintChildren)"),
		// T-046: a tab's paint split into its software render and the one copy to the screen; layout passes; the chart's
		// and the strip's cached images brought up to date.
		VIEW_RENDER("panel software render"),
		VIEW_BLIT("panel copy to screen"),
		LAYOUT("panel layout pass"),
		CHART_DRAW("damage chart image"),
		STRIP_DRAW("tick strip image"),
		// T-046 follow-up: a Fight tab rebuilt because its structure changed (a section or a damage row came or went).
		REBUILD("panel rebuild (structure changed)");

		final String label;

		SwingSlot(String label)
		{
			this.label = label;
		}
	}

	// Samples a timer keeps per fight for its percentiles; its count, total and max cover every sample.
	static final int TIMER_SAMPLES = 4096;
	// Frame intervals kept per fight (10 minutes at 144 FPS fits), and the rolling window behind the baseline.
	static final int FRAME_SAMPLES = 1 << 17;
	static final int BASELINE_FRAMES = 1 << 13;
	static final long BASELINE_NANOS = 30_000_000_000L;

	/** One timer: durations in nanoseconds, the first {@link #TIMER_SAMPLES} of a fight kept for its percentiles. */
	static final class Timer
	{
		private final int[] samples;
		private int count;
		private long total;
		private long max;

		Timer(int capacity)
		{
			samples = new int[capacity];
		}

		void add(long nanos)
		{
			if (count < samples.length)
			{
				samples[count] = (int) Math.min(Integer.MAX_VALUE, nanos);
			}
			count++;
			total += nanos;
			max = Math.max(max, nanos);
		}

		void reset()
		{
			count = 0;
			total = 0;
			max = 0;
		}

		int count()
		{
			return count;
		}

		long total()
		{
			return total;
		}

		long max()
		{
			return max;
		}

		/** The q quantile in nanoseconds, over the samples kept; 0 with none. */
		long quantile(double q)
		{
			return PerfProbe.quantile(samples, Math.min(count, samples.length), q);
		}
	}

	private volatile boolean on;
	private Timer[] timers;
	private final Object swingLock = new Object();
	private Timer[] swingTimers;
	private final long[] lastNested = new long[Slot.values().length];

	// Frames: this fight's intervals in microseconds, and a rolling window of (end time, interval) for the baseline.
	private int[] frames;
	private int frameCount;
	private long frameMax;
	private long[] windowEnds;
	private int[] windowIntervals;
	private int windowNext;
	private int windowSize;
	private long lastFrame;
	private long fightStartNanos;
	private int[] baseline;
	private int baselineCount;
	private long baselineSpanNanos;
	// Read by the 20 ms watch on RuneLite's executor too (T-046 follow-up).
	private volatile boolean fighting;

	// Which panel state each tick of the fight was in (panelState's codes), in the order first seen.
	static final int PANEL_STATES = 13;
	private final int[] panelTicks = new int[PANEL_STATES];
	private final int[] panelOrder = new int[PANEL_STATES];
	private int panelSeen;

	private List<String> lastReport = new ArrayList<>();

	// Input delay (T-045 item 4): how long a task posted to Swing's queue waits to run, which is how long a click waits.
	// One probe a tick, reused: none is posted while the last is still waiting, so a wait past a tick is still recorded
	// whole, once, when it runs.
	static final long INPUT_SLOW_NANOS = 16_000_000L;
	static final long INPUT_STUCK_NANOS = 50_000_000L;
	private Timer inputDelay;
	private int inputSlow;
	private int inputStuck;
	private final AtomicBoolean inputPending = new AtomicBoolean();
	private volatile long inputPosted;
	private final Runnable inputProbe = () ->
	{
		recordInputDelay(System.nanoTime() - inputPosted);
		inputPending.set(false);
	};

	// T-046 follow-up: Swing's thread watched every 20 ms from RuneLite's executor, so no stall can fall between two
	// probes. One probe pending at a time; a post skipped because the last is still waiting is 20 ms of stall. When a probe
	// has waited over 50 ms, Swing's thread's stack is read: what holds it.
	static final long WATCH_MILLIS = 20;
	/** A fight's watch samples kept for the percentiles: 11 minutes; the count, total and max cover every one. */
	static final int WATCH_SAMPLES = 1 << 15;
	static final int STALL_FRAMES = 30;
	static final int STALLS_SHOWN = 5;
	private static final int STALLS_KEPT = 64;
	private ScheduledFuture<?> watch;
	private final AtomicBoolean watchPending = new AtomicBoolean();
	private volatile long watchPosted;
	/** The thread the last probe ran on: Swing's. */
	private volatile Thread swingThread;
	/** When the last panel apply ended, to say how long after it the slow probes were posted. */
	private volatile long lastApplyEnd;
	private Timer watchDelay;
	private int watchSlow;
	private int watchStuck;
	private long watchSkipped;
	/** Slow probes (over 16 ms) by when they were posted: no apply yet, under 100 ms after one, 0.1 to 0.6 s, 0.6 to 3 s, later. */
	private final int[] slowAfterApply = new int[5];
	/** What held Swing's thread, by its top frames; written on the executor, read at the report, under {@link #stallLock}. */
	private final Object stallLock = new Object();
	private final Map<String, Stall> stalls = new LinkedHashMap<>();
	private int stallsOther;
	private final Runnable watchProbe = () ->
	{
		swingThread = Thread.currentThread();
		final long posted = watchPosted;
		recordWatch(System.nanoTime() - posted, posted);
		watchPending.set(false);
	};

	/** One distinct stack Swing's thread was seen in while a probe waited over 50 ms. */
	static final class Stall
	{
		final StackTraceElement[] frames;
		int seen;
		long longest;

		Stall(StackTraceElement[] frames)
		{
			this.frames = frames;
		}
	}

	// T-046 follow-up: what asked for each layout pass of the panel during a fight, by the stack of the first component
	// to invalidate since the pass before. Swing's thread, under swingLock.
	private final Map<String, int[]> layoutAskers = new LinkedHashMap<>();
	private int layoutPasses;
	private String pendingAsker;

	void setOn(boolean on)
	{
		if (on && timers == null)
		{
			timers = new Timer[Slot.values().length];
			for (int i = 0; i < timers.length; i++)
			{
				timers[i] = new Timer(TIMER_SAMPLES);
			}
			synchronized (swingLock)
			{
				swingTimers = new Timer[SwingSlot.values().length];
				for (int i = 0; i < swingTimers.length; i++)
				{
					swingTimers[i] = new Timer(TIMER_SAMPLES);
				}
				inputDelay = new Timer(TIMER_SAMPLES);
				watchDelay = new Timer(WATCH_SAMPLES);
			}
			frames = new int[FRAME_SAMPLES];
			windowEnds = new long[BASELINE_FRAMES];
			windowIntervals = new int[BASELINE_FRAMES];
			baseline = new int[BASELINE_FRAMES];
		}
		if (!on)
		{
			// Turned off mid-fight, the fight has no report: turned back on, the next fight starts clean.
			lastFrame = 0;
			fighting = false;
			stopWatch();
		}
		this.on = on;
	}

	// ---- The 20 ms watch on Swing's thread (T-046 follow-up) ----

	/**
	 * Once a tick on the client thread: while on, makes sure the 20 ms watch runs on RuneLite's executor (no thread of
	 * our own); off, stops it.
	 */
	synchronized void watchSwing(ScheduledExecutorService executor)
	{
		if (!on || executor == null)
		{
			stopWatch();
			return;
		}
		if (watch == null)
		{
			watch = executor.scheduleAtFixedRate(this::sampleSwing, WATCH_MILLIS, WATCH_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	/** Whether the 20 ms watch is scheduled; for tests. */
	synchronized boolean watching()
	{
		return watch != null;
	}

	private synchronized void stopWatch()
	{
		if (watch != null)
		{
			watch.cancel(false);
			watch = null;
		}
	}

	/** Every 20 ms on the executor: posts a probe, or, while the last still waits, counts the stall and reads the stack. */
	void sampleSwing()
	{
		// Only during a fight: the report is a fight's.
		if (!on || !fighting)
		{
			return;
		}
		final long now = System.nanoTime();
		if (watchPending.get())
		{
			if (fighting)
			{
				synchronized (swingLock)
				{
					watchSkipped++;
				}
				final Thread swing = swingThread;
				if (swing != null && now - watchPosted > INPUT_STUCK_NANOS)
				{
					recordStall(swing.getStackTrace(), now - watchPosted);
				}
			}
			return;
		}
		watchPending.set(true);
		watchPosted = now;
		SwingUtilities.invokeLater(watchProbe);
	}

	/** One watch probe's wait. Swing's thread, or a test. */
	void recordWatch(long nanos, long posted)
	{
		synchronized (swingLock)
		{
			if (watchDelay == null || !fighting)
			{
				return;
			}
			watchDelay.add(nanos);
			if (nanos > INPUT_SLOW_NANOS)
			{
				watchSlow++;
				final long apply = lastApplyEnd;
				final long since = posted - apply;
				slowAfterApply[apply == 0L || since < 0 ? 0 : since < 100_000_000L ? 1 : since < 600_000_000L ? 2
					: since < 3_000_000_000L ? 3 : 4]++;
			}
			watchStuck += nanos > INPUT_STUCK_NANOS ? 1 : 0;
		}
	}

	/** Swing's thread's stack while a probe waits over 50 ms: kept by its top frames, counted. */
	void recordStall(StackTraceElement[] stack, long waited)
	{
		final StackTraceElement[] top = Arrays.copyOf(stack, Math.min(STALL_FRAMES, stack.length));
		final String key = Arrays.toString(top);
		synchronized (stallLock)
		{
			Stall s = stalls.get(key);
			if (s == null)
			{
				if (stalls.size() >= STALLS_KEPT)
				{
					stallsOther++;
					return;
				}
				s = new Stall(top);
				stalls.put(key, s);
			}
			s.seen++;
			s.longest = Math.max(s.longest, waited);
		}
	}

	// ---- What asks for the panel's layout passes (T-046 follow-up) ----

	/**
	 * The panel's validate root was invalidated: the stack says by what. Kept for the next layout pass, the first since
	 * the last pass only. Swing's thread; the caller reads the stack only while the probe is on.
	 */
	void layoutAsked(StackTraceElement[] stack)
	{
		if (!fighting)
		{
			return;
		}
		synchronized (swingLock)
		{
			if (pendingAsker == null)
			{
				pendingAsker = asker(stack);
			}
		}
	}

	/** A layout pass of the panel ran: counted against what asked for it. */
	void layoutPassed()
	{
		if (!fighting)
		{
			return;
		}
		synchronized (swingLock)
		{
			layoutPasses++;
			final String asker = pendingAsker == null ? "nothing invalidated it (a validate from outside)" : pendingAsker;
			pendingAsker = null;
			final int[] n = layoutAskers.get(asker);
			if (n != null)
			{
				n[0]++;
			}
			else if (layoutAskers.size() < STALLS_KEPT)
			{
				layoutAskers.put(asker, new int[]{1});
			}
		}
	}

	/** The first frames of an invalidation's stack past AWT's and Swing's own invalidate and revalidate. */
	static String asker(StackTraceElement[] stack)
	{
		final StringBuilder out = new StringBuilder();
		int shown = 0;
		for (StackTraceElement f : stack)
		{
			final String c = f.getClassName();
			final String m = f.getMethodName();
			final boolean machinery = m.equals("invalidate") || m.equals("invalidateIfValid") || m.equals("invalidateParent")
				|| m.equals("revalidate") || m.equals("getStackTrace") || c.equals(PerfProbe.class.getName())
				|| c.startsWith("com.pvmlogs.PvmPerformancePanel$");
			if (machinery && shown == 0)
			{
				continue;
			}
			out.append(shown == 0 ? "" : " < ").append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(m)
				.append(':').append(f.getLineNumber());
			if (++shown == 6)
			{
				break;
			}
		}
		return shown == 0 ? "unknown" : out.toString();
	}

	boolean isOn()
	{
		return on;
	}

	// ---- Timing points ----

	/** The start of a timed stretch: 0 when off, which {@link #stop} then ignores. */
	long start()
	{
		return on ? System.nanoTime() : 0L;
	}

	/** Null-safe {@link #start()}, for classes that may have no probe (tests). */
	static long start(PerfProbe probe)
	{
		return probe == null ? 0L : probe.start();
	}

	/** Ends a stretch on the client thread; returns its length in nanoseconds (0 when off). */
	long stop(Slot slot, long started)
	{
		if (started == 0L || timers == null)
		{
			return 0L;
		}
		final long nanos = System.nanoTime() - started;
		timers[slot.ordinal()].add(nanos);
		lastNested[slot.ordinal()] = nanos;
		return nanos;
	}

	static void stop(PerfProbe probe, Slot slot, long started)
	{
		if (probe != null)
		{
			probe.stop(slot, started);
		}
	}

	/** Records a length worked out by the caller (onGameTick's rest). */
	void record(Slot slot, long nanos)
	{
		if (on && timers != null)
		{
			timers[slot.ordinal()].add(Math.max(0, nanos));
		}
	}

	/** The last length a slot recorded, for onGameTick's rest: 0 if it didn't run this tick (reset with {@link #clearNested}). */
	long lastNanos(Slot slot)
	{
		return lastNested[slot.ordinal()];
	}

	void clearNested()
	{
		if (on)
		{
			Arrays.fill(lastNested, 0L);
		}
	}

	/** Ends a stretch on Swing's thread. */
	void stopSwing(SwingSlot slot, long started)
	{
		if (started == 0L)
		{
			return;
		}
		final long ended = System.nanoTime();
		final long nanos = ended - started;
		if (slot == SwingSlot.APPLY)
		{
			lastApplyEnd = ended;
		}
		synchronized (swingLock)
		{
			if (swingTimers != null)
			{
				swingTimers[slot.ordinal()].add(nanos);
			}
		}
	}

	/**
	 * Once a tick, on the client thread: posts the input-delay probe to Swing's
	 * queue, stamped now, unless the last one is still waiting there.
	 */
	void postInputProbe()
	{
		if (!on || !inputPending.compareAndSet(false, true))
		{
			return;
		}
		inputPosted = System.nanoTime();
		SwingUtilities.invokeLater(inputProbe);
	}

	/** How long one probe waited on Swing's queue. Swing's thread, or a test. */
	void recordInputDelay(long nanos)
	{
		synchronized (swingLock)
		{
			if (inputDelay == null)
			{
				return;
			}
			inputDelay.add(nanos);
			inputSlow += nanos > INPUT_SLOW_NANOS ? 1 : 0;
			inputStuck += nanos > INPUT_STUCK_NANOS ? 1 : 0;
		}
	}

	/** One frame: the {@code BeforeRender} event, once a frame on the client thread. Records the interval and nothing else. */
	void frame()
	{
		if (!on)
		{
			return;
		}
		frameAt(System.nanoTime());
	}

	void frameAt(long now)
	{
		if (frames == null)
		{
			return;
		}
		if (lastFrame != 0L)
		{
			final long interval = now - lastFrame;
			final int micros = (int) Math.min(Integer.MAX_VALUE, interval / 1000);
			windowEnds[windowNext] = now;
			windowIntervals[windowNext] = micros;
			windowNext = (windowNext + 1) % BASELINE_FRAMES;
			windowSize = Math.min(BASELINE_FRAMES, windowSize + 1);
			if (fighting)
			{
				if (frameCount < frames.length)
				{
					frames[frameCount] = micros;
				}
				frameCount++;
				frameMax = Math.max(frameMax, interval);
			}
		}
		lastFrame = now;
	}

	/**
	 * The side panel's state as a number, so a tick's note allocates nothing:
	 * 0 closed, else 1 + tab x 4 + Update during fights x 2 + Charts, the tab
	 * 0 Fight, 1 History, 2 Trip.
	 */
	static int panelState(boolean open, int tab, boolean live, boolean charts)
	{
		return !open ? 0 : 1 + Math.max(0, Math.min(2, tab)) * 4 + (live ? 2 : 0) + (charts ? 1 : 0);
	}

	static String panelLabel(int state)
	{
		if (state <= 0)
		{
			return "closed";
		}
		final int s = state - 1;
		return String.format("open on %s, Update during fights %s, Charts %s",
			new String[]{"Fight", "History", "Trip"}[s / 4], (s & 2) != 0 ? "on" : "off", (s & 1) != 0 ? "on" : "off");
	}

	/** Which panel state this tick of the fight was in ({@link #panelState}). */
	void notePanelState(int state)
	{
		if (on && fighting && state >= 0 && state < PANEL_STATES)
		{
			if (panelTicks[state]++ == 0)
			{
				panelOrder[panelSeen++] = state;
			}
		}
	}

	// ---- A fight ----

	/** A fight starts: its timers start empty, and the 30 s of frames before it become its baseline. */
	void fightStarted()
	{
		fightStartedAt(System.nanoTime());
	}

	void fightStartedAt(long now)
	{
		if (!on || timers == null)
		{
			return;
		}
		for (Timer t : timers)
		{
			t.reset();
		}
		synchronized (swingLock)
		{
			for (Timer t : swingTimers)
			{
				t.reset();
			}
			watchDelay.reset();
			watchSlow = 0;
			watchStuck = 0;
			watchSkipped = 0;
			Arrays.fill(slowAfterApply, 0);
			layoutAskers.clear();
			layoutPasses = 0;
			pendingAsker = null;
			inputDelay.reset();
			inputSlow = 0;
			inputStuck = 0;
		}
		synchronized (stallLock)
		{
			stalls.clear();
			stallsOther = 0;
		}
		Arrays.fill(panelTicks, 0);
		panelSeen = 0;
		baselineCount = 0;
		long earliest = Long.MAX_VALUE;
		for (int i = 0; i < windowSize; i++)
		{
			final int k = Math.floorMod(windowNext - 1 - i, BASELINE_FRAMES);
			// Frames that started inside the 30 s.
			final long frameStart = windowEnds[k] - windowIntervals[k] * 1000L;
			if (now - frameStart > BASELINE_NANOS)
			{
				break;
			}
			baseline[baselineCount++] = windowIntervals[k];
			earliest = Math.min(earliest, frameStart);
		}
		baselineSpanNanos = baselineCount == 0 ? 0 : now - earliest;
		frameCount = 0;
		frameMax = 0;
		fightStartNanos = now;
		fighting = true;
	}

	/**
	 * A fight ends: its {@code PERF} block, kept for the Save debug log header
	 * ({@link #lastReport()}). Empty when off or no fight was started under it.
	 */
	List<String> fightEnded(String fight)
	{
		return fightEndedAt(fight, System.nanoTime());
	}

	List<String> fightEndedAt(String fight, long now)
	{
		if (!on || !fighting || timers == null)
		{
			return new ArrayList<>();
		}
		fighting = false;
		final List<String> lines = new ArrayList<>();
		final long span = Math.max(1, now - fightStartNanos);
		lines.add(String.format("PERF %s: %.1f s; panel %s", fight, span / 1e9, panelSummary()));
		lines.add("PERF frames: " + frameSummary(frames, Math.min(frameCount, frames.length), frameCount, frameMax,
			span) + " | the 30 s before: " + frameSummary(baseline, baselineCount, baselineCount,
			maxMicros(baseline, baselineCount) * 1000L, baselineSpanNanos));

		// The plugin's own time on the client thread: the top-level timers only, the nested ones are inside them.
		long plugin = 0;
		for (Slot s : Slot.values())
		{
			if (!s.nested)
			{
				plugin += timers[s.ordinal()].total();
			}
		}
		lines.add(String.format("PERF client thread: the plugin %.1f ms of %.1f s (%.2f%%)", plugin / 1e6, span / 1e9,
			100.0 * plugin / span));
		final List<Slot> ranked = new ArrayList<>();
		for (Slot s : Slot.values())
		{
			if (timers[s.ordinal()].count() > 0)
			{
				ranked.add(s);
			}
		}
		ranked.sort((a, b) -> Long.compare(timers[b.ordinal()].quantile(0.99), timers[a.ordinal()].quantile(0.99)));
		final StringBuilder top = new StringBuilder("PERF top by p99:");
		for (int i = 0; i < ranked.size(); i++)
		{
			top.append(i == 0 ? " " : "; ").append(timerSummary(ranked.get(i).label, timers[ranked.get(i).ordinal()]));
		}
		lines.add(ranked.isEmpty() ? "PERF top by p99: nothing ran" : top.toString());

		final StringBuilder swing = new StringBuilder("PERF Swing's thread:");
		synchronized (swingLock)
		{
			for (SwingSlot s : SwingSlot.values())
			{
				swing.append(s == SwingSlot.APPLY ? " " : "; ").append(timerSummary(s.label, swingTimers[s.ordinal()]));
			}
		}
		lines.add(swing.toString());
		synchronized (swingLock)
		{
			// Behind only the panel's apply: the apply's repaint is queued while it runs, behind this probe (T-046 follow-up).
			lines.add("PERF input delay (Swing's queue, a probe a tick): " + timerSummary("waited", inputDelay)
				+ String.format("; over 16 ms %d, over 50 ms %d", inputSlow, inputStuck));
		}
		lines.add("PERF budgets: " + budgets());
		lines.addAll(watchLines(span));
		lastReport = new ArrayList<>(lines);
		return lines;
	}

	/** The 20 ms watch's line, the layout passes' and the stalls' (T-046 follow-up). */
	private List<String> watchLines(long span)
	{
		final List<String> lines = new ArrayList<>();
		synchronized (swingLock)
		{
			lines.add(String.format("PERF input delay (Swing's queue, a probe every %d ms): %s; over 16 ms %d, over 50 ms %d;"
				+ " stalled %.2f%% of the fight (%d posts skipped while one waited); the slow ones posted: %d before any"
				+ " apply, %d under 0.1 s after one, %d at 0.1-0.6 s, %d at 0.6-3 s, %d later; budget p99 %.3f ms %s 16,"
				+ " over 50 ms %d %s 0", WATCH_MILLIS, timerSummary("waited", watchDelay), watchSlow, watchStuck,
				100.0 * watchSkipped * WATCH_MILLIS * 1e6 / span, watchSkipped, slowAfterApply[0], slowAfterApply[1],
				slowAfterApply[2], slowAfterApply[3], slowAfterApply[4], watchDelay.quantile(0.99) / 1e6,
				watchDelay.quantile(0.99) < INPUT_SLOW_NANOS ? "<" : ">=", watchStuck, watchStuck == 0 ? "=" : ">"));
			final List<Map.Entry<String, int[]>> askers = new ArrayList<>(layoutAskers.entrySet());
			askers.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
			final StringBuilder layout = new StringBuilder("PERF layout passes: " + layoutPasses);
			for (int i = 0; i < askers.size() && i < 8; i++)
			{
				layout.append(i == 0 ? "; asked by " : " | ").append(askers.get(i).getValue()[0]).append("x ")
					.append(askers.get(i).getKey());
			}
			lines.add(layout.toString());
		}
		lines.addAll(stallLines());
		return lines;
	}

	/** The {@code PERF stall} lines: the most seen stacks, top frames first. */
	private List<String> stallLines()
	{
		final List<String> out = new ArrayList<>();
		synchronized (stallLock)
		{
			final List<Stall> seen = new ArrayList<>(stalls.values());
			seen.sort((a, b) -> Integer.compare(b.seen, a.seen));
			for (int i = 0; i < seen.size() && i < STALLS_SHOWN; i++)
			{
				final Stall st = seen.get(i);
				out.add(String.format("PERF stall %d of %d: Swing's thread seen here %dx, a probe waiting up to %.0f ms",
					i + 1, seen.size(), st.seen, st.longest / 1e6));
				for (StackTraceElement f : st.frames)
				{
					out.add("PERF stall " + (i + 1) + "   at " + f);
				}
			}
			if (stallsOther > 0)
			{
				out.add("PERF stall: " + stallsOther + " more samples in other stacks");
			}
		}
		return out;
	}

	/** The last fight's PERF block, for the Save debug log header; empty before one. */
	List<String> lastReport()
	{
		return new ArrayList<>(lastReport);
	}

	// ---- Report pieces ----

	private String panelSummary()
	{
		if (panelSeen == 0)
		{
			return "not sampled";
		}
		int total = 0;
		for (int n : panelTicks)
		{
			total += n;
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < panelSeen; i++)
		{
			final int state = panelOrder[i];
			out.append(i == 0 ? "" : "; then ").append(panelLabel(state));
			if (panelSeen > 1)
			{
				out.append(String.format(" (%d of %d ticks)", panelTicks[state], total));
			}
		}
		return out.toString();
	}

	/**
	 * Frames over a stretch: count and FPS; the interval's median, p95, p99 and
	 * max; hitches (longer than both twice the median and the median + 16 ms)
	 * and long frames (over 50 ms).
	 */
	static String frameSummary(int[] micros, int kept, int count, long maxNanos, long spanNanos)
	{
		if (count == 0)
		{
			return "no frames";
		}
		final long median = quantile(micros, kept, 0.5);
		final long hitchOver = Math.max(2 * median, median + 16_000);
		int hitches = 0;
		int longFrames = 0;
		for (int i = 0; i < kept; i++)
		{
			hitches += micros[i] > hitchOver ? 1 : 0;
			longFrames += micros[i] > 50_000 ? 1 : 0;
		}
		return String.format("%d at %.1f fps; interval median %.1f, p95 %.1f, p99 %.1f, max %.1f ms; hitches %d, "
				+ "long frames %d%s", count, spanNanos <= 0 ? 0 : count / (spanNanos / 1e9), median / 1e3,
			quantile(micros, kept, 0.95) / 1e3, quantile(micros, kept, 0.99) / 1e3, maxNanos / 1e6, hitches,
			longFrames, kept < count ? " (of the first " + kept + ")" : "");
	}

	static String timerSummary(String label, Timer t)
	{
		if (t.count() == 0)
		{
			return label + " none";
		}
		return String.format("%s %dx p50 %.3f p99 %.3f max %.3f ms", label, t.count(), t.quantile(0.5) / 1e6,
			t.quantile(0.99) / 1e6, t.max() / 1e6);
	}

	private String budgets()
	{
		final List<String> out = new ArrayList<>();
		final long overlayP99 = Math.max(timers[Slot.OVERLAY.ordinal()].quantile(0.99),
			timers[Slot.LOADOUT_OVERLAY.ordinal()].quantile(0.99));
		out.add(String.format("overlay render p99 %.3f ms %s 0.2", overlayP99 / 1e6, overlayP99 < 200_000 ? "<" : ">="));
		Slot worstP99 = null;
		Slot worstMax = null;
		for (Slot s : Slot.values())
		{
			if (s == Slot.OVERLAY || s == Slot.LOADOUT_OVERLAY || timers[s.ordinal()].count() == 0)
			{
				continue;
			}
			if (worstP99 == null || timers[s.ordinal()].quantile(0.99) > timers[worstP99.ordinal()].quantile(0.99))
			{
				worstP99 = s;
			}
			if (worstMax == null || timers[s.ordinal()].max() > timers[worstMax.ordinal()].max())
			{
				worstMax = s;
			}
		}
		if (worstP99 != null)
		{
			final long p99 = timers[worstP99.ordinal()].quantile(0.99);
			final long max = timers[worstMax.ordinal()].max();
			out.add(String.format("worst handler p99 %.3f ms (%s) %s 1, worst max %.3f ms (%s) %s 3", p99 / 1e6,
				worstP99.label, p99 < 1_000_000 ? "<" : ">=", max / 1e6, worstMax.label, max < 3_000_000 ? "<" : ">="));
		}
		synchronized (swingLock)
		{
			final long apply = swingTimers[SwingSlot.APPLY.ordinal()].quantile(0.99);
			final long paint = swingTimers[SwingSlot.PAINT_AFTER_APPLY.ordinal()].quantile(0.99);
			out.add(String.format("panel apply + repaint p99 %.3f ms %s 2", (apply + paint) / 1e6,
				apply + paint < 2_000_000 ? "<" : ">="));
			final long input = inputDelay.quantile(0.99);
			out.add(String.format("input delay p99 %.3f ms %s 16, over 50 ms %d %s 0", input / 1e6,
				input < INPUT_SLOW_NANOS ? "<" : ">=", inputStuck, inputStuck == 0 ? "=" : ">"));
		}
		return String.join("; ", out);
	}

	private static int maxMicros(int[] micros, int n)
	{
		int max = 0;
		for (int i = 0; i < n; i++)
		{
			max = Math.max(max, micros[i]);
		}
		return max;
	}

	/** The q quantile of the first n values (nearest rank); 0 for none. Sorts a copy: report time only. */
	static long quantile(int[] values, int n, double q)
	{
		if (n <= 0)
		{
			return 0;
		}
		final int[] sorted = Arrays.copyOf(values, n);
		Arrays.sort(sorted);
		final int rank = (int) Math.ceil(q * n) - 1;
		return sorted[Math.max(0, Math.min(n - 1, rank))];
	}
}

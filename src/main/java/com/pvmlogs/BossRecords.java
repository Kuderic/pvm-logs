package com.pvmlogs;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * T-187: a record per boss that outlives history's budget (2,000 boss fights, about four days of Eric's Araxxor): kills,
 * personal bests, every kill's clock for its rank, the last 50 executions for the grade, the grades' spread, a trend of
 * the luck-removed time, the streaks, and how often each badge was earned (T-192, by {@link Badges}' rules). The fun pass reads it (API agreed with the Side panel chat in
 * {@code claude/specs/t187-t188-record-api.md}):
 * <ul>
 * <li>{@link #standing(Encounter)}: one counted kill's place in its record, as it stood when it was recorded (T-188's
 * kill card, T-189's badges). A lookup by the room's start, no recount.</li>
 * <li>{@link #view(LedgerHistory.Like)}: one like-kill key's record (T-190's History top).</li>
 * </ul>
 * Kept in {@code records.json} beside {@code history.json}, saved as history is (T-178: a temp file forced to the disk,
 * an atomic move, a last-good copy; an unreadable file moved aside and never written over). Seeded once from history
 * and the history backups in the data folder; updated once per counted kill as history is saved.
 * <p>
 * Threads: a save's rooms are rebuilt and their figures worked out outside the lock; only the recording takes it, and
 * it's short. Both views are immutable, so the client thread reads them as it builds the panel's snapshot.
 */
final class BossRecords
{
	static final String FILE = "records.json";
	static final String TEMP = "records.json.tmp";
	static final String LAST_GOOD = "records-last-good.json";
	static final String LAST_GOOD_TEMP = "records-last-good.json.tmp";
	static final String UNREADABLE_PREFIX = "records-unreadable-";
	/** The window the grade is read against (T-186). */
	static final int GRADE_WINDOW = 50;
	/** Below this many earlier kills, the grade's fixed bands (T-186). */
	static final int GRADE_FROM_KILLS = 10;
	/** T-186's fixed bands, on execution. */
	static final double BAND_S = 0.995;
	static final double BAND_A = 0.975;
	static final double BAND_B = 0.90;
	/** T-186's shares of your last 50 below a kill (ties half): S your top 5%, A the next 25%, B the middle 60%. */
	static final double SHARE_S = 0.95;
	static final double SHARE_A = 0.70;
	static final double SHARE_B = 0.10;
	/** The luck-removed trend's length per key, newest kept (and the kills History's rows can read a grade for). */
	static final int TREND_KILLS = 500;
	/** The standings kept in memory for {@link #standing}: this session's kills, and each key's newest from the file. */
	static final int STANDINGS_KEPT = 500;
	static final double SECONDS_PER_TICK = 0.6;
	/** 2 (T-192): the badges counted. A file of version 1 has them counted once, from history and its backups. */
	static final int VERSION = 2;

	enum Grade
	{
		S, A, B, C;

		/** The grade above, or null at S. */
		Grade next()
		{
			return this == C ? B : this == B ? A : this == A ? S : null;
		}

		boolean atLeastA()
		{
			return this == S || this == A;
		}
	}

	/** A grade, what it was read against in words, and how many ticks short of the next one. */
	static final class Graded
	{
		final Grade grade;
		/** "top 12% of last 50", "bottom 8% of last 16", "best of last 50", "lowest of last 16", "fixed bands (4 kills)". */
		final String basis;
		/** The grade above, or null at S. */
		final Grade next;
		/** Ticks of uptime that would have reached {@link #next} (at least 1); 0 at S or when it can't be told. */
		final int ticksToNext;

		Graded(Grade grade, String basis, int ticksToNext)
		{
			this.grade = grade;
			this.basis = basis;
			this.next = grade.next();
			this.ticksToNext = next == null ? 0 : ticksToNext;
		}

		/** "2 ticks from an S", or null at S or unknown. */
		String tooltip()
		{
			return next == null || ticksToNext <= 0 ? null
				: ticksToNext + (ticksToNext == 1 ? " tick" : " ticks") + " from " + (next == Grade.B ? "a B" : "an " + next);
		}

		@Override
		public String toString()
		{
			return grade + " (" + basis + ")";
		}
	}

	/** One counted kill's place in its boss's record, as it stood when the kill was recorded. Immutable. */
	static final class Standing
	{
		/** The room's start (its first fight's): which kill this is. */
		final long startMillis;
		/** This kill's clock, in seconds. */
		final double clock;
		/** The best clock before this kill, in seconds; NaN with no earlier kill. */
		final double previousBestClock;
		final boolean newPb;
		/** This kill's luck-removed time ({@code Numbers.expectedDuration}), in seconds; NaN when unknown. */
		final double luckRemoved;
		/** The best luck-removed time before this kill; NaN with none. */
		final double previousBestLuckRemoved;
		final boolean newBestLuckRemoved;
		/** 1 + the recorded kills strictly faster (a tie shares the better rank), this one counted in {@link #ranked}. */
		final int rank;
		final int ranked;
		final double execution;
		/** Luck's percentile, 0 to 1 ({@code Numbers.percentile}); NaN when unknown. "rolled N of 100": {@link #roll}. */
		final double luckPercentile;
		/** Null when execution is unknown. */
		final Grade grade;
		final String basis;
		final Grade next;
		final int ticksToNext;
		/** A or better in a row, this one included; 0 when this one isn't. */
		final int streak;
		/** No avoidable damage in a row, this one included; -1 at a boss without attack rules. */
		final int flawlessStreak;
		/** The game's kill count after this kill, 0 unknown. */
		final int kc;
		/**
		 * The badges this kill earned (T-192's {@link Badges} rules), in the card's order; "3 A's in a row" only on the kill
		 * its run reached 3 on ({@link #streak} names the run on later kills). Unmodifiable.
		 */
		final Set<Badges.Badge> badges;

		private Standing(Kill k)
		{
			startMillis = k.startMillis;
			clock = k.clockTicks * SECONDS_PER_TICK;
			previousBestClock = k.previousBestClock;
			newPb = k.newPb;
			luckRemoved = k.luckRemoved;
			previousBestLuckRemoved = k.previousBestLuckRemoved;
			newBestLuckRemoved = k.newBestLuckRemoved;
			rank = k.rank;
			ranked = k.ranked;
			execution = k.execution;
			luckPercentile = k.luckPercentile;
			grade = k.grade;
			basis = k.basis;
			next = grade == null ? null : grade.next();
			ticksToNext = k.ticksToNext;
			streak = k.streak;
			flawlessStreak = k.flawlessStreak;
			kc = k.kc;
			final EnumSet<Badges.Badge> b = EnumSet.noneOf(Badges.Badge.class);
			if (k.badges != null)
			{
				for (Badges.Badge badge : k.badges)
				{
					if (badge != null)
					{
						b.add(badge);
					}
				}
			}
			badges = Collections.unmodifiableSet(b);
		}

		/** "rolled N of 100": 1 to 100, or 0 when unknown. */
		int roll()
		{
			return BossRecords.roll(luckPercentile);
		}

		/** "2 ticks from an S", or null. */
		String tooltip()
		{
			return grade == null ? null : new Graded(grade, basis, ticksToNext).tooltip();
		}
	}

	/** A best: its value (seconds, or execution's share), when it was set, and for the fastest its luck. */
	static final class Best
	{
		final double value;
		final long millis;
		/** 0 to 1; NaN unknown (and for all but the fastest). */
		final double luckPercentile;

		Best(double value, long millis, double luckPercentile)
		{
			this.value = value;
			this.millis = millis;
			this.luckPercentile = luckPercentile;
		}

		int roll()
		{
			return BossRecords.roll(luckPercentile);
		}
	}

	/** One like-kill key's record. Immutable: a copy made when asked. */
	static final class View
	{
		/** Tracked, seeded ones included. */
		final int kills;
		final long firstMillis;
		final Best fastest;
		final Best luckRemoved;
		final Best execution;
		final int streak;
		final int longestStreak;
		/** -1 without attack rules. */
		final int flawlessStreak;
		/** S, A, B, C counts over the tracked kills. */
		final int[] grades;
		/** {millis, seconds} per kill, oldest first; the last {@link #TREND_KILLS}. */
		final double[][] luckRemovedTrend;
		/** The newest kill's standing, or null. */
		final Standing last;
		/**
		 * T-192: how many kills earned each badge ("3 A's in a row": the runs that reached 3; the best run is
		 * {@link #longestStreak}). A badge never earned is absent, and Flawless always is at a boss without attack rules.
		 * Unmodifiable, in the card's order.
		 */
		final Map<Badges.Badge, Integer> badgeCounts;
		private final int[] clocks;
		private final int[] counts;
		private final double[] executions;
		private final Map<Long, Grade> gradeByStart;

		private View(Record r)
		{
			kills = r.kills;
			firstMillis = r.firstMillis;
			fastest = new Best(r.bestClockTicks * SECONDS_PER_TICK, r.bestClockMillis, r.bestClockPercentile);
			luckRemoved = new Best(r.bestLuckRemoved, r.bestLuckRemovedMillis, Double.NaN);
			execution = new Best(r.bestExecution, r.bestExecutionMillis, Double.NaN);
			streak = r.streak;
			longestStreak = r.longestStreak;
			flawlessStreak = r.flawlessStreak;
			grades = r.grades.clone();
			luckRemovedTrend = new double[r.trend.size()][];
			gradeByStart = new HashMap<>();
			for (int i = 0; i < luckRemovedTrend.length; i++)
			{
				final Point p = r.trend.get(i);
				luckRemovedTrend[i] = new double[]{p.start, p.luckRemoved};
				if (p.grade != null)
				{
					gradeByStart.put(p.start, p.grade);
				}
			}
			last = r.last == null ? null : new Standing(r.last);
			final Map<Badges.Badge, Integer> earned = new EnumMap<>(Badges.Badge.class);
			for (Badges.Badge b : Badges.Badge.values())
			{
				if (r.badges[b.ordinal()] > 0 && (b != Badges.Badge.FLAWLESS || r.flawlessStreak >= 0))
				{
					earned.put(b, r.badges[b.ordinal()]);
				}
			}
			badgeCounts = Collections.unmodifiableMap(earned);
			clocks = new int[r.clocks.size()];
			counts = new int[clocks.length];
			int i = 0;
			for (Map.Entry<Integer, Integer> e : r.clocks.entrySet())
			{
				clocks[i] = e.getKey();
				counts[i++] = e.getValue();
			}
			executions = r.executions.stream().mapToDouble(Double::doubleValue).toArray();
		}

		/** "Nth fastest": 1 + the recorded kills strictly faster than {@code clockSeconds}. Of {@link #kills}. */
		int rank(double clockSeconds)
		{
			final int ticks = ticks(clockSeconds);
			int faster = 0;
			for (int i = 0; i < clocks.length && clocks[i] < ticks; i++)
			{
				faster += counts[i];
			}
			return faster + 1;
		}

		/** The grade {@code execution} would get against the last 50 recorded (a kill not yet recorded); null if NaN. */
		Graded grade(double execution, double clockSeconds)
		{
			return BossRecords.grade(execution, clockSeconds, executions);
		}

		/** T-192: how many kills earned {@code badge}; 0 when never (or Flawless without attack rules). */
		int badgeCount(Badges.Badge badge)
		{
			return badgeCounts.getOrDefault(badge, 0);
		}

		/** The grade a kill in the trend got when it was recorded (History's rows), or null. */
		Grade gradeOf(long startMillis)
		{
			return gradeByStart.get(startMillis);
		}
	}

	// ---- what's saved (Gson's: fields only) ----

	/** A kill's standing, as saved. */
	static final class Kill
	{
		long startMillis;
		int clockTicks;
		double previousBestClock = Double.NaN;
		boolean newPb;
		double luckRemoved = Double.NaN;
		double previousBestLuckRemoved = Double.NaN;
		boolean newBestLuckRemoved;
		int rank;
		int ranked;
		double execution = Double.NaN;
		double luckPercentile = Double.NaN;
		Grade grade;
		String basis;
		int ticksToNext;
		int streak;
		int flawlessStreak = -1;
		int kc;
		List<Badges.Badge> badges;
	}

	/** One kill in the luck-removed trend. */
	static final class Point
	{
		long start;
		double luckRemoved;
		Grade grade;
	}

	/** One like-kill key's record. */
	static final class Record
	{
		String name;
		int maxHp;
		boolean solo;
		// Raids, later (keyed by raid and level); null and 0 for a boss.
		String raid;
		int raidLevel;
		int kills;
		long firstMillis;
		long lastStartMillis;
		int bestClockTicks;
		long bestClockMillis;
		double bestClockPercentile = Double.NaN;
		double bestLuckRemoved = Double.NaN;
		long bestLuckRemovedMillis;
		double bestExecution = Double.NaN;
		long bestExecutionMillis;
		/** Every kill's clock, by tick: exact ranks in a size bounded by the distinct clocks (a few hundred at most). */
		TreeMap<Integer, Integer> clocks = new TreeMap<>();
		/** The last {@link #GRADE_WINDOW} executions, oldest first. */
		List<Double> executions = new ArrayList<>();
		/** S, A, B, C. */
		int[] grades = new int[4];
		/** The last {@link #TREND_KILLS} kills, oldest first. */
		List<Point> trend = new ArrayList<>();
		int streak;
		int longestStreak;
		/** Kills in a row with no avoidable damage, at bosses with attack rules; -1 where none are known. */
		int flawlessStreak = -1;
		/** T-192: kills that earned each badge, by {@link Badges.Badge#ordinal()} ("3 A's in a row" once per run). */
		int[] badges = new int[Badges.Badge.values().length];
		Kill last;
	}

	private static final class Saved
	{
		int version;
		List<Record> records;
	}

	private final Filepath dir;
	private final Gson gson;
	private final Map<String, Record> byKey = new LinkedHashMap<>();
	// The standings standing() looks up, by the room's start: a map of its own, so a lookup never waits on a recording.
	private final Map<Long, Standing> standings = Collections.synchronizedMap(new LinkedHashMap<Long, Standing>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Standing> eldest)
		{
			return size() > STANDINGS_KEPT;
		}
	});
	// An unreadable records.json that couldn't be moved aside: never saved over.
	private boolean blocked;
	// The kills recorded since recordedLines was last asked, as the debug log's lines.
	private final List<String> recorded = new ArrayList<>();

	BossRecords(Filepath dir, Gson gson)
	{
		this.dir = dir;
		// An unknown best or luck is NaN, which plain JSON has no word for: written as NaN, read back leniently (as
		// Gson's fromJson reads).
		this.gson = gson.newBuilder().serializeSpecialFloatingPointValues().create();
	}

	static String key(String name, int maxHp, boolean solo)
	{
		return name + "|" + maxHp + "|" + (solo ? "solo" : "party");
	}

	static String key(LedgerHistory.Like like)
	{
		return key(like.name, like.maxHp, like.solo);
	}

	// ---- reading ----

	/**
	 * The standing of the counted kill that is {@code room}, if it has been recorded (it is as its history save
	 * runs); else null. A lookup by the room's start: this session's kills and each key's newest.
	 */
	Standing standing(Encounter room)
	{
		if (room == null)
		{
			return null;
		}
		final Standing s = standings.get(room.getStartMillis());
		if (s != null || room.getFights().isEmpty())
		{
			return s;
		}
		return standings.get(room.getFights().get(0).getStartMillis());
	}

	/** One key's record as an immutable copy, or null with no kill recorded. */
	synchronized View view(LedgerHistory.Like like)
	{
		final Record r = like == null || like.name == null ? null : byKey.get(key(like));
		return r == null || r.kills == 0 ? null : new View(r);
	}

	synchronized int size()
	{
		return byKey.size();
	}

	// ---- the grade (T-186) ----

	/**
	 * The grade of {@code execution} (0 to 1) against {@code earlier}, the executions before it (oldest first; only the
	 * last {@link #GRADE_WINDOW} are read). With fewer than {@link #GRADE_FROM_KILLS}, fixed bands: S from 99.5%, A from
	 * 97.5%, B from 90%, else C. Then by the share of them below it, ties counted half (T-186's {@code SidePanelFun}): S
	 * from 95% (your top 5%), A from 70% (the next 25%), B from 10% (the middle 60%), C your bottom 10%. The ticks to the
	 * next grade read one more tick of uptime as one more combat tick's share of execution; 0 without a clock.
	 */
	static Graded grade(double execution, double clockSeconds, double[] earlier)
	{
		if (Double.isNaN(execution))
		{
			return null;
		}
		final int combatTicks = clockSeconds > 0 ? ticks(clockSeconds) : 0;
		final double[] window = Arrays.copyOfRange(earlier, Math.max(0, earlier.length - GRADE_WINDOW), earlier.length);
		final int n = window.length;
		if (n < GRADE_FROM_KILLS)
		{
			final Grade g = band(execution);
			final double up = g == Grade.C ? BAND_B : g == Grade.B ? BAND_A : g == Grade.A ? BAND_S : Double.NaN;
			final int ticks = Double.isNaN(up) || combatTicks <= 0 ? 0
				: Math.max(1, (int) Math.ceil((up - execution) * combatTicks - 1e-9));
			return new Graded(g, "fixed bands (" + n + (n == 1 ? " kill)" : " kills)"), ticks);
		}
		final double share = share(execution, window);
		final Grade g = byShare(share);
		int ticks = 0;
		if (g != Grade.S && combatTicks > 0)
		{
			// One tick at a time, as far as a whole fight's worth: the first that reaches the grade above.
			for (int t = 1; t <= combatTicks; t++)
			{
				if (byShare(share(execution + (double) t / combatTicks, window)).ordinal() < g.ordinal())
				{
					ticks = t;
					break;
				}
			}
		}
		return new Graded(g, basis(share, n), ticks);
	}

	private static Grade band(double execution)
	{
		return execution >= BAND_S ? Grade.S : execution >= BAND_A ? Grade.A : execution >= BAND_B ? Grade.B : Grade.C;
	}

	private static Grade byShare(double share)
	{
		return share >= SHARE_S ? Grade.S : share >= SHARE_A ? Grade.A : share >= SHARE_B ? Grade.B : Grade.C;
	}

	/** The share of {@code window} below {@code x}, ties counted half (T-186). */
	static double share(double x, double[] window)
	{
		double below = 0;
		for (double v : window)
		{
			below += v < x ? 1 : v == x ? 0.5 : 0;
		}
		return window.length == 0 ? Double.NaN : below / window.length;
	}

	/** Where an execution sits in your last {@code n}, as T-186's {@code SidePanelFun.basis} words it. */
	static String basis(double share, int n)
	{
		if (share >= 1)
		{
			return "best of last " + n;
		}
		if (share <= 0)
		{
			return "lowest of last " + n;
		}
		return share >= 0.5 ? "top " + Math.max(1, Math.round((1 - share) * 100)) + "% of last " + n
			: "bottom " + Math.max(1, Math.round(share * 100)) + "% of last " + n;
	}

	// ---- updating ----

	/** A counted kill's figures, read off its room as the side panel reads them. */
	static final class KillFigures
	{
		final LedgerHistory.Like like;
		final long startMillis;
		final double clock;
		final double execution;
		final double luckRemoved;
		final double percentile;
		/** Null where the boss has no attack rules; otherwise whether no avoidable damage was taken. */
		final Boolean flawless;
		/** The game's kill count after this kill, 0 unknown. */
		final int kc;
		/** {@link Badges#cleanRun}. */
		final boolean cleanRun;

		KillFigures(LedgerHistory.Like like, long startMillis, double clock, double execution, double luckRemoved,
			double percentile, Boolean flawless)
		{
			this(like, startMillis, clock, execution, luckRemoved, percentile, flawless, 0, false);
		}

		KillFigures(LedgerHistory.Like like, long startMillis, double clock, double execution, double luckRemoved,
			double percentile, Boolean flawless, int kc, boolean cleanRun)
		{
			this.like = like;
			this.startMillis = startMillis;
			this.clock = clock;
			this.execution = execution;
			this.luckRemoved = luckRemoved;
			this.percentile = percentile;
			this.flawless = flawless;
			this.kc = kc;
			this.cleanRun = cleanRun;
		}

		/** The after-kill execution (with avoidable eating, at bosses with attack rules), as the card shows it. */
		static KillFigures of(Encounter room)
		{
			final LedgerRows.Scope scope = LedgerRows.of(room);
			final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
			// The rules the card reads (T-192): Flawless by the scope's avoidable damage, where attack rules tell it.
			final Boolean flawless = Badges.ruled(n) ? Badges.flawless(n, scope.damageTakenAvoidable()) : null;
			int kc = 0;
			for (Fight fight : room.getFights())
			{
				kc = Math.max(kc, fight.getContext() == null ? 0 : fight.getContext().kc);
			}
			return new KillFigures(LedgerHistory.Like.of(room), room.getStartMillis(), n.clock, n.execution,
				n.expectedDuration, n.percentile, flawless, kc, Badges.cleanRun(n));
		}
	}

	/** The RECORD lines for the kills recorded since last asked, then cleared. */
	synchronized List<String> recordedLines()
	{
		final List<String> lines = new ArrayList<>(recorded);
		recorded.clear();
		return lines;
	}

	/** Records one counted kill, once: a kill no newer than its record's newest is left alone. True if recorded. */
	synchronized boolean record(KillFigures k)
	{
		if (k.like == null || k.like.name == null || k.clock <= 0)
		{
			return false;
		}
		final String key = key(k.like);
		Record r = byKey.get(key);
		if (r == null)
		{
			r = new Record();
			r.name = k.like.name;
			r.maxHp = k.like.maxHp;
			r.solo = k.like.solo;
			byKey.put(key, r);
		}
		if (r.kills > 0 && k.startMillis <= r.lastStartMillis)
		{
			return false;
		}
		final int ticks = ticks(k.clock);
		final Graded graded = grade(k.execution, k.clock, r.executions.stream().mapToDouble(Double::doubleValue).toArray());
		final Kill kill = new Kill();
		kill.startMillis = k.startMillis;
		kill.clockTicks = ticks;
		kill.luckRemoved = k.luckRemoved;
		kill.execution = k.execution;
		kill.luckPercentile = k.percentile;
		if (graded != null)
		{
			kill.grade = graded.grade;
			kill.basis = graded.basis;
			kill.ticksToNext = graded.ticksToNext;
		}
		if (r.kills == 0)
		{
			r.firstMillis = k.startMillis;
		}
		else
		{
			kill.previousBestClock = r.bestClockTicks * SECONDS_PER_TICK;
			kill.previousBestLuckRemoved = r.bestLuckRemoved;
		}
		if (r.kills == 0 || ticks < r.bestClockTicks)
		{
			kill.newPb = r.kills > 0;
			r.bestClockTicks = ticks;
			r.bestClockMillis = k.startMillis;
			r.bestClockPercentile = k.percentile;
		}
		if (!Double.isNaN(k.luckRemoved) && (Double.isNaN(r.bestLuckRemoved) || k.luckRemoved < r.bestLuckRemoved))
		{
			kill.newBestLuckRemoved = !Double.isNaN(r.bestLuckRemoved);
			r.bestLuckRemoved = k.luckRemoved;
			r.bestLuckRemovedMillis = k.startMillis;
		}
		if (!Double.isNaN(k.execution) && (Double.isNaN(r.bestExecution) || k.execution > r.bestExecution))
		{
			r.bestExecution = k.execution;
			r.bestExecutionMillis = k.startMillis;
		}
		r.kills++;
		r.lastStartMillis = k.startMillis;
		r.clocks.merge(ticks, 1, Integer::sum);
		int faster = 0;
		for (int count : r.clocks.headMap(ticks, false).values())
		{
			faster += count;
		}
		kill.rank = faster + 1;
		kill.ranked = r.kills;
		if (!Double.isNaN(k.execution))
		{
			r.executions.add(k.execution);
			while (r.executions.size() > GRADE_WINDOW)
			{
				r.executions.remove(0);
			}
		}
		if (graded != null)
		{
			r.grades[graded.grade.ordinal()]++;
		}
		r.streak = graded != null && graded.grade.atLeastA() ? r.streak + 1 : 0;
		r.longestStreak = Math.max(r.longestStreak, r.streak);
		if (k.flawless != null)
		{
			r.flawlessStreak = k.flawless ? Math.max(0, r.flawlessStreak) + 1 : 0;
		}
		kill.streak = r.streak;
		kill.flawlessStreak = r.flawlessStreak;
		kill.kc = k.kc;
		kill.badges = new ArrayList<>(Badges.earned(k.kc, Boolean.TRUE.equals(k.flawless), k.cleanRun, r.streak));
		for (Badges.Badge b : kill.badges)
		{
			r.badges[b.ordinal()]++;
		}
		final Point p = new Point();
		p.start = k.startMillis;
		p.luckRemoved = k.luckRemoved;
		p.grade = kill.grade;
		r.trend.add(p);
		while (r.trend.size() > TREND_KILLS)
		{
			r.trend.remove(0);
		}
		r.last = kill;
		standings.put(k.startMillis, new Standing(kill));
		if (recorded.size() < 20)
		{
			recorded.add(String.format("RECORD %s (%d hp, %s): kill %d, %s fastest of %d (%.1f s%s), grade %s, luck rolled "
					+ "%d of 100%s%s", r.name, r.maxHp, r.solo ? "solo" : "party", r.kills, ordinal(kill.rank), r.kills,
				ticks * SECONDS_PER_TICK, kill.newPb ? ", a new PB" : "", graded == null ? "unknown" : graded.toString(),
				roll(k.percentile), kill.newBestLuckRemoved ? ", a new luck-removed best" : "",
				r.streak >= 3 ? ", " + r.streak + " A's in a row" : "") + (kill.badges.isEmpty() ? ""
				: ", badges " + kill.badges.stream().map(b -> b.title).collect(Collectors.joining(", "))));
		}
		return true;
	}

	static String ordinal(int n)
	{
		final int mod100 = n % 100;
		final String suffix = mod100 >= 11 && mod100 <= 13 ? "th"
			: n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
		return n + suffix;
	}

	/**
	 * Records the counted kills in {@code history} (newest first, as the plugin keeps it) newer than each record's
	 * newest. The rooms are rebuilt as the side panel rebuilds them ({@link LedgerHistory#rooms}), for the bosses of the
	 * last {@code recentFights} fights only, or of all of them when that's 0 (seeding). The rebuilding is done outside
	 * the lock. True if any was recorded.
	 */
	boolean noteHistory(List<Fight> history, int recentFights)
	{
		boolean any = false;
		for (KillFigures k : figures(history, recentFights, newestByKey()))
		{
			any |= record(k);
		}
		return any;
	}

	/**
	 * The counted kills in {@code history} (newest first) newer than {@code newest} has for their key, oldest first across
	 * bosses so each record's streaks and windows run in order: for the bosses of the last {@code recentFights} fights,
	 * or all when 0.
	 */
	private static List<KillFigures> figures(List<Fight> history, int recentFights, Map<String, Long> newest)
	{
		final Set<String> names = new HashSet<>();
		final int upTo = recentFights <= 0 ? history.size() : Math.min(history.size(), recentFights);
		for (int i = 0; i < upTo; i++)
		{
			final Fight f = history.get(i);
			if (f.getRaidName() == null && f.encounterName() != null)
			{
				names.add(f.encounterName());
			}
		}
		final Set<Fight> none = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<KillFigures> kills = new ArrayList<>();
		for (String name : names)
		{
			for (Encounter room : LedgerHistory.rooms(history, name))
			{
				// Only what's newer than its record's newest is worked out: a save looks again at rooms already recorded.
				final Long seen = newest.get(key(LedgerHistory.Like.of(room)));
				if ((seen == null || room.getStartMillis() > seen) && LedgerHistory.counts(room, none))
				{
					kills.add(KillFigures.of(room));
				}
			}
		}
		kills.sort((x, y) -> Long.compare(x.startMillis, y.startMillis));
		return kills;
	}

	private synchronized Map<String, Long> newestByKey()
	{
		final Map<String, Long> newest = new HashMap<>();
		for (Map.Entry<String, Record> e : byKey.entrySet())
		{
			if (e.getValue().kills > 0)
			{
				newest.put(e.getKey(), e.getValue().lastStartMillis);
			}
		}
		return newest;
	}

	// ---- the file ----

	/** Saved as history is: beside the file, forced, moved over it (T-178). */
	synchronized void save() throws IOException
	{
		if (blocked)
		{
			throw new IOException(FILE + " didn't read and couldn't be moved aside, so it isn't saved over");
		}
		final Saved saved = new Saved();
		saved.version = VERSION;
		saved.records = new ArrayList<>(byKey.values());
		final byte[] bytes = gson.toJson(saved).getBytes(StandardCharsets.UTF_8);
		dir.createDirectories();
		HistoryStore.writeWhole(dir.joinSegment(TEMP), dir.joinSegment(FILE), bytes);
	}

	/**
	 * Loads {@code records.json}; or, where there's none, or it didn't read (moved aside as
	 * {@code records-unreadable-<stamp>.json}, untouched), the last good copy, or a seeding from {@code history} and the
	 * history backups in the data folder. Returns the debug log's RECORDS line.
	 */
	synchronized String load(List<Fight> history, long nowMillis)
	{
		final String line = loadOrSeed(history, nowMillis);
		// A seeding's or a catching up's kills aren't news: no RECORD lines for them.
		recorded.clear();
		return line;
	}

	private String loadOrSeed(List<Fight> history, long nowMillis)
	{
		final Filepath file = dir.joinSegment(FILE);
		String why;
		if (file.exists())
		{
			final Saved read = read(file);
			if (read != null)
			{
				put(read.records);
				if (!byKey.isEmpty())
				{
					keepLastGood(file);
				}
				final String counted = read.version < 2 ? "; " + countBadges(history) : "";
				// Anything since it was last saved (a crash before the save): caught up from history.
				final boolean caught = noteHistory(history, 0);
				return String.format("RECORDS loaded %d bosses from %s%s%s", byKey.size(), FILE,
					caught ? ", caught up from history" : "", counted);
			}
			final String aside = UNREADABLE_PREFIX + HistoryStore.stamp(nowMillis) + ".json";
			try
			{
				file.moveTo(dir.joinSegment(aside));
				why = FILE + " didn't read, moved aside as " + aside;
			}
			catch (IOException e)
			{
				blocked = true;
				why = FILE + " didn't read and couldn't be moved aside (" + e.getMessage() + "): not saved this session";
			}
			final Filepath lastGood = dir.joinSegment(LAST_GOOD);
			final Saved good = lastGood.exists() ? read(lastGood) : null;
			if (good != null)
			{
				put(good.records);
				final String counted = good.version < 2 ? "; " + countBadges(history) : "";
				noteHistory(history, 0);
				return String.format("RECORDS %s; loaded %d bosses from %s, caught up from history%s", why, byKey.size(),
					LAST_GOOD, counted);
			}
		}
		else
		{
			why = "no " + FILE + " yet";
		}
		final int[] seeded = seed(history);
		return String.format("RECORDS %s; seeded %d bosses from %d fights (history and %d backups, %d fights read from "
			+ "them)", why, byKey.size(), seeded[0], seeded[1], seeded[2]);
	}

	/** Seeding: history and its backups, deduplicated by start time and target. {fights, backups, from backups}. */
	private int[] seed(List<Fight> history)
	{
		final Merged merged = merge(history);
		noteHistory(merged.fights, 0);
		return new int[]{merged.fights.size(), merged.backups, merged.fromBackups};
	}

	/** History and its backups, deduplicated by start time and target, newest first as history is kept. */
	private static final class Merged
	{
		final List<Fight> fights;
		final int backups;
		final int fromBackups;

		Merged(List<Fight> fights, int backups, int fromBackups)
		{
			this.fights = fights;
			this.backups = backups;
			this.fromBackups = fromBackups;
		}
	}

	private Merged merge(List<Fight> history)
	{
		final Map<String, Fight> byStart = new HashMap<>();
		for (Fight f : history)
		{
			byStart.putIfAbsent(f.getStartMillis() + "|" + f.getTargetName(), f);
		}
		int backups = 0;
		int fromBackups = 0;
		for (Filepath backup : backups())
		{
			final Fight[] fights = readFights(backup);
			if (fights == null)
			{
				continue;
			}
			backups++;
			for (Fight f : fights)
			{
				if (f != null && byStart.putIfAbsent(f.getStartMillis() + "|" + f.getTargetName(), f) == null)
				{
					fromBackups++;
				}
			}
		}
		final List<Fight> all = new ArrayList<>(byStart.values());
		all.sort((a, b) -> Long.compare(b.getStartMillis(), a.getStartMillis()));
		return new Merged(all, backups, fromBackups);
	}

	/**
	 * T-192, once, for a file of version 1: each record's badge counts, from the kills in history and the backups the
	 * seeding read, up to the record's newest kill. They're replayed through a record of their own, so the "3 A's in a
	 * row" runs follow the grades as they fell; only the counts (and the newest kill's badges and kill count) are taken
	 * from it, and every other field is kept. Returns the debug log's words.
	 */
	private String countBadges(List<Fight> history)
	{
		final Merged merged = merge(history);
		final BossRecords replay = new BossRecords(dir, gson);
		int kills = 0;
		for (KillFigures k : figures(merged.fights, 0, Collections.emptyMap()))
		{
			final Record r = k.like == null || k.like.name == null ? null : byKey.get(key(k.like));
			if (r != null && k.startMillis <= r.lastStartMillis && replay.record(k))
			{
				kills++;
			}
		}
		final int[] total = new int[Badges.Badge.values().length];
		for (Map.Entry<String, Record> e : byKey.entrySet())
		{
			final Record r = e.getValue();
			final Record counted = replay.byKey.get(e.getKey());
			r.badges = counted == null ? new int[total.length] : counted.badges.clone();
			for (int i = 0; i < total.length; i++)
			{
				total[i] += r.badges[i];
			}
			if (counted != null && counted.last != null && r.last != null && counted.last.startMillis == r.last.startMillis)
			{
				r.last.kc = counted.last.kc;
				r.last.badges = counted.last.badges;
				standings.put(r.last.startMillis, new Standing(r.last));
			}
		}
		final StringBuilder line = new StringBuilder(String.format("badges counted for %d bosses from %d kills (history "
			+ "and %d backups):", byKey.size(), kills, merged.backups));
		for (Badges.Badge b : Badges.Badge.values())
		{
			line.append(b.ordinal() == 0 ? " " : ", ").append(b.title).append(' ').append(total[b.ordinal()]);
		}
		return line.toString();
	}

	/** The project's history backups in the data folder, read only: {@code history.json.bak-*}, {@code history-before-*.json.bak}. */
	private List<Filepath> backups()
	{
		try (Stream<Filepath> files = dir.walk(1))
		{
			return files.filter(f -> isBackup(f.getFileName())).sorted((a, b) -> a.getFileName().compareTo(b.getFileName()))
				.collect(Collectors.toList());
		}
		catch (IOException | RuntimeException e)
		{
			return Collections.emptyList();
		}
	}

	static boolean isBackup(String name)
	{
		return name != null && (name.startsWith("history.json.bak-")
			|| name.startsWith("history-before-") && name.endsWith(".json.bak"));
	}

	private Fight[] readFights(Filepath file)
	{
		try (InputStream in = file.openInputStream())
		{
			return gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Fight[].class);
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	private Saved read(Filepath file)
	{
		try (InputStream in = file.openInputStream())
		{
			final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (json.chars().allMatch(c -> c == 0 || Character.isWhitespace(c)))
			{
				return null;
			}
			final Saved saved = gson.fromJson(json, Saved.class);
			if (saved == null || saved.records == null || saved.records.contains(null))
			{
				return null;
			}
			return saved;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	private void put(List<Record> records)
	{
		byKey.clear();
		standings.clear();
		for (Record r : records)
		{
			if (r.name == null)
			{
				continue;
			}
			if (r.clocks == null)
			{
				r.clocks = new TreeMap<>();
			}
			if (r.executions == null)
			{
				r.executions = new ArrayList<>();
			}
			if (r.grades == null || r.grades.length != 4)
			{
				r.grades = new int[4];
			}
			if (r.trend == null)
			{
				r.trend = new ArrayList<>();
			}
			if (r.badges == null || r.badges.length != Badges.Badge.values().length)
			{
				r.badges = Arrays.copyOf(r.badges == null ? new int[0] : r.badges, Badges.Badge.values().length);
			}
			byKey.put(key(r.name, r.maxHp, r.solo), r);
			if (r.last != null)
			{
				standings.put(r.last.startMillis, new Standing(r.last));
			}
		}
	}

	private void keepLastGood(Filepath file)
	{
		try (InputStream in = file.openInputStream())
		{
			HistoryStore.writeWhole(dir.joinSegment(LAST_GOOD_TEMP), dir.joinSegment(LAST_GOOD), in.readAllBytes());
		}
		catch (IOException | RuntimeException e)
		{
			// Only its freshness is lost.
		}
	}

	static int ticks(double clockSeconds)
	{
		return (int) Math.round(clockSeconds / SECONDS_PER_TICK);
	}

	/** Luck's percentile as a roll of 1 to 100 ("rolled 20 of 100"); 0 when unknown. */
	static int roll(double percentile)
	{
		return Double.isNaN(percentile) || percentile < 0 ? 0
			: Math.max(1, Math.min(100, (int) Math.round(percentile * 100)));
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * T-186 (Eric, 2026-11-09: "I feel like my plugin isn't 'fun' enough to use"): what a kill can honestly celebrate, as a
 * prototype. Test scope only: the facts the kill card and the lighter strip in {@link SidePanelFunCard} draw, worked
 * out from the kill's own {@link LedgerRows.Numbers} and your earlier kills like it ({@link LedgerHistory.Like}: the
 * same boss and version, solo or not). Rewards come from execution; luck is reported as luck.
 */
final class SidePanelFun
{
	private SidePanelFun()
	{
	}

	/**
	 * Below this many earlier like kills the grade reads fixed bands; from it, your own spread. Ten is where Eric's
	 * spreads stopped moving much (Maggot King's 10, Vardorvis's 16).
	 */
	static final int GRADE_FROM_KILLS = 10;
	/** The grade's own spread is your last this many like kills, so it follows you as you improve. */
	static final int GRADE_OVER_KILLS = 50;

	/**
	 * The fixed bands, on Eric's 274 counted kills in history.json on 2026-11-09: S 6%, A 31%, B 52%, C 11%. Unfair
	 * across bosses (Cerberus's uptime puts 78% of his kills below B), which is why they're only the start.
	 */
	static final double BAND_S = 0.995;
	static final double BAND_A = 0.975;
	static final double BAND_B = 0.90;
	/** Against your own spread: S your top 5%, A the next 25%, B the middle 60%, C your bottom 10%. */
	static final double SHARE_S = 0.95;
	static final double SHARE_A = 0.70;
	static final double SHARE_B = 0.10;

	enum Grade
	{
		S, A, B, C
	}

	/** Your earlier like kills, oldest first: what PBs, ranks, the grade's spread and streaks read. */
	static final class Past
	{
		final double[] clocks;
		final double[] luckRemoved;
		final double[] executions;
		/** Grade A or better in a row, ending with the last kill before this one. */
		final int streak;

		Past(double[] clocks, double[] luckRemoved, double[] executions, int streak)
		{
			this.clocks = clocks;
			this.luckRemoved = luckRemoved;
			this.executions = executions;
			this.streak = streak;
		}

		int kills()
		{
			return clocks.length;
		}
	}

	/** What the card says about one kill. */
	static final class Facts
	{
		double clock;
		/** Your best clock before this kill; NaN with none. */
		double pb = Double.NaN;
		boolean newPb;
		/** The usual clock (LedgerHistory's median of your recent like kills); NaN with too few. */
		double usual = Double.NaN;
		/** This kill's clock with luck taken out (T + luck), its best before, and whether it beat it. */
		double luckRemoved = Double.NaN;
		double bestLuckRemoved = Double.NaN;
		boolean newBestPace;
		/** 1-based rank among your like kills, this one included, by clock; and how many. */
		int rank;
		int ranked;
		double execution = Double.NaN;
		Grade grade;
		/** "top 16% of your 209 kills" or "fixed bands (3 kills so far)". */
		String gradeBasis;
		/** Luck's percentile, 0 to 1, and its seconds (positive: it saved time). */
		double luckPercentile = Double.NaN;
		double luckSeconds = Double.NaN;
		/** Execution's biggest part lost, and its points (0.006 is 0.6). */
		String tipPart;
		double tipPoints;
		final List<String> badges = new ArrayList<>();
		/** Grade A or better in a row, this one included; 0 when this one isn't. */
		int streak;
		boolean live;
	}

	/**
	 * The facts for a kill (or, {@code live}, the fight so far: no PB, rank or badge until it ends).
	 *
	 * @param n        the kill's numbers
	 * @param past     your earlier like kills
	 * @param usual    the usual clock (null for none)
	 * @param kc       the game's kill count after this kill (FightContext.kc), 0 unknown
	 * @param flawless avoidable damage taken was 0, at a boss with attack rules; null where it can't be told
	 */
	static Facts of(LedgerRows.Numbers n, Past past, LedgerHistory.Average usual, int kc, Boolean flawless, boolean live)
	{
		final Facts f = new Facts();
		f.live = live;
		f.clock = n.clock;
		f.usual = usual == null ? Double.NaN : usual.actual.median;
		f.execution = live ? n.executionLive : n.execution;
		f.luckPercentile = n.percentile;
		f.luckSeconds = n.luck;
		f.luckRemoved = n.expectedDuration;
		if (past != null && past.kills() > 0)
		{
			f.pb = min(past.clocks);
			f.bestLuckRemoved = min(past.luckRemoved);
		}
		final double[] spread = past == null ? new double[0] : last(past.executions, GRADE_OVER_KILLS);
		if (!Double.isNaN(f.execution))
		{
			f.grade = grade(f.execution, spread);
			if (spread.length >= GRADE_FROM_KILLS)
			{
				f.gradeBasis = basis(share(f.execution, spread), spread.length);
			}
			else
			{
				f.gradeBasis = "fixed bands (" + spread.length + " kills)";
			}
		}
		final List<LedgerRows.Loss> losses = LedgerRows.executionLosses(n, !live);
		for (LedgerRows.Loss l : losses)
		{
			if (l.points > f.tipPoints)
			{
				f.tipPoints = l.points;
				f.tipPart = l.label;
			}
		}
		if (live)
		{
			return f;
		}
		f.newPb = !Double.isNaN(f.pb) && f.clock < f.pb;
		f.newBestPace = !Double.isNaN(f.bestLuckRemoved) && f.luckRemoved < f.bestLuckRemoved;
		if (past != null)
		{
			f.ranked = past.kills() + 1;
			f.rank = 1;
			for (double c : past.clocks)
			{
				if (c < f.clock)
				{
					f.rank++;
				}
			}
		}
		final boolean good = f.grade == Grade.S || f.grade == Grade.A;
		f.streak = good ? (past == null ? 0 : past.streak) + 1 : 0;
		// Badges: rare, checkable, and from skill. Luck has none.
		if (Boolean.TRUE.equals(flawless))
		{
			f.badges.add("Flawless");
		}
		if (n.uptime >= 1 && n.efficiency >= 0.9995)
		{
			f.badges.add("Clean run");
		}
		if (f.streak >= 3)
		{
			f.badges.add(f.streak + " A's in a row");
		}
		if (kc > 0 && kc % 100 == 0)
		{
			f.badges.add(LedgerRows.number(kc) + " kills");
		}
		return f;
	}

	/** Where an execution sits in your last {@code n}: {@code top 12% of last 50}, {@code bottom 8% of last 16}. */
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

	/** The grade for an execution against your own earlier executions (fixed bands with fewer than ten). */
	static Grade grade(double execution, double[] spread)
	{
		if (spread.length < GRADE_FROM_KILLS)
		{
			return execution >= BAND_S ? Grade.S : execution >= BAND_A ? Grade.A : execution >= BAND_B ? Grade.B
				: Grade.C;
		}
		final double s = share(execution, spread);
		return s >= SHARE_S ? Grade.S : s >= SHARE_A ? Grade.A : s >= SHARE_B ? Grade.B : Grade.C;
	}

	/** The share of {@code spread} below {@code x}, ties counted half. */
	static double share(double x, double[] spread)
	{
		if (spread.length == 0)
		{
			return Double.NaN;
		}
		double below = 0;
		for (double v : spread)
		{
			below += v < x ? 1 : v == x ? 0.5 : 0;
		}
		return below / spread.length;
	}

	private static double[] last(double[] values, int n)
	{
		return Arrays.copyOfRange(values, Math.max(0, values.length - n), values.length);
	}

	private static double min(double[] values)
	{
		double m = Double.NaN;
		for (double v : values)
		{
			if (!Double.isNaN(v) && (Double.isNaN(m) || v < m))
			{
				m = v;
			}
		}
		return m;
	}

	/**
	 * {@code n} values spread over these quantile anchors ({@code {share, value}} pairs, shares rising from 0 to 1):
	 * a stand-in for a boss's history in the examples, from the quantiles measured on Eric's history.json.
	 */
	static double[] spread(int n, double[][] anchors)
	{
		final double[] out = new double[n];
		for (int i = 0; i < n; i++)
		{
			final double q = n == 1 ? 0.5 : (double) i / (n - 1);
			int k = 1;
			while (k < anchors.length - 1 && anchors[k][0] < q)
			{
				k++;
			}
			final double[] a = anchors[k - 1];
			final double[] b = anchors[k];
			out[i] = a[1] + (q - a[0]) / (b[0] - a[0]) * (b[1] - a[1]);
		}
		// In a fixed shuffled order, as kills come: sorted, the "last 50" the grade reads would be the best 50.
		final java.util.Random r = new java.util.Random(186);
		for (int i = n - 1; i > 0; i--)
		{
			final int j = r.nextInt(i + 1);
			final double t = out[i];
			out[i] = out[j];
			out[j] = t;
		}
		return out;
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-110: the local calibration report (claude/specs/global-logs.md, 6.2's v0).
 * Luck's z should average 0 and its deciles be flat for any player, good or
 * bad, so a group of the history whose mean z sits several standard errors
 * from 0 is the model, not the player: in Eric's history the Leviathan's
 * pathfinder and wound shots (T-079, T-083), the slayer helm on the Deranged
 * archaeologist (T-090) and the araxytes' guaranteed max hits (T-098) all
 * showed this way before they were found by hand (the spec's 1.3).
 *
 * <p>Grouped four ways:
 * <ul>
 * <li><b>boss</b>: each counted kill (LedgerHistory.counts' rules: a kill,
 * outside a raid, no stats warning, a build with boost samples), its room's
 * damage against its expectation;</li>
 * <li><b>target</b>: each fight in those kills, by the boss and the target -
 * the boss itself or each add - since a room's z can hide two errors pulling
 * opposite ways;</li>
 * <li><b>weapon</b>: each weapon or spell row of R1's record in those fights,
 * by the boss; "unknown" for a fight saved before R1, with the fight's own
 * sums;</li>
 * <li><b>build</b>: each counted kill by the boss and the build that scored
 * it; "before R1" for one saved before.</li>
 * </ul>
 * Per group: kills and fights, the sums of A and E, A/E, the mean z with its
 * standard error, the ten deciles of luck's percentile, and (boss rows) the
 * share of kills with a stats warning. A group is flagged when its mean z is
 * more than {@link #FLAG_SE} standard errors from 0 with at least
 * {@link #FLAG_MIN} samples. Pure.
 */
final class Calibration
{
	static final double FLAG_SE = 3.0;
	static final int FLAG_MIN = 10;
	static final int MAX_LINES = 100;
	static final String BEFORE_R1 = "before R1";
	static final String UNKNOWN_WEAPON = "unknown";

	private Calibration()
	{
	}

	/** One group's sums and samples. */
	static final class Group
	{
		final String dimension;
		final String name;
		int kills;
		int fights;
		long actual;
		double expected;
		final List<Double> z = new ArrayList<>();
		int warned;
		int warnedOf;

		Group(String dimension, String name)
		{
			this.dimension = dimension;
			this.name = name;
		}

		void add(int a, double e, double variance)
		{
			actual += a;
			expected += Math.max(0, e);
			final double zz = DamageMoments.z(a, e, variance);
			if (!Double.isNaN(zz))
			{
				z.add(zz);
			}
		}

		int n()
		{
			return z.size();
		}

		double meanZ()
		{
			double s = 0;
			for (double v : z)
			{
				s += v;
			}
			return z.isEmpty() ? Double.NaN : s / z.size();
		}

		/** The standard error of the mean z: the samples' standard deviation over the root of their number. */
		double standardError()
		{
			final int n = z.size();
			if (n < 2)
			{
				return Double.NaN;
			}
			final double m = meanZ();
			double ss = 0;
			for (double v : z)
			{
				ss += (v - m) * (v - m);
			}
			return Math.sqrt(ss / (n - 1)) / Math.sqrt(n);
		}

		double ratio()
		{
			return expected > 0 ? actual / expected : Double.NaN;
		}

		/** How many samples fall in each tenth of luck's percentile, from the bottom tenth up. */
		int[] deciles()
		{
			final int[] d = new int[10];
			for (double v : z)
			{
				d[Math.min(9, Math.max(0, (int) Math.floor(DamageMoments.phi(v) * 10)))]++;
			}
			return d;
		}

		boolean flagged()
		{
			final double se = standardError();
			return n() >= FLAG_MIN && !Double.isNaN(se) && se > 0 && Math.abs(meanZ()) > FLAG_SE * se;
		}

		String line()
		{
			final StringBuilder b = new StringBuilder();
			b.append(dimension).append(' ').append(name).append(": ");
			if (kills > 0)
			{
				b.append(kills).append(kills == 1 ? " kill, " : " kills, ");
			}
			b.append(fights).append(fights == 1 ? " fight" : " fights");
			b.append(String.format(", A %d / E %.0f = %s", actual, expected,
				Double.isNaN(ratio()) ? "?" : String.format("%.2f", ratio())));
			final double se = standardError();
			b.append(Double.isNaN(meanZ()) ? ", z unknown"
				: String.format(", mean z %+.2f (se %s, n %d)", meanZ(), Double.isNaN(se) ? "?" : String.format("%.2f", se),
				n()));
			if (n() > 0)
			{
				final StringBuilder d = new StringBuilder();
				for (int c : deciles())
				{
					d.append(d.length() == 0 ? "" : " ").append(c);
				}
				b.append(", deciles ").append(d);
			}
			if (warnedOf > 0)
			{
				b.append(String.format(", warnings %d%%", Math.round(100.0 * warned / warnedOf)));
			}
			return b.toString();
		}
	}

	/** Every group of the history, in report order within each dimension: boss, target, weapon, build. */
	static List<Group> groups(List<Fight> history, BossRules rules)
	{
		final Map<String, Group> bosses = new LinkedHashMap<>();
		final Map<String, Group> targets = new LinkedHashMap<>();
		final Map<String, Group> weapons = new LinkedHashMap<>();
		final Map<String, Group> builds = new LinkedHashMap<>();
		final Set<String> names = new LinkedHashSet<>();
		for (int i = history.size() - 1; i >= 0; i--)
		{
			final Fight f = history.get(i);
			if (HistoryBudget.isMonster(f, rules))
			{
				continue;
			}
			if (isOldAdd(f))
			{
				// An add saved before its boss was grouped (the araxytes before T-091) stood as a room of its own. It is a
				// target of its boss's, by fight, as History's lists put it under the boss (T-102).
				if (f.isScored() && f.getAttempts() > 0 && f.getStatsWarning() == null)
				{
					final Group tg = targets.computeIfAbsent(EncounterGroup.of(f.getTargetId()) + " / " + f.getTargetName(),
						k -> new Group("target", k));
					tg.fights++;
					tg.add(f.getDamageDealt(), f.getSumExpectedAverageHit(), f.damageVariance());
				}
				continue;
			}
			names.add(f.encounterName());
		}
		for (String name : names)
		{
			final List<Encounter> rooms = LedgerHistory.rooms(history, name);
			final Set<Integer> hps = new HashSet<>();
			for (Encounter room : rooms)
			{
				if (LedgerHistory.counts(room, Collections.emptySet()))
				{
					hps.add(mainMaxHp(room));
				}
			}
			for (Encounter room : rooms)
			{
				if (room.isMonsterRoom() || !LedgerHistory.isKill(room))
				{
					continue;
				}
				final String boss = hps.size() > 1 ? name + " (" + mainMaxHp(room) + " hp)" : name;
				final Group bg = bosses.computeIfAbsent(boss, k -> new Group("boss", k));
				bg.warnedOf++;
				if (room.getStatsWarning() != null)
				{
					bg.warned++;
				}
				if (!LedgerHistory.counts(room, Collections.emptySet()))
				{
					continue;
				}
				bg.kills++;
				bg.fights += room.getFights().size();
				bg.add(room.getDamageDealt(), room.sumExpectedAverageHit(), room.sumExpectedDamageVariance());
				final Group build = builds.computeIfAbsent(boss + " / " + buildOf(room), k -> new Group("build", k));
				build.kills++;
				build.fights += room.getFights().size();
				build.add(room.getDamageDealt(), room.sumExpectedAverageHit(), room.sumExpectedDamageVariance());
				for (Fight f : room.getFights())
				{
					if (!f.isScored() || f.getAttempts() <= 0)
					{
						continue;
					}
					final Group tg = targets.computeIfAbsent(boss + " / " + f.getTargetName(), k -> new Group("target", k));
					tg.fights++;
					tg.add(f.getDamageDealt(), f.getSumExpectedAverageHit(), f.damageVariance());
					final FightContext c = f.getContext();
					if (c == null || c.weapons == null || c.weapons.isEmpty())
					{
						final Group wg = weapons.computeIfAbsent(boss + " / " + UNKNOWN_WEAPON, k -> new Group("weapon", k));
						wg.fights++;
						wg.add(f.getDamageDealt(), f.getSumExpectedAverageHit(), f.damageVariance());
						continue;
					}
					for (Map.Entry<String, FightContext.Weapon> e : c.weapons.entrySet())
					{
						final FightContext.Weapon w = e.getValue();
						final Group wg = weapons.computeIfAbsent(boss + " / " + e.getKey(), k -> new Group("weapon", k));
						wg.fights++;
						wg.add(w.damage, w.expected, w.variance);
					}
				}
			}
		}
		final List<Group> out = new ArrayList<>();
		out.addAll(bosses.values());
		out.addAll(targets.values());
		out.addAll(weapons.values());
		out.addAll(builds.values());
		return out;
	}

	/**
	 * The report, at most {@link #MAX_LINES} lines: the flagged groups first,
	 * then one line per boss, then the targets of bosses with adds, the weapons
	 * and the builds while there is room.
	 */
	static String report(List<Fight> history, BossRules rules)
	{
		final List<Group> all = groups(history, rules);
		final List<String> lines = new ArrayList<>();
		int counted = 0;
		final Map<String, Integer> targetsPerBoss = new HashMap<>();
		final Map<String, Integer> buildsPerBoss = new HashMap<>();
		for (Group g : all)
		{
			if ("boss".equals(g.dimension))
			{
				counted += g.kills;
			}
			if ("target".equals(g.dimension))
			{
				targetsPerBoss.merge(bossOf(g.name), 1, Integer::sum);
			}
			if ("build".equals(g.dimension))
			{
				buildsPerBoss.merge(bossOf(g.name), 1, Integer::sum);
			}
		}
		// Only what a boss's own row doesn't already say: a boss's one target, its fights before R1 under "unknown", or its
		// one build would repeat the boss's line.
		final java.util.function.Predicate<Group> informative = g ->
			"boss".equals(g.dimension)
				|| "target".equals(g.dimension) && targetsPerBoss.getOrDefault(bossOf(g.name), 0) > 1
				|| "weapon".equals(g.dimension) && !g.name.endsWith(" / " + UNKNOWN_WEAPON)
				|| "build".equals(g.dimension) && buildsPerBoss.getOrDefault(bossOf(g.name), 0) > 1;
		lines.add(String.format("CALIBRATION over %d saved fights, %d counted kills: luck's z should average 0 and its deciles"
			+ " (bottom tenth first) be flat; a group is flagged past %.0f standard errors with n >= %d", history.size(),
			counted, FLAG_SE, FLAG_MIN));
		final List<Group> flagged = new ArrayList<>();
		for (Group g : all)
		{
			if (g.flagged() && informative.test(g))
			{
				flagged.add(g);
			}
		}
		lines.add(flagged.isEmpty() ? "FLAGGED: none" : "FLAGGED:");
		for (Group g : flagged)
		{
			lines.add("  " + g.line());
		}
		section(lines, "BOSSES:", all, g -> "boss".equals(g.dimension));
		section(lines, "TARGETS (bosses with adds):", all, g -> "target".equals(g.dimension) && informative.test(g));
		section(lines, "WEAPONS (R1's record):", all, g -> "weapon".equals(g.dimension) && informative.test(g));
		section(lines, "BUILDS (bosses killed on more than one):", all, g -> "build".equals(g.dimension) && informative.test(g));
		if (lines.size() > MAX_LINES)
		{
			final int cut = lines.size() - (MAX_LINES - 1);
			final List<String> kept = new ArrayList<>(lines.subList(0, MAX_LINES - 1));
			kept.add(String.format("... and %d more lines", cut));
			return String.join("\n", kept);
		}
		return String.join("\n", lines);
	}

	private static void section(List<String> lines, String title, List<Group> all,
		java.util.function.Predicate<Group> which)
	{
		final List<String> rows = new ArrayList<>();
		for (Group g : all)
		{
			if (which.test(g))
			{
				rows.add("  " + g.line());
			}
		}
		if (!rows.isEmpty())
		{
			lines.add(title);
			lines.addAll(rows);
		}
	}

	private static String bossOf(String groupName)
	{
		final int at = groupName.indexOf(" / ");
		return at < 0 ? groupName : groupName.substring(0, at);
	}

	/** A boss's add saved before its group was (no group then), so not a boss's room of its own. */
	static boolean isOldAdd(Fight f)
	{
		return f.getGroupName() == null && EncounterGroup.of(f.getTargetId()) != null
			&& !EncounterGroup.lootIsTheKill(f.getTargetId());
	}

	/** The build that scored a room: its first fight's R1 record's, or "before R1". */
	static String buildOf(Encounter room)
	{
		for (Fight f : room.getFights())
		{
			if (f.getContext() != null && f.getContext().build != null)
			{
				return f.getContext().build;
			}
		}
		return BEFORE_R1;
	}

	// The room's main target's full hitpoints: its largest, which is the boss's and not an add's.
	private static int mainMaxHp(Encounter room)
	{
		int hp = 0;
		for (Fight f : room.getFights())
		{
			hp = Math.max(hp, f.getMaxHp());
		}
		return hp;
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-110: the local calibration report (claude/specs/global-logs.md, 6.2's v0).
 * Luck's z should average 0 and its deciles be flat for any player, good or
 * bad, so a group of the history whose mean z sits several standard errors
 * from 0 is the model, not the player: in Eric's history the Leviathan's
 * pathfinder and wound shots (T-079, T-083), the slayer helm on the Deranged
 * archaeologist (T-090) and the araxytes' guaranteed max hits (T-098) all
 * showed this way before they were found by hand (the spec's 1.3).
 *
 * <p>Grouped three ways, each split by the build that scored it (T-113):
 * <ul>
 * <li><b>boss</b>: each counted kill (LedgerHistory.counts' rules: a kill,
 * outside a raid, no stats warning, a build with boost samples), its room's
 * damage against its expectation;</li>
 * <li><b>target</b>: each fight in those kills, by the boss and the target -
 * the boss itself or each add - since a room's z can hide two errors pulling
 * opposite ways;</li>
 * <li><b>weapon</b>: each weapon or spell row of R1's record in those fights,
 * by the boss; "unknown" for a fight with no record.</li>
 * </ul>
 * Per group: kills and fights, the sums of A and E, A/E, the mean z with its
 * standard error, the ten deciles of luck's percentile, and (boss rows) the
 * share of kills with a stats warning.
 *
 * <p>A group is <em>off</em> when its mean z is more than {@link #FLAG_SE}
 * standard errors from 0 with at least {@link #FLAG_MIN} samples, and
 * <em>flagged</em> only when it is off on a build R1 recorded (T-113): a fix
 * changes the build, so kills saved before R1, with no build, may hold bugs
 * fixed since and are listed apart, never flagged. Builds are told apart by
 * their commit (and whether it was dirty), not by the build line's times. An
 * off group says how the same boss and target, or weapon, read on a later
 * build, which is how a fix shows as fixed. Pure.
 */
final class Calibration
{
	static final double FLAG_SE = 3.0;
	static final int FLAG_MIN = 10;
	static final int MAX_LINES = 100;
	static final String UNKNOWN_WEAPON = "unknown";
	/** The heading of the kills with no recorded build. */
	static final String OLDER = "Older kills (before the plugin recorded its build; not flagged):";
	/** At most this many plain sentences; the rest are in the report's table. */
	static final int MAX_SENTENCES = 6;

	private Calibration()
	{
	}

	/** One group's sums and samples. */
	static final class Group
	{
		final String dimension;
		/** The boss row's name, with its version suffix when it has more than one. */
		final String boss;
		/** The target's name for a target row, the weapon's for a weapon row; null for a boss row. */
		final String part;
		/** The build key ({@link #buildKey}); null for kills saved before R1 recorded one. */
		final String build;
		int kills;
		int fights;
		long actual;
		double expected;
		final List<Double> z = new ArrayList<>();
		int warned;
		int warnedOf;
		/** The first and last fight's start, to tell which build came later. */
		long firstMillis = Long.MAX_VALUE;
		long lastMillis = Long.MIN_VALUE;

		Group(String dimension, String boss, String part, String build)
		{
			this.dimension = dimension;
			this.boss = boss;
			this.part = part;
			this.build = build;
		}

		/** What the group is about, whatever the build: {@code Araxxor / Mirrorback Araxyte}. */
		String subject()
		{
			return part == null ? boss : boss + " / " + part;
		}

		boolean older()
		{
			return build == null;
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

		void seen(long millis)
		{
			firstMillis = Math.min(firstMillis, millis);
			lastMillis = Math.max(lastMillis, millis);
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

		/** More than luck explains: the mean z past {@link #FLAG_SE} standard errors, on {@link #FLAG_MIN} samples or more. */
		boolean off()
		{
			final double se = standardError();
			return n() >= FLAG_MIN && !Double.isNaN(se) && se > 0 && Math.abs(meanZ()) > FLAG_SE * se;
		}

		/** Off on a build R1 recorded: only those can be flagged. */
		boolean flagged()
		{
			return !older() && off();
		}

		/** Kills for a boss row, fights otherwise. */
		String count()
		{
			return "boss".equals(dimension) ? plural(kills, "kill") : plural(fights, "fight");
		}

		/** The number {@link #count} names. */
		int samples()
		{
			return "boss".equals(dimension) ? kills : fights;
		}

		String line()
		{
			final StringBuilder b = new StringBuilder();
			b.append(dimension).append(' ').append(subject());
			if (!older())
			{
				b.append(" on build ").append(build);
			}
			b.append(": ");
			if (kills > 0)
			{
				b.append(plural(kills, "kill")).append(", ");
			}
			b.append(plural(fights, "fight"));
			b.append(String.format(Locale.ROOT, ", A %d / E %.0f = %s", actual, expected,
				Double.isNaN(ratio()) ? "?" : String.format(Locale.ROOT, "%.2f", ratio())));
			final double se = standardError();
			b.append(Double.isNaN(meanZ()) ? ", z unknown"
				: String.format(Locale.ROOT, ", mean z %+.2f (se %s, n %d)", meanZ(),
				Double.isNaN(se) ? "?" : String.format(Locale.ROOT, "%.2f", se), n()));
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
				b.append(String.format(Locale.ROOT, ", warnings %d%%", Math.round(100.0 * warned / warnedOf)));
			}
			return b.toString();
		}
	}

	private static String plural(int n, String word)
	{
		return n + " " + word + (n == 1 ? "" : "s");
	}

	// "build 1f79693 (master, clean, committed ...)" or "1f79693 (...)": the commit, and whether it was dirty.
	private static final Pattern COMMIT = Pattern.compile("^(?:build )?([0-9a-f]{7,40})\\b(.*)$");

	/**
	 * The build a fight's R1 record names, as the report tells builds apart:
	 * its commit, with {@code dirty} when it had uncommitted changes, or the
	 * hub release; null with no record, or a build that couldn't tell.
	 */
	static String buildKey(String build)
	{
		if (build == null)
		{
			return null;
		}
		final String b = build.trim();
		final Matcher m = COMMIT.matcher(b);
		if (m.matches())
		{
			return m.group(1) + (m.group(2).contains("dirty") ? " dirty" : "");
		}
		if (b.startsWith("release ") || b.startsWith("version "))
		{
			return b;
		}
		return null;
	}

	/** Every group of the history: bosses, then targets, then weapons, each in the order first met. */
	static List<Group> groups(List<Fight> history, BossRules rules)
	{
		final Map<String, Group> bosses = new LinkedHashMap<>();
		final Map<String, Group> targets = new LinkedHashMap<>();
		final Map<String, Group> weapons = new LinkedHashMap<>();
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
				// target of its boss's, by fight, as History's lists put it under the boss (T-102). Saved before R1 too.
				if (f.isScored() && f.getAttempts() > 0 && f.getStatsWarning() == null)
				{
					final Group tg = group(targets, "target", EncounterGroup.of(f.getTargetId()), f.getTargetName(), null);
					tg.fights++;
					tg.seen(f.getStartMillis());
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
				final String build = buildKey(buildOf(room));
				final Group bg = group(bosses, "boss", boss, null, build);
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
				for (Fight f : room.getFights())
				{
					bg.seen(f.getStartMillis());
					if (!f.isScored() || f.getAttempts() <= 0)
					{
						continue;
					}
					final Group tg = group(targets, "target", boss, f.getTargetName(), build);
					tg.fights++;
					tg.seen(f.getStartMillis());
					tg.add(f.getDamageDealt(), f.getSumExpectedAverageHit(), f.damageVariance());
					final FightContext c = f.getContext();
					if (c == null || c.weapons == null || c.weapons.isEmpty())
					{
						final Group wg = group(weapons, "weapon", boss, UNKNOWN_WEAPON, build);
						wg.fights++;
						wg.seen(f.getStartMillis());
						wg.add(f.getDamageDealt(), f.getSumExpectedAverageHit(), f.damageVariance());
						continue;
					}
					for (Map.Entry<String, FightContext.Weapon> e : c.weapons.entrySet())
					{
						final FightContext.Weapon w = e.getValue();
						final Group wg = group(weapons, "weapon", boss, e.getKey(), build);
						wg.fights++;
						wg.seen(f.getStartMillis());
						wg.add(w.damage, w.expected, w.variance);
					}
				}
			}
		}
		final List<Group> out = new ArrayList<>();
		out.addAll(bosses.values());
		out.addAll(targets.values());
		out.addAll(weapons.values());
		return out;
	}

	private static Group group(Map<String, Group> groups, String dimension, String boss, String part, String build)
	{
		final String key = boss + "\n" + part + "\n" + build;
		return groups.computeIfAbsent(key, k -> new Group(dimension, boss, part, build));
	}

	/**
	 * The rows that say something a boss's own row doesn't: every boss row,
	 * targets only of a boss with adds, and weapons R1 recorded (not
	 * "unknown"). Only these can be flagged.
	 */
	private static Predicate<Group> informative(List<Group> all)
	{
		final Map<String, Set<String>> targetsPerBoss = new HashMap<>();
		for (Group g : all)
		{
			if ("target".equals(g.dimension))
			{
				targetsPerBoss.computeIfAbsent(g.boss, k -> new HashSet<>()).add(g.part);
			}
		}
		return g -> "boss".equals(g.dimension)
			|| "target".equals(g.dimension) && targetsPerBoss.getOrDefault(g.boss, Collections.emptySet()).size() > 1
			|| "weapon".equals(g.dimension) && !UNKNOWN_WEAPON.equals(g.part);
	}

	/** The same boss and target, or weapon, on the build seen latest after this group's last fight; null when none. */
	static Group later(Group g, List<Group> all)
	{
		Group best = null;
		for (Group o : all)
		{
			if (o == g || o.older() || !o.dimension.equals(g.dimension) || !o.subject().equals(g.subject())
				|| o.build.equals(g.build) || o.lastMillis <= g.lastMillis)
			{
				continue;
			}
			if (best == null || o.lastMillis > best.lastMillis)
			{
				best = o;
			}
		}
		return best;
	}

	/** Whether a later build reads the same thing in line: off here, not off there on enough samples. */
	static boolean fixedLater(Group g, List<Group> all)
	{
		final Group l = later(g, all);
		return l != null && l.n() >= FLAG_MIN && !l.off();
	}

	/** For an off group: how its boss and target, or weapon, read on a later build. Empty when there's nothing to say. */
	static String laterNote(Group g, List<Group> all)
	{
		final Group l = later(g, all);
		if (l == null)
		{
			return g.older() ? "; no kills on a recorded build yet" : "";
		}
		if (l.off())
		{
			return String.format(Locale.ROOT, "; still off on build %s (%s, mean z %+.2f)", l.build, l.count(), l.meanZ());
		}
		if (l.n() >= FLAG_MIN)
		{
			return String.format(Locale.ROOT, "; back in line on build %s (%s, mean z %+.2f)", l.build, l.count(),
				l.meanZ());
		}
		if (l.n() == 0 && l.samples() >= FLAG_MIN && !Double.isNaN(l.ratio()))
		{
			// Certain hits (the araxytes' guaranteed max, T-098): no luck to test, so A/E says it.
			return String.format(Locale.ROOT, "; A/E %.2f on build %s (%s, certain hits with no luck to test)", l.ratio(),
				l.build, l.count());
		}
		return String.format(Locale.ROOT, "; build %s has %s so far, too few to tell", l.build, l.count());
	}

	/**
	 * The report's findings as plain sentences a player can read (T-113), at
	 * most {@link #MAX_SENTENCES} and a line saying how many more: one per
	 * flagged group that no later build has put back in line, or one saying
	 * nothing looks off. For the side panel (T-114) and the top of the report.
	 */
	static List<String> summary(List<Fight> history, BossRules rules)
	{
		return summary(groups(history, rules));
	}

	private static List<String> summary(List<Group> all)
	{
		final Predicate<Group> informative = informative(all);
		final List<String> lines = new ArrayList<>();
		int found = 0;
		for (Group g : all)
		{
			if (g.flagged() && informative.test(g) && !fixedLater(g, all))
			{
				found++;
				if (lines.size() < MAX_SENTENCES)
				{
					lines.add(sentence(g));
				}
			}
		}
		if (found > lines.size())
		{
			final int more = found - lines.size();
			lines.add(String.format(Locale.ROOT, "And %d more in the saved report.", more));
		}
		if (found > 0)
		{
			return lines;
		}
		int kills = 0;
		boolean enough = false;
		for (Group g : all)
		{
			if ("boss".equals(g.dimension) && !g.older())
			{
				kills += g.kills;
				enough |= g.n() >= FLAG_MIN;
			}
		}
		if (kills == 0)
		{
			lines.add("Nothing to judge yet: none of your saved kills are on a build that records itself."
				+ " Older kills are listed in the report but never flagged.");
		}
		else if (!enough)
		{
			lines.add(String.format(Locale.ROOT, "Nothing looks off so far: over %s on current builds, your damage matches"
				+ " what the plugin expected. A boss needs %d kills on one build before it can be judged.",
				plural(kills, "kill"), FLAG_MIN));
		}
		else
		{
			lines.add(String.format(Locale.ROOT, "Nothing looks off: over %s on current builds, your damage matches what"
				+ " the plugin expected.", plural(kills, "kill")));
		}
		return lines;
	}

	/**
	 * One flagged group in words: {@code The Leviathan: you dealt 8% more than
	 * the plugin expected over 54 kills. More than luck explains, so likely a
	 * scoring error. (build abc1234)}
	 */
	static String sentence(Group g)
	{
		final double r = g.ratio();
		final boolean over = g.meanZ() > 0;
		final long pct = Double.isNaN(r) ? 0 : Math.round(Math.abs(r - 1) * 100);
		final String howMuch = pct == 0 ? (over ? "slightly more" : "slightly less") : pct + "% " + (over ? "more" : "less");
		return String.format(Locale.ROOT, "%s: you dealt %s than the plugin expected over %s. %s, so likely a scoring error."
			+ " (build %s)", who(g), howMuch, g.count(), over ? "More than luck explains" : "Less than luck explains",
			g.build);
	}

	// Whom the sentence is about: the boss, its adds ("Araxxor's Mirrorback araxytes"), the boss itself in a room with
	// adds, or a weapon at the boss.
	private static String who(Group g)
	{
		if ("target".equals(g.dimension))
		{
			return g.part.equalsIgnoreCase(g.boss) || g.boss.startsWith(g.part + " (")
				? g.boss + " itself (not its adds)"
				: g.boss + "'s " + pluralName(g.part);
		}
		if ("weapon".equals(g.dimension))
		{
			return g.boss + ", " + g.part;
		}
		return g.boss;
	}

	// "Mirrorback Araxyte" as a group of them: "Mirrorback araxytes".
	static String pluralName(String name)
	{
		final String[] words = name.trim().split(" ");
		final StringBuilder b = new StringBuilder(words[0]);
		for (int i = 1; i < words.length; i++)
		{
			b.append(' ').append(words[i].toLowerCase(Locale.ROOT));
		}
		return b.toString().endsWith("s") ? b.toString() : b.append('s').toString();
	}

	/**
	 * The report, at most {@link #MAX_LINES} lines: the plain sentences, the
	 * flagged groups, then the recorded builds' bosses, targets of bosses with
	 * adds and weapons, then the older kills apart, while there is room. Each
	 * off group says how a later build reads.
	 */
	static String report(List<Fight> history, BossRules rules)
	{
		final List<Group> all = groups(history, rules);
		final Predicate<Group> informative = informative(all);
		final List<String> lines = new ArrayList<>();
		int recorded = 0;
		int older = 0;
		for (Group g : all)
		{
			if ("boss".equals(g.dimension))
			{
				if (g.older())
				{
					older += g.kills;
				}
				else
				{
					recorded += g.kills;
				}
			}
		}
		lines.add(String.format(Locale.ROOT, "CALIBRATION over %d saved fights: %d counted kills on recorded builds, %d"
			+ " older. Luck's z should average 0 and its deciles (bottom tenth first) be flat; a group on a recorded build"
			+ " is flagged past %.0f standard errors with n >= %d", history.size(), recorded, older, FLAG_SE, FLAG_MIN));
		lines.add("IN SHORT:");
		for (String s : summary(all))
		{
			lines.add("  " + s);
		}
		final List<Group> flagged = new ArrayList<>();
		for (Group g : all)
		{
			if (g.flagged() && informative.test(g))
			{
				flagged.add(g);
			}
		}
		// T-111's button counts the lines under this heading: one per flag, its note on the same line.
		lines.add(flagged.isEmpty() ? "FLAGGED: none" : "FLAGGED:");
		for (Group g : flagged)
		{
			lines.add("  " + g.line() + laterNote(g, all));
		}
		section(lines, "BOSSES (recorded builds):", g -> !g.older() && "boss".equals(g.dimension), all);
		section(lines, "TARGETS (recorded builds, bosses with adds):",
			g -> !g.older() && "target".equals(g.dimension) && informative.test(g), all);
		section(lines, "WEAPONS (recorded builds):",
			g -> !g.older() && "weapon".equals(g.dimension) && informative.test(g), all);
		final List<String> old = new ArrayList<>();
		section(old, "  bosses:", g -> g.older() && "boss".equals(g.dimension), all);
		section(old, "  targets (bosses with adds):",
			g -> g.older() && "target".equals(g.dimension) && informative.test(g), all);
		if (!old.isEmpty())
		{
			lines.add(OLDER);
			lines.addAll(old);
		}
		if (lines.size() > MAX_LINES)
		{
			final int cut = lines.size() - (MAX_LINES - 1);
			final List<String> kept = new ArrayList<>(lines.subList(0, MAX_LINES - 1));
			kept.add(String.format(Locale.ROOT, "... and %d more lines", cut));
			return String.join("\n", kept);
		}
		return String.join("\n", lines);
	}

	// A heading and its rows; an off row says how a later build reads.
	private static void section(List<String> lines, String title, Predicate<Group> which, List<Group> all)
	{
		final List<String> rows = new ArrayList<>();
		final String indent = title.startsWith("  ") ? "    " : "  ";
		for (Group g : all)
		{
			if (which.test(g))
			{
				rows.add(indent + g.line() + (g.off() ? laterNote(g, all) : ""));
			}
		}
		if (!rows.isEmpty())
		{
			lines.add(title);
			lines.addAll(rows);
		}
	}

	/** A boss's add saved before its group was (no group then), so not a boss's room of its own. */
	static boolean isOldAdd(Fight f)
	{
		return f.getGroupName() == null && EncounterGroup.of(f.getTargetId()) != null
			&& !EncounterGroup.lootIsTheKill(f.getTargetId());
	}

	/** The build that scored a room: its first fight's R1 record's, or null before R1. */
	static String buildOf(Encounter room)
	{
		for (Fight f : room.getFights())
		{
			if (f.getContext() != null && f.getContext().build != null)
			{
				return f.getContext().build;
			}
		}
		return null;
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

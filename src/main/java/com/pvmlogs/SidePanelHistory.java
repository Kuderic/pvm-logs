package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import com.pvmlogs.PanelSnapshot.Tile;
import java.awt.Color;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The History tab's figures (T-034; spec 3.2, §4, states 10 and 11), from the
 * saved fights alone: no client and no Swing, so every list and median can be
 * checked on fixtures.
 * <p>
 * Rooms are rebuilt with {@link LedgerHistory#rooms}, the overlay's own rule, so
 * a boss's adds sit in its room (the Sire's respiratory systems, spawns and
 * scions). Your usual kill is taken over the kills {@link LedgerHistory#counts}
 * picks, as the overlay's average is, so the two can't disagree.
 */
final class SidePanelHistory
{
	/** Kill rows shown before "Show N more" (spec 3.2)... */
	static final int FIRST_KILLS = 7;
	/** ...and how many each press adds. */
	static final int MORE_KILLS = 20;
	/** The luck-removed tile's top edge and the sparkline's T* line (spec section 2). */
	static final Color LUCK_REMOVED = new Color(0x6E9BFF);

	private SidePanelHistory()
	{
	}

	/** The boss version's name for a room of this full HP, or null; {@link SidePanelModel#versionName} in the client. */
	interface Versions
	{
		Versions NONE = (room, maxHp) -> null;

		String name(Encounter room, int maxHp);

		/** T-044: the attack names the boss rules call avoidable at this room's boss; empty without rules. */
		default java.util.Set<String> avoidable(Encounter room)
		{
			return Collections.emptySet();
		}
	}

	// ---- rooms ----

	/**
	 * Every room in the history, newest first: each name's rooms from
	 * {@link LedgerHistory#rooms}, merged by their start.
	 */
	static List<Encounter> allRooms(List<Fight> history)
	{
		final Set<String> names = new LinkedHashSet<>();
		for (Fight f : history)
		{
			names.add(f.encounterName());
		}
		final List<Encounter> rooms = new ArrayList<>();
		for (String name : names)
		{
			rooms.addAll(LedgerHistory.rooms(history, name));
		}
		rooms.sort(Comparator.comparingLong(Encounter::getStartMillis).reversed());
		return rooms;
	}

	/**
	 * Whether a rebuilt room was a kill: the loot closed a grouped boss's room,
	 * or an ungrouped target died. One of a boss's parts dying is not the kill
	 * (the Sire's respiratory systems): the same test {@link LedgerHistory#counts}
	 * makes, without its build and raid filters, as old kills are still kills.
	 */
	static boolean isKill(Encounter room)
	{
		// T-095: the overlay's own test (T-094), so the two agree: a run of monsters (T-089) is a kill once it has
		// closed with a kill in it.
		return LedgerHistory.isKill(room);
	}

	/** The room was fought at all: a look that opened a fight and nothing more isn't a room to list. */
	static boolean fought(Encounter room)
	{
		return room.hasAttempts();
	}

	/** A boss for "Bosses only": the room's name is on the hiscores' boss list, or one of its targets' is (T-027). */
	static boolean isBoss(Encounter room)
	{
		if (PvmPerformancePlugin.isBossName(room.getName()))
		{
			return true;
		}
		for (Fight f : room.getFights())
		{
			if (PvmPerformancePlugin.isBossName(f.getTargetName()))
			{
				return true;
			}
		}
		return false;
	}

	static long endMillis(Encounter room)
	{
		long end = room.getEndMillis();
		for (Fight f : room.getFights())
		{
			end = Math.max(end, f.getEndMillis());
		}
		return end;
	}

	// ---- all bosses ----

	/** One line of "All bosses". */
	static final class BossEntry
	{
		final String name;
		final int kills;
		/** Your usual clock on its latest version, or null under five like kills. */
		final String average;
		/** The adds fought in its rooms, by name, in the order first fought. */
		final List<String> adds;
		final boolean boss;
		/** The boss's icon, a hiscores sprite, or {@link SidePanelBossIcons#NONE} (T-054). */
		final int icon;
		/**
		 * T-095: for a kind fought in runs (T-089), its runs with kills, the monsters killed in them, and its best
		 * kills/hr ({@code 227}, or null where no run had a minute). 0, 0 and null for a boss, and for a kind saved only
		 * before T-089, whose rows are still one per fight.
		 */
		final int runs;
		final int runKills;
		final String bestPerHour;

		BossEntry(String name, int kills, String average, List<String> adds, boolean boss)
		{
			this(name, kills, average, adds, boss, SidePanelBossIcons.sprite(name, null));
		}

		BossEntry(String name, int kills, String average, List<String> adds, boolean boss, int icon)
		{
			this(name, kills, average, adds, boss, icon, 0, 0, null);
		}

		BossEntry(String name, int kills, String average, List<String> adds, boolean boss, int icon, int runs,
			int runKills, String bestPerHour)
		{
			this.name = name;
			this.kills = kills;
			this.average = average;
			this.adds = Collections.unmodifiableList(adds);
			this.boss = boss;
			this.icon = icon;
			this.runs = runs;
			this.runKills = runKills;
			this.bestPerHour = bestPerHour;
		}

		/**
		 * The right side: {@code 23 kills · 1:49} for a boss with an average, else {@code 12 kills}; T-095:
		 * {@code 5 runs · 312 kills} for a kind fought in runs.
		 */
		String right()
		{
			if (runs > 0)
			{
				return plural(runs, "run") + " · " + plural(runKills, "kill");
			}
			return boss && average != null ? plural(kills, "kill") + " · " + average : plural(kills, "kill");
		}

		/**
		 * The sub-line: a boss's adds, {@code with Respiratory system, Scion}, or empty; T-095: a kind fought in runs
		 * has its best kills/hr in the average's place, {@code best 227/hr}. T-102: no "not a boss": the Monsters section
		 * says so.
		 */
		String sub()
		{
			if (runs > 0)
			{
				return bestPerHour == null ? "" : "best " + bestPerHour + "/hr";
			}
			return !boss || adds.isEmpty() ? "" : "with " + String.join(", ", adds);
		}
	}

	// ---- T-102: Bosses and Monsters ----

	/**
	 * T-102: whether a rebuilt room is a monster's, by the history budget's own rule (T-093, {@link
	 * HistoryBudget#isMonster}): every fight in it a monster's, flagged as a run when fought (T-089), else by T-089's
	 * test on its name and id ({@link MonsterRooms#isMonster}: not grouped, not in {@code boss-rules.json}, not on the
	 * hiscores' list nor {@link EncounterGroup#isBossByName}, not in a raid). A room with any boss fight is a boss's.
	 * {@code rules} null leaves {@code boss-rules.json} out, as the budget does before the data has loaded.
	 */
	static boolean isMonster(Encounter room, BossRules rules)
	{
		boolean any = false;
		for (Fight f : room.getFights())
		{
			if (f.getAttempts() <= 0)
			{
				continue;
			}
			any = true;
			if (!HistoryBudget.isMonster(f, rules))
			{
				return false;
			}
		}
		return any || room.isMonsterRoom();
	}

	/**
	 * T-102: the boss a room of nothing but its adds belongs to, or null: the araxyte rooms saved on their own before
	 * T-091 grouped them with Araxxor (some as runs of monsters, T-089). Their ids are an add of the group now ({@link
	 * EncounterGroup#lootIsTheKill} false), so they're listed under the group's boss, as its adds, not as a boss or a
	 * monster of their own. Never their kills: an add's death was never the boss's.
	 */
	static String addsOf(Encounter room)
	{
		String group = null;
		for (Fight f : room.getFights())
		{
			if (f.getAttempts() <= 0)
			{
				continue;
			}
			final String g = EncounterGroup.of(f.getTargetId());
			if (g == null || EncounterGroup.lootIsTheKill(f.getTargetId()) || (group != null && !group.equals(g)))
			{
				return null;
			}
			group = g;
		}
		return group == null || group.equals(room.getName()) ? null : group;
	}

	/**
	 * T-102: the room ended because the player died (T-100's {@code roomEnd = "died"} on its last fight), so it reads
	 * {@code (died)} where a room that isn't a kill reads {@code (ended)}. A room left ({@code "left"}) keeps {@code
	 * (ended)}.
	 */
	static boolean died(Encounter room)
	{
		if (room == null)
		{
			return false;
		}
		for (Fight f : room.getFights())
		{
			if (DIED.equals(f.getRoomEnd()))
			{
				return true;
			}
		}
		return false;
	}

	/** T-100's word for a room the player died in. */
	static final String DIED = "died";

	/** Before T-102, for the tests that ask it so: every name, bosses only, or matching {@code query}. */
	static List<BossEntry> bosses(List<Fight> history, boolean bossesOnly, String query)
	{
		final List<BossEntry> out = new ArrayList<>();
		for (BossEntry e : bosses(history, query, null))
		{
			if (!bossesOnly || e.boss)
			{
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * Every room name in the history, most recently fought first, grouped so a boss's adds sit under it (spec 3.2).
	 * T-102: {@link BossEntry#boss} by {@link #isMonster}, for the Bosses and Monsters sections; the rooms of a boss's
	 * adds alone ({@link #addsOf}) under that boss when it's in the history. {@code query} filters by name, case aside.
	 */
	static List<BossEntry> bosses(List<Fight> history, String query, BossRules rules)
	{
		final String q = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);
		final Map<String, List<Encounter>> byName = new LinkedHashMap<>();
		final Map<String, Set<String>> addRooms = new LinkedHashMap<>();
		for (Encounter room : allRooms(history))
		{
			if (fought(room))
			{
				byName.computeIfAbsent(room.getName(), k -> new ArrayList<>()).add(room);
			}
		}
		// The adds' own rooms go under their boss when it has rooms of its own; without them they stay as they are.
		for (Map.Entry<String, List<Encounter>> e : new ArrayList<>(byName.entrySet()))
		{
			String group = null;
			for (Encounter room : e.getValue())
			{
				final String g = addsOf(room);
				if (g == null || (group != null && !group.equals(g)))
				{
					group = null;
					break;
				}
				group = g;
			}
			if (group != null && byName.containsKey(group))
			{
				addRooms.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(e.getKey());
				byName.remove(e.getKey());
			}
		}
		final List<BossEntry> out = new ArrayList<>();
		for (Map.Entry<String, List<Encounter>> e : byName.entrySet())
		{
			final String name = e.getKey();
			final List<Encounter> rooms = e.getValue();
			final boolean boss = rooms.stream().anyMatch(r -> !isMonster(r, rules));
			if (!q.isEmpty() && !name.toLowerCase(Locale.ENGLISH).contains(q))
			{
				continue;
			}
			int kills = 0;
			Encounter latestKill = null;
			// T-095: runs (T-089) count their kills, not themselves.
			int runs = 0;
			int runKills = 0;
			double best = -1;
			final Set<String> adds = new LinkedHashSet<>();
			for (Encounter room : rooms)
			{
				if (room.isMonsterRoom())
				{
					if (isKill(room))
					{
						runs++;
						runKills += room.getKills();
						final double seconds = SidePanelModel.savedRunSeconds(room);
						if (seconds >= SidePanelModel.PER_HOUR_FROM_SECONDS)
						{
							best = Math.max(best, room.getKills() * 3600.0 / seconds);
						}
					}
				}
				else if (isKill(room))
				{
					kills++;
					if (latestKill == null)
					{
						latestKill = room;
					}
				}
				for (Fight f : room.getFights())
				{
					if (f.getAttempts() > 0 && !f.getTargetName().equals(name))
					{
						adds.add(f.getTargetName());
					}
				}
			}
			adds.addAll(addRooms.getOrDefault(name, Collections.emptySet()));
			final LedgerHistory.Average a = latestKill == null ? null
				: LedgerHistory.of(history, LedgerHistory.Like.of(latestKill), null);
			out.add(new BossEntry(name, kills + runKills, a == null ? null : LedgerRows.duration(a.actual.median),
				new ArrayList<>(adds), boss, icon(name, rooms), runs, kills + runKills, best < 0 ? null
					: LedgerRows.number(Math.round(best))));
		}
		return out;
	}

	// ---- the boss card ----

	/** A version in the picker: a {@link LedgerHistory.Like} and its kills. */
	static final class Version
	{
		final LedgerHistory.Like like;
		final String label;
		final int kills;

		Version(LedgerHistory.Like like, String label, int kills)
		{
			this.like = like;
			this.label = label;
			this.kills = kills;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** The last like kills' clocks, oldest first, for the sparkline. */
	static final class Spark
	{
		final double[] clock;
		final double[] star;
		/** The luck-removed clock's middle half, drawn as a band. */
		final double bandLow;
		final double bandHigh;

		Spark(double[] clock, double[] star, double bandLow, double bandHigh)
		{
			this.clock = clock;
			this.star = star;
			this.bandLow = bandLow;
			this.bandHigh = bandHigh;
		}
	}

	/** Your usual kill on one version: the medians over the last 20 like kills. */
	static final class Usual
	{
		/** The kills it's taken over, newest first. */
		final List<Encounter> kills;
		final LedgerHistory.Spread clock;
		final LedgerHistory.Spread star;
		final double execution;
		final double damageTaken;
		/**
		 * T-044: the kills Execution's median is over. When the boss has kills classed by attack rules, only those (their
		 * Execution counts avoidable eating, older ones' doesn't); under {@link LedgerRows#AVERAGE_FROM_KILLS} of them, no
		 * median.
		 */
		final int executionKills;

		Usual(List<Encounter> kills, LedgerHistory.Spread clock, LedgerHistory.Spread star, double execution,
			double damageTaken)
		{
			this(kills, clock, star, execution, damageTaken, kills.size());
		}

		Usual(List<Encounter> kills, LedgerHistory.Spread clock, LedgerHistory.Spread star, double execution,
			double damageTaken, int executionKills)
		{
			this.kills = kills;
			this.clock = clock;
			this.star = star;
			this.execution = execution;
			this.damageTaken = damageTaken;
			this.executionKills = executionKills;
		}
	}

	/** The boss card (spec 3.2): name and kills, the version picker, the usual tiles and the sparkline. */
	static final class Card
	{
		final String name;
		final int kills;
		final List<Version> versions;
		final int selected;
		final List<Tile> tiles;
		/** Null under two like kills (spec: hidden). */
		final Spark spark;
		/** The boss's icon beside its name (T-054). */
		final int icon;
		/** T-095: a kind fought in runs: how many with kills, and no version picker or sparkline. */
		final int runs;

		Card(String name, int kills, List<Version> versions, int selected, List<Tile> tiles, Spark spark, int icon)
		{
			this(name, kills, versions, selected, tiles, spark, icon, 0);
		}

		Card(String name, int kills, List<Version> versions, int selected, List<Tile> tiles, Spark spark, int icon,
			int runs)
		{
			this.runs = runs;
			this.icon = icon;
			this.name = name;
			this.kills = kills;
			this.versions = Collections.unmodifiableList(versions);
			this.selected = selected;
			this.tiles = Collections.unmodifiableList(tiles);
			this.spark = spark;
		}
	}

	/**
	 * The last {@link LedgerRows#AVERAGE_OF_LAST} kills like {@code like} that
	 * {@link LedgerHistory#counts} counts, newest first: the same kills
	 * {@link LedgerHistory#of} takes your average from.
	 */
	static List<Encounter> likeKills(List<Fight> history, LedgerHistory.Like like)
	{
		final List<Encounter> out = new ArrayList<>();
		if (like == null || like.name == null)
		{
			return out;
		}
		final Set<Fight> none = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<Encounter> rooms = LedgerHistory.rooms(history, like.name);
		for (int i = rooms.size() - 1; i >= 0 && out.size() < LedgerRows.AVERAGE_OF_LAST; i--)
		{
			final Encounter room = rooms.get(i);
			if (like.matches(room) && LedgerHistory.counts(room, none)
				&& !Double.isNaN(new LedgerRows.Numbers(LedgerRows.of(room)).luck))
			{
				out.add(room);
			}
		}
		return out;
	}

	/** Medians and quartiles over {@link #likeKills}, or null under {@link LedgerRows#AVERAGE_FROM_KILLS}. */
	static Usual usual(List<Fight> history, LedgerHistory.Like like)
	{
		final List<Encounter> kills = likeKills(history, like);
		if (kills.size() < LedgerRows.AVERAGE_FROM_KILLS)
		{
			return null;
		}
		final double[] clock = new double[kills.size()];
		final double[] star = new double[kills.size()];
		final double[] taken = new double[kills.size()];
		final List<Double> execution = new ArrayList<>();
		final List<Double> ruledExecution = new ArrayList<>();
		int ruled = 0;
		for (int i = 0; i < kills.size(); i++)
		{
			final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(kills.get(i)));
			clock[i] = n.clock;
			star[i] = n.expectedDuration;
			taken[i] = kills.get(i).getDamageTaken();
			// T-044: a kill classed by attack rules has avoidable eating in its Execution; it compares with its own kind.
			final boolean classed = !Double.isNaN(n.avoidableEatingCost);
			ruled += classed ? 1 : 0;
			if (!Double.isNaN(n.execution))
			{
				execution.add(n.execution);
				if (classed)
				{
					ruledExecution.add(n.execution);
				}
			}
		}
		final boolean byRules = ruled > 0;
		final List<Double> over = byRules ? ruledExecution : execution;
		Arrays.sort(clock);
		Arrays.sort(star);
		Arrays.sort(taken);
		final double[] exec = over.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		final int executionKills = byRules ? ruled : kills.size();
		final boolean enough = executionKills >= LedgerRows.AVERAGE_FROM_KILLS;
		return new Usual(kills, LedgerHistory.Spread.of(clock), LedgerHistory.Spread.of(star),
			exec.length == 0 || !enough ? Double.NaN : LedgerHistory.quantile(exec, 0.5),
			LedgerHistory.quantile(taken, 0.5), executionKills);
	}

	/**
	 * The card for {@code name}, on version {@code pick} or, when it's null or
	 * not among the boss's, the version of its latest kill. Null when the name
	 * has no rooms.
	 */
	static Card card(List<Fight> history, String name, LedgerHistory.Like pick, Versions names)
	{
		final List<Encounter> rooms = new ArrayList<>(LedgerHistory.rooms(history, name));
		Collections.reverse(rooms);
		final List<Encounter> kills = new ArrayList<>();
		for (Encounter r : rooms)
		{
			if (fought(r) && isKill(r))
			{
				kills.add(r);
			}
		}
		if (rooms.isEmpty())
		{
			return null;
		}
		final Card runs = runCard(name, rooms);
		if (runs != null)
		{
			return runs;
		}
		// Versions in the order their latest kill came, newest first.
		final Map<String, Version> byKey = new LinkedHashMap<>();
		for (Encounter k : kills)
		{
			final LedgerHistory.Like like = LedgerHistory.Like.of(k);
			final String key = key(like);
			final Version had = byKey.get(key);
			if (had == null)
			{
				byKey.put(key, new Version(like, versionLabel(k, like, names), 1));
			}
			else
			{
				byKey.put(key, new Version(had.like, had.label, had.kills + 1));
			}
		}
		final List<Version> versions = new ArrayList<>();
		for (Version v : byKey.values())
		{
			versions.add(new Version(v.like, v.label + " (" + v.kills + ")", v.kills));
		}
		int selected = 0;
		if (pick != null)
		{
			for (int i = 0; i < versions.size(); i++)
			{
				if (key(versions.get(i).like).equals(key(pick)))
				{
					selected = i;
				}
			}
		}
		final LedgerHistory.Like like = versions.isEmpty() ? null : versions.get(selected).like;
		final Usual usual = like == null ? null : usual(history, like);
		final int likeCount = like == null ? 0 : likeKills(history, like).size();
		return new Card(name, kills.size(), versions, selected, tiles(usual, likeCount), spark(history, like),
			icon(name, rooms));
	}

	/**
	 * T-095: the card for a kind fought in runs (T-089), or null for a boss and for a kind saved only before T-089: its
	 * runs and kills, and four tiles in the usual ones' place. No version picker and no sparkline: a run has no "your
	 * average" to pick or chart. Kills from before T-089, one per fight, are in the kills.
	 */
	static Card runCard(String name, List<Encounter> rooms)
	{
		int runs = 0;
		int kills = 0;
		double runSeconds = 0;
		double scoredSeconds = 0;
		int damage = 0;
		double expected = 0;
		int taken = 0;
		int runKills = 0;
		double best = -1;
		for (Encounter r : rooms)
		{
			if (!fought(r) || !isKill(r))
			{
				continue;
			}
			if (!r.isMonsterRoom())
			{
				kills++;
				continue;
			}
			runs++;
			kills += r.getKills();
			runKills += r.getKills();
			final double seconds = SidePanelModel.savedRunSeconds(r);
			runSeconds += seconds;
			final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(r));
			scoredSeconds += n.scoredClock;
			damage += Math.max(0, n.damage);
			expected += Math.max(0, n.expected);
			taken += Math.max(0, r.getDamageTaken());
			if (seconds >= SidePanelModel.PER_HOUR_FROM_SECONDS)
			{
				best = Math.max(best, r.getKills() * 3600.0 / seconds);
			}
		}
		if (runs == 0)
		{
			return null;
		}
		final List<Tile> tiles = new ArrayList<>(4);
		tiles.add(tile("best kills/hr", best < 0 ? LedgerRows.UNKNOWN : LedgerRows.number(Math.round(best)),
			best < 0 ? "from runs of a minute" : "best run", SidePanelStyle.TRACK));
		final String all = SidePanelModel.perHour(runKills, runSeconds);
		tiles.add(tile("kills/hr", all == null ? LedgerRows.UNKNOWN : all, "over " + plural(runs, "run"),
			SidePanelStyle.TRACK));
		tiles.add(tile("DPS", scoredSeconds <= 0 ? LedgerRows.UNKNOWN : LedgerRows.oneDecimal(damage / scoredSeconds),
			scoredSeconds > 0 && expected > 0 ? "expected " + LedgerRows.oneDecimal(expected / scoredSeconds) : "",
			SidePanelStyle.TRACK));
		tiles.add(tile("taken per kill", runKills == 0 ? LedgerRows.UNKNOWN
			: LedgerRows.oneDecimal((double) taken / runKills), "damage", SidePanelStyle.TRACK));
		return new Card(name, kills, Collections.emptyList(), 0, tiles, null, icon(name, rooms), runs);
	}

	/** T-054: the icon for rooms of this name, by their raid when they were fought in one. */
	static int icon(String name, List<Encounter> rooms)
	{
		for (Encounter r : rooms)
		{
			if (r.getRaid() != null)
			{
				return SidePanelBossIcons.sprite(name, r.getRaid());
			}
		}
		return SidePanelBossIcons.sprite(name, null);
	}

	static String key(LedgerHistory.Like like)
	{
		return like.name + "|" + like.maxHp + "|" + like.solo;
	}

	/** {@code Post-quest · solo}, or {@code 1,400 HP · group} where the boss rules give no name. */
	static String versionLabel(Encounter room, LedgerHistory.Like like, Versions names)
	{
		final String name = names == null ? null : names.name(room, like.maxHp);
		final String version = name != null ? name : like.maxHp > 0 ? SidePanelModel.hp(like.maxHp) : "unknown HP";
		return version + " · " + (like.solo ? "solo" : "group");
	}

	/** The four usual tiles; under five like kills each reads {@code —} with "from 5 kills (3 so far)". */
	static List<Tile> tiles(Usual u, int kills)
	{
		final List<Tile> tiles = new ArrayList<>(4);
		if (u == null)
		{
			final String sub = "from " + LedgerRows.AVERAGE_FROM_KILLS + " kills (" + kills + " so far)";
			for (String label : new String[]{"your average", "luck removed", "execution", "damage taken"})
			{
				tiles.add(tile(label, LedgerRows.UNKNOWN, sub, label.equals("luck removed") ? LUCK_REMOVED
					: SidePanelStyle.TRACK));
			}
			return tiles;
		}
		tiles.add(tile("your average", LedgerRows.duration(u.clock.median), middleHalf(u.clock), SidePanelStyle.TRACK));
		tiles.add(tile("luck removed", LedgerRows.duration(u.star.median), middleHalf(u.star), LUCK_REMOVED));
		// T-044: over ruled kills only once the boss has any, with the same under-5 rule as the others.
		tiles.add(u.executionKills < LedgerRows.AVERAGE_FROM_KILLS
			? tile("execution", LedgerRows.UNKNOWN, "from " + LedgerRows.AVERAGE_FROM_KILLS + " kills ("
				+ u.executionKills + " so far)", SidePanelStyle.TRACK)
			: tile("execution", Double.isNaN(u.execution) ? LedgerRows.UNKNOWN : LedgerRows.partPercent(u.execution),
				"median", SidePanelStyle.TRACK));
		tiles.add(tile("damage taken", LedgerRows.number(Math.round(u.damageTaken)), "median", SidePanelStyle.TRACK));
		return tiles;
	}

	/** {@code middle half 1:37 to 2:07}: no en dash, the fonts haven't one (spec section 1). */
	static String middleHalf(LedgerHistory.Spread s)
	{
		return "middle half " + LedgerRows.duration(s.lowerQuartile) + " to " + LedgerRows.duration(s.upperQuartile);
	}

	private static Tile tile(String label, String value, String sub, Color edge)
	{
		return new Tile(Text.small(label, SidePanelStyle.SECONDARY), Text.title(value, SidePanelStyle.VALUE),
			Text.small(sub, SidePanelStyle.MUTED), edge);
	}

	/** The last like kills, oldest left; null under two (spec 3.2). */
	static Spark spark(List<Fight> history, LedgerHistory.Like like)
	{
		final List<Encounter> kills = likeKills(history, like);
		if (kills.size() < 2)
		{
			return null;
		}
		final int n = kills.size();
		final double[] clock = new double[n];
		final double[] star = new double[n];
		for (int i = 0; i < n; i++)
		{
			// Oldest left: the list is newest first.
			final LedgerRows.Numbers num = new LedgerRows.Numbers(LedgerRows.of(kills.get(n - 1 - i)));
			clock[i] = num.clock;
			star[i] = num.expectedDuration;
		}
		final double[] sorted = star.clone();
		Arrays.sort(sorted);
		return new Spark(clock, star, LedgerHistory.quantile(sorted, 0.25), LedgerHistory.quantile(sorted, 0.75));
	}

	// ---- the kills list ----

	/** One kill row (spec 3.2): clock, date, luck chip, execution, and the room it opens. */
	static final class KillRow
	{
		final Encounter room;
		final String clock;
		final String date;
		/** The chip's word and colour, or null when luck is unknown. */
		final Text chip;
		final Color chipFill;
		final String execution;
		/** For the Trip tab's rows: the room's name, and whether it ended without a kill. */
		final String name;
		final boolean ended;
		/**
		 * T-095: a run's kills (T-089), its name then {@code Abyssal demon ×12}, its chip its kills/hr, and no execution;
		 * -1 for a boss's room, or a fight saved before T-089.
		 */
		final int kills;

		KillRow(Encounter room, String clock, String date, Text chip, Color chipFill, String execution, String name,
			boolean ended)
		{
			this(room, clock, date, chip, chipFill, execution, name, ended, -1);
		}

		KillRow(Encounter room, String clock, String date, Text chip, Color chipFill, String execution, String name,
			boolean ended, int kills)
		{
			this.kills = kills;
			this.room = room;
			this.clock = clock;
			this.date = date;
			this.chip = chip;
			this.chipFill = chipFill;
			this.execution = execution;
			this.name = name;
			this.ended = ended;
		}
	}

	/** The boss's kills, newest first (spec 3.2). */
	static List<KillRow> kills(List<Fight> history, String name, long now, ZoneId zone)
	{
		final List<Encounter> rooms = new ArrayList<>(LedgerHistory.rooms(history, name));
		Collections.reverse(rooms);
		final List<KillRow> out = new ArrayList<>();
		for (Encounter r : rooms)
		{
			if (fought(r) && isKill(r))
			{
				out.add(row(r, now, zone));
			}
		}
		return out;
	}

	/** A room as a row: its own figures, the overlay's chip rules. */
	static KillRow row(Encounter room, long now, ZoneId zone)
	{
		final long end = endMillis(room);
		if (room.isMonsterRoom())
		{
			return runRow(room, end > 0 ? SidePanelModel.rowDate(end, now, zone) : "");
		}
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(room));
		final boolean warning = room.getStatsWarning() != null;
		return new KillRow(room, LedgerRows.clock(room.getCombatTicks()),
			end > 0 ? SidePanelModel.rowDate(end, now, zone) : "", SidePanelModel.chip(n, warning),
			chipFill(n, warning), Double.isNaN(n.execution) ? LedgerRows.UNKNOWN : LedgerRows.partPercent(n.execution),
			room.getName(), !isKill(room));
	}

	/**
	 * T-095: a run as a row (T-089): {@code Abyssal demon ×12}, its clock, and its kills/hr in the luck chip's and
	 * Execution's place, neutral. No luck or execution: over a run they mean little (Eric, 2026-10-29).
	 */
	static KillRow runRow(Encounter room, String date)
	{
		final int kills = room.getKills();
		// The run's own clock, as the overlay's (T-094): kills/hr over it.
		final double seconds = SidePanelModel.savedRunSeconds(room);
		final String rate = SidePanelModel.perHour(kills, seconds);
		return new KillRow(room, LedgerRows.duration(seconds), date,
			rate == null ? null : Text.small(rate + "/hr", SidePanelStyle.BODY), rate == null ? null
				: SidePanelStyle.CHIP_NEUTRAL, "", SidePanelModel.runName(room.getName(), kills), !isKill(room), kills);
	}

	private static Color chipFill(LedgerRows.Numbers n, boolean warning)
	{
		return warning || Double.isNaN(n.luck) ? null : SidePanelStyle.chip(SidePanelModel.luckColour(n));
	}

	/** {@code Show 29 more}, or null when every kill is shown. */
	static String showMore(int total, int shown)
	{
		return shown >= total ? null : "Show " + (total - shown) + " more";
	}

	// ---- the saved kill view ----

	/**
	 * A saved room as the Fight tab's after-fight input (spec 3.2, state 11).
	 * Kills from before this RuneLite session ({@code sessionStart}) get the
	 * "later" card in the damage rows' place; this session's keep the Now rows
	 * until T-036 adds charts.
	 */
	static SidePanelModel.Input savedKill(List<Fight> history, Encounter room, long sessionStart, long now, ZoneId zone,
		Versions names)
	{
		return savedKill(history, room, sessionStart, now, zone, names, null);
	}

	/**
	 * {@code view}: the room's timeline from this session (T-036), for its damage
	 * rows, chart and strip; null while it's being fetched. An empty one (charts
	 * off, or the fights dropped past the store's cap) gets a card saying so.
	 */
	static SidePanelModel.Input savedKill(List<Fight> history, Encounter room, long sessionStart, long now, ZoneId zone,
		Versions names, FightTimeline.View view)
	{
		return savedKill(history, room, sessionStart, now, zone, names, view, null);
	}

	/**
	 * T-099: {@code partViews}, the timelines of the room's boss parts and adds ({@link SidePanelModel#parts}), in that
	 * order, for the Damage card's switch; null while they're fetched, or for a room without one.
	 */
	static SidePanelModel.Input savedKill(List<Fight> history, Encounter room, long sessionStart, long now, ZoneId zone,
		Versions names, FightTimeline.View view, FightTimeline.View[] partViews)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.saved = true;
		in.ended = true;
		in.killed = isKill(room);
		if (in.run)
		{
			in.runSeconds = SidePanelModel.savedRunSeconds(room);
		}
		in.endMillis = endMillis(room);
		in.warning = room.getStatsWarning();
		in.now = now;
		in.zone = zone;
		in.historyFights = history.size();
		in.version = names == null ? null : names.name(room, in.maxHp);
		in.avoidableAttacks = names == null ? Collections.emptySet() : names.avoidable(room);
		// T-095: a run has no average to set against, and no luck to remove.
		if (in.killed && room.getRaid() == null && in.warning == null && !in.run)
		{
			in.average = LedgerHistory.of(history, room);
			in.luckRemoved = in.average == null ? null : SidePanelModel.luckRemoved(history, room);
		}
		if (!fromThisSession(room, sessionStart))
		{
			in.later = SidePanelModel.LATER_TEXT;
		}
		else if (view != null && view.states.length == 0)
		{
			in.later = SidePanelModel.NO_CHARTS_TEXT;
		}
		else if (view != null)
		{
			in.view = view;
			if (in.bossOnly != null && partViews != null)
			{
				in.bossOnly.view = partViews[0];
				in.addsOnly.view = partViews[1];
			}
		}
		return in;
	}

	/** Every fight of the room started in this RuneLite session, so its timeline may still be held. */
	static boolean fromThisSession(Encounter room, long sessionStart)
	{
		if (room.getFights().isEmpty())
		{
			return false;
		}
		for (Fight f : room.getFights())
		{
			if (f.getStartMillis() < sessionStart)
			{
				return false;
			}
		}
		return true;
	}

	// ---- the trip ----

	/**
	 * The saved rooms fought since {@code since}, newest first (spec 3.3): kills
	 * and ended fights. Cached by the panel until the history or the trip
	 * changes; {@link #tripRooms} adds the running room each tick.
	 */
	static List<KillRow> tripSaved(List<Fight> history, long since, long now, ZoneId zone)
	{
		final List<KillRow> out = new ArrayList<>();
		for (Encounter room : allRooms(history))
		{
			// T-123: only what started since the trip did.
			final Encounter part = sinceTrip(room, since);
			if (part != null && fought(part))
			{
				out.add(row(part, now, zone));
			}
		}
		return out;
	}

	/**
	 * T-123: the part of a room that belongs to the trip started at {@code since}, as a trip holds only fights started at
	 * or after its start. The whole room when its first fight started then or later. None of a boss's room that was
	 * already running when New trip was clicked: it is the old trip's, the fights after the click in it too, as the kill
	 * closing it. Of a run of monsters (T-089), which goes on across the click, the fights started since, as a run of their
	 * own. Null for none.
	 */
	static Encounter sinceTrip(Encounter room, long since)
	{
		if (room == null || room.getFights().isEmpty())
		{
			return room;
		}
		if (firstStart(room) >= since)
		{
			return room;
		}
		if (!room.isMonsterRoom())
		{
			return null;
		}
		Encounter part = null;
		for (Fight f : room.getFights())
		{
			if (f.getStartMillis() >= since)
			{
				if (part == null)
				{
					part = new Encounter(room.getName(), room.getRaid(), f.getStartMillis());
				}
				part.add(f);
			}
		}
		return part;
	}

	/** T-123: when a room began, by its first fight: a live room can be opened a moment before its fight. */
	static long firstStart(Encounter room)
	{
		long first = Long.MAX_VALUE;
		for (Fight f : room.getFights())
		{
			first = Math.min(first, f.getStartMillis());
		}
		return first == Long.MAX_VALUE ? room.getStartMillis() : first;
	}

	/**
	 * The trip's rooms: the room still being fought first, then the saved ones,
	 * less any that shares a fight with it (a Sire room's finished respiratory
	 * systems are saved before the room closes).
	 */
	static List<KillRow> tripRooms(List<KillRow> saved, Encounter running)
	{
		if (running == null)
		{
			return saved;
		}
		final Set<Fight> live = Collections.newSetFromMap(new IdentityHashMap<>());
		live.addAll(running.getFights());
		final List<KillRow> out = new ArrayList<>(saved.size() + 1);
		if (running.isMonsterRoom())
		{
			// T-095: the run being fought: its kills and kills/hr, never "(ended)".
			final KillRow r = runRow(running, "");
			out.add(new KillRow(running, r.clock, "", r.chip, r.chipFill, "", r.name, false, r.kills));
		}
		else
		{
			final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(running));
			final boolean warning = running.getStatsWarning() != null;
			out.add(new KillRow(running, LedgerRows.clock(running.getCombatTicks()), "", SidePanelModel.chip(n, warning),
				chipFill(n, warning), "", running.getName(), false));
		}
		for (KillRow r : saved)
		{
			if (r.room.getFights().stream().noneMatch(live::contains))
			{
				out.add(r);
			}
		}
		return out;
	}

	private static String plural(int n, String noun)
	{
		return n + " " + noun + (n == 1 ? "" : "s");
	}

	/** Whether two picks are the same version. */
	static boolean same(LedgerHistory.Like a, LedgerHistory.Like b)
	{
		return a != null && b != null && Objects.equals(key(a), key(b));
	}
}

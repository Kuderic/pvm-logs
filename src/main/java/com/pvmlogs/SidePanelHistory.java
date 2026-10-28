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
		for (Fight f : room.getFights())
		{
			if (f.isClosedRoom() || (f.getGroupName() == null && f.isTargetDied()))
			{
				return true;
			}
		}
		return false;
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

		BossEntry(String name, int kills, String average, List<String> adds, boolean boss)
		{
			this.name = name;
			this.kills = kills;
			this.average = average;
			this.adds = Collections.unmodifiableList(adds);
			this.boss = boss;
		}

		/** The right side: {@code 23 · 1:49} for a boss with an average, else {@code 12 kills}. */
		String right()
		{
			return boss && average != null ? kills + " · " + average : plural(kills, "kill");
		}

		/** The sub-line: {@code with Respiratory system, Scion}, {@code not a boss}, or empty. */
		String sub()
		{
			if (!boss)
			{
				return "not a boss";
			}
			return adds.isEmpty() ? "" : "with " + String.join(", ", adds);
		}
	}

	/**
	 * Every room name in the history, most recently fought first, grouped so a
	 * boss's adds sit under it (spec 3.2 "All bosses"). {@code query} filters by
	 * name, case aside; {@code bossesOnly} drops what isn't a boss.
	 */
	static List<BossEntry> bosses(List<Fight> history, boolean bossesOnly, String query)
	{
		final String q = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);
		final Map<String, List<Encounter>> byName = new LinkedHashMap<>();
		for (Encounter room : allRooms(history))
		{
			if (fought(room))
			{
				byName.computeIfAbsent(room.getName(), k -> new ArrayList<>()).add(room);
			}
		}
		final List<BossEntry> out = new ArrayList<>();
		for (Map.Entry<String, List<Encounter>> e : byName.entrySet())
		{
			final String name = e.getKey();
			final List<Encounter> rooms = e.getValue();
			final boolean boss = rooms.stream().anyMatch(SidePanelHistory::isBoss);
			if ((bossesOnly && !boss) || (!q.isEmpty() && !name.toLowerCase(Locale.ENGLISH).contains(q)))
			{
				continue;
			}
			int kills = 0;
			Encounter latestKill = null;
			final Set<String> adds = new LinkedHashSet<>();
			for (Encounter room : rooms)
			{
				if (isKill(room))
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
			final LedgerHistory.Average a = latestKill == null ? null
				: LedgerHistory.of(history, LedgerHistory.Like.of(latestKill), null);
			out.add(new BossEntry(name, kills, a == null ? null : LedgerRows.duration(a.actual.median),
				new ArrayList<>(adds), boss));
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

		Card(String name, int kills, List<Version> versions, int selected, List<Tile> tiles, Spark spark)
		{
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
		return new Card(name, kills.size(), versions, selected, tiles(usual, likeCount), spark(history, like));
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

		KillRow(Encounter room, String clock, String date, Text chip, Color chipFill, String execution, String name,
			boolean ended)
		{
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
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(room));
		final boolean warning = room.getStatsWarning() != null;
		final long end = endMillis(room);
		return new KillRow(room, LedgerRows.clock(room.getCombatTicks()),
			end > 0 ? SidePanelModel.date(end, now, zone) : "", SidePanelModel.chip(n, warning),
			chipFill(n, warning), Double.isNaN(n.execution) ? LedgerRows.UNKNOWN : LedgerRows.partPercent(n.execution),
			room.getName(), !isKill(room));
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
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.saved = true;
		in.ended = true;
		in.killed = isKill(room);
		in.endMillis = endMillis(room);
		in.warning = room.getStatsWarning();
		in.now = now;
		in.zone = zone;
		in.historyFights = history.size();
		in.version = names == null ? null : names.name(room, in.maxHp);
		in.avoidableAttacks = names == null ? Collections.emptySet() : names.avoidable(room);
		if (in.killed && room.getRaid() == null && in.warning == null)
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
			if (room.getStartMillis() >= since && fought(room))
			{
				out.add(row(room, now, zone));
			}
		}
		return out;
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
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(running));
		final boolean warning = running.getStatsWarning() != null;
		out.add(new KillRow(running, LedgerRows.clock(running.getCombatTicks()), "", SidePanelModel.chip(n, warning),
			chipFill(n, warning), "", running.getName(), false));
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

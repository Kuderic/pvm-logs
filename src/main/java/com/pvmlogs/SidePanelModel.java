package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.BudgetRow;
import com.pvmlogs.PanelSnapshot.Fill;
import com.pvmlogs.PanelSnapshot.ListRow;
import com.pvmlogs.PanelSnapshot.Part;
import com.pvmlogs.PanelSnapshot.Row;
import com.pvmlogs.PanelSnapshot.Segment;
import com.pvmlogs.PanelSnapshot.Text;
import com.pvmlogs.PanelSnapshot.Tile;
import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the Fight tab's {@link PanelSnapshot} from what the plugin knows (T-033).
 * Two halves: {@link Input} is filled on the client thread from the plugin's
 * scope classes, and {@link #fight(Input)} turns it into texts and figures with
 * no client and no Swing, so every state can be built from a fake scope in a
 * test.
 * <p>
 * Every number comes from {@link LedgerRows.Numbers} on the overlay's own
 * {@link LedgerRows.Scope}, and every word and colour from the overlay's own
 * rules ({@link LedgerRows#band}, {@link LedgerRows#luckColour},
 * {@link LedgerRows#isCosting}), so the panel and the overlay can't disagree
 * (spec section 4).
 */
final class SidePanelModel
{
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final String DOT = " · ";
	static final String NO_FIGHT_TEXT = "Attack a monster and this tab fills in as you fight. The last fight stays here "
		+ "until the next one starts.";
	static final String NO_HISTORY_TEXT = "No kills saved yet. Kills are kept on this computer only.";
	static final String WARNING_BODY = "Its stats are placeholders or its version is unclear, so luck and expected "
		+ "damage are hidden until it's fixed.";
	static final String NO_CURVE_TEXT = "No curve: kills saved before 27 Oct didn't keep the spread of their hits, so "
		+ "their luck is in seconds only.";
	static final String NOT_A_KILL = "not a kill, so no average to compare";
	static final String RAID_NO_AVERAGE = "no average yet: raid level isn't recorded";
	/** The luck curve's marker stays inside the drawing. */
	static final double CURVE_Z_LIMIT = 2.9;

	private SidePanelModel()
	{
	}

	/** What the Fight tab is about, before any number is worked out. */
	enum Kind
	{
		EMPTY,
		PRE_ATTACK,
		ROOM,
		RAID
	}

	/** One target in the room, for the Targets list (spec 3.1). */
	static final class Target
	{
		String name;
		int fights;
		int damage;
		double expected;
		int attacks;
		int combatTicks;
		int phases;
		boolean scored;
	}

	/** One room of the raid, for the whole raid's Rooms list. */
	static final class Room
	{
		String name;
		int combatTicks;
		LedgerRows.Numbers numbers;
		boolean warning;
	}

	/** The previous room, for "Last fight" before the first attack. */
	static final class LastFight
	{
		String name;
		boolean killed;
		long endMillis;
		LedgerRows.Numbers numbers;
		boolean warning;
	}

	/** Everything the Fight tab needs, read on the client thread. */
	static final class Input
	{
		Kind kind = Kind.EMPTY;
		/** The room's name, the target's before the first attack, or the raid's. */
		String name;
		boolean ended;
		boolean killed;
		/** {@code getStatsWarning(fight)}, or null. */
		String warning;
		LedgerRows.Scope scope;
		/** The boss's full HP (LedgerHistory.Like), 0 when unknown. */
		int maxHp;
		/** Null when unknown (no fights yet). */
		Boolean solo;
		long startMillis;
		long endMillis;
		/** A raid's rooms so far. */
		int roomCount;
		/** The room has fights that aren't scored (T-020): the Sire's respiratory systems. */
		boolean unscored;
		/** The fights the scored figures are about: the room's name. */
		String scoredName;
		int hitsTaken;
		/** Thrall, DoT and reflected damage on the scored fights only, each apart, for the Damage rows. */
		int scoredThrall;
		int scoredDot;
		int scoredReflect;
		List<Target> targets = Collections.emptyList();
		List<Room> rooms = Collections.emptyList();
		/** The room is in a raid: no average (raid level isn't recorded). */
		boolean inRaid;
		/** Null outside a raid; else whether the switch reads Whole raid. */
		Boolean wholeRaid;
		LedgerHistory.Average average;
		/** The luck-removed clock's median and quartiles over the same like kills; null for none. */
		LedgerHistory.Spread luckRemoved;
		LedgerRows.Loadout loadout;
		/** The spec weapon's name for the loadout card, or null. */
		String specName;
		LastFight lastFight;
		int historyFights;
		ZoneId zone = ZoneId.systemDefault();
	}

	// ---- reading the plugin's objects (client thread) ----

	/** The room's scope and everything about it that the scope doesn't carry. */
	static void fillRoom(Input in, Encounter room)
	{
		in.scope = LedgerRows.of(room);
		in.name = room.getName();
		in.scoredName = room.getName();
		final List<Fight> fights = room.getFights();
		in.maxHp = maxHp(fights);
		in.solo = fights.isEmpty() ? null : solo(fights);
		in.startMillis = room.getStartMillis();
		in.unscored = anyUnscored(fights);
		in.hitsTaken = room.getHitsTaken();
		in.inRaid = room.getRaid() != null;
		fillOther(in, fights);
		in.targets = targets(fights);
	}

	/** The whole raid's scope and its rooms. */
	static void fillRaid(Input in, Raid raid)
	{
		in.scope = LedgerRows.of(raid);
		in.name = raid.getName();
		in.roomCount = raid.getEncounters().size();
		in.inRaid = true;
		in.hitsTaken = raid.getHitsTaken();
		final List<Fight> fights = new ArrayList<>();
		final List<Room> rooms = new ArrayList<>();
		for (Encounter e : raid.getEncounters())
		{
			fights.addAll(e.getFights());
			final Room r = new Room();
			r.name = e.getName();
			r.combatTicks = e.getCombatTicks();
			r.numbers = new LedgerRows.Numbers(LedgerRows.of(e));
			r.warning = e.getStatsWarning() != null;
			rooms.add(r);
		}
		in.startMillis = raid.getStartMillis();
		in.rooms = rooms;
		fillOther(in, fights);
	}

	/** The previous room for the "Last fight" row. */
	static LastFight lastFight(Encounter room, boolean ended)
	{
		if (room == null || !room.hasAttempts())
		{
			return null;
		}
		final LastFight l = new LastFight();
		l.name = room.getName();
		l.killed = ended && room.isKilled();
		long end = 0;
		for (Fight f : room.getFights())
		{
			end = Math.max(end, f.getEndMillis());
		}
		l.endMillis = end;
		l.numbers = new LedgerRows.Numbers(LedgerRows.of(room));
		l.warning = room.getStatsWarning() != null;
		return l;
	}

	private static void fillOther(Input in, List<Fight> fights)
	{
		int thrall = 0;
		int dot = 0;
		int reflect = 0;
		for (Fight f : fights)
		{
			if (f.isScored())
			{
				thrall += Math.max(0, f.getThrallDamage());
				dot += Math.max(0, f.getDotDamage());
				reflect += Math.max(0, f.getReflectDamage());
			}
		}
		in.scoredThrall = thrall;
		in.scoredDot = dot;
		in.scoredReflect = reflect;
	}

	private static int maxHp(List<Fight> fights)
	{
		int hp = 0;
		for (Fight f : fights)
		{
			hp = Math.max(hp, f.getMaxHp());
		}
		return hp;
	}

	private static boolean solo(List<Fight> fights)
	{
		for (Fight f : fights)
		{
			if (f.getOthersDamage() > 0)
			{
				return false;
			}
		}
		return true;
	}

	private static boolean anyUnscored(List<Fight> fights)
	{
		for (Fight f : fights)
		{
			if (!f.isScored())
			{
				return true;
			}
		}
		return false;
	}

	/** The room's fights grouped by target name, in the order they were first fought. */
	static List<Target> targets(List<Fight> fights)
	{
		final Map<String, Target> byName = new LinkedHashMap<>();
		final Map<String, Set<Integer>> ids = new LinkedHashMap<>();
		for (Fight f : fights)
		{
			if (f.getAttempts() <= 0)
			{
				// Opened by looking at it, never fought.
				continue;
			}
			final String name = f.getTargetName();
			Target t = byName.get(name);
			if (t == null)
			{
				t = new Target();
				t.name = name;
				t.scored = true;
				byName.put(name, t);
				ids.put(name, new LinkedHashSet<>());
			}
			t.fights++;
			t.damage += f.getDamageDealt();
			t.expected += f.getSumExpectedAverageHit();
			t.attacks += f.getAttacksMade();
			t.combatTicks += f.getCombatTicks();
			t.scored &= f.isScored();
			ids.get(name).add(f.getTargetId());
		}
		for (Target t : byName.values())
		{
			t.phases = ids.get(t.name).size();
		}
		return new ArrayList<>(byName.values());
	}

	/**
	 * Your luck-removed clock over the same like kills {@link LedgerHistory#of}
	 * takes your average from: the median and quartiles of each kill's
	 * {@code T* = T + luck} (spec section 4), or null under
	 * {@link LedgerRows#AVERAGE_FROM_KILLS}.
	 * <p>
	 * The kills are chosen as {@code LedgerHistory} chooses them; its filter is
	 * private to it, so the rule is restated here and pinned against it in
	 * {@code SidePanelModelTest}. Asked of the overlay chat (via the Orchestrator)
	 * to share it.
	 */
	static LedgerHistory.Spread luckRemoved(List<Fight> history, Encounter current)
	{
		if (history == null || current == null || current.getName() == null)
		{
			return null;
		}
		final LedgerHistory.Like like = LedgerHistory.Like.of(current);
		final Set<Fight> exclude = Collections.newSetFromMap(new IdentityHashMap<>());
		exclude.addAll(current.getFights());
		final double[] stars = new double[LedgerRows.AVERAGE_OF_LAST];
		int n = 0;
		final List<Encounter> rooms = LedgerHistory.rooms(history, like.name);
		for (int i = rooms.size() - 1; i >= 0 && n < stars.length; i--)
		{
			final Encounter room = rooms.get(i);
			if (!like.matches(room) || !counts(room, exclude))
			{
				continue;
			}
			final LedgerRows.Numbers numbers = new LedgerRows.Numbers(LedgerRows.of(room));
			if (!Double.isNaN(numbers.luck))
			{
				stars[n++] = numbers.expectedDuration;
			}
		}
		if (n < LedgerRows.AVERAGE_FROM_KILLS)
		{
			return null;
		}
		final double[] sorted = Arrays.copyOf(stars, n);
		Arrays.sort(sorted);
		return LedgerHistory.Spread.of(sorted);
	}

	/** {@code LedgerHistory.counts}, restated: a kill outside a raid, from a T-010-or-later build, with no warning. */
	static boolean counts(Encounter room, Collection<Fight> exclude)
	{
		boolean scored = false;
		boolean killed = false;
		for (Fight fight : room.getFights())
		{
			if (exclude.contains(fight) || fight.getRaidName() != null || fight.getStatsWarning() != null)
			{
				return false;
			}
			if (fight.isScored())
			{
				if (fight.getBoostSamples() <= 0)
				{
					return false;
				}
				scored = true;
			}
			killed |= fight.isClosedRoom() || (fight.getGroupName() == null && fight.isTargetDied());
		}
		return scored && killed;
	}

	// ---- the Fight tab (any thread: no client, no Swing) ----

	static PanelSnapshot.FightTab fight(Input in)
	{
		final PanelSnapshot.Builder b = new PanelSnapshot.Builder();
		switch (in.kind)
		{
			case EMPTY:
				b.state = PanelSnapshot.State.EMPTY;
				b.empty = new PanelSnapshot.Empty(in.historyFights > 0
					? "Your saved fights are on the History tab. They are kept on this computer only."
					: NO_HISTORY_TEXT, in.historyFights > 0);
				return b.build();
			case PRE_ATTACK:
				preAttack(b, in);
				return b.build();
			default:
				fighting(b, in);
				return b.build();
		}
	}

	private static void preAttack(PanelSnapshot.Builder b, Input in)
	{
		b.state = PanelSnapshot.State.PRE_ATTACK;
		b.wholeRaid = in.wholeRaid;
		final List<String> sub = new ArrayList<>();
		if (in.maxHp > 0)
		{
			sub.add(hp(in.maxHp));
		}
		sub.add("no attacks yet");
		b.header = new PanelSnapshot.Header(Text.title(in.name, SidePanelStyle.VALUE), Text.NONE,
			Text.small(String.join(DOT, sub), SidePanelStyle.SECONDARY),
			new Text(LedgerRows.clock(0), SidePanelStyle.UNSCORED, SidePanelStyle.Type.CLOCK));
		if (in.warning != null)
		{
			b.banner = new PanelSnapshot.Banner(WARNING_BODY, in.warning);
		}
		b.loadout = loadout(in.loadout, in.specName);
		if (in.lastFight != null)
		{
			b.lastFight = lastFightRow(in.lastFight, in.zone);
		}
	}

	private static void fighting(PanelSnapshot.Builder b, Input in)
	{
		final boolean raid = in.kind == Kind.RAID;
		final boolean after = in.ended;
		b.state = !after ? PanelSnapshot.State.LIVE
			: in.killed && !raid ? PanelSnapshot.State.DEAD : PanelSnapshot.State.ENDED;
		b.wholeRaid = in.wholeRaid;
		final LedgerRows.Scope s = in.scope;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		final boolean warning = in.warning != null;
		final LedgerRows.Colour luckColour = luckColour(n);

		b.header = header(in, s);
		if (warning)
		{
			b.banner = new PanelSnapshot.Banner(WARNING_BODY, in.warning);
		}
		if (in.unscored && !raid)
		{
			b.unscoredNote = "Luck, DPS and execution count the " + in.scoredName + "'s fights only ("
				+ LedgerRows.duration(n.scoredClock) + "). The clock is the whole room.";
		}
		if (!raid && in.targets.size() > 1)
		{
			b.targets = targets(in.targets, warning);
		}
		if (raid)
		{
			b.rooms = rooms(in.rooms);
		}
		if (after)
		{
			b.budget = budget(n, in, luckColour, warning);
		}
		b.tiles = tiles(n, s, in, luckColour, warning);
		if (after && !warning)
		{
			if (!Double.isNaN(n.percentile))
			{
				b.curve = curve(n, s, luckColour);
			}
			else if (!Double.isNaN(n.luck))
			{
				b.noCurve = NO_CURVE_TEXT;
			}
		}
		b.execution = execution(n);
		b.damage = damage(n, in);
		if (after)
		{
			b.details = details(s);
		}
		b.taken = taken(s, in.hitsTaken);
	}

	private static PanelSnapshot.Header header(Input in, LedgerRows.Scope s)
	{
		final List<String> sub = new ArrayList<>();
		Text suffix = Text.NONE;
		if (in.kind == Kind.RAID)
		{
			sub.add(in.roomCount + (in.roomCount == 1 ? " room" : " rooms"));
			if (in.startMillis > 0)
			{
				sub.add(time(in.startMillis, in.zone));
			}
		}
		else
		{
			if (in.maxHp > 0)
			{
				sub.add(hp(in.maxHp));
			}
			if (in.solo != null)
			{
				sub.add(in.solo ? "solo" : "group");
			}
			if (in.ended)
			{
				suffix = in.killed ? Text.title(" (dead)", SidePanelStyle.DEAD)
					: Text.title(" (ended)", SidePanelStyle.SECONDARY);
				if (in.endMillis > 0)
				{
					sub.add(time(in.endMillis, in.zone));
				}
			}
		}
		return new PanelSnapshot.Header(Text.title(in.name, SidePanelStyle.VALUE), suffix,
			Text.small(String.join(DOT, sub), SidePanelStyle.SECONDARY),
			new Text(LedgerRows.clock(s.combatTicks()), SidePanelStyle.VALUE, SidePanelStyle.Type.CLOCK));
	}

	/** Luck's colour role: by the percentile when it's known (T-029), else by the seconds, as the overlay goes. */
	static LedgerRows.Colour luckColour(LedgerRows.Numbers n)
	{
		return Double.isNaN(n.percentile) ? LedgerRows.luckColour(n.luck, n.clock) : LedgerRows.percentileBand(n.percentile);
	}

	// ---- tiles ----

	static List<Tile> tiles(LedgerRows.Numbers n, LedgerRows.Scope s, Input in, LedgerRows.Colour luckColour,
		boolean warning)
	{
		final List<Tile> tiles = new ArrayList<>(4);
		final Color luck = SidePanelStyle.of(luckColour);
		final Color edge = warning || luckColour == LedgerRows.Colour.NEUTRAL ? SidePanelStyle.TRACK : luck;

		// Luck.
		if (warning)
		{
			tiles.add(tile("Luck", Text.title("hidden", SidePanelStyle.MUTED),
				Text.small("boss data unsure", SidePanelStyle.MUTED), SidePanelStyle.TRACK));
		}
		else if (Double.isNaN(n.luck))
		{
			tiles.add(tile("Luck", Text.title(LedgerRows.UNKNOWN, SidePanelStyle.VALUE),
				Text.small(n.expected <= 0 ? "no monster data yet" : "", SidePanelStyle.SECONDARY), SidePanelStyle.TRACK));
		}
		else if (Double.isNaN(n.percentile))
		{
			tiles.add(tile("Luck", Text.title(LedgerRows.fasterOrSlower(n.luck), luck),
				Text.small("seconds only", SidePanelStyle.MUTED), edge));
		}
		else
		{
			tiles.add(tile("Luck", Text.title(LedgerRows.band(n.percentile), luck),
				Text.small(LedgerRows.fasterOrSlower(n.luck), SidePanelStyle.hint(luckColour)), edge));
		}

		// Damage/expected, as the overlay's row: the weapon's damage against what it was expected to deal.
		final String other = otherDamage(in);
		if (warning || n.expected <= 0)
		{
			tiles.add(tile("Damage", Text.title(LedgerRows.number(n.damage), SidePanelStyle.VALUE),
				Text.small(!other.isEmpty() ? other : warning ? "" : "no monster data yet", SidePanelStyle.SECONDARY),
				SidePanelStyle.TRACK));
		}
		else
		{
			tiles.add(tile("Damage/expected", Text.title(LedgerRows.number(n.damage) + "/"
				+ LedgerRows.number(Math.round(n.expected)), luck), Text.small(other, SidePanelStyle.SECONDARY), edge));
		}

		tiles.add(tile("DPS", Text.title(Double.isNaN(n.dps) ? LedgerRows.UNKNOWN : LedgerRows.oneDecimal(n.dps),
			SidePanelStyle.VALUE), Text.small(in.unscored && in.kind == Kind.ROOM ? "on the " + in.scoredName
			: "weapon only", SidePanelStyle.SECONDARY), SidePanelStyle.TRACK));

		final double lost = lostSeconds(n);
		final boolean costing = anyCosting(n);
		final Text lostText = LedgerRows.roundHalfAway(lost) >= 1
			? Text.small(SidePanelStyle.seconds(lost) + " lost", costing ? SidePanelStyle.COST : SidePanelStyle.SECONDARY)
			: Text.small(Double.isNaN(n.execution) ? "" : "no time lost", SidePanelStyle.MUTED);
		tiles.add(tile("Execution", Text.title(Double.isNaN(n.execution) ? LedgerRows.UNKNOWN
			: LedgerRows.partPercent(n.execution), SidePanelStyle.VALUE), lostText, SidePanelStyle.TRACK));
		return tiles;
	}

	private static Tile tile(String label, Text value, Text sub, Color edge)
	{
		return new Tile(Text.small(label, SidePanelStyle.SECONDARY), value, sub, edge);
	}

	/** {@code +80 thrall}, {@code +80 thrall, 8 burn}: the player's other damage on the scored fights, kind by kind. */
	static String otherDamage(Input in)
	{
		final List<String> parts = new ArrayList<>(3);
		if (in.scoredThrall > 0)
		{
			parts.add(LedgerRows.number(in.scoredThrall) + " thrall");
		}
		if (in.scoredDot > 0)
		{
			parts.add(LedgerRows.number(in.scoredDot) + " burn");
		}
		if (in.scoredReflect > 0)
		{
			parts.add(LedgerRows.number(in.scoredReflect) + " recoil");
		}
		return parts.isEmpty() ? "" : "+" + String.join(", ", parts);
	}

	/** Every part's seconds together: uptime, then the setup (boost, prayer, gear, or one setup figure). */
	static double lostSeconds(LedgerRows.Numbers n)
	{
		double lost = n.uptimeCost;
		if (!Double.isNaN(n.setupCost))
		{
			lost += n.setupCost;
		}
		return lost;
	}

	private static boolean anyCosting(LedgerRows.Numbers n)
	{
		for (double c : costs(n))
		{
			if (LedgerRows.isCosting(c, n.clock))
			{
				return true;
			}
		}
		return false;
	}

	/** The parts' seconds in the time budget's order (spec section 4): Cu, Cb, Cp, Cg, or Cu and one setup figure. */
	private static double[] costs(LedgerRows.Numbers n)
	{
		if (n.split)
		{
			return new double[]{n.uptimeCost, n.boostCost, n.prayerCost, n.gearCost};
		}
		return new double[]{n.uptimeCost, Double.isNaN(n.setupCost) ? 0 : n.setupCost};
	}

	private static String[] costLabels(LedgerRows.Numbers n)
	{
		return n.split ? new String[]{"uptime", "stat boost", "prayer", "gear"} : new String[]{"uptime", "setup"};
	}

	// ---- execution ----

	static PanelSnapshot.Parts execution(LedgerRows.Numbers n)
	{
		final List<Part> parts = new ArrayList<>(4);
		parts.add(part("uptime", n.uptime, n.uptimeCost, n));
		if (!Double.isNaN(n.setupCost))
		{
			if (n.split)
			{
				// The overlay's order (Eric, 2026-10-26): uptime, prayer, gear, stat boost.
				parts.add(part("prayer", n.prayerShare, n.prayerCost, n));
				parts.add(part("gear", n.gearShare, n.gearCost, n));
				final Part boost = part("stat boost", n.boostShare, n.boostCost, n);
				parts.add(n.drained ? new Part(boost.label, Text.small(LedgerRows.withSeconds(LedgerRows.DRAINED,
					n.boostCost), boost.value.colour), boost.kept, boost.lost) : boost);
			}
			else
			{
				// Fights from before T-010: one row stands in for the three, as on the overlay.
				parts.add(part("setup", n.efficiency, n.setupCost, n));
			}
		}
		return new PanelSnapshot.Parts(Text.title(Double.isNaN(n.execution) ? LedgerRows.UNKNOWN
			: LedgerRows.partPercent(n.execution), SidePanelStyle.VALUE), parts);
	}

	private static Part part(String label, double share, double cost, LedgerRows.Numbers n)
	{
		final boolean costing = LedgerRows.isCosting(cost, n.clock);
		final String figure = Double.isNaN(share) ? LedgerRows.UNKNOWN : LedgerRows.withSeconds(
			LedgerRows.partPercent(share), cost);
		return new Part(Text.small(label, SidePanelStyle.BODY),
			Text.small(figure, costing ? SidePanelStyle.COST : SidePanelStyle.BODY),
			Double.isNaN(share) ? Double.NaN : Math.max(0, Math.min(1, share)),
			costing ? SidePanelStyle.COST : SidePanelStyle.MINOR_COST);
	}

	// ---- where the time went ----

	static PanelSnapshot.TimeBudget budget(LedgerRows.Numbers n, Input in, LedgerRows.Colour luckColour,
		boolean warning)
	{
		final double t = n.clock;
		final double u = Math.max(0, n.clock - n.scoredClock);
		final boolean luckKnown = !warning && !Double.isNaN(n.luck);
		final double l = luckKnown ? n.luck : 0;
		final double[] costs = costs(n);
		final String[] labels = costLabels(n);
		double costSum = 0;
		for (double c : costs)
		{
			costSum += c;
		}
		final double best = t - u + l - costSum;
		final boolean bestKnown = luckKnown && best >= 0;
		final boolean averageBand = luckColour == LedgerRows.Colour.NEUTRAL;

		final List<Segment> segments = new ArrayList<>();
		segments.add(new Segment(Math.max(0, best), Fill.KEPT));
		for (double c : costs)
		{
			segments.add(new Segment(c, LedgerRows.isCosting(c, n.clock) ? Fill.COST : Fill.MINOR_COST));
		}
		segments.add(new Segment(Math.max(0, -l), averageBand ? Fill.LUCK_AVERAGE : Fill.LUCK_SLOWER));
		segments.add(new Segment(u, Fill.UNSCORED));
		final double total = t + Math.max(0, l);

		final List<BudgetRow> rows = new ArrayList<>();
		final String bestLabel = in.unscored && in.kind == Kind.ROOM ? "best play on the " + in.scoredName : "best play";
		rows.add(new BudgetRow(Fill.KEPT, Text.small(bestLabel, SidePanelStyle.BODY),
			in.unscored ? null : Text.small("no losses, average luck", SidePanelStyle.MUTED),
			Text.small(bestKnown ? SidePanelStyle.roundedDuration(best) : LedgerRows.UNKNOWN, SidePanelStyle.VALUE)));
		for (int i = 0; i < costs.length; i++)
		{
			if (LedgerRows.roundHalfAway(costs[i]) < 1)
			{
				continue;
			}
			final boolean costing = LedgerRows.isCosting(costs[i], n.clock);
			rows.add(new BudgetRow(costing ? Fill.COST : Fill.MINOR_COST, Text.small(labels[i], SidePanelStyle.BODY),
				null, Text.small("+" + SidePanelStyle.seconds(costs[i]), costing ? SidePanelStyle.COST : SidePanelStyle.BODY)));
		}
		// Luck always shows: what it added to the clock (+), or took off it (-).
		final Fill luckSwatch = averageBand ? Fill.LUCK_AVERAGE : l > 0 ? Fill.LUCK_FASTER : Fill.LUCK_SLOWER;
		final String luckValue;
		if (!luckKnown)
		{
			luckValue = LedgerRows.UNKNOWN;
		}
		else
		{
			final long r = LedgerRows.roundHalfAway(-l);
			luckValue = r == 0 ? LedgerRows.EVEN : (r > 0 ? "+" : "-") + Math.abs(r) + " s";
		}
		rows.add(new BudgetRow(luckSwatch, Text.small(averageBand && luckKnown ? "luck average" : "luck",
			SidePanelStyle.BODY), null, Text.small(luckValue, luckKnown ? SidePanelStyle.of(luckColour)
			: SidePanelStyle.VALUE)));
		if (u > 0)
		{
			rows.add(new BudgetRow(Fill.UNSCORED, Text.small("adds, not scored", SidePanelStyle.BODY), null,
				Text.small("+" + SidePanelStyle.seconds(u), SidePanelStyle.BODY)));
		}

		// Against your average: after a kill only, outside a raid, never under the warning (spec 3.1).
		final List<Row> average = new ArrayList<>(2);
		String note = null;
		double marker = Double.NaN;
		if (in.kind == Kind.RAID || in.inRaid)
		{
			note = RAID_NO_AVERAGE;
		}
		else if (!in.killed)
		{
			note = NOT_A_KILL;
		}
		else if (!warning && in.average != null && !Double.isNaN(n.expectedDuration))
		{
			final LedgerHistory.Spread a = in.average.actual;
			marker = a.median;
			average.add(new Row(Text.small("your average " + LedgerRows.duration(a.median), SidePanelStyle.BODY),
				Text.small(against(t, a.median), SidePanelStyle.of(usual(t, a)))));
			if (in.luckRemoved != null)
			{
				final LedgerHistory.Spread r = in.luckRemoved;
				average.add(new Row(Text.small("luck removed " + LedgerRows.duration(n.expectedDuration), SidePanelStyle.BODY),
					Text.small(against(n.expectedDuration, r.median) + (LedgerRows.roundHalfAway(n.expectedDuration - r.median)
						== 0 ? " with " : " than ") + LedgerRows.duration(r.median), SidePanelStyle.of(usual(n.expectedDuration,
						r)))));
			}
		}
		final double luckSaved = luckKnown && l > 0 ? l : 0;
		return new PanelSnapshot.TimeBudget(segments, total, t, luckSaved,
			averageBand ? SidePanelStyle.AVERAGE_STRIPE : SidePanelStyle.BETTER, marker, rows, average, note);
	}

	/** {@code 23 s slower}, {@code 5 s faster}, {@code even}: this kill's time against a usual one. */
	static String against(double seconds, double usual)
	{
		return LedgerRows.fasterOrSlower(usual - seconds);
	}

	/** Cost above your upper quartile, Better below your lower quartile, neutral inside (spec section 2). */
	static LedgerRows.Colour usual(double seconds, LedgerHistory.Spread spread)
	{
		if (seconds > spread.upperQuartile)
		{
			return LedgerRows.Colour.COST;
		}
		return seconds < spread.lowerQuartile ? LedgerRows.Colour.BETTER : LedgerRows.Colour.NEUTRAL;
	}

	// ---- the luck curve ----

	static PanelSnapshot.Curve curve(LedgerRows.Numbers n, LedgerRows.Scope s, LedgerRows.Colour luckColour)
	{
		double z = DamageMoments.z(n.damage, n.expected, s.damageVariance());
		if (Double.isNaN(z))
		{
			z = 0;
		}
		z = Math.max(-CURVE_Z_LIMIT, Math.min(CURVE_Z_LIMIT, z));
		final double p = n.percentile;
		final String caption;
		if (p < 0.5)
		{
			caption = Math.round(100 * (1 - p)) + "% of fights with these attacks dealt more";
		}
		else
		{
			caption = Math.round(100 * p) + "% of fights with these attacks dealt less";
		}
		return new PanelSnapshot.Curve(z, Text.title(LedgerRows.band(p), SidePanelStyle.of(luckColour)), caption);
	}

	// ---- lists ----

	private static PanelSnapshot.ListRows targets(List<Target> targets, boolean warning)
	{
		final List<ListRow> rows = new ArrayList<>(targets.size());
		for (Target t : targets)
		{
			final List<String> sub = new ArrayList<>();
			if (t.phases > 1)
			{
				sub.add(t.phases + " phases");
			}
			sub.add(t.attacks + (t.attacks == 1 ? " attack" : " attacks"));
			sub.add(LedgerRows.clock(t.combatTicks));
			final boolean figures = t.scored && !warning && t.expected > 0;
			final Color text = t.scored ? SidePanelStyle.VALUE : SidePanelStyle.MUTED;
			rows.add(new ListRow(Text.body(t.name + (t.fights > 1 && t.phases <= 1 ? " ×" + t.fights : ""), text), null,
				Text.title(figures ? LedgerRows.number(t.damage) + "/" + LedgerRows.number(Math.round(t.expected))
					: LedgerRows.number(t.damage), text),
				Text.small(String.join(DOT, sub), t.scored ? SidePanelStyle.SECONDARY : SidePanelStyle.MUTED),
				Text.small(t.scored ? "scored" : "not scored", t.scored ? SidePanelStyle.SECONDARY : SidePanelStyle.BODY),
				t.scored ? null : SidePanelStyle.CHIP_NEUTRAL));
		}
		return new PanelSnapshot.ListRows(Text.small("damage/expected", SidePanelStyle.SECONDARY), rows);
	}

	private static PanelSnapshot.ListRows rooms(List<Room> rooms)
	{
		final List<ListRow> rows = new ArrayList<>(rooms.size());
		for (Room r : rooms)
		{
			rows.add(new ListRow(Text.body(r.name, SidePanelStyle.VALUE), null,
				Text.title(LedgerRows.clock(r.combatTicks), SidePanelStyle.VALUE),
				Text.small("execution " + (Double.isNaN(r.numbers.execution) ? LedgerRows.UNKNOWN
					: LedgerRows.partPercent(r.numbers.execution)), SidePanelStyle.SECONDARY),
				chip(r.numbers, r.warning), chipFill(r.numbers, r.warning)));
		}
		return new PanelSnapshot.ListRows(Text.small("time · luck · execution", SidePanelStyle.SECONDARY), rows);
	}

	private static ListRow lastFightRow(LastFight l, ZoneId zone)
	{
		final List<String> sub = new ArrayList<>();
		if (l.endMillis > 0)
		{
			sub.add(time(l.endMillis, zone));
		}
		if (!Double.isNaN(l.numbers.execution))
		{
			sub.add("execution " + LedgerRows.partPercent(l.numbers.execution));
		}
		return new ListRow(Text.title(l.name, SidePanelStyle.VALUE),
			l.killed ? Text.title(" (dead)", SidePanelStyle.DEAD) : Text.title(" (ended)", SidePanelStyle.SECONDARY),
			Text.title(LedgerRows.duration(l.numbers.clock), SidePanelStyle.VALUE),
			Text.small(String.join(DOT, sub), SidePanelStyle.SECONDARY), chip(l.numbers, l.warning),
			chipFill(l.numbers, l.warning));
	}

	/** A list's luck chip: T-029's words, or the seconds for fights saved before T-028; null when unknown. */
	static Text chip(LedgerRows.Numbers n, boolean warning)
	{
		if (warning || Double.isNaN(n.luck))
		{
			return null;
		}
		final String word = Double.isNaN(n.percentile) ? LedgerRows.fasterOrSlower(n.luck) : LedgerRows.band(n.percentile);
		final LedgerRows.Colour c = luckColour(n);
		return Text.small(word, c == LedgerRows.Colour.NEUTRAL ? SidePanelStyle.BODY : SidePanelStyle.of(c));
	}

	private static Color chipFill(LedgerRows.Numbers n, boolean warning)
	{
		return warning || Double.isNaN(n.luck) ? null : SidePanelStyle.chip(luckColour(n));
	}

	// ---- damage, details, damage taken ----

	/** The Damage section's Now rows (spec 3.1): thrall, burn/poison, recoil/vengeance. Weapon rows come with T-036. */
	static PanelSnapshot.Sources damage(LedgerRows.Numbers n, Input in)
	{
		final int whole = Math.max(0, n.damage) + in.scoredThrall + in.scoredDot + in.scoredReflect;
		final List<PanelSnapshot.Source> rows = new ArrayList<>(3);
		source(rows, "Thrall", in.scoredThrall, whole, SidePanelStyle.THRALL);
		source(rows, "Burn and poison", in.scoredDot, whole, SidePanelStyle.DOT);
		source(rows, "Recoil and vengeance", in.scoredReflect, whole, SidePanelStyle.REFLECT);
		if (rows.isEmpty())
		{
			return null;
		}
		final String where = in.unscored && in.kind == Kind.ROOM ? " on the " + in.scoredName : " yours";
		return new PanelSnapshot.Sources(Text.small(LedgerRows.number(whole) + where, SidePanelStyle.SECONDARY), rows);
	}

	private static void source(List<PanelSnapshot.Source> rows, String name, int damage, int whole, Color colour)
	{
		if (damage <= 0)
		{
			return;
		}
		final double share = whole <= 0 ? 0 : (double) damage / whole;
		rows.add(new PanelSnapshot.Source(name, "no luck either way", LedgerRows.number(damage),
			Math.round(100 * share) + "%", share, colour));
	}

	/** After the fight: the overlay's Summary rows that the tiles and Damage taken don't already show. */
	static PanelSnapshot.Rows details(LedgerRows.Scope s)
	{
		final List<Row> rows = new ArrayList<>();
		if (s.expectedHits() > 0)
		{
			rows.add(row("hits/expected", s.hits() + "/" + LedgerRows.oneDecimal(s.expectedHits())));
		}
		if (s.thrallDamage() > 0)
		{
			rows.add(row("thrall dmg", LedgerRows.number(s.thrallDamage())));
		}
		if (s.reflectDamage() > 0)
		{
			rows.add(row("recoil/veng dmg", LedgerRows.number(s.reflectDamage())));
		}
		if (s.dotDamage() > 0)
		{
			rows.add(row("burn/poison dmg", LedgerRows.number(s.dotDamage())));
		}
		// The boss's doing rather than the player's, so after the fight only (own-output guardrail).
		if (s.healed() > 0)
		{
			rows.add(row("boss healed", LedgerRows.number(s.healed())));
		}
		if (s.ticksLostEating() > 0)
		{
			rows.add(row("eating", LedgerRows.roundHalfAway(s.ticksLostEating() * LedgerRows.SECONDS_PER_TICK) + " s"));
		}
		return rows.isEmpty() ? null : new PanelSnapshot.Rows(null, rows);
	}

	static PanelSnapshot.Rows taken(LedgerRows.Scope s, int hitsTaken)
	{
		if (s.damageTaken() <= 0 && s.dotTaken() <= 0)
		{
			return null;
		}
		final List<Row> rows = new ArrayList<>(4);
		if (hitsTaken > 0)
		{
			rows.add(row("hits", LedgerRows.number(hitsTaken)));
		}
		if (s.biggestHitTaken() > 0)
		{
			rows.add(row("biggest hit", LedgerRows.number(s.biggestHitTaken())));
		}
		rows.add(new Row(Text.small("without protection", SidePanelStyle.BODY),
			Text.small(LedgerRows.number(s.damageTakenUnprotected()), s.damageTakenUnprotected() > 0
				? SidePanelStyle.COST : SidePanelStyle.BODY)));
		if (s.dotTaken() > 0)
		{
			// Named as the overlay names it (Eric, 2026-10-27): burn and bleed count too.
			rows.add(row("burn/poison taken", LedgerRows.number(s.dotTaken())));
		}
		return new PanelSnapshot.Rows(Text.title(LedgerRows.number(s.damageTaken()), SidePanelStyle.VALUE), rows);
	}

	private static Row row(String label, String value)
	{
		return new Row(Text.small(label, SidePanelStyle.BODY), Text.small(value, SidePanelStyle.BODY));
	}

	// ---- before the first attack ----

	/** The loadout card: the overlay's loadout figures (T-022, T-030), each row only when known. */
	static PanelSnapshot.Loadout loadout(LedgerRows.Loadout l, String specName)
	{
		if (l == null)
		{
			return null;
		}
		final List<Row> rows = new ArrayList<>();
		if (l.dps >= 0)
		{
			rows.add(new Row(Text.body("Expected DPS", SidePanelStyle.BODY), Text.title(LedgerRows.oneDecimal(l.dps),
				SidePanelStyle.VALUE)));
		}
		if (l.maxHit > 0)
		{
			rows.add(row("max hit", String.valueOf(l.maxHit)));
		}
		if (l.accuracy >= 0)
		{
			rows.add(row("accuracy", LedgerRows.percent(l.accuracy)));
		}
		final List<Row> spec = new ArrayList<>();
		if (l.specMaxHit > 0)
		{
			spec.add(row("spec", l.specMaxHit + " max" + (l.specAccuracy >= 0 ? DOT + LedgerRows.percent(l.specAccuracy)
				: "")));
			if (l.specAverage >= 0)
			{
				spec.add(row("spec damage", LedgerRows.specDamage(l)));
			}
		}
		final String name = LedgerRows.attackLabel(l.attack);
		if (rows.isEmpty() && spec.isEmpty())
		{
			return null;
		}
		return new PanelSnapshot.Loadout(Text.title(name == null ? "Loadout" : name, SidePanelStyle.VALUE), rows,
			spec.isEmpty() ? null : Text.body(specName == null ? "Special attack" : specName, SidePanelStyle.VALUE), spec);
	}

	// ---- formats ----

	static String hp(int maxHp)
	{
		return LedgerRows.number(maxHp) + " HP";
	}

	static String time(long millis, ZoneId zone)
	{
		return TIME.format(Instant.ofEpochMilli(millis).atZone(zone));
	}
}

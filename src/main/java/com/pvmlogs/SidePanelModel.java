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
import java.util.Locale;
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
	/**
	 * T-072: a time of day, 12-hour with no leading zero ({@code 3:31}); {@link #clockTime} adds am or pm, so it can't
	 * be read as a kill time. English whatever the system locale.
	 */
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm", java.util.Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH);
	static final String NO_CHARTS_TEXT = "No damage chart for this kill: charts were off, or it was more than 50 fights "
		+ "ago.";
	static final String LATER_TEXT = "Kept only for fights since RuneLite started. Saving them with every kill is planned "
		+ "for later.";
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
	/** T-042: the time budget bar's end when luck saved time, after its clock. */
	static final String AT_AVERAGE_LUCK = " at average luck";
	/** T-042: luck's row's hint when luck saved time, before your clock: the stretch past it. */
	static final String PAST = "past ";
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
		RAID,
		/** The Trip tab's scope (T-034). */
		TRIP
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
		/** Every fight of the room has ended: its Execution may include avoidable eating (T-044). */
		boolean ended;
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

	/**
	 * T-052: what the defence drains did over a room (T-049's record, T-050's
	 * figures), or the trip's sums (the levels and specials -1 there).
	 */
	static final class Drain
	{
		int defenceStart = -1;
		int defenceEnd = -1;
		int defenceBase = -1;
		int defenceFloor = -1;
		int magicStart = -1;
		int magicEnd = -1;
		/** The player's own draining specials by RuneLite's weapon name: {thrown, landed}. */
		Map<String, int[]> specs = new LinkedHashMap<>();
		int partyDrains;
		/** T-050's drain value, realized, in damage and seconds (positive is time saved); NaN unknown. */
		double valueDamage = Double.NaN;
		double valueSeconds = Double.NaN;
		/** T-050's spec luck in damage and seconds; NaN unknown. */
		double specLuckDamage = Double.NaN;
		double specLuckSeconds = Double.NaN;

		int ownThrown()
		{
			int n = 0;
			for (int[] c : specs.values())
			{
				n += c[0];
			}
			return n;
		}
	}

	/** T-052: a target's tracked stats while the fight runs (T-049's tracker). */
	static final class LiveDefence
	{
		int defence;
		int defenceBase;
		int defenceFloor;
		int magic;
		int magicBase;
	}

	/** Everything the Fight tab needs, read on the client thread. */
	static final class Input
	{
		Kind kind = Kind.EMPTY;
		/** The room's name, the target's before the first attack, or the raid's. */
		String name;
		/** The raid the room or scope is in, or null: a raid's rooms show its icon (T-054). */
		RaidType raidType;
		/** T-052: the room's tracked drains, or the trip's sums; null where nothing was tracked. */
		Drain drain;
		/** T-052: the target's tracked stats while the fight runs, once a drain has landed; null before. */
		LiveDefence liveDefence;
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
		/** T-076: the part of {@link #hitsTaken} that were 0s; 0 for kills from before T-076, whose count stays one number. */
		int missedHitsTaken;
		/** Thrall, DoT and reflected damage on the scored fights only, each apart, for the Damage rows. */
		int scoredThrall;
		int scoredDot;
		/** T-063: {@link #scoredDot} by kind (T-062); the part with no kind is the remainder, an old kill's. */
		Map<String, Integer> scoredDotByKind = Collections.emptyMap();
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
		/** The boss version's name from the boss rules ("Post-quest"), shown in the HP's place; null for none. */
		String version;
		/** A saved kill opened from History (T-034): its date and "saved kill" under the name. */
		boolean saved;
		/** Now, for "today 15:31" against "26 Oct 21:40". */
		long now = System.currentTimeMillis();
		/** The "later" card in the damage rows' place (spec 3.2): a saved kill from before this RuneLite session. */
		String later;
		/**
		 * The scope's scored fights from {@link FightTimeline} (T-036), on one tick
		 * axis; null with no in-memory data (fights from before the panel loaded,
		 * past the store's cap, or with charts off).
		 */
		FightTimeline.View view;
		/** T-046: the Damage rows the panel kept for {@link #view}; null to add up the view's attacks here. */
		List<WeaponRow> weaponRows;
		/** The chart's series are sent from this index; 0 starts the panel's copy over (spec 5.1). */
		int chartFrom;
		/** The "Charts" setting: off, no damage chart or tick strip. */
		boolean charts = true;
		/** The trip's start (T-034). */
		long tripSince;
		/** The trip's rooms, newest first; the running one first. */
		List<PanelSnapshot.TripRow> tripRows = Collections.emptyList();
		/** The trip's whole other damage, for its "Other damage" rows. */
		int thrall;
		int dot;
		/** T-063: {@link #dot} by kind. */
		Map<String, Integer> dotByKind = Collections.emptyMap();
		int reflect;
		/**
		 * T-044: HP taken by attack ({@code melee}, {@code melee off prayer}, {@code axe}, ...) over the scope's ended
		 * fights at bosses with attack rules; empty for none.
		 */
		Map<String, Integer> takenByAttack = Collections.emptyMap();
		/** T-044: the attack names the boss rules call avoidable, all or in part ({@code melee off prayer}). */
		Set<String> avoidableAttacks = Collections.emptySet();
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
		in.missedHitsTaken = room.getMissedHitsTaken();
		in.inRaid = room.getRaid() != null;
		in.raidType = room.getRaid();
		in.takenByAttack = room.getDamageTakenByAttack();
		in.drain = drain(room);
		fillOther(in, fights);
		in.targets = targets(fights);
	}

	/**
	 * T-052: a room's drains, or null where nothing was tracked: no record (fights saved before T-049), an immune
	 * target, or no special thrown at it by anyone.
	 */
	static Drain drain(Encounter room)
	{
		DrainRecord first = null;
		for (Fight f : room.getFights())
		{
			if (f.getDrainRecord() != null)
			{
				first = f.getDrainRecord();
				break;
			}
		}
		if (first == null || first.immune)
		{
			return null;
		}
		final Drain d = new Drain();
		d.specs = room.getOwnDrainSpecs();
		d.partyDrains = Math.max(0, room.getPartyDrains());
		if (d.ownThrown() <= 0 && d.partyDrains <= 0)
		{
			return null;
		}
		d.defenceStart = room.getDefenceStart();
		d.defenceEnd = room.getDefenceEnd();
		d.defenceBase = first.defenceBase;
		d.defenceFloor = first.defenceFloor;
		d.magicStart = room.getMagicStart();
		d.magicEnd = room.getMagicEnd();
		d.valueDamage = room.getDrainValueDamage();
		d.valueSeconds = room.luckSeconds(d.valueDamage);
		d.specLuckDamage = room.getSpecLuckDamage();
		d.specLuckSeconds = room.luckSeconds(d.specLuckDamage);
		return d;
	}

	/** T-052: a target's tracked stats for the live line, or null until a drain has landed on it. */
	static LiveDefence liveDefence(DrainTracker.Tracked t)
	{
		if (t == null || t.target.immune || !t.drained())
		{
			return null;
		}
		final LiveDefence l = new LiveDefence();
		l.defence = t.state.defence;
		l.defenceBase = t.target.defence;
		l.defenceFloor = t.target.defenceFloor;
		l.magic = t.state.magic;
		l.magicBase = t.target.magic;
		return l;
	}

	/** The whole raid's scope and its rooms. */
	static void fillRaid(Input in, Raid raid)
	{
		in.scope = LedgerRows.of(raid);
		in.name = raid.getName();
		in.raidType = raid.getType();
		in.roomCount = raid.getEncounters().size();
		in.inRaid = true;
		in.hitsTaken = raid.getHitsTaken();
		in.missedHitsTaken = raid.getMissedHitsTaken();
		in.takenByAttack = raid.getDamageTakenByAttack();
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
			r.ended = allEnded(e.getFights());
			rooms.add(r);
		}
		in.startMillis = raid.getStartMillis();
		in.rooms = rooms;
		fillOther(in, fights);
	}

	private static boolean allEnded(List<Fight> fights)
	{
		for (Fight f : fights)
		{
			if (!f.isEnded())
			{
				return false;
			}
		}
		return !fights.isEmpty();
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
		final Map<String, Integer> dotByKind = new LinkedHashMap<>();
		for (Fight f : fights)
		{
			if (f.isScored())
			{
				thrall += Math.max(0, f.getThrallDamage());
				dot += Math.max(0, f.getDotDamage());
				DotKinds.addAll(dotByKind, f.getDotDamageByKind());
				reflect += Math.max(0, f.getReflectDamage());
			}
		}
		in.scoredThrall = thrall;
		in.scoredDot = dot;
		in.scoredDotByKind = dotByKind;
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
	 * The kills are chosen by {@code LedgerHistory}'s own filter
	 * ({@link LedgerHistory#counts}), so the two can't drift apart (T-034).
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
			if (!like.matches(room) || !LedgerHistory.counts(room, exclude))
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
		if (in.version != null || in.maxHp > 0)
		{
			sub.add(in.version != null ? in.version : hp(in.maxHp));
		}
		sub.add("no attacks yet");
		b.header = new PanelSnapshot.Header(Text.title(in.name, SidePanelStyle.VALUE), Text.NONE,
			Text.small(String.join(DOT, sub), SidePanelStyle.SECONDARY),
			new Text(LedgerRows.clock(0), SidePanelStyle.UNSCORED, SidePanelStyle.Type.CLOCK),
			SidePanelBossIcons.sprite(in.name, in.raidType));
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
		final boolean avoidable = avoidableShown(n, in);
		if (after && !warning)
		{
			if (!Double.isNaN(n.percentile))
			{
				b.curve = curve(n, s, luckColour);
				final String specs = specsShareOfLuck(in.drain);
				if (specs != null)
				{
					b.curve = new PanelSnapshot.Curve(b.curve.z, b.curve.headerValue, b.curve.caption + ". " + specs);
				}
			}
			else if (!Double.isNaN(n.luck))
			{
				b.noCurve = NO_CURVE_TEXT;
				b.noCurveHint = Text.title(LedgerRows.fasterOrSlower(n.luck), SidePanelStyle.of(luckColour));
			}
		}
		b.execution = execution(n, avoidable);
		if (in.later != null)
		{
			// A kill from before this session: no per-attack data, so one card says why (spec 3.2).
			b.later = in.later;
		}
		else
		{
			b.damage = damage(n, in, warning);
			if (in.charts)
			{
				b.chart = chart(in, s, luckColour, warning);
			}
		}
		if (after)
		{
			b.details = details(s, avoidable);
			if (!raid && in.drain != null)
			{
				b.defence = defence(in.drain);
			}
		}
		b.taken = taken(s, in, avoidable);
	}

	private static PanelSnapshot.Header header(Input in, LedgerRows.Scope s)
	{
		final List<String> sub = new ArrayList<>();
		// T-072: where "solo" or "group" is, for the short form that leaves it out when the time doesn't fit beside it.
		int soloAt = -1;
		Text suffix = Text.NONE;
		if (in.kind == Kind.RAID)
		{
			sub.add(in.roomCount + (in.roomCount == 1 ? " room" : " rooms"));
			if (in.startMillis > 0)
			{
				sub.add(time(in.startMillis, in.zone));
			}
		}
		else if (in.saved)
		{
			suffix = in.killed ? Text.title(" (dead)", SidePanelStyle.DEAD) : Text.title(" (ended)", SidePanelStyle.SECONDARY);
			if (in.endMillis > 0)
			{
				sub.add(date(in.endMillis, in.now, in.zone));
			}
			sub.add("saved kill");
		}
		else
		{
			// The version's name where the boss rules give one (spec 3.1), else its full HP.
			if (in.version != null || in.maxHp > 0)
			{
				sub.add(in.version != null ? in.version : hp(in.maxHp));
			}
			if (in.solo != null)
			{
				sub.add(in.solo ? "solo" : "group");
				soloAt = sub.size() - 1;
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
		List<String> subShort = null;
		if (soloAt >= 0 && sub.size() > 2)
		{
			final List<String> shorter = new ArrayList<>(sub);
			shorter.remove(soloAt);
			subShort = Collections.singletonList(String.join(DOT, shorter));
		}
		return new PanelSnapshot.Header(Text.title(in.name, SidePanelStyle.VALUE), suffix,
			Text.small(String.join(DOT, sub), SidePanelStyle.SECONDARY),
			new Text(LedgerRows.clock(s.combatTicks()), SidePanelStyle.VALUE, SidePanelStyle.Type.CLOCK),
			SidePanelBossIcons.sprite(in.name, in.raidType),
			in.ended || in.kind == Kind.RAID ? Text.NONE : liveDefenceText(in.liveDefence), subShort);
	}

	/**
	 * T-052: {@code defence 145 / 215}, {@code defence 129 (floor)} at its floor, and {@code · magic 80 / 100} once
	 * Magic was drained. The player's and party's own specials' effect, as RuneLite's Special Attack Counter shows it:
	 * nothing about the boss's attacks.
	 */
	static Text liveDefenceText(LiveDefence l)
	{
		if (l == null)
		{
			return Text.NONE;
		}
		final StringBuilder s = new StringBuilder("defence ").append(l.defence);
		s.append(l.defence <= l.defenceFloor && l.defenceFloor < l.defenceBase ? " (floor)" : " / " + l.defenceBase);
		if (l.magic < l.magicBase)
		{
			s.append(DOT).append("magic ").append(l.magic).append(" / ").append(l.magicBase);
		}
		return Text.small(s.toString(), SidePanelStyle.SECONDARY);
	}

	/** Luck's colour role: by the percentile when it's known (T-029), else by the seconds, as the overlay goes. */
	static LedgerRows.Colour luckColour(LedgerRows.Numbers n)
	{
		return Double.isNaN(n.percentile) ? LedgerRows.luckColour(n.luck, n.clock) : LedgerRows.percentileBand(n.percentile);
	}

	// ---- the Trip tab (spec 3.3) ----

	static final String NO_TRIP_FIGHTS = "No fights this trip yet.";

	static PanelSnapshot.TripTab trip(Input in)
	{
		final LedgerRows.Scope s = in.scope;
		final int ticks = s == null ? 0 : s.combatTicks();
		final List<String> sub = new ArrayList<>(2);
		if (in.tripSince > 0)
		{
			sub.add("since " + time(in.tripSince, in.zone));
		}
		// T-072: where "since 2:52 pm · fighting time" doesn't fit, "since 2:52 pm · in fights", else "since 2:52 pm".
		final List<String> subShort = sub.isEmpty() ? null : Arrays.asList(sub.get(0) + DOT + "in fights", sub.get(0));
		sub.add("fighting time");
		final PanelSnapshot.Header header = new PanelSnapshot.Header(Text.title("Trip", SidePanelStyle.VALUE), Text.NONE,
			Text.small(String.join(DOT, sub), SidePanelStyle.SECONDARY),
			new Text(LedgerRows.clock(ticks), ticks > 0 ? SidePanelStyle.VALUE : SidePanelStyle.UNSCORED,
				SidePanelStyle.Type.CLOCK), SidePanelBossIcons.NONE, Text.NONE, subShort);
		List<Tile> tiles = null;
		PanelSnapshot.Parts execution = null;
		PanelSnapshot.Rows taken = null;
		if (s != null && s.attacksMade() > 0)
		{
			final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
			tiles = tiles(n, s, in, luckColour(n), false);
			final boolean avoidable = avoidableShown(n, in);
			execution = execution(n, avoidable);
			taken = taken(s, in, avoidable);
		}
		int kills = 0;
		int ended = 0;
		for (PanelSnapshot.TripRow r : in.tripRows)
		{
			if (r.ended)
			{
				ended++;
			}
			else
			{
				kills++;
			}
		}
		final String hint = in.tripRows.isEmpty() ? "" : kills + (kills == 1 ? " kill" : " kills")
			+ (ended == 0 ? "" : DOT + ended + " ended");
		final List<Row> other = new ArrayList<>(3);
		if (in.thrall > 0)
		{
			other.add(row("thrall dmg", LedgerRows.number(in.thrall)));
		}
		// T-063: a line a kind, then "DoT dmg" for what has no kind (fights from before T-062).
		dotRows(other, in.dotByKind, in.dot, " dmg");
		if (in.reflect > 0)
		{
			other.add(row("recoil/veng dmg", LedgerRows.number(in.reflect)));
		}
		return new PanelSnapshot.TripTab(header, tiles, execution, Text.small(hint, SidePanelStyle.SECONDARY), in.tripRows,
			taken, other.isEmpty() ? null : new PanelSnapshot.Rows(null, other), tripDrains(in.drain));
	}

	/** A trip row from a rebuilt or running room: texts only. The running one is never "(ended)". */
	static PanelSnapshot.TripRow tripRow(SidePanelHistory.KillRow r)
	{
		return new PanelSnapshot.TripRow(r.name, r.ended, r.clock, r.chip, r.chipFill,
			r.room == null ? SidePanelBossIcons.NONE : SidePanelBossIcons.sprite(r.room.getName(), r.room.getRaid()));
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
				Text.small("no percentile", SidePanelStyle.MUTED), edge));
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
			final String shortOther = otherDamageShort(in);
			tiles.add(new Tile(Text.small("Damage/expected", SidePanelStyle.SECONDARY), Text.title(LedgerRows.number(n.damage)
				+ "/" + LedgerRows.number(Math.round(n.expected)), luck), Text.small(other, SidePanelStyle.SECONDARY),
				shortOther.equals(other) ? null : shortOther, edge));
		}

		tiles.add(tile("DPS", Text.title(Double.isNaN(n.dps) ? LedgerRows.UNKNOWN : LedgerRows.oneDecimal(n.dps),
			SidePanelStyle.VALUE), Text.small(in.unscored && in.kind == Kind.ROOM ? "on the " + in.scoredName
			: "weapon only", SidePanelStyle.SECONDARY), SidePanelStyle.TRACK));

		final boolean avoidable = avoidableShown(n, in);
		final double execution = shownExecution(n, in);
		final double lost = lostSeconds(n, avoidable);
		final boolean costing = anyCosting(n, avoidable);
		final Text lostText = LedgerRows.roundHalfAway(lost) >= 1
			? Text.small(SidePanelStyle.seconds(lost) + " lost", costing ? SidePanelStyle.COST : SidePanelStyle.SECONDARY)
			: Text.small(Double.isNaN(execution) ? "" : "no time lost", SidePanelStyle.MUTED);
		tiles.add(tile("Execution", Text.title(Double.isNaN(execution) ? LedgerRows.UNKNOWN
			: LedgerRows.partPercent(execution), SidePanelStyle.VALUE), lostText, SidePanelStyle.TRACK));
		return tiles;
	}

	private static Tile tile(String label, Text value, Text sub, Color edge)
	{
		return new Tile(Text.small(label, SidePanelStyle.SECONDARY), value, sub, edge);
	}

	/**
	 * T-063: the tile's damage over time, by the kind it was: {@code 30 venom}, {@code 8 burn}; {@code 40 DoT} for an
	 * old kill's, with no kind, or where there were several kinds (their split is on the Damage card).
	 */
	static String dotTile(Map<String, Integer> byKind, int total)
	{
		final Map<String, Integer> lines = DotKinds.split(byKind, total, DotKinds.REMAINDER);
		if (lines.size() == 1)
		{
			final Map.Entry<String, Integer> only = lines.entrySet().iterator().next();
			return LedgerRows.number(only.getValue()) + " " + only.getKey();
		}
		return LedgerRows.number(total) + " " + DotKinds.REMAINDER;
	}

	/** T-063: a row a kind with damage, in T-062's order, then the remainder as {@code DoT}: {@code venom dmg}. */
	static void dotRows(List<Row> rows, Map<String, Integer> byKind, int total, String suffix)
	{
		for (Map.Entry<String, Integer> e : DotKinds.split(byKind, total, DotKinds.REMAINDER).entrySet())
		{
			rows.add(row(e.getKey() + suffix, LedgerRows.number(e.getValue())));
		}
	}

	/** T-063: a kind's Damage row: {@code Burn}, {@code Venom}; the part with no kind is {@code Damage over time}. */
	static String dotSourceName(String kind)
	{
		if (DotKinds.REMAINDER.equals(kind))
		{
			return "Damage over time";
		}
		return kind.isEmpty() ? kind : Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
	}

	/**
	 * T-063: {@link #otherDamage} with its damage over time as {@code N DoT}, for a tile too narrow for the kind's
	 * name ({@code +80 thrall, 30 venom} doesn't fit at 1× in the RuneScape font).
	 */
	static String otherDamageShort(Input in)
	{
		final Input shorter = new Input();
		shorter.scoredThrall = in.scoredThrall;
		shorter.scoredReflect = in.scoredReflect;
		shorter.scoredDot = in.scoredDot;
		// No kinds: the whole as the remainder, DoT.
		return otherDamage(shorter);
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
			parts.add(dotTile(in.scoredDotByKind, in.scoredDot));
		}
		if (in.scoredReflect > 0)
		{
			parts.add(LedgerRows.number(in.scoredReflect) + " recoil");
		}
		return parts.isEmpty() ? "" : "+" + String.join(", ", parts);
	}

	/**
	 * T-044 (from #50): whether avoidable eating shows, as the overlay decides it:
	 * once the scope has ended, and only where its fights had attack rules. While
	 * it runs nothing about the boss's attacks shows (Jagex's rule).
	 */
	static boolean avoidableShown(LedgerRows.Numbers n, Input in)
	{
		return in.ended && !Double.isNaN(n.avoidableEatingCost);
	}

	/** Execution as shown: with avoidable eating once the scope has ended (T-043), without it while it runs. */
	static double shownExecution(LedgerRows.Numbers n, Input in)
	{
		return shownExecution(n, avoidableShown(n, in));
	}

	static double shownExecution(LedgerRows.Numbers n, boolean avoidable)
	{
		return avoidable ? n.execution : n.executionLive;
	}

	/**
	 * Every part's seconds together: uptime, avoidable eating once it shows
	 * (T-044), then the setup (boost, prayer, gear, or one setup figure). Not the
	 * rest of the eating (T-038): it isn't one of Execution's parts.
	 */
	static double lostSeconds(LedgerRows.Numbers n, boolean avoidable)
	{
		double lost = n.uptimeCost;
		if (avoidable)
		{
			lost += avoidableEating(n);
		}
		if (!Double.isNaN(n.setupCost))
		{
			lost += n.setupCost;
		}
		return lost;
	}

	/** {@code Ca}: the eating the avoidable hits cost, never more than all the eating; 0 when unknown. */
	static double avoidableEating(LedgerRows.Numbers n)
	{
		if (Double.isNaN(n.avoidableEatingCost))
		{
			return 0;
		}
		return Math.max(0, Math.min(n.avoidableEatingCost, Double.isNaN(n.eatingCost) ? 0 : n.eatingCost));
	}

	private static boolean anyCosting(LedgerRows.Numbers n, boolean avoidable)
	{
		if (avoidable && LedgerRows.isCosting(avoidableEating(n), n.clock))
		{
			return true;
		}
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

	/** The Execution card without avoidable eating: a running scope's, or one with no attack rules. */
	static PanelSnapshot.Parts execution(LedgerRows.Numbers n)
	{
		return execution(n, false);
	}

	/** The Execution card; with {@code avoidable}, T-044's fifth bar after uptime and the % that includes it. */
	static PanelSnapshot.Parts execution(LedgerRows.Numbers n, boolean avoidable)
	{
		final List<Part> parts = new ArrayList<>(5);
		parts.add(part("uptime", n.uptime, n.uptimeCost, n));
		if (avoidable)
		{
			parts.add(part("avoidable eating", n.avoidableEatingShare, avoidableEating(n), n));
		}
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
		final double execution = shownExecution(n, avoidable);
		return new PanelSnapshot.Parts(Text.title(Double.isNaN(execution) ? LedgerRows.UNKNOWN
			: LedgerRows.partPercent(execution), SidePanelStyle.VALUE), parts);
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
		// Eating is its own stretch after uptime (T-038): B = T - U + L - (Cu + Ce + Cb + Cp + Cg). Neutral, not a cost:
		// the plugin can't tell why you ate. After a ruled fight (T-044) its avoidable part Ca is a cost of its own and
		// the rest stays neutral; best play is the same: B = T - U + L - (Cu + Ca + (Ce - Ca) + Cb + Cp + Cg).
		final double e = Double.isNaN(n.eatingCost) ? 0 : n.eatingCost;
		// T-081: the time a boss's mechanic forced (T-074) is a stretch of its own after eating, in a lighter neutral
		// grey: not a cost, and not best play either, which is the time spent attacking with no losses at average luck.
		// B = T - U + L - (Cu + Ce + M + Cb + Cp + Cg). Before T-081 it sat inside best play's stretch.
		final double m = Double.isNaN(n.mechanicsCost) ? 0 : n.mechanicsCost;
		final double ca = avoidableShown(n, in) ? avoidableEating(n) : 0;
		final boolean caCosting = LedgerRows.isCosting(ca, n.clock);
		double costSum = e + m;
		for (double c : costs)
		{
			costSum += c;
		}
		final double best = t - u + l - costSum;
		final boolean bestKnown = luckKnown && best >= 0;
		final boolean averageBand = luckColour == LedgerRows.Colour.NEUTRAL;

		final double luckSaved = luckKnown && l > 0 ? l : 0;
		// When luck saved time, only best play got shorter: the losses happened inside your real time, so they're drawn
		// before the tick and best play's last L seconds go past it, dimmed (Eric, 2026-10-27, after T-042). Up to the
		// tick: (B - L) + Cu + Ce + Cb + Cp + Cg + U = T.
		final double bestBeforeTick = Math.max(0, best - luckSaved);
		final List<Segment> segments = new ArrayList<>();
		segments.add(new Segment(bestBeforeTick, Fill.KEPT));
		for (int i = 0; i < costs.length; i++)
		{
			segments.add(new Segment(costs[i], LedgerRows.isCosting(costs[i], n.clock) ? Fill.COST : Fill.MINOR_COST));
			if (i == 0)
			{
				if (ca > 0)
				{
					segments.add(new Segment(ca, caCosting ? Fill.COST : Fill.MINOR_COST));
				}
				segments.add(new Segment(e - ca, Fill.EATING));
				if (m > 0)
				{
					// Only where a boss's mechanic forced some: the bar of every other fight is as it was.
					segments.add(new Segment(m, Fill.MECHANICS));
				}
			}
		}
		segments.add(new Segment(Math.max(0, -l), averageBand ? Fill.LUCK_AVERAGE : Fill.LUCK_SLOWER));
		segments.add(new Segment(u, Fill.UNSCORED));
		if (luckSaved > 0)
		{
			segments.add(new Segment(Math.max(0, best) - bestBeforeTick, Fill.KEPT));
		}
		final double total = t + Math.max(0, l);
		// T-042: the bar marks your real time with a tick; when luck saved time it runs on to the time at average luck,
		// labelled. Not when the two would read as the same clock (luck saved under a second).
		final String clockLabel = LedgerRows.duration(t);
		final String endLabel = luckSaved > 0 && !LedgerRows.duration(total).equals(clockLabel)
			? LedgerRows.duration(total) + AT_AVERAGE_LUCK : null;

		final List<BudgetRow> rows = new ArrayList<>();
		final String bestLabel = in.unscored && in.kind == Kind.ROOM ? "best play on the " + in.scoredName : "best play";
		rows.add(new BudgetRow(Fill.KEPT, Text.small(bestLabel, SidePanelStyle.BODY),
			in.unscored ? null : Text.small("no losses, average luck", SidePanelStyle.MUTED),
			Text.small(bestKnown ? SidePanelStyle.roundedDuration(best) : LedgerRows.UNKNOWN, SidePanelStyle.VALUE)));
		for (int i = 0; i < costs.length; i++)
		{
			if (LedgerRows.roundHalfAway(costs[i]) >= 1)
			{
				final boolean costing = LedgerRows.isCosting(costs[i], n.clock);
				rows.add(new BudgetRow(costing ? Fill.COST : Fill.MINOR_COST, Text.small(labels[i], SidePanelStyle.BODY),
					null, Text.small("+" + SidePanelStyle.seconds(costs[i]), costing ? SidePanelStyle.COST
						: SidePanelStyle.BODY)));
			}
			// After uptime's row, whether or not that one shows: a fight whose only lost ticks were eating has no uptime row.
			if (i == 0 && LedgerRows.roundHalfAway(ca) >= 1)
			{
				rows.add(new BudgetRow(caCosting ? Fill.COST : Fill.MINOR_COST, Text.small("avoidable eating",
					SidePanelStyle.BODY), null, Text.small("+" + SidePanelStyle.seconds(ca), caCosting ? SidePanelStyle.COST
					: SidePanelStyle.BODY)));
			}
			if (i == 0 && LedgerRows.roundHalfAway(e - ca) >= 1)
			{
				rows.add(new BudgetRow(Fill.EATING, Text.small("eating", SidePanelStyle.BODY), null,
					Text.small("+" + SidePanelStyle.seconds(e - ca), SidePanelStyle.BODY)));
			}
			if (i == 0 && LedgerRows.roundHalfAway(m) >= 1)
			{
				rows.add(new BudgetRow(Fill.MECHANICS, Text.small(MECHANICS, SidePanelStyle.BODY), null,
					Text.small("+" + SidePanelStyle.seconds(m), SidePanelStyle.BODY)));
			}
		}
		// Luck always shows, in the Luck tile's words (Orchestrator, T-034): "luck average (2 s faster)", "luck (14 s
		// slower)", so a saving doesn't read like a cost.
		// T-042: time luck saved is the striped stretch past your clock, in the middle half too (grey stripes there), and
		// the row says it's the part past your time.
		final Fill luckSwatch = luckSaved > 0 ? Fill.LUCK_FASTER : averageBand ? Fill.LUCK_AVERAGE : Fill.LUCK_SLOWER;
		final boolean middleHalf = luckKnown && averageBand && !Double.isNaN(n.percentile);
		rows.add(new BudgetRow(luckSwatch, Text.small(middleHalf ? "luck average" : "luck", SidePanelStyle.BODY),
			endLabel == null ? null : Text.small(PAST + clockLabel, SidePanelStyle.MUTED),
			Text.small(luckKnown ? LedgerRows.fasterOrSlower(l) : LedgerRows.UNKNOWN,
				luckKnown ? SidePanelStyle.of(luckColour) : SidePanelStyle.VALUE)));
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
				// Never coloured (Orchestrator, T-034): the raw clock carries luck, which the Luck tile already shows.
				Text.small(against(t, a.median), SidePanelStyle.BODY)));
			if (in.luckRemoved != null)
			{
				final LedgerHistory.Spread r = in.luckRemoved;
				average.add(new Row(Text.small("luck removed " + LedgerRows.duration(n.expectedDuration), SidePanelStyle.BODY),
					Text.small(against(n.expectedDuration, r.median) + (LedgerRows.roundHalfAway(n.expectedDuration - r.median)
						== 0 ? " with " : " than ") + LedgerRows.duration(r.median), SidePanelStyle.of(usual(n.expectedDuration,
						r)))));
			}
		}
		return new PanelSnapshot.TimeBudget(segments, total, t, luckSaved,
			averageBand ? SidePanelStyle.AVERAGE_STRIPE : SidePanelStyle.BETTER, clockLabel, endLabel, marker, rows, average,
			note);
	}

	/** T-081: the time budget's and the strip's word for the ticks a boss's mechanic forced (T-074). */
	static final String MECHANICS = "mechanics";

	/** {@code 23 s slower}, {@code 5 s faster}, {@code even}: this kill's time against a usual one. */
	static String against(double seconds, double usual)
	{
		return LedgerRows.fasterOrSlower(usual - seconds);
	}

	/**
	 * Cost above your upper quartile, Better below your lower quartile, neutral
	 * inside (spec section 2). Only luck removed is coloured by it (T-034).
	 */
	static LedgerRows.Colour usual(double seconds, LedgerHistory.Spread spread)
	{
		if (seconds > spread.upperQuartile)
		{
			return LedgerRows.Colour.COST;
		}
		return seconds < spread.lowerQuartile ? LedgerRows.Colour.BETTER : LedgerRows.Colour.NEUTRAL;
	}

	// ---- the Defence card (T-052) ----

	/**
	 * The Defence card after a room with tracked drains (T-052), in short labelled rows since T-076: Eric found T-052's
	 * lowercase rows and two sentences hard to read. The header's hint is the drop ({@code 250 → 174}); no row repeats
	 * it, and the floor is a row only where the boss has one.
	 */
	static PanelSnapshot.Defence defence(Drain d)
	{
		final List<Row> rows = new ArrayList<>();
		if (hasFloor(d))
		{
			final boolean atFloor = d.defenceEnd >= 0 && d.defenceEnd <= d.defenceFloor;
			rows.add(new Row(Text.small("Floor", SidePanelStyle.BODY),
				atFloor ? Text.small(" reached", SidePanelStyle.SECONDARY) : Text.NONE,
				Text.small(String.valueOf(d.defenceFloor), SidePanelStyle.VALUE)));
		}
		if (d.magicStart >= 0 && d.magicEnd >= 0 && d.magicEnd != d.magicStart)
		{
			rows.add(row("Magic", path(d.magicStart, d.magicEnd)));
		}
		for (Map.Entry<String, int[]> e : d.specs.entrySet())
		{
			rows.add(row(weaponName(e.getKey()), e.getValue()[1] + " of " + e.getValue()[0] + " landed"));
		}
		if (d.partyDrains > 0)
		{
			rows.add(row("Party drains", String.valueOf(d.partyDrains)));
		}
		return new PanelSnapshot.Defence(Text.small(path(d.defenceStart, d.defenceEnd), SidePanelStyle.SECONDARY), rows,
			drainWorth(d), specLuck(d), drop(d));
	}

	/** Whether the boss has a Defence floor above 0 and below its base (T-049's rules). */
	private static boolean hasFloor(Drain d)
	{
		return d.defenceFloor > 0 && (d.defenceBase < 0 || d.defenceFloor < d.defenceBase);
	}

	/** T-076: the bar's drop, {@code Drained 76 (30%)}; null when a level isn't known. */
	static PanelSnapshot.Drop drop(Drain d)
	{
		if (d.defenceStart < 0 || d.defenceEnd < 0)
		{
			return null;
		}
		final int base = Math.max(d.defenceBase, d.defenceStart);
		if (base <= 0)
		{
			return null;
		}
		final int drained = Math.max(0, d.defenceStart - d.defenceEnd);
		final String share = d.defenceStart <= 0 ? "" : " (" + Math.round(100.0 * drained / d.defenceStart) + "%)";
		return new PanelSnapshot.Drop(d.defenceStart, d.defenceEnd, base, hasFloor(d) ? d.defenceFloor : 0,
			Text.small("Drained", SidePanelStyle.BODY), Text.small(drained + share, SidePanelStyle.VALUE));
	}

	private static String path(int start, int end)
	{
		return start < 0 || end < 0 ? "unknown" : start == end ? String.valueOf(end) : start + " → " + end;
	}

	/** A special's name as the game's items are written: {@code Dragon warhammer}, {@code Dinh's bulwark}. */
	static String weaponName(String weapon)
	{
		String name;
		try
		{
			name = net.runelite.client.plugins.specialcounter.SpecialWeapon.valueOf(weapon).getName();
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			name = weapon == null ? "special" : weapon.replace('_', ' ');
		}
		final String lower = name.toLowerCase(Locale.ENGLISH);
		return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/** T-050's drain value: {@code Damage added  +182 · 14 s faster}. Null when unknown. */
	static PanelSnapshot.Figure drainWorth(Drain d)
	{
		if (Double.isNaN(d.valueDamage))
		{
			return null;
		}
		final long dmg = LedgerRows.roundHalfAway(d.valueDamage);
		final String secs = Double.isNaN(d.valueSeconds) || LedgerRows.roundHalfAway(d.valueSeconds) == 0 ? ""
			: LedgerRows.fasterOrSlower(d.valueSeconds);
		return new PanelSnapshot.Figure(WORTH, signed(dmg), secs, SidePanelStyle.VALUE);
	}

	static final String WORTH = "Damage added";
	static final String SPEC_LUCK = "Specials' luck";

	private static String signed(long n)
	{
		return (n > 0 ? "+" : n < 0 ? "-" : "") + LedgerRows.number(Math.abs(n));
	}

	/**
	 * T-050's spec luck: {@code Specials' luck  +12 · 1 s faster}, in Better or Cost. It is the difference between the
	 * chart's expected line as thrown and the tile's averaged figure (T-076). Null without own draining specials, or
	 * when unknown.
	 */
	static PanelSnapshot.Figure specLuck(Drain d)
	{
		if (d == null || d.ownThrown() <= 0 || Double.isNaN(d.specLuckSeconds))
		{
			return null;
		}
		final long r = LedgerRows.roundHalfAway(d.specLuckSeconds);
		final Color c = r > 0 ? SidePanelStyle.BETTER : r < 0 ? SidePanelStyle.COST : SidePanelStyle.BODY;
		final long dmg = Double.isNaN(d.specLuckDamage) ? 0 : LedgerRows.roundHalfAway(d.specLuckDamage);
		return new PanelSnapshot.Figure(SPEC_LUCK, signed(dmg), r == 0 ? "about even" : LedgerRows.fasterOrSlower(
			d.specLuckSeconds), c);
	}

	private static String specLuckPhrase(Drain d)
	{
		if (d == null || d.ownThrown() <= 0 || Double.isNaN(d.specLuckSeconds))
		{
			return null;
		}
		final long r = LedgerRows.roundHalfAway(d.specLuckSeconds);
		return r == 0 ? "landed about as well as average"
			: (r > 0 ? "landed better than average: " : "landed worse than average: ")
			+ LedgerRows.fasterOrSlower(d.specLuckSeconds);
	}

	/** Luck's caption, second sentence: {@code Your specials' share: 5 s faster}. Null without own specials. */
	static String specsShareOfLuck(Drain d)
	{
		if (specLuckPhrase(d) == null)
		{
			return null;
		}
		final long r = LedgerRows.roundHalfAway(d.specLuckSeconds);
		return "Your specials' share: " + (r == 0 ? "about even" : LedgerRows.fasterOrSlower(d.specLuckSeconds));
	}

	/** T-052: the trip's drains, or null where none were worth anything. */
	static PanelSnapshot.Rows tripDrains(Drain d)
	{
		if (d == null || Double.isNaN(d.valueDamage))
		{
			return null;
		}
		final long dmg = LedgerRows.roundHalfAway(d.valueDamage);
		final boolean luck = !Double.isNaN(d.specLuckSeconds) && LedgerRows.roundHalfAway(d.specLuckSeconds) != 0;
		if (dmg == 0 && !luck)
		{
			return null;
		}
		final List<Row> rows = new ArrayList<>(2);
		final String secs = Double.isNaN(d.valueSeconds) || LedgerRows.roundHalfAway(d.valueSeconds) == 0 ? ""
			: DOT + LedgerRows.fasterOrSlower(d.valueSeconds);
		rows.add(row("expected dmg added", (dmg > 0 ? "+" : "") + LedgerRows.number(dmg) + secs));
		if (luck)
		{
			final long r = LedgerRows.roundHalfAway(d.specLuckSeconds);
			rows.add(new Row(Text.small("your specials' luck", SidePanelStyle.BODY),
				Text.small(LedgerRows.fasterOrSlower(d.specLuckSeconds), r > 0 ? SidePanelStyle.BETTER : SidePanelStyle.COST)));
		}
		return new PanelSnapshot.Rows(null, rows);
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
				Text.small("execution " + (Double.isNaN(roomExecution(r)) ? LedgerRows.UNKNOWN
					: LedgerRows.partPercent(roomExecution(r))), SidePanelStyle.SECONDARY),
				chip(r.numbers, r.warning), chipFill(r.numbers, r.warning)));
		}
		return new PanelSnapshot.ListRows(Text.small("time · luck · execution", SidePanelStyle.SECONDARY), rows);
	}

	/** A raid room's Execution: with avoidable eating once the room has ended (T-044), without it while it runs. */
	private static double roomExecution(Room r)
	{
		return shownExecution(r.numbers, r.ended && !Double.isNaN(r.numbers.avoidableEatingCost));
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

	/** What a weapon row adds up while its attacks are grouped. */
	static final class WeaponRow
	{
		String name;
		boolean special;
		int attacks;
		int damage;
		double expected;
		boolean expectedKnown;
		/**
		 * T-085: the ticks spent on its attacks ({@link SidePanelWeaponRows#ticks}), the attacks that landed, the chances
		 * they were expected to land summed over the attacks that had one, the largest max hit, and the biggest hit.
		 */
		int ticks;
		int landed;
		double landedExpected;
		int landedKnown;
		int maxHit = -1;
		int biggest;
		int itemId = -1;
		Spell spell;
		FightTimeline.Style style = FightTimeline.Style.UNKNOWN;
	}

	/** The Damage section without a stats warning; {@link #damage(LedgerRows.Numbers, Input, boolean)}. */
	static PanelSnapshot.Sources damage(LedgerRows.Numbers n, Input in)
	{
		return damage(n, in, false);
	}

	/**
	 * The Damage section (spec 3.1): a row per attack key from
	 * {@link FightTimeline}'s attacks (T-036), weapons and spells, specials apart,
	 * then thrall, each kind of damage over time (T-063) and recoil/vengeance.
	 * Shares are of all of them, scored fights only, so they add to 100%. Weapon
	 * damage no attack holds (a fight from before the panel loaded, or a splat
	 * waiting for its attack) gets a row of its own, so the rows always add up to
	 * the scope's totals.
	 */
	static PanelSnapshot.Sources damage(LedgerRows.Numbers n, Input in, boolean warning)
	{
		// The panel keeps the rows' sums between snapshots (T-046); a saved kill and the tests add up the whole list.
		final List<WeaponRow> weapons = in.weaponRows != null ? in.weaponRows : weaponRows(in.view);
		int attributed = 0;
		for (WeaponRow w : weapons)
		{
			attributed += w.damage;
		}
		final int weaponDamage = Math.max(0, n.damage);
		final int whole = weaponDamage + in.scoredThrall + in.scoredDot + in.scoredReflect;
		final List<PanelSnapshot.Source> rows = new ArrayList<>(weapons.size() + 4);
		if (!weapons.isEmpty())
		{
			weapons.sort((a, b) -> Integer.compare(b.damage, a.damage));
			for (WeaponRow w : weapons)
			{
				final double share = whole <= 0 ? 0 : (double) Math.max(0, w.damage) / whole;
				final String count = w.attacks + (w.special ? (w.attacks == 1 ? " spec" : " specs")
					: (w.attacks == 1 ? " attack" : " attacks"));
				final String sub = warning || !w.expectedKnown ? count
					: count + DOT + "expected " + LedgerRows.number(Math.round(w.expected));
				final int sprite = SidePanelIcons.spellSprite(w.spell);
				rows.add(weaponSource(w, count, sub, share, warning, sprite));
			}
			// What no attack holds: damage waiting for its attack's booking, or from a fight the timeline didn't record;
			// expectations that arrived before any attack of their fight was booked (a held sample after a target switch).
			final int unmatched = weaponDamage - attributed;
			double heldExpected = 0;
			for (WeaponRow w : weapons)
			{
				heldExpected += w.expected;
			}
			// T-076: the rows hold each attack's expectation as thrown, so what they don't hold is measured against the
			// scope's as thrown, not T-050's averaged figure (the tile's). Their difference is the specials' luck, not an
			// unmatched attack: Eric's Sire recording showed a "0 · 0%" row for it. With nothing in it, no row.
			final double unmatchedExpected = Math.max(0, n.expected + asThrownShift(in.scope)) - heldExpected;
			if (unmatched != 0 || Math.abs(unmatchedExpected) >= 0.5)
			{
				final double share = whole <= 0 ? 0 : (double) Math.max(0, unmatched) / whole;
				rows.add(new PanelSnapshot.Source("Not matched to an attack", warning ? "weapon damage"
					: "weapon damage" + DOT + "expected " + LedgerRows.number(Math.round(unmatchedExpected)),
					LedgerRows.number(unmatched), Math.round(100 * share) + "%", share, SidePanelStyle.UNSCORED));
			}
		}
		source(rows, "Thrall", in.scoredThrall, whole, SidePanelStyle.THRALL, -1, SidePanelIcons.THRALL_SPRITE);
		// T-063: a row a kind of damage over time, each with its share of the whole card.
		for (Map.Entry<String, Integer> e : DotKinds.split(in.scoredDotByKind, in.scoredDot, DotKinds.REMAINDER).entrySet())
		{
			source(rows, dotSourceName(e.getKey()), e.getValue(), whole, SidePanelStyle.DOT, -1, -1);
		}
		source(rows, "Recoil and vengeance", in.scoredReflect, whole, SidePanelStyle.REFLECT, SidePanelIcons.RECOIL_ITEM, -1);
		if (rows.isEmpty())
		{
			return null;
		}
		final String where = in.unscored && in.kind == Kind.ROOM ? " on the " + in.scoredName : " yours";
		return new PanelSnapshot.Sources(Text.small(LedgerRows.number(whole) + where, SidePanelStyle.SECONDARY), rows);
	}

	/**
	 * T-076: T-050's spec luck in damage, E(as thrown) - E(averaged): what the attacks as thrown (the chart, the Damage
	 * rows) expected beyond the tile's averaged figure. 0 when unknown.
	 */
	static double asThrownShift(LedgerRows.Scope s)
	{
		final double d = s == null ? Double.NaN : s.specLuckDamage();
		return Double.isNaN(d) ? 0 : d;
	}

	// ---- T-085: each weapon's DPS, and its details on hover ----

	/**
	 * T-085: a weapon or spell row with its DPS, its damage over the time spent attacking with it
	 * ({@link SidePanelWeaponRows#ticks}: its attacks and the cooldown after each, up to the next attack), beside its
	 * expected DPS, the row's expected damage over the same time. Not for a special: its worth is its burst and its
	 * drain, spent by energy rather than time, so a rate over its few ticks says little. Not under the stats warning's
	 * expected, nor with no ticks (a row read without its view).
	 */
	private static PanelSnapshot.Source weaponSource(WeaponRow w, String count, String sub, double share,
		boolean warning, int sprite)
	{
		final String shareText = Math.round(100 * share) + "%";
		final double seconds = w.ticks * LedgerRows.SECONDS_PER_TICK;
		final String dps = !w.special && w.ticks > 0 ? LedgerRows.oneDecimal(Math.max(0, w.damage) / seconds) : null;
		final String exp = dps != null && w.expectedKnown && !warning ? LedgerRows.oneDecimal(w.expected / seconds) : null;
		final String tooltip = weaponTooltip(w, shareText, dps, exp, warning);
		final int item = sprite >= 0 ? -1 : w.itemId;
		final Color colour = styleColour(w.style, w.special);
		final String damage = LedgerRows.number(w.damage);
		if (dps == null)
		{
			return new PanelSnapshot.Source(w.name, sub, null, damage, shareText, share, colour, item, sprite, tooltip, null,
				null);
		}
		// T-085, Eric's pick (layout A): the DPS on the sub-line. Where the longest doesn't fit, the shorter forms in turn;
		// the card draws every weapon row in the same one (SidePanelText.FormGroup), so the rows don't go ragged.
		final String rate = dps + " DPS";
		final List<String> forms = exp == null
			? Arrays.asList(count + DOT + rate, rate, rate, rate)
			: Arrays.asList(count + DOT + rate + " (expected " + exp + ")", rate + " (expected " + exp + ")",
				rate + " (exp " + exp + ")", rate);
		return new PanelSnapshot.Source(w.name, forms.get(0), forms.subList(1, forms.size()), damage, shareText, share,
			colour, item, sprite, tooltip, dps, exp);
	}

	/**
	 * T-085: a weapon row's details, for its tooltip: attacks and the ones that landed, against the chance they had;
	 * the average and biggest hit, against the max; the expected damage; the time spent attacking and the DPS. HTML,
	 * built in the snapshot.
	 */
	static String weaponTooltip(WeaponRow w, String share, String dps, String exp, boolean warning)
	{
		final StringBuilder s = new StringBuilder("<html><b>").append(escape(w.name)).append("</b>");
		s.append("<br>").append(w.attacks).append(w.special ? (w.attacks == 1 ? " special" : " specials")
			: (w.attacks == 1 ? " attack" : " attacks")).append(", ").append(w.landed).append(" landed");
		if (w.attacks > 0)
		{
			s.append(" (").append(Math.round(100.0 * w.landed / w.attacks)).append("%");
			if (w.landedKnown > 0 && !warning)
			{
				s.append(", expected ").append(Math.round(100.0 * w.landedExpected / w.landedKnown)).append("%");
			}
			s.append(")");
		}
		if (w.landed > 0)
		{
			s.append("<br>average hit ").append(LedgerRows.oneDecimal((double) Math.max(0, w.damage) / w.landed));
		}
		if (w.biggest > 0)
		{
			s.append("<br>biggest hit ").append(w.biggest);
			if (w.maxHit > 0 && !warning)
			{
				s.append(" of max ").append(w.maxHit);
			}
		}
		s.append("<br>damage ").append(LedgerRows.number(w.damage));
		if (w.expectedKnown && !warning)
		{
			s.append(", expected ").append(LedgerRows.number(Math.round(w.expected)));
		}
		s.append(" (").append(share).append(" of your damage)");
		if (dps != null)
		{
			s.append("<br>").append(dps).append(" DPS");
			if (exp != null)
			{
				s.append(" (expected ").append(exp).append(")");
			}
			s.append(" over ").append(LedgerRows.duration(w.ticks * LedgerRows.SECONDS_PER_TICK)).append(" attacking, ")
				.append(w.ticks).append(w.ticks == 1 ? " tick" : " ticks");
		}
		else if (w.special)
		{
			s.append("<br>no DPS: a special is spent by energy, not time");
		}
		return s.append("</html>").toString();
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** The view's attacks grouped by the name shown and whether it was the special, in the order first thrown. */
	static List<WeaponRow> weaponRows(FightTimeline.View view)
	{
		final List<WeaponRow> rows = new ArrayList<>();
		if (view == null)
		{
			return rows;
		}
		final Map<String, WeaponRow> byKey = new LinkedHashMap<>();
		for (int i = 0; i < view.attacks.size(); i++)
		{
			SidePanelWeaponRows.add(byKey, view.attacks.get(i), SidePanelWeaponRows.ticks(view, i));
		}
		rows.addAll(byKey.values());
		return rows;
	}

	/** A style's colour (spec section 2); a special's is its style's mixed 70% toward white. */
	static Color styleColour(FightTimeline.Style style, boolean special)
	{
		final Color base;
		switch (style == null ? FightTimeline.Style.UNKNOWN : style)
		{
			case RANGED:
				base = SidePanelStyle.RANGED;
				break;
			case MAGIC:
				base = SidePanelStyle.MAGIC;
				break;
			case MELEE:
				base = SidePanelStyle.MELEE;
				break;
			default:
				base = SidePanelStyle.SECONDARY;
				break;
		}
		return special ? SidePanelStyle.towardWhite(base, 0.7) : base;
	}

	private static void source(List<PanelSnapshot.Source> rows, String name, int damage, int whole, Color colour,
		int itemId, int spriteId)
	{
		if (damage <= 0)
		{
			return;
		}
		final double share = whole <= 0 ? 0 : (double) damage / whole;
		rows.add(new PanelSnapshot.Source(name, "no luck either way", LedgerRows.number(damage),
			Math.round(100 * share) + "%", share, colour, itemId, spriteId));
	}

	// ---- the damage chart and tick strip (T-036; spec 3.1) ----

	/**
	 * The chart's and strip's data for this snapshot: the view's series from
	 * {@link Input#chartFrom}. Null with no in-memory data. Expected and the
	 * typical range are left out under the stats warning; the range also when
	 * the spread isn't known.
	 */
	static PanelSnapshot.Chart chart(Input in, LedgerRows.Scope s, LedgerRows.Colour luckColour, boolean warning)
	{
		final FightTimeline.View v = in.view;
		if (v == null || v.states.length == 0)
		{
			return null;
		}
		final int length = v.states.length;
		final int from = Math.max(0, Math.min(in.chartFrom, length));
		final byte[] styles = new byte[length - from];
		int lost = 0;
		int eating = 0;
		int mechanics = 0;
		final int[] byStyle = new int[FightTimeline.Style.values().length];
		for (FightTimeline.Attack a : v.attacks)
		{
			final int i = a.tick - v.startTick;
			if (i >= from && i < length)
			{
				styles[i - from] = (byte) (a.style.ordinal() + 1);
			}
			if (!a.special)
			{
				byStyle[a.style.ordinal()]++;
			}
		}
		for (byte state : v.states)
		{
			if (state == FightTimeline.LOST)
			{
				lost++;
			}
			else if (state == FightTimeline.EATING)
			{
				eating++;
			}
			else if (state == FightTimeline.MECHANIC)
			{
				mechanics++;
			}
		}
		int main = FightTimeline.Style.UNKNOWN.ordinal();
		for (int i = 0; i < byStyle.length; i++)
		{
			if (byStyle[i] > byStyle[main])
			{
				main = i;
			}
		}
		final float last = v.damage[length - 1];
		final float lastExpected = v.expected[length - 1];
		final boolean rangeKnown = s.damageVariance() > 0 && v.variance[length - 1] > 0;
		final int ticks = s.combatTicks();
		return new PanelSnapshot.Chart(from, length, Arrays.copyOfRange(v.damage, from, length),
			Arrays.copyOfRange(v.expected, from, length), Arrays.copyOfRange(v.variance, from, length),
			Arrays.copyOfRange(v.states, from, length), styles,
			luckColour == LedgerRows.Colour.NEUTRAL || warning ? SidePanelStyle.VALUE : SidePanelStyle.of(luckColour),
			!warning, !warning && rangeKnown, LedgerRows.clock(ticks), "you " + LedgerRows.number(Math.round(last)),
			expectedLegend(lastExpected, s), lost, eating, mechanics,
			styleColour(FightTimeline.Style.values()[main], false));
	}

	/**
	 * T-076: the chart's expected line is each attack as thrown, the only expectation a single attack has; the tile's is
	 * T-050's, averaged over how the draining specials could have landed. Where they differ by a point or more the
	 * legend says which it is, and the Defence card's specials row is the difference.
	 */
	static String expectedLegend(double lastExpected, LedgerRows.Scope s)
	{
		final String figure = "expected " + LedgerRows.number(Math.round(lastExpected));
		return Math.abs(asThrownShift(s)) >= 0.5 ? figure + AS_THROWN : figure;
	}

	static final String AS_THROWN = " as thrown";

	/** After the fight: the overlay's Summary rows that the tiles and Damage taken don't already show. */
	static PanelSnapshot.Rows details(LedgerRows.Scope s)
	{
		return details(s, false);
	}

	/** With {@code avoidable} (T-044), the eating row is what's left once the avoidable eating is Execution's, as on the overlay. */
	static PanelSnapshot.Rows details(LedgerRows.Scope s, boolean avoidable)
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
		// T-063: a line a kind, then "DoT dmg" for what has no kind, as the overlay has them (T-062).
		dotRows(rows, s.dotDamageByKind(), s.dotDamage(), " dmg");
		// The boss's doing rather than the player's, so after the fight only (own-output guardrail).
		if (s.healed() > 0)
		{
			rows.add(row("boss healed", LedgerRows.number(s.healed())));
		}
		final double eating = s.ticksLostEating() * LedgerRows.SECONDS_PER_TICK
			- (avoidable ? avoidableEating(new LedgerRows.Numbers(s)) : 0);
		if (s.ticksLostEating() > 0 && LedgerRows.roundHalfAway(eating) >= 1)
		{
			rows.add(row("eating", LedgerRows.roundHalfAway(eating) + " s"));
		}
		// T-081: the idle time a boss's mechanic forced (T-074), beside eating and neutral like it, as the overlay's
		// T-080 line. Nothing for a boss with no allowances, or an old kill.
		final long mechanics = LedgerRows.roundHalfAway(Math.max(0, s.ticksMechanics()) * LedgerRows.SECONDS_PER_TICK);
		if (mechanics >= 1)
		{
			rows.add(row("mechanics", mechanics + " s"));
		}
		return rows.isEmpty() ? null : new PanelSnapshot.Rows(null, rows);
	}

	/** Damage taken without T-044's rows by attack: a running scope, or no attack rules. */
	static PanelSnapshot.Rows taken(LedgerRows.Scope s, int hitsTaken)
	{
		final Input in = new Input();
		in.hitsTaken = hitsTaken;
		return taken(s, in, false);
	}

	/**
	 * The Damage taken card. With {@code avoidable} (an ended scope with attack
	 * rules, T-044): first a row per attack, biggest first, each avoidable one
	 * marked in words, then {@code avoidable 141 (54%)}; then the rows from before.
	 */
	static PanelSnapshot.Rows taken(LedgerRows.Scope s, Input in, boolean avoidable)
	{
		// T-063: what the boss's damage over time was (venom, poison, ...) is its doing, so after the fight only, as the
		// overlay has it (T-062). Before T-063 the panel showed burn/poison taken while the fight ran.
		final boolean dotShown = in.ended && s.dotTaken() > 0;
		if (s.damageTaken() <= 0 && !dotShown)
		{
			return null;
		}
		// T-076: the hits that did damage; the 0s (a miss, or a hit the prayer blocked) apart. A kill from before T-076 didn't
		// count its 0s apart, so its hits stay its one number.
		final int missed = Math.max(0, Math.min(in.hitsTaken, in.missedHitsTaken));
		final int hitsTaken = in.hitsTaken - missed;
		final List<Row> rows = new ArrayList<>(12);
		final int part = s.damageTakenAvoidable();
		if (avoidable && part >= 0)
		{
			final List<Map.Entry<String, Integer>> attacks = new ArrayList<>(in.takenByAttack.entrySet());
			// Biggest first; unnamed only when there is some, and last among equals.
			attacks.removeIf(a -> a.getValue() == null || a.getValue() <= 0);
			attacks.sort((a, b) -> b.getValue().equals(a.getValue()) ? Boolean.compare(isUnnamed(a.getKey()),
				isUnnamed(b.getKey())) : Integer.compare(b.getValue(), a.getValue()));
			for (Map.Entry<String, Integer> a : attacks)
			{
				final boolean marked = in.avoidableAttacks.contains(a.getKey());
				rows.add(new Row(Text.small(a.getKey(), SidePanelStyle.BODY),
					marked ? Text.small(" " + AVOIDABLE, SidePanelStyle.COST) : Text.NONE,
					Text.small(LedgerRows.number(a.getValue()), SidePanelStyle.BODY)));
			}
			final int share = s.damageTaken() <= 0 ? 0 : (int) Math.round(100.0 * part / s.damageTaken());
			rows.add(new Row(Text.small(AVOIDABLE, SidePanelStyle.BODY), Text.small(LedgerRows.number(part) + " ("
				+ share + "%)", part > 0 ? SidePanelStyle.COST : SidePanelStyle.BODY)));
		}
		if (!in.ended)
		{
			// T-068 (T-067's finding D-1): while the fight runs, the total only, as the overlay's " damage taken" row. A
			// live count of the boss's hits is close to an attack counter, and "without protection" in the cost colour a
			// prayer nudge; the overlay keeps all three for its Summary (T-019). The trip waits for no fight to run.
			rows.add(new Row(Text.small(AFTER_THE_FIGHT, SidePanelStyle.MUTED), Text.NONE));
			return new PanelSnapshot.Rows(Text.title(LedgerRows.number(s.damageTaken()), SidePanelStyle.VALUE), rows);
		}
		if (hitsTaken > 0)
		{
			rows.add(row("hits", LedgerRows.number(hitsTaken)));
		}
		if (missed > 0)
		{
			rows.add(row(MISSED, LedgerRows.number(missed)));
		}
		if (s.biggestHitTaken() > 0)
		{
			rows.add(row("biggest hit", LedgerRows.number(s.biggestHitTaken())));
		}
		rows.add(new Row(Text.small("without protection", SidePanelStyle.BODY),
			Text.small(LedgerRows.number(s.damageTakenUnprotected()), s.damageTakenUnprotected() > 0
				? SidePanelStyle.COST : SidePanelStyle.BODY)));
		// By kind, as the overlay has them (T-062, T-063): poison taken, venom taken, ..., then DoT taken.
		if (dotShown)
		{
			dotRows(rows, s.dotTakenByKind(), s.dotTaken(), " taken");
		}
		return new PanelSnapshot.Rows(Text.title(LedgerRows.number(s.damageTaken()), SidePanelStyle.VALUE), rows);
	}

	static final String AVOIDABLE = "avoidable";
	/** T-076: the Damage taken card's 0s. */
	static final String MISSED = "missed or blocked";
	/** T-068: the Damage taken card's line while the fight runs. */
	static final String AFTER_THE_FIGHT = "hits and biggest hit after the fight";

	private static boolean isUnnamed(String attack)
	{
		return attack.startsWith(AttackClassifier.UNNAMED);
	}

	/**
	 * T-044: the attack names a version's rules call avoidable, as the classifier
	 * saves them: an {@code always} attack by its name, an {@code offPrayer} one
	 * off prayer ({@code melee off prayer}). Empty for none.
	 */
	static Set<String> avoidableAttacks(BossRules.AttacksOnPlayer rules)
	{
		final Set<String> out = new java.util.LinkedHashSet<>();
		if (rules == null || rules.attacks == null)
		{
			return out;
		}
		for (BossRules.AttackRule r : rules.attacks)
		{
			if (r == null || r.name == null)
			{
				continue;
			}
			if ("always".equals(r.avoidable))
			{
				out.add(r.name);
			}
			else if ("offPrayer".equals(r.avoidable))
			{
				out.add(r.name + AttackClassifier.OFF_PRAYER);
			}
		}
		return out;
	}

	/** {@link #avoidableAttacks(BossRules.AttacksOnPlayer)} over the bosses these fights were at. */
	static Set<String> avoidableAttacks(BossRules rules, Collection<Fight> fights)
	{
		final Set<String> out = new java.util.LinkedHashSet<>();
		if (rules == null || fights == null)
		{
			return out;
		}
		for (Fight f : fights)
		{
			out.addAll(avoidableAttacks(BossRules.attacksFor(rules.forNpc(f.getTargetId()), f.getMaxHp())));
		}
		return out;
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

	/** {@code today 3:31 pm}, else {@code 26 Oct 9:40 pm} (spec 3.2's kill rows; T-072). */
	static String date(long millis, long now, ZoneId zone)
	{
		final java.time.ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(zone);
		final java.time.LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
		return (at.toLocalDate().equals(today) ? "today" : DAY.format(at)) + " " + clockTime(at);
	}

	/**
	 * T-072: a History kill row's date, as {@link #date} but a kill from today is its time alone, {@code 3:31 pm}: the
	 * rows were full at 213 px before am and pm, and a time with no day can only be today.
	 */
	static String rowDate(long millis, long now, ZoneId zone)
	{
		final java.time.ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(zone);
		final java.time.LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
		return at.toLocalDate().equals(today) ? clockTime(at) : DAY.format(at) + " " + clockTime(at);
	}

	/** T-072: {@code 3:31 pm}, {@code 12:05 am}, {@code 12:00 pm}: lower case, no leading zero. */
	static String clockTime(java.time.ZonedDateTime at)
	{
		return TIME.format(at) + (at.getHour() < 12 ? " am" : " pm");
	}

	/**
	 * The boss version's name for a room whose full HP is {@code maxHp}: the
	 * boss rule's variant with that HP, from any of the room's targets ("Post-quest"
	 * for Vardorvis at 700). Null where the rules don't say.
	 */
	static String versionName(BossRules rules, List<Fight> fights, int maxHp)
	{
		if (rules == null || fights == null || maxHp <= 0)
		{
			return null;
		}
		for (Fight f : fights)
		{
			final BossRules.Boss boss = rules.forNpc(f.getTargetId());
			if (boss != null && boss.variants != null)
			{
				for (BossRules.Variant v : boss.variants)
				{
					if (v.maxHp == maxHp && v.version != null)
					{
						return v.version;
					}
				}
			}
		}
		return null;
	}

	/** A time of day, {@code 3:31 pm} (T-072). */
	static String time(long millis, ZoneId zone)
	{
		return clockTime(Instant.ofEpochMilli(millis).atZone(zone));
	}
}

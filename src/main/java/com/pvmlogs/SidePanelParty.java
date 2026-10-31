package com.pvmlogs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * T-125: the Party tab's model (Eric's pick, option B, 2026-10-31). Every member of the RuneLite party who runs Luck vs
 * Skill as a row of the meter: their damage, DPS and share, a bar filled to their share of the top member's damage, in
 * their colour; then {@code others}, the damage on the room's targets that no sharing member accounts for. After a room,
 * Luck and Execution too. Clicking a member shows their breakdown: their attack rows, Luck, Execution and its parts, and
 * the damage they took. Damage over time has a line a member.
 *
 * <p>Every member's figures are a {@link LuckVsSkillUpdate}: the party's from T-124's {@link PartyBoard}, the player's own
 * built by {@link PartyShare#build}, the same as what they send, so every row is read the same way.
 *
 * <p>Names are the party's display names, held in the snapshot only: never saved, exported or written to CSV or history.
 */
final class SidePanelParty
{
	private SidePanelParty()
	{
	}

	/**
	 * One colour a member, in the order they joined the board, kept through the raid. Seven hues validated as a set on the
	 * panel's dark card (dataviz's validator: lightness band, chroma, adjacent pairs at ΔE 8.4 under colour-blindness and
	 * 19.3 with normal vision, 3:1 contrast), none of them the cost orange or the better cyan. The player is white, so eight
	 * in all; an eighth other member takes the first colour again, with their name beside it.
	 */
	static final Color[] COLOURS = {new Color(0x3987e5), new Color(0x199e70), new Color(0xc98500), new Color(0xd55181),
		new Color(0x008300), new Color(0x9085e9), new Color(0xe66767)};
	static final Color YOU = SidePanelStyle.VALUE;
	static final Color OTHERS = new Color(0x6A6A6A);

	static final String NOBODY = "No one in your party is sharing.";
	static final String NOT_IN_PARTY = "Not in a RuneLite party. Members of your party who run Luck vs Skill show here, "
		+ "with their damage beside yours.";
	static final String NOTHING_YET = "Your party's damage shows here once you fight.";
	static final String OTHERS_NOTE = "others: damage on the targets no sharing member accounts for: members not running "
		+ "Luck vs Skill, and thralls.";
	/** An attack row the sender summed the rest into (T-124's {@link LuckVsSkillUpdate#MAX_ROWS}). */
	static final String OTHER_ATTACKS = "Other attacks";
	static final String YOU_NAME = "you";
	/** The player's own row's id: never a party member's, which are positive. */
	static final long YOU_ID = -2;

	/** What the meter's value columns show: the header's switch, after a room (Eric's pick, the defaults). */
	enum Metric
	{
		DMG, LUCK, EXEC;

		String word()
		{
			return name().toLowerCase(Locale.ENGLISH);
		}
	}

	/** One member's figures for the scope on show, and their damage over it. */
	static final class Member
	{
		final long id;
		final String name;
		final boolean you;
		/** Left the party with the scope still on show: their row stays, muted. */
		boolean left;
		/** Their colour's slot, kept through the raid. */
		int slot;
		final LuckVsSkillUpdate figures;
		/** Cumulative damage over ticks, a point at a time; empty for none. */
		int[] ticks = new int[0];
		int[] damage = new int[0];
		/** T-128: their Discord picture, from RuneLite's party (its Discord plugin sets it); null for none. */
		java.awt.image.BufferedImage avatar;
		/** T-128: their Fight tab, built for the member opened only; null otherwise or while it isn't in. */
		PanelSnapshot.FightTab fight;
		/** T-128: {@link #fight} is from their live update: the rest comes when their room ends. */
		boolean fightLive;

		Member(long id, String name, boolean you, LuckVsSkillUpdate figures)
		{
			this.id = id;
			this.name = name;
			this.you = you;
			this.figures = figures;
		}

		Color colour()
		{
			return you ? YOU : COLOURS[Math.floorMod(slot, COLOURS.length)];
		}
	}

	/** What the panel read: the scope on show, its members, and the damage no member accounts for. */
	static final class Input
	{
		boolean inParty;
		/** The room or raid on show; null with nothing fought yet. */
		String scope;
		/** Its clock, seconds. */
		double seconds;
		/** Whether it's still being fought: no Luck or Execution to switch to yet. */
		boolean live;
		/** The room/raid switch, as the Fight tab's: null outside a raid. */
		Boolean wholeRaid;
		/** Other players' hits on the scope's targets, as the client saw them (Fight's othersDamage). */
		int othersDamage;
		final List<Member> members = new ArrayList<>();
		/** Item names, for the attack rows; the id's number without one. */
		IntFunction<String> itemNames = id -> "item " + id;
	}

	// ---- the model ----

	/** The Party tab for {@code in}. */
	static PanelSnapshot.PartyTab party(Input in)
	{
		boolean sharing = false;
		for (Member m : in.members)
		{
			sharing |= !m.you;
		}
		if (!sharing || in.scope == null)
		{
			return PanelSnapshot.PartyTab.empty(!in.inParty ? NOT_IN_PARTY : !sharing ? NOBODY : NOTHING_YET);
		}
		final int others = others(in.othersDamage, in.members);
		final int total = total(in.members, others);
		final List<Member> order = sorted(in.members);
		final int top = Math.max(others, order.get(0).figures.d);
		final double seconds = Math.max(0.6, in.seconds);
		final List<PanelSnapshot.PartyRow> rows = new ArrayList<>(order.size() + 1);
		for (int i = 0; i < order.size(); i++)
		{
			final Member m = order.get(i);
			rows.add(row(m, i + 1, order.size(), top, total, seconds, in));
		}
		if (others > 0)
		{
			rows.add(PanelSnapshot.PartyRow.others(fill(others, top), new String[]{compact(others), "", share(others, total)},
				otherSeries(in), OTHERS));
		}
		int ticks = 1;
		for (Member m : in.members)
		{
			ticks = Math.max(ticks, m.ticks.length == 0 ? 0 : m.ticks[m.ticks.length - 1]);
		}
		return new PanelSnapshot.PartyTab(in.scope, String.format(Locale.ENGLISH,
			"party %.1f dps", total / seconds), in.wholeRaid, !in.live, rows, others > 0 ? OTHERS_NOTE : null, ticks,
			LedgerRows.duration(seconds));
	}

	private static PanelSnapshot.PartyRow row(Member m, int rank, int of, int top, int total, double seconds, Input in)
	{
		final LuckVsSkillUpdate f = m.figures;
		final String name = m.you ? YOU_NAME : m.name;
		final String[] dmg = {compact(f.d), dps(f.d, seconds), share(f.d, total)};
		final boolean luckKnown = f.p >= 0;
		final String[] luck = {luckKnown ? LedgerRows.band(f.p) : "", luckKnown ? seconds(f.ls) : ""};
		final String[] exec = {f.x >= 0 ? LedgerRows.partPercent(f.x) : "", f.il > 0 ? "-" + f.il + LedgerRows.TICKS_SHORT
			: ""};
		final Color luckColour = luckKnown ? SidePanelStyle.of(LedgerRows.percentileBand(f.p)) : SidePanelStyle.MUTED;
		final String place = ordinal(rank) + " of " + of + " · " + share(f.d, total) + " · " + dps(f.d, seconds) + " dps";
		final PanelSnapshot.PartyBreakdown breakdown = new PanelSnapshot.PartyBreakdown(m.you ? "You" : m.name, place,
			attacks(f, seconds, in.itemNames), summary(f, !in.live));
		return new PanelSnapshot.PartyRow(m.id, name, m.you, m.left, m.colour(), fill(f.d, top), dmg, luck, exec,
			luckColour, m.ticks, m.damage, breakdown).with(m.avatar, m.fight, m.fightLive);
	}

	/** The members, biggest damage first; the player first among equals. */
	static List<Member> sorted(List<Member> members)
	{
		final List<Member> out = new ArrayList<>(members);
		out.sort(Comparator.comparingInt((Member m) -> -m.figures.d).thenComparing(m -> !m.you).thenComparingLong(m -> m.id));
		return out;
	}

	/** The damage no sharing member accounts for: other players' hits less the members' own. Never negative. */
	static int others(int othersDamage, List<Member> members)
	{
		long members_ = 0;
		for (Member m : members)
		{
			if (!m.you)
			{
				members_ += Math.max(0, m.figures.d);
			}
		}
		return (int) Math.max(0, othersDamage - members_);
	}

	static int total(List<Member> members, int others)
	{
		long t = Math.max(0, others);
		for (Member m : members)
		{
			t += Math.max(0, m.figures.d);
		}
		return (int) Math.min(Integer.MAX_VALUE, t);
	}

	/** A bar's length: its damage against the top's, 0 to 1. */
	static double fill(int damage, int top)
	{
		return top <= 0 ? 0 : Math.max(0, Math.min(1, (double) damage / top));
	}

	/** The Fight tab's line, which opens the Party tab: {@code you 2nd of 5 · 38%}; null with no one sharing. */
	static String fightLine(PanelSnapshot.PartyTab t)
	{
		if (!t.sharing)
		{
			return null;
		}
		int members = 0;
		int rank = 0;
		String share = "";
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			if (r.others)
			{
				continue;
			}
			members++;
			if (r.you)
			{
				rank = members;
				share = r.dmg[2];
			}
		}
		return rank == 0 ? null : "you " + ordinal(rank) + " of " + members + " · " + share;
	}

	// ---- the breakdown ----

	/** The member's attack rows, as the Damage card has them: name, damage and share, then DPS, expected and hits. */
	static List<PanelSnapshot.PartyAttack> attacks(LuckVsSkillUpdate f, double seconds, IntFunction<String> itemNames)
	{
		final List<PanelSnapshot.PartyAttack> out = new ArrayList<>();
		for (LuckVsSkillUpdate.Row r : f.rows())
		{
			final String name = attackName(r, itemNames);
			final String right = LedgerRows.number(r.d) + "  " + percent(r.d, f.d);
			final String line1 = dps(r.d, seconds) + " dps" + (r.e > 0 ? " · " + Math.round(100 * r.d / r.e)
				+ "% of expected" : "");
			final String line2 = (r.x ? "landed " : "hit ") + r.l + " of " + r.a;
			out.add(new PanelSnapshot.PartyAttack(name, right, fill(r.d, f.d), line1, line2));
		}
		return out;
	}

	static String attackName(LuckVsSkillUpdate.Row r, IntFunction<String> itemNames)
	{
		String name;
		final Spell spell = r.s > 0 ? Spell.forVarbit(r.s) : null;
		if (spell != null)
		{
			name = spell.getDisplayName();
		}
		else if (r.i >= 0)
		{
			name = itemNames.apply(r.i);
		}
		else
		{
			return OTHER_ATTACKS;
		}
		return r.x ? name + " spec" : name;
	}

	/** Luck, Execution and its parts, and the damage taken, in the panel's own rows. Luck and Execution after a room. */
	static List<PanelSnapshot.Row> summary(LuckVsSkillUpdate f, boolean after)
	{
		final List<PanelSnapshot.Row> rows = new ArrayList<>();
		if (after && f.p >= 0)
		{
			rows.add(row("luck", LedgerRows.band(f.p) + " (" + LedgerRows.fasterOrSlower(f.ls) + ")",
				SidePanelStyle.of(LedgerRows.percentileBand(f.p)), false));
		}
		if (f.x >= 0)
		{
			rows.add(row(after ? "execution" : "execution so far", LedgerRows.partPercent(f.x), SidePanelStyle.VALUE, false));
			if (f.u >= 0)
			{
				rows.add(row("uptime", LedgerRows.withTicks(LedgerRows.partPercent(f.u), f.il * LedgerRows.SECONDS_PER_TICK),
					f.il > 0 ? SidePanelStyle.COST : SidePanelStyle.BODY, true));
			}
			setup(rows, SidePanelModel.PRAYER, f.ps, f.pd);
			setup(rows, SidePanelModel.GEAR, f.gs, f.gd);
			setup(rows, SidePanelModel.BOOST, f.bs, f.bd);
		}
		if (f.tk > 0 || f.th > 0)
		{
			rows.add(row("damage taken", LedgerRows.number(f.tk) + " (" + f.th + (f.th == 1 ? " hit)" : " hits)"),
				SidePanelStyle.BODY, false));
		}
		return rows;
	}

	private static void setup(List<PanelSnapshot.Row> rows, String label, double share, double damage)
	{
		if (share < 0)
		{
			return;
		}
		final long d = damage < 0 ? 0 : LedgerRows.roundHalfAway(damage);
		rows.add(row(label, LedgerRows.withDamage(LedgerRows.partPercent(share), d), d >= 1 ? SidePanelStyle.COST
			: SidePanelStyle.BODY, true));
	}

	private static PanelSnapshot.Row row(String left, String right, Color colour, boolean indent)
	{
		return new PanelSnapshot.Row(PanelSnapshot.Text.small(left, SidePanelStyle.BODY), PanelSnapshot.Text.NONE,
			PanelSnapshot.Text.small(right, colour), null, false, indent);
	}

	// ---- whole raid ----

	/** A member's raid: their rooms' summaries summed, the room still running (its live figures) after them. */
	static LuckVsSkillUpdate sum(List<LuckVsSkillUpdate> rooms)
	{
		final LuckVsSkillUpdate s = new LuckVsSkillUpdate();
		s.k = LuckVsSkillUpdate.ROOM_END;
		double pWeighted = 0;
		double pWeight = 0;
		double xWeighted = 0;
		double uWeighted = 0;
		double xWeight = 0;
		final java.util.Map<String, LuckVsSkillUpdate.Row> rows = new java.util.LinkedHashMap<>();
		for (LuckVsSkillUpdate u : rooms)
		{
			if (u == null)
			{
				continue;
			}
			s.r = u.rn != null ? u.rn : u.r;
			s.rn = u.rn;
			s.d += u.d;
			s.e += Math.max(0, u.e);
			s.st += u.st;
			s.a += u.a;
			s.ct += u.ct;
			s.il += u.il;
			s.tk += u.tk;
			s.th += u.th;
			s.ls += u.ls;
			if (u.p >= 0 && u.e > 0)
			{
				pWeighted += u.p * u.e;
				pWeight += u.e;
			}
			if (u.x >= 0 && u.st > 0)
			{
				xWeighted += u.x * u.st;
				uWeighted += Math.max(0, u.u) * u.st;
				xWeight += u.st;
			}
			s.bd = add(s.bd, u.bd);
			s.gd = add(s.gd, u.gd);
			s.pd = add(s.pd, u.pd);
			for (LuckVsSkillUpdate.Row r : u.rows())
			{
				final LuckVsSkillUpdate.Row t = rows.computeIfAbsent(r.i + "/" + r.s + "/" + r.x, k ->
				{
					final LuckVsSkillUpdate.Row n = new LuckVsSkillUpdate.Row();
					n.i = r.i;
					n.s = r.s;
					n.x = r.x;
					return n;
				});
				t.a += r.a;
				t.l += r.l;
				t.d += r.d;
				t.e += r.e;
			}
		}
		// The raid's luck: each room's percentile weighted by what was expected of it, as a raid's own scope weighs its
		// rooms' spread. An approximation, labelled as the rooms' own are.
		s.p = pWeight > 0 ? pWeighted / pWeight : -1;
		s.x = xWeight > 0 ? xWeighted / xWeight : -1;
		s.u = xWeight > 0 ? uWeighted / xWeight : -1;
		final List<LuckVsSkillUpdate.Row> all = new ArrayList<>(rows.values());
		all.sort(Comparator.comparingInt((LuckVsSkillUpdate.Row r) -> -r.d));
		s.w = all;
		return s;
	}

	private static double add(double sum, double part)
	{
		return part < 0 ? sum : Math.max(0, sum) + part;
	}

	/** Rooms' series end to end: each room's ticks and damage after the last's. */
	static int[][] concat(List<List<PartyBoard.Point>> rooms)
	{
		int n = 0;
		for (List<PartyBoard.Point> r : rooms)
		{
			n += r.size();
		}
		final int[] ticks = new int[n];
		final int[] damage = new int[n];
		int i = 0;
		int tickBase = 0;
		int damageBase = 0;
		for (List<PartyBoard.Point> r : rooms)
		{
			int lastTicks = 0;
			int lastDamage = 0;
			for (PartyBoard.Point p : r)
			{
				// T-128: by the room's clock, as the player's own line is.
				ticks[i] = tickBase + p.x();
				damage[i] = damageBase + p.damage;
				lastTicks = p.x();
				lastDamage = p.damage;
				i++;
			}
			tickBase += lastTicks;
			damageBase += lastDamage;
		}
		return new int[][]{ticks, damage};
	}

	/** A series from a member's points. */
	static int[][] series(List<PartyBoard.Point> points)
	{
		return concat(Collections.singletonList(points == null ? Collections.<PartyBoard.Point>emptyList() : points));
	}

	/**
	 * The player's own series from their timeline: its damage is already cumulative, the weapon damage up to and
	 * including each tick ({@link FightTimeline.View#damage}), on the room's clock. T-128: T-125 summed it again, so the
	 * line climbed far past the members' (Eric, 2026-10-31: "The damage over time graph is bugged").
	 */
	static int[][] series(float[] cumulative)
	{
		final int n = cumulative == null ? 0 : cumulative.length;
		final int[] ticks = new int[n + 1];
		final int[] damage = new int[n + 1];
		for (int t = 0; t < n; t++)
		{
			ticks[t + 1] = t + 1;
			damage[t + 1] = Math.round(cumulative[t]);
		}
		return new int[][]{ticks, damage};
	}

	/** Others' line: what's left of the party's damage once the members' is taken out, as it grew; flat for none. */
	private static int[][] otherSeries(Input in)
	{
		// The client has no tick-by-tick record of other players' hits, so others' line is drawn as a straight rise to
		// its total over the scope: the chart says so in its tooltip.
		int ticks = 1;
		for (Member m : in.members)
		{
			ticks = Math.max(ticks, m.ticks.length == 0 ? 0 : m.ticks[m.ticks.length - 1]);
		}
		final int others = others(in.othersDamage, in.members);
		return new int[][]{{0, ticks}, {0, others}};
	}

	// ---- formatting ----

	static String compact(int n)
	{
		return n >= 100_000 ? Math.round(n / 1000.0) + "k" : n >= 10_000 ? String.format(Locale.ENGLISH, "%.1fk",
			n / 1000.0) : LedgerRows.number(n);
	}

	static String dps(int damage, double seconds)
	{
		return String.format(Locale.ENGLISH, "%.1f", seconds <= 0 ? 0 : damage / seconds);
	}

	static String share(int damage, int total)
	{
		return percent(damage, total);
	}

	static String percent(int part, int whole)
	{
		return (whole <= 0 ? 0 : Math.round(100.0 * Math.max(0, part) / whole)) + "%";
	}

	/** Luck's seconds in the meter's narrow column: {@code 12 s}, coloured by the band beside it. */
	static String seconds(double ls)
	{
		final long s = Math.round(Math.abs(ls));
		return s < 1 ? "even" : s + " s";
	}

	static String ordinal(int n)
	{
		final int t = n % 100;
		final String suffix = t >= 11 && t <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
		return n + suffix;
	}
}

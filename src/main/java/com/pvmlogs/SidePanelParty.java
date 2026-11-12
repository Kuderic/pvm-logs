package com.pvmlogs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * T-125: the party's model (Eric's pick, option B, 2026-10-31; on the Fight tab since T-176). Every member of the RuneLite party who runs Luck vs
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
	/** T-129: in a party, before anyone's update has come in. */
	static String waiting(int partySize)
	{
		return (partySize > 1 ? "In a party of " + partySize + "." : "In a party.")
			+ " Members running Luck vs Skill appear here once their fight starts.";
	}
	static final String NOT_IN_PARTY = "Not in a RuneLite party. Members of your party who run Luck vs Skill show here, "
		+ "with their damage beside yours.";
	static final String NOTHING_YET = "Your party's damage shows here once you fight.";
	/**
	 * T-134: sharing members' thrall, recoil and damage over time are theirs now (their {@code o}), so "and thralls" goes:
	 * what's left is players not running Luck vs Skill (their thralls too) and members on a sender before T-134.
	 */
	static final String OTHERS_NOTE = "others: damage on the targets no sharing member accounts for: members not running "
		+ "Luck vs Skill.";
	static final String NO_FIGURES_YET = "no figures yet";
	static final List<String> NO_FIGURES_YET_SHORT = Collections.singletonList("");
	/** T-167: after the name of {@code others} taken as the members' who don't share. */
	static final String ABOUT = "about";

	/**
	 * T-167: before others' damage where it's taken as members': {@code ~9,870}, so the estimate shows on the row even
	 * where {@link #ABOUT} doesn't fit beside the name.
	 */
	static final String APPROX = "~";


	/** T-167: two or more members not running Luck vs Skill: their combined row's name. T-208: "1 member" too. */
	static String members(int n)
	{
		return n == 1 ? "1 member" : n + " members";
	}

	/**
	 * T-208 (Eric: "the 2 players in my party should be grouped as part of the '2 members' damage, not as their own
	 * listing"): the group row's tooltip: who is in it, and that its damage is estimated (and, for two or more, can't be
	 * split). The note that was under the meter.
	 */
	static String groupTip(List<Unshared> in)
	{
		final List<String> names = new ArrayList<>(in.size());
		for (Unshared u : in)
		{
			names.add(u.name);
		}
		final String who = members(in.size()) + " not running Luck vs Skill: " + joined(names) + ".";
		return who + (in.size() == 1 ? " Their damage is estimated: the damage on the targets that no sharing member "
			+ "accounts for." : " Their damage is estimated, the damage on the targets that no sharing member accounts "
			+ "for, and can't be split between them.") + " Players outside the party, and thralls, are in it too.";
	}

	/**
	 * T-216: the estimate row's tooltip: both figures, then who and why ({@link #groupTip}), and that it has no luck or
	 * skill.
	 */
	static String estimateTip(List<Unshared> in, int damage, String dps)
	{
		return APPROX + LedgerRows.number(damage) + " damage, " + APPROX + dps + " dps.<br>" + groupTip(in)
			+ "<br>No luck or skill: they don't run Luck vs Skill.";
	}

	/** T-208: the members not seen in the fight, under the meter, and their names on its hover. */
	static String away(int n)
	{
		return "not in this fight: " + n;
	}

	static String awayTip(List<Unshared> away)
	{
		final List<String> names = new ArrayList<>(away.size());
		for (Unshared u : away)
		{
			names.add(u.name);
		}
		return "Not seen in this fight: " + joined(names) + ". In your party, not running Luck vs Skill, and left out "
			+ "of its damage.";
	}

	/** "A", "A and B", "A, B and C". */
	static String joined(List<String> names)
	{
		if (names.size() <= 1)
		{
			return names.isEmpty() ? "" : names.get(0);
		}
		return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
	}

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
			// T-199: "skill", the plugin's one word for it (the enum's name is code).
			return this == EXEC ? "skill" : name().toLowerCase(Locale.ENGLISH);
		}

		/** T-216: the column's word: {@code dps} live for damage, as the rows show DPS while the fight is on. */
		String word(boolean live)
		{
			return this == DMG && live ? "dps" : word();
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
		/**
		 * T-216: luck and Skill so far at each of {@link #ticks}' points, 0 to 1, NaN where the update didn't have it; empty
		 * for none. The chart's luck and skill lines.
		 */
		float[] luck = new float[0];
		float[] skill = new float[0];
		/** T-216: the ticks of {@link #luck} and {@link #skill}'s points where they aren't {@link #ticks}' (yours); null. */
		int[] metricTicks;
		/** T-128: their Discord picture, from RuneLite's party (its Discord plugin sets it); null for none. */
		java.awt.image.BufferedImage avatar;
		/** T-128: their Fight tab, built for the member opened only; null otherwise or while it isn't in. */
		PanelSnapshot.FightTab fight;
		/** T-128: {@link #fight} is from their live update: the rest comes when their room ends. */
		boolean fightLive;
		/**
		 * T-169: the dev loopback's member ({@link PartyBoard#ECHO_NAME}, {@link PartyBoard#LOCAL_NAME}): your own figures
		 * shown back, for testing. Shown, but never counted: not taken from others, not in the total, not ranked.
		 */
		boolean echo;

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

	/**
	 * T-167: a party member who hasn't shared anything: in RuneLite's party, not on the board. Their name and picture as
	 * the party has them, held in the snapshot only.
	 */
	static final class Unshared
	{
		final long id;
		final String name;
		final java.awt.image.BufferedImage avatar;

		Unshared(long id, String name, java.awt.image.BufferedImage avatar)
		{
			this.id = id;
			this.name = name == null || name.isEmpty() ? "Party member" : name;
			this.avatar = avatar;
		}
	}

	/**
	 * T-169: whether a board member is the dev loopback's: your own party id, or the name the loopback gives it (the
	 * local copy's id is -1 where the party had no local member yet).
	 */
	static boolean echo(long memberId, String name, long localId)
	{
		return memberId == localId || PartyBoard.ECHO_NAME.equals(name) || PartyBoard.LOCAL_NAME.equals(name);
	}

	/**
	 * T-167: the party's members who haven't shared: everyone in {@code party} but you ({@code localId}, also the dev
	 * loopback's echo) and those on the board ({@code onBoard}), in the party's order.
	 */
	static List<Unshared> unshared(List<Unshared> party, long localId, java.util.Set<Long> onBoard)
	{
		final List<Unshared> out = new ArrayList<>();
		for (Unshared m : party)
		{
			if (m.id != localId && !onBoard.contains(m.id))
			{
				out.add(m);
			}
		}
		return out;
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
		/**
		 * T-216: {@link #othersDamage} as it grew, on the chart's axis (the scope's ticks, as your own line's): the running
		 * total at each tick a splat landed. Null where it wasn't recorded (the scope from history, a build before T-216).
		 */
		int[] othersTicks;
		int[] othersTotals;
		final List<Member> members = new ArrayList<>();
		/** T-129: the party's size as RuneLite has it, and how many members have shared anything yet. */
		int partySize;
		int shared;
		/** T-130: RuneLite's party id, 0 out of one. */
		long partyKey;
		/** Item names, for the attack rows; the id's number without one. */
		IntFunction<String> itemNames = id -> "item " + id;
		/** T-167: the party's members who haven't shared anything, in the party's order. */
		final List<Unshared> unshared = new ArrayList<>();
		/**
		 * T-209: the party members in the scope's fight, by id (seen at it, or sharing for it); null where unknown, when
		 * every member who hasn't shared is taken as in it, as before.
		 */
		java.util.Set<Long> inFight;
	}

	/** T-209: the members who haven't shared and were in the fight: all of them where that isn't known. */
	static List<Unshared> atFight(List<Unshared> unshared, java.util.Set<Long> inFight)
	{
		if (inFight == null)
		{
			return unshared;
		}
		final List<Unshared> out = new ArrayList<>();
		for (Unshared u : unshared)
		{
			if (inFight.contains(u.id))
			{
				out.add(u);
			}
		}
		return out;
	}

	// ---- the model ----

	/** The party's block on the Fight tab for {@code in} (T-176; it was the Party tab). */
	static PanelSnapshot.PartyTab party(Input in)
	{
		boolean sharing = false;
		for (Member m : in.members)
		{
			sharing |= !m.you;
		}
		final List<Unshared> unshared = in.inParty ? in.unshared : Collections.<Unshared>emptyList();
		if (!sharing && unshared.isEmpty() || in.scope == null)
		{
			// T-129: out of a party; in one before anyone has shared; shared, but nothing of yours on show yet. T-167: the
			// members who haven't shared listed under it, by name.
			final List<PanelSnapshot.PartyRow> listed = new ArrayList<>(unshared.size());
			for (Unshared u : unshared)
			{
				listed.add(PanelSnapshot.PartyRow.listed(u.id, u.name, u.avatar, NO_FIGURES_YET, NO_FIGURES_YET_SHORT,
					u.name + " hasn't shared anything with your party yet: their figures show here once their fight starts, "
						+ "if they run Luck vs Skill."));
			}
			return PanelSnapshot.PartyTab.empty(!in.inParty ? NOT_IN_PARTY : in.shared == 0 && !sharing
				? waiting(in.partySize) : !sharing ? NOBODY : NOTHING_YET, listed).party(in.partyKey);
		}
		final int others = others(in.othersDamage, in.members);
		final int total = total(in.members, others);
		final List<Member> order = sorted(in.members);
		final int top = Math.max(others, order.get(0).figures.all());
		final double seconds = Math.max(0.6, in.seconds);
		final List<PanelSnapshot.PartyRow> rows = new ArrayList<>(order.size() + unshared.size() + 1);
		// T-169: ranked among the members counted: the echo takes your place, being your figures.
		int counted = 0;
		int yourRank = 1;
		for (Member m : order)
		{
			if (!m.echo)
			{
				counted++;
				yourRank = m.you ? counted : yourRank;
			}
		}
		int rank = 0;
		for (Member m : order)
		{
			rank += m.echo ? 0 : 1;
			rows.add(row(m, m.echo ? yourRank : rank, counted, top, total, seconds, in));
		}
		final String[] othersDmg = {compact(others), "", share(others, total)};
		final String[] aboutDmg = {APPROX + compact(others), "", share(others, total)};
		// T-209: others is split only between the members who haven't shared and were in the fight; the rest are out of it.
		final List<Unshared> fighting = atFight(unshared, in.inFight);
		final List<Unshared> away = new ArrayList<>(unshared);
		away.removeAll(fighting);
		final String othersDps = dps(others, seconds);
		if (fighting.size() == 1)
		{
			// T-216 (Eric: "1 member should show the member's discord picture still when they're the only member in
			// party"): one member not running Luck vs Skill is drawn as themselves, their picture and name, in a colour of
			// their own; "~" before the estimated figure, what it is on hover.
			final Unshared u = fighting.get(0);
			rows.add(PanelSnapshot.PartyRow.estimate(u.id, u.name, u.avatar, estimateTip(fighting, others, othersDps),
				fill(others, top), aboutDmg,
				otherSeries(in), COLOURS[Math.floorMod(freeSlot(in.members), COLOURS.length)]).rate(others / seconds,
				APPROX + othersDps));
		}
		else if (!fighting.isEmpty())
		{
			// T-208: two or more members not running Luck vs Skill in the fight: one group row, "N members", with the
			// estimated damage and share; who they are, and what it is, on its hover.
			rows.add(PanelSnapshot.PartyRow.estimate(PanelSnapshot.PartyRow.OTHERS_ID, members(fighting.size()), null,
				estimateTip(fighting, others, othersDps), fill(others, top), aboutDmg, otherSeries(in), OTHERS).rate(
				others / seconds, APPROX + othersDps));
		}
		else if (others > 0)
		{
			rows.add(PanelSnapshot.PartyRow.others(fill(others, top), othersDmg, otherSeries(in), OTHERS).rate(
				others / seconds, othersDps));
		}
		int ticks = 1;
		for (Member m : in.members)
		{
			ticks = Math.max(ticks, m.ticks.length == 0 ? 0 : m.ticks[m.ticks.length - 1]);
		}
		for (PanelSnapshot.PartyRow r : rows)
		{
			// T-216: live, the first column is DPS on every row.
			r.live = in.live;
			ticks = Math.max(ticks, r.seriesTicks.length == 0 ? 0 : r.seriesTicks[r.seriesTicks.length - 1]);
		}
		// T-208: the paragraph under the meter is the group row's tooltip now; who wasn't there, set apart under it.
		return new PanelSnapshot.PartyTab(in.scope, String.format(Locale.ENGLISH,
			"party %.1f dps", total / seconds), in.wholeRaid, !in.live, rows, null, ticks,
			LedgerRows.duration(seconds)).party(in.partyKey).inFight(in.inFight).away(away.isEmpty() ? null
			: away(away.size()), away.isEmpty() ? null : awayTip(away));
	}

	/** T-216: the first colour no sharing member has: the lone member not running Luck vs Skill's. */
	static int freeSlot(List<Member> members)
	{
		final java.util.Set<Integer> used = new java.util.HashSet<>();
		for (Member m : members)
		{
			if (!m.you && !m.echo)
			{
				used.add(Math.floorMod(m.slot, COLOURS.length));
			}
		}
		for (int i = 0; i < COLOURS.length; i++)
		{
			if (!used.contains(i))
			{
				return i;
			}
		}
		return used.size();
	}

	/**
	 * T-216: a member's DPS: all their damage over their own clock ({@link LuckVsSkillUpdate#ct}), the scope's where their
	 * update didn't send it. Yours is the card's (T-200), set on the meter.
	 */
	static double rate(LuckVsSkillUpdate f, double seconds)
	{
		final double own = f.ct > 0 ? f.ct * LedgerRows.SECONDS_PER_TICK : seconds;
		return own <= 0 ? 0 : f.all() / Math.max(0.6, own);
	}

	private static PanelSnapshot.PartyRow row(Member m, int rank, int of, int top, int total, double seconds, Input in)
	{
		final LuckVsSkillUpdate f = m.figures;
		final String name = m.you ? YOU_NAME : m.name;
		// T-134: all their damage: the weapon's and their thrall, recoil and damage over time.
		final int all = f.all();
		final double rate = rate(f, seconds);
		final String[] dmg = {compact(all), String.format(Locale.ENGLISH, "%.1f", rate), share(all, total)};
		final boolean luckKnown = f.p >= 0;
		final String[] luck = {luckKnown ? SidePanelModel.luckText(f.p) : "", luckKnown ? seconds(f.ls) : ""};
		final String[] exec = {f.x >= 0 ? LedgerRows.partPercent(f.x) : "", f.il > 0 ? "-" + f.il + LedgerRows.TICKS_SHORT
			: ""};
		// T-194: the roll in its strength of luck's violet, never skill's colours.
		final Color luckColour = luckKnown ? SidePanelModel.luckInk(f.p) : SidePanelStyle.MUTED;
		final String place = ordinal(rank) + " of " + of + " · " + share(all, total) + " · " + dmg[1] + " dps";
		final PanelSnapshot.PartyBreakdown breakdown = new PanelSnapshot.PartyBreakdown(m.you ? "You" : m.name, place,
			attacks(f, seconds, in.itemNames), summary(f, !in.live));
		// T-208: luck and skill at a glance: the mini track's percentile, its words, and the Skill share.
		final String luckWords = luckKnown ? SidePanelModel.luckText(f.p) + " (" + LedgerRows.fasterOrSlower(f.ls) + ")"
			: "";
		return new PanelSnapshot.PartyRow(m.id, name, m.you, m.left, m.colour(), fill(all, top), dmg, luck, exec,
			luckColour, m.ticks, m.damage, breakdown).with(m.avatar, m.fight, m.fightLive).echo(m.echo).glance(
			luckKnown ? f.p : Double.NaN, luckWords, f.x, f.x >= 0 ? LedgerRows.partPercent(f.x) : "", in.live)
			.rate(rate, dmg[1]).metrics(m.metricTicks == null ? m.ticks : m.metricTicks, m.luck, m.skill);
	}

	/** The members, biggest damage first; the player first among equals. */
	static List<Member> sorted(List<Member> members)
	{
		final List<Member> out = new ArrayList<>(members);
		out.sort(Comparator.comparingInt((Member m) -> -m.figures.all()).thenComparing(m -> !m.you)
			.thenComparingLong(m -> m.id));
		return out;
	}

	/**
	 * The damage no sharing member accounts for: other players' hits less the members' own, all of it (T-134: their
	 * thrall's splats reach you as another player's too). Never negative. T-169: never less the dev loopback's echo,
	 * whose figures are your own and never among other players' hits (Eric, 2026-11-05: a duo partner read 0).
	 */
	static int others(int othersDamage, List<Member> members)
	{
		long members_ = 0;
		for (Member m : members)
		{
			if (!m.you && !m.echo)
			{
				members_ += m.figures.all();
			}
		}
		return (int) Math.max(0, othersDamage - members_);
	}

	static int total(List<Member> members, int others)
	{
		long t = Math.max(0, others);
		for (Member m : members)
		{
			// T-169: the echo is your figures again: counted once, as you.
			t += m.echo ? 0 : m.figures.all();
		}
		return (int) Math.min(Integer.MAX_VALUE, t);
	}

	/** A bar's length: its damage against the top's, 0 to 1. */
	static double fill(int damage, int top)
	{
		return top <= 0 ? 0 : Math.max(0, Math.min(1, (double) damage / top));
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
			rows.add(row("luck", SidePanelModel.luckText(f.p) + " (" + LedgerRows.fasterOrSlower(f.ls) + ")",
				SidePanelModel.luckInk(f.p), false));
		}
		if (f.x >= 0)
		{
			rows.add(row(after ? "skill" : "skill so far", LedgerRows.partPercent(f.x), SidePanelStyle.VALUE, false));
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
			s.o += Math.max(0, u.o);
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

	/**
	 * T-216: the rooms' luck and Skill so far at each point, in {@link #concat}'s order: {luck, skill}, 0 to 1, NaN where
	 * the update didn't have it. Each room's own running figure: luck and Skill aren't summed across rooms.
	 */
	static float[][] metrics(List<List<PartyBoard.Point>> rooms)
	{
		int n = 0;
		for (List<PartyBoard.Point> r : rooms)
		{
			n += r.size();
		}
		final float[] luck = new float[n];
		final float[] skill = new float[n];
		int i = 0;
		for (List<PartyBoard.Point> r : rooms)
		{
			for (PartyBoard.Point p : r)
			{
				luck[i] = p.luck >= 0 ? (float) p.luck : Float.NaN;
				skill[i] = p.skill >= 0 ? (float) p.skill : Float.NaN;
				i++;
			}
		}
		return new float[][]{luck, skill};
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

	/**
	 * Others' line: what's left of the party's damage once the members' is taken out, as it grew. T-216: from the other
	 * players' splats as they landed, tick by tick ({@link Input#othersTicks}), less the sharing members' damage at each
	 * tick (their line, scaled to all their damage, as the total takes all of it out); never falling, and ending at the
	 * total. Without the ticks (a scope from history), a straight rise to the total, as before.
	 */
	static int[][] otherSeries(Input in)
	{
		int ticks = 1;
		for (Member m : in.members)
		{
			ticks = Math.max(ticks, m.ticks.length == 0 ? 0 : m.ticks[m.ticks.length - 1]);
		}
		final int others = others(in.othersDamage, in.members);
		if (in.othersTicks == null || in.othersTicks.length == 0)
		{
			return new int[][]{{0, ticks}, {0, others}};
		}
		final int n = in.othersTicks.length;
		final int[] xs = new int[n + 2];
		final int[] ys = new int[n + 2];
		int k = 1;
		int running = 0;
		for (int i = 0; i < n; i++)
		{
			final int x = Math.max(0, in.othersTicks[i]);
			double members = 0;
			for (Member m : in.members)
			{
				if (!m.you && !m.echo)
				{
					members += sharedAt(m, x);
				}
			}
			running = Math.max(running, (int) Math.min(others, Math.max(0, Math.round(in.othersTotals[i] - members))));
			if (x == xs[k - 1] && k > 1)
			{
				ys[k - 1] = running;
				continue;
			}
			xs[k] = x;
			ys[k] = running;
			k++;
		}
		// The last point: the total, at the chart's end.
		xs[k] = Math.max(ticks, xs[k - 1]);
		ys[k] = others;
		k++;
		return new int[][]{java.util.Arrays.copyOf(xs, k), java.util.Arrays.copyOf(ys, k)};
	}

	/**
	 * T-216: a sharing member's damage at tick {@code x}, read off their line between its points, scaled from their
	 * weapon's damage (the line) to all of it (their thrall's splats reach you as another player's too).
	 */
	static double sharedAt(Member m, int x)
	{
		if (m.ticks.length == 0)
		{
			return 0;
		}
		final int last = m.damage[m.damage.length - 1];
		final double scale = last <= 0 ? 1 : Math.max(1, (double) m.figures.all() / last);
		double d = 0;
		for (int i = 0; i < m.ticks.length; i++)
		{
			if (m.ticks[i] >= x)
			{
				if (i == 0 || m.ticks[i] == x)
				{
					d = m.ticks[i] == x ? m.damage[i] : 0;
				}
				else
				{
					final double t = (double) (x - m.ticks[i - 1]) / (m.ticks[i] - m.ticks[i - 1]);
					d = m.damage[i - 1] + t * (m.damage[i] - m.damage[i - 1]);
				}
				return d * scale;
			}
			d = m.damage[i];
		}
		return d * scale;
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

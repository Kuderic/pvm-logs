package com.pvmlogs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything the side panel shows for one tick, already worked out and
 * formatted (T-033; spec section 5.1). Built on the client thread at the end of
 * {@code onGameTick}, then handed to Swing's thread, which only copies the
 * texts and colours onto components that already exist. Immutable: nothing in
 * here points back at the scope classes the client thread keeps writing.
 * <p>
 * Every list is unmodifiable and every text carries its own colour and type,
 * so the view makes no decisions of its own.
 */
final class PanelSnapshot
{
	final FightTab fight;
	/** The Trip tab (T-034); null when it wasn't built. */
	final TripTab trip;

	PanelSnapshot(FightTab fight)
	{
		this(fight, null);
	}

	PanelSnapshot(FightTab fight, TripTab trip)
	{
		this(fight, trip, null);
	}

	/** T-125: the party, on the Fight tab since T-176; null when it wasn't read. */
	final PartyTab party;

	PanelSnapshot(FightTab fight, TripTab trip, PartyTab party)
	{
		this.fight = fight;
		this.trip = trip;
		this.party = party;
	}

	// ---- T-125: the party (on the Fight tab since T-176) ----

	/**
	 * The party: the meter's rows (members biggest damage first, {@code others} last), its header, and what the
	 * Damage over time chart needs. With no one sharing, {@link #sharing} is false and {@link #empty} says why.
	 */
	static final class PartyTab
	{
		final boolean sharing;
		final String empty;
		/** The room or raid: {@code Zebak}; its clock is {@link #clock}. */
		final String where;
		/** {@code party 79.1 dps}. */
		final String partyDps;
		/** The room/raid switch: null outside a raid. */
		final Boolean wholeRaid;
		/** After the room: the {@code dmg · luck · exec} switch shows. */
		final boolean after;
		final List<PartyRow> rows;
		/** What {@code others} is, under the meter; null without an others row. */
		final String othersNote;
		/** The chart's last tick. */
		final int ticks;
		/** The chart's end label: the scope's clock. */
		final String clock;
		/** T-130: the party it's for (RuneLite's party id, 0 out of one): a new party puts the selection back on you. */
		long partyKey;
		/**
		 * T-209: the party members in the scope's fight (seen in the scene at it, or sharing for it), by id; null where
		 * that isn't known (no fight on show, or none fought in a party). For T-208's rows.
		 */
		java.util.Set<Long> inFight;
		/**
		 * T-208: the members not seen in the fight, set apart under the meter ({@code not in this fight: 1}) and named in
		 * its tooltip; null for none, or where who was in it isn't known.
		 */
		String away;
		String awayTip;

		/** T-208: this tab, with the members who weren't in the fight. */
		PartyTab away(String line, String tip)
		{
			this.away = line;
			this.awayTip = tip;
			return this;
		}

		/** T-130: this tab, for this party. */
		PartyTab party(long key)
		{
			this.partyKey = key;
			return this;
		}

		/** T-209: this tab, with the members in the fight. */
		PartyTab inFight(java.util.Set<Long> ids)
		{
			this.inFight = ids;
			return this;
		}

		PartyTab(String where, String partyDps, Boolean wholeRaid, boolean after, List<PartyRow> rows, String othersNote,
			int ticks, String clock)
		{
			this.sharing = true;
			this.empty = null;
			this.where = where;
			this.partyDps = partyDps;
			this.wholeRaid = wholeRaid;
			this.after = after;
			this.rows = list(rows);
			this.othersNote = othersNote;
			this.ticks = Math.max(1, ticks);
			this.clock = clock;
		}

		private PartyTab(String empty, List<PartyRow> listed)
		{
			this.sharing = false;
			this.empty = empty;
			this.where = "";
			this.partyDps = "";
			this.wholeRaid = null;
			this.after = false;
			// T-167: the party's members who haven't shared, listed under the line.
			this.rows = list(listed);
			this.othersNote = null;
			this.ticks = 1;
			this.clock = "";
		}

		static PartyTab empty(String why)
		{
			return new PartyTab(why, null);
		}

		/** T-167: empty, with the members who haven't shared listed by name. */
		static PartyTab empty(String why, List<PartyRow> listed)
		{
			return new PartyTab(why, listed);
		}

		/** The members' ids in order, others as -1: a member drilled into stays while their id is here. */
		List<Long> ids()
		{
			final List<Long> out = new ArrayList<>(rows.size());
			for (PartyRow r : rows)
			{
				out.add(r.id);
			}
			return out;
		}

		/** What decides the tab's components: rows are painted, so only their number, the switches and the note. */
		String structure()
		{
			// T-129: an empty tab's line is its structure: joining a party changes it.
			return !sharing ? "empty|" + empty + (rows.isEmpty() ? "" : "|listed" + rows.size()) : "rows" + rows.size() + (wholeRaid == null ? "" : wholeRaid ? "|raid" : "|room")
				+ (after ? "|after" : "") + (othersNote == null ? "" : "|n") + (away == null ? "" : "|away");
		}

		/** T-128: whether any member has a Discord picture, so the meter keeps a picture's room on every row. */
		boolean avatars()
		{
			for (PartyRow r : rows)
			{
				if (r.avatar != null)
				{
					return true;
				}
			}
			return false;
		}

		PartyRow row(long id)
		{
			for (PartyRow r : rows)
			{
				if (r.id == id && !r.others)
				{
					return r;
				}
			}
			return null;
		}
	}

	/** One row of the meter: a member, or others. Values by metric: damage, DPS, share; luck and its seconds; execution. */
	static final class PartyRow
	{
		static final long OTHERS_ID = -1;
		final long id;
		final String name;
		final boolean you;
		final boolean left;
		final boolean others;
		final Color colour;
		/** The bar: this row's damage against the top's, 0 to 1. */
		final double fill;
		final String[] dmg;
		final String[] luck;
		final String[] exec;
		final Color luckColour;
		/** Damage over time: cumulative damage at each point's tick. */
		final int[] seriesTicks;
		final int[] seriesDamage;
		/** The click-in view; null for others. */
		final PartyBreakdown breakdown;
		/**
		 * T-167: muted after the name, its shorter forms after it, longest first, for a narrow row: {@code about} on others
		 * taken as members', {@code not running Luck vs Skill} on a member listed without figures. Null for none.
		 */
		final String hint;
		final List<String> hintShort;
		/** T-167: the row's tooltip: others, and a member listed without figures or taken as others. Null for a member's. */
		final String tip;
		/** T-167: a party member listed without figures, as others is: no click, no values. */
		final boolean listed;
		/** T-169: the dev loopback's echo of your own figures: shown, never counted in the Fight tab's line. */
		boolean echo;
		/**
		 * T-208: the row's three figures at a glance: luck's percentile for the mini track (NaN for none) and its words
		 * for the tooltip ({@code top 19% (12 s faster)}); the Skill share, 0 to 1 (-1 for none), as written ({@code 96%});
		 * whether the fight is still on (live: the marker on the track and the Skill % so far).
		 */
		double luckP = Double.NaN;
		String luckWords = "";
		double skillShare = -1;
		String skill = "";
		boolean live;

		/** T-208: this row with its luck and skill at a glance. */
		PartyRow glance(double luckP, String luckWords, double skillShare, String skill, boolean live)
		{
			this.luckP = luckP;
			this.luckWords = luckWords;
			this.skillShare = skillShare;
			this.skill = skill;
			this.live = live;
			return this;
		}

		PartyRow echo(boolean echo)
		{
			this.echo = echo;
			return this;
		}

		/**
		 * T-216 (Orchestrator's answer to Eric: "yes, DPS live, damage after"): the row's DPS, as a number for the live sort
		 * and as written ({@code 8.4}, {@code ~3.1} for an estimate); 0 and empty for none.
		 */
		double rate;
		String rateText = "";
		/**
		 * T-216: luck and Skill so far at each of {@link #metricTicks}' points, 0 to 1, NaN where not known; empty for none
		 * (members not running Luck vs Skill have neither).
		 */
		float[] seriesLuck = new float[0];
		float[] seriesSkill = new float[0];
		/** T-216: the ticks of their points (a member's are {@link #seriesTicks}; yours are your updates'). */
		int[] metricTicks = new int[0];
		/**
		 * T-216: the members not running Luck vs Skill, one or a group: their damage and line are estimated from other
		 * players' splats, drawn solid but lighter, and said so on hover.
		 */
		boolean estimate;

		/** T-216: this row with its DPS. */
		PartyRow rate(double rate, String text)
		{
			this.rate = rate;
			this.rateText = text == null ? "" : text;
			return this;
		}

		/** T-216: this row with its luck and Skill over the fight. */
		PartyRow metrics(int[] ticks, float[] luck, float[] skill)
		{
			this.metricTicks = ticks == null ? new int[0] : ticks;
			this.seriesLuck = luck == null ? new float[0] : luck;
			this.seriesSkill = skill == null ? new float[0] : skill;
			return this;
		}

		/** T-216: whether it has any luck ({@code luck}) or Skill to chart. */
		boolean charts(SidePanelParty.Metric m)
		{
			final float[] v = m == SidePanelParty.Metric.LUCK ? seriesLuck : seriesSkill;
			for (int i = 0; i < v.length && i < metricTicks.length; i++)
			{
				if (!Float.isNaN(v[i]))
				{
					return true;
				}
			}
			return false;
		}

		/** T-216: the figure on the row's first column: DPS live, damage after. */
		String first()
		{
			return live && !rateText.isEmpty() ? rateText : dmg[0];
		}

		/** T-216: luck or Skill at {@code tick}, the last point at or before it; NaN for none. */
		double metricAt(SidePanelParty.Metric m, int tick)
		{
			final float[] v = m == SidePanelParty.Metric.LUCK ? seriesLuck : seriesSkill;
			double out = Double.NaN;
			for (int i = 0; i < metricTicks.length && i < v.length && metricTicks[i] <= tick; i++)
			{
				out = Float.isNaN(v[i]) ? out : v[i];
			}
			return out;
		}
		/** T-128: their Discord picture; null for none, when the colour swatch stands in. */
		java.awt.image.BufferedImage avatar;
		/** T-128: their Fight tab, for the member opened; null otherwise. */
		FightTab fight;
		/** T-128: {@link #fight} is live: the rest after their room. */
		boolean fightLive;

		/** T-128: this row with its picture and Fight tab. */
		PartyRow with(java.awt.image.BufferedImage avatar, FightTab fight, boolean fightLive)
		{
			this.avatar = avatar;
			this.fight = fight;
			this.fightLive = fightLive;
			return this;
		}

		PartyRow(long id, String name, boolean you, boolean left, Color colour, double fill, String[] dmg, String[] luck,
			String[] exec, Color luckColour, int[] seriesTicks, int[] seriesDamage, PartyBreakdown breakdown)
		{
			this.id = id;
			this.name = name;
			this.you = you;
			this.left = left;
			this.others = false;
			this.colour = colour;
			this.fill = fill;
			this.dmg = dmg;
			this.luck = luck;
			this.exec = exec;
			this.luckColour = luckColour;
			this.seriesTicks = seriesTicks;
			this.seriesDamage = seriesDamage;
			this.breakdown = breakdown;
			this.hint = null;
			this.hintShort = Collections.emptyList();
			this.tip = null;
			this.listed = false;
		}

		private PartyRow(long id, String name, String hint, List<String> hintShort, String tip, boolean listed,
			double fill, String[] dmg, int[][] series, Color colour)
		{
			this.id = id;
			this.name = name;
			this.you = false;
			this.left = false;
			this.others = true;
			this.colour = colour;
			this.fill = fill;
			this.dmg = dmg;
			this.luck = new String[]{"", ""};
			this.exec = new String[]{"", ""};
			this.luckColour = SidePanelStyle.MUTED;
			this.seriesTicks = series[0];
			this.seriesDamage = series[1];
			this.breakdown = null;
			this.hint = hint;
			this.hintShort = list(hintShort);
			this.tip = tip;
			this.listed = listed;
		}

		static PartyRow others(double fill, String[] dmg, int[][] series, Color colour)
		{
			return new PartyRow(OTHERS_ID, "others", null, null, SidePanelParty.OTHERS_NOTE, false, fill, dmg, series,
				colour);
		}

		/**
		 * T-167: others, taken as the damage of the members not running Luck vs Skill: one by name and picture, or two
		 * or more as {@code N members} ({@code id} {@link #OTHERS_ID}). Labelled {@code about}, its values muted as others'.
		 */
		static PartyRow about(long id, String name, java.awt.image.BufferedImage avatar, String tip, double fill,
			String[] dmg, int[][] series, Color colour)
		{
			final PartyRow r = new PartyRow(id, name, SidePanelParty.ABOUT, Collections.<String>singletonList(""), tip,
				false, fill, dmg, series, colour);
			r.avatar = avatar;
			return r;
		}

		/**
		 * T-216: the members not running Luck vs Skill in the fight: one by their picture and name ({@code id} theirs), or
		 * two or more as {@code N members} ({@link #OTHERS_ID}); "~" before the figures, what they are on hover. Its line is
		 * an estimate, drawn lighter.
		 */
		static PartyRow estimate(long id, String name, java.awt.image.BufferedImage avatar, String tip, double fill,
			String[] dmg, int[][] series, Color colour)
		{
			final PartyRow r = new PartyRow(id, name, null, Collections.<String>emptyList(), tip, false, fill, dmg, series,
				colour);
			r.avatar = avatar;
			r.estimate = true;
			return r;
		}

		/** T-167: a party member without figures, by name and picture: no bar, no values, no line on the chart. */
		static PartyRow listed(long id, String name, java.awt.image.BufferedImage avatar, String hint,
			List<String> hintShort, String tip)
		{
			final PartyRow r = new PartyRow(id, name, hint, hintShort, tip, true, 0, new String[]{"", "", ""},
				new int[][]{new int[0], new int[0]}, SidePanelParty.OTHERS);
			r.avatar = avatar;
			return r;
		}

		/** T-167: a person's row (a member, or others taken as one), not the plain others: it keeps a picture's place. */
		boolean person()
		{
			return !others || id != OTHERS_ID;
		}

		/** The meter's values for {@code m}. */
		String[] values(SidePanelParty.Metric m)
		{
			return m == SidePanelParty.Metric.LUCK ? luck : m == SidePanelParty.Metric.EXEC ? exec : dmg;
		}

		/** Damage at {@code tick}, read off the series: the last point at or before it. */
		int damageAt(int tick)
		{
			int d = 0;
			for (int i = 0; i < seriesTicks.length && seriesTicks[i] <= tick; i++)
			{
				d = seriesDamage[i];
			}
			return d;
		}
	}

	/** A member's breakdown: their place, attack rows, and Luck, Execution and damage taken as rows. */
	static final class PartyBreakdown
	{
		final String title;
		final String place;
		final List<PartyAttack> attacks;
		final List<Row> summary;

		PartyBreakdown(String title, String place, List<PartyAttack> attacks, List<Row> summary)
		{
			this.title = title;
			this.place = place;
			this.attacks = list(attacks);
			this.summary = list(summary);
		}
	}

	/** One attack row: its name, damage and share, a share bar, and two lines of figures. */
	static final class PartyAttack
	{
		final String name;
		final String right;
		final double fill;
		final String line1;
		final String line2;

		PartyAttack(String name, String right, double fill, String line1, String line2)
		{
			this.name = name;
			this.right = right;
			this.fill = fill;
			this.line1 = line1;
			this.line2 = line2;
		}
	}

	/** One of the trip's rooms (spec 3.3): texts only, nothing that points back at the room. */
	static final class TripRow
	{
		final String name;
		final boolean ended;
		final String clock;
		final Text chip;
		final Color chipFill;
		/** The boss's icon, a hiscores sprite, or {@link SidePanelBossIcons#NONE} (T-054). */
		final int icon;
		/** T-095: the kills it counts for in the trip's hint: a run's (T-089), else 1 for a kill. */
		final int kills;
		/**
		 * T-095: a run's {@code  ×12} after its name, kept when the name is cut short ({@code Abyssal de… ×12}); empty
		 * for a boss's room, or a run with no kill yet.
		 */
		final String suffix;
		/** T-102: an ended room the player died in (T-100): {@code (died)} in place of {@code (ended)}. */
		final boolean died;
		/** T-194: the luck chip's tooltip, its words; null for none. */
		String chipTip;

		TripRow(String name, boolean ended, String clock, Text chip, Color chipFill)
		{
			this(name, ended, clock, chip, chipFill, SidePanelBossIcons.NONE);
		}

		TripRow(String name, boolean ended, String clock, Text chip, Color chipFill, int icon)
		{
			this(name, ended, clock, chip, chipFill, icon, ended ? 0 : 1);
		}

		TripRow(String name, boolean ended, String clock, Text chip, Color chipFill, int icon, int kills)
		{
			this(name, ended, clock, chip, chipFill, icon, kills, "");
		}

		TripRow(String name, boolean ended, String clock, Text chip, Color chipFill, int icon, int kills, String suffix)
		{
			this(name, ended, clock, chip, chipFill, icon, kills, suffix, false);
		}

		TripRow(String name, boolean ended, String clock, Text chip, Color chipFill, int icon, int kills, String suffix,
			boolean died)
		{
			this.died = died;
			this.kills = kills;
			this.suffix = suffix == null ? "" : suffix;
			this.name = name;
			this.ended = ended;
			this.clock = clock;
			this.chip = chip;
			this.chipFill = chipFill;
			this.icon = icon;
		}
	}

	/** The Trip tab (spec 3.3): every section, null where it isn't shown. */
	static final class TripTab
	{
		final Header header;
		final List<Tile> tiles;
		final Parts execution;
		final Text roomsHint;
		final List<TripRow> rooms;
		final Rows taken;
		final Rows other;
		/** T-052: the trip's drains, what they added and the specials' share of luck; null for none. */
		final Rows drains;

		TripTab(Header header, List<Tile> tiles, Parts execution, Text roomsHint, List<TripRow> rooms, Rows taken,
			Rows other)
		{
			this(header, tiles, execution, roomsHint, rooms, taken, other, null);
		}

		TripTab(Header header, List<Tile> tiles, Parts execution, Text roomsHint, List<TripRow> rooms, Rows taken,
			Rows other, Rows drains)
		{
			this.drains = drains;
			this.header = header;
			this.tiles = tiles == null ? null : list(tiles);
			this.execution = execution;
			this.roomsHint = roomsHint;
			this.rooms = list(rooms);
			this.taken = taken;
			this.other = other;
		}

		/** As {@link FightTab#structure()}: which sections there are and how many rows each has. */
		/** T-095: whether its Luck and Execution leave its runs out, so the Execution card is the bosses' too. */
		boolean bossesOnly()
		{
			return tiles != null && !tiles.isEmpty() && SidePanelModel.LUCK_NO_RUNS.equals(tiles.get(0).label.text);
		}

		String structure()
		{
			return (tiles == null ? "" : bossesOnly() ? "Tb" : "T") + (execution == null ? "" : "|e" + execution.parts.size())
				+ "|r" + rooms.size()
				+ (taken == null ? "" : "|k" + taken.rows.size()) + (other == null ? "" : "|o" + other.rows.size())
				+ (drains == null ? "" : "|dr" + drains.rows.size());
		}
	}

	/** Which of the spec's Fight-tab states (section 6) this is. */
	enum State
	{
		/** 1: no fight this session. */
		EMPTY,
		/** 2: a fight is open and nothing has been thrown in its room yet. */
		PRE_ATTACK,
		/** 3: at least one attack, not ended. */
		LIVE,
		/** 4: ended and killed. */
		DEAD,
		/** 5: ended without a kill. */
		ENDED
	}

	/** One piece of text with its colour and type role. */
	static final class Text
	{
		static final Text NONE = new Text("", SidePanelStyle.VALUE, SidePanelStyle.Type.SMALL);

		final String text;
		final Color colour;
		final SidePanelStyle.Type type;

		Text(String text, Color colour, SidePanelStyle.Type type)
		{
			// T-201: only what the panel's fonts can draw (a minus sign as a hyphen, an arrow as "to").
			this.text = text == null ? "" : SidePanelStyle.drawable(text);
			this.colour = colour;
			this.type = type;
		}

		static Text small(String text, Color colour)
		{
			return new Text(text, colour, SidePanelStyle.Type.SMALL);
		}

		static Text title(String text, Color colour)
		{
			return new Text(text, colour, SidePanelStyle.Type.TITLE);
		}

		static Text body(String text, Color colour)
		{
			return new Text(text, colour, SidePanelStyle.Type.BODY);
		}

		boolean isEmpty()
		{
			return text.isEmpty();
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Text))
			{
				return false;
			}
			final Text t = (Text) o;
			return text.equals(t.text) && Objects.equals(colour, t.colour) && type == t.type;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(text, colour, type);
		}

		@Override
		public String toString()
		{
			return text;
		}
	}

	/** A label on the left, a value on the right. */
	static final class Row
	{
		final Text left;
		/** A word after the label in its own colour (T-044's {@code avoidable}); empty for none. */
		final Text hint;
		final Text right;
		/** T-109: what the row is, on hover (the room's damage and its sources); null for none. */
		final String tooltip;
		/** T-112: a group's subheading in Details or Damage taken (muted, no value), not a fact. */
		final boolean heading;
		/** T-112: a row under a subheading, set in a little. */
		final boolean indent;
		/**
		 * T-119: the right text's short forms, longest first ({@code 5 of 8}), drawn only where the full one would cut the
		 * label; empty for none.
		 */
		final List<String> rightShort;

		Row(Text left, Text right)
		{
			this(left, Text.NONE, right);
		}

		Row(Text left, Text hint, Text right)
		{
			this(left, hint, right, null);
		}

		Row(Text left, Text hint, Text right, String tooltip)
		{
			this(left, hint, right, tooltip, false, false);
		}

		Row(Text left, Text hint, Text right, String tooltip, boolean heading, boolean indent)
		{
			this(left, hint, right, tooltip, heading, indent, java.util.Collections.<String>emptyList());
		}

		Row(Text left, Text hint, Text right, String tooltip, boolean heading, boolean indent, List<String> rightShort)
		{
			this.rightShort = list(rightShort);
			this.left = left;
			this.hint = hint == null ? Text.NONE : hint;
			this.right = right;
			this.tooltip = tooltip;
			this.heading = heading;
			this.indent = indent;
		}

		/** T-112: this row set in under a subheading. */
		Row indented()
		{
			return new Row(left, hint, right, tooltip, heading, true, rightShort);
		}

		/** T-119: this row with short forms for its right text, longest first, for where the full one would cut the label. */
		Row withRightShort(String... forms)
		{
			return new Row(left, hint, right, tooltip, heading, indent, java.util.Arrays.asList(forms));
		}

		/** T-112: a group's subheading. */
		static Row heading(String title)
		{
			return new Row(Text.small(title, SidePanelStyle.MUTED), Text.NONE, Text.NONE, null, true, false);
		}

		@Override
		public String toString()
		{
			return left + (hint.isEmpty() ? "" : " [" + hint.text.trim() + "]") + " | " + right;
		}
	}

	/** A two-line list row: a name and a value, a sub-line and a chip (targets, rooms, the last fight). */
	static final class ListRow
	{
		final Text name;
		/** {@code (dead)} or {@code (ended)} after the name; empty for none. */
		final Text suffix;
		final Text value;
		final Text sub;
		/** The chip's word and colour role, or null for none. */
		final Text chip;
		final Color chipFill;
		/** T-173: a part of the row above it (the Wardens' phase 2): set in under it, with its tooltip. */
		boolean part;
		String tip;

		/** T-173: shorter forms of the name and the sub-line, longest first, where the full one doesn't fit; empty for none. */
		List<String> nameShort = Collections.emptyList();
		List<String> subShort = Collections.emptyList();

		/** T-173: as a part of the row above, with {@code tip} on hover. */
		ListRow part(String tip)
		{
			this.part = true;
			this.tip = tip;
			return this;
		}

		/** T-194: the row's tooltip, its luck in words; null leaves it without. */
		ListRow tip(String t)
		{
			this.tip = t;
			return this;
		}

		/** T-173: the name's and the sub-line's shorter forms. */
		ListRow shorter(List<String> name, List<String> sub)
		{
			this.nameShort = name == null ? Collections.<String>emptyList() : name;
			this.subShort = sub == null ? Collections.<String>emptyList() : sub;
			return this;
		}

		ListRow(Text name, Text suffix, Text value, Text sub, Text chip, Color chipFill)
		{
			this.name = name;
			this.suffix = suffix == null ? Text.NONE : suffix;
			this.value = value;
			this.sub = sub;
			this.chip = chip;
			this.chipFill = chipFill;
		}
	}

	static final class Header
	{
		final Text name;
		final Text suffix;
		final Text sub;
		final Text clock;
		/** The boss's icon before its name, a hiscores sprite, or {@link SidePanelBossIcons#NONE} (T-054). */
		final int icon;
		/** T-052: the target's tracked Defence while the fight runs, once a drain has landed; empty before. */
		final Text defence;
		/** T-072: sub-lines drawn instead when {@link #sub} doesn't fit, longest first: the first that fits; empty for none. */
		final List<String> subShort;

		Header(Text name, Text suffix, Text sub, Text clock)
		{
			this(name, suffix, sub, clock, SidePanelBossIcons.NONE);
		}

		Header(Text name, Text suffix, Text sub, Text clock, int icon)
		{
			this(name, suffix, sub, clock, icon, Text.NONE);
		}

		Header(Text name, Text suffix, Text sub, Text clock, int icon, Text defence)
		{
			this(name, suffix, sub, clock, icon, defence, null);
		}

		Header(Text name, Text suffix, Text sub, Text clock, int icon, Text defence, List<String> subShort)
		{
			this.subShort = subShort == null ? java.util.Collections.<String>emptyList() : list(subShort);
			this.name = name;
			this.suffix = suffix;
			this.sub = sub;
			this.clock = clock;
			this.icon = icon;
			this.defence = defence == null ? Text.NONE : defence;
		}
	}

	/** {@code ! Boss data unsure} and why. */
	static final class Banner
	{
		final String text;
		/** The plugin's own reason, for the tooltip. */
		final String reason;

		Banner(String text, String reason)
		{
			this.text = text;
			this.reason = reason;
		}
	}

	static final class Tile
	{
		final Text label;
		final Text value;
		final Text sub;
		/** T-063: the sub-line shown instead when {@link #sub} doesn't fit the tile ({@code 38 DoT}); null for none. */
		final String subShort;
		/** The 2 px top edge: Track, or the value's colour when it's coloured. */
		final Color edge;
		/** T-134: the tile's tooltip (the DPS tile's parts); null for none, or the tab's own (Luck's, Execution's). */
		final String tooltip;

		Tile(Text label, Text value, Text sub, Color edge)
		{
			this(label, value, sub, null, edge);
		}

		Tile(Text label, Text value, Text sub, String subShort, Color edge)
		{
			this(label, value, sub, subShort, edge, null);
		}

		Tile(Text label, Text value, Text sub, String subShort, Color edge, String tooltip)
		{
			this.label = label;
			this.value = value;
			this.sub = sub;
			this.subShort = subShort;
			this.edge = edge;
			this.tooltip = tooltip;
		}
	}

	/** One execution part: its label and figure above a 5 px bar of kept and lost. */
	static final class Part
	{
		final Text label;
		final Text value;
		/** The kept share, 0 to 1; NaN draws an empty track. */
		final double kept;
		final Color lost;
		/** T-196: under the bar, what the part cost in plain words ({@code 0.6 s standing still}); null for no line. */
		Text note;

		Part(Text label, Text value, double kept, Color lost)
		{
			this.label = label;
			this.value = value;
			this.kept = kept;
			this.lost = lost;
		}
	}

	/** What a stretch of the time budget's bar is filled with (spec section 2, fills). */
	enum Fill
	{
		KEPT,
		COST,
		MINOR_COST,
		LUCK_SLOWER,
		LUCK_AVERAGE,
		UNSCORED,
		/** Time spent eating (T-038): neutral, neither kept nor a cost. */
		EATING,
		/** T-081: time a boss's mechanic forced (T-074): neutral, neither kept nor a cost, apart from eating. */
		MECHANICS,
		/**
		 * The time luck saved (T-042): the bar's last stretch, past your clock, best play dimmed under luck's stripes. T-214:
		 * a segment of its own, so the bar and its legend list the same things in the same order.
		 */
		LUCK_FASTER;

		/** T-214: a stretch skill lost (idle, avoidable eating, the setup parts): the bar's orange, the folded line's "skill". */
		boolean skill()
		{
			return this == COST || this == MINOR_COST;
		}

		/** T-214: luck's stretch, in luck's violet. */
		boolean luck()
		{
			return this == LUCK_SLOWER || this == LUCK_AVERAGE || this == LUCK_FASTER;
		}
	}

	static final class Segment
	{
		final double seconds;
		final Fill fill;
		/**
		 * T-214: whether the legend lists it. A listed stretch too thin to see is still drawn 1 px wide, in its place; an
		 * unlisted one (a part under a tick, under 1 damage) is drawn at its own width only.
		 */
		final boolean listed;

		Segment(double seconds, Fill fill)
		{
			this(seconds, fill, true);
		}

		Segment(double seconds, Fill fill, boolean listed)
		{
			this.seconds = seconds;
			this.fill = fill;
			this.listed = listed;
		}
	}

	/** A row under the time budget's bar: a swatch, a label with an optional hint, the seconds. */
	static final class BudgetRow
	{
		final Fill swatch;
		final Text label;
		final Text hint;
		final Text value;
		/** T-118: on hover, a setup part's other unit (T-123: its ticks, now it reads damage); null for none. */
		final String tooltip;
		/** T-123: the value's short forms, longest first, for where the whole one would cut the label: {@code -16}. */
		final List<String> valueShort;

		BudgetRow(Fill swatch, Text label, Text hint, Text value)
		{
			this(swatch, label, hint, value, null);
		}

		BudgetRow(Fill swatch, Text label, Text hint, Text value, String tooltip)
		{
			this(swatch, label, hint, value, tooltip, Collections.emptyList());
		}

		private BudgetRow(Fill swatch, Text label, Text hint, Text value, String tooltip, List<String> valueShort)
		{
			this.swatch = swatch;
			this.label = label;
			this.hint = hint == null ? Text.NONE : hint;
			this.value = value;
			this.tooltip = tooltip;
			this.valueShort = valueShort;
		}

		/** T-123: this row with the value's short forms. */
		BudgetRow withValueShort(String... forms)
		{
			return new BudgetRow(swatch, label, hint, value, tooltip, Arrays.asList(forms));
		}
	}

	/** "Where the time went", after the fight only (spec sections 3.1 and 4). */
	static final class TimeBudget
	{
		final List<Segment> segments;
		/** The bar's whole length in seconds: T + max(0, L). */
		final double total;
		/** The clock T: where the tick for your real time sits, and the time luck saved starts. */
		final double clock;
		/** Seconds the rolls saved (the dimmed, striped stretch past the clock), 0 for none. */
		final double luckSaved;
		/**
		 * T-214: luck's stripes on the bar and its swatch, saved or cost: luck's violet in its roll's strength (T-194), never
		 * skill's orange or Better's green.
		 */
		final Color luckStripe;
		/** The tick's label: your clock, as the title shows it ({@code 1:16}). */
		final String clockLabel;
		/** The bar's end when luck saved time ({@code 1:31 at average luck}); null when it didn't. */
		final String endLabel;
		/** Your average's clock, for the dashed marker; NaN for none. */
		final double marker;
		final List<BudgetRow> rows;
		/** Your average and luck removed against your usual; empty for none. */
		final List<Row> average;
		/** One line in the average's place ("not a kill, so no average to compare"), or null. */
		final String note;

		TimeBudget(List<Segment> segments, double total, double clock, double luckSaved, Color luckStripe,
			String clockLabel, String endLabel, double marker, List<BudgetRow> rows, List<Row> average, String note)
		{
			this.segments = list(segments);
			this.total = total;
			this.clock = clock;
			this.luckSaved = luckSaved;
			this.luckStripe = luckStripe;
			this.clockLabel = clockLabel;
			this.endLabel = endLabel;
			this.marker = marker;
			this.rows = list(rows);
			this.average = list(average);
			this.note = note;
		}

		/**
		 * T-214: the seconds the bar draws as skill's (idle, avoidable eating, the setup parts): the folded line's "skill".
		 * The same as {@code SidePanelModel.lostSeconds}: the setup parts split the setup's seconds exactly.
		 */
		double skillSeconds()
		{
			double s = 0;
			for (Segment seg : segments)
			{
				if (seg.fill.skill() && seg.seconds > 0)
				{
					s += seg.seconds;
				}
			}
			return s;
		}

		/** What the segments add up to: the clock when luck cost time, T + L when it saved some. */
		double sum()
		{
			double s = 0;
			for (Segment seg : segments)
			{
				s += seg.seconds;
			}
			return s;
		}
	}

	/** The luck curve after the fight: where this fight sits among fights with the same attacks. */
	static final class Curve
	{
		/** (A - E) / √V, clamped to ±2.9 for the marker. */
		final double z;
		final Text headerValue;
		/** T-201: the band explained, the hits against average and what the width is, on hover; null for none. */
		final String tooltip;

		Curve(double z, Text headerValue, String tooltip)
		{
			this.z = z;
			this.headerValue = headerValue;
			this.tooltip = tooltip;
		}
	}

	/** A damage source row (thrall, DoT, reflect now; weapons from T-036), with its share bar. */
	static final class Source
	{
		final String name;
		final String sub;
		final String damage;
		final String share;
		final double shareFraction;
		final Color colour;
		/** The row's icon: an item's image ({@code ItemManager}), else a sprite ({@code SpriteManager}); -1 for none. */
		final int itemId;
		final int spriteId;
		/**
		 * T-085: the sub-line's shorter forms, longest first; empty for none. A weapon row's has three, and the card draws
		 * every weapon row in the same form, the longest that fits them all.
		 */
		final List<String> subShort;
		/** T-085: the row's details on hover (attacks, accuracy, hits, expected), or null. */
		final String tooltip;
		/** T-085: the row's DPS and expected DPS ({@code 6.1}, {@code 7.1}), or null: for tests. */
		final String dps;
		final String expectedDps;

		Source(String name, String sub, String damage, String share, double shareFraction, Color colour)
		{
			this(name, sub, damage, share, shareFraction, colour, -1, -1);
		}

		Source(String name, String sub, String damage, String share, double shareFraction, Color colour, int itemId,
			int spriteId)
		{
			this(name, sub, null, damage, share, shareFraction, colour, itemId, spriteId, null, null, null);
		}

		Source(String name, String sub, List<String> subShort, String damage, String share, double shareFraction,
			Color colour, int itemId, int spriteId, String tooltip, String dps, String expectedDps)
		{
			this.name = name;
			this.sub = sub;
			this.subShort = subShort == null ? Collections.<String>emptyList() : subShort;
			this.damage = damage;
			this.share = share;
			this.shareFraction = shareFraction;
			this.colour = colour;
			this.itemId = itemId;
			this.spriteId = spriteId;
			this.tooltip = tooltip;
			this.dps = dps;
			this.expectedDps = expectedDps;
		}
	}

	/**
	 * The damage chart's and tick strip's data for one tick (T-036; spec §3.1 and
	 * §5.1): the series from index {@link #from} to {@link #length}, as the
	 * panel's copy of them is extended a tick at a time. {@code from} 0 means a
	 * fresh scope: start over.
	 */
	static final class Chart
	{
		final int from;
		final int length;
		/** Cumulative weapon damage, expected damage and its variance, per tick from {@link #from}. */
		final float[] damage;
		final float[] expected;
		final float[] variance;
		/** {@link FightTimeline}'s tick states from {@link #from}. */
		final byte[] states;
		/** The style of the attack on each tick from {@link #from}: {@code FightTimeline.Style} ordinal + 1, 0 for none. */
		final byte[] styles;
		/** The actual line's colour: luck's, white when neutral. */
		final Color actual;
		/** False under the stats warning: no expected line or range ("expected hidden"). */
		final boolean expectedShown;
		/** False when the spread isn't known: no range. */
		final boolean rangeShown;
		/** {@code 0:00} and the clock, under the chart. */
		final String end;
		final String youLegend;
		final String expectedLegend;
		/** The strip's legend counts; -1 for no entry (T-095: a run's idle ticks aren't lost or eating). */
		final int lost;
		final int eating;
		/** T-081: the ticks a boss's mechanic forced (T-074); 0 for a boss without allowances, or an old kill. */
		final int mechanics;
		/** The colour most attacks were in, for the legend's "attack" swatch. */
		final Color attackColour;

		Chart(int from, int length, float[] damage, float[] expected, float[] variance, byte[] states, byte[] styles,
			Color actual, boolean expectedShown, boolean rangeShown, String end, String youLegend, String expectedLegend,
			int lost, int eating, Color attackColour)
		{
			this(from, length, damage, expected, variance, states, styles, actual, expectedShown, rangeShown, end, youLegend,
				expectedLegend, lost, eating, 0, attackColour);
		}

		Chart(int from, int length, float[] damage, float[] expected, float[] variance, byte[] states, byte[] styles,
			Color actual, boolean expectedShown, boolean rangeShown, String end, String youLegend, String expectedLegend,
			int lost, int eating, int mechanics, Color attackColour)
		{
			this.from = from;
			this.length = length;
			this.damage = damage;
			this.expected = expected;
			this.variance = variance;
			this.states = states;
			this.styles = styles;
			this.actual = actual;
			this.expectedShown = expectedShown;
			this.rangeShown = rangeShown;
			this.end = end;
			this.youLegend = youLegend;
			this.expectedLegend = expectedLegend;
			this.lost = lost;
			this.eating = eating;
			this.mechanics = mechanics;
			this.attackColour = attackColour;
		}
	}

	/** The loadout card before the first attack. */
	static final class Loadout
	{
		final Text name;
		final List<Row> rows;
		/** The spec weapon's name, or null for none. */
		final Text specName;
		final List<Row> specRows;

		Loadout(Text name, List<Row> rows, Text specName, List<Row> specRows)
		{
			this.name = name;
			this.rows = list(rows);
			this.specName = specName;
			this.specRows = list(specRows);
		}
	}

	/** The empty state's text and which tools can be used. */
	static final class Empty
	{
		final String history;
		final boolean hasHistory;

		Empty(String history, boolean hasHistory)
		{
			this.history = history;
			this.hasHistory = hasHistory;
		}
	}

	/** A titled section's header hint and its rows. */
	static final class Rows
	{
		final Text hint;
		final List<Row> rows;

		Rows(Text hint, List<Row> rows)
		{
			this.hint = hint == null ? Text.NONE : hint;
			this.rows = list(rows);
		}
	}

	/** A titled list of two-line rows. */
	static final class ListRows
	{
		final Text hint;
		final List<ListRow> rows;

		ListRows(Text hint, List<ListRow> rows)
		{
			this.hint = hint == null ? Text.NONE : hint;
			this.rows = list(rows);
		}
	}

	/** The parts and the damage rows, each under a header with a value. */
	static final class Parts
	{
		final Text hint;
		final List<Part> parts;
		/** T-126: the Execution card's and tile's tooltip, its sum ({@code 100% − uptime 0.6% … = 95%}); null for none. */
		final String tooltip;

		Parts(Text hint, List<Part> parts)
		{
			this(hint, parts, null);
		}

		Parts(Text hint, List<Part> parts, String tooltip)
		{
			this.hint = hint;
			this.parts = list(parts);
			this.tooltip = tooltip;
		}
	}

	static final class Sources
	{
		final Text hint;
		final List<Source> rows;
		/** T-199: the header's hover (what's against expected, and the thrall and other damage outside it); null for none. */
		final String tooltip;

		Sources(Text hint, List<Source> rows)
		{
			this(hint, rows, null);
		}

		Sources(Text hint, List<Source> rows, String tooltip)
		{
			this.hint = hint;
			this.rows = list(rows);
			this.tooltip = tooltip;
		}

	}

	/** The Fight tab: every section, null where it isn't shown. */
	static final class FightTab
	{
		final State state;
		/** The room/raid switch's choice, or null outside a raid. */
		final Boolean wholeRaid;
		final Header header;
		final Banner banner;
		final String unscoredNote;
		final ListRows targets;
		final ListRows rooms;
		final TimeBudget budget;
		final List<Tile> tiles;
		final Curve curve;
		/** The curve's place when the spread wasn't kept (fights saved before T-028); null otherwise. */
		final String noCurve;
		/** The Luck header's value beside {@link #noCurve}: the seconds, in luck's colour. */
		final Text noCurveHint;
		final Parts execution;
		final Sources damage;
		/** The damage chart and tick strip (T-036); null with no in-memory data or with charts off. */
		final Chart chart;
		/** The "later" card in the damage rows' place, for a saved kill from before this session (T-034); null for none. */
		final String later;
		final Rows taken;
		/** T-052: the Defence card after a fight with tracked drains; null for none. */
		final Defence defence;
		final Loadout loadout;
		final ListRow lastFight;
		final Empty empty;
		/**
		 * T-099: the Damage card's Boss, Adds and All views, in that order, in a room with a boss and adds that took
		 * damage; null for no switch. {@link #damage} and {@link #chart} are All's until {@link #showing} picks one.
		 */
		final List<TargetView> byTarget;
		/** T-099: which of {@link #byTarget} {@link #damage} and {@link #chart} are; -1 before one is picked. */
		final int target;
		/**
		 * T-188: the kill card in place of the title, live and after the kill: a room's (T-202, whatever Celebrations
		 * is); null for a whole raid, a run of monsters and wherever the tab has no fight on show.
		 */
		final KillCard card;
		/**
		 * T-188: each section's one line while folded, by its id ({@code damage}, {@code curve}, {@code budget},
		 * {@code execution}, {@code taken}); T-202: in every view but the empty and pre-attack ones.
		 */
		final java.util.Map<String, Text> summaries;
		/** T-196: the clarity pass's sections (T-202: every fighting view's, whatever Celebrations is). */
		final Clear clear;

		FightTab(Builder b)
		{
			state = b.state;
			wholeRaid = b.wholeRaid;
			header = b.header;
			banner = b.banner;
			unscoredNote = b.unscoredNote;
			targets = b.targets;
			rooms = b.rooms;
			budget = b.budget;
			tiles = b.tiles == null ? null : list(b.tiles);
			curve = b.curve;
			noCurve = b.noCurve;
			noCurveHint = b.noCurveHint;
			execution = b.execution;
			damage = b.damage;
			chart = b.chart;
			later = b.later;
			taken = b.taken;
			defence = b.defence;
			loadout = b.loadout;
			lastFight = b.lastFight;
			empty = b.empty;
			byTarget = b.byTarget == null ? null : list(b.byTarget);
			target = -1;
			card = b.card;
			summaries = b.summaries == null ? Collections.<String, Text>emptyMap()
				: Collections.unmodifiableMap(new java.util.LinkedHashMap<>(b.summaries));
			clear = b.clear;
		}

		/** T-099: this tab with the Damage card and chart of {@code byTarget}'s view {@code i}. */
		private FightTab(FightTab t, int i)
		{
			state = t.state;
			wholeRaid = t.wholeRaid;
			header = t.header;
			banner = t.banner;
			unscoredNote = t.unscoredNote;
			targets = t.targets;
			rooms = t.rooms;
			budget = t.budget;
			tiles = t.tiles;
			curve = t.curve;
			noCurve = t.noCurve;
			noCurveHint = t.noCurveHint;
			execution = t.execution;
			damage = t.byTarget.get(i).damage;
			chart = t.byTarget.get(i).chart;
			later = t.later;
			taken = t.taken;
			defence = t.defence;
			loadout = t.loadout;
			lastFight = t.lastFight;
			empty = t.empty;
			byTarget = t.byTarget;
			target = i;
			card = t.card;
			summaries = t.summaries;
			clear = t.clear;
		}

		/** T-099: the tab as it shows with view {@code i} of the switch picked; itself without a switch. */
		FightTab showing(int i)
		{
			return byTarget == null ? this : new FightTab(this, Math.max(0, Math.min(i, byTarget.size() - 1)));
		}

		/**
		 * What decides the tab's components: which sections there are and how
		 * many rows each has. Two snapshots with the same structure differ only in
		 * texts, colours and painted figures, so Swing's side updates the
		 * components it has rather than building new ones (spec section 5.1).
		 */
		String structure()
		{
			final StringBuilder s = new StringBuilder(state.name());
			s.append(wholeRaid == null ? "" : wholeRaid ? "|raid" : "|room");
			s.append(header == null ? "" : "|h");
			// T-188: the card, and the rows that change its height.
			s.append(clear == null ? "" : "|C" + clear.structure());
			s.append(card == null ? "" : "|K" + card.structure());
			// Whether the header has an icon's box (T-054); which icon is a value, set in place.
			s.append(header == null || header.icon == SidePanelBossIcons.NONE ? "" : "i");
			// T-052: the live defence line, from the first drain that lands.
			s.append(header == null || header.defence.isEmpty() ? "" : "d");
			s.append(banner == null ? "" : "|w");
			s.append(unscoredNote == null ? "" : "|u");
			s.append(targets == null ? "" : "|t" + targets.rows.size());
			s.append(rooms == null ? "" : "|r" + rooms.rows.size());
			if (budget != null)
			{
				s.append("|b").append(budget.rows.size()).append('.').append(budget.average.size())
					.append(budget.note == null ? "" : "n");
			}
			s.append(tiles == null ? "" : "|T");
			s.append(curve == null ? "" : "|c");
			s.append(noCurve == null ? "" : "|nc");
			s.append(execution == null ? "" : "|e" + execution.parts.size());
			// T-099: the switch, and which view it shows: a click on it is a structure change, nothing else is.
			s.append(byTarget == null ? "" : "|sw" + target);
			s.append(damage == null ? "" : "|d" + damage.rows.size());
			s.append(later == null ? "" : "|lt");
			s.append(chart == null ? "" : chart.expectedShown ? "|ch" : "|chx");
			s.append(chart != null && chart.rangeShown ? "r" : "");
			s.append(taken == null ? "" : "|k" + taken.rows.size());
			s.append(defence == null ? "" : "|df" + defence.structure());
			if (loadout != null)
			{
				s.append("|l").append(loadout.rows.size()).append('.').append(loadout.specName == null ? 0
					: loadout.specRows.size());
			}
			s.append(lastFight == null ? "" : "|L");
			s.append(empty == null ? "" : "|E");
			return s.toString();
		}
	}

	/**
	 * T-099: one view of the Damage card's switch: its label, its rows (null with nothing scored in it, as the
	 * Zulrah's snakelings), its chart (null likewise, or with charts off). T-105: {@code note}, the switch's tooltip:
	 * the damage and time on that target set, and that Luck and Execution cover the whole kill; {@code empty}, the
	 * card's one line when it has no rows, else null.
	 */
	static final class TargetView
	{
		final String label;
		final Sources damage;
		final Chart chart;
		final String note;
		final String empty;

		TargetView(String label, Sources damage, Chart chart, String note)
		{
			this(label, damage, chart, note, null);
		}

		TargetView(String label, Sources damage, Chart chart, String note, String empty)
		{
			this.label = label;
			this.damage = damage;
			this.chart = chart;
			this.note = note;
			this.empty = empty;
		}

		/** T-196: this view with its damage rows reworded. */
		TargetView withDamage(Sources d)
		{
			return new TargetView(label, d, chart, note, empty);
		}
	}

	/** Collects a Fight tab's sections; every field starts out as "not shown". */
	/**
	 * T-052: the Defence card after a fight with tracked drains: the path and
	 * floor, the specials thrown and landed, what the drains added, and the
	 * specials' share of luck.
	 */
	static final class Defence
	{
		/** The section's value: Defence start → end. */
		final Text hint;
		/** T-076: the facts, short labels and figures: each special's landings, the floor where there is one, Magic, party drains. */
		final List<Row> rows;
		/** T-050's drain value: {@code Damage added +42 · 5 s faster}. Null when unknown. */
		final Figure worth;
		/** T-050's spec luck: {@code Specials' luck +12 · 1 s faster}, in Better or Cost. Null without own specials. */
		final Figure specLuck;
		/** T-076: the drop for a bar, base to now with the floor; null when the levels aren't known. */
		final Drop drop;
		/**
		 * T-142 (Eric's pick, A): the Drains card's blocks, one per drained stat, each with its specials under it; null
		 * for a kill saved before T-141, which shows the card above ({@link #rows}, {@link #drop}).
		 */
		final List<DrainBlock> blocks;

		Defence(Text hint, List<Row> rows, Figure worth, Figure specLuck, Drop drop)
		{
			this(hint, rows, worth, specLuck, drop, null);
		}

		Defence(Text hint, List<Row> rows, Figure worth, Figure specLuck, Drop drop, List<DrainBlock> blocks)
		{
			this.hint = hint == null ? Text.NONE : hint;
			this.rows = list(rows);
			this.worth = worth;
			this.specLuck = specLuck;
			this.drop = drop;
			this.blocks = blocks == null ? null : list(blocks);
		}

		String structure()
		{
			final StringBuilder b = new StringBuilder();
			if (blocks != null)
			{
				b.append("B");
				for (DrainBlock k : blocks)
				{
					b.append('[').append(k.structure()).append(']');
				}
			}
			return b + (rows.size() + (worth == null ? "" : "w") + (specLuck == null ? "" : "s") + (drop == null ? ""
				: "b"));
		}
	}

	/**
	 * T-142: one drained stat's block on the Drains card: its name and change ({@code Defence  225 → 191}, {@code Magic
	 * defence  −93}), a bar from its base with the floor where it has one, then its lines: each special (what it added,
	 * then its landings and what it took off), drains it already had, its regen, and which of your attacks it helped.
	 */
	static final class DrainBlock
	{
		final Text name;
		/** {@code  at its floor}, or none. */
		final Text hint;
		final Text change;
		/** Null for none: magic defence, or a stat no drain changed. */
		final Drop bar;
		final List<BlockLine> lines;

		DrainBlock(Text name, Text hint, Text change, Drop bar, List<BlockLine> lines)
		{
			this.name = name;
			this.hint = hint == null ? Text.NONE : hint;
			this.change = change;
			this.bar = bar;
			this.lines = list(lines);
		}

		String structure()
		{
			final StringBuilder b = new StringBuilder(bar == null ? "" : "b");
			for (BlockLine l : lines)
			{
				b.append(l.level);
			}
			return b.toString();
		}
	}

	/** T-142: one line of a block, set in by {@code level} (1 under the stat, 2 under a special), its label's short forms. */
	static final class BlockLine
	{
		final Row row;
		final int level;
		/** Shorter forms of the label, longest first, drawn where the whole one doesn't fit; empty for none. */
		final List<String> leftShort;

		BlockLine(Row row, int level, List<String> leftShort)
		{
			this.row = row;
			this.level = level;
			this.leftShort = leftShort == null ? java.util.Collections.<String>emptyList() : list(leftShort);
		}
	}

	/** T-076: one of the Defence card's figures, in damage and in seconds. */
	static final class Figure
	{
		final String label;
		/** {@code +42}. */
		final String damage;
		/** {@code 5 s faster}, {@code about even}; empty where it rounds to nothing, or where it's the tooltip's (T-119). */
		final String seconds;
		final Color colour;
		/** T-119: what the figure is worth on the clock, on hover; null for none. */
		final String tooltip;

		Figure(String label, String damage, String seconds, Color colour)
		{
			this(label, damage, seconds, colour, null);
		}

		Figure(String label, String damage, String seconds, Color colour, String tooltip)
		{
			this.label = label;
			this.damage = damage;
			this.seconds = seconds == null ? "" : seconds;
			this.colour = colour;
			this.tooltip = tooltip;
		}

		/** As a row: {@code Drains added    +42 dmg}, its time on hover (T-119). */
		Row row()
		{
			return new Row(Text.small(label, SidePanelStyle.BODY), Text.NONE,
				Text.small(seconds.isEmpty() ? damage : damage + " · " + seconds, colour), tooltip);
		}

		@Override
		public String toString()
		{
			return row().toString();
		}
	}

	/** T-076: a Defence drop for the bar: levels, with -1 where unknown, and the bar's line. */
	static final class Drop
	{
		final int start;
		final int end;
		final int base;
		/** 0 for none. */
		final int floor;
		final Text label;
		final Text value;

		Drop(int start, int end, int base, int floor, Text label, Text value)
		{
			this.start = start;
			this.end = end;
			this.base = base;
			this.floor = floor;
			this.label = label;
			this.value = value;
		}
	}

	/**
	 * T-196: the clarity pass's sections under the card (Eric approved the canvas's top row): Damage with its second by
	 * second chart and the boss's healing, Luck with its hits, Your play (was Execution) with its attack rhythm, and a
	 * small picture before each folded section's line. Details, Damage over time and Ticks are merged into these.
	 */
	static final class Clear
	{
		/** In Damage, only when the boss healed: {@code Boss healed  12}; null for none. */
		final Row healed;
		/** In Luck: {@code hits that landed  30 (average 29.6)}, {@code damage per landed hit  30 (average 27)}. */
		final List<Row> luckHits;
		/** A folded section's picture before its line, by its id. */
		final java.util.Map<String, Mark> marks;

		Clear(Row healed, List<Row> luckHits, java.util.Map<String, Mark> marks)
		{
			this.healed = healed;
			this.luckHits = list(luckHits);
			this.marks = marks == null ? Collections.<String, Mark>emptyMap()
				: Collections.unmodifiableMap(new java.util.LinkedHashMap<>(marks));
		}

		String structure()
		{
			return (healed == null ? "" : "h") + luckHits.size() + marks.keySet();
		}
	}

	/**
	 * T-196: a folded section's small picture: Luck's mini 1 to 100 track with the roll, Where the time went's bar, Your
	 * play's grade chip. T-214: the time's bar is the open section's, its segments, order and colours, drawn small.
	 */
	static final class Mark
	{
		enum Kind
		{
			LUCK, TIME, GRADE, SHARES
		}

		final Kind kind;
		final int roll;
		/** TIME (T-214): the open section's bar, drawn by the same painter at 6 px. */
		final TimeBudget budget;
		/** TIME: whether luck saved time (its stretch past the clock). */
		final boolean luckSaved;
		/** GRADE: the letter and its colour. */
		final String grade;
		final Color colour;
		/** SHARES (T-198): Damage's share bar in small, each source's share and colour, biggest first. */
		final double[] shares;
		final Color[] colours;
		/**
		 * T-199 (Eric: "why are there two numbers, one plus and one minus, with no labels?"): the summary's words in
		 * coloured runs, painted in place of its one-colour line ({@code skill −1.1 s} orange, {@code luck +10 s} violet);
		 * empty for the plain line.
		 */
		final Text[] runs;

		private Mark(Kind kind, int roll, TimeBudget budget, String grade, Color colour)
		{
			this(kind, roll, budget, grade, colour, new double[0], new Color[0]);
		}

		private Mark(Kind kind, int roll, TimeBudget budget, String grade, Color colour, double[] shares, Color[] colours)
		{
			this(kind, roll, budget, grade, colour, shares, colours, new Text[0]);
		}

		private Mark(Kind kind, int roll, TimeBudget budget, String grade, Color colour, double[] shares, Color[] colours,
			Text[] runs)
		{
			this.runs = runs;
			this.shares = shares;
			this.colours = colours;
			this.kind = kind;
			this.roll = roll;
			this.budget = budget;
			this.luckSaved = budget != null && budget.luckSaved > 0;
			this.grade = grade;
			this.colour = colour;
		}

		/** T-199: this picture with its summary in coloured runs. */
		Mark withRuns(Text... runs)
		{
			return new Mark(kind, roll, budget, grade, colour, shares, colours, runs.clone());
		}

		static Mark luck(int roll)
		{
			return new Mark(Kind.LUCK, roll, null, null, null);
		}

		/** T-214: the open section's bar, small. */
		static Mark time(TimeBudget budget)
		{
			return new Mark(Kind.TIME, -1, budget, null, null);
		}

		static Mark grade(String grade, Color colour)
		{
			return new Mark(Kind.GRADE, -1, null, grade, colour);
		}

		/** T-198: Damage's sources, biggest first. */
		static Mark shares(List<Source> rows)
		{
			final List<Source> shown = new ArrayList<>();
			for (Source r : rows)
			{
				if (r.shareFraction > 0)
				{
					shown.add(r);
				}
			}
			shown.sort((a, b) -> Double.compare(b.shareFraction, a.shareFraction));
			final double[] f = new double[shown.size()];
			final Color[] c = new Color[shown.size()];
			for (int i = 0; i < f.length; i++)
			{
				f[i] = shown.get(i).shareFraction;
				c[i] = shown.get(i).colour;
			}
			return new Mark(Kind.SHARES, -1, null, null, null, f, c);
		}
	}

	/**
	 * T-188 (Eric, 2026-11-09: "let's choose A for all of them"), redrawn by T-196's clarity pass (Eric approved,
	 * 21:5x) and T-198 (after Eric played it: "too wordy"): the kill card, in place of the Fight tab's title and four
	 * tiles. Glance first, words on hover: three tiles, Time, Skill and Luck, each a number with its scale and one short
	 * line, every explanation in its tooltip; the badges as icon chips. Live: the clock, skill so far with your
	 * last 10 attacks (T-214), luck's word so far. Ticks are written as seconds throughout. Painted at the height its blocks need ({@code SidePanelKillCard}); which blocks it has is
	 * {@link #structure()}.
	 *
	 * <p>The header is fixed at construction; the blocks are set once by {@code SidePanelModel.card} on the client
	 * thread, before the snapshot is handed to Swing's, and never after.
	 */
	static final class KillCard
	{
		final boolean live;
		/**
		 * T-202: whether the kill-end moment plays (Celebrations): the die rolling to its number and the chips flashing,
		 * once, at the kill. Off, the card shows settled; nothing else changes.
		 */
		boolean moment = true;
		final String boss;
		/** The boss's colour ({@link BossColours}): the top edge, the name and a NEW PB. */
		final Color tint;
		/** The boss's hiscores sprite, or {@link SidePanelBossIcons#NONE}. */
		final int icon;
		/** {@code 1,020 HP · solo · 3:41 pm}, and its shorter forms. */
		final String sub;
		final List<String> subShort;
		/** Top right: {@code kill 555} after a kill, {@code live} while it runs. */
		final String corner;
		final String clock;

		// T-198 (Eric, 23:53: "too wordy"): glance first, words on hover. Three tiles, Time, Skill and Luck, each a number
		// and a short second line; every explanation is its tile's tooltip.

		// ---- Time ----
		/** After: your usual ({@code 1:34}), the median of your last like kills; null without. */
		String usual;
		/** A new fastest kill: the gold PB chip on the Time tile. */
		boolean newPb;
		/** Your best before this kill ({@code 1:05}) and the rank ({@code 42nd fastest of 199}), for the tooltip. */
		String best;
		String rank;
		String timeTip;

		// ---- Skill ----
		/** S to C after the kill; null live and before the record has it. */
		String grade;
		Color gradeColour = SidePanelStyle.VALUE;
		/** {@code 99%}: after the kill of a perfect fight, live the uptime so far; null when unknown. */
		String skill;
		/** {@code −1.1 s} in orange; null when nothing was lost. */
		String lost;
		String skillTip;
		/**
		 * T-214 (Eric's pick, A): live, your last {@link #CELLS} attacks' delays in ticks, oldest first: 0 on time, 1 a tick
		 * late, 2 or more. Idle ticks only: eating and a mechanic's excused ticks aren't late. Empty before the first; null
		 * without the fight's timeline.
		 */
		int[] attacks;

		// ---- T-199: DPS ----
		/**
		 * T-199 (Eric: "Why did we remove expected dps?"): the old DPS tile as a line, live and after the kill: your DPS
		 * ({@code 5.9}, all your damage over the scored clock) and the expected ({@code 5.3}); each null when unknown or
		 * hidden. The parts on hover.
		 */
		String dps;
		String expDps;
		String dpsTip;

		// ---- Luck ----
		/** After: rolled 1 to 100; -1 live and when unknown. */
		int rolled = -1;
		/** After: luck's time, {@code +10 s} or {@code −9 s}, in violet; null without. */
		String luckTime;
		/** Live: one word, its strength so far ({@code average}) in violet; or a muted note ({@code no spread}). */
		Text luckWord = Text.NONE;
		/**
		 * T-199 (Eric: "Luck bar should be drawn as well during the live fight"): live, where luck so far sits on the 1 to
		 * 100 track, marked with no number (the roll is the kill's); 0 for none.
		 */
		int luckAt;
		/**
		 * T-201: luck's percentile, 0 to 1: after the kill the kill's, live luck so far; NaN when unknown. The card writes
		 * it as its band ({@code top 19%}) and marks it on the track.
		 */
		double luckP = Double.NaN;
		String luckTip;
		/** T-194: the roll's words; null for none. */
		String luckWords;

		/** T-189: the badges earned, only after a kill and only when earned; empty for none. */
		List<Badge> badges = Collections.emptyList();

		/** T-214: the live Skill tile's cells, one an attack. */
		static final int CELLS = 10;

		KillCard(boolean live, String boss, Color tint, int icon, String sub, List<String> subShort, String corner,
			String clock)
		{
			this.live = live;
			this.boss = boss;
			this.tint = tint;
			this.icon = icon;
			this.sub = sub;
			this.subShort = list(subShort);
			this.corner = corner;
			this.clock = clock;
		}

		/** T-189: this card with its badges. */
		KillCard withBadges(List<Badge> b)
		{
			badges = list(b);
			return this;
		}

		/** Live or after, and what takes room: the cells live, the badges after. Every other line is always there. */
		String structure()
		{
			return live ? "l" + (attacks == null ? "" : "s") : "k" + (badges.isEmpty() ? "" : "B" + badges.size());
		}
	}

	/** T-189: a badge on the kill card: its name, what it reads (its tooltip), and its mark. */
	static final class Badge
	{
		enum Mark
		{
			KILLS, FLAWLESS, CLEAN, STREAK
		}

		final String name;
		final String tip;
		final Mark mark;

		Badge(String name, String tip, Mark mark)
		{
			this.name = name;
			this.tip = tip;
			this.mark = mark;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	static final class Builder
	{
		State state = State.EMPTY;
		Boolean wholeRaid;
		Header header;
		Banner banner;
		String unscoredNote;
		ListRows targets;
		ListRows rooms;
		TimeBudget budget;
		List<Tile> tiles;
		Curve curve;
		String noCurve;
		Text noCurveHint;
		Parts execution;
		Sources damage;
		Chart chart;
		String later;
		Rows taken;
		Defence defence;
		Loadout loadout;
		ListRow lastFight;
		Empty empty;
		List<TargetView> byTarget;
		KillCard card;
		java.util.Map<String, Text> summaries;
		Clear clear;

		FightTab build()
		{
			return new FightTab(this);
		}
	}

	private static <T> List<T> list(List<T> items)
	{
		return items == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(items));
	}
}

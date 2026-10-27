package com.pvmlogs;

import java.awt.Color;
import java.util.ArrayList;
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
		this.fight = fight;
		this.trip = trip;
	}

	/** One of the trip's rooms (spec 3.3): texts only, nothing that points back at the room. */
	static final class TripRow
	{
		final String name;
		final boolean ended;
		final String clock;
		final Text chip;
		final Color chipFill;

		TripRow(String name, boolean ended, String clock, Text chip, Color chipFill)
		{
			this.name = name;
			this.ended = ended;
			this.clock = clock;
			this.chip = chip;
			this.chipFill = chipFill;
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

		TripTab(Header header, List<Tile> tiles, Parts execution, Text roomsHint, List<TripRow> rooms, Rows taken,
			Rows other)
		{
			this.header = header;
			this.tiles = tiles == null ? null : list(tiles);
			this.execution = execution;
			this.roomsHint = roomsHint;
			this.rooms = list(rooms);
			this.taken = taken;
			this.other = other;
		}

		/** As {@link FightTab#structure()}: which sections there are and how many rows each has. */
		String structure()
		{
			return (tiles == null ? "" : "T") + (execution == null ? "" : "|e" + execution.parts.size()) + "|r" + rooms.size()
				+ (taken == null ? "" : "|k" + taken.rows.size()) + (other == null ? "" : "|o" + other.rows.size());
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
			this.text = text == null ? "" : text;
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
		final Text right;

		Row(Text left, Text right)
		{
			this.left = left;
			this.right = right;
		}

		@Override
		public String toString()
		{
			return left + " | " + right;
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

		Header(Text name, Text suffix, Text sub, Text clock)
		{
			this.name = name;
			this.suffix = suffix;
			this.sub = sub;
			this.clock = clock;
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
		/** The 2 px top edge: Track, or the value's colour when it's coloured. */
		final Color edge;

		Tile(Text label, Text value, Text sub, Color edge)
		{
			this.label = label;
			this.value = value;
			this.sub = sub;
			this.edge = edge;
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
		/** Only as a row's swatch: the time luck saved is an outline past the clock, not a stretch of the bar. */
		LUCK_FASTER
	}

	static final class Segment
	{
		final double seconds;
		final Fill fill;

		Segment(double seconds, Fill fill)
		{
			this.seconds = seconds;
			this.fill = fill;
		}
	}

	/** A row under the time budget's bar: a swatch, a label with an optional hint, the seconds. */
	static final class BudgetRow
	{
		final Fill swatch;
		final Text label;
		final Text hint;
		final Text value;

		BudgetRow(Fill swatch, Text label, Text hint, Text value)
		{
			this.swatch = swatch;
			this.label = label;
			this.hint = hint == null ? Text.NONE : hint;
			this.value = value;
		}
	}

	/** "Where the time went", after the fight only (spec sections 3.1 and 4). */
	static final class TimeBudget
	{
		final List<Segment> segments;
		/** The bar's whole length in seconds: T + max(0, L). */
		final double total;
		/** The clock T: where the outline for the time luck saved starts. */
		final double clock;
		/** Seconds the rolls saved (drawn as an outline past the clock), 0 for none. */
		final double luckSaved;
		/** The outline's colour: Better, or the average grey in the middle half. */
		final Color luckSavedColour;
		/** Your average's clock, for the dashed marker; NaN for none. */
		final double marker;
		final List<BudgetRow> rows;
		/** Your average and luck removed against your usual; empty for none. */
		final List<Row> average;
		/** One line in the average's place ("not a kill, so no average to compare"), or null. */
		final String note;

		TimeBudget(List<Segment> segments, double total, double clock, double luckSaved, Color luckSavedColour,
			double marker, List<BudgetRow> rows, List<Row> average, String note)
		{
			this.segments = list(segments);
			this.total = total;
			this.clock = clock;
			this.luckSaved = luckSaved;
			this.luckSavedColour = luckSavedColour;
			this.marker = marker;
			this.rows = list(rows);
			this.average = list(average);
			this.note = note;
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
		final String caption;

		Curve(double z, Text headerValue, String caption)
		{
			this.z = z;
			this.headerValue = headerValue;
			this.caption = caption;
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

		Source(String name, String sub, String damage, String share, double shareFraction, Color colour)
		{
			this.name = name;
			this.sub = sub;
			this.damage = damage;
			this.share = share;
			this.shareFraction = shareFraction;
			this.colour = colour;
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

		Parts(Text hint, List<Part> parts)
		{
			this.hint = hint;
			this.parts = list(parts);
		}
	}

	static final class Sources
	{
		final Text hint;
		final List<Source> rows;

		Sources(Text hint, List<Source> rows)
		{
			this.hint = hint;
			this.rows = list(rows);
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
		/** The "later" card in the damage rows' place, for a saved kill from before this session (T-034); null for none. */
		final String later;
		final Rows details;
		final Rows taken;
		final Loadout loadout;
		final ListRow lastFight;
		final Empty empty;

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
			later = b.later;
			details = b.details;
			taken = b.taken;
			loadout = b.loadout;
			lastFight = b.lastFight;
			empty = b.empty;
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
			s.append(damage == null ? "" : "|d" + damage.rows.size());
			s.append(later == null ? "" : "|lt");
			s.append(details == null ? "" : "|D" + details.rows.size());
			s.append(taken == null ? "" : "|k" + taken.rows.size());
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

	/** Collects a Fight tab's sections; every field starts out as "not shown". */
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
		String later;
		Rows details;
		Rows taken;
		Loadout loadout;
		ListRow lastFight;
		Empty empty;

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

package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.runelite.client.util.QuantityFormatter;

/**
 * The overlay's rows as data: what each row says, in which font role and which
 * colour role, for every state the overlay can be in. Nothing here draws or
 * reads the client, so every state can be built from a fake {@link Scope} in a
 * test. {@code PvmPerformanceOverlay} only turns these rows into line
 * components.
 * <p>
 * The design is "Ledger", {@code claude/specs/overlay-design.md} rev 4 in the
 * project: a top block that keeps its shape from the first attack to the kill
 * (title and clock, Luck, Damage / expected, DPS, Execution and its parts),
 * then a details block once the fight is over.
 */
final class LedgerRows
{
	static final double SECONDS_PER_TICK = 0.6;

	// The thresholds that decide what is coloured. All in one place: they were set on two of Eric's fights and will be
	// tuned on more.
	/** Luck, and an execution part's cost, is coloured only from this many seconds... */
	static final double MIN_SECONDS = 1;
	/** ...and only from this share of the clock, so a trip's few seconds over twelve minutes stay neutral. */
	static final double MIN_SHARE_OF_CLOCK = 0.02;
	/** v2 (Analyzer): "luckier than" reads Better from this percentile... */
	static final double PERCENTILE_BETTER = 0.60;
	/** ...and Cost at or under this one. */
	static final double PERCENTILE_COST = 0.40;

	/** An unknown value. U+2014 is in all three RuneScape fonts; arrows and bullets are not. */
	static final String UNKNOWN = "—";
	/** U+00B7, also in the fonts. */
	private static final String DOT = " · ";

	private LedgerRows()
	{
	}

	/** Which of the three fonts a side of a row is drawn in, relative to the player's interface font. */
	enum FontRole
	{
		/** The player's interface font itself. */
		BODY,
		/** The title's name and the Luck and Damage values. */
		EMPHASIS,
		/** Sub-rows, the ones whose left text starts with a space. */
		DETAIL
	}

	/** What a colour means. Always beside a sign or a word, never alone: Better and Cost are blue and orange, not green and red. */
	enum Colour
	{
		NEUTRAL(0xFFFFFF),
		BETTER(0x5CFFFF),
		COST(0xFFB366),
		WARNING(0xFFFF00),
		DEAD(0xFF7070);

		final int rgb;

		Colour(int rgb)
		{
			this.rgb = rgb;
		}
	}

	/** One line of the overlay: left text and right text, each with its own font role and colour role. */
	static final class Row
	{
		final String left;
		final String right;
		final FontRole leftFont;
		final FontRole rightFont;
		final Colour leftColour;
		final Colour rightColour;

		Row(String left, String right, FontRole leftFont, FontRole rightFont, Colour leftColour, Colour rightColour)
		{
			this.left = left == null ? "" : left;
			this.right = right == null ? "" : right;
			this.leftFont = leftFont;
			this.rightFont = rightFont;
			this.leftColour = leftColour;
			this.rightColour = rightColour;
		}

		boolean isBlank()
		{
			return left.isEmpty() && right.isEmpty();
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Row))
			{
				return false;
			}
			final Row r = (Row) o;
			return left.equals(r.left) && right.equals(r.right) && leftFont == r.leftFont && rightFont == r.rightFont
				&& leftColour == r.leftColour && rightColour == r.rightColour;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(left, right, leftFont, rightFont, leftColour, rightColour);
		}

		@Override
		public String toString()
		{
			return String.format("[%s|%s %s/%s %s/%s]", left, right, leftFont, rightFont, leftColour, rightColour);
		}
	}

	/**
	 * The numbers on show, from whichever scope is picked: the room, the raid or
	 * the trip. Unknown fractions are negative, as the scope classes return them.
	 */
	interface Scope
	{
		int combatTicks();

		/** The weapon's damage only, which is what the expected side describes. */
		int damage();

		double expectedDamage();

		int thrallDamage();

		/** Burn, poison, venom, bleed. */
		int dotDamage();

		/** 0 where the scope doesn't keep it (the trip). Only ever shown after the fight. */
		int healed();

		int ticksLost();

		double ticksLostShare();

		double efficiency();

		/** T-010's share of the full boost, or negative for a scope with no attack it was read for. */
		double boostShare();

		double boostCostShare();

		double switchCostShare();

		int attacksMade();

		int attacksPrayed();

		int attacksSwitched();

		int hits();

		double expectedHits();

		int ticksLostEating();
	}

	static Scope of(Encounter room)
	{
		return new Scope()
		{
			public int combatTicks()
			{
				return room.getCombatTicks();
			}

			public int damage()
			{
				return room.getDamageDealt();
			}

			public double expectedDamage()
			{
				return room.sumExpectedAverageHit();
			}

			public int thrallDamage()
			{
				return room.getThrallDamage();
			}

			public int dotDamage()
			{
				return room.getDotDamage();
			}

			public int healed()
			{
				return room.getHealed();
			}

			public int ticksLost()
			{
				return room.getTicksLost();
			}

			public double ticksLostShare()
			{
				return room.ticksLostShare();
			}

			public double efficiency()
			{
				return room.efficiency();
			}

			public double boostShare()
			{
				return room.boostShare();
			}

			public double boostCostShare()
			{
				return room.boostCostShare();
			}

			public double switchCostShare()
			{
				return room.switchCostShare();
			}

			public int attacksMade()
			{
				return room.getAttacksMade();
			}

			public int attacksPrayed()
			{
				return room.getAttacksPrayed();
			}

			public int attacksSwitched()
			{
				return room.getAttacksSwitched();
			}

			public int hits()
			{
				return room.getHits();
			}

			public double expectedHits()
			{
				return room.sumExpectedAccuracy();
			}

			public int ticksLostEating()
			{
				return room.getTicksLostEating();
			}
		};
	}

	static Scope of(Raid raid)
	{
		return new Scope()
		{
			public int combatTicks()
			{
				return raid.getCombatTicks();
			}

			public int damage()
			{
				return raid.getDamageDealt();
			}

			public double expectedDamage()
			{
				return raid.sumExpectedAverageHit();
			}

			public int thrallDamage()
			{
				return raid.getThrallDamage();
			}

			public int dotDamage()
			{
				return raid.getDotDamage();
			}

			public int healed()
			{
				return raid.getHealed();
			}

			public int ticksLost()
			{
				return raid.getTicksLost();
			}

			public double ticksLostShare()
			{
				return raid.ticksLostShare();
			}

			public double efficiency()
			{
				return raid.efficiency();
			}

			public double boostShare()
			{
				return raid.boostShare();
			}

			public double boostCostShare()
			{
				return raid.boostCostShare();
			}

			public double switchCostShare()
			{
				return raid.switchCostShare();
			}

			public int attacksMade()
			{
				return raid.getAttacksMade();
			}

			public int attacksPrayed()
			{
				return raid.getAttacksPrayed();
			}

			public int attacksSwitched()
			{
				return raid.getAttacksSwitched();
			}

			public int hits()
			{
				return raid.getHits();
			}

			public double expectedHits()
			{
				return raid.sumExpectedAccuracy();
			}

			public int ticksLostEating()
			{
				return raid.getTicksLostEating();
			}
		};
	}

	static Scope of(SessionTotals trip)
	{
		return new Scope()
		{
			public int combatTicks()
			{
				return trip.getCombatTicks();
			}

			public int damage()
			{
				return trip.getDamageDealt();
			}

			public double expectedDamage()
			{
				return trip.getSumExpectedAverageHit();
			}

			public int thrallDamage()
			{
				return trip.getThrallDamage();
			}

			public int dotDamage()
			{
				return trip.getDotDamage();
			}

			public int healed()
			{
				// Not kept for the trip (T-014 adds thrall and DoT only), so its row stays hidden there.
				return 0;
			}

			public int ticksLost()
			{
				return trip.getTicksLost();
			}

			public double ticksLostShare()
			{
				return trip.ticksLostShare();
			}

			public double efficiency()
			{
				return trip.efficiency();
			}

			public double boostShare()
			{
				return trip.boostShare();
			}

			public double boostCostShare()
			{
				return trip.boostCostShare();
			}

			public double switchCostShare()
			{
				return trip.switchCostShare();
			}

			public int attacksMade()
			{
				return trip.getAttacksMade();
			}

			public int attacksPrayed()
			{
				return trip.getAttacksPrayed();
			}

			public int attacksSwitched()
			{
				return trip.getAttacksSwitched();
			}

			public int hits()
			{
				return trip.getHits();
			}

			public double expectedHits()
			{
				return trip.getSumExpectedAccuracy();
			}

			public int ticksLostEating()
			{
				return trip.getTicksLostEating();
			}
		};
	}

	/** What the overlay is showing. */
	enum View
	{
		/** A fight is open on the target and nothing has been thrown in its room yet: the loadout against it. */
		PRE_ATTACK,
		/** The "Loadout only" setting: the pre-attack rows, always. */
		LOADOUT_ONLY,
		/** The room or fight on show, while it runs and once it ends. */
		FIGHT,
		/** "Overlay: whole trip". */
		TRIP,
		/** Raid scope set to the whole raid. */
		RAID
	}

	/** The loadout's figures against the target, as the plugin works them out. Unknown values are 0 or negative. */
	static final class Loadout
	{
		int maxHit;
		double accuracy = -1;
		double dps = -1;
		/** Only for a weapon with a special that hits. */
		int specMaxHit;
		double specAccuracy = -1;
	}

	/** Which state the overlay is in, beyond the numbers. */
	static final class State
	{
		final View view;
		/** The room's or target's name; for a raid, the raid's. */
		final String name;
		/** Kills on a trip, rooms in a raid. */
		final int count;
		/**
		 * The fight has ended. On a trip or raid: no fight is running, which is when
		 * the details show.
		 */
		final boolean ended;
		final boolean killed;
		/** {@code getStatsWarning(fight) != null}: the expected side can't be believed. */
		final boolean warning;
		final Loadout loadout;

		private State(View view, String name, int count, boolean ended, boolean killed, boolean warning,
			Loadout loadout)
		{
			this.view = view;
			this.name = name == null ? "" : name;
			this.count = count;
			this.ended = ended;
			this.killed = killed;
			this.warning = warning;
			this.loadout = loadout == null ? new Loadout() : loadout;
		}

		static State preAttack(String name, Loadout loadout)
		{
			return new State(View.PRE_ATTACK, name, 0, false, false, false, loadout);
		}

		static State loadoutOnly(String name, Loadout loadout)
		{
			return new State(View.LOADOUT_ONLY, name, 0, false, false, false, loadout);
		}

		static State fight(String name, boolean ended, boolean killed, boolean warning)
		{
			return new State(View.FIGHT, name, 0, ended, ended && killed, warning, null);
		}

		static State trip(int kills, boolean fightRunning, boolean warning)
		{
			return new State(View.TRIP, null, kills, !fightRunning, false, warning, null);
		}

		static State raid(String raidName, int rooms, boolean fightRunning, boolean warning)
		{
			return new State(View.RAID, raidName, rooms, !fightRunning, false, warning, null);
		}
	}

	/** The overlay's settings (spec section 7), so the rows don't read the config themselves. */
	static final class Settings
	{
		boolean showLoadout = true;
		boolean luck = true;
		ExecutionParts parts = ExecutionParts.ALL;
		boolean details = true;
	}

	/**
	 * Every figure the rows show, worked out once from the scope (spec section
	 * 3). Seconds are seconds; unknown values are NaN.
	 */
	static final class Numbers
	{
		/** T: the clock, from the first attack. */
		final double clock;
		/** A: the weapon's damage. */
		final int damage;
		/** E: what the weapon's attacks were expected to deal. */
		final double expected;
		/** O: the player's other damage, thrall and DoT, treated as luck-neutral. */
		final int other;
		/** T* - T: positive when the rolls saved time. */
		final double luck;
		final double dps;
		final double uptime;
		final double uptimeCost;
		final double efficiency;
		final double execution;
		/** What the setup (boost, prayer, gear) cost in seconds. */
		final double setupCost;
		/** Whether T-010's split exists for this scope; without it the setup shows as one row. */
		final boolean split;
		final double boostShare;
		final double boostCost;
		final double prayerShare;
		final double prayerCost;
		final double gearShare;
		final double gearCost;
		/** T + luck: the same play at average luck. */
		final double expectedDuration;

		Numbers(Scope s)
		{
			clock = Math.max(0, s.combatTicks()) * SECONDS_PER_TICK;
			damage = s.damage();
			expected = s.expectedDamage();
			other = Math.max(0, s.thrallDamage()) + Math.max(0, s.dotDamage());

			luck = expected <= 0 || expected + other <= 0 || clock <= 0
				? Double.NaN : clock * (damage - expected) / (expected + other);
			dps = clock <= 0 ? Double.NaN : damage / clock;
			expectedDuration = Double.isNaN(luck) ? Double.NaN : clock + luck;

			final double lostShare = s.ticksLostShare();
			uptime = lostShare < 0 ? Double.NaN : 1 - lostShare;
			uptimeCost = Math.max(0, s.ticksLost()) * SECONDS_PER_TICK;

			efficiency = s.efficiency();
			final boolean setupKnown = efficiency >= 0;
			execution = !setupKnown || Double.isNaN(uptime) ? Double.NaN : uptime * efficiency;
			setupCost = setupKnown ? Math.max(0, clock - uptimeCost) * Math.max(0, 1 - efficiency) : Double.NaN;

			final int made = s.attacksMade();
			split = setupKnown && s.boostShare() >= 0 && made > 0;
			if (split)
			{
				// The setup's seconds, split in proportion to what T-010 says the missing boost and the missed switches cost;
				// the prayer is what's left. Both costs are parts of the same setup loss, so when they come to more than it the
				// two are scaled down to fit and the prayer gets nothing.
				final double loss = Math.max(0, 1 - efficiency);
				double boost = Math.max(0, s.boostCostShare());
				double gear = Math.max(0, s.switchCostShare());
				if (boost + gear > loss)
				{
					final double scale = boost + gear <= 0 ? 0 : loss / (boost + gear);
					boost *= scale;
					gear *= scale;
				}
				final double prayer = Math.max(0, loss - boost - gear);
				final double perShare = loss <= 0 ? 0 : setupCost / loss;
				boostCost = boost * perShare;
				gearCost = gear * perShare;
				prayerCost = prayer * perShare;
				boostShare = Math.min(1, s.boostShare());
				prayerShare = (double) s.attacksPrayed() / made;
				gearShare = (double) s.attacksSwitched() / made;
			}
			else
			{
				boostCost = gearCost = prayerCost = Double.NaN;
				boostShare = prayerShare = gearShare = Double.NaN;
			}
		}
	}

	/** Builds the rows for one frame. */
	static List<Row> build(Scope scope, State state, Settings settings)
	{
		final List<Row> rows = new ArrayList<>();
		switch (state.view)
		{
			case PRE_ATTACK:
				rows.add(row(state.name, "", FontRole.EMPHASIS, FontRole.BODY, Colour.NEUTRAL, Colour.NEUTRAL));
				if (settings.showLoadout)
				{
					loadout(rows, state.loadout);
				}
				return rows;
			case LOADOUT_ONLY:
				rows.add(row(state.name, "", FontRole.EMPHASIS, FontRole.BODY, Colour.NEUTRAL, Colour.NEUTRAL));
				loadout(rows, state.loadout);
				return rows;
			default:
				break;
		}
		if (scope == null)
		{
			return Collections.emptyList();
		}
		final Numbers n = new Numbers(scope);
		rows.add(title(state, scope.combatTicks()));
		top(rows, n, state, settings);
		if (settings.details && state.ended)
		{
			details(rows, n, scope, state, settings);
		}
		return rows;
	}

	private static void loadout(List<Row> rows, Loadout l)
	{
		if (l.maxHit > 0)
		{
			rows.add(body("Max hit", String.valueOf(l.maxHit)));
		}
		if (l.accuracy >= 0)
		{
			rows.add(body("Accuracy", percent(l.accuracy)));
		}
		if (l.dps >= 0)
		{
			rows.add(body("Expected DPS", oneDecimal(l.dps)));
		}
		if (l.specMaxHit > 0)
		{
			rows.add(detail(" spec", l.specMaxHit + " max" + (l.specAccuracy >= 0 ? DOT + percent(l.specAccuracy) : ""),
				Colour.NEUTRAL));
		}
	}

	private static Row title(State state, int ticks)
	{
		switch (state.view)
		{
			case TRIP:
				return row("Trip" + DOT + plural(state.count, "kill"), clock(ticks),
					FontRole.EMPHASIS, FontRole.BODY, Colour.NEUTRAL, Colour.NEUTRAL);
			case RAID:
				return row(raidShortName(state.name) + DOT + plural(state.count, "room"), clock(ticks),
					FontRole.EMPHASIS, FontRole.BODY, Colour.NEUTRAL, Colour.NEUTRAL);
			default:
				final String name = !state.ended ? state.name
					: state.name + (state.killed ? " (dead)" : " (ended)");
				return row(name, clock(ticks),
					FontRole.EMPHASIS, FontRole.BODY, state.killed ? Colour.DEAD : Colour.NEUTRAL, Colour.NEUTRAL);
		}
	}

	private static void top(List<Row> rows, Numbers n, State state, Settings settings)
	{
		final Colour luckColour = luckColour(n.luck, n.clock);
		final boolean noData = n.expected <= 0;
		if (settings.luck && !state.warning)
		{
			rows.add(row("Luck", Double.isNaN(n.luck) ? UNKNOWN : seconds(n.luck),
				FontRole.BODY, FontRole.EMPHASIS, Colour.NEUTRAL, luckColour));
		}
		if (state.warning)
		{
			rows.add(row("! Boss data unsure", "", FontRole.BODY, FontRole.BODY, Colour.WARNING, Colour.NEUTRAL));
			rows.add(detail(" luck hidden until it's fixed", "", Colour.NEUTRAL));
		}
		if (!state.warning && !noData)
		{
			rows.add(row("Damage / expected", number(n.damage) + " / " + number(Math.round(n.expected)),
				FontRole.BODY, FontRole.EMPHASIS, Colour.NEUTRAL, luckColour));
		}
		else
		{
			rows.add(row("Damage", number(n.damage), FontRole.BODY, FontRole.EMPHASIS, Colour.NEUTRAL, Colour.NEUTRAL));
			if (!state.warning)
			{
				rows.add(detail(" no monster data yet", "", Colour.NEUTRAL));
			}
		}
		rows.add(body("DPS", Double.isNaN(n.dps) ? UNKNOWN : oneDecimal(n.dps)));
		rows.add(body("Execution", Double.isNaN(n.execution) ? UNKNOWN : percent(n.execution)));
		if (settings.parts != ExecutionParts.OFF)
		{
			parts(rows, n, settings.parts == ExecutionParts.COSTING);
		}
	}

	private static void parts(List<Row> rows, Numbers n, boolean costingOnly)
	{
		final int before = rows.size();
		if (!Double.isNaN(n.uptime))
		{
			part(rows, " uptime", n.uptime, n.uptimeCost, n.clock, costingOnly);
		}
		// Without the setup figure (no monster data) only uptime can be priced; the canvas shows uptime alone there.
		if (!Double.isNaN(n.setupCost))
		{
			if (n.split)
			{
				part(rows, " stat boost", n.boostShare, n.boostCost, n.clock, costingOnly);
				part(rows, " prayer", n.prayerShare, n.prayerCost, n.clock, costingOnly);
				part(rows, " gear", n.gearShare, n.gearCost, n.clock, costingOnly);
			}
			else
			{
				// A scope with no boost share read: fights from before T-010. One row stands in for the three.
				part(rows, " setup", n.efficiency, n.setupCost, n.clock, costingOnly);
			}
		}
		if (costingOnly && rows.size() == before)
		{
			rows.add(detail(" no losses", "", Colour.NEUTRAL));
		}
	}

	private static void part(List<Row> rows, String label, double share, double cost, double clock, boolean costingOnly)
	{
		final boolean costing = isCosting(cost, clock);
		if (costingOnly && !costing)
		{
			return;
		}
		rows.add(detail(label, partValue(share, cost), costing ? Colour.COST : Colour.NEUTRAL));
	}

	private static void details(List<Row> rows, Numbers n, Scope s, State state, Settings settings)
	{
		rows.add(blank());
		final String time = clock(s.combatTicks());
		if (settings.luck)
		{
			// Hidden with Luck under the warning, since it is the clock plus the luck.
			final boolean known = !Double.isNaN(n.expectedDuration) && !state.warning;
			rows.add(body("Duration / expected", time + " / " + (known ? duration(n.expectedDuration) : UNKNOWN)));
		}
		else
		{
			rows.add(body("Duration", time));
		}
		// v2 (Analyzer) rows go here, under Duration / expected: " vs your average" and " luckier than", coloured by
		// PERCENTILE_BETTER / PERCENTILE_COST. Not built in v1.
		if (s.expectedHits() > 0)
		{
			rows.add(detail(" hits / expected", s.hits() + " / " + oneDecimal(s.expectedHits()), Colour.NEUTRAL));
		}
		if (s.thrallDamage() > 0)
		{
			rows.add(detail(" thrall dmg", number(s.thrallDamage()), Colour.NEUTRAL));
		}
		if (s.dotDamage() > 0)
		{
			rows.add(detail(" burn/poison dmg", number(s.dotDamage()), Colour.NEUTRAL));
		}
		// The boss's doing rather than the player's, so after the fight only (own-output guardrail).
		if (s.healed() > 0)
		{
			rows.add(detail(" boss healed", number(s.healed()), Colour.NEUTRAL));
		}
		if (s.ticksLostEating() > 0)
		{
			rows.add(detail(" eating", plural(s.ticksLostEating(), "tick"), Colour.NEUTRAL));
		}
	}

	// ---- colour rules ----

	/** Better at +1 s and +2% of the clock, Cost at -1 s and -2%, else neutral. */
	static Colour luckColour(double luck, double clock)
	{
		if (Double.isNaN(luck))
		{
			return Colour.NEUTRAL;
		}
		final double needed = Math.max(MIN_SECONDS, MIN_SHARE_OF_CLOCK * clock);
		if (luck >= needed)
		{
			return Colour.BETTER;
		}
		if (luck <= -needed)
		{
			return Colour.COST;
		}
		return Colour.NEUTRAL;
	}

	/** A part costs real time at 1 s and 2% of the clock. */
	static boolean isCosting(double seconds, double clock)
	{
		return !Double.isNaN(seconds) && seconds >= MIN_SECONDS && seconds >= MIN_SHARE_OF_CLOCK * clock;
	}

	/** v2: the percentile's colour. Not shown in v1; here so the bands sit with the other thresholds. */
	static Colour percentileColour(double percentile)
	{
		if (percentile >= PERCENTILE_BETTER)
		{
			return Colour.BETTER;
		}
		return percentile <= PERCENTILE_COST ? Colour.COST : Colour.NEUTRAL;
	}

	// ---- formats ----

	/** {@code NN%}, then {@code (-N s)} when the cost rounds to at least a second. */
	static String partValue(double share, double costSeconds)
	{
		final String pct = Double.isNaN(share) ? UNKNOWN : percent(share);
		final long secs = Double.isNaN(costSeconds) ? 0 : roundHalfAway(costSeconds);
		return secs >= 1 ? pct + " (-" + secs + " s)" : pct;
	}

	/** {@code +14 s}, {@code -8 s}, {@code 0 s}: rounded half away from zero, never {@code -0 s}. */
	static String seconds(double seconds)
	{
		final long r = roundHalfAway(seconds);
		if (r == 0)
		{
			return "0 s";
		}
		return (r > 0 ? "+" : "-") + Math.abs(r) + " s";
	}

	static long roundHalfAway(double v)
	{
		return v < 0 ? -Math.round(-v) : Math.round(v);
	}

	static String percent(double share)
	{
		return Math.round(share * 100) + "%";
	}

	static String oneDecimal(double v)
	{
		return String.format(Locale.ENGLISH, "%.1f", v);
	}

	static String number(long v)
	{
		return QuantityFormatter.formatNumber(v);
	}

	/**
	 * Every clock: whole seconds, {@code m:ss}, {@code h:mm:ss} past an hour,
	 * counted up like a stopwatch. No tenths once the fight ends either (Eric,
	 * 2026-10-26), so it reads like the expected time beside it.
	 */
	static String clock(int ticks)
	{
		return hms(Math.max(0, (long) ticks) * 6 / 10);
	}

	/**
	 * An expected time: whole seconds, cut down like the clock beside it, so the
	 * gap between the two reads as the luck does (12:40 against 12:26 for 13.5 s).
	 */
	static String duration(double seconds)
	{
		return hms((long) Math.floor(Math.max(0, seconds)));
	}

	private static String hms(long seconds)
	{
		final long h = seconds / 3600;
		final long m = seconds % 3600 / 60;
		final long s = seconds % 60;
		return h > 0 ? String.format(Locale.ENGLISH, "%d:%02d:%02d", h, m, s)
			: String.format(Locale.ENGLISH, "%d:%02d", m, s);
	}

	/** CoX, ToB, ToA; any other name as it is. */
	static String raidShortName(String name)
	{
		if (RaidType.CHAMBERS_OF_XERIC.getDisplayName().equals(name))
		{
			return "CoX";
		}
		if (RaidType.THEATRE_OF_BLOOD.getDisplayName().equals(name))
		{
			return "ToB";
		}
		if (RaidType.TOMBS_OF_AMASCUT.getDisplayName().equals(name))
		{
			return "ToA";
		}
		return name;
	}

	private static String plural(int n, String noun)
	{
		return n + " " + noun + (n == 1 ? "" : "s");
	}

	// ---- row shapes ----

	private static Row row(String left, String right, FontRole lf, FontRole rf, Colour lc, Colour rc)
	{
		return new Row(left, right, lf, rf, lc, rc);
	}

	private static Row body(String left, String right)
	{
		return row(left, right, FontRole.BODY, FontRole.BODY, Colour.NEUTRAL, Colour.NEUTRAL);
	}

	private static Row detail(String left, String right, Colour rightColour)
	{
		return row(left, right, FontRole.DETAIL, FontRole.DETAIL, Colour.NEUTRAL, rightColour);
	}

	static Row blank()
	{
		return body("", "");
	}
}

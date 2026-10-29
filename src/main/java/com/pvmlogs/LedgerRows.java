package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * (title and clock, Luck, Damage/expected, DPS, Execution and its parts),
 * then a details block once the fight is over.
 */
final class LedgerRows
{
	static final double SECONDS_PER_TICK = 0.6;
	/** The eating row's label (T-038). */
	static final String EATING = " eating";
	/** T-051's rows: the target's Defence and Magic, the drains' worth over a raid or trip, and the specials' luck. */
	static final String DEFENCE = " defence";
	static final String MAGIC = " magic";
	static final String DEFENCE_DRAINS = " defence drains";
	static final String SPECS = " specs";
	private static final String ARROW = " \u2192 ";
	/** Execution's fifth part after a ruled fight (T-043). */
	static final String AVOIDABLE_EATING = " avoidable eating";

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
	/** " vs your average" is the median of this many of your latest kills of the boss... */
	static final int AVERAGE_OF_LAST = 20;
	/** ...and shows once there are this many (metrics spec, decision 5's sample). */
	static final int AVERAGE_FROM_KILLS = 5;

	/** An unknown value. U+2014 is in all three RuneScape fonts; arrows and bullets are not. */
	static final String UNKNOWN = "—";
	/** U+00B7, also in the fonts. */
	private static final String DOT = " · ";
	/** Heads the Ledger in Luck's place, and the loadout overlay, when the boss's data can't be believed. */
	static final String WARNING_TEXT = "! Boss data unsure";
	/** The main row the details sit under once the fight is over (Eric, 2026-10-26). */
	static final String SUMMARY = "Summary";
	/** After the title's name once its kill has happened. */
	static final String DEAD_SUFFIX = " (dead)";
	/**
	 * The title's clock (T-073): the title is the one row whose figure stays
	 * bold, as the box's heading, while every other main row's figure is
	 * regular. Both were rendered for Eric; see the status doc.
	 */
	static final FontRole TITLE_CLOCK = FontRole.EMPHASIS;
	/** The DPS overlay's first line, naming what its figures are against (T-073): {@code vs Dusk}. */
	static final String VERSUS = "vs ";
	/** The middle half's edges, for Luck's band and colour (T-029). */
	static final double LOW_QUARTILE = 0.25;
	static final double HIGH_QUARTILE = 0.75;
	/** What a name cut to fit ends with: U+2026, which all three RuneScape fonts have (checked with Font.canDisplay). */
	static final String CUT = "…";
	/** An item's variant tag: a space and a bracketed word in lower case, like " (or)" or " (uncharged)". */
	private static final Pattern VARIANT_TAG = Pattern.compile("\\s*\\([a-z][^()]*\\)");
	/**
	 * A name ending in a bracketed word, as kills saved before T-071 named a cast
	 * ({@code weapon (Spell)}); group 1 is the bracket's text, a spell only if
	 * {@link Spell} knows it.
	 */
	private static final Pattern SPELL = Pattern.compile(".+ \\(([^()]+)\\)");
	/** Luck, or this kill against your average, that rounds to no seconds either way. */
	static final String EVEN = "even";
	/** The stat boost row's figure when more than half the attacks were below base (T-016). */
	static final String DRAINED = "drained";

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
		DETAIL,
		/**
		 * A main row's figure (T-073, Eric 2026-10-28: "keep bold on the left and
		 * change the numbers on the right unboldened"): the interface font's
		 * regular weight, beside a bold label.
		 */
		VALUE
	}

	/** What a colour means. Always beside a sign or a word, never alone: Better and Cost are blue and orange, not green and red. */
	enum Colour
	{
		NEUTRAL(0xFFFFFF),
		BETTER(0x5CFFFF),
		COST(0xFFB366),
		WARNING(0xFFFF00),
		DEAD(0xFF7070),
		/**
		 * A boss's name heading the overlay (Eric, 2026-10-28): its own themed
		 * colour, carried on the row ({@link Row#leftRgb}), this accent where it has
		 * none. See {@link BossColours}.
		 */
		BOSS(BossColours.ACCENT);

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
		/**
		 * For a left side that may be shortened to fit the panel, the shortest
		 * form worth showing before it's cut; null for a row RuneLite may wrap.
		 * The attack's name has one, as its length isn't ours to choose, and so
		 * does "Damage/expected", one word RuneLite can't wrap.
		 */
		final String shortLeft;
		/**
		 * T-029: the same for the right side, for a figure that may run long (the
		 * Luck row's {@code bottom 20%} without its seconds); null for none.
		 */
		final String shortRight;
		/** A boss's own colour for the left side ({@link Colour#BOSS}), or -1: the left colour's own. */
		final int leftRgb;

		Row(String left, String right, FontRole leftFont, FontRole rightFont, Colour leftColour, Colour rightColour)
		{
			this(left, right, leftFont, rightFont, leftColour, rightColour, null);
		}

		Row(String left, String right, FontRole leftFont, FontRole rightFont, Colour leftColour, Colour rightColour,
			String shortLeft)
		{
			this(left, right, leftFont, rightFont, leftColour, rightColour, shortLeft, null);
		}

		Row(String left, String right, FontRole leftFont, FontRole rightFont, Colour leftColour, Colour rightColour,
			String shortLeft, String shortRight)
		{
			this(left, right, leftFont, rightFont, leftColour, rightColour, shortLeft, shortRight, -1);
		}

		Row(String left, String right, FontRole leftFont, FontRole rightFont, Colour leftColour, Colour rightColour,
			String shortLeft, String shortRight, int leftRgb)
		{
			this.leftRgb = leftRgb;
			this.left = left == null ? "" : left;
			this.right = right == null ? "" : right;
			this.leftFont = leftFont;
			this.rightFont = rightFont;
			this.leftColour = leftColour;
			this.rightColour = rightColour;
			this.shortLeft = shortLeft;
			this.shortRight = shortRight;
		}

		/** This row with its left side swapped, e.g. for a shortened name; the rest as it was. */
		Row withLeft(String newLeft)
		{
			return new Row(newLeft, right, leftFont, rightFont, leftColour, rightColour, shortLeft, shortRight, leftRgb);
		}

		/** This row with its right side swapped for its short form; the rest as it was. */
		Row withRight(String newRight)
		{
			return new Row(left, newRight, leftFont, rightFont, leftColour, rightColour, shortLeft, shortRight, leftRgb);
		}

		boolean isBlank()
		{
			return left.isEmpty() && right.isEmpty();
		}

		/** The left side's colour as drawn: a boss's own, else its colour role's. */
		int leftRgb()
		{
			return leftRgb >= 0 ? leftRgb : leftColour.rgb;
		}

		/**
		 * The left side as drawn: a boss's title after its kill has the word
		 * "(dead)" in Dead's red and the name in the boss's colour (Eric,
		 * 2026-10-28: "show only the '(dead)' word in red, so you get both
		 * signals"), by RuneLite's colour tag. Widths are measured without tags.
		 */
		String drawnLeft()
		{
			if (leftColour != Colour.BOSS || !left.endsWith(DEAD_SUFFIX))
			{
				return left;
			}
			final int at = left.length() - DEAD_SUFFIX.length() + 1;
			return left.substring(0, at) + String.format("<col=%06x>", Colour.DEAD.rgb) + left.substring(at);
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
				&& leftColour == r.leftColour && rightColour == r.rightColour && Objects.equals(shortLeft, r.shortLeft)
				&& Objects.equals(shortRight, r.shortRight) && leftRgb == r.leftRgb;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(left, right, leftFont, rightFont, leftColour, rightColour, shortLeft, shortRight, leftRgb);
		}

		@Override
		public String toString()
		{
			return String.format("[%s|%s %s/%s %s%s/%s]", left, right, leftFont, rightFont, leftColour,
				leftRgb >= 0 ? String.format("#%06X", leftRgb) : "", rightColour);
		}
	}

	/**
	 * The numbers on show, from whichever scope is picked: the room, the raid or
	 * the trip. Unknown fractions are negative, as the scope classes return them.
	 */
	interface Scope
	{
		int combatTicks();

		/**
		 * T-023: the ticks of the scored fights only, which the damage and expected
		 * damage come from, so luck's seconds and DPS are rates over the same time.
		 * The clock in the title stays {@link #combatTicks()}.
		 */
		int scoredCombatTicks();

		/** The weapon's damage only, which is what the expected side describes. */
		int damage();

		double expectedDamage();

		int thrallDamage();

		/** T-017 Part B: recoil and vengeance on the target, the player's own and luck-neutral like thrall and DoT. */
		int reflectDamage();

		/**
		 * T-025: thrall, DoT and reflected damage on the scored fights only, luck's
		 * other damage {@code O}: damage dealt and expected are scored-only, so a
		 * thrall's or a burn's damage on an unscored add stays out too. The Summary's
		 * rows keep the whole scope's ({@link #thrallDamage()} and the rest).
		 */
		int scoredOtherDamage();

		/**
		 * T-028: the variance of the expected damage on the scored fights, summed;
		 * -1 when any is unknown (fights saved before T-028) or nothing was scored.
		 * T-029's luck percentile divides by its root.
		 */
		double damageVariance();

		/** Burn, poison, venom, bleed. */
		int dotDamage();

		/**
		 * T-062: {@link #dotDamage()} by kind ({@code burn}, {@code poison},
		 * {@code venom}, {@code bleed}); the part with no kind (old kills) is what
		 * the total has beyond these. Empty where nothing is known by kind.
		 */
		default java.util.Map<String, Integer> dotDamageByKind()
		{
			return java.util.Collections.emptyMap();
		}

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

		/** T-015: attacks made with any stat the style rolls on below its base (drained or brewed down). */
		int attacksDrained();

		/** The damage the player took (T-016): hits only since T-018. */
		int damageTaken();

		/** T-018: what poison, venom, burn and bleed took from the player. */
		int dotTaken();

		/** T-062: {@link #dotTaken()} by kind, as {@link #dotDamageByKind()}. */
		default java.util.Map<String, Integer> dotTakenByKind()
		{
			return java.util.Collections.emptyMap();
		}

		/** T-018: the biggest single hit the player took. */
		int biggestHitTaken();

		/** T-018: hit damage taken with no protection prayer up. */
		int damageTakenUnprotected();

		int hits();

		double expectedHits();

		int ticksLostEating();

		/**
		 * T-041/T-043: the eating ticks the avoidable hits cost, over the ended
		 * fights at a boss with attack rules; -1 when none (a running fight, no
		 * rules, a kill saved before T-041).
		 */
		default double avoidableEatingTicks()
		{
			return -1;
		}

		/** T-041/T-043: the avoidable part of {@link #damageTaken()}, over the same fights; -1 when none. */
		default int damageTakenAvoidable()
		{
			return -1;
		}

		/** T-049/T-051: the target's Defence as the scope's first recorded fight opened; -1 unknown or many targets. */
		default int defenceStart()
		{
			return -1;
		}

		/** Its Defence as the scope's last recorded fight ended; -1 unknown. */
		default int defenceEnd()
		{
			return -1;
		}

		/** Its Magic as the first recorded fight opened; -1 unknown. */
		default int magicStart()
		{
			return -1;
		}

		/** Its Magic as the last recorded fight ended; -1 unknown. */
		default int magicEnd()
		{
			return -1;
		}

		/** T-050/T-051: what the drains were worth in damage, realized; NaN unknown (old kills, nothing drainable). */
		default double drainValueDamage()
		{
			return Double.NaN;
		}

		/** T-050/T-051: the specials' share of luck in damage, E(realized) - E(averaged); NaN unknown. */
		default double specLuckDamage()
		{
			return Double.NaN;
		}

		/** T-050/T-051: damage as seconds, the way the scope's luck turns damage into time; NaN without a rate. */
		default double luckSeconds(double damage)
		{
			return Double.NaN;
		}
	}

	static Scope of(Encounter room)
	{
		return new Scope()
		{
			public int combatTicks()
			{
				return room.getCombatTicks();
			}

			public int scoredCombatTicks()
			{
				return room.getScoredCombatTicks();
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

			public int reflectDamage()
			{
				return room.getReflectDamage();
			}

			public int scoredOtherDamage()
			{
				return room.getScoredThrallDamage() + room.getScoredDotDamage() + room.getScoredReflectDamage();
			}

			public double damageVariance()
			{
				return room.sumExpectedDamageVariance();
			}

			public int dotDamage()
			{
				return room.getDotDamage();
			}

			@Override
			public java.util.Map<String, Integer> dotDamageByKind()
			{
				return room.getDotDamageByKind();
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

			public int attacksDrained()
			{
				return room.getAttacksDrained();
			}

			public int damageTaken()
			{
				return room.getDamageTaken();
			}

			public int dotTaken()
			{
				return room.getDotTaken();
			}

			@Override
			public java.util.Map<String, Integer> dotTakenByKind()
			{
				return room.getDotTakenByKind();
			}

			public int biggestHitTaken()
			{
				return room.getBiggestHitTaken();
			}

			public int damageTakenUnprotected()
			{
				return room.getDamageTakenUnprotected();
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

			public double avoidableEatingTicks()
			{
				return room.getAvoidableEatingTicks();
			}

			public int damageTakenAvoidable()
			{
				return room.getDamageTakenAvoidable();
			}

			public int defenceStart()
			{
				return room.getDefenceStart();
			}

			public int defenceEnd()
			{
				return room.getDefenceEnd();
			}

			public int magicStart()
			{
				return room.getMagicStart();
			}

			public int magicEnd()
			{
				return room.getMagicEnd();
			}

			public double drainValueDamage()
			{
				return room.getDrainValueDamage();
			}

			public double specLuckDamage()
			{
				return room.getSpecLuckDamage();
			}

			public double luckSeconds(double damage)
			{
				return room.luckSeconds(damage);
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

			public int scoredCombatTicks()
			{
				return raid.getScoredCombatTicks();
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

			public int reflectDamage()
			{
				return raid.getReflectDamage();
			}

			public int scoredOtherDamage()
			{
				return raid.getScoredOtherDamage();
			}

			public double damageVariance()
			{
				return raid.sumExpectedDamageVariance();
			}

			public int dotDamage()
			{
				return raid.getDotDamage();
			}

			@Override
			public java.util.Map<String, Integer> dotDamageByKind()
			{
				return raid.getDotDamageByKind();
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

			public int attacksDrained()
			{
				return raid.getAttacksDrained();
			}

			public int damageTaken()
			{
				return raid.getDamageTaken();
			}

			public int dotTaken()
			{
				return raid.getDotTaken();
			}

			@Override
			public java.util.Map<String, Integer> dotTakenByKind()
			{
				return raid.getDotTakenByKind();
			}

			public int biggestHitTaken()
			{
				return raid.getBiggestHitTaken();
			}

			public int damageTakenUnprotected()
			{
				return raid.getDamageTakenUnprotected();
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

			public double avoidableEatingTicks()
			{
				return raid.getAvoidableEatingTicks();
			}

			public int damageTakenAvoidable()
			{
				return raid.getDamageTakenAvoidable();
			}

			// T-051: the drains summed; a raid or a trip has many targets, so no single Defence path.
			public double drainValueDamage()
			{
				return raid.getDrainValueDamage();
			}

			public double specLuckDamage()
			{
				return raid.getSpecLuckDamage();
			}

			public double luckSeconds(double damage)
			{
				return raid.luckSeconds(damage);
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

			public int scoredCombatTicks()
			{
				// T-024 counts the trip's ticks on scored fights apart (T-025).
				return trip.getScoredCombatTicks();
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

			public int reflectDamage()
			{
				return trip.getReflectDamage();
			}

			public int scoredOtherDamage()
			{
				// The trip's thrall, DoT and reflect are recorded behind isScored() already.
				return Math.max(0, trip.getThrallDamage()) + Math.max(0, trip.getDotDamage())
					+ Math.max(0, trip.getReflectDamage());
			}

			public double damageVariance()
			{
				return trip.damageVariance();
			}

			public int dotDamage()
			{
				return trip.getDotDamage();
			}

			@Override
			public java.util.Map<String, Integer> dotDamageByKind()
			{
				return trip.getDotDamageByKind();
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

			public int attacksDrained()
			{
				return trip.getAttacksDrained();
			}

			public int damageTaken()
			{
				return trip.getDamageTaken();
			}

			public int dotTaken()
			{
				return trip.getDotTaken();
			}

			@Override
			public java.util.Map<String, Integer> dotTakenByKind()
			{
				return trip.getDotTakenByKind();
			}

			public int biggestHitTaken()
			{
				return trip.getBiggestHitTaken();
			}

			public int damageTakenUnprotected()
			{
				return trip.getDamageTakenUnprotected();
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

			public double avoidableEatingTicks()
			{
				return trip.getAvoidableEatingTicks();
			}

			public int damageTakenAvoidable()
			{
				return trip.getDamageTakenAvoidable();
			}

			// T-051: the drains summed; a raid or a trip has many targets, so no single Defence path.
			public double drainValueDamage()
			{
				return trip.getDrainValueDamage();
			}

			public double specLuckDamage()
			{
				return trip.getSpecLuckDamage();
			}

			public double luckSeconds(double damage)
			{
				return trip.luckSeconds(damage);
			}
		};
	}

	/** What the overlay is showing. */
	enum View
	{
		/**
		 * A fight is open on the target and nothing has been thrown in its room yet:
		 * the title alone, as the loadout overlay carries the figures (T-022).
		 */
		PRE_ATTACK,
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
		/** What the figures are for: the weapon, and the spell when one is cast (T-016). Null reads as "Loadout". */
		String attack;
		int maxHit;
		double accuracy = -1;
		double dps = -1;
		/** Only for a weapon with a special that hits. */
		int specMaxHit;
		double specAccuracy = -1;
		/** What one spec is expected to deal: hit chance times damage, as the plugin books it (Eric, 2026-10-26). */
		double specAverage = -1;
		/** T-030: what the spec deals beyond its own hitsplats on average (Burning barrage's burn); 0 for none. */
		double specExtra = -1;
		/** What {@link #specExtra} is, for its bracket: "burn". */
		String specExtraKind;
		/**
		 * T-073: the monster the figures are against, for the {@code vs} line: the
		 * fight's own target, so the part being fought in a grouped room ("Dusk").
		 * Null with no monster (before any fight, or gone idle): no line.
		 */
		String versus;
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
		/** Your usual kill on this boss, or null with too few kills to say. */
		final LedgerHistory.Average average;
		/** T-051: the running fight's target as tracked now, or null: nothing drained yet, immune, or not running. */
		final LiveDrain drain;
		/**
		 * The title's colour where the name alone can't say it: a raid's room takes
		 * its raid's (Eric, 2026-10-28). -1: from the name ({@link BossColours#of(String)}).
		 */
		final int titleRgb;

		private State(View view, String name, int count, boolean ended, boolean killed, boolean warning,
			LedgerHistory.Average average)
		{
			this(view, name, count, ended, killed, warning, average, null, -1);
		}

		private State(View view, String name, int count, boolean ended, boolean killed, boolean warning,
			LedgerHistory.Average average, LiveDrain drain, int titleRgb)
		{
			this.titleRgb = titleRgb;
			this.view = view;
			this.name = name == null ? "" : name;
			this.count = count;
			this.ended = ended;
			this.killed = killed;
			this.warning = warning;
			this.average = average;
			this.drain = drain;
		}

		static State preAttack(String name)
		{
			return new State(View.PRE_ATTACK, name, 0, false, false, false, null);
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

		/** The same state with your usual time on the boss, for the details after a kill. */
		State withAverage(LedgerHistory.Average average)
		{
			return new State(view, name, count, ended, killed, warning, average, drain, titleRgb);
		}

		/** The same state with the title in this colour, or -1 for the name's own; a raid's room takes the raid's. */
		State withTitleRgb(int rgb)
		{
			return new State(view, name, count, ended, killed, warning, average, drain, rgb);
		}

		/** The colour the title's name is drawn in: the given one, else the name's, else -1 for none. */
		int titleColour()
		{
			return titleRgb >= 0 ? titleRgb : BossColours.of(name);
		}

		/** The same state with the running fight's tracked Defence and Magic (T-051). */
		State withDrain(LiveDrain drain)
		{
			return new State(view, name, count, ended, killed, warning, average, drain, titleRgb);
		}
	}

	/**
	 * A running fight's target as T-049 tracks it, for the live readout (T-051):
	 * its Defence and Magic now, as the raid scales them, and their floors. Only
	 * the player's and the party's own specials move these.
	 */
	static final class LiveDrain
	{
		final int defence;
		final int defenceBase;
		final int defenceFloor;
		final int magic;
		final int magicBase;
		final int magicFloor;

		LiveDrain(int defence, int defenceBase, int defenceFloor, int magic, int magicBase, int magicFloor)
		{
			this.defence = defence;
			this.defenceBase = defenceBase;
			this.defenceFloor = defenceFloor;
			this.magic = magic;
			this.magicBase = magicBase;
			this.magicFloor = magicFloor;
		}

		/** Whether anything has been taken off it: before that, and once it has all come back, nothing shows. */
		boolean drained()
		{
			return defence != defenceBase || magic != magicBase;
		}
	}

	/** The overlay's settings (spec section 7), so the rows don't read the config themselves. */
	static final class Settings
	{
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
		/** T on the scored fights only (T-023): what luck's seconds and DPS are rates over. */
		final double scoredClock;
		/**
		 * T-029: the share of fights with these same attacks that would have dealt
		 * less, Φ((A − E) / √V) over T-028's variance; NaN when V is unknown.
		 */
		final double percentile;
		/** A: the weapon's damage. */
		final int damage;
		/** E: what the weapon's attacks were expected to deal. */
		final double expected;
		/** O: the player's other damage, thrall and DoT, treated as luck-neutral. */
		final int other;
		/** T* - T: positive when the rolls saved time. */
		final double luck;
		final double dps;
		/** The share of the clock spent attacking or on cooldown: idle ticks count against it, eating doesn't (T-038). */
		final double uptime;
		/** The idle ticks' seconds: off cooldown, no attack, not eating. */
		final double uptimeCost;
		/**
		 * Ticks lost to eating, in seconds (T-038): out of Execution, as the
		 * plugin can't tell eating through chip damage you can't avoid from eating
		 * for damage you could have.
		 */
		final double eatingCost;
		/**
		 * T-043: the eating the avoidable hits cost, in seconds, and its share
		 * kept (1 − avoidable eating ticks ÷ combat ticks); NaN when unknown (a
		 * running fight, no attack rules, an old kill).
		 */
		final double avoidableEatingCost;
		final double avoidableEatingShare;
		/** T-038's Execution, without avoidable eating: what the overlay shows while the fight runs. */
		final double executionLive;
		final double efficiency;
		final double execution;
		/** What the setup (boost, prayer, gear) cost in seconds. */
		final double setupCost;
		/** Whether T-010's split exists for this scope; without it the setup shows as one row. */
		final boolean split;
		/** More than half the attacks were made below base (T-016): the stat boost row says so in words. */
		final boolean drained;
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
			scoredClock = Math.max(0, s.scoredCombatTicks()) * SECONDS_PER_TICK;
			damage = s.damage();
			expected = s.expectedDamage();
			// Recoil and vengeance are the player's too and luck-neutral, like thrall and DoT (T-023); all three on the
			// scored fights only, like the damage and expected damage (T-025).
			other = Math.max(0, s.scoredOtherDamage());

			// Rates over the time the damage and expected damage come from: the scored fights. A room with unscored fights in
			// it (the Sire's respiratory systems, T-020) would otherwise spread the damage gap over time spent elsewhere
			// and overstate luck's seconds, and understate DPS (T-023). The clock in the title stays the whole room's.
			luck = expected <= 0 || expected + other <= 0 || scoredClock <= 0
				? Double.NaN : scoredClock * (damage - expected) / (expected + other);
			dps = scoredClock <= 0 ? Double.NaN : damage / scoredClock;
			final double p = expected > 0 ? DamageMoments.percentile(damage, expected, s.damageVariance()) : -1;
			percentile = p < 0 ? Double.NaN : p;
			expectedDuration = Double.isNaN(luck) ? Double.NaN : clock + luck;

			// Eating comes out of uptime and gets a line of its own (Eric, 2026-10-27, option C; T-038). The lost share is
			// lost ticks over combat ticks, so the idle ticks' share is the eating ticks' share less of it.
			final double lostShare = s.ticksLostShare();
			final int lost = Math.max(0, s.ticksLost());
			final int eating = Math.min(lost, Math.max(0, s.ticksLostEating()));
			final int idle = lost - eating;
			uptime = lostShare < 0 ? Double.NaN : 1 - (lost == 0 ? lostShare : lostShare * idle / lost);
			uptimeCost = idle * SECONDS_PER_TICK;
			eatingCost = eating * SECONDS_PER_TICK;

			efficiency = s.efficiency();
			final boolean setupKnown = efficiency >= 0;
			executionLive = !setupKnown || Double.isNaN(uptime) ? Double.NaN : uptime * efficiency;
			// T-043: once the fight has ended at a boss with attack rules, the eating its avoidable hits cost counts against
			// Execution again, as a fifth part (Eric, 2026-10-27). The scope's figure is -1 until then, and for no rules.
			final double avoidableTicks = s.avoidableEatingTicks();
			final int ticks = s.combatTicks();
			final boolean avoidableKnown = avoidableTicks >= 0 && ticks > 0;
			avoidableEatingCost = avoidableKnown ? avoidableTicks * SECONDS_PER_TICK : Double.NaN;
			avoidableEatingShare = avoidableKnown ? Math.max(0, 1 - avoidableTicks / ticks) : Double.NaN;
			execution = avoidableKnown ? executionLive * avoidableEatingShare : executionLive;
			// The setup applies to the time you were attacking: neither idle nor eating. The same number as before T-038, when
			// uptime's seconds held the eating too.
			setupCost = setupKnown ? Math.max(0, clock - uptimeCost - eatingCost) * Math.max(0, 1 - efficiency)
				: Double.NaN;

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
				// Like gear, the damage the setup kept against what the best prayer you can use would have dealt (Eric,
				// 2026-10-26), not the share of attacks with it up: a weaker prayer (Chivalry with Piety unlocked) counted
				// as none at all. It's the same remainder of the setup loss its seconds are.
				prayerShare = Math.max(0, 1 - prayer);
				// Your gear's damage against the best gear on hand, not the share of attacks with nothing better to switch
				// to (Eric, 2026-10-26): blood fury worn over a torture flagged nearly every attack, so it read 6% while
				// costing 2 s. The seconds beside it were right all along.
				gearShare = Math.max(0, 1 - gear);
				drained = (long) s.attacksDrained() * 2 > made;
			}
			else
			{
				boostCost = gearCost = prayerCost = Double.NaN;
				boostShare = prayerShare = gearShare = Double.NaN;
				drained = false;
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
				// The title alone: the loadout overlay carries the figures (T-022).
				rows.add(titleRow(state.name, "", state, Colour.NEUTRAL));
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
		top(rows, n, scope, state, settings);
		// No loadout here: it's an overlay of its own (Eric, 2026-10-27; T-022).
		if (settings.details && state.ended)
		{
			details(rows, n, scope, state);
		}
		return rows;
	}

	/**
	 * The loadout overlay's rows (T-022): {@code ! Boss data unsure} on top when
	 * the warning applies, as the accuracy is what bad boss data gets wrong, then
	 * the loadout. Nothing at all when none of the figures is known.
	 */
	static List<Row> loadout(Loadout l, boolean warning)
	{
		final List<Row> figures = loadout(l);
		if (figures.isEmpty() || !warning)
		{
			return figures;
		}
		final List<Row> rows = new ArrayList<>(figures.size() + 1);
		rows.add(row(WARNING_TEXT, "", FontRole.BODY, FontRole.BODY, Colour.WARNING, Colour.NEUTRAL));
		rows.addAll(figures);
		return rows;
	}

	/**
	 * The DPS overlay's rows (T-073; T-022's loadout overlay): {@code vs} the
	 * monster, the attack's name as a main row of its own, {@code DPS} as a
	 * main row, then max hit, accuracy and the spec as sub-rows, each only when
	 * known. With no monster (before any fight, or gone idle) the figures that
	 * need one are unknown, so it's the attack's name, max hit and the spec's max
	 * alone. Nothing at all when none of the figures is known.
	 */
	private static List<Row> loadout(Loadout l)
	{
		final List<Row> rows = new ArrayList<>();
		if (l.dps >= 0)
		{
			rows.add(main("DPS", oneDecimal(l.dps)));
		}
		if (l.maxHit > 0)
		{
			rows.add(detail(" max hit", String.valueOf(l.maxHit), Colour.NEUTRAL));
		}
		if (l.accuracy >= 0)
		{
			rows.add(detail(" accuracy", percent(l.accuracy), Colour.NEUTRAL));
		}
		if (l.specMaxHit > 0)
		{
			// Two rows (Eric, 2026-10-27): the hit, then what a spec deals, burn and all.
			rows.add(detail(" spec", l.specMaxHit + " max" + (l.specAccuracy >= 0 ? DOT + percent(l.specAccuracy) : ""),
				Colour.NEUTRAL));
			if (l.specAverage >= 0)
			{
				rows.add(detail(" spec damage", specDamage(l), Colour.NEUTRAL));
			}
		}
		if (rows.isEmpty())
		{
			return rows;
		}
		// The attack's name on a row of its own (T-073), its figures under it. A name too wide for a saved width is cut.
		final String name = attackLabel(l.attack);
		final String label = name == null ? "Loadout" : name;
		rows.add(0, new Row(label, "", FontRole.EMPHASIS, FontRole.VALUE, Colour.NEUTRAL, Colour.NEUTRAL, label));
		if (l.versus != null && !l.versus.isEmpty())
		{
			final String versus = VERSUS + l.versus;
			rows.add(0, new Row(versus, "", FontRole.BODY, FontRole.BODY, Colour.NEUTRAL, Colour.NEUTRAL, versus));
		}
		return rows;
	}

	/**
	 * {@code 26.0 (8.2 burn)}: the spec's expected damage including what it deals
	 * beyond its hitsplats, that extra named in brackets; {@code 17.8} with none.
	 * No "exp" (Eric's standing rule).
	 */
	static String specDamage(Loadout l)
	{
		final double extra = Math.max(0, l.specExtra);
		final String total = oneDecimal(l.specAverage + extra);
		return extra >= 0.05 && l.specExtraKind != null
			? total + " (" + oneDecimal(extra) + " " + l.specExtraKind + ")" : total;
	}

	/**
	 * The attack's name as the loadout row and the Damage card show it, without
	 * the item's variant tags: the bracketed lower-case words like {@code (or)},
	 * {@code (i)}, {@code (e)} or {@code (uncharged)} that ornament kits and
	 * charges add. They don't change the attack and widened the panel until the
	 * row wrapped (Eric, 2026-10-26: "Abyssal tentacle (or)"). A cast is named by
	 * its spell alone (T-071), so a kill saved as {@code weapon (Spell)} shows the
	 * spell: only a bracket {@link Spell} knows by name is a spell, so
	 * {@code Bow of faerdhinen (c)} stays a bow. Null when there's no name yet.
	 */
	static String attackLabel(String attack)
	{
		if (attack == null)
		{
			return null;
		}
		final String label = VARIANT_TAG.matcher(attack).replaceAll("").trim();
		final Matcher m = SPELL.matcher(label);
		if (m.matches())
		{
			final Spell spell = Spell.forDisplayName(m.group(1));
			if (spell != null)
			{
				return spell.getDisplayName();
			}
		}
		return label.isEmpty() ? null : label;
	}

	/**
	 * {@code text} cut from the end, with {@link #CUT} added, until it fits
	 * {@code room} by {@code width}; whole when it already fits, empty when not
	 * even the dots do.
	 */
	static String cut(String text, ToIntFunction<String> width, int room)
	{
		if (width.applyAsInt(text) <= room)
		{
			return text;
		}
		for (int end = text.length() - 1; end > 0; end--)
		{
			final String shorter = text.substring(0, end).trim() + CUT;
			if (width.applyAsInt(shorter) <= room)
			{
				return shorter;
			}
		}
		return width.applyAsInt(CUT) <= room ? CUT : "";
	}

	private static Row title(State state, int ticks)
	{
		switch (state.view)
		{
			case TRIP:
				return row("Trip" + DOT + plural(state.count, "kill"), clock(ticks),
					FontRole.EMPHASIS, TITLE_CLOCK, Colour.NEUTRAL, Colour.NEUTRAL);
			case RAID:
				return titleRow(raidShortName(state.name) + DOT + plural(state.count, "room"), clock(ticks), state,
					Colour.NEUTRAL);
			default:
				final String name = !state.ended ? state.name
					: state.name + (state.killed ? DEAD_SUFFIX : " (ended)");
				return titleRow(name, clock(ticks), state, state.killed ? Colour.DEAD : Colour.NEUTRAL);
		}
	}

	/**
	 * The title row, its name in the boss's colour (Eric, 2026-10-28), killed
	 * or not: the words "(dead)" say that. Anything that isn't a boss keeps
	 * {@code otherwise}: white, or Dead's red after its kill.
	 */
	private static Row titleRow(String left, String right, State state, Colour otherwise)
	{
		final int rgb = state.titleColour();
		return rgb < 0
			? row(left, right, FontRole.EMPHASIS, TITLE_CLOCK, otherwise, Colour.NEUTRAL)
			: new Row(left, right, FontRole.EMPHASIS, TITLE_CLOCK, Colour.BOSS, Colour.NEUTRAL, null, null, rgb);
	}

	private static void top(List<Row> rows, Numbers n, Scope scope, State state, Settings settings)
	{
		// By the percentile when it's known (T-029), else by the seconds as before.
		final Colour luckColour = Double.isNaN(n.percentile) ? luckColour(n.luck, n.clock) : percentileBand(n.percentile);
		final boolean noData = n.expected <= 0;
		if (settings.luck && !state.warning)
		{
			// A short form for a panel saved too narrow: the band without its seconds (T-029).
			final String band = Double.isNaN(n.luck) || Double.isNaN(n.percentile) ? null : band(n.percentile);
			rows.add(new Row("Luck", luckFigure(n), FontRole.EMPHASIS, FontRole.VALUE, Colour.NEUTRAL, luckColour, null,
				band));
			specs(rows, n, scope, state);
		}
		if (state.warning)
		{
			rows.add(row(WARNING_TEXT, "", FontRole.BODY, FontRole.BODY, Colour.WARNING, Colour.NEUTRAL));
			rows.add(detail(" luck hidden until it's fixed", "", Colour.NEUTRAL));
		}
		if (!state.warning && !noData)
		{
			// No spaces round the slashes (Eric, 2026-10-26): they only widened the panel. Without them the label is one word,
			// which RuneLite can't wrap, so on a panel saved too narrow it would run into the figures: it falls back to
			// "Damage" instead, as the figures still read damage against expected.
			rows.add(new Row("Damage/expected", number(n.damage) + "/" + number(Math.round(n.expected)),
				FontRole.EMPHASIS, FontRole.VALUE, Colour.NEUTRAL, luckColour, "Damage"));
		}
		else
		{
			rows.add(main("Damage", number(n.damage)));
			if (!state.warning)
			{
				rows.add(detail(" no monster data yet", "", Colour.NEUTRAL));
			}
		}
		if (!state.warning)
		{
			drain(rows, n, scope, state);
		}
		// No DPS row (T-073, Eric 2026-10-28: "remove DPS"): the side panel has its tile, the DPS overlay the expected DPS.
		// T-043: avoidable eating counts once the fight is over, at a boss with attack rules, and only then: nothing about
		// the boss's attacks is shown live (Jagex's rule). A room still running may hold an ended fight's figure; it waits.
		final boolean avoidable = state.ended && !Double.isNaN(n.avoidableEatingCost);
		final double execution = avoidable ? n.execution : n.executionLive;
		rows.add(main("Execution", Double.isNaN(execution) ? UNKNOWN : partPercent(execution)));
		if (settings.parts != ExecutionParts.OFF)
		{
			parts(rows, n, settings.parts == ExecutionParts.COSTING, avoidable);
			// Under Execution too (Eric, 2026-10-26): the damage you took. Your own HP, nothing about the boss.
			rows.add(damageTaken(scope, avoidable));
			eating(rows, n, avoidable);
		}
	}

	/**
	 * T-051: under Luck after the fight, {@code  specs  +5 s}: the specials'
	 * share of luck (T-050), coloured as luck is. Hidden when it rounds to none,
	 * which it does with no draining specials of the player's own (the party's
	 * drains are no one's luck).
	 */
	private static void specs(List<Row> rows, Numbers n, Scope scope, State state)
	{
		if (!state.ended)
		{
			return;
		}
		final double seconds = scope.luckSeconds(scope.specLuckDamage());
		if (Double.isNaN(seconds) || roundHalfAway(seconds) == 0)
		{
			return;
		}
		rows.add(detail(SPECS, signedSeconds(seconds), luckColour(seconds, n.clock)));
	}

	/**
	 * T-051: the target's Defence under Damage/expected, as the drains are what
	 * moves the expected. Live, once a drain has been tracked on it: {@code
	 * defence  145 / 215}, or {@code 145 (floor)}, and Magic the same way when it
	 * was drained. After the fight: {@code defence  215 → 145 · +14 s}, with what
	 * the drains were worth (T-050); a raid or a trip, which has many targets,
	 * shows the worth alone. Nothing for an immune or untracked target, or a kill
	 * saved before T-049.
	 */
	private static void drain(List<Row> rows, Numbers n, Scope scope, State state)
	{
		if (!state.ended)
		{
			final LiveDrain live = state.drain;
			if (live != null && live.drained())
			{
				rows.add(detail(DEFENCE, level(live.defence, live.defenceBase, live.defenceFloor), Colour.NEUTRAL));
				if (live.magic != live.magicBase)
				{
					rows.add(detail(MAGIC, level(live.magic, live.magicBase, live.magicFloor), Colour.NEUTRAL));
				}
			}
			return;
		}
		final double seconds = scope.luckSeconds(scope.drainValueDamage());
		final String worth = Double.isNaN(seconds) || roundHalfAway(seconds) == 0 ? "" : signedSeconds(seconds);
		final int start = scope.defenceStart();
		final int end = scope.defenceEnd();
		final boolean defenceMoved = start >= 0 && end >= 0 && start != end;
		final boolean magicMoved = scope.magicStart() >= 0 && scope.magicEnd() >= 0
			&& scope.magicStart() != scope.magicEnd();
		if (start < 0)
		{
			// A raid or a trip: the drains' worth over its fights.
			if (!worth.isEmpty())
			{
				rows.add(detail(DEFENCE_DRAINS, worth, Colour.NEUTRAL));
			}
			return;
		}
		if (defenceMoved || !worth.isEmpty() && !magicMoved)
		{
			rows.add(detail(DEFENCE, start + ARROW + end + (worth.isEmpty() ? "" : DOT + worth), Colour.NEUTRAL));
		}
		if (magicMoved)
		{
			rows.add(detail(MAGIC, scope.magicStart() + ARROW + scope.magicEnd()
				+ (defenceMoved || worth.isEmpty() ? "" : DOT + worth), Colour.NEUTRAL));
		}
	}

	/** {@code 145 / 215}, or {@code 145 (floor)} once it can go no lower. */
	static String level(int now, int base, int floor)
	{
		return now <= floor ? now + " (floor)" : now + " / " + base;
	}

	/** {@code +14 s} or {@code -5 s}: positive is time saved. */
	static String signedSeconds(double seconds)
	{
		final long s = roundHalfAway(seconds);
		return (s > 0 ? "+" : "-") + Math.abs(s) + " s";
	}

	/**
	 * {@code  damage taken  263}, and after a ruled fight {@code 263 · 141
	 * avoidable} (T-043): plain, as it's the boss's mechanics. A panel saved too
	 * narrow drops the avoidable part.
	 */
	private static Row damageTaken(Scope scope, boolean avoidable)
	{
		final String taken = number(scope.damageTaken());
		final int part = scope.damageTakenAvoidable();
		if (!avoidable || part < 0)
		{
			return detail(" damage taken", taken, Colour.NEUTRAL);
		}
		return new Row(" damage taken", taken + DOT + number(part) + " avoidable", FontRole.DETAIL, FontRole.DETAIL,
			Colour.NEUTRAL, Colour.NEUTRAL, null, taken);
	}

	/**
	 * {@code  eating  4 s} (T-038): the time spent eating, live and after the
	 * fight, once it rounds to a second. After {@code damage taken} (Eric,
	 * 2026-10-27, over a main row of its own), with which it shows and hides; like
	 * it, not one of Execution's parts, and never coloured as a cost: the plugin
	 * can't tell why you ate.
	 */
	private static void eating(List<Row> rows, Numbers n, boolean avoidable)
	{
		// After a ruled fight, the rest of the eating: the avoidable part is Execution's now (T-043).
		final long seconds = roundHalfAway(avoidable ? Math.max(0, n.eatingCost - n.avoidableEatingCost) : n.eatingCost);
		if (seconds >= 1)
		{
			rows.add(detail(EATING, seconds + " s", Colour.NEUTRAL));
		}
	}

	private static void parts(List<Row> rows, Numbers n, boolean costingOnly, boolean avoidable)
	{
		final int before = rows.size();
		if (!Double.isNaN(n.uptime))
		{
			part(rows, " uptime", partPercent(n.uptime), n.uptimeCost, n.clock, costingOnly);
		}
		if (avoidable)
		{
			// T-043's fifth part, after uptime: the eating the avoidable hits cost, priced like the others.
			part(rows, AVOIDABLE_EATING, partPercent(n.avoidableEatingShare), n.avoidableEatingCost, n.clock, costingOnly);
		}
		// Without the setup figure (no monster data) only uptime can be priced; the canvas shows uptime alone there.
		if (!Double.isNaN(n.setupCost))
		{
			if (n.split)
			{
				// In Eric's order (2026-10-26): uptime, prayer, gear, stat boost.
				part(rows, " prayer", partPercent(n.prayerShare), n.prayerCost, n.clock, costingOnly);
				part(rows, " gear", partPercent(n.gearShare), n.gearCost, n.clock, costingOnly);
				// Option A (Eric): the boost's share stays 0-100% of a full boost held, the seconds carry the whole cost,
				// drains included. Mostly below base reads as the word, so it isn't taken for a plain 0%.
				part(rows, " stat boost", n.drained ? DRAINED : partPercent(n.boostShare), n.boostCost, n.clock,
					costingOnly);
			}
			else
			{
				// A scope with no boost share read: fights from before T-010. One row stands in for the three.
				part(rows, " setup", partPercent(n.efficiency), n.setupCost, n.clock, costingOnly);
			}
		}
		if (costingOnly && rows.size() == before)
		{
			rows.add(detail(" no losses", "", Colour.NEUTRAL));
		}
	}

	private static void part(List<Row> rows, String label, String figure, double cost, double clock, boolean costingOnly)
	{
		final boolean costing = isCosting(cost, clock);
		if (costingOnly && !costing)
		{
			return;
		}
		rows.add(detail(label, withSeconds(figure, cost), costing ? Colour.COST : Colour.NEUTRAL));
	}

	private static void details(List<Row> rows, Numbers n, Scope s, State state)
	{
		final List<Row> details = new ArrayList<>();
		// No "Duration / expected" (Eric, 2026-10-26): the clock is in the title and Luck sits right under it, so the row
		// only repeated them. In its place, your average (Eric, the same evening).
		details.addAll(average(n, state));
		if (s.expectedHits() > 0)
		{
			details.add(detail(" hits/expected", s.hits() + "/" + oneDecimal(s.expectedHits()), Colour.NEUTRAL));
		}
		if (s.thrallDamage() > 0)
		{
			details.add(detail(" thrall dmg", number(s.thrallDamage()), Colour.NEUTRAL));
		}
		if (s.reflectDamage() > 0)
		{
			details.add(detail(" recoil/veng dmg", number(s.reflectDamage()), Colour.NEUTRAL));
		}
		// One row a kind (T-062, Eric 2026-10-28): burn, poison, venom, bleed, then " DoT dmg" for what has no kind.
		dot(details, s.dotDamageByKind(), s.dotDamage(), " dmg");
		// The boss's doing rather than the player's, so after the fight only (own-output guardrail).
		if (s.healed() > 0)
		{
			details.add(detail(" boss healed", number(s.healed()), Colour.NEUTRAL));
		}
		// What the player took, beyond the live total under Execution (T-019). Nothing on which attack or style hit them:
		// that needs per-boss data, and would be after the fight only too.
		// By kind, as dealt is above (T-062).
		dot(details, s.dotTakenByKind(), s.dotTaken(), " taken");
		if (s.biggestHitTaken() > 0)
		{
			details.add(detail(" biggest hit taken", number(s.biggestHitTaken()), Colour.NEUTRAL));
		}
		if (s.damageTakenUnprotected() > 0)
		{
			details.add(detail(" without protection", number(s.damageTakenUnprotected()), Colour.NEUTRAL));
		}
		// No eating time here any more: it has its own row, live and after (T-038).
		if (!details.isEmpty())
		{
			// Under a main row of their own (Eric, 2026-10-26), as the parts sit under Execution.
			rows.add(blank());
			rows.add(main(SUMMARY, ""));
			rows.addAll(details);
		}
	}

	/**
	 * T-062: {@code  burn dmg}, {@code  poison dmg}, {@code  venom dmg},
	 * {@code  bleed dmg}, then {@code  DoT dmg} for the part of the total with no
	 * kind (an old kill's); a kind with 0 has no row. The same for taken.
	 */
	private static void dot(List<Row> rows, java.util.Map<String, Integer> byKind, int total, String suffix)
	{
		for (java.util.Map.Entry<String, Integer> line : DotKinds.split(byKind, total, DotKinds.REMAINDER).entrySet())
		{
			rows.add(detail(" " + line.getKey() + suffix, number(line.getValue()), Colour.NEUTRAL));
		}
	}

	/**
	 * " your average 1:59": the median clock of your recent like kills, to set
	 * against the title's (spec section 5, brought forward from v2). Eric made it
	 * a row of its own on 2026-10-26, then on 2026-10-27 dropped the comparisons
	 * under it (" this kill", " luck removed") and " luckier than", which read as
	 * the average's luck. After a kill only, outside a raid, and not under the
	 * warning.
	 */
	static List<Row> average(Numbers n, State state)
	{
		final LedgerHistory.Average a = state.average;
		if (a == null || state.view != View.FIGHT || !state.killed || state.warning
			|| Double.isNaN(n.expectedDuration))
		{
			return Collections.emptyList();
		}
		return Collections.singletonList(detail(" your average", duration(a.actual.median), Colour.NEUTRAL));
	}

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
		return withSeconds(partPercent(share), costSeconds);
	}

	/** A part's figure ({@code NN%} or {@code drained}), then {@code (-N s)} when the cost rounds to at least a second. */
	static String withSeconds(String figure, double costSeconds)
	{
		final long secs = Double.isNaN(costSeconds) ? 0 : roundHalfAway(costSeconds);
		return secs >= 1 ? figure + " (-" + secs + " s)" : figure;
	}

	/**
	 * {@code bottom 20% (14 s slower)}, {@code top 10% (9 s faster)},
	 * {@code average (2 s slower)} (T-029; Eric, 2026-10-27: a damage ratio read
	 * like "76% lucky"): where this fight's damage sits among fights with the same
	 * attacks, then what the rolls cost or saved on the clock. No bracket when the
	 * seconds round to none. Without T-028's variance (fights saved before it),
	 * the seconds alone; a dash when there's nothing to compare.
	 */
	static String luckFigure(Numbers n)
	{
		if (Double.isNaN(n.luck))
		{
			return UNKNOWN;
		}
		final String seconds = fasterOrSlower(n.luck);
		if (Double.isNaN(n.percentile))
		{
			return seconds;
		}
		final String band = band(n.percentile);
		return seconds.equals(EVEN) ? band : band + " (" + seconds + ")";
	}

	/**
	 * {@code bottom N%} under the middle half, {@code top N%} over it,
	 * {@code average} inside; N is never 0, as a fight can't be luckier than all
	 * of them.
	 */
	static String band(double percentile)
	{
		if (percentile < LOW_QUARTILE)
		{
			return "bottom " + Math.max(1, Math.round(100 * percentile)) + "%";
		}
		if (percentile > HIGH_QUARTILE)
		{
			return "top " + Math.max(1, Math.round(100 * (1 - percentile))) + "%";
		}
		return "average";
	}

	/** Luck's colour from the percentile: Better over the middle half, Cost under it, as " your average" goes (T-029). */
	static Colour percentileBand(double percentile)
	{
		if (percentile > HIGH_QUARTILE)
		{
			return Colour.BETTER;
		}
		return percentile < LOW_QUARTILE ? Colour.COST : Colour.NEUTRAL;
	}

	/**
	 * Seconds saved or lost, in words: {@code 10 s faster}, {@code 14 s slower},
	 * {@code even}. A sign on a time was ambiguous (Eric, 2026-10-26): "+10 s"
	 * read as ten seconds added to the kill, when it meant the rolls saved ten.
	 * Positive is time saved. Rounded half away from zero.
	 */
	static String fasterOrSlower(double secondsSaved)
	{
		final long r = roundHalfAway(secondsSaved);
		if (r == 0)
		{
			return EVEN;
		}
		return Math.abs(r) + " s " + (r > 0 ? "faster" : "slower");
	}

	static long roundHalfAway(double v)
	{
		return v < 0 ? -Math.round(-v) : Math.round(v);
	}

	static String percent(double share)
	{
		return Math.round(share * 100) + "%";
	}

	/**
	 * Execution's or a part's share: rounded, but never up to 100% unless it is
	 * 100%, so a fight that cost something doesn't read as perfect (parts:
	 * Orchestrator, PR #14 review; Execution: Eric, 2026-10-26).
	 */
	static String partPercent(double share)
	{
		if (Double.isNaN(share))
		{
			return UNKNOWN;
		}
		final long p = Math.round(share * 100);
		return (p >= 100 && share < 1 - 1e-9 ? 99 : p) + "%";
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
	 * 2026-10-26).
	 */
	static String clock(int ticks)
	{
		return hms(Math.max(0, (long) ticks) * 6 / 10);
	}

	/** A time in seconds, e.g. your average: whole seconds, cut down like the clock in the title. */
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

	/**
	 * A main row: bold label, regular value (T-073, Eric 2026-10-28; before it
	 * both were bold). Its sub-rows stay in the detail font.
	 */
	private static Row main(String left, String right)
	{
		return row(left, right, FontRole.EMPHASIS, FontRole.VALUE, Colour.NEUTRAL, Colour.NEUTRAL);
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

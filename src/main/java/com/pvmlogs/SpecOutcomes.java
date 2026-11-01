package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * A fight's expected damage averaged over how the player's own draining specials
 * could have landed (T-050). Pure: the plugin feeds it each attack's figures at
 * each state and the target's drains as they happened, so every rule here is
 * under test.
 *
 * <p><b>The state</b> is the target's stats after drains ({@link DrainRule.State}),
 * with T-049's floors, caps and resets. The fight keeps a small distribution over
 * them: each branch has its probability and the mean and second moment of the
 * damage booked so far under it.
 * <ul>
 * <li><b>An attack</b> adds its expectation and second moment at each state, as if
 * independent of what came before under that state.</li>
 * <li><b>One of the player's own draining specials</b> branches every state: a miss
 * (1 - p) through T-049's miss rule, a hit with damage d (p.P(d)) through its hit
 * rule, the special's own damage inside its branch. Branches that land on the
 * same state merge (a mixture of their means and second moments).</li>
 * <li><b>Everything else</b> (a party member's drain, a boss's reset or form change,
 * regeneration) applies to every state as it happened: none of it is the player's
 * luck.</li>
 * </ul>
 * The fight's figures are then E = &Sigma; &pi;&mu; and V = &Sigma; &pi;m2 - E&sup2;. The attacks stay as made
 * (their choice is skill); only the specials' outcomes are rolled.
 *
 * <p><b>The cap.</b> Past {@link #MAX_STATES} states the two nearest (by Defence,
 * then Magic and the magic defence bonus) merge, at their levels' average
 * weighted by their chances. That costs the merged branches' later attacks the
 * difference between their states' figures, a few levels of Defence apart
 * ({@link #worstMerge()} says how far). Two 60-max godswords and a hammer on a
 * 300-Defence boss, then 100 attacks: 193 states down to 32, the expected
 * damage off by 0.0002% and its variance by 0.3% (SpecOutcomesTest). Each attack
 * costs O(states), so the cap is also what holds the time down.
 */
final class SpecOutcomes
{
	/**
	 * States kept. A hammer makes 2 and two make 3; a Bandos godsword makes one per
	 * damage it can deal, so two of them on a high-Defence boss reach the cap,
	 * where neighbouring levels merge.
	 */
	static final int MAX_STATES = 32;

	/** An attack's damage at a state: its mean and its variance, or null where nothing is known there. */
	interface Figures
	{
		double[] at(DrainRule.State state);
	}

	/** A special's outcomes at a state, their probabilities summing to 1. */
	interface SpecialFigures
	{
		List<Outcome> at(DrainRule.State state);
	}

	/** One way a special can land: its chance, the damage it deals then (mean and second moment), and what the drain rule is fed. */
	static final class Outcome
	{
		final double p;
		final double mean;
		final double m2;
		final int hit;
		final boolean landed;

		Outcome(double p, double mean, double m2, int hit, boolean landed)
		{
			this.p = p;
			this.mean = mean;
			this.m2 = m2;
			this.hit = hit;
			this.landed = landed;
		}
	}

	private static final class Branch
	{
		DrainRule.State state;
		double p;
		double mean;
		double m2;

		Branch(DrainRule.State state, double p, double mean, double m2)
		{
			this.state = state;
			this.p = p;
			this.mean = mean;
			this.m2 = m2;
		}
	}

	private final int maxStates;
	private DrainRule.Target target;
	private List<Branch> branches = new ArrayList<>();
	private boolean branched;
	private int nextRegenTick = -1;
	// The same attacks, at the states they were really made at (as booked) and at the undrained state.
	private double realizedMean;
	private double realizedVariance;
	private double undrainedMean;
	private int attacks;
	private int specials;
	private int merges;
	private int worstMerge;

	SpecOutcomes(DrainRule.Target target, DrainRule.State start)
	{
		this(target, start, MAX_STATES);
	}

	/** With another cap: the tests measure what {@link #MAX_STATES} costs against none. */
	SpecOutcomes(DrainRule.Target target, DrainRule.State start, int maxStates)
	{
		this.target = target;
		this.maxStates = Math.max(1, maxStates);
		branches.add(new Branch(start, 1, 0, 0));
	}

	// ---- What happens ----

	/**
	 * An attack, booked at {@code bookedMean} and {@code bookedVariance} (the
	 * figures at the state it was really made at); {@code figures} gives it at the
	 * others. Unknown there, it is the booked figure.
	 */
	void attack(Figures figures, double bookedMean, double bookedVariance)
	{
		if (bookedMean < 0)
		{
			return;
		}
		final double bookedVar = Math.max(0, bookedVariance);
		for (Branch b : branches)
		{
			final double[] f = figures == null ? null : figures.at(b.state);
			final double mean = f == null || f[0] < 0 ? bookedMean : f[0];
			final double var = f == null || f[1] < 0 ? bookedVar : f[1];
			b.m2 += 2 * b.mean * mean + var + mean * mean;
			b.mean += mean;
		}
		final double[] base = figures == null ? null : figures.at(DrainRule.State.of(target));
		undrainedMean += base == null || base[0] < 0 ? bookedMean : base[0];
		realizedMean += bookedMean;
		realizedVariance += bookedVar;
		attacks++;
	}

	/**
	 * One of the player's own draining specials: every state branches on its
	 * outcomes, the special's damage inside each.
	 *
	 * @param tick when it landed, for the regeneration that follows
	 */
	void special(SpecialWeapon weapon, SpecialFigures figures, double bookedMean, double bookedVariance, int tick)
	{
		if (bookedMean < 0)
		{
			return;
		}
		final Map<DrainRule.State, Branch> next = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			List<Outcome> outcomes = figures == null ? null : figures.at(b.state);
			if (outcomes == null || outcomes.isEmpty())
			{
				outcomes = Collections.singletonList(new Outcome(1, bookedMean,
					Math.max(0, bookedVariance) + bookedMean * bookedMean, 0, false));
			}
			for (Outcome o : outcomes)
			{
				if (o.p <= 0)
				{
					continue;
				}
				final DrainRule.State after = DrainRule.apply(b.state, weapon, o.hit, o.landed, target).after;
				add(next, after, b.p * o.p, b.mean + o.mean, b.m2 + 2 * b.mean * o.mean + o.m2);
			}
		}
		final List<Outcome> atBase = figures == null ? null : figures.at(DrainRule.State.of(target));
		double baseMean = bookedMean;
		if (atBase != null && !atBase.isEmpty())
		{
			baseMean = 0;
			for (Outcome o : atBase)
			{
				baseMean += o.p * o.mean;
			}
		}
		undrainedMean += baseMean;
		realizedMean += bookedMean;
		realizedVariance += Math.max(0, bookedVariance);
		replace(next);
		branched |= branches.size() > 1;
		specials++;
		startRegen(tick);
	}

	/** A party member's drain, or one of the player's own the booking never saw: every state takes it as it happened. */
	void drainAsHappened(SpecialWeapon weapon, int hit, boolean landed, int tick)
	{
		if (weapon == null)
		{
			return;
		}
		final Map<DrainRule.State, Branch> next = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			add(next, DrainRule.apply(b.state, weapon, hit, landed, target).after, b.p, b.mean, b.m2);
		}
		replace(next);
		startRegen(tick);
	}

	/** The target's stats came back to the base of {@code to} (a new phase, Sotetseg's maze): every state with them. */
	void reset(DrainRule.Target to)
	{
		target = to;
		final Map<DrainRule.State, Branch> next = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			add(next, DrainRule.State.of(to), b.p, b.mean, b.m2);
		}
		replace(next);
		nextRegenTick = -1;
	}

	/** The target changed form and kept its drains (Zulrah, the Kalphite Queen): every state is measured against the new form. */
	void retarget(DrainRule.Target to)
	{
		target = to;
		final Map<DrainRule.State, Branch> next = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			final DrainRule.State s = b.state;
			add(next, new DrainRule.State(clamp(s.defence, to.defenceFloor, to.defence),
				clamp(s.magic, to.magicFloor, to.magic), Math.min(s.magicDefence, to.magicDefence),
				Math.min(s.attack, to.attack), Math.min(s.strength, to.strength), Math.min(s.ranged, to.ranged)),
				b.p, b.mean, b.m2);
		}
		replace(next);
	}

	/**
	 * Once a tick: drained levels come back one at a time every {@code regenTicks}
	 * (T-049), in every state alike. The timer starts at the first special
	 * resolved or drain taken, as the tracker's starts at its first drain.
	 */
	void tick(int now)
	{
		if (nextRegenTick < 0 || now < nextRegenTick || target.regenTicks <= 0)
		{
			return;
		}
		nextRegenTick += target.regenTicks;
		final Map<DrainRule.State, Branch> next = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			add(next, DrainRule.regenerate(b.state, target), b.p, b.mean, b.m2);
		}
		replace(next);
	}

	private void startRegen(int tick)
	{
		if (nextRegenTick < 0 && target.regenTicks > 0 && anyDrained())
		{
			nextRegenTick = tick + target.regenTicks;
		}
	}

	private boolean anyDrained()
	{
		final DrainRule.State base = DrainRule.State.of(target);
		for (Branch b : branches)
		{
			if (!b.state.equals(base))
			{
				return true;
			}
		}
		return false;
	}

	// ---- The figures ----

	/** E: the fight's expected damage from these attacks, averaged over the specials' outcomes. */
	double expected()
	{
		double e = 0;
		for (Branch b : branches)
		{
			e += b.p * b.mean;
		}
		return e;
	}

	/** V: its variance, the specials' outcomes included. */
	double variance()
	{
		final double e = expected();
		double m2 = 0;
		for (Branch b : branches)
		{
			m2 += b.p * b.m2;
		}
		return Math.max(0, m2 - e * e);
	}

	/** Whether a special has ever split the states; until one does, the figures are the attacks' as booked. */
	boolean branched()
	{
		return branched;
	}

	/** What averaging over the outcomes adds to the attacks' expected damage as booked: 0 until a special branches. */
	double expectedShift()
	{
		return branched ? expected() - realizedMean : 0;
	}

	/** What it adds to their variance. */
	double varianceShift()
	{
		return branched ? variance() - realizedVariance : 0;
	}

	/** The attacks' expectation as made, less what it would have been with nothing drained: the drain's worth. */
	/** T-141: the attacks' expected damage as booked, at the states they were really made at. */
	double realizedMean()
	{
		return realizedMean;
	}

	/** T-141: the same attacks at the undrained state. */
	double undrainedMean()
	{
		return undrainedMean;
	}

	double drainValueDamage()
	{
		return realizedMean - undrainedMean;
	}

	/** The same, averaged over the player's own specials' outcomes. */
	double drainValueExpected()
	{
		return (branched ? expected() : realizedMean) - undrainedMean;
	}

	/** E(realized) - E(averaged): the part of luck that came from the specials, positive when they landed better than average. */
	double specLuckDamage()
	{
		return -expectedShift();
	}

	int states()
	{
		return branches.size();
	}

	int attacks()
	{
		return attacks;
	}

	int specials()
	{
		return specials;
	}

	int merges()
	{
		return merges;
	}

	/** The widest merge the cap made, in levels (Defence, Magic and magic defence summed); 0 for none. */
	int worstMerge()
	{
		return worstMerge;
	}

	DrainRule.Target target()
	{
		return target;
	}

	/** The distinct states, likeliest first: what the attacks' figures are needed at. */
	List<DrainRule.State> states(int limit)
	{
		final List<Branch> sorted = new ArrayList<>(branches);
		sorted.sort((a, b) -> Double.compare(b.p, a.p));
		final List<DrainRule.State> out = new ArrayList<>();
		for (int i = 0; i < sorted.size() && i < limit; i++)
		{
			out.add(sorted.get(i).state);
		}
		return out;
	}

	/** Each state's probability, for the tests and the debug log. */
	Map<DrainRule.State, Double> probabilities()
	{
		final Map<DrainRule.State, Double> out = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			out.put(b.state, b.p);
		}
		return out;
	}

	// ---- Merging ----

	private static void add(Map<DrainRule.State, Branch> into, DrainRule.State state, double p, double mean, double m2)
	{
		final Branch b = into.get(state);
		if (b == null)
		{
			into.put(state, new Branch(state, p, mean, m2));
			return;
		}
		final double total = b.p + p;
		b.mean = (b.p * b.mean + p * mean) / total;
		b.m2 = (b.p * b.m2 + p * m2) / total;
		b.p = total;
	}

	private void replace(Map<DrainRule.State, Branch> next)
	{
		branches = new ArrayList<>(next.values());
		if (branches.size() <= maxStates)
		{
			return;
		}
		// Sorted once: a merge lands between its two neighbours, so the order holds.
		branches.sort(BY_LEVELS);
		while (branches.size() > maxStates)
		{
			mergeNearest();
		}
		// A merged state can round onto one already held: they become one.
		final Map<DrainRule.State, Branch> distinct = new LinkedHashMap<>();
		for (Branch b : branches)
		{
			add(distinct, b.state, b.p, b.mean, b.m2);
		}
		branches = new ArrayList<>(distinct.values());
	}

	private static final Comparator<Branch> BY_LEVELS = Comparator
		.comparingInt((Branch b) -> b.state.defence)
		.thenComparingInt(b -> b.state.magic)
		.thenComparingInt(b -> b.state.magicDefence)
		.thenComparingInt(b -> b.state.attack)
		.thenComparingInt(b -> b.state.strength)
		.thenComparingInt(b -> b.state.ranged);

	// The two neighbours (in level order) that are closest merge, at their weighted average.
	private void mergeNearest()
	{
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i + 1 < branches.size(); i++)
		{
			final int d = distance(branches.get(i).state, branches.get(i + 1).state);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = i;
			}
		}
		final Branch a = branches.get(best);
		final Branch b = branches.remove(best + 1);
		final double total = a.p + b.p;
		a.mean = (a.p * a.mean + b.p * b.mean) / total;
		a.m2 = (a.p * a.m2 + b.p * b.m2) / total;
		a.state = between(a.state, a.p, b.state, b.p);
		a.p = total;
		merges++;
		worstMerge = Math.max(worstMerge, bestDistance);
	}

	// The merged state: each level the two states' average, weighted by their chances.
	private static DrainRule.State between(DrainRule.State a, double pa, DrainRule.State b, double pb)
	{
		final double w = pb / (pa + pb);
		return new DrainRule.State(mix(a.defence, b.defence, w), mix(a.magic, b.magic, w),
			mix(a.magicDefence, b.magicDefence, w), mix(a.attack, b.attack, w), mix(a.strength, b.strength, w),
			mix(a.ranged, b.ranged, w));
	}

	private static int mix(int a, int b, double w)
	{
		return (int) Math.round(a + (b - a) * w);
	}

	// Levels apart in what the figures read: Defence, Magic and the magic defence bonus. The other stats only break ties.
	private static int distance(DrainRule.State a, DrainRule.State b)
	{
		return Math.abs(a.defence - b.defence) + Math.abs(a.magic - b.magic) + Math.abs(a.magicDefence - b.magicDefence);
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	// ---- A special's outcomes from its figures ----

	/**
	 * How a draining special can land at one state, from the figures
	 * {@link CombatCalc#figuresAt} gives there.
	 * <ul>
	 * <li><b>Drains that only ask whether it landed</b> (the warhammer, the elder
	 * maul, Weaken, the Accursed sceptre): a miss dealing nothing, and a hit
	 * dealing the special's damage given that it landed (its mean and second
	 * moment over its land chance).</li>
	 * <li><b>Drains by the damage</b> (the Bandos godsword, the bone dagger and
	 * Dorgeshuun crossbow, the anchor, Seercull, the Eye of ayak): a miss, and
	 * one outcome per damage the roll can deal, 0 to the max with a landed 0
	 * pushed to 1 (wiki, Successful hit) and the per-hitsplat cap applied. The
	 * damage is scaled so the outcomes' mean and second moment are the special's
	 * as the combat model gives them.</li>
	 * <li><b>The Tonalztics of ralos</b>: 0, 1 or 2 glaives landing, each on its own
	 * accuracy roll; a glaive's damage from the special's mean and second moment.</li>
	 * </ul>
	 *
	 * @param land     the chance it lands at all
	 * @param accuracy one roll's chance (a glaive's, for the Tonalztics)
	 * @param mean     the special's expected damage, misses included
	 * @param variance its variance
	 * @param max      its max hit
	 * @param cap      the per-hitsplat cap
	 */
	static List<Outcome> outcomes(SpecialWeapon weapon, double land, double accuracy, double mean, double variance,
		int max, int cap)
	{
		final List<Outcome> out = new ArrayList<>();
		if (mean < 0)
		{
			return out;
		}
		final double m2 = Math.max(0, variance) + mean * mean;
		final double p = Math.max(0, Math.min(1, land));
		if (weapon == SpecialWeapon.TONALZTICS_OF_RALOS)
		{
			final double r = Math.max(0, Math.min(1, accuracy));
			if (r <= 0)
			{
				out.add(new Outcome(1, 0, 0, 0, false));
				return out;
			}
			final double g = mean / (2 * r);
			final double g2 = Math.max(g * g, (m2 - 2 * r * r * g * g) / (2 * r));
			for (int k = 0; k <= 2; k++)
			{
				final double pk = (k == 1 ? 2 : 1) * Math.pow(r, k) * Math.pow(1 - r, 2 - k);
				out.add(new Outcome(pk, k * g, k * g2 + k * (k - 1) * g * g, k, k > 0));
			}
			return out;
		}
		if (p <= 0)
		{
			out.add(new Outcome(1, 0, 0, 0, false));
			return out;
		}
		if (!byDamage(weapon))
		{
			if (p < 1)
			{
				out.add(new Outcome(1 - p, 0, 0, 0, false));
			}
			out.add(new Outcome(p, mean / p, m2 / p, 1, true));
			return out;
		}
		// One outcome per damage dealt: the roll 0..max, a landed 0 pushed to 1, held to the cap.
		final int top = Math.max(1, max);
		final int limit = Math.max(1, Math.min(cap, top));
		final double[] chance = new double[limit + 1];
		for (int roll = 0; roll <= top; roll++)
		{
			chance[Math.min(limit, Math.max(1, roll))] += 1.0 / (top + 1);
		}
		double rawMean = 0;
		double rawM2 = 0;
		for (int d = 1; d <= limit; d++)
		{
			rawMean += chance[d] * d;
			rawM2 += chance[d] * d * (double) d;
		}
		// The damage dealt mapped onto the model's, a + b.d, so the landed outcomes' mean and second moment are the
		// model's given a landed hit (its mean and m2 over p); the drain still goes by the d dealt. Where the roll has no
		// spread (a cap at 1), the model's variance stays inside the one outcome.
		final double landedMean = mean / p;
		final double landedVar = Math.max(0, m2 / p - landedMean * landedMean);
		final double rawVar = Math.max(0, rawM2 - rawMean * rawMean);
		final double b = rawVar <= 0 ? 0 : Math.sqrt(landedVar / rawVar);
		final double a = landedMean - b * rawMean;
		final double inside = rawVar <= 0 ? landedVar : 0;
		if (p < 1)
		{
			out.add(new Outcome(1 - p, 0, 0, 0, false));
		}
		for (int dealt = 1; dealt <= limit; dealt++)
		{
			if (chance[dealt] > 0)
			{
				final double d = a + b * dealt;
				out.add(new Outcome(p * chance[dealt], d, d * d + inside, dealt, true));
			}
		}
		return out;
	}

	/** Whether the special drains by the damage it deals, rather than by landing. */
	static boolean byDamage(SpecialWeapon weapon)
	{
		if (weapon == null)
		{
			return false;
		}
		switch (weapon)
		{
			case BANDOS_GODSWORD:
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
			case BARRELCHEST_ANCHOR:
			case SEERCULL:
			case EYE_OF_AYAK:
				return true;
			default:
				return false;
		}
	}
}

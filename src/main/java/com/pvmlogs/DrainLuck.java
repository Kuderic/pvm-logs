package com.pvmlogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * The plugin's side of T-050: each fight's {@link SpecOutcomes}, fed the
 * attacks as they are booked, the figures at each state as the combat model
 * gives them on the tick each attack was made, and the target's drains as
 * T-049's tracker saw them happen. Reads the tracker; never changes it.
 *
 * <p><b>Which tick's figures.</b> Every tick of a fight on a drainable target,
 * {@link #snapshot} asks the combat model for the figures at each state the
 * distribution holds (and at the undrained and the real state). An attack takes
 * the snapshot of the tick it was made on, as its booked figure takes that
 * tick's: a hammer thrown and swapped out of is still judged as a hammer. Its
 * figure at a state is the booked figure plus the snapshot's difference between
 * that state and the real one, so at the real state it is exactly the booked
 * figure.
 *
 * <p><b>The player's own specials.</b> The booking branches the distribution;
 * the tracker's own drain for the same special, which comes the same tick (a
 * melee special, a projectile booked as it lands) or before it (the elder maul,
 * read from the throw's experience), is then matched to it and not applied a
 * second time. An own drain no booking claims within {@link #MATCH_TICKS} (a
 * special the booking didn't see as one) is applied as it happened, as a party
 * member's is.
 */
final class DrainLuck
{
	/** Ticks an own special's booking and the tracker's drain for it may be apart. */
	static final int MATCH_TICKS = 6;
	/** Ticks of snapshots kept: the longest a projectile is booked after it was thrown. */
	static final int SNAPSHOT_TICKS = 20;
	/** Ended fights kept, for a shot still landing after its fight closed (T-021). */
	static final int KEEP_FIGHTS = 8;

	/** The combat model's figures at a state ({@link CombatCalc#figuresAt}). */
	interface Evaluator
	{
		double[] at(int npcId, DrainRule.Target base, DrainRule.State state);
	}

	/** Where a change in a fight's shifts goes: the trip. */
	interface ShiftSink
	{
		void shift(double expectedDelta, double varianceDelta);
	}

	private static final Map<Integer, SpecialWeapon> BY_ITEM = new HashMap<>();

	static
	{
		for (SpecialWeapon weapon : SpecialWeapon.values())
		{
			for (int id : weapon.getItemID())
			{
				BY_ITEM.put(id, weapon);
			}
		}
	}

	/** RuneLite's special for a weapon, or null. */
	static SpecialWeapon weaponFor(int itemId)
	{
		return BY_ITEM.get(itemId);
	}

	/** One tick's figures at each state. */
	static final class Snapshot
	{
		final int tick;
		final DrainRule.State realized;
		final Map<Long, double[]> figures = new HashMap<>();
		final Map<Long, DrainRule.State> states = new HashMap<>();
		/** T-141: the real state with one stat's drains undone ({@code stat}), or one source's share of them ({@code stat|source}). */
		final Map<String, DrainRule.State> undo = new LinkedHashMap<>();

		Snapshot(int tick, DrainRule.State realized)
		{
			this.tick = tick;
			this.realized = realized;
		}

		void put(DrainRule.State s, double[] f)
		{
			figures.put(key(s), f);
			states.put(key(s), s);
		}

		/** The figures at this state, or at the nearest state the snapshot holds. */
		double[] near(DrainRule.State s)
		{
			final double[] exact = figures.get(key(s));
			if (exact != null)
			{
				return exact;
			}
			double[] best = null;
			int bestDistance = Integer.MAX_VALUE;
			for (Map.Entry<Long, DrainRule.State> e : states.entrySet())
			{
				final DrainRule.State o = e.getValue();
				final int d = Math.abs(o.defence - s.defence) + Math.abs(o.magic - s.magic)
					+ Math.abs(o.magicDefence - s.magicDefence);
				if (d < bestDistance)
				{
					bestDistance = d;
					best = figures.get(e.getKey());
				}
			}
			return best;
		}

		static long key(DrainRule.State s)
		{
			return ((long) (s.defence & 0xFFFFF) << 40) | ((long) (s.magic & 0xFFFFF) << 20) | (s.magicDefence + 0x7FFFF
				& 0xFFFFF);
		}
	}

	private static final class OwnEvent
	{
		final int tick;
		final SpecialWeapon weapon;
		final int hit;
		final boolean landed;
		final boolean applied;

		OwnEvent(int tick, SpecialWeapon weapon, int hit, boolean landed, boolean applied)
		{
			this.tick = tick;
			this.weapon = weapon;
			this.hit = hit;
			this.landed = landed;
			this.applied = applied;
		}
	}

	private static final class Entry
	{
		final int npcIndex;
		final SpecOutcomes outcomes;
		final Map<Integer, Snapshot> snapshots = new LinkedHashMap<>();
		DrainEvent lastEvent;
		final List<Integer> ownBookings = new ArrayList<>(2);
		final List<OwnEvent> ownEvents = new ArrayList<>(2);
		double sentExpected;
		double sentVariance;
		boolean open = true;
		// T-141: what each source took off each stat in the fight (stat -> source key -> amount), read from the tracker's
		// events as they come; the drains before the fight under BEFORE.
		final Map<String, Map<String, Integer>> drainedBy = new LinkedHashMap<>();
		DrainEvent shareEvent;
		// T-141: the expected damage each stat's drains added, and each source's per stat, as made.
		final Map<String, Double> valueByStat = new LinkedHashMap<>();
		final Map<String, Map<String, Double>> valueBySource = new LinkedHashMap<>();
		Integer bonusAtStart;
		Integer bonusAtEnd;

		Entry(int npcIndex, SpecOutcomes outcomes)
		{
			this.npcIndex = npcIndex;
			this.outcomes = outcomes;
		}
	}

	private final IntFunction<DrainTracker.Tracked> tracked;
	private final Evaluator evaluator;
	private final ShiftSink trip;
	private final PerfProbe perf;
	private final Map<Fight, Entry> entries = new IdentityHashMap<>();
	private final List<Fight> order = new ArrayList<>();

	DrainLuck(IntFunction<DrainTracker.Tracked> tracked, Evaluator evaluator, ShiftSink trip, PerfProbe perf)
	{
		this.tracked = tracked;
		this.evaluator = evaluator;
		this.trip = trip == null ? (e, v) -> { } : trip;
		this.perf = perf;
	}

	/**
	 * A fight opens on a target the tracker follows. Nothing is kept for a target
	 * that can't be drained (immune) or whose stats aren't known: its drain value
	 * stays unknown.
	 */
	void start(Fight fight, DrainTracker.Tracked target)
	{
		if (fight == null || target == null || target.target.immune)
		{
			return;
		}
		final Entry e = new Entry(fight.getTargetIndex(), new SpecOutcomes(target.target, target.state));
		e.lastEvent = target.events.isEmpty() ? null : target.events.get(target.events.size() - 1);
		e.shareEvent = e.lastEvent;
		// T-144: what the target opens the fight drained with, by the source that made it, from the tracker's events: a
		// drain carried from an earlier fight of the room stays with its special. BEFORE keeps only what the tracker didn't
		// see made.
		for (Map.Entry<String, Map<String, Integer>> stat : carried(target).entrySet())
		{
			for (Map.Entry<String, Integer> source : stat.getValue().entrySet())
			{
				took(e, stat.getKey(), source.getKey(), source.getValue());
			}
		}
		entries.put(fight, e);
		order.add(fight);
		while (order.size() > KEEP_FIGHTS)
		{
			entries.remove(order.remove(0));
		}
		push(fight, e);
	}

	boolean follows(Fight fight)
	{
		return entries.containsKey(fight);
	}

	/** The fight's outcomes, for the tests and the debug log; null where none are kept. */
	SpecOutcomes outcomes(Fight fight)
	{
		final Entry e = entries.get(fight);
		return e == null ? null : e.outcomes;
	}

	/** This tick's figures at each state the fight's distribution holds, for the attacks made this tick. */
	void snapshot(Fight fight, int npcId, int tick)
	{
		final Entry e = entries.get(fight);
		if (e == null || !e.open)
		{
			return;
		}
		final long started = PerfProbe.start(perf);
		final DrainTracker.Tracked t = tracked.apply(e.npcIndex);
		final DrainRule.Target base = e.outcomes.target();
		final DrainRule.State realized = t != null && t.target == base ? t.state : DrainRule.State.of(base);
		final Snapshot snap = new Snapshot(tick, realized);
		final List<DrainRule.State> wanted = new ArrayList<>(e.outcomes.states(SpecOutcomes.MAX_STATES));
		wanted.add(realized);
		wanted.add(DrainRule.State.of(base));
		// T-141: and the real state with each stat's drains undone, and each source's share where a stat has several.
		if (t != null)
		{
			readShares(e, t);
		}
		undoStates(e, base, realized, snap.undo);
		wanted.addAll(snap.undo.values());
		for (DrainRule.State s : wanted)
		{
			if (!snap.figures.containsKey(Snapshot.key(s)))
			{
				final double[] f = evaluator.at(npcId, base, s);
				if (f != null)
				{
					snap.put(s, f);
				}
			}
		}
		e.snapshots.put(tick, snap);
		final double[] real = snap.figures.get(Snapshot.key(realized));
		if (real != null && real.length > CombatCalc.AT_MAGIC_DEFENCE)
		{
			final int bonus = (int) real[CombatCalc.AT_MAGIC_DEFENCE];
			e.bonusAtStart = e.bonusAtStart == null ? bonus : e.bonusAtStart;
			e.bonusAtEnd = bonus;
		}
		for (Iterator<Integer> it = e.snapshots.keySet().iterator(); it.hasNext(); )
		{
			if (tick - it.next() > SNAPSHOT_TICKS)
			{
				it.remove();
			}
		}
		PerfProbe.stop(perf, PerfProbe.Slot.DRAIN_OUTCOMES, started);
	}

	/**
	 * An attack's expectation was booked into the fight, as made on
	 * {@code madeTick}: {@code special} with {@code weapon} in hand, at the
	 * booked figures.
	 */
	void booked(Fight fight, int madeTick, int tick, boolean special, SpecialWeapon weapon, double mean, double variance)
	{
		final Entry e = entries.get(fight);
		if (e == null || mean < 0)
		{
			return;
		}
		final long started = PerfProbe.start(perf);
		final Snapshot snap = snapshotFor(e, madeTick, tick);
		final double bookedVar = Math.max(0, variance);
		// T-141: what this attack adds to the drain value, as SpecOutcomes counts it, to split by stat and source below.
		final double realizedBefore = e.outcomes.realizedMean();
		final double undrainedBefore = e.outcomes.undrainedMean();
		if (special && weapon != null && DrainRule.drains(weapon))
		{
			e.outcomes.special(weapon, snap == null ? null : s ->
			{
				final double[] f = snap.near(s);
				final double[] r = snap.near(snap.realized);
				if (f == null || r == null || f[CombatCalc.AT_SPEC_MEAN] < 0 || r[CombatCalc.AT_SPEC_MEAN] < 0)
				{
					return null;
				}
				final double m = Math.max(0, mean + f[CombatCalc.AT_SPEC_MEAN] - r[CombatCalc.AT_SPEC_MEAN]);
				final double v = f[CombatCalc.AT_SPEC_VARIANCE] < 0 || r[CombatCalc.AT_SPEC_VARIANCE] < 0 ? bookedVar
					: Math.max(0, bookedVar + f[CombatCalc.AT_SPEC_VARIANCE] - r[CombatCalc.AT_SPEC_VARIANCE]);
				return SpecOutcomes.outcomes(weapon, f[CombatCalc.AT_SPEC_LAND], f[CombatCalc.AT_SPEC_ACCURACY], m, v,
					(int) f[CombatCalc.AT_SPEC_MAX], (int) f[CombatCalc.AT_CAP]);
			}, mean, bookedVar, tick);
			claimOwnEvent(e, tick);
		}
		else
		{
			final int meanAt = special ? CombatCalc.AT_SPEC_MEAN : CombatCalc.AT_MEAN;
			final int varAt = special ? CombatCalc.AT_SPEC_VARIANCE : CombatCalc.AT_VARIANCE;
			e.outcomes.attack(snap == null ? null : s ->
			{
				final double[] f = snap.near(s);
				final double[] r = snap.near(snap.realized);
				if (f == null || r == null || f[meanAt] < 0 || r[meanAt] < 0)
				{
					return null;
				}
				final double v = f[varAt] < 0 || r[varAt] < 0 ? bookedVar
					: Math.max(0, bookedVar + f[varAt] - r[varAt]);
				return new double[]{Math.max(0, mean + f[meanAt] - r[meanAt]), v};
			}, mean, bookedVar);
		}
		final double added = e.outcomes.realizedMean() - realizedBefore - (e.outcomes.undrainedMean() - undrainedBefore);
		attribute(e, snap, added, special ? CombatCalc.AT_SPEC_MEAN : CombatCalc.AT_MEAN);
		push(fight, e);
		PerfProbe.stop(perf, PerfProbe.Slot.DRAIN_OUTCOMES, started);
	}

	// The tick the attack was made on, else the last before it, else the latest there is.
	private static Snapshot snapshotFor(Entry e, int madeTick, int tick)
	{
		Snapshot best = e.snapshots.get(madeTick);
		if (best != null)
		{
			return best;
		}
		Snapshot latest = null;
		for (Snapshot s : e.snapshots.values())
		{
			if (s.tick <= madeTick && (best == null || s.tick > best.tick))
			{
				best = s;
			}
			if (s.tick <= tick && (latest == null || s.tick > latest.tick))
			{
				latest = s;
			}
		}
		return best != null ? best : latest;
	}

	// The booking of an own special: the tracker's drain for it, if already in, is spent; else it waits for it.
	private static void claimOwnEvent(Entry e, int tick)
	{
		for (Iterator<OwnEvent> it = e.ownEvents.iterator(); it.hasNext(); )
		{
			final OwnEvent o = it.next();
			if (Math.abs(tick - o.tick) <= MATCH_TICKS)
			{
				it.remove();
				return;
			}
		}
		e.ownBookings.add(tick);
	}

	/**
	 * Once a tick, after the tracker's: the target's drains since the last tick
	 * (the party's, an own special the booking didn't see, a reset, a change of
	 * form) are applied to every state as they happened, and drained levels come
	 * back as they regenerate.
	 */
	void tick(int now)
	{
		if (entries.isEmpty())
		{
			return;
		}
		final long started = PerfProbe.start(perf);
		for (Map.Entry<Fight, Entry> me : entries.entrySet())
		{
			final Entry e = me.getValue();
			if (!e.open)
			{
				continue;
			}
			final DrainTracker.Tracked t = tracked.apply(e.npcIndex);
			if (t != null)
			{
				readShares(e, t);
				readEvents(e, t, now);
				if (t.target != e.outcomes.target())
				{
					e.outcomes.retarget(t.target);
				}
			}
			expire(e, now);
			e.outcomes.tick(now);
			push(me.getKey(), e);
		}
		PerfProbe.stop(perf, PerfProbe.Slot.DRAIN_OUTCOMES, started);
	}

	private void readEvents(Entry e, DrainTracker.Tracked t, int now)
	{
		final List<DrainEvent> events = t.events;
		int from = 0;
		if (e.lastEvent != null)
		{
			for (int i = events.size() - 1; i >= 0; i--)
			{
				if (events.get(i) == e.lastEvent)
				{
					from = i + 1;
					break;
				}
			}
		}
		for (int i = from; i < events.size(); i++)
		{
			final DrainEvent ev = events.get(i);
			e.lastEvent = ev;
			if (ev.isReset())
			{
				e.outcomes.reset(t.target);
				continue;
			}
			if (ev.isRegen() || !(ev.isDrain() || ev.isSkip()))
			{
				// Regeneration runs on the outcomes' own timer, in every state alike.
				continue;
			}
			final SpecialWeapon weapon = weapon(ev.weapon);
			if (!ev.own)
			{
				if (ev.isDrain())
				{
					e.outcomes.drainAsHappened(weapon, ev.hit, ev.landed, now);
				}
				continue;
			}
			// The player's own: its booking branched the outcomes already, or will within a few ticks.
			if (!e.ownBookings.isEmpty())
			{
				e.ownBookings.remove(0);
				continue;
			}
			e.ownEvents.add(new OwnEvent(now, weapon, ev.hit, ev.landed, ev.isDrain()));
		}
	}

	// An own drain no booking claimed in time is applied as it happened; a booking whose drain never came is let go.
	private static void expire(Entry e, int now)
	{
		for (Iterator<OwnEvent> it = e.ownEvents.iterator(); it.hasNext(); )
		{
			final OwnEvent o = it.next();
			if (now - o.tick > MATCH_TICKS)
			{
				it.remove();
				if (o.applied)
				{
					e.outcomes.drainAsHappened(o.weapon, o.hit, o.landed, now);
				}
			}
		}
		e.ownBookings.removeIf(t -> now - t > MATCH_TICKS);
	}

	private static SpecialWeapon weapon(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return SpecialWeapon.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}

	// The fight's figures as they stand, and the change in its shifts to the trip.
	private void push(Fight fight, Entry e)
	{
		final SpecOutcomes o = e.outcomes;
		final double expected = o.expectedShift();
		final double variance = o.varianceShift();
		trip.shift(expected - e.sentExpected, variance - e.sentVariance);
		e.sentExpected = expected;
		e.sentVariance = variance;
		fight.recordSpecOutcomes(expected, variance, o.drainValueDamage(), o.drainValueExpected());
		fight.recordDrainValues(e.valueByStat, e.valueBySource, e.bonusAtStart, e.bonusAtEnd);
	}

	// ---- T-141: the value per stat and per source ----

	/**
	 * The source key for drains the target already had when the fight opened. T-144: only those the tracker didn't see
	 * made: drained before it followed the target, or by events dropped past {@link DrainTracker#MAX_EVENTS}.
	 */
	static final String BEFORE = "before this fight";

	/**
	 * T-144: what each source took off each stat of a target since the tracker followed it ({@code stat -> source key ->
	 * amount}), from its events: the drains it opens a fight with. A reset puts the stats back, so nobody's drains are
	 * left. What the first event kept already found drained, and anything drained with no event at all, is
	 * {@link #BEFORE}'s.
	 */
	static Map<String, Map<String, Integer>> carried(DrainTracker.Tracked t)
	{
		final Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
		final DrainRule.Target base = t.target;
		if (t.events.isEmpty())
		{
			add(out, DrainRecord.StatDrain.DEFENCE, BEFORE, base.defence - t.state.defence);
			add(out, DrainRecord.StatDrain.MAGIC, BEFORE, base.magic - t.state.magic);
			add(out, DrainRecord.StatDrain.MAGIC_DEFENCE, BEFORE, DrainRule.magicDefenceReduction(t.state, base));
			return out;
		}
		final DrainEvent first = t.events.get(0);
		add(out, DrainRecord.StatDrain.DEFENCE, BEFORE, base.defence - first.defenceBefore);
		add(out, DrainRecord.StatDrain.MAGIC, BEFORE, base.magic - first.magicBefore);
		add(out, DrainRecord.StatDrain.MAGIC_DEFENCE, BEFORE, base.magicDefence - first.magicDefenceBefore);
		for (DrainEvent ev : t.events)
		{
			if (ev.isReset())
			{
				out.clear();
				continue;
			}
			if (!ev.isDrain() || ev.weapon == null)
			{
				continue;
			}
			final String source = DrainRecord.sourceKey(ev.weapon, ev.own);
			add(out, DrainRecord.StatDrain.DEFENCE, source, ev.defenceBefore - ev.defenceAfter);
			add(out, DrainRecord.StatDrain.MAGIC, source, ev.magicBefore - ev.magicAfter);
			add(out, DrainRecord.StatDrain.MAGIC_DEFENCE, source, ev.magicDefenceBefore - ev.magicDefenceAfter);
		}
		return out;
	}

	private static void add(Map<String, Map<String, Integer>> into, String stat, String source, int amount)
	{
		if (amount > 0)
		{
			into.computeIfAbsent(stat, k -> new LinkedHashMap<>()).merge(source, amount, Integer::sum);
		}
	}
	private static final String[] STATS = {DrainRecord.StatDrain.DEFENCE, DrainRecord.StatDrain.MAGIC,
		DrainRecord.StatDrain.MAGIC_DEFENCE};

	private static void took(Entry e, String stat, String source, int amount)
	{
		if (amount > 0)
		{
			e.drainedBy.computeIfAbsent(stat, k -> new LinkedHashMap<>()).merge(source, amount, Integer::sum);
		}
	}

	// The tracker's drains since the last read, by source and stat. A reset puts the stats back: nobody's drains remain.
	private static void readShares(Entry e, DrainTracker.Tracked t)
	{
		final List<DrainEvent> events = t.events;
		int from = 0;
		if (e.shareEvent != null)
		{
			for (int i = events.size() - 1; i >= 0; i--)
			{
				if (events.get(i) == e.shareEvent)
				{
					from = i + 1;
					break;
				}
			}
		}
		for (int i = from; i < events.size(); i++)
		{
			final DrainEvent ev = events.get(i);
			e.shareEvent = ev;
			if (ev.isReset())
			{
				e.drainedBy.clear();
				continue;
			}
			if (!ev.isDrain() || ev.weapon == null)
			{
				continue;
			}
			final String source = DrainRecord.sourceKey(ev.weapon, ev.own);
			took(e, DrainRecord.StatDrain.DEFENCE, source, ev.defenceBefore - ev.defenceAfter);
			took(e, DrainRecord.StatDrain.MAGIC, source, ev.magicBefore - ev.magicAfter);
			took(e, DrainRecord.StatDrain.MAGIC_DEFENCE, source, ev.magicDefenceBefore - ev.magicDefenceAfter);
		}
	}

	/** How far a stat is drained now: levels, or the Eye of ayak's reduction. */
	static int drainedNow(String stat, DrainRule.Target base, DrainRule.State s)
	{
		switch (stat)
		{
			case DrainRecord.StatDrain.DEFENCE:
				return Math.max(0, base.defence - s.defence);
			case DrainRecord.StatDrain.MAGIC:
				return Math.max(0, base.magic - s.magic);
			default:
				return DrainRule.magicDefenceReduction(s, base);
		}
	}

	/** The state with {@code back} of a stat's drains given back. */
	static DrainRule.State giveBack(String stat, DrainRule.State s, int back)
	{
		switch (stat)
		{
			case DrainRecord.StatDrain.DEFENCE:
				return new DrainRule.State(s.defence + back, s.magic, s.magicDefence, s.attack, s.strength, s.ranged);
			case DrainRecord.StatDrain.MAGIC:
				return new DrainRule.State(s.defence, s.magic + back, s.magicDefence, s.attack, s.strength, s.ranged);
			default:
				return new DrainRule.State(s.defence, s.magic, s.magicDefence + back, s.attack, s.strength, s.ranged);
		}
	}

	/**
	 * The real state with each drained stat's drains undone (key {@code stat}) and, where a stat has two sources or more,
	 * with each one's share undone ({@code stat|source}): its share of what was taken off the stat in the fight, of what
	 * is drained now. Regeneration and floors so fall on the sources alike, a reading: which source's levels come back
	 * first, or which a floor stopped, isn't kept.
	 */
	private static void undoStates(Entry e, DrainRule.Target base, DrainRule.State real, Map<String, DrainRule.State> into)
	{
		for (String stat : STATS)
		{
			final int now = drainedNow(stat, base, real);
			if (now <= 0)
			{
				continue;
			}
			into.put(stat, giveBack(stat, real, now));
			final Map<String, Integer> by = e.drainedBy.get(stat);
			if (by == null || by.size() < 2)
			{
				continue;
			}
			final double total = by.values().stream().mapToInt(Integer::intValue).sum();
			for (Map.Entry<String, Integer> s : by.entrySet())
			{
				into.put(stat + "|" + s.getKey(), giveBack(stat, real, (int) Math.round(now * s.getValue() / total)));
			}
		}
	}

	/**
	 * An attack's share of the drain value ({@code added}: what it added, as SpecOutcomes counts it), split over the stats
	 * by how much its figure falls with each stat's drains undone, and within a stat over its sources the same way. An
	 * attack that rolls against one stat only (a melee blow against Defence, a cast against Magic and the magic defence
	 * bonus) gives it all to that stat. The parts sum to {@code added}, so the stats sum to the combined value.
	 */
	private static void attribute(Entry e, Snapshot snap, double added, int at)
	{
		if (added == 0)
		{
			return;
		}
		final Map<String, Double> byStat = weights(snap, at, null);
		if (byStat.isEmpty())
		{
			// No figures to say which: the first stat drained now, else the first drained in the fight.
			final String stat = firstDrained(e, snap);
			if (stat != null)
			{
				byStat.put(stat, 1.0);
			}
			else
			{
				return;
			}
		}
		final double sum = byStat.values().stream().mapToDouble(Double::doubleValue).sum();
		for (Map.Entry<String, Double> w : byStat.entrySet())
		{
			final double v = added * w.getValue() / sum;
			e.valueByStat.merge(w.getKey(), v, Double::sum);
			final Map<String, Integer> by = e.drainedBy.get(w.getKey());
			if (by == null || by.isEmpty())
			{
				continue;
			}
			Map<String, Double> bySource = by.size() == 1 ? null : weights(snap, at, w.getKey());
			if (bySource == null || bySource.isEmpty())
			{
				// One source, or no figures to split them: by what each took.
				bySource = new LinkedHashMap<>();
				for (Map.Entry<String, Integer> s : by.entrySet())
				{
					bySource.put(s.getKey(), (double) s.getValue());
				}
			}
			final double sourceSum = bySource.values().stream().mapToDouble(Double::doubleValue).sum();
			for (Map.Entry<String, Double> s : bySource.entrySet())
			{
				e.valueBySource.computeIfAbsent(s.getKey(), k -> new LinkedHashMap<>()).merge(w.getKey(),
					v * s.getValue() / sourceSum, Double::sum);
			}
		}
	}

	// How much the figure falls with each undo state (of the stats, or of one stat's sources); only positive ones.
	private static Map<String, Double> weights(Snapshot snap, int at, String stat)
	{
		final Map<String, Double> out = new LinkedHashMap<>();
		if (snap == null)
		{
			return out;
		}
		final double[] r = snap.figures.get(Snapshot.key(snap.realized));
		if (r == null || r[at] < 0)
		{
			return out;
		}
		for (Map.Entry<String, DrainRule.State> u : snap.undo.entrySet())
		{
			final String key = u.getKey();
			final boolean ofSource = key.indexOf('|') >= 0;
			if (stat == null ? ofSource : !key.startsWith(stat + "|"))
			{
				continue;
			}
			final double[] f = snap.figures.get(Snapshot.key(u.getValue()));
			if (f == null || f[at] < 0)
			{
				continue;
			}
			final double fall = r[at] - f[at];
			if (fall > 1e-9)
			{
				out.put(stat == null ? key : key.substring(stat.length() + 1), fall);
			}
		}
		return out;
	}

	private static String firstDrained(Entry e, Snapshot snap)
	{
		if (snap != null)
		{
			for (String key : snap.undo.keySet())
			{
				if (key.indexOf('|') < 0)
				{
					return key;
				}
			}
		}
		return e.drainedBy.isEmpty() ? null : e.drainedBy.keySet().iterator().next();
	}

	/** T-141: the fight's value per stat, for the tests and the debug log; empty where none is kept. */
	Map<String, Double> valueByStat(Fight fight)
	{
		final Entry e = entries.get(fight);
		return e == null ? new LinkedHashMap<>() : e.valueByStat;
	}

	/** T-141: the fight's value per source and stat. */
	Map<String, Map<String, Double>> valueBySource(Fight fight)
	{
		final Entry e = entries.get(fight);
		return e == null ? new LinkedHashMap<>() : e.valueBySource;
	}

	/** T-141: the values in a few words, for the SPEC LUCK line: "defence +12.0 (EMBERLIGHT +12.0)". */
	static String valuesLine(Map<String, Double> byStat, Map<String, Map<String, Double>> bySource)
	{
		final StringBuilder b = new StringBuilder();
		for (Map.Entry<String, Double> s : byStat.entrySet())
		{
			b.append(b.length() == 0 ? "" : ", ").append(s.getKey()).append(String.format(" %+.1f", s.getValue()));
			final StringBuilder parts = new StringBuilder();
			for (Map.Entry<String, Map<String, Double>> src : bySource.entrySet())
			{
				final Double v = src.getValue().get(s.getKey());
				if (v != null)
				{
					parts.append(parts.length() == 0 ? "" : ", ").append(src.getKey()).append(String.format(" %+.1f", v));
				}
			}
			if (parts.length() > 0)
			{
				b.append(" (").append(parts).append(')');
			}
		}
		return b.length() == 0 ? "none" : b.toString();
	}

	/** The fight has ended: its figures are final. A shot still landing on it is still booked (T-021). */
	String end(Fight fight)
	{
		final Entry e = entries.get(fight);
		if (e == null)
		{
			return null;
		}
		e.open = false;
		push(fight, e);
		final SpecOutcomes o = e.outcomes;
		return String.format("SPEC LUCK %s: %d attacks, %d of them my draining specials, %d states%s; expected %.1f as made, "
				+ "%.1f averaged over the specials (spec luck %+.1f, %+.1f s); drain value %+.1f as made, %+.1f averaged "
				+ "(%+.1f s, %+.1f s)",
			fight.getTargetName(), o.attacks() + o.specials(), o.specials(), o.states(),
			o.merges() == 0 ? "" : String.format(" (%d merged, widest %d levels)", o.merges(), o.worstMerge()),
			fight.getRealizedExpected(), fight.getSumExpectedAverageHit(), fight.getSpecLuckDamage(),
			fight.luckSeconds(fight.getSpecLuckDamage()), fight.getDrainValueDamage(), fight.getDrainValueExpected(),
			fight.luckSeconds(fight.getDrainValueDamage()), fight.luckSeconds(fight.getDrainValueExpected()))
			+ "; by stat " + valuesLine(e.valueByStat, e.valueBySource);
	}

	void clear()
	{
		entries.clear();
		order.clear();
	}
}

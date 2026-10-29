package com.pvmlogs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * One fight, attack by attack and tick by tick, for the side panel's damage
 * rows, damage chart and tick strip (T-035; spec {@code side-panel-design.md}
 * §5.2). In memory for this RuneLite session only: the {@link Store} keeps the
 * last {@value Store#CAP} fights and nothing is saved.
 *
 * <p>The sums are fed from {@link Fight}'s own methods, at the point each one
 * counts, so they cannot drift from the fight's totals: Σ damage is
 * {@code getDamageDealt()}, Σ expected {@code getRealizedExpected()} (each
 * attack as made; since T-050 the fight's own expected is averaged over its
 * draining specials' outcomes), Σ variance
 * {@code getSumExpectedDamageVariance()}, the lost and eating ticks
 * {@code getTicksLost()} and {@code getTicksLostEating()}. The plugin adds what
 * Fight doesn't know: what each attack was (its name, weapon, spell, style,
 * whether it was the special), which attack a splat belongs to (its
 * {@link AttackSplats} burst) and which attack a held expectation was for.
 *
 * <p>Ticks are game ticks. A tick is recorded on the tick the fight counted it:
 * an attack on the tick it was booked, which trails the throw by its booking
 * lag (one tick for melee, two or three for a projectile or a cast).
 */
final class FightTimeline
{
	/** A tick the fight did not count: before its first attack, between phases, or after it. */
	static final byte NONE = 0;
	/** On cooldown: spent waiting for the weapon. */
	static final byte COOLDOWN = 1;
	/** An attack was booked. */
	static final byte ATTACK = 2;
	/** A special was booked. */
	static final byte SPECIAL = 3;
	/** Off cooldown with no attack. */
	static final byte LOST = 4;
	/** Off cooldown with no attack, inside the delay an eat added. */
	static final byte EATING = 5;
	/** A tick on an unscored fight, in a room's view. */
	static final byte UNSCORED = 6;

	enum Style
	{
		MELEE,
		RANGED,
		MAGIC,
		UNKNOWN
	}

	/** One attack the fight booked. Written on the client thread; the panel copies what it shows. */
	static final class Attack
	{
		/** The game tick the attack was booked on. */
		final int tick;
		/** The tick it was thrown on, or {@code tick} when not told. */
		int thrownTick;
		/** As the loadout names it: the spell when one is cast (T-071), the weapon otherwise; weapon (Spell) before T-071. */
		String key = "Unknown";
		/** The weapon (or the special's weapon), or -1. */
		int itemId = -1;
		/** The spell cast, or null; the panel takes its icon from it. */
		Spell spell;
		Style style = Style.UNKNOWN;
		boolean special;
		/** The expected damage and its variance as booked; -1 until an expectation arrives, or with none. */
		double mean = -1;
		double variance = -1;
		/** The weapon damage credited to it, and the splats that made it land. */
		int damage;
		int hits;

		Attack(int tick)
		{
			this.tick = tick;
			this.thrownTick = tick;
		}

		/** The key the panel groups rows by: the attack's name, and whether it was the special. */
		String rowKey()
		{
			return special ? key + " (special)" : key;
		}

		@Override
		public String toString()
		{
			return String.format("%d %s%s: %d dmg, exp %.1f, var %.1f, %d hits", tick, key, special ? " (spec)" : "",
				damage, mean, variance, hits);
		}
	}

	private final IntSupplier clock;
	private final int startTick;
	private final List<Attack> attacks = new ArrayList<>();
	// Per tick from startTick: the state, and the weapon damage, expected and variance up to and including it.
	private byte[] states = new byte[64];
	private float[] damage = new float[64];
	private float[] expected = new float[64];
	private float[] variance = new float[64];
	private int length;
	// Running totals, exact (the arrays are floats).
	private int totalDamage;
	private double totalExpected;
	private double totalVariance;
	private int lostTicks;
	private int eatingTicks;
	// The expectation the plugin said the next one is for; spent by it.
	private Attack expectFor;
	// The splat Fight just counted, until the plugin says whose it is (attributeSplat); handed to the latest attack if the
	// plugin never does. Splats waiting for an attack not booked yet (a melee blow books after its splat), by thrown tick.
	private int pendingAmount;
	private boolean pendingLanded;
	private boolean pending;
	private final Map<Integer, int[]> waiting = new java.util.HashMap<>();
	private Attack lastCredited;
	// Times the hooks below while Debug logging is on (T-045); null in tests.
	private final PerfProbe probe;

	FightTimeline(IntSupplier clock)
	{
		this(clock, null);
	}

	FightTimeline(IntSupplier clock, PerfProbe probe)
	{
		this.clock = clock;
		this.startTick = clock.getAsInt();
		this.probe = probe;
	}

	// ---- Fed by Fight, where it counts ----

	/** {@link Fight#recordAttackMade}: an attack booked now. */
	void attackMade()
	{
		final long t0 = PerfProbe.start(probe);
		attackMadeTimed();
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void attackMadeTimed()
	{
		final Attack attack = new Attack(clock.getAsInt());
		attacks.add(attack);
		setState(attack.tick, ATTACK);
	}

	/** {@link Fight#recordExpected}, when it counts: for the attack the plugin named, else the latest. */
	void expected(double mean, double damageVariance)
	{
		final long t0 = PerfProbe.start(probe);
		expectedTimed(mean, damageVariance);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void expectedTimed(double mean, double damageVariance)
	{
		final Attack attack = expectFor != null ? expectFor : latest();
		expectFor = null;
		final int tick = clock.getAsInt();
		if (attack != null)
		{
			attack.mean = Math.max(0, attack.mean) + mean;
			if (damageVariance >= 0)
			{
				attack.variance = Math.max(0, attack.variance) + damageVariance;
			}
		}
		totalExpected += mean;
		add(EXPECTED, tick, mean);
		if (damageVariance >= 0)
		{
			totalVariance += damageVariance;
			add(VARIANCE, tick, damageVariance);
		}
	}

	/**
	 * {@link Fight#recordDamageDealt} and {@link Fight#recordExtraTarget}: weapon
	 * damage, in the sums now and held for {@link #attributeSplat} to say whose.
	 */
	void damage(int amount, boolean landed)
	{
		final long t0 = PerfProbe.start(probe);
		damageTimed(amount, landed);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void damageTimed(int amount, boolean landed)
	{
		flushPending();
		pending = true;
		pendingAmount = amount;
		pendingLanded = landed;
		totalDamage += amount;
		add(DAMAGE, clock.getAsInt(), amount);
	}

	/** {@link Fight#revokeDamageDealt}: what was actually taken back, from the attack it was last credited to. */
	void revoked(int amount, boolean hit)
	{
		final long t0 = PerfProbe.start(probe);
		revokedTimed(amount, hit);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void revokedTimed(int amount, boolean hit)
	{
		flushPending();
		final Attack attack = lastCredited != null ? lastCredited : latest();
		if (attack != null)
		{
			attack.damage -= amount;
			attack.hits = Math.max(0, attack.hits - (hit ? 1 : 0));
		}
		totalDamage -= amount;
		add(DAMAGE, clock.getAsInt(), -amount);
	}

	/** {@link Fight#recordTickSpent}, when it counts. */
	void tickSpent()
	{
		final long t0 = PerfProbe.start(probe);
		tickSpentTimed();
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void tickSpentTimed()
	{
		setState(clock.getAsInt(), COOLDOWN);
	}

	/** {@link Fight#recordTickLost}, when it counts. */
	void tickLost(boolean eating)
	{
		final long t0 = PerfProbe.start(probe);
		tickLostTimed(eating);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void tickLostTimed(boolean eating)
	{
		setState(clock.getAsInt(), eating ? EATING : LOST);
		if (eating)
		{
			eatingTicks++;
		}
		else
		{
			lostTicks++;
		}
	}

	// ---- Told by the plugin ----

	/** What the attack just booked was: called right after {@link Fight#recordAttackMade}. */
	void describeLatest(String key, int itemId, Spell spell, Style style, int thrownTick)
	{
		final long t0 = PerfProbe.start(probe);
		describeLatestTimed(key, itemId, spell, style, thrownTick);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void describeLatestTimed(String key, int itemId, Spell spell, Style style, int thrownTick)
	{
		final Attack attack = latest();
		if (attack == null)
		{
			return;
		}
		attack.key = key == null ? "Unknown" : key;
		attack.itemId = itemId;
		attack.spell = spell;
		attack.style = style == null ? Style.UNKNOWN : style;
		attack.thrownTick = thrownTick;
		// Splats that landed before this attack was booked: a melee blow's lands the tick before its booking.
		final int[] early = waiting.remove(thrownTick);
		if (early != null)
		{
			credit(attack, early[0], early[1]);
		}
		// Splats still waiting under an earlier tick were never this attack's, nor any later one's (T-059).
		settleBefore(thrownTick, attack);
	}

	/**
	 * At the fight's end: every splat still waiting goes to the last attack
	 * thrown on or before its tick (T-059). What has none stays, for
	 * {@link #unmatched()} to report.
	 */
	void settle()
	{
		settleBefore(Integer.MAX_VALUE, null);
	}

	// Each splat waiting under a tick before this one to the last attack thrown on or before it, never a later one.
	private void settleBefore(int tick, Attack except)
	{
		if (waiting.isEmpty())
		{
			return;
		}
		for (java.util.Iterator<Map.Entry<Integer, int[]>> it = waiting.entrySet().iterator(); it.hasNext(); )
		{
			final Map.Entry<Integer, int[]> e = it.next();
			if (e.getKey() == Integer.MIN_VALUE || e.getKey() >= tick)
			{
				continue;
			}
			final Attack before = lastThrownBy(e.getKey(), except);
			if (before != null)
			{
				it.remove();
				credit(before, e.getValue()[0], e.getValue()[1]);
			}
		}
	}

	// The last attack thrown on or before this tick, the one just booked aside.
	private Attack lastThrownBy(int tick, Attack except)
	{
		for (int i = attacks.size() - 1; i >= 0; i--)
		{
			final Attack a = attacks.get(i);
			if (a != except && a.thrownTick != Integer.MIN_VALUE && a.thrownTick <= tick)
			{
				return a;
			}
		}
		return null;
	}

	/** The attack just booked was the special: marks it and its tick. */
	void markLatestSpecial()
	{
		final long t0 = PerfProbe.start(probe);
		markLatestSpecialTimed();
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void markLatestSpecialTimed()
	{
		final Attack attack = latest();
		if (attack != null)
		{
			attack.special = true;
			setState(attack.tick, SPECIAL);
		}
	}

	/** The next expectation recorded is this attack's: a held sample resolving after other attacks went out. */
	void expectNextFor(Attack attack)
	{
		final long t0 = PerfProbe.start(probe);
		expectNextForTimed(attack);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void expectNextForTimed(Attack attack)
	{
		expectFor = attack;
	}

	/**
	 * The splat just counted belongs to the attack thrown on this tick (its
	 * {@link AttackSplats} burst), or {@code Integer.MIN_VALUE} for the latest.
	 * One not booked yet gets it when it is ({@link #describeLatest}).
	 */
	void attributeSplat(int thrownTick)
	{
		final long t0 = PerfProbe.start(probe);
		attributeSplatTimed(thrownTick);
		PerfProbe.stop(probe, PerfProbe.Slot.TIMELINE, t0);
	}

	private void attributeSplatTimed(int thrownTick)
	{
		if (!pending)
		{
			return;
		}
		pending = false;
		if (thrownTick == Integer.MIN_VALUE)
		{
			credit(latest(), pendingAmount, pendingLanded ? 1 : 0);
			return;
		}
		for (int i = attacks.size() - 1; i >= 0; i--)
		{
			if (attacks.get(i).thrownTick == thrownTick)
			{
				credit(attacks.get(i), pendingAmount, pendingLanded ? 1 : 0);
				return;
			}
		}
		final int[] early = waiting.computeIfAbsent(thrownTick, t -> new int[2]);
		early[0] += pendingAmount;
		early[1] += pendingLanded ? 1 : 0;
	}

	private void flushPending()
	{
		if (pending)
		{
			pending = false;
			credit(latest(), pendingAmount, pendingLanded ? 1 : 0);
		}
	}

	private void credit(Attack attack, int amount, int hits)
	{
		if (attack == null)
		{
			final int[] none = waiting.computeIfAbsent(Integer.MIN_VALUE, t -> new int[2]);
			none[0] += amount;
			none[1] += hits;
			return;
		}
		attack.damage += amount;
		attack.hits += hits;
		lastCredited = attack;
	}

	/**
	 * Weapon damage in the sums that no attack holds yet: splats waiting for an
	 * attack's booking, or with no attack to give them to. With it, the attacks'
	 * damage adds up to {@link #totalDamage()}.
	 */
	/**
	 * The splats still waiting, as {thrown tick, damage} ({@code Integer.MIN_VALUE}
	 * for none): logged at the fight's end as {@code TIMELINE UNMATCHED} (T-059),
	 * so damage the Damage card can only show as "Not matched to an attack" says
	 * where it came from.
	 */
	java.util.List<int[]> unmatched()
	{
		final java.util.List<int[]> out = new ArrayList<>();
		for (Map.Entry<Integer, int[]> e : new java.util.TreeMap<>(waiting).entrySet())
		{
			if (e.getValue()[0] != 0)
			{
				out.add(new int[]{e.getKey(), e.getValue()[0]});
			}
		}
		if (pending && pendingAmount != 0)
		{
			out.add(new int[]{Integer.MIN_VALUE, pendingAmount});
		}
		return out;
	}

	int unattributedDamage()
	{
		int total = pending ? pendingAmount : 0;
		for (int[] early : waiting.values())
		{
			total += early[0];
		}
		return total;
	}

	Attack latest()
	{
		return attacks.isEmpty() ? null : attacks.get(attacks.size() - 1);
	}

	// ---- The arrays ----

	private int index(int tick)
	{
		return tick - startTick;
	}

	// Grows the arrays to hold this tick, carrying the cumulative values forward.
	private void reach(int i)
	{
		if (i < length)
		{
			return;
		}
		if (i >= states.length)
		{
			final int size = Math.max(states.length * 2, i + 1);
			states = Arrays.copyOf(states, size);
			damage = Arrays.copyOf(damage, size);
			expected = Arrays.copyOf(expected, size);
			variance = Arrays.copyOf(variance, size);
		}
		final float d = length == 0 ? 0 : damage[length - 1];
		final float e = length == 0 ? 0 : expected[length - 1];
		final float v = length == 0 ? 0 : variance[length - 1];
		for (int k = length; k <= i; k++)
		{
			damage[k] = d;
			expected[k] = e;
			variance[k] = v;
		}
		length = i + 1;
	}

	private void setState(int tick, byte state)
	{
		final int i = index(tick);
		if (i < 0)
		{
			return;
		}
		reach(i);
		states[i] = state;
	}

	private static final int DAMAGE = 0;
	private static final int EXPECTED = 1;
	private static final int VARIANCE = 2;

	// Adds to a cumulative series from this tick on: nearly always the last tick, so one entry. The series is picked after
	// reach(), which may have replaced the arrays.
	private void add(int which, int tick, double amount)
	{
		final int i = Math.max(0, index(tick));
		reach(i);
		final float[] series = which == DAMAGE ? damage : which == EXPECTED ? expected : variance;
		for (int k = i; k < length; k++)
		{
			series[k] += (float) amount;
		}
	}

	// ---- Read ----

	int startTick()
	{
		return startTick;
	}

	/** Ticks recorded, from {@link #startTick()}. */
	int length()
	{
		return length;
	}

	byte stateAt(int i)
	{
		return i >= 0 && i < length ? states[i] : NONE;
	}

	/** Copies of the series from index {@code from}: what the panel sends a tick at a time. */
	byte[] states(int from)
	{
		return Arrays.copyOfRange(states, Math.min(from, length), length);
	}

	float[] cumulativeDamage(int from)
	{
		return Arrays.copyOfRange(damage, Math.min(from, length), length);
	}

	float[] cumulativeExpected(int from)
	{
		return Arrays.copyOfRange(expected, Math.min(from, length), length);
	}

	float[] cumulativeVariance(int from)
	{
		return Arrays.copyOfRange(variance, Math.min(from, length), length);
	}

	List<Attack> attacks()
	{
		return Collections.unmodifiableList(attacks);
	}

	int totalDamage()
	{
		return totalDamage;
	}

	double totalExpected()
	{
		return totalExpected;
	}

	double totalVariance()
	{
		return totalVariance;
	}

	int lostTicks()
	{
		return lostTicks + eatingTicks;
	}

	int eatingTicks()
	{
		return eatingTicks;
	}

	/** Roughly what this holds, for the store's cap: the arrays at their size, and the attacks. */
	long approximateBytes()
	{
		return 64 + (long) states.length * (1 + 3 * 4) + (long) attacks.size() * 96;
	}

	// ---- Views over several fights ----

	/**
	 * A room's or a raid's fights laid on one tick axis (spec §5.2): the scored
	 * fights' series summed, their attacks in order, and the unscored fights'
	 * counted ticks marked {@link #UNSCORED} where no scored fight counted the
	 * tick.
	 */
	static final class View
	{
		final int startTick;
		final byte[] states;
		final float[] damage;
		final float[] expected;
		final float[] variance;
		final List<Attack> attacks;

		View(int startTick, byte[] states, float[] damage, float[] expected, float[] variance, List<Attack> attacks)
		{
			this.startTick = startTick;
			this.states = states;
			this.damage = damage;
			this.expected = expected;
			this.variance = variance;
			this.attacks = attacks;
		}

		static final View EMPTY = new View(0, new byte[0], new float[0], new float[0], new float[0],
			Collections.emptyList());
	}

	/** The fights' timelines on one axis, from the first tick any of them recorded to the last. */
	static View view(List<Fight> fights, Store store)
	{
		int start = Integer.MAX_VALUE;
		int end = Integer.MIN_VALUE;
		final List<FightTimeline> lines = new ArrayList<>();
		final List<Boolean> scored = new ArrayList<>();
		for (Fight fight : fights)
		{
			final FightTimeline line = store.of(fight);
			if (line == null || line.length == 0)
			{
				continue;
			}
			lines.add(line);
			scored.add(fight.isScored());
			start = Math.min(start, line.startTick);
			end = Math.max(end, line.startTick + line.length - 1);
		}
		if (lines.isEmpty())
		{
			return View.EMPTY;
		}
		final int size = end - start + 1;
		final byte[] states = new byte[size];
		final float[] damage = new float[size];
		final float[] expected = new float[size];
		final float[] variance = new float[size];
		final List<Attack> attacks = new ArrayList<>();
		for (int f = 0; f < lines.size(); f++)
		{
			final FightTimeline line = lines.get(f);
			final int offset = line.startTick - start;
			if (!scored.get(f))
			{
				for (int i = 0; i < line.length; i++)
				{
					if (line.states[i] != NONE && states[offset + i] == NONE)
					{
						states[offset + i] = UNSCORED;
					}
				}
				continue;
			}
			attacks.addAll(line.attacks);
			for (int i = 0; i < size - offset; i++)
			{
				// A fight's cumulative series holds its last value after it ends.
				final int k = Math.min(i, line.length - 1);
				damage[offset + i] += line.damage[k];
				expected[offset + i] += line.expected[k];
				variance[offset + i] += line.variance[k];
				if (i < line.length && line.states[i] != NONE)
				{
					states[offset + i] = line.states[i];
				}
			}
		}
		attacks.sort((a, b) -> Integer.compare(a.tick, b.tick));
		return new View(start, states, damage, expected, variance, attacks);
	}

	/** A room's view over its fights. */
	static View room(Encounter room, Store store)
	{
		return room == null ? View.EMPTY : view(room.getFights(), store);
	}

	/** A raid's view: its rooms' fights, on one axis in the order they happened. */
	static View raid(Raid raid, Store store)
	{
		if (raid == null)
		{
			return View.EMPTY;
		}
		final List<Fight> fights = new ArrayList<>();
		for (Encounter room : raid.getEncounters())
		{
			fights.addAll(room.getFights());
		}
		return view(fights, store);
	}

	// ---- The store ----

	/**
	 * Every fight's timeline this session, the last {@value #CAP} kept. A fight
	 * gets one when it starts, while recording is on (the {@code panelCharts}
	 * setting); with it off nothing is attached and nothing recorded.
	 */
	static final class Store
	{
		static final int CAP = 50;

		private final Map<Fight, FightTimeline> byFight = new IdentityHashMap<>();
		private final Deque<Fight> order = new ArrayDeque<>();
		private IntSupplier clock = () -> 0;
		private boolean enabled = true;
		private PerfProbe probe;

		void setClock(IntSupplier clock)
		{
			this.clock = clock;
		}

		/** Times the timelines' hooks (T-045): for those attached from now on. */
		void setProbe(PerfProbe probe)
		{
			this.probe = probe;
		}

		void setEnabled(boolean enabled)
		{
			this.enabled = enabled;
		}

		boolean isEnabled()
		{
			return enabled;
		}

		/** Gives a starting fight its timeline; the oldest is dropped past the cap. */
		FightTimeline attach(Fight fight)
		{
			if (!enabled || fight == null)
			{
				return null;
			}
			final FightTimeline existing = byFight.get(fight);
			if (existing != null)
			{
				return existing;
			}
			final FightTimeline line = new FightTimeline(clock, probe);
			byFight.put(fight, line);
			order.addLast(fight);
			fight.setTimeline(line);
			while (order.size() > CAP)
			{
				final Fight oldest = order.pollFirst();
				byFight.remove(oldest);
				oldest.setTimeline(null);
			}
			return line;
		}

		/** The fight's timeline, or null: started before the panel loaded, dropped past the cap, or recording off. */
		FightTimeline of(Fight fight)
		{
			return fight == null ? null : byFight.get(fight);
		}

		int size()
		{
			return byFight.size();
		}

		long approximateBytes()
		{
			long total = 0;
			for (FightTimeline line : byFight.values())
			{
				total += line.approximateBytes();
			}
			return total;
		}

		void clear()
		{
			for (Fight fight : order)
			{
				fight.setTimeline(null);
			}
			byFight.clear();
			order.clear();
		}
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Each target's stats as the specials thrown at it leave them (T-049). Pure: the
 * plugin's glue ({@link DefenceDrain}) feeds it what the client shows, so every
 * rule below is under test.
 *
 * <p><b>Which splat is the special's.</b> The way RuneLite's special attack counter
 * matches them (core {@code SpecialCounterPlugin}, 1.13.4): the special is aimed at
 * the player's target when the energy falls, and its splat is due a fixed number of
 * ticks later, the weapon's {@code getHitDelay} for the distance. Only the player's
 * own ({@code isMine()}) splats on that target, on that tick, are candidates. On one
 * tick they arrive in the order delayed hits, thrall, vengeance, instant hits
 * (RuneLite PR #20377), so a projectile special's is the first of them and a melee
 * special's the last: a thrall's splat, a recoil, or an earlier attack's late splat
 * on the same tick is never taken. Burn, poison and the rest are not
 * {@code isMine()} and never reach here. The Tonalztics of ralos throws two glaives
 * and is the first two. The elder maul's splat lands two ticks late, so like
 * RuneLite this takes its hit or miss from the hitpoints experience on the tick it
 * was thrown: only hit or miss matters to it.
 *
 * <p><b>Regeneration.</b> Drained levels come back a level at a time, at the
 * target's {@code regenTicks}: see {@link #regenerate}.
 *
 * <p><b>Party.</b> Other members' specials come from RuneLite's
 * {@code SpecialCounterUpdate}, already resolved by their own counter; the glue
 * drops the player's own, which come back as party messages too.
 */
final class DrainTracker
{
	/** Events kept per target; a fight needs a handful. */
	static final int MAX_EVENTS = 64;
	static final String NO_SPLAT = "no matching splat";
	static final String IMMUNE = "immune";
	static final String AT_FLOOR = "at the floor";
	static final String NOT_MODELLED = "weapon not modelled";
	static final String NO_EFFECT = "no effect";
	static final String TARGET_GONE = "target gone before it landed";
	/**
	 * How long a boss's drained stats wait for the actor that replaces it (Kephri's
	 * shield, The Fractured Archive's phases): the game swaps them within a tick,
	 * and a new kill takes far longer to spawn.
	 */
	static final int CARRY_TICKS = 5;

	/** One target's tracked stats. */
	static final class Tracked
	{
		final int npcIndex;
		final int npcId;
		final String name;
		final DrainRule.Target target;
		DrainRule.State state;
		final List<DrainEvent> events;
		/** The tick its next level comes back on; -1 until it is first drained, or where it never regenerates. */
		int nextRegenTick = -1;
		/** The index of the first actor these stats were tracked on, where a boss's actor was replaced since. */
		final int originIndex;

		Tracked(int npcIndex, int npcId, String name, DrainRule.Target target)
		{
			this(npcIndex, npcId, name, target, DrainRule.State.of(target), new ArrayList<>(), npcIndex);
		}

		private Tracked(int npcIndex, int npcId, String name, DrainRule.Target target, DrainRule.State state,
			List<DrainEvent> events, int originIndex)
		{
			this.npcIndex = npcIndex;
			this.npcId = npcId;
			this.name = name;
			this.target = target;
			this.state = state;
			this.events = events;
			this.originIndex = originIndex;
		}

		/** The same stats on another actor or form: its events and regeneration go with it. */
		Tracked moved(int index, int id, String newName, DrainRule.Target newTarget, DrainRule.State newState)
		{
			final Tracked t = new Tracked(index, id, newName == null ? name : newName, newTarget, newState, events,
				originIndex);
			t.nextRegenTick = nextRegenTick;
			return t;
		}

		boolean drained()
		{
			return !state.equals(DrainRule.State.of(target));
		}
	}

	private static final class Throw
	{
		final int thrownTick;
		final SpecialWeapon weapon;
		final int npcIndex;
		final int npcId;
		final String name;
		final int dueTick;
		final DrainRule.Target target;
		final List<Integer> splats = new ArrayList<>(2);
		/** T-143: whether hitpoints experience came on the tick it was thrown: it dealt damage. */
		final boolean hpXpGiven;

		Throw(int thrownTick, SpecialWeapon weapon, int npcIndex, int npcId, String name, int dueTick,
			DrainRule.Target target, boolean hpXpGiven)
		{
			this.thrownTick = thrownTick;
			this.weapon = weapon;
			this.npcIndex = npcIndex;
			this.npcId = npcId;
			this.name = name;
			this.dueTick = dueTick;
			this.target = target;
			this.hpXpGiven = hpXpGiven;
		}
	}

	/**
	 * T-147: a draining special seen thrown by a player outside the RuneLite party, waiting for its splat on its due tick,
	 * then for {@link #PARTY_GRACE_TICKS} in case a party member's counter sends the same one. The player is kept by their
	 * slot only, for the log; never a name.
	 */
	private static final class Seen
	{
		final int thrownTick;
		final SpecialWeapon weapon;
		final int playerSlot;
		final int npcIndex;
		final int npcId;
		final String name;
		final int dueTick;
		final DrainRule.Target target;
		final List<Integer> splats = new ArrayList<>(2);
		int resolvedTick = Integer.MIN_VALUE;
		int hit;
		boolean landed;

		Seen(int thrownTick, SpecialWeapon weapon, int playerSlot, int npcIndex, int npcId, String name, int dueTick,
			DrainRule.Target target)
		{
			this.thrownTick = thrownTick;
			this.weapon = weapon;
			this.playerSlot = playerSlot;
			this.npcIndex = npcIndex;
			this.npcId = npcId;
			this.name = name;
			this.dueTick = dueTick;
			this.target = target;
		}
	}

	/**
	 * T-147: ticks a seen special waits after its splat before it drains, for a party member's special counter to send
	 * the same one: the counter wins. Its message comes once their client has the splat; a guess at its lag, logged.
	 */
	static final int PARTY_GRACE_TICKS = 3;
	private final List<Seen> seen = new ArrayList<>();

	private final Map<Integer, Tracked> targets = new HashMap<>();
	/** Drained stats of a boss whose actor despawned, waiting for the one that replaces it, by boss. */
	private final Map<String, Tracked> carried = new HashMap<>();
	private final Map<String, Integer> carriedTick = new HashMap<>();
	private final List<Throw> pending = new ArrayList<>(4);
	private final Consumer<String> log;

	DrainTracker(Consumer<String> log)
	{
		this.log = log == null ? line -> { } : log;
	}

	/**
	 * The player's own special went out on {@code thrownTick}, aimed at this target,
	 * its splat due on {@code dueTick}.
	 *
	 * @param target    the target's levels and floors, or null where nothing is known of its stats
	 * @param hpXpGiven whether hitpoints experience came on the tick it was thrown: the elder maul's hit
	 */
	void ownThrow(int thrownTick, SpecialWeapon weapon, int npcIndex, int npcId, String name, int dueTick,
		DrainRule.Target target, boolean hpXpGiven)
	{
		if (!DrainRule.drains(weapon))
		{
			log.accept(skipLine(weapon, true, name, npcId, npcIndex, NOT_MODELLED));
			return;
		}
		if (npcIndex < 0)
		{
			log.accept(skipLine(weapon, true, "nothing", -1, -1, "no npc target"));
			return;
		}
		if (target == null)
		{
			log.accept(skipLine(weapon, true, name, npcId, npcIndex, "no stats for the target"));
			return;
		}
		if (weapon == SpecialWeapon.ELDER_MAUL)
		{
			apply(ensure(npcIndex, npcId, name, target), weapon, true, thrownTick, dueTick, hpXpGiven ? 1 : 0, hpXpGiven);
			return;
		}
		pending.add(new Throw(thrownTick, weapon, npcIndex, npcId, name, dueTick, target, hpXpGiven));
	}

	/**
	 * T-143: whether a special whose splat never came missed, rather than went unmatched: the Eye of ayak's Soul Rend
	 * with no hitpoints experience on the tick it was thrown. Its attack pays experience only for the damage it deals
	 * (OSRS Wiki, Powered staff: "The built-in spells give no base experience and grants the player 2 Magic experience
	 * per damage dealt"; Splashing: "As no damage is dealt to your target, splashing will not give you any Hitpoints
	 * experience"), so none on its tick says it dealt none. In Eric's logs (2026-10-31, 18:30 and 19:06) all five Soul
	 * Rends read "no matching splat" had no experience on their tick and no splat on Yama. Whether a splash shows a
	 * hitsplat isn't sourced, and doesn't matter here. Every other special keeps its no-splat skip.
	 */
	static boolean splashed(SpecialWeapon weapon, boolean hpXpGiven)
	{
		return weapon == SpecialWeapon.EYE_OF_AYAK && !hpXpGiven;
	}

	/** One of the player's own splats on an npc: a candidate for any special due there on this tick. */
	void ownSplat(int npcIndex, int tick, int amount)
	{
		for (int i = 0; i < pending.size(); i++)
		{
			final Throw t = pending.get(i);
			if (t.npcIndex == npcIndex && t.dueTick == tick)
			{
				t.splats.add(Math.max(0, amount));
			}
		}
	}

	/**
	 * T-147: a draining special by a player outside the RuneLite party, seen from their animation with the weapon in
	 * hand, aimed at the npc. Its splat is due {@code dueTick}: another player's splat on the npc then (see
	 * {@link #othersSplat}).
	 *
	 * @param playerSlot the player's index in the client's player list, for the log only
	 */
	void seenThrow(int thrownTick, SpecialWeapon weapon, int playerSlot, int npcIndex, int npcId, String name,
		int dueTick, DrainRule.Target target)
	{
		if (!DrainRule.drains(weapon) || target == null || npcIndex < 0)
		{
			return;
		}
		seen.add(new Seen(thrownTick, weapon, playerSlot, npcIndex, npcId, name, dueTick, target));
		log.accept(String.format("SEEN %s special by player slot %d (outside the party) at %s (npc %d, index %d): thrown"
			+ " %d, its splat due %d", weapon.getName(), playerSlot, name, npcId, npcIndex, thrownTick, dueTick));
	}

	/** T-147: another player's splat on an npc (others', {@code isOthers()}): a candidate for a seen special due then. */
	void othersSplat(int npcIndex, int tick, int amount)
	{
		for (Seen t : seen)
		{
			if (t.npcIndex == npcIndex && t.dueTick == tick && t.resolvedTick == Integer.MIN_VALUE)
			{
				t.splats.add(Math.max(0, amount));
			}
		}
	}

	/**
	 * T-147: which of the others' splats on a seen special's due tick is its: one, it; none, nothing to go on; two or
	 * more, its outcome only where they agree and it's one the drain rule sets by landing alone (the warhammer, the
	 * lights), since then the damage doesn't matter; otherwise nothing. Returns {hit, landed (1 or 0)}, or null.
	 */
	static int[] seenOutcome(SpecialWeapon weapon, List<Integer> splats)
	{
		if (splats.isEmpty())
		{
			return null;
		}
		if (splats.size() == 1)
		{
			return new int[]{splats.get(0), splats.get(0) > 0 ? 1 : 0};
		}
		boolean allHit = true;
		boolean allMissed = true;
		int least = Integer.MAX_VALUE;
		for (int a : splats)
		{
			allHit &= a > 0;
			allMissed &= a <= 0;
			least = Math.min(least, a);
		}
		if (!DrainRule.drainsOnLanding(weapon) || !allHit && !allMissed)
		{
			return null;
		}
		return new int[]{allHit ? least : 0, allHit ? 1 : 0};
	}

	// T-147: seen specials due now resolved by their splats, and those resolved PARTY_GRACE_TICKS ago drained.
	private void tickSeen(int now)
	{
		for (Iterator<Seen> it = seen.iterator(); it.hasNext(); )
		{
			final Seen t = it.next();
			if (t.resolvedTick == Integer.MIN_VALUE)
			{
				if (now < t.dueTick || now == t.dueTick && t.splats.isEmpty())
				{
					continue;
				}
				final int[] outcome = seenOutcome(t.weapon, t.splats);
				if (outcome == null)
				{
					it.remove();
					log.accept(String.format("SEEN %s by player slot %d on %s (npc %d, index %d): not drained, %s on tick %d",
						t.weapon.getName(), t.playerSlot, t.name, t.npcId, t.npcIndex, t.splats.isEmpty()
							? "no others' splat" : t.splats.size() + " others' splats " + t.splats + " that don't say which",
						t.dueTick));
					continue;
				}
				t.hit = outcome[0];
				t.landed = outcome[1] == 1;
				t.resolvedTick = now;
				log.accept(String.format("SEEN %s by player slot %d on %s (npc %d, index %d): splat %d on tick %d of %s (%s);"
						+ " drains in %d ticks unless a party member's counter sends it", t.weapon.getName(), t.playerSlot,
					t.name, t.npcId, t.npcIndex, t.hit, t.dueTick, t.splats, t.landed ? "landed" : "missed",
					PARTY_GRACE_TICKS));
				continue;
			}
			if (now - t.resolvedTick < PARTY_GRACE_TICKS)
			{
				continue;
			}
			it.remove();
			apply(ensure(t.npcIndex, t.npcId, t.name, t.target), t.weapon, false, true, t.thrownTick, t.dueTick, t.hit,
				t.landed);
		}
	}

	/** Once a tick, after its splats: the specials due now are resolved, and those overdue given up on. */
	void tick(int now)
	{
		tickSeen(now);
		for (Iterator<Throw> it = pending.iterator(); it.hasNext(); )
		{
			final Throw t = it.next();
			if (now < t.dueTick)
			{
				continue;
			}
			final boolean twoGlaives = t.weapon == SpecialWeapon.TONALZTICS_OF_RALOS;
			if (now == t.dueTick && (twoGlaives ? t.splats.size() < 2 : t.splats.isEmpty()))
			{
				// RuneLite waits the tick out too: the splats can be late to arrive.
				continue;
			}
			it.remove();
			if (t.splats.isEmpty() && splashed(t.weapon, t.hpXpGiven))
			{
				// T-143: a Soul Rend that splashed: it dealt nothing, so it reduces nothing. A miss, not a skip.
				apply(ensure(t.npcIndex, t.npcId, t.name, t.target), t.weapon, true, t.thrownTick, now, 0, false);
				continue;
			}
			if (t.splats.isEmpty() || twoGlaives && t.splats.size() < 2)
			{
				final Tracked tracked = ensure(t.npcIndex, t.npcId, t.name, t.target);
				record(tracked, DrainEvent.skip(t.weapon.name(), true, t.thrownTick, t.dueTick, 0, false, tracked.state,
					NO_SPLAT));
				log.accept(skipLine(t.weapon, true, t.name, t.npcId, t.npcIndex, NO_SPLAT + " on tick " + t.dueTick));
				continue;
			}
			final int hit;
			if (twoGlaives)
			{
				hit = (t.splats.get(0) > 0 ? 1 : 0) + (t.splats.get(1) > 0 ? 1 : 0);
			}
			else
			{
				final boolean delayed = t.weapon.getHitDelay(1) > 1;
				hit = t.splats.get(delayed ? 0 : t.splats.size() - 1);
			}
			apply(ensure(t.npcIndex, t.npcId, t.name, t.target), t.weapon, true, t.thrownTick, now, hit, hit > 0);
		}
		regenerate(now);
		if (!carried.isEmpty())
		{
			expireCarried(now);
		}
	}

	/**
	 * Drained levels come back one at a time, every {@code regenTicks} (wiki,
	 * Temporary skill drain). The game's timer for an npc is not visible, so this
	 * one starts at the first drain: a level may come back up to one interval
	 * later here than in the game. O(targets drained), a handful.
	 */
	private void regenerate(int now)
	{
		for (Tracked t : targets.values())
		{
			if (t.nextRegenTick < 0 || now < t.nextRegenTick)
			{
				continue;
			}
			t.nextRegenTick += t.target.regenTicks;
			final DrainRule.State before = t.state;
			final DrainRule.State after = DrainRule.regenerate(before, t.target);
			if (after.equals(before))
			{
				continue;
			}
			t.state = after;
			final DrainEvent last = t.events.isEmpty() ? null : t.events.get(t.events.size() - 1);
			if (last != null && last.isRegen())
			{
				last.extendRegen(now, after);
			}
			else
			{
				record(t, DrainEvent.regen(now, before, after));
			}
			log.accept(String.format("DRAIN REGEN %s (npc %d, index %d): tick %d, defence %d -> %d of %d, magic %d -> %d"
					+ " of %d (every %d ticks)", t.name, t.npcId, t.npcIndex, now, before.defence, after.defence,
				t.target.defence, before.magic, after.magic, t.target.magic, t.target.regenTicks));
		}
	}

	/** A party member's special, as their counter resolved it. */
	void partyDrain(int tick, SpecialWeapon weapon, int hit, int npcIndex, int npcId, String name,
		DrainRule.Target target)
	{
		if (!DrainRule.drains(weapon))
		{
			log.accept(skipLine(weapon, false, name, npcId, npcIndex, NOT_MODELLED));
			return;
		}
		if (target == null)
		{
			log.accept(skipLine(weapon, false, name, npcId, npcIndex, "no stats for the target"));
			return;
		}
		// T-147: the counter wins: a seen special of the same weapon on the same npc, waiting, is this one.
		for (Iterator<Seen> it = seen.iterator(); it.hasNext(); )
		{
			final Seen t = it.next();
			if (t.npcIndex == npcIndex && t.weapon == weapon && tick - t.thrownTick <= t.dueTick - t.thrownTick
				+ PARTY_GRACE_TICKS + 1)
			{
				it.remove();
				log.accept(String.format("SEEN %s by player slot %d on %s (npc %d, index %d): a party member's counter sent"
					+ " it, so it's theirs", weapon.getName(), t.playerSlot, name, npcId, npcIndex));
				break;
			}
		}
		apply(ensure(npcIndex, npcId, name, target), weapon, false, tick, tick, hit, hit > 0);
	}

	/** The target's stats come back to its base: a new phase, Sotetseg's maze. */
	void reset(int npcIndex, int tick, String reason)
	{
		final Tracked t = targets.get(npcIndex);
		if (t != null)
		{
			reset(t, tick, reason, t.npcId, t.name, t.target);
		}
	}

	/**
	 * The npc changed form. Where its rules restore its stats on the new form
	 * ({@code restores}), they start again from the new form's base; otherwise the
	 * drains carry over (Zulrah, the Kalphite Queen, Araxxor's enrage), measured
	 * against the new form's levels.
	 */
	void changed(int npcIndex, int tick, int npcId, String name, DrainRule.Target target, boolean restores)
	{
		final Tracked t = targets.get(npcIndex);
		if (t == null || target == null)
		{
			return;
		}
		if (restores)
		{
			reset(t, tick, "became npc " + npcId, npcId, name, target);
			return;
		}
		targets.put(npcIndex, t.moved(npcIndex, npcId, name, target, within(t.state, t.target, target)));
	}

	private void reset(Tracked t, int tick, String reason, int npcId, String name, DrainRule.Target target)
	{
		final DrainRule.State before = t.state;
		final Tracked now = t.moved(t.npcIndex, npcId, name, target, DrainRule.State.of(target));
		targets.put(t.npcIndex, now);
		if (!t.drained())
		{
			return;
		}
		record(now, DrainEvent.reset(reason, tick, before, now.state));
		log.accept(String.format("DRAIN RESET %s (npc %d, index %d): %s; defence %d -> %d, magic %d -> %d", now.name,
			npcId, now.npcIndex, reason, before.defence, now.state.defence, before.magic, now.state.magic));
	}

	// Drained levels carried onto a form with other levels: none above its base, none below its floors. T-141: the Eye of
	// ayak's reduction carries whole, onto the new form's bonus (the wiki: "This also applies to Zulrah").
	static DrainRule.State within(DrainRule.State s, DrainRule.Target from, DrainRule.Target t)
	{
		return new DrainRule.State(clamp(s.defence, t.defenceFloor, t.defence), clamp(s.magic, t.magicFloor, t.magic),
			t.magicDefence - DrainRule.magicDefenceReduction(s, from), Math.min(s.attack, t.attack),
			Math.min(s.strength, t.strength), Math.min(s.ranged, t.ranged));
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	/** Whether any boss's drained stats are waiting for the actor that replaces it. */
	boolean hasCarried()
	{
		return !carried.isEmpty();
	}

	/** Whether stats are waiting under this key. */
	boolean isCarried(String key)
	{
		return key != null && carried.containsKey(key);
	}

	/**
	 * A new actor of a boss whose stats were waiting ({@link #forget} with a key):
	 * they move onto it, or, where it is the form that restores them
	 * ({@code restores}, Kephri's last phase), start again from its base.
	 */
	void adopt(String key, int npcIndex, int npcId, String name, DrainRule.Target target, int tick, boolean restores)
	{
		final Tracked old = carried.remove(key);
		carriedTick.remove(key);
		if (old == null || target == null || targets.containsKey(npcIndex))
		{
			return;
		}
		final Tracked t = old.moved(npcIndex, npcId, name, target, restores ? DrainRule.State.of(target)
			: within(old.state, old.target, target));
		targets.put(npcIndex, t);
		if (restores)
		{
			record(t, DrainEvent.reset("became npc " + npcId, tick, old.state, t.state));
		}
		log.accept(String.format("DRAIN CARRY %s: index %d (npc %d) -> index %d (npc %d)%s; defence %d -> %d, magic %d"
				+ " -> %d", t.name, old.npcIndex, old.npcId, npcIndex, npcId, restores ? ", restored" : "",
			old.state.defence, t.state.defence, old.state.magic, t.state.magic));
	}

	/**
	 * The same target came back under another index (T-088: the Giant Mole after
	 * a trip away): its drained stats go with it, regenerated through the gap as
	 * they would have been.
	 */
	void move(int fromIndex, int toIndex)
	{
		final Tracked old = targets.remove(fromIndex);
		if (old == null || fromIndex == toIndex)
		{
			if (old != null)
			{
				targets.put(fromIndex, old);
			}
			return;
		}
		targets.put(toIndex, old.moved(toIndex, old.npcId, old.name, old.target, old.state));
		log.accept(String.format("DRAIN CARRY %s: index %d -> index %d (back after a trip); defence %d, magic %d", old.name,
			fromIndex, toIndex, old.state.defence, old.state.magic));
	}

	/** Drops waiting stats no actor took up in time. */
	void expireCarried(int now)
	{
		for (Iterator<Map.Entry<String, Integer>> it = carriedTick.entrySet().iterator(); it.hasNext(); )
		{
			final Map.Entry<String, Integer> e = it.next();
			if (now - e.getValue() > CARRY_TICKS)
			{
				it.remove();
				carried.remove(e.getKey());
			}
		}
	}

	private void apply(Tracked t, SpecialWeapon weapon, boolean own, int thrownTick, int landedTick, int hit,
		boolean landed)
	{
		apply(t, weapon, own, false, thrownTick, landedTick, hit, landed);
	}

	private void apply(Tracked t, SpecialWeapon weapon, boolean own, boolean seen, int thrownTick, int landedTick,
		int hit, boolean landed)
	{
		final DrainRule.State before = t.state;
		final DrainRule.Result r = DrainRule.apply(before, weapon, hit, landed, t.target);
		if (r.changed(before))
		{
			t.state = r.after;
			if (t.nextRegenTick < 0 && t.target.regenTicks > 0)
			{
				t.nextRegenTick = landedTick + t.target.regenTicks;
			}
			record(t, seen(DrainEvent.drain(weapon.name(), own, thrownTick, landedTick, hit, landed, before, r.after,
				r.floored), seen));
			log.accept(drainLine(t, weapon, own, thrownTick, landedTick, hit, landed, before, r).replaceFirst(" by party ",
				seen ? " by a player outside the party " : " by party "));
			return;
		}
		if (!landed && !r.floored)
		{
			// A miss: nothing taken, but it was thrown and it counts.
			record(t, seen(DrainEvent.drain(weapon.name(), own, thrownTick, landedTick, hit, false, before, before, false),
				seen));
			log.accept(drainLine(t, weapon, own, thrownTick, landedTick, hit, false, before, r).replaceFirst(" by party ",
				seen ? " by a player outside the party " : " by party "));
			return;
		}
		final String reason = t.target.immune ? IMMUNE : r.floored ? AT_FLOOR : NO_EFFECT;
		record(t, seen(DrainEvent.skip(weapon.name(), own, thrownTick, landedTick, hit, landed, before, reason), seen));
		log.accept(skipLine(weapon, own, t.name, t.npcId, t.npcIndex, reason + " (hit " + hit + ")"));
	}

	private static DrainEvent seen(DrainEvent e, boolean seen)
	{
		e.seen = seen;
		return e;
	}

	private Tracked ensure(int npcIndex, int npcId, String name, DrainRule.Target target)
	{
		Tracked t = targets.get(npcIndex);
		if (t == null || t.npcId != npcId && !t.drained())
		{
			t = new Tracked(npcIndex, npcId, name, target);
			targets.put(npcIndex, t);
		}
		return t;
	}

	private static void record(Tracked t, DrainEvent e)
	{
		if (t.events.size() >= MAX_EVENTS)
		{
			t.events.remove(0);
		}
		t.events.add(e);
	}

	/** The target's tracked stats, or null where nothing has been thrown at it. */
	Tracked tracked(int npcIndex)
	{
		return targets.get(npcIndex);
	}

	/**
	 * A target's tracked stats, made if need be from what the glue knows of it: the
	 * live readout and a fight's record start from this.
	 */
	Tracked track(int npcIndex, int npcId, String name, DrainRule.Target target)
	{
		return target == null ? targets.get(npcIndex) : ensure(npcIndex, npcId, name, target);
	}

	/** The npc died or left: its stats go with it, as the game's do, and anything still due on it is dropped. */
	void forget(int npcIndex)
	{
		forget(npcIndex, null, 0);
	}

	/**
	 * As {@link #forget(int)}, but a boss whose actor the game replaces mid-fight
	 * ({@code carryKey} not null) keeps its drained stats for {@link #CARRY_TICKS}
	 * for the next actor to {@link #adopt}.
	 */
	void forget(int npcIndex, String carryKey, int tick)
	{
		final Tracked gone = targets.remove(npcIndex);
		if (carryKey != null && gone != null && gone.drained())
		{
			carried.put(carryKey, gone);
			carriedTick.put(carryKey, tick);
		}
		for (Iterator<Throw> it = pending.iterator(); it.hasNext(); )
		{
			final Throw t = it.next();
			if (t.npcIndex == npcIndex)
			{
				it.remove();
				log.accept(skipLine(t.weapon, true, t.name, t.npcId, t.npcIndex, TARGET_GONE));
			}
		}
	}

	void clear()
	{
		targets.clear();
		pending.clear();
		seen.clear();
		carried.clear();
		carriedTick.clear();
	}

	/** T-147: seen specials still waiting for their splat or for a party counter. */
	int seenCount()
	{
		return seen.size();
	}

	/** Specials still waiting for their splat. */
	int pendingCount()
	{
		return pending.size();
	}

	/**
	 * A fight's record: its target's stats when the fight opened (before the first
	 * event on or after {@code fromTick}) and now, its base and floors, and the
	 * events since. Null where nothing is known of the target.
	 */
	DrainRecord record(int npcIndex, int fromTick)
	{
		final Tracked t = find(npcIndex);
		if (t == null)
		{
			return null;
		}
		final DrainRecord r = new DrainRecord();
		r.immune = t.target.immune;
		r.defenceBase = t.target.defence;
		r.defenceFloor = t.target.defenceFloor;
		r.magicBase = t.target.magic;
		r.magicFloor = t.target.magicFloor;
		r.magicDefenceBase = t.target.magicDefence;
		DrainRule.State start = null;
		for (DrainEvent e : t.events)
		{
			if (e.thrownTick < fromTick && e.landedTick < fromTick)
			{
				continue;
			}
			if (start == null)
			{
				start = new DrainRule.State(e.defenceBefore, e.magicBefore, e.magicDefenceBefore, 0, 0, 0);
			}
			r.events.add(e);
			if (e.isReset() || e.isRegen())
			{
				continue;
			}
			if (e.own)
			{
				final int[] counts = r.specs.computeIfAbsent(e.weapon, w -> new int[2]);
				counts[0]++;
				if (e.landed)
				{
					counts[1]++;
				}
			}
			else
			{
				r.partyDrains++;
			}
		}
		r.defenceEnd = t.state.defence;
		r.magicEnd = t.state.magic;
		r.magicDefenceEnd = t.state.magicDefence;
		r.defenceStart = start == null ? r.defenceEnd : start.defence;
		r.magicStart = start == null ? r.magicEnd : start.magic;
		r.magicDefenceStart = start == null ? r.magicDefenceEnd : start.magicDefence;
		final List<DrainEvent> before = new ArrayList<>();
		for (DrainEvent e : t.events)
		{
			if (e.thrownTick < fromTick && e.landedTick < fromTick)
			{
				before.add(e);
			}
		}
		perStatAndSource(r, t.target, before);
		return r;
	}

	/** As below, with nothing carried in. */
	static void perStatAndSource(DrainRecord r, DrainRule.Target target)
	{
		perStatAndSource(r, target, java.util.Collections.emptyList());
	}

	/**
	 * T-141: the record's drains per stat and per source, from its events: each stat that changed in the fight (or that
	 * a drain moved, back since), and each weapon with what it took off each stat. A reset's levels are no source's.
	 */
	/**
	 * T-144: and with {@code before}, the target's events before the fight, each source whose drains it opens with is a
	 * source here too, its throws counted where they landed ({@code thrown} and {@code landed} 0 here, nothing taken in
	 * this fight): so a room's records summed give each special its whole value, each throw once.
	 */
	static void perStatAndSource(DrainRecord r, DrainRule.Target target, List<DrainEvent> before)
	{
		r.stats = new ArrayList<>(3);
		r.sources = new ArrayList<>(2);
		final Map<String, DrainRecord.SourceDrain> bySource = new LinkedHashMap<>();
		final boolean[] moved = new boolean[3];
		final boolean[] drainedAtStart = {r.defenceStart < target.defence, r.magicStart < target.magic,
			r.magicDefenceStart < target.magicDefence};
		for (DrainEvent e : carriedIn(before))
		{
			final DrainRecord.SourceDrain d = bySource.computeIfAbsent(DrainRecord.sourceKey(e.weapon, e.own, e.seen), k ->
			{
				final DrainRecord.SourceDrain n = new DrainRecord.SourceDrain();
				n.weapon = e.weapon;
				n.own = e.own;
				n.seen = e.seen;
				return n;
			});
			carriedOn(d, DrainRecord.StatDrain.DEFENCE, drainedAtStart[0] && e.defenceBefore > e.defenceAfter);
			carriedOn(d, DrainRecord.StatDrain.MAGIC, drainedAtStart[1] && e.magicBefore > e.magicAfter);
			carriedOn(d, DrainRecord.StatDrain.MAGIC_DEFENCE, drainedAtStart[2]
				&& e.magicDefenceBefore > e.magicDefenceAfter);
		}
		// A carried source whose stat is back at its base by the fight brings nothing: dropped.
		bySource.values().removeIf(d -> d.drained.isEmpty());
		for (DrainEvent e : r.events)
		{
			if (e.isReset() || e.isRegen() || e.weapon == null || !(e.isDrain() || e.isSkip()))
			{
				continue;
			}
			final DrainRecord.SourceDrain d = bySource.computeIfAbsent(DrainRecord.sourceKey(e.weapon, e.own, e.seen), k ->
			{
				final DrainRecord.SourceDrain n = new DrainRecord.SourceDrain();
				n.weapon = e.weapon;
				n.own = e.own;
				n.seen = e.seen;
				return n;
			});
			d.thrown++;
			if (e.landed)
			{
				d.landed++;
			}
			if (!e.isDrain())
			{
				continue;
			}
			moved[0] |= took(d, DrainRecord.StatDrain.DEFENCE, e.defenceBefore - e.defenceAfter);
			moved[1] |= took(d, DrainRecord.StatDrain.MAGIC, e.magicBefore - e.magicAfter);
			moved[2] |= took(d, DrainRecord.StatDrain.MAGIC_DEFENCE, e.magicDefenceBefore - e.magicDefenceAfter);
		}
		r.sources.addAll(bySource.values());
		// T-144: a stat the fight opens drained is the fight's too, though nothing moved it: its drains still add.
		if (moved[0] || r.defenceStart != r.defenceEnd || drainedAtStart[0])
		{
			r.stats.add(stat(DrainRecord.StatDrain.DEFENCE, target.defence, target.defenceFloor, r.defenceStart,
				r.defenceEnd, target.regenTicks));
		}
		if (moved[1] || r.magicStart != r.magicEnd || drainedAtStart[1])
		{
			r.stats.add(stat(DrainRecord.StatDrain.MAGIC, target.magic, target.magicFloor, r.magicStart, r.magicEnd,
				target.regenTicks));
		}
		if (moved[2] || r.magicDefenceStart != r.magicDefenceEnd || drainedAtStart[2])
		{
			r.stats.add(stat(DrainRecord.StatDrain.MAGIC_DEFENCE, target.magicDefence, 0, r.magicDefenceStart,
				r.magicDefenceEnd, 0));
		}
	}

	// The events before a fight since the target's stats were last put back: the drains it can still be carrying.
	private static List<DrainEvent> carriedIn(List<DrainEvent> before)
	{
		final List<DrainEvent> out = new ArrayList<>();
		for (DrainEvent e : before)
		{
			if (e.isReset())
			{
				out.clear();
			}
			else if (e.isDrain() && e.weapon != null)
			{
				out.add(e);
			}
		}
		return out;
	}

	// A carried source: the stat it drained, with nothing taken in this fight (its takes were counted where they landed).
	private static void carriedOn(DrainRecord.SourceDrain d, String stat, boolean on)
	{
		if (on)
		{
			d.drained.putIfAbsent(stat, 0);
		}
	}

	private static boolean took(DrainRecord.SourceDrain d, String stat, int amount)
	{
		if (amount <= 0)
		{
			return false;
		}
		d.drained.merge(stat, amount, Integer::sum);
		return true;
	}

	private static DrainRecord.StatDrain stat(String name, int base, int floor, int start, int end, int regenTicks)
	{
		final DrainRecord.StatDrain d = new DrainRecord.StatDrain();
		d.stat = name;
		d.base = base;
		d.floor = floor;
		d.start = start;
		d.end = end;
		d.regenTicks = regenTicks;
		return d;
	}

	// The stats tracked on this index, or on the actor that has since replaced it.
	private Tracked find(int npcIndex)
	{
		final Tracked t = targets.get(npcIndex);
		if (t != null)
		{
			return t;
		}
		for (Tracked other : targets.values())
		{
			if (other.originIndex == npcIndex)
			{
				return other;
			}
		}
		for (Tracked other : carried.values())
		{
			if (other.originIndex == npcIndex || other.npcIndex == npcIndex)
			{
				return other;
			}
		}
		return null;
	}

	private static String who(boolean own)
	{
		return own ? "me" : "party";
	}

	static String drainLine(Tracked t, SpecialWeapon weapon, boolean own, int thrownTick, int landedTick, int hit,
		boolean landed, DrainRule.State before, DrainRule.Result r)
	{
		final DrainRule.State after = r.after;
		// T-141: the magic defence as the Eye of ayak's reduction, which meets whatever bonus the target has then.
		return String.format("DRAIN %s by %s on %s (npc %d, index %d): thrown %d, landed %d, hit %d (%s): defence %d -> %d"
				+ " of %d (floor %d), magic %d -> %d of %d, magic defence %+d, reduced by %d -> %d%s",
			weapon.getName(), who(own), t.name, t.npcId, t.npcIndex, thrownTick, landedTick, hit,
			landed ? "landed" : "missed", before.defence, after.defence, t.target.defence, t.target.defenceFloor,
			before.magic, after.magic, t.target.magic, t.target.magicDefence,
			DrainRule.magicDefenceReduction(before, t.target), DrainRule.magicDefenceReduction(after, t.target),
			r.floored ? ", floored" : "");
	}

	static String skipLine(SpecialWeapon weapon, boolean own, String name, int npcId, int npcIndex, String reason)
	{
		return String.format("DRAIN SKIP %s by %s on %s (npc %d, index %d): %s",
			weapon == null ? "unknown" : weapon.getName(), who(own), name, npcId, npcIndex, reason);
	}
}

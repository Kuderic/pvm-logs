package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The boss states that change the player's damage (T-057): which of a boss's
 * {@code damageModifiers} windows are open, from what the client shows - a cast
 * of the player's own landing, the boss's animations, the id it wears, an attack
 * of the player's landing with a modifier. Pure: {@link DamageModifiers} feeds
 * it and asks it which modifiers an attack takes.
 *
 * <p>A window is asked about at the tick an attack LANDS: one with a fixed
 * length is open from its start for its length; one closed by an event stays
 * open until that event (or its {@code maxTicks}).
 */
final class ModifierTracker
{
	/** One open (or since closed) window on one boss. */
	static final class Window
	{
		final String state;
		final int start;
		/** The first tick it is no longer open; Integer.MAX_VALUE while nothing has closed it. */
		int end;
		/** Closed by an event, whose line was logged then: running out on the same tick is not logged again (T-066). */
		boolean closed;
		/** An attack thrown before the start lands free of it (T-078: the shot the stun's reset attack delay allows). */
		boolean spareThrownBefore;

		Window(String state, int start, int end)
		{
			this.state = state;
			this.start = start;
			this.end = end;
		}

		boolean openAt(int tick)
		{
			return tick >= start && tick < end;
		}

		/** Whether an attack thrown on {@code throwTick} and landing on {@code landTick} lands in it. */
		boolean takes(int throwTick, int landTick)
		{
			return openAt(landTick) && !(spareThrownBefore && throwTick < start);
		}
	}

	private static final class Boss
	{
		final String name;
		final BossRules.DamageModifierRules rules;
		final Map<String, Window> windows = new HashMap<>();
		int npcId = -1;

		Boss(String name, BossRules.DamageModifierRules rules)
		{
			this.name = name;
			this.rules = rules;
		}
	}

	/** Whether the player stands within {@code range} tiles of an npc of this id. */
	interface InsideCheck
	{
		boolean inside(int npcId, int range);
	}

	private final Map<Integer, Boss> bosses = new HashMap<>();
	private final Consumer<String> log;
	// Whether an npc of this id is present now (T-078: the Leviathan's pathfinder), for the states that end while one is.
	private java.util.function.IntPredicate present = id -> false;

	/** Tells the tracker how to ask whether an npc of an id is present, for {@code endWhileNpc}. */
	void setPresence(java.util.function.IntPredicate present)
	{
		this.present = present == null ? id -> false : present;
	}

	/** The first of the state's {@code endWhileNpc} ids present now, or -1. */
	private int presentEnder(BossRules.StateRule s)
	{
		if (s.endWhileNpc != null)
		{
			for (int id : s.endWhileNpc)
			{
				if (present.test(id))
				{
					return id;
				}
			}
		}
		return -1;
	}

	ModifierTracker(Consumer<String> log)
	{
		this.log = log == null ? line -> { } : log;
	}

	private Boss boss(int npcIndex, String name, BossRules.DamageModifierRules rules)
	{
		Boss b = bosses.get(npcIndex);
		if (b == null || b.rules != rules)
		{
			b = new Boss(name, rules);
			bosses.put(npcIndex, b);
		}
		return b;
	}

	private static List<BossRules.StateRule> states(BossRules.DamageModifierRules rules)
	{
		return rules == null || rules.states == null ? Collections.emptyList() : rules.states;
	}

	private static List<BossRules.ModifierRule> modifiers(BossRules.DamageModifierRules rules)
	{
		return rules == null || rules.modifiers == null ? Collections.emptyList() : rules.modifiers;
	}

	private Window open(Boss b, BossRules.StateRule s, int tick, String signal)
	{
		final Window w = b.windows.get(s.name);
		if (w != null && w.openAt(tick))
		{
			return null;
		}
		final int ender = presentEnder(s);
		if (ender >= 0)
		{
			log.accept(String.format("MOD %s: %s not on at tick %d (%s, but npc %d is here)", b.name, s.name, tick, signal,
				ender));
			return null;
		}
		final int end = s.ticks > 0 ? tick + s.ticks : s.maxTicks > 0 ? tick + s.maxTicks : Integer.MAX_VALUE;
		final Window opened = new Window(s.name, tick, end);
		b.windows.put(s.name, opened);
		log.accept(String.format("MOD %s: %s on at tick %d (%s%s)", b.name, s.name, tick, signal,
			s.ticks > 0 ? ", for " + s.ticks + " ticks" : ""));
		return opened;
	}

	private void close(Boss b, BossRules.StateRule s, int tick, String signal)
	{
		final Window w = b.windows.get(s.name);
		if (w == null || !w.openAt(tick) || tick < w.start + s.minTicks)
		{
			return;
		}
		w.end = tick;
		w.closed = true;
		log.accept(String.format("MOD %s: %s off at tick %d (%s, after %d ticks)", b.name, s.name, tick, signal,
			tick - w.start));
	}

	/** The player's own cast of {@code spell} lands on the boss on {@code landTick}. */
	void onPlayerSpell(int npcIndex, String name, BossRules.DamageModifierRules rules, String spell, int landTick)
	{
		final Boss b = boss(npcIndex, name, rules);
		for (BossRules.StateRule s : states(rules))
		{
			if (s.startOnPlayerSpell != null && s.startOnPlayerSpell.contains(spell))
			{
				final Window opened = open(b, s, landTick, "your " + spell);
				if (opened != null)
				{
					opened.spareThrownBefore = s.resetsAttackDelay;
				}
			}
		}
	}

	/** The boss started an animation. */
	void onBossAnim(int npcIndex, String name, BossRules.DamageModifierRules rules, int anim, int tick)
	{
		final Boss b = boss(npcIndex, name, rules);
		for (BossRules.StateRule s : states(rules))
		{
			if (contains(s.endOnBossAnim, anim))
			{
				close(b, s, tick, "anim " + anim);
			}
			if (contains(s.startOnBossAnim, anim))
			{
				open(b, s, tick, "anim " + anim);
			}
		}
	}

	/** One of the player's attacks landed with these modifiers: the windows they start and end. */
	void onModifiersLanded(int npcIndex, String name, BossRules.DamageModifierRules rules, List<String> landed,
		int tick)
	{
		if (landed.isEmpty())
		{
			return;
		}
		final Boss b = boss(npcIndex, name, rules);
		for (BossRules.StateRule s : states(rules))
		{
			if (s.endOnModifierLanded != null && landed.contains(s.endOnModifierLanded))
			{
				close(b, s, tick, "a hit with " + s.endOnModifierLanded);
			}
		}
		for (BossRules.StateRule s : states(rules))
		{
			if (s.startOnModifierLanded != null && landed.contains(s.startOnModifierLanded))
			{
				open(b, s, tick, "a hit with " + s.startOnModifierLanded);
			}
		}
	}

	/** The boss wears {@code npcId} from {@code tick}: logs the id-keyed modifiers turning on and off. */
	void onNpcId(int npcIndex, String name, BossRules.DamageModifierRules rules, int npcId, int tick)
	{
		final Boss b = boss(npcIndex, name, rules);
		final int was = b.npcId;
		b.npcId = npcId;
		if (was == npcId)
		{
			return;
		}
		for (BossRules.ModifierRule m : modifiers(rules))
		{
			if (m.npcIds == null)
			{
				continue;
			}
			final boolean before = was >= 0 && contains(m.npcIds, was);
			final boolean after = contains(m.npcIds, npcId);
			if (before != after)
			{
				log.accept(String.format("MOD %s: %s %s at tick %d (npc %d)", b.name, m.name, after ? "on" : "off", tick,
					npcId));
			}
		}
	}

	/** Once a tick: windows that ran out are logged closed, and those an npc's presence ends are closed (T-078). */
	void tick(int now)
	{
		for (Boss b : bosses.values())
		{
			for (BossRules.StateRule s : states(b.rules))
			{
				final Window w = b.windows.get(s.name);
				final int ender = w == null || w.closed || w.end <= now ? -1 : presentEnder(s);
				if (ender >= 0)
				{
					// One that has not started yet never does.
					w.end = Math.max(now, w.start);
					w.closed = true;
					log.accept(String.format("MOD %s: %s off at tick %d (npc %d is here, after %d ticks)", b.name, s.name,
						now, ender, Math.max(0, now - w.start)));
				}
			}
			for (Iterator<Window> it = b.windows.values().iterator(); it.hasNext(); )
			{
				final Window w = it.next();
				if (w.end != Integer.MAX_VALUE && now >= w.end)
				{
					if (w.end == now && !w.closed)
					{
						log.accept(String.format("MOD %s: %s off at tick %d (ran out after %d ticks)", b.name, w.state,
							now, now - w.start));
					}
					it.remove();
				}
			}
		}
	}

	/** Whether a state is open at this (landing) tick. */
	boolean isOpen(int npcIndex, String state, int tick)
	{
		final Boss b = bosses.get(npcIndex);
		final Window w = b == null ? null : b.windows.get(state);
		return w != null && w.openAt(tick);
	}

	private boolean takes(int npcIndex, String state, int throwTick, int landTick)
	{
		final Boss b = bosses.get(npcIndex);
		final Window w = b == null ? null : b.windows.get(state);
		return w != null && w.takes(throwTick, landTick);
	}

	/**
	 * The modifiers an attack takes: landing on {@code landTick} on the boss as
	 * {@code npcId}, in this style ({@code null} for any), from where the player
	 * stood when it went out ({@code behind}, and the npcs it was inside the range
	 * of, by id).
	 */
	List<BossRules.ModifierRule> applying(int npcIndex, BossRules.DamageModifierRules rules, int npcId, String style,
		int landTick, boolean behind, InsideCheck insideNpc)
	{
		return applying(npcIndex, rules, npcId, style, landTick, landTick, behind, insideNpc);
	}

	/**
	 * As above, for an attack thrown on {@code throwTick} (T-078): a window
	 * that spares attacks thrown before it starts does not take one.
	 */
	List<BossRules.ModifierRule> applying(int npcIndex, BossRules.DamageModifierRules rules, int npcId, String style,
		int throwTick, int landTick, boolean behind, InsideCheck insideNpc)
	{
		final List<BossRules.ModifierRule> out = new ArrayList<>(2);
		for (BossRules.ModifierRule m : modifiers(rules))
		{
			if (m.npcIds != null && !contains(m.npcIds, npcId))
			{
				continue;
			}
			if (m.state != null && !takes(npcIndex, m.state, throwTick, landTick))
			{
				continue;
			}
			if (m.notState != null && isOpen(npcIndex, m.notState, landTick))
			{
				continue;
			}
			if (m.behind != null && m.behind != behind)
			{
				continue;
			}
			if (m.insideNpc != null && !anyInside(m.insideNpc, insideNpc))
			{
				continue;
			}
			if (style != null && m.styles != null && !m.styles.contains(style))
			{
				continue;
			}
			out.add(m);
		}
		return out;
	}

	private static boolean anyInside(BossRules.InsideNpc inside, InsideCheck insideNpc)
	{
		if (inside.npcIds == null || insideNpc == null)
		{
			return false;
		}
		for (int id : inside.npcIds)
		{
			if (insideNpc.inside(id, inside.range))
			{
				return true;
			}
		}
		return false;
	}

	void forget(int npcIndex)
	{
		bosses.remove(npcIndex);
	}

	void clear()
	{
		bosses.clear();
	}

	static boolean contains(int[] ids, int id)
	{
		if (ids == null)
		{
			return false;
		}
		for (int i : ids)
		{
			if (i == id)
			{
				return true;
			}
		}
		return false;
	}

	// ---- the effects ------------------------------------------------------------------------------------------------

	/** The accuracy the modifiers leave: the last that replaces it, else the roll's own. */
	static double accuracy(List<BossRules.ModifierRule> mods, double accuracy)
	{
		double a = accuracy;
		for (BossRules.ModifierRule m : mods)
		{
			if (m.accuracy != null && a >= 0)
			{
				a = m.accuracy;
			}
		}
		return a;
	}

	/** One rolled hitsplat after the modifiers: the minimum hit on a landed roll, then each cap and multiplier. */
	static int damage(List<BossRules.ModifierRule> mods, int rolled, int max, boolean landed)
	{
		int v = rolled;
		for (BossRules.ModifierRule m : mods)
		{
			if (landed && m.minHitShare != null)
			{
				v = Math.max(v, (int) Math.floor(m.minHitShare * max));
			}
			if (m.cap != null)
			{
				v = Math.min(v, m.cap);
			}
			if (m.multiply != null && m.multiply.length == 2 && m.multiply[1] > 0)
			{
				v = v * m.multiply[0] / m.multiply[1];
			}
		}
		return v;
	}

	/** The most one hitsplat of this max can deal after the modifiers. */
	static int max(List<BossRules.ModifierRule> mods, int max)
	{
		return damage(mods, max, max, true);
	}

	/**
	 * One attack's damage as moments after the modifiers: each hitsplat a roll
	 * from 0 to its max at this accuracy, rolled independently, walked value by
	 * value so the rounding is the game's (floor of each hit, not a scaled mean).
	 * The accuracy is the modified one ({@link #accuracy}).
	 */
	static DamageMoments moments(List<BossRules.ModifierRule> mods, int[] maxima, double accuracy, int cap)
	{
		double mean = 0;
		double variance = 0;
		for (int max : maxima)
		{
			if (max <= 0)
			{
				continue;
			}
			final double each = accuracy / (max + 1);
			final double missValue = Math.min(cap, damage(mods, 0, max, false));
			double m = (1 - accuracy) * missValue;
			double second = (1 - accuracy) * missValue * missValue;
			for (int r = 0; r <= max; r++)
			{
				final double v = Math.min(cap, damage(mods, r, max, true));
				m += each * v;
				second += each * v * v;
			}
			mean += m;
			variance += Math.max(0, second - m * m);
		}
		return new DamageMoments(mean, variance + mean * mean);
	}

	/** The names, for a log line. */
	static String names(List<BossRules.ModifierRule> mods)
	{
		if (mods.isEmpty())
		{
			return "none";
		}
		final StringBuilder b = new StringBuilder();
		for (BossRules.ModifierRule m : mods)
		{
			b.append(b.length() == 0 ? "" : ", ").append(m.name);
		}
		return b.toString();
	}
}

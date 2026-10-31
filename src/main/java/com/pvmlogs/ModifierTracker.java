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
		/** Each count it carries (T-087): {how many, the tick the splat that showed it landed}. */
		final Map<String, int[]> stacks = new HashMap<>();
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
				open(b, s, tick + Math.max(0, s.startDelay), "anim " + anim
					+ (s.startDelay > 0 ? " at tick " + tick + ", " + s.startDelay + " ticks on" : ""));
			}
		}
	}

	private static List<BossRules.StackRule> stackRules(BossRules.DamageModifierRules rules)
	{
		return rules == null || rules.stacks == null ? Collections.emptyList() : rules.stacks;
	}

	/**
	 * A damage-over-time splat of this kind ({@code burn}) landed on the boss
	 * (T-087): it shows how many of the counts read off it the boss carries.
	 */
	void onBossDot(int npcIndex, String name, BossRules.DamageModifierRules rules, String kind, int amount, int tick)
	{
		if (kind == null)
		{
			return;
		}
		Boss b = null;
		for (BossRules.StackRule r : stackRules(rules))
		{
			if (!kind.equalsIgnoreCase(r.fromDot))
			{
				continue;
			}
			b = b == null ? boss(npcIndex, name, rules) : b;
			final int count = Math.max(0, Math.min(r.max > 0 ? r.max : Integer.MAX_VALUE, amount));
			final int[] was = b.stacks.get(r.name);
			final int before = was == null || tick - was[1] > r.lapseTicks ? 0 : was[0];
			b.stacks.put(r.name, new int[]{count, tick});
			if (count != before)
			{
				log.accept(String.format("MOD %s: %s %d at tick %d (a %s splat of %d)", b.name, r.name, count, tick, kind,
					amount));
			}
		}
	}

	/** How many of this count the boss carries on {@code tick}: the last splat's, until it lapses; 0 with none. */
	int stacks(int npcIndex, String stack, BossRules.DamageModifierRules rules, int tick)
	{
		final Boss b = bosses.get(npcIndex);
		final int[] seen = b == null || stack == null ? null : b.stacks.get(stack);
		if (seen == null || tick < seen[1])
		{
			return seen == null ? 0 : seen[0];
		}
		for (BossRules.StackRule r : stackRules(rules))
		{
			if (stack.equals(r.name))
			{
				return tick - seen[1] > r.lapseTicks ? 0 : seen[0];
			}
		}
		return 0;
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

	/**
	 * The window of {@code state} open on {@code tick}, or set to open after it
	 * (a stun cast still in the air), as {start, end}; null for none (T-074).
	 */
	int[] window(int npcIndex, String state, int tick)
	{
		final Boss b = bosses.get(npcIndex);
		final Window w = b == null ? null : b.windows.get(state);
		return w == null || w.end <= tick ? null : new int[]{w.start, w.end};
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
		return applying(npcIndex, rules, npcId, style, throwTick, landTick, behind, insideNpc, null);
	}

	/**
	 * As above, with whether the player stands in the boss's melee distance
	 * (T-120); null when unknown, and then no modifier with an
	 * {@code outOfMelee} condition applies.
	 */
	List<BossRules.ModifierRule> applying(int npcIndex, BossRules.DamageModifierRules rules, int npcId, String style,
		int throwTick, int landTick, boolean behind, InsideCheck insideNpc, Boolean inMelee)
	{
		final List<BossRules.ModifierRule> out = new ArrayList<>(2);
		for (BossRules.ModifierRule m : modifiers(rules))
		{
			if (m.npcIds != null && !contains(m.npcIds, npcId))
			{
				continue;
			}
			if (m.state != null && !(Boolean.TRUE.equals(m.stateAtThrow) ? isOpen(npcIndex, m.state, throwTick)
				: takes(npcIndex, m.state, throwTick, landTick)))
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
			if (m.outOfMelee != null && (inMelee == null || m.outOfMelee == inMelee))
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
			// A guaranteed max hit (T-098) cannot miss either.
			if (Boolean.TRUE.equals(m.guaranteedMax) && a >= 0)
			{
				a = 1.0;
			}
		}
		return a;
	}

	/** One rolled hitsplat after the modifiers: the minimum hit on a landed roll, then each cap and multiplier. */
	static int damage(List<BossRules.ModifierRule> mods, int rolled, int max, boolean landed)
	{
		return damage(mods, rolled, max, landed, 0);
	}

	/** As above for an attack's {@code hit}th hitsplat, from 0: a first-hit-only multiplier skips the rest (T-087). */
	static int damage(List<BossRules.ModifierRule> mods, int rolled, int max, boolean landed, int hit)
	{
		int v = rolled;
		for (BossRules.ModifierRule m : mods)
		{
			// T-098: whatever was rolled, the max.
			if (landed && Boolean.TRUE.equals(m.guaranteedMax))
			{
				v = Math.max(v, max);
			}
			if (landed && m.minHitShare != null)
			{
				v = Math.max(v, (int) Math.floor(m.minHitShare * max));
			}
			if (m.cap != null)
			{
				v = Math.min(v, m.cap);
			}
			if (m.multiply != null && m.multiply.length == 2 && m.multiply[1] > 0
				&& (hit == 0 || !Boolean.TRUE.equals(m.firstHitOnly)))
			{
				v = v * m.multiply[0] / m.multiply[1];
			}
		}
		return v;
	}

	/** The most one hitsplat of this max can deal after the modifiers. */
	static int max(List<BossRules.ModifierRule> mods, int max)
	{
		return damage(mods, max, max, true, 0);
	}

	/** The most an attack's {@code hit}th hitsplat (from 0) of this max can deal after the modifiers. */
	static int max(List<BossRules.ModifierRule> mods, int max, int hit)
	{
		return damage(mods, max, max, true, hit);
	}

	/**
	 * The boss's defence roll after the modifiers (T-087): each {@code defenceRoll}
	 * whose {@code attackTypes} take this one (STAB, SLASH, CRUSH, RANGED or MAGIC)
	 * multiplies it, rounded down.
	 */
	static int defenceRoll(List<BossRules.ModifierRule> mods, String attackType, int roll)
	{
		long r = roll;
		for (BossRules.ModifierRule m : mods)
		{
			if (m.defenceRoll != null && m.defenceRoll.length == 2 && m.defenceRoll[1] > 0
				&& (m.attackTypes == null || attackType != null && m.attackTypes.contains(attackType)))
			{
				r = r * m.defenceRoll[0] / m.defenceRoll[1];
			}
		}
		return (int) Math.max(0, r);
	}

	/**
	 * The player's attack roll after the modifiers (T-120): each {@code attackRoll}
	 * whose {@code attackTypes} take this one multiplies it, rounded down, as the
	 * DPS calc multiplies the ranged roll against the Royal Titans, last.
	 */
	static int attackRoll(List<BossRules.ModifierRule> mods, String attackType, int roll)
	{
		long r = roll;
		for (BossRules.ModifierRule m : mods)
		{
			if (m.attackRoll != null && m.attackRoll.length == 2 && m.attackRoll[1] > 0
				&& (m.attackTypes == null || attackType != null && m.attackTypes.contains(attackType)))
			{
				r = r * m.attackRoll[0] / m.attackRoll[1];
			}
		}
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, r));
	}

	/** The modifiers the live figures may show (T-087): all but those marked {@code hiddenLive}. */
	static List<BossRules.ModifierRule> shownLive(List<BossRules.ModifierRule> mods)
	{
		List<BossRules.ModifierRule> out = null;
		for (int i = 0; i < mods.size(); i++)
		{
			final boolean hidden = Boolean.TRUE.equals(mods.get(i).hiddenLive);
			if (hidden && out == null)
			{
				out = new ArrayList<>(mods.subList(0, i));
			}
			else if (!hidden && out != null)
			{
				out.add(mods.get(i));
			}
		}
		return out == null ? mods : out;
	}

	/** The version of the boss the modifiers name (T-087), the last that names one; null for its usual version. */
	static String version(List<BossRules.ModifierRule> mods)
	{
		String v = null;
		for (BossRules.ModifierRule m : mods)
		{
			if (m.version != null)
			{
				v = m.version;
			}
		}
		return v;
	}

	/**
	 * The modifiers whose conditions on the player's attack hold (T-098): the
	 * weapon in hand, its category, the attack type rolled (for a guaranteed max)
	 * and the player's highest attack bonus. A modifier that names none of them
	 * is kept.
	 *
	 * @param weaponId   the weapon's item id, or -1
	 * @param category   its WeaponCategory name, or null
	 * @param attackType the attack type rolled: STAB, SLASH, CRUSH, RANGED or MAGIC
	 * @param highest    the player's highest attack bonus, as {@link #highestAccuracy} has it
	 */
	static List<BossRules.ModifierRule> forPlayer(List<BossRules.ModifierRule> mods, int weaponId, String category,
		String attackType, String highest)
	{
		List<BossRules.ModifierRule> out = null;
		for (int i = 0; i < mods.size(); i++)
		{
			final BossRules.ModifierRule m = mods.get(i);
			final boolean holds = (m.weapons == null || contains(m.weapons, weaponId))
				&& (m.weaponCategories == null || category != null && m.weaponCategories.contains(category))
				&& (m.highestAccuracy == null || m.highestAccuracy.equals(highest))
				&& (!Boolean.TRUE.equals(m.guaranteedMax) || m.attackTypes == null || m.attackTypes.contains(attackType));
			if (!holds && out == null)
			{
				out = new ArrayList<>(mods.subList(0, i));
			}
			else if (holds && out != null)
			{
				out.add(m);
			}
		}
		return out == null ? mods : out;
	}

	/**
	 * The player's "highest accuracy style" (T-098), as the DPS calc reads the
	 * araxytes' rule: the largest of the attack bonuses over everything worn,
	 * the ties going to the first in the order slash, stab, crush, ranged, magic
	 * (the calc compares its totals in that order and takes a later one only if
	 * strictly larger). Levels, prayers and the style in use don't enter it.
	 */
	static String highestAccuracy(int stab, int slash, int crush, int magic, int ranged)
	{
		String best = "SLASH";
		int top = slash;
		if (stab > top)
		{
			best = "STAB";
			top = stab;
		}
		if (crush > top)
		{
			best = "CRUSH";
			top = crush;
		}
		if (ranged > top)
		{
			best = "RANGED";
			top = ranged;
		}
		if (magic > top)
		{
			best = "MAGIC";
		}
		return best;
	}

	/** The Defence, Magic and Ranged levels the modifiers add (T-091: Araxxor's enrage), summed; zeros for none. */
	static int[] levels(List<BossRules.ModifierRule> mods)
	{
		final int[] add = new int[3];
		for (BossRules.ModifierRule m : mods)
		{
			if (m.levels != null)
			{
				add[0] += m.levels.defence;
				add[1] += m.levels.magic;
				add[2] += m.levels.ranged;
			}
		}
		return add;
	}

	/** How many damage rolls the attack takes the best of (T-079): the most any modifier asks, one by default. */
	static int rolls(List<BossRules.ModifierRule> mods)
	{
		int n = 1;
		for (BossRules.ModifierRule m : mods)
		{
			if (m.rolls != null && m.rolls > n)
			{
				n = m.rolls;
			}
		}
		return n;
	}

	/**
	 * The chance the best of {@code rolls} rolls, each uniform from 0 to {@code max},
	 * comes to {@code r}: {@code ((r+1)^n - r^n) / (max+1)^n}; {@code 1/(max+1)} for one.
	 */
	static double rollChance(int r, int max, int rolls)
	{
		if (rolls <= 1)
		{
			return 1.0 / (max + 1);
		}
		return (Math.pow(r + 1, rolls) - Math.pow(r, rolls)) / Math.pow(max + 1, rolls);
	}

	/**
	 * Where a landed roll starts (T-083): the minimum, ⌊share × max⌋, when a
	 * modifier draws the roll from its minimum up rather than raising it to the
	 * minimum ({@code rollsFromMin}); 0 otherwise.
	 */
	static int rollFloor(List<BossRules.ModifierRule> mods, int max)
	{
		int lo = 0;
		for (BossRules.ModifierRule m : mods)
		{
			if (Boolean.TRUE.equals(m.rollsFromMin) && m.minHitShare != null)
			{
				lo = Math.max(lo, (int) Math.floor(m.minHitShare * max));
			}
		}
		return Math.min(lo, max);
	}

	/**
	 * One attack's damage as moments after the modifiers: each hitsplat a roll
	 * from 0 to its max at this accuracy (the best of {@link #rolls} where a
	 * modifier says so, from {@link #rollFloor} up where one says that), rolled
	 * independently, walked value by value so the rounding is the game's (floor
	 * of each hit, not a scaled mean). The accuracy is the modified one
	 * ({@link #accuracy}).
	 */
	static DamageMoments moments(List<BossRules.ModifierRule> mods, int[] maxima, double accuracy, int cap)
	{
		final int rolls = rolls(mods);
		double mean = 0;
		double variance = 0;
		for (int hit = 0; hit < maxima.length; hit++)
		{
			final int max = maxima[hit];
			if (max <= 0)
			{
				continue;
			}
			final double missValue = Math.min(cap, damage(mods, 0, max, false, hit));
			double m = (1 - accuracy) * missValue;
			double second = (1 - accuracy) * missValue * missValue;
			final int lo = rollFloor(mods, max);
			for (int r = lo; r <= max; r++)
			{
				final double each = accuracy * rollChance(r - lo, max - lo, rolls);
				final double v = Math.min(cap, damage(mods, r, max, true, hit));
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

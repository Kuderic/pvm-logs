package com.pvmlogs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names each hit on the player by the boss attack it came from, and says how much
 * of it was avoidable (T-041, from T-039's research). The rules are data, one
 * {@link BossRules.AttacksOnPlayer} block per boss version.
 *
 * <p>The game never says which attack a splat came from. What it shows is enough
 * at Vardorvis: the animation the boss started and the projectiles in the air
 * before the hit (T-018's {@link TargetActions}), the overhead up, the splat's
 * type, whether the boss healed off it, and whether a bleed followed. The last two
 * come after the hit, so a hit waits {@link BossRules.AttacksOnPlayer#waitTicks}
 * ticks before it is named; anything still waiting when the fight ends is named
 * then, with what there is. A hit that fits no attack counts as chip.
 *
 * <p>Pure: the plugin feeds it, so every rule is under test. Nothing it decides is
 * shown while the fight runs (Jagex's guidelines: nothing live about boss
 * behaviour); the plugin reads it at the fight's end.
 */
final class AttackClassifier
{
	/** What a hit fitting no attack is saved under. */
	static final String UNNAMED = "unnamed";
	static final String OFF_PRAYER = " off prayer";

	/** A hit, once named. */
	static final class Named
	{
		final int tick;
		final int amount;
		/** The class it is saved under: {@code melee}, {@code melee off prayer}, {@code axe}, ...; a 0 is "{@code <class> zero}". */
		final String name;
		final String evidence;
		/** The part of it that was avoidable: all of it, the part prayer would have stopped, or none. */
		final double avoidable;

		Named(int tick, int amount, String name, String evidence, double avoidable)
		{
			this.tick = tick;
			this.amount = amount;
			this.name = name;
			this.evidence = evidence;
			this.avoidable = avoidable;
		}

		/** The debug line's tail: {@code as axe (strong: no heal, bleed follows), avoidable 14}. */
		String describe()
		{
			return String.format("as %s (%s), avoidable %s", name, evidence, amount(avoidable));
		}

		private static String amount(double value)
		{
			return value == Math.rint(value) ? String.valueOf((long) value) : String.format("%.2f", value);
		}
	}

	private static final class Hit
	{
		final int tick;
		final int amount;
		final int splat;
		final String overhead;
		final List<TargetActions.Action> actions;

		Hit(int tick, int amount, int splat, String overhead, List<TargetActions.Action> actions)
		{
			this.tick = tick;
			this.amount = amount;
			this.splat = splat;
			this.overhead = overhead;
			this.actions = actions;
		}
	}

	private static final class Heal
	{
		final int tick;
		final int amount;

		Heal(int tick, int amount)
		{
			this.tick = tick;
			this.amount = amount;
		}
	}

	private final BossRules.AttacksOnPlayer rules;
	private final int waitTicks;
	// Waiting for their evidence, in the order they landed.
	private final Deque<Hit> pending = new ArrayDeque<>();
	// The boss's heals not yet matched to a hit, oldest first. Each pays for one hit only.
	private final List<Heal> heals = new ArrayList<>();
	// Every splat on the player of the last few ticks, for the "followed by" check: [tick, splat type].
	private final Deque<int[]> recentSplats = new ArrayDeque<>();
	private final Map<String, Integer> damageByClass = new LinkedHashMap<>();
	private double avoidable;

	AttackClassifier(BossRules.AttacksOnPlayer rules)
	{
		this.rules = rules;
		this.waitTicks = Math.max(0, rules.waitTicks);
	}

	/** The rules this classifier names by, or null where the version has none that can be used. */
	static BossRules.AttacksOnPlayer usable(BossRules.AttacksOnPlayer rules)
	{
		if (rules == null || rules.rulesVersion <= 0 || rules.attacks == null || rules.attacks.isEmpty())
		{
			return null;
		}
		return rules;
	}

	int rulesVersion()
	{
		return rules.rulesVersion;
	}

	/** A heal splat on the boss. */
	void onBossHeal(int tick, int amount)
	{
		if (amount > 0)
		{
			heals.add(new Heal(tick, amount));
		}
	}

	/**
	 * A splat on the player during the fight: a hit or damage over time, 0s too.
	 *
	 * @param overhead the protection prayer up, as {@link DamageTaken#protectionName} names it, or null
	 * @param actions  what the boss did in the ticks before, relative to this tick (see {@link TargetActions#actions})
	 */
	void onHit(int tick, int amount, int splatType, String overhead, List<TargetActions.Action> actions)
	{
		pending.addLast(new Hit(tick, Math.max(0, amount), splatType, overhead,
			actions == null ? Collections.emptyList() : actions));
		recentSplats.addLast(new int[]{tick, splatType});
	}

	/**
	 * Names the hits whose evidence is all in by {@code now}: every hit at least
	 * {@link BossRules.AttacksOnPlayer#waitTicks} ticks old, in the order they
	 * landed. Call once a tick, after the tick's splats.
	 */
	List<Named> settle(int now)
	{
		List<Named> out = null;
		while (!pending.isEmpty() && now - pending.peekFirst().tick >= waitTicks)
		{
			if (out == null)
			{
				out = new ArrayList<>(2);
			}
			out.add(name(pending.pollFirst()));
		}
		prune(now);
		return out == null ? Collections.emptyList() : out;
	}

	/** The fight is over: every hit still waiting is named with what there is. */
	List<Named> settleAll()
	{
		final List<Named> out = new ArrayList<>(pending.size());
		while (!pending.isEmpty())
		{
			out.add(name(pending.pollFirst()));
		}
		return out;
	}

	/** HP taken by class so far, of the hits named; 0s are not in it. */
	Map<String, Integer> damageByClass()
	{
		return Collections.unmodifiableMap(damageByClass);
	}

	/** The avoidable part of the hits named so far. */
	double avoidable()
	{
		return avoidable;
	}

	boolean hasPending()
	{
		return !pending.isEmpty();
	}

	// Heals and splats too old for anything still waiting, or anything yet to land, to use.
	private void prune(int now)
	{
		final int oldest = pending.isEmpty() ? now : Math.min(now, pending.peekFirst().tick);
		heals.removeIf(h -> h.tick < oldest);
		while (!recentSplats.isEmpty() && recentSplats.peekFirst()[0] < oldest)
		{
			recentSplats.pollFirst();
		}
	}

	private Named name(Hit hit)
	{
		if (hit.amount == 0)
		{
			return zero(hit);
		}
		final Boolean healed = healed(hit);
		for (BossRules.AttackRule rule : rules.attacks)
		{
			if (rule != null && fits(rule, hit, healed))
			{
				return count(hit, rule);
			}
		}
		return count(hit, null);
	}

	/**
	 * Whether the boss healed off this hit: an unmatched heal of the hit times the
	 * heal share, rounded down, on its tick or within the window after. One is
	 * used up whenever a hit finds it, the way T-039's harness has it. Null when
	 * the rules have no heal, or the hit is too small for one.
	 */
	private Boolean healed(Hit hit)
	{
		final BossRules.BossHeal heal = rules.bossHeal;
		if (heal == null || heal.share <= 0 || heal.splat != null && !contains(heal.splat, hit.splat))
		{
			return null;
		}
		final int expected = (int) Math.floor(hit.amount * heal.share + 1e-9);
		if (expected < 1)
		{
			return null;
		}
		for (int dt = 0; dt <= Math.max(0, heal.withinTicks); dt++)
		{
			for (Iterator<Heal> it = heals.iterator(); it.hasNext(); )
			{
				final Heal h = it.next();
				if (h.tick == hit.tick + dt && h.amount == expected)
				{
					it.remove();
					return true;
				}
			}
		}
		return false;
	}

	private boolean fits(BossRules.AttackRule rule, Hit hit, Boolean healed)
	{
		if (rule.splat != null && !contains(rule.splat, hit.splat))
		{
			return false;
		}
		if (rule.minDamage != null && hit.amount < rule.minDamage)
		{
			return false;
		}
		if (rule.maxDamage != null && hit.amount > rule.maxDamage)
		{
			return false;
		}
		if (rule.bossHeals != null && (healed == null || healed.booleanValue() != rule.bossHeals))
		{
			return false;
		}
		if (rule.overheadNot != null && protects(hit.overhead, rule.overheadNot))
		{
			return false;
		}
		if (rule.anim != null && !animationFits(rule.anim, hit))
		{
			return false;
		}
		if (rule.projectile != null && !projectileFits(rule.projectile, hit))
		{
			return false;
		}
		return rule.followedBy == null || followed(rule.followedBy, hit);
	}

	/** For each id, its latest start before the hit is inside the range: "anim 10340 a tick before". */
	private static boolean animationFits(BossRules.ActionCondition condition, Hit hit)
	{
		if (condition.ids == null)
		{
			return false;
		}
		for (int id : condition.ids)
		{
			int latest = Integer.MIN_VALUE;
			for (TargetActions.Action action : hit.actions)
			{
				if (!action.projectile && action.id == id)
				{
					latest = Math.max(latest, action.relativeTick);
				}
			}
			if (latest != Integer.MIN_VALUE && inRange(-latest, condition.ticksBefore))
			{
				return true;
			}
		}
		return false;
	}

	/** One of the projectiles landed inside the range of ticks before the hit. */
	private static boolean projectileFits(BossRules.ActionCondition condition, Hit hit)
	{
		if (condition.ids == null)
		{
			return false;
		}
		for (TargetActions.Action action : hit.actions)
		{
			if (action.projectile && contains(condition.ids, action.id) && inRange(-action.landsRelative,
				condition.ticksBefore))
			{
				return true;
			}
		}
		return false;
	}

	private boolean followed(BossRules.FollowedBy by, Hit hit)
	{
		for (int[] splat : recentSplats)
		{
			if ((by.splat == null || contains(by.splat, splat[1])) && inRange(splat[0] - hit.tick, by.withinTicks))
			{
				return true;
			}
		}
		return false;
	}

	private Named count(Hit hit, BossRules.AttackRule rule)
	{
		final String name;
		final String evidence;
		final double part;
		if (rule == null)
		{
			name = UNNAMED;
			evidence = "fits no attack: chip";
			part = 0;
		}
		else if ("offPrayer".equals(rule.avoidable))
		{
			final boolean prayed = protects(hit.overhead, rule.prayer);
			final boolean late = prayed && rule.prayedMax > 0 && hit.amount > rule.prayedMax;
			if (prayed && !late)
			{
				name = rule.name;
				evidence = rule.evidence == null ? "named" : rule.evidence;
				part = 0;
			}
			else
			{
				name = rule.name + OFF_PRAYER;
				evidence = late ? "inferred: above the " + rule.prayedMax + " prayer lets through, so a late prayer"
					: (rule.evidence == null ? "named" : rule.evidence) + ", no prayer";
				// What prayer would have stopped: the rest gets through it anyway, so it is chip.
				part = hit.amount * Math.max(0, 1 - rule.prayedShare);
			}
		}
		else
		{
			name = rule.name;
			evidence = rule.evidence == null ? "named" : rule.evidence;
			part = "always".equals(rule.avoidable) ? hit.amount : 0;
		}
		damageByClass.merge(name, hit.amount, Integer::sum);
		avoidable += part;
		return new Named(hit.tick, hit.amount, name, evidence, part);
	}

	/** A 0 changes nothing; it is named by the boss's animation alone, for the debug log. */
	private Named zero(Hit hit)
	{
		for (BossRules.AttackRule rule : rules.attacks)
		{
			if (rule != null && rule.anim != null && animationFits(rule.anim, hit))
			{
				return new Named(hit.tick, 0, rule.name + " zero", "by animation", 0);
			}
		}
		return new Named(hit.tick, 0, UNNAMED + " zero", "nothing to name it by", 0);
	}

	/**
	 * Whether the overhead protects from a style: the standard book's prayer or the
	 * Ruinous Powers' Dampen prayer of it.
	 */
	static boolean protects(String overhead, String style)
	{
		if (overhead == null || style == null)
		{
			return false;
		}
		switch (style)
		{
			case "melee":
				return overhead.equals("melee") || overhead.equals("dampen melee");
			case "missiles":
			case "ranged":
				return overhead.equals("missiles") || overhead.equals("dampen ranged");
			case "magic":
				return overhead.equals("magic") || overhead.equals("dampen magic");
			default:
				return overhead.equals(style);
		}
	}

	private static boolean inRange(int value, int[] range)
	{
		if (range == null || range.length == 0)
		{
			return true;
		}
		final int lo = range[0];
		final int hi = range.length > 1 ? range[1] : range[0];
		return value >= lo && value <= hi;
	}

	private static boolean contains(int[] values, int value)
	{
		for (int v : values)
		{
			if (v == value)
			{
				return true;
			}
		}
		return false;
	}
}

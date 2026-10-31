package com.pvmlogs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The room's damage on a boss (T-106): a splat that is neither the player's nor
 * another player's. Araxxor's are the Ruptura araxytes' explosions under him and,
 * once enraged, his own cleave on the tiles he stands on (OSRS Wiki,
 * Araxxor/Strategies). The game shows them as another player's damage, type 17,
 * so the plugin read every such kill of Eric's as a group kill.
 *
 * <p><b>Whose it is.</b> A splat the game shows as someone else's is another
 * player's only if another player is there to have dealt it. With no other
 * player in the scene, it is the room's, at any boss. That is the deciding test;
 * the boss's rules ({@code roomDamage} in {@code boss-rules.json}) only name the
 * source in the log. A player attacking from beyond the scene the client loads
 * (about 15 tiles from the local player, past the boss) would be missed and their
 * splats taken as the room's: a guess at how rare that is, not measured.
 */
final class RoomDamage
{
	/** T-131: the source of room damage no rule names, in {@code Fight.roomDamageBySource}. */
	static final String OTHER = "other";
	/** T-131: the label for room damage no rule names, beside named sources; alone it's T-109's {@code room dmg}. */
	static final String OTHER_LABEL = "other room dmg";
	static final String TOTAL_LABEL = "room dmg";

	private RoomDamage()
	{
	}

	/**
	 * T-131: the rule a room splat is booked under, for Details' rows: the one whose amounts hold it, of those that fit
	 * as {@link #sourceOf} has them; with none holding it, of those it fits as a capped killing splat, the one with the
	 * smallest least (the smallest hit that could have been capped). The log names both. In Eric's 10:35 log
	 * ({@code pvm-debug-20261031-103506}, [2170]) a 6 with 6 left, enraged, fits the explosion and his cleave; it came
	 * on his cleave's 4 ticks ([2158], [2162], [2166]) just after its animation, so the cleave. Null for none.
	 */
	static BossRules.RoomDamageRule ruleFor(List<BossRules.RoomDamageRule> rules, int amount, int left,
		Predicate<String> open)
	{
		if (rules == null)
		{
			return null;
		}
		BossRules.RoomDamageRule capped = null;
		for (BossRules.RoomDamageRule r : rules)
		{
			if (r.amounts == null || r.amounts.length != 2 || r.state != null && !open.test(r.state))
			{
				continue;
			}
			if (amount >= r.amounts[0] && amount <= r.amounts[1])
			{
				return r;
			}
			if (amount < r.amounts[0] && amount > 0 && amount == left
				&& (capped == null || r.amounts[0] < capped.amounts[0]))
			{
				capped = r;
			}
		}
		return capped;
	}

	/** T-131: one of Details' room-damage rows: its label, the damage and its tooltip (null for the generic one). */
	static final class Source
	{
		final String label;
		final int damage;
		final String tip;

		Source(String label, int damage, String tip)
		{
			this.label = label;
			this.damage = damage;
			this.tip = tip;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Source))
			{
				return false;
			}
			final Source x = (Source) o;
			return label.equals(x.label) && damage == x.damage && java.util.Objects.equals(tip, x.tip);
		}

		@Override
		public int hashCode()
		{
			return java.util.Objects.hash(label, damage, tip);
		}

		@Override
		public String toString()
		{
			return label + " " + damage;
		}
	}

	/**
	 * T-131: the room damage over these fights by source, as Details' rows: each rule's by its label (from the rules of
	 * the fight's own boss), summed by label, largest first; damage no rule names under {@link #OTHER_LABEL}. A fight
	 * saved before T-131, with its total and no split, adds its total to a {@link #TOTAL_LABEL} row. Null when nothing
	 * is split (old kills alone, or room damage no rule names alone): Details then shows T-109's one total.
	 */
	static List<Source> split(List<Fight> fights, BossRules rules)
	{
		final Map<String, Integer> byLabel = new LinkedHashMap<>();
		final Map<String, String> tips = new LinkedHashMap<>();
		int unsplit = 0;
		boolean named = false;
		for (Fight f : fights)
		{
			if (f.getRoomDamage() <= 0)
			{
				continue;
			}
			final Map<String, Integer> by = f.getRoomDamageBySource();
			if (by == null || by.isEmpty())
			{
				unsplit += f.getRoomDamage();
				continue;
			}
			final BossRules.Boss boss = rules == null ? null : rules.forNpc(f.getTargetId());
			for (Map.Entry<String, Integer> e : by.entrySet())
			{
				final BossRules.RoomDamageRule rule = OTHER.equals(e.getKey()) ? null
					: rule(boss == null ? null : boss.roomDamage, e.getKey());
				final String label = rule == null ? OTHER_LABEL : rule.label != null ? rule.label : rule.name;
				named |= rule != null;
				byLabel.merge(label, Math.max(0, e.getValue()), Integer::sum);
				if (rule != null && rule.tip != null)
				{
					tips.putIfAbsent(label, rule.tip);
				}
			}
		}
		if (!named)
		{
			return null;
		}
		final List<Source> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : byLabel.entrySet())
		{
			if (e.getValue() > 0)
			{
				out.add(new Source(e.getKey(), e.getValue(), tips.get(e.getKey())));
			}
		}
		if (unsplit > 0)
		{
			out.add(new Source(TOTAL_LABEL, unsplit, null));
		}
		out.sort((a, b) -> a.damage != b.damage ? Integer.compare(b.damage, a.damage) : a.label.compareTo(b.label));
		return out;
	}

	/** T-131: RECONCILE's split of its {@code room} term, {@code  (hit himself 90, araxyte explosions 47)}; empty for none. */
	static String reconcileSplit(Map<String, Integer> bySource, List<BossRules.RoomDamageRule> rules)
	{
		if (bySource == null || bySource.isEmpty())
		{
			return "";
		}
		final List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> e : bySource.entrySet())
		{
			final BossRules.RoomDamageRule rule = OTHER.equals(e.getKey()) ? null : rule(rules, e.getKey());
			parts.add((rule == null ? OTHER.equals(e.getKey()) ? OTHER : e.getKey()
				: rule.label != null ? rule.label : rule.name) + " " + e.getValue());
		}
		return " (" + String.join(", ", parts) + ")";
	}

	private static BossRules.RoomDamageRule rule(List<BossRules.RoomDamageRule> rules, String name)
	{
		if (rules != null && name != null)
		{
			for (BossRules.RoomDamageRule r : rules)
			{
				if (name.equals(r.name))
				{
					return r;
				}
			}
		}
		return null;
	}

	/**
	 * What a splat the game shows as another's is: another player's while one is
	 * there, else the room's. Any other kind is left as it is.
	 *
	 * @param otherPlayers the players in the scene besides the local player
	 */
	static HitsplatKind kindOf(HitsplatKind kind, int otherPlayers)
	{
		return kind == HitsplatKind.OTHER_PLAYER && otherPlayers <= 0 ? HitsplatKind.ROOM : kind;
	}

	/**
	 * The names of the boss's room-damage sources this splat fits, joined with
	 * "or"; null for none. It fits a rule whose state (if any) is open and whose
	 * amounts hold it, or, below the lowest, a killing splat capped at what the
	 * boss had left.
	 *
	 * @param left  what the boss had left before the splat, or -1 if unknown
	 * @param open  whether a damage-modifier state is open on the boss now
	 */
	static String sourceOf(List<BossRules.RoomDamageRule> rules, int amount, int left, Predicate<String> open)
	{
		if (rules == null)
		{
			return null;
		}
		final List<String> names = new ArrayList<>();
		for (BossRules.RoomDamageRule r : rules)
		{
			if (r.amounts == null || r.amounts.length != 2 || r.state != null && !open.test(r.state))
			{
				continue;
			}
			final boolean inRange = amount >= r.amounts[0] && amount <= r.amounts[1];
			final boolean cappedKill = amount < r.amounts[0] && amount > 0 && amount == left;
			if (inRange || cappedKill)
			{
				names.add(r.name);
			}
		}
		if (names.isEmpty())
		{
			return null;
		}
		final String joined = String.join(" or ", names);
		// All he had left: it killed him, capped at those hitpoints or not (a smaller one below the range was).
		return amount == left ? joined + ", the killing splat at the " + left + " left" : joined;
	}
}

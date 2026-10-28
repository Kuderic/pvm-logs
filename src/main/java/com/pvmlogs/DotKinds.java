package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-062: damage over time by kind. {@code Fight} keeps its totals, dealt and
 * taken, as it always has, and beside them how much of each was burn, poison,
 * venom or bleed, keyed by {@link HitsplatKind#typeName}. A fight saved before
 * T-062 has the totals and no kinds; whatever part of a total has no kind is the
 * remainder, shown as one line of its own. Summing a scope's kinds is a few map
 * entries a fight, done when the scope is read, never a frame.
 */
final class DotKinds
{
	/** The rows' order (the brief's): any other kind follows these, by name, and the remainder comes last. */
	static final List<String> ORDER = Collections.unmodifiableList(Arrays.asList("burn", "poison", "venom", "bleed"));
	/** The overlay's name for a total's part with no kind: an old kill's whole total. */
	static final String REMAINDER = "DoT";
	/** The debug line's name for it. */
	static final String OTHER = "other";

	private DotKinds()
	{
	}

	/**
	 * Adds a splat to a kind, making the map on the first one.
	 *
	 * @return the map, made here if it was null; null still when there is nothing to add
	 */
	static Map<String, Integer> add(Map<String, Integer> byKind, String kind, int amount)
	{
		if (kind == null || amount <= 0)
		{
			return byKind;
		}
		final Map<String, Integer> map = byKind == null ? new LinkedHashMap<>() : byKind;
		map.merge(kind, amount, Integer::sum);
		return map;
	}

	/** Adds every kind of {@code from} into {@code into}. */
	static void addAll(Map<String, Integer> into, Map<String, Integer> from)
	{
		if (from != null)
		{
			from.forEach((kind, amount) -> into.merge(kind, amount, Integer::sum));
		}
	}

	/** What the kinds add up to. */
	static int sum(Map<String, Integer> byKind)
	{
		int total = 0;
		if (byKind != null)
		{
			for (int amount : byKind.values())
			{
				total += Math.max(0, amount);
			}
		}
		return total;
	}

	/** The part of {@code total} no kind accounts for: an old kill's, or 0. */
	static int remainder(Map<String, Integer> byKind, int total)
	{
		return Math.max(0, total - sum(byKind));
	}

	/**
	 * The lines to show, in order: each kind with damage (burn, poison, venom,
	 * bleed, then any other by name), then the remainder under {@code remainderName}
	 * when there is one. Empty when {@code total} is 0 and no kind has damage.
	 */
	static LinkedHashMap<String, Integer> split(Map<String, Integer> byKind, int total, String remainderName)
	{
		final LinkedHashMap<String, Integer> lines = new LinkedHashMap<>();
		if (byKind != null)
		{
			for (String kind : ORDER)
			{
				final Integer amount = byKind.get(kind);
				if (amount != null && amount > 0)
				{
					lines.put(kind, amount);
				}
			}
			final List<String> others = new ArrayList<>();
			for (Map.Entry<String, Integer> e : byKind.entrySet())
			{
				if (!ORDER.contains(e.getKey()) && e.getValue() != null && e.getValue() > 0)
				{
					others.add(e.getKey());
				}
			}
			Collections.sort(others);
			for (String kind : others)
			{
				lines.put(kind, byKind.get(kind));
			}
		}
		final int rest = remainder(byKind, total);
		if (rest > 0)
		{
			lines.merge(remainderName, rest, Integer::sum);
		}
		return lines;
	}

	/**
	 * The fight's debug line: {@code DOT Vardorvis: dealt burn 8, venom 30; taken
	 * poison 25}. Only kinds with damage; a part with no kind reads {@code other};
	 * a side with none reads {@code none}. Null when there was no damage over time
	 * either way.
	 */
	static String debugLine(Fight fight)
	{
		final Map<String, Integer> dealt = split(fight.getDotDamageByKind(), fight.getDotDamage(), OTHER);
		final Map<String, Integer> taken = split(fight.getDotTakenByKind(), fight.getDotTaken(), OTHER);
		if (dealt.isEmpty() && taken.isEmpty())
		{
			return null;
		}
		return "DOT " + fight.getTargetName() + ": dealt " + words(dealt) + "; taken " + words(taken);
	}

	private static String words(Map<String, Integer> lines)
	{
		if (lines.isEmpty())
		{
			return "none";
		}
		final StringBuilder b = new StringBuilder();
		for (Map.Entry<String, Integer> e : lines.entrySet())
		{
			if (b.length() > 0)
			{
				b.append(", ");
			}
			b.append(e.getKey()).append(' ').append(e.getValue());
		}
		return b.toString();
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fight's defence drains, saved with it (T-049): the target's Defence and Magic
 * at the start and the end, its base and floor, the player's own specials thrown
 * and landed per weapon, the party's drains, and every drain on the way. Plain
 * fields for the history file; a fight saved before T-049 has none, and
 * {@link Fight}'s getters read -1 for it.
 *
 * <p>T-141 adds, add-only, {@link #stats} (one per stat a drain changed: Defence,
 * Magic, the magic defence bonus) and {@link #sources} (one per weapon that
 * drained, the player's own or a party member's), each with the expected damage
 * its drains added. Null on a fight saved before; the flat fields stay as they
 * were.
 */
final class DrainRecord
{
	/** 1 from T-049, 2 from T-141 ({@link #stats}, {@link #sources}); 0 is never saved. */
	int version = 2;
	boolean immune;
	int defenceBase;
	int defenceFloor;
	int magicBase;
	int magicFloor;
	int magicDefenceBase;
	int defenceStart;
	int defenceEnd;
	int magicStart;
	int magicEnd;
	int magicDefenceStart;
	int magicDefenceEnd;
	/** The player's own specials by RuneLite's weapon name: {thrown, landed}. Only draining specials. */
	Map<String, int[]> specs = new LinkedHashMap<>();
	/** Drains by other party members, as their special attack counters sent them. */
	int partyDrains;
	List<DrainEvent> events = new ArrayList<>();
	/** T-141: each stat a drain changed in the fight, in Defence, Magic, magic defence order. Null before T-141. */
	List<StatDrain> stats;
	/** T-141: each weapon whose specials drained in the fight, in the order they first did. Null before T-141. */
	List<SourceDrain> sources;

	/** T-141: one drained stat. */
	static final class StatDrain
	{
		static final String DEFENCE = "defence";
		static final String MAGIC = "magic";
		static final String MAGIC_DEFENCE = "magicDefence";

		/** {@link #DEFENCE}, {@link #MAGIC} or {@link #MAGIC_DEFENCE}. */
		String stat;
		/** Its base: the level, or for magic defence the data's bonus. */
		int base;
		/** The lowest drains can take it: the level's floor; 0 for magic defence, the bonus it can't be reduced below. */
		int floor;
		/**
		 * At the fight's start and end: the level; for magic defence the data's bonus less the Eye of ayak's reduction,
		 * unclamped ({@code base - start} is the reduction then).
		 */
		int start;
		int end;
		/** Ticks between each level it gets back; 0 for none (magic defence never comes back). */
		int regenTicks;
		/**
		 * For magic defence, the bonus as attacks met it, the target's modifiers in (Yama's +60 or -30), at the fight's
		 * first and last figures with its drains; null when not known (no figures, or another stat).
		 */
		Integer bonusAtStart;
		Integer bonusAtEnd;
		/** The expected damage its drains added to the attacks that roll against it, as made (T-141). */
		double value;
	}

	/** T-141: one weapon whose specials drained: the player's own, or a party member's as their counter sent it. */
	static final class SourceDrain
	{
		/** RuneLite's {@code SpecialWeapon} name, e.g. {@code EMBERLIGHT}, {@code EYE_OF_AYAK}. */
		String weapon;
		boolean own;
		/** Specials thrown and landed; for a party member's, the drains their counter sent (thrown) and landed. */
		int thrown;
		int landed;
		/** What it took off each stat, in all ({@link StatDrain}'s names): levels, or points of magic defence bonus. */
		Map<String, Integer> drained = new LinkedHashMap<>();
		/** The expected damage its drains added, as made, and that per stat. */
		double value;
		Map<String, Double> valueByStat = new LinkedHashMap<>();
	}

	/** T-141: the source's key in {@link DrainLuck}'s values: its weapon, a party member's marked as such. */
	static String sourceKey(String weapon, boolean own)
	{
		return own ? weapon : weapon + " (party)";
	}

	/**
	 * T-141: the values {@link DrainLuck} worked out, into {@link #stats} and {@link #sources}: by stat, and by source and
	 * stat ({@code source key -> stat -> value}). Nothing where either list is missing.
	 */
	void fillValues(Map<String, Double> byStat, Map<String, Map<String, Double>> bySource, Integer bonusAtStart,
		Integer bonusAtEnd)
	{
		if (stats != null)
		{
			for (StatDrain d : stats)
			{
				d.value = byStat == null ? 0 : byStat.getOrDefault(d.stat, 0.0);
				if (StatDrain.MAGIC_DEFENCE.equals(d.stat))
				{
					d.bonusAtStart = bonusAtStart;
					d.bonusAtEnd = bonusAtEnd;
				}
			}
		}
		if (sources != null)
		{
			for (SourceDrain d : sources)
			{
				final Map<String, Double> v = bySource == null ? null : bySource.get(sourceKey(d.weapon, d.own));
				d.valueByStat = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
				double total = 0;
				for (double x : d.valueByStat.values())
				{
					total += x;
				}
				d.value = total;
			}
		}
	}

	/** T-141: the stat by name, or null. */
	StatDrain stat(String name)
	{
		if (stats != null)
		{
			for (StatDrain d : stats)
			{
				if (d.stat.equals(name))
				{
					return d;
				}
			}
		}
		return null;
	}

	/** Own specials thrown, all weapons. */
	int ownThrown()
	{
		int total = 0;
		for (int[] counts : specs.values())
		{
			total += counts[0];
		}
		return total;
	}

	/** Own specials landed, all weapons. */
	int ownLanded()
	{
		int total = 0;
		for (int[] counts : specs.values())
		{
			total += counts[1];
		}
		return total;
	}
}

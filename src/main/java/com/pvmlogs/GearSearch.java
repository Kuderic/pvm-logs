package com.pvmlogs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.EquipmentInventorySlot;

// The best gear the player could have been wearing for the weapon in hand.
//
// The weapon is taken as given and every other slot judged. Which weapon to bring is where the ambiguity lives - a
// halberd for reach, a spec weapon held for a phase - and holding it fixed removes every per-boss exception. A player
// who brought the wrong weapon knows to distrust the figure; one who brought the right weapon and missed the switches
// is still caught.
//
// Loadouts are scored by damage per tick, not per attack (T-010): average hit over the weapon's attack speed. With the
// weapon held fixed no other slot changes the speed, so today the two rank alike - but per tick is the figure that stays
// right if a candidate ever does, where per attack would rank a slow heavy hitter above a faster weapon that outdamages
// it.
//
// Some items are worn for what the damage figures cannot see - an amulet of blood fury for its healing - and the player
// names them in the settings. Their slot is kept as worn, so a better-damage amulet is not called a missed switch.
final class GearSearch
{
	// Two passes, so a slot judged early can be reconsidered once a later one changed what the loadout is worth. The
	// second is cheap insurance.
	private static final int MAX_PASSES = 2;
	// Expected damage per tick is a double, so anything under this is arithmetic noise rather than a switch worth making.
	// Shared with the caller, which holds an answer across ticks and needs the same threshold.
	static final double MEANINGFUL = 1e-6;

	/** One thing that could go in one slot. */
	static final class Candidate
	{
		private final EquipmentInventorySlot slot;
		private final int itemId;

		Candidate(EquipmentInventorySlot slot, int itemId)
		{
			this.slot = slot;
			this.itemId = itemId;
		}
	}

	/** What a loadout is worth, which only the combat model can answer. */
	interface Score
	{
		double of(Loadout gear);
	}

	private GearSearch()
	{
	}

	/**
	 * The best loadout reachable from what is worn by swapping in the candidates,
	 * or the worn loadout itself when nothing improves on it - returned by
	 * identity, so a caller can test for "nothing was missed" without comparing
	 * figures.
	 */
	static Loadout best(Loadout worn, List<Candidate> candidates, boolean twoHandedWeapon, Score score)
	{
		return best(worn, candidates, twoHandedWeapon, Collections.emptySet(), score);
	}

	/**
	 * As above, leaving the {@code kept} slots as they are worn: the ones
	 * holding an item the player wears on purpose.
	 */
	static Loadout best(Loadout worn, List<Candidate> candidates, boolean twoHandedWeapon,
		Set<EquipmentInventorySlot> kept, Score score)
	{
		Loadout best = worn;
		double bestScore = score.of(worn);
		for (int pass = 0; pass < MAX_PASSES; pass++)
		{
			boolean improved = false;
			for (Candidate candidate : candidates)
			{
				// The weapon is not judged, and a two-hander leaves no shield slot - offering one would be advice the game will not
				// let the player take.
				if (candidate.slot == EquipmentInventorySlot.WEAPON
					|| (twoHandedWeapon && candidate.slot == EquipmentInventorySlot.SHIELD)
					|| kept.contains(candidate.slot))
				{
					continue;
				}
				final Loadout swapped = best.with(candidate.slot, candidate.itemId);
				if (swapped == best)
				{
					continue; // already in that slot
				}
				final double swappedScore = score.of(swapped);
				if (swappedScore > bestScore + MEANINGFUL)
				{
					best = swapped;
					bestScore = swappedScore;
					improved = true;
				}
			}
			if (!improved)
			{
				break;
			}
		}
		return best;
	}

	/**
	 * Expected damage per tick: the average hit over the attack speed. A
	 * negative hit is the combat model's "no figure" and stays negative.
	 */
	static double perTick(double averageHit, int speedTicks)
	{
		return averageHit < 0 ? averageHit : averageHit / Math.max(1, speedTicks);
	}

	/**
	 * What a missed switch cost one attack, in that attack's expected damage:
	 * the better loadout's damage per tick over the ticks this attack took.
	 * Never negative, and nothing when either figure is missing.
	 */
	static double switchCost(double wornHit, int wornSpeed, double bestHit, int bestSpeed)
	{
		if (wornHit < 0 || bestHit < 0)
		{
			return 0;
		}
		return Math.max(0, (perTick(bestHit, bestSpeed) - perTick(wornHit, wornSpeed)) * Math.max(1, wornSpeed));
	}

	/** The setting's comma-separated item names, trimmed and lower-cased. */
	static Set<String> deliberateNames(String setting)
	{
		final Set<String> names = new HashSet<>();
		if (setting == null)
		{
			return names;
		}
		for (String name : setting.split(","))
		{
			final String trimmed = name.trim().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty())
			{
				names.add(trimmed);
			}
		}
		return names;
	}

	/**
	 * The slots whose worn item is one the player named as worn on purpose.
	 * Names are matched whole and without case.
	 */
	static Set<EquipmentInventorySlot> keptSlots(Loadout worn, Set<String> deliberate,
		Function<EquipmentInventorySlot, String> nameOf)
	{
		final Set<EquipmentInventorySlot> kept = EnumSet.noneOf(EquipmentInventorySlot.class);
		if (deliberate.isEmpty())
		{
			return kept;
		}
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (slot == EquipmentInventorySlot.WEAPON || worn.id(slot) < 0)
			{
				continue;
			}
			final String name = nameOf.apply(slot);
			if (name != null && deliberate.contains(name.trim().toLowerCase(Locale.ROOT)))
			{
				kept.add(slot);
			}
		}
		return kept;
	}
}

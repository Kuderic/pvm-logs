package com.pvmlogs;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import net.runelite.api.gameval.ItemID;

/**
 * Damage the player reflects onto what hit them (T-017 Part B): a recoil ring's
 * and vengeance's. Both put a splat on the attacker that the game marks as the
 * player's own (isMine(), type 16 in Eric's 23:08 log) with no attack behind it,
 * so before this they were booked as weapon damage, and could book an attack.
 *
 * <p>From the OSRS Wiki (Ring of recoil, Vengeance; read 2026-10-27):
 * <ul>
 * <li>Recoil: "10% + 1 of the damage received by the player (rounded down) is
 * dealt to the attacker", each hit on its own, and only a hit that does damage.
 * A ring of recoil breaks after 40; a ring of suffering charged with recoils
 * keeps going.</li>
 * <li>Vengeance: "reflects 75% of damage taken back to an opponent", rounded
 * down with a minimum of 1, on the next hit; the player says "Taste
 * vengeance!".</li>
 * </ul>
 *
 * <p>A guess until a log with T-018's TAKEN lines shows it: the reflected splat
 * lands on the tick of the hit it answers or the one after, and vengeance's
 * varbit falls (or the text shows) on one of those. The 23:08 log fits: the
 * vengeance cast on 2803, an unpaid 10 on the boss on 2804; it has no line for
 * the hits taken, so neither the hit nor its tick can be read from it.
 *
 * <p>Pure, so the plugin and the tests match alike. The plugin hands in the hits
 * taken per tick ({@code hitsTakenOn}, T-018) and whether a recoil ring was worn.
 */
final class Reflect
{
	/** How far behind the splat a hit it answers may be, and vengeance's rebound. */
	static final int WINDOW_TICKS = 1;
	private static final int KEEP_TICKS = 8;

	private static final Set<Integer> RECOIL_RINGS = new HashSet<>(java.util.Arrays.asList(
		ItemID.RING_OF_RECOIL,
		// The ring of suffering charged with recoils: (r), and (ri) imbued at the Nightmare Zone, Soul Wars and LMS.
		ItemID.ZENYTE_RING_ENCHANTED_RECOIL, ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL,
		ItemID.SW_ZENYTE_RING_ENCHANTED_RECOIL, ItemID.PVPA_ZENYTE_RING_ENCHANTED_RECOIL));

	enum Source
	{
		RECOIL("recoil"),
		VENGEANCE("vengeance");

		final String label;

		Source(String label)
		{
			this.label = label;
		}
	}

	/** A splat matched to the hit it answers. */
	static final class Match
	{
		final Source source;
		final int hit;
		final int hitTick;

		Match(Source source, int hit, int hitTick)
		{
			this.source = source;
			this.hit = hit;
			this.hitTick = hitTick;
		}

		@Override
		public String toString()
		{
			return source.label + " of a " + hit + " taken on " + hitTick;
		}
	}

	// Per tick of hits taken: which of them (by their order in hitsTakenOn) recoil has already answered.
	private final Map<Integer, Set<Integer>> recoilAnswered = new HashMap<>();
	// Ticks vengeance's rebound went off on, and those already matched to a splat.
	private final TreeSet<Integer> vengeanceFired = new TreeSet<>();
	private final Set<Integer> vengeanceAnswered = new HashSet<>();

	/** What recoil sends back for one hit: 10% rounded down, plus 1, and nothing for a hit that did no damage. */
	static int recoilOf(int hit)
	{
		return hit <= 0 ? 0 : hit / 10 + 1;
	}

	/** What vengeance sends back for its hit: 75% rounded down, at least 1, and nothing for a hit that did no damage. */
	static int vengeanceOf(int hit)
	{
		return hit <= 0 ? 0 : Math.max(1, hit * 3 / 4);
	}

	static boolean isRecoilRing(int itemId)
	{
		return RECOIL_RINGS.contains(itemId);
	}

	/** Vengeance's rebound went off on this tick: its varbit fell, or the player said "Taste vengeance!". */
	void vengeanceFired(int tick)
	{
		vengeanceFired.add(tick);
		prune(tick);
	}

	/**
	 * Which reflection a splat of {@code amount} landing on {@code tick} is, and
	 * claims it, so one hit is answered once; null when none fits. Vengeance is
	 * asked first: it is rarer and its amount is the more telling.
	 *
	 * @param hitsOn     the hits taken on a tick, in the order they landed
	 * @param recoilWorn whether a recoil ring was worn on this tick or the one before
	 */
	Match claim(int tick, int amount, IntFunction<List<Integer>> hitsOn, boolean recoilWorn)
	{
		if (amount <= 0)
		{
			return null;
		}
		for (int fired : vengeanceFired.subSet(tick - WINDOW_TICKS, true, tick, true))
		{
			if (vengeanceAnswered.contains(fired))
			{
				continue;
			}
			for (int hitTick = fired; hitTick >= fired - WINDOW_TICKS; hitTick--)
			{
				for (int hit : hits(hitsOn, hitTick))
				{
					if (vengeanceOf(hit) == amount)
					{
						// The varbit and the text can each mark one rebound, a tick apart.
						for (int t = fired - WINDOW_TICKS; t <= fired + WINDOW_TICKS; t++)
						{
							vengeanceAnswered.add(t);
						}
						return new Match(Source.VENGEANCE, hit, hitTick);
					}
				}
			}
		}
		if (!recoilWorn)
		{
			return null;
		}
		for (int hitTick = tick - WINDOW_TICKS; hitTick <= tick; hitTick++)
		{
			final List<Integer> hits = hits(hitsOn, hitTick);
			for (int i = 0; i < hits.size(); i++)
			{
				final Set<Integer> answered = recoilAnswered.computeIfAbsent(hitTick, t -> new HashSet<>());
				if (!answered.contains(i) && recoilOf(hits.get(i)) == amount)
				{
					answered.add(i);
					return new Match(Source.RECOIL, hits.get(i), hitTick);
				}
			}
		}
		return null;
	}

	private static List<Integer> hits(IntFunction<List<Integer>> hitsOn, int tick)
	{
		final List<Integer> hits = hitsOn.apply(tick);
		return hits == null ? Collections.emptyList() : hits;
	}

	void prune(int tick)
	{
		recoilAnswered.keySet().removeIf(t -> tick - t > KEEP_TICKS);
		vengeanceFired.removeIf(t -> tick - t > KEEP_TICKS);
		vengeanceAnswered.removeIf(t -> tick - t > KEEP_TICKS + WINDOW_TICKS);
	}

	void clear()
	{
		recoilAnswered.clear();
		vengeanceFired.clear();
		vengeanceAnswered.clear();
	}
}

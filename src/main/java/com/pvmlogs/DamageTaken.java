package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Prayer;

/**
 * Splats on the local player (T-018): what each one is, what it adds to the fight
 * and the trip, and the hit damage tick by tick.
 *
 * <p>Before this, every splat on the player during a fight was damage taken,
 * whatever its type: heals, poison, prayer drain and the rest. Now only a hit
 * ({@link HitsplatKind#HIT_TAKEN}) is damage taken; damage over time on the player
 * is kept beside it, and heals and the splats that leave hitpoints alone are
 * logged only. Kinds are {@link HitsplatKind#onPlayer}.
 *
 * <p>Nothing here needs the client, so it is all under test. The plugin reads the
 * prayer, the hitpoints and what the target was doing, and hands them in.
 */
final class DamageTaken
{
	/**
	 * How many ticks of hits are kept behind the newest. Recoil and vengeance land a
	 * tick or so after the hit they answer; this is several times that.
	 */
	static final int KEEP_TICKS = 8;

	/**
	 * The overhead prayers that protect from a style, with what the TAKEN line calls
	 * them: the standard book's three, and the Ruinous Powers' Dampen prayers, which
	 * block all of an NPC's damage of their style the same way (wiki, 2026-10-27).
	 */
	static final Prayer[] PROTECTION = {
		Prayer.PROTECT_FROM_MELEE, Prayer.PROTECT_FROM_MISSILES, Prayer.PROTECT_FROM_MAGIC,
		Prayer.RP_DAMPEN_MELEE, Prayer.RP_DAMPEN_RANGED, Prayer.RP_DAMPEN_MAGIC,
	};

	// Every hit splat on the player, by the tick it landed, 0s included. Pruned as hits arrive.
	private final Map<Integer, List<Integer>> hitsByTick = new HashMap<>();
	private int newestTick = Integer.MIN_VALUE;

	/**
	 * One splat on the local player. The per-tick record takes every hit, in a fight
	 * or not: a boss's first swing lands before the fight it opens, and the recoil it
	 * sets off still needs matching. Only when {@code fight} is open does the splat
	 * count: a hit or damage over time on it, and on {@code trip} too when the fight
	 * is scored, like the rest of the trip's figures.
	 *
	 * @param prayerUp whether a protection prayer was up (see {@link #PROTECTION}),
	 *                 from the server's copy of the varbits
	 * @param fight    the open fight, or null when there is none
	 * @return the splat's kind
	 */
	HitsplatKind onSplat(int type, int amount, int tick, boolean prayerUp, Fight fight, SessionTotals trip, long now)
	{
		final HitsplatKind kind = HitsplatKind.onPlayer(type);
		if (kind == HitsplatKind.HIT_TAKEN)
		{
			recordHit(tick, amount);
		}
		if (fight == null || fight.isEnded())
		{
			return kind;
		}
		switch (kind)
		{
			case HIT_TAKEN:
				fight.recordHitTaken(amount, !prayerUp, now);
				if (fight.isScored() && trip != null)
				{
					trip.recordHitTaken(amount, !prayerUp);
				}
				break;
			case DAMAGE_OVER_TIME:
				fight.recordDotTaken(amount, type, now);
				if (fight.isScored() && trip != null)
				{
					trip.recordDotTaken(amount, type);
				}
				break;
			default:
				// A heal, a splat that leaves hitpoints alone, or a type not known here: logged, never damage, and no
				// activity - so a heal or a sanity tick does not keep a fight open.
				break;
		}
		return kind;
	}

	private void recordHit(int tick, int amount)
	{
		hitsByTick.computeIfAbsent(tick, t -> new ArrayList<>(2)).add(Math.max(0, amount));
		if (tick > newestTick)
		{
			newestTick = tick;
			hitsByTick.keySet().removeIf(t -> newestTick - t > KEEP_TICKS);
		}
	}

	/**
	 * The hit damage on the local player on {@code tick}: the sum of its hit splats,
	 * fight or no fight, prayer or not. Damage over time, heals and splats that
	 * leave hitpoints alone are not in it, and neither recoil nor vengeance answers
	 * them. Exact for any tick within {@link #KEEP_TICKS} of the newest hit; older
	 * ticks read 0 once pruned. Read during a tick it may still grow, since splats
	 * arrive one event at a time.
	 */
	int damageTakenOn(int tick)
	{
		final List<Integer> hits = hitsByTick.get(tick);
		int total = 0;
		if (hits != null)
		{
			for (int hit : hits)
			{
				total += hit;
			}
		}
		return total;
	}

	/**
	 * The hit splats on the local player on {@code tick}, one per splat in the order
	 * they arrived, 0s included. Recoil and vengeance answer each hit, not the tick's
	 * sum, so two hits on one tick are kept apart here. Same retention as
	 * {@link #damageTakenOn}.
	 */
	List<Integer> hitsTakenOn(int tick)
	{
		final List<Integer> hits = hitsByTick.get(tick);
		return hits == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(hits));
	}

	void clear()
	{
		hitsByTick.clear();
		newestTick = Integer.MIN_VALUE;
	}

	/** What the TAKEN line calls a protection prayer. */
	static String protectionName(Prayer prayer)
	{
		switch (prayer)
		{
			case PROTECT_FROM_MELEE:
				return "melee";
			case PROTECT_FROM_MISSILES:
				return "missiles";
			case PROTECT_FROM_MAGIC:
				return "magic";
			case RP_DAMPEN_MELEE:
				return "dampen melee";
			case RP_DAMPEN_RANGED:
				return "dampen ranged";
			case RP_DAMPEN_MAGIC:
				return "dampen magic";
			default:
				return prayer.name().toLowerCase();
		}
	}

	/**
	 * The debug line for one splat on the player: {@code TAKEN <amount> type <t> <kind>
	 * (<type name>), protect <prayer|none>, hp <n>, from <npcs>; target <what it did>}.
	 * The game names no source for a splat, so "from" lists the npcs that had the
	 * player as their target when it landed; the target part is the fight target's
	 * animations and projectiles of the last few ticks. Raw facts, for a later
	 * per-boss task to tell attack styles apart (T-018 item 3).
	 */
	static String line(int amount, int type, HitsplatKind kind, String protection, int hp, String from, String target,
		boolean inFight)
	{
		return String.format("TAKEN %d type %d %s (%s), protect %s, hp %d, from %s; target %s%s",
			amount, type, kind.takenName(), HitsplatKind.typeName(type), protection == null ? "none" : protection, hp,
			from == null || from.isEmpty() ? "unknown" : from,
			target == null || target.isEmpty() ? "nothing seen" : target,
			inFight ? "" : " (no fight)");
	}

	/** The fight's damage taken, for the end-of-fight log beside RECONCILE. */
	static String summary(Fight fight)
	{
		return String.format("TAKEN TOTAL %s (npc %d): hits %d for %d (biggest %d, without protection %d), dot %d",
			fight.getTargetName(), fight.getTargetId(), fight.getHitsTaken(), fight.getDamageTaken(),
			fight.getBiggestHitTaken(), fight.getDamageTakenUnprotected(), fight.getDotTaken());
	}
}

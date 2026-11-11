package com.pvmlogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * T-204: the player's own dwarf multicannon, whose hits are its own source, as a thrall's are: real damage on the
 * target, never the weapon's.
 *
 * <p>What is read, and from where:
 * <ul>
 * <li><b>Where the cannon stands:</b> {@link VarPlayerID#OWNEDMCANNON} (varp 4), a packed coordinate (as
 * {@code WorldPoint.fromCoord} reads it) of the cannon's south-west tile; it fills 3x3 tiles from there, so it shoots
 * from the middle one. As RuneLite's own cannon plugin reads it (1.13.6).</li>
 * <li><b>Whether it's set up:</b> {@link VarPlayerID#DROPCANNON} (varp 2) at 4. The cannonballs left are varp 3 (named
 * {@code ROCKTHROWER} in the gameval list); not needed here.</li>
 * <li><b>Its shots:</b> a projectile with no source actor and one of the four cannonball spotanims, set off from the
 * cannon's 3x3 tiles. Eric's 2026-11-10 log (build {@code 1dff745}): 515 of 2018 at smoke devils and 49 of 2019 at
 * drakes, each "from my tile", since he stood beside it.</li>
 * </ul>
 * <b>Another player's cannon</b> is told apart by where its ball sets off: never from this player's cannon tiles (two
 * cannons can't share a tile). Its balls are nobody's attack of this player's, and its splats are others' anyway.
 *
 * <p><b>Timing</b> (the same log): a ball is seen on the tick its splat lands or the tick before (drakes: 2019 seen on
 * 7613, 7621 and 7629 with its splat on that tick; on 7629 with a 30 on 7630). Its experience, 2 Ranged a point of
 * damage at the monster's bonus and no Hitpoints (OSRS Wiki, Dwarf multicannon; drakes x1.025: 18 paid +37, 15 paid +31;
 * smoke devils x1.000: 17 paid +34), comes on the tick before its splat. So the ledger takes it out of a tick's
 * experience before decoding the player's own attack ({@link XpLedger#close}), and the amount it read is what a
 * splat is matched against here.
 */
final class CannonTracker
{
	/** The four cannonball projectiles: steel and granite, and their League looks (RuneLite 1.13.6). */
	static final int[] CANNONBALLS = {SpotanimID.CANNONBALL_TRAVEL, SpotanimID.CANNONBALL_TRAVEL_GRANITE,
		SpotanimID.CANNONBALL_TRAVEL_LEAGUE, SpotanimID.CANNONBALL_TRAVEL_GRANITE_LEAGUE};
	/** {@link VarPlayerID#DROPCANNON}'s value while the cannon is set up (RuneLite's cannon plugin). */
	static final int SET_UP = 4;
	/** Granite cannonballs' max hit; steel's is 30 (OSRS Wiki). */
	static final int MAX_HIT = 35;
	/** A ball's splat lands on the tick it's seen or the next; one more for the client's lateness. */
	static final int LANDING_TICKS = 2;
	/** Experience a point of damage, before the monster's bonus (OSRS Wiki). */
	static final double XP_PER_DAMAGE = 2.0;

	private boolean setUp;
	// The cannon's south-west tile, world coordinates; Integer.MIN_VALUE when unknown.
	private int x = Integer.MIN_VALUE;
	private int y = Integer.MIN_VALUE;
	private int plane;
	// Balls set off from the player's cannon still to land, by the npc they're aimed at.
	private final Map<Integer, List<Ball>> balls = new HashMap<>();
	// The damage the experience said the cannon dealt, still to be matched to a splat.
	private final List<int[]> paid = new ArrayList<>();
	// Damage on npcs no fight of the player's was open on, by npc index, for the fight that opens on one later.
	private final Map<Integer, Integer> banked = new HashMap<>();

	private static final class Ball
	{
		final int seenTick;
		final int lastTick;

		Ball(int seenTick, int landsTick)
		{
			this.seenTick = seenTick;
			this.lastTick = Math.max(landsTick, seenTick + 1) + 1;
		}
	}

	static boolean isCannonball(int projectileId)
	{
		for (int id : CANNONBALLS)
		{
			if (id == projectileId)
			{
				return true;
			}
		}
		return false;
	}

	/** A varp changed: the cannon's state or where it stands. */
	void onVarp(int varpId, int value)
	{
		if (varpId == VarPlayerID.DROPCANNON)
		{
			setUp = value == SET_UP;
			if (!setUp)
			{
				balls.clear();
				paid.clear();
			}
		}
		else if (varpId == VarPlayerID.OWNEDMCANNON)
		{
			final WorldPoint at = WorldPoint.fromCoord(value);
			x = at.getX();
			y = at.getY();
			plane = at.getPlane();
		}
	}

	boolean isSetUp()
	{
		return setUp && x != Integer.MIN_VALUE;
	}

	/**
	 * Whether a ball set off at world tile ({@code fromX}, {@code fromY}) left the player's cannon: one of its 3x3 tiles
	 * on its plane. Another player's cannon stands elsewhere.
	 */
	boolean fromMyCannon(int fromX, int fromY, int fromPlane)
	{
		return isSetUp() && fromPlane == plane && fromX >= x && fromX <= x + 2 && fromY >= y && fromY <= y + 2;
	}

	/** A ball from the player's cannon, seen on {@code tick}, aimed at this npc, landing by {@code landsTick}. */
	void onBall(int npcIndex, int tick, int landsTick)
	{
		balls.computeIfAbsent(npcIndex, k -> new ArrayList<>()).add(new Ball(tick, landsTick));
	}

	/** The ledger read this much cannon damage from the experience of {@code tick}. */
	void onPaid(int damage, int tick)
	{
		if (damage > 0)
		{
			paid.add(new int[]{damage, tick});
		}
	}

	/** Whether a ball is in the air at this npc on this tick. */
	boolean ballOn(int npcIndex, int tick)
	{
		final List<Ball> on = balls.get(npcIndex);
		if (on == null)
		{
			return false;
		}
		for (Ball b : on)
		{
			if (tick >= b.seenTick && tick <= b.lastTick)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a splat of the player's on this npc is the cannon's, and if so takes its ball (and the experience that paid
	 * it). With a ball in the air at the npc:
	 * <ul>
	 * <li>one the experience paid, within a point of rounding: the cannon's;</li>
	 * <li>a 0 pays nothing, so it's the cannon's unless the player's own attack lands on it this tick
	 * ({@code weaponLands});</li>
	 * <li>any other, with no experience read for the cannon (the ledger untrusted, or at 200m), the cannon's when it's
	 * within its max and the player's own attack doesn't land on it this tick.</li>
	 * </ul>
	 * A weapon hit and a ball on one monster on one tick are told apart by the cannon's paid amount: the splat that
	 * matches it is the cannon's, the other the weapon's.
	 */
	boolean claim(int npcIndex, int amount, int tick, boolean weaponLands)
	{
		if (!ballOn(npcIndex, tick))
		{
			return false;
		}
		boolean take = false;
		if (amount > 0)
		{
			final int[] match = paidMatching(amount, tick);
			if (match != null)
			{
				paid.remove(match);
				take = true;
			}
			else
			{
				take = !anyPaidRecently(tick) && amount <= MAX_HIT && !weaponLands;
			}
		}
		else
		{
			take = !weaponLands;
		}
		if (take)
		{
			takeBall(npcIndex, tick);
		}
		return take;
	}

	private int[] paidMatching(int amount, int tick)
	{
		for (int[] p : paid)
		{
			if (Math.abs(p[0] - amount) <= 1 && tick - p[1] >= 0 && tick - p[1] <= LANDING_TICKS + 1)
			{
				return p;
			}
		}
		return null;
	}

	private boolean anyPaidRecently(int tick)
	{
		for (int[] p : paid)
		{
			if (tick - p[1] >= 0 && tick - p[1] <= LANDING_TICKS + 1)
			{
				return true;
			}
		}
		return false;
	}

	private void takeBall(int npcIndex, int tick)
	{
		final List<Ball> on = balls.get(npcIndex);
		for (Iterator<Ball> it = on.iterator(); it.hasNext(); )
		{
			final Ball b = it.next();
			if (tick >= b.seenTick && tick <= b.lastTick)
			{
				it.remove();
				break;
			}
		}
		if (on.isEmpty())
		{
			balls.remove(npcIndex);
		}
	}

	/** Cannon damage on an npc no fight of the player's was open on, kept for a fight that opens on it. */
	void bank(int npcIndex, int amount)
	{
		if (amount > 0)
		{
			banked.merge(npcIndex, amount, Integer::sum);
		}
	}

	/** What the cannon dealt this npc before a fight opened on it, taken (0 when nothing). */
	int takeBanked(int npcIndex)
	{
		final Integer had = banked.remove(npcIndex);
		return had == null ? 0 : had;
	}

	/** The npc is gone: what was kept for it goes too. */
	void forget(int npcIndex)
	{
		banked.remove(npcIndex);
		balls.remove(npcIndex);
	}

	/** Balls that landed with no splat seen, and experience matched to none, are dropped once they're past landing. */
	void expire(int tick)
	{
		for (Iterator<List<Ball>> it = balls.values().iterator(); it.hasNext(); )
		{
			final List<Ball> on = it.next();
			on.removeIf(b -> tick > b.lastTick + 1);
			if (on.isEmpty())
			{
				it.remove();
			}
		}
		paid.removeIf(p -> tick - p[1] > LANDING_TICKS + 2);
	}

	void clear()
	{
		balls.clear();
		paid.clear();
		banked.clear();
	}
}

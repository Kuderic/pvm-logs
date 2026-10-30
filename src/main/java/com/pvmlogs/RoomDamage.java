package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
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
	private RoomDamage()
	{
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

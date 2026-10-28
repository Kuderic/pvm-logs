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
 */
final class DrainRecord
{
	/** 1 from T-049; 0 is never saved. */
	int version = 1;
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

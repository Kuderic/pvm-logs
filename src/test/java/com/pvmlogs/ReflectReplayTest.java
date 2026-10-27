package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-017 Part B end to end through the replay, as debug-log lines in the shape a
 * build with T-018 writes them: TAKEN lines for the hits on the player, VENGEANCE
 * lines for the rebound. A reflected splat books no blow and counts nowhere as
 * the player's; the blow beside it still books.
 *
 * <p>Whether the reflected splat lands on its hit's tick or the next, and whether
 * it comes before or after the hit's TAKEN line in a tick, is still a guess: both
 * orders are tested, since the plugin re-judges at the end of the tick.
 */
public class ReflectReplayTest
{
	private static final String START = "[100] fight START Vardorvis (npc 12223, index 5, maxHp 700)";

	private static String splat(int tick, int amount)
	{
		return String.format("[%d] hitsplat %d type 16 on Vardorvis (npc 12223, index 5), landedAttack=false", tick, amount);
	}

	private static String taken(int tick, int amount)
	{
		return String.format("[%d] TAKEN %d type 16 hit (damage me), protect none, hp 80, from npc 12223 #5 (target); "
			+ "target nothing seen", tick, amount);
	}

	private static ThrallReplay replay(boolean recoil, String... lines)
	{
		final List<String> all = new ArrayList<>(Collections.singletonList(START));
		all.addAll(Arrays.asList(lines));
		final ThrallReplay r = new ThrallReplay(1.55, 0.45, 47, 4);
		return (recoil ? r.wearingRecoil() : r).run(all);
	}

	@Test
	public void vengeancesSplatBooksNoBlowAndTheBlowAfterItBooks()
	{
		// The 23:08 pattern, with the hit Eric took written in: a 14 taken, vengeance's 10 on the boss, the blow's 20 next.
		final ThrallReplay r = replay(false, splat(101, 18), "[103] VENGEANCE varbit -> 1", taken(104, 14),
			"[104] VENGEANCE varbit -> 0", splat(104, 10), splat(105, 20));
		assertEquals(Collections.singletonList("104: 10 vengeance"), r.reflected);
		assertEquals(Arrays.asList(100, 104), r.blows.get(1));
		assertEquals(Collections.emptyList(), r.extras);
	}

	@Test
	public void evidenceLaterInTheTickStillCounts()
	{
		// The splat ahead of the hit and the varbit: taken back at the end of the tick, its blow withdrawn.
		final ThrallReplay r = replay(false, splat(101, 18), "[103] VENGEANCE varbit -> 1", splat(104, 10),
			taken(104, 14), "[104] VENGEANCE said Taste vengeance!", splat(105, 20));
		assertEquals(Collections.singletonList("104: 10 vengeance"), r.reflected);
		assertEquals(Arrays.asList(100, 104), r.blows.get(1));
	}

	@Test
	public void recoilAnswersEachHitAndTheBlowBesideItStaysThePlayers()
	{
		// Two hits taken on 102: recoils of 3 and 1. The blow's 3 on 105 is the player's: no hit left to answer.
		final ThrallReplay r = replay(true, splat(101, 18), taken(102, 25), taken(102, 7), splat(102, 3), splat(103, 1),
			splat(105, 3));
		assertEquals(Arrays.asList("102: 3 recoil", "103: 1 recoil"), r.reflected);
		assertEquals(Arrays.asList(100, 104), r.blows.get(1));
	}

	@Test
	public void withoutTheRingASplatOfTheRightSizeIsThePlayers()
	{
		final ThrallReplay r = replay(false, splat(101, 18), taken(102, 25), splat(105, 3));
		assertEquals(Collections.emptyList(), r.reflected);
		assertEquals(Arrays.asList(100, 104), r.blows.get(1));
	}

	@Test
	public void theTwoThreeOhEightLogHasNoHitsTakenToMatch() throws IOException
	{
		// Before T-018 the log had no TAKEN lines, so nothing in it can be matched: the vengeance 10 on 2804 is still
		// booked as the blow, and the blow's 20 on 2805 is the one extra. A log from a build with T-018 settles it.
		final ThrallReplay r = new ThrallReplay(1.55, 0.45, 47, 4).wearingRecoil()
			.run("/com/pvmlogs/replay/vardorvis-claws-recoil-veng-20261026-2308.log");
		assertEquals(Collections.emptyList(), r.reflected);
		assertEquals(Collections.singletonList("2805: 20"), r.extras);
	}
}

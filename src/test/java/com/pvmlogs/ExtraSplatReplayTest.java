package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-017 Part A, end to end through the thrall model: a splat within what the
 * attack deals stays the player's, one beyond it goes to the thrall, and with
 * no thrall out it stays the player's and is reported as an extra.
 *
 * <p>Written as debug-log lines, replayed the way the plugin judges them. The
 * thrall is a greater ghost just claimed, none of its projectiles seen yet, so
 * nothing but the player's side tells the splats apart. The player misses
 * often (accuracy 0.4), so a 0 is as likely theirs as the thrall's on its
 * amount alone. The amounts over the thrall's max of 3 are certain to be the
 * player's and are never judged.
 */
public class ExtraSplatReplayTest
{
	private static final String CLAIM = "[100] THRALL claimed npc 10880 (index 9), max hit 3: spawned beside me";
	private static final String START = "[100] fight START Boss (npc 12223, index 5, maxHp -1)";
	private static final String SPECIAL = "[%d]   scored as a SPECIAL: max 61, chances 0.80, avg 30.00";

	private static String splat(int tick, int amount)
	{
		return String.format("[%d] hitsplat %d type 16 on Boss (npc 12223, index 5), landedAttack=false", tick, amount);
	}

	private static ThrallReplay replay(AttackSplats ordinary, AttackSplats special, String... lines)
	{
		final List<String> all = new ArrayList<>(Arrays.asList(CLAIM, START));
		all.addAll(Arrays.asList(lines));
		return new ThrallReplay(1.0, 0.4, 40, 4, ordinary, special).run(all);
	}

	/** The judged splats on a tick, in order, as "player" or "THRALL". */
	private static List<String> judgedOn(ThrallReplay r, int tick)
	{
		final List<String> out = new ArrayList<>();
		for (ThrallReplay.Judged j : r.judgedBetween(tick, tick))
		{
			out.add(j.thralls ? "THRALL" : "player");
		}
		return out;
	}

	@Test
	public void aScytheOnALargeTargetKeepsThreeAndTheFourthIsTheThralls()
	{
		final AttackSplats scythe = AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 3, null);
		final ThrallReplay r = replay(scythe, scythe, splat(101, 20), splat(101, 0), splat(101, 0), splat(101, 0));
		assertEquals(r.judged.toString(), Arrays.asList("player", "player", "THRALL"), judgedOn(r, 101));
		assertEquals(ThrallTracker.PlayerSplat.EXTRA_SPLAT, r.judged.get(2).player);
	}

	@Test
	public void aScytheOnAOneTileTargetKeepsOne()
	{
		final AttackSplats scythe = AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 1, null);
		final ThrallReplay r = replay(scythe, scythe, splat(101, 20), splat(101, 0));
		assertEquals(r.judged.toString(), Collections.singletonList("THRALL"), judgedOn(r, 101));
	}

	@Test
	public void aClawSpecialKeepsFourAndTheFifthIsTheThralls()
	{
		final ThrallReplay r = replay(AttackSplats.ONE, AttackSplats.of(ItemID.DRAGON_CLAWS, true, 3, null),
			splat(101, 12), splat(101, 0), splat(101, 0), splat(101, 0), splat(101, 0), String.format(SPECIAL, 101));
		assertEquals(r.judged.toString(), Arrays.asList("player", "player", "player", "THRALL"), judgedOn(r, 101));
	}

	@Test
	public void burningClawsThirdSplatATickLateStaysThePlayers()
	{
		final ThrallReplay r = replay(AttackSplats.ONE, AttackSplats.of(ItemID.BONE_CLAWS, true, 3, null),
			splat(101, 10), splat(101, 0), String.format(SPECIAL, 101), splat(102, 0), splat(102, 0));
		assertEquals(r.judged.toString(), Collections.singletonList("player"), judgedOn(r, 101));
		// The third is due on 102; a second splat there is beyond the special.
		assertEquals(r.judged.toString(), Arrays.asList("player", "THRALL"), judgedOn(r, 102));
		assertEquals(ThrallTracker.PlayerSplat.LATE_SPLAT_DUE, r.judgedBetween(102, 102).get(0).player);
	}

	@Test
	public void aDualMacuahuitlHitsOnceATick()
	{
		final AttackSplats maca = AttackSplats.of(ItemID.DUAL_MACUAHUITL, false, 3, null);
		// A 0 beside its first hit is not its second, which comes a tick later.
		final ThrallReplay r = replay(maca, maca, splat(101, 8), splat(101, 0), splat(102, 0));
		assertEquals(r.judged.toString(), Collections.singletonList("THRALL"), judgedOn(r, 101));
		assertEquals(r.judged.toString(), Collections.singletonList("player"), judgedOn(r, 102));
		assertEquals(ThrallTracker.PlayerSplat.LATE_SPLAT_DUE, r.judgedBetween(102, 102).get(0).player);
	}

	@Test
	public void withNoThrallOutAnExtraStaysThePlayersAndIsReported()
	{
		final List<String> lines = Arrays.asList(START, splat(101, 20), splat(101, 3), splat(105, 15));
		final ThrallReplay r = new ThrallReplay(1.0, 0.4, 40, 4, AttackSplats.ONE, AttackSplats.ONE).run(lines);
		assertEquals(Collections.singletonList("101: 3"), r.extras);
		assertEquals(Arrays.asList(100, 104), r.blows.get(1));
	}

	@Test
	public void theOneExtraInTheOlderLogsIsTheGhostClaimedAtTheEndOfItsTick() throws IOException
	{
		// 16:08, 194: the ghost's 1 and the player's 4 landed before the thrall was claimed at the end of the tick, so
		// neither was weighed. The 4 was counted beyond the blow's one splat and reported; the totals are as before.
		for (double[] asRun : new double[][]{{1.70, 0.98}, {1.55, 0.55}})
		{
			final ThrallReplay r = new ThrallReplay(asRun[0], asRun[1], 49, 4)
				.run("/com/pvmlogs/replay/vardorvis-zombie-then-ghost-20261026-1608.log");
			assertEquals(Collections.singletonList("194: 4"), r.extras);
		}
	}
}

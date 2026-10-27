package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Eric's 19:34 log (2026-10-26), from master 70a6874: a short Vardorvis fight
 * with a greater zombified thrall, the first log with the zombie's attack
 * animations in it (the trace is kept whatever the file-logging setting since
 * T-003). It also holds the T-012 eating checks: a shark two ticks before the
 * attack was due, a shark with the weapon ready, and a brew.
 *
 * <p>What it settles: the zombie's splat lands exactly one tick after its
 * attack animation, 7 of 7 - the lag no earlier log could show.
 *
 * <p>Tick 439 (T-017): the tentacle's miss and the zombie's 0 landed together.
 * The plugin gave both to the player, the second as SAME_TICK_BURST although a
 * tentacle hits once. Counted against the one splat a tentacle deals, the
 * second is an extra, and the zombie's.
 */
public class ZombieThrallReplayTest
{
	private static final String LOG = "/com/pvmlogs/replay/vardorvis-zombie-eats-20261026-1934.log";
	private static final int ZOMBIE_ATTACK = 13681;
	private static final int SPEED = 4;
	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)\\] THRALL animation (-?\\d+): ");

	private static ThrallReplay replay() throws IOException
	{
		// As the plugin ran: x1.55 from T-001, an expected accuracy near what it logged, the tentacle's max with Piety.
		return new ThrallReplay(1.55, 0.40, 41, SPEED).run(LOG);
	}

	// The zombie's splats and the player's misses on this stretch, from the log: the zombie's on 415 + 4k, each a tick after
	// its animation; the player's tentacle misses a tick after each blow thrown (408, 412, 419, 423, 430, 434, 438). On 439
	// both are 0s, and which of the two is whose cannot be told: one each.
	private static final int[][] ZOMBIE = {{415, 2}, {419, 2}, {423, 1}, {427, 2}, {431, 3}, {435, 1}, {439, 0}};
	private static final int[][] PLAYER = {{413, 0}, {424, 0}, {431, 0}, {435, 0}, {439, 0}};

	private static boolean judged(ThrallReplay r, int tick, int amount, boolean thralls)
	{
		for (ThrallReplay.Judged j : r.judged)
		{
			if (j.tick == tick && j.amount == amount && j.thralls == thralls)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void everyZombieSplatAndEveryPlayerMissGoesToItsOwner() throws IOException
	{
		final ThrallReplay r = replay();
		final List<String> wrong = new ArrayList<>();
		for (int[] z : ZOMBIE)
		{
			if (!judged(r, z[0], z[1], true))
			{
				wrong.add("the zombie's " + z[1] + " on " + z[0]);
			}
		}
		for (int[] p : PLAYER)
		{
			if (!judged(r, p[0], p[1], false))
			{
				wrong.add("the player's " + p[1] + " on " + p[0]);
			}
		}
		assertTrue(wrong + " in " + r.judged, wrong.isEmpty());
	}

	@Test
	public void onTick439TheSecondZeroIsBeyondTheTentaclesOneSplatAndGoesToTheZombie() throws IOException
	{
		final List<ThrallReplay.Judged> on439 = replay().judgedBetween(439, 439);
		assertEquals(on439.toString(), 2, on439.size());
		// The first is the blow's, on cadence.
		assertFalse(on439.toString(), on439.get(0).thralls);
		assertEquals(ThrallTracker.PlayerSplat.ON_CADENCE, on439.get(0).player);
		// The second is an extra: the tentacle has put its one splat down.
		assertTrue(on439.toString(), on439.get(1).thralls);
		assertEquals(ThrallTracker.PlayerSplat.EXTRA_SPLAT, on439.get(1).player);
	}

	@Test
	public void theFirstZombieSplatIsItsFromTheAnimationAloneBeforeAnyPhase() throws IOException
	{
		// 415 is its first hit: no cycle yet, only the animation on 414 and the tentacle in cooldown.
		for (ThrallReplay.Judged j : replay().judged)
		{
			if (j.tick == 415)
			{
				assertTrue(j.toString(), j.thralls && j.probability > 0.99);
				return;
			}
		}
		throw new AssertionError("415 not judged");
	}

	@Test
	public void noBlowIsBookedInsideTheWeaponsCooldown() throws IOException
	{
		for (Map.Entry<Integer, List<Integer>> f : replay().blows.entrySet())
		{
			final List<Integer> ticks = f.getValue();
			for (int i = 1; i < ticks.size(); i++)
			{
				assertTrue("blows on " + ticks.get(i - 1) + " and " + ticks.get(i), ticks.get(i) - ticks.get(i - 1) >= SPEED);
			}
		}
	}

	@Test
	public void theZombieHitsOneTickAfterItsAttackAnimation() throws IOException
	{
		final List<Integer> animations = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			ZombieThrallReplayTest.class.getResourceAsStream(LOG), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				final Matcher m = LINE.matcher(line);
				if (m.find() && Integer.parseInt(m.group(2)) == ZOMBIE_ATTACK)
				{
					animations.add(Integer.parseInt(m.group(1)));
				}
			}
		}
		// Seven attacks before the fight ended: 414 to 438. The last one's splat is a zero on 439 (see the class note).
		assertEquals(7, animations.size());
		for (int i = 0; i < ZOMBIE.length; i++)
		{
			assertEquals("the zombie's hit on " + ZOMBIE[i][0], animations.get(i) + 1, ZOMBIE[i][0]);
		}
	}
}

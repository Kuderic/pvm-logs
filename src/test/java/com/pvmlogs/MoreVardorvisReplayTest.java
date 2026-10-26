package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Eric's second session of logs (2026-10-26), written by the master build
 * before T-003:
 *
 * <ul>
 * <li>15:26, streamed: four kills with a greater ghostly thrall, file logging
 * on, so every thrall spawn, animation and projectile is in it.</li>
 * <li>16:08, saved from the panel with file logging off: one kill with a
 * greater zombified thrall (melee, fires nothing) from 69 to 171, then a ghost
 * from 194. With file logging off the thrall trace was not kept, so the zombie's
 * animations are missing: the replay sees only its splats, which makes this the
 * test of the cycle alone.</li>
 * </ul>
 *
 * <p>Each runs twice, as the plugin was (x1.70, 98% expected accuracy) and as it
 * should be (x1.55, 55%).
 */
public class MoreVardorvisReplayTest
{
	private static final String GHOST_LOG = "/com/pvmlogs/replay/vardorvis-4-kills-ghost-20261026-1526.log";
	private static final String ZOMBIE_LOG = "/com/pvmlogs/replay/vardorvis-zombie-then-ghost-20261026-1608.log";
	private static final int SPEED = 4;

	private static ThrallReplay asLogged(String log) throws IOException
	{
		return new ThrallReplay(1.70, 0.98, 49, SPEED).run(log);
	}

	private static ThrallReplay corrected(String log) throws IOException
	{
		return new ThrallReplay(1.55, 0.55, 49, SPEED).run(log);
	}

	private static List<String> projectileMismatches(ThrallReplay r, int fromTick)
	{
		final List<String> wrong = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> f : r.thrallProjectiles.entrySet())
		{
			for (int tick : f.getValue())
			{
				if (tick < fromTick)
				{
					continue;
				}
				int owned = 0;
				for (ThrallReplay.Judged j : r.judgedBetween(tick, tick))
				{
					owned += j.thralls ? 1 : 0;
				}
				if (owned != 1)
				{
					wrong.add("projectile on " + tick + " owns " + owned + ": " + r.judgedBetween(tick, tick));
				}
			}
		}
		return wrong;
	}

	private static List<String> blowsInsideCooldown(ThrallReplay r)
	{
		final List<String> wrong = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> f : r.blows.entrySet())
		{
			final List<Integer> ticks = f.getValue();
			for (int i = 1; i < ticks.size(); i++)
			{
				if (ticks.get(i) - ticks.get(i - 1) < SPEED)
				{
					wrong.add("kill " + f.getKey() + ": blows on " + ticks.get(i - 1) + " and " + ticks.get(i));
				}
			}
		}
		return wrong;
	}

	private static void assertGhostKills(ThrallReplay r)
	{
		final List<String> wrong = projectileMismatches(r, Integer.MIN_VALUE);
		for (ThrallReplay.Judged j : r.judged)
		{
			final List<Integer> projectiles = r.thrallProjectiles.get(j.fight);
			if (j.thralls && (projectiles == null || !projectiles.contains(j.tick)))
			{
				wrong.add("thrall's with no projectile: " + j);
			}
		}
		wrong.addAll(blowsInsideCooldown(r));
		assertTrue(wrong.size() + " wrong:\n" + String.join("\n", wrong), wrong.isEmpty());
		assertEquals(1.55, r.ledger.multiplierFor(12223, 1.0), 1e-9);
	}

	@Test
	public void fourGhostKillsAsTheLogWas() throws IOException
	{
		assertGhostKills(asLogged(GHOST_LOG));
	}

	@Test
	public void fourGhostKillsOnceTheStatsAreRight() throws IOException
	{
		assertGhostKills(corrected(GHOST_LOG));
	}

	// The zombie's splats, worked out from the log: its four-tick cycle on 74-94, a one-tick re-path, then 99-171. The
	// player's tentacle misses on those stretches are the other small splats: 86 and 113 to 170. Every tick here was
	// checked against the player's drops (an attack's drop lands on its tick and its splat the tick after) and against
	// both cadences.
	private static final int[][] ZOMBIE = {
		{74, 1}, {78, 1}, {82, 0}, {86, 3}, {90, 2}, {94, 3}, {99, 2}, {103, 2}, {107, 0}, {111, 2}, {115, 1},
		{119, 0}, {123, 2}, {127, 2}, {131, 2}, {135, 0}, {139, 2}, {143, 3}, {147, 2}, {151, 3}, {155, 2}, {159, 1},
		{163, 3}, {167, 1}, {171, 0}};
	private static final int[][] PLAYER_SMALL = {{86, 0}, {113, 0}, {132, 0}, {144, 0}, {148, 0}, {160, 0}, {170, 0}};

	private static void assertZombieStretch(ThrallReplay r)
	{
		final Set<String> thrall = new HashSet<>();
		for (int[] z : ZOMBIE)
		{
			thrall.add(z[0] + ":" + z[1]);
		}
		final Set<String> player = new HashSet<>();
		for (int[] p : PLAYER_SMALL)
		{
			player.add(p[0] + ":" + p[1]);
		}
		final List<String> wrong = new ArrayList<>();
		for (ThrallReplay.Judged j : r.judgedBetween(69, 171))
		{
			final String key = j.tick + ":" + j.amount;
			if (thrall.contains(key) && !j.thralls)
			{
				wrong.add("the zombie's, given the player: " + j);
			}
			if (player.contains(key) && j.thralls)
			{
				wrong.add("the player's, given the zombie: " + j);
			}
			if (!thrall.contains(key) && !player.contains(key))
			{
				wrong.add("not accounted for: " + j);
			}
		}
		// Every one of the zombie's splats was judged at all.
		int seen = 0;
		for (ThrallReplay.Judged j : r.judgedBetween(69, 171))
		{
			seen += thrall.contains(j.tick + ":" + j.amount) && j.thralls ? 1 : 0;
		}
		if (seen != ZOMBIE.length)
		{
			wrong.add("the zombie's splats found: " + seen + " of " + ZOMBIE.length);
		}
		wrong.addAll(blowsInsideCooldown(r));
		// The ghost after it, claimed on 194: every projectile from 198 on owns its splat. 194's own is the tracker's
		// claim-before-projectile case, which the replay cannot reach without the spawn line; ThrallTrackerTest has it.
		wrong.addAll(projectileMismatches(r, 195));
		assertTrue(wrong.size() + " wrong:\n" + String.join("\n", wrong), wrong.isEmpty());
	}

	@Test
	public void theZombieStretchByItsCycleAloneAsTheLogWas() throws IOException
	{
		assertZombieStretch(asLogged(ZOMBIE_LOG));
	}

	@Test
	public void theZombieStretchByItsCycleAloneOnceTheStatsAreRight() throws IOException
	{
		assertZombieStretch(corrected(ZOMBIE_LOG));
	}

	@Test
	public void theZombieKillIsCalibratedAndTrusted() throws IOException
	{
		final ThrallReplay r = asLogged(ZOMBIE_LOG);
		assertEquals(1.55, r.ledger.multiplierFor(12223, 1.70), 1e-9);
		final List<Integer> clean = r.cleanHits.get(1);
		assertTrue(clean.size() >= 5);
		final Integer from = r.trustedFrom.get(1);
		assertTrue("trusted from " + from, from != null && from <= clean.get(4) + XpLedger.WINDOW_TICKS);
	}

	@Test
	public void theZombieTruthIsConsistent()
	{
		// Its own cycle: four ticks apart, bar the one re-path.
		for (int i = 1; i < ZOMBIE.length; i++)
		{
			final int gap = ZOMBIE[i][0] - ZOMBIE[i - 1][0];
			assertTrue(Arrays.toString(ZOMBIE[i]), gap == 4 || (ZOMBIE[i][0] == 99 && gap == 5));
		}
	}
}

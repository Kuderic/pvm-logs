package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Eric's first real-fight logs (2026-10-26): eight Vardorvis kills with a
 * tentacle and burning claws, a greater ghostly thrall out in seven. Reviewed in
 * claude/status/vardorvis-log-review.md; this is T-003's replay of its bugs 3
 * and 4.
 *
 * <p>The plugin that wrote the log used a wrong multiplier (x1.70, the Awakened
 * entry; the drops say x1.55) and an expected accuracy of 98% (Vardorvis's
 * defence read as zero). Both are T-001's to fix, so the replay runs as the
 * plugin was and as it should be, and has to come out right either way.
 */
public class VardorvisReplayTest
{
	private static final String LOG = "/com/pvmlogs/replay/vardorvis-8-kills-20261026.log";
	private static final int VARDORVIS = 12223;
	private static final int SPEED = 4;

	// As the plugin was: the wrong multiplier and accuracy it really ran with.
	private static ThrallReplay asLogged() throws IOException
	{
		return new ThrallReplay(1.70, 0.98, 49, SPEED).run(LOG);
	}

	// As it should be once T-001 lands: the post-quest multiplier, and an accuracy near what Eric sees.
	private static ThrallReplay corrected() throws IOException
	{
		return new ThrallReplay(1.55, 0.55, 49, SPEED).run(LOG);
	}

	private static ThrallReplay.Judged at(ThrallReplay r, int tick)
	{
		for (ThrallReplay.Judged j : r.judged)
		{
			if (j.tick == tick)
			{
				return j;
			}
		}
		throw new AssertionError("no small splat judged on tick " + tick);
	}

	private static void assertTicks197To203(ThrallReplay r)
	{
		// Ground truth from the review: the player's tentacle misses on 197 and 201 (attacks on 196 and 200), the
		// thrall's projectiles first seen on 199 and 203 with their splats.
		assertFalse("197 is the player's miss: " + at(r, 197), at(r, 197).thralls);
		assertTrue("199 is the thrall's 3: " + at(r, 199), at(r, 199).thralls);
		assertFalse("201 is the player's miss: " + at(r, 201), at(r, 201).thralls);
		assertTrue("203 is the thrall's 0: " + at(r, 203), at(r, 203).thralls);
	}

	@Test
	public void ticks197To203AttributeCorrectlyAsTheLogWas() throws IOException
	{
		assertTicks197To203(asLogged());
	}

	@Test
	public void ticks197To203AttributeCorrectlyOnceTheStatsAreRight() throws IOException
	{
		assertTicks197To203(corrected());
	}

	/** Every thrall projectile owns exactly one splat on its tick, and nothing else is the thrall's. */
	private static void assertThrallSplatsMatchItsProjectiles(ThrallReplay r)
	{
		final List<String> wrong = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> f : r.thrallProjectiles.entrySet())
		{
			for (int tick : f.getValue())
			{
				int owned = 0;
				for (ThrallReplay.Judged j : r.judgedBetween(tick, tick))
				{
					owned += j.thralls ? 1 : 0;
				}
				if (owned != 1)
				{
					wrong.add("projectile on " + tick + " owns " + owned + " splats: " + r.judgedBetween(tick, tick));
				}
			}
		}
		for (ThrallReplay.Judged j : r.judged)
		{
			final List<Integer> projectiles = r.thrallProjectiles.get(j.fight);
			if (j.thralls && (projectiles == null || !projectiles.contains(j.tick)))
			{
				wrong.add("thrall's with no projectile: " + j);
			}
		}
		assertTrue(wrong.size() + " wrong:\n" + String.join("\n", wrong), wrong.isEmpty());
	}

	@Test
	public void acrossAllEightKillsTheThrallOwnsItsProjectilesSplatsAndNothingElseAsTheLogWas() throws IOException
	{
		assertThrallSplatsMatchItsProjectiles(asLogged());
	}

	@Test
	public void acrossAllEightKillsTheThrallOwnsItsProjectilesSplatsAndNothingElseOnceTheStatsAreRight()
		throws IOException
	{
		assertThrallSplatsMatchItsProjectiles(corrected());
	}

	/** No two blows booked closer than the weapon's speed: one inside its cooldown is a thrall's splat taken for one. */
	private static void assertNoBlowInsideCooldown(ThrallReplay r)
	{
		final List<String> wrong = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> f : r.blows.entrySet())
		{
			final List<Integer> ticks = f.getValue();
			for (int i = 1; i < ticks.size(); i++)
			{
				if (ticks.get(i) - ticks.get(i - 1) < SPEED)
				{
					wrong.add("kill " + f.getKey() + ": blows thrown on " + ticks.get(i - 1) + " and " + ticks.get(i));
				}
			}
		}
		assertTrue(String.join("\n", wrong), wrong.isEmpty());
	}

	@Test
	public void noBlowIsBookedInsideTheWeaponsCooldownAsTheLogWas() throws IOException
	{
		assertNoBlowInsideCooldown(asLogged());
	}

	@Test
	public void noBlowIsBookedInsideTheWeaponsCooldownOnceTheStatsAreRight() throws IOException
	{
		assertNoBlowInsideCooldown(corrected());
	}

	@Test
	public void theWrongMultiplierIsCorrectedToWhatTheDropsSay() throws IOException
	{
		final ThrallReplay r = asLogged();
		assertEquals(1.55, r.ledger.multiplierFor(VARDORVIS, 1.70), 1e-9);
	}

	/** The ledger is trusted by the time its fifth clean hit has settled, in every kill with five. */
	private static void assertTrustedWithinFiveCleanHits(ThrallReplay r)
	{
		final List<String> wrong = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> f : r.cleanHits.entrySet())
		{
			final List<Integer> hits = f.getValue();
			if (hits.size() < 5)
			{
				continue;
			}
			final Integer from = r.trustedFrom.get(f.getKey());
			// A hit is checked once its attack's window has closed: the attack was thrown the tick before the splat.
			final int settledBy = hits.get(4) - 1 + XpLedger.WINDOW_TICKS + 1;
			if (from == null || from > settledBy)
			{
				wrong.add("kill " + f.getKey() + ": fifth clean hit on " + hits.get(4) + ", trusted from " + from);
			}
		}
		assertTrue(String.join("\n", wrong), wrong.isEmpty());
	}

	@Test
	public void startingFromTheWrongMultiplierTheLedgerIsTrustedWithinFiveCleanHits() throws IOException
	{
		assertTrustedWithinFiveCleanHits(asLogged());
	}

	@Test
	public void startingFromTheRightMultiplierTheLedgerIsTrustedWithinFiveCleanHits() throws IOException
	{
		assertTrustedWithinFiveCleanHits(corrected());
	}

	@Test
	public void theFirstKillIsTrustedBeforeTheThrallsFirstSplat() throws IOException
	{
		// The claw specials before the thrall is summoned on 194 are enough to calibrate and check; its first splat is 199.
		final ThrallReplay r = asLogged();
		final Integer from = r.trustedFrom.get(1);
		assertNotNull(from);
		assertTrue("trusted from " + from, from < 199);
	}
}

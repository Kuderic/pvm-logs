package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-115, from Eric's 06:38 log (build 824b2f2, read in place, nothing copied):
 * an Acidic araxyte (58 hp) took the axe's 24 [4220], the crossbow's guaranteed
 * 29 [4227] and a thrall's 3 the same tick, then the thrall's 2 at [4230] left
 * it at 0, dead by the game ({@code dead but still standing} at [4230]). The
 * axe swing thrown at [4230] landed a 0 at [4231] and was booked at an ordinary
 * swing's 23.28: the fight read A 53, E 75.4. Its cap was "unknown", since a
 * count at 0 read as one that had lost the target.
 */
public class DeadAddCapTest
{
	private static final int INDEX = 47969;
	private static final int ACIDIC = NpcID.ARAXXOR_MINION_VENOM;

	@Test
	public void aSwingLandingOnAnAddDeadBeforeItIsExpectedAtNothing()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, ACIDIC, 58, 4220);
		health.damage(INDEX, ACIDIC, 24, 4220);
		health.damage(INDEX, ACIDIC, 29, 4227);
		health.damage(INDEX, ACIDIC, 3, 4227);
		assertEquals(2, health.capRemaining(INDEX, 4229));
		health.damage(INDEX, ACIDIC, 2, 4230);
		assertEquals(0, health.capRemaining(INDEX, 4231));
		// Seen dead at [4230]; the swing's splat lands at [4231]: nothing was left.
		final int capNow = PvmPerformancePlugin.capLeft(health.capRemaining(INDEX, 4231), 4230, 4231);
		assertEquals(0, capNow);
		// Its figures, read at the swing's own tick with no cap known.
		final double[] at = LandingCap.recap(51, 0.91, 23.28, 262.5, -1, capNow);
		assertEquals(0.0, at[0], 1e-9);
		assertEquals(0.0, at[1], 1e-9);
		// So the fight reads E 52.1 against A 53 (the thrall's 5 apart): 24 (avg 23.13) and 29 (certain) landed, then 0.
		final Fight fight = new Fight("Acidic Araxyte", ACIDIC, INDEX, 58, 0, null, 0);
		fight.recordAttackMade(false);
		fight.recordExpected(51, 0.91, 23.13, 259.2);
		fight.recordDamageDealt(24, 1, true);
		fight.recordAttackMade(false);
		fight.recordExpected(29, 1.0, 29.0, 0);
		fight.recordDamageDealt(29, 2, true);
		fight.recordAttackMade(false);
		fight.recordExpected(51, 0.91, at[0], at[1]);
		fight.recordDamageDealt(0, 3, false);
		assertEquals(52.13, fight.getSumExpectedAverageHit(), 1e-9);
		assertEquals(53, fight.getDamageDealt());
	}

	@Test
	public void aSwingAtAnAddWithFiveLeftIsCappedAtFive()
	{
		// The brief's case: 5 left after a shot and a thrall's hit, a swing whose figures were read uncapped.
		final double[] at = LandingCap.recap(51, 0.91, 23.28, 262.5, 58, 5);
		assertTrue(at[0] <= 5.0);
		// A roll of 0-51 capped at 5 averages (0+1+2+3+4 + 47 x 5) / 52 = 4.71; about 4.4 at 0.91 to land.
		assertEquals(4.36, at[0], 0.05);
	}

	@Test
	public void theKillingBlowsOwnTickIsNotNothing()
	{
		// The game shows an npc dead on its killing hit's tick (T-100). A splat landing that tick may be the killing hit,
		// or one the game applied before a thrall's the count saw first: never capped at nothing.
		assertEquals(-1, PvmPerformancePlugin.capLeft(0, 4231, 4231));
		// A count run out with the npc not seen dead: something unseen was at work, so unknown, as before.
		assertEquals(-1, PvmPerformancePlugin.capLeft(0, null, 4231));
		// Hitpoints left: the count, dead or not.
		assertEquals(5, PvmPerformancePlugin.capLeft(5, null, 4231));
		assertEquals(5, PvmPerformancePlugin.capLeft(5, 4230, 4231));
		assertEquals(-1, PvmPerformancePlugin.capLeft(-1, 4230, 4231));
	}

	@Test
	public void nothingLeftCapsAnyFiguresAtNothing()
	{
		// Whatever the figures were read at: a projectile's throw-tick row (29 left), or none.
		final double[] shot = LandingCap.recap(29, 1.0, 29.0, 0, 29, 0);
		assertEquals(0.0, shot[0], 1e-9);
		assertEquals(0.0, shot[1], 1e-9);
		// Already nothing expected: nothing to change.
		assertNull(LandingCap.recap(51, 0.91, 0, 0, -1, 0));
		// Unknown stays unknown.
		assertNull(LandingCap.recap(51, 0.91, 23.28, 262.5, -1, -1));
		assertNull(LandingCap.recap(51, 0.91, 23.28, 262.5, 0, 5));
	}
}

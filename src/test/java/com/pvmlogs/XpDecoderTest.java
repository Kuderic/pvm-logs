package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class XpDecoderTest
{
	private static final int U = XpDecoder.UNKNOWN;

	private static int[] decode(int atk, int str, int def, int rng, int mag, int hp, double m)
	{
		return XpDecoder.damageRange(atk, str, def, rng, mag, hp, m);
	}

	@Test
	public void aSingleMeleeSkillPinsTheDamageExactly()
	{
		// 37 damage on aggressive: 148 strength, 49.3 hitpoints (49 or 50 on the client).
		assertArrayEquals(new int[]{37, 37}, decode(0, 148, 0, 0, 0, 49, 1.0));
		assertArrayEquals(new int[]{37, 37}, decode(0, 148, 0, 0, 0, 50, 1.0));
	}

	@Test
	public void hitpointsAloneIsAmbiguousAtSmallHits()
	{
		// The case that made hitpoints look useless: +2 is a one or a two. A powered staff pays no base, so magic here
		// is all damage - but the decoder leaves magic out and must say so honestly.
		assertArrayEquals(new int[]{1, 2}, decode(0, 0, 0, 0, 4, 2, 1.0));
	}

	@Test
	public void butTheCombatSkillSettlesIt()
	{
		assertArrayEquals(new int[]{1, 1}, decode(0, 0, 0, 4, 0, 2, 1.0));
		assertArrayEquals(new int[]{2, 2}, decode(0, 0, 0, 8, 0, 2, 1.0));
	}

	@Test
	public void aSplashPaysMagicAndNoHitpointsSoItDealtNothing()
	{
		assertArrayEquals(new int[]{0, 0}, decode(0, 0, 0, 0, 52, 0, 1.0));
	}

	@Test
	public void theBossBonusScalesEveryDrop()
	{
		// General Graardor, +32.5%: 40 damage pays 212 strength and 70.7 hitpoints.
		assertArrayEquals(new int[]{40, 40}, decode(0, 212, 0, 0, 0, 70, 1.325));
		assertArrayEquals(new int[]{40, 40}, decode(0, 212, 0, 0, 0, 71, 1.325));
		// Read without the bonus, the same drops are a different, wrong number - which is why the multiplier matters.
		assertEquals(53, decode(0, 212, 0, 0, 0, 70, 1.0)[0]);
	}

	@Test
	public void longRangeSplitsRangedAndDefence()
	{
		assertArrayEquals(new int[]{21, 21}, decode(0, 0, 42, 42, 0, 28, 1.0));
	}

	@Test
	public void sharedMeleeIsSettledByFourConstraintsTogether()
	{
		assertArrayEquals(new int[]{30, 30}, decode(40, 40, 40, 0, 0, 40, 1.0));
		assertArrayEquals(new int[]{2, 2}, decode(3, 2, 3, 0, 0, 3, 1.0));
	}

	@Test
	public void defensiveCastingIsLeftWithinAPoint()
	{
		// Defence at one a point and hitpoints at 1.33 cannot separate five from six; the range says so.
		assertArrayEquals(new int[]{5, 6}, decode(0, 0, 5, 0, 37, 7, 1.0));
	}

	@Test
	public void aSkillAtTheCapIsUnknownRatherThanZero()
	{
		// Hitpoints at 200m with no fake drop: its silence must not be read as zero damage.
		assertArrayEquals(new int[]{30, 30}, decode(0, 0, 0, 120, 0, U, 1.0));
	}

	@Test
	public void nothingPaidIsNothingToDecode()
	{
		assertNull(decode(0, 0, 0, 0, 0, 0, 1.0));
	}

	@Test
	public void dropsThatNoDamageFitsAreRejected()
	{
		// 160 ranged says 40; 10 hitpoints says 7 or 8.
		assertNull(decode(0, 0, 0, 160, 0, 10, 1.0));
	}

	@Test
	public void theBonusFormulaMatchesTheWikisListedBonuses()
	{
		// From the DPS calculator's data. Wiki infoboxes: Graardor +32.5%, Corporeal Beast +55%.
		assertEquals(1.325, XpDecoder.bonusMultiplier(280, 350, 250, 255, 90, 90, 90, 120, 43), 1e-9);
		assertEquals(1.55, XpDecoder.bonusMultiplier(320, 320, 310, 2000, 25, 200, 100, 50, 0), 1e-9);
		// An ordinary monster with no bonus.
		assertEquals(1.0, XpDecoder.bonusMultiplier(1, 1, 1, 10, 0, 0, 0, 0, 0), 1e-9);
	}

	@Test
	public void theOlmIsSetByHand()
	{
		assertEquals(1.0, XpDecoder.overrideFor("Great Olm"), 1e-9);
		assertEquals(-1, XpDecoder.overrideFor("Vorkath"), 1e-9);
	}
}

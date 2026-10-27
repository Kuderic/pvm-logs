package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The magic formula, step by step as the wiki's Maximum magic hit page gives it
 * (read 2026-10-27), with a floor after each step. Each test names the step it
 * pins and, where the plugin used to get it wrong, what it used to read.
 */
public class MagicDamageTest
{
	private static final double DELTA = 1e-9;

	private static MagicDamage spell(int base, double percent)
	{
		final MagicDamage m = new MagicDamage();
		m.base = base;
		m.percent = percent;
		return m;
	}

	/**
	 * "All of these bonuses are calculated after the player's own magic damage
	 * bonuses" (wiki, Demonbane spells), and on the rolled hit: Superior
	 * Demonbane's 23 at +17% is floor(26.91) = 26, and the Mark's 25% of that is
	 * 6, so 32. One multiplier over the lot reads 33.
	 */
	@Test
	public void theMarkComesAfterTheMagicDamage()
	{
		final MagicDamage m = spell(23, 17);
		m.markPercent = 25;
		assertEquals(26, m.preHitRoll());
		assertEquals(32, m.maxHit());
		assertEquals(33, (int) Math.floor(23 * 1.17 * 1.25));
	}

	/** Dark Demonbane, +10% from the purging staff, the Mark with the staff: 33 rolled, +16. */
	@Test
	public void markWithThePurgingStaffAddsHalfTheRoll()
	{
		final MagicDamage m = spell(30, 10);
		m.markPercent = 50;
		assertEquals(33, m.preHitRoll());
		assertEquals(49, m.maxHit());
		// Every roll from 0 to 33, each with its own half added: 24.5, the max over two.
		assertEquals(24.5, m.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
	}

	/**
	 * Demonbane effectiveness scales the Mark's added damage, rounded down (wiki,
	 * Maximum magic hit): Duke Sucellus 70% gives 33 + floor(16 x 0.7) = 44, a
	 * Void Flare's 200% gives 33 + 32 = 65.
	 */
	@Test
	public void effectivenessScalesTheMarksDamage()
	{
		final MagicDamage duke = spell(30, 10);
		duke.markPercent = 50;
		duke.effectivenessPercent = 70;
		assertEquals(44, duke.maxHit());
		assertEquals(21.676470588235293, duke.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
		final MagicDamage flare = spell(30, 10);
		flare.markPercent = 50;
		flare.effectivenessPercent = 200;
		assertEquals(65, flare.maxHit());
		assertEquals(32.5, flare.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
	}

	/**
	 * Elemental weakness joins the primary damage, before the slayer helm and the
	 * tome multiply it: Fire Surge 24 at +20% against 50% fire weakness is
	 * 28 + 12 = 40, and the tome makes 44. Added after the multipliers, as this
	 * used to, it read 30 + 12 = 42.
	 */
	@Test
	public void theTomeMultipliesElementalWeaknessToo()
	{
		final MagicDamage m = spell(24, 20);
		m.weaknessPercent = 50;
		m.tomePercent = 10;
		assertEquals(40, m.primaryMagicDamage());
		assertEquals(44, m.maxHit());
	}

	/** The pre-roll steps floor one at a time: 30 at +15% slayer is 34, at +10% tome 37. */
	@Test
	public void slayerThenTomeEachRoundDown()
	{
		final MagicDamage m = spell(24, 25);
		m.slayerPercent = 15;
		m.tomePercent = 10;
		assertEquals(30, m.primaryMagicDamage());
		assertEquals(34, m.beforeTome());
		assertEquals(37, m.preHitRoll());
	}

	/** Chaos gauntlets and Charge add to the base, before any percentage (wiki). */
	@Test
	public void flatAdditionsComeFirst()
	{
		final MagicDamage bolt = spell(12, 20);
		bolt.flat = 3;
		assertEquals(15, bolt.baseDamageModifier());
		assertEquals(18, bolt.maxHit());
		final MagicDamage strike = spell(20, 0);
		strike.flat = 10;
		assertEquals(30, strike.maxHit());
	}

	/**
	 * Sunfire runes: a successful hit deals at least a tenth of the max, the
	 * tome's 10% left out of it (wiki, Tome of Fire). 28 before the tome, 30 after:
	 * rolls of 2 to 30, an average of 16 where 0 to 30 averages 15.
	 */
	@Test
	public void sunfireRaisesTheBottomOfTheRoll()
	{
		final MagicDamage m = spell(24, 20);
		m.tomePercent = 10;
		m.tenthMinimum = true;
		assertEquals(30, m.preHitRoll());
		assertEquals(2, m.minHit());
		assertEquals(16.0, m.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
		m.tenthMinimum = false;
		assertEquals(0, m.minHit());
		assertEquals(15.0, m.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
	}

	/**
	 * The twinflame staff's second spell is 40% of the first, rounded down (wiki):
	 * Fire Wave 20 at +10% from the staff is 22, the second 8, the attack 30.
	 */
	@Test
	public void twinflamesSecondHitIsFortyPercentOfTheFirst()
	{
		final MagicDamage m = spell(20, 10);
		m.twinflame = true;
		assertEquals(22, m.maxHit());
		assertEquals(8, m.secondMaxHit());
		assertEquals(30, m.attackMaxHit());
		assertEquals(15.0, m.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
		// Each splat is held to a per-splat cap on its own.
		assertEquals(5.043478260869565, m.expectedOnHit(1.0, 3, Integer.MAX_VALUE), DELTA);
	}

	/** Ahrim's: a 25% chance of 30% more on the rolled hit, so 26 is reachable and the average is a little over 10. */
	@Test
	public void ahrimsIsAChanceOnTheRoll()
	{
		final MagicDamage m = spell(20, 0);
		m.ahrims = true;
		assertEquals(26, m.maxHit());
		assertEquals(10.642857142857142, m.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
	}

	/** What the target has left caps the attack; its mitigation scales each splat. */
	@Test
	public void capsAndMitigation()
	{
		final MagicDamage m = spell(20, 0);
		assertEquals(4.285714285714286, m.expectedOnHit(1.0, Integer.MAX_VALUE, 5), DELTA);
		assertEquals(4.761904761904762, m.expectedOnHit(0.5, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
	}

	/** Other multipliers, such as the dragon hunter wand's, sit after the slayer helm and before the tome. */
	@Test
	public void otherMultipliersSitBetweenSlayerAndTome()
	{
		final MagicDamage m = spell(24, 0);
		m.slayerPercent = 15;
		m.others = 1.4;
		// 24, then floor(27.6) = 27 for the helm, then floor(37.8) = 37 for the wand.
		assertEquals(37, m.beforeTome());
		// A leafy target with the wrong spell: nothing lands.
		final MagicDamage leafy = spell(24, 0);
		leafy.others = 0.0;
		assertEquals(0, leafy.maxHit());
		assertEquals(0.0, leafy.expectedOnHit(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE), DELTA);
	}

	@Test
	public void describeNamesEveryStepUsed()
	{
		final MagicDamage m = spell(30, 10);
		m.markPercent = 50;
		m.effectivenessPercent = 70;
		final String line = m.describe();
		assertEquals("base 30, magic dmg +10.0% -> 33 -> roll 0-33, mark +50% x 70% effective -> max 44", line);
	}
}

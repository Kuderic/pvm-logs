package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-151: Araxxor's ~3% more landings than expected, and the crossbow's max on the araxytes one or two short.
 * <ul>
 * <li>A player's successful 0 is pushed up to 1 (OSRS Wiki, Successful hit, since 2024-05-29), so every successful roll
 * lands a splat: the landed chance is the accuracy, not accuracy x max / (max + 1).</li>
 * <li>Dizana's Sunfire: a charged quiver's +10 ranged accuracy and +1 ranged strength with arrows or bolts (OSRS Wiki,
 * Dizana's quiver).</li>
 * </ul>
 */
public class SuccessfulHitsTest
{
	@Test
	public void everySuccessfulRollLands()
	{
		// The tentacle at Araxxor, max about 40: before, 2.4% of its successful rolls were taken for misses.
		assertEquals(0.80, Fight.landedChance(0.80, 40), 1e-12);
		final Fight f = new Fight("Araxxor", 13668, 1, 1020, 0L, null, 0);
		f.recordExpected(40, 0.80, 16.4);
		assertEquals(0.80, f.getSumExpectedLanded(), 1e-12);
		assertEquals(0.80 * 0.20, f.getSumExpectedLandedVariance(), 1e-12);
	}

	@Test
	public void theSunfireTakesACrossbowsBoltsOrABowsArrows()
	{
		assertTrue(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_CHARGED, "Sunlight antler bolts", WeaponCategory.CROSSBOW));
		assertTrue(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_INFINITE, "Dragon arrow", WeaponCategory.BOW));
		assertTrue(CombatCalc.sunfire(ItemID.SKILLCAPE_MAX_DIZANAS, "Ruby dragon bolts (e)", WeaponCategory.CROSSBOW));
		// Uncharged, or nothing it stores, or ammo the weapon doesn't fire: nothing.
		assertFalse(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_UNCHARGED, "Sunlight antler bolts", WeaponCategory.CROSSBOW));
		assertFalse(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_CHARGED, "Rada's blessing 4", WeaponCategory.CROSSBOW));
		assertFalse(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_CHARGED, "Dragon arrow", WeaponCategory.CROSSBOW));
		assertFalse(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_CHARGED, "Dragon dart", WeaponCategory.THROWN));
		assertFalse(CombatCalc.sunfire(6570 /* Fire cape */, "Sunlight antler bolts", WeaponCategory.CROSSBOW));
		assertFalse(CombatCalc.sunfire(ItemID.DIZANAS_QUIVER_CHARGED, null, WeaponCategory.CROSSBOW));
	}

	@Test
	public void theCrossbowsMaxOnTheAraxytes()
	{
		// Eric's 11-02 araxytes: Ranged 109 (99 + 10), Deadeye x1.18, Rapid: an effective 136. Bolts +55, the quiver +3,
		// Avernic treads +2: 60. On task, the Oathplate slayer helmet (i)'s x1.15.
		final int without = CombatCalc.maxHitFromStrength(136, 60);
		final int with = CombatCalc.maxHitFromStrength(136, 60 + CombatCalc.SUNFIRE_STRENGTH);
		assertEquals(26, without);
		assertEquals(27, with);
		// The plugin's 29; the max-hit splat's 31 ([6794], [6836], [14217], ...).
		assertEquals(29, (int) (without * 1.15));
		assertEquals(31, (int) (with * 1.15));
		// The potion further worn down (boost 0.86 at [15674]): an effective 131 (Ranged 105) gives 28 and 29, the other
		// MAX EXCEEDED's (29 > 28 at [7200], [14630], [15674], [15677]).
		assertEquals(28, (int) (CombatCalc.maxHitFromStrength(131, 60) * 1.15));
		assertEquals(29, (int) (CombatCalc.maxHitFromStrength(131, 61) * 1.15));
	}
}

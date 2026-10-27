package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.pvmlogs.CombatCalc.OrdinaryShape;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-032: weapons that hit more than once per attack, their expected damage and
 * its variance against each attack's distribution written out value by value,
 * from the OSRS Wiki (Multi-hit weapons and each weapon's page, read
 * 2026-10-27).
 */
public class MultiHitDamageTest
{
	private static final int NONE = Integer.MAX_VALUE;

	// ---- PMF helpers: P(damage = d) ----

	private static double[] roll(int max, int cap)
	{
		final double[] p = new double[Math.max(0, max) + 1];
		for (int r = 0; r <= max; r++)
		{
			p[Math.min(r, cap)] += 1.0 / (max + 1);
		}
		return p;
	}

	private static double[] landing(double[] roll, double chance)
	{
		final double[] p = new double[roll.length];
		for (int d = 0; d < roll.length; d++)
		{
			p[d] = chance * roll[d];
		}
		p[0] += 1 - chance;
		return p;
	}

	private static double[] convolve(double[] a, double[] b)
	{
		final double[] c = new double[a.length + b.length - 1];
		for (int i = 0; i < a.length; i++)
		{
			for (int j = 0; j < b.length; j++)
			{
				c[i + j] += a[i] * b[j];
			}
		}
		return c;
	}

	private static double mean(double[] p)
	{
		double m = 0;
		for (int d = 0; d < p.length; d++)
		{
			m += d * p[d];
		}
		return m;
	}

	private static double variance(double[] p)
	{
		final double m = mean(p);
		double v = 0;
		for (int d = 0; d < p.length; d++)
		{
			v += (d - m) * (d - m) * p[d];
		}
		return v;
	}

	private static void assertMatches(String what, double[] pmf, DamageMoments m)
	{
		assertEquals(what + " mean", mean(pmf), m.mean, 1e-9);
		assertEquals(what + " variance", variance(pmf), m.variance(), 1e-7);
	}

	// ---- Ordinary attacks ----

	@Test
	public void theSplitWeaponsRollTwoHalvesApart()
	{
		// Torag's hammers, the blades, temotli and tecpatls: "45 -> 22 + 23", each its own accuracy roll.
		assertArrayEquals(new int[]{22, 23}, CombatCalc.shapeMaxima(OrdinaryShape.SPLIT, 45, 1));
		final double p = 0.7;
		final DamageMoments m = CombatCalc.shapeMoments(OrdinaryShape.SPLIT, 45, p, 1, NONE);
		assertMatches("split", convolve(landing(roll(22, NONE), p), landing(roll(23, NONE), p)), m);
		// The same average as one roll to 45; a narrower spread.
		assertEquals(p * 45 / 2.0, m.mean, 1e-12);
		assertTrue(m.variance() < CombatCalc.shapeMoments(OrdinaryShape.ONE, 45, p, 1, NONE).variance());
		// Each half held to the cap on its own.
		assertMatches("split capped", convolve(landing(roll(22, 15), p), landing(roll(23, 15), p)),
			CombatCalc.shapeMoments(OrdinaryShape.SPLIT, 45, p, 1, 15));
		assertEquals(1 - 0.3 * 0.3, CombatCalc.shapeLandChance(OrdinaryShape.SPLIT, p, 1), 1e-12);
	}

	@Test
	public void theMacuahuitlsSecondHitNeedsItsFirst()
	{
		// "41 splits into 20 and 21"; "the second accuracy check is guaranteed to fail if the first one failed".
		assertArrayEquals(new int[]{20, 21}, CombatCalc.shapeMaxima(OrdinaryShape.MACUAHUITL, 41, 1));
		final double p = 0.7;
		final DamageMoments m = CombatCalc.shapeMoments(OrdinaryShape.MACUAHUITL, 41, p, 1, NONE);
		// B1 (Y1 + B2 Y2): with chance 1 - p nothing; else the first roll plus a second that lands with p.
		final double[] landed = convolve(roll(20, NONE), landing(roll(21, NONE), p));
		assertMatches("macuahuitl", landing(landed, p), m);
		assertEquals(p * 10 + p * p * 10.5, m.mean, 1e-12);
		// Below the one roll to 41 that master expected.
		assertTrue(m.mean < p * 41 / 2.0);
		assertEquals(p, CombatCalc.shapeLandChance(OrdinaryShape.MACUAHUITL, p, 1), 0);
	}

	@Test
	public void theDarkBowFiresTwoFullArrows()
	{
		assertArrayEquals(new int[]{38, 38}, CombatCalc.shapeMaxima(OrdinaryShape.TWO_FULL, 38, 1));
		final double p = 0.6;
		final DamageMoments m = CombatCalc.shapeMoments(OrdinaryShape.TWO_FULL, 38, p, 1, NONE);
		final double[] arrow = landing(roll(38, NONE), p);
		assertMatches("dark bow", convolve(arrow, arrow), m);
		// Twice the one roll master expected.
		assertEquals(2 * p * 19, m.mean, 1e-12);
	}

	@Test
	public void theTonalzticsThrowThreeQuartersOfTheMax()
	{
		// "hits a target for 0-75% of the player's maximum ranged hit", twice when charged.
		assertArrayEquals(new int[]{30, 30}, CombatCalc.shapeMaxima(OrdinaryShape.TWO_THREE_QUARTERS, 40, 1));
		assertArrayEquals(new int[]{30}, CombatCalc.shapeMaxima(OrdinaryShape.THREE_QUARTERS, 40, 1));
		final double p = 0.65;
		final double[] thrown = landing(roll(30, NONE), p);
		assertMatches("charged", convolve(thrown, thrown),
			CombatCalc.shapeMoments(OrdinaryShape.TWO_THREE_QUARTERS, 40, p, 1, NONE));
		assertMatches("uncharged", thrown, CombatCalc.shapeMoments(OrdinaryShape.THREE_QUARTERS, 40, p, 1, NONE));
		assertMatches("charged capped", convolve(landing(roll(30, 12), p), landing(roll(30, 12), p)),
			CombatCalc.shapeMoments(OrdinaryShape.TWO_THREE_QUARTERS, 40, p, 1, 12));
	}

	@Test
	public void theScytheAndASingleHitAreAsBefore()
	{
		assertEquals(CombatCalc.ordinaryMoments(51, 0.7, 3, NONE).mean,
			CombatCalc.shapeMoments(OrdinaryShape.SCYTHE, 51, 0.7, 3, NONE).mean, 0);
		assertArrayEquals(new int[]{51, 25, 12}, CombatCalc.shapeMaxima(OrdinaryShape.SCYTHE, 51, 3));
		assertEquals(CombatCalc.ordinaryMoments(46, 0.7, 1, NONE).mean,
			CombatCalc.shapeMoments(OrdinaryShape.ONE, 46, 0.7, 1, NONE).mean, 0);
	}

	// ---- Specials ----

	private static double[] darkBowArrow(int top, int minimum, int ceiling, double p)
	{
		final double[] hit = new double[ceiling == NONE ? top + 1 : Math.min(top, ceiling) + 1];
		for (int r = 0; r <= top; r++)
		{
			hit[Math.min(ceiling, Math.max(minimum, r))] += 1.0 / (top + 1);
		}
		final double[] arrow = new double[Math.max(hit.length, minimum + 1)];
		for (int d = 0; d < hit.length; d++)
		{
			arrow[d] += p * hit[d];
		}
		arrow[minimum] += 1 - p;
		return arrow;
	}

	@Test
	public void descentOfDragonsAndOfDarkness()
	{
		final double p = 0.55;
		// Dragon arrows: 40 x 1.5 = 60, held to 48, at least 8 even on a miss.
		final double[] dragon = darkBowArrow(60, 8, 48, p);
		assertMatches("dragon", convolve(dragon, dragon), CombatCalc.darkBowSpecMoments(40, p, true, NONE));
		// Others: 40 x 1.3 = 52, no cap, at least 5.
		final double[] other = darkBowArrow(52, 5, NONE, p);
		assertMatches("other", convolve(other, other), CombatCalc.darkBowSpecMoments(40, p, false, NONE));
		// A miss still deals the minimum: two 8s at no accuracy at all.
		assertEquals(16, CombatCalc.darkBowSpecMoments(40, 0, true, NONE).mean, 0);
		assertEquals(0, CombatCalc.darkBowSpecMoments(40, 0, true, NONE).variance(), 1e-12);
	}

	@Test
	public void bloodInfusionSplitsItsRaisedMax()
	{
		// "floor(38 x 1.25) = 47, split as 23 + 24", +25% accuracy, the two rolling independently.
		final SpecialAttack spec = SpecialAttack.DUAL_MACUAHUITL;
		assertArrayEquals(new int[]{47}, spec.hitMaxima(38));
		assertTrue(spec.splitsMaxInTwo());
		assertEquals(2, spec.hits());
		assertEquals(1.25, spec.getAttackRollMultiplier(), 0);
		final double p = 0.8;
		final DamageMoments m = CombatCalc.specMoments(new int[]{23, 24}, p, 0, 0, NONE, false);
		assertMatches("blood infusion", convolve(landing(roll(23, NONE), p), landing(roll(24, NONE), p)), m);
	}

	@Test
	public void brandIsFourRollsAndOneDamageRoll()
	{
		final SpecialAttack spec = SpecialAttack.forItem(ItemID.INFERNAL_TECPATL);
		assertEquals(SpecialAttack.INFERNAL_TECPATL, spec);
		assertEquals(4, spec.hits());
		assertEquals(1.25, spec.getAttackRollMultiplier(), 0);
		assertArrayEquals(new int[]{50}, spec.hitMaxima(40));
		assertEquals(50, spec.maxTotal(40));
	}

	// ---- Splat counts ----

	@Test
	public void dinhsBulwarkHitsTwiceOnlyInMulticombat()
	{
		assertEquals(2, AttackSplats.of(ItemID.DINHS_BULWARK, true, 1, null, true).count);
		assertEquals(1, AttackSplats.of(ItemID.DINHS_BULWARK, true, 1, null, false).count);
		// The four-argument form keeps the upper bound the replays were written against.
		assertEquals(2, AttackSplats.of(ItemID.DINHS_BULWARK, true, 1, null).count);
	}

	@Test
	public void theSplatCountsTheModelsAgreeWith()
	{
		// Torag's hammers do hit twice in Old School (wiki: "two independently rolled hits per attack").
		assertEquals(2, AttackSplats.of(ItemID.BARROWS_TORAG_WEAPON, false, 1, null).count);
		assertTrue(AttackSplats.splitsMaxInTwo(ItemID.BARROWS_TORAG_WEAPON_50));
		assertTrue(AttackSplats.splitsMaxInTwo(ItemID.INFERNAL_TECPATL));
		assertTrue(AttackSplats.isMacuahuitl(ItemID.BR_DUAL_MACUAHUITL));
		assertEquals(4, AttackSplats.of(ItemID.INFERNAL_TECPATL, true, 1, null).count);
		assertEquals(2, AttackSplats.of(ItemID.TONALZTICS_OF_RALOS_CHARGED, false, 1, null).count);
		assertEquals(1, AttackSplats.of(ItemID.TONALZTICS_OF_RALOS_UNCHARGED, false, 1, null).count);
		// The twinflame staff's count follows the same rule as its second hit's damage.
		assertEquals(2, AttackSplats.of(ItemID.TWINFLAME_STAFF, false, 1, Spell.FIRE_WAVE).count);
		assertEquals(1, AttackSplats.of(ItemID.TWINFLAME_STAFF, false, 1, Spell.FIRE_SURGE).count);
		assertEquals(1, AttackSplats.of(ItemID.TWINFLAME_STAFF, false, 1, Spell.FIRE_STRIKE).count);
	}
}

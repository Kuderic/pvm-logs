package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.pvmlogs.CombatCalc.OrdinaryShape;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-203: golembane and flat armour. Eric's granite hammer on a gargoyle on task read a max of 47; the game's max-hit
 * splat was 63 = ⌊47 × 1.3⌋ + 2 (his log of 2026-11-10, build eef42ce). OSRS Wiki, read 2026-11-10: Granite hammer
 * ("30% extra damage and accuracy against all golem-type creatures", stacking "multiplicatively with the slayer
 * helmet's bonuses"), Barronite mace ("15% damage and accuracy"), Flat armour ("subtracting the flat armour value from
 * the attack's final damage roll", "applied to each hitsplat separately", magic ignores it). The DPS calc (c446f2e,
 * facts only): golembane's maxima after the slayer helmet's, each rounded down; flat armour on accurate hitsplats only,
 * after the successful 0's push, never below 0.
 */
public class GolembaneFlatArmourTest
{
	private static final int NONE = Integer.MAX_VALUE;

	// The DPS calc's monsters.json shape for Gargoyle (Basement): golem, flat armour -2.
	private static final String MONSTERS = "[{\"id\": 1543, \"name\": \"Gargoyle\", \"version\": \"Basement\", \"size\": 3,"
		+ " \"skills\": {\"atk\": 75, \"def\": 107, \"hp\": 105, \"magic\": 1, \"ranged\": 1, \"str\": 105},"
		+ " \"offensive\": {\"atk\": 0, \"magic\": 0, \"magic_str\": 0, \"ranged\": 0, \"ranged_str\": 0, \"str\": 0},"
		+ " \"defensive\": {\"flat_armour\": -2, \"crush\": 10, \"magic\": 20, \"heavy\": 20, \"standard\": 20,"
		+ " \"light\": 20, \"slash\": 20, \"stab\": 20}, \"attributes\": [\"golem\"]},"
		+ " {\"id\": 2, \"name\": \"Plain\", \"version\": \"\", \"size\": 1,"
		+ " \"skills\": {\"atk\": 1, \"def\": 1, \"hp\": 10, \"magic\": 1, \"ranged\": 1, \"str\": 1},"
		+ " \"defensive\": {\"crush\": 0, \"magic\": 0, \"standard\": 0, \"slash\": 0, \"stab\": 0}, \"attributes\": []}]";

	private static MonsterStatsProvider.MonsterStats parsed(int id)
	{
		final MonsterStatsProvider.Parsed data = MonsterStatsProvider.parse(new Gson(), new StringReader(MONSTERS),
			BossRules.empty());
		return data.versionsById.get(id).get(0);
	}

	/** Every roll 0..max alike, landed with p: the 0 pushed to 1, then the shift, never below 0, then the cap. */
	private static double[] bruteForce(int max, double p, int cap, int shift, boolean push)
	{
		double mean = 0;
		double second = 0;
		for (int r = 0; r <= max; r++)
		{
			final double v = Math.min(cap, Math.max(0, (push ? Math.max(1, r) : r) + shift));
			mean += p * v / (max + 1);
			second += p * v * v / (max + 1);
		}
		return new double[]{mean, second};
	}

	private static void assertMoments(double[] want, DamageMoments got)
	{
		assertEquals("mean", want[0], got.mean, 1e-9);
		assertEquals("second", want[1], got.second, 1e-9);
	}

	@Test
	public void theDataGivesTheGargoyleItsGolemAndItsFlatArmour()
	{
		final MonsterStatsProvider.MonsterStats gargoyle = parsed(1543);
		assertTrue(gargoyle.hasAttribute("golem"));
		assertEquals(-2, gargoyle.getFlatArmour());
		// A monster with none in the data reads 0.
		assertEquals(0, parsed(2).getFlatArmour());
		// Copies keep it.
		assertEquals(-2, gargoyle.withDrainFacts(1, 1, 1, false).getFlatArmour());
		assertEquals(6, gargoyle.withFlatArmour(6).getFlatArmour());
	}

	@Test
	public void theGraniteHammerOnAGargoyleOnTaskReaches63()
	{
		final MonsterStatsProvider.MonsterStats gargoyle = parsed(1543);
		final int[] bane = GearBonusCalc.golembane(AttackType.CRUSH, gargoyle, ItemID.GRANITE_HAMMER);
		assertArrayEquals(new int[]{13, 10}, bane);
		// 47 with the slayer helmet (his log), ⌊47 × 1.3⌋ = 61, then the flat armour's 2 on the hit that lands.
		final int golembane = CombatCalc.golembaneMax(47, bane);
		assertEquals(61, golembane);
		final int shift = CombatCalc.flatArmourShift(false, gargoyle);
		assertEquals(2, shift);
		assertEquals(63, golembane + shift);
		// The landed hit's moments: 1..61 pushed and raised by 2, so 3..63 and nothing under 3 (his smallest of 55 was 3).
		final double p = 0.94;
		assertMoments(bruteForce(61, p, NONE, 2, true), CombatCalc.ordinaryMoments(61, p, 1, NONE, true, 2));
		assertMoments(bruteForce(61, p, NONE, 2, true), CombatCalc.shapeMoments(OrdinaryShape.ONE, 61, p, 1, NONE, true, 2));
		// Eric's 0.922 at 47 averaged 21.69; ×1.3 on the attack roll and 63 make about 30.6.
		assertEquals(21.69, 0.922 * (47 / 2.0 + 1.0 / 48), 0.01);
		assertTrue(CombatCalc.ordinaryMoments(61, 0.94, 1, NONE, true, 2).mean > 30.5);
	}

	@Test
	public void theBarroniteMaceGetsFifteenPercent()
	{
		final MonsterStatsProvider.MonsterStats gargoyle = parsed(1543);
		final int[] bane = GearBonusCalc.golembane(AttackType.CRUSH, gargoyle, ItemID.BARRONITE_MACE);
		assertArrayEquals(new int[]{23, 20}, bane);
		assertEquals(54, CombatCalc.golembaneMax(47, bane));
		assertArrayEquals(new int[]{23, 20},
			GearBonusCalc.golembane(AttackType.CRUSH, gargoyle, ItemID.BARRONITE_MACE_TROUVER));
	}

	@Test
	public void aNonGolemOrAnotherStyleIsUnchanged()
	{
		final MonsterStatsProvider.MonsterStats plain = parsed(2);
		assertNull(GearBonusCalc.golembane(AttackType.CRUSH, plain, ItemID.GRANITE_HAMMER));
		final MonsterStatsProvider.MonsterStats gargoyle = parsed(1543);
		assertNull(GearBonusCalc.golembane(AttackType.CRUSH, gargoyle, ItemID.DRAGON_WARHAMMER));
		assertNull(GearBonusCalc.golembane(AttackType.RANGED, gargoyle, ItemID.GRANITE_HAMMER));
		assertNull(GearBonusCalc.golembane(AttackType.MAGIC, gargoyle, ItemID.GRANITE_HAMMER));
		assertEquals(47, CombatCalc.golembaneMax(47, null));
		// No flat armour: the shift is nothing, and the moments are the old ones exactly.
		assertEquals(0, CombatCalc.flatArmourShift(false, plain));
		assertEquals(CombatCalc.ordinaryMoments(40, 0.8, 1, NONE, true).second,
			CombatCalc.ordinaryMoments(40, 0.8, 1, NONE, true, 0).second, 0);
	}

	@Test
	public void flatArmourOnMeleeAndRangedNotMagic()
	{
		final MonsterStatsProvider.MonsterStats gargoyle = parsed(1543);
		// -2 (a gargoyle, the Blood Moon), +6 (the Eclipse Moon), -5 (the Blue Moon): the shift is the armour's negative.
		for (int armour : new int[]{-2, 6, -5})
		{
			final MonsterStatsProvider.MonsterStats npc = gargoyle.withFlatArmour(armour);
			final int shift = CombatCalc.flatArmourShift(false, npc);
			assertEquals(-armour, shift);
			// Magic ignores it, positive or negative.
			assertEquals(0, CombatCalc.flatArmourShift(true, npc));
			// A melee or ranged roll, uncapped and capped, walked value by value; +6 armour clamps the low rolls to 0.
			assertMoments(bruteForce(40, 0.8, NONE, shift, true), CombatCalc.shapeMoments(OrdinaryShape.ONE, 40, 0.8, 1, NONE,
				true, shift));
			assertMoments(bruteForce(40, 0.8, 25, shift, true), CombatCalc.shapeMoments(OrdinaryShape.ONE, 40, 0.8, 1, 25,
				true, shift));
		}
		// +6 armour: a landed 1..6 deals 0; the landed mean is 40/2 + 1/41 - 6 plus what the clamp gives back.
		final double[] clamped = bruteForce(40, 1.0, NONE, -6, true);
		assertTrue(clamped[0] > 40 / 2.0 + 1.0 / 41 - 6);
	}

	@Test
	public void aScytheGetsTheFlatArmourOnEachOfItsThreeHits()
	{
		// 51, 25, 12, each its own roll and accuracy, each +2.
		final double p = 0.7;
		final DamageMoments scythe = CombatCalc.shapeMoments(OrdinaryShape.SCYTHE, 51, p, 3, NONE, true, 2);
		double mean = 0;
		double variance = 0;
		for (int max : new int[]{51, 25, 12})
		{
			final double[] hit = bruteForce(max, p, NONE, 2, true);
			mean += hit[0];
			variance += hit[1] - hit[0] * hit[0];
		}
		assertEquals(mean, scythe.mean, 1e-9);
		assertEquals(variance, scythe.variance(), 1e-9);
		// Three hits, so 6 more on average than one +2 would give when all land.
		assertEquals(CombatCalc.shapeMoments(OrdinaryShape.SCYTHE, 51, 1.0, 3, NONE, true, 0).mean + 6,
			CombatCalc.shapeMoments(OrdinaryShape.SCYTHE, 51, 1.0, 3, NONE, true, 2).mean, 1e-9);
		// The dual macuahuitl and the split hits likewise: each half that lands gets it.
		assertEquals(CombatCalc.shapeMoments(OrdinaryShape.SPLIT, 40, 1.0, 1, NONE, true, 0).mean + 4,
			CombatCalc.shapeMoments(OrdinaryShape.SPLIT, 40, 1.0, 1, NONE, true, 2).mean, 1e-9);
		assertEquals(CombatCalc.shapeMoments(OrdinaryShape.MACUAHUITL, 40, 1.0, 1, NONE, true, 0).mean + 4,
			CombatCalc.shapeMoments(OrdinaryShape.MACUAHUITL, 40, 1.0, 1, NONE, true, 2).mean, 1e-9);
		// The claws: each hitsplat of the connecting outcome; with every first roll landing, the four splats +2 each.
		assertEquals(CombatCalc.clawsMoments(40, 1.0, NONE, 0).mean + 8, CombatCalc.clawsMoments(40, 1.0, NONE, 2).mean,
			1e-9);
		// A multi-hit special's every hitsplat.
		assertEquals(CombatCalc.specMoments(new int[]{30, 30}, 1.0, 0, 0, NONE, false, true, 0).mean + 4,
			CombatCalc.specMoments(new int[]{30, 30}, 1.0, 0, 0, NONE, false, true, 2).mean, 1e-9);
	}

	@Test
	public void hammerBlowOnAGolem()
	{
		// +50% accuracy is the special's; golembane's 61 the max; a landed hit rolls 0..61 (pushed), adds 5, then the 2.
		// A miss deals the 5 alone: flat armour is on landed hitsplats only.
		final double p = 0.97;
		final DamageMoments blow = CombatCalc.hammerBlowMoments(61, p, 5, NONE, true, 2);
		final double[] hit = bruteForce(61, 1.0, NONE, 7, true);
		assertEquals(p * hit[0] + (1 - p) * 5, blow.mean, 1e-9);
		assertEquals(p * hit[1] + (1 - p) * 25, blow.second, 1e-9);
		// No golem, no flat armour: still 5 on a miss, 5 more on a hit.
		final DamageMoments plain = CombatCalc.hammerBlowMoments(40, 0.6, 5, NONE, true, 0);
		final double[] plainHit = bruteForce(40, 1.0, NONE, 5, true);
		assertEquals(0.6 * plainHit[0] + 0.4 * 5, plain.mean, 1e-9);
		// Its top: 61 + 5 + 2.
		assertEquals(68, 61 + SpecialAttack.GRANITE_HAMMER.flatAddition() + 2);
	}

	@Test
	public void theHpLeftStillCaps()
	{
		// 30 left: the raised roll collapses onto 30; 1 left: every landed hit deals 1.
		assertMoments(bruteForce(61, 0.94, 30, 2, true), CombatCalc.ordinaryMoments(61, 0.94, 1, 30, true, 2));
		assertEquals(0.94, CombatCalc.ordinaryMoments(61, 0.94, 1, 1, true, 2).mean, 1e-12);
		// Nothing left lets nothing through.
		assertEquals(0, DamageMoments.shot(0, 61, 0, true, 2).mean, 0);
		// Hammer Blow's miss is held to the cap too.
		assertEquals(3.0, CombatCalc.hammerBlowMoments(61, 0.0, 5, 3, true, 2).mean, 1e-12);
	}

	@Test
	public void aBossModifierWalkTakesTheFlatArmourLast()
	{
		// A halving (Corp-like), then the 2: ⌊pushed/2⌋ + 2 on a landed hit; a miss deals nothing.
		final BossRules.ModifierRule half = new BossRules.ModifierRule();
		half.name = "halved";
		half.multiply = new int[]{1, 2};
		final List<BossRules.ModifierRule> mods = Collections.singletonList(half);
		final DamageMoments got = ModifierTracker.moments(mods, new int[]{20}, 0.8, NONE, true, 2);
		double mean = 0;
		for (int r = 0; r <= 20; r++)
		{
			mean += 0.8 * (Math.max(1, r) / 2 + 2) / 21.0;
		}
		assertEquals(mean, got.mean, 1e-9);
		assertNotNull(got);
	}
}

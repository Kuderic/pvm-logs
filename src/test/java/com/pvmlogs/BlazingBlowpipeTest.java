package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import org.junit.Test;

/**
 * T-150: the Blazing blowpipe is the Toxic blowpipe with the Trailblazer reloaded ornament kit, which "gives no
 * additional bonuses" (OSRS Wiki, Blazing blowpipe). Its special, Toxic Siphon (wiki, Toxic blowpipe): accuracy +100%,
 * damage +50%. Eric's MAX EXCEEDED lines with it were specials booked as ordinary attacks, or bow shots scored with its
 * figures; his darts were Adamant throughout, by their projectile.
 */
public class BlazingBlowpipeTest
{
	/**
	 * Darts' ranged strength (wiki, Toxic blowpipe's table): the blowpipe adds them to its own +20. The plugin reads them
	 * from RuneLite's item stats; these are only for the arithmetic below.
	 */
	private static final int BLOWPIPE = 20;
	private static final int MITHRIL = 9;
	private static final int ADAMANT = 17;
	private static final int RUNE = 26;
	private static final int AMETHYST = 28;
	private static final int DRAGON = 35;
	/** The rest of Eric's ranged strength at the Hydra (anguish +5, Dizana's quiver +3): a reading, labelled. */
	private static final int GEAR = 8;
	/** An effective Ranged Strength giving the Hydra's logged max of 24 with Adamant darts (138 to 143 all do). */
	private static final int EFFECTIVE = 141;

	private static int max(int dart)
	{
		return CombatCalc.maxHitFromStrength(EFFECTIVE, BLOWPIPE + dart + GEAR);
	}

	@Test
	public void theBlazingBlowpipesSpecialIsToxicSiphon()
	{
		// Charged (28688, Eric's) and empty (28687). Before T-150 neither was in the table: RuneLite's variations map
		// 28688 to 28687, not to the Toxic blowpipe.
		assertSame(SpecialAttack.TOXIC_BLOWPIPE, SpecialAttack.forItem(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
		assertSame(SpecialAttack.TOXIC_BLOWPIPE, SpecialAttack.forItem(ItemID.TOXIC_BLOWPIPE_ORNAMENT));
		assertEquals(28688, ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT);
		final SpecialAttack s = SpecialAttack.forItem(28688);
		assertEquals("Toxic Siphon", s.getDisplayName());
		// Accuracy +100%: the attack roll doubled. Damage +50%: Adamant's 24 is 36.
		assertEquals(2.0, s.getAttackRollMultiplier(), 0);
		assertEquals(1000, s.scaleAttackRoll(500));
		assertEquals(36, s.maxTotal(24));
		assertFalse(s.alwaysHits());
		assertEquals(1, s.hits());
	}

	@Test
	public void itsMaxWithEachDart()
	{
		assertEquals(22, max(MITHRIL));
		assertEquals(24, max(ADAMANT));
		assertEquals(26, max(RUNE));
		assertEquals(26, max(AMETHYST));
		assertEquals(28, max(DRAGON));
		// Toxic Siphon's: half again.
		final SpecialAttack s = SpecialAttack.TOXIC_BLOWPIPE;
		assertEquals(33, s.maxTotal(max(MITHRIL)));
		assertEquals(36, s.maxTotal(max(ADAMANT)));
		assertEquals(39, s.maxTotal(max(RUNE)));
		assertEquals(42, s.maxTotal(max(DRAGON)));
		// The Hydra's [48190] 30, and 19:46's [1571] 33: over any dart's ordinary max (28 at most), within Adamant's
		// special. Both flew as the special's projectile.
		assertTrue(30 > max(DRAGON) + 1 && 30 <= s.maxTotal(max(ADAMANT)));
		assertTrue(33 <= s.maxTotal(max(ADAMANT)));
	}

	@Test
	public void theToxicBlowpipeIsUnchanged()
	{
		assertSame(SpecialAttack.TOXIC_BLOWPIPE, SpecialAttack.forItem(ItemID.TOXIC_BLOWPIPE_LOADED));
		assertSame(SpecialAttack.TOXIC_BLOWPIPE, SpecialAttack.forItem(ItemID.TOXIC_BLOWPIPE));
		assertEquals(36, SpecialAttack.TOXIC_BLOWPIPE.maxTotal(24));
		assertTrue(PvmPerformancePlugin.isDartBlowpipe(ItemID.TOXIC_BLOWPIPE_LOADED));
		assertTrue(PvmPerformancePlugin.isDartBlowpipe(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
		assertFalse(PvmPerformancePlugin.isDartBlowpipe(ItemID.BOW_OF_FAERDHINEN));
	}

	@Test
	public void theDartFromItsProjectile()
	{
		// Eric's ordinary blowpipe shots fly as 230 in every log, 10-28 to 11-01.
		assertSame(BlowpipeDart.ADAMANT, BlowpipeDart.fromProjectile(230));
		assertSame(BlowpipeDart.ADAMANT, BlowpipeDart.fromProjectile(SpotanimID.ADAMANT_DART_TRAVEL));
		assertSame(BlowpipeDart.MITHRIL, BlowpipeDart.fromProjectile(SpotanimID.MITHRIL_DART_TRAVEL));
		assertSame(BlowpipeDart.RUNE, BlowpipeDart.fromProjectile(SpotanimID.RUNE_DART_TRAVEL));
		assertSame(BlowpipeDart.AMETHYST, BlowpipeDart.fromProjectile(SpotanimID.AMETHYST_DART_TRAVEL));
		assertSame(BlowpipeDart.DRAGON, BlowpipeDart.fromProjectile(SpotanimID.II_DRAGON_DART_PROJANIM));
		assertSame(BlowpipeDart.BLACK, BlowpipeDart.fromProjectile(SpotanimID.BLACK_DART_TRAVEL));
		// The special's own projectile (the Blazing blowpipe's, 2599) and a bow's arrow (1926) name no dart.
		assertNull(BlowpipeDart.fromProjectile(SpotanimID.TOXIC_BLOWPIPE_SPECIALATTACK_LEAGUE04));
		assertNull(BlowpipeDart.fromProjectile(SpotanimID.TOXIC_BLOWPIPE_SPECIALATTACK));
		assertNull(BlowpipeDart.fromProjectile(1926));
	}

	@Test
	public void twoSiphonsBackToBackAreBothSpecials()
	{
		// The Hydra: thrown on 48185 and 48187, the energy falling on each throw, each seen two ticks on.
		final List<Integer> drops = new ArrayList<>(Arrays.asList(48185, 48187));
		// By the tick it was seen alone, the first claimed the second's fall, and the second found none.
		assertEquals(48187, PvmPerformancePlugin.unspentSpecialFor(drops, 48187));
		// By its throw: each its own.
		assertEquals(48185, PvmPerformancePlugin.unspentSpecialFor(drops, 48187, 48185));
		drops.remove(Integer.valueOf(48185));
		assertEquals(48187, PvmPerformancePlugin.unspentSpecialFor(drops, 48189, 48187));
	}

	@Test
	public void aLoneSiphonAndTheShotBeforeIt()
	{
		// A lone special, seen two ticks after its throw: no longer missed.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(100), 102));
		assertEquals(100, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(100), 102, 100));
		// An ordinary shot thrown on 100 and seen on 102, a special thrown on 102: the fall on 102 is the special's.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(102), 102, 100));
		assertEquals(102, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(102), 104, 102));
	}

	@Test
	public void meleeKeepsItsWindow()
	{
		// Burning claws: the fall on 45, the splat (and the booking) on 46, thrown on 45.
		assertEquals(45, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(45), 46, 45));
		assertEquals(46, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(46), 46, 45));
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.unspentSpecialFor(Arrays.asList(43), 46, 45));
	}

	@Test
	public void aStaleDartIsLogged()
	{
		final List<String> lines = new ArrayList<>();
		final MaxHitCheck check = new MaxHitCheck(lines::add);
		check.scored(null, 7, 10, 24, true, () -> "weapon Blazing blowpipe #28688",
			"Adamant (remembered for this account)");
		check.splat(7, 10, 30);
		check.endTick(10);
		assertEquals(Arrays.asList("MAX EXCEEDED 30 > 24 (weapon Blazing blowpipe #28688)",
			"BLOWPIPE dart may be stale: hit 30 > max 24 with Adamant (remembered for this account)"), lines);
		// Any other weapon: the MAX EXCEEDED alone.
		lines.clear();
		check.scored(null, 7, 11, 44, true, () -> "weapon Bow of faerdhinen", null);
		check.splat(7, 11, 46);
		check.endTick(11);
		assertEquals(1, lines.size());
		// A hit within the max: nothing.
		lines.clear();
		check.scored(null, 7, 12, 24, true, () -> "weapon Blazing blowpipe #28688", "Adamant (seen in flight, tick 5)");
		check.splat(7, 12, 24);
		check.endTick(12);
		assertTrue(lines.isEmpty());
	}
}

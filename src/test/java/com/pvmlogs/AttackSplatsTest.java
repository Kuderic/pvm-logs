package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-017 Part A: how many splats one attack puts on its target, per the OSRS
 * Wiki (read 2026-10-26; see AttackSplats), and the count kept as they land.
 */
public class AttackSplatsTest
{
	private static AttackSplats ordinary(int weapon)
	{
		return AttackSplats.of(weapon, false, 1, null);
	}

	private static AttackSplats special(int weapon)
	{
		return AttackSplats.of(weapon, true, 1, null);
	}

	@Test
	public void theScytheHitsByTheTargetsSize()
	{
		assertEquals(1, AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 1, null).count);
		assertEquals(2, AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 2, null).count);
		assertEquals(3, AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 3, null).count);
		assertEquals(3, AttackSplats.of(ItemID.SCYTHE_OF_VITUR_BL, false, 5, null).count);
		assertEquals(3, AttackSplats.of(ItemID.SCYTHE_OF_VITUR_OR, false, 4, null).count);
	}

	@Test
	public void theDualMacuahuitlHitsTwiceATickApart()
	{
		final AttackSplats maca = ordinary(ItemID.DUAL_MACUAHUITL);
		assertEquals(2, maca.count);
		assertEquals(1, maca.firstTick);
		assertTrue(maca.late);
		assertEquals(2, special(ItemID.DUAL_MACUAHUITL).count);
	}

	@Test
	public void theDarkBowFiresTwoArrowsOnOneTickAndTwoOnTheSpecial()
	{
		final AttackSplats bow = ordinary(ItemID.DARKBOW);
		assertEquals(2, bow.count);
		assertEquals(2, bow.firstTick);
		assertFalse(bow.late);
		assertEquals(2, ordinary(ItemID.DARKBOW_WHITE).count);
		assertEquals(2, special(ItemID.DARKBOW).count);
	}

	@Test
	public void theTwinflameStaffDoublesBoltsBlastsAndWavesOnly()
	{
		for (Spell spell : Arrays.asList(Spell.FIRE_BOLT, Spell.WATER_BLAST, Spell.EARTH_WAVE, Spell.WIND_WAVE))
		{
			final AttackSplats twin = AttackSplats.of(ItemID.TWINFLAME_STAFF, false, 1, spell);
			assertEquals(spell.name(), 2, twin.count);
			assertEquals(spell.name(), 1, twin.firstTick);
		}
		for (Spell spell : Arrays.asList(Spell.FIRE_STRIKE, Spell.FIRE_SURGE, Spell.ICE_BARRAGE, Spell.UNDEAD_GRASP))
		{
			assertEquals(spell.name(), 1, AttackSplats.of(ItemID.TWINFLAME_STAFF, false, 1, spell).count);
		}
		assertEquals(1, AttackSplats.of(ItemID.TWINFLAME_STAFF, false, 1, null).count);
		// Another staff casting a wave hits once.
		assertEquals(1, AttackSplats.of(ItemID.STAFF_OF_FIRE, false, 1, Spell.FIRE_WAVE).count);
	}

	@Test
	public void theOtherTwoHitWeapons()
	{
		for (int weapon : new int[]{ItemID.SULPHUR_BLADES, ItemID.GLACIAL_TEMOTLI, ItemID.EARTHBOUND_TECPATL,
			ItemID.BARROWS_TORAG_WEAPON, ItemID.BARROWS_TORAG_WEAPON_50})
		{
			final AttackSplats two = ordinary(weapon);
			assertEquals(String.valueOf(weapon), 2, two.count);
			assertFalse(String.valueOf(weapon), two.late);
		}
		assertEquals(2, ordinary(ItemID.INFERNAL_TECPATL).count);
		assertEquals(4, special(ItemID.INFERNAL_TECPATL).count);
		assertEquals(2, ordinary(ItemID.TONALZTICS_OF_RALOS_CHARGED).count);
		assertEquals(2, special(ItemID.TONALZTICS_OF_RALOS_CHARGED).count);
		assertEquals(1, ordinary(ItemID.TONALZTICS_OF_RALOS_UNCHARGED).count);
		assertEquals(1, special(ItemID.TONALZTICS_OF_RALOS_UNCHARGED).count);
	}

	@Test
	public void theMultiHitSpecials()
	{
		assertEquals(4, special(ItemID.DRAGON_CLAWS).count);
		assertEquals(4, special(ItemID.WILD_CAVE_WEBWEAVER_CHARGED).count);
		final AttackSplats burning = special(ItemID.BONE_CLAWS);
		assertEquals(3, burning.count);
		assertEquals(2, burning.firstTick);
		assertTrue(burning.late);
		for (int weapon : new int[]{ItemID.DRAGON_DAGGER, ItemID.DRAGON_DAGGER_P__, ItemID.ABYSSAL_DAGGER,
			ItemID.DRAGON_HALBERD, ItemID.CRYSTAL_HALBERD, ItemID.SARADOMIN_SWORD, ItemID.MAGIC_SHORTBOW,
			ItemID.MAGIC_SHORTBOW_I, ItemID.DRAGON_KNIFE, ItemID.DRAGON_KNIFE_P, ItemID.DINHS_BULWARK})
		{
			assertEquals(String.valueOf(weapon), 2, special(weapon).count);
			// Their ordinary attacks hit once.
			assertEquals(String.valueOf(weapon), 1, ordinary(weapon).count);
		}
		// The halberd's second hit is counted on a 1x1 too: the wiki says it "may be hit a second time".
		assertEquals(2, AttackSplats.of(ItemID.CRYSTAL_HALBERD, true, 1, null).count);
		assertEquals(2, AttackSplats.of(ItemID.CRYSTAL_HALBERD, true, 3, null).count);
	}

	@Test
	public void everythingElseHitsOnce()
	{
		for (int weapon : new int[]{ItemID.ABYSSAL_TENTACLE, ItemID.ABYSSAL_WHIP, ItemID.TOXIC_BLOWPIPE_LOADED,
			ItemID.AGS, ItemID.BLESSED_SARADOMIN_SWORD, ItemID.SOULREAPER, ItemID.OSMUMTENS_FANG, ItemID.VOIDWAKER, -1})
		{
			assertEquals(String.valueOf(weapon), 1, ordinary(weapon).count);
			assertEquals(String.valueOf(weapon), 1, special(weapon).count);
		}
	}

	@Test
	public void aOneHitBlowIsSpentByItsSplat()
	{
		// 19:34, 439: the tentacle's 0 landed, and a second 0 beside it is not the tentacle's.
		final AttackSplats.Burst tentacle = new AttackSplats.Burst(438, 439, AttackSplats.ONE, AttackSplats.ONE, false,
			"Abyssal tentacle");
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, tentacle.stateOn(439));
		assertFalse(tentacle.give(439));
		// The tick after is the cooldown's business, not the burst's.
		assertEquals(AttackSplats.State.NONE, tentacle.stateOn(440));
	}

	@Test
	public void aScytheOnALargeTargetHasThreeSplatsAndOnASmallOneOne()
	{
		final AttackSplats.Burst large = burst(AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 3, null), false);
		assertEquals(AttackSplats.State.ROOM_THIS_TICK, large.stateOn(101));
		assertTrue(large.give(101));
		assertTrue(large.give(101));
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, large.stateOn(101));
		assertFalse(large.give(101));
		assertEquals(4, large.given());

		final AttackSplats.Burst small = burst(AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 1, null), false);
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, small.stateOn(101));
	}

	@Test
	public void aClawSpecialHasFourThenTheFifthIsExtra()
	{
		final AttackSplats.Burst claws = burst(ordinary(ItemID.DRAGON_CLAWS), special(ItemID.DRAGON_CLAWS), true);
		assertTrue(claws.give(101));
		assertTrue(claws.give(101));
		assertTrue(claws.give(101));
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, claws.stateOn(101));
		// Split two and two across two ticks instead, the count is per attack.
		final AttackSplats.Burst split = burst(ordinary(ItemID.DRAGON_CLAWS), special(ItemID.DRAGON_CLAWS), true);
		assertTrue(split.give(101));
		assertEquals(AttackSplats.State.LATE_MAYBE, split.stateOn(102));
		assertTrue(split.give(102));
		assertEquals(AttackSplats.State.ROOM_THIS_TICK, split.stateOn(102));
		assertTrue(split.give(102));
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, split.stateOn(102));
		assertEquals(4, split.given());
	}

	@Test
	public void burningClawsThirdSplatIsDueTheTickAfter()
	{
		final AttackSplats.Burst burning = burst(AttackSplats.ONE, special(ItemID.BONE_CLAWS), true);
		assertTrue(burning.give(101));
		// Two on the first tick, so a third there is not the claws'...
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, burning.stateOn(101));
		// ...but on the next tick it is due.
		assertEquals(AttackSplats.State.LATE_DUE, burning.stateOn(102));
		assertTrue(burning.give(102));
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, burning.stateOn(102));
		assertEquals(AttackSplats.State.NONE, burning.stateOn(103));
	}

	@Test
	public void aDualMacuahuitlHasOneSplatATick()
	{
		final AttackSplats.Burst maca = burst(ordinary(ItemID.DUAL_MACUAHUITL), false);
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, maca.stateOn(101));
		assertEquals(AttackSplats.State.LATE_DUE, maca.stateOn(102));
		assertTrue(maca.give(102));
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, maca.stateOn(102));
	}

	@Test
	public void anAttackCountedOrdinaryCanTurnOutASpecial()
	{
		// The energy's varp can arrive after the first splat: until then the claws read as one hit.
		final AttackSplats.Burst claws = burst(AttackSplats.ONE, special(ItemID.BONE_CLAWS), false);
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, claws.stateOn(101));
		claws.markSpecial();
		assertEquals(AttackSplats.State.ROOM_THIS_TICK, claws.stateOn(101));
	}

	@Test
	public void aSplatTakenBackFreesItsPlace()
	{
		final AttackSplats.Burst scythe = burst(AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 2, null), false);
		scythe.give(101);
		assertEquals(AttackSplats.State.SPENT_THIS_TICK, scythe.stateOn(101));
		scythe.takeBack();
		assertEquals(AttackSplats.State.ROOM_THIS_TICK, scythe.stateOn(101));
	}

	@Test
	public void eitherSideOfASwitchTheLargerCountIsKept()
	{
		// Claws specced, then the tentacle back in hand before the splats land: the claws' four still count.
		final AttackSplats both = AttackSplats.atLeast(special(ItemID.ABYSSAL_TENTACLE), special(ItemID.DRAGON_CLAWS));
		assertEquals(4, both.count);
		assertEquals(4, both.firstTick);
		assertEquals(AttackSplats.ONE, AttackSplats.atLeast(AttackSplats.ONE, AttackSplats.ONE));
		assertEquals(AttackSplats.ONE, AttackSplats.atLeast(null, AttackSplats.ONE));
		final AttackSplats maca = AttackSplats.atLeast(AttackSplats.ONE, ordinary(ItemID.DUAL_MACUAHUITL));
		assertEquals(2, maca.count);
		assertEquals(1, maca.firstTick);
		assertTrue(maca.late);
	}

	@Test
	public void theEnergyFallingBesideTheThrowMakesItTheSpecial()
	{
		// Thrown on 45: the drop on 45 (the traced burning claws), or read a tick either side.
		assertTrue(AttackSplats.specialNear(Collections.singletonList(45), 45));
		assertTrue(AttackSplats.specialNear(Collections.singletonList(46), 45));
		assertTrue(AttackSplats.specialNear(Collections.singletonList(44), 45));
		// The attacks before and after a two-tick weapon's are not this one.
		assertFalse(AttackSplats.specialNear(Collections.singletonList(43), 45));
		assertFalse(AttackSplats.specialNear(Collections.singletonList(47), 45));
		assertFalse(AttackSplats.specialNear(Collections.emptyList(), 45));
		assertTrue(AttackSplats.specialNear(Arrays.asList(30, 45), 45));
	}

	private static AttackSplats.Burst burst(AttackSplats splats, boolean special)
	{
		return burst(splats, splats, special);
	}

	private static AttackSplats.Burst burst(AttackSplats ordinary, AttackSplats special, boolean isSpecial)
	{
		return new AttackSplats.Burst(100, 101, ordinary, special, isSpecial, "test");
	}
}

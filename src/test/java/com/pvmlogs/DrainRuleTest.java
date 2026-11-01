package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-049: each draining special's rule, on a hit and a miss, and every floor.
 * Figures by hand from the rules the wiki gives (sources in
 * claude/status/08-boss-stats.md), the DPS calculator's DefenceReduction.ts
 * agreeing.
 */
public class DrainRuleTest
{
	/** A boss of 200 Defence, 150 Magic, 200 Attack and Strength, 100 Ranged, +100 magic defence, no floor. */
	private static DrainRule.Target boss()
	{
		return target(0, false, false);
	}

	private static DrainRule.Target target(int floor, boolean demon, boolean tekton)
	{
		return new DrainRule.Target(200, 150, 100, 200, 200, 100, floor, 0, false, demon, tekton, false);
	}

	private static DrainRule.State fresh(DrainRule.Target t)
	{
		return DrainRule.State.of(t);
	}

	private static DrainRule.State hit(DrainRule.State s, SpecialWeapon w, int damage, DrainRule.Target t)
	{
		return DrainRule.apply(s, w, damage, damage > 0, t).after;
	}

	private static DrainRule.State miss(DrainRule.State s, SpecialWeapon w, DrainRule.Target t)
	{
		return DrainRule.apply(s, w, 0, false, t).after;
	}

	@Test
	public void warhammerTakesThirtyPercentOfWhatIsLeft()
	{
		final DrainRule.Target t = boss();
		DrainRule.State s = hit(fresh(t), SpecialWeapon.DRAGON_WARHAMMER, 34, t);
		assertEquals(140, s.defence);
		s = hit(s, SpecialWeapon.DRAGON_WARHAMMER, 1, t);
		// 30% of 140 is 42: by the current level, not the base.
		assertEquals(98, s.defence);
		assertEquals(150, s.magic);
		assertEquals(98, miss(s, SpecialWeapon.DRAGON_WARHAMMER, t).defence);
	}

	@Test
	public void elderMaulTakesThirtyFivePercent()
	{
		final DrainRule.Target t = boss();
		assertEquals(130, hit(fresh(t), SpecialWeapon.ELDER_MAUL, 50, t).defence);
		assertEquals(200, miss(fresh(t), SpecialWeapon.ELDER_MAUL, t).defence);
	}

	/** Tekton: a warhammer or maul that misses still takes 5%, a godsword 10 (RuneLite's SpecialWeapon). */
	@Test
	public void tektonDrainsOnAMiss()
	{
		final DrainRule.Target t = target(0, false, true);
		assertEquals(190, miss(fresh(t), SpecialWeapon.DRAGON_WARHAMMER, t).defence);
		assertEquals(190, miss(fresh(t), SpecialWeapon.ELDER_MAUL, t).defence);
		assertEquals(190, miss(fresh(t), SpecialWeapon.BANDOS_GODSWORD, t).defence);
		assertEquals(200, miss(fresh(t), SpecialWeapon.ARCLIGHT, t).defence);
	}

	@Test
	public void godswordDrainsItsDamage()
	{
		final DrainRule.Target t = boss();
		assertEquals(150, hit(fresh(t), SpecialWeapon.BANDOS_GODSWORD, 50, t).defence);
		assertEquals(200, miss(fresh(t), SpecialWeapon.BANDOS_GODSWORD, t).defence);
	}

	/** What is left once Defence reaches 0 runs on to Strength, then Attack, Magic and Ranged, and stops at the first it leaves above 0. */
	@Test
	public void godswordOverflowsInOrder()
	{
		final DrainRule.Target t = boss();
		DrainRule.State s = hit(fresh(t), SpecialWeapon.BANDOS_GODSWORD, 250, t);
		assertEquals(0, s.defence);
		assertEquals(150, s.strength);
		assertEquals(200, s.attack);
		s = hit(fresh(t), SpecialWeapon.BANDOS_GODSWORD, 700, t);
		assertEquals(0, s.defence);
		assertEquals(0, s.strength);
		assertEquals(0, s.attack);
		assertEquals(50, s.magic);
		assertEquals(100, s.ranged);
		s = hit(fresh(t), SpecialWeapon.BANDOS_GODSWORD, 800, t);
		assertEquals(0, s.magic);
		assertEquals(50, s.ranged);
	}

	/** A floor stops the overflow too: Nex keeps 250 of her 260, and the rest of a 40 goes nowhere. */
	@Test
	public void aFloorStopsTheGodsword()
	{
		final DrainRule.Target nex = new DrainRule.Target(260, 230, 300, 315, 200, 100, 250, 0, false, false, false,
			false);
		final DrainRule.Result r = DrainRule.apply(fresh(nex), SpecialWeapon.BANDOS_GODSWORD, 40, true, nex);
		assertEquals(250, r.after.defence);
		assertEquals(200, r.after.strength);
		assertTrue(r.floored);
	}

	/** The Corporeal Beast, and Pestilent Bloat while it is up, take twice a godsword's damage. */
	@Test
	public void godswordDoubled()
	{
		final DrainRule.Target corp = new DrainRule.Target(310, 350, 150, 320, 320, 1, 0, 0, false, false, false, true);
		assertEquals(250, hit(fresh(corp), SpecialWeapon.BANDOS_GODSWORD, 30, corp).defence);
	}

	/** Arclight: 5% of the BASE, plus 1, off Attack, Strength and Defence; 10% on a demon. */
	@Test
	public void arclightTakesAShareOfTheBase()
	{
		final DrainRule.Target t = boss();
		DrainRule.State s = hit(fresh(t), SpecialWeapon.ARCLIGHT, 20, t);
		assertEquals(189, s.defence);
		assertEquals(189, s.attack);
		assertEquals(189, s.strength);
		// The base, not what is left: the same 11 again.
		s = hit(s, SpecialWeapon.ARCLIGHT, 20, t);
		assertEquals(178, s.defence);
		assertEquals(200, miss(fresh(t), SpecialWeapon.ARCLIGHT, t).defence);
		final DrainRule.Target demon = target(0, true, false);
		assertEquals(179, hit(fresh(demon), SpecialWeapon.ARCLIGHT, 20, demon).defence);
		assertEquals(179, hit(fresh(demon), SpecialWeapon.DARKLIGHT, 20, demon).defence);
	}

	/** Emberlight: 5% plus 1, 15% plus 1 on a demon. */
	@Test
	public void emberlightOnADemon()
	{
		final DrainRule.Target t = boss();
		assertEquals(189, hit(fresh(t), SpecialWeapon.EMBERLIGHT, 20, t).defence);
		final DrainRule.Target demon = target(0, true, false);
		assertEquals(169, hit(fresh(demon), SpecialWeapon.EMBERLIGHT, 20, demon).defence);
	}

	@Test
	public void anchorTakesATenthOfItsDamage()
	{
		final DrainRule.Target t = boss();
		assertEquals(196, hit(fresh(t), SpecialWeapon.BARRELCHEST_ANCHOR, 45, t).defence);
		assertEquals(200, miss(fresh(t), SpecialWeapon.BARRELCHEST_ANCHOR, t).defence);
		assertEquals(200, hit(fresh(t), SpecialWeapon.BARRELCHEST_ANCHOR, 9, t).defence);
	}

	/**
	 * The anchor drains one stat: "if Defence is already on 1, it drains Attack,
	 * etc." (Mod Ash, wiki), in the order Defence, Attack, Ranged, Magic. Here
	 * Defence is at its floor.
	 */
	@Test
	public void anchorMovesOnOnceDefenceIsAtItsFloor()
	{
		final DrainRule.Target t = target(200, false, false);
		DrainRule.State s = hit(fresh(t), SpecialWeapon.BARRELCHEST_ANCHOR, 50, t);
		assertEquals(200, s.defence);
		assertEquals(195, s.attack);
		s = new DrainRule.State(200, 150, 100, 0, 200, 100);
		assertEquals(95, hit(s, SpecialWeapon.BARRELCHEST_ANCHOR, 50, t).ranged);
		s = new DrainRule.State(200, 150, 100, 0, 200, 0);
		assertEquals(145, hit(s, SpecialWeapon.BARRELCHEST_ANCHOR, 50, t).magic);
	}

	/** Regeneration: each drained level gets one back, never past its base; the magic defence bonus does not. */
	@Test
	public void regenerationGivesALevelBack()
	{
		final DrainRule.Target t = boss();
		final DrainRule.State s = new DrainRule.State(140, 150, 60, 190, 200, 99);
		final DrainRule.State up = DrainRule.regenerate(s, t);
		assertEquals(141, up.defence);
		assertEquals(150, up.magic);
		assertEquals(60, up.magicDefence);
		assertEquals(191, up.attack);
		assertEquals(200, up.strength);
		assertEquals(100, up.ranged);
		assertEquals(fresh(t), DrainRule.regenerate(fresh(t), t));
	}

	/** The bone dagger and Dorgeshuun crossbow drain their damage, only from an undrained Defence. */
	@Test
	public void boneWeaponsDrainOnlyAnUntouchedDefence()
	{
		final DrainRule.Target t = boss();
		final DrainRule.State once = hit(fresh(t), SpecialWeapon.BONE_DAGGER, 30, t);
		assertEquals(170, once.defence);
		final DrainRule.Result again = DrainRule.apply(once, SpecialWeapon.DORGESHUUN_CROSSBOW, 30, true, t);
		assertFalse(again.changed(once));
		assertEquals(185, hit(fresh(t), SpecialWeapon.DORGESHUUN_CROSSBOW, 15, t).defence);
	}

	/**
	 * The Accursed sceptre takes Defence and Magic to 85% of their base and no
	 * further: "an 80 cannot have their stats drained lower than 68" (wiki).
	 */
	@Test
	public void accursedSceptreDoesNotStack()
	{
		final DrainRule.Target t = boss();
		final DrainRule.State once = hit(fresh(t), SpecialWeapon.ACCURSED_SCEPTRE, 30, t);
		assertEquals(170, once.defence);
		assertEquals(127, once.magic);
		assertFalse(DrainRule.apply(once, SpecialWeapon.ACCURSED_SCEPTRE, 30, true, t).changed(once));
		// After a hammer (200 -> 140) Defence is already under 170: only Magic moves.
		final DrainRule.State hammered = hit(fresh(t), SpecialWeapon.DRAGON_WARHAMMER, 30, t);
		final DrainRule.State cursed = hit(hammered, SpecialWeapon.ACCURSED_SCEPTRE, 30, t);
		assertEquals(140, cursed.defence);
		assertEquals(127, cursed.magic);
		// Part way back up (180), it takes it to 170 again.
		final DrainRule.State partWay = new DrainRule.State(180, 150, 100, 200, 200, 100);
		assertEquals(170, hit(partWay, SpecialWeapon.ACCURSED_SCEPTRE, 1, t).defence);
	}

	/** The wiki's own example: an 80 goes no lower than 68. */
	@Test
	public void accursedSceptreWikiExample()
	{
		final DrainRule.Target t = new DrainRule.Target(80, 80, 0, 80, 80, 80, 0, 0, false, false, false, false);
		final DrainRule.State s = hit(DrainRule.State.of(t), SpecialWeapon.ACCURSED_SCEPTRE, 5, t);
		assertEquals(68, s.defence);
		assertEquals(68, s.magic);
		assertEquals(68, hit(s, SpecialWeapon.ACCURSED_SCEPTRE, 5, t).defence);
	}

	/** Each Tonalztics glaive that lands takes an eighth of the current Magic off Defence: 150 / 8 is 18. */
	@Test
	public void tonalzticsGlaives()
	{
		final DrainRule.Target t = boss();
		assertEquals(164, DrainRule.apply(fresh(t), SpecialWeapon.TONALZTICS_OF_RALOS, 2, true, t).after.defence);
		assertEquals(182, DrainRule.apply(fresh(t), SpecialWeapon.TONALZTICS_OF_RALOS, 1, true, t).after.defence);
		assertEquals(200, DrainRule.apply(fresh(t), SpecialWeapon.TONALZTICS_OF_RALOS, 0, false, t).after.defence);
	}

	@Test
	public void seercullTakesItsDamageOffMagic()
	{
		final DrainRule.Target t = boss();
		final DrainRule.State s = hit(fresh(t), SpecialWeapon.SEERCULL, 30, t);
		assertEquals(120, s.magic);
		assertEquals(200, s.defence);
		// It only drains a Magic level that has not been lowered.
		assertEquals(120, hit(s, SpecialWeapon.SEERCULL, 30, t).magic);
		// Back to its base, it can be drained again.
		final DrainRule.State restored = new DrainRule.State(200, 150, 100, 200, 200, 100);
		assertEquals(120, hit(restored, SpecialWeapon.SEERCULL, 30, t).magic);
	}

	/**
	 * The Eye of ayak takes its damage off the magic defence bonus. T-141: the state keeps the whole reduction, which
	 * meets whatever bonus the target has then, to no lower than 0, a bonus at or below 0 left alone (OSRS Wiki, Eye of
	 * ayak, read 2026-11-01).
	 */
	@Test
	public void eyeOfAyakLowersTheMagicDefenceBonus()
	{
		final DrainRule.Target t = boss();
		DrainRule.State s = hit(fresh(t), SpecialWeapon.EYE_OF_AYAK, 40, t);
		assertEquals(60, s.magicDefence);
		assertEquals(40, DrainRule.magicDefenceReduction(s, t));
		assertEquals(60, DrainRule.magicDefenceBonus(t.magicDefence, DrainRule.magicDefenceReduction(s, t)));
		s = hit(s, SpecialWeapon.EYE_OF_AYAK, 70, t);
		assertEquals(110, DrainRule.magicDefenceReduction(s, t));
		assertEquals(0, DrainRule.magicDefenceBonus(t.magicDefence, DrainRule.magicDefenceReduction(s, t)));
		final DrainRule.Target negative = new DrainRule.Target(100, 190, -30, 300, 150, 1, 0, 0, false, false, false,
			false);
		final DrainRule.State n = hit(fresh(negative), SpecialWeapon.EYE_OF_AYAK, 40, negative);
		assertEquals(40, DrainRule.magicDefenceReduction(n, negative));
		assertEquals(-30, DrainRule.magicDefenceBonus(-30, 40));
		// A miss, or a hit of 0, takes nothing.
		assertEquals(0, DrainRule.magicDefenceReduction(DrainRule.apply(fresh(t), SpecialWeapon.EYE_OF_AYAK, 0, true, t)
			.after, t));
		assertEquals(0, DrainRule.magicDefenceReduction(DrainRule.apply(fresh(t), SpecialWeapon.EYE_OF_AYAK, 30, false, t)
			.after, t));
	}

	/** T-141: the wiki's own example: 50 to Yama in his melee stance (-30) leaves -30; his magic stance then +10, +31. */
	@Test
	public void theWikisYamaExample()
	{
		final DrainRule.Target yama = new DrainRule.Target(225, 250, 0, 250, 250, 250, 145, 0, false, true, false, false,
			100);
		final DrainRule.State s = hit(fresh(yama), SpecialWeapon.EYE_OF_AYAK, 50, yama);
		final int reduction = DrainRule.magicDefenceReduction(s, yama);
		assertEquals(50, reduction);
		assertEquals(-30, DrainRule.magicDefenceBonus(0 - 30, reduction));
		assertEquals(10, DrainRule.magicDefenceBonus(0 + 60, reduction));
		assertEquals(31, DrainRule.magicDefenceBonus(81, reduction));
		// It never comes back.
		assertEquals(s.magicDefence, DrainRule.regenerate(s, yama).magicDefence);
	}

	@Test
	public void bulwarkDrainsNothing()
	{
		final DrainRule.Target t = boss();
		assertFalse(DrainRule.drains(SpecialWeapon.BULWARK));
		assertFalse(DrainRule.apply(fresh(t), SpecialWeapon.BULWARK, 30, true, t).changed(fresh(t)));
		assertFalse(DrainRule.drains(null));
	}

	@Test
	public void aDefenceFloorHolds()
	{
		// Sotetseg: 200 Defence, 100 its floor.
		final DrainRule.Target sotetseg = target(100, false, false);
		DrainRule.State s = hit(fresh(sotetseg), SpecialWeapon.DRAGON_WARHAMMER, 30, sotetseg);
		s = hit(s, SpecialWeapon.DRAGON_WARHAMMER, 30, sotetseg);
		assertEquals(100, hit(s, SpecialWeapon.DRAGON_WARHAMMER, 30, sotetseg).defence);
		final DrainRule.Result atFloor = DrainRule.apply(new DrainRule.State(100, 150, 100, 200, 200, 100),
			SpecialWeapon.DRAGON_WARHAMMER, 30, true, sotetseg);
		assertFalse(atFloor.changed(new DrainRule.State(100, 150, 100, 200, 200, 100)));
		assertTrue(atFloor.floored);
	}

	/** Immune (Verzik, Vardorvis): nothing drains, and the result says a floor stopped it. */
	@Test
	public void immuneDrainsNothing()
	{
		final DrainRule.Target verzik = new DrainRule.Target(150, 300, 100, 400, 400, 1, 0, 0, true, false, false,
			false);
		final DrainRule.Result r = DrainRule.apply(fresh(verzik), SpecialWeapon.DRAGON_WARHAMMER, 30, true, verzik);
		assertFalse(r.changed(fresh(verzik)));
		assertTrue(r.floored);
		assertEquals(150, verzik.defenceFloor);
		assertEquals(300, verzik.magicFloor);
	}

	/** The rule is pure: the state handed in is never changed, so T-050 can run outcomes that did not happen. */
	@Test
	public void theRuleTouchesNothing()
	{
		final DrainRule.Target t = boss();
		final DrainRule.State s = fresh(t);
		DrainRule.apply(s, SpecialWeapon.BANDOS_GODSWORD, 700, true, t);
		assertEquals(fresh(t), s);
		assertEquals(fresh(t).hashCode(), s.hashCode());
	}
}

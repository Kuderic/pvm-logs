package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-160, from Eric's duo Yama of 2026-11-02 (build 1e73c42, read in place, nothing copied): two Soul Rends booked as
 * splashes (no experience on their throw tick or after) drained on the thrall's splat at their landing tick: [16977]
 * "hit 3 (landed) ... reduced by 22 -> 25", the thrall's 3 the same tick; [17332] the thrall's 1.
 */
public class SplashedSpecialThrallSplatTest
{
	private static final int YAMA = 63869;
	private static final int YAMA_ID = 14176;
	private static final DrainRule.Target TARGET = new DrainRule.Target(225, 250, 0, 250, 250, 250, 145, 0, false,
		true, false, false, 100);

	private final List<String> lines = new ArrayList<>();
	private final DrainTracker tracker = new DrainTracker(lines::add);

	@Test
	public void aSplashedSpecialWithAThrallSplatAtItsLandingTickDrainsNothing()
	{
		// [16972]: thrown, no hitpoints experience on its tick; due 16977.
		tracker.ownThrow(16972, SpecialWeapon.EYE_OF_AYAK, YAMA, YAMA_ID, "Yama", 16977, TARGET, false);
		// [16973]: booked as a splash.
		tracker.ownSplashed(YAMA, 16972);
		// [16977]: the thrall's 3, which reaches the tracker as the player's (isMine) before the thrall is weighed.
		tracker.ownSplat(YAMA, 16977, 3);
		tracker.tick(16977);
		final DrainEvent e = tracker.tracked(YAMA).events.get(0);
		assertFalse(e.landed);
		assertEquals(0, e.hit);
		assertEquals(0, DrainRule.magicDefenceReduction(tracker.tracked(YAMA).state, TARGET));
		assertTrue(lines.get(0), lines.get(0).contains("hit 0 (missed)"));
	}

	@Test
	public void theThrallsSplatTakenBackLeavesASplashToMissEvenUnbooked()
	{
		// [17327]: the same, the thrall's 1 judged the thrall's and taken back, with no booking from the plugin.
		tracker.ownThrow(17327, SpecialWeapon.EYE_OF_AYAK, YAMA, YAMA_ID, "Yama", 17332, TARGET, false);
		tracker.ownSplat(YAMA, 17332, 1);
		tracker.notOwnSplat(YAMA, 17332, 1);
		tracker.tick(17332);
		tracker.tick(17333);
		final DrainEvent e = tracker.tracked(YAMA).events.get(0);
		assertFalse(e.landed);
		assertEquals(0, e.hit);
	}

	@Test
	public void aLandedSpecialWithAThrallSplatBesideItDrainsItsOwnHit()
	{
		// [16113]: Magic experience on its throw tick for 20, its 20 landing five ticks on - with the thrall's 3 after it.
		tracker.ownThrow(16113, SpecialWeapon.EYE_OF_AYAK, YAMA, YAMA_ID, "Yama", 16118, TARGET, true);
		tracker.ownSplat(YAMA, 16118, 20);
		tracker.ownSplat(YAMA, 16118, 3);
		tracker.notOwnSplat(YAMA, 16118, 3);
		tracker.tick(16118);
		final DrainEvent e = tracker.tracked(YAMA).events.get(0);
		assertTrue(e.landed);
		assertEquals(20, e.hit);
		assertEquals(20, DrainRule.magicDefenceReduction(tracker.tracked(YAMA).state, TARGET));
	}

	@Test
	public void theSplashCheckSaysWhatLandedInsteadOfIt()
	{
		final SplashCheck check = new SplashCheck();
		check.watch("Soul Rend", YAMA, 16972);
		// Before its window, and another npc's: not in it.
		check.splat(YAMA, 16974, 9, 17, "others'");
		check.splat(YAMA + 1, 16976, 40, 16, "mine");
		check.splat(YAMA, 16976, 52, 17, "others'");
		check.splat(YAMA, 16977, 3, 16, "mine");
		check.thrall(YAMA, 16977, 3);
		assertTrue(check.due(16979).isEmpty());
		assertEquals(Arrays.asList("SPLASH CHECK Soul Rend thrown 16972: others' 52 (type 17) at +4; thrall 3 (type 16) at "
			+ "+5; none of mine"), check.due(16980));
		assertTrue(check.due(16981).isEmpty());
		// One of mine in its window is named, and "none of mine" isn't said.
		check.watch("Soul Rend", YAMA, 100);
		check.splat(YAMA, 105, 20, 16, "mine");
		assertEquals(Arrays.asList("SPLASH CHECK Soul Rend thrown 100: mine 20 (type 16) at +5"), check.due(108));
	}
}

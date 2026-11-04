package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;

/**
 * T-168: the Purging staff's special, Scatter ashes ("uses the best demonbane spell the player can cast", OSRS Wiki,
 * Purging staff), at a Void Flare, which is always max hit for magic (OSRS Wiki, Void flare; the DPS calc's
 * ALWAYS_MAX_HIT_MONSTERS, which takes in specials). Eric's 8 (2026-11-04 01:54 log) were scored as rolled specials,
 * max 77 at 0.91, avg about 35, where each killed its flare: 71 on 71 left, 60 on 60.
 */
public class ScatterAshesTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int VOID_FLARE = 14179;
	private static final int JUDGE = 14180;
	private static final int YAMA = 14176;
	private static final int INDEX = 9;

	private static List<BossRules.ModifierRule> specialMods(int npcId, String style)
	{
		final List<BossRules.ModifierRule> mods = new ModifierTracker(null).applying(INDEX, RULES.forNpc(npcId).damageModifiers,
			npcId, style, 100, 100, false, (id, range) -> false);
		return ModifierTracker.forSpecials(mods);
	}

	@Test
	public void scatterAshesOnAVoidFlareIsTheCertainMaxCappedAtTheHpLeft()
	{
		final List<BossRules.ModifierRule> mods = specialMods(VOID_FLARE, "MAGIC");
		assertEquals(1, mods.size());
		assertEquals(1.0, ModifierTracker.accuracy(mods, 0.91), 0);
		// 71 left of a flare's 140: the 71 it dealt, every time, no variance.
		final DamageMoments at71 = ModifierTracker.moments(mods, new int[]{77}, ModifierTracker.accuracy(mods, 0.91), 71);
		assertEquals(71, at71.mean, 1e-12);
		assertEquals(0, at71.variance(), 1e-9);
		// The one with 60 left: 60.
		assertEquals(60, ModifierTracker.moments(mods, new int[]{77}, 1.0, 60).mean, 1e-12);
		// Uncapped, the max: 77.
		assertEquals(77, ModifierTracker.moments(mods, new int[]{77}, 1.0, Integer.MAX_VALUE).mean, 1e-12);
		// Not for a melee special at the flare: its magic rule is magic only.
		assertTrue(specialMods(VOID_FLARE, "MELEE").isEmpty());
	}

	@Test
	public void anySpecialOnTheJudgeIsItsCertainMax()
	{
		final List<BossRules.ModifierRule> mods = specialMods(JUDGE, "MAGIC");
		assertEquals(1, mods.size());
		assertEquals(1.0, ModifierTracker.accuracy(mods, 0.5), 0);
		assertEquals(41, ModifierTracker.moments(mods, new int[]{41}, 1.0, 400).mean, 1e-12);
		assertEquals(1, specialMods(JUDGE, "MELEE").size());
	}

	@Test
	public void scatterAshesOnYamaHerselfIsStillRolled()
	{
		// Yama's own modifiers take no specials: her special is scored by its own roll, as before.
		for (String style : new String[]{"MAGIC", "MELEE", "RANGED"})
		{
			assertTrue(style, specialMods(YAMA, style).isEmpty());
		}
		// The rolled special's figures (the log's max 77 at 0.91) are the roll's, about 35.
		final DamageMoments rolled = CombatCalc.specMoments(new int[]{77}, 0.91, 0, 0, Integer.MAX_VALUE, false);
		assertEquals(35.1, rolled.mean, 0.1);
		assertTrue(rolled.variance() > 500);
	}

	@Test
	public void aRefundBooksNothing()
	{
		// The Purging staff's refund on a kill brings the energy back up: not a special.
		assertFalse(PvmPerformancePlugin.specialFired(75, 100));
		assertFalse(PvmPerformancePlugin.specialFired(50, 75));
		// The next Scatter ashes is a fall from the refunded energy, booked as usual.
		assertTrue(PvmPerformancePlugin.specialFired(100, 75));
		// A first reading can't say; no change is nothing.
		assertFalse(PvmPerformancePlugin.specialFired(-1, 75));
		assertFalse(PvmPerformancePlugin.specialFired(75, 75));
	}
}

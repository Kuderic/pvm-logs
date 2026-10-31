package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** T-056: a hit above its attack's expected max is logged and counted; specials and multi-hits are not checked. */
public class MaxHitCheckTest
{
	private final List<String> lines = new ArrayList<>();
	private final MaxHitCheck check = new MaxHitCheck(lines::add);
	private final Fight fight = new Fight("The Leviathan", 12214, 61375, 900, 0L, null, 0);

	/** The Leviathan's 41 at [1575] against a max of 39. */
	@Test
	public void aHitOverTheMaxIsLoggedAndCounted()
	{
		check.splat(61375, 1575, 41);
		check.scored(fight, 61375, 1575, 39, true, () -> "weapon Bow of faerdhinen (c) #25867");
		check.endTick(1575);
		assertEquals(1, check.exceeded(fight));
		assertEquals(1, lines.size());
		assertEquals("MAX EXCEEDED 41 > 39 (weapon Bow of faerdhinen (c) #25867)", lines.get(0));
	}

	/** A melee blow is scored before its splat counts here; a projectile's splat comes first. Either order pairs. */
	@Test
	public void eitherOrderPairs()
	{
		check.scored(fight, 61375, 10, 30, true, () -> "melee");
		check.splat(61375, 10, 31);
		check.splat(61375, 11, 40);
		check.scored(fight, 61375, 11, 39, true, () -> "bow");
		check.endTick(11);
		assertEquals(2, check.exceeded(fight));
	}

	/**
	 * T-135: a shot booked from its drop (UNSEEN SHOT) is scored at the tick's end, after the hitsplats; settled before
	 * that, as the plugin did, its splat was gone. Eric's 14:02 log at Yama, [7591]: a 60 against max 39, never flagged,
	 * nor any of the 33 over 39. The plugin now settles last.
	 */
	@Test
	public void aShotScoredAtTheTicksEndMeetsItsSplat()
	{
		final Fight yama = new Fight("Yama", 14176, 60992, 2500, 0L, null, 0);
		// Before: the tick settled, then the shot scored.
		check.splat(60992, 7591, 60);
		check.endTick(7591);
		check.scored(yama, 60992, 7591, 39, true, () -> "Purging staff");
		check.splat(60992, 7596, 58);
		check.endTick(7596);
		assertEquals(0, check.exceeded(yama));
		// Now: scored, then settled.
		check.splat(60992, 7601, 61);
		check.scored(yama, 60992, 7601, 39, true, () -> "Purging staff");
		check.endTick(7601);
		assertEquals(1, check.exceeded(yama));
		assertEquals("MAX EXCEEDED 61 > 39 (Purging staff)", lines.get(lines.size() - 1));
	}

	@Test
	public void atOrUnderTheMaxIsQuiet()
	{
		check.splat(61375, 5, 39);
		check.scored(fight, 61375, 5, 39, true, () -> "bow");
		check.splat(61375, 6, 0);
		check.scored(fight, 61375, 6, 39, true, () -> "bow");
		check.endTick(6);
		assertEquals(0, check.exceeded(fight));
		assertTrue(lines.isEmpty());
	}

	/** Specials and multi-hit weapons are left out: their max is not one splat's. */
	@Test
	public void specialsAndMultiHitsAreNotChecked()
	{
		check.splat(61375, 5, 70);
		check.scored(fight, 61375, 5, 39, false, () -> "a special");
		check.endTick(5);
		assertEquals(0, check.exceeded(fight));
		assertTrue(lines.isEmpty());
	}

	/** Only the attack's own npc: a splat on something else that tick is not compared. */
	@Test
	public void anotherNpcsSplatIsNotCompared()
	{
		check.splat(7, 5, 80);
		check.scored(fight, 61375, 5, 39, true, () -> "bow");
		check.endTick(5);
		assertEquals(0, check.exceeded(fight));
	}

	/** A splat with no attack scored on its tick (a thrall, an add's AoE) is not compared with anything. */
	@Test
	public void aTickWithNoScoredAttackIsQuiet()
	{
		check.splat(61375, 5, 80);
		check.endTick(5);
		check.scored(fight, 61375, 6, 39, true, () -> "bow");
		check.endTick(6);
		assertEquals(0, check.exceeded(fight));
		check.forget(fight);
		assertEquals(0, check.exceeded(fight));
	}
}

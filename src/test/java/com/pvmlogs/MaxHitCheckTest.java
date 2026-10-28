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

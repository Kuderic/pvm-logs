package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The cap is the most that can be taken <em>off</em>, not the level draining
 * stops at. Getting that backwards would invert every entry silently, so it is
 * the first thing pinned down here.
 */
public class DefenceDrainCapTest
{
	@Test
	public void aCapIsAnAmountRemovedAndNotAFloor()
	{
		// The wiki's own example: a cap of 50 against 250 leaves 200.
		// Doom is the case in the table: 30 off a defence of 90 leaves 60.
		assertEquals(30, DefenceDrainCap.maxDrain(NpcIds.DOOM, 90));
		assertEquals(60, DefenceDrainCap.floor(NpcIds.DOOM, 90));
	}

	@Test
	public void anImmuneTargetLosesNothing()
	{
		assertTrue(DefenceDrainCap.isImmune(NpcIds.VERZIK_P1));
		assertEquals(0, DefenceDrainCap.maxDrain(NpcIds.VERZIK_P1, 20));
		assertEquals(20, DefenceDrainCap.floor(NpcIds.VERZIK_P1, 20));
	}

	@Test
	public void everythingElseDrainsToNothing()
	{
		assertFalse(DefenceDrainCap.isImmune(NpcIds.GOBLIN));
		assertEquals(150, DefenceDrainCap.maxDrain(NpcIds.GOBLIN, 150));
		assertEquals(0, DefenceDrainCap.floor(NpcIds.GOBLIN, 150));
	}

	@Test
	public void aCapNeverTakesMoreThanIsThere()
	{
		// A target whose defence is already below the cap cannot go negative.
		assertEquals(10, DefenceDrainCap.maxDrain(NpcIds.DOOM, 10));
		assertEquals(0, DefenceDrainCap.floor(NpcIds.DOOM, 10));
	}

	/**
	 * The DPS calculator floors Verzik's defence at its current value, like
	 * Vardorvis's (weirdgloop/osrs-dps-calc@c446f2e, DefenceReduction.ts, VERZIK_IDS
	 * in constants.ts). Every one of its ids is immune here, except the three
	 * phase transitions, which cannot be attacked.
	 */
	@Test
	public void verzikMatchesTheCalculatorsFloor()
	{
		final int[] calc = {10830, 10831, 10832, 8369, 8370, 8371, 10847, 10848, 10849, 10833, 10834, 10835, 8372, 8373,
			8374, 10850, 10851, 10852};
		final java.util.Set<Integer> transitions = new java.util.HashSet<>(java.util.Arrays.asList(
			net.runelite.api.gameval.NpcID.VERZIK_PHASE1_TO2_TRANSITION,
			net.runelite.api.gameval.NpcID.VERZIK_PHASE2_TO3_TRANSITION,
			net.runelite.api.gameval.NpcID.VERZIK_PHASE1_TO2_TRANSITION_STORY,
			net.runelite.api.gameval.NpcID.VERZIK_PHASE2_TO3_TRANSITION_STORY,
			net.runelite.api.gameval.NpcID.VERZIK_PHASE1_TO2_TRANSITION_HARD,
			net.runelite.api.gameval.NpcID.VERZIK_PHASE2_TO3_TRANSITION_HARD));
		for (int id : calc)
		{
			if (!transitions.contains(id))
			{
				assertTrue("npc " + id, DefenceDrainCap.isImmune(id));
				assertEquals(200, DefenceDrainCap.floor(id, 200));
			}
		}
		assertEquals(6, transitions.size());
	}
}

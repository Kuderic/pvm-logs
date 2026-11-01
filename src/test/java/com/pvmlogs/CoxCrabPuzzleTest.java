package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-148, from Eric's Chambers of Xeric raid of 2026-11-01 13:02 (build 8201462, read in place, nothing copied): a
 * Dragon warhammer on a Jewelled Crab, the crab puzzle, opened a fight on it ([5402]-[5553]) with no stats, nothing
 * dealt, 111 lost ticks and 56 taken, saved to history.
 */
public class CoxCrabPuzzleTest
{
	@Test
	public void aHitOnAJewelledCrabOpensNoFight()
	{
		// Every colour: it changes id as it changes colour ([5408]: 7576, the grey, under the red's fight). The ignored
		// list is asked by every way a fight opens - the player's hitsplat, the target switch and isFightable - so none
		// opens, and no lost tick or hit taken is counted against one.
		for (int id : new int[]{NpcID.RAIDS_LASERCRABS_CRAB_GREY, NpcID.RAIDS_LASERCRABS_CRAB_RED,
			NpcID.RAIDS_LASERCRABS_CRAB_GREEN, NpcID.RAIDS_LASERCRABS_CRAB_BLUE})
		{
			assertTrue("npc " + id, EncounterGroup.isIgnored(id));
		}
		// The energy focuses aren't listed: no source says they can't be attacked.
		assertFalse(EncounterGroup.isIgnored(NpcID.RAIDS_LASERCRABS_ENERGY_WHITE));
		// The raid's fights around it are untouched.
		assertFalse(EncounterGroup.isIgnored(NpcID.RAIDS_STONEGUARDIANS_LEFT));
		assertFalse(EncounterGroup.isIgnored(NpcID.RAIDS_ICEFIEND));
	}

	@Test
	public void aFightOnAMonsterWithNoStatsStillCountsWhatLanded()
	{
		// A real fight the data lacks keeps today's NO MONSTER STATS fight: not ignored, scored, counting its damage.
		final int unknown = 99_999;
		assertFalse(EncounterGroup.isIgnored(unknown));
		assertNull(EncounterGroup.of(unknown));
		final Fight fight = new Fight("Something new", unknown, 1, -1, 0L, null, 0);
		assertTrue(fight.isScored());
		fight.recordAttackMade(false);
		fight.recordDamageDealt(25, 600L, true);
		assertEquals(1, fight.getAttempts());
		assertEquals(25, fight.getDamageDealt());
	}
}

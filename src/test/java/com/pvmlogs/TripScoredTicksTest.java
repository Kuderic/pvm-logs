package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

/**
 * The trip's scored ticks (T-024): fed beside its other tick counts, but only
 * for a scored fight, so whole-trip luck and DPS divide the scored fights'
 * damage by the scored fights' time. Each step here makes the calls the plugin's
 * tick loop makes for it ({@code trackAttackCooldown}).
 */
public class TripScoredTicksTest
{
	private final SessionTotals trip = new SessionTotals(0L);

	/** An attack thrown: the fight's tick, and the trip's as an attack when scored, else as a tick spent. */
	private void attack(Fight fight)
	{
		fight.recordAttackMade(false, 1.0);
		if (fight.isScored())
		{
			trip.recordAttackMade(false, 1.0, false);
		}
		else
		{
			trip.recordTickSpent(false);
		}
	}

	private void spent(Fight fight)
	{
		fight.recordTickSpent(false);
		if (fight.isAttacking())
		{
			trip.recordTickSpent(fight.isScored());
		}
	}

	private void lost(Fight fight, boolean eating)
	{
		fight.recordTickLost(eating, false);
		if (fight.isAttacking())
		{
			trip.recordTickLost(eating, fight.isScored());
		}
	}

	@Test
	public void anUnscoredFightAddsToTheTripsTicksButNotItsScoredOnes()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = new Fight("Abyssal Sire", NpcIds.GOBLIN, 1, 425, 0L, null, 0);
		// The Sire's respiratory system: on EncounterGroup's unscored list.
		final Fight respiratory = new Fight("Respiratory system", net.runelite.api.gameval.NpcID.ABYSSALSIRE_LUNG, 2,
			50, 0L, null, 0);
		assertFalse(respiratory.isScored());
		room.add(sire);
		room.add(respiratory);

		for (int i = 0; i < 6; i++)
		{
			attack(sire);
			spent(sire);
			spent(sire);
			lost(sire, i % 2 == 0);
		}
		for (int i = 0; i < 3; i++)
		{
			attack(respiratory);
			spent(respiratory);
			spent(respiratory);
			spent(respiratory);
		}

		assertEquals(36, trip.getCombatTicks());
		assertEquals(24, trip.getScoredCombatTicks());
		// The same as the room counts them: the trip's ticks are its rooms', scored or all.
		assertEquals(room.getCombatTicks(), trip.getCombatTicks());
		assertEquals(room.getScoredCombatTicks(), trip.getScoredCombatTicks());
		// Lost ticks still count wherever they were lost, as before.
		assertEquals(6, trip.getTicksLost());
	}

	@Test
	public void resetClearsThem()
	{
		final Fight sire = new Fight("Abyssal Sire", NpcIds.GOBLIN, 1, 425, 0L, null, 0);
		attack(sire);
		spent(sire);
		trip.reset(1L);
		assertEquals(0, trip.getCombatTicks());
		assertEquals(0, trip.getScoredCombatTicks());
	}
}

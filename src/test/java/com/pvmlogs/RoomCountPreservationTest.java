package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

public class RoomCountPreservationTest
{
	/** Regression for the shared add-death rule changed by #107; synthetic room lifecycles. */
	@Test
	public void sireKrakenAndHesporiAddsKeepCountsOnlyInsideTheirOpenRoom()
	{
		final int[][] groups = {
			{NpcID.ABYSSALSIRE_SIRE_WANDERING, NpcID.ABYSSALSIRE_SPAWN, NpcID.ABYSSALSIRE_SCION,
				NpcID.ABYSSALSIRE_LUNG},
			{NpcID.SLAYER_KRAKEN_BOSS, NpcID.SLAYER_KRAKEN_BOSS_TENTACLE},
			{NpcID.HESPORI, NpcID.HESPORI_HEALER_ACTIVE, NpcID.HESPORI_HEALER_INACTIVE}
		};
		for (int[] group : groups)
		{
			final String name = EncounterGroup.of(group[0]);
			final Encounter room = new Encounter(name, null, 0);
			final Fight boss = new Fight(name, group[0], 1, 300, 0, null, 0);
			room.add(boss);
			assertFalse(PvmPerformancePlugin.roomGoesOnAtDeath(room, boss, group[0]));
			for (int i = 1; i < group.length; i++)
			{
				final Fight add = new Fight("Add", group[i], i + 1, 30, 0, null, 0);
				room.add(add);
				assertTrue(name, PvmPerformancePlugin.roomGoesOnAtDeath(room, add, group[i]));
				assertFalse(name, EncounterGroup.otherFallEndsFight(group[i], group[0]));
				assertFalse(name, room.closesOnLootFrom(group[i]));
				assertFalse(name, PvmPerformancePlugin.roomGoesOnAtDeath(null, add, group[i]));
			}
			room.end(100);
			for (Fight f : room.getFights())
			{
				assertFalse(name, PvmPerformancePlugin.roomGoesOnAtDeath(room, f, f.getTargetId()));
			}
		}
	}

}

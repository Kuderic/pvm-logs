package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-155, from Eric's Chambers of Xeric of 2026-11-02 (build ea86ada, read in place, nothing copied): Vespula's portal
 * (index 41064) left his scene with each of his deaths there and was fought again on the same index, its kill
 * reconciling 3 against 242 (the four pieces 248); Olm's left claw likewise across a death (index 42160: 234 then 371
 * against 600) and across a turn to Olm's head (index 54443: 29 then 575).
 */
public class DamageAcrossADespawnTest
{
	private static final int PORTAL = NpcID.RAIDS_VESPULA_PORTAL;
	private static final int INDEX = 41064;
	private static final int WORLD = 329;
	private static final int RAID = 3;

	@Test
	public void aTargetFoughtAgainAfterThePlayersDeathKeepsItsCount()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, PORTAL, 242, 41160);
		health.damage(INDEX, PORTAL, 94, 41205);
		// [41206]: despawned 8 ticks after his death; the fight ends with it alive, and every count is cleared.
		health.park(INDEX, WORLD, RAID, 94);
		health.clear();
		// [41253]: the same portal on the same index, fought again.
		final TargetHealth.Parked kept = health.unpark(INDEX, PORTAL, WORLD, RAID);
		assertNotNull(kept);
		assertEquals(94, kept.extra);
		health.setTableMaxHp(INDEX, PORTAL, 242, 41253);
		health.damage(INDEX, PORTAL, 119, 41299);
		health.park(INDEX, WORLD, RAID, 213);
		health.clear();
		health.unpark(INDEX, PORTAL, WORLD, RAID);
		health.damage(INDEX, PORTAL, 32, 41385);
		health.park(INDEX, WORLD, RAID, 245);
		health.clear();
		health.unpark(INDEX, PORTAL, WORLD, RAID);
		health.damage(INDEX, PORTAL, 3, 41446);
		// RECONCILE at the kill sums the four pieces: 248 (the bar's 242 and T-154's opening splats), where it read 3.
		assertEquals(248, health.damageCounted(INDEX));
	}

	@Test
	public void anotherIndexAnotherWorldOrAnotherRaidStartsAfresh()
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, PORTAL, 242, 0);
		health.damage(INDEX, PORTAL, 94, 1);
		health.park(INDEX, WORLD, RAID, 94);
		health.clear();
		assertNull(health.unpark(INDEX + 1, PORTAL, WORLD, RAID));
		health.park(INDEX, WORLD, RAID, 94);
		assertNull(health.unpark(INDEX, PORTAL, WORLD + 1, RAID));
		health.setTableMaxHp(INDEX, PORTAL, 242, 0);
		health.damage(INDEX, PORTAL, 94, 1);
		health.park(INDEX, WORLD, RAID, 94);
		health.clear();
		assertNull(health.unpark(INDEX, PORTAL, WORLD, RAID + 1));
		// Another npc on the index (an index reused).
		health.setTableMaxHp(INDEX, PORTAL, 242, 0);
		health.damage(INDEX, PORTAL, 94, 1);
		health.park(INDEX, WORLD, RAID, 94);
		health.clear();
		assertNull(health.unpark(INDEX, NpcID.RAIDS_SCAVENGER_BEAST_A, WORLD, RAID));
		// Gone for good (died, or found fresh on return): nothing parked is kept.
		health.setTableMaxHp(INDEX, PORTAL, 242, 0);
		health.damage(INDEX, PORTAL, 94, 1);
		health.park(INDEX, WORLD, RAID, 94);
		health.forget(INDEX);
		assertNull(health.unpark(INDEX, PORTAL, WORLD, RAID));
	}

	@Test
	public void aTurnToAnotherTargetAndAPhaseLabelKeepThePieceBefore()
	{
		// Olm's left claw, index 54443: 29 at [44555]-[44565], then a turn to Olm's head (another group: every count
		// cleared), then [44805] in the room labelled "· acid", 575 to its death. 604 against 600.
		final int claw = NpcID.OLM_HAND_LEFT;
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(54443, claw, 600, 44555);
		health.damage(54443, claw, 29, 44564);
		health.park(54443, WORLD, RAID, 29);
		health.clear();
		// The thrall's splat on it while Eric was on the head, counted on a fresh entry: added on.
		health.damage(54443, claw, 2, 44700);
		assertNotNull(health.unpark(54443, claw, WORLD, RAID));
		health.damage(54443, claw, 575, 45030);
		assertEquals(606, health.damageCounted(54443));
	}
}

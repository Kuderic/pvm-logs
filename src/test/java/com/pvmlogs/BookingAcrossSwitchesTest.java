package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Skill;
import org.junit.Test;

/** T-021: an attack belongs to the npc it hit; a shot never seen leaving books from its drop; a room's switches. */
public class BookingAcrossSwitchesTest
{
	private static final int SIRE = 5886;
	private static final int RESPIRATORY = 5914;

	private static Fight fight(int npcId, int index, int startTick)
	{
		final Fight f = new Fight("npc", npcId, index, 50, startTick * 600L, null, 0);
		f.noteStartTick(startTick);
		return f;
	}

	@Test
	public void aShotBooksToTheFightOnTheNpcItWasAimedAtNotTheOneOpenWhenItWasThrown()
	{
		// The Sire log, 68-70: thrown at respiratory system 35728 while the Sire fight was open; the shot itself opened 35728's.
		final Fight sire = fight(SIRE, 24468, 46);
		sire.end(false, 70 * 600L);
		final Fight lung = fight(RESPIRATORY, 35728, 70);
		assertSame(lung, PvmPerformancePlugin.attackerFor(35728, 68, lung, sire));
		// The old rule, the fight open on the throw's tick, is what booked it to the Sire.
		assertSame(sire, PvmPerformancePlugin.fightAsOf(68, lung, sire));
	}

	@Test
	public void aLastSwingBookedAfterTheNextFightOpenedStaysWithItsOwnTarget()
	{
		final Fight first = fight(SIRE, 24468, 46);
		first.end(true, 100 * 600L);
		final Fight next = fight(RESPIRATORY, 35728, 101);
		assertSame(first, PvmPerformancePlugin.attackerFor(24468, 99, next, first));
	}

	@Test
	public void withNoFightOnItsNpcTheFightOpenOnItsTickTakesIt()
	{
		final Fight open = fight(SIRE, 24468, 46);
		assertSame(open, PvmPerformancePlugin.attackerFor(99999, 60, open, null));
		assertSame(open, PvmPerformancePlugin.attackerFor(-1, 60, open, null));
	}

	@Test
	public void anUnseenShotIsBookedFromItsDropWhenTheWeaponWasFree()
	{
		// 78's 34 on a respiratory system: paid on 74, the weapon due on 72 after the shot thrown on 68.
		assertEquals(74, PvmPerformancePlugin.shotThrownOn(78, 74, 72));
		// A tick of slack against the due tick.
		assertEquals(71, PvmPerformancePlugin.shotThrownOn(75, 71, 72));
		// No drop for it (a miss pays nothing): not booked.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(78, Integer.MIN_VALUE, 72));
		// Paid while the weapon was busy: an attack already booked, its splat landing late.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(80, 74, 78));
		// Nothing lands on the tick it is thrown.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(78, 78, 72));
		// The first attack of all.
		assertEquals(74, PvmPerformancePlugin.shotThrownOn(78, 74, Integer.MIN_VALUE));
	}

	@Test
	public void theLedgerSaysWhichDropASplatSpends()
	{
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.RANGED, 136);
		ledger.add(Skill.HITPOINTS, 45);
		ledger.close(74, RESPIRATORY, 1.0, null);
		assertEquals(74, ledger.attackTickFor(34, 78));
		// Nothing lands on the drop's own tick, and a zero pays nothing.
		assertEquals(Integer.MIN_VALUE, ledger.attackTickFor(34, 74));
		assertEquals(Integer.MIN_VALUE, ledger.attackTickFor(0, 78));
		// Read-only: still there after asking.
		assertEquals(74, ledger.attackTickFor(34, 78));
		ledger.spend(34, 78, true);
		assertEquals(Integer.MIN_VALUE, ledger.attackTickFor(34, 79));
	}

	@Test
	public void movingBetweenTheSiresTargetsIsMovingWithinItsRoom()
	{
		final Fight lung = fight(RESPIRATORY, 35728, 70);
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		room.add(lung);
		assertTrue(lung.getGroupName() != null);
		assertTrue(PvmPerformancePlugin.switchesWithinRoom(room, lung, RESPIRATORY));
		assertTrue(PvmPerformancePlugin.switchesWithinRoom(room, lung, SIRE));
		// Something outside the group, or a room already closed, is a new start.
		assertFalse(PvmPerformancePlugin.switchesWithinRoom(room, lung, NpcIds.GOBLIN));
		room.end(1000L);
		assertFalse(PvmPerformancePlugin.switchesWithinRoom(room, lung, SIRE));
		assertFalse(PvmPerformancePlugin.switchesWithinRoom(null, lung, SIRE));
		// An ungrouped fight has no room to move within.
		assertFalse(PvmPerformancePlugin.switchesWithinRoom(new Encounter("Goblin", null, 0L),
			fight(NpcIds.GOBLIN, 5, 1), NpcIds.GOBLIN));
		assertNull(fight(NpcIds.GOBLIN, 5, 1).getGroupName());
	}
}

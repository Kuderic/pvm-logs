package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-068 part 2 (T-067's finding D-3): the side panel treats the room on show as
 * over only by T-070's rule, as the overlay does. An add's death or one titan
 * down leaves it running: no "(dead)", no after-fight cards, no average, and the
 * Damage taken card's total only, until the boss's kill.
 */
public class SidePanelRoomOverTest
{
	private static Fight fight(String name, int npcId, int index)
	{
		final Fight f = new Fight(name, npcId, index, 300, 0L, null, 0);
		f.recordAttackMade(true, 1.0);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(20, 0L, true);
		f.recordExpected(30, 0.8, 18, 100);
		f.recordHitTaken(12, false, 0L);
		for (int tick = 0; tick < 5; tick++)
		{
			f.recordTickSpent(true);
		}
		return f;
	}

	/** The Fight tab as the panel reads this room, with this fight on show. */
	private static PanelSnapshot.FightTab tab(Encounter room, Fight onShow)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.ended = PvmPerformancePanel.roomEnded(room, onShow);
		in.killed = in.ended && room.isKilled();
		return SidePanelModel.fight(in);
	}

	private static void assertRunning(PanelSnapshot.FightTab tab)
	{
		assertEquals(PanelSnapshot.State.LIVE, tab.state);
		assertTrue(tab.header.suffix.isEmpty());
		assertNull("no after-fight budget", tab.budget);
		assertNull("no details", tab.details);
		assertEquals(java.util.Collections.singletonList(SidePanelModel.AFTER_THE_FIGHT),
			java.util.Arrays.asList(tab.taken.rows.get(0).left.text));
	}

	@Test
	public void aSireRoomWithADeadRespiratorySystemIsShownAsRunning()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 1);
		sire.end(false, 5L);
		final Fight lung = fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 2);
		lung.end(true, 10L);
		room.add(sire);
		room.add(lung);
		// The lung's fight has ended; before T-070's rule the panel took that for the room's end.
		assertTrue(lung.isEnded());
		assertFalse(PvmPerformancePanel.roomEnded(room, lung));
		assertRunning(tab(room, lung));

		// The Sire's death: the after-fight view.
		final Fight phase2 = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, 1);
		room.add(phase2);
		phase2.end(true, 20L);
		assertTrue(PvmPerformancePanel.roomEnded(room, phase2));
		final PanelSnapshot.FightTab dead = tab(room, phase2);
		assertEquals(PanelSnapshot.State.DEAD, dead.state);
		assertEquals(" (dead)", dead.header.suffix.text);
	}

	@Test
	public void aRoyalTitansRoomWithOneTitanDownIsShownAsRunning()
	{
		final Encounter room = new Encounter("The Royal Titans", null, 0L);
		final Fight king = fight("Branda the Ice King", NpcID.RT_ICE_KING, 2);
		king.end(false, 5L);
		final Fight queen = fight("Eldric the Fire Queen", NpcID.RT_FIRE_QUEEN, 1);
		queen.end(true, 10L);
		room.add(king);
		room.add(queen);
		assertFalse(PvmPerformancePanel.roomEnded(room, queen));
		assertRunning(tab(room, queen));

		// The second titan down closes the room (T-061).
		final Fight kingAgain = fight("Branda the Ice King", NpcID.RT_ICE_KING, 2);
		room.add(kingAgain);
		kingAgain.end(true, 20L);
		kingAgain.closeRoom();
		room.end(20L);
		assertTrue(PvmPerformancePanel.roomEnded(room, kingAgain));
		assertEquals(PanelSnapshot.State.DEAD, tab(room, kingAgain).state);
	}

	@Test
	public void anUngroupedBossIsUnchanged()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final Fight v = fight("Vardorvis", NpcID.VARDORVIS, 1);
		room.add(v);
		assertFalse(PvmPerformancePanel.roomEnded(room, v));
		v.end(true, 10L);
		assertTrue(PvmPerformancePanel.roomEnded(room, v));
	}

	@Test
	public void aRoomPickedFromTheRaidsListIsShownAfterItsFight()
	{
		final Encounter earlier = new Encounter("Tekton", RaidType.CHAMBERS_OF_XERIC, 0L);
		earlier.add(fight("Tekton", NpcID.RAIDS_TEKTON_WAITING, 1));
		final Fight now = fight("Vasa Nistirio", NpcID.RAIDS_VASANISTIRIO_WALKING, 2);
		assertTrue(PvmPerformancePanel.roomEnded(earlier, now));
	}
}

package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-173 (Eric, 2026-11-05: "make sure the wardens are separated … damage dealt to the damaged warden's exposed core also
 * applies huge damage to the damaged warden"): the Wardens room on the Fight tab by phase, from the npc id, and phase 2's
 * poise, core and passed-on apart, with no total counting the passed-on.
 */
public class SidePanelWardensTest
{
	private static List<String> names(PanelSnapshot.ListRows rows)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.ListRow r : rows.rows)
		{
			out.add(r.name.text);
		}
		return out;
	}

	@Test
	public void aPhase2RecordShowsItsThreePartsAndTheTotalsLeaveOutThePassedOn()
	{
		final Encounter room = SidePanelWardensExamples.encounter(true, true);
		final Fight p2 = room.getFights().get(1);
		final WardensPhase2 parts = p2.getWardensP2();
		// The fight's own A is poise + core (T-172), never the 5,740 passed on.
		assertEquals(929, parts.poise.damage);
		assertEquals(1_148, parts.core.damage);
		assertEquals(5_740, parts.passedOn);
		assertEquals(parts.poise.damage + parts.core.damage, p2.getDamageDealt());
		assertEquals(parts.poise.expected + parts.core.expected, p2.getSumExpectedAverageHit(), 1e-6);

		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelWardensExamples.room(true, true));
		assertEquals(Arrays.asList("Obelisk", "P2 · Elidinis' Warden", "poise", "core", SidePanelModel.PASSED_ON,
			"P3 · Tumeken's Warden"), names(tab.targets));
		final List<PanelSnapshot.ListRow> rows = tab.targets.rows;
		// P2's row: poise + core against their expected.
		assertEquals("2,077/2,191", rows.get(1).value.text);
		assertFalse(rows.get(1).part);
		// Poise and core: A/E and luck, set in under it.
		assertTrue(rows.get(2).part);
		assertEquals("929/923", rows.get(2).value.text);
		assertTrue(rows.get(2).sub.text, rows.get(2).sub.text.contains("luck +6"));
		assertTrue(rows.get(2).sub.text.contains("not hitpoints"));
		assertEquals("1,148/1,268", rows.get(3).value.text);
		assertTrue(rows.get(3).sub.text, rows.get(3).sub.text.contains("luck -120"));
		// The core's melee is certain: no spread, so no band, its luck the difference alone.
		assertEquals("certain", rows.get(3).chip.text);
		// The passed-on: her hitpoints only, no luck, "not counted"; its tooltip says why.
		final PanelSnapshot.ListRow passed = rows.get(4);
		assertTrue(passed.part);
		assertEquals("5,740", passed.value.text);
		assertFalse(passed.sub.text.contains("luck"));
		assertEquals("not counted", passed.chip.text);
		assertEquals(SidePanelModel.PASSED_ON_TIP, passed.tip);
		assertTrue(passed.tip.contains("no luck, skill or total counts it"));

		// The room's totals: the fights' own figures, which never hold the passed-on.
		long fights = 0;
		for (Fight f : room.getFights())
		{
			fights += f.getDamageDealt();
		}
		assertEquals(fights, room.getDamageDealt());
		long targets = 0;
		for (SidePanelModel.Target t : SidePanelModel.targets(room.getFights()))
		{
			targets += t.damage;
		}
		assertEquals(fights, targets);
		assertFalse(fights >= 5_740 + 2_077);
	}

	@Test
	public void thePhaseLabelsComeFromTheRecordWhicheverWardenIsDamaged()
	{
		assertEquals(Arrays.asList("Obelisk", "P2 · Tumeken's Warden", "poise", "core", SidePanelModel.PASSED_ON,
			"P3 · Elidinis' Warden"), names(SidePanelModel.fight(SidePanelWardensExamples.room(false, true)).targets));
		// By npc id, every form of each phase, either warden; nothing else.
		for (int id = NpcID.TOA_WARDENS_P1_OBELISK_NPC_INACTIVE; id <= NpcID.TOA_WARDENS_P2_OBELISK_NPC; id++)
		{
			assertEquals(id + "", 1, SidePanelModel.wardensPhase(id));
		}
		for (int id = NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE; id <= NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED; id++)
		{
			assertEquals(id + "", 2, SidePanelModel.wardensPhase(id));
		}
		for (int id = NpcID.TOA_WARDEN_ELIDINIS_PHASE3_INACTIVE; id <= NpcID.TOA_WARDEN_P3_DEATH_ELIDINIS; id++)
		{
			assertEquals(id + "", 3, SidePanelModel.wardensPhase(id));
		}
		assertEquals(0, SidePanelModel.wardensPhase(NpcID.TOA_WARDEN_ELIDINIS_PHASE1));
		assertEquals(0, SidePanelModel.wardensPhase(NpcID.TOA_WARDEN_ELIDINIS_CORE));
		assertEquals(0, SidePanelModel.wardensPhase(NpcID.TOA_ZEBAK));
		assertEquals("Zebak", SidePanelModel.wardensLabel(0, "Zebak"));
		// One warden in both phases (a name alone can't tell them apart): still two rows.
		final Fight p2 = new Fight("Elidinis' Warden", NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 2, 5_940, 0,
			RaidType.TOMBS_OF_AMASCUT, 1);
		final Fight p3 = new Fight("Elidinis' Warden", NpcID.TOA_WARDEN_ELIDINIS_PHASE3, 2, 1_880, 0,
			RaidType.TOMBS_OF_AMASCUT, 1);
		p2.recordAttackMade(true, 1.0);
		p3.recordAttackMade(true, 1.0);
		final List<SidePanelModel.Target> t = SidePanelModel.targets(Arrays.asList(p2, p3));
		assertEquals(2, t.size());
		assertEquals(2, t.get(0).wardensPhase);
		assertEquals(3, t.get(1).wardensPhase);
	}

	@Test
	public void anOldRecordShowsAsBefore()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelWardensExamples.room(true, false));
		// No parts recorded: no part rows, and P2's row is the fight's own figures, as before.
		assertEquals(Arrays.asList("Obelisk", "P2 · Elidinis' Warden", "P3 · Tumeken's Warden"), names(tab.targets));
		for (PanelSnapshot.ListRow r : tab.targets.rows)
		{
			assertFalse(r.name.text, r.part);
			assertNull(r.tip);
		}
		final Encounter room = SidePanelWardensExamples.encounter(true, false);
		assertNull(room.getFights().get(1).getWardensP2());
		assertEquals(room.getFights().get(1).getDamageDealt() + "", "2077", room.getFights().get(1).getDamageDealt()
			+ "");
		// The same room's other cards don't move with the parts: its A and E are the same either way.
		final Encounter now = SidePanelWardensExamples.encounter(true, true);
		assertEquals(room.getDamageDealt(), now.getDamageDealt());
	}

	@Test
	public void aValueChangeIsSetInPlace()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		final SidePanelModel.Input in = SidePanelWardensExamples.room(true, true);
		p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, null));
		final String before = SidePanelModel.fight(in).structure();
		final SidePanelModel.Input more = SidePanelWardensExamples.room(true, true);
		final Encounter room = SidePanelWardensExamples.encounter(true, true);
		room.getFights().get(1).recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 17);
		SidePanelModel.fillRoom(more, room);
		assertEquals(before, SidePanelModel.fight(more).structure());
	}

	@Test
	public void nothingIsCutAtAnySize()
	{
		final List<String> cut = new ArrayList<>();
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
					p.setText(larger, smooth);
					try
					{
						p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelWardensExamples.room(true, true)), null,
							null));
						final Component section = SidePanelPictures.sectionOf(p.fightTab(), "targets");
						SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
						for (Component c : all((Container) section))
						{
							if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null
								&& ((SidePanelText) c).lastShown().contains(LedgerRows.CUT))
							{
								cut.add(larger + " " + smooth + " " + scale + ": " + ((SidePanelText) c).lastShown());
							}
						}
					}
					finally
					{
						SidePanelStyle.setText(false, false);
					}
				}
			}
		}
		assertTrue(String.join("\n", cut), cut.isEmpty());
	}

	private static List<Component> all(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}
}

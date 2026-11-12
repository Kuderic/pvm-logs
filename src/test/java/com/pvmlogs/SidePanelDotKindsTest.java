package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-063: burn, poison, venom and bleed each on their own line in the side panel,
 * from T-062's figures: the details, the Damage taken card, the Trip's other
 * damage, a Damage row a kind, and the Trip's tile naming the kind (T-202: the
 * Fight tab has no tiles). What has no kind (an old kill's) is one {@code DoT} line.
 */
public class SidePanelDotKindsTest
{
	private static List<String> lefts(PanelSnapshot.Rows rows)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : rows.rows)
		{
			out.add(r.left.text + " " + r.right.text);
		}
		return out;
	}

	private static List<String> dotRows(List<String> rows)
	{
		final List<String> out = new ArrayList<>();
		for (String r : rows)
		{
			if (r.matches("^(burn|poison|venom|bleed|DoT) (dmg|taken) .*"))
			{
				out.add(r);
			}
		}
		return out;
	}

	private static List<String> sources(PanelSnapshot.Sources s)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Source r : s.rows)
		{
			out.add(r.name + " " + r.damage + " " + r.share);
		}
		return out;
	}

	@Test
	public void theTripMixesOldAndNewKills()
	{
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(SidePanelExamples.tripMixedOldAndNew());
		assertEquals(Arrays.asList("burn dmg 20", "venom dmg 40", "DoT dmg 40"), dotRows(lefts(trip.other)));
		assertEquals("+290 thrall, 100 DoT", trip.tiles.get(1).sub.text);
	}

	@Test
	public void otherKindsByNameAndTheirRowNames()
	{
		assertEquals("Bleed", SidePanelModel.dotSourceName("bleed"));
		assertEquals("Damage over time", SidePanelModel.dotSourceName(DotKinds.REMAINDER));
		assertEquals("Disease", SidePanelModel.dotSourceName("disease"));
		assertEquals("12 bleed", SidePanelModel.dotTile(SidePanelExamples.kinds("bleed", 12), 12));
	}

	@Test
	public void takenLinesAreAfterTheFightOnly()
	{
		final SidePanelModel.Input running = SidePanelExamples.venomDealtPoisonTaken();
		running.ended = false;
		running.killed = false;
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(running);
		// The Damage taken card while it runs is the live total only, as before T-063.
		assertTrue(tab.taken == null || dotRows(lefts(tab.taken)).isEmpty());
	}

	@Test
	public void aNewKindMidFightIsOneStructureChange()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelModel.Input venom = SidePanelExamples.live();
		venom.scoredDot = 20;
		venom.scoredDotByKind = SidePanelExamples.kinds("venom", 20);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(venom)));
		final SidePanelFightTab tab = panel.fightTab();
		final int builds = tab.builds();
		// More venom: values in place.
		final SidePanelModel.Input more = SidePanelExamples.live();
		more.scoredDot = 34;
		more.scoredDotByKind = SidePanelExamples.kinds("venom", 34);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(more)));
		assertEquals(builds, tab.builds());
		// A burn too: T-218, a row added in place in Damage, never a rebuild of the section or the tab.
		final int slots = tab.slotBuilds();
		final int rowsBefore = SidePanelModel.fight(more).damage.rows.size();
		final SidePanelModel.Input burn = SidePanelExamples.live();
		burn.scoredDot = 40;
		burn.scoredDotByKind = SidePanelExamples.kinds("burn", 6, "venom", 34);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(burn)));
		assertEquals(builds, tab.builds());
		assertEquals(slots, tab.slotBuilds());
		assertEquals(rowsBefore + 1, SidePanelModel.fight(burn).damage.rows.size());
		assertEquals(rowsBefore + 1, sourceRows(tab));
		panel.apply(new PanelSnapshot(SidePanelModel.fight(burn)));
		assertEquals(builds, tab.builds());
		assertEquals(slots, tab.slotBuilds());
	}

	@Test
	public void aRoomsKindsAreSummedOverItsScoredFights()
	{
		final Encounter room = new Encounter("Zulrah", null, 0L);
		final Fight a = new Fight("Zulrah", NpcIds.GOBLIN, 1, 500, 1000, null, 0);
		final Fight b = new Fight("Zulrah", NpcIds.GOBLIN, 1, 500, 60_000, null, 0);
		a.recordDotDamage(12, net.runelite.api.HitsplatID.VENOM);
		b.recordDotDamage(18, net.runelite.api.HitsplatID.VENOM);
		b.recordDotDamage(4, net.runelite.api.HitsplatID.BURN);
		room.add(a);
		room.add(b);
		final SidePanelModel.Input in = new SidePanelModel.Input();
		SidePanelModel.fillRoom(in, room);
		assertEquals(34, in.scoredDot);
		assertEquals(SidePanelExamples.kinds("venom", 30, "burn", 4), in.scoredDotByKind);
		assertEquals("34 DoT", SidePanelModel.dotTile(in.scoredDotByKind, in.scoredDot));
	}

	private static int sourceRows(java.awt.Container c)
	{
		int n = 0;
		for (java.awt.Component k : c.getComponents())
		{
			n += k instanceof SidePanelParts.SourceRowView ? 1 : k instanceof java.awt.Container ? sourceRows(
				(java.awt.Container) k) : 0;
		}
		return n;
	}
}

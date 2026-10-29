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
 * damage, a Damage row a kind, and the tile naming the kind. What has no kind (an
 * old kill's) is one {@code DoT} line.
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
	public void venomDealtAndPoisonTaken()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.venomDealtPoisonTaken());
		assertEquals(Collections.singletonList("venom dmg 30"), dotRows(lefts(tab.details)));
		assertEquals(Collections.singletonList("poison taken 25"), dotRows(lefts(tab.taken)));
		assertEquals("+80 thrall, 30 venom", tab.tiles.get(1).sub.text);
		final List<String> rows = sources(tab.damage);
		assertTrue(rows.toString(), rows.stream().anyMatch(r -> r.startsWith("Venom 30 ")));
		assertFalse(rows.toString(), rows.stream().anyMatch(r -> r.startsWith("Burn and poison")));
	}

	@Test
	public void aBurnClawsKill()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.burnClaws());
		assertEquals(Collections.singletonList("burn dmg 47"), dotRows(lefts(tab.details)));
		assertTrue(dotRows(lefts(tab.taken)).isEmpty());
		assertEquals("+80 thrall, 47 burn", tab.tiles.get(1).sub.text);
	}

	@Test
	public void twoKindsDealtSplitOnTheCardAndSumOnTheTile()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.twoKindsDealt());
		assertEquals(Arrays.asList("burn dmg 8", "venom dmg 30"), dotRows(lefts(tab.details)));
		assertEquals("+80 thrall, 38 DoT", tab.tiles.get(1).sub.text);
		// A row a kind, in T-062's order; each its share of everything on the card, all DoT's colour.
		final List<PanelSnapshot.Source> dot = new ArrayList<>();
		int whole = 0;
		for (PanelSnapshot.Source s : tab.damage.rows)
		{
			whole += Integer.parseInt(s.damage.replace(",", ""));
			if (s.colour.equals(SidePanelStyle.DOT))
			{
				dot.add(s);
			}
		}
		assertEquals(2, dot.size());
		assertEquals("Burn", dot.get(0).name);
		assertEquals("Venom", dot.get(1).name);
		assertEquals(8.0 / whole, dot.get(0).shareFraction, 1e-9);
		assertEquals(30.0 / whole, dot.get(1).shareFraction, 1e-9);
	}

	private static SidePanelStatTile damageTile(java.awt.Container c)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelStatTile && ((SidePanelStatTile) k).valueText().contains("/"))
			{
				return (SidePanelStatTile) k;
			}
			if (k instanceof java.awt.Container)
			{
				final SidePanelStatTile t = damageTile((java.awt.Container) k);
				if (t != null)
				{
					return t;
				}
			}
		}
		return null;
	}

	private static String tileShown(SidePanelModel.Input in)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		SidePanelPictures.render(in, 1);
		final SidePanelFightTab tab = panel.fightTab();
		SidePanelPictures.draw(panel.tabs(), tab, 1);
		return damageTile(tab).subText().lastShown();
	}

	@Test
	public void theTileNamesTheKindWhereItFitsAndSaysDotWhereItDoesnt()
	{
		// RuneScape Small at 1x: "+80 thrall, 30 venom" is wider than the tile, so it reads DoT; the whole is the tooltip.
		final SidePanelModel.Input venom = SidePanelExamples.venomDealtPoisonTaken();
		assertEquals("+80 thrall, 30 venom", SidePanelModel.fight(venom).tiles.get(1).sub.text);
		assertEquals("+80 thrall, 30 DoT", SidePanelModel.fight(venom).tiles.get(1).subShort);
		assertEquals("+80 thrall, 30 DoT", tileShown(venom));
		// One that fits keeps the kind's name: "+47 burn" (no thrall this kill).
		final SidePanelModel.Input burn = SidePanelExamples.burnClaws();
		burn.scoredThrall = 0;
		assertEquals("+47 burn", tileShown(burn));
		// Already DoT: no short form.
		assertEquals(null, SidePanelModel.fight(SidePanelExamples.twoKindsDealt()).tiles.get(1).subShort);
	}

	@Test
	public void anOldKillHasOneDotLine()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.oldKill());
		assertEquals(Collections.singletonList("DoT dmg 40"), dotRows(lefts(tab.details)));
		assertEquals(Collections.singletonList("DoT taken 12"), dotRows(lefts(tab.taken)));
		assertEquals("+80 thrall, 40 DoT", tab.tiles.get(1).sub.text);
		assertTrue(sources(tab.damage).stream().anyMatch(r -> r.startsWith("Damage over time 40 ")));
	}

	@Test
	public void theTripMixesOldAndNewKills()
	{
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(SidePanelExamples.tripMixedOldAndNew());
		assertEquals(Arrays.asList("burn dmg 20", "venom dmg 40", "DoT dmg 40"), dotRows(lefts(trip.other)));
		assertEquals("+290 thrall, 100 DoT", trip.tiles.get(1).sub.text);
	}

	@Test
	public void noLineForAKindWithNothing()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.dotKill("Vardorvis", 0,
			SidePanelExamples.kinds("venom", 0), 0, SidePanelExamples.kinds()));
		assertTrue(dotRows(lefts(tab.details)).isEmpty());
		assertEquals("+80 thrall", tab.tiles.get(1).sub.text);
		assertFalse(sources(tab.damage).stream().anyMatch(r -> r.startsWith("Venom")));
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
		// A burn too: one rebuild for the new row.
		final SidePanelModel.Input burn = SidePanelExamples.live();
		burn.scoredDot = 40;
		burn.scoredDotByKind = SidePanelExamples.kinds("burn", 6, "venom", 34);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(burn)));
		assertEquals(builds + 1, tab.builds());
		panel.apply(new PanelSnapshot(SidePanelModel.fight(burn)));
		assertEquals(builds + 1, tab.builds());
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
}

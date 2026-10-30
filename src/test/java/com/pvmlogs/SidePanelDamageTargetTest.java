package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * T-099: the Damage card's Boss · Adds · All switch. Eric, 2026-10-29: "araxxor weapon dps includes the damage done to
 * araxytes, but these are always max hits and so it sort of skews this data".
 */
public class SidePanelDamageTargetTest
{
	private static final int BOSS = 0;
	private static final int ADDS = 1;
	private static final int ALL = 2;

	@After
	public void reset()
	{
		SidePanelFightTab.resetTarget();
	}

	// ---- the views ----

	@Test
	public void araxxorHasTheSwitchBossAddsAll()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		assertNotNull(t.byTarget);
		final List<String> labels = new ArrayList<>();
		for (PanelSnapshot.TargetView v : t.byTarget)
		{
			labels.add(v.label);
		}
		assertEquals("[Boss, Adds, All]", labels.toString());
		assertEquals(0, SidePanelModel.DEFAULT_TARGET);
	}

	@Test
	public void theBossViewHoldsOnlyTheAttacksOnAraxxor()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		final PanelSnapshot.Source halberd = row(t.byTarget.get(BOSS), "Noxious halberd");
		final PanelSnapshot.Source bossBow = row(t.byTarget.get(BOSS), "Zaryte crossbow");
		// The crossbow's four bolts at Araxxor himself, none of the eight at the araxytes.
		assertTrue(bossBow.sub, bossBow.sub.startsWith("4 attacks"));
		assertTrue(halberd.sub, halberd.sub.startsWith("48 attacks"));
		final PanelSnapshot.Source addsBow = row(t.byTarget.get(ADDS), "Zaryte crossbow");
		assertTrue(addsBow.sub, addsBow.sub.startsWith("8 attacks"));
		assertNull(find(t.byTarget.get(ADDS), "Noxious halberd"));
		final PanelSnapshot.Source allBow = row(t.byTarget.get(ALL), "Zaryte crossbow");
		assertTrue(allBow.sub, allBow.sub.startsWith("12 attacks"));
	}

	@Test
	public void theDpsFollowsTheSwitch()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		final double boss = Double.parseDouble(row(t.byTarget.get(BOSS), "Zaryte crossbow").dps);
		final double adds = Double.parseDouble(row(t.byTarget.get(ADDS), "Zaryte crossbow").dps);
		final double all = Double.parseDouble(row(t.byTarget.get(ALL), "Zaryte crossbow").dps);
		// Max hits on the araxytes: their DPS is the crossbow's best, the boss's its real one, and All between.
		assertTrue(adds + " > " + all, adds > all);
		assertTrue(all + " > " + boss, all > boss);
		// 48 every 5 ticks, over the time spent shooting them (each bolt's tick and the cooldown after it).
		assertEquals(16.0, adds, 0.05);
		// The tooltips follow too.
		assertTrue(row(t.byTarget.get(BOSS), "Zaryte crossbow").tooltip.contains("4"));
	}

	@Test
	public void theChartFollowsTheSwitch()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		final PanelSnapshot.Chart boss = t.byTarget.get(BOSS).chart;
		final PanelSnapshot.Chart adds = t.byTarget.get(ADDS).chart;
		final PanelSnapshot.Chart all = t.byTarget.get(ALL).chart;
		assertNotNull(boss);
		assertNotNull(adds);
		// Each view's whole series, from its start, so a click shows it from 0:00.
		assertEquals(0, boss.from);
		assertEquals(0, all.from);
		final float bossEnd = boss.damage[boss.damage.length - 1];
		final float addsEnd = adds.damage[adds.damage.length - 1];
		final float allEnd = all.damage[all.damage.length - 1];
		assertEquals(allEnd, bossEnd + addsEnd, 0.5f);
		assertEquals(8 * 48, addsEnd, 0.5f);
		// The adds' axis spans their first fight to their last, Araxxor's time between included.
		assertEquals(LedgerRows.clock(adds.length), adds.end);
		assertNotEquals(LedgerRows.clock(SidePanelTargetExamples.input(SidePanelTargetExamples.araxxor()).addsOnly
			.scope.combatTicks()), adds.end);
	}

	@Test
	public void theRoomsLuckAndExecutionStayTheWholeKills()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		for (int i = 0; i < 3; i++)
		{
			final PanelSnapshot.FightTab shown = t.showing(i);
			assertEquals(texts(t.tiles), texts(shown.tiles));
			assertEquals(t.execution, shown.execution);
			assertEquals(t.budget, shown.budget);
			assertTrue(shown.byTarget.get(i).note, shown.byTarget.get(i).note.endsWith(SidePanelModel.WHOLE_KILL));
			assertEquals(t.byTarget.get(i).damage, shown.damage);
		}
		// T-105: the switch's tooltip, the side's damage and time, then the note.
		assertEquals("1,504 on the boss over 2:40. " + SidePanelModel.WHOLE_KILL, t.byTarget.get(BOSS).note);
		assertTrue(t.byTarget.get(ADDS).note, t.byTarget.get(ADDS).note.startsWith("384 on the adds over "));
		assertTrue(t.byTarget.get(ALL).note, t.byTarget.get(ALL).note.startsWith("1,888 on the boss and adds over 3:06."));
		assertEquals("1,108 on the boss", hintText(t.byTarget.get(BOSS)).replaceAll("^[\\d,]+", "1,108"));
	}

	@Test
	public void theSiresAddsAreTheirTimeOnly()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.sire());
		assertNotNull(t.byTarget);
		final PanelSnapshot.TargetView adds = t.byTarget.get(ADDS);
		assertNull(adds.damage);
		assertNull(adds.chart);
		assertTrue(adds.note, adds.note.contains(" on the adds, not scored."));
		assertNotNull(row(t.byTarget.get(BOSS), "Emberlight"));
		// The Sire's phases on the Targets list are unchanged (#117).
		assertEquals("Abyssal Sire", t.targets.rows.get(0).name.text);
	}

	@Test
	public void zulrahsSnakelingsAreTheirTimeOnly()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.zulrah());
		assertNotNull(t.byTarget);
		assertNull(t.byTarget.get(ADDS).damage);
		assertTrue(t.byTarget.get(ADDS).note, t.byTarget.get(ADDS).note.startsWith("0:03 on the adds, not scored."));
		final PanelSnapshot.Source bow = row(t.byTarget.get(BOSS), "Twisted bow");
		assertTrue(bow.sub, bow.sub.startsWith("24 attacks"));
	}

	@Test
	public void noSwitchForAnUngroupedBossARunOrAnOldKill()
	{
		assertNull(SidePanelModel.fight(SidePanelTargetExamples.ungrouped()).byTarget);
		assertNull(SidePanelModel.fight(SidePanelRunExamples.saved()).byTarget);
		final SidePanelModel.Input old = SidePanelTargetExamples.input(SidePanelTargetExamples.araxxor());
		old.later = SidePanelModel.LATER_TEXT;
		assertNull(SidePanelModel.fight(old).byTarget);
	}

	@Test
	public void noSwitchWhenTheAddsTookNoDamage()
	{
		final SidePanelTargetExamples.Room r = SidePanelTargetExamples.room("Araxxor",
			new SidePanelTargetExamples.Leg("Araxxor", net.runelite.api.gameval.NpcID.ARAXXOR, 1,
				SidePanelTargetExamples.weapon("Noxious halberd", net.runelite.api.gameval.ItemID.NOXIOUS_HALBERD,
					FightTimeline.Style.MELEE, 5, new int[]{40, 0, 30}, 27.5, 61, 0.62, 0)),
			new SidePanelTargetExamples.Leg("Acidic Araxyte", net.runelite.api.gameval.NpcID.ARAXXOR_MINION_VENOM, 2,
				SidePanelTargetExamples.weapon("Noxious halberd", net.runelite.api.gameval.ItemID.NOXIOUS_HALBERD,
					FightTimeline.Style.MELEE, 5, new int[]{0}, 27.5, 61, 0.62, 0)));
		assertNull(SidePanelModel.parts(r.room));
		assertNull(tab(r).byTarget);
	}

	// ---- no lag: the switch is a structure change, on a click only ----

	@Test
	public void eachViewIsItsOwnStructure()
	{
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		assertNotEquals(t.showing(BOSS).structure(), t.showing(ADDS).structure());
		assertEquals(t.showing(BOSS).structure(), tab(SidePanelTargetExamples.araxxor()).showing(BOSS).structure());
	}

	@Test
	public void theTabShowsTheBossFirstAndAClickRebuildsItOnce()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelFightTab view = panel.fightTab();
		final PanelSnapshot.FightTab t = tab(SidePanelTargetExamples.araxxor());
		view.apply(t);
		assertEquals(BOSS, SidePanelFightTab.target());
		final int built = view.builds();
		// The same snapshot again: no rebuild.
		view.apply(t);
		assertEquals(built, view.builds());
		view.pick(ADDS);
		assertEquals(built + 1, view.builds());
		assertEquals(ADDS, SidePanelFightTab.target());
		// The choice lasts: the next snapshot shows the adds too, without another build.
		view.apply(tab(SidePanelTargetExamples.araxxor()));
		assertEquals(built + 1, view.builds());
	}

	// ---- from History ----

	@Test
	public void aSavedKillGetsItsSidesViews()
	{
		final SidePanelTargetExamples.Room r = SidePanelTargetExamples.araxxor();
		final List<Fight> history = SidePanelTargetExamples.fights(r);
		java.util.Collections.reverse(history);
		final Encounter[] parts = SidePanelModel.parts(r.room);
		final SidePanelModel.Input in = SidePanelHistory.savedKill(history, r.room, -1, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE, null, FightTimeline.room(r.room, r.store), new FightTimeline.View[]{
				FightTimeline.room(parts[0], r.store), FightTimeline.room(parts[1], r.store)});
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertNotNull(t.byTarget);
		assertTrue(row(t.byTarget.get(ADDS), "Zaryte crossbow").sub.startsWith("8 attacks"));
		// Before its sides' views have come: the rows hold no attacks yet, as a kill's before its own view comes.
		final SidePanelModel.Input waiting = SidePanelHistory.savedKill(history, r.room, -1, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE, null, FightTimeline.room(r.room, r.store));
		assertNotNull(SidePanelModel.fight(waiting).byTarget);
	}

	private static PanelSnapshot.FightTab tab(SidePanelTargetExamples.Room r)
	{
		return SidePanelModel.fight(SidePanelTargetExamples.input(r));
	}

	private static PanelSnapshot.Source row(PanelSnapshot.TargetView v, String name)
	{
		final PanelSnapshot.Source s = find(v, name);
		if (s == null)
		{
			throw new AssertionError("no row " + name + " in " + v.label);
		}
		return s;
	}

	private static PanelSnapshot.Source find(PanelSnapshot.TargetView v, String name)
	{
		if (v.damage == null)
		{
			return null;
		}
		for (PanelSnapshot.Source s : v.damage.rows)
		{
			if (s.name.equals(name))
			{
				return s;
			}
		}
		return null;
	}

	private static String hintText(PanelSnapshot.TargetView v)
	{
		return v.damage.hint.text;
	}

	private static String texts(List<PanelSnapshot.Tile> tiles)
	{
		final StringBuilder s = new StringBuilder();
		for (PanelSnapshot.Tile t : tiles)
		{
			s.append(t.label.text).append('=').append(t.value.text).append(';');
		}
		return s.toString();
	}

	@Test
	public void theAddsSideIsEveryAddTogether()
	{
		final Encounter[] parts = SidePanelModel.parts(SidePanelTargetExamples.araxxor().room);
		assertNotNull(parts);
		assertEquals(3, parts[0].getFights().size());
		assertEquals(3, parts[1].getFights().size());
		assertFalse(parts[1].getFights().stream().anyMatch(f -> "Araxxor".equals(f.getTargetName())));
	}
}

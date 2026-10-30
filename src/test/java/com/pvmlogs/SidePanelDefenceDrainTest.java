package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * T-052: what the defence drains did, in the side panel. The Defence card after
 * a kill (the path and floor, the specials, what the drains added, the
 * specials' share of luck), the header's live line, the trip's sums; nothing for
 * an immune target or an old kill.
 */
public class SidePanelDefenceDrainTest
{
	private static List<String> rows(PanelSnapshot.Defence d)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : d.rows)
		{
			out.add(r.toString());
		}
		return out;
	}

	// ---- the Defence card ----

	@Test
	public void twoHammersOneLanded()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.twoHammersOneLanded());
		final PanelSnapshot.Defence d = tab.defence;
		assertNotNull(d);
		assertEquals("250 → 175", d.hint.text);
		// T-076: no row repeats the header's drop, and no floor where the boss has none.
		assertEquals(Arrays.asList("Dragon warhammer | 1 of 2 landed"), rows(d));
		assertEquals("Damage added | +182 · 24 ticks faster", d.worth.toString());
		assertEquals("Specials' luck | -41 · 5 ticks slower", d.specLuck.toString());
		assertEquals(SidePanelStyle.COST, d.specLuck.colour);
		// Luck's card says the specials' share is in it.
		assertTrue(tab.curve.caption, tab.curve.caption.endsWith(". Your specials' share: 5 ticks slower"));
	}

	@Test
	public void aBandosGodsword()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.bandosGodsword());
		assertEquals(Arrays.asList("Bandos godsword | 1 of 1 landed"), rows(tab.defence));
		assertEquals("Damage added | +96 · 13 ticks faster", tab.defence.worth.toString());
		assertEquals("Specials' luck | +52 · 7 ticks faster", tab.defence.specLuck.toString());
		assertEquals(SidePanelStyle.BETTER, tab.defence.specLuck.colour);
	}

	@Test
	public void aBossAtItsFloor()
	{
		final PanelSnapshot.Defence d = SidePanelModel.fight(SidePanelExamples.atItsFloor()).defence;
		assertEquals(Arrays.asList("Floor [reached] | 102", "Dragon warhammer | 2 of 2 landed", "Party drains | 1"), rows(d));
		// A party member's drain is in the worth too.
		assertEquals("Damage added | +60 · 9 ticks faster", d.worth.toString());
		assertEquals("Specials' luck | +2 · about even", d.specLuck.toString());
		assertEquals(SidePanelStyle.BODY, d.specLuck.colour);
		// The bar: base to now, the floor ticked.
		assertEquals(170, d.drop.base);
		assertEquals(102, d.drop.end);
		assertEquals(102, d.drop.floor);
		assertEquals("68 (40%)", d.drop.value.text);
	}

	@Test
	public void anImmuneBossHasNoCard()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.immune());
		assertNull(tab.defence);
		assertFalse(tab.structure(), tab.structure().contains("|df"));
		assertFalse(tab.curve.caption.contains("specials"));
		// A drain adds the card and nothing else: without one, the tab is what it was before T-052.
		final SidePanelModel.Input with = SidePanelExamples.drainKill("Vardorvis",
			SidePanelExamples.twoHammersOneLanded().drain);
		assertEquals(tab.structure(), SidePanelModel.fight(with).structure().replaceAll("\\|df[^|]*", ""));
	}

	@Test
	public void onlyAfterTheFightAndNotForAWholeRaid()
	{
		final SidePanelModel.Input running = SidePanelExamples.twoHammersOneLanded();
		running.ended = false;
		running.killed = false;
		assertNull(SidePanelModel.fight(running).defence);
		final SidePanelModel.Input raid = SidePanelExamples.raid();
		raid.drain = SidePanelExamples.twoHammersOneLanded().drain;
		assertNull(SidePanelModel.fight(raid).defence);
	}

	@Test
	public void partyDrainsAloneAndNoT050Figures()
	{
		// A kill saved between T-049 and T-050: the record, no worth or spec luck.
		final SidePanelModel.Drain d = SidePanelExamples.drain(250, 225, 250, 0, 1, Double.NaN, Double.NaN, Double.NaN,
			Double.NaN);
		final PanelSnapshot.Defence card = SidePanelModel.defence(d);
		assertEquals(Arrays.asList("Party drains | 1"), rows(card));
		assertNull(card.worth);
		assertNull(card.specLuck);
		assertNull(SidePanelModel.specsShareOfLuck(d));
	}

	@Test
	public void magicShowsOnlyWhenDrained()
	{
		final SidePanelModel.Drain d = SidePanelExamples.drain(200, 140, 200, 0, 0, 50, 4, 0, 0);
		d.specs.put("EYE_OF_AYAK", new int[]{1, 1});
		d.magicStart = 150;
		d.magicEnd = 120;
		assertEquals(Arrays.asList("Magic | 150 → 120", "Eye of ayak | 1 of 1 landed"), rows(SidePanelModel.defence(d)));
	}

	@Test
	public void weaponsAreNamedAsTheGamesItemsAre()
	{
		assertEquals("Dragon warhammer", SidePanelModel.weaponName("DRAGON_WARHAMMER"));
		assertEquals("Tonalztics of ralos", SidePanelModel.weaponName("TONALZTICS_OF_RALOS"));
		assertEquals("Dinh's bulwark", SidePanelModel.weaponName("BULWARK"));
		assertEquals("Not a weapon", SidePanelModel.weaponName("NOT_A_WEAPON"));
	}

	// ---- from a room's fights (T-049's record, T-050's figures) ----

	private static Fight fight(String name, long start, DrainRecord record, double value, double specLuck)
	{
		final Fight f = new Fight(name, NpcIds.GOBLIN, 1, 700, start, null, 0);
		for (int i = 0; i < 30; i++)
		{
			f.recordAttackMade(false, 0.92);
			f.recordExpected(40, 0.55, 12, 140);
			f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
		}
		for (int i = 0; i < 90; i++)
		{
			f.recordTickSpent();
		}
		if (record != null)
		{
			f.recordDrain(record);
		}
		if (!Double.isNaN(value))
		{
			f.recordSpecOutcomes(-specLuck, 0, value, value - specLuck);
		}
		return f;
	}

	private static DrainRecord record(int start, int end, int thrown, int landed, int party, boolean immune)
	{
		final DrainRecord r = new DrainRecord();
		r.immune = immune;
		r.defenceBase = 250;
		r.defenceFloor = immune ? 250 : 0;
		r.magicBase = 80;
		r.magicFloor = 0;
		r.defenceStart = start;
		r.defenceEnd = end;
		r.magicStart = 80;
		r.magicEnd = 80;
		if (thrown > 0)
		{
			r.specs.put("DRAGON_WARHAMMER", new int[]{thrown, landed});
		}
		r.partyDrains = party;
		return r;
	}

	@Test
	public void aRoomsDrainsAreReadFromItsFights()
	{
		final Encounter room = new Encounter("General Graardor", null, 0L);
		room.add(fight("General Graardor", 1000, record(250, 175, 2, 1, 0, false), 120, -20));
		room.add(fight("General Graardor", 60_000, record(175, 175, 0, 0, 1, false), 62, 0));
		final SidePanelModel.Drain d = SidePanelModel.drain(room);
		assertNotNull(d);
		assertEquals(250, d.defenceStart);
		assertEquals(175, d.defenceEnd);
		assertEquals(250, d.defenceBase);
		assertEquals(0, d.defenceFloor);
		assertEquals(2, d.specs.get("DRAGON_WARHAMMER")[0]);
		assertEquals(1, d.specs.get("DRAGON_WARHAMMER")[1]);
		assertEquals(1, d.partyDrains);
		assertEquals(182, d.valueDamage, 1e-9);
		assertEquals(-20, d.specLuckDamage, 1e-9);
		assertEquals(room.luckSeconds(182), d.valueSeconds, 1e-9);
		assertTrue(d.valueSeconds > 0);
		assertTrue(d.specLuckSeconds < 0);
	}

	@Test
	public void nothingForAnImmuneTargetAnOldKillOrNoSpecials()
	{
		final Encounter immune = new Encounter("Vardorvis", null, 0L);
		immune.add(fight("Vardorvis", 1000, record(250, 250, 1, 0, 0, true), Double.NaN, Double.NaN));
		assertNull(SidePanelModel.drain(immune));
		// Saved before T-049: no record.
		final Encounter old = new Encounter("General Graardor", null, 0L);
		old.add(fight("General Graardor", 1000, null, Double.NaN, Double.NaN));
		assertNull(SidePanelModel.drain(old));
		// A record, but nothing thrown by anyone.
		final Encounter none = new Encounter("General Graardor", null, 0L);
		none.add(fight("General Graardor", 1000, record(250, 250, 0, 0, 0, false), 0, 0));
		assertNull(SidePanelModel.drain(none));
	}

	// ---- live ----

	private static DrainTracker.Tracked tracked(int defence, int magic, int floor, boolean immune)
	{
		final DrainRule.Target target = new DrainRule.Target(250, 80, 0, 200, 200, 1, floor, 0, immune, false, false, false);
		final DrainTracker.Tracked t = new DrainTracker.Tracked(5, 1, "General Graardor", target);
		t.state = new DrainRule.State(defence, magic, 0, 200, 200, 1);
		return t;
	}

	@Test
	public void theLiveLineOnceADrainHasLanded()
	{
		assertNull(SidePanelModel.liveDefence(null));
		assertNull("nothing drained yet", SidePanelModel.liveDefence(tracked(250, 80, 0, false)));
		assertNull("immune", SidePanelModel.liveDefence(tracked(200, 80, 0, true)));
		assertEquals("defence 175 / 250", SidePanelModel.liveDefenceText(SidePanelModel.liveDefence(
			tracked(175, 80, 0, false))).text);
		assertEquals("defence 150 (floor)", SidePanelModel.liveDefenceText(SidePanelModel.liveDefence(
			tracked(150, 80, 150, false))).text);
		assertEquals("defence 175 / 250 · magic 60 / 80", SidePanelModel.liveDefenceText(SidePanelModel.liveDefence(
			tracked(175, 60, 0, false))).text);
	}

	@Test
	public void theHeadersLiveLineAppearsOnceThenUpdatesInPlace()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelModel.Input before = SidePanelExamples.liveDrained();
		before.liveDefence = null;
		panel.apply(new PanelSnapshot(SidePanelModel.fight(before)));
		assertTrue(SidePanelModel.fight(before).header.defence.isEmpty());
		final SidePanelFightTab tab = panel.fightTab();
		final int builds = tab.builds();
		// The first drain lands: the line comes, one rebuild.
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.liveDrained())));
		assertEquals(builds + 1, tab.builds());
		assertTrue(texts(tab).contains("defence 175 / 250"));
		// A second hammer: the same line, set in place.
		final SidePanelModel.Input later = SidePanelExamples.liveDrained();
		later.liveDefence.defence = 123;
		panel.apply(new PanelSnapshot(SidePanelModel.fight(later)));
		assertEquals(builds + 1, tab.builds());
		assertTrue(texts(tab).contains("defence 123 / 250"));
		// The fight over: the line goes, the card has it.
		final SidePanelModel.Input ended = SidePanelExamples.liveDrained();
		ended.ended = true;
		assertTrue(SidePanelModel.fight(ended).header.defence.isEmpty());
	}

	// ---- trip ----

	@Test
	public void theTripSumsTheDrainsAndTheSpecialsLuck()
	{
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(SidePanelExamples.tripWithDrains());
		assertNotNull(trip.drains);
		final List<String> rows = new ArrayList<>();
		for (PanelSnapshot.Row r : trip.drains.rows)
		{
			rows.add(r.toString());
		}
		assertEquals(Arrays.asList("expected dmg added | +338 · 45 ticks faster", "your specials' luck | 2 ticks faster"), rows);
		assertTrue(trip.structure().contains("|dr2"));
		// None known, or nothing worth showing: no section.
		assertNull(SidePanelModel.trip(SidePanelExamples.trip()).drains);
		assertNull(SidePanelModel.tripDrains(SidePanelExamples.drain(-1, -1, -1, -1, 0, 0, 0, 0.2, 0.01)));
	}

	// ---- no lag ----

	@Test
	public void theCardIsBuiltOnceAndUpdatedInPlace()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.twoHammersOneLanded())));
		final SidePanelFightTab tab = panel.fightTab();
		final int builds = tab.builds();
		final int layouts = SidePanelParts.relayouts;
		for (int i = 0; i < 10; i++)
		{
			panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.twoHammersOneLanded())));
		}
		assertEquals(builds, tab.builds());
		assertEquals(layouts, SidePanelParts.relayouts);
		assertTrue(texts(tab).contains("Defence"));
	}

	/**
	 * Timed as T-045's worst cases are: the after-kill snapshot with its Defence
	 * card built and applied, warmed up first. Printed; it fails only if the work
	 * breaks, since a CI runner's clock says nothing about Eric's PC.
	 */
	@Test
	public void snapshotAndApplyTimed()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelModel.Input in = SidePanelExamples.twoHammersOneLanded();
		final PerfProbe.Timer t = new PerfProbe.Timer(1000);
		for (int i = 0; i < 1300; i++)
		{
			final long started = System.nanoTime();
			panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
			if (i >= 300)
			{
				t.add(System.nanoTime() - started);
			}
		}
		assertEquals(1000, t.count());
		System.out.println("T-052: " + PerfProbe.timerSummary("after-kill snapshot with the Defence card, built and applied", t));
	}

	private static List<String> texts(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelText)
			{
				out.add(((SidePanelText) k).getText());
			}
			if (k instanceof Container)
			{
				out.addAll(texts((Container) k));
			}
		}
		return out;
	}
}

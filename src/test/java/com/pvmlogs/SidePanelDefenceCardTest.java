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
 * T-076, from Eric's recorded Sire kill: the Defence card in short labelled rows under a drained bar (his pick of
 * three layouts), no zero "Not matched to an attack" row, one expectation on the panel (or the chart saying which it
 * is), and {@code hits} counting the hits that did damage.
 */
public class SidePanelDefenceCardTest
{
	private static List<String> rows(List<PanelSnapshot.Row> rows)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : rows)
		{
			out.add(r.toString());
		}
		return out;
	}

	// ---- the card ----

	@Test
	public void theSireKillsCardInLabelledRows()
	{
		final PanelSnapshot.Defence d = SidePanelModel.fight(SidePanelExamples.sireEmberlight()).defence;
		assertEquals("250 → 174", d.hint.text);
		// No row repeats the drop, no floor where the boss has none, no sentences.
		assertEquals(Arrays.asList("Emberlight | 2 of 2 landed", "Damage added | +42 · 9 ticks faster",
			"Specials' luck | +12 · 3 ticks faster"), rows(SidePanelFightTab.defenceRows(d)));
		assertEquals(SidePanelStyle.BETTER, d.specLuck.colour);
		for (String r : rows(SidePanelFightTab.defenceRows(d)))
		{
			assertFalse(r, r.startsWith("defence") || r.startsWith("floor") || r.endsWith("none") || r.endsWith("."));
			assertTrue(r, Character.isUpperCase(r.charAt(0)));
		}
	}

	@Test
	public void aBossWithAFloorHasItsRowAndItsTick()
	{
		final PanelSnapshot.Defence d = SidePanelModel.fight(SidePanelExamples.floorNotReached()).defence;
		assertEquals(Arrays.asList("Floor | 102", "Dragon warhammer | 1 of 2 landed"), rows(d.rows));
		assertEquals(102, d.drop.floor);
		assertEquals("40 (24%)", d.drop.value.text);
		// Without one, the bar has no tick.
		assertEquals(0, SidePanelModel.fight(SidePanelExamples.sireEmberlight()).defence.drop.floor);
		// Unknown levels: no bar.
		assertNull(SidePanelModel.drop(SidePanelExamples.drain(-1, -1, 250, 0, 1, 1, 1, 0, 0)));
	}

	@Test
	public void theDrainedBarThenTheRows()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.sireEmberlight())));
		final List<String> texts = texts(panel.fightTab());
		assertEquals(1, count(panel.fightTab(), SidePanelDrainBar.class));
		final int drained = texts.indexOf("Drained");
		assertTrue(texts.toString(), drained >= 0);
		assertEquals("76 (30%)", texts.get(drained + 1));
		assertEquals(Arrays.asList("Emberlight", "2 of 2 landed", "Damage added", "+42 · 9 ticks faster", "Specials' luck",
			"+12 · 3 ticks faster"), texts.subList(drained + 2, drained + 8));
		assertFalse(texts.toString(), texts.contains("floor"));
		// Levels unknown: the rows alone.
		final SidePanelModel.Input unknown = SidePanelExamples.sireEmberlight();
		unknown.drain.defenceStart = -1;
		final PvmPerformancePanel other = new PvmPerformancePanel(null, null, null, null);
		other.apply(new PanelSnapshot(SidePanelModel.fight(unknown)));
		assertEquals(0, count(other.fightTab(), SidePanelDrainBar.class));
		assertTrue(texts(other.fightTab()).contains("Specials' luck"));
	}

	@Test
	public void theCardIsBuiltOnceAndUpdatedInPlace()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.floorNotReached())));
		final SidePanelFightTab tab = panel.fightTab();
		SidePanelPictures.draw(panel.tabs(), tab, 1);
		final int builds = tab.builds();
		final int layouts = SidePanelParts.relayouts;
		for (int i = 0; i < 10; i++)
		{
			panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.floorNotReached())));
		}
		assertEquals(builds, tab.builds());
		assertEquals(layouts, SidePanelParts.relayouts);
	}

	// ---- no zero "Not matched" row ----

	private static List<String> sources(PanelSnapshot.Sources s)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Source r : s.rows)
		{
			out.add(r.name + " " + r.damage);
		}
		return out;
	}

	@Test
	public void noNotMatchedRowWithNothingInIt()
	{
		// The Sire kill: the rows hold every hit, and their expectation as thrown is the scope's as thrown (576 + 12).
		final List<String> rows = sources(SidePanelModel.fight(SidePanelExamples.sireEmberlight()).damage);
		assertEquals(Arrays.asList("Abyssal tentacle 399", "Emberlight 47", "Thrall 80"), rows);
		// The recording's row, by its mechanism: the tile's expectation 12 under the rows' because the specials landed
		// well. Measured against the averaged figure (no spec luck known) it is a row of 0; against as thrown, none.
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.expectedDamage -= 12;
		assertTrue(sources(SidePanelModel.fight(in).damage).contains("Not matched to an attack 0"));
		s.specLuckDamage = 12;
		assertFalse(sources(SidePanelModel.fight(in).damage).stream().anyMatch(r -> r.startsWith("Not matched")));
	}

	@Test
	public void theCatchAllStaysForAHeldExpectation()
	{
		// An attack's expectation held before its fight booked any attack (the replayed logs have 8 to 43 of it), so
		// that the rows still add up to the chart's line.
		final SidePanelModel.Input held = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) held.scope).expectedDamage += 30;
		final PanelSnapshot.Source row = SidePanelModel.fight(held).damage.rows.stream()
			.filter(r -> r.name.startsWith("Not matched")).findFirst().orElse(null);
		assertNotNull(row);
		assertEquals("0", row.damage);
		// T-112 item 6: in brackets, as the DPS rows have it.
		assertEquals("weapon damage (expected 30)", row.sub);
	}

	@Test
	public void theCatchAllStaysWhenItHoldsDamage()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) in.scope).damage += 25;
		assertTrue(sources(SidePanelModel.fight(in).damage).contains("Not matched to an attack 25"));
	}

	// ---- one expectation ----

	@Test
	public void theChartSaysItsExpectationIsAsThrownAndTheDifferenceIsTheSpecialsLuck()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.sireEmberlight());
		assertEquals("446/576", tab.tiles.get(1).value.text);
		assertEquals("expected 588 as thrown", tab.chart.expectedLegend);
		// The Damage rows' expectations add up to the chart's (T-085: in each row's details).
		int held = 0;
		for (PanelSnapshot.Source s : tab.damage.rows)
		{
			final java.util.regex.Matcher m = java.util.regex.Pattern.compile("expected (\\d+) \\(").matcher(
				s.tooltip == null ? "" : s.tooltip);
			held += m.find() ? Integer.parseInt(m.group(1)) : 0;
		}
		assertEquals(588, held);
		// And the Defence card's specials row is the difference.
		assertEquals("+12", tab.defence.specLuck.damage);
	}

	@Test
	public void withoutSpecialsLuckTheChartAndTileShowOneFigure()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.dead());
		final String tile = tab.tiles.get(1).value.text;
		assertEquals("expected " + tile.substring(tile.indexOf('/') + 1), tab.chart.expectedLegend);
	}

	// ---- hits that did damage ----

	@Test
	public void hitsAreTheOnesThatDidDamage()
	{
		final PanelSnapshot.Rows taken = SidePanelModel.fight(SidePanelExamples.sireEmberlight()).taken;
		assertEquals("27", taken.hint.text);
		assertEquals(Arrays.asList("hits | 6", "missed or blocked | 94", "biggest hit | 8", "without protection | 0"),
			rows(taken.rows));
	}

	@Test
	public void anOldKillKeepsItsOneNumber()
	{
		// Saved before T-076: no 0s counted apart, so its hits are the count it always showed.
		final List<String> r = rows(SidePanelModel.fight(SidePanelExamples.dead()).taken.rows);
		assertTrue(r.toString(), r.contains("hits | 14"));
		assertFalse(r.toString(), r.stream().anyMatch(s -> s.startsWith(SidePanelModel.MISSED)));
	}

	@Test
	public void theFightAndItsScopesCountThe0s()
	{
		final Fight a = new Fight("Abyssal Sire", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		final Fight b = new Fight("Abyssal Sire", NpcIds.GOBLIN, 2, 700, 0L, null, 0);
		final SessionTotals trip = new SessionTotals(0L);
		for (int hit : new int[]{0, 8, 0, 0, 3})
		{
			a.recordHitTaken(hit, false, 0L);
			trip.recordHitTaken(hit, false);
		}
		b.recordHitTaken(0, true, 0L);
		assertEquals(5, a.getHitsTaken());
		assertEquals(3, a.getMissedHitsTaken());
		assertEquals(3, trip.getMissedHitsTaken());
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		room.add(a);
		room.add(b);
		assertEquals(6, room.getHitsTaken());
		assertEquals(4, room.getMissedHitsTaken());
		final SidePanelModel.Input in = new SidePanelModel.Input();
		SidePanelModel.fillRoom(in, room);
		assertEquals(4, in.missedHitsTaken);
		trip.reset(0L);
		assertEquals(0, trip.getMissedHitsTaken());
	}

	@Test
	public void aFightSavedBeforeT076ReadsNo0s()
	{
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final Fight f = new Fight("Abyssal Sire", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordHitTaken(0, false, 0L);
		f.recordHitTaken(5, false, 0L);
		final com.google.gson.JsonObject json = gson.toJsonTree(f).getAsJsonObject();
		assertEquals(1, json.get("missedHitsTaken").getAsInt());
		json.remove("missedHitsTaken");
		final Fight old = gson.fromJson(json, Fight.class);
		assertEquals(2, old.getHitsTaken());
		assertEquals(0, old.getMissedHitsTaken());
	}

	// ---- the time budget with an average far shorter than the kill (not a fix: a check) ----

	@Test
	public void anAverageFarShorterThanTheKillReadsPlainly()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.sireShortAverage());
		assertNotNull(tab.budget);
		assertEquals("[your average 0:35 | 82 ticks slower]", tab.budget.average.toString());
		assertEquals(35, tab.budget.marker, 1e-9);
	}

	private static int count(Container c, Class<?> type)
	{
		int n = 0;
		for (Component k : c.getComponents())
		{
			if (type.isInstance(k))
			{
				n++;
			}
			if (k instanceof Container)
			{
				n += count((Container) k, type);
			}
		}
		return n;
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

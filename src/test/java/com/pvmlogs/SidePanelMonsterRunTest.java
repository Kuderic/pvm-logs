package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * T-095: a run of monsters (T-089) in the side panel: kills, time, kills/hr and DPS against expected, live and in
 * History, with no Luck or Execution; a kill only with kills; and none of the runs in the trip's Luck and Execution.
 */
public class SidePanelMonsterRunTest
{
	private static final String DEMON = SidePanelRunExamples.DEMON;

	// ---- the Fight tab, live ----

	@Test
	public void aLiveRunShowsKillsTimeKillsPerHourAndDps()
	{
		final SidePanelModel.Input in = SidePanelRunExamples.live();
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("Abyssal demon ×12", t.header.name.text);
		assertEquals(PanelSnapshot.State.LIVE, t.state);
		assertEquals(labels(t.tiles), "Kills, Kills/hr, DPS, Damage");
		assertEquals("12", tile(t.tiles, "Kills").value.text);
		// Kills over the run's own clock, the overlay's (T-094): 3:40, its start to now, the respawn wait and all, where
		// the combat clock is 3:11.
		assertEquals(220.0, in.runSeconds, 1e-9);
		assertEquals("3:40", t.header.clock.text);
		assertEquals(String.valueOf(Math.round(12 * 3600 / 220.0)), tile(t.tiles, "Kills/hr").value.text);
		assertEquals("over 3:40", tile(t.tiles, "Kills/hr").sub.text);
		assertEquals(LedgerRows.State.run(DEMON, 12, 220, false, false).runSeconds, in.runSeconds, 1e-9);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		assertEquals(LedgerRows.oneDecimal(n.dps), tile(t.tiles, "DPS").value.text);
		assertEquals("expected " + LedgerRows.oneDecimal(n.expected / n.scoredClock), tile(t.tiles, "DPS").sub.text);
		for (PanelSnapshot.Tile tile : t.tiles)
		{
			// Neutral: no luck colours on a run.
			assertEquals(tile.label.text, SidePanelStyle.TRACK, tile.edge);
		}
	}

	@Test
	public void aRunHasNoLuckNorExecutionNorAnythingMadeOfThem()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelRunExamples.saved());
		assertNull(t.budget);
		assertNull(t.curve);
		assertNull(t.noCurve);
		assertNull(t.execution);
		assertNull(t.defence);
		for (PanelSnapshot.Tile tile : t.tiles)
		{
			assertFalse(tile.label.text, tile.label.text.contains("Luck") || tile.label.text.contains("Execution"));
		}
		for (PanelSnapshot.Row r : t.details == null ? new ArrayList<PanelSnapshot.Row>() : t.details.rows)
		{
			assertFalse(r.left.text, r.left.text.contains("eating") || r.left.text.contains("hits/expected"));
		}
	}

	@Test
	public void aRunKeepsItsDamageCardChartAndDamageTaken()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelRunExamples.saved());
		assertNotNull(t.damage);
		assertEquals("Abyssal tentacle", t.damage.rows.get(0).name);
		assertNotNull(t.chart);
		assertNotNull(t.taken);
		// The strip has no lost or eating colours: no legend entry, every idle tick drawn as track.
		assertEquals(-1, t.chart.lost);
		assertEquals(-1, t.chart.eating);
		for (byte s : t.chart.states)
		{
			assertTrue(s != FightTimeline.LOST && s != FightTimeline.EATING);
		}
	}

	@Test
	public void killsPerHourWaitsForAMinute()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelRunExamples.liveUnderAMinute());
		assertEquals("Abyssal demon ×1", t.header.name.text);
		assertEquals(LedgerRows.UNKNOWN, tile(t.tiles, "Kills/hr").value.text);
		assertEquals("after a minute", tile(t.tiles, "Kills/hr").sub.text);
	}

	@Test
	public void aRunWithNoKillYetIsItsNameAlone()
	{
		final SidePanelModel.Input in = SidePanelRunExamples.live(SidePanelRunExamples.demonRun(0));
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals(DEMON, t.header.name.text);
		assertEquals("none yet", tile(t.tiles, "Kills").sub.text);
	}

	@Test
	public void aKillChangesTextsOnlyNotTheTabsStructure()
	{
		final SidePanelTimelineExamples.Built b = SidePanelRunExamples.demonRun(11);
		final PanelSnapshot.FightTab before = SidePanelModel.fight(SidePanelRunExamples.live(b));
		b.fight.recordKill();
		final PanelSnapshot.FightTab after = SidePanelModel.fight(SidePanelRunExamples.live(b));
		assertEquals(before.structure(), after.structure());
		assertEquals("Abyssal demon ×11", before.header.name.text);
		assertEquals("Abyssal demon ×12", after.header.name.text);
	}

	// ---- the Fight tab, from History ----

	@Test
	public void aSavedRunReadsEndedNeverDeadAndHasNoAverage()
	{
		final SidePanelModel.Input in = SidePanelRunExamples.saved();
		assertTrue(in.run);
		assertTrue(in.killed);
		assertNull(in.average);
		assertNull(in.luckRemoved);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("Abyssal demon ×12", t.header.name.text);
		// No " (ended)": "saved run" says so, and the name with its kills fills the line beside the clock.
		assertEquals("", t.header.suffix.text);
		assertEquals("today 3:03 pm · saved run", t.header.sub.text);
		assertEquals(labels(t.tiles), "Kills, Kills/hr, DPS, Damage");
	}

	@Test
	public void aRunThatHasEndedSaysSoOnItsSubLine()
	{
		final SidePanelModel.Input in = SidePanelRunExamples.live();
		in.ended = true;
		in.killed = true;
		in.endMillis = SidePanelExamples.at(15, 20);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("", t.header.suffix.text);
		assertTrue(t.header.sub.text, t.header.sub.text.endsWith("ended 3:20 pm"));
	}

	@Test
	public void theLastFightRowOfARunHasItsKillsPerHour()
	{
		final SidePanelModel.Input in = SidePanelExamples.preAttack();
		in.lastFight = SidePanelModel.lastFight(savedRoom(), true);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("Abyssal demon ×12", t.lastFight.name.text);
		assertEquals(" (ended)", t.lastFight.suffix.text);
		assertTrue(t.lastFight.chip.text, t.lastFight.chip.text.endsWith("/hr"));
		assertEquals(SidePanelStyle.CHIP_NEUTRAL, t.lastFight.chipFill);
	}

	// ---- History ----

	@Test
	public void aRunIsAKillOnlyWithKills()
	{
		final List<Fight> none = reversed(SidePanelRunExamples.runFights(SidePanelExamples.at(15, 0), 3, 0, 5));
		final Encounter empty = SidePanelHistory.allRooms(none).get(0);
		assertTrue(empty.isMonsterRoom());
		assertFalse(SidePanelHistory.isKill(empty));
		final List<Fight> some = reversed(SidePanelRunExamples.runFights(SidePanelExamples.at(15, 0), 3, 1, 5));
		assertTrue(SidePanelHistory.isKill(SidePanelHistory.allRooms(some).get(0)));
	}

	@Test
	public void theKillsListShowsARunAsItsKillsTimeAndKillsPerHour()
	{
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(SidePanelRunExamples.history(), DEMON,
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE);
		// Three runs, newest first, then the three demons saved one fight each before T-089.
		assertEquals(6, rows.size());
		final SidePanelHistory.KillRow newest = rows.get(0);
		assertEquals("Abyssal demon ×15", newest.name);
		assertEquals(15, newest.kills);
		assertEquals("", newest.execution);
		final double seconds = newest.room.durationMillis() / 1000.0;
		assertEquals(Math.round(15 * 3600 / seconds) + "/hr", newest.chip.text);
		assertEquals(SidePanelStyle.CHIP_NEUTRAL, newest.chipFill);
		// The run's own clock, its first fight's start to its last attack.
		assertEquals(LedgerRows.duration(seconds), newest.clock);
		assertFalse(newest.ended);
		for (int i = 3; i < 6; i++)
		{
			// Old history, unchanged: a row per demon, no kills.
			assertEquals(-1, rows.get(i).kills);
			assertEquals(DEMON, rows.get(i).name);
		}
	}

	@Test
	public void theBossListCountsRunsAndKillsWithTheBestKillsPerHour()
	{
		SidePanelHistory.BossEntry demons = null;
		for (SidePanelHistory.BossEntry e : SidePanelHistory.bosses(SidePanelRunExamples.history(), false, ""))
		{
			if (DEMON.equals(e.name))
			{
				demons = e;
			}
		}
		assertNotNull(demons);
		// 12 + 9 + 15 in runs, and the three saved one fight each before T-089.
		assertEquals("3 runs · 39 kills", demons.right());
		assertTrue(demons.sub(), demons.sub().matches("best \\d+/hr"));
		assertFalse(demons.right().contains("trip"));
	}

	@Test
	public void aKindsCardIsItsRunsNotItsAverageTime()
	{
		final SidePanelHistory.Card card = SidePanelHistory.card(SidePanelRunExamples.history(), DEMON, null, null);
		assertEquals(3, card.runs);
		final List<String> labels = new ArrayList<>();
		for (PanelSnapshot.Tile t : card.tiles)
		{
			labels.add(t.label.text);
		}
		assertEquals("[best kills/hr, kills/hr, DPS, taken per kill]", labels.toString());
	}

	@Test
	public void oldPerFightHistoryIsUnchanged()
	{
		final List<Fight> old = SidePanelHistoryExamples.history();
		for (SidePanelHistory.BossEntry e : SidePanelHistory.bosses(old, false, ""))
		{
			if (DEMON.equals(e.name))
			{
				assertEquals("3 kills", e.right());
				assertEquals("not a boss", e.sub());
			}
		}
		final SidePanelHistory.Card card = SidePanelHistory.card(old, DEMON, null, null);
		assertEquals(0, card.runs);
		for (SidePanelHistory.KillRow r : SidePanelHistory.kills(old, DEMON, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE))
		{
			assertEquals(-1, r.kills);
		}
	}

	// ---- the Trip tab ----

	@Test
	public void aTripsLuckAndExecutionLeaveItsRunsOut()
	{
		final SidePanelModel.Input in = SidePanelRunExamples.trip();
		final PanelSnapshot.TripTab t = SidePanelModel.trip(in);
		assertEquals(SidePanelModel.LUCK_NO_RUNS, t.tiles.get(0).label.text);
		assertEquals(SidePanelModel.EXECUTION_NO_RUNS, t.tiles.get(3).label.text);
		// The Vardorvis kill's own Luck and Execution.
		final Encounter vardorvis = new Encounter("Vardorvis", null, 0L);
		for (Fight f : SidePanelHistory.allRooms(SidePanelRunExamples.tripHistory()).get(1).getFights())
		{
			vardorvis.add(f);
		}
		final List<PanelSnapshot.Tile> alone = SidePanelModel.tiles(new LedgerRows.Numbers(LedgerRows.of(vardorvis)),
			LedgerRows.of(vardorvis), in, LedgerRows.Colour.NEUTRAL, false);
		assertEquals(alone.get(0).value.text, t.tiles.get(0).value.text);
		assertEquals(alone.get(3).value.text, t.tiles.get(3).value.text);
		// Damage and DPS are the whole trip's, runs and all.
		final LedgerRows.Numbers whole = new LedgerRows.Numbers(in.scope);
		assertTrue(t.tiles.get(1).value.text, t.tiles.get(1).value.text.startsWith(LedgerRows.number(whole.damage)));
		assertNotNull(t.execution);
		assertTrue(t.bossesOnly());
		assertFalse(SidePanelModel.trip(SidePanelExamples.trip()).bossesOnly());
		// The kill and the run's 12.
		assertEquals("13 kills", t.roomsHint.text);
		assertEquals(2, t.rooms.size());
		assertEquals(DEMON, t.rooms.get(0).name);
		assertEquals(" ×12", t.rooms.get(0).suffix);
		assertEquals(12, t.rooms.get(0).kills);
		assertEquals("Vardorvis", t.rooms.get(1).name);
		assertEquals("", t.rooms.get(1).suffix);
	}

	@Test
	public void aTripOfRunsOnlyHasNoLuckNorExecution()
	{
		final PanelSnapshot.TripTab t = SidePanelModel.trip(SidePanelRunExamples.tripOfRunsOnly());
		assertEquals(LedgerRows.UNKNOWN, t.tiles.get(0).value.text);
		assertEquals("runs only", t.tiles.get(0).sub.text);
		assertEquals(LedgerRows.UNKNOWN, t.tiles.get(3).value.text);
		assertNull(t.execution);
		assertEquals("12 kills", t.roomsHint.text);
	}

	@Test
	public void aTripWithoutRunsIsUnchanged()
	{
		final SidePanelModel.Input in = SidePanelExamples.trip();
		assertNull(in.luckScope);
		final PanelSnapshot.TripTab t = SidePanelModel.trip(in);
		assertEquals("Luck", t.tiles.get(0).label.text);
		assertEquals("6 kills · 1 ended", t.roomsHint.text);
	}

	// ---- bosses ----

	@Test
	public void aBossIsUnchanged()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		assertFalse(in.run);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("Vardorvis", t.header.name.text);
		assertEquals(" (dead)", t.header.suffix.text);
		assertEquals("Luck", t.tiles.get(0).label.text);
		assertNotNull(t.budget);
		assertNotNull(t.execution);
	}

	private static Encounter savedRoom()
	{
		final SidePanelTimelineExamples.Built b = SidePanelRunExamples.demonRun(12);
		b.fight.end(true, SidePanelRunExamples.START + SidePanelRunExamples.RUN_MILLIS);
		b.fight.closeRoom();
		return SidePanelHistory.allRooms(java.util.Collections.singletonList(b.fight)).get(0);
	}

	private static List<Fight> reversed(List<Fight> oldestFirst)
	{
		final List<Fight> out = new ArrayList<>(oldestFirst);
		java.util.Collections.reverse(out);
		return out;
	}

	private static PanelSnapshot.Tile tile(List<PanelSnapshot.Tile> tiles, String label)
	{
		for (PanelSnapshot.Tile t : tiles)
		{
			if (t.label.text.equals(label))
			{
				return t;
			}
		}
		throw new AssertionError("no tile " + label);
	}

	private static String labels(List<PanelSnapshot.Tile> tiles)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Tile t : tiles)
		{
			out.add(t.label.text);
		}
		return String.join(", ", out);
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * T-094: the fight overlay's light view for a run of monsters (T-089): the
 * kind with its kills, the run's clock, kills/hr and DPS against expected; no
 * Luck, no Execution, no Summary, no defence rows. It reads as running until
 * the run ends, and runs never feed a boss's average.
 */
public class MonsterRunOverlayTest
{
	private static final String DEMON = "Abyssal demon";
	private static final int CATACOMBS_DEMON = 7241;
	private static final String VORKATH = "Vorkath";
	private static final int VORKATH_ID = 8061;
	private static final long MINUTE = 60_000;

	private final List<Encounter> askedForAverage = new ArrayList<>();
	private final Function<Encounter, LedgerHistory.Average> average = room ->
	{
		askedForAverage.add(room);
		return new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10);
	};

	/** Three demons, all dead, the run still open: MonsterRoomTest's run. */
	private static MonsterRunReplay threeDemons()
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(26).splash(DEMON, 2, 49).expect(24.7, 369.6);
		run.attack().hit(30).splash(DEMON, 2, 20).splash(DEMON, 3, 10).expect(24.7, 369.6);
		run.fight(DEMON, CATACOMBS_DEMON, 2, 150).attack().hit(81).expect(24.7, 369.6);
		run.dies(2).loot(CATACOMBS_DEMON, 2);
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(94).splash(DEMON, 3, 140).expect(24.7, 369.6);
		run.dies(3).loot(CATACOMBS_DEMON, 3);
		run.dies(1).loot(CATACOMBS_DEMON, 1);
		return run;
	}

	private LedgerRows.State state(MonsterRunReplay run, boolean warning, long now)
	{
		return PvmPerformanceOverlay.shownRoomState(run.open, run.room, warning, average, null, now);
	}

	private List<LedgerRows.Row> rows(MonsterRunReplay run, boolean warning, long now)
	{
		return LedgerRows.build(LedgerRows.of(run.room), state(run, warning, now), new LedgerRows.Settings());
	}

	private static List<String> lefts(List<LedgerRows.Row> rows)
	{
		final List<String> out = new ArrayList<>();
		for (LedgerRows.Row row : rows)
		{
			out.add(row.left);
		}
		return out;
	}

	private static LedgerRows.Row row(List<LedgerRows.Row> rows, String left)
	{
		for (LedgerRows.Row row : rows)
		{
			if (row.left.equals(left))
			{
				return row;
			}
		}
		return null;
	}

	private static void assertLight(List<LedgerRows.Row> rows)
	{
		for (String left : lefts(rows))
		{
			assertFalse(left, left.equals("Luck") || left.equals("Execution") || left.equals(LedgerRows.SUMMARY)
				|| left.startsWith("Damage") || left.startsWith(" defence") || left.startsWith(" uptime")
				|| left.startsWith(" damage taken") || left.startsWith(" eating"));
		}
	}

	// ---- mid-trip ----

	@Test
	public void aRunMidTripShowsItsKillsClockAndDpsAndNothingElse()
	{
		final MonsterRunReplay run = threeDemons();
		final Encounter room = run.room;
		assertTrue(room.isMonsterRoom());
		// The fight on show has ended with its demon; the run hasn't.
		assertTrue(run.open.isEnded());
		final long now = room.getStartMillis() + 190_000;
		final LedgerRows.State state = state(run, false, now);
		assertEquals(LedgerRows.View.RUN, state.view);
		assertFalse(state.ended);
		final List<LedgerRows.Row> rows = rows(run, false, now);
		assertEquals(Arrays.asList(DEMON + " ×3", LedgerRows.KILLS_PER_HOUR, "DPS"), lefts(rows));
		// The run's clock, from its start to now: through the waits between kills.
		assertEquals("3:10", rows.get(0).right);
		assertEquals(LedgerRows.FontRole.EMPHASIS, rows.get(0).leftFont);
		// 3 kills over 190 s.
		assertEquals("57", rows.get(1).right);
		// The run's damage and expected damage, both over its scored combat time.
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(room));
		final String dps = LedgerRows.oneDecimal(n.damage / n.scoredClock);
		final String expected = LedgerRows.oneDecimal(n.expected / n.scoredClock);
		assertEquals(dps + " (expected " + expected + ")", rows.get(2).right);
		assertEquals(dps + "/" + expected, rows.get(2).shortRight);
		// Neutral, not luck's colours.
		assertEquals(LedgerRows.Colour.NEUTRAL, rows.get(2).rightColour);
		assertEquals(LedgerRows.Colour.NEUTRAL, rows.get(0).leftColour);
		assertLight(rows);
		// Never asked for a boss's average.
		assertTrue(askedForAverage.isEmpty());
	}

	@Test
	public void aRunReadsAsRunningThroughItsFightsEndsAndRespawnWaits()
	{
		final MonsterRunReplay run = threeDemons();
		// 40 s of respawn wait: under the pause.
		run.waits(40_000);
		assertFalse(run.room.isEnded());
		assertFalse(run.room.isOver());
		assertFalse(state(run, false, run.now).ended);
		// The overlay stays up through it whatever "Keep last fight up" says, unless "Bosses only" hides it.
		assertTrue(PvmPerformanceOverlay.holdsRun(run.open, run.room, false));
		assertFalse(PvmPerformanceOverlay.holdsRun(run.open, run.room, true));
		assertFalse(PvmPerformanceOverlay.onShow(run.open, 10, false, false, run.now));
		// The next demon: still the same run, still running.
		run.fight(DEMON, CATACOMBS_DEMON, 4, 150).attack().hit(40).expect(24.7, 369.6);
		assertFalse(state(run, false, run.now).ended);
		assertEquals(DEMON + " ×3", rows(run, false, run.now).get(0).left);
	}

	// ---- after its pause ----

	@Test
	public void aRunAfterItsPauseIsOverWithTheSameRowsAndTheClockStopped()
	{
		final MonsterRunReplay run = threeDemons();
		final Encounter room = run.room;
		final List<String> running = lefts(rows(run, false, room.getStartMillis() + 190_000));
		run.waits(MINUTE);
		assertTrue(room.isEnded());
		// T-070's isOver() agrees: a run is over once endMonsterRoom has run.
		assertTrue(room.isOver());
		final LedgerRows.State state = state(run, false, run.now);
		assertTrue(state.ended);
		assertFalse(PvmPerformanceOverlay.holdsRun(run.open, room, false));
		// Stopped at its last attack (T-089 ends it there), however long after it's looked at.
		final List<LedgerRows.Row> now = rows(run, false, run.now);
		final List<LedgerRows.Row> later = rows(run, false, run.now + 5 * MINUTE);
		assertEquals(LedgerRows.duration(room.durationMillis() / 1000.0), now.get(0).right);
		assertEquals(now.get(0).right, later.get(0).right);
		// The same rows; no "(dead)", no Summary. Under a minute of run, so no kills/hr.
		assertEquals(Arrays.asList(DEMON + " ×3", "DPS"), lefts(now));
		assertEquals(running.get(0), now.get(0).left);
		assertLight(now);
		assertTrue(askedForAverage.isEmpty());
	}

	// ---- no kills ----

	@Test
	public void aRunWithNoKillsHasNoTimesSignAndIsNoKill()
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(26).expect(24.7, 369.6);
		run.attack().hit(30).expect(24.7, 369.6);
		final List<LedgerRows.Row> rows = rows(run, false, run.room.getStartMillis() + 90_000);
		assertEquals(Arrays.asList(DEMON, LedgerRows.KILLS_PER_HOUR, "DPS"), lefts(rows));
		assertEquals("0", rows.get(1).right);
		run.waits(MINUTE);
		assertTrue(run.room.isOver());
		assertEquals(DEMON, rows(run, false, run.now).get(0).left);
		// History: rebuilt, it closed on its last fight, but with no kill in it it isn't one.
		final List<Encounter> saved = LedgerHistory.rooms(run.history, DEMON);
		assertEquals(1, saved.size());
		assertTrue(saved.get(0).isMonsterRoom());
		assertFalse(LedgerHistory.isKill(saved.get(0)));
	}

	// ---- kills/hr ----

	@Test
	public void killsPerHourWaitsForAMinute()
	{
		assertNull(LedgerRows.killsPerHour(1, 6));
		assertNull(LedgerRows.killsPerHour(5, 59.9));
		assertEquals("60", LedgerRows.killsPerHour(1, 60));
		assertEquals("227", LedgerRows.killsPerHour(12, 190));
		assertEquals("1,200", LedgerRows.killsPerHour(40, 120));
		assertNull(LedgerRows.killsPerHour(1, Double.NaN));
		final MonsterRunReplay run = threeDemons();
		final long start = run.room.getStartMillis();
		assertNull(row(rows(run, false, start + 59_000), LedgerRows.KILLS_PER_HOUR));
		assertEquals("180", row(rows(run, false, start + 60_000), LedgerRows.KILLS_PER_HOUR).right);
	}

	// ---- the warning ----

	@Test
	public void underTheWarningTheDpsHasNoExpected()
	{
		final MonsterRunReplay run = threeDemons();
		final List<LedgerRows.Row> rows = rows(run, true, run.room.getStartMillis() + 190_000);
		assertEquals(Arrays.asList(DEMON + " ×3", LedgerRows.KILLS_PER_HOUR, "DPS", LedgerRows.WARNING_TEXT),
			lefts(rows));
		assertFalse(row(rows, "DPS").right.contains("expected"));
		assertNull(row(rows, "DPS").shortRight);
		assertEquals(LedgerRows.Colour.WARNING, rows.get(3).leftColour);
		assertLight(rows);
	}

	@Test
	public void withNoMonsterDataTheDpsStandsAlone()
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		run.fight(DEMON, CATACOMBS_DEMON, 1, 150).attack().hit(26);
		final List<LedgerRows.Row> rows = rows(run, false, run.room.getStartMillis() + 5_000);
		assertEquals(Arrays.asList(DEMON, "DPS", " no monster data yet"), lefts(rows));
		assertFalse(row(rows, "DPS").right.contains("expected"));
	}

	// ---- History ----

	@Test
	public void runsCountAsKillsOnlyWithKillsAndNeverFeedAnAverage()
	{
		final MonsterRunReplay run = threeDemons();
		run.waits(MINUTE);
		final Encounter saved = LedgerHistory.rooms(run.history, DEMON).get(0);
		assertTrue(saved.isMonsterRoom());
		assertEquals(3, saved.getKills());
		assertTrue(LedgerHistory.isKill(saved));
		final Set<Fight> none = Collections.newSetFromMap(new IdentityHashMap<>());
		assertFalse(LedgerHistory.counts(saved, none));
		// Five like runs make no average.
		final MonsterRunReplay five = new MonsterRunReplay();
		for (int i = 0; i < 6; i++)
		{
			five.fight(DEMON, CATACOMBS_DEMON, 10 + i, 150).attack().hit(150).expect(24.7, 369.6);
			five.dies(10 + i).waits(MINUTE);
		}
		assertEquals(6, LedgerHistory.rooms(five.history, DEMON).size());
		assertNull(LedgerHistory.of(five.history, LedgerHistory.Like.of(five.room), null));
	}

	// ---- a boss beside it ----

	@Test
	public void aBossBesideARunIsUnchanged()
	{
		final MonsterRunReplay run = threeDemons();
		run.fight(VORKATH, VORKATH_ID, 9, 750).attack().hit(30).expect(24.7, 369.6);
		final Encounter vorkath = run.room;
		assertFalse(vorkath.isMonsterRoom());
		final LedgerRows.State state = state(run, false, run.now);
		assertEquals(LedgerRows.View.FIGHT, state.view);
		assertTrue(Double.isNaN(state.runSeconds));
		// The same state as T-070's room view, with Luck and Execution.
		final LedgerRows.State before = PvmPerformanceOverlay.roomState(run.open, vorkath, false, average);
		assertEquals(before.name, state.name);
		assertEquals(before.ended, state.ended);
		final List<String> rows = lefts(rows(run, false, run.now));
		assertTrue(rows.contains("Luck"));
		assertTrue(rows.contains("Execution"));
		assertTrue(rows.contains("Damage/expected"));
		assertEquals(VORKATH, rows.get(0));
		// The run it ended is over.
		assertTrue(run.rooms.get(0).isOver());
		assertFalse(PvmPerformanceOverlay.holdsRun(run.open, vorkath, false));
	}

	// ---- fit ----

	@Test
	public void aCutTitleKeepsItsKills()
	{
		final java.util.function.ToIntFunction<String> chars = String::length;
		assertEquals("Abyssal demon \u00d712", LedgerRows.cutKeepingCount("Abyssal demon \u00d712", chars, 17));
		assertEquals("Abyssal d\u2026 \u00d712", LedgerRows.cutKeepingCount("Abyssal demon \u00d712", chars, 14));
		// No count: cut from the end, as an attack's name is.
		assertEquals("Abyssal d\u2026", LedgerRows.cutKeepingCount("Abyssal demon", chars, 10));
		assertEquals("Abyssal tentac\u2026", LedgerRows.cutKeepingCount("Abyssal tentacle", chars, 15));
		// No room for any of the name: cut as a whole.
		assertEquals("A\u2026", LedgerRows.cutKeepingCount("Abyssal demon \u00d712", chars, 2));
	}


	@Test
	public void theRunsRowsFitInEveryFont()
	{
		final MonsterRunReplay run = threeDemons();
		final List<LedgerRows.Row> rows = rows(run, false, run.room.getStartMillis() + 190_000);
		final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		for (Font font : Arrays.asList(FontManager.getRunescapeFont(), FontManager.getRunescapeBoldFont(),
			FontManager.getRunescapeSmallFont()))
		{
			g.setFont(font);
			assertTrue(font.getName(), font.canDisplay('×'));
			// A long kind with three-figure kills and an hour on the clock.
			final List<LedgerRows.Row> long_ = LedgerRows.build(LedgerRows.of(run.room),
				LedgerRows.State.run("Greater abyssal demon", 120, 3_753, false, false), new LedgerRows.Settings());
			final PvmPerformanceOverlay.Lines wide = PvmPerformanceOverlay.lines(g, long_, null);
			assertNotNull(wide);
			assertTrue(font.getName() + " " + wide.width, wide.width < PvmPerformanceOverlay.MAX_WIDTH);
			// The usual run is no wider than a boss's kill: its widest row is its title.
			final int usual = PvmPerformanceOverlay.lines(g, rows, null).width;
			final int boss = PvmPerformanceOverlay.lines(g, LedgerRows.build(LedgerExamples.kill(),
				LedgerRows.State.fight("Vardorvis", true, true, false), new LedgerRows.Settings()), null).width;
			System.out.println("T-094 widths " + font.getName() + (font.isBold() ? " bold" : "") + ": run " + usual
				+ ", long run " + wide.width + ", Vardorvis kill " + boss);
			assertTrue(font.getName() + " " + usual + " > " + boss, usual <= boss);
			assertEquals("Greater abyssal demon ×120", wide.rows.get(0).left);
			assertEquals("1:02:33", wide.rows.get(0).right);
			// Saved at the narrowest width, the DPS pair gives way to its short form rather than wrap.
			final List<LedgerRows.Row> narrow = PvmPerformanceOverlay.lines(g, rows,
				new Dimension(PvmPerformanceOverlay.MIN_WIDTH, 0)).rows;
			// The title cuts the kind and keeps its kills rather than wrap.
			final LedgerRows.Row title = narrow.get(0);
			assertTrue(font.getName() + " " + title.left, title.left.endsWith(" \u00d73"));
			assertTrue(font.getName() + " " + title.left, title.left.startsWith("Abyssal"));
			final LedgerRows.Row dps = row(narrow, "DPS");
			assertFalse(font.getName() + " " + dps.right, dps.right.contains("expected"));
			assertTrue(dps.right, dps.right.contains("/"));
		}
	}
}

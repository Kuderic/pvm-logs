package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

/** T-045's probe: what it records, what it reports, and that off it records nothing. */
public class PerfProbeTest
{
	private static final long MS = 1_000_000L;

	@Test
	public void offItRecordsNothing()
	{
		final PerfProbe probe = new PerfProbe();
		assertEquals(0L, probe.start());
		assertEquals(0L, probe.stop(PerfProbe.Slot.ON_GAME_TICK, probe.start()));
		probe.frame();
		probe.fightStarted();
		assertTrue(probe.fightEnded("Vardorvis").isEmpty());
		assertTrue(probe.lastReport().isEmpty());
		// Nothing is even allocated until it's first turned on.
		assertEquals(0L, PerfProbe.start(null));
		PerfProbe.stop(null, PerfProbe.Slot.TIMELINE, 123L);
	}

	@Test
	public void timersKeepCountTotalMaxAndPercentiles()
	{
		final PerfProbe.Timer t = new PerfProbe.Timer(100);
		for (int i = 1; i <= 100; i++)
		{
			t.add(i * 1000L);
		}
		assertEquals(100, t.count());
		assertEquals(50_000L, t.quantile(0.5));
		assertEquals(99_000L, t.quantile(0.99));
		assertEquals(100_000L, t.max());
		// Past its capacity it still counts, totals and keeps the max; the percentiles are over the first ones.
		t.add(5_000_000L);
		assertEquals(101, t.count());
		assertEquals(5_000_000L, t.max());
		assertEquals(99_000L, t.quantile(0.99));
	}

	@Test
	public void framesHitchesAndLongFrames()
	{
		// 100 frames of 16 ms, two of 40 ms (hitches: over both 32 and 32 ms), one of 60 ms (a hitch and a long frame).
		final int[] micros = new int[103];
		for (int i = 0; i < 100; i++)
		{
			micros[i] = 16_000;
		}
		micros[100] = 40_000;
		micros[101] = 40_000;
		micros[102] = 60_000;
		final String s = PerfProbe.frameSummary(micros, micros.length, micros.length, 60 * MS, 1_756 * MS);
		assertTrue(s, s.startsWith("103 at 58.7 fps; interval median 16.0, p95 16.0, p99 40.0, max 60.0 ms"));
		assertTrue(s, s.contains("hitches 3, long frames 1"));
		assertEquals("no frames", PerfProbe.frameSummary(micros, 0, 0, 0, 0));
	}

	@Test
	public void theBaselineIsTheThirtySecondsBeforeTheFight()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		long now = 1_000 * MS;
		// 60 s of 20 ms frames before the fight: only the last 30 s are its baseline, 1,500 frames.
		for (int i = 0; i <= 3000; i++)
		{
			probe.frameAt(now);
			now += 20 * MS;
		}
		now -= 20 * MS;
		probe.fightStartedAt(now);
		// The fight: 10 s at 10 ms a frame.
		for (int i = 0; i < 1000; i++)
		{
			now += 10 * MS;
			probe.frameAt(now);
		}
		probe.notePanelState(PerfProbe.panelState(true, 0, true, true));
		final List<String> lines = probe.fightEndedAt("Vardorvis", now);
		final String frames = lines.get(1);
		assertTrue(frames, frames.startsWith("PERF frames: 1000 at 100.0 fps; interval median 10.0"));
		assertTrue(frames, frames.contains("| the 30 s before: 1500 at 50.0 fps; interval median 20.0"));
		assertEquals(lines, probe.lastReport());
		assertTrue(lines.get(0), lines.get(0).startsWith("PERF Vardorvis: 10.0 s; panel open on Fight, Update during "
			+ "fights on, Charts on"));
	}

	@Test
	public void theReportRanksHandlersAndLeavesNestedOnesOutOfThePluginsTotal()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStartedAt(0);
		for (int i = 0; i < 100; i++)
		{
			probe.record(PerfProbe.Slot.ON_GAME_TICK, 400_000);
			probe.record(PerfProbe.Slot.REFRESH_EXPECTED, 300_000);
			probe.record(PerfProbe.Slot.ON_HITSPLAT_APPLIED, 50_000);
			probe.record(PerfProbe.Slot.OVERLAY, 100_000);
		}
		probe.record(PerfProbe.Slot.ON_HITSPLAT_APPLIED, 4 * MS);
		probe.stopSwing(PerfProbe.SwingSlot.APPLY, System.nanoTime() - 500_000);
		final List<String> lines = probe.fightEndedAt("Vardorvis", 10_000 * MS);
		// onGameTick 40 ms, hitsplats 9 ms, the overlay 10 ms; refreshExpected is inside onGameTick.
		assertEquals("PERF client thread: the plugin 59.0 ms of 10.0 s (0.59%)", lines.get(2));
		final String top = lines.get(3);
		assertTrue(top, top.startsWith("PERF top by p99: onGameTick 100x p50 0.400"));
		assertTrue(top, top.indexOf("onGameTick: refreshExpected") < top.indexOf("overlay render"));
		assertTrue(lines.get(4), lines.get(4).startsWith("PERF Swing's thread: panel apply 1x"));
		final String budgets = lines.get(6);
		assertTrue(budgets, budgets.contains("overlay render p99 0.100 ms < 0.2"));
		assertTrue(budgets, budgets.contains("worst handler p99 0.400 ms (onGameTick) < 1, worst max 4.000 ms "
			+ "(onHitsplatApplied) >= 3"));
		// The panel was never sampled: no ticks of the fight went by with it noted.
		assertTrue(lines.get(0), lines.get(0).endsWith("panel not sampled"));
	}

	@Test
	public void aFightStartsItsTimersEmpty()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStartedAt(0);
		probe.record(PerfProbe.Slot.ON_STAT_CHANGED, 2 * MS);
		probe.fightEndedAt("first", MS * 1000);
		probe.fightStartedAt(MS * 2000);
		final List<String> lines = probe.fightEndedAt("second", MS * 3000);
		assertEquals("PERF top by p99: nothing ran", lines.get(3));
		// A second end without a start writes nothing.
		assertTrue(probe.fightEndedAt("again", MS * 4000).isEmpty());
	}

	@Test
	public void inputDelayCountsWaitsOverSixteenAndFiftyMs()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStartedAt(0);
		for (int i = 0; i < 97; i++)
		{
			probe.recordInputDelay(2 * MS);
		}
		probe.recordInputDelay(20 * MS);
		probe.recordInputDelay(30 * MS);
		probe.recordInputDelay(120 * MS);
		final List<String> lines = probe.fightEndedAt("Vardorvis", MS * 60_000);
		assertEquals("PERF input delay (Swing's queue, a probe a tick): waited 100x p50 2.000 p99 30.000 max 120.000 ms; "
			+ "over 16 ms 3, over 50 ms 1", lines.get(5));
		assertTrue(lines.get(6), lines.get(6).endsWith("input delay p99 30.000 ms >= 16, over 50 ms 1 > 0"));
		// The next fight starts with none.
		probe.fightStartedAt(MS * 70_000);
		final List<String> next = probe.fightEndedAt("Vardorvis", MS * 80_000);
		assertEquals("PERF input delay (Swing's queue, a probe a tick): waited none; over 16 ms 0, over 50 ms 0",
			next.get(5));
	}

	@Test
	public void theInputProbeRunsOnSwingsQueueOneAtATime() throws Exception
	{
		final PerfProbe probe = new PerfProbe();
		probe.postInputProbe();
		probe.setOn(true);
		probe.fightStartedAt(System.nanoTime());
		final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		// Swing's thread kept busy: the second tick's probe isn't posted while the first still waits.
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			held.countDown();
			try
			{
				release.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		});
		held.await();
		probe.postInputProbe();
		Thread.sleep(30);
		probe.postInputProbe();
		release.countDown();
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
		final String line = probe.fightEndedAt("Vardorvis", System.nanoTime()).get(5);
		assertTrue(line, line.startsWith("PERF input delay (Swing's queue, a probe a tick): waited 1x"));
		assertTrue(line, line.endsWith("over 16 ms 1, over 50 ms 0") || line.endsWith("over 16 ms 1, over 50 ms 1"));
	}

	@Test
	public void panelStates()
	{
		assertEquals("closed", PerfProbe.panelLabel(PerfProbe.panelState(false, 2, true, true)));
		assertEquals("open on Trip, Update during fights off, Charts on",
			PerfProbe.panelLabel(PerfProbe.panelState(true, 2, false, true)));
		assertEquals("open on History, Update during fights on, Charts off",
			PerfProbe.panelLabel(PerfProbe.panelState(true, 1, true, false)));
		// T-130 review: the Party tab is its own, not Trip, and its states fit.
		assertEquals("open on Party, Update during fights on, Charts on",
			PerfProbe.panelLabel(PerfProbe.panelState(true, 3, true, true)));
		assertTrue(PerfProbe.panelState(true, 3, true, true) < PerfProbe.PANEL_STATES);
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStartedAt(0);
		for (int i = 0; i < 30; i++)
		{
			probe.notePanelState(PerfProbe.panelState(false, 0, true, true));
		}
		for (int i = 0; i < 10; i++)
		{
			probe.notePanelState(PerfProbe.panelState(true, 0, true, true));
		}
		final String head = probe.fightEndedAt("Vardorvis", MS * 24_000).get(0);
		assertEquals("PERF Vardorvis: 24.0 s; panel closed (30 of 40 ticks); then open on Fight, Update during fights on, "
			+ "Charts on (10 of 40 ticks)", head);
		assertFalse(head.contains("History"));
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

/**
 * T-217 (Eric, 2026-11-11: "Game freezes after a boss dies and side panel is recalculating / drawing"): the 5 s after a
 * kill, measured as a window of their own. The fight's PERF block is written at its end, before the kill's own work.
 */
public class AfterKillPerfTest
{
	private static final long MS = 1_000_000L;

	private static String find(List<String> lines, String start)
	{
		for (String l : lines)
		{
			if (l.startsWith(start))
			{
				return l;
			}
		}
		throw new AssertionError("no line starting \"" + start + "\" in " + lines);
	}

	/** A fight, then its kill: the window opens after the fight's own lines. */
	private static PerfProbe killed(long[] now)
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStartedAt(now[0]);
		for (int i = 0; i < 200; i++)
		{
			probe.frameAt(now[0] += 8 * MS);
		}
		probe.fightEndedAt("Alchemical Hydra", now[0]);
		probe.killEndedAt("Alchemical Hydra", now[0]);
		return probe;
	}

	@Test
	public void onceAKillAfterFiveSecondsWithEachPart()
	{
		final long[] now = {1_000 * MS};
		final PerfProbe probe = killed(now);
		final long kill = now[0];
		assertTrue(probe.afterKillOpen());
		// The kill's work: frames, one 150 ms hitch, a client-thread handler, the panel on Swing's thread, the two jobs.
		for (int i = 0; i < 300; i++)
		{
			probe.frameAt(now[0] += i == 100 ? 150 * MS : 8 * MS);
		}
		probe.record(PerfProbe.Slot.PANEL_BUILD, 9 * MS);
		probe.record(PerfProbe.Slot.ON_GAME_TICK, 12 * MS);
		probe.stopSwing(PerfProbe.SwingSlot.APPLY, System.nanoTime() - 20 * MS);
		probe.recordInputDelay(3 * MS);
		final long started = kill + 20 * MS;
		probe.jobEndedAt("history save", kill + 5 * MS, started, started + 310 * MS, "6,705,225 bytes, 2500 fights");
		probe.jobEndedAt("records", started + 310 * MS, started + 310 * MS, started + 314 * MS, "nothing new");
		// Not yet 5 s: nothing.
		assertTrue(probe.afterKillDueAt(kill + 4_999 * MS).isEmpty());
		final List<String> lines = probe.afterKillDueAt(kill + 5_000 * MS);
		assertTrue(lines.get(0), lines.get(0).startsWith("PERF after the kill Alchemical Hydra: 5.0 s; panel "));
		assertTrue(find(lines, "PERF after the kill, frames: ").contains("300 at "));
		assertTrue(find(lines, "PERF after the kill, frames: ").contains("max 150.0 ms"));
		assertFalse("no baseline: that's the fight's", find(lines, "PERF after the kill, frames: ").contains("30 s before"));
		assertTrue(find(lines, "PERF after the kill, client thread: ").contains("the plugin 12.0 ms"));
		assertTrue(find(lines, "PERF after the kill, top by p99: ").contains("side panel snapshot, the builds alone 1x"));
		assertTrue(find(lines, "PERF after the kill, Swing's thread: ").startsWith(
			"PERF after the kill, Swing's thread: panel apply 1x"));
		assertTrue(find(lines, "PERF after the kill, input delay (Swing's queue, a probe a tick): ").contains("waited 1x"));
		find(lines, "PERF after the kill, input delay (Swing's queue, a probe every 20 ms): ");
		find(lines, "PERF after the kill, budgets: ");
		final String executor = find(lines, "PERF after the kill, executor: ");
		assertTrue(executor, executor.startsWith("PERF after the kill, executor: 2 jobs, 314.0 ms on its one thread: "
			+ "history save 310.0 ms (6,705,225 bytes, 2500 fights; started 0.02 s after the kill, 15.0 ms after it was "
			+ "queued); records 4.0 ms (nothing new; started 0.33 s after the kill"));
		final String gc = find(lines, "PERF after the kill, garbage collection: ");
		assertTrue(gc, gc.matches("PERF after the kill, garbage collection: \\d+ collections, \\d+ ms paused.*"));
		// Once: the window is closed.
		assertFalse(probe.afterKillOpen());
		assertTrue(probe.afterKillDueAt(kill + 6_000 * MS).isEmpty());
		// Kept for the Save debug log's header, after the fight's block.
		final List<String> header = probe.lastReport();
		assertTrue(header.get(0).startsWith("PERF Alchemical Hydra: "));
		assertEquals(lines.get(0), header.get(header.size() - lines.size()));
	}

	@Test
	public void theNextFightCutsItShortAndStartsClean()
	{
		final long[] now = {1_000 * MS};
		final PerfProbe probe = killed(now);
		final long kill = now[0];
		for (int i = 0; i < 100; i++)
		{
			probe.frameAt(now[0] += 10 * MS);
		}
		final List<String> lines = probe.afterKillEndAt(kill + 1_000 * MS, "the next fight started");
		assertTrue(lines.get(0), lines.get(0).startsWith("PERF after the kill Alchemical Hydra: 1.0 s (cut short: the next "
			+ "fight started); panel "));
		assertTrue(find(lines, "PERF after the kill, executor: ").endsWith("no job started in it"));
		assertTrue(probe.afterKillEndAt(kill + 1_100 * MS, "again").isEmpty());
		// The next fight: its own block, none of the window's frames in it.
		probe.fightStartedAt(kill + 1_000 * MS);
		probe.frameAt(now[0] += 10 * MS);
		final List<String> fight = probe.fightEndedAt("Hydra again", now[0] + MS);
		assertTrue(fight.get(1), fight.get(1).startsWith("PERF frames: 1 at "));
	}

	@Test
	public void itCostsNothingOutsideTheWindow()
	{
		final PerfProbe probe = new PerfProbe();
		// Off: no window, no job timed.
		probe.killEndedAt("Alchemical Hydra", 0);
		assertFalse(probe.afterKillOpen());
		assertEquals(0L, probe.jobStart());
		probe.setOn(true);
		// On, between kills: a job isn't timed (jobStart is 0), and a stray end is ignored.
		assertEquals(0L, probe.jobStart());
		probe.jobEndedAt("history save", 0, 0, 5 * MS, "x");
		assertTrue(probe.afterKillDueAt(Long.MAX_VALUE).isEmpty());
		// A job started before the window opened isn't the kill's.
		probe.killEndedAt("Alchemical Hydra", 1_000 * MS);
		probe.jobEndedAt("earlier", 900 * MS, 900 * MS, 1_100 * MS, null);
		assertTrue(find(probe.afterKillDueAt(6_000 * MS), "PERF after the kill, executor: ").endsWith(
			"no job started in it"));
		// Turned off mid-window: closed, nothing written.
		probe.killEndedAt("Alchemical Hydra", 7_000 * MS);
		probe.setOn(false);
		assertFalse(probe.afterKillOpen());
	}

	@Test
	public void garbageCollectionIsReadable()
	{
		// java.lang.management isn't on the Plugin Hub's forbidden list (ForbiddenApiTest); read where the JVM allows.
		final long[] gc = PerfProbe.gc();
		assertTrue(gc[0] >= 0 && gc[1] >= 0);
	}
}

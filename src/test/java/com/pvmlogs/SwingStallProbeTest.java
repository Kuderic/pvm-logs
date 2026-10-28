package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * T-046's follow-up: the 20 ms watch on Swing's thread, its {@code PERF stall}
 * stacks, what asks for the panel's layout passes, and one paint pass an update.
 */
public class SwingStallProbeTest
{
	private static final long MS = 1_000_000L;

	/** Holds Swing's thread, as a long paint would: the stack the watch reads should name this method. */
	private static void holdSwingsThread(long millis)
	{
		final long until = System.nanoTime() + millis * MS;
		while (System.nanoTime() < until)
		{
			Thread.onSpinWait();
		}
	}

	@Test
	public void theWatchSeesAStallAndNamesWhatHeldSwingsThread() throws Exception
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStarted();
		// A first probe runs at once: the watch learns which thread is Swing's.
		probe.sampleSwing();
		SwingUtilities.invokeAndWait(() ->
		{
		});
		final CountDownLatch held = new CountDownLatch(1);
		SwingUtilities.invokeLater(() ->
		{
			held.countDown();
			holdSwingsThread(300);
		});
		held.await();
		// The watch every 20 ms while Swing's thread is held for 300 ms.
		final long end = System.nanoTime() + 280 * MS;
		while (System.nanoTime() < end)
		{
			probe.sampleSwing();
			final long next = System.nanoTime() + 20 * MS;
			while (System.nanoTime() < next)
			{
				Thread.onSpinWait();
			}
		}
		SwingUtilities.invokeAndWait(() ->
		{
		});
		final List<String> report = probe.fightEnded("Vardorvis");
		final String watch = line(report, "PERF input delay (Swing's queue, a probe every 20 ms)");
		assertTrue(watch, watch.contains("over 50 ms 1"));
		assertFalse(watch, watch.contains("(0 posts skipped"));
		final String stall = line(report, "PERF stall 1 of");
		assertTrue(stall, stall.contains("Swing's thread seen here"));
		boolean named = false;
		for (String l : report)
		{
			named |= l.startsWith("PERF stall 1   at ") && l.contains("holdSwingsThread");
		}
		assertTrue("the stall's stack names what held Swing's thread:\n" + String.join("\n", report), named);
	}

	@Test
	public void slowProbesAreDatedFromTheLastApply()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStarted();
		// No apply yet: a slow probe goes in the first column.
		probe.recordWatch(30 * MS, System.nanoTime());
		probe.stopSwing(PerfProbe.SwingSlot.APPLY, System.nanoTime() - MS);
		final long now = System.nanoTime();
		probe.recordWatch(60 * MS, now + 10 * MS);
		probe.recordWatch(20 * MS, now + 300 * MS);
		probe.recordWatch(5 * MS, now + 300 * MS);
		final String watch = line(probe.fightEnded("Vardorvis"), "PERF input delay (Swing's queue, a probe every 20 ms)");
		assertTrue(watch, watch.contains("over 16 ms 3, over 50 ms 1"));
		assertTrue(watch, watch.contains("1 before any apply, 1 under 0.1 s after one, 1 at 0.1-0.6 s, 0 at 0.6-3 s, 0 later"));
	}

	@Test
	public void theWatchRunsOnTheExecutorAndStopsWhenTurnedOff() throws Exception
	{
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try
		{
			final PerfProbe probe = new PerfProbe();
			// Off: nothing scheduled.
			probe.watchSwing(executor);
			assertFalse(probe.watching());
			probe.setOn(true);
			probe.fightStarted();
			probe.watchSwing(executor);
			// Called every tick: one schedule, not one a tick.
			probe.watchSwing(executor);
			assertTrue(probe.watching());
			final long end = System.nanoTime() + 300 * MS;
			while (System.nanoTime() < end)
			{
				Thread.onSpinWait();
			}
			SwingUtilities.invokeAndWait(() ->
			{
			});
			final String watch = line(probe.fightEnded("Vardorvis"), "PERF input delay (Swing's queue, a probe every 20 ms)");
			// About 15 probes in 300 ms.
			final int count = Integer.parseInt(watch.replaceAll(".*: waited (\\d+)x.*", "$1"));
			assertTrue(watch, count >= 5 && count <= 20);
			probe.setOn(false);
			assertFalse(probe.watching());
		}
		finally
		{
			executor.shutdownNow();
			executor.awaitTermination(1, TimeUnit.SECONDS);
		}
	}

	@Test
	public void layoutPassesAreCountedAgainstWhatAskedForThem()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStarted();
		final StackTraceElement[] fromText = {
			new StackTraceElement("java.lang.Throwable", "getStackTrace", "Throwable.java", 1),
			new StackTraceElement("com.pvmlogs.PvmPerformancePanel$2", "invalidate", "PvmPerformancePanel.java", 230),
			new StackTraceElement("java.awt.Container", "invalidateParent", "Container.java", 1),
			new StackTraceElement("java.awt.Component", "invalidate", "Component.java", 1),
			new StackTraceElement("javax.swing.JComponent", "revalidate", "JComponent.java", 1),
			new StackTraceElement("com.pvmlogs.SidePanelParts", "relayout", "SidePanelParts.java", 40),
			new StackTraceElement("com.pvmlogs.SidePanelText", "set", "SidePanelText.java", 90),
			new StackTraceElement("com.pvmlogs.SidePanelParts$Line", "set", "SidePanelParts.java", 75),
		};
		assertEquals("SidePanelParts.relayout:40 < SidePanelText.set:90 < SidePanelParts$Line.set:75",
			PerfProbe.asker(fromText));
		// Two invalidations before a pass: the first is what asked for it.
		probe.layoutAsked(fromText);
		probe.layoutAsked(new StackTraceElement[]{new StackTraceElement("x.Other", "m", "Other.java", 1)});
		probe.layoutPassed();
		probe.layoutAsked(fromText);
		probe.layoutPassed();
		// A pass nothing of ours invalidated.
		probe.layoutPassed();
		final String layout = line(probe.fightEnded("Vardorvis"), "PERF layout passes:");
		assertTrue(layout, layout.startsWith("PERF layout passes: 3; asked by 2x SidePanelParts.relayout:40"));
		assertTrue(layout, layout.contains("1x nothing invalidated it"));
		assertFalse(layout, layout.contains("Other"));
	}

	@Test
	public void anUpdateIsOnePaintPass() throws Exception
	{
		// Swing paints each dirty component with no dirty ancestor in a pass of its own, each copied to the window on its
		// own. An update marks the whole tab dirty, so its children's repaints join one pass.
		final List<JComponent> dirty = new ArrayList<>();
		final RepaintManager recording = new RepaintManager()
		{
			@Override
			public void addDirtyRegion(JComponent c, int x, int y, int w, int h)
			{
				dirty.add(c);
			}
		};
		SwingUtilities.invokeAndWait(() ->
		{
			final RepaintManager was = RepaintManager.currentManager((JComponent) null);
			RepaintManager.setCurrentManager(recording);
			try
			{
				final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
				panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.live())));
				final SidePanelFightTab tab = panel.fightTab();
				tab.setBounds(new Rectangle(0, 0, 225, 2000));
				SidePanelPictures.layout(tab);
				dirty.clear();
				final int builds = tab.builds();
				// Five ticks on: the clock, DPS and thrall damage move; the structure holds.
				final SidePanelModel.Input later = SidePanelExamples.live();
				later.scoredThrall += 20;
				((LedgerExamples.FakeScope) later.scope).combatTicks += 5;
				panel.apply(new PanelSnapshot(SidePanelModel.fight(later)));
				assertEquals("no rebuild", builds, tab.builds());
				assertTrue("values changed", dirty.size() > 3);
				assertTrue("the tab itself is dirty, so every child joins its pass", dirty.contains(tab));
			}
			finally
			{
				RepaintManager.setCurrentManager(was);
			}
		});
	}

	private static String line(List<String> report, String start)
	{
		for (String l : report)
		{
			if (l.startsWith(start))
			{
				return l;
			}
		}
		throw new AssertionError("no line starting \"" + start + "\" in:\n" + String.join("\n", report));
	}
}

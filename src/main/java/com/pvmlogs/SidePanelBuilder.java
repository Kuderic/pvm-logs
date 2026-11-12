package com.pvmlogs;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * T-218 (Eric, 2026-11-11 21:52: "Game freezes after a boss dies and side panel is recalculating / drawing"; his log:
 * the snapshot's builds p99 9.6 ms on the client thread, the game's own): the panel's model is built on a thread of its
 * own. The client thread hands over a build from its copy ({@link SidePanelCopy}); the build runs here, one at a time, in
 * the order handed over (each snapshot's chart is sent from where the last one's ended, so none is skipped); its
 * snapshot goes to Swing's thread to be applied. The thread goes after a while with nothing to build and comes back with
 * the next.
 */
@Slf4j
final class SidePanelBuilder
{
	static final String THREAD = "luck-vs-skill panel build";

	private final ThreadPoolExecutor builds;
	private final Executor swing;
	/** For tests: builds run so far. */
	private volatile int built;

	SidePanelBuilder()
	{
		this(SwingUtilities::invokeLater);
	}

	SidePanelBuilder(Executor swing)
	{
		this.swing = swing;
		builds = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r ->
		{
			final Thread t = new Thread(r, THREAD);
			t.setDaemon(true);
			return t;
		});
		builds.allowCoreThreadTimeOut(true);
	}

	/** Builds {@code build} on the panel's thread, then applies it on Swing's. Any thread. */
	void submit(Supplier<PanelSnapshot> build, Consumer<PanelSnapshot> apply)
	{
		builds.execute(() ->
		{
			final PanelSnapshot snapshot;
			try
			{
				snapshot = build.get();
			}
			catch (RuntimeException e)
			{
				// A build that fails shows nothing new; the next one, a few ticks on, starts from the plugin again.
				log.warn("Luck vs Skill: the side panel's update failed to build", e);
				return;
			}
			finally
			{
				built++;
			}
			if (snapshot != null)
			{
				swing.execute(() -> apply.accept(snapshot));
			}
		});
	}

	/** Whether the calling thread is the build thread. */
	static boolean onBuildThread()
	{
		return THREAD.equals(Thread.currentThread().getName());
	}

	int built()
	{
		return built;
	}

	/** The plugin stopped: no more builds; one under way finishes. */
	void shutdown()
	{
		builds.shutdown();
	}
}

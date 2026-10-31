package com.pvmlogs;

import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.RepaintManager;

/**
 * T-133: a dev-only timing of Swing's whole repaint pass, for what the side panel's own timers can't see: the layout
 * ({@code validateInvalidComponents}), the paint and the copy to the window ({@code paintDirtyRegions}, whose paint
 * manager ends each pass with that copy), and which components asked for a repaint and how large an area, for a few
 * passes after each side panel apply. Installed only with {@code -Dluckvsskill.repaintTiming=true}
 * ({@code gradlew run -PrepaintTiming}); off by default, nothing is installed and Swing's own manager runs. Its figures
 * go into the PERF block (with Debug logging on) as "Swing's repaint pass after an apply" and the line of what asked.
 * Swing's thread only.
 */
final class SidePanelRepaintTiming extends RepaintManager
{
	static final String FLAG = "luckvsskill.repaintTiming";

	/** Passes recorded after each apply. */
	static final int PASSES = 4;

	/** One repaint pass: its layout and paint times, and what asked for it. */
	static final class Pass
	{
		long validateNanos;
		long paintNanos;
		/** Component class to {count, area in px} of its repaint requests. */
		final Map<String, long[]> dirty = new LinkedHashMap<>();

		long totalNanos()
		{
			return validateNanos + paintNanos;
		}

		@Override
		public String toString()
		{
			final StringBuilder s = new StringBuilder(String.format("layout %.2f ms, paint %.2f ms;", validateNanos / 1e6,
				paintNanos / 1e6));
			for (Map.Entry<String, long[]> e : dirty.entrySet())
			{
				s.append(' ').append(e.getKey()).append(' ').append(e.getValue()[0]).append("x ").append(e.getValue()[1])
					.append(" px;");
			}
			return s.toString();
		}
	}

	private final List<Pass> passes = new ArrayList<>();
	private Pass next = new Pass();
	private int left;
	private final PerfProbe perf;
	private final java.util.function.Consumer<String> out;

	SidePanelRepaintTiming(PerfProbe perf, java.util.function.Consumer<String> out)
	{
		this.perf = perf;
		this.out = out;
	}

	/** Installs it if the flag is on (on Swing's thread); the instance, or null. */
	static SidePanelRepaintTiming installIfAsked(PerfProbe perf)
	{
		if (!Boolean.getBoolean(FLAG))
		{
			return null;
		}
		final SidePanelRepaintTiming t = new SidePanelRepaintTiming(perf, null);
		javax.swing.SwingUtilities.invokeLater(() -> RepaintManager.setCurrentManager(t));
		return t;
	}

	/** A side panel apply is starting: record what it asks to repaint, and the next few passes. */
	void applying(String what)
	{
		left = PASSES;
		next = new Pass();
		label = what;
	}

	private String label = "";

	@Override
	public void addDirtyRegion(JComponent c, int x, int y, int w, int h)
	{
		if (left > 0 && w > 0 && h > 0)
		{
			final long[] n = next.dirty.computeIfAbsent(name(c), k -> new long[2]);
			n[0]++;
			n[1] += (long) w * h;
		}
		super.addDirtyRegion(c, x, y, w, h);
	}

	private static String name(Component c)
	{
		final String n = c.getClass().getName();
		return n.substring(n.lastIndexOf('.') + 1);
	}

	@Override
	public void validateInvalidComponents()
	{
		final long t0 = System.nanoTime();
		super.validateInvalidComponents();
		next.validateNanos += System.nanoTime() - t0;
	}

	@Override
	public void paintDirtyRegions()
	{
		final long t0 = System.nanoTime();
		super.paintDirtyRegions();
		next.paintNanos += System.nanoTime() - t0;
		if (left > 0)
		{
			left--;
			if (passes.size() < 10_000)
			{
				passes.add(next);
			}
			if (perf != null)
			{
				perf.repaintPass(next.totalNanos(), next.dirty);
			}
			if (out != null)
			{
				out.accept("REPAINT after " + label + " apply, pass " + (PASSES - left) + ": " + next);
			}
			next = new Pass();
		}
		else
		{
			next = new Pass();
		}
	}

	/** For tests and the repro: the passes recorded. */
	List<Pass> passes()
	{
		return passes;
	}
}

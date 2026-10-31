package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * T-133: the Party tab's update is one paint pass, as the Fight and Trip tabs' are, and the dev-only timing of Swing's
 * whole repaint pass reports into the PERF block only when asked for.
 */
public class SidePanelRepaintTimingTest
{
	/**
	 * Swing paints each dirty component with no dirty ancestor in a pass of its own, each through the tab's buffer and its
	 * own copy to the window. Before T-133 a Party-tab update left the meter, the chart, the header's texts and your Fight
	 * panels dirty on their own: about 4 copies an update in the repro (the 11:58 log: 5.8), against the Fight tab's 1.
	 */
	@Test
	public void aPartyTabUpdateIsOnePaintPass() throws Exception
	{
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
				final SidePanelPartyLagTest.Party party = new SidePanelPartyLagTest.Party();
				party.run(130);
				final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
				panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, null));
				panel.tabs().select(panel.tabs().getTab(3));
				panel.apply(party.partySnapshot());
				final SidePanelPartyTab tab = panel.partyTab();
				tab.setBounds(new Rectangle(0, 0, 225, 4000));
				SidePanelPictures.layout(tab);
				final int built = tab.rebuilds();
				for (int i = 0; i < 6; i++)
				{
					party.run(PvmPerformancePanel.UPDATE_TICKS);
					dirty.clear();
					panel.apply(party.partySnapshot());
					assertEquals("no rebuild", built, tab.rebuilds());
					assertTrue("values changed", dirty.size() > 3);
					assertTrue("the tab itself is dirty, so every child joins its pass", dirty.contains(tab));
					// Every other dirty component is inside it: one root, one pass, one copy to the window.
					for (JComponent c : dirty)
					{
						assertTrue(c.getClass().getSimpleName(), c == tab || inside(c, tab));
					}
				}
			}
			finally
			{
				RepaintManager.setCurrentManager(was);
			}
		});
	}

	private static boolean inside(Component c, Container tab)
	{
		for (Component p = c.getParent(); p != null; p = p.getParent())
		{
			if (p == tab)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void theTimingIsOffUnlessAskedFor()
	{
		assertFalse(Boolean.getBoolean(SidePanelRepaintTiming.FLAG));
		assertNull(SidePanelRepaintTiming.installIfAsked(null));
	}

	/** Asked for, a pass after an apply goes into the PERF block: its time, and what asked for it. */
	@Test
	public void aPassAfterAnApplyIsReported()
	{
		final PerfProbe probe = new PerfProbe();
		probe.setOn(true);
		probe.fightStartedAt(0);
		final SidePanelRepaintTiming timing = new SidePanelRepaintTiming(probe, null);
		// A pass with no apply before it isn't counted.
		timing.paintDirtyRegions();
		timing.applying("Party");
		final SidePanelText text = new SidePanelText(PanelSnapshot.Text.small("1:00", SidePanelStyle.VALUE), true);
		timing.addDirtyRegion(text, 0, 0, 30, 14);
		timing.addDirtyRegion(text, 0, 0, 30, 14);
		timing.paintDirtyRegions();
		assertEquals(1, timing.passes().size());
		final List<String> lines = probe.fightEndedAt("Araxxor", 10_000_000_000L);
		final String swing = line(lines, "PERF Swing's thread:");
		assertTrue(swing, swing.contains("Swing's repaint pass after an apply, whole (dev timing) 1x"));
		final String asked = line(lines, "PERF repaint passes after applies (dev timing)");
		assertTrue(asked, asked.contains("SidePanelText 2x"));
		// Without the timing, neither appears.
		final PerfProbe plain = new PerfProbe();
		plain.setOn(true);
		plain.fightStartedAt(0);
		final List<String> none = plain.fightEndedAt("Araxxor", 10_000_000_000L);
		assertFalse(String.join("\n", none).contains("dev timing"));
	}

	private static String line(List<String> lines, String start)
	{
		for (String l : lines)
		{
			if (l.startsWith(start))
			{
				return l;
			}
		}
		throw new AssertionError("no line starting \"" + start + "\" in:\n" + String.join("\n", lines));
	}
}

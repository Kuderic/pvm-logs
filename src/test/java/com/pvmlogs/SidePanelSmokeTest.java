package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-033 item 6: the panel builds and paints every Fight-tab state headless, and
 * a tick that only changes figures updates the components it has rather than
 * building new ones (spec 5.1: no {@code removeAll()} per tick).
 */
public class SidePanelSmokeTest
{
	static
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static PvmPerformancePanel panel()
	{
		return new PvmPerformancePanel(null, null, null, null);
	}

	private static void paint(PvmPerformancePanel panel)
	{
		final SidePanelFightTab tab = panel.fightTab();
		tab.setSize(225, 10_000);
		SidePanelPictures.layout(tab);
		tab.setSize(225, tab.getPreferredSize().height);
		SidePanelPictures.layout(tab);
		final BufferedImage img = new BufferedImage(225, Math.max(1, tab.getHeight()), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		tab.printAll(g);
		g.dispose();
	}

	@Test
	public void everyStateBuildsAndPaints()
	{
		for (Map.Entry<String, SidePanelModel.Input> e : SidePanelExamples.states().entrySet())
		{
			final PvmPerformancePanel panel = panel();
			panel.apply(new PanelSnapshot(SidePanelModel.fight(e.getValue())));
			paint(panel);
			assertTrue(e.getKey(), panel.fightTab().getComponentCount() > 0);
			assertTrue(e.getKey(), panel.fightTab().getPreferredSize().height > 100);
		}
	}

	@Test
	public void everyStateAlsoPaintsAt2x()
	{
		for (Map.Entry<String, SidePanelModel.Input> e : SidePanelExamples.states().entrySet())
		{
			final BufferedImage img = SidePanelPictures.render(e.getValue(), 2);
			assertEquals(e.getKey(), 2 * SidePanelPictures.SIDEBAR, img.getWidth());
		}
	}

	@Test
	public void aTickUpdatesTheComponentsItHas()
	{
		final PvmPerformancePanel panel = panel();
		final int before = panel.fightTab().builds();
		final SidePanelModel.Input first = SidePanelExamples.live();
		panel.apply(new PanelSnapshot(SidePanelModel.fight(first)));
		assertEquals(before + 1, panel.fightTab().builds());
		final List<Component> components = all(panel.fightTab());
		final String clock = clock(panel);

		// The next tick: a second on the clock and an attack landed. Same sections, so the same components.
		final SidePanelModel.Input next = SidePanelExamples.live();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) next.scope;
		s.combatTicks += 2;
		// A thrall hit: the weapon's damage stays the timeline's, so no row is added.
		next.scoredThrall += 9;
		panel.apply(new PanelSnapshot(SidePanelModel.fight(next)));
		assertEquals(before + 1, panel.fightTab().builds());
		assertEquals(components, all(panel.fightTab()));
		assertNotEquals(clock, clock(panel));
		paint(panel);

		// The kill: new sections, so built again, once.
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		assertEquals(before + 2, panel.fightTab().builds());
	}

	@Test
	public void foldedSectionsStayFoldedAcrossRebuilds()
	{
		final PvmPerformancePanel panel = panel();
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.live())));
		final SidePanelSection execution = section(panel.fightTab(), "execution");
		execution.toggle();
		assertTrue(execution.isFolded());
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		assertTrue(section(panel.fightTab(), "execution").isFolded());
	}

	private static SidePanelSection section(Container c, String id)
	{
		for (Component k : all(c))
		{
			if (k instanceof SidePanelSection && ((SidePanelSection) k).id().equals(id))
			{
				return (SidePanelSection) k;
			}
		}
		throw new AssertionError("no section " + id);
	}

	/** The header's clock text: the big one, the last text in the header. */
	private static String clock(PvmPerformancePanel panel)
	{
		for (Component k : all(panel.fightTab()))
		{
			if (k instanceof SidePanelText && ((SidePanelText) k).getFont().getSize() == 32)
			{
				return ((SidePanelText) k).getText();
			}
		}
		throw new AssertionError("no clock");
	}

	private static List<Component> all(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}
}

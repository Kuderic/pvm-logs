package com.pvmlogs;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-223: "repaint after apply" split at the tab's own buffered paint, into the wait before it and the wait after it (the
 * copy of the window's buffer to the screen, which none of the panel's timers sees).
 */
public class SidePanelRepaintSplitTest
{
	@Test
	public void theRepaintAfterApplyIsSplitAtTheTabsPaint()
	{
		// Apply ended at 100, the tab's paint ran 103 to 104, the marker at 110: 3 before, 6 after.
		final long[] split = PvmPerformancePanel.repaintSplit(100, 103, 104, 110);
		assertEquals(3, split[0]);
		assertEquals(6, split[1]);
		// The last paint was before this apply: no split.
		assertNull(PvmPerformancePanel.repaintSplit(100, 90, 95, 110));
	}

	@Test
	public void aBufferedPaintNotesWhenItRan()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setSize(242, 1000);
		h.run(30);
		p.apply(SidePanelHydraLagTest.snapshot(h.input(new SidePanelWeaponRows(), 0, false)));
		SidePanelHydraLagTest.settle(p.fightTab());
		final long t0 = System.nanoTime();
		draw(p.fightTab());
		assertTrue(SidePanelBuffer.lastStarted() - t0 >= 0);
		assertTrue(SidePanelBuffer.lastEnded() - SidePanelBuffer.lastStarted() >= 0);
	}

	static void draw(JComponent tab)
	{
		final BufferedImage img = new BufferedImage(tab.getWidth(), tab.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		tab.paint(g);
		g.dispose();
	}
}

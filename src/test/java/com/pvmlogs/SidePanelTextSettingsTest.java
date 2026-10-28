package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import org.junit.After;
import org.junit.Test;

/**
 * T-053: the side panel's "Larger text" and "Smooth font" settings. Both off
 * draws exactly as before; each changes only its own fonts and line heights; a
 * change builds each tab once and drops the cached images; nothing per tick.
 */
public class SidePanelTextSettingsTest
{
	private static final SidePanelStyle.Type[] TEXT = {
		SidePanelStyle.Type.TITLE, SidePanelStyle.Type.BODY, SidePanelStyle.Type.SMALL};

	@After
	public void bothOffAgain()
	{
		SidePanelStyle.setText(false, false);
	}

	// ---- the fonts ----

	@Test
	public void bothOffAreTheRuneScapeFontsAsBefore()
	{
		final SidePanelStyle.TextStyle t = new SidePanelStyle.TextStyle(false, false, null, null);
		assertSame(FontManager.getRunescapeBoldFont(), t.font(SidePanelStyle.Type.TITLE));
		assertSame(FontManager.getRunescapeFont(), t.font(SidePanelStyle.Type.BODY));
		assertSame(FontManager.getRunescapeSmallFont(), t.font(SidePanelStyle.Type.SMALL));
		assertEquals(32f, t.font(SidePanelStyle.Type.CLOCK).getSize2D(), 0);
		assertEquals(30, t.line(SidePanelStyle.Type.CLOCK));
		assertEquals(16, t.line(SidePanelStyle.Type.TITLE));
		assertEquals(16, t.line(SidePanelStyle.Type.BODY));
		assertEquals(12, t.line(SidePanelStyle.Type.SMALL));
		assertNull(t.family);
	}

	@Test
	public void largerWithTheRuneScapeFontsChangesOnlySmall()
	{
		final SidePanelStyle.TextStyle off = new SidePanelStyle.TextStyle(false, false, null, null);
		final SidePanelStyle.TextStyle larger = new SidePanelStyle.TextStyle(true, false, null, null);
		assertSame(FontManager.getRunescapeFont(), larger.font(SidePanelStyle.Type.SMALL));
		assertEquals(16, larger.line(SidePanelStyle.Type.SMALL));
		for (SidePanelStyle.Type t : new SidePanelStyle.Type[]{
			SidePanelStyle.Type.CLOCK, SidePanelStyle.Type.TITLE, SidePanelStyle.Type.BODY})
		{
			assertSame(t.name(), off.font(t), larger.font(t));
			assertEquals(t.name(), off.line(t), larger.line(t));
		}
	}

	@Test
	public void smoothDrawsTitleBodyAndSmallInTheSystemFontAndKeepsTheClock()
	{
		final String[] installed = {"Arial", SidePanelStyle.SMOOTH_FAMILY, "Tahoma"};
		final SidePanelStyle.TextStyle off = new SidePanelStyle.TextStyle(false, false, null, null);
		final SidePanelStyle.TextStyle smooth = new SidePanelStyle.TextStyle(false, true, installed, null);
		assertEquals(SidePanelStyle.SMOOTH_FAMILY, smooth.family);
		assertSame(off.font(SidePanelStyle.Type.CLOCK), smooth.font(SidePanelStyle.Type.CLOCK));
		assertEquals(30, smooth.line(SidePanelStyle.Type.CLOCK));
		assertTrue(smooth.font(SidePanelStyle.Type.TITLE).isBold());
		assertFalse(smooth.font(SidePanelStyle.Type.BODY).isBold());
		assertFalse(smooth.font(SidePanelStyle.Type.SMALL).isBold());
		assertEquals(13f, smooth.font(SidePanelStyle.Type.TITLE).getSize2D(), 0);
		assertEquals(13f, smooth.font(SidePanelStyle.Type.BODY).getSize2D(), 0);
		assertEquals(11f, smooth.font(SidePanelStyle.Type.SMALL).getSize2D(), 0);
		for (SidePanelStyle.Type t : TEXT)
		{
			assertEquals(t.name(), SidePanelStyle.SMOOTH_FAMILY, smooth.font(t).getName());
		}
	}

	@Test
	public void smoothAndLargerAddAStepToEachSize()
	{
		final SidePanelStyle.TextStyle both = new SidePanelStyle.TextStyle(true, true, new String[0], null);
		assertEquals(14f, both.font(SidePanelStyle.Type.TITLE).getSize2D(), 0);
		assertEquals(14f, both.font(SidePanelStyle.Type.BODY).getSize2D(), 0);
		assertEquals(12f, both.font(SidePanelStyle.Type.SMALL).getSize2D(), 0);
		assertTrue(both.font(SidePanelStyle.Type.TITLE).isBold());
		assertEquals(30, both.line(SidePanelStyle.Type.CLOCK));
	}

	@Test
	public void smoothLineHeightsAreTheFontsAscentAndDescent()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			final SidePanelStyle.TextStyle t = new SidePanelStyle.TextStyle(larger, true, new String[0], null);
			final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
			for (SidePanelStyle.Type type : TEXT)
			{
				final FontMetrics fm = g.getFontMetrics(t.font(type));
				assertEquals(type.name(), fm.getAscent() + fm.getDescent(), t.line(type));
			}
			g.dispose();
		}
	}

	@Test
	public void segoeUiWhereInstalledElseSansSerif()
	{
		assertEquals("Segoe UI", SidePanelStyle.smoothFamily(new String[]{"Arial", "Segoe UI"}));
		assertEquals(Font.SANS_SERIF, SidePanelStyle.smoothFamily(new String[]{"Arial", "Tahoma"}));
		assertEquals(Font.SANS_SERIF, SidePanelStyle.smoothFamily(new String[0]));
		assertEquals(Font.SANS_SERIF, SidePanelStyle.smoothFamily(null));
		final SidePanelStyle.TextStyle missing = new SidePanelStyle.TextStyle(false, true, new String[]{"Arial"}, null);
		assertEquals(Font.SANS_SERIF, missing.family);
		for (SidePanelStyle.Type t : TEXT)
		{
			assertEquals(t.name(), Font.SANS_SERIF, missing.font(t).getName());
		}
	}

	@Test
	public void theRolesReadTheSettingInForce()
	{
		assertFalse(SidePanelStyle.setText(false, false));
		final Font small = SidePanelStyle.Type.SMALL.font();
		assertTrue(SidePanelStyle.setText(true, false));
		assertNotEquals(small, SidePanelStyle.Type.SMALL.font());
		assertEquals(16, SidePanelStyle.Type.SMALL.lineHeight());
		// The same again builds nothing.
		final SidePanelStyle.TextStyle made = SidePanelStyle.text();
		assertFalse(SidePanelStyle.setText(true, false));
		assertSame(made, SidePanelStyle.text());
		assertTrue(SidePanelStyle.setText(false, false));
		assertSame(small, SidePanelStyle.Type.SMALL.font());
	}

	@Test
	public void aSmoothTextLaidOutAtOneScaleIsNotCutAtAnother()
	{
		// A system font's rounded advances differ from scale to scale: measured at 100% and drawn at 150%, "+11 s" lost its
		// last characters. Measured and drawn with fractional advances, it fits at every scale.
		final String[] samples = {"+11 s", "14 s slower", "+1:05", "bottom 20%", "688/778", "Damage taken", "99% (-3 s)",
			"Vardorvis", "2:16", "100%"};
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(new SidePanelStyle.TextStyle(larger, true, new String[0], null));
			for (SidePanelStyle.Type type : TEXT)
			{
				for (String sample : samples)
				{
					for (double scale : new double[]{1, 1.25, 1.5, 1.75, 2})
					{
						final SidePanelText text = new SidePanelText(new PanelSnapshot.Text(sample, java.awt.Color.WHITE, type),
							true);
						text.setSize(text.getPreferredSize());
						final BufferedImage img = new BufferedImage((int) Math.ceil(text.getWidth() * scale) + 1,
							(int) Math.ceil(text.getHeight() * scale) + 1, BufferedImage.TYPE_INT_RGB);
						final Graphics2D g = img.createGraphics();
						g.scale(scale, scale);
						text.paint(g);
						g.dispose();
						assertNull(type + " \"" + sample + "\" at " + scale + "x", text.getToolTipText());
					}
				}
			}
		}
	}

	// ---- smoothing ----

	@Test
	public void runeScapeFontsTakeNoTextHints()
	{
		final Graphics2D g = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).createGraphics();
		final Object before = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		SidePanelStyle.textHints(g);
		assertEquals(before, g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
		g.dispose();
	}

	@Test
	public void smoothIsSubpixelOnOpaqueSurfacesAndGreyscaleOnOthers()
	{
		SidePanelStyle.setText(new SidePanelStyle.TextStyle(false, true, new String[0], null));
		final Graphics2D opaque = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).createGraphics();
		SidePanelStyle.textHints(opaque);
		assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, opaque.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
		assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_ON, opaque.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS));
		opaque.dispose();
		final Graphics2D alpha = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).createGraphics();
		SidePanelStyle.textHints(alpha);
		assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, alpha.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
		alpha.dispose();
	}

	@Test
	public void smoothUsesTheDesktopsOwnHintsWhereItGivesThem()
	{
		final Map<RenderingHints.Key, Object> desktop = new java.util.HashMap<>();
		desktop.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB);
		desktop.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, 140);
		SidePanelStyle.setText(new SidePanelStyle.TextStyle(false, true, new String[0], desktop));
		final Graphics2D g = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).createGraphics();
		SidePanelStyle.textHints(g);
		assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB, g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
		assertEquals(140, g.getRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST));
		g.dispose();
		// The desktop's hints are only for Smooth font.
		assertNull(new SidePanelStyle.TextStyle(false, false, null, desktop).desktopHints);
	}

	// ---- the panel ----

	private static PvmPerformancePanel panelWithEveryTab()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.ruledKill(true)),
			SidePanelModel.trip(SidePanelExamples.trip())));
		return panel;
	}

	private static BufferedImage paintFight(PvmPerformancePanel panel)
	{
		return SidePanelPictures.draw(panel.tabs(), panel.fightTab(), 1);
	}

	@Test
	public void aChangeBuildsEachTabOnceAndDropsTheCachedImages()
	{
		final PvmPerformancePanel panel = panelWithEveryTab();
		final SidePanelFightTab fight = panel.fightTab();
		final SidePanelTripTab trip = panel.tripTab();
		final SidePanelHistoryTab history = panel.historyTab();
		// Fill the tabs' images, as a paint on screen does.
		for (javax.swing.JComponent tab : new javax.swing.JComponent[]{fight, trip, history})
		{
			tab.setSize(225, 2000);
			SidePanelPictures.layout(tab);
			final Graphics2D g = new BufferedImage(225, 2000, BufferedImage.TYPE_INT_ARGB).createGraphics();
			tab.paint(g);
			g.dispose();
		}
		assertTrue(fight.buffer().imageSize().width > 0);
		assertTrue(trip.buffer().imageSize().width > 0);
		assertTrue(history.buffer().imageSize().width > 0);
		final int f = fight.builds();
		final int t = trip.builds();
		final int h = history.builds();

		assertTrue(panel.setText(false, true));
		assertEquals(1, panel.restyles());
		assertEquals(f + 1, fight.builds());
		assertEquals(t + 1, trip.builds());
		assertEquals(h + 1, history.builds());
		assertEquals(0, fight.buffer().imageSize().width);
		assertEquals(0, trip.buffer().imageSize().width);
		assertEquals(0, history.buffer().imageSize().width);
		// Every text the Fight tab now holds is in the new fonts.
		final List<Font> now = java.util.Arrays.asList(SidePanelStyle.text().fonts);
		final List<SidePanelText> texts = texts(fight);
		assertTrue(texts.size() > 20);
		for (SidePanelText text : texts)
		{
			assertTrue(text.getFont().toString(), now.contains(text.getFont()));
			assertTrue(text.getFont() == SidePanelStyle.Type.CLOCK.font() || !text.getFont().getName().startsWith("RuneScape"));
		}

		// The same settings again: nothing built, nothing dropped.
		assertFalse(panel.setText(false, true));
		assertEquals(1, panel.restyles());
		assertEquals(f + 1, fight.builds());
	}

	@Test
	public void aSnapshotAfterTheChangeIsNoRebuild()
	{
		final PvmPerformancePanel panel = panelWithEveryTab();
		panel.setText(true, true);
		final int builds = panel.fightTab().builds();
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.ruledKill(true))));
		assertEquals(builds, panel.fightTab().builds());
	}

	@Test
	public void bothOffAgainDrawsExactlyAsBefore()
	{
		final BufferedImage before = paintFight(panelWithEveryTab());
		final PvmPerformancePanel panel = panelWithEveryTab();
		panel.setText(true, true);
		final BufferedImage smooth = paintFight(panel);
		assertFalse(same(before, smooth));
		panel.setText(false, false);
		assertTrue("back to both off, the Fight tab draws as it did", same(before, paintFight(panel)));
		// And every state's render, the panel never having been switched, is what master draws: the PR's render diff
		// checks that against master's own renders, file by file.
		for (Map.Entry<String, SidePanelModel.Input> e : SidePanelExamples.states().entrySet())
		{
			final BufferedImage a = SidePanelPictures.render(e.getValue(), 1);
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.apply(new PanelSnapshot(SidePanelModel.fight(e.getValue())));
			p.setText(true, false);
			p.setText(false, false);
			assertTrue(e.getKey(), same(a, SidePanelPictures.draw(p.tabs(), p.fightTab(), 1)));
		}
	}

	@Test
	public void eachModeKeepsTheRowsInTheSidebarsWidth()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				final PvmPerformancePanel panel = panelWithEveryTab();
				panel.setText(larger, smooth);
				for (javax.swing.JComponent tab : new javax.swing.JComponent[]{
					panel.fightTab(), panel.tripTab(), panel.historyTab()})
				{
					final String mode = (larger ? "larger " : "") + (smooth ? "smooth " : "") + tab.getClass().getSimpleName();
					assertTrue(mode + " " + tab.getPreferredSize().width,
						tab.getPreferredSize().width <= SidePanelPictures.SIDEBAR - SidePanelPictures.SCROLLBAR);
				}
			}
		}
	}

	/**
	 * T-053 item 8: the Fight tab's software render (T-046's {@code panel software render}) in each mode, at 1× and
	 * 1.5×, after a kill, timed by {@link PerfProbe} as in game. Printed; it fails only if nothing was timed, since a CI
	 * runner's clock says nothing about Eric's PC.
	 */
	@Test
	public void softwareRenderInEachMode()
	{
		for (boolean smooth : new boolean[]{false, true})
		{
			for (double scale : new double[]{1, 1.5})
			{
				final PvmPerformancePanel panel = panelWithEveryTab();
				panel.setText(false, smooth);
				final SidePanelFightTab tab = panel.fightTab();
				tab.setSize(225, tab.getPreferredSize().height);
				SidePanelPictures.layout(tab);
				final BufferedImage screen = new BufferedImage((int) Math.ceil(225 * scale),
					(int) Math.ceil(tab.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
				final PerfProbe probe = new PerfProbe();
				probe.setOn(true);
				probe.fightStarted();
				tab.setPerf(probe);
				for (int i = 0; i < 600; i++)
				{
					if (i == 200)
					{
						// The first 200 warm up the JIT, as a long session would have.
						probe.fightStarted();
					}
					final Graphics2D g = screen.createGraphics();
					g.scale(scale, scale);
					tab.paint(g);
					g.dispose();
				}
				final List<String> report = probe.fightEnded("Vardorvis");
				String line = null;
				for (String l : report)
				{
					if (l.contains("panel software render"))
					{
						line = l;
					}
				}
				assertTrue(String.join("\n", report), line != null && line.contains("400x"));
				System.out.println("T-053 " + (smooth ? "Smooth font" : "RuneScape fonts") + " at " + scale + "x: " + line.trim());
			}
		}
	}

	private static List<SidePanelText> texts(Container c)
	{
		final List<SidePanelText> out = new java.util.ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelText)
			{
				out.add((SidePanelText) k);
			}
			else if (k instanceof Container)
			{
				out.addAll(texts((Container) k));
			}
		}
		return out.isEmpty() ? Collections.emptyList() : out;
	}

	private static boolean same(BufferedImage a, BufferedImage b)
	{
		if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
		{
			return false;
		}
		final int[] pa = a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth());
		final int[] pb = b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
		try
		{
			assertArrayEquals(pa, pb);
			return true;
		}
		catch (AssertionError e)
		{
			return false;
		}
	}
}

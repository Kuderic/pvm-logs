package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import org.junit.Test;

/**
 * T-047: an overlay drawn from its cache looks exactly as RuneLite draws it,
 * and is rebuilt only when what it shows changes.
 */
public class OverlayCacheTest
{
	private static final List<Font> FONTS = Arrays.asList(FontManager.getRunescapeFont(),
		FontManager.getRunescapeBoldFont(), FontManager.getRunescapeSmallFont());

	/** An overlay panel as ours are: the rows added, the width set, then OverlayPanel.render. */
	private static final class Panel extends OverlayPanel
	{
		PvmPerformanceOverlay.Lines lines;

		Dimension paint(Graphics2D g)
		{
			panelComponent.getChildren().clear();
			panelComponent.getChildren().addAll(lines.components);
			panelComponent.setPreferredSize(new Dimension(lines.width, 0));
			return super.render(g);
		}
	}

	/** The game under the overlay: a pattern, so a wrong blend can't hide in a flat colour. */
	private static BufferedImage scene()
	{
		final BufferedImage img = new BufferedImage(520, 720, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				img.setRGB(x, y, ((x * 7) & 0xFF) << 16 | ((y * 5) & 0xFF) << 8 | ((x + y) * 3 & 0xFF));
			}
		}
		return img;
	}

	/** A graphics as OverlayRenderer hands one over: antialiasing on, the font set, UI scale, then translated to the overlay. */
	private static Graphics2D overlayGraphics(BufferedImage img, Font font, double scale)
	{
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(scale, scale);
		g.translate(5, 7);
		g.setFont(font);
		return g;
	}

	private static void assertSamePixels(String what, BufferedImage expected, BufferedImage actual)
	{
		for (int y = 0; y < expected.getHeight(); y++)
		{
			for (int x = 0; x < expected.getWidth(); x++)
			{
				if (expected.getRGB(x, y) != actual.getRGB(x, y))
				{
					throw new AssertionError(what + ": pixel " + x + "," + y + " is "
						+ Integer.toHexString(actual.getRGB(x, y)) + ", RuneLite draws " + Integer.toHexString(expected.getRGB(x, y)));
				}
			}
		}
	}

	/** Draws the rows both ways and checks they match, pixel for pixel, and that the second frame came from the cache. */
	private static void compare(String what, List<LedgerRows.Row> rows, Font font, double scale, Color preferred,
		Dimension saved)
	{
		// RuneLite's way, twice: PanelComponent sizes its background from its previous layout.
		final BufferedImage before = scene();
		final Panel old = new Panel();
		old.setPreferredColor(preferred);
		old.setPreferredSize(saved);
		Dimension oldSize = null;
		for (int i = 0; i < 2; i++)
		{
			final Graphics2D g = overlayGraphics(before, font, scale);
			old.lines = PvmPerformanceOverlay.lines(g, rows, saved);
			oldSize = old.paint(g);
			g.dispose();
		}
		final BufferedImage expected = scene();
		{
			final Graphics2D g = overlayGraphics(expected, font, scale);
			old.lines = PvmPerformanceOverlay.lines(g, rows, saved);
			oldSize = old.paint(g);
			g.dispose();
		}

		// Ours: the first frame builds the cache, the second draws from it; each frame on a fresh scene.
		final Panel ours = new Panel();
		ours.setPreferredColor(preferred);
		ours.setPreferredSize(saved);
		final OverlayCache cache = new OverlayCache();
		BufferedImage actual = null;
		Dimension size = null;
		for (int i = 0; i < 2; i++)
		{
			actual = scene();
			final Graphics2D g = overlayGraphics(actual, font, scale);
			ours.lines = PvmPerformanceOverlay.lines(g, rows, saved);
			final PvmPerformanceOverlay.Lines lines = ours.lines;
			size = cache.draw(g, lines.look(saved), PvmPerformanceOverlay.background(ours), ours::paint);
			g.dispose();
		}
		assertEquals(what, 1, cache.builds());
		// The fast path: an opaque colour at RuneLite's unscaled UI is one image; anything else replays the text.
		assertEquals(what, preferred != null && preferred.getAlpha() == 255 && scale == 1, cache.drawsImage());
		assertEquals(what, oldSize, size);
		assertSamePixels(what, expected, actual);
	}

	@Test
	public void everyStateLooksAsRuneLiteDrawsIt()
	{
		final Map<String, List<LedgerRows.Row>> states = LedgerPictures.states();
		for (Map.Entry<String, List<LedgerRows.Row>> state : states.entrySet())
		{
			for (Font font : FONTS)
			{
				for (double scale : new double[]{1, 2})
				{
					// RuneLite's standard see-through colour, a see-through colour of the player's, and an opaque one.
					for (Color colour : new Color[]{null, new Color(20, 40, 90, 120), new Color(30, 30, 30)})
					{
						compare(state.getKey() + " " + font.getName() + " x" + scale + " " + colour, state.getValue(), font,
							scale, colour, null);
					}
				}
			}
		}
	}

	@Test
	public void theLoadoutOverlayToo()
	{
		for (Font font : FONTS)
		{
			compare("loadout " + font.getName(), LedgerRows.loadout(LedgerExamples.loadout(), true), font, 1, null, null);
		}
	}

	@Test
	public void aSavedWidthStillFitsTheRows()
	{
		// T-016/#18: a panel saved narrower than its rows shortens the attack's name; T-029: Luck's band drops its seconds.
		final List<LedgerRows.Row> rows = LedgerPictures.states().get("12-saved-width");
		for (Font font : FONTS)
		{
			compare("saved width " + font.getName(), rows, font, 1, null, new Dimension(160, 0));
		}
		final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		g.setFont(FontManager.getRunescapeBoldFont());
		final LedgerRows.Loadout tentacle = LedgerExamples.loadout();
		tentacle.attack = "Abyssal tentacle (or)";
		final List<LedgerRows.Row> wide = LedgerRows.loadout(tentacle, false);
		final PvmPerformanceOverlay.Lines narrow = PvmPerformanceOverlay.lines(g, wide, new Dimension(130, 0));
		final PvmPerformanceOverlay.Lines full = PvmPerformanceOverlay.lines(g, wide, null);
		// The cache keys on the fitted rows, so the fallback is what it draws.
		assertNotEquals(full.rows, narrow.rows);
		assertNotEquals(full.look(null), narrow.look(new Dimension(130, 0)));
	}

	// ---- rebuilt only when the content changes ----

	private static final class Counted
	{
		final Panel panel = new Panel();
		final OverlayCache cache = new OverlayCache();
		int paints;

		Dimension frame(Graphics2D g, List<LedgerRows.Row> rows)
		{
			panel.lines = PvmPerformanceOverlay.lines(g, rows, null);
			final PvmPerformanceOverlay.Lines lines = panel.lines;
			return cache.draw(g, lines.look(panel.getPreferredSize()), PvmPerformanceOverlay.background(panel), gg -> {
				paints++;
				return panel.paint(gg);
			});
		}
	}

	@Test
	public void aTickWithoutChangesReusesTheImage()
	{
		final BufferedImage img = scene();
		final Graphics2D g = overlayGraphics(img, FontManager.getRunescapeFont(), 1);
		final Counted c = new Counted();
		final List<LedgerRows.Row> rows = LedgerPictures.states().get("3-dead");
		c.frame(g, rows);
		assertEquals(1, c.cache.builds());
		// The build paints twice, then never again while nothing changes: frames, and ticks that build new but equal
		// lines, as OncePerKey does once a tick.
		assertEquals(2, c.paints);
		for (int frame = 0; frame < 200; frame++)
		{
			c.frame(g, new ArrayList<>(rows));
		}
		assertEquals(1, c.cache.builds());
		assertEquals(2, c.paints);
	}

	@Test
	public void itIsRebuiltWhenWhatItShowsChanges()
	{
		final BufferedImage img = scene();
		final Graphics2D g = overlayGraphics(img, FontManager.getRunescapeFont(), 1);
		final Counted c = new Counted();
		final Map<String, List<LedgerRows.Row>> states = LedgerPictures.states();
		c.frame(g, states.get("2-mid"));
		// The rows change (a tick of the fight: the clock, a hit).
		c.frame(g, states.get("3-dead"));
		assertEquals(2, c.cache.builds());
		// The player's font.
		g.setFont(FontManager.getRunescapeSmallFont());
		c.frame(g, states.get("3-dead"));
		assertEquals(3, c.cache.builds());
		// The player's overlay colour.
		c.panel.setPreferredColor(new Color(10, 10, 10, 200));
		c.frame(g, states.get("3-dead"));
		assertEquals(4, c.cache.builds());
		// The saved size.
		c.panel.setPreferredSize(new Dimension(220, 0));
		c.frame(g, states.get("3-dead"));
		assertEquals(5, c.cache.builds());
		// The UI scale.
		g.scale(2, 2);
		c.frame(g, states.get("3-dead"));
		assertEquals(6, c.cache.builds());
		// And nothing changed: no rebuild.
		c.frame(g, states.get("3-dead"));
		assertEquals(6, c.cache.builds());
	}

	@Test
	public void nothingToDrawIsNothing()
	{
		final Graphics2D g = overlayGraphics(scene(), FontManager.getRunescapeFont(), 1);
		final OverlayCache cache = new OverlayCache();
		assertNull(cache.draw(g, "empty", null, gg -> null));
		assertNull(cache.draw(g, "empty", null, gg -> null));
		assertEquals(1, cache.builds());
	}

	@Test
	public void textItCantReplayIsDrawnTheOrdinaryWay()
	{
		// A panel drawing text some other way (a TextLayout, say) is drawn by the panel itself every frame.
		final BufferedImage img = scene();
		final Graphics2D g = overlayGraphics(img, FontManager.getRunescapeFont(), 1);
		final OverlayCache cache = new OverlayCache();
		final int[] paints = {0};
		for (int i = 0; i < 3; i++)
		{
			cache.draw(g, "other", null, gg -> {
				paints[0]++;
				gg.drawString("x", 1.5f, 10f);
				return new Dimension(10, 10);
			});
		}
		assertEquals(1, cache.builds());
		// Two to build, then one each frame.
		assertEquals(2 + 3, paints[0]);
	}

	@Test
	public void aUiScaleThatIsntWholeIsExactToo()
	{
		// Scaled, an opaque colour replays the text like a see-through one (the border's edge reaches past the box).
		compare("x1.5 opaque", LedgerPictures.states().get("3-dead"), FontManager.getRunescapeFont(), 1.5,
			new Color(30, 30, 30), null);
		compare("x1.25 see-through", LedgerPictures.states().get("2-mid"), FontManager.getRunescapeFont(), 1.25, null,
			null);
	}

	@Test
	public void theLinesCarryARowForEachComponent()
	{
		final Graphics2D g = overlayGraphics(scene(), FontManager.getRunescapeFont(), 1);
		final PvmPerformanceOverlay.Lines lines = PvmPerformanceOverlay.lines(g, LedgerPictures.states().get("3-dead"), null);
		assertEquals(lines.rows.size(), lines.components.size());
		assertEquals(LedgerPictures.states().get("3-dead"), lines.rows);
	}
}

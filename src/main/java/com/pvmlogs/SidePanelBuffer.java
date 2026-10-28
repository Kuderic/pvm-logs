package com.pvmlogs;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * T-046: a tab's paint, done in software and copied to the screen once. Every
 * anti-aliased shape, translucent fill and string of the tab is drawn into a
 * cached {@link BufferedImage} at the screen's own scale, the way the offline
 * renders draw them (about 1 to 2 ms for the whole panel), and then copied to
 * the screen as one image. Drawn straight to the screen, each of those shapes
 * is its own trip through the platform's pipeline, which on Eric's PC kept
 * Swing's thread busy for about 100 ms after every update (T-045's PERF block)
 * while the same paint offline takes 2 ms.
 *
 * <p>The tab is its children's painting origin ({@link JComponent#isPaintingOrigin}),
 * so a value's repaint comes through here too, clipped to what changed. The
 * image grows to the largest area painted and is kept. Swing's thread only.
 */
final class SidePanelBuffer
{
	/** The tab's own paint, without this buffer: {@code super.paint}. */
	interface Painter
	{
		void paint(Graphics g);
	}

	private static final AffineTransform IDENTITY = new AffineTransform();
	/** Inside a buffered paint: a nested buffered component paints straight into the outer one's image. */
	private static boolean painting;

	private BufferedImage image;
	private PerfProbe perf;

	void setPerf(PerfProbe perf)
	{
		this.perf = perf;
	}

	/** The image's size in device pixels, 0 by 0 before the first buffered paint; for tests. */
	java.awt.Dimension imageSize()
	{
		return image == null ? new java.awt.Dimension() : new java.awt.Dimension(image.getWidth(), image.getHeight());
	}

	void paint(JComponent c, Graphics g, Painter direct)
	{
		if (painting || !c.isOpaque() || c.isPaintingForPrint() || !(g instanceof Graphics2D))
		{
			direct.paint(g);
			return;
		}
		final Graphics2D g2 = (Graphics2D) g;
		final AffineTransform t = g2.getTransform();
		if (t.getShearX() != 0 || t.getShearY() != 0 || t.getScaleX() <= 0 || t.getScaleY() <= 0)
		{
			direct.paint(g);
			return;
		}
		Rectangle clip = g2.getClipBounds();
		final Rectangle bounds = new Rectangle(0, 0, c.getWidth(), c.getHeight());
		clip = clip == null ? bounds : clip.intersection(bounds);
		if (clip.isEmpty())
		{
			return;
		}
		// The clip in device pixels, widened to whole pixels.
		final double sx = t.getScaleX();
		final double sy = t.getScaleY();
		final double ox = t.getTranslateX();
		final double oy = t.getTranslateY();
		final int dx0 = (int) Math.floor(ox + clip.x * sx);
		final int dy0 = (int) Math.floor(oy + clip.y * sy);
		final int dx1 = (int) Math.ceil(ox + (clip.x + clip.width) * sx);
		final int dy1 = (int) Math.ceil(oy + (clip.y + clip.height) * sy);
		final int w = dx1 - dx0;
		final int h = dy1 - dy0;
		if (w <= 0 || h <= 0)
		{
			return;
		}
		if (image == null || image.getWidth() < w || image.getHeight() < h)
		{
			image = new BufferedImage(Math.max(w, image == null ? 0 : image.getWidth()),
				Math.max(h, image == null ? 0 : image.getHeight()), BufferedImage.TYPE_INT_RGB);
		}

		final long rendering = PerfProbe.start(perf);
		final Graphics2D ig = image.createGraphics();
		try
		{
			ig.setRenderingHints(g2.getRenderingHints());
			ig.setClip(0, 0, w, h);
			// The component's origin lands where it lands on the screen, less the image's corner there.
			ig.translate(ox - dx0, oy - dy0);
			ig.scale(sx, sy);
			ig.clip(clip);
			ig.setFont(g2.getFont());
			ig.setColor(g2.getColor());
			painting = true;
			direct.paint(ig);
		}
		finally
		{
			painting = false;
			ig.dispose();
		}
		if (rendering != 0L)
		{
			perf.stopSwing(PerfProbe.SwingSlot.VIEW_RENDER, rendering);
		}

		final long copying = PerfProbe.start(perf);
		final Graphics2D screen = (Graphics2D) g2.create();
		try
		{
			// Device space: the image's pixels land one to one on the screen's.
			screen.setTransform(IDENTITY);
			screen.drawImage(image, dx0, dy0, dx1, dy1, 0, 0, w, h, null);
		}
		finally
		{
			screen.dispose();
		}
		if (copying != 0L)
		{
			perf.stopSwing(PerfProbe.SwingSlot.VIEW_BLIT, copying);
		}
	}
}

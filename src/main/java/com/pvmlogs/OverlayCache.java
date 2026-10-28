package com.pvmlogs;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.runelite.client.ui.overlay.components.BackgroundComponent;

/**
 * One overlay box, drawn from what it drew last time until its content changes
 * (T-047). Measured offline (the PR has the table), three quarters of a frame
 * was RuneLite blending the box's see-through background onto the game, which
 * no cache can skip as the game under it changes every frame. So:
 * <ul>
 * <li><b>see-through background</b> (RuneLite's standard, and the default): the
 * background is drawn as RuneLite draws it, and the text is replayed from a
 * recording of the panel's own drawing, without its layout, wrapping and
 * colour-tag parsing;</li>
 * <li><b>opaque background</b> (the player's "Overlay color" with full alpha), at
 * RuneLite's unscaled UI: the whole box is one opaque image, copied in a
 * frame.</li>
 * </ul>
 * Both draw exactly what the panel drew (a test compares the pixels). A rebuild
 * paints the panel twice, as {@code PanelComponent} sizes its background from the
 * layout of its previous render.
 */
final class OverlayCache
{
	private Object key;
	private Built built;
	private int builds;
	// The opaque path's two images, reused while the box keeps its size: a fight rebuilds about once a second.
	private BufferedImage argb;
	private BufferedImage rgb;
	private int[] pixels;

	/** What a rebuild made. */
	private static final class Built
	{
		final Dimension size;
		final List<RecordingGraphics.Op> ops;
		final BackgroundComponent background;
		/** The whole box, when its background is opaque and the UI unscaled; null otherwise. */
		final BufferedImage opaque;

		Built(Dimension size, List<RecordingGraphics.Op> ops, BackgroundComponent background, BufferedImage opaque)
		{
			this.size = size;
			this.ops = ops;
			this.background = background;
			this.opaque = opaque;
		}
	}

	/**
	 * Draws the box. {@code paint} renders the panel as the overlay always has
	 * (its rows added, then {@code OverlayPanel.render}) and returns its size; it
	 * runs only when {@code content}, the background colour or the drawing
	 * surface's font, scale or hints differ from last time.
	 *
	 * @param content    what decides the box's look: its rows, widths and saved size
	 * @param background the colour the panel's background is drawn in
	 * @return the box's size, as {@code OverlayPanel.render} returns it
	 */
	Dimension draw(Graphics2D g, Object content, Color background, Function<Graphics2D, Dimension> paint)
	{
		final AffineTransform t = g.getTransform();
		final Object full = Arrays.asList(content, background, g.getFont(), t.getScaleX(), t.getScaleY(), t.getShearX(),
			t.getShearY(), g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING),
			g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS), g.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
		if (!Objects.equals(full, key))
		{
			key = full;
			built = build(g, t, background, paint);
			builds++;
		}
		if (built == null)
		{
			return null;
		}
		if (built.opaque != null)
		{
			g.drawImage(built.opaque, 0, 0, null);
			return built.size;
		}
		if (built.ops == null)
		{
			// The panel drew text this recorder can't replay: draw it the ordinary way.
			return paint.apply(g);
		}
		built.background.render(g);
		final Font font = g.getFont();
		final Color colour = g.getColor();
		for (RecordingGraphics.Op op : built.ops)
		{
			g.setFont(op.font);
			g.setColor(op.color);
			g.drawString(op.text, op.x, op.y);
		}
		g.setFont(font);
		g.setColor(colour);
		return built.size;
	}

	/** How many times the box has been rebuilt, for tests. */
	int builds()
	{
		return builds;
	}

	/** Whether the box is drawn as one opaque image, for tests. */
	boolean drawsImage()
	{
		return built != null && built.opaque != null;
	}

	private Built build(Graphics2D g, AffineTransform t, Color background,
		Function<Graphics2D, Dimension> paint)
	{
		// Record the panel twice on a scratch surface set up like the real one; the second pass has its final size.
		final BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		List<RecordingGraphics.Op> ops = null;
		Dimension size = null;
		final boolean[] unrecorded = {false};
		for (int pass = 0; pass < 2; pass++)
		{
			final Graphics2D sg = like(scratch.createGraphics(), g, t);
			ops = new ArrayList<>();
			try
			{
				size = paint.apply(new RecordingGraphics(sg, ops, unrecorded));
			}
			finally
			{
				sg.dispose();
			}
		}
		if (size == null || size.width <= 0 || size.height <= 0)
		{
			return null;
		}
		size = new Dimension(size);
		// As PanelComponent draws it: a new BackgroundComponent over the panel's rectangle, in the panel's colour.
		final BackgroundComponent back = new BackgroundComponent();
		back.setRectangle(new Rectangle(0, 0, size.width, size.height));
		back.setBackgroundColor(background);
		if (unrecorded[0])
		{
			return new Built(size, null, back, null);
		}
		final List<RecordingGraphics.Op> kept = Collections.unmodifiableList(ops);
		final BufferedImage opaque = background != null && background.getAlpha() == 255
			? opaqueImage(g, t, size, back, kept) : null;
		return new Built(size, kept, back, opaque);
	}

	/**
	 * The whole box as one opaque image, when every pixel of it is opaque; null
	 * when any isn't, as the image would then cover what the box lets show
	 * through. Only at RuneLite's unscaled UI: scaled, the border's antialiased
	 * edge reaches a pixel past the box (seen in the pixel test at 1.5 and 2), so
	 * a scaled UI replays the text instead.
	 */
	private BufferedImage opaqueImage(Graphics2D g, AffineTransform t, Dimension size, BackgroundComponent back,
		List<RecordingGraphics.Op> ops)
	{
		if (t.getScaleX() != 1 || t.getScaleY() != 1 || t.getShearX() != 0 || t.getShearY() != 0)
		{
			return null;
		}
		if (argb == null || argb.getWidth() != size.width || argb.getHeight() != size.height)
		{
			argb = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
			rgb = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
			pixels = new int[size.width * size.height];
		}
		final Graphics2D ig = like(argb.createGraphics(), g, new AffineTransform());
		try
		{
			// Cleared first: a pixel this box doesn't paint mustn't keep the last box's.
			ig.setComposite(AlphaComposite.Clear);
			ig.fillRect(0, 0, size.width, size.height);
			ig.setComposite(AlphaComposite.SrcOver);
			back.render(ig);
			for (RecordingGraphics.Op op : ops)
			{
				ig.setFont(op.font);
				ig.setColor(op.color);
				ig.drawString(op.text, op.x, op.y);
			}
		}
		finally
		{
			ig.dispose();
		}
		argb.getRGB(0, 0, argb.getWidth(), argb.getHeight(), pixels, 0, argb.getWidth());
		for (int p : pixels)
		{
			if (p >>> 24 != 0xFF)
			{
				return null;
			}
		}
		rgb.setRGB(0, 0, argb.getWidth(), argb.getHeight(), pixels, 0, argb.getWidth());
		return rgb;
	}

	/** {@code into} set up as {@code like} is: font, colour, hints, and {@code t}'s scale (no translation). */
	private static Graphics2D like(Graphics2D into, Graphics2D like, AffineTransform t)
	{
		into.setRenderingHints(like.getRenderingHints());
		into.setFont(like.getFont());
		into.setColor(like.getColor());
		into.setTransform(new AffineTransform(t.getScaleX(), t.getShearY(), t.getShearX(), t.getScaleY(), 0, 0));
		return into;
	}
}

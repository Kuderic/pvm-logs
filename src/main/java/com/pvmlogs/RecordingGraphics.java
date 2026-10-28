package com.pvmlogs;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.List;

/**
 * A {@link Graphics2D} that passes every call to another and keeps the text
 * drawn through it (T-047): each {@code drawString(String, int, int)} with the
 * font and colour it was drawn in, at its place in the space the recording
 * started in. That's the only text call RuneLite's {@code TextComponent} makes
 * (1.13.4's bytecode), so a panel of lines recorded once can be drawn again by
 * replaying its text, without its layout, wrapping and colour-tag parsing.
 * Text drawn any other way sets {@link #unrecorded} and isn't kept.
 * <p>
 * The overrides below were generated from {@code Graphics2D}'s and
 * {@code Graphics}' abstract methods; all but the text calls and
 * {@code create()} simply delegate.
 */
final class RecordingGraphics extends Graphics2D
{
	/** One recorded {@code drawString}. */
	static final class Op
	{
		final Font font;
		final Color color;
		final String text;
		final int x;
		final int y;

		Op(Font font, Color color, String text, int x, int y)
		{
			this.font = font;
			this.color = color;
			this.text = text;
			this.x = x;
			this.y = y;
		}
	}

	private final Graphics2D g;
	private final List<Op> ops;
	/** Set when text went through a call that isn't recorded; shared with every {@link #create()}d copy. */
	private final boolean[] unrecorded;
	private final double originX;
	private final double originY;

	/** Records into {@code ops}, relative to {@code g}'s translation now. */
	RecordingGraphics(Graphics2D g, List<Op> ops, boolean[] unrecorded)
	{
		this(g, ops, unrecorded, g.getTransform().getTranslateX(), g.getTransform().getTranslateY());
	}

	private RecordingGraphics(Graphics2D g, List<Op> ops, boolean[] unrecorded, double originX, double originY)
	{
		this.g = g;
		this.ops = ops;
		this.unrecorded = unrecorded;
		this.originX = originX;
		this.originY = originY;
	}

	@Override
	public void addRenderingHints(java.util.Map<?, ?> a0)
	{
		g.addRenderingHints(a0);
	}

	@Override
	public void clearRect(int a0, int a1, int a2, int a3)
	{
		g.clearRect(a0, a1, a2, a3);
	}

	@Override
	public void clipRect(int a0, int a1, int a2, int a3)
	{
		g.clipRect(a0, a1, a2, a3);
	}

	@Override
	public void clip(java.awt.Shape a0)
	{
		g.clip(a0);
	}

	@Override
	public void copyArea(int a0, int a1, int a2, int a3, int a4, int a5)
	{
		g.copyArea(a0, a1, a2, a3, a4, a5);
	}

	@Override
	public java.awt.Graphics create()
	{
		return new RecordingGraphics((Graphics2D) g.create(), ops, unrecorded, originX, originY);
	}

	@Override
	public void dispose()
	{
		g.dispose();
	}

	@Override
	public void drawArc(int a0, int a1, int a2, int a3, int a4, int a5)
	{
		g.drawArc(a0, a1, a2, a3, a4, a5);
	}

	@Override
	public void drawGlyphVector(java.awt.font.GlyphVector a0, float a1, float a2)
	{
		// Text this recorder can't replay: the cache draws the panel the ordinary way instead.
		unrecorded[0] = true;
		g.drawGlyphVector(a0, a1, a2);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, java.awt.geom.AffineTransform a1, java.awt.image.ImageObserver a2)
	{
		return g.drawImage(a0, a1, a2);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, int a1, int a2, java.awt.Color a3, java.awt.image.ImageObserver a4)
	{
		return g.drawImage(a0, a1, a2, a3, a4);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, java.awt.Color a5, java.awt.image.ImageObserver a6)
	{
		return g.drawImage(a0, a1, a2, a3, a4, a5, a6);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, java.awt.Color a9, java.awt.image.ImageObserver a10)
	{
		return g.drawImage(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, java.awt.image.ImageObserver a9)
	{
		return g.drawImage(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, int a1, int a2, int a3, int a4, java.awt.image.ImageObserver a5)
	{
		return g.drawImage(a0, a1, a2, a3, a4, a5);
	}

	@Override
	public boolean drawImage(java.awt.Image a0, int a1, int a2, java.awt.image.ImageObserver a3)
	{
		return g.drawImage(a0, a1, a2, a3);
	}

	@Override
	public void drawImage(java.awt.image.BufferedImage a0, java.awt.image.BufferedImageOp a1, int a2, int a3)
	{
		g.drawImage(a0, a1, a2, a3);
	}

	@Override
	public void drawLine(int a0, int a1, int a2, int a3)
	{
		g.drawLine(a0, a1, a2, a3);
	}

	@Override
	public void drawOval(int a0, int a1, int a2, int a3)
	{
		g.drawOval(a0, a1, a2, a3);
	}

	@Override
	public void drawPolygon(int[] a0, int[] a1, int a2)
	{
		g.drawPolygon(a0, a1, a2);
	}

	@Override
	public void drawPolyline(int[] a0, int[] a1, int a2)
	{
		g.drawPolyline(a0, a1, a2);
	}

	@Override
	public void drawRenderableImage(java.awt.image.renderable.RenderableImage a0, java.awt.geom.AffineTransform a1)
	{
		g.drawRenderableImage(a0, a1);
	}

	@Override
	public void drawRenderedImage(java.awt.image.RenderedImage a0, java.awt.geom.AffineTransform a1)
	{
		g.drawRenderedImage(a0, a1);
	}

	@Override
	public void drawRoundRect(int a0, int a1, int a2, int a3, int a4, int a5)
	{
		g.drawRoundRect(a0, a1, a2, a3, a4, a5);
	}

	@Override
	public void drawString(String a0, float a1, float a2)
	{
		// Text this recorder can't replay: the cache draws the panel the ordinary way instead.
		unrecorded[0] = true;
		g.drawString(a0, a1, a2);
	}

	@Override
	public void drawString(String a0, int a1, int a2)
	{
		// The one text call RuneLite's TextComponent makes: kept, in the space the recording started in.
		ops.add(new Op(g.getFont(), g.getColor(), a0, a1 + (int) Math.round(g.getTransform().getTranslateX() - originX),
			a2 + (int) Math.round(g.getTransform().getTranslateY() - originY)));
	}

	@Override
	public void drawString(java.text.AttributedCharacterIterator a0, float a1, float a2)
	{
		// Text this recorder can't replay: the cache draws the panel the ordinary way instead.
		unrecorded[0] = true;
		g.drawString(a0, a1, a2);
	}

	@Override
	public void drawString(java.text.AttributedCharacterIterator a0, int a1, int a2)
	{
		// Text this recorder can't replay: the cache draws the panel the ordinary way instead.
		unrecorded[0] = true;
		g.drawString(a0, a1, a2);
	}

	@Override
	public void draw(java.awt.Shape a0)
	{
		g.draw(a0);
	}

	@Override
	public void fillArc(int a0, int a1, int a2, int a3, int a4, int a5)
	{
		g.fillArc(a0, a1, a2, a3, a4, a5);
	}

	@Override
	public void fillOval(int a0, int a1, int a2, int a3)
	{
		g.fillOval(a0, a1, a2, a3);
	}

	@Override
	public void fillPolygon(int[] a0, int[] a1, int a2)
	{
		g.fillPolygon(a0, a1, a2);
	}

	@Override
	public void fillRect(int a0, int a1, int a2, int a3)
	{
		g.fillRect(a0, a1, a2, a3);
	}

	@Override
	public void fillRoundRect(int a0, int a1, int a2, int a3, int a4, int a5)
	{
		g.fillRoundRect(a0, a1, a2, a3, a4, a5);
	}

	@Override
	public void fill(java.awt.Shape a0)
	{
		g.fill(a0);
	}

	@Override
	public java.awt.Color getBackground()
	{
		return g.getBackground();
	}

	@Override
	public java.awt.Rectangle getClipBounds()
	{
		return g.getClipBounds();
	}

	@Override
	public java.awt.Shape getClip()
	{
		return g.getClip();
	}

	@Override
	public java.awt.Color getColor()
	{
		return g.getColor();
	}

	@Override
	public java.awt.Composite getComposite()
	{
		return g.getComposite();
	}

	@Override
	public java.awt.GraphicsConfiguration getDeviceConfiguration()
	{
		return g.getDeviceConfiguration();
	}

	@Override
	public java.awt.FontMetrics getFontMetrics(java.awt.Font a0)
	{
		return g.getFontMetrics(a0);
	}

	@Override
	public java.awt.font.FontRenderContext getFontRenderContext()
	{
		return g.getFontRenderContext();
	}

	@Override
	public java.awt.Font getFont()
	{
		return g.getFont();
	}

	@Override
	public java.awt.Paint getPaint()
	{
		return g.getPaint();
	}

	@Override
	public Object getRenderingHint(java.awt.RenderingHints.Key a0)
	{
		return g.getRenderingHint(a0);
	}

	@Override
	public java.awt.RenderingHints getRenderingHints()
	{
		return g.getRenderingHints();
	}

	@Override
	public java.awt.Stroke getStroke()
	{
		return g.getStroke();
	}

	@Override
	public java.awt.geom.AffineTransform getTransform()
	{
		return g.getTransform();
	}

	@Override
	public boolean hit(java.awt.Rectangle a0, java.awt.Shape a1, boolean a2)
	{
		return g.hit(a0, a1, a2);
	}

	@Override
	public void rotate(double a0, double a1, double a2)
	{
		g.rotate(a0, a1, a2);
	}

	@Override
	public void rotate(double a0)
	{
		g.rotate(a0);
	}

	@Override
	public void scale(double a0, double a1)
	{
		g.scale(a0, a1);
	}

	@Override
	public void setBackground(java.awt.Color a0)
	{
		g.setBackground(a0);
	}

	@Override
	public void setClip(int a0, int a1, int a2, int a3)
	{
		g.setClip(a0, a1, a2, a3);
	}

	@Override
	public void setClip(java.awt.Shape a0)
	{
		g.setClip(a0);
	}

	@Override
	public void setColor(java.awt.Color a0)
	{
		g.setColor(a0);
	}

	@Override
	public void setComposite(java.awt.Composite a0)
	{
		g.setComposite(a0);
	}

	@Override
	public void setFont(java.awt.Font a0)
	{
		g.setFont(a0);
	}

	@Override
	public void setPaintMode()
	{
		g.setPaintMode();
	}

	@Override
	public void setPaint(java.awt.Paint a0)
	{
		g.setPaint(a0);
	}

	@Override
	public void setRenderingHint(java.awt.RenderingHints.Key a0, Object a1)
	{
		g.setRenderingHint(a0, a1);
	}

	@Override
	public void setRenderingHints(java.util.Map<?, ?> a0)
	{
		g.setRenderingHints(a0);
	}

	@Override
	public void setStroke(java.awt.Stroke a0)
	{
		g.setStroke(a0);
	}

	@Override
	public void setTransform(java.awt.geom.AffineTransform a0)
	{
		g.setTransform(a0);
	}

	@Override
	public void setXORMode(java.awt.Color a0)
	{
		g.setXORMode(a0);
	}

	@Override
	public void shear(double a0, double a1)
	{
		g.shear(a0, a1);
	}

	@Override
	public void transform(java.awt.geom.AffineTransform a0)
	{
		g.transform(a0);
	}

	@Override
	public void translate(double a0, double a1)
	{
		g.translate(a0, a1);
	}

	@Override
	public void translate(int a0, int a1)
	{
		g.translate(a0, a1);
	}
}

package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.Objects;
import javax.swing.JComponent;

/**
 * One line of text in one of the panel's four type roles, painted by hand so
 * it never wraps and is never clipped by the panel's edge: a text longer than
 * its space is cut with "…", as the overlay cuts since #18 (spec section 2).
 * An optional second run (a name's {@code (dead)}, a row's hint) follows the
 * first in its own colour and is kept whole while the first is cut.
 */
final class SidePanelText extends JComponent
{
	private String text = "";
	private Color colour = SidePanelStyle.VALUE;
	private String suffix = "";
	private Color suffixColour = SidePanelStyle.VALUE;
	private SidePanelStyle.Type type;
	private final boolean right;
	/** A hint after a label gives way before the label does; a name's {@code (dead)} doesn't. */
	private boolean cutSuffixFirst;

	SidePanelText(SidePanelStyle.Type type, boolean right)
	{
		this.type = type;
		this.right = right;
		setOpaque(false);
		setFont(type.font());
	}

	SidePanelText(PanelSnapshot.Text t, boolean right)
	{
		this(t.type, right);
		set(t);
	}

	/** The text, its colour and its type; only what changed is repainted, and only a new width lays out again. */
	void set(PanelSnapshot.Text t)
	{
		set(t, null);
	}

	void set(PanelSnapshot.Text t, PanelSnapshot.Text after)
	{
		final String newSuffix = after == null ? "" : after.text;
		final Color newSuffixColour = after == null ? suffixColour : after.colour;
		final boolean width = !t.text.equals(text) || !newSuffix.equals(suffix) || t.type != type;
		if (!width && Objects.equals(t.colour, colour) && Objects.equals(newSuffixColour, suffixColour))
		{
			return;
		}
		text = t.text;
		colour = t.colour;
		suffix = newSuffix;
		suffixColour = newSuffixColour;
		if (t.type != type)
		{
			type = t.type;
			setFont(type.font());
		}
		if (width)
		{
			revalidate();
		}
		repaint();
	}

	/** The second run is cut before the first: for hints, which matter less than what they follow. */
	SidePanelText cutSuffixFirst()
	{
		cutSuffixFirst = true;
		return this;
	}

	String getText()
	{
		return text + suffix;
	}

	@Override
	public Dimension getPreferredSize()
	{
		final FontMetrics fm = getFontMetrics(getFont());
		return new Dimension(fm.stringWidth(text) + fm.stringWidth(suffix), type.lineHeight());
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, type.lineHeight());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, type.lineHeight());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		final Font font = getFont();
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics(font);
		String after = suffix;
		if (cutSuffixFirst && !after.isEmpty() && fm.stringWidth(text) + fm.stringWidth(after) > getWidth())
		{
			// The gap before a hint stays a gap: cut what follows it.
			final String lead = after.startsWith(" ") ? " " : "";
			final String cut = LedgerRows.cut(after.substring(lead.length()), fm::stringWidth,
				Math.max(0, getWidth() - fm.stringWidth(text) - fm.stringWidth(lead)));
			// A hint cut to its dots says nothing: drop it.
			after = cut.isEmpty() || cut.equals(LedgerRows.CUT) ? "" : lead + cut;
		}
		final int suffixWidth = fm.stringWidth(after);
		final int room = Math.max(0, getWidth() - suffixWidth);
		final String shown = LedgerRows.cut(text, fm::stringWidth, room);
		final int width = fm.stringWidth(shown) + suffixWidth;
		final int x = right ? Math.max(0, getWidth() - width) : 0;
		final int baseline = getHeight() - fm.getDescent();
		g.setColor(colour);
		g.drawString(shown, x, baseline);
		if (!after.isEmpty())
		{
			g.setColor(suffixColour);
			g.drawString(after, x + fm.stringWidth(shown), baseline);
		}
		setToolTipText(shown.equals(text) && after.equals(suffix) ? null : text + suffix);
	}
}

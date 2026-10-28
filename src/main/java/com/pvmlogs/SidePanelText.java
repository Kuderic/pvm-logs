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
	/** The second run's type: its own font, as a row's {@code (ended)} is Small beside a Body name. */
	private SidePanelStyle.Type suffixType;
	private SidePanelStyle.Type type;
	private final boolean right;
	/** A hint after a label gives way before the label does; a name's {@code (dead)} doesn't. */
	private boolean cutSuffixFirst;
	/**
	 * T-046: the widest this text has been, which is the width it asks for. It
	 * never shrinks, so a value that grows back to an earlier width (a clock's
	 * digits, a damage total) needs no layout.
	 */
	private int reserved;

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
		final SidePanelStyle.Type newSuffixType = after == null ? null : after.type;
		final boolean width = !t.text.equals(text) || !newSuffix.equals(suffix) || t.type != type
			|| newSuffixType != suffixType;
		if (!width && Objects.equals(t.colour, colour) && Objects.equals(newSuffixColour, suffixColour))
		{
			return;
		}
		suffixType = newSuffixType;
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
			// Layout only when the text outgrows the space it has (T-046): within it, it's painted where it belongs. Once
			// laid out, a changing value reserves room for its widest digits, so it doesn't ask again until it gains one.
			final int needed = getWidth() > 0 ? moving() : natural();
			if (needed > reserved)
			{
				reserved = needed;
				if (needed > getWidth())
				{
					SidePanelParts.relayout(this);
				}
			}
		}
		repaint();
	}

	/** T-046: takes room for its widest digits now, laying out if that's more than it has. */
	void reserveDigits()
	{
		final int needed = moving();
		if (needed > reserved)
		{
			reserved = needed;
			if (needed > getWidth())
			{
				SidePanelParts.relayout(this);
			}
		}
	}

	/** The width the text and its second run take. */
	private int natural()
	{
		return getFontMetrics(getFont()).stringWidth(text) + getFontMetrics(suffixFont()).stringWidth(suffix);
	}

	/**
	 * The width a changing value keeps (T-046): every digit as wide as the widest.
	 * RuneLite's fonts give digits different widths, and a clock going from 1:18
	 * to 1:24 shouldn't lay the panel out. Only a value gaining a digit (999 to
	 * 1,000) needs more room. A text that never changes keeps its own width, so a
	 * view at rest looks as it always did.
	 */
	private int moving()
	{
		return reservedWidth(getFontMetrics(getFont()), text) + reservedWidth(getFontMetrics(suffixFont()), suffix);
	}

	/** {@code s}'s width with each digit counted at the font's widest digit. */
	static int reservedWidth(FontMetrics fm, String s)
	{
		int widest = 0;
		for (char d = '0'; d <= '9'; d++)
		{
			widest = Math.max(widest, fm.charWidth(d));
		}
		int extra = 0;
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			if (c >= '0' && c <= '9')
			{
				extra += widest - fm.charWidth(c);
			}
		}
		return fm.stringWidth(s) + extra;
	}

	/** The second run's font: its own type's, or the first's when it has none or the same. */
	private Font suffixFont()
	{
		return suffixType == null || suffixType == type ? getFont() : suffixType.font();
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
		reserved = Math.max(reserved, natural());
		return new Dimension(reserved, type.lineHeight());
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
		final FontMetrics sm = g.getFontMetrics(suffixFont());
		String after = suffix;
		if (cutSuffixFirst && !after.isEmpty() && fm.stringWidth(text) + sm.stringWidth(after) > getWidth())
		{
			// The gap before a hint stays a gap: cut what follows it.
			final String lead = after.startsWith(" ") ? " " : "";
			final String cut = LedgerRows.cut(after.substring(lead.length()), sm::stringWidth,
				Math.max(0, getWidth() - fm.stringWidth(text) - sm.stringWidth(lead)));
			// A hint cut to its dots says nothing: drop it.
			after = cut.isEmpty() || cut.equals(LedgerRows.CUT) ? "" : lead + cut;
		}
		final int suffixWidth = sm.stringWidth(after);
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
			g.setFont(suffixFont());
			g.drawString(after, x + fm.stringWidth(shown), baseline);
		}
		setToolTipText(shown.equals(text) && after.equals(suffix) ? null : text + suffix);
	}
}

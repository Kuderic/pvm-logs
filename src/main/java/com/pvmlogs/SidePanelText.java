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
	 * T-063, T-072: shorter texts, longest first, the first that fits drawn instead when the text doesn't
	 * ({@code +80 thrall, 38 DoT}); empty for none.
	 */
	private java.util.List<String> shortTexts = java.util.Collections.emptyList();
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
	/** Its colour now, for tests. */
	Color colour()
	{
		return colour;
	}

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
	/**
	 * T-132: on a tinted background (the Party tab's selected member), muted text is drawn a little lighter, so it keeps
	 * the contrast it has on the plain panel. Set with the tint, never per tick.
	 */
	private boolean lifted;

	void setLifted(boolean on)
	{
		if (on != lifted)
		{
			lifted = on;
			repaint();
		}
	}

	boolean isLifted()
	{
		return lifted;
	}

	private Color lifted(Color c)
	{
		return lifted && SidePanelStyle.MUTED.equals(c) ? SidePanelStyle.MUTED_ON_TINT : c;
	}

	/** T-119: its full text's width in its fonts, both runs. */
	int naturalWidth()
	{
		return natural();
	}

	/** T-119: {@code s}'s width in its first run's font. */
	int widthOf(String s)
	{
		return SidePanelStyle.metrics(this, getFont()).stringWidth(s);
	}

	private int natural()
	{
		return SidePanelStyle.metrics(this, getFont()).stringWidth(text)
			+ SidePanelStyle.metrics(this, suffixFont()).stringWidth(suffix);
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
		return reservedWidth(SidePanelStyle.metrics(this, getFont()), text)
			+ reservedWidth(SidePanelStyle.metrics(this, suffixFont()), suffix);
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

	/** The text settings changed (T-053): the role's new font, and the width it asks for measured again. */
	void restyle()
	{
		setFont(type.font());
		reserved = 0;
		revalidate();
		repaint();
	}

	/** The second run's font: its own type's, or the first's when it has none or the same. */
	private Font suffixFont()
	{
		return suffixType == null || suffixType == type ? getFont() : suffixType.font();
	}

	/**
	 * T-063: a shorter form, drawn when the text doesn't fit its width and this does, instead of cutting the text. It
	 * asks for no width of its own, so changing it lays nothing out.
	 */
	void setShort(String s)
	{
		setShort(s == null ? java.util.Collections.<String>emptyList() : java.util.Collections.singletonList(s));
	}

	/** As {@link #setShort(String)}, with several forms, longest first: the first that fits is drawn. */
	void setShort(java.util.List<String> forms)
	{
		final java.util.List<String> now = forms == null ? java.util.Collections.<String>emptyList() : forms;
		if (!now.equals(shortTexts))
		{
			shortTexts = now;
			repaint();
		}
	}

	/**
	 * T-085: texts drawn in the same form, the longest that fits them all: a card's weapon rows, so one row doesn't
	 * read {@code 30 attacks · 8.6 DPS (expected 7.6)} over another's {@code 5.4 DPS (exp 5.6)}. Each member's forms
	 * are its text then its short forms, in the same order of length. Swing's thread only.
	 */
	static final class FormGroup
	{
		private final java.util.List<SidePanelText> members = new java.util.ArrayList<>();

		void join(SidePanelText t)
		{
			if (t.group != this)
			{
				if (t.group != null)
				{
					t.group.leave(t);
				}
				t.group = this;
				members.add(t);
				repaintAll();
			}
		}

		void leave(SidePanelText t)
		{
			if (t.group == this)
			{
				members.remove(t);
				t.group = null;
				t.drawnForm = -1;
				repaintAll();
			}
		}

		/** The form the members draw: the longest every one of them fits, by what each measured when it last painted. */
		int form(SidePanelText painting, int own)
		{
			int form = own;
			for (SidePanelText m : members)
			{
				if (m != painting)
				{
					form = Math.max(form, m.fitForm >= 0 ? m.fitForm
						: m.fit(SidePanelStyle.metrics(m, m.getFont()), m.room(SidePanelStyle.metrics(m, m.suffixFont()))));
				}
			}
			return form;
		}

		/** After a member drew {@code form}: the others that drew another form draw again. */
		void drawn(SidePanelText painting, int form)
		{
			for (SidePanelText m : members)
			{
				if (m != painting && m.drawnForm >= 0 && m.drawnForm != form)
				{
					m.repaint();
				}
			}
		}

		private void repaintAll()
		{
			for (SidePanelText m : members)
			{
				m.repaint();
			}
		}

		int size()
		{
			return members.size();
		}
	}

	private FormGroup group;
	/** T-085: the form this text fit when it last painted, and the form it drew; -1 before its first paint. */
	private int fitForm = -1;
	private int drawnForm = -1;

	/** T-085: draws in {@code g}'s form, or on its own with null. */
	void setGroup(FormGroup g)
	{
		if (g == null)
		{
			if (group != null)
			{
				group.leave(this);
			}
		}
		else
		{
			g.join(this);
		}
	}

	/** The first of the forms (the text, then the short forms) that fits {@code room}; the last when none does. */
	private int fit(FontMetrics fm, int room)
	{
		if (fm.stringWidth(text) <= room)
		{
			return 0;
		}
		for (int i = 0; i < shortTexts.size(); i++)
		{
			if (fm.stringWidth(shortTexts.get(i)) <= room)
			{
				return i + 1;
			}
		}
		return shortTexts.size();
	}

	/** The width the first run has: all of it less the second run's. */
	private int room(FontMetrics suffixMetrics)
	{
		return Math.max(0, getWidth() - suffixMetrics.stringWidth(suffix));
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
		SidePanelStyle.textHints((java.awt.Graphics2D) g);
		final Font font = getFont();
		g.setFont(font);
		final FontMetrics fm = SidePanelStyle.metrics(g, font);
		final FontMetrics sm = SidePanelStyle.metrics(g, suffixFont());
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
		// The short form where the text would be cut (T-063), cut itself only if even it doesn't fit.
		String full = text;
		if (group != null && !shortTexts.isEmpty())
		{
			// T-085: the group's form, the longest every member fits.
			fitForm = fit(fm, room);
			final int form = Math.min(shortTexts.size(), group.form(this, fitForm));
			full = form == 0 ? text : shortTexts.get(form - 1);
			drawnForm = form;
			group.drawn(this, form);
		}
		else if (!shortTexts.isEmpty() && fm.stringWidth(text) > room)
		{
			// The first short form that fits; the shortest, to be cut, when none does.
			full = shortTexts.get(shortTexts.size() - 1);
			for (String form : shortTexts)
			{
				if (fm.stringWidth(form) <= room)
				{
					full = form;
					break;
				}
			}
		}
		final String shown = LedgerRows.cut(full, fm::stringWidth, room);
		final int width = fm.stringWidth(shown) + suffixWidth;
		final int x = right ? Math.max(0, getWidth() - width) : 0;
		final int baseline = getHeight() - fm.getDescent();
		g.setColor(lifted(colour));
		g.drawString(shown, x, baseline);
		if (!after.isEmpty())
		{
			g.setColor(lifted(suffixColour));
			g.setFont(suffixFont());
			g.drawString(after, x + fm.stringWidth(shown), baseline);
		}
		setToolTipText(shown.equals(text) && after.equals(suffix) ? null : text + suffix);
		lastShown = shown;
	}

	/** What the last paint drew of the first run, for tests. */
	private String lastShown;

	String lastShown()
	{
		return lastShown;
	}
}

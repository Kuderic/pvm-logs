package com.pvmlogs;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * The small pieces the Fight tab is made of: a label/value line, a chip, a
 * wrapped paragraph, a swatch, the cards, and the two-line list rows. Each has
 * a {@code set} that only copies texts and colours, for the snapshot pipeline.
 */
final class SidePanelParts
{
	private SidePanelParts()
	{
	}

	/** A plain container with the stack layout and no background of its own. */
	static JPanel stack(int gap)
	{
		final JPanel p = new JPanel(new SidePanelStack(gap));
		p.setOpaque(false);
		return p;
	}

	/** A card: DARKER_GRAY, 6 px top and bottom, 7 px left and right. */
	static JPanel card(int gap)
	{
		final JPanel p = new JPanel(new SidePanelStack(gap));
		p.setBackground(SidePanelStyle.CARD);
		p.setOpaque(true);
		p.setBorder(new EmptyBorder(SidePanelStyle.CARD_PAD_V, SidePanelStyle.CARD_PAD_H, SidePanelStyle.CARD_PAD_V,
			SidePanelStyle.CARD_PAD_H));
		return p;
	}

	/** A label on the left (cut to fit, with an optional second run) and a value on the right. */
	/** T-053: every text part under {@code c} takes its role's new font and line height. */
	static void restyle(java.awt.Container c)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelText)
			{
				((SidePanelText) k).restyle();
			}
			else if (k instanceof Chip)
			{
				((Chip) k).restyle();
			}
			else if (k instanceof Paragraph)
			{
				((Paragraph) k).restyle();
			}
			else if (k instanceof Legend)
			{
				((Legend) k).restyle();
			}
			else if (k instanceof SidePanelTimeBudgetBar)
			{
				((SidePanelTimeBudgetBar) k).restyle();
			}
			else if (k instanceof java.awt.Container)
			{
				restyle((java.awt.Container) k);
			}
		}
	}

	static final class Line extends JPanel
	{
		final SidePanelText left;
		final SidePanelText right;

		Line(PanelSnapshot.Text l, PanelSnapshot.Text r)
		{
			super(new BorderLayout(6, 0));
			setOpaque(false);
			left = new SidePanelText(l, false);
			right = new SidePanelText(r == null ? PanelSnapshot.Text.NONE : r, true);
			add(left, BorderLayout.CENTER);
			add(right, BorderLayout.EAST);
		}

		Line(PanelSnapshot.Row row)
		{
			this(row.left, row.right);
			set(row);
		}

		/** T-112: the indent it's set in by and the gap above it, kept so the border is only set again when they change. */
		private int indent = -1;
		private int above = -1;

		void set(PanelSnapshot.Row row)
		{
			set(row, row.indent ? INDENT : 0, 0);
		}

		/** T-112: with {@code px} of indent on the left (a row under a subheading) and {@code gap} above (a subheading). */
		void set(PanelSnapshot.Row row, int px, int gap)
		{
			if (px != indent || gap != above)
			{
				indent = px;
				above = gap;
				setBorder(px == 0 && gap == 0 ? null : new EmptyBorder(gap, px, 0, 0));
			}
			left.set(row.left, row.hint.isEmpty() ? null : row.hint);
			right.set(row.right);
			// T-109: a row's tooltip on the whole line, both its texts.
			setToolTipText(row.tooltip);
			left.setToolTipText(row.tooltip);
			right.setToolTipText(row.tooltip);
		}

		void set(PanelSnapshot.Text l, PanelSnapshot.Text lAfter, PanelSnapshot.Text r)
		{
			left.set(l, lAfter);
			right.set(r == null ? PanelSnapshot.Text.NONE : r);
		}
	}

	/** T-112: a row under a subheading is set in by this much (Eric's pick, layout A). */
	static final int INDENT = 8;

	/** T-112: the gap above a subheading that isn't the card's first row. */
	static final int GROUP_GAP = 5;

	/** Rows of {@link Line}s in a card, updated in place while their count holds. */
	static final class RowsCard extends JPanel
	{
		private final List<Line> lines = new ArrayList<>();

		/** T-112: while placing rows, whether the row before was in a group. */
		private boolean previousIndented;

		RowsCard(List<PanelSnapshot.Row> rows, int gap)
		{
			super(new SidePanelStack(gap));
			setBackground(SidePanelStyle.CARD);
			setBorder(new EmptyBorder(SidePanelStyle.CARD_PAD_V, SidePanelStyle.CARD_PAD_H, SidePanelStyle.CARD_PAD_V,
				SidePanelStyle.CARD_PAD_H));
			for (int i = 0; i < rows.size(); i++)
			{
				final Line l = new Line(rows.get(i));
				place(l, rows.get(i), i);
				lines.add(l);
				add(l);
			}
		}

		void set(List<PanelSnapshot.Row> rows)
		{
			for (int i = 0; i < lines.size() && i < rows.size(); i++)
			{
				place(lines.get(i), rows.get(i), i);
			}
		}

		/**
		 * T-112: a row under a subheading set in; a gap above a subheading that isn't the first row, and above a row
		 * standing alone after a group, so it doesn't read as the group's last.
		 */
		private void place(Line l, PanelSnapshot.Row r, int i)
		{
			final boolean gap = i > 0 && (r.heading || (!r.indent && previousIndented));
			previousIndented = r.indent;
			l.set(r, r.indent ? INDENT : 0, gap ? GROUP_GAP : 0);
		}
	}

	/** How many times a value asked for layout (Swing's thread); tests read it across a fight's updates. */
	static int relayouts;

	/**
	 * T-046: a component's size changed and its parent should lay it out again.
	 * Only when it's on screen: one in a folded section, or a tab that isn't
	 * showing, is marked for the next layout without asking for one now.
	 */
	static void relayout(JComponent c)
	{
		relayouts++;
		if (c.isShowing())
		{
			c.revalidate();
		}
		else
		{
			c.invalidate();
		}
	}

	/** A luck chip in a list: a small word on a fill (spec section 2). */
	static final class Chip extends JComponent
	{
		private PanelSnapshot.Text text = PanelSnapshot.Text.NONE;
		private Color fill;
		/** The widest it has been (T-046): it asks for that, so it lays out again only when it grows past it. */
		private int reserved;

		Chip()
		{
			setOpaque(false);
			setFont(SidePanelStyle.Type.SMALL.font());
		}

		void set(PanelSnapshot.Text t, Color fill)
		{
			final PanelSnapshot.Text now = t == null ? PanelSnapshot.Text.NONE : t;
			final boolean width = !now.text.equals(text.text) || (fill == null) != (this.fill == null);
			final boolean wasEmpty = text.isEmpty();
			text = now;
			this.fill = fill;
			if (width)
			{
				// As a text does (T-046): once laid out, a changing chip reserves room for its widest digits.
				final int needed = getWidth() > 0 ? moving() : natural();
				// Its height differs when empty, so turning empty or not lays out too.
				if (needed > reserved || wasEmpty != text.isEmpty())
				{
					reserved = Math.max(reserved, needed);
					if (needed > getWidth() || wasEmpty != text.isEmpty())
					{
						relayout(this);
					}
				}
			}
			repaint();
		}

		/** T-053: Small's new font; the width it asks for is measured again. */
		void restyle()
		{
			setFont(SidePanelStyle.Type.SMALL.font());
			reserved = 0;
			revalidate();
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
					relayout(this);
				}
			}
		}

		private int natural()
		{
			return text.isEmpty() ? 0 : SidePanelStyle.metrics(this, getFont()).stringWidth(text.text) + (fill == null ? 0 : 8);
		}

		private int moving()
		{
			return text.isEmpty() ? 0 : SidePanelText.reservedWidth(SidePanelStyle.metrics(this, getFont()), text.text)
				+ (fill == null ? 0 : 8);
		}

		@Override
		public Dimension getPreferredSize()
		{
			reserved = Math.max(reserved, natural());
			final int line = SidePanelStyle.Type.SMALL.lineHeight();
			return new Dimension(reserved, text.isEmpty() ? line : line + 2);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (text.isEmpty())
			{
				return;
			}
			SidePanelStyle.textHints((Graphics2D) g);
			final FontMetrics fm = SidePanelStyle.metrics(g, getFont());
			final int w = fm.stringWidth(text.text) + (fill == null ? 0 : 8);
			final int x = getWidth() - w;
			if (fill != null)
			{
				g.setColor(fill);
				g.fillRect(x, 0, w, getHeight());
			}
			g.setFont(getFont());
			g.setColor(text.colour);
			g.drawString(text.text, x + (fill == null ? 0 : 4), getHeight() - 2 - fm.getDescent() + 1);
		}
	}

	/**
	 * A paragraph wrapped at word breaks to the width it's given, in one type
	 * role. Painted by hand like {@link SidePanelText}, so it wraps the same in
	 * the client and in the renders.
	 */
	static final class Paragraph extends JComponent
	{
		private String text;
		private Color colour;
		private final SidePanelStyle.Type type;
		/** The width it's laid out for before the panel has one: the card's inside. */
		private final int defaultWidth;
		/** Small text wraps on 14 px lines with the RuneScape font, as the canvas sets its notes; 2 more than its line. */
		private int lineHeight;

		Paragraph(String text, Color colour, SidePanelStyle.Type type, int defaultWidth)
		{
			this.text = text == null ? "" : text;
			this.colour = colour;
			this.type = type;
			this.defaultWidth = defaultWidth;
			this.lineHeight = lineHeight(type);
			setOpaque(false);
			setFont(type.font());
		}

		private static int lineHeight(SidePanelStyle.Type type)
		{
			return type == SidePanelStyle.Type.SMALL ? type.lineHeight() + 2 : type.lineHeight();
		}

		/** Its text, for tests. */
		String text()
		{
			return text;
		}

		/** T-053: the role's new font and line height. */
		void restyle()
		{
			lineHeight = lineHeight(type);
			setFont(type.font());
			revalidate();
			repaint();
		}

		void set(String newText, Color newColour)
		{
			final String t = newText == null ? "" : newText;
			if (t.equals(text) && newColour.equals(colour))
			{
				return;
			}
			// Layout only when the new text takes a different number of lines (T-046).
			final int width = getWidth() > 0 ? getWidth() : defaultWidth;
			final boolean relayout = !t.equals(text) && lines(width).size() != linesOf(t, width);
			text = t;
			colour = newColour;
			if (relayout)
			{
				relayout(this);
			}
			repaint();
		}

		private int linesOf(String other, int width)
		{
			final String was = text;
			text = other;
			try
			{
				return lines(width).size();
			}
			finally
			{
				text = was;
			}
		}

		List<String> lines(int width)
		{
			final FontMetrics fm = SidePanelStyle.metrics(this, getFont());
			final List<String> lines = new ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (String word : text.split(" "))
			{
				final String tried = line.length() == 0 ? word : line + " " + word;
				if (fm.stringWidth(tried) <= width || line.length() == 0)
				{
					line = new StringBuilder(tried);
				}
				else
				{
					lines.add(line.toString());
					line = new StringBuilder(word);
				}
			}
			if (line.length() > 0)
			{
				lines.add(line.toString());
			}
			return lines;
		}

		@Override
		public Dimension getPreferredSize()
		{
			final int width = getWidth() > 0 ? getWidth() : defaultWidth;
			final int n = lines(width).size();
			return new Dimension(defaultWidth, Math.max(1, n) * lineHeight - (lineHeight - type.lineHeight()));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			SidePanelStyle.textHints((Graphics2D) g);
			final FontMetrics fm = SidePanelStyle.metrics(g, getFont());
			g.setFont(getFont());
			g.setColor(colour);
			int y = type.lineHeight() - fm.getDescent();
			for (String line : lines(getWidth()))
			{
				g.drawString(line, 0, y);
				y += lineHeight;
			}
		}
	}

	/** Paints a fill kind over a shape: solid, striped, or an outline (spec section 2, fills). */
	static void paint(Graphics2D g, Shape area, PanelSnapshot.Fill fill, Color outline)
	{
		switch (fill)
		{
			case KEPT:
				g.setColor(SidePanelStyle.KEPT);
				g.fill(area);
				break;
			case COST:
				g.setColor(SidePanelStyle.COST);
				g.fill(area);
				break;
			case MINOR_COST:
				g.setColor(SidePanelStyle.MINOR_COST);
				g.fill(area);
				break;
			case UNSCORED:
				g.setColor(SidePanelStyle.UNSCORED);
				g.fill(area);
				break;
			case EATING:
				g.setColor(SidePanelStyle.EATING);
				g.fill(area);
				break;
			case MECHANICS:
				g.setColor(SidePanelStyle.MECHANICS);
				g.fill(area);
				break;
			case LUCK_SLOWER:
				stripes(g, area, SidePanelStyle.COST_STRIPE_GROUND, SidePanelStyle.COST);
				break;
			case LUCK_AVERAGE:
				stripes(g, area, SidePanelStyle.TRACK, SidePanelStyle.AVERAGE_STRIPE);
				break;
			case LUCK_FASTER:
				// The legend's swatch for the time luck saved (T-042): best play dimmed under the stripes, as on the bar.
				g.setColor(SidePanelStyle.KEPT);
				g.fill(area);
				SidePanelTimeBudgetBar.saved(g, area, outline);
				break;
			default:
				break;
		}
	}

	/** 45° stripes, 2 px of {@code stripe} on {@code ground}, inside {@code area}. */
	static void stripes(Graphics2D g, Shape area, Color ground, Color stripe)
	{
		final Shape clip = g.getClip();
		g.setColor(ground);
		g.fill(area);
		g.clip(area);
		g.setColor(stripe);
		g.setStroke(new BasicStroke(2f));
		final Rectangle2D b = area.getBounds2D();
		final double h = b.getHeight();
		for (double x = b.getX() - h; x < b.getMaxX() + h; x += 4 * Math.sqrt(2))
		{
			g.draw(new java.awt.geom.Line2D.Double(x, b.getMaxY(), x + h, b.getY()));
		}
		g.setClip(clip);
	}

	/** An 8 × 8 legend swatch. */
	static final class Swatch extends JComponent
	{
		private PanelSnapshot.Fill fill;
		private Color outline;

		Swatch(PanelSnapshot.Fill fill)
		{
			this.fill = fill;
			setOpaque(false);
		}

		void set(PanelSnapshot.Fill f, Color o)
		{
			if (f != fill || o != outline)
			{
				fill = f;
				outline = o;
				repaint();
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(12, 12);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			SidePanelParts.paint(g2, new Rectangle2D.Double(0, 2, 8, 8), fill, outline);
			g2.dispose();
		}
	}

	/** A two-line list row in a card: name and value, then sub-line and chip (targets, rooms, the last fight). */
	static final class ListRowView extends JPanel
	{
		private final Line top;
		private final SidePanelText sub;
		private final Chip chip = new Chip();

		ListRowView(PanelSnapshot.ListRow row)
		{
			super(new SidePanelStack(4));
			setBackground(SidePanelStyle.CARD);
			setBorder(new EmptyBorder(SidePanelStyle.CARD_PAD_V, SidePanelStyle.CARD_PAD_H, SidePanelStyle.CARD_PAD_V,
				SidePanelStyle.CARD_PAD_H));
			top = new Line(row.name, row.value);
			add(top);
			final JPanel bottom = new JPanel(new BorderLayout(6, 0));
			bottom.setOpaque(false);
			sub = new SidePanelText(row.sub, false);
			bottom.add(sub, BorderLayout.CENTER);
			bottom.add(chip, BorderLayout.EAST);
			add(bottom);
			set(row);
		}

		void set(PanelSnapshot.ListRow row)
		{
			top.set(row.name, row.suffix, row.value);
			sub.set(row.sub);
			chip.set(row.chip, row.chipFill);
		}
	}

	/**
	 * A damage source row, loa-logs style (spec 3.1): the share painted behind the
	 * row in the source's colour at 30%, a 36 × 32 icon, name and sub-line on the
	 * left, damage and share on the right. The icon is an item's image or a
	 * spell's sprite, loaded through {@link SidePanelIcons}; until it arrives (or
	 * with none) a swatch of the source's colour stands in.
	 */
	static final class SourceRowView extends JPanel
	{
		static final int ICON_WIDTH = 36;
		static final int ICON_HEIGHT = 32;

		private final SidePanelIcons icons;
		private final Line top;
		private final Line bottom;
		/** T-085: the card's weapon rows draw their sub-lines in one form; null for a row on its own. */
		private final SidePanelText.FormGroup forms;
		private final Icon icon = new Icon();
		/** T-085: hovered, as the panel's other rows: the Selected fill under the share. */
		private boolean hover;
		private double share;
		private Color colour;
		private int itemId = Integer.MIN_VALUE;
		private int spriteId = Integer.MIN_VALUE;

		SourceRowView(PanelSnapshot.Source s)
		{
			this(s, SidePanelIcons.NONE);
		}

		SourceRowView(PanelSnapshot.Source s, SidePanelIcons icons)
		{
			this(s, icons, null);
		}

		/** T-085: with the card's {@code forms}, which its weapon rows' sub-lines join. */
		SourceRowView(PanelSnapshot.Source s, SidePanelIcons icons, SidePanelText.FormGroup forms)
		{
			super(new BorderLayout(6, 0));
			this.forms = forms;
			this.icons = icons == null ? SidePanelIcons.NONE : icons;
			setOpaque(false);
			setBorder(new EmptyBorder(2, 2, 2, 6));
			final JPanel text = stack(3);
			text.setBorder(new EmptyBorder(2, 0, 2, 0));
			top = new Line(PanelSnapshot.Text.body(s.name, SidePanelStyle.VALUE),
				PanelSnapshot.Text.title(s.damage, SidePanelStyle.VALUE));
			bottom = new Line(PanelSnapshot.Text.small(s.sub, SidePanelStyle.SECONDARY),
				PanelSnapshot.Text.small(s.share, SidePanelStyle.SECONDARY));
			text.add(top);
			text.add(bottom);
			add(icon, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					hover = getToolTipText() != null;
					repaint();
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
			set(s);
		}

		boolean hovered()
		{
			return hover;
		}

		void set(PanelSnapshot.Source s)
		{
			top.set(new PanelSnapshot.Row(PanelSnapshot.Text.body(s.name, SidePanelStyle.VALUE),
				PanelSnapshot.Text.title(s.damage, SidePanelStyle.VALUE)));
			bottom.set(new PanelSnapshot.Row(PanelSnapshot.Text.small(s.sub, SidePanelStyle.SECONDARY),
				PanelSnapshot.Text.small(s.share, SidePanelStyle.SECONDARY)));
			// T-085: the sub-line's shorter forms, in the card's one form for a weapon row; the details on hover.
			bottom.left.setShort(s.subShort);
			if (forms != null)
			{
				bottom.left.setGroup(s.dps != null && !s.subShort.isEmpty() ? forms : null);
			}
			if (!java.util.Objects.equals(s.tooltip, getToolTipText()))
			{
				setToolTipText(s.tooltip);
			}
			if (s.shareFraction != share || !s.colour.equals(colour))
			{
				share = s.shareFraction;
				colour = s.colour;
				icon.colour = s.colour;
				repaint();
			}
			if (s.itemId != itemId || s.spriteId != spriteId)
			{
				itemId = s.itemId;
				spriteId = s.spriteId;
				icon.image = null;
				icon.repaint();
				final int wantItem = itemId;
				final int wantSprite = spriteId;
				final java.util.function.Consumer<java.awt.image.BufferedImage> done = img ->
				{
					// Only if the row still shows the same source: a late icon for a row reused for another is dropped.
					if (img != null && itemId == wantItem && spriteId == wantSprite)
					{
						icon.image = img;
						icon.repaint();
					}
				};
				if (itemId >= 0)
				{
					icons.item(itemId, done);
				}
				else if (spriteId >= 0)
				{
					icons.sprite(spriteId, done);
				}
			}
		}

		boolean hasIcon()
		{
			return icon.image != null;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(hover ? SidePanelStyle.SELECTED : SidePanelStyle.CARD);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(SidePanelStyle.alpha(colour, 0.30));
			g.fillRect(0, 0, (int) Math.round(getWidth() * Math.max(0, Math.min(1, share))), getHeight());
		}

		/** The 36 × 32 icon box, transparent: the image centred, or a small swatch in the source's colour. */
		private static final class Icon extends javax.swing.JComponent
		{
			java.awt.image.BufferedImage image;
			Color colour = SidePanelStyle.SECONDARY;

			@Override
			public Dimension getPreferredSize()
			{
				return new Dimension(ICON_WIDTH, ICON_HEIGHT);
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				// No box behind the icon: the row's share bar shows through it (Eric, 2026-10-29).
				if (image != null)
				{
					g.drawImage(image, (getWidth() - image.getWidth()) / 2, (getHeight() - image.getHeight()) / 2, null);
					return;
				}
				g.setColor(colour);
				g.fillRect(getWidth() / 2 - 4, getHeight() / 2 - 4, 8, 8);
			}
		}
	}

	/**
	 * A chart's legend: swatches and words left to right, wrapping onto a second
	 * line when they don't fit. Painted by hand so its height is known before
	 * the panel lays it out, as {@link Paragraph}'s is.
	 */
	static final class Legend extends javax.swing.JComponent
	{
		/** One entry: a swatch (a colour, or striped, or none) and its words. */
		static final class Item
		{
			final Color swatch;
			final boolean striped;
			final String text;
			final Color textColour;

			Item(Color swatch, boolean striped, String text, Color textColour)
			{
				this.swatch = swatch;
				this.striped = striped;
				this.text = text;
				this.textColour = textColour;
			}
		}

		private static final int SWATCH = 8;
		private static final int GAP = 9;
		private java.util.List<Item> items = new ArrayList<>();
		private final int defaultWidth;

		Legend(int defaultWidth)
		{
			this.defaultWidth = defaultWidth;
			setOpaque(false);
			setFont(SidePanelStyle.Type.SMALL.font());
		}

		/** T-053: Small's new font and line height. */
		void restyle()
		{
			setFont(SidePanelStyle.Type.SMALL.font());
			revalidate();
			repaint();
		}

		/** What it shows; for tests. */
		java.util.List<Item> items()
		{
			return items;
		}

		void set(java.util.List<Item> newItems)
		{
			// Layout only when the legend takes a different number of lines (T-046): its counts change in place.
			final int width = getWidth() > 0 ? getWidth() : defaultWidth;
			final int before = lines(width);
			items = newItems;
			if (lines(width) != before)
			{
				relayout(this);
			}
			repaint();
		}

		/** A line of the legend: Small's line height (T-053; 12 with the RuneScape fonts). */
		private static int line()
		{
			return SidePanelStyle.Type.SMALL.lineHeight();
		}

		private int itemWidth(FontMetrics fm, Item it)
		{
			return (it.swatch == null ? 0 : SWATCH + 3) + fm.stringWidth(it.text);
		}

		private int lines(int width)
		{
			final FontMetrics fm = SidePanelStyle.metrics(this, getFont());
			int lines = 1;
			int x = 0;
			for (Item it : items)
			{
				final int w = itemWidth(fm, it);
				if (x > 0 && x + w > width)
				{
					lines++;
					x = 0;
				}
				x += w + GAP;
			}
			return lines;
		}

		@Override
		public Dimension getPreferredSize()
		{
			final int width = getWidth() > 0 ? getWidth() : defaultWidth;
			final int n = lines(width);
			return new Dimension(defaultWidth, n * line() + (n - 1) * 3);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g;
			SidePanelStyle.textHints(g2);
			g2.setFont(getFont());
			final FontMetrics fm = SidePanelStyle.metrics(g2, getFont());
			int x = 0;
			int y = 0;
			for (Item it : items)
			{
				final int w = itemWidth(fm, it);
				if (x > 0 && x + w > getWidth())
				{
					x = 0;
					y += line() + 3;
				}
				int tx = x;
				if (it.swatch != null)
				{
					final java.awt.geom.Rectangle2D.Double r = new java.awt.geom.Rectangle2D.Double(x, y + (line() - SWATCH) / 2,
						SWATCH, SWATCH);
					if (it.striped)
					{
						stripes(g2, r, SidePanelStyle.COST_STRIPE_GROUND, it.swatch);
					}
					else
					{
						g2.setColor(it.swatch);
						g2.fill(r);
					}
					tx += SWATCH + 3;
				}
				g2.setColor(it.textColour);
				g2.drawString(it.text, tx, y + line() - fm.getDescent());
				x += w + GAP;
			}
		}
	}

	/** The warning banner's border and fill. */
	static JPanel banner()
	{
		final JPanel p = new JPanel(new SidePanelStack(5));
		p.setBackground(SidePanelStyle.WARNING_FILL);
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SidePanelStyle.WARNING_EDGE),
			new EmptyBorder(5, 6, 5, 6)));
		return p;
	}
}

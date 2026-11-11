package com.pvmlogs;

import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * A titled block (spec section 2): the title in bold white on the left, a
 * hint or total on the right, and the body under it. Clicking the header folds
 * the body away; a folded section stays folded, across fights and restarts,
 * through the {@link Folds} it's given (spec section 7).
 * <p>
 * T-206 (Eric, 2026-11-10 22:12: "it should all be contained in one colored section so the user knows what goes with
 * what"): the section is one card, folded or open: the chevron and title at its top, inside, and the body under them on
 * the same ground. A body that is a card gives up its own ground; a body that is a list of cards makes them rows of this
 * one, a {@link SidePanelStyle#DIVIDER_ON_CARD} line between them. Parts with their own ground (a tinted source row, a
 * selected row) keep it, inset on the card.
 */
final class SidePanelSection extends JPanel
{
	/** Where folded sections are remembered. */
	interface Folds
	{
		boolean isFolded(String id);

		void setFolded(String id, boolean folded);
	}

	/** Folds kept for as long as the panel is open, for tests and renders. */
	static final class MemoryFolds implements Folds
	{
		private final java.util.Set<String> folded = new java.util.HashSet<>();

		@Override
		public boolean isFolded(String id)
		{
			return folded.contains(id);
		}

		@Override
		public void setFolded(String id, boolean f)
		{
			if (f)
			{
				folded.add(id);
			}
			else
			{
				folded.remove(id);
			}
		}
	}

	/**
	 * T-198 (Eric, 23:34: "make it more clear that the cards are clickable and expandable"): the header's tooltip, folded
	 * and open, after any of its own. A chevron at its left (folded pointing right, open pointing down), a lighter ground
	 * on hover and the hand cursor say it too; the faint "+" after the hint is gone.
	 */
	static final String OPEN_TIP = "Click to open";
	static final String FOLD_TIP = "Click to fold";

	private final String id;
	/** T-102: its title, for tests. */
	private final String title;
	private final Folds folds;
	/** T-198: the expand mark at the header's left. */
	private final Chevron chevron = new Chevron();
	/** T-126: the header's own tooltip before the fold's; null for none. */
	private String ownTip;
	/** T-198: whether the mouse is over the header (and the folded line under it). */
	private boolean hover;
	private final SidePanelParts.Line header;
	private final JComponent body;
	private PanelSnapshot.Text hint = PanelSnapshot.Text.NONE;
	/** T-105: a control in the hint's place (the Damage card's switch); null for the text hint. */
	private JComponent hintComponent;
	/** T-188: the header and, while folded, the one-line summary under it. */
	private final JPanel head;
	/** T-206: the title strip (chevron, title, hint), the only part the hover lights. */
	private final JPanel strip;
	private final Summary summary = new Summary();
	/** T-206: the summary with the card's padding round it. */
	private final JPanel summaryBox = new JPanel(new SidePanelStack(0));
	/** T-206: the body's parts that were cards and are rows of this one now, a divider between each and the one above. */
	private final java.util.Set<java.awt.Component> rows = java.util.Collections.newSetFromMap(
		new java.util.IdentityHashMap<>());
	/** T-188: run when the section opens, so a body skipped while folded catches up. */
	private final java.util.List<Runnable> onOpen = new java.util.ArrayList<>(1);

	SidePanelSection(String id, String title, PanelSnapshot.Text hint, JComponent body, Folds folds)
	{
		super(new SidePanelStack(0));
		this.id = id;
		this.title = title;
		this.folds = folds;
		this.body = body;
		// T-206: one card, folded or open.
		setOpaque(true);
		setBackground(SidePanelStyle.CARD);
		header = new SidePanelParts.Line(PanelSnapshot.Text.title(title, SidePanelStyle.VALUE), hint);
		head = new JPanel(new SidePanelStack(0));
		head.setOpaque(false);
		final MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				setHover(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				// Moving between the header's parts isn't leaving it.
				final java.awt.Point p = javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), head);
				setHover(head.contains(p));
			}
		};
		// The chevron, then the title and its hint: the whole row is the click target.
		final JPanel top = new JPanel(new java.awt.BorderLayout(4, 0));
		top.setOpaque(false);
		top.add(chevron, java.awt.BorderLayout.WEST);
		top.add(header, java.awt.BorderLayout.CENTER);
		strip = new JPanel(new SidePanelStack(0));
		strip.setOpaque(false);
		strip.setBackground(SidePanelStyle.CARD_HOVER);
		strip.add(top);
		summaryBox.setOpaque(false);
		summaryBox.setBorder(new EmptyBorder(0, SidePanelStyle.CARD_PAD_H, SUMMARY_PAD_BOTTOM, SidePanelStyle.CARD_PAD_H));
		summaryBox.add(summary);
		// The parts with their own listener take the mouse; the header's texts and the chevron pass it to theirs.
		for (JComponent c : new JComponent[]{head, strip, top, header, summary, summaryBox})
		{
			c.addMouseListener(click);
		}
		for (JComponent c : new JComponent[]{head, strip, top, chevron, header, header.left, header.right, summary,
			summaryBox})
		{
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		head.add(strip);
		summaryBox.setVisible(false);
		head.add(summaryBox);
		add(head);
		add(body);
		flatten();
		body.setVisible(!folds.isFolded(id));
		restyleHead();
		setHint(hint);
	}

	/** T-206: the title strip's padding: the card's sides, 5 px above the title. */
	static final int STRIP_TOP = 5;
	/** T-206: under the title: on its own (folded, no summary), before the summary, and before the open body. */
	static final int STRIP_BOTTOM_ALONE = 6;
	static final int STRIP_BOTTOM_SUMMARY = 3;
	static final int STRIP_BOTTOM_OPEN = 2;
	static final int SUMMARY_PAD_BOTTOM = 6;

	/**
	 * T-188, T-206: the title strip at the card's top; folded with a summary, the line under it (as the design canvas
	 * draws the folded sections under the kill card); open, the body under it. Padding only: the card is always there.
	 */
	private void restyleHead()
	{
		final boolean withSummary = isFolded() && !summary.text.isEmpty();
		final int bottom = !isFolded() ? STRIP_BOTTOM_OPEN : withSummary ? STRIP_BOTTOM_SUMMARY : STRIP_BOTTOM_ALONE;
		strip.setBorder(new EmptyBorder(STRIP_TOP, SidePanelStyle.CARD_PAD_H, bottom, SidePanelStyle.CARD_PAD_H));
		summaryBox.setVisible(withSummary);
		chevron.folded = isFolded();
		paintHead();
		setTooltip(ownTip);
	}

	/**
	 * T-198, T-206: the hover lights the title strip only, inside the card ({@link SidePanelStyle#CARD_HOVER}); the rest
	 * of the card stays. A repaint, no layout.
	 */
	private void paintHead()
	{
		strip.setOpaque(hover);
		strip.repaint();
		chevron.repaint();
	}

	/**
	 * T-206: the body's own cards give up their ground to this one. A body that is a card is the card's lower part; a
	 * body that is a stack of parts has each card among them made a row, divided from the part above it. Run when built
	 * and again on each layout (new rows, as History's Show more adds), never per tick.
	 */
	private void flatten()
	{
		if (isCardGround(body))
		{
			body.setOpaque(false);
		}
		if (body.getLayout() instanceof SidePanelStack)
		{
			for (java.awt.Component k : body.getComponents())
			{
				if (isCardGround(k))
				{
					((JComponent) k).setOpaque(false);
					rows.add(k);
				}
			}
		}
	}

	private static boolean isCardGround(java.awt.Component k)
	{
		return k instanceof JComponent && k.isOpaque() && SidePanelStyle.CARD.equals(k.getBackground());
	}

	@Override
	public void doLayout()
	{
		flatten();
		super.doLayout();
	}

	/** T-206: the dividers between the body's rows, as Damage's Timeline is divided from the rows above it. */
	@Override
	protected void paintComponent(java.awt.Graphics g)
	{
		super.paintComponent(g);
		if (!body.isVisible() || rows.isEmpty())
		{
			return;
		}
		g.setColor(SidePanelStyle.DIVIDER_ON_CARD);
		java.awt.Component above = null;
		for (java.awt.Component k : body.getComponents())
		{
			if (!k.isVisible())
			{
				continue;
			}
			if (above != null && (rows.contains(k) || rows.contains(above)))
			{
				final int y = body.getY() + (above.getY() + above.getHeight() + k.getY()) / 2;
				g.fillRect(SidePanelStyle.CARD_PAD_H, y, getWidth() - 2 * SidePanelStyle.CARD_PAD_H, 1);
			}
			above = k;
		}
	}

	/** T-206: the y of each divider line on the card, for tests; empty while folded. */
	java.util.List<Integer> dividers()
	{
		final java.util.List<Integer> out = new java.util.ArrayList<>();
		if (!body.isVisible())
		{
			return out;
		}
		java.awt.Component above = null;
		for (java.awt.Component k : body.getComponents())
		{
			if (!k.isVisible())
			{
				continue;
			}
			if (above != null && (rows.contains(k) || rows.contains(above)))
			{
				out.add(body.getY() + (above.getY() + above.getHeight() + k.getY()) / 2);
			}
			above = k;
		}
		return out;
	}

	/** T-206: the title strip, for tests: what the hover lights. */
	JComponent strip()
	{
		return strip;
	}

	/** T-206: the body, for tests. */
	JComponent body()
	{
		return body;
	}

	private void setHover(boolean h)
	{
		if (hover != h)
		{
			hover = h;
			paintHead();
		}
	}

	/** T-198: whether the header shows its hover, for tests. */
	boolean hovered()
	{
		return hover;
	}

	/** T-198: whether the chevron points down (open), for tests. */
	boolean chevronOpen()
	{
		return !chevron.folded;
	}

	/** T-198: the header's tooltip, for tests. */
	String headerTip()
	{
		return header.getToolTipText();
	}

	/** T-198: the expand mark, 7 px: a triangle pointing right while folded, down while open, centred on the title. */
	private static final class Chevron extends JComponent
	{
		boolean folded;

		@Override
		public java.awt.Dimension getPreferredSize()
		{
			return new java.awt.Dimension(7, SidePanelStyle.Type.TITLE.lineHeight());
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			g.setColor(SidePanelStyle.SECONDARY);
			final int top = Math.min(getHeight(), SidePanelStyle.Type.TITLE.lineHeight()) / 2 - 3;
			if (folded)
			{
				// ▸
				for (int i = 0; i < 4; i++)
				{
					g.fillRect(1 + i, top + i, 1, 7 - 2 * i);
				}
			}
			else
			{
				// ▾
				for (int i = 0; i < 4; i++)
				{
					g.fillRect(i, top + 1 + i, 7 - 2 * i, 1);
				}
			}
		}
	}

	/**
	 * T-188: the section's one line while folded ({@code axe 80% · claws 10% · thrall 10%}); null or empty for none.
	 * Painted, cut to its width: a new value repaints it and lays nothing out, except when it comes or goes.
	 */
	void setSummary(PanelSnapshot.Text t)
	{
		setSummary(t, null);
	}

	/** T-196: the line with its small picture before it (Luck's track, the time's bar, the grade's chip); null for none. */
	void setSummary(PanelSnapshot.Text t, PanelSnapshot.Mark mark)
	{
		final PanelSnapshot.Text now = t == null ? PanelSnapshot.Text.NONE : t;
		final boolean had = !summary.text.isEmpty();
		final boolean tallBefore = summary.tall();
		summary.text = now.text;
		summary.colour = now.colour;
		summary.mark = mark;
		if (had != !now.text.isEmpty() || tallBefore != summary.tall())
		{
			restyleHead();
			revalidate();
		}
		summary.repaint();
	}

	/** T-196: the summary's picture, for tests; null for none. */
	PanelSnapshot.Mark summaryMark()
	{
		return summary.isVisible() ? summary.mark : null;
	}

	/**
	 * T-207: the folded summary's bar in the section's coordinates (its full width on the first line), for tests; null
	 * for none, or a summary without a bar.
	 */
	java.awt.Rectangle summaryBar()
	{
		if (!summary.isVisible() || !summary.tall() || summary.text.isEmpty())
		{
			return null;
		}
		final java.awt.Point p = javax.swing.SwingUtilities.convertPoint(summary.getParent(), summary.getLocation(), this);
		return new java.awt.Rectangle(p.x, p.y + Summary.BAR_TOP, summary.getWidth(), Summary.TIME_BAR);
	}

	/** T-214: the fills the folded time bar drew last (any section's), for tests. */
	static java.util.List<PanelSnapshot.Fill> summaryBarDrawn()
	{
		return Summary.drawn;
	}

	/** T-188: the summary on show, for tests; empty for none. */
	String summary()
	{
		return summary.isVisible() ? summary.text : "";
	}

	/** T-188: runs {@code r} each time the section opens. */
	void onOpen(Runnable r)
	{
		onOpen.add(r);
	}

	/** T-188: one line of small text at a fixed height, cut with "…" to its width. */
	private static final class Summary extends JComponent
	{
		static final int TIME_BAR = 6;
		String text = "";
		java.awt.Color colour = SidePanelStyle.SECONDARY;
		PanelSnapshot.Mark mark;

		/**
		 * The time's bar takes a line of its own above the words. T-207 (Eric: "These 3 collapsed sections have different
		 * length bars"): so do Damage's share bar and Luck's track, one shape for every summary with a bar: the bar the
		 * full width on the first line, the numbers under it. The grade's chip stays beside its words.
		 */
		boolean tall()
		{
			return mark != null && mark.kind != PanelSnapshot.Mark.Kind.GRADE;
		}

		/** T-207: the bar's top in a tall summary, room for Luck's marker above and below it. */
		static final int BAR_TOP = 2;

		@Override
		public java.awt.Dimension getPreferredSize()
		{
			return new java.awt.Dimension(0, SidePanelStyle.Type.SMALL.lineHeight() + (tall() ? BAR_TOP + TIME_BAR + 4 : 0));
		}

		@Override
		protected void paintComponent(java.awt.Graphics g0)
		{
			if (text.isEmpty())
			{
				return;
			}
			final java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
			SidePanelStyle.textHints(g);
			g.setFont(SidePanelStyle.Type.SMALL.font());
			final java.awt.FontMetrics fm = g.getFontMetrics();
			final int line = SidePanelStyle.Type.SMALL.lineHeight();
			final int w = getWidth();
			int x = 0;
			if (mark != null)
			{
				x = paintMark(g, mark, w, getHeight() - line, line);
			}
			final int base = getHeight() - fm.getDescent();
			if (mark != null && mark.runs.length > 0)
			{
				// T-199: each label with its number in its colour; the last that doesn't fit is cut with "…".
				for (PanelSnapshot.Text run : mark.runs)
				{
					final int room = w - x;
					final String shown = LedgerRows.cut(run.text, fm::stringWidth, room);
					g.setColor(run.colour);
					g.drawString(shown, x, base);
					x += fm.stringWidth(shown);
					if (!shown.equals(run.text) || x >= w)
					{
						break;
					}
				}
				g.dispose();
				return;
			}
			g.setColor(colour);
			g.drawString(LedgerRows.cut(text, fm::stringWidth, w - x), x, base);
			g.dispose();
		}

		/** T-214: the time bar's fills as last painted, for tests. */
		static java.util.List<PanelSnapshot.Fill> drawn = java.util.Collections.emptyList();

		/** The picture; returns where the words start. */
		private static int paintMark(java.awt.Graphics2D g, PanelSnapshot.Mark m, int w, int lineTop, int line)
		{
			switch (m.kind)
			{
				case TIME:
				{
					// T-214: the open section's bar, its segments, order and colours, 6 px tall; no tick, labels or marker.
					drawn = SidePanelTimeBudgetBar.paintBar(g, m.budget, 0, BAR_TOP, w, TIME_BAR);
					return 0;
				}
				case SHARES:
				{
					// T-198: Damage's share bar in small; T-207: the full width, the numbers under it.
					final int y = BAR_TOP;
					double total = 0;
					for (double f : m.shares)
					{
						total += f;
					}
					int x = 0;
					double acc = 0;
					for (int i = 0; i < m.shares.length; i++)
					{
						acc += m.shares[i];
						final int end = i == m.shares.length - 1 ? w : (int) Math.round(acc / Math.max(1e-9, total) * w);
						g.setColor(m.colours[i]);
						g.fillRect(x, y, Math.max(0, end - x), TIME_BAR);
						x = end;
					}
					return 0;
				}
				case LUCK:
				{
					// T-207: the track the full width, its marker standing above and below it, the band under it.
					final int y = BAR_TOP;
					final int a = w / 4;
					final int b = w * 3 / 4;
					g.setColor(SidePanelStyle.LUCK_TRACK_DIM);
					g.fillRect(0, y, a, TIME_BAR);
					g.setColor(SidePanelStyle.LUCK_TRACK_NORMAL);
					g.fillRect(a, y, b - a, TIME_BAR);
					g.setColor(SidePanelStyle.LUCK_TRACK_BRIGHT);
					g.fillRect(b, y, w - b, TIME_BAR);
					final int at = (int) Math.round((Math.max(1, Math.min(100, m.roll)) - 1) / 99.0 * (w - 2));
					g.setColor(SidePanelStyle.VALUE);
					g.fillRect(at, y - BAR_TOP, 2, TIME_BAR + 2 * BAR_TOP);
					return 0;
				}
				default:
				{
					// The grade's chip: its letter in its colour, edged with it.
					final java.awt.FontMetrics fm = g.getFontMetrics();
					final int cw = fm.stringWidth(m.grade) + 6;
					g.setColor(m.colour);
					g.drawRect(0, lineTop, cw - 1, line - 1);
					g.drawString(m.grade, 3, lineTop + line - fm.getDescent());
					return cw + 5;
				}
			}
		}
	}

	String id()
	{
		return id;
	}

	String title()
	{
		return title;
	}

	boolean isFolded()
	{
		return !body.isVisible();
	}

	void toggle()
	{
		final boolean fold = body.isVisible();
		folds.setFolded(id, fold);
		if (!fold)
		{
			// T-188: the body was left as it was while folded; bring it up to date before it shows.
			for (Runnable r : onOpen)
			{
				r.run();
			}
		}
		body.setVisible(!fold);
		restyleHead();
		setHint(hint);
		revalidate();
		repaint();
	}

	/**
	 * T-126: a tooltip for the header, before its "Click to fold or open"; null for that alone. Text only: set only
	 * when it changes, and never a layout.
	 */
	void setTooltip(String tooltip)
	{
		ownTip = tooltip;
		final String fold = isFolded() ? OPEN_TIP : FOLD_TIP;
		// T-201: through the panel's one tooltip helper, its own lines and then the fold's.
		final String text = SidePanelStyle.tip(tooltip == null ? fold : tooltip + "<br>" + fold);
		if (!text.equals(header.getToolTipText()))
		{
			header.setToolTipText(text);
		}
	}

	void setHint(PanelSnapshot.Text h)
	{
		hint = h == null ? PanelSnapshot.Text.NONE : h;
		if (hintComponent != null)
		{
			// T-105: the control hides while folded; the chevron says it opens.
			hintComponent.setVisible(!isFolded());
			header.right.set(PanelSnapshot.Text.NONE);
			return;
		}
		header.right.set(hint);
	}

	/**
	 * T-105: a small control in the hint's place on the header's right (the Damage card's {@code boss · adds · all}),
	 * set once when the section is built. Its clicks are its own; a click elsewhere on the header still folds.
	 */
	void setHintComponent(JComponent c)
	{
		header.remove(header.right);
		final JPanel east = new JPanel(new java.awt.BorderLayout(0, 0));
		east.setOpaque(false);
		east.add(c, java.awt.BorderLayout.CENTER);
		east.add(header.right, java.awt.BorderLayout.EAST);
		header.add(east, java.awt.BorderLayout.EAST);
		hintComponent = c;
		setHint(hint);
	}
}

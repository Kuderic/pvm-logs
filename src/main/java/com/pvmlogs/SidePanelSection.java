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

	/** Shown after the hint while folded, so a folded section reads as one that opens. */
	static final String FOLDED_MARK = "+";

	private final String id;
	/** T-102: its title, for tests. */
	private final String title;
	/** The header's own tooltip. */
	static final String FOLD_TIP = "Click to fold or open";
	private final Folds folds;
	private final SidePanelParts.Line header;
	private final JComponent body;
	private PanelSnapshot.Text hint = PanelSnapshot.Text.NONE;
	/** T-105: a control in the hint's place (the Damage card's switch); null for the text hint. */
	private JComponent hintComponent;
	/** T-188: the header and, while folded, the one-line summary under it, on a card. */
	private final JPanel head;
	private final Summary summary = new Summary();
	/** T-188: run when the section opens, so a body skipped while folded catches up. */
	private final java.util.List<Runnable> onOpen = new java.util.ArrayList<>(1);

	SidePanelSection(String id, String title, PanelSnapshot.Text hint, JComponent body, Folds folds)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.id = id;
		this.title = title;
		this.folds = folds;
		this.body = body;
		setOpaque(false);
		header = new SidePanelParts.Line(PanelSnapshot.Text.title(title, SidePanelStyle.VALUE), hint);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setToolTipText(FOLD_TIP);
		final MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}
		};
		header.addMouseListener(click);
		head = new JPanel(new SidePanelStack(3));
		head.add(header);
		summary.setVisible(false);
		summary.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		summary.addMouseListener(click);
		head.add(summary);
		add(head);
		add(body);
		body.setVisible(!folds.isFolded(id));
		restyleHead();
		setHint(hint);
	}

	/**
	 * T-188: the header as before; folded with a summary, a card holding the title, its value and the line under them,
	 * as the design canvas draws the folded sections under the kill card.
	 */
	private void restyleHead()
	{
		final boolean card = isFolded() && !summary.text.isEmpty();
		head.setOpaque(card);
		head.setBackground(SidePanelStyle.CARD);
		head.setBorder(card ? new EmptyBorder(5, 7, 6, 7) : new EmptyBorder(4, 0, 0, 0));
		summary.setVisible(card);
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
		static final int LUCK_TRACK_W = 44;
		String text = "";
		java.awt.Color colour = SidePanelStyle.SECONDARY;
		PanelSnapshot.Mark mark;

		/** The time's bar takes a line of its own above the words. */
		boolean tall()
		{
			return mark != null && mark.kind == PanelSnapshot.Mark.Kind.TIME;
		}

		@Override
		public java.awt.Dimension getPreferredSize()
		{
			return new java.awt.Dimension(0, SidePanelStyle.Type.SMALL.lineHeight() + (tall() ? TIME_BAR + 4 : 0));
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
			g.setColor(colour);
			g.drawString(LedgerRows.cut(text, fm::stringWidth, w - x), x, getHeight() - fm.getDescent());
			g.dispose();
		}

		/** The picture; returns where the words start. */
		private static int paintMark(java.awt.Graphics2D g, PanelSnapshot.Mark m, int w, int lineTop, int line)
		{
			switch (m.kind)
			{
				case TIME:
				{
					// The perfect fight, what was lost, and luck's stretch striped in its violet.
					g.setColor(SidePanelStyle.TRACK);
					g.fillRect(0, 0, w, TIME_BAR);
					final int kept = (int) Math.round(m.kept * w);
					final int lost = (int) Math.round((m.kept + m.lost) * w);
					g.setColor(SidePanelStyle.KEPT);
					g.fillRect(0, 0, kept, TIME_BAR);
					g.setColor(SidePanelStyle.COST);
					g.fillRect(kept, 0, Math.max(m.lost > 0 ? 1 : 0, lost - kept), TIME_BAR);
					final int from = m.luckSaved ? (int) Math.round((1 - m.luck) * w) : lost;
					final int to = m.luckSaved ? w : (int) Math.round((m.kept + m.lost + m.luck) * w);
					g.setColor(SidePanelStyle.LUCK);
					for (int x = from; x < to; x += 3)
					{
						g.fillRect(x, 0, Math.min(2, to - x), TIME_BAR);
					}
					return 0;
				}
				case LUCK:
				{
					final int h = 6;
					final int y = lineTop + (line - h) / 2;
					final int a = LUCK_TRACK_W / 4;
					final int b = LUCK_TRACK_W * 3 / 4;
					g.setColor(SidePanelStyle.LUCK_TRACK_DIM);
					g.fillRect(0, y, a, h);
					g.setColor(SidePanelStyle.LUCK_TRACK_NORMAL);
					g.fillRect(a, y, b - a, h);
					g.setColor(SidePanelStyle.LUCK_TRACK_BRIGHT);
					g.fillRect(b, y, LUCK_TRACK_W - b, h);
					final int at = (int) Math.round((Math.max(1, Math.min(100, m.roll)) - 1) / 99.0 * (LUCK_TRACK_W - 2));
					g.setColor(SidePanelStyle.VALUE);
					g.fillRect(at, y - 2, 2, h + 4);
					return LUCK_TRACK_W + 6;
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
		final String text = tooltip == null ? FOLD_TIP : tooltip.startsWith("<html>")
			? tooltip.replace("</html>", "<br>" + FOLD_TIP + "</html>") : "<html>" + tooltip + "<br>" + FOLD_TIP + "</html>";
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
			// T-105: the control hides while folded, and the mark alone says it opens.
			hintComponent.setVisible(!isFolded());
			header.right.set(isFolded() ? PanelSnapshot.Text.small(FOLDED_MARK, SidePanelStyle.SECONDARY)
				: PanelSnapshot.Text.NONE);
			return;
		}
		final PanelSnapshot.Text shown = !isFolded() ? hint : hint.isEmpty()
			? PanelSnapshot.Text.small(FOLDED_MARK, SidePanelStyle.SECONDARY)
			: new PanelSnapshot.Text(hint.text + " " + FOLDED_MARK, hint.colour, hint.type);
		header.right.set(shown);
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

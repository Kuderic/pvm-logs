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
	private final Folds folds;
	private final SidePanelParts.Line header;
	private final JComponent body;
	private PanelSnapshot.Text hint = PanelSnapshot.Text.NONE;

	SidePanelSection(String id, String title, PanelSnapshot.Text hint, JComponent body, Folds folds)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.id = id;
		this.folds = folds;
		this.body = body;
		setOpaque(false);
		header = new SidePanelParts.Line(PanelSnapshot.Text.title(title, SidePanelStyle.VALUE), hint);
		header.setBorder(new EmptyBorder(4, 0, 0, 0));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setToolTipText("Click to fold or open");
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}
		});
		add(header);
		add(body);
		body.setVisible(!folds.isFolded(id));
		setHint(hint);
	}

	String id()
	{
		return id;
	}

	boolean isFolded()
	{
		return !body.isVisible();
	}

	void toggle()
	{
		final boolean fold = body.isVisible();
		folds.setFolded(id, fold);
		body.setVisible(!fold);
		setHint(hint);
		revalidate();
		repaint();
	}

	void setHint(PanelSnapshot.Text h)
	{
		hint = h == null ? PanelSnapshot.Text.NONE : h;
		final PanelSnapshot.Text shown = !isFolded() ? hint : hint.isEmpty()
			? PanelSnapshot.Text.small(FOLDED_MARK, SidePanelStyle.SECONDARY)
			: new PanelSnapshot.Text(hint.text + " " + FOLDED_MARK, hint.colour, hint.type);
		header.right.set(shown);
	}
}

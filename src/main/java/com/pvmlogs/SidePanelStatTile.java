package com.pvmlogs;

import java.awt.Color;
import java.awt.Graphics;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * One of the Fight tab's four tiles (spec 3.1): a label, a value and a
 * sub-line on a card, with a 2 px top edge that takes the value's colour when
 * it's coloured.
 */
final class SidePanelStatTile extends JPanel
{
	private final SidePanelText label;
	private final SidePanelText value;
	private final SidePanelText sub;
	/** The sub-line wrapped onto two lines instead of cut: History's "middle half 1:55 to 2:01". */
	private SidePanelParts.Paragraph wrapped;
	private Color edge = SidePanelStyle.TRACK;
	private Color fill = SidePanelStyle.CARD;

	SidePanelStatTile(PanelSnapshot.Tile t)
	{
		super(new SidePanelStack(4));
		setOpaque(false);
		setBorder(new EmptyBorder(5 + SidePanelStyle.TILE_EDGE, 6, 6, 6));
		label = new SidePanelText(t.label, false);
		value = new SidePanelText(t.value, false);
		sub = new SidePanelText(t.sub, false);
		add(label);
		add(value);
		add(sub);
		set(t);
	}

	/** A tile whose sub-line wraps rather than being cut; {@code width} is the tile's inside. */
	static SidePanelStatTile wrapping(PanelSnapshot.Tile t, int width)
	{
		final SidePanelStatTile tile = new SidePanelStatTile(t);
		tile.remove(tile.sub);
		tile.wrapped = new SidePanelParts.Paragraph(t.sub.text, t.sub.colour, SidePanelStyle.Type.SMALL, width);
		tile.add(tile.wrapped);
		return tile;
	}

	void set(PanelSnapshot.Tile t)
	{
		label.set(t.label);
		value.set(t.value);
		sub.set(t.sub);
		sub.setShort(t.subShort);
		if (wrapped != null)
		{
			wrapped.set(t.sub.text, t.sub.colour);
		}
		if (!t.edge.equals(edge))
		{
			edge = t.edge;
			repaint();
		}
	}

	/** The tile's fill: the card's, or the panel's for tiles inside a card (History's boss card). */
	void setFill(Color f)
	{
		fill = f;
		repaint();
	}

	/** The sub-line's text, for tests. */
	SidePanelText subText()
	{
		return sub;
	}

	String valueText()
	{
		return value.getText();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(fill);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setColor(edge);
		g.fillRect(0, 0, getWidth(), SidePanelStyle.TILE_EDGE);
	}
}

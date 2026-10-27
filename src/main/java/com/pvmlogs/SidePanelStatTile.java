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
	private Color edge = SidePanelStyle.TRACK;

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

	void set(PanelSnapshot.Tile t)
	{
		label.set(t.label);
		value.set(t.value);
		sub.set(t.sub);
		if (!t.edge.equals(edge))
		{
			edge = t.edge;
			repaint();
		}
	}

	String valueText()
	{
		return value.getText();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(SidePanelStyle.CARD);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setColor(edge);
		g.fillRect(0, 0, getWidth(), SidePanelStyle.TILE_EDGE);
	}
}

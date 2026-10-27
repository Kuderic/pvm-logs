package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * The panel's one layout: children top to bottom, each its preferred height
 * and the container's full width, a fixed gap between them. Hidden children
 * take no room. Widths come from the container, never the children, so a long
 * text can't widen the panel: it's cut instead.
 */
final class SidePanelStack implements LayoutManager
{
	private final int gap;

	SidePanelStack(int gap)
	{
		this.gap = gap;
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
	}

	@Override
	public Dimension preferredLayoutSize(Container parent)
	{
		final Insets in = parent.getInsets();
		int h = 0;
		int w = 0;
		boolean first = true;
		for (Component c : parent.getComponents())
		{
			if (!c.isVisible())
			{
				continue;
			}
			final Dimension d = c.getPreferredSize();
			h += (first ? 0 : gap) + d.height;
			w = Math.max(w, d.width);
			first = false;
		}
		return new Dimension(Math.min(w, SidePanelStyle.CONTENT_WIDTH) + in.left + in.right, h + in.top + in.bottom);
	}

	@Override
	public Dimension minimumLayoutSize(Container parent)
	{
		final Dimension d = preferredLayoutSize(parent);
		return new Dimension(0, d.height);
	}

	@Override
	public void layoutContainer(Container parent)
	{
		final Insets in = parent.getInsets();
		final int width = Math.max(0, parent.getWidth() - in.left - in.right);
		int y = in.top;
		for (Component c : parent.getComponents())
		{
			if (!c.isVisible())
			{
				continue;
			}
			final int h = c.getPreferredSize().height;
			c.setBounds(in.left, y, width, h);
			y += h + gap;
		}
	}
}

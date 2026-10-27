package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/** The Trip tab until T-034 (SP-2): what's coming, and the New trip button, which the overlay's trip totals use today. */
final class SidePanelTripTab extends JPanel implements Scrollable
{
	SidePanelTripTab(SidePanelFightTab.Actions actions)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		setBackground(SidePanelStyle.PANEL);
		setBorder(new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));
		final JPanel note = SidePanelParts.card(6);
		note.add(new SidePanelText(Text.title("Trip", SidePanelStyle.VALUE), false));
		note.add(new SidePanelParts.Paragraph("Your whole trip's luck, execution and kills come here in the next panel "
			+ "update. The overlay's \"whole trip\" setting shows its totals now.", SidePanelStyle.SECONDARY,
			SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14));
		add(note);
		final JButton newTrip = SidePanelFightTab.button("New trip", SidePanelStyle.VALUE);
		newTrip.setToolTipText("Start the trip totals over. Saved fights are untouched");
		newTrip.addActionListener(e -> actions.newTrip());
		add(newTrip);
		add(new SidePanelText(Text.small("Starts the trip's totals over; saved kills stay.", SidePanelStyle.MUTED), false));
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return orientation == SwingConstants.VERTICAL ? visibleRect.height - 16 : visibleRect.width;
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}
}

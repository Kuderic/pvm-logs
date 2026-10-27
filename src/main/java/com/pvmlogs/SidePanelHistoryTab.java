package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The History tab until T-034 (SP-2) builds the real one: the Tools grid, and
 * today's per-NPC list with its CSV buttons, so nothing the old panel did goes
 * missing in between. Built on {@link #refresh()} only, never per tick, as
 * spec 5.1 step 3 has it. It reads the saved fights, which are copy-on-write,
 * from Swing's thread as the old panel did.
 */
final class SidePanelHistoryTab extends JPanel implements Scrollable
{
	private final PvmPerformancePlugin plugin;
	private final JCheckBox bossesOnly = new JCheckBox("Bosses only");
	private final SidePanelText status = new SidePanelText(Text.small("", SidePanelStyle.SECONDARY), false);
	private final SidePanelFightTab.ToolButtons tools;
	private final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);

	SidePanelHistoryTab(PvmPerformancePlugin plugin, SidePanelFightTab.Actions actions)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.plugin = plugin;
		setBackground(SidePanelStyle.PANEL);
		setBorder(new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));

		final JPanel note = SidePanelParts.card(0);
		note.add(new SidePanelParts.Paragraph("Bosses, your usual times and every saved kill come here in the next panel "
			+ "update. Until then: your fights by NPC, and the tools.", SidePanelStyle.SECONDARY, SidePanelStyle.Type.SMALL,
			SidePanelStyle.CONTENT_WIDTH - 14));
		add(note);

		tools = new SidePanelFightTab.ToolButtons(actions, this);
		final JPanel toolBox = SidePanelParts.stack(4);
		toolBox.add(tools);
		bossesOnly.setOpaque(false);
		bossesOnly.setForeground(SidePanelStyle.VALUE);
		bossesOnly.setFont(SidePanelStyle.Type.SMALL.font());
		bossesOnly.setToolTipText("List and export only NPCs above the boss HP threshold");
		bossesOnly.addActionListener(e -> refresh());
		toolBox.add(bossesOnly);
		toolBox.add(status);
		add(new SidePanelSection("historyTools", "Tools", Text.NONE, toolBox, new SidePanelSection.MemoryFolds()));

		add(new SidePanelSection("historyNpcs", "By NPC", Text.NONE, list, new SidePanelSection.MemoryFolds()));
		refresh();
	}

	boolean bossesOnly()
	{
		return bossesOnly.isSelected();
	}

	void setStatus(String text)
	{
		status.set(Text.small(text == null ? "" : text, SidePanelStyle.SECONDARY));
	}

	/** Reads the saved fights again; Swing's thread. */
	void refresh()
	{
		list.removeAll();
		final List<NpcStats> stats = plugin == null ? java.util.Collections.emptyList()
			: plugin.getNpcStats(bossesOnly.isSelected());
		tools.setHistory(plugin != null && !plugin.getHistory().isEmpty());
		if (stats.isEmpty())
		{
			final JPanel card = SidePanelParts.card(0);
			card.add(new SidePanelParts.Paragraph(SidePanelModel.NO_HISTORY_TEXT, SidePanelStyle.SECONDARY,
				SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14));
			list.add(card);
		}
		for (NpcStats s : stats)
		{
			list.add(row(s));
		}
		revalidate();
		repaint();
	}

	private JPanel row(NpcStats s)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(SidePanelStyle.CARD);
		row.setBorder(new EmptyBorder(SidePanelStyle.CARD_PAD_V, SidePanelStyle.CARD_PAD_H, SidePanelStyle.CARD_PAD_V,
			SidePanelStyle.CARD_PAD_H));
		final JPanel text = SidePanelParts.stack(4);
		text.add(new SidePanelText(Text.body(s.getName(), SidePanelStyle.VALUE), false));
		text.add(new SidePanelText(Text.small(s.getFights() + " fights · " + s.getKills() + " kills · "
			+ LedgerRows.number(s.getTotalDamageDealt()) + " dmg", SidePanelStyle.SECONDARY), false));
		row.add(text, BorderLayout.CENTER);
		final JButton export = SidePanelFightTab.button("CSV", SidePanelStyle.VALUE);
		export.setToolTipText("Export " + s.getName() + " fights to CSV");
		export.setMargin(new Insets(2, 6, 2, 6));
		export.addActionListener(e -> plugin.exportNpc(s.getName()));
		row.add(export, BorderLayout.EAST);
		return row;
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

package com.pvmlogs;

import com.pvmlogs.PanelSnapshot.Text;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.components.IconTextField;

/**
 * The History tab (T-034; spec 3.2, states 10 and 11): search, the boss card
 * with its version picker, usual tiles and sparkline, the kill list, a saved
 * kill's own view, every boss with its adds under it, and the tools. While the
 * search has text, only the matching bosses (T-082); a picked boss can be put
 * away by its row or the card's close control, leaving the list.
 * <p>
 * Built on {@link #refresh()} (after a fight is saved, history loads or is
 * cleared, the tab opens), on typing and on a pick; never per tick (spec 5.1
 * step 3). It reads the saved fights from Swing's thread: they're a
 * copy-on-write list of ended fights, which nothing writes to again.
 */
final class SidePanelHistoryTab extends JPanel implements Scrollable
{
	/** T-046: the tab paints in software and reaches the screen as one image; see {@link SidePanelBuffer}. */
	private final SidePanelBuffer buffer = new SidePanelBuffer();

	/** For tests: the tab's cached image. */
	SidePanelBuffer buffer()
	{
		return buffer;
	}

	/** The strain probe's timers for the buffer (T-046); null in tests. */
	void setPerf(PerfProbe perf)
	{
		buffer.setPerf(perf);
	}

	/** Its children's repaints come through {@link #paint}, so they're buffered too. */
	@Override
	public boolean isPaintingOrigin()
	{
		return isOpaque();
	}

	@Override
	public void paint(java.awt.Graphics g)
	{
		buffer.paint(this, g, super::paint);
	}

	/** Where the tab reads the saved fights and what it needs to show them; the plugin in the client. */
	interface Source
	{
		/** The saved fights, newest first. */
		List<Fight> history();

		SidePanelHistory.Versions versions();

		/** When this RuneLite session started: kills since then aren't given the "later" card. */
		long sessionStart();

		long now();

		ZoneId zone();

		void exportNpc(String name);

		/**
		 * A saved room's timeline view, for its damage rows, chart and strip
		 * (T-036): built where the store lives and handed back on Swing's thread.
		 * Empty when the room's fights have none.
		 */
		default void charts(Encounter room, java.util.function.Consumer<FightTimeline.View> done)
		{
			done.accept(FightTimeline.View.EMPTY);
		}

		/** The damage rows' icons. */
		default SidePanelIcons icons()
		{
			return SidePanelIcons.NONE;
		}
	}

	/** No saved fights: the panel before the plugin is wired, tests, renders. */
	static final Source EMPTY = new Source()
	{
		public List<Fight> history()
		{
			return Collections.emptyList();
		}

		public SidePanelHistory.Versions versions()
		{
			return SidePanelHistory.Versions.NONE;
		}

		public long sessionStart()
		{
			return 0;
		}

		public long now()
		{
			return System.currentTimeMillis();
		}

		public ZoneId zone()
		{
			return ZoneId.systemDefault();
		}

		public void exportNpc(String name)
		{
		}
	};

	private final Source source;
	private final SidePanelFightTab.Actions actions;
	private final SidePanelSection.Folds folds;

	private final IconTextField search = new IconTextField();
	private final JCheckBox bossesOnly = new JCheckBox("Bosses only");
	private final SidePanelText status = new SidePanelText(Text.small("", SidePanelStyle.SECONDARY), false);
	private final JPanel searchBox = SidePanelParts.stack(4);
	private final JPanel content = SidePanelParts.stack(SidePanelStyle.BLOCK_GAP);
	private SidePanelFightTab.ToolButtons tools;

	// What the player picked; kept across refreshes. Rooms are rebuilt each time, so a kill is known by its first fight.
	private String boss;
	/**
	 * T-082: the player put the picked boss away (its row clicked again, or the card's close control), so none is
	 * picked until they pick one. Kept across refreshes (a fight saved, the tab reopened) while the panel lives; a new
	 * RuneLite session opens on the default boss again.
	 */
	private boolean putAway;
	private LedgerHistory.Like version;
	private int shownKills = SidePanelHistory.FIRST_KILLS;
	private Fight selectedKill;
	private Fight openKill;
	/** The open kill's timeline view once the client thread has sent it (T-036), and whose it is. */
	private FightTimeline.View openView;
	/** T-099: the open kill's boss parts' and adds' timelines, for the Damage card's switch; null for none. */
	private FightTimeline.View[] openPartViews;
	private Fight openViewFor;

	private int builds;

	SidePanelHistoryTab(Source source, SidePanelFightTab.Actions actions, SidePanelSection.Folds folds)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.source = source == null ? EMPTY : source;
		this.actions = actions;
		this.folds = folds;
		setBackground(SidePanelStyle.PANEL);
		setBorder(new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(SidePanelStyle.CONTENT_WIDTH, 30));
		search.setBackground(SidePanelStyle.CARD);
		search.setHoverBackgroundColor(SidePanelStyle.SELECTED);
		search.setToolTipText("Search bosses. Escape clears it");
		// T-082: Escape clears the search, and so brings back what it hid.
		search.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyPressed(java.awt.event.KeyEvent e)
			{
				if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE && !search.getText().isEmpty())
				{
					search.setText("");
				}
			}
		});
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				refresh();
			}

			public void removeUpdate(DocumentEvent e)
			{
				refresh();
			}

			public void changedUpdate(DocumentEvent e)
			{
				refresh();
			}
		});
		searchBox.add(new SidePanelText(Text.small("Search bosses", SidePanelStyle.SECONDARY), false));
		searchBox.add(search);

		bossesOnly.setOpaque(false);
		bossesOnly.setForeground(SidePanelStyle.SECONDARY);
		bossesOnly.setFont(SidePanelStyle.Type.SMALL.font());
		bossesOnly.setToolTipText("List and export only bosses (the hiscores' list)");
		bossesOnly.addActionListener(e -> refresh());

		add(searchBox);
		add(content);
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

	/** How many times the tab was built: on refreshes and picks only. */
	int builds()
	{
		return builds;
	}

	/**
	 * The text settings changed (T-053): the parts that outlive a refresh take the new fonts, the cached image goes, and
	 * the list is built again once.
	 */
	void restyle()
	{
		buffer.drop();
		bossesOnly.setFont(SidePanelStyle.Type.SMALL.font());
		status.restyle();
		SidePanelParts.restyle(searchBox);
		refresh();
	}

	/** Reads the saved fights again and shows them; Swing's thread. */
	void refresh()
	{
		builds++;
		content.removeAll();
		final List<Fight> history = new ArrayList<>(source.history());
		if (openKill != null)
		{
			final Encounter room = find(history, openKill);
			if (room != null)
			{
				searchBox.setVisible(false);
				savedKill(history, room);
				revalidate();
				repaint();
				return;
			}
			openKill = null;
		}
		searchBox.setVisible(true);
		final boolean searching = searching();
		final List<SidePanelHistory.BossEntry> all = SidePanelHistory.bosses(history, false, "");
		if (all.isEmpty())
		{
			final JPanel card = SidePanelParts.card(0);
			card.add(new SidePanelParts.Paragraph(SidePanelModel.NO_HISTORY_TEXT, SidePanelStyle.SECONDARY,
				SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14));
			content.add(card);
		}
		else if (searching)
		{
			// T-082: searching shows only what matches, and changes no pick: clearing it brings the card back.
			allBosses(history);
		}
		else
		{
			if (boss != null && all.stream().noneMatch(b -> b.name.equals(boss)))
			{
				boss = null;
			}
			if (boss == null && !putAway)
			{
				boss = defaultBoss(history, all);
				version = null;
			}
			if (boss != null)
			{
				bossCard(history);
				kills(history);
			}
			allBosses(history);
		}
		if (!searching)
		{
			toolsSection(!history.isEmpty());
		}
		revalidate();
		repaint();
	}

	/** The boss of the last kill, else the most recently fought (spec 3.2). */
	private static String defaultBoss(List<Fight> history, List<SidePanelHistory.BossEntry> all)
	{
		for (Encounter room : SidePanelHistory.allRooms(history))
		{
			if (SidePanelHistory.fought(room) && SidePanelHistory.isKill(room))
			{
				return room.getName();
			}
		}
		return all.get(0).name;
	}

	// ---- the boss card ----

	private void bossCard(List<Fight> history)
	{
		final SidePanelHistory.Card card = SidePanelHistory.card(history, boss, version, source.versions());
		if (card == null)
		{
			return;
		}
		final JPanel box = SidePanelParts.card(6);
		// T-095: a kind fought in runs, "5 runs · 312 kills".
		final String count = card.kills + (card.kills == 1 ? " kill" : " kills");
		final SidePanelParts.Line line = new SidePanelParts.Line(Text.title(card.name, SidePanelStyle.VALUE),
			Text.small(card.runs > 0 ? card.runs + (card.runs == 1 ? " run · " : " runs · ") + count : count,
				SidePanelStyle.SECONDARY));
		// T-082: a close control after the kill count puts the boss away.
		final JPanel title = new JPanel(new BorderLayout(6, 0));
		title.setOpaque(false);
		title.add(line, BorderLayout.CENTER);
		title.add(new Close("Put " + card.name + " away and show all bosses", this::putAway), BorderLayout.EAST);
		box.add(card.icon == SidePanelBossIcons.NONE ? title : withIcon(card.icon, title));
		if (!card.versions.isEmpty())
		{
			final JComboBox<SidePanelHistory.Version> picker = new JComboBox<>(
				card.versions.toArray(new SidePanelHistory.Version[0]));
			picker.setSelectedIndex(card.selected);
			picker.setFont(SidePanelStyle.Type.SMALL.font());
			picker.setToolTipText("Which version of the boss, solo or in a group: your usual is taken over kills like it");
			picker.setPreferredSize(new Dimension(SidePanelStyle.CONTENT_WIDTH, 24));
			picker.addActionListener(e ->
			{
				final SidePanelHistory.Version v = (SidePanelHistory.Version) picker.getSelectedItem();
				if (v != null && !SidePanelHistory.same(v.like, version))
				{
					version = v.like;
					SwingUtilities.invokeLater(this::refresh);
				}
			});
			box.add(picker);
		}
		final JPanel grid = new JPanel(new GridLayout(2, 2, SidePanelStyle.TILE_GAP, SidePanelStyle.TILE_GAP));
		grid.setOpaque(false);
		for (PanelSnapshot.Tile t : card.tiles)
		{
			// Tiles in a card take the panel's fill, so they read as tiles (canvas).
			// Two to a row in the card's inside, less each tile's own 6 px a side.
			final SidePanelStatTile tile = SidePanelStatTile.wrapping(t,
				(SidePanelStyle.CONTENT_WIDTH - 2 * SidePanelStyle.CARD_PAD_H - SidePanelStyle.TILE_GAP) / 2 - 12);
			tile.setFill(SidePanelStyle.PANEL);
			grid.add(tile);
		}
		box.add(grid);
		if (card.spark != null)
		{
			box.add(new SidePanelSparkline(card.spark));
			final JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			legend.setOpaque(false);
			legend.add(swatch(SidePanelSparkline.CLOCK, "kill time"));
			legend.add(swatch(SidePanelHistory.LUCK_REMOVED, "luck removed"));
			box.add(legend);
			// On its own line: a FlowLayout wraps it out of the card's height.
			box.add(new SidePanelText(Text.small("last " + card.spark.clock.length + " like kills · up = slower",
				SidePanelStyle.MUTED), false));
		}
		content.add(box);
	}

	/** A legend entry: an 8 px square and its word. */
	private static JComponent swatch(Color colour, String text)
	{
		final JPanel p = new JPanel(new BorderLayout(3, 0));
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(0, 0, 0, 9));
		p.add(new JComponent()
		{
			@Override
			public Dimension getPreferredSize()
			{
				return new Dimension(8, SidePanelStyle.Type.SMALL.lineHeight());
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(colour);
				g.fillRect(0, 2, 8, 8);
			}
		}, BorderLayout.WEST);
		p.add(new SidePanelText(Text.small(text, SidePanelStyle.SECONDARY), false), BorderLayout.CENTER);
		return p;
	}

	// ---- kills ----

	private void kills(List<Fight> history)
	{
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(history, boss, source.now(), source.zone());
		if (rows.isEmpty())
		{
			return;
		}
		final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);
		final int shown = Math.min(rows.size(), shownKills);
		for (int i = 0; i < shown; i++)
		{
			final SidePanelHistory.KillRow row = rows.get(i);
			final Fight key = row.room.getFights().get(0);
			final SidePanelKillRow view = SidePanelKillRow.history(row, () -> open(key));
			view.setSelected(key == selectedKill);
			view.setToolTipText(row.kills >= 0 ? "Open this run" : "Open this kill");
			list.add(view);
		}
		final String more = SidePanelHistory.showMore(rows.size(), shown);
		if (more != null)
		{
			final JButton button = SidePanelFightTab.button(more, SidePanelStyle.VALUE);
			button.addActionListener(e ->
			{
				shownKills += SidePanelHistory.MORE_KILLS;
				refresh();
			});
			list.add(button);
		}
		// T-095: a kind fought in runs lists its runs: their time and kills/hr, no luck or execution.
		final boolean runs = rows.stream().anyMatch(r -> r.kills >= 0);
		content.add(new SidePanelSection("historyKills", runs ? "Runs" : "Kills",
			Text.small(runs ? "time · kills/hr" : "time · luck · execution", SidePanelStyle.SECONDARY), list, folds));
	}

	/** Opens a kill's own view (state 11). */
	void open(Fight firstFight)
	{
		openKill = firstFight;
		selectedKill = firstFight;
		refresh();
	}

	/** Back to the boss's list from a kill's view. */
	void back()
	{
		openKill = null;
		refresh();
	}

	boolean isShowingKill()
	{
		return openKill != null;
	}

	private static Encounter find(List<Fight> history, Fight first)
	{
		for (Encounter room : LedgerHistory.rooms(history, first.encounterName()))
		{
			if (!room.getFights().isEmpty() && room.getFights().get(0) == first)
			{
				return room;
			}
		}
		return null;
	}

	private void savedKill(List<Fight> history, Encounter room)
	{
		final SidePanelText backLink = new SidePanelText(Text.small("Back to " + room.getName(), SidePanelStyle.ACCENT),
			false);
		final JPanel back = new JPanel(new BorderLayout());
		back.setOpaque(false);
		back.setBorder(new EmptyBorder(2, 0, 2, 0));
		back.add(backLink, BorderLayout.CENTER);
		back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		back.setToolTipText("Back to " + room.getName() + "'s kills");
		back.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				back();
			}
		});
		content.add(back);
		final SidePanelFightTab view = new SidePanelFightTab(actions, folds, true, source.icons());
		final Fight first = room.getFights().get(0);
		final boolean known = openViewFor == first;
		view.apply(SidePanelModel.fight(SidePanelHistory.savedKill(history, room, source.sessionStart(), source.now(),
			source.zone(), source.versions(), known ? openView : null, known ? openPartViews : null)));
		content.add(view);
		if (!known && SidePanelHistory.fromThisSession(room, source.sessionStart()))
		{
			// This session's kill: ask for its charts, and show them when they come. Last, so a source that answers at
			// once (tests) rebuilds the view rather than having it added twice. T-099: with its boss parts' and adds'
			// too, for the Damage card's switch, each asked of the same source as one room.
			final Encounter[] parts = SidePanelModel.parts(room);
			source.charts(room, v ->
			{
				if (parts == null)
				{
					shown(first, v, null);
					return;
				}
				source.charts(parts[0], boss -> source.charts(parts[1], adds ->
					shown(first, v, new FightTimeline.View[]{boss, adds})));
			});
		}
	}

	/** A kill's charts have come: kept for it, and shown if it's still open. */
	private void shown(Fight first, FightTimeline.View v, FightTimeline.View[] parts)
	{
		openView = v;
		openPartViews = parts;
		openViewFor = first;
		if (openKill == first)
		{
			refresh();
		}
	}

	// ---- all bosses ----

	private void allBosses(List<Fight> history)
	{
		final List<SidePanelHistory.BossEntry> entries = SidePanelHistory.bosses(history, bossesOnly.isSelected(),
			search.getText());
		final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);
		if (entries.isEmpty())
		{
			final JPanel card = SidePanelParts.card(0);
			card.add(new SidePanelText(Text.small("No boss matches that name.", SidePanelStyle.SECONDARY), false));
			list.add(card);
		}
		for (SidePanelHistory.BossEntry b : entries)
		{
			list.add(bossRow(b));
		}
		list.add(bossesOnly);
		content.add(new SidePanelSection("historyBosses", "All bosses",
			Text.small("kills · average", SidePanelStyle.SECONDARY), list, folds));
	}

	/** A line with the boss's icon before it (T-054). */
	private static JPanel withIcon(int sprite, JComponent line)
	{
		final JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setOpaque(false);
		p.add(new SidePanelBossIcons.View(sprite, SidePanelBossIcons.ROW_BOX), BorderLayout.WEST);
		p.add(line, BorderLayout.CENTER);
		return p;
	}

	private JPanel bossRow(SidePanelHistory.BossEntry b)
	{
		final boolean picked = b.name.equals(boss);
		final JPanel row = new JPanel(new BorderLayout(6, 0))
		{
			private boolean hover;

			{
				addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseEntered(MouseEvent e)
					{
						hover = true;
						repaint();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						hover = false;
						repaint();
					}
				});
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(picked || hover ? SidePanelStyle.SELECTED : SidePanelStyle.CARD);
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(SidePanelStyle.CARD_PAD_V, SidePanelStyle.CARD_PAD_H, SidePanelStyle.CARD_PAD_V,
			SidePanelStyle.CARD_PAD_H));
		// The boss's icon (T-054) in a box kept for every row, so the names line up; empty for one without.
		row.add(new SidePanelBossIcons.View(b.icon, SidePanelBossIcons.ROW_BOX), BorderLayout.WEST);
		final JPanel text = new JPanel(new SidePanelStack(3));
		text.setOpaque(false);
		text.add(new SidePanelParts.Line(Text.body(b.name, SidePanelStyle.VALUE),
			Text.small(b.right(), SidePanelStyle.BODY)));
		final String sub = b.sub();
		if (!sub.isEmpty())
		{
			// Secondary rather than Muted on the picked row (spec section 2: Muted is too faint on #3C3C3C).
			text.add(new SidePanelText(Text.small(sub, picked ? SidePanelStyle.SECONDARY : SidePanelStyle.MUTED), false));
		}
		row.add(text, BorderLayout.CENTER);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText((picked && !searching() ? "Put " + b.name + " away" : "Show " + b.name)
			+ ". Right-click to export its fights to CSV");
		final JPopupMenu menu = new JPopupMenu();
		final JMenuItem export = new JMenuItem("Export " + b.name + " to CSV");
		export.addActionListener(e -> source.exportNpc(b.name));
		menu.add(export);
		row.setComponentPopupMenu(menu);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				if (searching())
				{
					// T-082: picking a match picks it and clears the search; clearing it refreshes.
					select(b.name);
					search.setText("");
				}
				else if (b.name.equals(boss))
				{
					// T-082: the picked boss's row again puts it away.
					putAway();
				}
				else
				{
					select(b.name);
					refresh();
				}
			}
		});
		return row;
	}

	// ---- tools ----

	private void toolsSection(boolean anyHistory)
	{
		final JPanel box = SidePanelParts.stack(4);
		tools = new SidePanelFightTab.ToolButtons(actions, this);
		tools.setHistory(anyHistory);
		box.add(tools);
		box.add(status);
		content.add(new SidePanelSection("historyTools", "Tools", Text.NONE, box, folds));
	}

	SidePanelFightTab.ToolButtons tools()
	{
		return tools;
	}

	String boss()
	{
		return boss;
	}

	void pick(String name)
	{
		select(name);
		refresh();
	}

	/** T-082: picks a boss, which ends a put-away. */
	private void select(String name)
	{
		boss = name;
		putAway = false;
		version = null;
		shownKills = SidePanelHistory.FIRST_KILLS;
	}

	/** T-082: no boss picked, until the player picks one: the list and the tools. */
	void putAway()
	{
		boss = null;
		putAway = true;
		version = null;
		shownKills = SidePanelHistory.FIRST_KILLS;
		refresh();
	}

	/** T-082: whether the search has text, and so the tab shows only the matching bosses. */
	boolean searching()
	{
		return !search.getText().trim().isEmpty();
	}

	/**
	 * T-082: the boss card's close control, a small X: Secondary, Value on a Selected square when hovered, as the
	 * panel's rows hover. Painted, so it doesn't hang on a glyph the RuneScape fonts may lack.
	 */
	static final class Close extends JComponent
	{
		private boolean hover;

		Close(String tooltip, Runnable onClick)
		{
			setOpaque(false);
			setToolTipText(tooltip);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}

				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						onClick.run();
					}
				}
			});
		}

		boolean hovered()
		{
			return hover;
		}

		@Override
		public Dimension getPreferredSize()
		{
			final int side = Math.max(14, SidePanelStyle.Type.SMALL.lineHeight() + 2);
			return new Dimension(side, side);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final int side = Math.min(getWidth(), getHeight());
			final int x0 = (getWidth() - side) / 2;
			final int y0 = (getHeight() - side) / 2;
			if (hover)
			{
				g.setColor(SidePanelStyle.SELECTED);
				g.fillRect(x0, y0, side, side);
			}
			final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setStroke(new java.awt.BasicStroke(1.5f));
			g2.setColor(hover ? SidePanelStyle.VALUE : SidePanelStyle.SECONDARY);
			final int inset = Math.max(3, side / 4);
			g2.drawLine(x0 + inset, y0 + inset, x0 + side - 1 - inset, y0 + side - 1 - inset);
			g2.drawLine(x0 + side - 1 - inset, y0 + inset, x0 + inset, y0 + side - 1 - inset);
			g2.dispose();
		}
	}

	IconTextField search()
	{
		return search;
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

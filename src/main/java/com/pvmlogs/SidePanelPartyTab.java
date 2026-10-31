package com.pvmlogs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * T-125, T-130: the Party tab, one page (Eric, 2026-10-31 10:38: "it should show those fight panel tabs under damage
 * over time graph, so the user doesnt have to click back and forth"). Top to bottom: the room/raid switch, the meter,
 * the party's Damage over time, then a heading naming whose panels follow and that member's Fight panels, by the Fight
 * tab's own component (T-128), you by default. Clicking a member's row selects them; clicking it again, or your own,
 * goes back to you.
 *
 * <p>The top (switch, meter, chart) is built only when its structure changes; the member's part only when the selection
 * or its own structure does, never the meter's; otherwise the meter and the chart repaint and texts are set in place.
 */
final class SidePanelPartyTab extends JPanel
{
	/** T-130: the heading's hint. */
	static final String SWITCH_HINT = "click a member above to switch";
	static final String FIGHT_PANELS = " · Fight panels";

	/**
	 * T-130 review: the tab paints in software and reaches the screen as one image, as the other tabs have since T-046
	 * (see {@link SidePanelBuffer}). Without it every repaint went straight to the screen: the 10:35 log's ~29 ms of
	 * "panel repaint after apply" on this tab against the Fight tab's ~6 ms, with "panel software render" none.
	 */
	private final SidePanelBuffer buffer = new SidePanelBuffer();

	/** For tests: the tab's cached image. */
	SidePanelBuffer buffer()
	{
		return buffer;
	}

	/** The strain probe's timers for the buffer; null in tests. */
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
	public void paint(Graphics g)
	{
		buffer.paint(this, g, super::paint);
	}

	private final SidePanelFightTab.Actions actions;
	private final SidePanelSection.Folds folds;
	/** T-128: the item and spell icons for the Fight panels. */
	private final SidePanelIcons icons;
	/** T-130: told which member is selected, so the panel builds their Fight panels and only theirs. */
	private final java.util.function.LongConsumer onSelected;

	private PanelSnapshot.PartyTab shown;
	private String builtFor;
	private String memberBuiltFor;
	/** The member whose Fight panels show: you by default. Swing's thread. */
	private long selected = SidePanelParty.YOU_ID;
	/** The party the selection was made in: a new party puts it back on you. */
	private long partyKey;
	private SidePanelParty.Metric metric = SidePanelParty.Metric.DMG;
	private int rebuilds;
	private int memberRebuilds;

	static final long NONE = Long.MIN_VALUE;

	// The current components, for updates in place.
	private SidePanelParts.Line header;
	private SidePanelPartyMeter meter;
	private SidePanelPartyChart chart;
	private MetricSwitch words;
	private SidePanelFightTab.Segment roomSegment;
	private SidePanelFightTab.Segment raidSegment;
	/** T-130: the selected member's part: the heading and their panels. */
	private JPanel memberArea;
	private Picture headingPicture;
	private SidePanelParts.Line heading;
	private SidePanelParts.Paragraph headingSub;
	private SidePanelFightTab memberFight;
	/** The snapshot {@link #memberFight} last had, so the same one (a member's room, built once) isn't applied again. */
	private PanelSnapshot.FightTab memberApplied;
	/**
	 * T-130 review: your own Fight panels, kept for the tab's life. Every snapshot that carries them is applied here,
	 * shown or not, so their chart can be sent incrementally (as the Fight tab's is), and a click back to you shows the
	 * last ones at once while the panel builds new ones.
	 */
	private SidePanelFightTab yourPanels;
	private PanelSnapshot.FightTab yourLast;
	private Attacks attacks;
	private SidePanelParts.RowsCard summary;

	SidePanelPartyTab(SidePanelFightTab.Actions actions, SidePanelSection.Folds folds)
	{
		this(actions, folds, SidePanelIcons.NONE, id ->
		{
		});
	}

	SidePanelPartyTab(SidePanelFightTab.Actions actions, SidePanelSection.Folds folds, SidePanelIcons icons,
		java.util.function.LongConsumer onSelected)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.actions = actions;
		this.folds = folds;
		this.icons = icons == null ? SidePanelIcons.NONE : icons;
		this.onSelected = onSelected;
		setBackground(SidePanelStyle.PANEL);
		setBorder(new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));
	}

	/** Shows the snapshot; null keeps what's shown (another tab's snapshot). */
	void apply(PanelSnapshot.PartyTab t)
	{
		if (t == null)
		{
			return;
		}
		shown = t;
		final PanelSnapshot.PartyRow you = t.row(SidePanelParty.YOU_ID);
		if (you != null && you.fight != null && you.fight != yourLast)
		{
			if (yourPanels == null)
			{
				yourPanels = new SidePanelFightTab(actions, folds, true, icons);
			}
			yourLast = you.fight;
			yourPanels.apply(yourLast);
		}
		// A new party, or the selected member gone from the board: back to you.
		if (t.partyKey != partyKey)
		{
			partyKey = t.partyKey;
			setSelected(SidePanelParty.YOU_ID);
		}
		if (selected != SidePanelParty.YOU_ID && t.row(selected) == null)
		{
			setSelected(SidePanelParty.YOU_ID);
		}
		if (!t.after)
		{
			metric = SidePanelParty.Metric.DMG;
		}
		final String structure = t.structure();
		if (!structure.equals(builtFor))
		{
			build(t);
			builtFor = structure;
		}
		else
		{
			update(t);
		}
		// T-133: one paint pass for the whole update, as the Fight and Trip tabs have had since T-046. Swing paints each
		// dirty component with no dirty ancestor in a pass of its own, and each went through this tab's buffer and its own
		// copy to the window: the meter, the chart, the header's texts and your Fight panels, about 6 an update, each
		// costing ~6 ms on Eric's PC outside our timers (the 11:58 log's ~42 ms "repaint after apply"). With the tab dirty,
		// every child's repaint joins its pass.
		repaint();
	}

	/** Built again in the panel's new text settings (T-053). */
	void restyle()
	{
		buffer.drop();
		builtFor = null;
		memberBuiltFor = null;
		if (yourPanels != null)
		{
			yourPanels.restyle(yourLast);
		}
		if (shown != null)
		{
			apply(shown);
		}
	}

	private void setSelected(long id)
	{
		if (selected != id)
		{
			selected = id;
			onSelected.accept(id);
		}
	}

	/**
	 * T-130: a click on a member's row. It selects them; on the selected row again, or on your own, back to you. Only
	 * the member's part is built again; the meter and the chart repaint.
	 */
	void select(long id)
	{
		final PanelSnapshot.PartyRow r = shown == null ? null : shown.row(id);
		if (r == null)
		{
			return;
		}
		final long to = r.you || id == selected ? SidePanelParty.YOU_ID : id;
		if (to == selected)
		{
			return;
		}
		setSelected(to);
		if (meter != null)
		{
			meter.setSelected(selected);
		}
		if (chart != null)
		{
			chart.set(shown, focus());
		}
		if (memberArea != null)
		{
			member(shown, true);
		}
	}

	/** The member whose panels show. */
	long selected()
	{
		return selected;
	}

	/** The chart's bright line: a member selected, with the others faded; none for you, as the party is. */
	private long focus()
	{
		return selected == SidePanelParty.YOU_ID ? NONE : selected;
	}

	void pick(SidePanelParty.Metric m)
	{
		metric = m;
		if (meter != null)
		{
			meter.setMetric(m);
		}
		if (words != null)
		{
			words.set(m);
		}
	}

	SidePanelParty.Metric metric()
	{
		return metric;
	}

	/** For tests (T-046): how many times the whole tab, and the member's part alone, were built. */
	int rebuilds()
	{
		return rebuilds;
	}

	int memberRebuilds()
	{
		return memberRebuilds;
	}

	SidePanelPartyMeter meter()
	{
		return meter;
	}

	SidePanelPartyChart chart()
	{
		return chart;
	}

	/**
	 * The Fight panels to show for this row: yours are the kept ones (the last built, while the panel builds for someone
	 * else); a member's are what their row carries. Null for the compact breakdown.
	 */
	private PanelSnapshot.FightTab panelsOf(PanelSnapshot.PartyRow r)
	{
		return r.you ? yourLast : r.fight;
	}

	/** T-128, T-130: the selected member's Fight panels; null while they show the compact breakdown, or none. */
	SidePanelFightTab memberFight()
	{
		return memberFight;
	}

	/** T-130: the heading naming whose panels follow. */
	SidePanelParts.Line heading()
	{
		return heading;
	}

	private void build(PanelSnapshot.PartyTab t)
	{
		rebuilds++;
		removeAll();
		header = null;
		meter = null;
		chart = null;
		words = null;
		roomSegment = null;
		raidSegment = null;
		memberArea = null;
		memberBuiltFor = null;
		clearMember();
		if (!t.sharing)
		{
			final JPanel card = SidePanelParts.card(0);
			card.add(new SidePanelParts.Paragraph(t.empty, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
				SidePanelStyle.CONTENT_WIDTH - 14));
			add(new SidePanelSection("party", "Party", PanelSnapshot.Text.NONE, card, folds));
			revalidate();
			repaint();
			return;
		}
		if (t.wholeRaid != null)
		{
			raidSwitch(t.wholeRaid);
		}
		meterSection(t);
		chart = new SidePanelPartyChart();
		chart.set(t, focus());
		final JPanel card = SidePanelParts.card(4);
		card.add(chart);
		// No hint beside the title: in Larger and Smooth text the title takes the width.
		add(new SidePanelSection("partyChart", "Damage over time", PanelSnapshot.Text.NONE, card, folds));
		memberArea = new JPanel(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		memberArea.setOpaque(false);
		add(memberArea);
		member(t, true);
		revalidate();
		repaint();
	}

	private void clearMember()
	{
		headingPicture = null;
		heading = null;
		headingSub = null;
		memberFight = null;
		memberApplied = null;
		attacks = null;
		summary = null;
	}

	private void raidSwitch(boolean whole)
	{
		final JPanel row = new JPanel(new GridLayout(1, 2, 0, 0));
		row.setOpaque(false);
		roomSegment = new SidePanelFightTab.Segment("This room", () -> actions.wholeRaid(false));
		raidSegment = new SidePanelFightTab.Segment("Whole raid", () -> actions.wholeRaid(true));
		roomSegment.select(!whole);
		raidSegment.select(whole);
		row.add(roomSegment);
		row.add(raidSegment);
		add(row);
	}

	private void meterSection(PanelSnapshot.PartyTab t)
	{
		final JPanel card = SidePanelParts.card(5);
		header = new SidePanelParts.Line(PanelSnapshot.Text.small(t.where, SidePanelStyle.SECONDARY),
			PanelSnapshot.Text.small(headerRight(t), SidePanelStyle.SECONDARY));
		// The room's or raid's name stays whole: the party's DPS drops its word, then goes, then the clock, all of it in
		// the tooltip.
		header.setRightShort(headerShort(t));
		header.setTip(t.where + " · " + headerRight(t));
		card.add(header);
		meter = new SidePanelPartyMeter(this::select);
		meter.setMetric(metric);
		meter.set(t.rows);
		meter.setSelected(selected);
		card.add(meter);
		if (t.othersNote != null)
		{
			card.add(new SidePanelParts.Paragraph(t.othersNote, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
				SidePanelStyle.CONTENT_WIDTH - 14));
		}
		final SidePanelSection section = new SidePanelSection("party", "Party", PanelSnapshot.Text.NONE, card, folds);
		if (t.after)
		{
			words = new MetricSwitch(metric, this::pick);
			section.setHintComponent(words);
		}
		add(section);
	}

	/** The selected member's row; you when they're gone. */
	private PanelSnapshot.PartyRow selectedRow(PanelSnapshot.PartyTab t)
	{
		final PanelSnapshot.PartyRow r = t.row(selected);
		return r != null ? r : t.row(SidePanelParty.YOU_ID);
	}

	private String memberStructure(PanelSnapshot.PartyTab t)
	{
		final PanelSnapshot.PartyRow r = selectedRow(t);
		if (r == null)
		{
			return "none";
		}
		if (panelsOf(r) != null)
		{
			// Their Fight panels rebuild themselves as their own structure changes; here only live or after.
			return r.id + (!r.you && r.fightLive ? "|live" : "|fight");
		}
		return r.id + "|b" + r.breakdown.attacks.size() + "." + r.breakdown.summary.size();
	}

	/** T-130: the member's part, built again when its structure differs from what's built (or {@code force}). */
	private void member(PanelSnapshot.PartyTab t, boolean force)
	{
		final String structure = memberStructure(t);
		if (!force && structure.equals(memberBuiltFor))
		{
			updateMember(t);
			return;
		}
		memberRebuilds++;
		memberBuiltFor = structure;
		memberArea.removeAll();
		clearMember();
		final PanelSnapshot.PartyRow r = selectedRow(t);
		if (r != null)
		{
			memberArea.add(headingCard(r, t));
			if (r.you && yourLast != null)
			{
				memberFight = yourPanels;
				memberArea.add(memberFight);
			}
			else if (r.fight != null)
			{
				memberFight = new SidePanelFightTab(actions, folds, true, icons);
				memberApplied = r.fight;
				memberFight.apply(r.fight);
				memberArea.add(memberFight);
			}
			else
			{
				// T-125's compact breakdown, where their room's message isn't in yet (or from an older sender).
				final JPanel card = SidePanelParts.card(6);
				attacks = new Attacks();
				attacks.set(r.breakdown.attacks, r.colour);
				card.add(attacks);
				summary = new SidePanelParts.RowsCard(r.breakdown.summary, 3);
				summary.setOpaque(false);
				summary.setBorder(new EmptyBorder(0, 0, 0, 0));
				card.add(summary);
				memberArea.add(card);
			}
		}
		memberArea.revalidate();
		memberArea.repaint();
		revalidate();
	}

	/**
	 * T-130: whose panels follow: their picture or colour, {@code Brindle · Fight panels}, their place on the right,
	 * and the hint under it; live, a line on what comes after their room.
	 */
	private JPanel headingCard(PanelSnapshot.PartyRow r, PanelSnapshot.PartyTab t)
	{
		final JPanel card = SidePanelParts.card(3);
		final JPanel line = new JPanel(new BorderLayout(5, 0));
		line.setOpaque(false);
		headingPicture = new Picture(r.avatar, r.colour, 12);
		line.add(headingPicture, BorderLayout.WEST);
		heading = new SidePanelParts.Line(PanelSnapshot.Text.title(headingTitle(r), SidePanelStyle.VALUE),
			PanelSnapshot.Text.NONE);
		line.add(heading, BorderLayout.CENTER);
		card.add(line);
		// Their place and the hint on a line of their own, so the name is never cut for them.
		headingSub = new SidePanelParts.Paragraph(headingSub(r, t), SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
			SidePanelStyle.CONTENT_WIDTH - 14);
		card.add(headingSub);
		if (!r.you && r.fight != null && r.fightLive)
		{
			card.add(new SidePanelParts.Paragraph(SidePanelMemberFight.LATER_LIVE, SidePanelStyle.MUTED,
				SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14));
		}
		return card;
	}

	/** {@code 3rd of 5 · click a member above to switch}; the place alone for a party of one sharer. */
	static String headingSub(PanelSnapshot.PartyRow r, PanelSnapshot.PartyTab t)
	{
		final String place = place(r);
		return members(t) > 1 ? place + " · " + SWITCH_HINT : place;
	}

	static String headingTitle(PanelSnapshot.PartyRow r)
	{
		return r.name + FIGHT_PANELS;
	}

	/** {@code 3rd of 5}: the first of the breakdown's place. */
	static String place(PanelSnapshot.PartyRow r)
	{
		final String p = r.breakdown == null ? "" : r.breakdown.place;
		final int dot = p.indexOf(" · ");
		return dot < 0 ? p : p.substring(0, dot);
	}

	private static int members(PanelSnapshot.PartyTab t)
	{
		int n = 0;
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			n += r.others ? 0 : 1;
		}
		return n;
	}

	private void updateMember(PanelSnapshot.PartyTab t)
	{
		final PanelSnapshot.PartyRow r = selectedRow(t);
		if (r == null || heading == null)
		{
			return;
		}
		headingPicture.set(r.avatar, r.colour);
		heading.set(PanelSnapshot.Text.title(headingTitle(r), SidePanelStyle.VALUE), null, PanelSnapshot.Text.NONE);
		headingSub.set(headingSub(r, t), SidePanelStyle.MUTED);
		if (memberFight != null && memberFight != yourPanels && r.fight != null && r.fight != memberApplied)
		{
			memberApplied = r.fight;
			memberFight.apply(r.fight);
		}
		else if (attacks != null)
		{
			attacks.set(r.breakdown.attacks, r.colour);
			summary.set(r.breakdown.summary);
		}
	}

	private void update(PanelSnapshot.PartyTab t)
	{
		if (!t.sharing)
		{
			return;
		}
		if (roomSegment != null)
		{
			roomSegment.select(!t.wholeRaid);
			raidSegment.select(t.wholeRaid);
		}
		if (header != null)
		{
			header.set(PanelSnapshot.Text.small(t.where, SidePanelStyle.SECONDARY), null,
				PanelSnapshot.Text.small(headerRight(t), SidePanelStyle.SECONDARY));
			header.setRightShort(headerShort(t));
			header.setTip(t.where + " · " + headerRight(t));
		}
		if (meter != null)
		{
			meter.set(t.rows);
			meter.setSelected(selected);
		}
		if (chart != null)
		{
			chart.set(t, focus());
		}
		if (memberArea != null)
		{
			member(t, false);
		}
	}

	/** The header's right: the clock and the party's DPS, {@code 2:09 · party 79.1 dps}. */
	static String headerRight(PanelSnapshot.PartyTab t)
	{
		return t.clock + " · " + t.partyDps;
	}

	/** Its shorter forms, where the room's name needs the line: {@code 2:09 · 79.1 dps}, {@code 2:09}, then nothing. */
	static List<String> headerShort(PanelSnapshot.PartyTab t)
	{
		return java.util.Arrays.asList(t.clock + " · " + shortDps(t.partyDps), t.clock, "");
	}

	/** {@code party 66.1 dps} without its first word, where the room's name would be cut. */
	static String shortDps(String partyDps)
	{
		return partyDps.startsWith("party ") ? partyDps.substring("party ".length()) : partyDps;
	}

	/** The meter's {@code dmg · luck · exec}, as the Damage card's switch: the picked word bright, a click picks. */
	static final class MetricSwitch extends JPanel
	{
		private final List<SidePanelText> words = new ArrayList<>();
		private SidePanelParty.Metric picked;

		MetricSwitch(SidePanelParty.Metric picked, java.util.function.Consumer<SidePanelParty.Metric> onPick)
		{
			setOpaque(false);
			setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS));
			// Clicks between the words are the switch's, not the header's fold.
			addMouseListener(new MouseAdapter()
			{
			});
			final SidePanelParty.Metric[] all = SidePanelParty.Metric.values();
			for (int i = 0; i < all.length; i++)
			{
				if (i > 0)
				{
					add(new SidePanelText(PanelSnapshot.Text.small(SidePanelFightTab.TargetSwitch.DOT, SidePanelStyle.MUTED),
						false));
				}
				final SidePanelParty.Metric m = all[i];
				final SidePanelText w = new SidePanelText(PanelSnapshot.Text.small(m.word(), SidePanelStyle.MUTED), false);
				w.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				w.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent e)
					{
						if (m != MetricSwitch.this.picked)
						{
							onPick.accept(m);
						}
					}
				});
				words.add(w);
				add(w);
			}
			set(picked);
		}

		void set(SidePanelParty.Metric m)
		{
			picked = m;
			final SidePanelParty.Metric[] all = SidePanelParty.Metric.values();
			for (int i = 0; i < all.length; i++)
			{
				words.get(i).set(PanelSnapshot.Text.small(all[i].word(), all[i] == m ? SidePanelStyle.VALUE
					: SidePanelStyle.MUTED));
			}
		}
	}

	/**
	 * T-128: a member's Discord picture in a circle, or their colour as a round swatch without one: beside their name in
	 * their view's head and on the Fight tab's line. Scaled once per picture and size; a new picture is a repaint.
	 */
	static final class Picture extends JComponent
	{
		private java.awt.image.BufferedImage avatar;
		private Color colour;
		private final int size;
		private java.awt.image.BufferedImage scaled;
		private int scaledPx;

		Picture(java.awt.image.BufferedImage avatar, Color colour, int size)
		{
			this.avatar = avatar;
			this.colour = colour;
			this.size = size;
		}

		void set(java.awt.image.BufferedImage avatar, Color colour)
		{
			if (avatar != this.avatar || !Objects.equals(colour, this.colour))
			{
				this.avatar = avatar;
				this.colour = colour;
				scaled = null;
				repaint();
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(size, size);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			final int y = (getHeight() - size) / 2;
			if (avatar == null)
			{
				g.setColor(colour);
				g.fillOval(0, y, size, size);
			}
			else
			{
				final int px = (int) Math.round(size * Math.max(1, g.getTransform().getScaleX()));
				if (scaled == null || scaledPx != px)
				{
					scaled = SidePanelPartyMeter.scale(avatar, px);
					scaledPx = px;
				}
				g.setClip(new java.awt.geom.Ellipse2D.Double(0, y, size, size));
				g.drawImage(scaled, 0, y, size, size, null);
			}
			g.dispose();
		}
	}

	/** A small square in the member's colour beside their name. */
	static final class Swatch extends JComponent
	{
		private final Color colour;

		Swatch(Color colour)
		{
			this.colour = colour;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(8, 8);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(colour);
			g.fillRect(0, (getHeight() - 8) / 2, 8, 8);
		}
	}

	/** A member's attack rows, painted: name and damage, a share bar in their colour, two lines of figures. */
	static final class Attacks extends JComponent
	{
		private List<PanelSnapshot.PartyAttack> rows = Collections.emptyList();
		private Color colour = SidePanelStyle.VALUE;

		void set(List<PanelSnapshot.PartyAttack> rows, Color colour)
		{
			final boolean count = rows.size() != this.rows.size();
			if (!count && Objects.equals(colour, this.colour) && rowsEqual(rows))
			{
				return;
			}
			this.rows = rows;
			this.colour = colour;
			if (count)
			{
				revalidate();
			}
			repaint();
		}

		private boolean rowsEqual(List<PanelSnapshot.PartyAttack> other)
		{
			for (int i = 0; i < other.size(); i++)
			{
				final PanelSnapshot.PartyAttack a = other.get(i);
				final PanelSnapshot.PartyAttack b = rows.get(i);
				if (!a.name.equals(b.name) || !a.right.equals(b.right) || !a.line1.equals(b.line1) || !a.line2.equals(b.line2))
				{
					return false;
				}
			}
			return true;
		}

		static int rowHeight()
		{
			return SidePanelStyle.Type.BODY.lineHeight() + 4 + 2 * SidePanelStyle.Type.SMALL.lineHeight() + 4;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(SidePanelStyle.CONTENT_WIDTH - 2 * SidePanelStyle.CARD_PAD_H, rows.size() * rowHeight());
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = (Graphics2D) g0.create();
			SidePanelStyle.textHints(g);
			final int w = getWidth();
			final Font body = SidePanelStyle.Type.BODY.font();
			final Font small = SidePanelStyle.Type.SMALL.font();
			final FontMetrics fb = SidePanelStyle.metrics(g, body);
			final FontMetrics fs = SidePanelStyle.metrics(g, small);
			int y = 0;
			for (PanelSnapshot.PartyAttack a : rows)
			{
				final int base = y + fb.getAscent();
				g.setFont(body);
				final int rightW = fb.stringWidth(a.right);
				g.setColor(SidePanelStyle.VALUE);
				g.drawString(a.right, w - rightW, base);
				g.setColor(SidePanelStyle.BODY);
				g.drawString(SidePanelPartyMeter.cut(a.name, fb, w - rightW - 6), 0, base);
				final int barY = y + SidePanelStyle.Type.BODY.lineHeight() + 1;
				g.setColor(SidePanelStyle.TRACK);
				g.fillRect(0, barY, w, 3);
				g.setColor(colour);
				g.fillRect(0, barY, (int) Math.round(w * a.fill), 3);
				g.setFont(small);
				g.setColor(SidePanelStyle.MUTED);
				int sy = barY + 4 + fs.getAscent();
				g.drawString(SidePanelPartyMeter.cut(a.line1, fs, w), 0, sy);
				sy += SidePanelStyle.Type.SMALL.lineHeight();
				g.drawString(SidePanelPartyMeter.cut(a.line2, fs, w), 0, sy);
				y += rowHeight();
			}
			g.dispose();
		}
	}
}

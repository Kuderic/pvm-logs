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
 * T-125: the Party tab (Eric's pick, option B). The meter fills the tab, with Damage over time under it; clicking a
 * member swaps the tab to their breakdown, with {@code ‹ Party} to come back, as loa-logs does. In a raid the room/raid
 * switch is the Fight tab's own. Components are built only when the structure changes (the rows' number, the switches,
 * a member opened or closed); otherwise the meter and the chart repaint and the texts are set in place.
 */
final class SidePanelPartyTab extends JPanel
{
	private final SidePanelFightTab.Actions actions;
	private final SidePanelSection.Folds folds;

	private PanelSnapshot.PartyTab shown;
	private String builtFor;
	/** The member opened, or none. Swing's thread. */
	private long opened = NONE;
	private SidePanelParty.Metric metric = SidePanelParty.Metric.DMG;
	private int rebuilds;

	static final long NONE = Long.MIN_VALUE;

	// The current components, for updates in place.
	private SidePanelParts.Line header;
	private SidePanelPartyMeter meter;
	private SidePanelPartyChart chart;
	private MetricSwitch words;
	private SidePanelParts.Line place;
	private Attacks attacks;
	private SidePanelParts.RowsCard summary;
	private SidePanelFightTab.Segment roomSegment;
	private SidePanelFightTab.Segment raidSegment;

	SidePanelPartyTab(SidePanelFightTab.Actions actions, SidePanelSection.Folds folds)
	{
		super(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		this.actions = actions;
		this.folds = folds;
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
		// A member who's no longer on the board can't stay open.
		if (opened != NONE && t.row(opened) == null)
		{
			opened = NONE;
		}
		if (!t.after)
		{
			metric = SidePanelParty.Metric.DMG;
		}
		final String structure = structure(t);
		if (!structure.equals(builtFor))
		{
			build(t);
			builtFor = structure;
		}
		else
		{
			update(t);
		}
	}

	/** Built again in the panel's new text settings (T-053). */
	void restyle()
	{
		builtFor = null;
		if (shown != null)
		{
			apply(shown);
		}
	}

	private String structure(PanelSnapshot.PartyTab t)
	{
		if (opened == NONE)
		{
			return t.structure();
		}
		final PanelSnapshot.PartyBreakdown b = t.row(opened).breakdown;
		return t.structure() + "|open" + opened + "." + b.attacks.size() + "." + b.summary.size();
	}

	/** Opens a member's breakdown: the click on the meter. */
	void open(long id)
	{
		if (shown != null && shown.row(id) != null && opened != id)
		{
			opened = id;
			apply(shown);
		}
	}

	/** Back to the meter: {@code ‹ Party}. */
	void back()
	{
		if (opened != NONE)
		{
			opened = NONE;
			apply(shown);
		}
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

	long opened()
	{
		return opened;
	}

	SidePanelParty.Metric metric()
	{
		return metric;
	}

	/** For tests (T-046): how many times the components were built. */
	int rebuilds()
	{
		return rebuilds;
	}

	SidePanelPartyMeter meter()
	{
		return meter;
	}

	SidePanelPartyChart chart()
	{
		return chart;
	}

	private void build(PanelSnapshot.PartyTab t)
	{
		rebuilds++;
		removeAll();
		header = null;
		meter = null;
		chart = null;
		words = null;
		place = null;
		attacks = null;
		summary = null;
		roomSegment = null;
		raidSegment = null;
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
		if (opened == NONE)
		{
			meterSection(t);
		}
		else
		{
			memberSection(t.row(opened));
		}
		chart = new SidePanelPartyChart();
		chart.set(t, opened);
		final JPanel card = SidePanelParts.card(4);
		card.add(chart);
		// No hint beside the title: in Larger and Smooth text the title takes the width.
		add(new SidePanelSection("partyChart", "Damage over time", PanelSnapshot.Text.NONE, card, folds));
		revalidate();
		repaint();
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
		meter = new SidePanelPartyMeter(this::open);
		meter.setMetric(metric);
		meter.set(t.rows);
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

	private void memberSection(PanelSnapshot.PartyRow r)
	{
		final PanelSnapshot.PartyBreakdown b = r.breakdown;
		final JPanel card = SidePanelParts.card(6);
		final SidePanelParts.Line backLine = new SidePanelParts.Line(PanelSnapshot.Text.small("‹ Party",
			SidePanelStyle.SECONDARY), PanelSnapshot.Text.NONE);
		backLine.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		backLine.setToolTipText("Back to the party");
		backLine.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				back();
			}
		});
		card.add(backLine);
		final JPanel title = new JPanel(new BorderLayout(5, 0));
		title.setOpaque(false);
		title.add(new Swatch(r.colour), BorderLayout.WEST);
		place = new SidePanelParts.Line(PanelSnapshot.Text.title(b.title, SidePanelStyle.VALUE),
			PanelSnapshot.Text.small(b.place, SidePanelStyle.SECONDARY));
		// The name stays whole: the place drops its DPS, then its share, first.
		place.setRightShort(placeShort(b.place));
		title.add(place, BorderLayout.CENTER);
		card.add(title);
		attacks = new Attacks();
		attacks.set(b.attacks, r.colour);
		card.add(attacks);
		summary = new SidePanelParts.RowsCard(b.summary, 3);
		summary.setOpaque(false);
		summary.setBorder(new EmptyBorder(0, 0, 0, 0));
		card.add(summary);
		add(new SidePanelSection("partyMember", "Party", PanelSnapshot.Text.NONE, card, folds));
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
		}
		if (opened != NONE)
		{
			final PanelSnapshot.PartyRow r = t.row(opened);
			place.set(PanelSnapshot.Text.title(r.breakdown.title, SidePanelStyle.VALUE), null,
				PanelSnapshot.Text.small(r.breakdown.place, SidePanelStyle.SECONDARY));
			place.setRightShort(placeShort(r.breakdown.place));
			attacks.set(r.breakdown.attacks, r.colour);
			summary.set(r.breakdown.summary);
		}
		chart.set(t, opened);
	}

	/** {@code 3rd of 3 · 20% · 15.4 dps}'s short forms: {@code 3rd of 3 · 20%}, then {@code 3rd of 3}. */
	static List<String> placeShort(String place)
	{
		final String[] parts = place.split(" · ");
		if (parts.length < 3)
		{
			return Collections.emptyList();
		}
		return java.util.Arrays.asList(parts[0] + " · " + parts[1], parts[0]);
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

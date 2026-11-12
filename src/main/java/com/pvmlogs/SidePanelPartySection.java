package com.pvmlogs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
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
 * T-176 (Eric, 2026-11-05: "rework the party tab to just be part of the fight tab and remove the party tab, since the two
 * are very similar"): the party on the Fight tab. Before T-176 this was the Party tab (T-125, T-130, T-132, T-167, T-169):
 * the switch, the title, the meter, the party's Damage over time, then a member's heading and their Fight panels. The
 * Fight tab has its own switch, title and panels, so what's left is two blocks the Fight tab places:
 * <ul>
 * <li>{@link #top()}: the Party section ({@code party}): the meter, then its Timeline (T-205; was the party's Damage over
 * time, {@code partyChart}), after the title; out of a fight, the party's line and the members listed (T-167);</li>
 * <li>{@link #member()}: with a member selected, their heading, tinted (T-132), and their Fight panels from the tiles
 * down, in place of yours. Clicking them again, or your own row, goes back to yours.</li>
 * </ul>
 * Out of a party neither is shown and nothing is reserved. The top is built only when its structure changes (members,
 * the board's shape), the member's part only when the selection or its own structure does; otherwise the meter and the
 * chart repaint and texts are set in place. Both live inside the Fight tab, which paints through its buffer.
 */
final class SidePanelPartySection
{
	/** T-130, T-132: the meter's tooltip; the heading names whose panels follow and nothing else. */
	static final String SWITCH_HINT = "Click a member to show their Fight panels below; click again, or your own row, "
		+ "for yours.";
	/** T-132: the heading's picture, at 1× (Eric, 2026-10-31: "Just the Picture made larger and the person's name"). */
	static final int HEADING_PICTURE = 24;
	/**
	 * T-176: the sections' ids, as the Party tab had them, so saved folds carry. T-205: {@code partyChart} isn't a section
	 * any more; its key opens Party.
	 */
	static final String PARTY = "party";
	static final String PARTY_CHART = "partyChart";

	/**
	 * T-132: how the selected member's part is marked as theirs: the area behind their heading and panels blended toward
	 * their colour, or their cards' backgrounds; and whether your own part is tinted too (your colour is white) or plain.
	 * Set on a selection change, never per tick. Static for the renders' options.
	 */
	enum Tint
	{
		AREA, CARDS, NONE
	}

	static Tint tint = Tint.AREA;
	/** How far toward the member's colour: the area's background, and the cards' with {@link Tint#CARDS}. */
	static final double AREA_TINT = 0.10;
	static final double CARD_TINT = 0.12;
	/** The tinted area's inset around the heading and panels, the same with the tint or without, so nothing moves. */
	static final int AREA_PAD = 4;

	private final SidePanelFightTab.Actions actions;
	private final SidePanelSection.Folds folds;
	/** T-128: the item and spell icons for a member's Fight panels. */
	private final SidePanelIcons icons;
	/** T-130: told which member is selected, so the panel builds their Fight panels and only theirs. */
	private final java.util.function.LongConsumer onSelected;
	/** T-176: the Fight tab, told when what it places changes (shown or not, a member's panels or yours). */
	private Runnable onPlacement = () ->
	{
	};

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

	/** T-176: the two blocks the Fight tab places. Their children are built here; the panels themselves are kept. */
	private final JPanel top = new JPanel(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
	private final JPanel memberArea = new JPanel(new SidePanelStack(SidePanelStyle.BLOCK_GAP));

	// The current components, for updates in place.
	private SidePanelParts.Line header;
	private SidePanelPartyMeter meter;
	/** T-208: the "not in this fight" line under the meter; null without one. */
	private SidePanelParts.Line awayLine;
	/** T-208: your grade and its colour, from your kill card; null without one. */
	private String yourGrade;
	private java.awt.Color yourGradeColour;
	/** T-216: your DPS from your live card; null after the fight. */
	private String yourDps;
	private SidePanelPartyChart chart;
	private MetricSwitch words;
	private Picture headingPicture;
	private SidePanelParts.Line heading;
	private SidePanelFightTab memberFight;
	/** T-132: the colour the member's part is tinted toward, null for none; and the builds of their panels it was set on. */
	private Color tinted;
	private int tintedBuilds = -1;
	/** The snapshot {@link #memberFight} last had, so the same one (a member's room, built once) isn't applied again. */
	private PanelSnapshot.FightTab memberApplied;
	private Attacks attacks;
	private SidePanelParts.RowsCard summary;

	SidePanelPartySection(SidePanelFightTab.Actions actions, SidePanelSection.Folds folds)
	{
		this(actions, folds, SidePanelIcons.NONE, id ->
		{
		});
	}

	SidePanelPartySection(SidePanelFightTab.Actions actions, SidePanelSection.Folds folds, SidePanelIcons icons,
		java.util.function.LongConsumer onSelected)
	{
		this.actions = actions;
		this.folds = folds;
		this.icons = icons == null ? SidePanelIcons.NONE : icons;
		this.onSelected = onSelected;
		top.setOpaque(false);
		memberArea.setOpaque(false);
	}

	/** T-176: the Fight tab's hook, called when {@link #placement()} may have changed. */
	void setOnPlacement(Runnable r)
	{
		onPlacement = r == null ? () ->
		{
		} : r;
	}

	/**
	 * T-176: shows the party's snapshot; null keeps what's shown (no party read this time). Called before the Fight tab
	 * takes its own snapshot, which asks {@link #placement()} where these blocks go.
	 */
	void apply(PanelSnapshot.PartyTab t)
	{
		if (t == null)
		{
			return;
		}
		shown = t;
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
		if (!shows(t))
		{
			// Out of a party, or nobody but you: nothing on the Fight tab, nothing kept.
			if (builtFor != null)
			{
				clear();
			}
			return;
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
	}

	/** T-176: whether the Fight tab shows the party: a meter (someone sharing, or members listed, T-167) or members listed. */
	static boolean shows(PanelSnapshot.PartyTab t)
	{
		return t != null && (t.sharing || !t.rows.isEmpty());
	}

	/**
	 * T-176: where the Fight tab puts the blocks, as a key for its structure: empty out of a party; {@code |party} with
	 * your panels below; {@code |party|m<id>} with a member's in their place.
	 */
	String placement()
	{
		if (!shows(shown))
		{
			return "";
		}
		return memberShown() ? "|party|m" + selected : "|party";
	}

	/** T-176: a member's panels show in place of yours. */
	boolean memberShown()
	{
		return shows(shown) && shown.sharing && selected != SidePanelParty.YOU_ID && shown.row(selected) != null;
	}

	/** T-176: the party's block, after the title. */
	JPanel top()
	{
		return top;
	}

	/** T-176: the selected member's heading and panels, in place of yours. */
	JPanel member()
	{
		return memberArea;
	}

	/** Built again in the panel's new text settings (T-053). */
	void restyle()
	{
		builtFor = null;
		memberBuiltFor = null;
		tinted = null;
		if (shown != null)
		{
			apply(shown);
		}
	}

	private void clear()
	{
		builtFor = null;
		memberBuiltFor = null;
		top.removeAll();
		memberArea.removeAll();
		header = null;
		meter = null;
		awayLine = null;
		chart = null;
		words = null;
		clearMember();
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
	 * T-130: a click on a member's row. It selects them; on the selected row again, or on your own, back to you. The
	 * meter and the chart repaint; the Fight tab puts their panels, or yours, below.
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
			chart.set(shown, focus(), metric);
		}
		member(shown, true);
		onPlacement.run();
	}

	/** The member whose panels show; {@link SidePanelParty#YOU_ID} for yours. */
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
		// T-216: the chart follows the switch too: damage, luck or skill over the fight.
		if (chart != null && shown != null)
		{
			chart.set(shown, focus(), m);
		}
	}

	SidePanelParty.Metric metric()
	{
		return metric;
	}

	/** For tests (T-046): how many times the party's block, and the member's part alone, were built. */
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

	/** T-128, T-130: the selected member's Fight panels; null for yours (the Fight tab's own) or the compact breakdown. */
	SidePanelFightTab memberFight()
	{
		return memberFight;
	}

	/** T-130: the heading naming whose panels follow; null while yours show. */
	SidePanelParts.Line heading()
	{
		return heading;
	}

	private void build(PanelSnapshot.PartyTab t)
	{
		rebuilds++;
		top.removeAll();
		header = null;
		meter = null;
		awayLine = null;
		chart = null;
		words = null;
		memberBuiltFor = null;
		if (!t.sharing)
		{
			// Before a fight of yours is on show: the party's line, the members listed by name and picture (T-167).
			final JPanel card = SidePanelParts.card(t.rows.isEmpty() ? 0 : 5);
			card.add(new SidePanelParts.Paragraph(t.empty, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
				SidePanelStyle.CONTENT_WIDTH - 14));
			if (!t.rows.isEmpty())
			{
				meter = new SidePanelPartyMeter(this::select);
				meter.set(t.rows);
				card.add(meter);
			}
			top.add(new SidePanelSection(PARTY, "Party", PanelSnapshot.Text.NONE, card, folds));
			memberArea.removeAll();
			clearMember();
			top.revalidate();
			top.repaint();
			return;
		}
		meterSection(t);
		member(t, true);
		top.revalidate();
		top.repaint();
	}

	private void clearMember()
	{
		headingPicture = null;
		heading = null;
		memberFight = null;
		memberApplied = null;
		attacks = null;
		summary = null;
	}

	/**
	 * T-205 (Eric: "Party and party damage over time should match the way damage is handled bundled into one"): one
	 * Party section, built as Damage is: the party's DPS, the meter and the others' note on one card, then the
	 * "Timeline" subhead and the party's chart on the next (was the "Party damage over time" section, {@code partyChart},
	 * whose fold key now opens Party through the panel's MERGED).
	 */
	private void meterSection(PanelSnapshot.PartyTab t)
	{
		final JPanel list = SidePanelParts.stack(SidePanelStyle.ROW_GAP);
		final JPanel card = SidePanelParts.card(5);
		// T-132: the room's name and clock are the Fight tab's title, above: the party's DPS alone here.
		header = new SidePanelParts.Line(PanelSnapshot.Text.small(t.partyDps, SidePanelStyle.SECONDARY),
			PanelSnapshot.Text.NONE);
		header.setTip(t.where + " · " + headerRight(t));
		card.add(header);
		meter = new SidePanelPartyMeter(this::select);
		meter.setToolTipText(SidePanelStyle.tip(SWITCH_HINT));
		meter.setMetric(metric);
		meter.setYourGrade(yourGrade, yourGradeColour);
		meter.setYourDps(yourDps);
		meter.set(t.rows);
		meter.setSelected(selected);
		card.add(meter);
		if (t.othersNote != null)
		{
			card.add(new SidePanelParts.Paragraph(t.othersNote, SidePanelStyle.MUTED, SidePanelStyle.Type.SMALL,
				SidePanelStyle.CONTENT_WIDTH - 14));
		}
		// T-208: who wasn't in the fight, set apart in one muted line; their names on its hover.
		if (t.away != null)
		{
			awayLine = new SidePanelParts.Line(PanelSnapshot.Text.small(t.away, SidePanelStyle.MUTED),
				PanelSnapshot.Text.NONE);
			awayLine.setTip(t.awayTip);
			card.add(awayLine);
		}
		list.add(card);
		// Its Timeline, as Damage's: the subhead, then the chart, on a card of its own under the meter's.
		final JPanel box = SidePanelParts.card(5);
		box.add(SidePanelFightTab.subhead(SidePanelFightTab.TIMELINE, null));
		chart = new SidePanelPartyChart();
		chart.set(t, focus(), metric);
		box.add(chart);
		list.add(box);
		final SidePanelSection section = new SidePanelSection(PARTY, "Party", PanelSnapshot.Text.NONE, list, folds);
		// T-216: live too, its first word "dps" as the column's: the chart and the sort follow it.
		words = new MetricSwitch(metric, !t.after, this::pick);
		section.setHintComponent(words);
		top.add(section);
	}

	/** The selected member's row; null for you. */
	private PanelSnapshot.PartyRow selectedRow(PanelSnapshot.PartyTab t)
	{
		final PanelSnapshot.PartyRow r = t.row(selected);
		return r == null || r.you ? null : r;
	}

	private String memberStructure(PanelSnapshot.PartyTab t)
	{
		final PanelSnapshot.PartyRow r = selectedRow(t);
		if (r == null)
		{
			return "you";
		}
		if (r.fight != null)
		{
			// Their Fight panels rebuild themselves as their own structure changes; here only live or after.
			return r.id + (r.fightLive ? "|live" : "|fight");
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
			memberArea.add(headingCard(r));
			if (r.fight != null)
			{
				memberFight = new SidePanelFightTab(actions, folds, true, icons);
				memberFight.setParts(SidePanelFightTab.Parts.BODY);
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
		// A new part: the tint is set again, whatever it was.
		tintedBuilds = Integer.MIN_VALUE;
		tint(r);
		memberArea.revalidate();
		memberArea.repaint();
	}

	/**
	 * T-132 (Eric, 2026-10-31: "some sort of UI color change to indicate which player you are on"): the member's part
	 * tinted toward their colour, as {@link #tint} says. Set when the member's part is built, and again only when their
	 * panels rebuild themselves (new cards), never per tick.
	 */
	private void tint(PanelSnapshot.PartyRow r)
	{
		final Color c = r == null || tint == Tint.NONE ? null : r.colour;
		final int builds = memberFight == null ? -1 : memberFight.builds();
		if (java.util.Objects.equals(c, tinted) && builds == tintedBuilds && memberArea.getBorder() != null)
		{
			return;
		}
		tinted = c;
		tintedBuilds = builds;
		tints++;
		memberArea.setBorder(new EmptyBorder(AREA_PAD, AREA_PAD, AREA_PAD, AREA_PAD));
		final boolean area = c != null && tint == Tint.AREA;
		memberArea.setOpaque(area);
		memberArea.setBackground(area ? tinted(SidePanelStyle.PANEL, c, AREA_TINT) : SidePanelStyle.PANEL);
		cards(memberArea, c != null && tint == Tint.CARDS ? tinted(SidePanelStyle.CARD, c, CARD_TINT) : SidePanelStyle.CARD);
		final java.util.Set<Color> tints = new java.util.HashSet<>();
		if (c != null)
		{
			tints.add(tint == Tint.AREA ? tinted(SidePanelStyle.PANEL, c, AREA_TINT) : tinted(SidePanelStyle.CARD, c,
				CARD_TINT));
		}
		lift(memberArea, memberArea.isOpaque() ? memberArea.getBackground() : SidePanelStyle.PANEL, tints);
		memberArea.repaint();
	}

	/**
	 * T-132 review: muted text drawn on a tinted background (its nearest opaque ancestor's), a little lighter, for the
	 * contrast it has on the plain panel; back to plain where the background isn't tinted.
	 */
	private static void lift(java.awt.Container c, Color behind, java.util.Set<Color> tints)
	{
		for (java.awt.Component k : c.getComponents())
		{
			final Color bg = k.isOpaque() && k.getBackground() != null ? k.getBackground() : behind;
			if (k instanceof SidePanelText)
			{
				((SidePanelText) k).setLifted(tints.contains(bg));
			}
			if (k instanceof java.awt.Container)
			{
				lift((java.awt.Container) k, bg, tints);
			}
		}
	}

	/** {@code base} blended {@code amount} of the way toward {@code toward}. */
	static Color tinted(Color base, Color toward, double amount)
	{
		return new Color((int) Math.round(base.getRed() + (toward.getRed() - base.getRed()) * amount),
			(int) Math.round(base.getGreen() + (toward.getGreen() - base.getGreen()) * amount),
			(int) Math.round(base.getBlue() + (toward.getBlue() - base.getBlue()) * amount));
	}

	/** Every card in {@code c} (an opaque panel in a card's colour, tinted or not) to {@code to}. */
	private static void cards(java.awt.Container c, Color to)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof JComponent && k.isOpaque() && isCard(k.getBackground()))
			{
				k.setBackground(to);
			}
			if (k instanceof java.awt.Container)
			{
				cards((java.awt.Container) k, to);
			}
		}
	}

	/** A card's background: the plain one, or one tinted from it toward any member's colour. */
	private static boolean isCard(Color bg)
	{
		if (bg == null)
		{
			return false;
		}
		if (bg.equals(SidePanelStyle.CARD))
		{
			return true;
		}
		for (Color m : SidePanelParty.COLOURS)
		{
			if (bg.equals(tinted(SidePanelStyle.CARD, m, CARD_TINT)))
			{
				return true;
			}
		}
		return bg.equals(tinted(SidePanelStyle.CARD, SidePanelParty.YOU, CARD_TINT));
	}

	/** For tests: the colour the member's part is tinted toward; null for none. */
	Color tintedToward()
	{
		return tinted;
	}

	/** For tests: how many times the tint was set (on selection changes and the member's panels rebuilding only). */
	int tints()
	{
		return tints;
	}

	private int tints;

	JPanel memberArea()
	{
		return memberArea;
	}

	/**
	 * T-132: whose panels follow: their picture (or their colour, round) at {@link #HEADING_PICTURE} and their name in the
	 * title font, nothing else; live, a line on what comes after their room.
	 */
	private JPanel headingCard(PanelSnapshot.PartyRow r)
	{
		final JPanel card = SidePanelParts.card(3);
		final JPanel line = new JPanel(new BorderLayout(7, 0));
		line.setOpaque(false);
		headingPicture = new Picture(r.avatar, r.colour, HEADING_PICTURE);
		line.add(headingPicture, BorderLayout.WEST);
		heading = new SidePanelParts.Line(PanelSnapshot.Text.title(headingTitle(r), SidePanelStyle.VALUE),
			PanelSnapshot.Text.NONE);
		line.add(heading, BorderLayout.CENTER);
		card.add(line);
		if (!r.you && r.fight != null && r.fightLive)
		{
			card.add(new SidePanelParts.Paragraph(SidePanelMemberFight.LATER_LIVE, SidePanelStyle.MUTED,
				SidePanelStyle.Type.SMALL, SidePanelStyle.CONTENT_WIDTH - 14));
		}
		return card;
	}

	/** T-132: the member's name, as the meter has it. */
	static String headingTitle(PanelSnapshot.PartyRow r)
	{
		return r.name;
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
		if (memberFight != null && r.fight != null && r.fight != memberApplied)
		{
			memberApplied = r.fight;
			memberFight.apply(r.fight);
		}
		else if (memberFight == null && attacks != null)
		{
			attacks.set(r.breakdown.attacks, r.colour);
			summary.set(r.breakdown.summary);
		}
		// Their panels rebuilt themselves (new cards): the tint again; otherwise nothing.
		tint(r);
	}

	private void update(PanelSnapshot.PartyTab t)
	{
		if (!t.sharing)
		{
			// T-167: a listed member's new name or picture: a repaint.
			if (meter != null)
			{
				meter.set(t.rows);
			}
			return;
		}
		if (header != null)
		{
			header.set(PanelSnapshot.Text.small(t.partyDps, SidePanelStyle.SECONDARY), null, PanelSnapshot.Text.NONE);
			header.setTip(t.where + " · " + headerRight(t));
		}
		if (meter != null)
		{
			meter.set(t.rows);
			meter.setSelected(selected);
		}
		if (awayLine != null && t.away != null)
		{
			awayLine.set(PanelSnapshot.Text.small(t.away, SidePanelStyle.MUTED), null, PanelSnapshot.Text.NONE);
			awayLine.setTip(t.awayTip);
		}
		if (chart != null)
		{
			chart.set(t, focus(), metric);
		}
		member(t, false);
	}

	/**
	 * T-208: your kill card, for your row's grade chip after the fight (a member's grade isn't shared, so theirs shows
	 * their Skill %). Null, or live, for none. A repaint.
	 */
	void setYours(PanelSnapshot.KillCard card)
	{
		yourGrade = card == null || card.live ? null : card.grade;
		yourGradeColour = card == null ? null : card.gradeColour;
		// T-216: live, your row's DPS is the card's (T-200).
		yourDps = card == null || !card.live ? null : card.dps;
		if (meter != null)
		{
			meter.setYourGrade(yourGrade, yourGradeColour);
			meter.setYourDps(yourDps);
		}
	}

	/** T-208: the "not in this fight" line, for tests; null without one. */
	SidePanelParts.Line awayLine()
	{
		return awayLine;
	}


	/** The header's right: the clock and the party's DPS, {@code 2:09 · party 79.1 dps}. */
	static String headerRight(PanelSnapshot.PartyTab t)
	{
		return t.clock + " · " + t.partyDps;
	}

	/**
	 * The meter's {@code dmg · luck · skill} ({@code dps · luck · skill} live, T-216), as the Damage card's switch: the
	 * picked word bright, a click picks.
	 */
	static final class MetricSwitch extends SidePanelFightTab.Segmented
	{
		MetricSwitch(SidePanelParty.Metric picked, java.util.function.Consumer<SidePanelParty.Metric> onPick)
		{
			this(picked, false, onPick);
		}

		MetricSwitch(SidePanelParty.Metric picked, boolean live, java.util.function.Consumer<SidePanelParty.Metric> onPick)
		{
			super(labels(live), live ? LIVE_TIPS : TIPS, picked.ordinal(), i -> onPick.accept(
				SidePanelParty.Metric.values()[i]));
		}

		/**
		 * T-207: each choice in plain words, on its own pill's tooltip. T-208: the rows always show all three; the switch
		 * sorts them. T-216: and the Timeline charts what's picked.
		 */
		static final String[] TIPS = {"Sort the rows by damage and chart damage over the fight",
			"Sort the rows by luck and chart luck over the fight", "Sort the rows by skill and chart Skill over the fight"};
		static final String[] LIVE_TIPS = {"Sort the rows by DPS and chart damage so far",
			"Sort the rows by luck and chart luck so far", "Sort the rows by skill and chart Skill so far"};

		private static String[] labels(boolean live)
		{
			final SidePanelParty.Metric[] all = SidePanelParty.Metric.values();
			final String[] out = new String[all.length];
			for (int i = 0; i < all.length; i++)
			{
				out[i] = all[i].word(live);
			}
			return out;
		}

		void set(SidePanelParty.Metric m)
		{
			set(m.ordinal(), null);
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

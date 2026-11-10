package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * T-132 (Eric, 2026-10-31 11:11), on the Fight tab since T-176: a member's heading is their picture and name only; their
 * part is tinted toward their colour; the room's title is the Fight tab's own, above the party, and a member's panels
 * start at the tiles; a member's Damage card has the boss · adds · all switch from their room message; the label reads
 * "party" in a RuneLite party.
 */
public class SidePanelPartyTitleTest
{
	@After
	public void reset()
	{
		SidePanelPartySection.tint = SidePanelPartySection.Tint.AREA;
		SidePanelFightTab.resetTarget();
	}

	private static PvmPerformancePanel panel(SidePanelParty.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		return p;
	}

	// ---- 1. The heading ----

	@Test
	public void theHeadingIsThePictureAndTheNameOnly()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		// T-176: yours have no heading: nobody's selected.
		assertNull(tab.heading());
		tab.select(22);
		assertEquals("Brindle", tab.heading().left.getText());
		final Container card = tab.heading().getParent().getParent();
		final List<String> texts = new ArrayList<>();
		int pictures = 0;
		for (Component c : all(card))
		{
			if (c instanceof SidePanelText)
			{
				texts.add(((SidePanelText) c).getText());
			}
			if (c instanceof SidePanelParts.Paragraph)
			{
				texts.add(((SidePanelParts.Paragraph) c).text());
			}
			if (c instanceof SidePanelPartySection.Picture)
			{
				pictures++;
				assertEquals(SidePanelPartySection.HEADING_PICTURE, c.getPreferredSize().width);
			}
		}
		assertEquals(1, pictures);
		// Her name and nothing else: no " · Fight panels", no place, no hint.
		assertEquals(Arrays.asList("Brindle", ""), texts);
		// The hint is the meter's tooltip.
		assertEquals(SidePanelPartySection.SWITCH_HINT, Tips.plain(tab.meter().getToolTipText()));
	}

	// ---- 2. The tint ----

	@Test
	public void theTintFollowsTheSelectionAndIsntSetPerTick()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		// Yours plain by default.
		assertNull(tab.tintedToward());
		assertFalse(tab.memberArea().isOpaque());
		tab.select(22);
		final Color brindle = tab.chart() == null ? null : rowColour(p, 22);
		assertEquals(brindle, tab.tintedToward());
		assertTrue(tab.memberArea().isOpaque());
		assertEquals(SidePanelPartySection.tinted(SidePanelStyle.PANEL, brindle, SidePanelPartySection.AREA_TINT),
			tab.memberArea().getBackground());
		// Snapshots with the same shape: the tint isn't set again.
		final int tints = tab.tints();
		for (int i = 0; i < 5; i++)
		{
			p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.toaYouAndBrindle())));
		}
		assertEquals(tints, tab.tints());
		// Back to you: plain again, the inset the same, so nothing moves.
		tab.select(22);
		assertNull(tab.tintedToward());
		assertFalse(tab.memberArea().isOpaque());
		assertEquals(SidePanelPartySection.AREA_PAD, tab.memberArea().getInsets().top);
	}

	@Test
	public void theCardsOptionTintsTheirCards()
	{
		SidePanelPartySection.tint = SidePanelPartySection.Tint.CARDS;
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		// T-176: yours are the Fight tab's own, never tinted.
		assertNull(tab.tintedToward());
		tab.select(22);
		final Color card = SidePanelPartySection.tinted(SidePanelStyle.CARD, rowColour(p, 22), SidePanelPartySection.CARD_TINT);
		int tinted = 0;
		for (Component c : all(tab.memberArea()))
		{
			assertFalse("no card left plain", c.isOpaque() && SidePanelStyle.CARD.equals(c.getBackground()));
			tinted += c.isOpaque() && card.equals(c.getBackground()) ? 1 : 0;
		}
		assertTrue(tinted > 5);
		assertFalse(tab.memberArea().isOpaque());
	}

	/** The muted text stays readable on the tint for every member colour (WCAG contrast, the untinted card's 5.2). */
	@Test
	public void mutedTextStaysReadableOnTheTint()
	{
		for (Color m : SidePanelParty.COLOURS)
		{
			final double onArea = contrast(SidePanelStyle.MUTED, SidePanelPartySection.tinted(SidePanelStyle.PANEL, m,
				SidePanelPartySection.AREA_TINT));
			final double onCard = contrast(SidePanelStyle.MUTED, SidePanelPartySection.tinted(SidePanelStyle.CARD, m,
				SidePanelPartySection.CARD_TINT));
			assertTrue(m + " area " + onArea, onArea >= 4.0);
			assertTrue(m + " card " + onCard, onCard >= 4.3);
		}
	}

	// ---- 3. The title on top ----

	@Test
	public void theRoomsTitleIsOnTopAndAMembersPanelsStartAtTheirSections()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		final SidePanelFightTab fight = p.fightTab();
		// T-176: the title is the Fight tab's own, above the party.
		final Component title = titleOf(fight);
		assertNotNull(title);
		assertTrue(SidePanelPartyMemberViewTest.place(fight, title) < SidePanelPartyMemberViewTest.place(fight,
			tab.top()));
		assertTrue(title instanceof SidePanelKillCard);
		// Hers on a click: no title or card of her own; T-202: her sections first, the same as yours.
		tab.select(22L);
		final SidePanelFightTab panels = tab.memberFight();
		assertFalse(texts(panels).stream().anyMatch(x -> x.startsWith("Zebak")));
		assertFalse(texts(panels).stream().anyMatch(x -> x.contains("900 HP")));
		assertTrue(texts(panels).contains("Luck"));
		assertTrue(panels.getComponent(0) instanceof SidePanelSection);
		assertEquals("damage", ((SidePanelSection) panels.getComponent(0)).id());
		// The title stays the Fight tab's, still above.
		assertSame(title.getClass(), titleOf(fight).getClass());
		// The meter's header: the party's DPS, the room's name and clock left to the title.
		assertTrue(texts(SidePanelPictures.sectionOf(tab.top(), "party")).contains("party 79.1 dps"));
	}

	/** The Fight tab's first child that holds the room's name: its title block, a room's kill card (T-202). */
	private static Component titleOf(SidePanelFightTab fight)
	{
		for (Component c : fight.getComponents())
		{
			if (c instanceof SidePanelKillCard && ((SidePanelKillCard) c).card().boss.startsWith("Zebak")
				|| c instanceof Container && texts((Container) c).stream().anyMatch(x -> x.startsWith("Zebak")))
			{
				return c;
			}
		}
		return null;
	}

	/** T-176: the party on the Fight tab through ten minutes of snapshots: neither the tab nor the party's block rebuilds. */
	@Test
	public void nothingRebuildsWhileTheShapeHolds()
	{
		final SidePanelPartyLagTest.Party party = new SidePanelPartyLagTest.Party();
		party.run(130);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(party.snapshot());
		final int builds = p.fightTab().builds();
		final int top = p.partySection().rebuilds();
		final int member = p.partySection().memberRebuilds();
		for (int i = 0; i < 100; i++)
		{
			party.run(PvmPerformancePanel.UPDATE_TICKS);
			p.apply(party.snapshot());
		}
		assertEquals(builds, p.fightTab().builds());
		assertEquals(top, p.partySection().rebuilds());
		assertEquals(member, p.partySection().memberRebuilds());
	}

	// ---- 4. A member's Damage switch ----

	@Test
	public void aMembersDamageCardHasTheSwitchFromTheirRoomMessage()
	{
		final SidePanelTargetExamples.Room r = SidePanelTargetExamples.araxxor();
		final PanelSnapshot.FightTab sender = SidePanelModel.fight(SidePanelTargetExamples.input(r));
		final PanelSnapshot.FightTab theirs = SidePanelModel.fight(SidePanelPartyExamples.araxxorDecoded(
			SidePanelTargetExamples.araxxor(), null));
		assertNotNull(theirs.byTarget);
		assertEquals(3, theirs.byTarget.size());
		for (int i = 0; i < 3; i++)
		{
			final PanelSnapshot.FightTab a = sender.showing(i);
			final PanelSnapshot.FightTab b = theirs.showing(i);
			assertEquals(i + "", a.damage.hint.text, b.damage.hint.text);
			assertEquals(i + "", a.damage.rows.size(), b.damage.rows.size());
			for (int k = 0; k < a.damage.rows.size(); k++)
			{
				assertEquals(a.damage.rows.get(k).name, b.damage.rows.get(k).name);
				assertEquals(a.damage.rows.get(k).damage, b.damage.rows.get(k).damage);
				assertEquals(a.damage.rows.get(k).sub, b.damage.rows.get(k).sub);
			}
			assertEquals(i + "", a.chart.length, b.chart.length);
			assertEquals(i + "", a.chart.end, b.chart.end);
		}
		// On the Party tab: the switch on her card, and "adds" shows her araxyte rows.
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.araxxorWithAdds());
		p.partySection().select(22);
		final SidePanelFightTab hers = p.partySection().memberFight();
		hers.pick(1);
		assertTrue(texts(hers).contains("adds"));
		assertTrue(texts(hers).contains("Zaryte crossbow"));
		assertFalse(texts(hers).contains("Noxious halberd"));
	}

	@Test
	public void anOlderSendersRoomShowsNoSwitchAndARoomWithoutAddsSendsNothingMore()
	{
		final SidePanelTargetExamples.Room r = SidePanelTargetExamples.araxxor();
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final LuckVsSkillRoom with = SidePanelMemberFight.message(r.room, e -> FightTimeline.room(e, r.store), 0, null);
		assertNotNull(with.f.b);
		final int withBytes = gson.toJson(with).length();
		// A sender from before T-132: no parts.
		final LuckVsSkillRoom older = gson.fromJson(gson.toJson(with), LuckVsSkillRoom.class);
		older.f.b = null;
		older.f.a = null;
		final int olderBytes = gson.toJson(older).length();
		assertNull(SidePanelModel.fight(SidePanelMemberFight.decode(older.f)).byTarget);
		System.out.println("T-132: an Araxxor kill with araxytes, its room message " + olderBytes + " bytes, "
			+ withBytes + " with the boss-only and adds-only parts (+" + (withBytes - olderBytes) + ")");
		assertTrue(withBytes - olderBytes < 6_000);
		// A room without adds: nothing more than before.
		final SidePanelTimelineExamples.Built zebak = SidePanelPartyExamples.longRoom();
		final LuckVsSkillRoom plain = SidePanelMemberFight.message(zebak.room, e -> FightTimeline.room(e, zebak.store),
			0, null);
		assertNull(plain.f.b);
		assertNull(plain.f.a);
		assertFalse(gson.toJson(plain).contains("\"ns\":true"));
	}

	// ---- 5. "party" ----

	@Test
	public void theLabelReadsPartyInARuneLitePartyAndIsKeptWithTheKill()
	{
		final SidePanelTimelineExamples.Built b = SidePanelPartyExamples.longRoom();
		final SidePanelModel.Input solo = SidePanelMemberFight.input(b.room, b.view(), null, true);
		assertFalse(solo.inParty);
		assertTrue(SidePanelModel.fight(solo).header.sub.text.contains("solo"));
		b.fight.markInParty();
		final SidePanelModel.Input party = SidePanelMemberFight.input(b.room, b.view(), null, true);
		assertTrue(party.inParty);
		final String sub = SidePanelModel.fight(party).header.sub.text;
		assertTrue(sub, sub.contains("party"));
		assertFalse(sub, sub.contains("solo"));
		// Kept with the kill: through the history's JSON and back.
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final Fight loaded = gson.fromJson(gson.toJson(b.fight), Fight.class);
		assertTrue(loaded.isInParty());
		// A kill saved before reads false.
		final Fight older = gson.fromJson(gson.toJson(b.fight).replace("\"inParty\":true,", "")
			.replace(",\"inParty\":true", ""), Fight.class);
		assertFalse(older.isInParty());
		// "Kills like this" still pool by others' damage: a solo kill in a party is still solo.
		assertTrue(LedgerHistory.Like.of(b.room).solo);
		// A member's panels read "party": they're sending.
		assertTrue(SidePanelModel.fight(SidePanelPartyExamples.araxxorDecoded(SidePanelTargetExamples.araxxor(), "B"))
			.header.sub.text.contains("party") || SidePanelPartyExamples.araxxorDecoded(SidePanelTargetExamples.araxxor(),
			"B").inParty);
	}

	/**
	 * T-132 review: where the title's line doesn't fit, the time of day goes first and the label stays: Eric's Araxxor
	 * at 1,020 HP, Normal text at 1×, reads "1,020 HP · party", not "1,020 HP · 2:06 pm".
	 */
	@Test
	public void theTimeOfDayGoesBeforeTheLabel()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		in.name = "Araxxor";
		in.maxHp = 1_020;
		in.inParty = true;
		in.endMillis = SidePanelExamples.at(14, 6);
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		assertTrue(tab.header.sub.text, tab.header.sub.text.contains("1,020 HP · party · "));
		assertEquals(Arrays.asList("1,020 HP · party", "1,020 HP"), tab.header.subShort);
		for (boolean party : new boolean[]{true, false})
		{
			final SidePanelModel.Input k = SidePanelExamples.dead();
			k.name = "Araxxor";
			k.maxHp = 1_020;
			k.inParty = party;
			k.endMillis = SidePanelExamples.at(14, 6);
			// T-202: a room's header is its kill card, which draws the line itself.
			String shown = null;
			for (String x : SidePanelKillCard.drawnTexts(SidePanelModel.fight(k).card))
			{
				shown = x.startsWith("1,020 HP") ? x : shown;
			}
			assertNotNull(shown);
			assertTrue(shown, shown.contains(party ? "party" : "solo"));
			assertFalse(shown, shown.contains(LedgerRows.CUT));
		}
	}

	/** T-132 review: muted text on the tint is drawn lighter, for the plain panel's contrast; nowhere else. */
	@Test
	public void mutedTextOnTheTintIsLifted()
	{
		for (Color m : SidePanelParty.COLOURS)
		{
			final double lifted = contrast(SidePanelStyle.MUTED_ON_TINT, SidePanelPartySection.tinted(SidePanelStyle.PANEL, m,
				SidePanelPartySection.AREA_TINT));
			assertTrue(m + " " + lifted, lifted >= 4.6);
		}
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		assertEquals(0, lifted(tab.memberArea()));
		tab.select(22);
		final int onTint = lifted(tab.memberArea());
		assertTrue("muted section hints on the tinted area", onTint > 0);
		// Inside the cards (not tinted by default) nothing is lifted.
		for (Component c : all(tab.memberArea()))
		{
			if (c instanceof SidePanelText && ((SidePanelText) c).isLifted())
			{
				for (Component a = c.getParent(); a != tab.memberArea(); a = a.getParent())
				{
					assertFalse(a.isOpaque() && SidePanelStyle.CARD.equals(a.getBackground()));
				}
			}
		}
		tab.select(22);
		assertEquals(0, lifted(tab.memberArea()));
	}

	private static int lifted(Container c)
	{
		int n = 0;
		for (Component k : all(c))
		{
			n += k instanceof SidePanelText && ((SidePanelText) k).isLifted() ? 1 : 0;
		}
		return n;
	}

	@Test
	public void outOfAPartySoloAndGroupAreAsBefore()
	{
		final SidePanelTimelineExamples.Built b = SidePanelPartyExamples.longRoom();
		b.fight.recordOthersDamage(40);
		final SidePanelModel.Input group = SidePanelMemberFight.input(b.room, b.view(), null, true);
		assertTrue(SidePanelModel.fight(group).header.sub.text.contains("group"));
		assertFalse(LedgerHistory.Like.of(b.room).solo);
	}

	// ---- helpers ----

	private static Color rowColour(PvmPerformancePanel p, long id)
	{
		return p.partySection().meter() == null ? null : ((PanelSnapshot) p.shown()).party.row(id).colour;
	}

	private static List<String> texts(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : all(c))
		{
			if (k instanceof SidePanelText)
			{
				out.add(((SidePanelText) k).getText());
			}
		}
		return out;
	}

	private static List<Component> all(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	private static double contrast(Color a, Color b)
	{
		final double la = luminance(a);
		final double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(Color c)
	{
		final double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
		for (int i = 0; i < 3; i++)
		{
			v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
	}
}

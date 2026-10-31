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
 * T-132 (Eric, 2026-10-31 11:11): the Party tab's heading is the member's picture and name only; their part is tinted
 * toward their colour; the room's title block is on top of the tab and a member's panels start at the tiles; a member's
 * Damage card has the boss · adds · all switch from their room message; the label reads "party" in a RuneLite party.
 */
public class SidePanelPartyTitleTest
{
	@After
	public void reset()
	{
		SidePanelPartyTab.tint = SidePanelPartyTab.Tint.AREA;
		SidePanelPartyTab.tintYours = false;
		SidePanelFightTab.resetTarget();
	}

	private static PvmPerformancePanel panel(SidePanelParty.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, SidePanelParty.party(in)));
		p.tabs().select(p.tabs().getTab(3));
		return p;
	}

	// ---- 1. The heading ----

	@Test
	public void theHeadingIsThePictureAndTheNameOnly()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		assertEquals("you", tab.heading().left.getText());
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
			if (c instanceof SidePanelPartyTab.Picture)
			{
				pictures++;
				assertEquals(SidePanelPartyTab.HEADING_PICTURE, c.getPreferredSize().width);
			}
		}
		assertEquals(1, pictures);
		// Her name and nothing else: no " · Fight panels", no place, no hint.
		assertEquals(Arrays.asList("Brindle", ""), texts);
		// The hint is the meter's tooltip.
		assertEquals(SidePanelPartyTab.SWITCH_HINT, tab.meter().getToolTipText());
	}

	// ---- 2. The tint ----

	@Test
	public void theTintFollowsTheSelectionAndIsntSetPerTick()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		// Yours plain by default.
		assertNull(tab.tintedToward());
		assertFalse(tab.memberArea().isOpaque());
		tab.select(22);
		final Color brindle = tab.chart() == null ? null : rowColour(p, 22);
		assertEquals(brindle, tab.tintedToward());
		assertTrue(tab.memberArea().isOpaque());
		assertEquals(SidePanelPartyTab.tinted(SidePanelStyle.PANEL, brindle, SidePanelPartyTab.AREA_TINT),
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
		assertEquals(SidePanelPartyTab.AREA_PAD, tab.memberArea().getInsets().top);
	}

	@Test
	public void theCardsOptionTintsTheirCardsAndYoursCanBeTintedToo()
	{
		SidePanelPartyTab.tint = SidePanelPartyTab.Tint.CARDS;
		SidePanelPartyTab.tintYours = true;
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		assertEquals(SidePanelParty.YOU, tab.tintedToward());
		tab.select(22);
		final Color card = SidePanelPartyTab.tinted(SidePanelStyle.CARD, rowColour(p, 22), SidePanelPartyTab.CARD_TINT);
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
			final double onArea = contrast(SidePanelStyle.MUTED, SidePanelPartyTab.tinted(SidePanelStyle.PANEL, m,
				SidePanelPartyTab.AREA_TINT));
			final double onCard = contrast(SidePanelStyle.MUTED, SidePanelPartyTab.tinted(SidePanelStyle.CARD, m,
				SidePanelPartyTab.CARD_TINT));
			assertTrue(m + " area " + onArea, onArea >= 4.0);
			assertTrue(m + " card " + onCard, onCard >= 4.3);
		}
	}

	// ---- 3. The title on top ----

	@Test
	public void theRoomsTitleIsOnTopAndAMembersPanelsStartAtTheTiles()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		final SidePanelFightTab title = tab.title();
		assertNotNull(title);
		// The title block alone, above the meter.
		final List<Component> order = Arrays.asList(tab.getComponents());
		assertTrue(order.indexOf(title) < order.indexOf(SidePanelPictures.sectionOf(tab, "party")));
		assertTrue(texts(title).toString(), texts(title).stream().anyMatch(x -> x.startsWith("Zebak")));
		assertTrue(texts(title).contains("2:09"));
		assertTrue(texts(title).toString(), texts(title).stream().anyMatch(x -> x.contains("900 HP")));
		assertNull(SidePanelPictures.sectionOf(title, "damage"));
		// Your panels below, and hers on a click: no title block, the tiles first.
		for (Long open : new Long[]{null, 22L})
		{
			if (open != null)
			{
				tab.select(open);
			}
			final SidePanelFightTab panels = tab.memberFight();
			assertFalse(texts(panels).stream().anyMatch(x -> x.startsWith("Zebak")));
			assertFalse(texts(panels).stream().anyMatch(x -> x.contains("900 HP")));
			assertTrue(texts(panels).contains("Luck"));
			assertTrue(texts(panels).contains("Damage/expected"));
			assertNotNull(SidePanelPictures.sectionOf(panels, "damage"));
		}
		// The meter's header: the party's DPS, the room's name and clock left to the title.
		assertTrue(texts(SidePanelPictures.sectionOf(tab, "party")).contains("party 79.1 dps"));
	}

	@Test
	public void theTitleClockIsSetInPlace()
	{
		final SidePanelPartyLagTest.Party party = new SidePanelPartyLagTest.Party();
		party.run(130);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, null));
		p.tabs().select(p.tabs().getTab(3));
		p.apply(party.partySnapshot());
		final SidePanelFightTab title = p.partyTab().title();
		final int builds = title.builds();
		for (int i = 0; i < 10; i++)
		{
			party.run(PvmPerformancePanel.UPDATE_TICKS);
			p.apply(party.partySnapshot());
		}
		assertSame(title, p.partyTab().title());
		assertEquals(builds, title.builds());
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
		p.partyTab().select(22);
		final SidePanelFightTab hers = p.partyTab().memberFight();
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
		return p.partyTab().meter() == null ? null : ((PanelSnapshot) p.shown()).party.row(id).colour;
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

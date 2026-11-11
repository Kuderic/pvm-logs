package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import org.junit.Test;

/**
 * T-206 (Eric, 2026-11-10 22:12: "it should all be contained in one colored section so the user knows what goes with
 * what"): every section is one card, folded or open, its title inside; an open body sits on the same ground under its
 * title, its own cards rows of this one; the hover lights the title strip alone. On every tab.
 */
public class SidePanelOneCardTest
{
	/** Every tab that has sections, settled at the panel's width. */
	private static Map<String, Container> tabs(boolean open)
	{
		final Map<String, Container> out = new LinkedHashMap<>();
		final PvmPerformancePanel fight = new PvmPerformancePanel(null, null, null, null);
		fight.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()), null,
			SidePanelParty.party(new SidePanelParty.Input())));
		out.put("Fight", fight.fightTab());
		final PvmPerformancePanel history = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		history.historyTab().refresh();
		out.put("History", history.historyTab());
		final PvmPerformancePanel trip = new PvmPerformancePanel(null, null, null, null);
		trip.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), SidePanelModel.trip(
			SidePanelExamples.trip())));
		out.put("Trip", trip.tripTab());
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel party = new PvmPerformancePanel(null, null, null, null);
		party.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		party.partySection().select(22L);
		out.put("Party and a member's panels", party.fightTab());
		for (Container c : out.values())
		{
			if (open)
			{
				SidePanelPictures.openAll(c);
			}
			settle((JComponent) c);
		}
		return out;
	}

	@Test
	public void anOpenSectionIsOneCardWithItsTitleInside()
	{
		int checked = 0;
		for (Map.Entry<String, Container> t : tabs(true).entrySet())
		{
			for (SidePanelSection s : all(t.getValue(), SidePanelSection.class))
			{
				final String at = t.getKey() + " " + s.id();
				if (s.isFolded())
				{
					continue;
				}
				checked++;
				// One opaque ground from the title to the last row.
				assertTrue(at, s.isOpaque());
				assertTrue(at + " " + s.getBackground(), SidePanelStyle.CARD.equals(s.getBackground())
					|| t.getKey().startsWith("Party"));
				final Component head = s.getComponent(0);
				// The title inside the card, at its padding.
				assertEquals(at, SidePanelStyle.CARD_PAD_H, s.strip().getInsets().left);
				assertEquals(at, 0, head.getX());
				// The body straight under the title: no gap of the panel's ground.
				assertEquals(at, head.getY() + head.getHeight(), s.body().getY());
				assertEquals(at, s.getHeight(), s.body().getY() + s.body().getHeight());
				// The body's own card ground is given up to the section's.
				assertFalse(at, cardGround(s.body()));
				for (Component k : s.body().getComponents())
				{
					assertFalse(at + " " + k.getClass().getSimpleName(), cardGround(k));
				}
			}
		}
		assertTrue(String.valueOf(checked), checked >= 15);
	}

	@Test
	public void aFoldedSectionIsOnACardWithOrWithoutItsLine()
	{
		int bare = 0;
		int lined = 0;
		for (Map.Entry<String, Container> t : tabs(false).entrySet())
		{
			for (SidePanelSection s : all(t.getValue(), SidePanelSection.class))
			{
				if (!s.isFolded())
				{
					s.toggle();
					settle((JComponent) t.getValue());
				}
				final String at = t.getKey() + " " + s.id();
				assertTrue(at, s.isOpaque());
				assertEquals(at, s.getComponent(0).getHeight(), s.getHeight());
				if (s.summary().isEmpty())
				{
					bare++;
					// The title alone on the card: the strip is the whole card.
					assertEquals(at, s.strip().getHeight(), s.getHeight());
				}
				else
				{
					lined++;
				}
			}
		}
		assertTrue("folded without a line: " + bare, bare >= 5);
		assertTrue("folded with a line: " + lined, lined >= 4);
	}

	@Test
	public void theHoverLightsOnlyTheTitleStrip()
	{
		for (boolean open : new boolean[]{false, true})
		{
			final Container fight = tabs(open).get("Fight");
			final SidePanelSection luck = SidePanelPictures.sectionOf(fight, "curve");
			assertNotNull(luck);
			assertEquals(!open, luck.isFolded());
			final Component head = luck.getComponent(0);
			final int layouts = SidePanelParts.relayouts;
			assertFalse(luck.strip().isOpaque());
			head.dispatchEvent(new MouseEvent(head, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false));
			assertTrue(luck.hovered());
			assertTrue(luck.strip().isOpaque());
			assertEquals(SidePanelStyle.CARD_HOVER, luck.strip().getBackground());
			// The rest of the card doesn't change: the card's ground, the head and the body see-through as before.
			assertEquals(SidePanelStyle.CARD, luck.getBackground());
			assertFalse(((JComponent) head).isOpaque());
			assertFalse(luck.body().isOpaque());
			// The strip is the title's row, not the line under it or the body.
			assertTrue(luck.strip().getHeight() < luck.getHeight());
			head.dispatchEvent(new MouseEvent(head, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false));
			assertFalse(luck.strip().isOpaque());
			// A repaint, never a layout (T-198).
			assertEquals(layouts, SidePanelParts.relayouts);
		}
	}

	@Test
	public void aBodyOfCardsBecomesRowsWithADividerBetween()
	{
		final Container fight = tabs(true).get("Fight");
		// Damage: its rows, then the Timeline on what was its own card, a line above it.
		final SidePanelSection damage = SidePanelPictures.sectionOf(fight, "damage");
		assertFalse(damage.dividers().isEmpty());
		final List<Integer> lines = damage.dividers();
		for (int y : lines)
		{
			assertTrue(y > damage.body().getY() && y < damage.getHeight());
		}
		// Party: the meter's card, then its Timeline's (T-205).
		final Container party = tabs(true).get("Party and a member's panels");
		assertFalse(SidePanelPictures.sectionOf(party, SidePanelPartySection.PARTY).dividers().isEmpty());
		// A body that was one card has no line in it.
		assertTrue(SidePanelPictures.sectionOf(fight, "budget").dividers().isEmpty());
		// Folded: none drawn.
		damage.toggle();
		assertTrue(damage.dividers().isEmpty());
	}

	@Test
	public void sectionsStackAsCardsUnderTheKillCard()
	{
		final Container fight = tabs(false).get("Fight");
		Component above = null;
		for (Component k : fight.getComponents())
		{
			if (!k.isVisible())
			{
				continue;
			}
			if (above != null && k instanceof SidePanelSection)
			{
				// Each a card of its own, the panel's gap between.
				assertEquals(above.getY() + above.getHeight() + SidePanelStyle.BLOCK_GAP, k.getY());
			}
			above = k;
		}
		assertTrue(fight.getComponent(0) instanceof SidePanelKillCard);
	}

	// ---- helpers ----

	private static boolean cardGround(Component k)
	{
		return k.isOpaque() && SidePanelStyle.CARD.equals(k.getBackground());
	}

	private static void settle(JComponent tab)
	{
		tab.setSize(SidePanelStyle.CONTENT_WIDTH, 10_000);
		SidePanelPictures.layout(tab);
		tab.setSize(SidePanelStyle.CONTENT_WIDTH, tab.getPreferredSize().height);
		SidePanelPictures.layout(tab);
	}

	private static <T> List<T> all(Container c, Class<T> type)
	{
		final List<T> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (type.isInstance(k))
			{
				out.add(type.cast(k));
			}
			if (k instanceof Container)
			{
				out.addAll(all((Container) k, type));
			}
		}
		return out;
	}
}

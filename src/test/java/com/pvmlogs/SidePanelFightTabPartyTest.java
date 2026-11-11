package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-176 (Eric, 2026-11-05: "rework the party tab to just be part of the fight tab and remove the party tab"): solo, the
 * Fight tab is exactly as before; in a party, the Party section sits between the title and your panels, and saved folds
 * carry under the Party tab's ids. T-205 (Eric, 22:01: "Party and party damage over time should match the way damage is
 * handled bundled into one. Also open party by default"): one Party section, its Timeline inside, open by default.
 */
public class SidePanelFightTabPartyTest
{
	private static List<String> sections(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				out.add(((SidePanelSection) k).id());
			}
			if (k instanceof Container)
			{
				out.addAll(sections((Container) k));
			}
		}
		return out;
	}

	@Test
	public void soloTheFightTabBuildsAsBefore()
	{
		// The same kill on a Fight tab that never had a party, and on one out of a party (an empty party read).
		final SidePanelFightTab alone = new SidePanelFightTab(SidePanelFightTab.NO_ACTIONS,
			new SidePanelSection.MemoryFolds());
		alone.apply(SidePanelModel.fight(SidePanelExamples.dead()));
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead()), null, SidePanelParty.party(
			new SidePanelParty.Input())));
		assertEquals("", p.partySection().placement());
		assertEquals(sections(alone), sections(p.fightTab()));
		assertEquals(alone.getComponentCount(), p.fightTab().getComponentCount());
		assertFalse(sections(p.fightTab()).contains(SidePanelPartySection.PARTY));
		// Nothing reserved: the party's blocks aren't in the tab at all.
		assertNull(p.partySection().top().getParent());
		assertNull(p.partySection().member().getParent());
	}

	@Test
	public void inAPartyThePartySitsBetweenTheTitleAndYourPanels()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final List<String> ids = sections(p.fightTab());
		assertEquals("|party", p.partySection().placement());
		assertTrue(ids.toString(), ids.indexOf(SidePanelPartySection.PARTY) < ids.indexOf("damage"));
		assertFalse(ids.toString(), ids.contains(SidePanelPartySection.PARTY_CHART));
		// Your own panels below, as solo: their sections after the party's are the solo tab's, in its order.
		final SidePanelFightTab solo = new SidePanelFightTab(SidePanelFightTab.NO_ACTIONS,
			new SidePanelSection.MemoryFolds());
		solo.apply(SidePanelPartyMemberViewTest.yours(in));
		assertEquals(sections(solo), ids.subList(1, ids.size()));
	}

	@Test
	public void onePartySectionWithItsTimelineInside()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final List<SidePanelSection> party = new ArrayList<>();
		for (Component k : p.partySection().top().getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				party.add((SidePanelSection) k);
			}
		}
		assertEquals(1, party.size());
		final SidePanelSection section = party.get(0);
		assertEquals("Party", section.title());
		// The meter, then the Timeline subhead and the party's chart, as Damage has its Timeline.
		final List<String> texts = texts(section);
		assertTrue(texts.toString(), texts.contains(SidePanelFightTab.TIMELINE));
		final SidePanelPartyChart chart = p.partySection().chart();
		assertNotNull(chart);
		assertTrue(javax.swing.SwingUtilities.isDescendingFrom(chart, section));
		assertTrue(javax.swing.SwingUtilities.isDescendingFrom(all(section, SidePanelPartyMeter.class).get(0), section));
		for (SidePanelSection s : all(p.fightTab(), SidePanelSection.class))
		{
			assertFalse(s.title(), s.title().equals("Party damage over time"));
		}
		// Before a fight of yours is on show: the members listed, no chart.
		final PvmPerformancePanel before = new PvmPerformancePanel(null, null, null, null);
		before.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.preAttack()), null,
			SidePanelParty.party(SidePanelPartyExamples.waitingWithMembers())));
		assertNotNull(SidePanelPictures.sectionOf(before.partySection().top(), SidePanelPartySection.PARTY));
		assertNull(before.partySection().chart());
		assertFalse(texts(before.partySection().top()).contains(SidePanelFightTab.TIMELINE));
	}

	@Test
	public void partyOpensByDefault()
	{
		// A fresh profile: open, Celebrations on or off.
		for (boolean celebrations : new boolean[]{true, false})
		{
			final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setCelebrations(celebrations);
			p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
			final SidePanelSection party = SidePanelPictures.sectionOf(p.partySection().top(),
				SidePanelPartySection.PARTY);
			assertFalse("open", party.isFolded());
			// Folded, it stays folded through a rebuild in new text settings.
			party.toggle();
			p.setText(true, false);
			try
			{
				assertTrue(SidePanelPictures.sectionOf(p.partySection().top(), SidePanelPartySection.PARTY).isFolded());
			}
			finally
			{
				p.setText(false, false);
			}
		}
		assertFalse(PvmPerformancePanel.FOLDED_UNDER_CARD.contains(SidePanelPartySection.PARTY));
		assertFalse(PvmPerformancePanel.FOLDED_BY_DEFAULT.contains(SidePanelPartySection.PARTY_CHART));
	}

	@Test
	public void aProfileThatSavedPartyFoldedOpensItOnce()
	{
		// T-188 to T-204 kept Party open only under party.opened: absent, it read folded. T-205 reads "party", which a
		// build before T-188 saved as folded here: opened once on this build, then remembered.
		final SidePanelSection.MemoryFolds stored = new SidePanelSection.MemoryFolds();
		stored.setFolded(SidePanelPartySection.PARTY, true);
		assertTrue(PvmPerformancePanel.reopenPartyOnce(stored));
		assertFalse(stored.isFolded(SidePanelPartySection.PARTY));
		// Folded again by hand: the next start leaves it folded.
		stored.setFolded(SidePanelPartySection.PARTY, true);
		assertFalse(PvmPerformancePanel.reopenPartyOnce(stored));
		assertTrue(stored.isFolded(SidePanelPartySection.PARTY));
		// Damage's own once is a key of its own.
		assertFalse(stored.isFolded(PvmPerformancePanel.DAMAGE_REOPENED));
	}

	@Test
	public void anOpenedPartyChartOpensPartyAndFoldingPartyWritesItToo()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		final SidePanelSection.Folds folds = p.fightFolds();
		assertFalse(folds.isFolded(SidePanelPartySection.PARTY));
		// Folding Party settles the old key as well: partyChart.opened goes with it.
		folds.setFolded(SidePanelPartySection.PARTY, true);
		assertTrue(folds.isFolded(SidePanelPartySection.PARTY));
		assertFalse(folds.isFolded(SidePanelPartySection.PARTY_CHART + PvmPerformancePanel.OPENED));
		folds.setFolded(SidePanelPartySection.PARTY, false);
		assertTrue(folds.isFolded(SidePanelPartySection.PARTY_CHART + PvmPerformancePanel.OPENED));
		// The party's Damage over time left open before T-205 (partyChart.opened) opens Party, folded or not.
		folds.setFolded(SidePanelPartySection.PARTY, true);
		folds.setFolded(SidePanelPartySection.PARTY_CHART + PvmPerformancePanel.OPENED, true);
		assertFalse(folds.isFolded(SidePanelPartySection.PARTY));
	}

	private static List<String> texts(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (SidePanelText t : all(c, SidePanelText.class))
		{
			out.add(t.getText());
		}
		return out;
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

	@Test
	public void aMemberLeavingPutsYourPanelsBack()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		p.partySection().select(22);
		assertEquals("|party|m22", p.partySection().placement());
		final SidePanelParty.Input gone = SidePanelPartyExamples.toaYouAndBrindle();
		gone.members.remove(2);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(gone)));
		assertEquals("|party", p.partySection().placement());
		assertNotNull(SidePanelPictures.sectionOf(p.fightTab(), "budget"));
		assertNull(p.partySection().member().getParent());
		// Out of the party altogether: as solo.
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(
			new SidePanelParty.Input())));
		assertEquals("", p.partySection().placement());
		assertNull(p.partySection().top().getParent());
	}
}

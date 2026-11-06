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
 * Fight tab is exactly as before; in a party, the meter and the party's Damage over time (folded by default) sit between
 * the title and the tiles, and saved folds carry under the Party tab's ids.
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
	public void inAPartyTheMeterAndChartSitBetweenTheTitleAndTheTiles()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final List<String> ids = sections(p.fightTab());
		assertEquals("|party", p.partySection().placement());
		assertTrue(ids.toString(), ids.indexOf(SidePanelPartySection.PARTY) < ids.indexOf(
			SidePanelPartySection.PARTY_CHART));
		assertTrue(ids.toString(), ids.indexOf(SidePanelPartySection.PARTY_CHART) < ids.indexOf("damage"));
		// Your own panels below, as solo: their sections after the party's are the solo tab's, in its order.
		final SidePanelFightTab solo = new SidePanelFightTab(SidePanelFightTab.NO_ACTIONS,
			new SidePanelSection.MemoryFolds());
		solo.apply(SidePanelPartyMemberViewTest.yours(in));
		assertEquals(sections(solo), ids.subList(2, ids.size()));
	}

	@Test
	public void thePartysChartIsFoldedByDefaultAndSavedFoldsCarry()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final SidePanelSection chart = SidePanelPictures.sectionOf(p.partySection().top(),
			SidePanelPartySection.PARTY_CHART);
		assertNotNull(chart);
		assertTrue("folded by default", chart.isFolded());
		assertFalse("the meter open", SidePanelPictures.sectionOf(p.partySection().top(), SidePanelPartySection.PARTY)
			.isFolded());
		// Opened, it stays open through a rebuild in new text settings (kept as partyChart.opened).
		chart.toggle();
		assertFalse(chart.isFolded());
		p.setText(true, false);
		try
		{
			assertFalse(SidePanelPictures.sectionOf(p.partySection().top(), SidePanelPartySection.PARTY_CHART).isFolded());
		}
		finally
		{
			p.setText(false, false);
		}
		// The fold store under the Party tab's ids: partyChart saved as folded before still reads folded, and the
		// meter's "party" carries as it was.
		final SidePanelSection.MemoryFolds stored = new SidePanelSection.MemoryFolds();
		stored.setFolded(SidePanelPartySection.PARTY, true);
		stored.setFolded(SidePanelPartySection.PARTY_CHART, true);
		assertTrue(PvmPerformancePanel.FOLDED_BY_DEFAULT.contains(SidePanelPartySection.PARTY_CHART));
		assertFalse(PvmPerformancePanel.FOLDED_BY_DEFAULT.contains(SidePanelPartySection.PARTY));
		assertFalse(stored.isFolded(SidePanelPartySection.PARTY_CHART + PvmPerformancePanel.OPENED));
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

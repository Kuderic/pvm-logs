package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * T-208 (Eric, 2026-11-10 22:28: "is there a way to show 3 numbers at the forefront ... dmg, luck, skill?"; "the 2
 * players in my party should be grouped as part of the '2 members' damage"; "Is there a way to tell which players are
 * in the fight with me?"): the party's rows show damage, luck and skill at a glance; members not running the plugin only
 * in the group row, among those in the fight; who wasn't there set apart. Invented names.
 */
public class SidePanelPartyRowsTest
{
	private static final long QUILLON = 51;

	private static SidePanelPartyMeter painted(PanelSnapshot.PartyTab t, String yourGrade)
	{
		final SidePanelPartyMeter m = new SidePanelPartyMeter(id -> { });
		m.setYourGrade(yourGrade, SidePanelStyle.ZONE_A);
		m.set(t.rows);
		m.setSize(m.getPreferredSize());
		final BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		m.paint(g);
		g.dispose();
		return m;
	}

	private static List<String> names(PanelSnapshot.PartyTab t)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			out.add(r.name);
		}
		return out;
	}

	@Test
	public void afterTheFightEachSharingRowShowsDamageATrackAndTheGrade()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.yamaDuoWithAway(false));
		final SidePanelPartyMeter m = painted(t, "A");
		for (long id : new long[]{SidePanelParty.YOU_ID, QUILLON})
		{
			final PanelSnapshot.PartyRow r = t.row(id);
			assertFalse(r.dmg[0].isEmpty());
			assertNotNull("a luck track on " + r.name, m.lastTrack(id));
			assertFalse(Double.isNaN(r.luckP));
		}
		// Your grade's chip; a member's grade isn't shared, so theirs is the Skill %.
		assertEquals("grade A", m.lastSkill(SidePanelParty.YOU_ID));
		assertEquals("93%", m.lastSkill(QUILLON));
		// The share is the bar; DPS, share, luck's words and skill on the hover.
		final String tip = Tips.plain(SidePanelPartyMeter.rowTip(t.row(SidePanelParty.YOU_ID), "A"));
		assertTrue(tip, tip.startsWith("You: 6,240 damage, "));
		assertTrue(tip, tip.contains(" dps."));
		assertTrue(tip, tip.contains("Luck: bottom 2% (29 s slower)."));
		assertTrue(tip, tip.contains("Skill: 98%, grade A."));
		// No grade from a card: your Skill % instead.
		assertEquals("98%", painted(t, null).lastSkill(SidePanelParty.YOU_ID));
	}

	@Test
	public void liveTheMarkerAndTheSkillSoFar()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.yamaDuoWithAway(true));
		assertFalse(t.after);
		// Live, no grade even with one from a card: the Skill % so far, as the card has it.
		final SidePanelPartyMeter m = painted(t, "A");
		assertEquals("96%", m.lastSkill(SidePanelParty.YOU_ID));
		assertEquals("90%", m.lastSkill(QUILLON));
		assertNotNull(m.lastTrack(SidePanelParty.YOU_ID));
		assertTrue(t.row(SidePanelParty.YOU_ID).live);
		final String tip = Tips.plain(SidePanelPartyMeter.rowTip(t.row(QUILLON), null));
		assertTrue(tip, tip.contains("so far"));
	}

	@Test
	public void membersNotRunningThePluginAreOnlyInTheGroupRow()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.yamaDuoWithAway(false));
		// T-216: one member, by name and picture, as an estimate.
		assertEquals(Arrays.asList("you", "Quillon", "Ash Kettle"), names(t));
		final PanelSnapshot.PartyRow group = t.rows.get(2);
		assertTrue(group.others);
		assertTrue(group.estimate);
		assertEquals(77L, group.id);
		assertTrue(group.dmg[0].startsWith(SidePanelParty.APPROX));
		// Its tooltip names who is in it and says the damage is estimated; the paragraph under the meter is gone.
		assertTrue(group.tip, group.tip.contains("Ash Kettle"));
		assertTrue(group.tip, group.tip.contains("estimated"));
		assertNull(t.othersNote);
		// Two in the fight: "2 members", which can't be split.
		final SidePanelParty.Input both = SidePanelPartyExamples.yamaDuoWithAway(false);
		both.inFight.add(78L);
		final PanelSnapshot.PartyTab two = SidePanelParty.party(both);
		assertEquals(Arrays.asList("you", "Quillon", "2 members"), names(two));
		assertTrue(two.rows.get(2).tip.contains("Ash Kettle and Old Tam"));
		assertTrue(two.rows.get(2).tip.contains("can't be split"));
		// Before a fight of yours is on show, they're listed by name as before (T-167).
		final PanelSnapshot.PartyTab waiting = SidePanelParty.party(SidePanelPartyExamples.waitingWithMembers());
		assertEquals(Arrays.asList("Moss Pike", "Brindle"), names(waiting));
		assertTrue(waiting.rows.get(0).listed);
	}

	@Test
	public void aMemberNotInTheFightIsSetApart()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.yamaDuoWithAway(false);
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		// Old Tam wasn't seen: out of the group and its tooltip, one muted line under the meter.
		assertFalse(names(t).contains("Old Tam"));
		assertFalse(t.rows.get(2).tip.contains("Old Tam"));
		assertEquals("not in this fight: 1", t.away);
		assertTrue(t.awayTip.contains("Old Tam"));
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, t));
		final SidePanelParts.Line line = p.partySection().awayLine();
		assertNotNull(line);
		assertTrue(Tips.plain(line.getToolTipText()).contains("Old Tam"));
		// Everyone there: no line.
		in.inFight.add(78L);
		assertNull(SidePanelParty.party(in).away);
		// Not known: as before, every member who hasn't shared counts as there.
		in.inFight = null;
		assertNull(SidePanelParty.party(in).away);
		assertTrue(names(SidePanelParty.party(in)).contains("2 members"));
	}

	@Test
	public void theSwitchSortsAndNeverChangesTheFigures()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.yamaDuoWithAway(false));
		final SidePanelPartyMeter m = painted(t, "A");
		final List<String> before = new ArrayList<>();
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			before.add(r.name + " " + Arrays.toString(r.dmg) + " " + r.luckP + " " + r.skill);
		}
		// By damage: you (6,240), Quillon, then the group.
		assertEquals(SidePanelParty.YOU_ID, m.shown().get(0).id);
		// By luck: Quillon's top 19% before your bottom 2%; the group last.
		m.setMetric(SidePanelParty.Metric.LUCK);
		assertEquals(QUILLON, m.shown().get(0).id);
		assertTrue(m.shown().get(2).others);
		// By skill: your 98% before Quillon's 93%.
		m.setMetric(SidePanelParty.Metric.EXEC);
		assertEquals(SidePanelParty.YOU_ID, m.shown().get(0).id);
		// The figures are the same whatever is picked.
		final List<String> after = new ArrayList<>();
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			after.add(r.name + " " + Arrays.toString(r.dmg) + " " + r.luckP + " " + r.skill);
		}
		assertEquals(before, after);
		// In the panel: picking luck sorts the meter, and the Timeline keeps charting damage.
		final SidePanelParty.Input in = SidePanelPartyExamples.yamaDuoWithAway(false);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final SidePanelPartyChart chart = p.partySection().chart();
		p.partySection().pick(SidePanelParty.Metric.LUCK);
		assertSame(chart, p.partySection().chart());
		assertEquals(QUILLON, p.partySection().meter().shown().get(0).id);
		assertEquals(SidePanelParty.Metric.LUCK, p.partySection().meter().metric());
		assertTrue(SidePanelPartySection.MetricSwitch.TIPS[1].startsWith("Sort the rows by luck"));
	}

	@Test
	public void aRepaintNotNewComponentsPerUpdate()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.yamaDuoWithAway(true);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final int rebuilds = p.partySection().rebuilds();
		final SidePanelPartyMeter meter = p.partySection().meter();
		for (int i = 0; i < 10; i++)
		{
			final SidePanelParty.Input later = SidePanelPartyExamples.yamaDuoWithAway(true);
			later.members.get(0).figures.p = 0.3 + i * 0.02;
			p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(later), null, SidePanelParty.party(later)));
		}
		assertEquals(rebuilds, p.partySection().rebuilds());
		assertSame(meter, p.partySection().meter());
	}
}

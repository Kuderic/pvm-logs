package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-167 (Eric, 2026-11-04: "Party member is not showing up … still list them even if they arent running luck vs
 * skill"): party members with nothing on the board are listed by name and picture. One takes the others row as about
 * theirs; two or more are listed without a share, with one combined row; out of a fight they're just listed.
 */
public class SidePanelPartyNotSharingTest
{
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
	public void aDuoWithOneSharerTakesOthersAsAboutThePartnersDamage()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.yamaDuoNotSharing();
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		// The meter shows though nobody else shares: you, then your partner.
		assertTrue(t.sharing);
		assertEquals(Arrays.asList("you", "Ash Kettle"), names(t));
		final PanelSnapshot.PartyRow partner = t.rows.get(1);
		assertEquals(77, partner.id);
		assertSame(in.unshared.get(0).avatar, partner.avatar);
		// Others' damage, labelled about, its value marked as an estimate; no click (no figures of theirs).
		assertTrue(partner.others);
		assertFalse(partner.listed);
		assertEquals(SidePanelParty.ABOUT, partner.hint);
		assertEquals("~9,870", partner.dmg[0]);
		assertEquals("47%", partner.dmg[2]);
		assertTrue(partner.person());
		// The note and the tooltip say what it holds.
		assertEquals(SidePanelParty.aboutOne("Ash Kettle"), t.othersNote);
		assertEquals(t.othersNote, partner.tip);
		assertTrue(partner.tip.contains("not running Luck vs Skill"));
		assertTrue(partner.tip.contains("Players outside the party"));
		assertTrue(partner.tip.contains("thralls"));
		// Your row is as it was.
		assertEquals("53%", t.rows.get(0).dmg[2]);
		// Not selectable: the selection stays on you.
		assertNull(t.row(77));
	}

	@Test
	public void aTrioWithOneSharerListsBothAndOneCombinedRow()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.trioTwoNotSharing());
		assertEquals(Arrays.asList("you", "Moss Pike", "Brindle", "2 members"), names(t));
		for (int i = 1; i <= 2; i++)
		{
			final PanelSnapshot.PartyRow r = t.rows.get(i);
			// Listed without a share: no values, no bar, no line on the chart.
			assertTrue(r.listed);
			assertEquals(SidePanelParty.NOT_RUNNING, r.hint);
			assertEquals(Arrays.asList("", "", ""), Arrays.asList(r.dmg));
			assertEquals(0, r.fill, 0);
			assertEquals(0, r.seriesTicks.length);
		}
		final PanelSnapshot.PartyRow combined = t.rows.get(3);
		assertEquals(PanelSnapshot.PartyRow.OTHERS_ID, combined.id);
		assertEquals(SidePanelParty.ABOUT, combined.hint);
		assertEquals("~27.4k", combined.dmg[0]);
		assertNull(combined.avatar);
		assertEquals(SidePanelParty.aboutMany(2), t.othersNote);
		assertTrue(t.othersNote.contains("can't be split"));
	}

	@Test
	public void besideSharingMembersTheOneWhoDoesntTakesOthersAndTheFightLineAgrees()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.toaOneNotSharing());
		assertEquals(Arrays.asList("Moss Pike", "you", "Brindle", "Old Tam"), names(t));
		assertEquals("~1,830", t.rows.get(3).dmg[0]);
		// T-167 part 3: the Fight tab's line counts the members who share, as the tab ranks them.
		assertEquals("you 2nd of 3 · 29%", SidePanelPartyExamples.fightLine(t));
		final PanelSnapshot.PartyTab before = SidePanelParty.party(SidePanelPartyExamples.toa());
		assertEquals(SidePanelPartyExamples.fightLine(before), SidePanelPartyExamples.fightLine(t));
	}

	@Test
	public void aMemberWhoStartsSharingMovesFromTheListToAFullRow()
	{
		final List<SidePanelParty.Unshared> party = Arrays.asList(new SidePanelParty.Unshared(1, "Me", null),
			new SidePanelParty.Unshared(81, "Moss Pike", null), new SidePanelParty.Unshared(82, "Brindle", null));
		// You (and the loopback's echo, which has your id) are never listed.
		assertEquals(Arrays.asList(81L, 82L), ids(SidePanelParty.unshared(party, 1, Collections.<Long>emptySet())));
		// Moss Pike's first update reaches the board: she's no longer listed.
		final List<SidePanelParty.Unshared> left = SidePanelParty.unshared(party, 1, new HashSet<>(Arrays.asList(81L)));
		assertEquals(Collections.singletonList(82L), ids(left));

		final SidePanelParty.Input in = SidePanelPartyExamples.trioTwoNotSharing();
		in.unshared.clear();
		in.unshared.addAll(left);
		in.members.add(SidePanelPartyExamples.member(81, "Moss Pike", false, 0, SidePanelPartyExamples.figures("Nex",
			true, 9_100, 9_000, 420, 0.5, 0, 0.9, 20), 420));
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		assertEquals(Arrays.asList("you", "Moss Pike", "Brindle"), names(t));
		// A full row: clickable, with her figures.
		assertFalse(t.rows.get(1).others);
		assertNotNull(t.row(81));
		assertEquals("9,100", t.rows.get(1).dmg[0]);
		// Brindle is the one left: others is about hers now.
		assertEquals(SidePanelParty.ABOUT, t.rows.get(2).hint);
		assertEquals(82, t.rows.get(2).id);
	}

	@Test
	public void aMemberWhoLeavesGoes()
	{
		final List<SidePanelParty.Unshared> party = Collections.singletonList(new SidePanelParty.Unshared(1, "Me", null));
		assertTrue(SidePanelParty.unshared(party, 1, Collections.<Long>emptySet()).isEmpty());
		final SidePanelParty.Input in = SidePanelPartyExamples.yamaDuoNotSharing();
		in.unshared.clear();
		in.partySize = 1;
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		// Nobody else in the party: no meter, no name.
		assertFalse(t.sharing);
		assertTrue(t.rows.isEmpty());
	}

	@Test
	public void outOfAFightTheyreListedUnderTheLine()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.waitingWithMembers());
		assertFalse(t.sharing);
		assertEquals(SidePanelParty.waiting(3), t.empty);
		assertEquals(Arrays.asList("Moss Pike", "Brindle"), names(t));
		assertEquals(SidePanelParty.NO_FIGURES_YET, t.rows.get(0).hint);
		assertTrue(t.structure().contains("listed2"));
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(null, null, t));
		assertNotNull(p.partySection().meter());
		// Out of a party, nothing is listed.
		final SidePanelParty.Input out = SidePanelPartyExamples.waitingWithMembers();
		out.inParty = false;
		assertTrue(SidePanelParty.party(out).rows.isEmpty());
	}

	@Test
	public void nothingRebuildsOnATickWhereNoneOfThemChanged()
	{
		final java.awt.image.BufferedImage picture = SidePanelPartyExamples.picture(6, 'A');
		final List<Object> key = PvmPerformancePanel.partyState(true, 42, Arrays.asList(1L, 77L), Arrays.<Object>asList(
			PvmPerformancePanel.look(null, "Me"), PvmPerformancePanel.look(picture, "Ash Kettle")));
		final List<Object> same = PvmPerformancePanel.partyState(true, 42, Arrays.asList(1L, 77L), Arrays.<Object>asList(
			PvmPerformancePanel.look(null, "Me"), PvmPerformancePanel.look(picture, "Ash Kettle")));
		assertEquals(key, same);
		// In a fight, an unchanged key waits for the next snapshot (every 5 ticks); out of one, the equal key stops it.
		assertFalse(PvmPerformancePanel.due(false, true, key.equals(same), true, 1));
		// A picture or a name arriving later is a new key.
		assertNotEquals(key, PvmPerformancePanel.partyState(true, 42, Arrays.asList(1L, 77L), Arrays.<Object>asList(
			PvmPerformancePanel.look(null, "Me"), PvmPerformancePanel.look(null, "Ash Kettle"))));
		assertNotEquals(key, PvmPerformancePanel.partyState(true, 42, Arrays.asList(1L, 77L), Arrays.<Object>asList(
			PvmPerformancePanel.look(null, "Me"), PvmPerformancePanel.look(picture, null))));
		// Out of a party, the key is as T-129's.
		assertEquals(PvmPerformancePanel.partyState(false, 0, Collections.<Long>emptyList()),
			PvmPerformancePanel.partyState(false, 0, Collections.<Long>emptyList(), Collections.emptyList()));

		// The tab: the same snapshot again builds nothing; a picture arriving repaints the meter, no rebuild.
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.yamaDuoNotSharing())));
		final int built = p.partySection().rebuilds();
		for (int i = 0; i < 5; i++)
		{
			p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.yamaDuoNotSharing())));
		}
		final SidePanelParty.Input noPicture = SidePanelPartyExamples.yamaDuoNotSharing();
		noPicture.unshared.set(0, new SidePanelParty.Unshared(77, "Ash Kettle", null));
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(noPicture)));
		assertEquals(built, p.partySection().rebuilds());
		// Out of a fight too.
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.waitingWithMembers())));
		final int waiting = p.partySection().rebuilds();
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.waitingWithMembers())));
		assertEquals(waiting, p.partySection().rebuilds());
	}

	@Test
	public void namesAreNeverCutAndHintsOnlyShorten()
	{
		final List<String> problems = new ArrayList<>();
		final SidePanelParty.Input[] inputs = {SidePanelPartyExamples.yamaDuoNotSharing(),
			SidePanelPartyExamples.trioTwoNotSharing(), SidePanelPartyExamples.toaOneNotSharing(),
			SidePanelPartyExamples.waitingWithMembers()};
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					for (SidePanelParty.Input in : inputs)
					{
						final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
						p.setText(larger, smooth);
						try
						{
							final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
							p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, t));
							SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
							final SidePanelPartyMeter m = p.partySection().meter();
							final String where = larger + " " + smooth + " " + scale + " " + t.rows.size() + " rows: ";
							if (m.cutNames() > 0)
							{
								problems.add(where + m.cutNames() + " names cut");
							}
							for (PanelSnapshot.PartyRow r : t.rows)
							{
								final String drawn = m.lastHint(r.id);
								if (r.hint != null && drawn != null && !drawn.equals(r.hint) && !r.hintShort.contains(drawn))
								{
									problems.add(where + r.name + "'s hint drawn as " + drawn);
								}
							}
							// A listed member's hint keeps at least its short form at Normal, 1x.
							if (!larger && !smooth && scale == 1)
							{
								for (PanelSnapshot.PartyRow r : t.rows)
								{
									if (r.listed && "".equals(m.lastHint(r.id)))
									{
										problems.add(where + r.name + "'s hint dropped at Normal");
									}
								}
							}
						}
						finally
						{
							SidePanelStyle.setText(false, false);
						}
					}
				}
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void theMetersTooltipSaysWhatEachRowIs()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.trioTwoNotSharing());
		final SidePanelPartyMeter m = new SidePanelPartyMeter(id -> { });
		m.set(t.rows);
		final int rh = SidePanelPartyMeter.rowHeight();
		assertTrue(tip(m, 1 * rh + 2).contains("not running Luck vs Skill"));
		assertTrue(tip(m, 1 * rh + 2).startsWith("Moss Pike"));
		assertEquals(SidePanelParty.aboutMany(2), tip(m, 3 * rh + 2));
		// The plain others row keeps its note.
		final PanelSnapshot.PartyTab toa = SidePanelParty.party(SidePanelPartyExamples.toa());
		m.set(toa.rows);
		assertEquals(SidePanelParty.OTHERS_NOTE, tip(m, (toa.rows.size() - 1) * rh + 2));
	}

	private static String tip(SidePanelPartyMeter m, int y)
	{
		return Tips.plain(m.getToolTipText(new java.awt.event.MouseEvent(m, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, 5,
			y, 0, false)));
	}

	private static List<Long> ids(List<SidePanelParty.Unshared> members)
	{
		final List<Long> out = new ArrayList<>();
		for (SidePanelParty.Unshared m : members)
		{
			out.add(m.id);
		}
		return out;
	}
}

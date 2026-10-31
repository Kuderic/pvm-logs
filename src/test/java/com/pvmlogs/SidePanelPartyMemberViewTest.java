package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-128 on the Party tab: a member opened is their Fight tab, drawn by the Fight tab's own component (live, with a line
 * on what comes after their room, and the party chart under it); Discord pictures beside the names, the colour
 * otherwise; nothing rebuilt on a hover; everything fits.
 */
public class SidePanelPartyMemberViewTest
{
	private static PvmPerformancePanel panel(SidePanelParty.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, SidePanelParty.party(in)));
		p.tabs().select(p.tabs().getTab(3));
		return p;
	}

	/** T-130: one page, your Fight panels under the party by default, named. */
	@Test
	public void theDefaultIsYourFightPanelsUnderTheParty()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		assertEquals(SidePanelParty.YOU_ID, tab.selected());
		assertNotNull(tab.meter());
		assertNotNull(tab.chart());
		assertEquals("you · Fight panels", tab.heading().left.getText());
		assertNotNull(tab.memberFight());
		assertNotNull(SidePanelPictures.sectionOf(tab.memberFight(), "budget"));
		// No line faded for you.
		assertEquals(SidePanelPartyTab.NONE, tab.chart().focus());
		// The meter and chart come first, then the heading, then the panels.
		final List<Component> order = new ArrayList<>(Arrays.asList(tab.getComponents()));
		assertTrue(order.indexOf(SidePanelPictures.sectionOf(tab, "party")) < order.indexOf(SidePanelPictures.sectionOf(tab,
			"partyChart")));
	}

	@Test
	public void selectingAMemberSwitchesThePanelsInPlaceAndNotTheMeter()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		final int built = tab.rebuilds();
		final int members = tab.memberRebuilds();
		final SidePanelPartyMeter meter = tab.meter();
		final SidePanelPartyChart chart = tab.chart();
		tab.select(22);
		// Only the member's part is built again.
		assertEquals(built, tab.rebuilds());
		assertEquals(members + 1, tab.memberRebuilds());
		assertSame(meter, tab.meter());
		assertSame(chart, tab.chart());
		// Their row outlined, their line bright, their name above their panels.
		assertEquals(22, meter.selected());
		assertEquals(22, tab.chart().focus());
		assertEquals("Brindle · Fight panels", tab.heading().left.getText());
		final SidePanelFightTab fight = tab.memberFight();
		assertNotNull(fight);
		for (String id : new String[]{"damage", "curve", "budget", "execution", "chart", "ticks"})
		{
			assertNotNull(id, SidePanelPictures.sectionOf(fight, id));
		}
		// Again: back to you.
		tab.select(22);
		assertEquals(SidePanelParty.YOU_ID, tab.selected());
		assertEquals(SidePanelPartyTab.NONE, tab.chart().focus());
		assertEquals("you · Fight panels", tab.heading().left.getText());
		assertEquals(built, tab.rebuilds());
	}

	@Test
	public void aMemberLeavingOrANewPartyGoesBackToYou()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartyTab tab = p.partyTab();
		tab.select(22);
		final SidePanelParty.Input without = SidePanelPartyExamples.toaYouAndBrindle();
		without.members.remove(2);
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(without)));
		assertEquals(SidePanelParty.YOU_ID, tab.selected());
		tab.select(21);
		final SidePanelParty.Input other = SidePanelPartyExamples.toaYouAndBrindle();
		other.partyKey = 99;
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(other)));
		assertEquals(SidePanelParty.YOU_ID, tab.selected());
	}

	@Test
	public void midFightAMembersPanelsAreTheLiveSubsetWithTheLine()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.tobQuillonLive());
		final SidePanelPartyTab tab = p.partyTab();
		tab.select(31);
		final SidePanelFightTab fight = tab.memberFight();
		assertNotNull(fight);
		assertNotNull(SidePanelPictures.sectionOf(fight, "damage"));
		assertNotNull(SidePanelPictures.sectionOf(fight, "execution"));
		assertNull(SidePanelPictures.sectionOf(fight, "budget"));
		assertNotNull(tab.chart());
		boolean note = false;
		for (Component c : all(tab))
		{
			note |= c instanceof SidePanelParts.Paragraph && SidePanelMemberFight.LATER_LIVE.equals(
				((SidePanelParts.Paragraph) c).text());
		}
		assertTrue(note);
	}

	@Test
	public void withoutTheirRoomMessageTheCompactBreakdownShowsInline()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toa());
		final SidePanelPartyTab tab = p.partyTab();
		tab.select(22);
		assertNull(tab.memberFight());
		assertEquals("Brindle · Fight panels", tab.heading().left.getText());
		assertNotNull(tab.meter());
		assertNotNull(tab.chart());
	}

	@Test
	public void picturesBesideTheNamesAndTheColourWithoutOne()
	{
		final PvmPerformancePanel with = panel(SidePanelPartyExamples.toaWithPictures());
		SidePanelPictures.draw(with.tabs(), with.partyTab(), 1);
		// Two pictures, each scaled once; Brindle's colour stands in for hers.
		assertEquals(2, with.partyTab().meter().scaledPictures());
		SidePanelPictures.draw(with.tabs(), with.partyTab(), 1);
		assertEquals(2, with.partyTab().meter().scaledPictures());
		assertTrue(SidePanelParty.party(SidePanelPartyExamples.toaWithPictures()).avatars());
		final PvmPerformancePanel without = panel(SidePanelPartyExamples.toa());
		SidePanelPictures.draw(without.tabs(), without.partyTab(), 1);
		assertEquals(0, without.partyTab().meter().scaledPictures());
		assertFalse(SidePanelParty.party(SidePanelPartyExamples.toa()).avatars());
		// Your picture on the Fight tab's line, and none without one.
		final SidePanelModel.Input kill = SidePanelExamples.dead();
		kill.partyLine = "you 2nd of 3 · 29%";
		kill.partyAvatar = SidePanelPartyExamples.picture(1, 'Y');
		assertSame(kill.partyAvatar, SidePanelModel.fight(kill).partyAvatar);
	}

	@Test
	public void aPictureArrivingLaterIsARepaintNotARebuild()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toa());
		final SidePanelPartyTab tab = p.partyTab();
		final int built = tab.rebuilds();
		final SidePanelPartyMeter meter = tab.meter();
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.toaWithPictures())));
		assertEquals(built, tab.rebuilds());
		assertSame(meter, tab.meter());
	}

	@Test
	public void noRebuildOnAHoverOrTheSameShapeWithAMemberOpen()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.tobQuillonLive());
		final SidePanelPartyTab tab = p.partyTab();
		tab.select(31);
		final int built = tab.rebuilds();
		final SidePanelFightTab fight = tab.memberFight();
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.tobQuillonLive())));
		assertEquals(built, tab.rebuilds());
		assertSame(fight, tab.memberFight());
		assertTrue(tab.chart().setHover(30));
		assertEquals(built, tab.rebuilds());
	}

	/** A member's views and the meter with pictures, in every text setting at 1× and 2×: nothing cut. */
	@Test
	public void everythingFitsAtEverySize()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					for (Object[] c : new Object[][]{{SidePanelPartyExamples.toaBrindleAfter(), 22L},
						{SidePanelPartyExamples.toaYouAndBrindle(), null}, {SidePanelPartyExamples.cox(), 51L},
						{SidePanelPartyExamples.tobQuillonLive(), 31L}, {SidePanelPartyExamples.toaWithPictures(), null},
						{SidePanelPartyExamples.withEcho(), null}})
					{
						final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
						p.setText(larger, smooth);
						try
						{
							p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null,
								SidePanelParty.party((SidePanelParty.Input) c[0])));
							p.tabs().select(p.tabs().getTab(3));
							if (c[1] != null)
							{
								p.partyTab().select((Long) c[1]);
							}
							SidePanelPictures.draw(p.tabs(), p.partyTab(), scale);
							final String at = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale + "× "
								+ ((SidePanelParty.Input) c[0]).scope + ": ";
							for (Component k : all(p.partyTab()))
							{
								if (k instanceof SidePanelText && ((SidePanelText) k).lastShown() != null)
								{
									final SidePanelText t = (SidePanelText) k;
									assertFalse(at + t.getText() + " => " + t.lastShown(),
										t.lastShown().contains(LedgerRows.CUT) && !fightTabsOwn(t, p.partyTab()));
								}
							}
							if (p.partyTab().meter() != null)
							{
								assertEquals(at + "names cut", 0, p.partyTab().meter().cutNames());
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
	}

	/**
	 * A text inside a member's Fight tab that the Fight tab itself cuts at that size (the open questions' weapon names
	 * in Smooth): the Fight tab's own fit tests hold those, not this one.
	 */
	private static boolean fightTabsOwn(SidePanelText t, SidePanelPartyTab tab)
	{
		final SidePanelFightTab fight = tab.memberFight();
		for (Component c = t; c != null; c = c.getParent())
		{
			if (c == fight)
			{
				return true;
			}
		}
		return false;
	}

	/** Names are the party's and never written; the room message carries none. */
	@Test
	public void namesAreNeverSaved() throws IOException
	{
		final Path src = Paths.get("src/main/java/com/pvmlogs");
		for (String file : new String[]{"SidePanelMemberFight.java", "LuckVsSkillRoom.java"})
		{
			final String s = new String(Files.readAllBytes(src.resolve(file)), StandardCharsets.UTF_8);
			for (String forbidden : new String[]{"Filepath", "java.io.", "java.nio.file", "saveHistory", "csv",
				"ConfigManager", "getMemberById", "getLocalMember"})
			{
				assertFalse(file + " " + forbidden, s.contains(forbidden));
			}
			// A party member (not the message's PartyMemberMessage) is never read here.
			assertFalse(file, s.matches("(?s).*\\bPartyMember\\b.*"));
		}
		// The room message's fields: no name of a player among them.
		for (java.lang.reflect.Field f : LuckVsSkillRoom.class.getDeclaredFields())
		{
			assertFalse(f.getName(), f.getName().toLowerCase().contains("player") || f.getName().toLowerCase()
				.contains("member"));
		}
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
}

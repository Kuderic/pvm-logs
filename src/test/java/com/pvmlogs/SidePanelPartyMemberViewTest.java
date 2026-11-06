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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-128, on the Fight tab since T-176: a member opened is their Fight tab, drawn by the Fight tab's own component (live, with a line
 * on what comes after their room, and the party chart under it); Discord pictures beside the names, the colour
 * otherwise; nothing rebuilt on a hover; everything fits.
 */
public class SidePanelPartyMemberViewTest
{
	private static PvmPerformancePanel panel(SidePanelParty.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(yours(in), null, SidePanelParty.party(in)));
		return p;
	}

	/** T-176: your own Fight panels, the Fight tab's: the example's where it has them, else a kill of yours. */
	static PanelSnapshot.FightTab yours(SidePanelParty.Input in)
	{
		final PanelSnapshot.FightTab f = in.members.isEmpty() ? null : in.members.get(0).fight;
		return f != null ? f : SidePanelModel.fight(SidePanelExamples.dead());
	}

	/** T-176: {@code c}'s place among the Fight tab's own children (the child it sits in); -1 for none. */
	static int place(SidePanelFightTab tab, Component c)
	{
		for (Component k = c; k != null; k = k.getParent())
		{
			if (k.getParent() == tab)
			{
				return Arrays.asList(tab.getComponents()).indexOf(k);
			}
		}
		return -1;
	}

	/**
	 * T-130, T-176: one page, the Fight tab: the switch and the title, the party (the meter, then its Damage over time),
	 * then your own tiles and panels, as solo. No heading for yours: nobody's selected.
	 */
	@Test
	public void theDefaultIsYourFightPanelsUnderTheParty()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		final SidePanelFightTab fight = p.fightTab();
		assertEquals(SidePanelParty.YOU_ID, tab.selected());
		assertNotNull(tab.meter());
		assertNotNull(tab.chart());
		assertNull(tab.heading());
		assertNull(tab.memberFight());
		// Your panels are the Fight tab's own, after the party.
		final SidePanelSection budget = SidePanelPictures.sectionOf(fight, "budget");
		assertNotNull(budget);
		assertTrue(place(fight, budget) > place(fight, tab.top()));
		// No line faded for you.
		assertEquals(SidePanelPartySection.NONE, tab.chart().focus());
		// The meter, then the party's chart, inside the party's block, before the tiles.
		final List<Component> order = new ArrayList<>(Arrays.asList(tab.top().getComponents()));
		assertTrue(order.indexOf(SidePanelPictures.sectionOf(tab.top(), "party")) < order.indexOf(
			SidePanelPictures.sectionOf(tab.top(), "partyChart")));
		assertTrue(place(fight, tab.top()) < place(fight, SidePanelPictures.sectionOf(fight, "damage")));
	}

	@Test
	public void selectingAMemberSwitchesThePanelsInPlaceAndNotTheMeter()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
		final int built = tab.rebuilds();
		final int members = tab.memberRebuilds();
		final int fightBuilds = p.fightTab().builds();
		final SidePanelPartyMeter meter = tab.meter();
		final SidePanelPartyChart chart = tab.chart();
		tab.select(22);
		// Only the member's part is built again, and the Fight tab places it where yours were, once.
		assertEquals(built, tab.rebuilds());
		assertEquals(members + 1, tab.memberRebuilds());
		assertEquals(fightBuilds + 1, p.fightTab().builds());
		assertSame(meter, tab.meter());
		assertSame(chart, tab.chart());
		// Yours out of the way: the only Where the time went on the tab is hers, in the member's part.
		assertEquals(place(p.fightTab(), tab.member()), place(p.fightTab(), SidePanelPictures.sectionOf(p.fightTab(),
			"budget")));
		// Their row outlined, their line bright, their name above their panels.
		assertEquals(22, meter.selected());
		assertEquals(22, tab.chart().focus());
		assertEquals("Brindle", tab.heading().left.getText());
		final SidePanelFightTab fight = tab.memberFight();
		assertNotNull(fight);
		for (String id : new String[]{"damage", "curve", "budget", "execution", "chart", "ticks"})
		{
			assertNotNull(id, SidePanelPictures.sectionOf(fight, id));
		}
		// Again: back to you: your own panels, no heading.
		tab.select(22);
		assertEquals(SidePanelParty.YOU_ID, tab.selected());
		assertEquals(SidePanelPartySection.NONE, tab.chart().focus());
		assertNull(tab.heading());
		assertNull(tab.memberFight());
		assertEquals(-1, place(p.fightTab(), tab.member()));
		assertNotNull(SidePanelPictures.sectionOf(p.fightTab(), "budget"));
		assertEquals(built, tab.rebuilds());
		assertEquals(fightBuilds + 2, p.fightTab().builds());
	}

	/**
	 * T-130 review, T-176: the real flow. The panel builds every member's panels (a room's once per message); yours are
	 * the Fight tab's own, built with every snapshot. A click on Brindle shows hers at once, and the next snapshot changes
	 * nothing of them; a click back shows yours, and the next snapshot updates them in place. One member rebuild per
	 * click, and never the compact breakdown in between.
	 */
	@Test
	public void aClickShowsTheirPanelsAtOnceNeverTheBreakdownFirst()
	{
		final SidePanelMemberFight.Panels panels = new SidePanelMemberFight.Panels();
		final LuckVsSkillRoom hersSent = SidePanelPartyExamples.brindlesMessage();
		final PvmPerformancePanel p = panel(asThePanelBuilds(panels, hersSent));
		final SidePanelPartySection tab = p.partySection();
		assertNull(tab.memberFight());
		final int members = tab.memberRebuilds();

		tab.select(22);
		assertEquals(members + 1, tab.memberRebuilds());
		final SidePanelFightTab hers = tab.memberFight();
		assertNotNull("no compact breakdown after the click", hers);
		// The next snapshot: hers are the same panels.
		p.apply(new PanelSnapshot(SidePanelPartyExamples.toaYourPanels(), null, SidePanelParty.party(asThePanelBuilds(
			panels, hersSent))));
		assertEquals(members + 1, tab.memberRebuilds());
		assertSame(hers, tab.memberFight());
		// Her room message made into panels once, however many snapshots carried it.
		assertEquals(1, panels.builds());

		tab.select(22);
		assertEquals(members + 2, tab.memberRebuilds());
		assertNull("your own panels at once", tab.memberFight());
		assertNull(tab.heading());
		// And the next snapshot: yours, in place.
		final int built = p.fightTab().builds();
		p.apply(new PanelSnapshot(SidePanelPartyExamples.toaYourPanels(), null, SidePanelParty.party(asThePanelBuilds(
			panels, hersSent))));
		assertEquals(members + 2, tab.memberRebuilds());
		assertEquals(built, p.fightTab().builds());
		assertEquals(1, panels.builds());
	}

	/**
	 * The board's rows with panels attached as {@code PvmPerformancePanel.readParty} attaches them: every member's from
	 * what the board holds for them (Brindle's room message; Moss Pike's isn't in). Yours are the Fight tab's (T-176).
	 */
	private static SidePanelParty.Input asThePanelBuilds(SidePanelMemberFight.Panels panels, LuckVsSkillRoom hersSent)
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaRows();
		in.members.get(0).fight = SidePanelPartyExamples.toaYourPanels();
		for (SidePanelParty.Member m : in.members)
		{
			if (!m.you)
			{
				panels.attach(m, m.id == 22 ? java.util.Collections.singletonMap(m.figures.roomKey(), hersSent)
					: java.util.Collections.<String, LuckVsSkillRoom>emptyMap(), SidePanelPartyExamples::itemName);
			}
		}
		return in;
	}

	/** T-130 review: a member's panels name them where yours say "you" and "yours". */
	@Test
	public void aMembersPanelsNameThem()
	{
		final PanelSnapshot.FightTab hers = SidePanelPartyExamples.toaYouAndBrindle().members.get(2).fight;
		assertTrue(hers.damage.hint.text, hers.damage.hint.text.endsWith(" Brindle's"));
		assertTrue(hers.chart.youLegend, hers.chart.youLegend.startsWith("Brindle "));
		final PanelSnapshot.FightTab mine = SidePanelPartyExamples.toaYouAndBrindle().members.get(0).fight;
		assertTrue(mine.damage.hint.text, mine.damage.hint.text.endsWith(" yours"));
		assertTrue(mine.chart.youLegend, mine.chart.youLegend.startsWith("you "));
		assertEquals("Jess'", SidePanelModel.possessive("Jess"));
		assertEquals("Brindle's", SidePanelModel.possessive("Brindle"));
		// Mid-fight too: the Damage card of Quillon's live panels.
		final PanelSnapshot.FightTab his = SidePanelPartyExamples.tobQuillonLive().members.get(1).fight;
		assertTrue(his.damage.hint.text, his.damage.hint.text.endsWith(" Quillon's"));
		// Nothing on show in their panels says "you" or "yours" (the units note's tooltip speaks to the reader).
		for (Object[] c : new Object[][]{{SidePanelPartyExamples.toaYouAndBrindle(), 22L},
			{SidePanelPartyExamples.tobQuillonLive(), 31L}})
		{
			final PvmPerformancePanel p = panel((SidePanelParty.Input) c[0]);
			p.partySection().select((Long) c[1]);
			SidePanelPictures.draw(p.tabs(), p.fightTab(), 1);
			for (Component k : all(p.partySection().memberFight()))
			{
				final String text = k instanceof SidePanelText ? ((SidePanelText) k).getText()
					: k instanceof SidePanelParts.Paragraph ? ((SidePanelParts.Paragraph) k).text() : "";
				assertFalse(text, text.toLowerCase().matches(".*\\byours?\\b.*"));
			}
		}
		final SidePanelModel.WeaponRow w = new SidePanelModel.WeaponRow();
		w.name = "Bow of faerdhinen";
		w.attacks = 10;
		w.damage = 200;
		assertTrue(SidePanelModel.weaponTooltip(w, "100%", null, null, false, "Brindle").contains("of Brindle's damage"));
	}

	/** T-130 review: each row's panels are its own fight's, as its meter row (the renders had them disagree). */
	@Test
	public void eachRowsPanelsAreItsOwnFight()
	{
		for (SidePanelParty.Input in : new SidePanelParty.Input[]{SidePanelPartyExamples.toaYouAndBrindle(),
			SidePanelPartyExamples.tobQuillonLive()})
		{
			for (SidePanelParty.Member m : in.members)
			{
				if (m.fight == null)
				{
					continue;
				}
				final String damage = LedgerRows.number(m.figures.d);
				assertTrue(m.name + ": " + m.fight.damage.hint.text + " against " + damage,
					m.fight.damage.hint.text.startsWith(damage + " "));
				assertEquals(m.name, in.scope, m.figures.r);
			}
		}
	}

	/** T-130 review: the member list changing under the read is a change, never an exception. */
	@Test
	public void aMemberListChangingMidReadIsAChange()
	{
		final List<Integer> members = new ArrayList<Integer>(Arrays.asList(1, 2, 3))
		{
			@Override
			public java.util.Iterator<Integer> iterator()
			{
				final java.util.Iterator<Integer> it = super.iterator();
				// As onUserJoin adds a member on OkHttp's thread while the client thread walks the list.
				add(4);
				return it;
			}
		};
		assertNull(PvmPerformancePanel.copy(members));
		assertEquals(Arrays.asList(1, 2), PvmPerformancePanel.copy(new ArrayList<>(Arrays.asList(1, 2))));
		assertFalse(PvmPerformancePanel.changing().equals(PvmPerformancePanel.changing()));
	}

	@Test
	public void aMemberLeavingOrANewPartyGoesBackToYou()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toaYouAndBrindle());
		final SidePanelPartySection tab = p.partySection();
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
		final SidePanelPartySection tab = p.partySection();
		tab.select(31);
		final SidePanelFightTab fight = tab.memberFight();
		assertNotNull(fight);
		assertNotNull(SidePanelPictures.sectionOf(fight, "damage"));
		assertNotNull(SidePanelPictures.sectionOf(fight, "execution"));
		assertNull(SidePanelPictures.sectionOf(fight, "budget"));
		assertNotNull(tab.chart());
		boolean note = false;
		for (Component c : all(tab.member()))
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
		final SidePanelPartySection tab = p.partySection();
		tab.select(22);
		assertNull(tab.memberFight());
		assertEquals("Brindle", tab.heading().left.getText());
		assertNotNull(tab.meter());
		assertNotNull(tab.chart());
	}

	@Test
	public void picturesBesideTheNamesAndTheColourWithoutOne()
	{
		final PvmPerformancePanel with = panel(SidePanelPartyExamples.toaWithPictures());
		SidePanelPictures.draw(with.tabs(), with.fightTab(), 1);
		// Two pictures, each scaled once; Brindle's colour stands in for hers.
		assertEquals(2, with.partySection().meter().scaledPictures());
		SidePanelPictures.draw(with.tabs(), with.fightTab(), 1);
		assertEquals(2, with.partySection().meter().scaledPictures());
		assertTrue(SidePanelParty.party(SidePanelPartyExamples.toaWithPictures()).avatars());
		final PvmPerformancePanel without = panel(SidePanelPartyExamples.toa());
		SidePanelPictures.draw(without.tabs(), without.fightTab(), 1);
		assertEquals(0, without.partySection().meter().scaledPictures());
		assertFalse(SidePanelParty.party(SidePanelPartyExamples.toa()).avatars());
	}

	@Test
	public void aPictureArrivingLaterIsARepaintNotARebuild()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toa());
		final SidePanelPartySection tab = p.partySection();
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
		final SidePanelPartySection tab = p.partySection();
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
							p.apply(new PanelSnapshot(yours((SidePanelParty.Input) c[0]), null,
								SidePanelParty.party((SidePanelParty.Input) c[0])));
							if (c[1] != null)
							{
								p.partySection().select((Long) c[1]);
							}
							SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
							final String at = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale + "× "
								+ ((SidePanelParty.Input) c[0]).scope + ": ";
							for (Component k : all(p.partySection().top()))
							{
								if (k instanceof SidePanelText && ((SidePanelText) k).lastShown() != null)
								{
									final SidePanelText t = (SidePanelText) k;
									assertFalse(at + t.getText() + " => " + t.lastShown(),
										t.lastShown().contains(LedgerRows.CUT));
								}
							}
							for (Component k : all(p.partySection().member()))
							{
								if (k instanceof SidePanelText && ((SidePanelText) k).lastShown() != null)
								{
									final SidePanelText t = (SidePanelText) k;
									assertFalse(at + t.getText() + " => " + t.lastShown(),
										t.lastShown().contains(LedgerRows.CUT) && !fightTabsOwn(t, p.partySection()));
								}
							}
							if (p.partySection().meter() != null)
							{
								assertEquals(at + "names cut", 0, p.partySection().meter().cutNames());
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
	private static boolean fightTabsOwn(SidePanelText t, SidePanelPartySection tab)
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

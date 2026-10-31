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
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-125: the Party tab (Eric's pick, option B, with the defaults): every sharing member as a bar with their DPS and
 * share, {@code others} last, a click into a member's breakdown and back, the room and the whole raid, and the
 * Fight tab's line. On T-124's figures ({@link LuckVsSkillUpdate}), from {@link SidePanelPartyExamples}.
 */
public class SidePanelPartyTest
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
	public void shownOnlyWithASharingMember()
	{
		final PanelSnapshot.PartyTab nobody = SidePanelParty.party(SidePanelPartyExamples.nobody());
		assertFalse(nobody.sharing);
		assertEquals(SidePanelParty.NOBODY, nobody.empty);
		assertNull(SidePanelParty.fightLine(nobody));
		assertEquals(SidePanelParty.NOT_IN_PARTY, SidePanelParty.party(SidePanelPartyExamples.notInParty()).empty);
		final PanelSnapshot.PartyTab toa = SidePanelParty.party(SidePanelPartyExamples.toa());
		assertTrue(toa.sharing);
		assertEquals("you 2nd of 3 · 29%", SidePanelParty.fightLine(toa));
		// The Fight tab shows the line only when there is one.
		assertFalse(SidePanelModel.fight(SidePanelExamples.dead()).structure().contains("|P"));
		final SidePanelModel.Input kill = SidePanelExamples.dead();
		kill.partyLine = SidePanelParty.fightLine(toa);
		assertEquals("you 2nd of 3 · 29%", SidePanelModel.fight(kill).partyLine);
	}

	@Test
	public void theRowsGoBiggestFirstWithOthersLastAndTheirBarsAgainstTheTop()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.toa());
		assertEquals(Arrays.asList("Moss Pike", "you", "Brindle", "others"), names(t));
		assertEquals("Zebak", t.where);
		assertEquals("2:09", t.clock);
		assertEquals("2:09 · party 79.1 dps", SidePanelPartyTab.headerRight(t));
		// 3,420 + 2,960 + 1,990 + others 1,830 = 10,200 over 129 s.
		assertEquals("party 79.1 dps", t.partyDps);
		final PanelSnapshot.PartyRow top = t.rows.get(0);
		assertEquals(1.0, top.fill, 1e-9);
		assertEquals(2_960 / 3_420.0, t.rows.get(1).fill, 1e-9);
		assertEquals(1_830 / 3_420.0, t.rows.get(3).fill, 1e-9);
		assertEquals(Arrays.asList("3,420", "26.5", "34%"), Arrays.asList(top.dmg));
		assertEquals(Arrays.asList("2,960", "22.9", "29%"), Arrays.asList(t.rows.get(1).dmg));
		assertTrue(t.rows.get(3).others);
		assertEquals(Arrays.asList("1,830", "", "18%"), Arrays.asList(t.rows.get(3).dmg));
		// The player white; the members their own colours by slot; others grey.
		assertEquals(SidePanelParty.YOU, t.rows.get(1).colour);
		assertEquals(SidePanelParty.COLOURS[0], top.colour);
		assertEquals(SidePanelParty.COLOURS[1], t.rows.get(2).colour);
		assertEquals(SidePanelParty.OTHERS, t.rows.get(3).colour);
		assertNotNull(t.othersNote);
	}

	@Test
	public void theColoursAreNeverTheCostOrBetterColours()
	{
		for (java.awt.Color c : SidePanelParty.COLOURS)
		{
			assertFalse(c.equals(SidePanelStyle.COST) || c.equals(SidePanelStyle.BETTER) || c.equals(SidePanelParty.YOU));
		}
		assertEquals(7, new java.util.HashSet<>(Arrays.asList(SidePanelParty.COLOURS)).size());
	}

	@Test
	public void othersIsNeverNegative()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toa();
		// The client saw less of other players' damage than the members say they dealt (hits on an add it didn't count).
		in.othersDamage = 1_000;
		assertEquals(0, SidePanelParty.others(in.othersDamage, in.members));
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		assertEquals(Arrays.asList("Moss Pike", "you", "Brindle"), names(t));
		assertNull(t.othersNote);
		// The duo: everyone's damage is a member's.
		assertFalse(names(SidePanelParty.party(SidePanelPartyExamples.duo())).contains("others"));
	}

	@Test
	public void tiesPutThePlayerFirst()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.duo();
		in.members.get(1).figures.d = in.members.get(0).figures.d;
		assertEquals("you", SidePanelParty.party(in).rows.get(0).name);
	}

	@Test
	public void luckAndExecutionAfterTheRoomOnly()
	{
		final PanelSnapshot.PartyTab after = SidePanelParty.party(SidePanelPartyExamples.toa());
		assertTrue(after.after);
		assertEquals(Arrays.asList("top 12%", "8 s"), Arrays.asList(after.rows.get(0).luck));
		assertEquals(SidePanelStyle.BETTER, after.rows.get(0).luckColour);
		assertEquals(Arrays.asList("bottom 9%", "12 s"), Arrays.asList(after.rows.get(2).luck));
		assertEquals(SidePanelStyle.COST, after.rows.get(2).luckColour);
		// Execution, and the uptime's idle ticks beside it (Eric's default).
		assertEquals(Arrays.asList("84%", "-13t"), Arrays.asList(after.rows.get(2).exec));
		final PanelSnapshot.PartyTab live = SidePanelParty.party(SidePanelPartyExamples.tobLive());
		assertFalse(live.after);
		for (PanelSnapshot.PartyRow r : live.rows)
		{
			assertEquals("", r.luck[0]);
		}
	}

	@Test
	public void aMembersBreakdownHasTheirAttacksLuckExecutionAndDamageTaken()
	{
		final PanelSnapshot.PartyRow you = SidePanelParty.party(SidePanelPartyExamples.toa()).row(SidePanelParty.YOU_ID);
		final PanelSnapshot.PartyBreakdown b = you.breakdown;
		assertEquals("You", b.title);
		assertEquals("2nd of 3 · 29% · 22.9 dps", b.place);
		assertEquals(2, b.attacks.size());
		final PanelSnapshot.PartyAttack bow = b.attacks.get(0);
		assertEquals("Twisted bow", bow.name);
		assertEquals("2,410  81%", bow.right);
		assertEquals("18.7 dps · 105% of expected", bow.line1);
		assertEquals("hit 41 of 52", bow.line2);
		assertEquals("Zaryte crossbow spec", b.attacks.get(1).name);
		assertEquals("landed 3 of 4", b.attacks.get(1).line2);
		final List<String> summary = new ArrayList<>();
		for (PanelSnapshot.Row r : b.summary)
		{
			summary.add((r.indent ? "  " : "") + r.left.text + " | " + r.right.text);
		}
		assertEquals(Arrays.asList("luck | average (1 s slower)", "execution | 91%", "  uptime | 97% (-6 ticks)",
			"  prayer | 100%", "  gear | 98% (-23 dmg)", "  stat boost | 97% (-35 dmg)", "damage taken | 71 (7 hits)"),
			summary);
		// The rows a sender summed the rest into, and a spell.
		final LuckVsSkillUpdate.Row rest = SidePanelPartyExamples.attacks(-1, 3, 2, 40, 38, false);
		assertEquals(SidePanelParty.OTHER_ATTACKS, SidePanelParty.attackName(rest, SidePanelPartyExamples::itemName));
	}

	@Test
	public void theWholeRaidSumsEachMembersRooms()
	{
		final LuckVsSkillUpdate maiden = SidePanelPartyExamples.figures("The Maiden of Sugadinti", true, 1_200, 1_100, 100,
			0.8, 4, 0.9, 10, SidePanelPartyExamples.attacks(SidePanelPartyExamples.SCYTHE, 30, 25, 1_200, 1_100, false));
		final LuckVsSkillUpdate bloat = SidePanelPartyExamples.figures("Pestilent Bloat", true, 800, 900, 60, 0.2, -3, 0.8,
			12, SidePanelPartyExamples.attacks(SidePanelPartyExamples.SCYTHE, 20, 14, 800, 900, false));
		maiden.rn = bloat.rn = "Theatre of Blood";
		final LuckVsSkillUpdate raid = SidePanelParty.sum(Arrays.asList(maiden, bloat));
		assertEquals(2_000, raid.d);
		assertEquals(2_000, raid.e, 1e-9);
		assertEquals(160, raid.st);
		assertEquals(22, raid.il);
		assertEquals("Theatre of Blood", raid.r);
		// One scythe row, summed.
		assertEquals(1, raid.rows().size());
		assertEquals(50, raid.rows().get(0).a);
		// Luck weighed by what was expected of each room; Execution by its ticks.
		assertEquals((0.8 * 1_100 + 0.2 * 900) / 2_000, raid.p, 1e-9);
		assertEquals((0.9 * 100 + 0.8 * 60) / 160, raid.x, 1e-9);
		// The rooms' series end to end.
		final int[][] s = SidePanelParty.concat(Arrays.asList(Arrays.asList(new PartyBoard.Point(5, 100, 90),
			new PartyBoard.Point(10, 300, 280)), Arrays.asList(new PartyBoard.Point(5, 50, 60))));
		assertEquals(Arrays.asList(5, 10, 15), Arrays.asList(s[0][0], s[0][1], s[0][2]));
		assertEquals(Arrays.asList(100, 300, 350), Arrays.asList(s[1][0], s[1][1], s[1][2]));
		// The raid's tab has the switch on Whole raid, and a member who left stays, muted.
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.tobRaid());
		assertEquals(Boolean.TRUE, t.wholeRaid);
		assertTrue(t.row(44).left);
		assertEquals(Boolean.FALSE, SidePanelParty.party(SidePanelPartyExamples.toa()).wholeRaid);
		assertNull(SidePanelParty.party(SidePanelPartyExamples.duo()).wholeRaid);
	}

	// ---- Swing ----

	private static PvmPerformancePanel panel(SidePanelParty.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, SidePanelParty.party(in)));
		p.tabs().select(p.tabs().getTab(3));
		return p;
	}

	@Test
	public void thePanelHasAFourthTabNamedParty()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toa());
		assertEquals(4, p.tabs().getComponentCount());
		// In the client the tabs are in RuneLite's look and feel's font, the RuneScape one (as the renders set it up); a
		// test's default is Java's Dialog, wider on some machines. Installing the look and feel here would change it for
		// every test after, so the tabs get its font.
		for (Component c : p.tabs().getComponents())
		{
			c.setFont(net.runelite.client.ui.FontManager.getRunescapeFont());
		}
		SidePanelPictures.draw(p.tabs(), p.partyTab(), 1);
		// Every tab on one row at the sidebar's width: the fourth didn't wrap out of sight.
		final int y = p.tabs().getComponent(0).getY();
		for (Component c : p.tabs().getComponents())
		{
			assertEquals(y, c.getY());
			assertTrue(c.getX() + c.getWidth() <= p.tabs().getWidth());
		}
	}

	@Test
	public void clickingAMemberOpensTheirBreakdownAndBackReturns()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toa());
		final SidePanelPartyTab tab = p.partyTab();
		assertNotNull(tab.meter());
		tab.open(22);
		assertEquals(22, tab.opened());
		assertNull(tab.meter());
		assertNotNull(SidePanelPictures.sectionOf(tab, "partyMember"));
		// The chart stays, the opened member bright.
		assertNotNull(tab.chart());
		tab.back();
		assertEquals(SidePanelPartyTab.NONE, tab.opened());
		assertNotNull(tab.meter());
		// Others can't be opened; a member who's gone from the board closes.
		tab.open(PanelSnapshot.PartyRow.OTHERS_ID);
		assertEquals(SidePanelPartyTab.NONE, tab.opened());
		tab.open(22);
		final SidePanelParty.Input without = SidePanelPartyExamples.toa();
		without.members.remove(2);
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(without)));
		assertEquals(SidePanelPartyTab.NONE, tab.opened());
	}

	@Test
	public void theSwitchPicksTheColumnsAfterTheRoomAndGoesBackToDamageLive()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.toa());
		final SidePanelPartyTab tab = p.partyTab();
		tab.pick(SidePanelParty.Metric.LUCK);
		assertEquals(SidePanelParty.Metric.LUCK, tab.meter().metric());
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(SidePanelPartyExamples.tobLive())));
		assertEquals(SidePanelParty.Metric.DMG, tab.metric());
	}

	@Test
	public void noRebuildOnAHoverOrANewSnapshotOfTheSameShape()
	{
		final PvmPerformancePanel p = panel(SidePanelPartyExamples.tobLive());
		final SidePanelPartyTab tab = p.partyTab();
		final int built = tab.rebuilds();
		final SidePanelPartyMeter meter = tab.meter();
		// Five ticks on: everyone's damage up, the same members.
		final SidePanelParty.Input later = SidePanelPartyExamples.tobLive();
		for (SidePanelParty.Member m : later.members)
		{
			m.figures.d += 50;
		}
		later.othersDamage += 200;
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(later)));
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(later)));
		assertEquals(built, tab.rebuilds());
		assertSame(meter, tab.meter());
		assertTrue(tab.chart().setHover(40));
		assertFalse(tab.chart().setHover(40));
		assertEquals(built, tab.rebuilds());
		// Another tab's snapshot leaves it as it is.
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		assertEquals(built, tab.rebuilds());
	}

	/** Every board in every text setting at 1× and 2×: no name cut, no text cut, the columns that fit. */
	@Test
	public void everythingFitsAtEverySize()
	{
		final List<String> dropped = new ArrayList<>();
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					for (SidePanelParty.Input in : Arrays.asList(SidePanelPartyExamples.duo(), SidePanelPartyExamples.toa(),
						SidePanelPartyExamples.tobLive(), SidePanelPartyExamples.tobRaid(), SidePanelPartyExamples.cox(),
						SidePanelPartyExamples.nobody()))
					{
						for (Long open : Arrays.asList(null, SidePanelParty.YOU_ID))
						{
							final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
							p.setText(larger, smooth);
							try
							{
								p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null,
									SidePanelParty.party(in)));
								p.tabs().select(p.tabs().getTab(3));
								if (open != null)
								{
									p.partyTab().open(open);
								}
								SidePanelPictures.draw(p.tabs(), p.partyTab(), scale);
								final String at = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale
									+ "× " + in.scope + (open == null ? "" : " opened") + ": ";
								for (Component c : all(p.partyTab()))
								{
									if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null)
									{
										final SidePanelText t = (SidePanelText) c;
										assertFalse(at + t.getText() + " => " + t.lastShown(),
											t.lastShown().contains(LedgerRows.CUT));
									}
								}
								final SidePanelPartyMeter meter = p.partyTab().meter();
								if (meter != null)
								{
									assertEquals(at + "names cut", 0, meter.cutNames());
									if (meter.shownColumns() < 3)
									{
										dropped.add(at.trim());
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
		}
		// The DPS column gives way only where it must; say where.
		System.out.println("T-125 DPS column dropped: " + dropped);
	}

	/** A long raid name keeps its line: the party's DPS shortens, then goes to the tooltip, before the name is cut. */
	@Test
	public void theHeaderKeepsTheRaidsNameWhole()
	{
		for (String scope : new String[]{"Theatre of Blood", "Tombs of Amascut: Expert", "Theatre of Blood: Hard Mode"})
		{
			final SidePanelParty.Input in = SidePanelPartyExamples.tobRaid();
			in.scope = scope;
			final PvmPerformancePanel p = panel(in);
			p.setText(true, true);
			try
			{
				SidePanelPictures.draw(p.tabs(), p.partyTab(), 1);
				for (Component c : all(p.partyTab()))
				{
					if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null)
					{
						final SidePanelText t = (SidePanelText) c;
						assertFalse(scope + ": " + t.getText() + " => " + t.lastShown(), t.lastShown().contains(LedgerRows.CUT));
					}
				}
			}
			finally
			{
				SidePanelStyle.setText(false, false);
			}
		}
	}

	/** Names are the party's, shown and never written: nothing that saves or exports reads the board or this tab. */
	@Test
	public void namesAreNeverSaved() throws IOException
	{
		final Path src = Paths.get("src/main/java/com/pvmlogs");
		for (String file : new String[]{"SidePanelParty.java", "SidePanelPartyTab.java", "SidePanelPartyMeter.java",
			"SidePanelPartyChart.java"})
		{
			final String s = new String(Files.readAllBytes(src.resolve(file)), StandardCharsets.UTF_8);
			for (String writes : new String[]{"Filepath", "java.io.", "java.nio.file", "toJson", "saveHistory", "csv",
				"ConfigManager", "setConfiguration"})
			{
				assertFalse(file + " " + writes, s.contains(writes));
			}
		}
		// What the history and the CSV are written from never sees a party name.
		for (String file : new String[]{"Fight.java", "Encounter.java", "LedgerHistory.java", "HistoryBudget.java"})
		{
			final String s = new String(Files.readAllBytes(src.resolve(file)), StandardCharsets.UTF_8);
			assertFalse(file, s.contains("PartyBoard") || s.contains("SidePanelParty") || s.contains("PartyMember")
				|| s.contains("getMemberById"));
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

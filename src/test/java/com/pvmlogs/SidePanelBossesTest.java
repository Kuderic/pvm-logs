package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-102: History's "All bosses" becomes Bosses and Monsters, by the history budget's rule (Eric, 2026-10-29 22:33:
 * "History tab has a section called All bosses but it also shows not bosses unless bosses only is ticked"). The
 * checkbox becomes the export's choice; a room the player died in reads (died).
 */
public class SidePanelBossesTest
{
	// ---- the rule ----

	@Test
	public void ericsNamesLandWhereTheBudgetPutsThem()
	{
		final Map<String, Boolean> boss = new LinkedHashMap<>();
		for (SidePanelHistory.BossEntry e : SidePanelHistory.bosses(SidePanelBossesExamples.mix(), "", null))
		{
			boss.put(e.name, e.boss);
		}
		assertEquals(Arrays.asList("Vardorvis", "Abyssal demon", "Abyssal Sire", "The Leviathan", "Reanimated abyssal",
			"Araxyte", "Araxxor", "Hespori", "Deranged archaeologist"), new ArrayList<>(boss.keySet()));
		// Bosses: on the hiscores' list, grouped, or named by EncounterGroup.isBossByName.
		for (String b : new String[]{"Vardorvis", "The Leviathan", "Abyssal Sire", "Araxxor", "Hespori",
			"Deranged archaeologist"})
		{
			assertTrue(b, boss.get(b));
		}
		// Monsters: the rest.
		for (String m : new String[]{"Abyssal demon", "Araxyte", "Reanimated abyssal"})
		{
			assertFalse(m, boss.get(m));
		}
	}

	@Test
	public void theRuleIsTheHistoryBudgets()
	{
		for (Encounter room : SidePanelHistory.allRooms(SidePanelBossesExamples.mix()))
		{
			for (Fight f : room.getFights())
			{
				if (f.getAttempts() > 0 && SidePanelHistory.addsOf(room) == null)
				{
					// A room is a monster's exactly when every fight in it counts against the monster budget.
					if (SidePanelHistory.isMonster(room, null))
					{
						assertTrue(room.getName(), HistoryBudget.isMonster(f, null));
					}
				}
			}
			final boolean anyBoss = room.getFights().stream().anyMatch(f -> f.getAttempts() > 0
				&& !HistoryBudget.isMonster(f, null));
			assertEquals(room.getName(), anyBoss, !SidePanelHistory.isMonster(room, null));
		}
	}

	@Test
	public void theDerangedArchaeologistIsABoss()
	{
		// On the hiscores' list (as "Deranged Archaeologist"), so MonsterRooms.isMonster says it isn't a monster.
		assertTrue(PvmPerformancePlugin.isBossName("Deranged archaeologist"));
		assertFalse(MonsterRooms.isMonster("Deranged archaeologist", SidePanelBossesExamples.DERANGED, false, null));
	}

	@Test
	public void theOldAraxyteRoomsGoUnderAraxxor()
	{
		final List<SidePanelHistory.BossEntry> entries = SidePanelHistory.bosses(SidePanelBossesExamples.mix(), "", null);
		for (SidePanelHistory.BossEntry e : entries)
		{
			assertFalse(e.name, e.name.endsWith("Araxyte") && !e.name.equals("Araxyte"));
		}
		final SidePanelHistory.BossEntry araxxor = entry(entries, "Araxxor");
		assertTrue(araxxor.adds.containsAll(Arrays.asList("Acidic Araxyte", "Mirrorback Araxyte")));
		assertEquals("with Acidic Araxyte, Mirrorback Araxyte", araxxor.sub());
		// Their own deaths aren't Araxxor's kills: one kill, its room's.
		assertEquals(1, araxxor.kills);
		// The old rooms: one ungrouped, one saved as a run of monsters (T-089); both an add of the group now.
		int old = 0;
		for (Encounter room : SidePanelHistory.allRooms(SidePanelBossesExamples.mix()))
		{
			if (room.getName().endsWith("Araxyte") && !room.getName().equals("Araxyte"))
			{
				old++;
				assertEquals("Araxxor", SidePanelHistory.addsOf(room));
			}
		}
		assertEquals(2, old);
	}

	@Test
	public void addsAloneWithoutTheirBossStayAsTheyAre()
	{
		final List<Fight> h = Collections.singletonList(SidePanelBossesExamples.saved(SidePanelBossesExamples.fight(
			"Acidic Araxyte", SidePanelBossesExamples.ACIDIC, 1, SidePanelExamples.at(9, 0), 2, 50, true), null, false));
		assertEquals(Collections.singletonList("Acidic Araxyte"), names(SidePanelHistory.bosses(h, "", null)));
	}

	@Test
	public void aMonsterSaysNothingAboutNotBeingABoss()
	{
		final SidePanelHistory.BossEntry demon = entry(SidePanelHistory.bosses(SidePanelBossesExamples.mix(), "", null),
			"Abyssal demon");
		assertEquals("1 run · 12 kills", demon.right());
		assertTrue(demon.sub(), demon.sub().matches("best \\d+/hr"));
		final SidePanelHistory.BossEntry reanimated = entry(SidePanelHistory.bosses(SidePanelBossesExamples.mix(), "",
			null), "Reanimated abyssal");
		assertEquals("", reanimated.sub());
	}

	// ---- the tab ----

	@Test
	public void theTabHasBossesThenMonsters()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab(SidePanelBossesExamples.mix());
			tab.putAway();
			assertEquals(Arrays.asList("historyBosses", "historyMonsters", "historyTools"), sections(tab));
			final List<String> bosses = texts(section(tab, "historyBosses"));
			final List<String> monsters = texts(section(tab, "historyMonsters"));
			assertTrue(bosses.toString(), bosses.contains("Vardorvis") && bosses.contains("Deranged archaeologist")
				&& bosses.contains("Araxxor"));
			assertFalse(bosses.toString(), bosses.contains("Abyssal demon"));
			assertTrue(monsters.toString(), monsters.contains("Abyssal demon") && monsters.contains("Araxyte"));
			assertFalse(monsters.toString(), monsters.contains("Vardorvis"));
			assertEquals("Bosses", section(tab, "historyBosses").title());
			assertEquals("Monsters", section(tab, "historyMonsters").title());
		});
	}

	@Test
	public void noMonstersNoMonstersSection()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab(SidePanelBossesExamples.bossesOnly());
			assertFalse(sections(tab).toString(), sections(tab).contains("historyMonsters"));
			assertTrue(sections(tab).contains("historyBosses"));
		});
	}

	@Test
	public void aSearchMatchingOnlyAMonster()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab(SidePanelBossesExamples.mix());
			tab.search().setText("reanimated");
			assertTrue(texts(section(tab, "historyBosses")).contains(SidePanelHistoryTab.NO_BOSS_MATCH));
			assertTrue(texts(section(tab, "historyMonsters")).contains("Reanimated abyssal"));
			tab.search().setText("hespori");
			assertTrue(texts(section(tab, "historyMonsters")).contains(SidePanelHistoryTab.NO_MONSTER_MATCH));
			tab.search().setText("zulrah");
			assertTrue(sections(tab).isEmpty());
			assertTrue(texts(tab).contains(SidePanelHistoryTab.NO_MATCH));
		});
	}

	@Test
	public void aMonsterPickedShowsItsRuns()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab(SidePanelBossesExamples.mix());
			tab.pick("Abyssal demon");
			assertEquals("Abyssal demon", tab.boss());
			assertEquals("Runs", section(tab, "historyKills").title());
			tab.pick("Araxxor");
			assertEquals("Kills", section(tab, "historyKills").title());
		});
	}

	// ---- the export's choice ----

	@Test
	public void exportKeepsItsChoiceInTools()
	{
		onEdt(() ->
		{
			final List<Boolean> exported = new ArrayList<>();
			final SidePanelHistoryTab[] holder = new SidePanelHistoryTab[1];
			final SidePanelFightTab.Actions actions = new SidePanelFightTab.Actions()
			{
				public void exportAll()
				{
					exported.add(holder[0].bossesOnly());
				}

				public void newTrip()
				{
				}

				public void saveDebugLog()
				{
				}

				public void clearHistory()
				{
				}

				public void wholeRaid(boolean whole)
				{
				}

				public void showRaidRoom(int index)
				{
				}
			};
			final SidePanelHistoryTab tab = new SidePanelHistoryTab(SidePanelPictures.source(SidePanelBossesExamples.mix()),
				actions, new SidePanelSection.MemoryFolds());
			holder[0] = tab;
			// In Tools, beside Export, off by default; nothing else in the tab is a checkbox.
			assertTrue(all(section(tab, "historyTools"), javax.swing.JCheckBox.class).contains(tab.exportChoice()));
			assertEquals(1, all(tab, javax.swing.JCheckBox.class).size());
			assertEquals(SidePanelHistoryTab.EXPORT_BOSSES_ONLY, tab.exportChoice().getText());
			assertEquals(SidePanelHistoryTab.EXPORT_BOSSES_ONLY_TIP, tab.exportChoice().getToolTipText());
			final javax.swing.JButton export = all(tab, javax.swing.JButton.class).stream().filter(b ->
				"Export CSV".equals(b.getText())).findFirst().get();
			export.doClick();
			tab.exportChoice().setSelected(true);
			// It changes nothing in the lists: no rebuild.
			final int builds = tab.builds();
			export.doClick();
			assertEquals(builds, tab.builds());
			assertEquals(Arrays.asList(false, true), exported);
			// It lasts across refreshes (a fight saved).
			tab.refresh();
			assertTrue(tab.bossesOnly());
		});
	}

	// ---- a died room ----

	@Test
	public void aDiedRoomReadsDiedAndALeftOneEnded()
	{
		assertTrue(SidePanelHistory.died(SidePanelBossesExamples.diedRoom()));
		assertFalse(SidePanelHistory.died(SidePanelBossesExamples.leftRoom()));
		// The Fight tab's header once it's over.
		assertEquals(SidePanelModel.DIED, header(SidePanelBossesExamples.diedRoom()));
		assertEquals(SidePanelModel.ENDED, header(SidePanelBossesExamples.leftRoom()));
		// The "Last fight" row before the next attack.
		final SidePanelModel.Input pre = SidePanelExamples.preAttack();
		pre.lastFight = SidePanelModel.lastFight(SidePanelBossesExamples.diedRoom(), true);
		assertEquals(SidePanelModel.DIED, SidePanelModel.fight(pre).lastFight.suffix.text);
		pre.lastFight = SidePanelModel.lastFight(SidePanelBossesExamples.leftRoom(), true);
		assertEquals(SidePanelModel.ENDED, SidePanelModel.fight(pre).lastFight.suffix.text);
		// The Trip tab's rows.
		final PanelSnapshot.TripRow died = SidePanelModel.tripRow(SidePanelHistory.row(SidePanelBossesExamples.diedRoom(),
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE));
		assertTrue(died.ended);
		assertTrue(died.died);
		final PanelSnapshot.TripRow left = SidePanelModel.tripRow(SidePanelHistory.row(SidePanelBossesExamples.leftRoom(),
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE));
		assertTrue(left.ended);
		assertFalse(left.died);
		// Not a kill, so not in the Kills list, as any room that isn't one.
		assertFalse(SidePanelHistory.isKill(SidePanelBossesExamples.diedRoom()));
	}

	@Test
	public void aKillIsStillDead()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		assertFalse(in.died);
		assertEquals(" (dead)", SidePanelModel.fight(in).header.suffix.text);
	}

	private static String header(Encounter room)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.ended = true;
		in.killed = false;
		return SidePanelModel.fight(in).header.suffix.text;
	}

	// ---- helpers ----

	static SidePanelHistoryTab tab(List<Fight> history)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null,
			SidePanelPictures.source(history));
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		return tab;
	}

	private static SidePanelHistory.BossEntry entry(List<SidePanelHistory.BossEntry> entries, String name)
	{
		return entries.stream().filter(e -> e.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static List<String> names(List<SidePanelHistory.BossEntry> entries)
	{
		final List<String> out = new ArrayList<>();
		for (SidePanelHistory.BossEntry e : entries)
		{
			out.add(e.name);
		}
		return out;
	}

	private static List<String> sections(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (SidePanelSection s : all(c, SidePanelSection.class))
		{
			out.add(s.id());
		}
		return out;
	}

	private static SidePanelSection section(Container c, String id)
	{
		for (SidePanelSection s : all(c, SidePanelSection.class))
		{
			if (s.id().equals(id))
			{
				return s;
			}
		}
		throw new AssertionError("no section " + id + " in " + sections(c));
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

	private static void onEdt(Runnable r)
	{
		try
		{
			javax.swing.SwingUtilities.invokeAndWait(r);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			if (e.getCause() instanceof Error)
			{
				throw (Error) e.getCause();
			}
			throw new RuntimeException(e.getCause());
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
}

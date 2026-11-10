package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

/**
 * T-202 (Eric, 2026-11-10 07:41: "Why does the celebrations setting move damage over time out from damage? and timeline
 * is missing."): one Fight tab. Celebrations is the kill-end moment alone (the die roll and flash), and every view, a
 * room, a whole raid, a run of monsters, History's kill and a party member's fight, has T-196's merged sections: the
 * Timeline inside Damage, the attack rhythm inside Skill, and no "Damage over time", "Ticks" or "Details".
 */
public class SidePanelOneTabTest
{
	private static final List<String> OLD_IDS = Arrays.asList("chart", "ticks", "details");
	private static final List<String> OLD_TITLES = Arrays.asList("Damage over time", "Ticks", "Details");

	// ---- the views ----

	/** A whole ToA raid with its timeline: the three rooms' 920 ticks as one view, as the panel's raid view has it. */
	static SidePanelModel.Input toaRaid(boolean ended)
	{
		final SidePanelModel.Input in = SidePanelExamples.raid();
		in.ended = ended;
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.fight("Tombs of Amascut", "Burning claws",
			ItemID.BONE_CLAWS, 920, new int[]{5, 60, 140}, new int[]{150, 12, 360, 20, 700, 23}, new int[]{400, 8, 820, 6},
			7_600, 7_450, 400, 350, 160);
		in.view = b.view();
		return in;
	}

	/** History's picked kill, with its timeline. */
	static SidePanelModel.Input historysKill()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		kill.fight.end(true, SidePanelExamples.at(15, 31));
		final List<Fight> history = java.util.Collections.singletonList(kill.fight);
		return SidePanelHistory.savedKill(history, SidePanelHistory.allRooms(history).get(0), -1,
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE, null, kill.view());
	}

	/** Every view of the Fight tab, by name. */
	private static Map<String, SidePanelModel.Input> views()
	{
		final Map<String, SidePanelModel.Input> out = new LinkedHashMap<>();
		out.put("room, live", SidePanelExamples.live());
		out.put("room, killed", SidePanelExamples.dead());
		out.put("room with its timeline, live", SidePanelTimelineExamples.input(SidePanelTimelineExamples.kill(), false));
		out.put("room with its timeline, killed", SidePanelTimelineExamples.input(SidePanelTimelineExamples.kill(), true));
		out.put("Araxxor, Celebrations on", SidePanelKillCardExamples.araxxorKill());
		out.put("Araxxor, Celebrations off", SidePanelKillCardExamples.off());
		out.put("Sire with weapons", SidePanelExamples.sireWeapons());
		out.put("whole raid", SidePanelExamples.raid());
		out.put("whole raid with its timeline", toaRaid(true));
		out.put("whole raid with its timeline, live", toaRaid(false));
		out.put("run of monsters", SidePanelRunExamples.saved());
		out.put("run of monsters, live", SidePanelRunExamples.live());
		out.put("History's kill", historysKill());
		out.put("before the first attack", SidePanelExamples.preAttack());
		return out;
	}

	private static PvmPerformancePanel panel(SidePanelModel.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(in.celebrations);
		p.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		SidePanelPictures.openAll(p.fightTab());
		return p;
	}

	/** A party member's fight, selected on the meter: their panels in the Fight tab, opened. */
	private static SidePanelFightTab member()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaYouAndBrindle();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		p.partySection().select(22L);
		final SidePanelFightTab fight = p.partySection().memberFight();
		assertNotNull(fight);
		SidePanelPictures.openAll(fight);
		return fight;
	}

	// ---- one set of sections ----

	@Test
	public void noDamageOverTimeTicksOrDetailsInAnyView()
	{
		final Map<String, Container> tabs = new LinkedHashMap<>();
		for (Map.Entry<String, SidePanelModel.Input> v : views().entrySet())
		{
			tabs.put(v.getKey(), panel(v.getValue()).fightTab());
			final String structure = SidePanelModel.fight(v.getValue()).structure();
			assertFalse(v.getKey() + ": " + structure, structure.contains("|D"));
		}
		tabs.put("party member", member());
		for (Map.Entry<String, Container> t : tabs.entrySet())
		{
			for (SidePanelSection s : all(t.getValue(), SidePanelSection.class))
			{
				assertFalse(t.getKey() + ": " + s.id(), OLD_IDS.contains(s.id()));
				assertFalse(t.getKey() + ": " + s.title(), OLD_TITLES.contains(s.title()));
			}
		}
	}

	@Test
	public void theSameSectionsInEveryViewLessWhatItHasNoDataFor()
	{
		// After the fight: Damage, Luck, Where the time went, Skill, Damage taken, plus Rooms in a raid.
		assertEquals(Arrays.asList("damage", "curve", "budget", "execution", "taken"), ids(panel(
			SidePanelExamples.dead()).fightTab()));
		assertEquals(Arrays.asList("damage", "curve", "budget", "execution", "rooms", "taken"), ids(panel(
			toaRaid(true)).fightTab()));
		// A member's fight: no Damage taken (the room message doesn't carry it).
		assertEquals(Arrays.asList("damage", "curve", "budget", "execution"), ids(member()));
		// Live: Luck and Where the time went wait for the end.
		assertEquals(Arrays.asList("damage", "execution", "taken"), ids(panel(SidePanelExamples.live()).fightTab()));
		// A run has no luck or skill (T-095): Damage, with its Timeline, and Damage taken under its four tiles.
		assertEquals(Arrays.asList("damage", "taken"), ids(panel(SidePanelRunExamples.saved()).fightTab()));
	}

	@Test
	public void theCardOnlyForARoom()
	{
		for (Map.Entry<String, SidePanelModel.Input> v : views().entrySet())
		{
			final SidePanelModel.Input in = v.getValue();
			final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
			final boolean room = in.kind == SidePanelModel.Kind.ROOM && !in.run;
			assertEquals(v.getKey(), room, tab.card != null);
			assertEquals(v.getKey(), room, all(panel(in).fightTab(), SidePanelKillCard.class).size() == 1);
			// No tiles in place of the card: only a run's four (kills, kills an hour, DPS, damage).
			assertEquals(v.getKey(), in.run, tab.tiles != null);
		}
	}

	@Test
	public void theTimelineIsInsideDamageWhereverThereIsAChart()
	{
		int charts = 0;
		final Map<String, Container> tabs = new LinkedHashMap<>();
		final Map<String, Boolean> hasChart = new LinkedHashMap<>();
		for (Map.Entry<String, SidePanelModel.Input> v : views().entrySet())
		{
			tabs.put(v.getKey(), panel(v.getValue()).fightTab());
			hasChart.put(v.getKey(), SidePanelModel.fight(v.getValue()).chart != null);
		}
		tabs.put("party member", member());
		hasChart.put("party member", true);
		for (Map.Entry<String, Container> t : tabs.entrySet())
		{
			final String view = t.getKey();
			final List<SidePanelDamageChart> drawn = all(t.getValue(), SidePanelDamageChart.class);
			if (!hasChart.get(view))
			{
				assertTrue(view, drawn.isEmpty());
				continue;
			}
			charts++;
			assertEquals(view, 1, drawn.size());
			final SidePanelSection damage = (SidePanelSection) SwingUtilities.getAncestorOfClass(SidePanelSection.class,
				drawn.get(0));
			assertNotNull(view, damage);
			assertEquals(view, "damage", damage.id());
			assertTrue(view, texts(damage).contains(SidePanelFightTab.TIMELINE));
			// The attack rhythm in Skill, where it has a Skill section (a run has none).
			for (SidePanelTickStrip strip : all(t.getValue(), SidePanelTickStrip.class))
			{
				final SidePanelSection in = (SidePanelSection) SwingUtilities.getAncestorOfClass(SidePanelSection.class,
					strip);
				assertEquals(view, "execution", in.id());
			}
			if (SidePanelPictures.sectionOf(t.getValue(), "execution") != null)
			{
				assertEquals(view, 1, all(t.getValue(), SidePanelTickStrip.class).size());
			}
		}
		// A room, a whole raid, a run, History's kill and a party member.
		assertTrue(String.valueOf(charts), charts >= 8);
	}

	// ---- Celebrations: the moment only ----

	@Test
	public void theSettingSaysItIsTheMoment() throws Exception
	{
		final Method m = PvmPerformanceConfig.class.getMethod("panelCelebrations");
		final ConfigItem item = m.getAnnotation(ConfigItem.class);
		assertEquals("panelCelebrations", item.keyName());
		assertEquals("Celebrations", item.name());
		assertTrue(item.description(), item.description().startsWith("The die roll and flash on the kill card when a kill"
			+ " ends."));
		assertFalse(item.description(), item.description().toLowerCase().contains("tiles"));
		// Default on.
		final PvmPerformanceConfig config = (PvmPerformanceConfig) java.lang.reflect.Proxy.newProxyInstance(
			PvmPerformanceConfig.class.getClassLoader(), new Class<?>[]{PvmPerformanceConfig.class},
			(proxy, method, args) -> java.lang.invoke.MethodHandles.privateLookupIn(PvmPerformanceConfig.class,
				java.lang.invoke.MethodHandles.lookup()).unreflectSpecial(method, PvmPerformanceConfig.class)
				.bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args));
		assertTrue(config.panelCelebrations());
	}

	@Test
	public void offTheKillEndsSettledAndEverythingElseIsTheSame()
	{
		for (boolean on : new boolean[]{true, false})
		{
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setCelebrations(on);
			final SidePanelModel.Input live = SidePanelKillCardExamples.araxxorLive();
			live.celebrations = on;
			final SidePanelModel.Input kill = SidePanelKillCardExamples.araxxorKill();
			kill.celebrations = on;
			p.apply(new PanelSnapshot(SidePanelModel.fight(live)));
			p.apply(new PanelSnapshot(SidePanelModel.fight(kill)));
			final SidePanelKillCard card = all(p.fightTab(), SidePanelKillCard.class).get(0);
			// The moment plays only with it on.
			assertEquals(on, card.celebrating());
			// The record is read either way: the grade and the rank.
			final PanelSnapshot.KillCard c = SidePanelModel.fight(kill).card;
			assertNotNull(c.grade);
			assertNotNull(c.rank);
		}
		final PanelSnapshot.FightTab on = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill());
		final PanelSnapshot.FightTab off = SidePanelModel.fight(SidePanelKillCardExamples.off());
		assertEquals(on.structure(), off.structure());
		assertEquals(ids(panel(SidePanelKillCardExamples.araxxorKill()).fightTab()), ids(panel(
			SidePanelKillCardExamples.off()).fightTab()));
		assertNull(off.tiles);
	}

	@Test
	public void theFoldsAreTheSameWithItOff()
	{
		for (boolean on : new boolean[]{true, false})
		{
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setCelebrations(on);
			final SidePanelSection.Folds folds = p.fightFolds();
			for (String id : PvmPerformancePanel.FOLDED_UNDER_CARD)
			{
				assertTrue(id + " " + on, folds.isFolded(id));
			}
			assertFalse(folds.isFolded("damage"));
		}
		for (String id : OLD_IDS)
		{
			assertFalse(id, PvmPerformancePanel.FOLDED_UNDER_CARD.contains(id));
		}
	}

	// ---- helpers ----

	private static List<String> ids(Container tab)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : tab.getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				out.add(((SidePanelSection) k).id());
			}
		}
		return out;
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
}

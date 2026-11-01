package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * T-142 (Eric, 2026-11-01: "at yama there's emberlight and ayak specs which are independent"; his pick, A): the Drains
 * card, a block per drained stat with its specials under it, wired to T-141's per-stat record; an old kill shows
 * today's card.
 */
public class SidePanelDrainsCardTest
{
	private static PanelSnapshot.Defence card(Encounter room, boolean magicUsesDefence)
	{
		return SidePanelModel.fight(SidePanelDrainsExamples.kill(room, magicUsesDefence)).defence;
	}

	private static List<String> texts(PanelSnapshot.DrainBlock b)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.BlockLine l : b.lines)
		{
			out.add((l.row.left.text + l.row.hint.text + " | " + l.row.right.text).trim());
		}
		return out;
	}

	@Test
	public void yamasEmberlightAndSoulRendAreTwoIndependentBlocks()
	{
		final PanelSnapshot.Defence d = card(SidePanelDrainsExamples.yama(), false);
		assertNotNull(d.blocks);
		assertEquals(2, d.blocks.size());
		final PanelSnapshot.DrainBlock def = d.blocks.get(0);
		assertEquals("Defence", def.name.text);
		assertEquals("225 → 191", def.change.text);
		assertNotNull(def.bar);
		assertEquals(145, def.bar.floor);
		// The Emberlight, the drains he had when the second fight began, his regen, and the sum for your melee and ranged.
		assertEquals(java.util.Arrays.asList("Emberlight | +30 dmg", "landed 2 of 5 · -34 |", "already drained | +8 dmg",
			"regens 1 every 100 ticks |", "for your melee & ranged | +38 dmg"), texts(def));
		final PanelSnapshot.DrainBlock md = d.blocks.get(1);
		assertEquals("Magic defence", md.name.text);
		// The reduction, and the bonus his casts met; no bar, no regen.
		assertEquals("-60", md.change.text);
		assertNull(md.bar);
		assertEquals(java.util.Arrays.asList("bonus met +81 → +21 |", "Eye of ayak | +27 dmg", "landed 3 of 3 · -60 |",
			"for your magic |"), texts(md));
		// The total: the sum of the stats' values, T-050's drain value.
		assertEquals("+65 dmg", d.worth.damage);
		assertEquals("+65 dmg", d.hint.text);
	}

	@Test
	public void oneStatWithTwoSourcesShowsEachAndTheirSum()
	{
		final PanelSnapshot.Defence d = card(SidePanelDrainsExamples.graardor(), false);
		assertEquals(1, d.blocks.size());
		assertEquals(java.util.Arrays.asList("Dragon warhammer | +96 dmg", "landed 1 of 2 · -75 |",
			"Bandos godsword | +51 dmg", "landed 1 of 1 · -62 |", "regens 1 every 100 ticks |",
			"for your melee & ranged | +147 dmg"), texts(d.blocks.get(0)));
		assertEquals("+147 dmg", d.worth.damage);
	}

	@Test
	public void aPartySourceSitsUnderItsStatLabelledParty()
	{
		final PanelSnapshot.DrainBlock b = card(SidePanelDrainsExamples.floorAndParty(), false).blocks.get(0);
		assertEquals(" at its floor", b.hint.text);
		assertEquals(java.util.Arrays.asList("Dragon warhammer | +41 dmg", "landed 2 of 2 · -34 |",
			"Dragon warhammer | +19 dmg", "party · 1 drain · -34 |", "for your melee & ranged | +60 dmg"), texts(b));
	}

	@Test
	public void aSourceThatAddedNothingShowsAsItIs()
	{
		final PanelSnapshot.Defence d = card(SidePanelDrainsExamples.soulRendsMissed(), false);
		assertEquals(2, d.blocks.size());
		final PanelSnapshot.DrainBlock md = d.blocks.get(1);
		assertEquals("unchanged", md.change.text);
		assertNull(md.bar);
		assertEquals(java.util.Arrays.asList("Eye of ayak | +0 dmg", "landed 0 of 2 |", "for your magic |"), texts(md));
	}

	@Test
	public void onlyChangedStatsShow()
	{
		// The Sire: Defence alone; no Magic, no magic defence block.
		final PanelSnapshot.Defence d = card(SidePanelDrainsExamples.sire(), false);
		assertEquals(1, d.blocks.size());
		assertEquals("Defence", d.blocks.get(0).name.text);
		// A single source: its own value says it all, no sum line's figure.
		final List<String> lines = texts(d.blocks.get(0));
		assertEquals("for your melee & ranged |", lines.get(lines.size() - 1));
	}

	@Test
	public void forYourComesFromTheStatAndTheRules()
	{
		assertEquals("for your melee, ranged & magic |",
			texts(card(SidePanelDrainsExamples.magicOffDefence(), true).blocks.get(0)).get(3));
		assertEquals(java.util.Arrays.asList("for your melee & ranged", "for melee & ranged", "melee & ranged"),
			SidePanelModel.helps(DrainRecord.StatDrain.DEFENCE, false));
		assertEquals(java.util.Arrays.asList("for your magic", "magic"),
			SidePanelModel.helps(DrainRecord.StatDrain.MAGIC_DEFENCE, false));
		assertEquals(java.util.Arrays.asList("for your magic", "magic"), SidePanelModel.helps(DrainRecord.StatDrain.MAGIC,
			true));
		// From boss-rules.json, not a name list: the Ice demon and Verzik roll magic defence off Defence, Yama doesn't.
		final BossRules rules = BossRules.load(new com.google.gson.Gson(), null);
		assertTrue(SidePanelModel.magicUsesDefence(rules, fights(7584)));
		assertFalse(SidePanelModel.magicUsesDefence(rules, fights(net.runelite.api.gameval.NpcID.YAMA)));
		assertFalse(SidePanelModel.magicUsesDefence(null, fights(7584)));
	}

	private static List<Fight> fights(int npcId)
	{
		return java.util.Collections.singletonList(new Fight("x", npcId, 1, 100, 0L, null, 0));
	}

	@Test
	public void anOldRecordShowsTodaysCard()
	{
		final PanelSnapshot.Defence d = card(SidePanelDrainsExamples.old(), false);
		assertNull(d.blocks);
		assertNotNull(d.drop);
		assertEquals("250 → 175", d.hint.text);
		// Through the history's JSON, a record without the per-stat fields reads them null.
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final DrainRecord loaded = gson.fromJson("{\"version\":1,\"defenceStart\":250,\"defenceEnd\":175,"
			+ "\"specs\":{\"DRAGON_WARHAMMER\":[2,1]}}", DrainRecord.class);
		assertNull(loaded.stats);
		final Encounter room = SidePanelDrainsExamples.room("General Graardor", 1, loaded);
		assertNull(SidePanelModel.statParts(room));
	}

	@Test
	public void theCardIsTitledDrainsAndBuiltOncePerStructure()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelDrainsExamples.kill(SidePanelDrainsExamples.yama(),
			false))));
		final SidePanelSection s = SidePanelPictures.sectionOf(p.fightTab(), "defence");
		assertNotNull(s);
		assertTrue(texts(s).contains(SidePanelModel.DRAINS));
		final int builds = p.fightTab().builds();
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelDrainsExamples.kill(SidePanelDrainsExamples.yama(),
			false))));
		assertEquals(builds, p.fightTab().builds());
		// An old kill keeps the title it had.
		final PvmPerformancePanel q = new PvmPerformancePanel(null, null, null, null);
		q.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelDrainsExamples.kill(SidePanelDrainsExamples.old(),
			false))));
		assertTrue(texts(SidePanelPictures.sectionOf(q.fightTab(), "defence")).contains("Defence"));
	}

	@Test
	public void nothingIsCutAtAnySize()
	{
		final Encounter[] rooms = {SidePanelDrainsExamples.yama(), SidePanelDrainsExamples.graardor(),
			SidePanelDrainsExamples.floorAndParty(), SidePanelDrainsExamples.soulRendsMissed(),
			SidePanelDrainsExamples.magicOffDefence()};
		// Every cut at once, so a machine with other fallback fonts (CI) names them all in one run.
		final List<String> cut = new ArrayList<>();
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					for (Encounter r : rooms)
					{
						final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
						p.setText(larger, smooth);
						try
						{
							p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelDrainsExamples.kill(r, r ==
								rooms[4]))));
							SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
							for (Component c : all(SidePanelPictures.sectionOf(p.fightTab(), "defence")))
							{
								if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null)
								{
									final String shown = ((SidePanelText) c).lastShown();
									if (shown.contains(LedgerRows.CUT))
									{
										cut.add(larger + " " + smooth + " " + scale + " " + r.getName() + ": " + shown);
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
		assertTrue(String.join("\n", cut), cut.isEmpty());
	}

	private static List<String> texts(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : all(c))
		{
			if (k instanceof SidePanelText)
			{
				out.add(((SidePanelText) k).getText());
			}
		}
		return out;
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

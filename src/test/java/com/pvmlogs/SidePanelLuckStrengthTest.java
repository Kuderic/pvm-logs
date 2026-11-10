package com.pvmlogs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-194: a roll in three strengths of luck's violet: dim (1 to 25), normal (26 to 75), bright (76 to 100). They differ
 * in lightness, each text readable on its chip, never in skill's colours (gold and green are S and A, orange is ticks
 * lost, and the overlay's better and cost), with the roll's words on hover.
 */
public class SidePanelLuckStrengthTest
{
	private static final Set<Color> INKS = new HashSet<>(Arrays.asList(SidePanelStyle.LUCK_DIM, SidePanelStyle.LUCK,
		SidePanelStyle.LUCK_BRIGHT));
	private static final Set<Color> CHIP_INKS = new HashSet<>(Arrays.asList(SidePanelStyle.LUCK_CHIP_DIM_INK,
		SidePanelStyle.LUCK, SidePanelStyle.LUCK_CHIP_BRIGHT_INK));
	private static final Set<Color> CHIP_FILLS = new HashSet<>(Arrays.asList(SidePanelStyle.LUCK_CHIP_DIM_FILL,
		SidePanelStyle.LUCK_CHIP_FILL, SidePanelStyle.LUCK_CHIP_BRIGHT_FILL));

	/** Skill's colours, and every band colour the overlay uses, with their chip fills. */
	private static Set<Color> skill()
	{
		final Set<Color> s = new HashSet<>(Arrays.asList(SidePanelStyle.GOLD, SidePanelStyle.GRADE_A,
			SidePanelModel.gradeColour("S"), SidePanelModel.gradeColour("A"), SidePanelStyle.COST, SidePanelStyle.BETTER));
		for (LedgerRows.Colour c : LedgerRows.Colour.values())
		{
			if (c != LedgerRows.Colour.NEUTRAL)
			{
				s.add(SidePanelStyle.of(c));
				s.add(SidePanelStyle.chip(c));
			}
		}
		return s;
	}

	private static double luminance(Color c)
	{
		final double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
		for (int i = 0; i < 3; i++)
		{
			v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
	}

	private static double contrast(Color a, Color b)
	{
		final double x = luminance(a);
		final double y = luminance(b);
		return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
	}

	private static double p(int roll)
	{
		return roll / 100.0;
	}

	@Test
	public void theBandsEdges()
	{
		assertEquals(SidePanelModel.LuckStrength.DIM, SidePanelModel.strength(1));
		assertEquals(SidePanelModel.LuckStrength.DIM, SidePanelModel.strength(25));
		assertEquals(SidePanelModel.LuckStrength.NORMAL, SidePanelModel.strength(26));
		assertEquals(SidePanelModel.LuckStrength.NORMAL, SidePanelModel.strength(75));
		assertEquals(SidePanelModel.LuckStrength.BRIGHT, SidePanelModel.strength(76));
		assertEquals(SidePanelModel.LuckStrength.BRIGHT, SidePanelModel.strength(100));
		// By the roll shown, so the chip's number and its strength never disagree: 25.4 shows "rolled 25", dim.
		assertEquals("rolled 25", SidePanelModel.rolled(0.254));
		assertEquals(SidePanelModel.LuckStrength.DIM, SidePanelModel.strength(0.254));
		assertEquals("rolled 76", SidePanelModel.rolled(0.755));
		assertEquals(SidePanelModel.LuckStrength.BRIGHT, SidePanelModel.strength(0.755));
		assertEquals(SidePanelStyle.LUCK_CHIP_DIM_FILL, SidePanelModel.luckChipFill(p(25)));
		assertEquals(SidePanelStyle.LUCK_CHIP_FILL, SidePanelModel.luckChipFill(p(26)));
		assertEquals(SidePanelStyle.LUCK_CHIP_FILL, SidePanelModel.luckChipFill(p(75)));
		assertEquals(SidePanelStyle.LUCK_CHIP_BRIGHT_FILL, SidePanelModel.luckChipFill(p(76)));
	}

	@Test
	public void everyRollUsesOneOfTheThreeAndNoSkillColour()
	{
		final Set<Color> skill = skill();
		for (int r = 1; r <= 100; r++)
		{
			final Color ink = SidePanelModel.luckInk(p(r));
			final Color chipInk = SidePanelModel.luckChipInk(p(r));
			final Color fill = SidePanelModel.luckChipFill(p(r));
			assertTrue("rolled " + r, INKS.contains(ink) && CHIP_INKS.contains(chipInk) && CHIP_FILLS.contains(fill));
			assertFalse("rolled " + r, skill.contains(ink) || skill.contains(chipInk) || skill.contains(fill));
		}
	}

	@Test
	public void theyDifferInLightnessAndEachReads()
	{
		// Lighter at each step, chip and text alone, so a reader without hue still sees it.
		assertTrue(luminance(SidePanelStyle.LUCK_CHIP_DIM_FILL) < luminance(SidePanelStyle.LUCK_CHIP_FILL));
		assertTrue(luminance(SidePanelStyle.LUCK_CHIP_FILL) < luminance(SidePanelStyle.LUCK_CHIP_BRIGHT_FILL));
		assertTrue(luminance(SidePanelStyle.LUCK_CHIP_DIM_INK) < luminance(SidePanelStyle.LUCK));
		assertTrue(luminance(SidePanelStyle.LUCK) < luminance(SidePanelStyle.LUCK_CHIP_BRIGHT_INK));
		assertTrue(luminance(SidePanelStyle.LUCK_DIM) < luminance(SidePanelStyle.LUCK));
		assertTrue(luminance(SidePanelStyle.LUCK) < luminance(SidePanelStyle.LUCK_BRIGHT));
		// Each text at 4.5 or more on its chip, and alone on the card and the tiles.
		final StringBuilder said = new StringBuilder("T-194 contrast:");
		for (int r : new int[]{9, 50, 96})
		{
			final double c = contrast(SidePanelModel.luckChipInk(p(r)), SidePanelModel.luckChipFill(p(r)));
			said.append(String.format(" chip %d %.2f;", r, c));
			assertTrue("chip " + r + ": " + c, c >= 4.5);
			for (Color bg : new Color[]{SidePanelStyle.CARD, SidePanelStyle.PANEL})
			{
				final double t = contrast(SidePanelModel.luckInk(p(r)), bg);
				said.append(String.format(" text %d on %06X %.2f;", r, bg.getRGB() & 0xFFFFFF, t));
				assertTrue("text " + r + ": " + t, t >= 4.5);
			}
		}
		System.out.println(said);
	}

	@Test
	public void theTooltipsWords()
	{
		assertEquals("rolled 96 of 100 · top 4% · dealt 112% of expected · 9 s faster",
			SidePanelModel.luckWords(0.96, 1120, 1000, 9));
		assertEquals("rolled 9 of 100 · bottom 9% · dealt 88% of expected · 14 s slower",
			SidePanelModel.luckWords(0.09, 880, 1000, -14));
		assertEquals("rolled 50 of 100 · middle half · dealt 101% of expected · 1 s faster",
			SidePanelModel.luckWords(0.5, 1010, 1000, 1));
		// Rolled 100 is the top 1%, not 0%; the parts not known are left out.
		assertEquals("rolled 100 of 100 · top 1%", SidePanelModel.luckWords(1.0, 0, 0, Double.NaN));
		assertEquals("bottom 25%", SidePanelModel.luckBand(0.25));
		assertEquals("middle half", SidePanelModel.luckBand(0.26));
		assertEquals("top 24%", SidePanelModel.luckBand(0.76));
	}

	// ---- on the tabs ----

	@Test
	public void theFightTabsRollsAndWords()
	{
		// The Vardorvis example's kill rolled 20: dim, on the tile, its edge, the curve's header and the budget's row.
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		PanelSnapshot.Tile luck = null;
		for (PanelSnapshot.Tile tile : t.tiles)
		{
			if (tile.label.text.equals("Luck"))
			{
				luck = tile;
			}
		}
		assertNotNull(luck);
		assertEquals("rolled 20", luck.value.text);
		assertEquals(SidePanelStyle.LUCK_DIM, luck.value.colour);
		assertEquals(SidePanelStyle.LUCK_DIM, luck.edge);
		assertTrue(luck.tooltip, luck.tooltip.startsWith("<html>rolled 20 of 100 · bottom 20% · dealt "));
		assertTrue(luck.tooltip, luck.tooltip.contains("14 s slower<br>" + SidePanelModel.TICK_NOTE));
		assertEquals(SidePanelStyle.LUCK_DIM, t.curve.headerValue.colour);
	}

	@Test
	public void theCardsNumberAndItsWords()
	{
		// Eric's Araxxor kill rolled 81: bright, the words on its tile; live, the die rolls with no words.
		final PanelSnapshot.KillCard card = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertEquals(81, card.rolled);
		assertEquals(SidePanelModel.LuckStrength.BRIGHT, SidePanelModel.strength(card.rolled));
		assertTrue(card.luckWords, card.luckWords.startsWith("rolled 81 of 100 · top 19% · dealt 112% of expected · "));
		assertNull(SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card.luckWords);
		// Its badges keep them; under the roll, its effect in words, in its strength.
		assertEquals(card.luckWords, card.withBadges(new ArrayList<>()).luckWords);
		assertTrue(card.luckLine.text, card.luckLine.text.startsWith("lucky · "));
		assertEquals(SidePanelStyle.LUCK_BRIGHT, card.luckLine.colour);
		final SidePanelModel.Input unlucky = SidePanelKillCardExamples.vardorvisKill();
		assertEquals("unlucky · 14 s slower", SidePanelModel.fight(unlucky).card.luckLine.text);
	}

	@Test
	public void withCelebrationsOffTheTilesUseTheSameStrengths()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelKillCardExamples.off());
		assertNull(t.card);
		for (PanelSnapshot.Tile tile : t.tiles)
		{
			if (tile.label.text.equals("Luck"))
			{
				assertEquals("rolled 81", tile.value.text);
				assertEquals(SidePanelStyle.LUCK_BRIGHT, tile.value.colour);
				return;
			}
		}
		throw new AssertionError("no Luck tile");
	}

	@Test
	public void historysKillRowsEachInItsStrengthWithItsWords()
	{
		final Set<Color> skill = skill();
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(SidePanelHistoryExamples.history(),
			"Vardorvis", SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE);
		int rolls = 0;
		for (SidePanelHistory.KillRow r : rows)
		{
			if (r.chip == null || !r.chip.text.startsWith("rolled"))
			{
				continue;
			}
			rolls++;
			final int roll = Integer.parseInt(r.chip.text.substring("rolled ".length()));
			assertEquals(r.chip.text, SidePanelModel.luckChipInk(p(roll)), r.chip.colour);
			assertEquals(r.chip.text, SidePanelModel.luckChipFill(p(roll)), r.chipFill);
			assertFalse(r.chip.text, skill.contains(r.chip.colour) || skill.contains(r.chipFill));
			assertNotNull(r.chip.text, r.chipTip);
			assertTrue(r.chipTip, r.chipTip.startsWith(SidePanelModel.rolledOf(p(roll)) + " · "
				+ SidePanelModel.luckBand(p(roll)) + " · dealt "));
			// The row, not the chip, answers the hover: the chip leaves the row its clicks.
			final SidePanelKillRow view = SidePanelKillRow.history(r, () -> { });
			assertEquals(r.chipTip, view.chipTip());
		}
		assertTrue(rolls >= 10);
	}

	@Test
	public void damageOpensByDefaultWithCelebrationsOn()
	{
		// A fresh setup: Damage open, live and after the kill; the rest folded under the card with their summaries.
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		for (SidePanelModel.Input in : Arrays.asList(SidePanelKillCardExamples.araxxorLive(),
			SidePanelKillCardExamples.araxxorKill()))
		{
			p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
			final List<SidePanelSection> sections = new ArrayList<>();
			collect(p.fightTab(), sections);
			boolean damage = false;
			for (SidePanelSection s : sections)
			{
				if (s.id().equals("damage"))
				{
					damage = true;
					assertFalse("Damage open", s.isFolded());
				}
				else if (PvmPerformancePanel.FOLDED_UNDER_CARD.contains(s.id()))
				{
					assertTrue(s.id() + " folded", s.isFolded());
				}
			}
			assertTrue(damage);
		}
	}

	private static void collect(java.awt.Container c, List<SidePanelSection> out)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				out.add((SidePanelSection) k);
			}
			if (k instanceof java.awt.Container)
			{
				collect((java.awt.Container) k, out);
			}
		}
	}

	@Test
	public void ericsKillsListReplays()
	{
		// 92, 96, 94 and 99 stand out, bright; 9 sinks, dim; 26 to 28 are the middle half's edge, normal.
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(SidePanelLuckExamples.history(), "Araxxor",
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE);
		final List<String> chips = new ArrayList<>();
		final List<SidePanelModel.LuckStrength> strengths = new ArrayList<>();
		for (SidePanelHistory.KillRow r : rows)
		{
			chips.add(r.chip.text);
			strengths.add(SidePanelModel.strength(Integer.parseInt(r.chip.text.substring("rolled ".length()))));
			assertEquals(SidePanelModel.luckChipFill(Integer.parseInt(r.chip.text.substring(7)) / 100.0), r.chipFill);
		}
		final List<String> want = new ArrayList<>();
		for (int roll : SidePanelLuckExamples.ROLLS)
		{
			want.add("rolled " + roll);
		}
		assertEquals(want, chips);
		final SidePanelModel.LuckStrength d = SidePanelModel.LuckStrength.DIM;
		final SidePanelModel.LuckStrength n = SidePanelModel.LuckStrength.NORMAL;
		final SidePanelModel.LuckStrength b = SidePanelModel.LuckStrength.BRIGHT;
		assertEquals(Arrays.asList(n, n, b, n, d, b, b, b, n, n, b, n, n, n), strengths);
	}

	@Test
	public void thePartysRollsInTheirStrengths()
	{
		final PanelSnapshot.PartyTab after = SidePanelParty.party(SidePanelPartyExamples.toa());
		assertEquals(SidePanelStyle.LUCK_BRIGHT, after.rows.get(0).luckColour);
		assertEquals(SidePanelStyle.LUCK_DIM, after.rows.get(2).luckColour);
	}
}

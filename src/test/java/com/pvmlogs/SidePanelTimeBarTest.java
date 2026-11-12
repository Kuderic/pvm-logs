package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * T-214 (Eric, 2026-11-11 16:48: "Where the time went score bar changes what makes up the bar when you expand it, can we
 * keep a consistent design here"; "the skill card at the very top with the small bar under it just shows where the
 * player was idle but this is usually in small increments that are hard to see on the bar, is there a better design";
 * 16:53: "Lets go with A"): one time bar at two sizes, and the live Skill tile's last 10 attacks.
 */
public class SidePanelTimeBarTest
{
	@After
	public void plainText()
	{
		SidePanelStyle.setText(false, false);
	}

	// ---- examples ----

	/**
	 * Eric's Yama example (illustrative figures, his kill's shape): 3:00, skill -3.6 s (4 idle ticks and 1.2 s of
	 * setup), luck -29 s at the bottom 2%, with eating (5 ticks) and the boss's mechanics (15 ticks).
	 */
	static SidePanelModel.Input yama()
	{
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 300;
		s.expectedDamage = 2000;
		s.damage = 1678;
		s.damageVariance = 24_650;
		s.ticksLost = 9;
		s.ticksLostEating = 5;
		s.ticksMechanics = 15;
		s.damageTaken = 210;
		s.biggestHitTaken = 31;
		s.attacksMade = 45;
		s.attacksPrayed = 45;
		s.attacksSwitched = 45;
		s.hits = 38;
		s.expectedHits = 39.4;
		s.efficiency = 1 - 1.2 / 165.6;
		s.boostShare = 0.95;
		s.boostCostShare = 0.005;
		s.switchCostShare = 0.0005;
		in.scope = s;
		in.name = "Yama";
		in.scoredName = "Yama";
		in.maxHp = 2500;
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(165, 152, 181), 20);
		in.luckRemoved = null;
		in.standing = null;
		return in;
	}

	private static List<SidePanelModel.Input> kills()
	{
		final SidePanelModel.Input stall = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) stall.scope).ticksMechanics = 25;
		((LedgerExamples.FakeScope) stall.scope).ticksStall = 20;
		return Arrays.asList(SidePanelKillCardExamples.araxxorKill(), yama(), SidePanelKillCardExamples.vardorvisKill(),
			SidePanelExamples.luckSaved(15), SidePanelExamples.luckSaved(-7), SidePanelExamples.unscoredAdds(),
			SidePanelExamples.raid(), stall);
	}

	// ---- one bar, two sizes ----

	/** The Where the time went section, folded or open, painted at the panel's width. */
	private static SidePanelSection painted(SidePanelModel.Input in, boolean open)
	{
		final PvmPerformancePanel p = SidePanelKillCardTest.panel(in);
		final SidePanelSection s = SidePanelPictures.sectionOf(p.fightTab(), "budget");
		assertNotNull(in.name, s);
		if (s.isFolded() == open)
		{
			s.toggle();
		}
		s.setSize(SidePanelStyle.CONTENT_WIDTH, s.getPreferredSize().height);
		SidePanelPictures.layout(s);
		final BufferedImage img = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		s.paint(g);
		g.dispose();
		return s;
	}

	private static SidePanelTimeBudgetBar bar(Container c)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelTimeBudgetBar)
			{
				return (SidePanelTimeBudgetBar) k;
			}
			if (k instanceof Container)
			{
				final SidePanelTimeBudgetBar b = bar((Container) k);
				if (b != null)
				{
					return b;
				}
			}
		}
		return null;
	}

	@Test
	public void foldedAndOpenDrawTheSameSegmentsInTheSameOrderAndColours()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			for (SidePanelModel.Input in : kills())
			{
				final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
				final SidePanelSection folded = painted(in, false);
				final PanelSnapshot.Mark mark = folded.summaryMark();
				assertEquals(PanelSnapshot.Mark.Kind.TIME, mark.kind);
				final List<PanelSnapshot.Fill> small = new ArrayList<>(SidePanelSection.summaryBarDrawn());
				final SidePanelTimeBudgetBar open = bar(painted(in, true));
				assertNotNull(open);
				final List<PanelSnapshot.Fill> big = open.drawn;
				assertFalse(in.name, big.isEmpty());
				assertEquals(in.name + " " + larger, big, small);
				// Luck's stripes in one colour at both sizes: its violet.
				assertEquals(b.luckStripe, mark.budget.luckStripe);
				assertTrue(b.luckStripe.equals(SidePanelStyle.LUCK) || b.luckStripe.equals(SidePanelStyle.LUCK_DIM)
					|| b.luckStripe.equals(SidePanelStyle.LUCK_BRIGHT));
				// The legend: a swatch per listed segment, in the bar's order and fills.
				final List<PanelSnapshot.Fill> listed = new ArrayList<>();
				for (PanelSnapshot.Segment s : b.segments)
				{
					if (s.listed)
					{
						listed.add(s.fill);
					}
				}
				final List<PanelSnapshot.Fill> swatches = new ArrayList<>();
				for (PanelSnapshot.BudgetRow r : b.rows)
				{
					swatches.add(r.swatch);
				}
				assertEquals(in.name, listed, swatches);
				// Every listed stretch with any seconds is on both bars.
				for (PanelSnapshot.Segment s : b.segments)
				{
					if (s.listed && s.seconds > 0)
					{
						assertTrue(in.name + " " + s.fill, small.contains(s.fill));
					}
				}
			}
		}
	}

	@Test
	public void theBarsPixelsAreTheSameColoursAtBothHeights()
	{
		final SidePanelModel.Input in = yama();
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
		final int w = 200;
		final BufferedImage small = bar(b, w, 6);
		final BufferedImage big = bar(b, w, SidePanelTimeBudgetBar.BAR_HEIGHT);
		final int[] edges = SidePanelTimeBudgetBar.edges(b, w);
		int solid = 0;
		for (int i = 0; i < b.segments.size(); i++)
		{
			if (edges[i + 1] - edges[i] < 1 || b.segments.get(i).fill.luck())
			{
				continue;
			}
			final int x = (edges[i] + edges[i + 1] - 1) / 2;
			assertEquals(b.segments.get(i).fill + " at " + x, big.getRGB(x, 8), small.getRGB(x, 3));
			solid++;
		}
		assertTrue(String.valueOf(solid), solid >= 5);
		// Yama's parts: perfect, idle, the setup parts, eating, mechanics, luck.
		final List<PanelSnapshot.Fill> fills = new ArrayList<>();
		for (PanelSnapshot.Segment s : b.segments)
		{
			if (s.seconds > 0)
			{
				fills.add(s.fill);
			}
		}
		assertEquals(PanelSnapshot.Fill.KEPT, fills.get(0));
		assertTrue(fills.get(1).skill());
		assertTrue(fills.indexOf(PanelSnapshot.Fill.EATING) < fills.indexOf(PanelSnapshot.Fill.MECHANICS));
		assertTrue(fills.get(fills.size() - 1).luck());
		for (int i = 1; i < fills.indexOf(PanelSnapshot.Fill.EATING); i++)
		{
			assertTrue(fills.toString(), fills.get(i).skill());
		}
	}

	private static BufferedImage bar(PanelSnapshot.TimeBudget b, int w, int h)
	{
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		SidePanelTimeBudgetBar.paintBar(g, b, 0, 0, w, h);
		g.dispose();
		return img;
	}

	@Test
	public void theFoldedSkillFigureIsTheSkillSegmentsSum()
	{
		for (SidePanelModel.Input in : kills())
		{
			final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
			final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
			double sum = 0;
			for (PanelSnapshot.Segment s : b.segments)
			{
				sum += s.fill.skill() ? s.seconds : 0;
			}
			assertEquals(in.name, sum, b.skillSeconds(), 1e-9);
			// The same number as lostSeconds: the setup parts split the setup's seconds exactly.
			assertEquals(in.name, Math.max(0, SidePanelModel.lostSeconds(n, SidePanelModel.avoidableShown(n, in))),
				b.skillSeconds(), 1e-9);
			final String line = painted(in, false).summary();
			assertTrue(line, line.startsWith(sum >= 0.05 ? "skill -" + SidePanelModel.secs(sum) : "skill ±0 s"));
		}
		// Eric's Yama example reads as his did.
		assertEquals("skill -3.6 s · luck -29 s", painted(yama(), false).summary());
	}

	@Test
	public void aThinListedStretchIsStillAPixelInItsPlace()
	{
		// One idle tick in a 10-minute fight: 0.6 of 600 s, 0.2 px of a 200 px bar.
		final SidePanelModel.Input in = yama();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.combatTicks = 1000;
		s.ticksLost = 6;
		s.ticksLostEating = 5;
		s.efficiency = 1;
		s.boostCostShare = 0;
		s.switchCostShare = 0;
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
		final int idle = 1;
		assertTrue(b.segments.get(idle).fill.skill());
		assertEquals(0.6, b.segments.get(idle).seconds, 1e-9);
		final int[] edges = SidePanelTimeBudgetBar.edges(b, 200);
		assertEquals(1, edges[idle + 1] - edges[idle]);
		// In its place: after perfect, before eating.
		assertTrue(edges[idle] > 150);
		assertEquals(200, edges[edges.length - 1]);
		assertTrue(bar(b, 200, 6).getRGB(edges[idle], 3) == SidePanelStyle.COST.getRGB()
			|| bar(b, 200, 6).getRGB(edges[idle], 3) == SidePanelStyle.MINOR_COST.getRGB());
	}

	// ---- the live Skill tile's last 10 attacks ----

	/** A fight's timeline: {@code at} attack ticks, the states given, cooldown elsewhere. */
	private static FightTimeline.View view(int length, int[] at, int[] lost, int[] eating, int[] mechanic)
	{
		final byte[] states = new byte[length];
		Arrays.fill(states, FightTimeline.COOLDOWN);
		final List<FightTimeline.Attack> attacks = new ArrayList<>();
		for (int t : at)
		{
			states[t] = FightTimeline.ATTACK;
			attacks.add(new FightTimeline.Attack(100 + t));
		}
		for (int t : lost)
		{
			states[t] = FightTimeline.LOST;
		}
		for (int t : eating)
		{
			states[t] = FightTimeline.EATING;
		}
		for (int t : mechanic)
		{
			states[t] = FightTimeline.MECHANIC;
		}
		return new FightTimeline.View(100, states, new float[length], new float[length], new float[length], attacks);
	}

	/** Every 4 ticks, {@code n} attacks; nothing late. */
	private static int[] every4(int n)
	{
		final int[] at = new int[n];
		for (int i = 0; i < n; i++)
		{
			at[i] = i * 4;
		}
		return at;
	}

	@Test
	public void aOneTickSlipIsOneLightOrangeCell()
	{
		// 12 attacks; the 11th a tick late (tick 41 idle), the 12th two late.
		final int[] at = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 41, 47};
		final SidePanelModel.Delays d = SidePanelModel.delays(view(50, at, new int[]{40, 45, 46}, new int[0], new int[0]),
			PanelSnapshot.KillCard.CELLS);
		assertEquals("[0, 0, 0, 0, 0, 0, 0, 0, 1, 2]", Arrays.toString(d.last));
		assertEquals(2, d.late);
		assertEquals(12, d.attacks);
		final List<Color> cells = cells(d.last);
		assertEquals(10, cells.size());
		assertEquals(SidePanelStyle.LATE_ONE, cells.get(8));
		assertEquals(SidePanelStyle.COST, cells.get(9));
		for (int i = 0; i < 8; i++)
		{
			assertEquals(SidePanelStyle.KEPT, cells.get(i));
		}
		assertEquals("Your last 10 attacks, seconds late, newest last: 0, 0, 0, 0, 0, 0, 0, 0, 0.6, 1.2.",
			SidePanelModel.lastAttacksLine(d.last));
	}

	@Test
	public void eatingAndAMechanicsExcusedTicksArentLate()
	{
		// Eating ticks 9 and 10 before the 3rd attack, a mechanic's 15 to 18 before the 5th, the adds' (unscored) before
		// the 6th, and a gap between phases (none) before the 7th.
		final int[] at = {0, 4, 11, 15, 19, 26, 34};
		final FightTimeline.View v = view(40, at, new int[0], new int[]{9, 10}, new int[]{16, 17, 18});
		v.states[24] = FightTimeline.UNSCORED;
		v.states[25] = FightTimeline.UNSCORED;
		v.states[31] = FightTimeline.NONE;
		v.states[32] = FightTimeline.NONE;
		final SidePanelModel.Delays d = SidePanelModel.delays(v, PanelSnapshot.KillCard.CELLS);
		assertEquals(0, d.late);
		for (int late : d.last)
		{
			assertEquals(0, late);
		}
		// The same ticks idle are late.
		final SidePanelModel.Delays idle = SidePanelModel.delays(view(40, at, new int[]{9, 10, 16, 17, 18}, new int[0],
			new int[0]), PanelSnapshot.KillCard.CELLS);
		assertEquals("[0, 0, 2, 0, 3, 0, 0]", Arrays.toString(idle.last));
	}

	@Test
	public void fewerThanTenAttacksDrawOutlinesForTheRest()
	{
		final SidePanelModel.Delays d = SidePanelModel.delays(view(12, every4(3), new int[0], new int[0], new int[0]),
			PanelSnapshot.KillCard.CELLS);
		assertEquals(3, d.last.length);
		final List<Color> cells = cells(d.last);
		assertEquals(10, cells.size());
		for (int i = 0; i < 10; i++)
		{
			assertEquals(String.valueOf(i), i < 3 ? SidePanelStyle.KEPT : null, cells.get(i));
		}
		// Before the first attack: ten outlines.
		final List<Color> none = cells(new int[0]);
		assertEquals(10, none.size());
		for (Color c : none)
		{
			assertNull(c);
		}
	}

	@Test
	public void theCellsAreLevelWithLucksTrackAndFillItsWidth()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
			final SidePanelKillCard view = card(c);
			assertEquals(view.trackY, view.cellsY);
			assertEquals(view.trackX - view.luckBox().x, view.cellsX - view.playBox().x);
			assertEquals(10, view.cells.size());
			// After the kill, nothing under Skill.
			final SidePanelKillCard kill = card(SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card);
			assertEquals(-1, kill.cellsY);
			assertTrue(kill.cells.isEmpty());
		}
	}

	@Test
	public void theTooltipSaysTheDelaysTheLateCountAndTheColours()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		final String tip = c.skillTip;
		assertTrue(tip, tip.contains("Your last 10 attacks, seconds late, newest last: "));
		assertTrue(tip, tip.contains("Late so far: 1 of 17 attacks."));
		assertTrue(tip, tip.contains("idle 1.2 s of 42 s"));
		assertTrue(tip, tip.contains(SidePanelModel.CELLS_KEY));
	}

	@Test
	public void theCellsChangeOnlyWhenAnAttackIsBooked()
	{
		// Ticks passing on cooldown leave the cells as they were; the next attack moves them on.
		final SidePanelModel.Delays before = SidePanelModel.delays(view(30, every4(7), new int[0], new int[0],
			new int[0]), PanelSnapshot.KillCard.CELLS);
		final SidePanelModel.Delays idle = SidePanelModel.delays(view(32, every4(7), new int[]{29, 30, 31}, new int[0],
			new int[0]), PanelSnapshot.KillCard.CELLS);
		assertEquals(Arrays.toString(before.last), Arrays.toString(idle.last));
		final int[] next = Arrays.copyOf(every4(7), 8);
		next[7] = 32;
		final SidePanelModel.Delays booked = SidePanelModel.delays(view(33, next, new int[]{29, 30, 31}, new int[0],
			new int[0]), PanelSnapshot.KillCard.CELLS);
		assertEquals(8, booked.last.length);
		assertEquals(3, booked.last[7]);
	}

	// ---- helpers ----

	private static SidePanelKillCard card(PanelSnapshot.KillCard c)
	{
		final SidePanelKillCard view = new SidePanelKillCard(c, null);
		final int h = view.getPreferredSize().height;
		view.setSize(SidePanelStyle.CONTENT_WIDTH, h);
		final BufferedImage img = new BufferedImage(SidePanelStyle.CONTENT_WIDTH, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		view.paint(g);
		g.dispose();
		return view;
	}

	/** The live card painted with these delays: its cells' colours, null for an outline. */
	private static List<Color> cells(int[] delays)
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		c.attacks = delays;
		return card(c).cells;
	}
}

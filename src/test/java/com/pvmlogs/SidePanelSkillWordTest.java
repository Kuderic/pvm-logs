package com.pvmlogs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-199 (Eric, 2026-11-10, on {@code cab94cb}): one word for skill, every summary number labelled with one sign rule,
 * Damage open once, the live luck bar, no "null" in a tooltip, and expected DPS back on the card and the weapon rows.
 */
public class SidePanelSkillWordTest
{
	/** The files whose string literals the player reads: the panel, the overlay's rows and the settings' text. */
	private static final List<String> USER_FACING = Arrays.asList("LedgerRows.java", "PvmPerformanceConfig.java",
		"PvmPerformanceOverlay.java", "PanelSnapshot.java", "ExecutionParts.java");

	/** Literals that are code names (fold keys, section ids), never shown. */
	private static final List<String> CODE_NAMES = Arrays.asList("execution", "tripExecution");

	@Test
	public void noExecutionOrYourPlayInWhatThePlayerReads() throws IOException
	{
		final Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
		final List<String> found = new ArrayList<>();
		try (java.util.stream.Stream<Path> walk = Files.list(Paths.get("src/main/java/com/pvmlogs")))
		{
			for (Path f : (Iterable<Path>) walk::iterator)
			{
				final String name = f.getFileName().toString();
				if (!name.startsWith("SidePanel") && !name.equals("PvmPerformancePanel.java") && !USER_FACING.contains(name))
				{
					continue;
				}
				int n = 0;
				for (String line : Files.readAllLines(f, StandardCharsets.UTF_8))
				{
					n++;
					final String code = line.trim();
					// Comments and debug log lines aren't read by the player.
					if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.contains("log.")
						|| code.contains("debug.log("))
					{
						continue;
					}
					final Matcher m = literal.matcher(line);
					while (m.find())
					{
						final String s = m.group(1);
						if (CODE_NAMES.contains(s))
						{
							continue;
						}
						final String low = s.toLowerCase(java.util.Locale.ROOT);
						if (low.contains("execution") || low.contains("your play") || low.contains("luck removed")
							|| low.contains("luck-removed"))
						{
							found.add(name + ":" + n + " \"" + s + "\"");
						}
					}
				}
			}
		}
		assertTrue(String.join("\n", found), found.isEmpty());
	}

	@Test
	public void theRenderedPanelSaysSkillNotExecution()
	{
		final List<String> seen = new ArrayList<>();
		for (SidePanelModel.Input in : inputs())
		{
			final PvmPerformancePanel p = SidePanelKillCardTest.panel(in);
			openAll(p.fightTab());
			seen.addAll(strings(p.fightTab()));
		}
		final PvmPerformancePanel off = SidePanelKillCardTest.panel(SidePanelKillCardExamples.off());
		off.setCelebrations(false);
		off.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.off())));
		seen.addAll(strings(off.fightTab()));
		for (String s : seen)
		{
			final String low = s.toLowerCase(java.util.Locale.ROOT);
			assertFalse(s, low.contains("execution") || low.contains("your play"));
		}
		// The section and the tile both say Skill.
		final PvmPerformancePanel p = SidePanelKillCardTest.panel(SidePanelKillCardExamples.araxxorKill());
		assertEquals(SidePanelModel.SKILL, SidePanelPictures.sectionOf(p.fightTab(), "execution").title());
		assertEquals("Skill", SidePanelModel.SKILL);
		assertTrue(SidePanelKillCard.blocks(p.fightTab().getComponent(0) instanceof SidePanelKillCard
			? ((SidePanelKillCard) p.fightTab().getComponent(0)).card() : null).contains("Skill"));
		// The overlay's row and the party meter's word.
		assertEquals("skill", SidePanelParty.Metric.EXEC.word());
		assertEquals("Boss skill", SidePanelModel.EXECUTION_NO_RUNS);
	}

	@Test
	public void skillsPartsReadInTheWordsPlayersUse()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill());
		final List<String> names = new ArrayList<>();
		for (PanelSnapshot.Part part : t.execution.parts)
		{
			names.add(part.label.text);
		}
		assertTrue(names.toString(), names.containsAll(Arrays.asList("Uptime", "Offensive prayers", "Gear", "Boosts")));
		for (String old : new String[]{"Attacking", "Right prayers", "Best gear worn", "Stats boosted"})
		{
			assertFalse(names.toString(), names.contains(old));
		}
		assertTrue(SidePanelModel.PERFECT, SidePanelModel.PERFECT.contains("full uptime"));
		assertFalse(SidePanelModel.PERFECT, SidePanelModel.PERFECT.contains("attacking"));
		final PanelSnapshot.KillCard live = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		assertTrue(live.skillTip, live.skillTip.startsWith("Uptime "));
		assertFalse(live.skillTip, live.skillTip.contains("attacking"));
	}

	@Test
	public void everySummaryNumberHasItsLabel()
	{
		final Pattern signed = Pattern.compile("[+−±-]\\d");
		for (SidePanelModel.Input in : inputs())
		{
			for (Map.Entry<String, PanelSnapshot.Text> e : SidePanelModel.fight(in).summaries.entrySet())
			{
				for (String part : e.getValue().text.split(" · "))
				{
					if (signed.matcher(part).find())
					{
						assertTrue(e.getKey() + ": " + part, part.matches("[a-z]+ [+−±].*"));
					}
				}
			}
		}
		final PanelSnapshot.FightTab live = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive());
		final String play = live.summaries.get("execution").text;
		assertTrue(play, play.startsWith("uptime "));
		// The budget's runs: skill's loss orange, luck violet; and the sign rule said on the hover.
		final PanelSnapshot.Mark bar = SidePanelModel.fight(SidePanelKillCardExamples.vardorvisKill()).clear.marks
			.get("budget");
		assertEquals("skill −17 s", bar.runs[0].text);
		assertEquals(SidePanelStyle.COST, bar.runs[0].colour);
		assertEquals("luck −14 s", bar.runs[2].text);
		assertEquals(SidePanelStyle.LUCK, bar.runs[2].colour);
		assertTrue(SidePanelFightTab.BUDGET_TIP, SidePanelFightTab.BUDGET_TIP.contains("+ is time gained"));
		final PvmPerformancePanel p = SidePanelKillCardTest.panel(SidePanelKillCardExamples.araxxorKill());
		final SidePanelSection budget = SidePanelPictures.sectionOf(p.fightTab(), "budget");
		assertTrue(budget.headerTip(), budget.headerTip().replace("<br>", " ").contains(SidePanelModel.SIGN_RULE));
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertTrue(c.luckTip, c.luckTip.contains(SidePanelModel.SIGN_RULE));
		// One sign: the budget's rows lose time with a minus.
		for (PanelSnapshot.BudgetRow r : SidePanelModel.fight(SidePanelKillCardExamples.vardorvisKill()).budget.rows)
		{
			assertFalse(r.label.text + " " + r.value.text, r.value.text.startsWith("+") && !r.label.text.equals("luck"));
		}
	}

	@Test
	public void damageOpensOnceAfterAProfileThatSavedItFolded()
	{
		// A profile from before T-188 (or with Celebrations off) saved panelFold.damage=true.
		final SidePanelSection.Folds stored = new SidePanelSection.MemoryFolds();
		stored.setFolded("damage", true);
		assertTrue(PvmPerformancePanel.reopenDamageOnce(stored));
		assertFalse(stored.isFolded("damage"));
		// After that, the player's own fold is remembered.
		stored.setFolded("damage", true);
		assertFalse(PvmPerformancePanel.reopenDamageOnce(stored));
		assertTrue(stored.isFolded("damage"));
		// A new profile: open, and marked done.
		final SidePanelSection.Folds fresh = new SidePanelSection.MemoryFolds();
		assertTrue(PvmPerformancePanel.reopenDamageOnce(fresh));
		assertFalse(fresh.isFolded("damage"));
		final PvmPerformancePanel p = SidePanelKillCardTest.panel(SidePanelKillCardExamples.araxxorKill());
		assertFalse(SidePanelPictures.sectionOf(p.fightTab(), "damage").isFolded());
	}

	@Test
	public void theLuckBarShowsLiveWithNoNumber()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		assertTrue(c.live);
		assertTrue(String.valueOf(c.luckAt), c.luckAt >= 1 && c.luckAt <= 100);
		assertTrue(c.rolled <= 0);
		assertNull(c.luckTime);
		assertFalse(c.luckWord.text.isEmpty());
		final SidePanelKillCard view = new SidePanelKillCard(c, null);
		final int h = view.getPreferredSize().height;
		view.setSize(SidePanelStyle.CONTENT_WIDTH, h);
		final BufferedImage img = new BufferedImage(SidePanelStyle.CONTENT_WIDTH, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		view.paint(g);
		g.dispose();
		assertTrue("marker painted", view.markerX >= 0);
		// The marker in its strength's ink, somewhere down its column.
		final Color ink = SidePanelModel.ink(SidePanelModel.strength(c.luckAt));
		boolean inked = false;
		for (int y = 0; y < h; y++)
		{
			inked |= img.getRGB(view.markerX, y) == ink.getRGB();
		}
		assertTrue("marker in " + ink, inked);
		// No luck yet: no marker.
		final PanelSnapshot.KillCard none = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		none.luckAt = 0;
		final SidePanelKillCard empty = new SidePanelKillCard(none, null);
		empty.setSize(SidePanelStyle.CONTENT_WIDTH, h);
		final Graphics2D g2 = img.createGraphics();
		empty.paint(g2);
		g2.dispose();
		assertEquals(-1, empty.markerX);
	}

	@Test
	public void noTooltipSaysNull()
	{
		for (SidePanelModel.Input in : inputs())
		{
			final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
			final PanelSnapshot.KillCard c = t.card;
			for (String tip : new String[]{c.timeTip, c.skillTip, c.luckTip, c.dpsTip,
				t.curve == null ? null : t.curve.tooltip, t.execution == null ? null : t.execution.tooltip,
				t.damage == null ? null : t.damage.tooltip})
			{
				clean(in.name + " " + (c.live ? "live" : "kill"), tip);
			}
			for (PanelSnapshot.Badge b : c.badges)
			{
				clean("badge", b.tip);
			}
			final PvmPerformancePanel p = SidePanelKillCardTest.panel(in);
			openAll(p.fightTab());
			for (Component k : components(p.fightTab()))
			{
				if (k instanceof JComponent)
				{
					clean(k.getClass().getSimpleName(), ((JComponent) k).getToolTipText());
				}
				if (k instanceof SidePanelSection)
				{
					clean("section " + ((SidePanelSection) k).id(), ((SidePanelSection) k).headerTip());
				}
			}
		}
	}

	@Test
	public void theLuckTooltipKeepsItsWordsWithSpecials()
	{
		// The "<br>null" (Eric, cab94cb): with your own drain specials the curve was rebuilt without its tooltip, and
		// the clarity pass appended that null.
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		in.drain = SidePanelExamples.twoHammersOneLanded().drain;
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertNotNull(t.curve.tooltip);
		clean("curve", t.curve.tooltip);
		assertTrue(t.curve.tooltip, t.curve.tooltip.contains("The curve is spread by damage"));
		assertEquals("a", SidePanelModel.tipLines("a", null, "", "null"));
		assertEquals("a<br>b", SidePanelModel.tipLines(null, "a", "b"));
		assertNull(SidePanelModel.tipLines(null, ""));
	}

	@Test
	public void dpsAgainstExpectedOnTheCardTheRowsAndTheHeader()
	{
		for (SidePanelModel.Input in : new SidePanelModel.Input[]{SidePanelKillCardExamples.araxxorKill(),
			SidePanelKillCardExamples.araxxorLive()})
		{
			final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
			assertNotNull(t.card.dps);
			assertNotNull(t.card.expDps);
			assertTrue(t.card.dps, t.card.dps.matches("\\d+\\.\\d"));
			assertTrue(t.card.expDps, t.card.expDps.matches("\\d+\\.\\d"));
			assertTrue(t.card.dpsTip, t.card.dpsTip.contains("Exp " + t.card.expDps));
		}
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill());
		final PanelSnapshot.Source tentacle = t.damage.rows.get(0);
		assertEquals("10.8 dps · exp 9.3", tentacle.sub);
		assertEquals(Arrays.asList("10.8 dps"), tentacle.subShort);
		assertEquals("901 · exp 804", t.damage.hint.text);
		assertTrue(t.damage.tooltip, t.damage.tooltip.contains("thrall"));
		assertTrue(t.damage.tooltip, t.damage.tooltip.contains("All your damage: 952."));
		// Under the warning: no expected anywhere.
		final SidePanelModel.Input unsure = SidePanelKillCardExamples.araxxorKill();
		unsure.warning = "unsure";
		final PanelSnapshot.KillCard w = SidePanelModel.fight(unsure).card;
		assertNull(w.expDps);
	}

	@Test
	public void personalBestsSayWhatEachNumberIs()
	{
		for (BossRecords.View v : new BossRecords.View[]{SidePanelProgressExamples.araxxor().view(
			new LedgerHistory.Like("Araxxor", 1020, true)), SidePanelProgressExamples.vardorvis().view(
			new LedgerHistory.Like("Vardorvis", 700, true))})
		{
			final SidePanelHistory.Progress p = SidePanelHistory.progress(v, SidePanelHistoryExamples.NOW,
				SidePanelExamples.ZONE);
			final List<String> labels = new ArrayList<>();
			for (PanelSnapshot.Row r : p.bests)
			{
				// Every row has a hover, and none says null or has a stray break.
				assertNotNull(r.left.text, r.tooltip);
				clean(r.left.text, r.tooltip);
				// Nothing between a label and its value but the gold star.
				assertTrue(r.left.text + " |" + r.hint.text + "|", r.hint.isEmpty()
					|| r.hint.text.equals(" " + SidePanelHistory.STAR));
				if (r.indent)
				{
					// The roll's small line under the fastest kill, with no value of its own.
					assertTrue(r.left.text, r.left.text.matches("rolled \\d+"));
					assertTrue(r.right.isEmpty());
					continue;
				}
				labels.add(r.left.text);
			}
			assertEquals(Arrays.asList(SidePanelHistory.FASTEST_KILL, SidePanelHistory.FASTEST_AVERAGE_LUCK,
				SidePanelHistory.BEST_SKILL), labels);
			assertEquals(Arrays.asList("Fastest kill", "Fastest with average luck", "Best Skill"), labels);
			assertTrue(p.bests.get(0).tooltip, p.bests.get(0).tooltip.contains("Your fastest kill: "));
			assertTrue(p.bests.get(0).tooltip, p.bests.get(0).tooltip.contains(" of 100: "));
			// The trend: its own header, both medians, the change, and what they are on hover.
			assertTrue(p.trendTitle, p.trendTitle.startsWith("With average luck, last "));
			assertTrue(p.trendMedians.text, p.trendMedians.text.matches("\\d+:\\d\\d \u2192 \\d+:\\d\\d"));
			assertTrue(p.trend.text, p.trend.text.matches("\\d+ s (faster|slower)|even"));
			assertTrue(p.trendTip, p.trendTip.contains("Lower is faster"));
			clean("trend", p.trendTip);
		}
		assertEquals("rolled 100 of 100: as lucky as it gets", SidePanelModel.rollWords(0.9999));
		assertEquals("rolled 50 of 100: about average", SidePanelModel.rollWords(0.5));
	}

	@Test
	public void theBestsFitAtLargerWithNothingCut()
	{
		// The labels and values whole in every text mode: the History tab's Personal bests as drawn.
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			try
			{
				final SidePanelHistory.Progress p = SidePanelHistory.progress(SidePanelProgressExamples.araxxor().view(
					new LedgerHistory.Like("Araxxor", 1020, true)), SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE);
				final javax.swing.JPanel block = SidePanelProgress.block(p, SidePanelStyle.CONTENT_WIDTH - 12);
				block.setSize(SidePanelStyle.CONTENT_WIDTH - 12, 1000);
				SidePanelPictures.layout(block);
				block.setSize(SidePanelStyle.CONTENT_WIDTH - 12, block.getPreferredSize().height);
				SidePanelPictures.layout(block);
				final BufferedImage img = new BufferedImage(block.getWidth(), block.getHeight(),
					BufferedImage.TYPE_INT_RGB);
				final Graphics2D g = img.createGraphics();
				block.paint(g);
				g.dispose();
				for (Component k : components(block))
				{
					if (k instanceof SidePanelText && ((SidePanelText) k).lastShown() != null)
					{
						final String shown = ((SidePanelText) k).lastShown();
						assertFalse((larger ? "larger: " : "normal: ") + shown, shown.contains(LedgerRows.CUT));
					}
				}
			}
			finally
			{
				SidePanelStyle.setText(false, false);
			}
		}
	}

	// ---- helpers ----

	private static SidePanelModel.Input[] inputs()
	{
		return new SidePanelModel.Input[]{SidePanelKillCardExamples.araxxorKill(), SidePanelKillCardExamples.araxxorLive(),
			SidePanelKillCardExamples.pbKill(), SidePanelKillCardExamples.unrecorded(),
			SidePanelKillCardExamples.vardorvisKill(), SidePanelKillCardExamples.vardorvisLive(),
			SidePanelKillCardExamples.allBadges()};
	}

	private static void clean(String where, String tip)
	{
		if (tip == null)
		{
			return;
		}
		final String low = tip.toLowerCase(java.util.Locale.ROOT);
		assertFalse(where + ": " + tip, low.matches(".*\\bnull\\b.*"));
		assertFalse(where + ": " + tip, low.contains("<br><br>") || low.contains("<br></html>")
			|| low.contains("<html><br>") || low.startsWith("<br>") || low.endsWith("<br>"));
		assertFalse(where + ": " + tip, low.contains("<br> <br>"));
	}

	private static void openAll(Container tab)
	{
		for (SidePanelSection s : SidePanelKillCardTest.all(tab, SidePanelSection.class))
		{
			if (s.isFolded())
			{
				s.toggle();
			}
		}
	}

	private static List<Component> components(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(components((Container) k));
			}
		}
		return out;
	}

	private static List<String> strings(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : components(c))
		{
			if (k instanceof SidePanelText)
			{
				out.add(((SidePanelText) k).getText());
			}
			if (k instanceof SidePanelSection)
			{
				out.add(((SidePanelSection) k).title());
				out.add(((SidePanelSection) k).summary());
			}
			if (k instanceof JComponent && ((JComponent) k).getToolTipText() != null)
			{
				out.add(((JComponent) k).getToolTipText());
			}
		}
		return out;
	}
}

package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-188, redrawn by T-196's clarity pass (Eric approved the canvas's top row): the kill card's four blocks after the
 * kill, Speed, Your play, Luck and Fix next, each in plain words with its scale and no ticks; live, the clock, your
 * play so far with its idle moments and luck so far (T-198: no Weapon row, no Fix next); luck written one way; the sections folded below it,
 * their bodies left alone while folded; Celebrations off as the tab was.
 */
public class SidePanelKillCardTest
{
	static PvmPerformancePanel panel(SidePanelModel.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(in.celebrations);
		p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
		return p;
	}

	static <T> List<T> all(Container c, Class<T> type)
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

	private static boolean hasTiles(Container tab)
	{
		for (Component c : tab.getComponents())
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof GridLayout
				&& ((GridLayout) ((JPanel) c).getLayout()).getColumns() == 2)
			{
				return true;
			}
		}
		return false;
	}

	/** Every word the card puts on the panel (its tooltips are apart). */
	static List<String> panelWords(PanelSnapshot.KillCard c)
	{
		final List<String> out = new ArrayList<>(Arrays.asList(c.corner, c.clock, c.usual, c.skill, c.lost, c.luckTime,
			c.luckWord.text, c.rolled > 0 ? String.valueOf(c.rolled) : null));
		out.removeIf(java.util.Objects::isNull);
		return out;
	}

	/** Every word the card says, on the panel and in its tooltips. */
	static List<String> words(PanelSnapshot.KillCard c)
	{
		final List<String> out = new ArrayList<>(panelWords(c));
		out.addAll(Arrays.asList(c.timeTip, c.skillTip, c.luckTip));
		out.removeIf(java.util.Objects::isNull);
		return out;
	}

	// ---- after the kill: Time, Skill, Luck (T-198: glance first, words on hover) ----

	@Test
	public void timeIsTheClockAndYourUsualWithTheRestOnHover()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertEquals("kill 555", c.corner);
		assertEquals("1:26", c.clock);
		assertEquals("1:34", c.usual);
		assertFalse(c.newPb);
		// The best and the rank are facts on hover, not targets on the panel.
		assertEquals("1:05", c.best);
		assertEquals("42nd fastest of 199", c.rank);
		assertTrue(c.timeTip, c.timeTip.contains("Usual 1:34: the median of your last 20 kills like this one."));
		assertTrue(c.timeTip, c.timeTip.contains("Your best: 1:05."));
		assertTrue(c.timeTip, c.timeTip.contains("42nd fastest of 199 kills tracked."));
	}

	@Test
	public void skillIsTheGradeTheShareAndTheLossWithTheWhyOnHover()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertEquals("A", c.grade);
		assertEquals(SidePanelStyle.GRADE_A, c.gradeColour);
		assertEquals("99%", c.skill);
		assertEquals("\u22121.1 s", c.lost);
		for (String part : new String[]{"99% of a perfect fight (" + SidePanelModel.PERFECT + ").",
			"Lost 1.1 s: 0.6 s idle, a boost wore off.", "Biggest loss: 0.6 s standing still.",
			"Grade A from your play only (never luck): better than 90% of your last 50 kills.",
			"1.2 s more attacking would make it an S."})
		{
			assertTrue(c.skillTip, c.skillTip.contains(part));
		}
	}

	@Test
	public void luckIsTheRollItsTrackAndItsTimeWithTheWordsOnHover()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertEquals(81, c.rolled);
		assertEquals("+10 s", c.luckTime);
		assertTrue(c.luckTip, c.luckTip.startsWith("rolled 81 of 100 · top 19%"));
		assertTrue(c.luckTip, c.luckTip.contains("Your hits did 12% more damage than average rolls: 10 s faster."));
		assertTrue(c.luckTip, c.luckTip.contains("With average luck: 1:36."));
		final PanelSnapshot.KillCard v = SidePanelModel.fight(SidePanelKillCardExamples.vardorvisKill()).card;
		assertEquals(20, v.rolled);
		assertEquals("\u221214 s", v.luckTime);
		assertTrue(v.luckTip, v.luckTip.contains("12% less damage than average rolls: 14 s slower."));
	}

	@Test
	public void noSentenceOnTheCardOrInAFoldedSummary()
	{
		// T-198: every explanation is a tooltip; what's on the panel is numbers and a word or two, no full stops.
		for (SidePanelModel.Input in : new SidePanelModel.Input[]{SidePanelKillCardExamples.araxxorKill(),
			SidePanelKillCardExamples.araxxorLive(), SidePanelKillCardExamples.pbKill(),
			SidePanelKillCardExamples.vardorvisKill(), SidePanelKillCardExamples.allBadges()})
		{
			final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
			final List<String> shown = new ArrayList<>(panelWords(t.card));
			shown.addAll(SidePanelKillCard.blocks(t.card));
			for (PanelSnapshot.Text line : t.summaries.values())
			{
				shown.add(line.text);
			}
			for (String w : shown)
			{
				assertFalse(w, w.matches(".*\\.(\\s.*|$)"));
				int wordy = 0;
				for (String word : w.split(" "))
				{
					wordy += word.matches("[A-Za-z']{3,}") ? 1 : 0;
				}
				assertTrue(w, wordy <= 2);
			}
		}
	}

	@Test
	public void noFixNextAfterTheKillAndNoWeaponLive()
	{
		// T-198: the blocks after the kill are Speed, Your play and Luck; live, Your play so far and Luck so far.
		final PanelSnapshot.KillCard kill = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertFalse(kill.structure(), kill.structure().contains("f"));
		assertEquals(Arrays.asList("Skill", "Luck"), SidePanelKillCard.blocks(kill));
		final PanelSnapshot.KillCard live = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		assertEquals(Arrays.asList("Skill", "Luck"), SidePanelKillCard.blocks(live));
	}

	@Test
	public void aPbKillShowsThePbChipAndTheBestItBeatOnHover()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.pbKill()).card;
		assertTrue(c.newPb);
		assertEquals("1:28", c.best);
		assertEquals("fastest of 17", c.rank);
		assertTrue(c.timeTip, c.timeTip.contains("A new best: your best was 1:28."));
		// A new best with average luck is said on Luck's hover.
		assertTrue(c.luckTip, c.luckTip.contains("With average luck: 1:36, a new best (was 1:37)."));
	}

	@Test
	public void beforeTheRecordHasTheKillItShowsYourUsualAndNoGrade()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.unrecorded()).card;
		assertEquals("1:34", c.usual);
		assertNull("no best yet", c.best);
		assertNull(c.rank);
		assertNull(c.grade);
		assertEquals("99%", c.skill);
	}

	@Test
	public void noTicksOnTheCard()
	{
		for (SidePanelModel.Input in : new SidePanelModel.Input[]{SidePanelKillCardExamples.araxxorKill(),
			SidePanelKillCardExamples.araxxorLive(), SidePanelKillCardExamples.pbKill(),
			SidePanelKillCardExamples.unrecorded(), SidePanelKillCardExamples.vardorvisKill(),
			SidePanelKillCardExamples.vardorvisLive(), SidePanelKillCardExamples.allBadges()})
		{
			for (String w : words(SidePanelModel.fight(in).card))
			{
				assertFalse(w, w.toLowerCase(java.util.Locale.ROOT).matches(".*\\btick.*"));
			}
		}
	}

	@Test
	public void secondsAreWrittenOneWay()
	{
		assertEquals("0.6 s", SidePanelModel.secs(0.6));
		assertEquals("1.2 s", SidePanelModel.secs(1.2));
		assertEquals("1 s", SidePanelModel.secs(1.0));
		assertEquals("14 s", SidePanelModel.secs(14.2));
		assertEquals("1:05", SidePanelModel.secs(65.4));
	}

	@Test
	public void theBasisReadsBackAsItsShare()
	{
		// Every basis the record writes (T-187's words) reads back within a percent, and the fixed bands read as none.
		for (int k = 0; k <= 100; k++)
		{
			final double share = k / 100.0;
			final SidePanelModel.Basis b = SidePanelModel.Basis.of(BossRecords.basis(share, 50));
			assertNotNull(BossRecords.basis(share, 50), b);
			assertEquals(BossRecords.basis(share, 50), share, b.share, 0.0101);
			assertEquals(50, b.of);
		}
		assertEquals("your best of your last 50 kills", SidePanelModel.Basis.of("best of last 50").words());
		assertEquals("your lowest of your last 16 kills", SidePanelModel.Basis.of("lowest of last 16").words());
		assertEquals("better than 8% of your last 50 kills", SidePanelModel.Basis.of("bottom 8% of last 50").words());
		assertNull(SidePanelModel.Basis.of("fixed bands (3 kills)"));
		final SidePanelModel.Input fixed = SidePanelKillCardExamples.araxxorKill();
		fixed.standing.basis = "fixed bands (3 kills)";
		final PanelSnapshot.KillCard c = SidePanelModel.fight(fixed).card;
		assertTrue(c.skillTip, c.skillTip.contains("Grade A from your play only (never luck): fixed bands (3 kills)."));
	}

	@Test
	public void theGradeIsNeverRed()
	{
		for (String g : new String[]{"S", "A", "B", "C"})
		{
			final java.awt.Color c = SidePanelModel.gradeColour(g);
			assertNotEquals(g, SidePanelStyle.DEAD, c);
			assertNotEquals(g, SidePanelStyle.COST, c);
		}
		assertEquals(SidePanelStyle.SECONDARY, SidePanelModel.gradeColour("C"));
	}

	// ---- live ----

	@Test
	public void liveItShowsSkillSoFarItsStripAndLucksWordAndNoGradeRollOrPb()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		assertTrue(c.live);
		assertEquals("live", c.corner);
		assertEquals("0:42", c.clock);
		// Skill so far: the share attacking and the idle time, with the fight's strip.
		assertEquals("97%", c.skill);
		assertEquals("\u22121.2 s", c.lost);
		assertNotNull(c.strip);
		assertEquals(70, c.strip.length);
		int idle = 0;
		for (byte b : c.strip)
		{
			idle += b == PanelSnapshot.KillCard.STRIP_IDLE ? 1 : 0;
		}
		assertEquals("the timeline's two lost ticks", 2, idle);
		assertTrue(c.skillTip, c.skillTip.contains("Idle 1.2 s of 42 s"));
		// Luck so far: one word, its strength; the damage against average rolls on hover.
		assertEquals("average", c.luckWord.text);
		assertEquals(SidePanelStyle.LUCK, c.luckWord.colour);
		assertTrue(c.luckTip, c.luckTip.contains("You've dealt 428. Average rolls: 403."));
		// Never a grade, a roll number or a PB live.
		assertNull(c.grade);
		assertEquals(-1, c.rolled);
		assertNull(c.best);
		assertNull(c.rank);
	}

	@Test
	public void theStripKeepsAtMostOneBucketAPixel()
	{
		final byte[] states = new byte[1000];
		states[0] = FightTimeline.LOST;
		states[999] = FightTimeline.EATING;
		final byte[] strip = SidePanelModel.strip(states, SidePanelModel.STRIP_BUCKETS);
		assertEquals(SidePanelModel.STRIP_BUCKETS, strip.length);
		assertEquals(PanelSnapshot.KillCard.STRIP_IDLE, strip[0]);
		assertEquals(PanelSnapshot.KillCard.STRIP_EATING, strip[strip.length - 1]);
		assertEquals(3, SidePanelModel.strip(new byte[3], SidePanelModel.STRIP_BUCKETS).length);
	}

	// ---- luck, written one way ----

	@Test
	public void luckIsWrittenOneWay()
	{
		assertEquals("rolled 20", SidePanelModel.rolled(0.20));
		assertEquals("rolled 20 of 100", SidePanelModel.rolledOf(0.20));
		// Never 0; 100 at the very top.
		assertEquals(1, SidePanelModel.roll(0.001));
		assertEquals(100, SidePanelModel.roll(0.999));
		// Celebrations off: the Luck section's header is the roll. On: its strength in words; the roll is its line.
		assertEquals("rolled 81", SidePanelModel.fight(SidePanelKillCardExamples.off()).curve.headerValue.text);
		final PanelSnapshot.FightTab on = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill());
		assertEquals("lucky", on.curve.headerValue.text);
		assertEquals("81", on.summaries.get("curve").text);
		// T-198: the curve's words are its tooltip, not a caption on the panel.
		assertEquals("", on.curve.caption);
		assertTrue(on.curve.tooltip, on.curve.tooltip.startsWith("rolled 81 of 100: of 100 fights with these attacks, 81"
			+ " dealt less damage."));
		// No band words anywhere on the tab, Celebrations on or off.
		for (SidePanelModel.Input in : new SidePanelModel.Input[]{SidePanelKillCardExamples.araxxorKill(),
			SidePanelKillCardExamples.off(), SidePanelKillCardExamples.vardorvisKill()})
		{
			final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
			if (t.tiles != null)
			{
				for (PanelSnapshot.Tile tile : t.tiles)
				{
					assertFalse(tile.value.text, tile.value.text.matches("(bottom|top) \\d+%|average"));
				}
			}
			final PvmPerformancePanel p = panel(in);
			SidePanelPictures.openAll(p.fightTab());
			for (SidePanelText text : all(p.fightTab(), SidePanelText.class))
			{
				assertFalse(text.getText(), text.getText().matches(".*\\b(bottom|top) \\d+%.*"));
			}
		}
	}

	// ---- the tab ----

	@Test
	public void celebrationsOffIsTodaysTab()
	{
		final PvmPerformancePanel off = panel(SidePanelKillCardExamples.off());
		assertTrue(all(off.fightTab(), SidePanelKillCard.class).isEmpty());
		assertTrue(hasTiles(off.fightTab()));
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelKillCardExamples.off());
		assertNull(tab.card);
		assertNull(tab.clear);
		assertTrue(tab.summaries.isEmpty());
		// Today's sections, open and named as before: Execution, Damage over time, Ticks and Details are all there.
		final List<String> titles = new ArrayList<>();
		for (SidePanelSection s : all(off.fightTab(), SidePanelSection.class))
		{
			assertFalse(s.id(), s.isFolded());
			assertEquals(s.id(), "", s.summary());
			titles.add(s.title());
		}
		assertTrue(titles.toString(), titles.containsAll(Arrays.asList("Damage", "Luck", "Where the time went",
			"Execution", "Damage over time", "Ticks", "Details", "Damage taken")));
		assertTrue(all(off.fightTab(), SidePanelShareBar.class).isEmpty());
		// Your line in the chart keeps luck's band colour there, as it was.
		assertEquals(SidePanelStyle.BETTER, tab.chart.actual);
		final PvmPerformancePanel on = panel(SidePanelKillCardExamples.araxxorKill());
		assertEquals(1, all(on.fightTab(), SidePanelKillCard.class).size());
		assertFalse(hasTiles(on.fightTab()));
		// The card is first: no title above it.
		assertTrue(on.fightTab().getComponent(0) instanceof SidePanelKillCard);
	}

	@Test
	public void theSectionsAreRenamedAndMergedUnderTheCard()
	{
		final PvmPerformancePanel p = panel(SidePanelKillCardExamples.araxxorKill());
		final List<String> ids = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		for (SidePanelSection s : all(p.fightTab(), SidePanelSection.class))
		{
			ids.add(s.id());
			titles.add(s.title());
		}
		assertEquals(Arrays.asList("damage", "curve", "budget", "execution", "taken"), ids);
		assertEquals(Arrays.asList("Damage", "Luck", "Where the time went", "Your play", "Damage taken"), titles);
		// Damage: open, its share bar, its rows, and its Timeline (was Damage over time; T-198 renamed it).
		final SidePanelSection damage = SidePanelPictures.sectionOf(p.fightTab(), "damage");
		assertFalse(damage.isFolded());
		assertEquals(1, all(damage, SidePanelShareBar.class).size());
		assertEquals(Arrays.asList("tentacle", "crossbow · thrall · warhammer"),
			Arrays.asList(SidePanelShareBar.names(all(damage, SidePanelShareBar.class).get(0).rows())));
		assertEquals(1, all(damage, SidePanelDamageChart.class).size());
		assertEquals("Timeline", SidePanelFightTab.TIMELINE);
		assertTrue(texts(damage).contains(SidePanelFightTab.TIMELINE));
		assertFalse(texts(damage).contains("Second by second"));
		assertTrue(texts(damage).toString(), texts(damage).contains("10.8 dps"));
		assertFalse(texts(damage).toString(), texts(damage).toString().contains("expected"));
		// Your play (was Execution): plain part names, and the attack rhythm (was Ticks) in it.
		final SidePanelSection play = SidePanelPictures.sectionOf(p.fightTab(), "execution");
		play.toggle();
		assertEquals(1, all(play, SidePanelTickStrip.class).size());
		final List<String> playTexts = texts(play);
		assertTrue(playTexts.toString(), playTexts.containsAll(Arrays.asList("Attacking", "Right prayers", "Best gear worn",
			"Stats boosted", SidePanelFightTab.ATTACK_RHYTHM, "\u22120.6 s idle", "\u22125 dmg")));
		assertFalse(playTexts.toString(), playTexts.contains("uptime"));
		// Luck: its hits, from Details.
		final SidePanelSection luck = SidePanelPictures.sectionOf(p.fightTab(), "curve");
		luck.toggle();
		assertTrue(texts(luck).toString(), texts(luck).containsAll(Arrays.asList("hits", "30 · avg 29.6", "per hit",
			"30 · avg 27")));
		// Your line in the chart is white: luck only ever in its violet.
		assertEquals(SidePanelStyle.VALUE, SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).chart.actual);
		// No "expected" on any row: a special's damage reads against average rolls (Vardorvis's claws).
		final SidePanelSection claws = SidePanelPictures.sectionOf(panel(SidePanelKillCardExamples.vardorvisKill())
			.fightTab(), "damage");
		assertFalse(texts(claws).toString(), texts(claws).toString().contains("expected"));
		assertTrue(texts(claws).toString(), texts(claws).toString().contains("(average 88)"));
	}

	@Test
	public void detailsAreGoneAndTheBossesHealingShowsOnlyWhenItIsntZero()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill());
		assertFalse(t.summaries.containsKey("details"));
		assertFalse(t.summaries.containsKey("chart"));
		assertFalse(t.summaries.containsKey("ticks"));
		assertNull(t.clear.healed);
		final PvmPerformancePanel p = panel(SidePanelKillCardExamples.araxxorKill());
		for (String gone : new String[]{"details", "chart", "ticks"})
		{
			for (SidePanelSection s : all(p.fightTab(), SidePanelSection.class))
			{
				assertNotEquals(gone, s.id());
			}
		}
		final SidePanelModel.Input healed = SidePanelKillCardExamples.araxxorKill();
		((LedgerExamples.FakeScope) healed.scope).healed = 12;
		final PanelSnapshot.FightTab h = SidePanelModel.fight(healed);
		assertEquals("Boss healed", h.clear.healed.left.text);
		assertEquals("12", h.clear.healed.right.text);
		assertTrue(texts(SidePanelPictures.sectionOf(panel(healed).fightTab(), "damage")).contains("Boss healed"));
	}

	@Test
	public void eachFoldedSectionHasItsLineAndPicture()
	{
		final PvmPerformancePanel p = panel(SidePanelKillCardExamples.araxxorKill());
		final SidePanelSection luck = SidePanelPictures.sectionOf(p.fightTab(), "curve");
		// T-198: one number beside its picture.
		assertEquals("81", luck.summary());
		assertEquals(PanelSnapshot.Mark.Kind.LUCK, luck.summaryMark().kind);
		assertEquals(81, luck.summaryMark().roll);
		final SidePanelSection time = SidePanelPictures.sectionOf(p.fightTab(), "budget");
		assertEquals("\u22121.1 s · +10 s", time.summary());
		final PanelSnapshot.Mark bar = time.summaryMark();
		assertEquals(PanelSnapshot.Mark.Kind.TIME, bar.kind);
		assertTrue(bar.luckSaved);
		assertEquals(1, bar.kept + bar.lost + bar.luck, 0.15);
		final SidePanelSection play = SidePanelPictures.sectionOf(p.fightTab(), "execution");
		assertEquals("99%", play.summary());
		assertEquals("A", play.summaryMark().grade);
		assertEquals("12 · max 3", SidePanelPictures.sectionOf(p.fightTab(), "taken").summary());
		final SidePanelSection damageFolded = SidePanelPictures.sectionOf(p.fightTab(), "damage");
		damageFolded.toggle();
		assertEquals("952", damageFolded.summary());
		assertEquals(PanelSnapshot.Mark.Kind.SHARES, damageFolded.summaryMark().kind);
		// Unlucky: luck's cost comes after the loss.
		final SidePanelSection unlucky = SidePanelPictures.sectionOf(panel(SidePanelKillCardExamples.vardorvisKill())
			.fightTab(), "budget");
		assertEquals("\u221217 s · \u221214 s", unlucky.summary());
		assertFalse(unlucky.summaryMark().luckSaved);
	}

	@Test
	public void theMergedSectionsOldFoldKeysStillOpenThem()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		final SidePanelSection.Folds folds = p.fightFolds();
		assertTrue(folds.isFolded("execution"));
		assertTrue(folds.isFolded("curve"));
		// Ticks and Details left open (T-188's keys, "ticks.opened" and "details.opened") open the sections they're in.
		folds.setFolded("ticks", false);
		folds.setFolded("details", false);
		assertFalse(folds.isFolded("execution"));
		assertFalse(folds.isFolded("curve"));
		// Folding Your play writes the old key too, so it stays folded.
		folds.setFolded("execution", true);
		assertTrue(folds.isFolded("execution"));
		assertTrue(folds.isFolded("ticks"));
		// Damage over time left open opens Damage, which opens by default anyway; folding Damage folds it.
		folds.setFolded("chart", false);
		assertFalse(folds.isFolded("damage"));
		folds.setFolded("damage", true);
		assertTrue(folds.isFolded("damage"));
		// Celebrations off reads only its own keys.
		p.setCelebrations(false);
		folds.setFolded("details", false);
		assertFalse(folds.isFolded("execution"));
	}

	@Test
	public void aFoldedBodyIsNotUpdatedUntilItOpens()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		final SidePanelModel.Input first = SidePanelKillCardExamples.araxxorKill();
		p.apply(new PanelSnapshot(SidePanelModel.fight(first), null, SidePanelParty.party(new SidePanelParty.Input())));
		final SidePanelSection taken = SidePanelPictures.sectionOf(p.fightTab(), "taken");
		assertTrue(taken.isFolded());
		final String before = rowsText(taken);
		// The next snapshot: a bigger hit. Folded, the body keeps what it had; its line moves.
		final SidePanelModel.Input next = SidePanelKillCardExamples.araxxorKill();
		((LedgerExamples.FakeScope) next.scope).biggestHitTaken = 9;
		p.apply(new PanelSnapshot(SidePanelModel.fight(next), null, SidePanelParty.party(new SidePanelParty.Input())));
		assertEquals(before, rowsText(taken));
		assertEquals("12 · max 9", taken.summary());
		// Opened, it catches up at once.
		taken.toggle();
		assertNotEquals(before, rowsText(taken));
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

	private static String rowsText(SidePanelSection s)
	{
		return String.join("|", texts(s));
	}

	@Test
	public void aValueChangeRepaintsTheCardAndLaysNothingOut()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		p.setSize(242, 1000);
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorLive();
		p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
		SidePanelPictures.layout(p);
		final SidePanelKillCard card = all(p.fightTab(), SidePanelKillCard.class).get(0);
		final int height = card.getPreferredSize().height;
		final int builds = p.fightTab().builds();
		// T-046: the first update after a build reserves every value's widest digits, in one pass; none after it.
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()), null,
			SidePanelParty.party(new SidePanelParty.Input())));
		SidePanelPictures.layout(p);
		final int before = SidePanelParts.relayouts;
		for (int k = 1; k <= 20; k++)
		{
			final SidePanelModel.Input next = SidePanelKillCardExamples.araxxorLive();
			final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) next.scope;
			// The clock, lost ticks and thrall move; the weapon damage stays the timeline's, so no row comes or goes.
			s.combatTicks += k;
			s.ticksLost += k / 4;
			s.thrallDamage += k;
			p.apply(new PanelSnapshot(SidePanelModel.fight(next), null, SidePanelParty.party(new SidePanelParty.Input())));
		}
		assertEquals(builds, p.fightTab().builds());
		assertEquals(height, card.getPreferredSize().height);
		assertEquals("0:54", card.card().clock);
		assertEquals("the card asks for no layout, and the folded sections' bodies none", before,
			SidePanelParts.relayouts);
	}
}

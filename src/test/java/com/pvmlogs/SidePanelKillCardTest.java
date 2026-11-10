package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.util.ArrayList;
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
 * T-188 (Eric, 2026-11-09: "let's choose A for all of them. No pace against pb mid fight"; "i like rolled 20 of 100"):
 * the kill card in place of the title and tiles, live and after the kill; luck written one way; the sections folded
 * below it, their bodies left alone while folded; Celebrations off as the tab was.
 */
public class SidePanelKillCardTest
{
	private static PvmPerformancePanel panel(SidePanelModel.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(in.celebrations);
		p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
		return p;
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

	@Test
	public void liveTheCardHasNoGradeTipRollOrPb()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		assertNotNull(c);
		assertTrue(c.live);
		assertEquals("live", c.corner);
		assertEquals("0:42", c.clock);
		// Damage against expected beside the clock; no PB, pace or rank in any form.
		assertEquals("428 dmg", c.side1.text);
		assertEquals("expected 403", c.side2.text);
		assertTrue(c.side3.isEmpty());
		assertFalse(c.newPb);
		// Skill: ticks lost so far, orange, and execution so far; no grade.
		assertNull(c.grade);
		assertEquals("2", c.big.text);
		assertEquals(SidePanelStyle.COST, c.big.colour);
		assertEquals("execution 96%", c.skill1.text);
		// Luck rolling: its band in words, violet, and no number.
		assertEquals(-1, c.rolled);
		assertEquals("middle half so far", c.luckLine.text);
		assertEquals(SidePanelStyle.LUCK, c.luckLine.colour);
		// No tip or luck-removed row live; the weapon in hand with its DPS.
		assertNull(c.tip);
		assertNull(c.luckRemoved);
		assertEquals("Hunters' sunlight crossbow", c.weapon);
		assertEquals("15.6 dps · expected 15.6", c.weaponDps);
	}

	@Test
	public void afterTheKillItHasThePbRankGradeRollAndTip()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertFalse(c.live);
		assertEquals("KC 555", c.corner);
		assertEquals("1:26", c.clock);
		assertEquals("your PB 1:05", c.side1.text);
		assertEquals("21 s slower", c.side2.text);
		assertEquals("42nd fastest of 199", c.side3.text);
		// The grade from T-187's record, its basis and the ticks to the next.
		assertEquals("A", c.grade);
		assertEquals(SidePanelStyle.GRADE_A, c.gradeColour);
		assertEquals("2 ticks from an S", c.gradeTip);
		assertEquals("A", c.big.text);
		assertEquals("99%", c.bigCaption.get(0).text);
		assertEquals("2 ticks lost", c.skill1.text);
		assertEquals("top 10% of last 50", c.skill2.text);
		// Luck rolled 81 of 100, 10 s faster, violet.
		assertEquals(81, c.rolled);
		assertEquals("10 s faster", c.luckLine.text);
		assertEquals(SidePanelStyle.LUCK, c.luckLine.colour);
		// Luck removed and its own PB; the tip from the biggest loss.
		assertEquals("1:36", c.luckRemoved);
		assertEquals("PB 1:12", c.luckRemovedPb);
		assertFalse(c.newLuckRemovedPb);
		assertEquals("Most lost: uptime, 1 tick idle", c.tip);
		assertNull(c.weapon);
	}

	@Test
	public void aPbKillSaysNewPbInTheBossColourAndOnlyTheLuckRemovedPbIsGold()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.pbKill()).card;
		assertTrue(c.newPb);
		assertEquals("NEW PB", c.side1.text);
		assertEquals(c.tint, c.side1.colour);
		assertEquals(new java.awt.Color(BossColours.of("Araxxor")), c.tint);
		assertEquals("2 s faster", c.side2.text);
		assertEquals("fastest of 17", c.side3.text);
		// The luck that helped, beside it.
		assertEquals("10 s faster", c.luckLine.text);
		assertTrue(c.newLuckRemovedPb);
		assertEquals("was 1:37", c.luckRemovedPb);
	}

	@Test
	public void beforeTheRecordHasTheKillItShowsYourUsualAndNoGrade()
	{
		final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.unrecorded()).card;
		assertEquals("your usual 1:34", c.side1.text);
		assertEquals("8 s faster", c.side2.text);
		assertNull(c.grade);
		assertEquals("99%", c.big.text);
		assertTrue(c.bigCaption.isEmpty());
		assertNull(c.luckRemovedPb);
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

	@Test
	public void luckIsWrittenOneWay()
	{
		assertEquals("rolled 20", SidePanelModel.rolled(0.20));
		assertEquals("rolled 20 of 100", SidePanelModel.rolledOf(0.20));
		// Never 0; 100 at the very top.
		assertEquals(1, SidePanelModel.roll(0.001));
		assertEquals(100, SidePanelModel.roll(0.999));
		// No band words anywhere on the tab, Celebrations on or off: the card, the tiles, the Luck section, the lists.
		for (SidePanelModel.Input in : new SidePanelModel.Input[]{SidePanelKillCardExamples.araxxorKill(),
			SidePanelKillCardExamples.off(), SidePanelKillCardExamples.vardorvisKill()})
		{
			final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
			assertTrue(t.curve.headerValue.text, t.curve.headerValue.text.matches("rolled \\d+"));
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

	@Test
	public void celebrationsOffIsTodaysTab()
	{
		final PvmPerformancePanel off = panel(SidePanelKillCardExamples.off());
		assertTrue(all(off.fightTab(), SidePanelKillCard.class).isEmpty());
		assertTrue(hasTiles(off.fightTab()));
		assertNull(SidePanelModel.fight(SidePanelKillCardExamples.off()).card);
		assertTrue(SidePanelModel.fight(SidePanelKillCardExamples.off()).summaries.isEmpty());
		// Today's sections open, as before.
		for (SidePanelSection s : all(off.fightTab(), SidePanelSection.class))
		{
			assertFalse(s.id(), s.isFolded());
			assertEquals(s.id(), "", s.summary());
		}
		final PvmPerformancePanel on = panel(SidePanelKillCardExamples.araxxorKill());
		assertEquals(1, all(on.fightTab(), SidePanelKillCard.class).size());
		assertFalse(hasTiles(on.fightTab()));
		// The card is first: no title above it.
		assertTrue(on.fightTab().getComponent(0) instanceof SidePanelKillCard);
	}

	@Test
	public void theSectionsFoldBelowTheCardWithALineEach()
	{
		final PvmPerformancePanel p = panel(SidePanelKillCardExamples.araxxorKill());
		final List<String> lines = new ArrayList<>();
		for (SidePanelSection s : all(p.fightTab(), SidePanelSection.class))
		{
			assertTrue(s.id(), s.isFolded());
			lines.add(s.id() + ": " + s.summary());
		}
		assertTrue(lines.toString(), lines.contains("damage: tentacle 87% · crossbow 6% · thrall 5%"));
		assertTrue(lines.toString(), lines.contains("curve: rolls dealt 112% of expected"));
		assertTrue(lines.toString(), lines.contains("budget: best 1:34 · lost 2 ticks · luck 10 s"));
		assertTrue(lines.toString(), lines.contains("execution: uptime -1t · -5 dmg"));
		assertTrue(lines.toString(), lines.contains("taken: largest hit 3"));
		assertTrue(lines.toString(), lines.contains("details: hits 30/29.6"));
		// Opened, a section is today's card and its line goes; the fold is kept under its own key.
		final SidePanelSection damage = SidePanelPictures.sectionOf(p.fightTab(), "damage");
		damage.toggle();
		assertFalse(damage.isFolded());
		assertEquals("", damage.summary());
		final PvmPerformancePanel again = panel(SidePanelKillCardExamples.araxxorKill());
		assertTrue("each panel its own folds in tests", SidePanelPictures.sectionOf(again.fightTab(), "damage")
			.isFolded());
	}

	@Test
	public void aFoldedBodyIsNotUpdatedUntilItOpens()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		final SidePanelModel.Input first = SidePanelKillCardExamples.araxxorKill();
		p.apply(new PanelSnapshot(SidePanelModel.fight(first), null, SidePanelParty.party(new SidePanelParty.Input())));
		final SidePanelSection details = SidePanelPictures.sectionOf(p.fightTab(), "details");
		assertTrue(details.isFolded());
		final String before = rowsText(details);
		// The next snapshot: two more hits. Folded, the body keeps what it had; its line moves.
		final SidePanelModel.Input next = SidePanelKillCardExamples.araxxorKill();
		((LedgerExamples.FakeScope) next.scope).hits = 32;
		p.apply(new PanelSnapshot(SidePanelModel.fight(next), null, SidePanelParty.party(new SidePanelParty.Input())));
		assertEquals(before, rowsText(details));
		assertEquals("hits 32/29.6", details.summary());
		// Opened, it catches up at once.
		details.toggle();
		assertNotEquals(before, rowsText(details));
		assertTrue(rowsText(details), rowsText(details).contains("32/29.6"));
	}

	private static String rowsText(SidePanelSection s)
	{
		final StringBuilder b = new StringBuilder();
		for (SidePanelText t : all(s, SidePanelText.class))
		{
			b.append(t.getText()).append('|');
		}
		return b.toString();
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

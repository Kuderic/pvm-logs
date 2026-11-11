package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * T-207 (Eric, 2026-11-10 22:28, after his Yama kill): the folded summaries with a bar share one full-width bar with
 * their numbers under it; the card's Luck and Skill tiles mirror line for line, the live track and idle strip level;
 * luck's seconds never cut off; the two switches drawn as segmented controls.
 */
public class SidePanelAlignmentTest
{
	@After
	public void plainText()
	{
		SidePanelStyle.setText(false, false);
	}

	// ---- the folded summaries ----

	@Test
	public void foldedBarsShareOneWidth()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()), null,
				SidePanelParty.party(new SidePanelParty.Input())));
			final SidePanelFightTab tab = p.fightTab();
			final SidePanelSection damage = SidePanelPictures.sectionOf(tab, "damage");
			if (!damage.isFolded())
			{
				damage.toggle();
			}
			tab.setSize(SidePanelStyle.CONTENT_WIDTH, 5_000);
			SidePanelPictures.layout(tab);
			final List<Rectangle> bars = new ArrayList<>();
			for (String id : new String[]{"damage", "curve", "budget"})
			{
				final SidePanelSection s = SidePanelPictures.sectionOf(tab, id);
				assertTrue(id, s.isFolded());
				final Rectangle bar = s.summaryBar();
				assertNotNull(id + " has a bar", bar);
				bars.add(bar);
			}
			for (Rectangle b : bars)
			{
				// Same left and right edges, the card's padding in from its sides, the same height and the same place.
				assertEquals(bars.toString(), bars.get(0).x, b.x);
				assertEquals(bars.toString(), bars.get(0).width, b.width);
				assertEquals(bars.toString(), bars.get(0).height, b.height);
				assertEquals(SidePanelStyle.CARD_PAD_H, b.x);
				assertEquals(damage.getWidth() - 2 * SidePanelStyle.CARD_PAD_H, b.width);
			}
			// The same y in each card (the bar's rectangle is in its section's coordinates): the summary's first line.
			for (Rectangle b : bars)
			{
				assertEquals(bars.toString(), bars.get(0).y, b.y);
			}
			// Skill keeps its grade chip beside its words, Damage taken its words alone: no bar.
			assertEquals(null, SidePanelPictures.sectionOf(tab, "execution").summaryBar());
			assertEquals(null, SidePanelPictures.sectionOf(tab, "taken").summaryBar());
		}
	}

	// ---- the card's tiles ----

	private static SidePanelKillCard painted(PanelSnapshot.KillCard c)
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

	@Test
	public void theLiveTrackAndStripAreLevel()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			final PanelSnapshot.KillCard live = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
			assertTrue(live.live);
			final SidePanelKillCard view = painted(live);
			assertTrue("track painted", view.trackY >= 0);
			assertTrue("strip painted", view.stripY >= 0);
			assertEquals("the same y", view.trackY, view.stripY);
			// The same inset in their tiles: each its tile's padding in from the tile's left.
			assertEquals(view.trackX - view.luckBox().x, view.stripX - view.playBox().x);
			// After the kill, the track sits where it did: nothing jumps at the kill.
			final SidePanelKillCard kill = painted(SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card);
			assertEquals(view.trackY, kill.trackY);
			assertEquals(view.luckBox().height, kill.luckBox().height);
			assertEquals(-1, kill.stripY);
		}
	}

	@Test
	public void luckSecondsNeverCutOffAtLarger()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			// The widest band and the widest seconds the card writes (luckTime is whole seconds, "-105 s").
			final PanelSnapshot.KillCard c = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
			c.luckP = 0.245;
			c.rolled = 25;
			c.luckTime = "-999 s";
			assertEquals("bottom 25%", LedgerRows.band(c.luckP));
			final List<String> drawn = SidePanelKillCard.drawnTexts(c);
			assertTrue(drawn.toString(), drawn.contains("bottom 25%"));
			assertTrue(drawn.toString(), drawn.contains("-999 s"));
			for (String t : drawn)
			{
				assertFalse(larger + " " + t, t.contains(LedgerRows.CUT));
			}
			// Eric's Yama kill.
			c.luckP = 0.02;
			c.luckTime = "-29 s";
			assertTrue(SidePanelKillCard.drawnTexts(c).contains("-29 s"));
		}
	}

	// ---- the switches ----

	private static void hoverAndCheck(SidePanelFightTab.Segmented s, String[] tips)
	{
		s.setSize(s.getPreferredSize());
		final int layouts = SidePanelParts.relayouts;
		for (int i = 0; i < s.words().size(); i++)
		{
			final Rectangle r = s.pill(i);
			final MouseEvent at = new MouseEvent(s, MouseEvent.MOUSE_MOVED, 0L, 0, r.x + r.width / 2, r.y + r.height / 2, 0,
				false);
			for (java.awt.event.MouseMotionListener l : s.getMouseMotionListeners())
			{
				l.mouseMoved(at);
			}
			assertEquals(i, s.hovered());
			assertEquals(i == s.picked() ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR, s.getCursor().getType());
			assertTrue(Tips.plain(s.getToolTipText(at)), Tips.plain(s.getToolTipText(at)).startsWith(tips[i]));
		}
		// A hover is a repaint.
		assertEquals(layouts, SidePanelParts.relayouts);
	}

	@Test
	public void bothSwitchesShowTheHandAndATooltipPerChoice()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.fightTab().apply(SidePanelModel.fight(SidePanelTargetExamples.input(SidePanelTargetExamples.araxxor())));
		final SidePanelFightTab.TargetSwitch targets = all(panel.fightTab(), SidePanelFightTab.TargetSwitch.class).get(0);
		hoverAndCheck(targets, new String[]{"Show the boss only", "Show the adds only",
			"Show the boss and the adds together"});
		final SidePanelParty.Input in = SidePanelPartyExamples.toa();
		final PvmPerformancePanel party = new PvmPerformancePanel(null, null, null, null);
		party.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final SidePanelPartySection.MetricSwitch metric = all(party.partySection().top(),
			SidePanelPartySection.MetricSwitch.class).get(0);
		assertEquals("[dmg, luck, skill]", metric.words().toString());
		hoverAndCheck(metric, SidePanelPartySection.MetricSwitch.TIPS);
		// A click picks it.
		final Rectangle r = metric.pill(1);
		for (java.awt.event.MouseListener l : metric.getMouseListeners())
		{
			l.mouseClicked(new MouseEvent(metric, MouseEvent.MOUSE_CLICKED, 0L, 0, r.x + 2, r.y + 2, 1, false));
		}
		assertEquals(SidePanelParty.Metric.LUCK, party.partySection().metric());
		assertEquals(1, metric.picked());
	}

	@Test
	public void bothSwitchesFitTheHeaderAtLarger()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			final SidePanelParty.Input in = SidePanelPartyExamples.toa();
			final PvmPerformancePanel party = new PvmPerformancePanel(null, null, null, null);
			party.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
			final SidePanelSection section = SidePanelPictures.sectionOf(party.partySection().top(),
				SidePanelPartySection.PARTY);
			section.setSize(SidePanelStyle.CONTENT_WIDTH, section.getPreferredSize().height);
			SidePanelPictures.layout(section);
			final SidePanelPartySection.MetricSwitch metric = all(section, SidePanelPartySection.MetricSwitch.class).get(0);
			final Rectangle at = javax.swing.SwingUtilities.convertRectangle(metric.getParent(), metric.getBounds(), section);
			assertEquals(larger + "", metric.getPreferredSize().width, at.width);
			assertTrue(larger + " " + at, at.x + at.width <= SidePanelStyle.CONTENT_WIDTH - SidePanelStyle.CARD_PAD_H);
		}
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

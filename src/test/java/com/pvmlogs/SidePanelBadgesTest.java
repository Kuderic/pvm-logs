package com.pvmlogs;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-189: the after-kill card's badges, only when earned (the 100th kill, Flawless where attack rules exist, Clean run,
 * 3 A's in a row), and the kill-end moment: once, 12 frames of 50 ms, stopped when hidden, never with Celebrations off.
 */
public class SidePanelBadgesTest
{
	private static List<String> badges(SidePanelModel.Input in)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Badge b : SidePanelModel.fight(in).card.badges)
		{
			out.add(b.name);
		}
		return out;
	}

	private static LedgerExamples.FakeScope scope(SidePanelModel.Input in)
	{
		return (LedgerExamples.FakeScope) in.scope;
	}

	@Test
	public void theAraxxorKillAt555EarnsNone()
	{
		assertEquals(Collections.emptyList(), badges(SidePanelKillCardExamples.araxxorKill()));
	}

	@Test
	public void everyHundredthKillByTheGamesCount()
	{
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		in.kc = 600;
		assertEquals(Collections.singletonList("600th kill"), badges(in));
		in.kc = 601;
		assertTrue(badges(in).isEmpty());
		// Unknown (0) is never a badge.
		in.kc = 0;
		assertTrue(badges(in).isEmpty());
	}

	@Test
	public void flawlessOnlyWhereAttackRulesCanTellIt()
	{
		// Araxxor has no attack rules: no avoidable figure, so no Flawless however little was taken.
		final SidePanelModel.Input unruled = SidePanelKillCardExamples.araxxorKill();
		scope(unruled).damageTakenAvoidable = 0;
		assertFalse(badges(unruled).contains("Flawless"));
		// With rules and nothing avoidable taken: Flawless.
		final SidePanelModel.Input ruled = SidePanelKillCardExamples.araxxorKill();
		scope(ruled).avoidableEatingTicks = 0;
		scope(ruled).damageTakenAvoidable = 0;
		assertTrue(badges(ruled).contains("Flawless"));
		// With rules and an avoidable hit: none.
		scope(ruled).damageTakenAvoidable = 12;
		assertFalse(badges(ruled).contains("Flawless"));
	}

	@Test
	public void cleanRunIsNoIdleTickAndAPerfectSetup()
	{
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		// The kill had one idle tick: no Clean run.
		assertFalse(badges(in).contains("Clean run"));
		// Eating isn't idling: two ticks of it and no idle tick, with the setup at 99.96%.
		scope(in).ticksLost = 2;
		scope(in).ticksLostEating = 2;
		scope(in).efficiency = 0.9996;
		scope(in).boostCostShare = 0.0003;
		scope(in).switchCostShare = 0;
		assertTrue(badges(in).contains("Clean run"));
		scope(in).efficiency = 0.9990;
		assertFalse(badges(in).contains("Clean run"));
	}

	@Test
	public void threeAsInARowFromTheRecordsStreak()
	{
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		in.standing.streak = 2;
		assertTrue(badges(in).isEmpty());
		in.standing.streak = 3;
		assertEquals(Collections.singletonList("3 A's in a row"), badges(in));
		in.standing.streak = 5;
		assertEquals(Collections.singletonList("5 A's in a row"), badges(in));
		// No record yet: no streak to read.
		in.standing = null;
		assertTrue(badges(in).isEmpty());
	}

	@Test
	public void eachBadgeSaysWhatItReads()
	{
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
		in.kc = 600;
		scope(in).avoidableEatingTicks = 0;
		scope(in).damageTakenAvoidable = 0;
		in.standing.streak = 3;
		final List<PanelSnapshot.Badge> all = SidePanelModel.fight(in).card.badges;
		assertEquals(3, all.size());
		assertTrue(all.get(0).tip, all.get(0).tip.contains("game's kill count"));
		assertTrue(all.get(1).tip, all.get(1).tip.contains("attack rules"));
		assertTrue(all.get(2).tip, all.get(2).tip.contains("A or better"));
	}

	@Test
	public void liveThereAreNoBadges()
	{
		assertTrue(SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card.badges.isEmpty());
	}

	// ---- the kill-end moment ----

	private static PvmPerformancePanel panel()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		return p;
	}

	private static void apply(PvmPerformancePanel p, SidePanelModel.Input in)
	{
		p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
	}

	private static SidePanelKillCard card(PvmPerformancePanel p)
	{
		return (SidePanelKillCard) p.fightTab().getComponent(0);
	}

	@Test
	public void theMomentPlaysOnceWhenTheFightSeenLiveEnds()
	{
		final PvmPerformancePanel p = panel();
		apply(p, SidePanelKillCardExamples.araxxorLive());
		assertFalse(card(p).celebrating());
		apply(p, SidePanelKillCardExamples.araxxorKill());
		assertTrue(card(p).celebrating());
		// The next snapshot of the same kill updates it in place: the moment isn't started again.
		final SidePanelKillCard same = card(p);
		apply(p, SidePanelKillCardExamples.araxxorKill());
		assertTrue(same == card(p));
	}

	@Test
	public void aKillAlreadyOverWhenThePanelOpensDoesntPlayIt()
	{
		final PvmPerformancePanel p = panel();
		apply(p, SidePanelKillCardExamples.araxxorKill());
		assertFalse(card(p).celebrating());
		assertFalse(card(p).timerRunning());
	}

	@Test
	public void itIsTwelveFramesThenStops()
	{
		final SidePanelKillCard c = new SidePanelKillCard(SidePanelModel.fight(SidePanelKillCardExamples.pbKill()).card,
			SidePanelIcons.NONE);
		c.showing = () -> true;
		c.celebrate();
		for (int i = 0; i < 20; i++)
		{
			c.step();
		}
		assertEquals(SidePanelKillCard.FRAMES, c.stepped());
		assertFalse(c.celebrating());
		assertFalse(c.timerRunning());
		assertEquals(600, SidePanelKillCard.FRAMES * SidePanelKillCard.FRAME_MS);
	}

	@Test
	public void hiddenItStopsAtItsFirstFrame() throws Exception
	{
		final SidePanelKillCard c = new SidePanelKillCard(SidePanelModel.fight(SidePanelKillCardExamples.pbKill()).card,
			SidePanelIcons.NONE);
		javax.swing.SwingUtilities.invokeAndWait(c::celebrate);
		assertTrue(c.timerRunning());
		final long end = System.nanoTime() + 2_000_000_000L;
		while (c.timerRunning() && System.nanoTime() < end)
		{
			Thread.sleep(10);
		}
		assertFalse(c.timerRunning());
		assertFalse(c.celebrating());
		assertEquals(0, c.stepped());
	}

	@Test
	public void nothingAnimatesWithCelebrationsOff()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(false);
		final SidePanelModel.Input live = SidePanelKillCardExamples.araxxorLive();
		live.celebrations = false;
		final SidePanelModel.Input kill = SidePanelKillCardExamples.off();
		apply(p, live);
		apply(p, kill);
		assertNull(SidePanelModel.fight(kill).card);
		for (java.awt.Component c : p.fightTab().getComponents())
		{
			assertFalse(c instanceof SidePanelKillCard);
		}
	}

	/** The moment's frames: each a repaint of the card's box through the tab's buffer. Printed; no timing assert. */
	@Test
	public void whatAFrameCosts()
	{
		final PvmPerformancePanel p = panel();
		p.setSize(242, 1000);
		apply(p, SidePanelKillCardExamples.araxxorLive());
		apply(p, SidePanelKillCardExamples.pbKill());
		final javax.swing.JComponent tab = p.fightTab();
		tab.setSize(242, 10_000);
		SidePanelPictures.layout(tab);
		tab.setSize(242, tab.getPreferredSize().height);
		SidePanelPictures.layout(tab);
		final SidePanelKillCard c = card(p);
		c.showing = () -> true;
		final Rectangle box = c.getBounds();
		final List<Long> frames = new ArrayList<>();
		for (int run = 0; run < 20; run++)
		{
			c.celebrate();
			for (int i = 0; i < SidePanelKillCard.FRAMES; i++)
			{
				final BufferedImage img = new BufferedImage(tab.getWidth(), tab.getHeight(), BufferedImage.TYPE_INT_RGB);
				final Graphics2D g = img.createGraphics();
				g.setClip(box);
				final long t0 = System.nanoTime();
				tab.paint(g);
				frames.add(System.nanoTime() - t0);
				g.dispose();
				c.step();
			}
		}
		Collections.sort(frames);
		System.out.println(String.format("T-189: the kill-end moment, %d frames (20 runs of %d): the card's box through "
				+ "the tab's buffer p50 %.2f p99 %.2f ms", frames.size(), SidePanelKillCard.FRAMES,
			frames.get(frames.size() / 2) / 1e6, frames.get((int) (frames.size() * 0.99)) / 1e6));
		assertEquals(20 * SidePanelKillCard.FRAMES, frames.size());
	}
}

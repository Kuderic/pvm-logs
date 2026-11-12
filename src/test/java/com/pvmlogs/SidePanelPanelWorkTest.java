package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * T-218 (Eric, 2026-11-11 21:52: "Game freezes after a boss dies and side panel is recalculating / drawing"): the
 * panel's model built off the client thread from a copy; one rebuild at the kill; History only when shown; no whole
 * rebuild in a fight for a row more; the kill-end moment repaints only the card.
 */
public class SidePanelPanelWorkTest
{
	// ---- 1. the build off the client thread ----

	@Test
	public void theBuildsRunOnThePanelsThreadInOrderAndApplyOnSwings() throws Exception
	{
		final List<String> builtOn = Collections.synchronizedList(new ArrayList<>());
		final List<Integer> applied = Collections.synchronizedList(new ArrayList<>());
		final CountDownLatch done = new CountDownLatch(50);
		final SidePanelBuilder builder = new SidePanelBuilder(r -> SwingUtilities.invokeLater(r));
		final String caller = Thread.currentThread().getName();
		for (int i = 0; i < 50; i++)
		{
			final int n = i;
			builder.submit(() ->
			{
				builtOn.add(Thread.currentThread().getName());
				assertTrue(SidePanelBuilder.onBuildThread());
				return new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()), null,
					SidePanelParty.party(new SidePanelParty.Input()));
			}, s ->
			{
				assertTrue(SwingUtilities.isEventDispatchThread());
				applied.add(n);
				done.countDown();
			});
		}
		assertTrue(done.await(20, TimeUnit.SECONDS));
		for (String t : builtOn)
		{
			assertEquals(SidePanelBuilder.THREAD, t);
			assertNotEquals(caller, t);
		}
		// In the order handed over: each snapshot's chart is sent from where the last one's ended.
		for (int i = 0; i < 50; i++)
		{
			assertEquals(Integer.valueOf(i), applied.get(i));
		}
		assertFalse(SidePanelBuilder.onBuildThread());
		builder.shutdown();
	}

	@Test
	public void aFailedBuildAppliesNothingAndTheNextOneDoes() throws Exception
	{
		final CountDownLatch done = new CountDownLatch(1);
		final List<PanelSnapshot> applied = Collections.synchronizedList(new ArrayList<>());
		final SidePanelBuilder builder = new SidePanelBuilder(Runnable::run);
		builder.submit(() ->
		{
			throw new IllegalStateException("a build that fails");
		}, applied::add);
		builder.submit(() -> new PanelSnapshot(SidePanelModel.fight(new SidePanelModel.Input())), s ->
		{
			applied.add(s);
			done.countDown();
		});
		assertTrue(done.await(20, TimeUnit.SECONDS));
		assertEquals(1, applied.size());
		assertEquals(2, builder.built());
		builder.shutdown();
	}

	/** The copy answers every figure of a scope, so a figure added to Scope can't fall back to a default unnoticed. */
	@Test
	public void theFrozenScopeAnswersEveryFigure()
	{
		for (Method m : LedgerRows.Scope.class.getMethods())
		{
			if (Modifier.isStatic(m.getModifiers()))
			{
				continue;
			}
			try
			{
				final Method own = SidePanelCopy.Frozen.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
				assertSame(m.getName(), SidePanelCopy.Frozen.class, own.getDeclaringClass());
			}
			catch (NoSuchMethodException e)
			{
				throw new AssertionError("SidePanelCopy.Frozen doesn't answer Scope." + m.getName(), e);
			}
		}
	}

	@Test
	public void theCopyHoldsTheFiguresAsTheyWereAndTheBuildReadsNothingLive()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		h.run(120);
		final SidePanelModel.Input in = SidePanelCopy.freeze(h.input(new SidePanelWeaponRows(), 0, false));
		final PanelSnapshot.FightTab before = SidePanelModel.fight(in);
		final int damage = in.scope.damage();
		final int attacks = in.view.attacks.size();
		final String newest = in.view.attacks.get(attacks - 1).key;
		// The fight goes on on the client thread: the copy doesn't move.
		h.run(60);
		assertEquals(damage, in.scope.damage());
		assertEquals(attacks, in.view.attacks.size());
		assertEquals(newest, in.view.attacks.get(attacks - 1).key);
		assertTrue(h.room.getDamageDealt() > damage);
		assertTrue(in.scope instanceof SidePanelCopy.Frozen);
		// The same snapshot built again from it.
		assertEquals(before.structure(), SidePanelModel.fight(in).structure());
		assertEquals(before.card.clock, SidePanelModel.fight(in).card.clock);
		// The same as a build straight from the live room at the time of the copy.
		final SidePanelHydraLagTest.Hydra again = new SidePanelHydraLagTest.Hydra();
		again.run(120);
		final PanelSnapshot.FightTab live = SidePanelModel.fight(again.input(new SidePanelWeaponRows(), 0, false));
		assertEquals(live.structure(), before.structure());
		assertEquals(live.card.clock, before.card.clock);
		assertEquals(live.card.dps, before.card.dps);
		assertEquals(live.damage.rows.get(0).damage, before.damage.rows.get(0).damage);
	}

	@Test
	public void aFrozenInputBuildsTheSameTabAsTheLiveOne()
	{
		final List<java.util.function.Supplier<SidePanelModel.Input>> inputs = java.util.Arrays.asList(
			SidePanelKillCardExamples::araxxorKill, SidePanelKillCardExamples::araxxorLive,
			SidePanelKillCardExamples::vardorvisKill, SidePanelExamples::dead, SidePanelExamples::live,
			SidePanelExamples::unscoredAdds, SidePanelExamples::raid, () -> SidePanelExamples.luckSaved(15),
			() -> SidePanelTimeBarTest.yama());
		for (java.util.function.Supplier<SidePanelModel.Input> make : inputs)
		{
			final PanelSnapshot.FightTab live = SidePanelModel.fight(make.get());
			final PanelSnapshot.FightTab frozen = SidePanelModel.fight(SidePanelCopy.freeze(make.get()));
			assertEquals(live.structure(), frozen.structure());
			assertEquals(texts(live.summaries), texts(frozen.summaries));
			if (live.card != null)
			{
				assertEquals(live.card.clock, frozen.card.clock);
				assertEquals(live.card.dps, frozen.card.dps);
				assertEquals(live.card.luckTime, frozen.card.luckTime);
				assertEquals(live.card.skill, frozen.card.skill);
				assertEquals(live.card.lost, frozen.card.lost);
			}
			if (live.damage != null)
			{
				for (int i = 0; i < live.damage.rows.size(); i++)
				{
					assertEquals(live.damage.rows.get(i).damage, frozen.damage.rows.get(i).damage);
					assertEquals(live.damage.rows.get(i).sub, frozen.damage.rows.get(i).sub);
				}
			}
			if (live.budget != null)
			{
				assertEquals(live.budget.skillSeconds(), frozen.budget.skillSeconds(), 1e-9);
			}
		}
	}

	private static java.util.Map<String, String> texts(java.util.Map<String, PanelSnapshot.Text> m)
	{
		final java.util.Map<String, String> out = new java.util.TreeMap<>();
		m.forEach((k, v) -> out.put(k, v == null ? null : v.text));
		return out;
	}

	// ---- 2. one rebuild at the kill ----

	@Test
	public void aKillWaitsForItsRecordAFewTicksAtMost()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		h.run(212);
		h.kill();
		final SidePanelModel.Input dead = h.input(new SidePanelWeaponRows(), 0, true);
		dead.standing = null;
		assertTrue("a counted kill", LedgerHistory.counts(h.room, Collections.<Fight>emptySet()));
		// Waits while its record is saved, so the card, standing and badges come in one apply.
		assertTrue(PvmPerformancePanel.waitsForRecord(dead, h.room, false, true));
		for (int t = 0; t < PvmPerformancePanel.RECORD_WAIT_TICKS; t++)
		{
			assertTrue(PvmPerformancePanel.holds(t));
		}
		// No longer than 3 s: a slow save shows the kill without its standing.
		assertFalse(PvmPerformancePanel.holds(PvmPerformancePanel.RECORD_WAIT_TICKS));
		// Its standing in: no wait.
		final SidePanelModel.Input recorded = h.input(new SidePanelWeaponRows(), 0, true);
		assertNotNull(recorded.standing);
		assertFalse(PvmPerformancePanel.waitsForRecord(recorded, h.room, false, true));
		// Nothing to wait for: records not loaded, the whole raid on show, a live fight.
		assertFalse(PvmPerformancePanel.waitsForRecord(dead, h.room, false, false));
		assertFalse(PvmPerformancePanel.waitsForRecord(dead, h.room, true, true));
		final SidePanelModel.Input live = h.input(new SidePanelWeaponRows(), 0, false);
		assertFalse(PvmPerformancePanel.waitsForRecord(live, h.room, false, true));
	}

	@Test
	public void theKillIsOneApplyAndOneRebuild()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		h.run(212);
		p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, 0, false)));
		final int builds = p.fightTab().builds();
		h.kill();
		// The snapshot the panel builds once the record is in: the card's final state with its standing.
		final PanelSnapshot dead = SidePanelHydraLagTest.snapshot(h.input(rows, 0, true));
		assertNotNull(dead.fight.card.rank);
		p.apply(dead);
		assertEquals(builds + 1, p.fightTab().builds());
		// The record's refresh, when it comes after, finds nothing new: no rebuild.
		p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, 0, true)));
		assertEquals(builds + 1, p.fightTab().builds());
	}

	@Test
	public void historyIsRebuiltOnlyWhileShown() throws Exception
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		flushSwing();
		final int before = p.historyTab().builds();
		// A kill saved, then its record: the Fight tab on show, History untouched.
		p.refresh();
		p.refresh();
		flushSwing();
		assertEquals(before, p.historyTab().builds());
		// Shown: rebuilt then (its tab's pick), and again for a refresh while it's up.
		SwingUtilities.invokeAndWait(() -> p.tabs().select(p.tabs().getTab(1)));
		final int shown = p.historyTab().builds();
		assertTrue(shown > before);
		p.refresh();
		flushSwing();
		assertEquals(shown + 1, p.historyTab().builds());
		// Back on Fight: hidden again.
		SwingUtilities.invokeAndWait(() -> p.tabs().select(p.tabs().getTab(0)));
		p.refresh();
		flushSwing();
		assertEquals(shown + 1, p.historyTab().builds());
	}

	// ---- 2. no whole rebuild in a fight without a structure change ----

	@Test
	public void aRowMoreInAFightRebuildsNothingWhole()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel.ChartBook book = new PvmPerformancePanel.ChartBook();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		h.run(6);
		p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, book.from(h.room, h.view().states.length), false)));
		final SidePanelFightTab tab = p.fightTab();
		final int builds = tab.builds();
		final int slots = tab.slotBuilds();
		final List<String> structures = new ArrayList<>();
		String last = null;
		while (h.tick < 212)
		{
			h.run(PvmPerformancePanel.UPDATE_TICKS);
			final PanelSnapshot s = SidePanelHydraLagTest.snapshot(h.input(rows, book.from(h.room,
				h.view().states.length), false));
			if (!s.fight.structure().equals(last))
			{
				structures.add(s.fight.structure());
				last = s.fight.structure();
			}
			p.apply(s);
		}
		// Eric's three: the first hit taken (a section that comes), the weapon switched and the missed switch (a row
		// more, in place, not in the structure).
		assertEquals(structures.toString(), 2, structures.size());
		assertEquals("no whole rebuild in the fight", builds, tab.builds());
		// The one section that came (Damage taken, at the first hit) was built on its own; the rows added in place.
		assertEquals(1, tab.slotBuilds() - slots);
		// And the rows are there: two weapons, the hits taken, the switch's part.
		assertEquals(2, count(tab, SidePanelParts.SourceRowView.class));
		assertNotNull(SidePanelPictures.sectionOf(tab, "taken"));
	}

	@Test
	public void aSectionRebuiltOnItsOwnStaysWhereItWasWithItsFoldAndLine()
	{
		final SidePanelHydraLagTest.Hydra h = new SidePanelHydraLagTest.Hydra();
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		h.run(60);
		p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, 0, false)));
		final SidePanelFightTab tab = p.fightTab();
		assertNull(SidePanelPictures.sectionOf(tab, "taken"));
		final List<String> order = ids(tab);
		h.run(30);
		p.apply(SidePanelHydraLagTest.snapshot(h.input(rows, 0, false)));
		final SidePanelSection taken = SidePanelPictures.sectionOf(tab, "taken");
		assertNotNull(taken);
		// Last, as the whole build places it; the rest in their order.
		final List<String> now = ids(tab);
		assertEquals("taken", now.get(now.size() - 1));
		assertEquals(order, now.subList(0, now.size() - 1));
		// The same as a whole build of the same snapshot.
		final PvmPerformancePanel whole = new PvmPerformancePanel(null, null, null, null);
		whole.setCelebrations(true);
		whole.apply(SidePanelHydraLagTest.snapshot(h.input(rows, 0, false)));
		assertEquals(ids(whole.fightTab()), now);
		assertEquals(SidePanelPictures.sectionOf(whole.fightTab(), "taken").summary(), taken.summary());
	}

	// ---- 4. the kill-end moment ----

	@Test
	public void theKillEndMomentRepaintsOnlyTheCardTwentyTimesASecond()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.setCelebrations(true);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()), null,
			SidePanelParty.party(new SidePanelParty.Input())));
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()), null,
			SidePanelParty.party(new SidePanelParty.Input())));
		final SidePanelKillCard card = (SidePanelKillCard) p.fightTab().getComponent(0);
		assertTrue(card.celebrating());
		card.showing = () -> true;
		// 50 ms a frame: 20 a second, for 0.6 s.
		assertEquals(50, SidePanelKillCard.FRAME_MS);
		assertEquals(12, SidePanelKillCard.FRAMES);
		card.setSize(SidePanelStyle.CONTENT_WIDTH, card.getPreferredSize().height);
		final int tab = p.fightTab().tabRepaints();
		final int layouts = SidePanelParts.relayouts;
		final List<Long> frames = new ArrayList<>();
		final BufferedImage img = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_RGB);
		while (card.celebrating())
		{
			card.step();
			final Graphics2D g = img.createGraphics();
			final long t0 = System.nanoTime();
			card.paint(g);
			frames.add(System.nanoTime() - t0);
			g.dispose();
		}
		// The card's own repaints: the tab isn't asked to repaint, nothing is laid out.
		assertEquals(tab, p.fightTab().tabRepaints());
		assertEquals(layouts, SidePanelParts.relayouts);
		assertEquals(SidePanelKillCard.FRAMES, frames.size());
		Collections.sort(frames);
		System.out.println(String.format("T-218, the kill-end moment: %d frames of the card alone, paint p50 %.3f max %.3f"
			+ " ms", frames.size(), frames.get(frames.size() / 2) / 1e6, frames.get(frames.size() - 1) / 1e6));
	}

	// ---- helpers ----

	private static void flushSwing() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> { });
		SwingUtilities.invokeAndWait(() -> { });
	}

	private static List<String> ids(SidePanelFightTab tab)
	{
		final List<String> out = new ArrayList<>();
		for (java.awt.Component c : tab.getComponents())
		{
			if (c instanceof SidePanelSection)
			{
				out.add(((SidePanelSection) c).id());
			}
		}
		return out;
	}

	private static int count(java.awt.Container c, Class<?> type)
	{
		int n = 0;
		for (java.awt.Component k : c.getComponents())
		{
			n += type.isInstance(k) ? 1 : k instanceof java.awt.Container ? count((java.awt.Container) k, type) : 0;
		}
		return n;
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** T-046: the side panel must never lag the game. */
public class SidePanelNoLagTest
{
	// ---- A live fight, fed a tick at a time the way the plugin feeds it ----

	/** A fight recorded tick by tick in one store: an attack every four ticks, a spec now and then, idle and eating spans. */
	private static final class Live
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		final Fight fight = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final FightTimeline line;
		private int cooldown;
		private int attacks;
		int tick;

		Live()
		{
			store.setClock(() -> clock[0]);
			store.attach(fight);
			line = store.of(fight);
			room.add(fight);
		}

		void run(int ticks)
		{
			for (int end = tick + ticks; tick < end; tick++)
			{
				clock[0] = tick;
				if (tick % 97 >= 90)
				{
					fight.recordTickLost(tick % 97 >= 95);
					cooldown = 0;
					continue;
				}
				if (cooldown > 0)
				{
					fight.recordTickSpent();
					cooldown--;
					continue;
				}
				attacks++;
				final boolean special = attacks % 23 == 0;
				fight.recordAttackMade(false, 1.0, false);
				line.describeLatest(special ? "Burning claws" : "Abyssal tentacle", special ? ItemID.BONE_CLAWS
					: ItemID.ABYSSAL_TENTACLE, null, FightTimeline.Style.MELEE, tick);
				if (special)
				{
					line.markLatestSpecial();
				}
				fight.recordExpected(49, 0.55, special ? 30 : 13.5, 233);
				fight.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
				if (attacks % 2 == 1)
				{
					fight.recordDamageDealt(special ? 41 : 27, 0L, true);
					line.attributeSplat(Integer.MIN_VALUE);
				}
				cooldown = 3;
			}
		}

		FightTimeline.View view()
		{
			return FightTimeline.room(room, store);
		}

		SidePanelModel.Input input(SidePanelWeaponRows rows, int chartFrom)
		{
			final SidePanelModel.Input in = new SidePanelModel.Input();
			in.zone = SidePanelExamples.ZONE;
			in.kind = SidePanelModel.Kind.ROOM;
			SidePanelModel.fillRoom(in, room);
			in.view = view();
			in.weaponRows = rows == null ? null : rows.rows(room, in.view);
			in.chartFrom = chartFrom;
			return in;
		}
	}

	// ---- 1. At most every 5 ticks ----

	@Test
	public void aRunningFightUpdatesEveryFiveTicks()
	{
		assertEquals(5, PvmPerformancePanel.UPDATE_TICKS);
		// The same fight running, live updates on: every 5 ticks, not between.
		for (int since = 0; since < 5; since++)
		{
			assertFalse(PvmPerformancePanel.due(false, true, true, true, since));
		}
		assertTrue(PvmPerformancePanel.due(false, true, true, true, 5));
		// Live updates off: nothing until the fight ends, however long.
		assertFalse(PvmPerformancePanel.due(false, true, true, false, 1000));
		// At once: forced (opening the panel, a fold, a tab switch), something on show changed (the fight ending), or no
		// fight running.
		assertTrue(PvmPerformancePanel.due(true, true, true, true, 0));
		assertTrue(PvmPerformancePanel.due(true, true, true, false, 0));
		assertTrue(PvmPerformancePanel.due(false, true, false, true, 0));
		assertTrue(PvmPerformancePanel.due(false, true, false, false, 0));
		assertTrue(PvmPerformancePanel.due(false, false, true, true, 0));
	}

	// ---- 2. No layout between structure changes ----

	@Test
	public void noLayoutWhileAFightRuns()
	{
		final Live live = new Live();
		live.run(130);
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.setSize(new Dimension(242, 1000));
		int sent = live.view().states.length;
		panel.apply(new PanelSnapshot(SidePanelModel.fight(live.input(rows, 0)), null));
		final SidePanelFightTab tab = panel.fightTab();
		final int builds = tab.builds();
		SidePanelPictures.layout(panel);
		int stripRows = SidePanelTickStrip.rows(sent);
		int stripLayouts = 0;
		int digitLayouts = 0;
		int updates = 0;
		// Ten minutes of a fight, a snapshot every 5 ticks.
		while (live.tick < 1000)
		{
			live.run(PvmPerformancePanel.UPDATE_TICKS);
			final int from = Math.max(0, sent - PvmPerformancePanel.CHART_RESEND);
			sent = live.view().states.length;
			final int before = SidePanelParts.relayouts;
			panel.apply(new PanelSnapshot(SidePanelModel.fight(live.input(rows, from)), null));
			updates++;
			assertEquals("no rebuild while the structure holds", builds, tab.builds());
			final int now = SidePanelTickStrip.rows(sent);
			final int asked = SidePanelParts.relayouts - before;
			if (asked > 0)
			{
				// One layout pass for the update. The strip grows by a row once every 36 s; the first update after a build
				// lets its changing values reserve their widest digits; otherwise only a value gaining a digit lays out.
				if (now != stripRows)
				{
					stripLayouts++;
				}
				else if (updates > 1)
				{
					digitLayouts++;
				}
				SidePanelPictures.layout(panel);
			}
			stripRows = now;
		}
		assertTrue(updates >= 170);
		// One per new strip row: 130 to 1,000 ticks is rows 3 to 17.
		assertTrue("strip layouts " + stripLayouts, stripLayouts <= 15);
		// A clock reaching 10:00, damage reaching 1,000 and the like: a handful in ten minutes, against every update before.
		System.out.println("T-046: " + updates + " updates, " + stripLayouts + " layouts for a new strip row, "
			+ digitLayouts + " for a value gaining a digit");
		assertTrue("digit layouts " + digitLayouts, digitLayouts <= 6);
	}

	@Test
	public void textsReserveTheirWidest()
	{
		final SidePanelText t = new SidePanelText(PanelSnapshot.Text.small("1:00", SidePanelStyle.VALUE), true);
		t.setSize(t.getPreferredSize());
		final int wide = t.getPreferredSize().width;
		// At rest it keeps its own width.
		assertEquals(t.getFontMetrics(t.getFont()).stringWidth("1:00"), wide);
		// Changing once laid out, it reserves its widest digits: one layout, then none while it keeps its digit count.
		t.set(PanelSnapshot.Text.small("1:11", SidePanelStyle.VALUE));
		final int reserved = t.getPreferredSize().width;
		assertEquals(SidePanelText.reservedWidth(t.getFontMetrics(t.getFont()), "1:11"), reserved);
		t.setSize(t.getPreferredSize());
		final int before = SidePanelParts.relayouts;
		for (String clock : new String[]{"1:00", "1:48", "9:59", "2:22"})
		{
			t.set(PanelSnapshot.Text.small(clock, SidePanelStyle.VALUE));
		}
		assertEquals(before, SidePanelParts.relayouts);
		assertEquals(reserved, t.getPreferredSize().width);
		// A digit more: it asks for layout.
		t.set(PanelSnapshot.Text.small("10:00", SidePanelStyle.VALUE));
		assertEquals(before + 1, SidePanelParts.relayouts);
		assertTrue(t.getPreferredSize().width > reserved);
	}

	// ---- 3. Cheap painting ----

	@Test
	public void theStripDrawsOnlyNewTicks()
	{
		final Live live = new Live();
		live.run(600);
		final SidePanelChartData data = new SidePanelChartData();
		data.apply(SidePanelModel.chart(live.input(null, 0), LedgerRows.of(live.room), LedgerRows.Colour.NEUTRAL, false));
		final SidePanelTickStrip strip = new SidePanelTickStrip(data);
		strip.setSize(SidePanelStyle.CONTENT_WIDTH, strip.getPreferredSize().height);
		paint(strip, 2);
		assertEquals(600, strip.ticksDrawn());
		// An update 5 ticks on, on the same row: those 5 and the 4 resent behind them.
		int sent = 600;
		live.run(5);
		final SidePanelModel.Input in = live.input(null, sent - PvmPerformancePanel.CHART_RESEND);
		data.apply(SidePanelModel.chart(in, LedgerRows.of(live.room), LedgerRows.Colour.NEUTRAL, false));
		strip.update();
		paint(strip, 2);
		assertEquals(600 + 5 + PvmPerformancePanel.CHART_RESEND, strip.ticksDrawn());
	}

	@Test
	public void theStripAndChartMatchADrawingFromScratch()
	{
		final Live live = new Live();
		live.run(300);
		final SidePanelChartData grown = new SidePanelChartData();
		grown.apply(SidePanelModel.chart(live.input(null, 0), LedgerRows.of(live.room), LedgerRows.Colour.NEUTRAL, false));
		final SidePanelTickStrip strip = new SidePanelTickStrip(grown);
		final SidePanelDamageChart chart = new SidePanelDamageChart(grown);
		strip.setSize(SidePanelStyle.CONTENT_WIDTH, SidePanelTickStrip.rows(400) * 13);
		chart.setSize(SidePanelStyle.CONTENT_WIDTH, SidePanelDamageChart.HEIGHT);
		paint(strip, 2);
		paint(chart, 2);
		int sent = grown.length();
		while (live.tick < 400)
		{
			live.run(5);
			grown.apply(SidePanelModel.chart(live.input(null, Math.max(0, sent - PvmPerformancePanel.CHART_RESEND)),
				LedgerRows.of(live.room),
				LedgerRows.Colour.NEUTRAL, false));
			sent = grown.length();
			strip.update();
			chart.update();
		}
		// The same series in one go, drawn by new components.
		final SidePanelChartData whole = new SidePanelChartData();
		whole.apply(SidePanelModel.chart(live.input(null, 0), LedgerRows.of(live.room), LedgerRows.Colour.NEUTRAL, false));
		final SidePanelTickStrip strip2 = new SidePanelTickStrip(whole);
		final SidePanelDamageChart chart2 = new SidePanelDamageChart(whole);
		strip2.setSize(strip.getSize());
		chart2.setSize(chart.getSize());
		for (int scale = 1; scale <= 2; scale++)
		{
			assertSame(paint(strip2, scale), paint(strip, scale));
			assertSame(paint(chart2, scale), paint(chart, scale));
		}
	}

	@Test
	public void theChartIsCappedAtAPointAColumn()
	{
		final Live live = new Live();
		live.run(3000);
		final SidePanelChartData data = new SidePanelChartData();
		data.apply(SidePanelModel.chart(live.input(null, 0), LedgerRows.of(live.room), LedgerRows.Colour.NEUTRAL, false));
		final SidePanelDamageChart chart = new SidePanelDamageChart(data);
		chart.setSize(SidePanelStyle.CONTENT_WIDTH, SidePanelDamageChart.HEIGHT);
		paint(chart, 1);
		assertEquals(SidePanelStyle.CONTENT_WIDTH, chart.points());
		paint(chart, 2);
		assertEquals(2 * SidePanelStyle.CONTENT_WIDTH, chart.points());
	}

	@Test
	public void theTabPaintsTheSameThroughItsBuffer()
	{
		for (SidePanelModel.Input in : new SidePanelModel.Input[]{SidePanelExamples.live(), SidePanelExamples.dead()})
		{
			final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
			panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
			final SidePanelFightTab tab = panel.fightTab();
			tab.setSize(SidePanelPictures.SIDEBAR - SidePanelPictures.SCROLLBAR, 10_000);
			SidePanelPictures.layout(tab);
			tab.setSize(tab.getWidth(), tab.getPreferredSize().height);
			SidePanelPictures.layout(tab);
			for (int scale = 1; scale <= 2; scale++)
			{
				// Printed: painted straight, as the renders paint it. Painted: through the buffer, as the screen gets it.
				final BufferedImage printed = image(tab.getWidth(), tab.getHeight(), scale);
				final Graphics2D pg = graphics(printed, scale);
				tab.printAll(pg);
				pg.dispose();
				final BufferedImage painted = image(tab.getWidth(), tab.getHeight(), scale);
				final Graphics2D g = graphics(painted, scale);
				tab.paint(g);
				g.dispose();
				assertSame(printed, painted);
			}
		}
	}

	@Test
	public void aValueRepaintComesThroughTheTab()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		assertTrue(panel.fightTab().isPaintingOrigin());
	}

	// ---- 4. The Damage rows' sums ----

	@Test
	public void keptRowsMatchAddingUpEveryAttack()
	{
		final Live live = new Live();
		final SidePanelWeaponRows kept = new SidePanelWeaponRows();
		int maxAdded = 0;
		while (live.tick < 1500)
		{
			live.run(PvmPerformancePanel.UPDATE_TICKS);
			final FightTimeline.View view = live.view();
			final List<SidePanelModel.WeaponRow> rows = kept.rows(live.room, view);
			final List<SidePanelModel.WeaponRow> all = SidePanelModel.weaponRows(view);
			assertEquals(all.size(), rows.size());
			for (int i = 0; i < all.size(); i++)
			{
				final SidePanelModel.WeaponRow a = all.get(i);
				final SidePanelModel.WeaponRow b = rows.get(i);
				assertEquals(a.name, b.name);
				assertEquals(a.special, b.special);
				assertEquals(a.attacks, b.attacks);
				assertEquals(a.damage, b.damage);
				assertEquals(a.expected, b.expected, 1e-9);
				assertEquals(a.expectedKnown, b.expectedKnown);
				assertEquals(a.itemId, b.itemId);
				assertEquals(a.style, b.style);
			}
			if (live.tick > 300)
			{
				maxAdded = Math.max(maxAdded, kept.added());
			}
		}
		// Each build adds the last minute's attacks (an attack every 4 ticks: about 25) and the few that settle: the same at
		// 15 minutes as at 2.
		assertTrue("added " + maxAdded, maxAdded <= SidePanelWeaponRows.SETTLE_TICKS / 4 + 5);
		// Another scope starts over.
		final Live other = new Live();
		other.run(50);
		assertEquals(SidePanelModel.weaponRows(other.view()).get(0).attacks, kept.rows(other.room, other.view()).get(0).attacks);
	}

	// ---- helpers ----

	private static BufferedImage paint(javax.swing.JComponent c, int scale)
	{
		final BufferedImage img = image(c.getWidth(), c.getHeight(), scale);
		final Graphics2D g = graphics(img, scale);
		c.paint(g);
		g.dispose();
		return img;
	}

	private static BufferedImage image(int w, int h, int scale)
	{
		return new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
	}

	private static Graphics2D graphics(BufferedImage img, int scale)
	{
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		return g;
	}

	private static void assertSame(BufferedImage expected, BufferedImage actual)
	{
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		int differ = 0;
		for (int y = 0; y < expected.getHeight(); y++)
		{
			for (int x = 0; x < expected.getWidth(); x++)
			{
				if (expected.getRGB(x, y) != actual.getRGB(x, y))
				{
					differ++;
				}
			}
		}
		assertEquals("pixels that differ", 0, differ);
	}
}

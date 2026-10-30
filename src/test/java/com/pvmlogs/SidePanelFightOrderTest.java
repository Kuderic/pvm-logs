package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * T-112: the Fight tab's order (tiles, Damage, Luck, Where the time went, Execution, then the rest), the luck curve's
 * shaded tail and tooltip, and one hover line on Damage over time and Ticks (Eric, 2026-10-30 00:45).
 */
public class SidePanelFightOrderTest
{
	// ---- the order ----

	@Test
	public void afterAKillTheTabReadsTilesDamageLuckTimeExecutionThenTheRest()
	{
		// The 2:17 Vardorvis kill, with damage taken.
		final List<String> order = order(SidePanelExamples.dead());
		assertEquals("[tiles, damage, curve, budget, execution, chart, ticks, details, taken]", order.toString());
	}

	/** Eric's pick (07:45): at the top 1% the caption reads 99%, never 100%; a share is 1% to 99%. */
	@Test
	public void theCaptionsSharesAreOneTo99Percent()
	{
		assertEquals(99, SidePanelModel.curveShare(0.996));
		assertEquals(99, SidePanelModel.curveShare(1.0));
		assertEquals(1, SidePanelModel.curveShare(0.002));
		assertEquals(1, SidePanelModel.curveShare(0));
		assertEquals(62, SidePanelModel.curveShare(0.62));
		for (int z : new int[]{15, -15, 7, -7})
		{
			final PanelSnapshot.Curve c = SidePanelModel.fight(SidePanelExamples.luckSaved(z)).curve;
			assertFalse(c.caption, c.caption.startsWith("100%") || c.caption.startsWith("0%"));
			assertFalse(c.tooltip, c.tooltip.contains(" 100%") || c.tooltip.contains(" 0%"));
		}
		assertTrue(SidePanelModel.fight(SidePanelExamples.luckSaved(15)).curve.caption.startsWith("99% "));
	}

	@Test
	public void liveTheAfterFightSectionsAreJustNotThere()
	{
		// Execution runs live (executionLive); Luck's curve, the time budget and Details wait for the end.
		final List<String> order = order(SidePanelExamples.live());
		assertEquals("[tiles, damage, execution, chart, ticks, taken]", order.toString());
	}

	@Test
	public void aRoomWithAddsPutsTargetsAfterExecution()
	{
		final List<String> order = order(SidePanelExamples.unscoredAdds());
		assertTrue(order.toString(), order.indexOf("execution") < order.indexOf("targets"));
		assertTrue(order.toString(), order.indexOf("damage") < order.indexOf("targets"));
	}

	@Test
	public void aMonsterRunKeepsItsLightViewInTheSameOrder()
	{
		assertEquals("[tiles, damage, chart, ticks, taken]", order(SidePanelRunExamples.saved()).toString());
	}

	@Test
	public void historysPickedKillFollowsTheSameOrder()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		kill.fight.end(true, SidePanelExamples.at(15, 31));
		final List<Fight> history = java.util.Collections.singletonList(kill.fight);
		final SidePanelModel.Input in = SidePanelHistory.savedKill(history, SidePanelHistory.allRooms(history).get(0), -1,
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE, null, kill.view());
		final List<String> order = order(in);
		assertEquals(order.indexOf("tiles") + 1, order.indexOf("damage"));
		assertEquals(order.indexOf("damage") + 1, order.indexOf("curve"));
		assertEquals(order.indexOf("curve") + 1, order.indexOf("budget"));
		assertEquals(order.indexOf("budget") + 1, order.indexOf("execution"));
	}

	// ---- the curve's tail ----

	@Test
	public void theTailFollowsZ()
	{
		// Top: from the marker to the right edge; bottom: from the left edge to the marker.
		assertEquals(2.33, SidePanelLuckCurve.tail(2.33)[0], 1e-9);
		assertEquals(3, SidePanelLuckCurve.tail(2.33)[1], 1e-9);
		assertEquals(-3, SidePanelLuckCurve.tail(-1.28)[0], 1e-9);
		assertEquals(-1.28, SidePanelLuckCurve.tail(-1.28)[1], 1e-9);
		assertEquals(0, SidePanelLuckCurve.tail(0)[0], 1e-9);
		// Clamped as the marker is.
		assertEquals(SidePanelModel.CURVE_Z_LIMIT, SidePanelLuckCurve.tail(9)[0], 1e-9);
	}

	@Test
	public void theCurveExplainsItsTail()
	{
		final String top = SidePanelModel.curveTip(2.33, 0.99);
		assertTrue(top, top.startsWith("Top 1%: 1% of fights with these attacks would have dealt more, the shaded right"));
		assertTrue(top, top.contains("thin sliver"));
		final String bottom = SidePanelModel.curveTip(-1.28, 0.10);
		assertTrue(bottom, bottom.startsWith("Bottom 10%: 10% of fights with these attacks would have dealt less, the"
			+ " shaded left"));
		final String middle = SidePanelModel.curveTip(0.2, 0.58);
		assertTrue(middle, middle.startsWith("Average: 42% of fights with these attacks would have dealt more"));
		// On the tab: the curve carries it.
		final SidePanelFightTab view = view(SidePanelExamples.dead());
		final SidePanelLuckCurve curve = all(view, SidePanelLuckCurve.class).get(0);
		assertNotNull(curve.getToolTipText());
		assertTrue(curve.getToolTipText(), curve.getToolTipText().contains("% of fights with these attacks"));
	}

	// ---- the hover line ----

	@Test
	public void hoveringTheChartMarksTheSameTickInTheStripWithNoRebuild()
	{
		final SidePanelFightTab view = view(SidePanelExamples.sireWeapons());
		layoutAll(view);
		final SidePanelDamageChart chart = all(view, SidePanelDamageChart.class).get(0);
		final SidePanelTickStrip strip = all(view, SidePanelTickStrip.class).get(0);
		final int builds = view.builds();
		final java.awt.Dimension chartSize = chart.getSize();
		move(chart, chart.getWidth() / 2, 10);
		final int tick = chart.tickAt(chart.getWidth() / 2);
		assertTrue(tick > 0);
		assertEquals(tick, data(view).hover());
		// The strip's cell for that tick is the one hovered: the strip maps it back.
		final int col = tick % SidePanelTickStrip.PER_ROW;
		final int row = tick / SidePanelTickStrip.PER_ROW;
		final int sx = (int) Math.round(col * (SidePanelTickStrip.TICK + SidePanelTickStrip.GAP) + 1);
		final int sy = row * (SidePanelTickStrip.TICK_HEIGHT + SidePanelTickStrip.ROW_GAP) + 2;
		assertEquals(tick, strip.tickAt(sx, sy));
		// No rebuild, no new size: nothing to lay out.
		assertEquals(builds, view.builds());
		assertEquals(chartSize, chart.getSize());
		// Leaving clears it.
		exit(chart);
		assertEquals(-1, data(view).hover());
	}

	@Test
	public void hoveringATickCellPutsTheLineOnTheChart()
	{
		final SidePanelFightTab view = view(SidePanelExamples.sireWeapons());
		layoutAll(view);
		final SidePanelTickStrip strip = all(view, SidePanelTickStrip.class).get(0);
		final SidePanelDamageChart chart = all(view, SidePanelDamageChart.class).get(0);
		// The 12th tick of the second row.
		final int sx = (int) Math.round(11 * (SidePanelTickStrip.TICK + SidePanelTickStrip.GAP) + 1);
		final int sy = SidePanelTickStrip.TICK_HEIGHT + SidePanelTickStrip.ROW_GAP + 2;
		move(strip, sx, sy);
		assertEquals(SidePanelTickStrip.PER_ROW + 11, data(view).hover());
		assertEquals(SidePanelTickStrip.PER_ROW + 11, chart.tickAt((int) Math.round(chart.xOf(SidePanelTickStrip.PER_ROW
			+ 11))));
		// Between rows: nothing.
		move(strip, sx, SidePanelTickStrip.TICK_HEIGHT + 1);
		assertEquals(-1, data(view).hover());
	}

	@Test
	public void theReadoutIsTheClockAndDamageAgainstExpected()
	{
		final SidePanelFightTab view = view(SidePanelExamples.sireWeapons());
		final SidePanelDamageChart chart = all(view, SidePanelDamageChart.class).get(0);
		final String r = chart.readout(100, true);
		assertTrue(r, r.matches("1:00 · [\\d,]+ / [\\d,]+"));
		assertTrue(chart.readout(100, false).matches("1:00 · [\\d,]+"));
	}

	// ---- helpers ----

	private static SidePanelChartData data(SidePanelFightTab view)
	{
		try
		{
			final java.lang.reflect.Field f = SidePanelFightTab.class.getDeclaredField("chartData");
			f.setAccessible(true);
			return (SidePanelChartData) f.get(view);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private static SidePanelFightTab view(SidePanelModel.Input in)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelFightTab view = panel.fightTab();
		view.apply(SidePanelModel.fight(in));
		return view;
	}

	private static void layoutAll(SidePanelFightTab view)
	{
		view.setSize(SidePanelStyle.CONTENT_WIDTH, view.getPreferredSize().height);
		SidePanelPictures.layout(view);
	}

	/** The tab's sections in order, with the tiles' grid as "tiles". */
	private static List<String> order(SidePanelModel.Input in)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : view(in).getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				out.add(((SidePanelSection) k).id());
			}
			else if (k instanceof Container && !all((Container) k, SidePanelStatTile.class).isEmpty())
			{
				out.add("tiles");
			}
		}
		return out;
	}

	private static void move(Component c, int x, int y)
	{
		for (MouseMotionListener l : c.getMouseMotionListeners())
		{
			l.mouseMoved(new MouseEvent(c, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false));
		}
	}

	private static void exit(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseExited(new MouseEvent(c, MouseEvent.MOUSE_EXITED, 0L, 0, 0, 0, 0, false));
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

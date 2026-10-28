package com.pvmlogs;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** T-042: "Where the time went" marks your real time, and the time luck saved reads as a dimmed stretch past it. */
public class SidePanelTimeBudgetTest
{
	private static final int W = SidePanelStyle.CONTENT_WIDTH;

	private static PanelSnapshot.TimeBudget budget(SidePanelModel.Input in)
	{
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
		assertNotNull(b);
		return b;
	}

	@Test
	public void theTickIsAtYourRealTime()
	{
		final PanelSnapshot.TimeBudget b = budget(SidePanelExamples.luckSaved(15));
		// Eric's 20:15 kill: T 76.2 s, L about 15 s.
		assertEquals(76.2, b.clock, 1e-9);
		assertEquals(15, b.luckSaved, 0.1);
		assertEquals(b.clock + b.luckSaved, b.total, 1e-9);
		assertEquals(b.clock / (b.clock + b.luckSaved) * W, SidePanelTimeBudgetBar.tickX(b, W), 1e-9);
		assertEquals(178, SidePanelTimeBudgetBar.tickX(b, W), 0.5);
		assertEquals("1:16", b.clockLabel);
		assertEquals("1:31 at average luck", b.endLabel);
	}

	@Test
	public void withoutTimeSavedTheTickIsAtTheEnd()
	{
		final PanelSnapshot.TimeBudget b = budget(SidePanelExamples.luckSaved(-7));
		assertEquals(0, b.luckSaved, 0);
		assertEquals(b.clock, b.total, 1e-9);
		assertEquals(W, SidePanelTimeBudgetBar.tickX(b, W), 1e-9);
		assertNull(b.endLabel);
		final SidePanelTimeBudgetBar.Labels l = SidePanelTimeBudgetBar.labels(b, W, metrics(1));
		assertEquals("1:16", l.clock);
		assertEquals(W, l.clockX + metrics(1).stringWidth(l.clock), 1e-9);
		assertNull(l.end);
	}

	@Test
	public void labelsNeverOverlap()
	{
		for (int scale = 1; scale <= 2; scale++)
		{
			final FontMetrics fm = metrics(scale);
			for (double luck : new double[]{2, 15, 60, -7})
			{
				final PanelSnapshot.TimeBudget b = budget(SidePanelExamples.luckSaved(luck));
				final SidePanelTimeBudgetBar.Labels l = SidePanelTimeBudgetBar.labels(b, W, fm);
				final String at = "L " + luck + " at " + scale + "×";
				final double tick = SidePanelTimeBudgetBar.tickX(b, W);
				if (l.clock != null)
				{
					final double right = l.clockX + fm.stringWidth(l.clock);
					assertTrue(at, l.clockX >= 0 && right <= W);
					if (l.end != null)
					{
						// Left of the tick, and clear of the end's label.
						assertTrue(at, right + SidePanelTimeBudgetBar.GAP <= tick);
						assertTrue(at, right + SidePanelTimeBudgetBar.GAP <= l.endX);
					}
				}
				if (l.end != null)
				{
					assertTrue(at, l.endX >= 0 && l.endX + fm.stringWidth(l.end) <= W + 1e-9);
				}
				// Every bar marks your time: on its own, or first in the end's label.
				assertTrue(at, l.clock != null || l.joined && l.end.startsWith(b.clockLabel));
				// The average marker's label is on its own line under the bar, the clock's above it.
				assertTrue(at, SidePanelTimeBudgetBar.LABEL_BASELINE + fm.getDescent()
					<= SidePanelTimeBudgetBar.HEIGHT - 1 - fm.getAscent());
				assertTrue(at, SidePanelTimeBudgetBar.LABEL_BASELINE < SidePanelTimeBudgetBar.TICK_TOP);
			}
		}
		// 2 s: too close to share the row apart, so one label: "1:16 · 1:18 at average luck".
		final SidePanelTimeBudgetBar.Labels close = SidePanelTimeBudgetBar.labels(budget(SidePanelExamples.luckSaved(2)),
			W, metrics(1));
		assertTrue(close.joined);
		assertEquals("1:16 · 1:18 at average luck", close.end);
	}

	@Test
	public void pastTheTickIsDimmedAndNotOutlined()
	{
		final PanelSnapshot.TimeBudget b = budget(SidePanelExamples.luckSaved(15));
		for (int scale = 1; scale <= 2; scale++)
		{
			final BufferedImage img = paint(b, scale);
			final int tick = (int) Math.round(SidePanelTimeBudgetBar.tickX(b, W) * scale);
			final Set<Integer> full = new HashSet<>(Arrays.asList(SidePanelStyle.KEPT.getRGB(),
				SidePanelStyle.MINOR_COST.getRGB(), SidePanelStyle.COST.getRGB(), SidePanelStyle.EATING.getRGB(),
				SidePanelStyle.BETTER.getRGB()));
			final int top = SidePanelTimeBudgetBar.BAR_TOP * scale;
			final int bottom = (SidePanelTimeBudgetBar.BAR_TOP + SidePanelTimeBudgetBar.BAR_HEIGHT) * scale - 1;
			// Before the tick, best play at full strength, and the losses too (uptime's orange just left of the tick).
			assertEquals(SidePanelStyle.KEPT.getRGB(), img.getRGB(10 * scale, top + 4 * scale));
			final double seconds = W / b.total;
			final int uptime = (int) Math.round((b.segments.get(0).seconds + 0.9) * seconds * scale);
			final Color uptimeColour = b.segments.get(1).fill == PanelSnapshot.Fill.COST ? SidePanelStyle.COST
				: SidePanelStyle.MINOR_COST;
			assertEquals(uptimeColour.getRGB(), img.getRGB(uptime, top + 4 * scale));
			int dimmed = 0;
			for (int x = tick + 2 * scale; x < W * scale; x++)
			{
				for (int y = top; y <= bottom; y++)
				{
					final int rgb = img.getRGB(x, y);
					// No segment shows at full strength there, and no outline in Better runs round it.
					assertFalse("x " + x + " y " + y + " at " + scale + "×", full.contains(rgb));
					if (luminance(rgb) < luminance(SidePanelStyle.KEPT.getRGB()) * 0.75)
					{
						dimmed++;
					}
				}
			}
			assertTrue(dimmed > 0);
		}
	}

	@Test
	public void theLossesAreInsideYourTime()
	{
		// Eric, after T-042: uptime, eating and the setup happened inside your 1:16, so they're drawn before the tick; only
		// best play got shorter, and its last L seconds go past the tick.
		final PanelSnapshot.TimeBudget b = budget(SidePanelExamples.luckSaved(15));
		final List<PanelSnapshot.Segment> s = b.segments;
		final PanelSnapshot.Segment saved = s.get(s.size() - 1);
		assertEquals(PanelSnapshot.Fill.KEPT, saved.fill);
		assertEquals(b.luckSaved, saved.seconds, 1e-9);
		double before = 0;
		for (int i = 0; i < s.size() - 1; i++)
		{
			before += s.get(i).seconds;
		}
		// (B - L) + Cu + Ce + Cb + Cp + Cg + U = T: every loss ends at or before the tick.
		assertEquals(b.clock, before, 1e-9);
		assertEquals(PanelSnapshot.Fill.KEPT, s.get(0).fill);
		assertEquals(76.2 - 1.8 - 2.4 - 1.0, s.get(0).seconds, 1e-6);
		// The past-the-tick stretch is best play, as the legend's swatch shows it.
		assertEquals(b.clock / b.total * W, SidePanelTimeBudgetBar.tickX(b, W), 1e-9);

		// Luck that cost time: the bar as before, nothing past the clock.
		final PanelSnapshot.TimeBudget slower = budget(SidePanelExamples.luckSaved(-7));
		assertEquals(PanelSnapshot.Fill.UNSCORED, slower.segments.get(slower.segments.size() - 1).fill);
		assertEquals(slower.clock, slower.sum(), 1e-9);
	}

	@Test
	public void bestPlayAndTheRowsSecondsDontMove()
	{
		final PanelSnapshot.TimeBudget b = budget(SidePanelExamples.luckSaved(15));
		final List<PanelSnapshot.BudgetRow> rows = b.rows;
		// B = T - U + L - (Cu + Ce + Cb) = 76.2 + 15 - 1.8 - 2.4 - 1 = 86 s.
		assertEquals("best play", rows.get(0).label.text);
		assertEquals("1:26", rows.get(0).value.text);
		assertEquals("+2 s", rows.get(1).value.text);
		assertEquals("eating", rows.get(2).label.text);
		assertEquals("+2 s", rows.get(2).value.text);
		assertEquals("stat boost", rows.get(3).label.text);
		assertEquals("+1 s", rows.get(3).value.text);
		double sum = 0;
		for (PanelSnapshot.Segment s : b.segments)
		{
			sum += s.seconds;
		}
		assertEquals(b.total, sum, 1e-9);

		// Luck's row: the striped swatch, the Luck tile's words and colour, and that it's the part past your time.
		final PanelSnapshot.BudgetRow luck = rows.get(4);
		assertEquals("luck", luck.label.text);
		assertEquals("past 1:16", luck.hint.text);
		assertEquals("15 s faster", luck.value.text);
		assertEquals(SidePanelStyle.BETTER, luck.value.colour);
		assertEquals(PanelSnapshot.Fill.LUCK_FASTER, luck.swatch);
		assertEquals(SidePanelStyle.BETTER, b.luckSavedColour);

		// In the middle half the stretch is striped grey, the same swatch.
		final PanelSnapshot.TimeBudget mid = budget(SidePanelExamples.luckSaved(2));
		final PanelSnapshot.BudgetRow average = mid.rows.get(4);
		assertEquals("luck average", average.label.text);
		assertEquals("past 1:16", average.hint.text);
		assertEquals(PanelSnapshot.Fill.LUCK_FASTER, average.swatch);
		assertEquals(SidePanelStyle.AVERAGE_STRIPE, mid.luckSavedColour);

		// Luck that cost time: no "past" hint, the bar as before.
		final PanelSnapshot.BudgetRow slower = budget(SidePanelExamples.luckSaved(-7)).rows.get(4);
		assertEquals("7 s slower", slower.value.text);
		assertTrue(slower.hint.text.isEmpty());
	}

	private static FontMetrics metrics(int scale)
	{
		final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		final FontMetrics fm = g.getFontMetrics(SidePanelStyle.Type.SMALL.font());
		g.dispose();
		return fm;
	}

	private static BufferedImage paint(PanelSnapshot.TimeBudget b, int scale)
	{
		final SidePanelTimeBudgetBar bar = new SidePanelTimeBudgetBar(b);
		bar.setSize(W, SidePanelTimeBudgetBar.HEIGHT);
		final BufferedImage img = new BufferedImage(W * scale, SidePanelTimeBudgetBar.HEIGHT * scale,
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(SidePanelStyle.CARD);
		g.fillRect(0, 0, W, SidePanelTimeBudgetBar.HEIGHT);
		bar.printAll(g);
		g.dispose();
		return img;
	}

	private static double luminance(int rgb)
	{
		final Color c = new Color(rgb);
		return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
	}
}

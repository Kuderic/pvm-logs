package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * T-117 (Eric, 2026-10-30, with the 10:04 update): time spent not attacking
 * reads in whole ticks (uptime's cost, eating, mechanics), luck and comparisons
 * with a kill's time stay in seconds ({@code 14 s slower}), the setup parts read
 * the damage they cost ({@link SetupDamageTest}), and clocks stay clocks.
 * {@code 25t} is the ticks' short form, only for a box saved too narrow.
 */
public class TicksTest
{
	// ---- the helpers ----

	@Test
	public void secondsRoundToWholeTicksHalfAwayFromZero()
	{
		assertEquals(0, LedgerRows.ticks(0));
		assertEquals(0, LedgerRows.ticks(-0.0));
		assertEquals(0, LedgerRows.ticks(Double.NaN));
		// One tick, either way.
		assertEquals(1, LedgerRows.ticks(0.6));
		assertEquals(-1, LedgerRows.ticks(-0.6));
		// ±0.3 s is half a tick: away from zero, to one.
		assertEquals(1, LedgerRows.ticks(0.3));
		assertEquals(-1, LedgerRows.ticks(-0.3));
		assertEquals(0, LedgerRows.ticks(0.29));
		assertEquals(0, LedgerRows.ticks(-0.29));
		// Whole ticks of seconds stay whole, though 3.6 / 0.6 is 5.999… in doubles.
		assertEquals(6, LedgerRows.ticks(3.6));
		assertEquals(10, LedgerRows.ticks(10 * LedgerRows.SECONDS_PER_TICK));
		// Large: an hour's luck, a long trip's uptime.
		assertEquals(6000, LedgerRows.ticks(3600));
		assertEquals(-1500, LedgerRows.ticks(-900));
	}

	@Test
	public void luckStaysInSeconds()
	{
		// The 10:04 update: fasterOrSlower is master's, in seconds.
		assertEquals("even", LedgerRows.fasterOrSlower(0));
		assertEquals("even", LedgerRows.fasterOrSlower(0.49));
		assertEquals("1 s faster", LedgerRows.fasterOrSlower(0.6));
		assertEquals("1 s slower", LedgerRows.fasterOrSlower(-0.6));
		assertEquals("14 s slower", LedgerRows.fasterOrSlower(-13.8));
	}

	@Test
	public void aPartsCostSaysTicksFromOne()
	{
		assertEquals("86%", LedgerRows.withTicks("86%", 0));
		assertEquals("86%", LedgerRows.withTicks("86%", 0.29));
		assertEquals("86%", LedgerRows.withTicks("86%", Double.NaN));
		assertEquals("86% (-1 tick)", LedgerRows.withTicks("86%", 0.3));
		assertEquals("86% (-1 tick)", LedgerRows.withTicks("86%", 0.6));
		assertEquals("86% (-25 ticks)", LedgerRows.withTicks("86%", 15));
		assertEquals("drained (-4 ticks)", LedgerRows.withTicks(LedgerRows.DRAINED, 2.4));
		assertEquals("96% (-1,500 ticks)", LedgerRows.withTicks("96%", 900));
		// The side panel's callers keep the old name until T-118; it says ticks too.
		assertEquals("86% (-25 ticks)", LedgerRows.withSeconds("86%", 15));
		assertEquals("86% (-25 ticks)", LedgerRows.partValue(0.86, 15));
		assertEquals("1 tick", LedgerRows.ticksWord(1));
		assertEquals("7 ticks", LedgerRows.ticksWord(7));
		assertEquals("1,500 ticks", LedgerRows.ticksWord(1500));
	}

	// ---- the fight overlay ----

	private static List<LedgerRows.Row> kill()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.ticksMechanics = 10;
		return LedgerRows.build(s, LedgerRows.State.fight("Vardorvis", true, true, false), new LedgerRows.Settings());
	}

	private static LedgerRows.Row row(List<LedgerRows.Row> rows, String left)
	{
		for (LedgerRows.Row row : rows)
		{
			if (row.left.equals(left))
			{
				return row;
			}
		}
		return null;
	}

	@Test
	public void theFightOverlaySaysTicksAndItsClocksStayClocks()
	{
		final List<LedgerRows.Row> rows = kill();
		// Eric's kill: luck 14.35 s slower (seconds), uptime 15 s and eating 4.2 s (ticks), the boost in damage.
		assertEquals("14 s slower", row(rows, "Luck").right);
		assertEquals("89% (-25 ticks)", row(rows, " uptime").right);
		assertEquals("85% (-16 dmg)", row(rows, " stat boost").right);
		assertEquals("7 ticks", row(rows, LedgerRows.EATING).right);
		assertEquals("10 ticks", row(rows, LedgerRows.MECHANICS).right);
		// Clocks.
		assertEquals("2:16", rows.get(0).right);
		// Execution's own figure stays a share; its ticks are in its parts.
		assertEquals("87%", row(rows, "Execution").right);
	}

	@Test
	public void secondsOnlyForLuckAndItsComparisons()
	{
		for (List<LedgerRows.Row> rows : LedgerPictures.states().values())
		{
			for (LedgerRows.Row r : rows)
			{
				// No cost in seconds, and no eating or mechanics in seconds.
				assertFalse(r.toString(), r.right.matches(".*\\(-\\d+ s\\).*"));
				if (r.left.equals(LedgerRows.EATING) || r.left.equals(LedgerRows.MECHANICS))
				{
					assertTrue(r.toString(), r.right.matches("[\\d,]+ ticks?"));
				}
				// What reads in seconds is luck's: faster or slower.
				if (r.right.matches(".*\\d s\\b.*"))
				{
					assertTrue(r.toString(), r.right.matches(".*\\d s (faster|slower)\\)?"));
				}
			}
		}
	}

	// ---- fit ----

	@Test
	public void everyRowFitsAndTheShortFormOnlyComesWhenSavedNarrow()
	{
		final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		for (Font font : Arrays.asList(FontManager.getRunescapeFont(), FontManager.getRunescapeBoldFont(),
			FontManager.getRunescapeSmallFont()))
		{
			g.setFont(font);
			for (List<LedgerRows.Row> rows : LedgerPictures.states().values())
			{
				final PvmPerformanceOverlay.Lines lines = PvmPerformanceOverlay.lines(g, rows, null);
				if (lines == null)
				{
					continue;
				}
				// Unsaved: the box grows to its rows (under the cap), and nothing takes its short form.
				assertTrue(lines.width < PvmPerformanceOverlay.MAX_WIDTH);
				for (LedgerRows.Row r : lines.rows)
				{
					assertFalse(font.getName() + " " + r, r.right.matches(".*\\d+t\\b.*"));
				}
			}
			// Saved at the narrowest width: uptime falls back to its short form rather than wrap, where it must.
			final List<LedgerRows.Row> narrow = PvmPerformanceOverlay.lines(g, kill(),
				new Dimension(PvmPerformanceOverlay.MIN_WIDTH, 0)).rows;
			final LedgerRows.Row uptime = row(narrow, " uptime");
			assertTrue(font.getName() + " " + uptime, uptime.right.equals("89% (-25t)")
				|| uptime.right.equals("89% (-25 ticks)"));
		}
	}

	@Test
	public void shortFormsAreThereForEveryTickRow()
	{
		final List<LedgerRows.Row> rows = kill();
		assertEquals("89% (-25t)", row(rows, " uptime").shortRight);
		assertEquals("7t", row(rows, LedgerRows.EATING).shortRight);
		assertEquals("10t", row(rows, LedgerRows.MECHANICS).shortRight);
		// Luck in seconds keeps master's short forms: none here, with no percentile.
		assertNull(row(rows, "Luck").shortRight);
		// A part that costs nothing has none.
		assertNull(row(rows, " prayer").shortRight);
	}
}

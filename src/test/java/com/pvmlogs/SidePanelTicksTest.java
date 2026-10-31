package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T-118, to the brief's split (Eric, 2026-10-30 10:04): time spent not attacking reads in ticks (uptime, eating,
 * mechanics, unscored adds, the Execution total); luck and comparisons with a kill's time stay in seconds; the setup
 * costs (stat boost, prayer, gear) read in damage on the Execution card, from T-117's figures, and in Where the time
 * went's tooltips. Clocks stay clocks, and everything fits at 1× and 2× in Normal, Smooth and Larger text.
 */
public class SidePanelTicksTest
{
	private static int checked;
	private static int clocks;

	@Test
	public void theExecutionTileIsTicksAndLuckIsSeconds()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelExamples.dead());
		assertEquals("14 s slower", tile(t, "Luck").sub.text);
		assertEquals("29 ticks lost", tile(t, "Execution").sub.text);
		assertEquals("29t lost", tile(t, "Execution").subShort);
	}

	@Test
	public void whereTheTimeWentIsTicksForIdleTimeAndSecondsForLuck()
	{
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(SidePanelExamples.dead()).budget;
		// The best play is a clock; the idle and setup parts are ticks; luck is seconds.
		assertTrue(b.rows.get(0).value.text, b.rows.get(0).value.text.matches("\\d+:\\d\\d"));
		for (PanelSnapshot.BudgetRow r : b.rows.subList(1, b.rows.size()))
		{
			final boolean luck = r.label.text.startsWith("luck");
			assertTrue(r.label.text + " " + r.value.text, r.value.text.matches(luck
				? "(\\d+ s (faster|slower)|even|" + LedgerRows.UNKNOWN + ")" : "\\+\\d+ ticks?"));
		}
		// "your average 1:54 … 23 s slower", "luck removed 2:02 … 5 s slower than 1:57": clocks, and seconds.
		for (PanelSnapshot.Row r : b.average)
		{
			assertTrue(r.left.text, r.left.text.matches("(your average|luck removed) \\d+:\\d\\d"));
			assertTrue(r.right.text, r.right.text.matches("(\\d+ s (faster|slower)|even)( (than|with) \\d+:\\d\\d)?"));
		}
	}

	@Test
	public void theExecutionCardsSetupRowsAreInDamageAndUptimeInTicks()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			final String label = p.label.text;
			if (label.equals("uptime"))
			{
				assertTrue(p.value.text, p.value.text.matches("\\d+% \\(-\\d+ ticks?\\)"));
			}
			else
			{
				// The same figures as the overlay's rows (T-117).
				final double dmg = SidePanelModel.setupDamage(n, label);
				assertEquals(label, LedgerRows.withDamage(LedgerRows.partPercent(label.equals(SidePanelModel.PRAYER)
					? n.prayerShare : label.equals(SidePanelModel.GEAR) ? n.gearShare : n.boostShare), dmg), p.value.text);
			}
		}
		final List<String> rows = new ArrayList<>();
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			rows.add(p.label.text + " " + p.value.text);
		}
		assertTrue(rows.toString(), rows.stream().anyMatch(r -> r.startsWith("stat boost ") && r.endsWith(" dmg)")));
	}

	@Test
	public void whereTheTimeWentsSetupPartsGiveTheirDamageOnHover()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(in).budget;
		boolean boost = false;
		for (PanelSnapshot.BudgetRow r : b.rows)
		{
			if (r.label.text.equals(SidePanelModel.BOOST))
			{
				boost = true;
				// "stat boost +4 ticks: 31 expected damage lost to a lower boost."
				assertEquals("stat boost " + r.value.text + ": " + LedgerRows.number(LedgerRows.roundHalfAway(n.boostDamage))
					+ " expected damage lost to a lower boost.", r.tooltip);
			}
			if (r.label.text.equals("uptime") || r.label.text.equals("eating") || r.label.text.startsWith("luck"))
			{
				assertEquals(r.label.text, null, r.tooltip);
			}
		}
		assertTrue(boost);
	}

	@Test
	public void withoutTheDamageTheSetupRowsKeepTheirTicks()
	{
		// A kill saved before T-117's figures: the setup rows' cost in ticks, as the uptime row's.
		final SidePanelModel.Input in = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) in.scope).idealSetupDamage = 0;
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			assertFalse(p.value.text, p.value.text.contains("dmg"));
		}
		for (PanelSnapshot.BudgetRow r : t.budget.rows)
		{
			assertEquals(r.label.text, null, r.tooltip);
		}
	}

	@Test
	public void theDrainsAndSpecialsLuckAreSeconds()
	{
		final PanelSnapshot.FightTab drained = SidePanelModel.fight(SidePanelExamples.drainKill("Vardorvis",
			SidePanelExamples.twoHammersOneLanded().drain));
		assertTrue(drained.defence.worth.tooltip, drained.defence.worth.tooltip.contains("14 s faster"));
		assertTrue(drained.curve.caption, drained.curve.caption.endsWith("Your specials' share: 3 s slower"));
	}

	@Test
	public void aTickIsSaidOnceWhereTheFiguresAre()
	{
		assertEquals("Luck and comparisons with your average are in seconds; time spent not attacking is in ticks (1 tick is"
			+ " 0.6 s).", SidePanelModel.TICK_NOTE);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		int noted = 0;
		for (Component c : all(p.fightTab()))
		{
			final String tip = c instanceof JComponent ? ((JComponent) c).getToolTipText() : null;
			if (tip != null && tip.contains(SidePanelModel.TICK_NOTE))
			{
				noted++;
			}
		}
		// Where the time went's card, and the Luck and Execution tiles; T-126: the Execution card's header too, after its
		// sum.
		assertEquals(4, noted);
	}

	@Test
	public void aRunsPaceStaysInSeconds()
	{
		// "one every 18 s" is a pace, not time won or lost.
		final PanelSnapshot.FightTab run = SidePanelModel.fight(SidePanelRunExamples.saved());
		boolean pace = false;
		for (PanelSnapshot.Tile t : run.tiles)
		{
			pace |= t.sub.text.startsWith("one every ") && t.sub.text.endsWith(" s");
		}
		assertTrue(pace);
	}

	/**
	 * Every text in ticks, seconds of luck or setup damage, in each tab and setting, is drawn whole: its full form, or its
	 * short one.
	 */
	@Test
	public void everythingInTicksFitsAtEverySize()
	{
		final List<String> shortForms = new ArrayList<>();
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					for (SidePanelModel.Input in : Arrays.asList(SidePanelExamples.dead(), SidePanelExamples.ruledKill(true),
						SidePanelExamples.leviathanKill(), SidePanelExamples.luckSaved(15), SidePanelExamples.luckSaved(-60),
						SidePanelExamples.drainKill("Vardorvis", SidePanelExamples.twoHammersOneLanded().drain),
						noPercentile()))
					{
						fits(fight(in), larger, smooth, scale, shortForms);
					}
					final PvmPerformancePanel trip = new PvmPerformancePanel(null, null, null, null);
					trip.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()),
						SidePanelModel.trip(SidePanelExamples.tripWithDrains())));
					trip.tabs().select(trip.tabs().getTab(2));
					fitsTab(trip, trip.tripTab(), larger, smooth, scale, shortForms);
				}
			}
		}
		System.out.println("T-118 short forms: " + shortForms + "; tick texts checked: " + checked);
		// Each tab's ticks were found: the tiles, Where the time went, Execution's rows, the Defence card, the Trip tab.
		assertTrue(String.valueOf(checked), checked > 200);
		assertTrue(String.valueOf(clocks), clocks > 20);
	}

	/** A kill saved before T-028, with no percentile: its Luck tile's value is the ticks themselves. */
	private static SidePanelModel.Input noPercentile()
	{
		final SidePanelModel.Input in = SidePanelExamples.luckSaved(-60);
		((LedgerExamples.FakeScope) in.scope).damageVariance = 0;
		return in;
	}

	private static PvmPerformancePanel fight(SidePanelModel.Input in)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		return p;
	}

	private static void fits(PvmPerformancePanel p, boolean larger, boolean smooth, double scale, List<String> shortForms)
	{
		fitsTab(p, p.fightTab(), larger, smooth, scale, shortForms);
	}

	private static void fitsTab(PvmPerformancePanel p, JComponent tab, boolean larger, boolean smooth, double scale,
		List<String> shortForms)
	{
		p.setText(larger, smooth);
		try
		{
			SidePanelPictures.draw(p.tabs(), tab, scale);
			for (Component c : all(tab))
			{
				if (!(c instanceof SidePanelText))
				{
					continue;
				}
				final SidePanelText t = (SidePanelText) c;
				final String text = t.getText();
				final String shown = t.lastShown();
				if (text != null && shown != null && (text.matches("(your average|luck removed) \\d+:\\d\\d")
					|| text.equals(SidePanelModel.WORTH) || text.equals(SidePanelModel.SPEC_LUCK)
					|| text.equals("expected dmg added") || text.equals("your specials' luck")))
				{
					// A clock or label beside a value in ticks stays whole: the value takes its short form first.
					final String at = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale + "×: ";
					assertFalse(at + text + " => " + shown, shown.contains(LedgerRows.CUT));
					clocks++;
				}
				if (text == null || shown == null || !(text.contains(" tick") || text.matches(".*\\d+t( .*)?")
					|| text.endsWith(" dmg)") || text.matches(".*\\d+ s (faster|slower).*")))
				{
					continue;
				}
				final String where = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale + "×: ";
				checked++;
				assertFalse(where + text + " => " + shown, shown.contains(LedgerRows.CUT));
				if (!shown.equals(text))
				{
					shortForms.add(where + text + " => " + shown);
				}
			}
		}
		finally
		{
			SidePanelStyle.setText(false, false);
		}
	}

	private static PanelSnapshot.Tile tile(PanelSnapshot.FightTab t, String label)
	{
		for (PanelSnapshot.Tile x : t.tiles)
		{
			if (x.label.text.equals(label))
			{
				return x;
			}
		}
		throw new AssertionError(label);
	}

	private static List<Component> all(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}
}

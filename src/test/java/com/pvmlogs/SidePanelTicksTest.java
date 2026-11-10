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
 * costs (stat boost, prayer, gear) read in damage, from T-117's figures: on the Execution card, and since T-123 (Eric,
 * 2026-10-31) in Where the time went too, with their ticks there on hover. Clocks stay clocks, and everything fits at 1×
 * and 2× in Normal, Smooth and Larger text.
 */
public class SidePanelTicksTest
{
	private static int checked;
	private static int clocks;

	@Test
	public void theCardIsSeconds()
	{
		// T-196: the card writes time in seconds, never ticks, with T-199's sign (T-202: no tiles any more).
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelExamples.dead());
		assertEquals("-14 s", t.card.luckTime);
		assertEquals("-17 s", t.card.lost);
	}

	@Test
	public void whereTheTimeWentIsTicksForIdleTimeAndSecondsForLuck()
	{
		final PanelSnapshot.TimeBudget b = SidePanelModel.fight(SidePanelExamples.dead()).budget;
		// Perfect is a clock; the idle parts are seconds lost (T-196); the setup parts damage (T-123); luck is signed
		// seconds (T-199's sign rule).
		assertTrue(b.rows.get(0).value.text, b.rows.get(0).value.text.matches("\\d+:\\d\\d"));
		for (PanelSnapshot.BudgetRow r : b.rows.subList(1, b.rows.size()))
		{
			final boolean luck = r.label.text.startsWith("luck");
			final boolean setup = SETUP.contains(r.label.text);
			assertTrue(r.label.text + " " + r.value.text, r.value.text.matches(luck
				? "([+-]\\d+(\\.\\d)? s|\u00b10 s|" + LedgerRows.UNKNOWN + ")" : setup ? "-\\d+ dmg" : "-\\d+(\\.\\d)? s"));
		}
		// "usual 1:54", "with average luck 2:02": clocks.
		for (PanelSnapshot.Row r : b.average)
		{
			assertTrue(r.left.text, r.left.text.matches("usual|with average luck"));
			assertTrue(r.right.text, r.right.text.matches("\\d+:\\d\\d"));
		}
	}

	@Test
	public void theExecutionCardsSetupRowsAreInDamageAndUptimeInTicks()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		// T-198/T-199: each bar its share, what it cost under it: idle seconds for Uptime, damage for the setup parts.
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			final String label = p.label.text;
			if (label.equals("Uptime"))
			{
				assertEquals(LedgerRows.partPercent(n.uptime), p.value.text);
				assertTrue(p.note.text, p.note.text.matches("-\\d+(\\.\\d)? s idle"));
			}
			else
			{
				// The same figures as the overlay's rows (T-117).
				final String part = label.equals("Offensive prayers") ? SidePanelModel.PRAYER : label.equals("Gear")
					? SidePanelModel.GEAR : SidePanelModel.BOOST;
				final double dmg = SidePanelModel.setupDamage(n, part);
				assertEquals(label, LedgerRows.partPercent(part.equals(SidePanelModel.PRAYER) ? n.prayerShare
					: part.equals(SidePanelModel.GEAR) ? n.gearShare : n.boostShare), p.value.text);
				assertEquals(label, dmg < 0.5 ? "" : "-" + LedgerRows.number(Math.round(dmg)) + " dmg", p.note.text);
			}
		}
		final List<String> rows = new ArrayList<>();
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			rows.add(p.label.text + " " + p.note.text);
		}
		assertTrue(rows.toString(), rows.stream().anyMatch(r -> r.startsWith("Boosts ") && r.endsWith(" dmg")));
	}

	private static final List<String> SETUP = Arrays.asList(SidePanelModel.BOOST, SidePanelModel.PRAYER,
		SidePanelModel.GEAR, "setup");

	@Test
	public void whereTheTimeWentsSetupPartsReadTheirDamageAndGiveTheirTicksOnHover()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		final PanelSnapshot.TimeBudget b = t.budget;
		boolean boost = false;
		for (PanelSnapshot.BudgetRow r : b.rows)
		{
			if (r.label.text.equals(SidePanelModel.BOOST))
			{
				boost = true;
				// T-123: "stat boost  -16 dmg", the Execution card's figure, and "-16" where it's narrow.
				final long dmg = LedgerRows.roundHalfAway(n.boostDamage);
				assertEquals("-" + dmg + " dmg", r.value.text);
				assertEquals(Arrays.asList("-" + dmg), r.valueShort);
				assertEquals("-" + dmg + " dmg", execution(t, "Boosts"));
				// "stat boost: 16 expected damage lost to a lower boost, about 4 ticks of the kill."
				assertEquals("stat boost: " + dmg + " expected damage lost to a lower boost, about "
					+ LedgerRows.ticksWord(LedgerRows.ticks(n.boostCost)) + " of the kill.", r.tooltip);
				// Coloured by its time, as before and as the Execution card's row.
				assertEquals(LedgerRows.isCosting(n.boostCost, n.clock) ? SidePanelStyle.COST : SidePanelStyle.BODY,
					r.value.colour);
			}
			if (r.label.text.equals("idle") || r.label.text.equals("eating") || r.label.text.startsWith("luck"))
			{
				assertEquals(r.label.text, null, r.tooltip);
			}
		}
		assertTrue(boost);
	}

	private static String execution(PanelSnapshot.FightTab t, String label)
	{
		for (PanelSnapshot.Part p : t.execution.parts)
		{
			if (p.label.text.equals(label))
			{
				return p.note.text;
			}
		}
		throw new AssertionError(label);
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
		assertTrue(drained.curve.tooltip, drained.curve.tooltip.contains("Your specials' share: 3 s slower."));
	}

	@Test
	public void aTickIsSaidOnceWhereTheFiguresAre()
	{
		assertEquals("Luck and comparisons with your average are in seconds; time spent not attacking is in ticks (1 tick is"
			+ " 0.6 s); setup costs are in damage.", SidePanelModel.TICK_NOTE);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		int noted = 0;
		for (Component c : all(p.fightTab()))
		{
			final String tip = c instanceof JComponent ? Tips.plain(((JComponent) c).getToolTipText()) : null;
			if (tip != null && tip.contains(SidePanelModel.TICK_NOTE))
			{
				noted++;
			}
		}
		// Where the time went's card; T-126: Skill's header too, after its sum. T-202: no Luck or Skill tiles any more.
		assertEquals(2, noted);
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
		// T-202: every view's sections fold under the card; open them so their texts are drawn.
		SidePanelPictures.openAll(p.fightTab());
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
				if (text != null && shown != null && (text.matches("usual|with average luck|perfect|idle|eating")
					|| text.equals(SidePanelModel.WORTH) || text.equals(SidePanelModel.SPEC_LUCK)
					|| text.equals("expected dmg added") || text.equals("your specials' luck") || SETUP.contains(text)))
				{
					// A clock or label beside a value in ticks stays whole: the value takes its short form first.
					final String at = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale + "×: ";
					assertFalse(at + text + " => " + shown, shown.contains(LedgerRows.CUT));
					clocks++;
				}
				if (text == null || shown == null || !(text.contains(" tick") || text.matches(".*\\d+t( .*)?")
					|| text.endsWith(" dmg)") || text.matches(".*-\\d+ dmg.*") || text.matches(".*\\d+ s (faster|slower).*")
					|| text.matches(".*[+-]\\d+(\\.\\d)? s\\b.*")))
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

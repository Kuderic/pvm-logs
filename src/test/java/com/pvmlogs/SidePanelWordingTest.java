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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-119 (Eric, 2026-10-30 09:11): Damage taken, Other damage and Defence drains reworded. Damage taken counts its hits
 * as a count, adds the average per hit, and its header is everything taken; the trip's other damage is dealt; and the
 * drains say what the drain specials did against their chance, not a time, with nothing cut off.
 */
public class SidePanelWordingTest
{
	private static List<String> rows(PanelSnapshot.Rows card)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : card.rows)
		{
			if (!r.heading)
			{
				out.add(r.toString());
			}
		}
		return out;
	}

	@Test
	public void damageTakenReadsAsACountAndItsHeaderIsEverythingTaken()
	{
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(SidePanelWordingExamples.ericsTrip());
		// 719 from hits and 868 of venom: 1,587 taken.
		assertEquals("1,587", trip.taken.hint.text);
		assertEquals(Arrays.asList("from hits | 719", "times hit | 249", "avg per hit | 2.9", "hits of 0 | 52",
			"largest hit taken | 44", "off-prayer damage | 112", "venom taken | 868"), rows(trip.taken));
		// The 0s and off prayer say what they are on hover; off prayer keeps its colour.
		for (PanelSnapshot.Row r : trip.taken.rows)
		{
			if (r.left.text.equals(SidePanelModel.HITS_OF_0))
			{
				assertEquals(SidePanelModel.HITS_OF_0_TIP, r.tooltip);
			}
			if (r.left.text.equals(SidePanelModel.OFF_PRAYER))
			{
				assertEquals(SidePanelModel.OFF_PRAYER_TIP, r.tooltip);
				assertEquals(SidePanelStyle.COST, r.right.colour);
			}
		}
	}

	@Test
	public void withNoDamageOverTimeTheHeaderIsTheHitsAndNoSplitRow()
	{
		final PanelSnapshot.Rows taken = SidePanelModel.fight(SidePanelExamples.sireEmberlight()).taken;
		assertEquals("27", taken.hint.text);
		assertFalse(rows(taken).toString(), rows(taken).stream().anyMatch(r -> r.startsWith(SidePanelModel.FROM_HITS)));
	}

	@Test
	public void liveTheHeaderIsTheHitsAndTheLineSaysWhatComes()
	{
		// Damage over time is the boss's doing, so after the fight only (T-063): live, the header is the hits.
		final SidePanelModel.Input in = SidePanelWordingExamples.ericsTrip();
		in.ended = false;
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(in);
		assertEquals("719", trip.taken.hint.text);
		assertEquals("times hit and largest hit after the fight", trip.taken.rows.get(trip.taken.rows.size() - 1).left.text);
	}

	@Test
	public void aKillWithNoDamageTakenHasNoCard()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.damageTaken = 0;
		s.dotTaken = 0;
		assertNull(SidePanelModel.fight(in).taken);
	}

	@Test
	public void otherDamageIsDealt()
	{
		assertEquals("Other damage dealt", SidePanelTripTab.OTHER_DAMAGE_DEALT);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), SidePanelModel.trip(
			SidePanelWordingExamples.ericsTrip())));
		assertEquals("Other damage dealt", SidePanelPictures.sectionOf(p.tripTab(), "tripOther").title());
	}

	@Test
	public void theDrainsSayWhatTheSpecialsDidAgainstTheirChance()
	{
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(SidePanelWordingExamples.ericsTrip());
		// A warhammer drains on landing: what landed, against its chance. A godsword drains by its damage.
		assertEquals(Arrays.asList("drains added | +630 dmg", "Dragon warhammer | landed 5 of 8 (expected 4.8)",
			"Bandos godsword | 152 dmg (expected 130)"), rows(trip.drains));
		// The times are on hover: the drains' worth, and the specials' luck on each special's row.
		assertTrue(trip.drains.rows.get(0).tooltip, trip.drains.rows.get(0).tooltip.contains("67 s faster"));
		assertTrue(trip.drains.rows.get(1).tooltip, trip.drains.rows.get(1).tooltip.contains("2 s faster"));
		for (PanelSnapshot.Row r : trip.drains.rows)
		{
			assertFalse(r.toString(), r.right.text.contains(" s ") || r.left.text.contains("luck"));
		}
	}

	@Test
	public void withoutThisSessionsTimelinesTheCountsAlone()
	{
		final SidePanelModel.Input in = SidePanelWordingExamples.ericsTrip();
		in.drain = SidePanelWordingExamples.drains(false);
		assertEquals(Arrays.asList("drains added | +630 dmg", "Dragon warhammer | landed 5 of 8",
			"Bandos godsword | landed 3 of 3"), rows(SidePanelModel.trip(in).drains));
	}

	@Test
	public void theTripSumsItsFightsDrainSpecials()
	{
		final DrainRecord a = new DrainRecord();
		a.specs.put("DRAGON_WARHAMMER", new int[]{2, 1});
		final DrainRecord b = new DrainRecord();
		b.specs.put("DRAGON_WARHAMMER", new int[]{3, 2});
		b.specs.put("BANDOS_GODSWORD", new int[]{1, 1});
		final DrainRecord immune = new DrainRecord();
		immune.immune = true;
		immune.specs.put("DRAGON_WARHAMMER", new int[]{9, 9});
		final List<Fight> fights = new ArrayList<>();
		for (DrainRecord r : Arrays.asList(a, b, immune))
		{
			final Fight f = SidePanelBossesExamples.fight("Araxxor", SidePanelBossesExamples.ARAXXOR, 70, 0L, 1, 10, true);
			f.recordDrain(r);
			fights.add(f);
		}
		final java.util.Map<String, int[]> sum = PvmPerformancePanel.tripSpecs(fights);
		assertEquals(Arrays.asList(5, 3), Arrays.asList(sum.get("DRAGON_WARHAMMER")[0], sum.get("DRAGON_WARHAMMER")[1]));
		assertEquals(1, sum.get("BANDOS_GODSWORD")[0]);
	}

	/** Damage taken, Other damage dealt and Defence drains, in every text setting at 1× and 2×: nothing cut. */
	@Test
	public void nothingIsCutOff()
	{
		final List<String> shortForms = new ArrayList<>();
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					final PvmPerformancePanel trip = new PvmPerformancePanel(null, null, null, null);
					trip.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), SidePanelModel.trip(
						SidePanelWordingExamples.ericsTrip())));
					trip.tabs().select(trip.tabs().getTab(2));
					fits(trip, trip.tripTab(), Arrays.asList("tripTaken", "tripOther", "tripDrains"), larger, smooth, scale,
						shortForms);
					final SidePanelModel.Input kill = SidePanelExamples.twoHammersOneLanded();
					kill.drain = SidePanelWordingExamples.drains(true);
					final PvmPerformancePanel fight = new PvmPerformancePanel(null, null, null, null);
					fight.apply(new PanelSnapshot(SidePanelModel.fight(kill)));
					fits(fight, fight.fightTab(), Arrays.asList("taken", "defence"), larger, smooth, scale, shortForms);
				}
			}
		}
		System.out.println("T-119 short forms: " + shortForms);
	}

	private static void fits(PvmPerformancePanel p, JComponent tab, List<String> ids, boolean larger, boolean smooth,
		double scale, List<String> shortForms)
	{
		p.setText(larger, smooth);
		try
		{
			SidePanelPictures.draw(p.tabs(), tab, scale);
			int sections = 0;
			for (String id : ids)
			{
				final SidePanelSection section = SidePanelPictures.sectionOf(tab, id);
				if (section == null)
				{
					continue;
				}
				sections++;
				for (Component c : all(section))
				{
					if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null)
					{
						final SidePanelText t = (SidePanelText) c;
						final String at = (larger ? "larger " : "") + (smooth ? "smooth " : "normal ") + scale + "× " + id
							+ ": ";
						assertFalse(at + t.getText() + " => " + t.lastShown(), t.lastShown().contains(LedgerRows.CUT));
						if (!t.lastShown().equals(t.getText()) && !t.getText().endsWith(t.lastShown()))
						{
							shortForms.add(at + t.getText() + " => " + t.lastShown());
						}
					}
				}
			}
			assertTrue(ids.toString(), sections >= 2);
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

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.junit.Test;

/**
 * T-072: every time of day in the side panel is 12-hour with am or pm, so it
 * can't be read as a kill time; durations are unchanged. Where a header no
 * longer fits, it leaves out its least needed part rather than cut the time.
 */
public class SidePanelTimesTest
{
	private static final ZoneId ZONE = SidePanelExamples.ZONE;

	private static String clock(int hour, int minute)
	{
		return SidePanelModel.clockTime(ZonedDateTime.of(2026, 10, 28, hour, minute, 0, 0, ZONE));
	}

	@Test
	public void twelveHourWithAmOrPm()
	{
		assertEquals("12:00 am", clock(0, 0));
		assertEquals("12:05 am", clock(0, 5));
		assertEquals("9:40 am", clock(9, 40));
		assertEquals("11:59 am", clock(11, 59));
		assertEquals("12:00 pm", clock(12, 0));
		assertEquals("12:05 pm", clock(12, 5));
		assertEquals("3:31 pm", clock(15, 31));
		assertEquals("11:59 pm", clock(23, 59));
	}

	@Test
	public void historyDatesAndKillRows()
	{
		final long now = SidePanelExamples.at(16, 0);
		final long today = SidePanelExamples.at(15, 31);
		final long older = SidePanelExamples.at(21, 40) - 24L * 3600 * 1000;
		assertEquals("today 3:31 pm", SidePanelModel.date(today, now, ZONE));
		// A kill row: today's is its time alone (the rows were full before am and pm); an older one keeps its day.
		assertEquals("3:31 pm", SidePanelModel.rowDate(today, now, ZONE));
		assertEquals("26 Oct 9:40 pm", SidePanelModel.date(older, now, ZONE));
		assertEquals("26 Oct 9:40 pm", SidePanelModel.rowDate(older, now, ZONE));
	}

	@Test
	public void englishWhateverTheSystemLocale()
	{
		final Locale was = Locale.getDefault();
		try
		{
			Locale.setDefault(Locale.GERMANY);
			final long now = SidePanelExamples.at(16, 0);
			assertEquals("26 Oct 9:40 pm", SidePanelModel.date(SidePanelExamples.at(21, 40) - 24L * 3600 * 1000, now, ZONE));
			assertEquals("3:31 pm", SidePanelModel.time(SidePanelExamples.at(15, 31), ZONE));
		}
		finally
		{
			Locale.setDefault(was);
		}
	}

	@Test
	public void theFightHeadersTimeAndItsShortForm()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.dead());
		assertEquals("700 HP · solo · 3:31 pm", tab.header.sub.text);
		assertEquals(java.util.Collections.singletonList("700 HP · 3:31 pm"), tab.header.subShort);
		// The kill's clock is a duration: unchanged.
		assertEquals("2:16", tab.header.clock.text);
		assertEquals("12:00 am", lastPart(SidePanelModel.fight(SidePanelExamples.deadAt(0, 0)).header.sub.text));
		assertEquals("12:00 pm", lastPart(SidePanelModel.fight(SidePanelExamples.deadAt(12, 0)).header.sub.text));
		assertEquals("12:05 am", lastPart(SidePanelModel.fight(SidePanelExamples.deadAt(0, 5)).header.sub.text));
		// While the fight runs there is no time, and nothing to shorten.
		assertEquals(java.util.Collections.emptyList(), SidePanelModel.fight(SidePanelExamples.live()).header.subShort);
	}

	@Test
	public void theTripHeaderAndItsShortForm()
	{
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(SidePanelExamples.trip());
		assertEquals("since 2:52 pm · fighting time", trip.header.sub.text);
		assertEquals(java.util.Arrays.asList("since 2:52 pm · in fights", "since 2:52 pm"), trip.header.subShort);
	}

	@Test
	public void aHeaderThatNoLongerFitsDrawsItsShortForm()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		try
		{
			panel.setText(true, false);
			SidePanelPictures.draw(panel.tabs(), panel.fightTab(), 1);
			// Larger text: "700 HP · solo · 3:31 pm" is wider than the header's room beside the clock.
			final SidePanelText sub = find(panel.fightTab(), "700 HP");
			assertEquals("700 HP · 3:31 pm", sub.lastShown());
		}
		finally
		{
			SidePanelStyle.setText(false, false);
		}
	}

	private static String lastPart(String sub)
	{
		final String[] parts = sub.split(" · ");
		return parts[parts.length - 1];
	}

	private static SidePanelText find(java.awt.Container c, String start)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelText && ((SidePanelText) k).getText().startsWith(start))
			{
				return (SidePanelText) k;
			}
			if (k instanceof java.awt.Container)
			{
				final SidePanelText t = find((java.awt.Container) k, start);
				if (t != null)
				{
					return t;
				}
			}
		}
		return null;
	}
}

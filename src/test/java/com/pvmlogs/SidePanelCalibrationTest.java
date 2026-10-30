package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * T-111: History's Tools has a Calibration report button (T-110's report): it builds the report and saves it on the
 * executor, never on Swing's thread or the client thread, says how many groups it flagged, and sends nothing.
 */
public class SidePanelCalibrationTest
{
	private static final String REPORT = "CALIBRATION over 762 saved fights, 180 counted kills: ...\n"
		+ "FLAGGED:\n"
		+ "  boss The Leviathan: z +0.71 +- 0.13 ...\n"
		+ "  weapon The Leviathan / Bow of Faerdhinen: z +0.9 ...\n"
		+ "  target Araxxor / Acidic Araxyte: z +1.4 ...\n"
		+ "BOSSES:\n"
		+ "  boss Vardorvis: z +0.1 ...\n";

	@Test
	public void theStatusCountsTheFlaggedGroups()
	{
		assertEquals(3, PvmPerformancePanel.calibrationFlags(REPORT));
		assertEquals(0, PvmPerformancePanel.calibrationFlags("CALIBRATION ...\nFLAGGED: none\nBOSSES:\n  boss X\n"));
		assertEquals("Saved calibration report (3 flags)", PvmPerformancePanel.calibrationStatus(3));
		assertEquals("Saved calibration report (1 flag)", PvmPerformancePanel.calibrationStatus(1));
		assertEquals("Saved calibration report (nothing flagged)", PvmPerformancePanel.calibrationStatus(0));
		assertEquals("calibration-20261030-011500.txt", PvmPerformancePanel.calibrationFile(
			LocalDateTime.of(2026, 10, 30, 1, 15, 0)));
	}

	@Test
	public void theCountMatchesTheRealReport()
	{
		// T-110's own report on the History fixtures, whatever it flags: the count is its FLAGGED lines.
		final String report = Calibration.report(SidePanelHistoryExamples.history(), null);
		int lines = 0;
		boolean in = false;
		for (String line : report.split("\n"))
		{
			if (line.startsWith("FLAGGED"))
			{
				in = line.equals("FLAGGED:");
			}
			else if (in && line.startsWith("  "))
			{
				lines++;
			}
			else
			{
				in = false;
			}
		}
		assertEquals(lines, PvmPerformancePanel.calibrationFlags(report));
	}

	@Test
	public void nothingIsBuiltOnTheCallersThread()
	{
		final List<Runnable> queued = new ArrayList<>();
		final Executor executor = queued::add;
		final List<String> statuses = new ArrayList<>();
		final boolean[] built = {false};
		PvmPerformancePanel.saveCalibration(executor, () ->
		{
			built[0] = true;
			return REPORT;
		}, () -> null, statuses::add);
		// Queued, not run: the report and the file wait for the executor.
		assertFalse(built[0]);
		assertEquals(1, queued.size());
		assertEquals(Collections.singletonList("Building the calibration report…"), statuses);
		queued.get(0).run();
		// No data folder: it says so, and builds nothing it couldn't save.
		assertEquals("Calibration report failed: no data folder.", statuses.get(1));
		assertFalse(built[0]);
	}

	@Test
	public void theButtonIsInToolsGreyedWithNothingSaved()
	{
		onEdt(() ->
		{
			final List<Integer> clicks = new ArrayList<>();
			final SidePanelFightTab.Actions actions = new SidePanelFightTab.Actions()
			{
				public void exportAll()
				{
				}

				public void newTrip()
				{
				}

				public void saveDebugLog()
				{
				}

				public void clearHistory()
				{
				}

				public void wholeRaid(boolean whole)
				{
				}

				public void showRaidRoom(int index)
				{
				}

				@Override
				public void calibrationReport()
				{
					clicks.add(1);
				}
			};
			final SidePanelHistoryTab tab = new SidePanelHistoryTab(SidePanelPictures.source(), actions,
				new SidePanelSection.MemoryFolds());
			tab.putAway();
			final javax.swing.JButton b = tab.calibrationButton();
			assertEquals(SidePanelHistoryTab.CALIBRATION, b.getText());
			assertEquals(SidePanelHistoryTab.CALIBRATION_TIP, b.getToolTipText());
			assertTrue(b.isEnabled());
			assertTrue(inTools(tab, b));
			final int builds = tab.builds();
			b.doClick();
			assertEquals(1, clicks.size());
			// Input only: the tab isn't rebuilt.
			assertEquals(builds, tab.builds());
			final SidePanelHistoryTab empty = new SidePanelHistoryTab(SidePanelPictures.source(Collections.emptyList()),
				actions, new SidePanelSection.MemoryFolds());
			assertFalse(empty.calibrationButton().isEnabled());
		});
	}

	private static boolean inTools(java.awt.Container c, java.awt.Component b)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection && "historyTools".equals(((SidePanelSection) k).id()))
			{
				return javax.swing.SwingUtilities.isDescendingFrom(b, k);
			}
			if (k instanceof java.awt.Container && inTools((java.awt.Container) k, b))
			{
				return true;
			}
		}
		return false;
	}

	private static void onEdt(Runnable r)
	{
		try
		{
			javax.swing.SwingUtilities.invokeAndWait(r);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			if (e.getCause() instanceof Error)
			{
				throw (Error) e.getCause();
			}
			throw new RuntimeException(e.getCause());
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
}

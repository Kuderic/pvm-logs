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

	/** Actions that count the Calibration report clicks. */
	private static SidePanelFightTab.Actions actions(List<Integer> clicks)
	{
		return new SidePanelFightTab.Actions()
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
	}

	@Test
	public void theButtonIsInToolsGreyedWithNothingSaved()
	{
		onEdt(() ->
		{
			final List<Integer> clicks = new ArrayList<>();
			final SidePanelFightTab.Actions actions = actions(clicks);
			final SidePanelHistoryTab tab = new SidePanelHistoryTab(SidePanelPictures.source(), actions,
				new SidePanelSection.MemoryFolds());
			tab.putAway();
			// T-114: shown only with Debug logging on.
			tab.setDebug(true);
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
			empty.setDebug(true);
			assertFalse(empty.calibrationButton().isEnabled());
		});
	}

	// ---- T-114 ----

	@Test
	public void theButtonShowsOnlyWithDebugLoggingOn()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = new SidePanelHistoryTab(SidePanelPictures.source(), actions(new ArrayList<>()),
				new SidePanelSection.MemoryFolds());
			assertFalse(tab.debug());
			assertFalse(inTools(tab, tab.calibrationButton()));
			// Turning it on is one refresh; the same value again is none.
			final int builds = tab.builds();
			tab.setDebug(true);
			assertEquals(builds + 1, tab.builds());
			assertTrue(inTools(tab, tab.calibrationButton()));
			tab.setDebug(true);
			assertEquals(builds + 1, tab.builds());
			tab.setDebug(false);
			assertFalse(inTools(tab, tab.calibrationButton()));
		});
	}

	@Test
	public void theFindingsShowInACardUntilClosed()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = new SidePanelHistoryTab(SidePanelPictures.source(), actions(new ArrayList<>()),
				new SidePanelSection.MemoryFolds());
			tab.setDebug(true);
			final int builds = tab.builds();
			final List<String> lines = java.util.Arrays.asList(FIRST, "Araxxor's Mirrorback araxytes: …", "Third.",
				"Fourth.", "Fifth.");
			tab.showCalibration("calibration-20261030-063906.txt", lines);
			// One structure change.
			assertEquals(builds + 1, tab.builds());
			assertEquals(lines, tab.findings());
			final List<String> shown = texts(tools(tab), SidePanelParts.Paragraph.class);
			assertEquals(java.util.Arrays.asList(FIRST, "Araxxor's Mirrorback araxytes: …", "Third.", "+2 more in the file"),
				shown);
			assertTrue(texts(tools(tab), SidePanelText.class).contains(SidePanelHistoryTab.FINDINGS_TITLE));
			// A refresh keeps it; the close control takes it away.
			tab.refresh();
			assertEquals(lines, tab.findings());
			for (java.awt.Component c : all(tab))
			{
				if (c instanceof SidePanelHistoryTab.Close && "Close the calibration findings".equals(
					((SidePanelHistoryTab.Close) c).getToolTipText()))
				{
					for (java.awt.event.MouseListener l : c.getMouseListeners())
					{
						l.mouseClicked(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_CLICKED, 0,
							java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
					}
				}
			}
			assertEquals(null, tab.findings());
			assertTrue(texts(tools(tab), SidePanelParts.Paragraph.class).isEmpty());
		});
	}

	@Test
	public void nothingFlaggedIsOneLineWithNoMore()
	{
		final List<String> one = Collections.singletonList("Nothing looks off: over 12 kills on current builds, your damage"
			+ " matches what the plugin expected.");
		assertEquals(one, SidePanelHistoryTab.shownFindings(one));
		assertEquals(4, SidePanelHistoryTab.shownFindings(java.util.Arrays.asList("a", "b", "c", "d")).size());
		assertEquals("+1 more in the file", SidePanelHistoryTab.shownFindings(java.util.Arrays.asList("a", "b", "c", "d"))
			.get(3));
	}

	@Test
	public void theStatusNamesTheFile()
	{
		assertEquals("Saved calibration-20261030-063906.txt in the plugin folder",
			SidePanelHistoryTab.calibrationSaved("calibration-20261030-063906.txt"));
	}

	@Test
	public void theSentencesAreReadOnTheExecutorAndShownAfter()
	{
		final List<Runnable> queued = new ArrayList<>();
		final List<String> statuses = new ArrayList<>();
		final boolean[] read = {false};
		final List<Object> saved = new ArrayList<>();
		PvmPerformancePanel.saveCalibration(queued::add, () -> REPORT, () ->
		{
			read[0] = true;
			return Collections.singletonList(FIRST);
		}, () -> null, statuses::add, (file, lines) -> saved.add(lines));
		assertFalse(read[0]);
		assertEquals(1, queued.size());
		queued.get(0).run();
		// No data folder: nothing saved, nothing shown.
		assertFalse(read[0]);
		assertTrue(saved.isEmpty());
		assertEquals("Calibration report failed: no data folder.", statuses.get(1));
	}

	private static final String FIRST = "The Leviathan: you dealt 8% more than the plugin expected over 54 kills. More than"
		+ " luck explains, so likely a scoring error. (build abc1234)";

	private static java.awt.Container tools(SidePanelHistoryTab tab)
	{
		return SidePanelPictures.sectionOf(tab, "historyTools");
	}

	private static List<java.awt.Component> all(java.awt.Container c)
	{
		final List<java.awt.Component> out = new ArrayList<>();
		for (java.awt.Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof java.awt.Container)
			{
				out.addAll(all((java.awt.Container) k));
			}
		}
		return out;
	}

	private static List<String> texts(java.awt.Container c, Class<?> kind)
	{
		final List<String> out = new ArrayList<>();
		for (java.awt.Component k : all(c))
		{
			if (kind.isInstance(k))
			{
				out.add(k instanceof SidePanelParts.Paragraph ? ((SidePanelParts.Paragraph) k).text()
					: ((SidePanelText) k).getText());
			}
		}
		return out;
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

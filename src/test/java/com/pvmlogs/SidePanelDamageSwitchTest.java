package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * T-105: the Damage card's Boss · Adds · All switch is its header's hint, {@code boss · adds · all} (Eric, 2026-10-29
 * 23:10: "Sidebar design for bosses adds and all is ugly"; his pick, A, at 23:14).
 */
public class SidePanelDamageSwitchTest
{
	@After
	public void reset()
	{
		SidePanelFightTab.resetTarget();
		SidePanelStyle.setText(false, false);
	}

	@Test
	public void theHeaderReadsBossAddsAllWithThePickBright()
	{
		final SidePanelFightTab view = tab(SidePanelTargetExamples.araxxor());
		final SidePanelFightTab.TargetSwitch s = only(view);
		assertEquals("[boss, adds, all]", texts(s.words()).toString());
		assertEquals(SidePanelStyle.VALUE, s.words().get(0).colour());
		assertEquals(SidePanelStyle.MUTED, s.words().get(1).colour());
		assertEquals(SidePanelStyle.MUTED, s.words().get(2).colour());
		// In the header, beside its title: the switch is the section's hint.
		final SidePanelSection damage = section(view, "damage");
		assertTrue(all(damage.getComponent(0), SidePanelFightTab.TargetSwitch.class).contains(s));
	}

	@Test
	public void theGreyRowAndTheNoteAreGoneAndTheirFactsAreInTheTooltip()
	{
		final SidePanelFightTab view = tab(SidePanelTargetExamples.araxxor());
		final SidePanelSection damage = section(view, "damage");
		assertTrue(all(damage, SidePanelFightTab.Segment.class).isEmpty());
		for (SidePanelParts.Paragraph p : all(damage, SidePanelParts.Paragraph.class))
		{
			fail("a paragraph in the Damage card: " + p);
		}
		final String tip = only(view).getToolTipText();
		assertEquals("1,504 on the boss over 2:40. Luck and Execution cover the whole kill.", tip);
		for (SidePanelText w : only(view).words())
		{
			assertEquals(tip, w.getToolTipText());
		}
	}

	@Test
	public void aClickPicksThatViewOnceAndThePickedWordDoesNothing()
	{
		final SidePanelFightTab view = tab(SidePanelTargetExamples.araxxor());
		final int built = view.builds();
		click(only(view).words().get(0));
		assertEquals(built, view.builds());
		click(only(view).words().get(1));
		assertEquals(built + 1, view.builds());
		assertEquals(1, SidePanelFightTab.target());
		final SidePanelFightTab.TargetSwitch s = only(view);
		assertEquals(SidePanelStyle.VALUE, s.words().get(1).colour());
		assertEquals(SidePanelStyle.MUTED, s.words().get(0).colour());
		assertTrue(s.getToolTipText(), s.getToolTipText().startsWith("384 on the adds over "));
	}

	@Test
	public void aHoverOnlyRecoloursAWord()
	{
		final SidePanelFightTab view = tab(SidePanelTargetExamples.araxxor());
		final SidePanelFightTab.TargetSwitch s = only(view);
		final int built = view.builds();
		final int width = s.getPreferredSize().width;
		enter(s.words().get(2));
		assertEquals(SidePanelStyle.SECONDARY, s.words().get(2).colour());
		assertEquals(java.awt.Cursor.HAND_CURSOR, s.words().get(2).getCursor().getType());
		assertEquals(java.awt.Cursor.DEFAULT_CURSOR, s.words().get(0).getCursor().getType());
		// No rebuild and no new width, so no layout.
		assertEquals(built, view.builds());
		assertEquals(width, s.getPreferredSize().width);
		exit(s.words().get(2));
		assertEquals(SidePanelStyle.MUTED, s.words().get(2).colour());
		// Hovering the picked word changes nothing.
		enter(s.words().get(0));
		assertEquals(SidePanelStyle.VALUE, s.words().get(0).colour());
	}

	@Test
	public void noSwitchKeepsTodaysHint()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelFightTab view = panel.fightTab();
		view.apply(SidePanelModel.fight(SidePanelTargetExamples.ungrouped()));
		assertTrue(all(view, SidePanelFightTab.TargetSwitch.class).isEmpty());
		final List<String> header = texts(all(section(view, "damage").getComponent(0), SidePanelText.class));
		assertEquals("[Damage, 2,145 yours]", header.toString());
	}

	@Test
	public void aSideWithNothingScoredSaysSoInTheCard()
	{
		SidePanelFightTab.useTarget(1);
		final SidePanelFightTab view = tab(SidePanelTargetExamples.zulrah());
		final List<String> lines = new ArrayList<>();
		for (SidePanelParts.Paragraph p : all(section(view, "damage"), SidePanelParts.Paragraph.class))
		{
			lines.add(p.text());
		}
		assertEquals("[Nothing scored on the adds: their time counts in the kill's.]", lines.toString());
		assertTrue(only(view).getToolTipText(), only(view).getToolTipText().startsWith("0:03 on the adds, not scored."));
	}

	@Test
	public void itFitsOnOneLineAtEverySize()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				SidePanelStyle.setText(larger, smooth);
				final SidePanelFightTab view = tab(SidePanelTargetExamples.araxxor());
				final SidePanelSection damage = section(view, "damage");
				final SidePanelText title = all(damage.getComponent(0), SidePanelText.class).get(0);
				final int need = title.getPreferredSize().width + 6 + only(view).getPreferredSize().width;
				final String at = "larger " + larger + " smooth " + smooth + ": " + need;
				assertTrue(at, need <= SidePanelStyle.CONTENT_WIDTH);
				// Laid out at the panel's width: the title whole, the switch at the right edge, one line.
				final java.awt.Component header = damage.getComponent(0);
				header.setSize(SidePanelStyle.CONTENT_WIDTH, header.getPreferredSize().height);
				SidePanelPictures.layout(header);
				assertTrue(at, title.getWidth() >= title.getPreferredSize().width);
				final java.awt.Rectangle s = javax.swing.SwingUtilities.convertRectangle(only(view).getParent(),
					only(view).getBounds(), header);
				assertEquals(at, SidePanelStyle.CONTENT_WIDTH, s.x + s.width);
				assertTrue(at, header.getHeight() <= title.getPreferredSize().height + 8);
			}
		}
	}

	@Test
	public void foldedTheMarkAloneShows()
	{
		final SidePanelFightTab view = tab(SidePanelTargetExamples.araxxor());
		final SidePanelSection damage = section(view, "damage");
		damage.toggle();
		assertFalse(only(view).isVisible());
		assertTrue(texts(all(damage.getComponent(0), SidePanelText.class)).contains(SidePanelSection.FOLDED_MARK));
		damage.toggle();
		assertTrue(only(view).isVisible());
	}

	private static SidePanelFightTab tab(SidePanelTargetExamples.Room r)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelFightTab view = panel.fightTab();
		view.apply(SidePanelModel.fight(SidePanelTargetExamples.input(r)));
		return view;
	}

	private static SidePanelFightTab.TargetSwitch only(Container c)
	{
		final List<SidePanelFightTab.TargetSwitch> s = all(c, SidePanelFightTab.TargetSwitch.class);
		assertEquals(1, s.size());
		assertNotNull(s.get(0));
		return s.get(0);
	}

	private static SidePanelSection section(Container c, String id)
	{
		for (SidePanelSection s : all(c, SidePanelSection.class))
		{
			if (s.id().equals(id))
			{
				return s;
			}
		}
		throw new AssertionError("no section " + id);
	}

	private static List<String> texts(List<SidePanelText> texts)
	{
		final List<String> out = new ArrayList<>();
		for (SidePanelText t : texts)
		{
			if (!t.getText().isEmpty())
			{
				out.add(t.getText());
			}
		}
		return out;
	}

	private static void click(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseClicked(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false));
		}
	}

	private static void enter(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseEntered(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0, false));
		}
	}

	private static void exit(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseExited(new MouseEvent(c, MouseEvent.MOUSE_EXITED, 0L, 0, 2, 2, 0, false));
		}
	}

	private static void fail(String message)
	{
		throw new AssertionError(message);
	}

	private static <T> List<T> all(Component c, Class<T> type)
	{
		final List<T> out = new ArrayList<>();
		if (!(c instanceof Container))
		{
			return out;
		}
		for (Component k : ((Container) c).getComponents())
		{
			if (type.isInstance(k))
			{
				out.add(type.cast(k));
			}
			out.addAll(all(k, type));
		}
		return out;
	}
}

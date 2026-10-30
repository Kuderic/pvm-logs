package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JTextField;
import org.junit.Test;

/**
 * T-082: History's search shows only the matching bosses, clearing it brings back what it hid, picking a match clears
 * it; a picked boss can be put away by its row or the card's close control; Escape clears the search.
 */
public class SidePanelHistorySearchTest
{
	/** IconTextField.setText asserts it's on Swing's thread, as the panel always is. */
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

	private static SidePanelHistoryTab tab()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		return tab;
	}

	private static List<String> sections(Container c)
	{
		final List<String> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				out.add(((SidePanelSection) k).id());
			}
			if (k instanceof Container)
			{
				out.addAll(sections((Container) k));
			}
		}
		return out;
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

	/** The boss list's row for {@code name}: the component whose tooltip names it. */
	private static JComponent row(Container c, String name)
	{
		for (JComponent k : all(c, JComponent.class))
		{
			final String tip = k.getToolTipText();
			if (tip != null && (tip.startsWith("Show " + name + ".") || tip.startsWith("Put " + name + " away.")))
			{
				return k;
			}
		}
		return null;
	}

	private static void click(Component c)
	{
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
	}

	private static List<String> bossNames(SidePanelHistoryTab tab)
	{
		final List<String> out = new ArrayList<>();
		for (String name : new String[]{"Vardorvis", "Abyssal Sire", "Abyssal demon"})
		{
			if (row(tab, name) != null)
			{
				out.add(name);
			}
		}
		return out;
	}

	@Test
	public void itOpensOnTheBossOfTheLastKill()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			assertEquals("Vardorvis", tab.boss());
			assertEquals(1, all(tab, SidePanelHistoryTab.Close.class).size());
			assertTrue(sections(tab).toString(), sections(tab).containsAll(java.util.Arrays.asList("historyKills",
				"historyBosses", "historyTools")));
		});
	}

	@Test
	public void typingShowsOnlyTheMatchesAndClearingBringsBackTheCard()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			tab.search().setText("abyss");
			assertTrue(tab.searching());
			// No card, no kills, no tools: the matches alone, in Bosses (the Sire) and Monsters (the demons, T-102).
			assertEquals(java.util.Arrays.asList("historyBosses", "historyMonsters"), sections(tab));
			assertTrue(all(tab, SidePanelHistoryTab.Close.class).isEmpty());
			assertEquals(java.util.Arrays.asList("Abyssal Sire", "Abyssal demon"), bossNames(tab));
			// The pick is unchanged, and comes back with the cleared search.
			assertEquals("Vardorvis", tab.boss());
			tab.search().setText("");
			assertEquals(1, all(tab, SidePanelHistoryTab.Close.class).size());
			assertTrue(sections(tab).contains("historyKills"));
			assertTrue(sections(tab).contains("historyTools"));
		});
	}

	@Test
	public void pickingAMatchPicksItAndClearsTheSearch()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			tab.search().setText("sire");
			click(row(tab, "Abyssal Sire"));
			assertEquals("Abyssal Sire", tab.boss());
			assertEquals("", tab.search().getText());
			assertTrue(sections(tab).contains("historyKills"));
		});
	}

	@Test
	public void thePickedBossesRowAgainPutsItAway()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			click(row(tab, "Vardorvis"));
			assertNull(tab.boss());
			assertTrue(all(tab, SidePanelHistoryTab.Close.class).isEmpty());
			assertFalse(sections(tab).contains("historyKills"));
			// Put away: the list and the tools.
			assertEquals(java.util.Arrays.asList("historyBosses", "historyMonsters", "historyTools"), sections(tab));
			// Its row again picks it.
			click(row(tab, "Vardorvis"));
			assertEquals("Vardorvis", tab.boss());
		});
	}

	@Test
	public void theCardsCloseControlPutsItAway()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			final SidePanelHistoryTab.Close close = all(tab, SidePanelHistoryTab.Close.class).get(0);
			assertEquals("Put Vardorvis away and show all bosses", close.getToolTipText());
			close.dispatchEvent(new MouseEvent(close, MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0, false));
			assertTrue(close.hovered());
			click(close);
			assertNull(tab.boss());
			assertEquals(java.util.Arrays.asList("historyBosses", "historyMonsters", "historyTools"), sections(tab));
		});
	}

	@Test
	public void putAwayStaysPutAcrossARefresh()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			tab.putAway();
			// A fight saved, the tab opened again: still none picked.
			tab.refresh();
			assertNull(tab.boss());
			// Searching and clearing doesn't pick one either.
			tab.search().setText("var");
			tab.search().setText("");
			assertNull(tab.boss());
			// Picking one ends it.
			tab.pick("Abyssal Sire");
			assertEquals("Abyssal Sire", tab.boss());
		});
	}

	@Test
	public void escapeClearsTheSearch()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			tab.search().setText("sire");
			final JTextField field = all(tab.search(), JTextField.class).get(0);
			assertNotNull(field);
			// Straight to the field's listeners: a dispatched key event goes to the focus owner, and nothing has focus here.
			final KeyEvent escape = new KeyEvent(field, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ESCAPE,
				KeyEvent.CHAR_UNDEFINED);
			for (java.awt.event.KeyListener l : field.getKeyListeners())
			{
				l.keyPressed(escape);
			}
			assertEquals("", tab.search().getText());
			assertTrue(sections(tab).contains("historyKills"));
		});
	}

	@Test
	public void noMatchSaysSo()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			tab.search().setText("zulrah");
			final List<String> texts = new ArrayList<>();
			for (SidePanelText t : all(tab, SidePanelText.class))
			{
				texts.add(t.getText());
			}
			// T-102: neither section has a match, so one line says so.
			assertTrue(texts.toString(), texts.contains(SidePanelHistoryTab.NO_MATCH));
			assertTrue(sections(tab).isEmpty());
		});
	}

	@Test
	public void typingIsAStructureChangeOnInputOnly()
	{
		onEdt(() ->
		{
			final SidePanelHistoryTab tab = tab();
			final int builds = tab.builds();
			tab.search().setText("a");
			assertEquals(builds + 1, tab.builds());
			tab.search().setText("");
			assertEquals(builds + 2, tab.builds());
		});
	}
}

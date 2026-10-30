package com.pvmlogs;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-112 item 5: Details and Damage taken in groups under subheadings. Each of the three layouts keeps every fact; an empty
 * group goes, a group of one row is that row with no subheading, and one group alone needs no subheading.
 */
public class SidePanelGroupingTest
{
	@After
	public void reset()
	{
		SidePanelModel.grouping = SidePanelModel.Grouping.HEADINGS;
	}

	private static PanelSnapshot.Row row(String left, String right)
	{
		return new PanelSnapshot.Row(PanelSnapshot.Text.small(left, SidePanelStyle.BODY), PanelSnapshot.Text.NONE,
			PanelSnapshot.Text.small(right, SidePanelStyle.BODY));
	}

	private static PanelSnapshot.Row row(String left, String right, String tooltip)
	{
		return new PanelSnapshot.Row(PanelSnapshot.Text.small(left, SidePanelStyle.BODY), PanelSnapshot.Text.NONE,
			PanelSnapshot.Text.small(right, SidePanelStyle.BODY), tooltip);
	}

	private static final String[] TITLES = {"Your damage", "The boss", "Time"};

	private static List<PanelSnapshot.Row> three()
	{
		return SidePanelModel.grouped(TITLES,
			Arrays.asList(row("thrall dmg", "52"), row("recoil dmg", "12")),
			Arrays.asList(row("boss healed", "40"), row("room dmg", "310", "Damage the boss took from the room.")),
			Collections.singletonList(row("eating", "4.2s")));
	}

	private static List<String> strings(List<PanelSnapshot.Row> rows)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Row r : rows)
		{
			out.add((r.heading ? "# " : r.indent ? "  " : "") + r.left.text + (r.right.text.isEmpty() ? "" : " | " +
				r.right.text));
		}
		return out;
	}

	@Test
	public void headingsSetTheRowsInUnderTheirGroupAndAGroupOfOneStandsAlone()
	{
		assertEquals(Arrays.asList("# Your damage", "  thrall dmg | 52", "  recoil dmg | 12", "# The boss",
			"  boss healed | 40", "  room dmg | 310", "eating | 4.2s"), strings(three()));
		final PanelSnapshot.Row heading = three().get(0);
		assertEquals(SidePanelStyle.MUTED, heading.left.colour);
		assertTrue(heading.right.isEmpty());
	}

	@Test
	public void cardsMarkTheSameGroups()
	{
		SidePanelModel.grouping = SidePanelModel.Grouping.CARDS;
		assertEquals(Arrays.asList("# Your damage", "  thrall dmg | 52", "  recoil dmg | 12", "# The boss",
			"  boss healed | 40", "  room dmg | 310", "eating | 4.2s"), strings(three()));
	}

	@Test
	public void flushHasTheSameSubheadingsAndTheRowsArentSetIn()
	{
		SidePanelModel.grouping = SidePanelModel.Grouping.FLUSH;
		final List<PanelSnapshot.Row> rows = three();
		assertEquals(Arrays.asList("# Your damage", "  thrall dmg | 52", "  recoil dmg | 12", "# The boss",
			"  boss healed | 40", "  room dmg | 310", "eating | 4.2s"), strings(rows));
		assertEquals("Damage the boss took from the room.", rows.get(5).tooltip);
		final SidePanelParts.RowsCard card = (SidePanelParts.RowsCard) SidePanelParts.rowsView(rows, 5);
		final EmptyBorder heading = (EmptyBorder) ((JComponent) card.getComponent(3)).getBorder();
		assertEquals(SidePanelParts.GROUP_GAP, heading.getBorderInsets().top);
		assertEquals(0, heading.getBorderInsets().left);
		assertNull(((JComponent) card.getComponent(1)).getBorder());
	}

	@Test
	public void emptyGroupsGoAndOneGroupAloneHasNoSubheading()
	{
		for (SidePanelModel.Grouping g : SidePanelModel.Grouping.values())
		{
			SidePanelModel.grouping = g;
			final List<PanelSnapshot.Row> rows = SidePanelModel.grouped(TITLES,
				Arrays.asList(row("thrall dmg", "52"), row("recoil dmg", "12")), Collections.emptyList(),
				Collections.emptyList());
			assertEquals(g.name(), Arrays.asList("thrall dmg | 52", "recoil dmg | 12"), strings(rows));
			assertTrue(SidePanelModel.grouped(TITLES, Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList()).isEmpty());
		}
	}

	/** Every fact from the flat rows, in each layout: the rows' values, in the rows or in a compact row's text. */
	@Test
	public void nothingIsLostOnARealKill()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		SidePanelModel.grouping = SidePanelModel.Grouping.HEADINGS;
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		final List<PanelSnapshot.Row> facts = new ArrayList<>();
		for (PanelSnapshot.Rows card : Arrays.asList(tab.details, tab.taken))
		{
			assertNotNull(card);
			for (PanelSnapshot.Row r : card.rows)
			{
				if (!r.heading)
				{
					facts.add(r);
				}
			}
		}
		// Damage taken has its groups: by attack and the total.
		assertTrue(strings(tab.taken.rows).toString(), strings(tab.taken.rows).contains("# " + SidePanelModel.BY_ATTACK));
		assertTrue(strings(tab.taken.rows).toString(), strings(tab.taken.rows).contains("# " + SidePanelModel.TOTAL));
		for (SidePanelModel.Grouping g : SidePanelModel.Grouping.values())
		{
			SidePanelModel.grouping = g;
			final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
			final StringBuilder all = new StringBuilder();
			for (PanelSnapshot.Rows card : Arrays.asList(t.details, t.taken))
			{
				for (PanelSnapshot.Row r : card.rows)
				{
					all.append(r.left.text).append(' ').append(r.hint.text).append(' ').append(r.right.text).append('\n');
				}
			}
			for (PanelSnapshot.Row f : facts)
			{
				assertTrue(g + ": " + f + " in\n" + all, all.toString().contains(f.right.text));
			}
		}
	}

	@Test
	public void aRowUnderASubheadingIsSetInAndUpdatesDontSetItsBorderAgain()
	{
		final List<PanelSnapshot.Row> rows = three();
		final SidePanelParts.RowsCard card = (SidePanelParts.RowsCard) SidePanelParts.rowsView(rows, 5);
		final JComponent heading = (JComponent) card.getComponent(0);
		final JComponent setIn = (JComponent) card.getComponent(1);
		final JComponent alone = (JComponent) card.getComponent(6);
		// The first subheading has no gap above it; the next one, and the row alone after a group, do.
		assertNull(heading.getBorder());
		assertEquals(SidePanelParts.GROUP_GAP, ((EmptyBorder) ((JComponent) card.getComponent(3)).getBorder())
			.getBorderInsets().top);
		assertEquals(SidePanelParts.GROUP_GAP, ((EmptyBorder) alone.getBorder()).getBorderInsets().top);
		assertEquals(0, ((EmptyBorder) alone.getBorder()).getBorderInsets().left);
		final Border border = setIn.getBorder();
		assertTrue(border instanceof EmptyBorder);
		assertEquals(SidePanelParts.INDENT, ((EmptyBorder) border).getBorderInsets().left);
		assertEquals(0, ((EmptyBorder) border).getBorderInsets().top);
		card.set(three());
		assertSame(border, setIn.getBorder());
	}

	@Test
	public void cardsAreACardAGroupUnderItsSubheading()
	{
		SidePanelModel.grouping = SidePanelModel.Grouping.CARDS;
		final JComponent view = SidePanelParts.rowsView(three(), 5);
		assertTrue(view instanceof SidePanelParts.GroupCards);
		final List<String> parts = new ArrayList<>();
		for (Component c : view.getComponents())
		{
			parts.add(c instanceof SidePanelParts.RowsCard ? "card of " + ((SidePanelParts.RowsCard) c).getComponentCount()
				: "title");
		}
		// Your damage, The boss, then eating on its own with no title.
		assertEquals(Arrays.asList("title", "card of 2", "title", "card of 2", "card of 1"), parts);
		// In a group's own card its rows aren't set in.
		final SidePanelParts.RowsCard first = (SidePanelParts.RowsCard) view.getComponent(1);
		assertNull(((JComponent) first.getComponent(0)).getBorder());
		((SidePanelParts.RowsView) view).set(three());
	}

	@Test
	public void cardsWithoutGroupsAreOneCard()
	{
		SidePanelModel.grouping = SidePanelModel.Grouping.CARDS;
		assertTrue(SidePanelParts.rowsView(Arrays.asList(row("hits", "21"), row("biggest hit", "31")), 5)
			instanceof SidePanelParts.RowsCard);
		assertFalse(SidePanelParts.rowsView(three(), 5) instanceof SidePanelParts.RowsCard);
	}
}

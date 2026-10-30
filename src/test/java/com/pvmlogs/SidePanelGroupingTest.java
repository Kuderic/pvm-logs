package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-112 item 5: Details and Damage taken in groups under subheadings, Eric's pick (07:45): layout A, rows set in under a
 * muted subheading. An empty group goes, a group of one row is that row with no subheading, one group alone needs no
 * subheading, and nothing is lost but Details' eating and mechanics, which Where the time went already has.
 */
public class SidePanelGroupingTest
{
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

	private static final String[] TITLES = {"By attack", "Total", "Over time"};

	/** Two groups of two, then a group of one. */
	private static List<PanelSnapshot.Row> three()
	{
		return SidePanelModel.grouped(TITLES,
			Arrays.asList(row("melee", "74"), row("avoidable", "69 (50%)")),
			Arrays.asList(row("hits", "33"), row("biggest hit", "40", "The biggest hit taken.")),
			Collections.singletonList(row("venom taken", "46")));
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
	public void rowsAreSetInUnderTheirGroupAndAGroupOfOneStandsAlone()
	{
		final List<PanelSnapshot.Row> rows = three();
		assertEquals(Arrays.asList("# By attack", "  melee | 74", "  avoidable | 69 (50%)", "# Total", "  hits | 33",
			"  biggest hit | 40", "venom taken | 46"), strings(rows));
		assertEquals(SidePanelStyle.MUTED, rows.get(0).left.colour);
		assertTrue(rows.get(0).right.isEmpty());
		assertEquals("The biggest hit taken.", rows.get(5).tooltip);
	}

	@Test
	public void emptyGroupsGoAndOneGroupAloneHasNoSubheading()
	{
		final List<PanelSnapshot.Row> rows = SidePanelModel.grouped(TITLES,
			Arrays.asList(row("melee", "74"), row("avoidable", "69 (50%)")), Collections.emptyList(),
			Collections.emptyList());
		assertEquals(Arrays.asList("melee | 74", "avoidable | 69 (50%)"), strings(rows));
		assertTrue(SidePanelModel.grouped(TITLES, Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList()).isEmpty());
	}

	/** Every fact from before, but eating and mechanics, which moved to Where the time went (Eric's pick). */
	@Test
	public void nothingIsLostButEatingAndMechanicsWhichTheTimeBudgetHas()
	{
		final SidePanelModel.Input in = SidePanelExamples.ruledKill(true);
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		final StringBuilder all = new StringBuilder();
		for (PanelSnapshot.Rows card : Arrays.asList(tab.details, tab.taken))
		{
			assertNotNull(card);
			for (PanelSnapshot.Row r : card.rows)
			{
				all.append(r.left.text).append(' ').append(r.hint.text).append(' ').append(r.right.text).append('\n');
				assertFalse(r.toString(), r.left.text.equals("eating") || r.left.text.equals("mechanics"));
			}
		}
		// The facts the cards had before T-112 (this kill's hits/expected, thrall, damage taken).
		for (String fact : new String[]{"hits/expected", "thrall dmg", "melee", "spike", "avoidable", "times hit",
			"largest hit taken", "off-prayer damage"})
		{
			assertTrue(fact + " in\n" + all, all.toString().contains(fact + " "));
		}
		// Damage taken has its groups: by attack and the total.
		assertTrue(strings(tab.taken.rows).toString(), strings(tab.taken.rows).contains("# " + SidePanelModel.BY_ATTACK));
		assertTrue(strings(tab.taken.rows).toString(), strings(tab.taken.rows).contains("# " + SidePanelModel.TOTAL));
		// Where the time went still has the eating.
		boolean eating = false;
		for (PanelSnapshot.BudgetRow r : tab.budget.rows)
		{
			eating |= r.label.text.startsWith("eating");
		}
		assertTrue(eating);
	}

	@Test
	public void aRowUnderASubheadingIsSetInAndUpdatesDontSetItsBorderAgain()
	{
		final SidePanelParts.RowsCard card = new SidePanelParts.RowsCard(three(), 5);
		final JComponent heading = (JComponent) card.getComponent(0);
		final JComponent setIn = (JComponent) card.getComponent(1);
		final JComponent alone = (JComponent) card.getComponent(6);
		// The first subheading has no gap above it; the next one, and the row alone after a group, do.
		assertNull(heading.getBorder());
		final EmptyBorder second = (EmptyBorder) ((JComponent) card.getComponent(3)).getBorder();
		assertEquals(SidePanelParts.GROUP_GAP, second.getBorderInsets().top);
		assertEquals(0, second.getBorderInsets().left);
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
	public void rowsWithoutGroupsHaveNoBorder()
	{
		final SidePanelParts.RowsCard card = new SidePanelParts.RowsCard(Arrays.asList(row("hits", "21"),
			row("biggest hit", "31")), 5);
		assertNull(((JComponent) card.getComponent(0)).getBorder());
		assertNull(((JComponent) card.getComponent(1)).getBorder());
	}
}

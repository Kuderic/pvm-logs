package com.pvmlogs;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T-129 (Eric, 2026-10-31 10:35: "says not in a runelite party until i start a boss"): the Party tab's shape key holds
 * whether the player is in a party, which, and its members, so joining, leaving or a member coming or going rebuilds it
 * on the next tick, in or out of a fight; and a party where nobody has shared yet says so plainly.
 */
public class SidePanelPartyJoiningTest
{
	private static List<Object> state(boolean in, long party, Long... members)
	{
		return PvmPerformancePanel.partyState(in, party, Arrays.asList(members));
	}

	/** The Party tab rebuilds on the next tick when its shape differs; out of a fight, even a same-shape tick waits. */
	private static boolean rebuilds(List<Object> before, List<Object> after)
	{
		return PvmPerformancePanel.due(false, false, before.equals(after), true, 1) && !before.equals(after);
	}

	@Test
	public void joiningLeavingOrAMemberComingOrGoingChangesTheKey()
	{
		final List<Object> out = state(false, 0);
		final List<Object> joined = state(true, 42, 1L);
		final List<Object> second = state(true, 42, 1L, 2L);
		// Join a party with an empty board: a new key, so the next tick rebuilds the tab.
		assertTrue(rebuilds(out, joined));
		// A member joins, then leaves.
		assertTrue(rebuilds(joined, second));
		assertTrue(rebuilds(second, joined));
		// Leave: back to out of a party.
		assertTrue(rebuilds(joined, out));
		// Another party of the same members is another key.
		assertNotEquals(state(true, 42, 1L), state(true, 43, 1L));
		// Nothing changed: the same key, no rebuild on that account.
		assertEquals(state(true, 42, 1L, 2L), state(true, 42, 1L, 2L));
		assertFalse(rebuilds(second, state(true, 42, 1L, 2L)));
	}

	@Test
	public void inAPartyBeforeAnyoneSharesTheTabSaysSoPlainly()
	{
		final SidePanelParty.Input in = new SidePanelParty.Input();
		in.inParty = true;
		in.partySize = 3;
		in.shared = 0;
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		assertFalse(t.sharing);
		assertEquals("In a party of 3. Members running Luck vs Skill appear here once their fight starts.", t.empty);
		in.partySize = 1;
		assertEquals("In a party. Members running Luck vs Skill appear here once their fight starts.",
			SidePanelParty.party(in).empty);
		// Out of a party: the not-in-a-party line.
		in.inParty = false;
		assertEquals(SidePanelParty.NOT_IN_PARTY, SidePanelParty.party(in).empty);
		// Someone has shared but nothing of yours is on show yet.
		in.inParty = true;
		in.shared = 1;
		in.members.add(new SidePanelParty.Member(5, "Moss Pike", false, SidePanelPartyExamples.figures("Zebak", true,
			100, 90, 20, 0.5, 0, 0.9, 1)));
		assertEquals(SidePanelParty.NOTHING_YET, SidePanelParty.party(in).empty);
	}

	/**
	 * T-176: joining a party with someone in it puts the party's line on the Fight tab at the next snapshot, the member
	 * listed (T-167); leaving takes it off, with nothing left behind.
	 */
	@Test
	public void theFightTabFollowsThePartyOnTheNextSnapshot()
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		final SidePanelParty.Input out = new SidePanelParty.Input();
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(out)));
		assertFalse(waiting(p));
		final int solo = p.fightTab().getComponentCount();
		final SidePanelParty.Input joined = new SidePanelParty.Input();
		joined.inParty = true;
		joined.partySize = 2;
		joined.partyKey = 42;
		joined.unshared.add(new SidePanelParty.Unshared(77, "Ash Kettle", null));
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(joined)));
		assertTrue(waiting(p));
		// Alone in a party (nobody to list): nothing, as solo.
		final SidePanelParty.Input alone = new SidePanelParty.Input();
		alone.inParty = true;
		alone.partySize = 1;
		alone.partyKey = 42;
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(alone)));
		assertFalse(waiting(p));
		p.apply(new PanelSnapshot(null, null, SidePanelParty.party(out)));
		assertFalse(waiting(p));
		assertEquals(solo, p.fightTab().getComponentCount());
	}

	private static boolean waiting(PvmPerformancePanel p)
	{
		boolean waiting = false;
		for (java.awt.Component c : all(p.fightTab()))
		{
			waiting |= c instanceof SidePanelParts.Paragraph && ((SidePanelParts.Paragraph) c).text().startsWith(
				"In a party");
		}
		return waiting;
	}

	private static List<java.awt.Component> all(java.awt.Container c)
	{
		final List<java.awt.Component> out = new java.util.ArrayList<>();
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
}

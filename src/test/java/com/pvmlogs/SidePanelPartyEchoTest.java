package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T-169 (Eric, 2026-11-05: "Party damage shows 0 for other player in the raid when they are in my party but don't have
 * luck vs skill installed"): the dev loopback's echo is your own figures, shown for testing, and never counted. Others
 * isn't less it, and no total, share or rank counts it twice.
 */
public class SidePanelPartyEchoTest
{
	/** {@code in} without the echo, as a hub build has it. */
	private static SidePanelParty.Input withoutEcho(SidePanelParty.Input in)
	{
		in.members.removeIf(m -> m.echo);
		return in;
	}

	private static PanelSnapshot.PartyRow row(PanelSnapshot.PartyTab t, String name)
	{
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			if (r.name.equals(name))
			{
				return r;
			}
		}
		throw new AssertionError(name + " not on the meter");
	}

	@Test
	public void withTheEchoOnADuoPartnerNotRunningItGetsTheOthersDamageNotZero()
	{
		// Zebak [72991] in pvm-debug-20261104-151902.log: others 1381, Eric's own 1634.
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.zebakDuoWithEcho());
		// T-208: the partner is the group row, "1 member".
		final PanelSnapshot.PartyRow partner = row(t, "1 member");
		assertEquals("~1,381", partner.dmg[0]);
		assertTrue(partner.tip, partner.tip.contains("Ash Kettle"));
		assertEquals(1_381, SidePanelParty.others(1_381, SidePanelPartyExamples.zebakDuoWithEcho().members));
		// Before T-169 the echo's 1,634 was taken from it: 0.
		final SidePanelParty.Input before = SidePanelPartyExamples.zebakDuoWithEcho();
		before.members.get(1).echo = false;
		assertEquals(0, SidePanelParty.others(before.othersDamage, before.members));
	}

	@Test
	public void theTotalSharesAndRanksAreUnchangedByTheEcho()
	{
		for (SidePanelParty.Input[] pair : new SidePanelParty.Input[][]{
			{SidePanelPartyExamples.zebakDuoWithEcho(), withoutEcho(SidePanelPartyExamples.zebakDuoWithEcho())},
			{withEcho(SidePanelPartyExamples.toa()), SidePanelPartyExamples.toa()},
			{withEcho(SidePanelPartyExamples.cox()), SidePanelPartyExamples.cox()}})
		{
			final PanelSnapshot.PartyTab on = SidePanelParty.party(pair[0]);
			final PanelSnapshot.PartyTab off = SidePanelParty.party(pair[1]);
			final String at = pair[1].scope + ": ";
			// The header's party DPS is the total over the time.
			assertEquals(at, off.partyDps, on.partyDps);
			assertEquals(at, off.othersNote, on.othersNote);
			// The Fight tab's line: your rank, of the members counted, and your share.
			assertEquals(at, SidePanelPartyExamples.fightLine(off), SidePanelPartyExamples.fightLine(on));
			// Every row but the echo's reads as it did, in the same order.
			final List<PanelSnapshot.PartyRow> counted = new ArrayList<>();
			for (PanelSnapshot.PartyRow r : on.rows)
			{
				if (!r.echo)
				{
					counted.add(r);
				}
			}
			assertEquals(at, off.rows.size(), counted.size());
			for (int i = 0; i < counted.size(); i++)
			{
				final PanelSnapshot.PartyRow a = off.rows.get(i);
				final PanelSnapshot.PartyRow b = counted.get(i);
				assertEquals(at + a.name, a.name, b.name);
				assertEquals(at + a.name, String.join("|", a.dmg), String.join("|", b.dmg));
				if (a.breakdown != null)
				{
					assertEquals(at + a.name, a.breakdown.place, b.breakdown.place);
				}
			}
			// The echo reads as you do, in your place.
			final PanelSnapshot.PartyRow you = row(on, SidePanelParty.YOU_NAME);
			final PanelSnapshot.PartyRow echo = row(on, PartyBoard.ECHO_NAME);
			assertTrue(echo.echo);
			assertFalse(echo.others);
			assertEquals(at, String.join("|", you.dmg), String.join("|", echo.dmg));
			assertEquals(at, you.breakdown.place, echo.breakdown.place);
		}
	}

	@Test
	public void withoutTheEchoNothingChanges()
	{
		// The ToA room: others is the non-sharing member's 1,830, as before T-169.
		final SidePanelParty.Input toa = SidePanelPartyExamples.toa();
		assertEquals(1_830, SidePanelParty.others(toa.othersDamage, toa.members));
		final PanelSnapshot.PartyTab t = SidePanelParty.party(toa);
		assertEquals("you 2nd of 3 · 29%", SidePanelPartyExamples.fightLine(t));
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			assertFalse(r.name, r.echo);
		}
	}

	@Test
	public void theEchoIsYourPartyIdOrTheLoopbacksName()
	{
		assertTrue(SidePanelParty.echo(42, "Ash Kettle", 42));
		assertTrue(SidePanelParty.echo(9, PartyBoard.ECHO_NAME, 42));
		// The local copy, fed before the party had a local member: id -1.
		assertTrue(SidePanelParty.echo(-1, PartyBoard.LOCAL_NAME, Long.MIN_VALUE));
		assertFalse(SidePanelParty.echo(9, "Ash Kettle", 42));
		assertFalse(SidePanelParty.echo(9, "Ash Kettle", Long.MIN_VALUE));
	}

	@Test
	public void soloWithTheEchoHasNoOthers()
	{
		// Nobody else hit: no others row, and you're 1st of 1 at 100%.
		final PanelSnapshot.PartyTab t = SidePanelParty.party(SidePanelPartyExamples.withEcho());
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			assertFalse(r.name, r.others);
		}
		assertEquals("you 1st of 1 · 100%", SidePanelPartyExamples.fightLine(t));
	}

	/** {@code in} with the dev echo of your own figures added, as the loopback shows it. */
	private static SidePanelParty.Input withEcho(SidePanelParty.Input in)
	{
		in.members.add(SidePanelPartyExamples.echoOf(in.members.get(0), 99));
		return in;
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * T-209: which party members were in the fight. Eric, 2026-11-10, after a duo Yama kill in a party of three: the meter
 * split "others" between both members not running Luck vs Skill, though one wasn't there. The names here are made up,
 * as in {@link SidePanelPartyExamples}; no party member's name goes in the repo.
 */
public class PartySeenTest
{
	private static final long YOU = 1;
	private static final long MOSS = 81;
	private static final long BRINDLE = 82;

	private static Map<String, Long> party()
	{
		final Map<Long, String> names = new HashMap<>();
		names.put(YOU, "Me Myself");
		names.put(MOSS, "Moss Pike");
		names.put(BRINDLE, "Brindle");
		return PartySeen.byKey(names, YOU);
	}

	@Test
	public void namesMatchAsJagexWritesThem()
	{
		final Map<String, Long> party = party();
		// You aren't among the members looked for.
		assertNull(party.get(PartySeen.key("Me Myself")));
		// The scene's name as the client gives it: a non-breaking space, any case, colour tags.
		assertEquals(Long.valueOf(MOSS), party.get(PartySeen.key("Moss Pike")));
		assertEquals(Long.valueOf(MOSS), party.get(PartySeen.key("moss pike")));
		assertEquals(Long.valueOf(MOSS), party.get(PartySeen.key("<col=ffffff>Moss Pike</col>")));
		assertEquals(Long.valueOf(BRINDLE), party.get(PartySeen.key("BRINDLE")));
		assertNull(party.get(PartySeen.key("Moss Pikes")));
		assertNull(PartySeen.key(null));
		assertNull(PartySeen.key(""));
	}

	@Test
	public void inAnInstanceAnywhereInTheOpenWorldNearTheTarget()
	{
		assertTrue(PartySeen.inReach(true, 60, -40, 0, 0));
		assertTrue(PartySeen.inReach(false, PartySeen.OPEN_WORLD_TILES, -3, 0, 0));
		assertFalse(PartySeen.inReach(false, PartySeen.OPEN_WORLD_TILES + 1, 0, 0, 0));
		assertFalse("another floor", PartySeen.inReach(false, 2, 2, 1, 0));
	}

	@Test
	public void aMemberInTheSceneCountsOneNotSeenDoesnt()
	{
		final PartySeen seen = new PartySeen();
		final Encounter yama = new Encounter("Yama", null, 1_000L);
		// Not fought in a party: unknown, not "nobody".
		assertNull(seen.inFight(yama));
		seen.looked(yama);
		assertEquals(Collections.emptySet(), seen.inFight(yama));
		seen.seen(yama, MOSS);
		seen.seen(yama, MOSS);
		assertEquals(Collections.singleton(MOSS), seen.inFight(yama));
		// Another room's fight is its own.
		assertNull(seen.inFight(new Encounter("Yama", null, 90_000L)));
	}

	@Test
	public void aSharingMembersUpdateForTheRoomCounts()
	{
		final PartySeen seen = new PartySeen();
		final Encounter yama = new Encounter("Yama", null, 1_000L);
		seen.looked(yama);
		// Brindle's update for Yama, since the room began: in the fight, though never seen.
		seen.shared(BRINDLE, "Yama", 5_000L);
		// An update for another room, or from before this one began, isn't.
		seen.shared(MOSS, "Vardorvis", 5_000L);
		seen.shared(MOSS, "Yama", 500L);
		seen.matchShared(yama, 6_000L);
		assertEquals(Collections.singleton(BRINDLE), seen.inFight(yama));
		assertEquals(1, seen.sharing(yama));
		// The room-end message after the room ended, within a minute, still counts.
		yama.end(10_000L);
		seen.shared(MOSS, "Yama", 40_000L);
		seen.matchShared(yama, 41_000L);
		assertEquals(new HashSet<>(Arrays.asList(BRINDLE, MOSS)), seen.inFight(yama));
	}

	@Test
	public void othersIsSplitOnlyBetweenThoseInTheFight()
	{
		// The trio: two members not running Luck vs Skill. Only Moss Pike was in the fight.
		final SidePanelParty.Input in = SidePanelPartyExamples.trioTwoNotSharing();
		in.inFight = Collections.singleton(MOSS);
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		// One member: others is about theirs (T-167's one-member case); Brindle is listed apart, out of it.
		assertEquals(SidePanelParty.aboutOne("Moss Pike"), t.othersNote);
		final List<String> names = new ArrayList<>();
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			names.add(r.name);
		}
		assertFalse(names.contains("2 members"));
		final PanelSnapshot.PartyRow moss = rowById(t, MOSS);
		assertTrue(moss.others);
		assertEquals(SidePanelParty.ABOUT, moss.hint);
		final PanelSnapshot.PartyRow brindle = rowById(t, BRINDLE);
		assertTrue(brindle.listed);
		assertEquals(SidePanelParty.NOT_RUNNING, brindle.hint);
		// For T-208's rows.
		assertSame(in.inFight, t.inFight);
		// Both in it: one combined row, as before.
		in.inFight = new HashSet<>(Arrays.asList(MOSS, BRINDLE));
		assertEquals(SidePanelParty.aboutMany(2), SidePanelParty.party(in).othersNote);
		// Not known (no fight in a party): as before, every member who hasn't shared.
		in.inFight = null;
		assertEquals(SidePanelParty.aboutMany(2), SidePanelParty.party(in).othersNote);
		// Neither in it: others stays others, no one's.
		in.inFight = Collections.emptySet();
		assertEquals(SidePanelParty.OTHERS_NOTE, SidePanelParty.party(in).othersNote);
	}

	private static PanelSnapshot.PartyRow rowById(PanelSnapshot.PartyTab t, long id)
	{
		for (PanelSnapshot.PartyRow r : t.rows)
		{
			if (r.id == id)
			{
				return r;
			}
		}
		throw new AssertionError("no row " + id);
	}

	@Test
	public void theLogLineHasCountsNeverNames()
	{
		final String line = PartySeen.line("Yama", 1, 2, 0);
		assertEquals("PARTY SEEN Yama: 1 of 2 members in the fight (0 of them by their own updates)", line);
		for (String name : party().keySet())
		{
			assertFalse(line.toLowerCase(java.util.Locale.ENGLISH).contains(name));
		}
		// Only ids are kept with the room.
		final PartySeen seen = new PartySeen();
		final Encounter yama = new Encounter("Yama", null, 1_000L);
		seen.seen(yama, MOSS);
		final Set<Long> ids = seen.inFight(yama);
		assertEquals(Collections.singleton(MOSS), ids);
	}

	@Test
	public void theDevEchoIsOffUnlessAskedFor()
	{
		assertFalse(PartyShare.echoAsked(null));
		assertFalse(PartyShare.echoAsked(""));
		assertFalse(PartyShare.echoAsked("false"));
		assertFalse(PartyShare.echoAsked("0"));
		assertTrue(PartyShare.echoAsked("true"));
		assertTrue(PartyShare.echoAsked(" TRUE "));
		assertTrue(PartyShare.echoAsked("1"));
		assertEquals("LUCKVSSKILL_PARTY_ECHO", PartyShare.ECHO_ENV);
		// And the property it sets is still never on for a hub build.
		assertFalse(PartyShare.echoEnabled("true", "release 1.0.0"));
	}
}

package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-123 (Eric, 2026-10-31 00:00): New trip clicked right after a boss kill showed that kill as the new trip's kill 1.
 * Reproduced here through the calls the plugin and the panel make, not from a log. The kill's fight was still open at
 * the click (the boss dead, its fight not yet ended or its room not yet closed), so two paths brought it in:
 * <ul>
 * <li>the rows: {@link SidePanelHistory#tripRooms} put the running room first whatever its start;</li>
 * <li>the totals: {@link SessionTotals#recordFightEnded} at the fight's end, and every record between the click and
 * the end, went into whatever trip was current, so the new one counted the kill.</li>
 * </ul>
 * The rule now: a trip holds only fights that started at or after its start. A fight or a boss's room running at the
 * click is the old trip's; a run of monsters (T-089) is split at the click.
 */
public class SidePanelTripResetTest
{
	private static final long KILL_START = SidePanelExamples.at(15, 0);
	/** The boss dies 1:30 in; its fight ends 3 s later (the despawn, or the loot that closes the room). */
	private static final long DEATH = KILL_START + 90_000L;
	private static final long FIGHT_END = DEATH + 3_000L;

	/** A Vardorvis kill fought through the trip's own calls, routed as the plugin routes them. */
	private static Fight fight(SessionTotals trip, SessionTotals old, long start, Encounter room)
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, start, null, 0);
		if (room != null)
		{
			room.add(f);
		}
		for (int i = 0; i < 30; i++)
		{
			f.recordAttackMade(false, 0.92);
			PvmPerformancePlugin.tripFor(trip, old, f, room).recordAttackMade(false, 0.92, false);
			f.recordExpected(40, 0.55, 12, 140);
			PvmPerformancePlugin.tripFor(trip, old, f, room).recordExpected(0.55, 12, 140);
			f.recordDamageDealt(23, start + i * 2400L, true);
			PvmPerformancePlugin.tripFor(trip, old, f, room).recordAttempt(23, true, start + i * 2400L);
		}
		return f;
	}

	/** The fight's end as the plugin books it (finalizeFight): its record, then the trip's. */
	private static void end(SessionTotals trip, SessionTotals old, Fight f, Encounter room, long now)
	{
		f.end(true, now);
		PvmPerformancePlugin.tripFor(trip, old, f, room).recordFightEnded(true, f, now);
	}

	private static List<SidePanelHistory.KillRow> rows(List<Fight> history, long since, Encounter running)
	{
		return SidePanelHistory.tripRooms(SidePanelHistory.tripSaved(history, since, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE), SidePanelHistory.sinceTrip(running, since));
	}

	/** The new trip as the Trip tab reads it, its rows as {@link PvmPerformancePanel}'s readTrip lists them. */
	private static PanelSnapshot.TripTab tab(SessionTotals trip, List<SidePanelHistory.KillRow> rooms, boolean running)
	{
		return SidePanelModel.trip(input(trip, rooms, running));
	}

	static SidePanelModel.Input input(SessionTotals trip, List<SidePanelHistory.KillRow> rooms, boolean running)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = LedgerRows.of(trip);
		in.tripSince = trip.getStartMillis();
		in.ended = !running;
		final List<PanelSnapshot.TripRow> out = new ArrayList<>();
		for (SidePanelHistory.KillRow r : rooms)
		{
			out.add(SidePanelModel.tripRow(r));
		}
		in.tripRows = out;
		in.drain = new SidePanelModel.Drain();
		return in;
	}

	/** For {@link SidePanelPictures}: New trip clicked 1 s after a Vardorvis died, before its fight ended; then it ends. */
	static SidePanelModel.Input newTripRightAfterAKill()
	{
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, KILL_START);
		final Fight f = fight(trip, old, KILL_START, room);
		trip.reset(DEATH + 1_000L);
		end(trip, old, f, room, FIGHT_END);
		return input(trip, rows(Collections.singletonList(f), trip.getStartMillis(), null), false);
	}

	/** For {@link SidePanelPictures}: a run of 12 demons with New trip clicked before the 7th. */
	static SidePanelModel.Input runAcrossNewTrip()
	{
		final List<Fight> oldestFirst = SidePanelRunExamples.runFights(KILL_START, 12, 12, 7);
		final long click = oldestFirst.get(6).getStartMillis() - 1_000L;
		final SessionTotals trip = new SessionTotals(click);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter run = new Encounter(SidePanelRunExamples.DEMON, null, KILL_START);
		oldestFirst.forEach(run::add);
		for (Fight f : oldestFirst)
		{
			for (int a = 0; a < f.getAttacksMade(); a++)
			{
				PvmPerformancePlugin.tripFor(trip, old, f, run).recordAttackMade(false, 0.9, false);
				// The tentacle's 4-tick cooldown: the attack's tick and three more.
				for (int t = 0; t < 3; t++)
				{
					PvmPerformancePlugin.tripFor(trip, old, f, run).recordTickSpent(true);
				}
			}
			PvmPerformancePlugin.tripFor(trip, old, f, run).recordAttempt(f.getDamageDealt(), true, f.getStartMillis());
			PvmPerformancePlugin.tripFor(trip, old, f, run).recordFightEnded(true, f, f.getLastActivityMillis());
		}
		final List<Fight> history = new ArrayList<>(oldestFirst);
		Collections.reverse(history);
		return input(trip, rows(history, click, null), false);
	}

	private static void assertEmpty(SessionTotals trip)
	{
		assertEquals(0, trip.getFights());
		assertEquals(0, trip.getKills());
		assertEquals(0, trip.getAttacksMade());
		assertEquals(0, trip.getDamageDealt());
		assertEquals(0, trip.getAttempts());
		assertEquals(0, trip.getExpectedSamples());
		assertEquals(0, trip.getDamageTaken());
		assertEquals(0, trip.getHitsTaken());
		assertEquals(0, trip.getDotTaken());
	}

	@Test
	public void damageTakenInAFightFromBeforeTheClickStaysOut()
	{
		// The Orchestrator's review of #144: the plugin's onSplatOnMe hands DamageTaken the trip the fight goes to.
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, KILL_START);
		final Fight a = fight(trip, old, KILL_START, room);
		final DamageTaken taken = new DamageTaken();
		taken.onSplat(net.runelite.api.HitsplatID.DAMAGE_ME, 25, 10, false, a, PvmPerformancePlugin.tripFor(trip, old, a, room),
			KILL_START + 20_000L);
		assertEquals(25, trip.getDamageTaken());
		trip.reset(KILL_START + 40_000L);
		// After the click: a hit and venom on the player, in the fight that began before it.
		taken.onSplat(net.runelite.api.HitsplatID.DAMAGE_ME, 31, 80, false, a, PvmPerformancePlugin.tripFor(trip, old, a, room),
			KILL_START + 48_000L);
		taken.onSplat(net.runelite.api.HitsplatID.VENOM, 6, 90, false, a, PvmPerformancePlugin.tripFor(trip, old, a, room),
			KILL_START + 54_000L);
		assertEquals(0, trip.getDamageTaken());
		assertEquals(0, trip.getHitsTaken());
		assertEquals(0, trip.getDotTaken());
		assertEquals(31, old.getDamageTaken());
		assertEquals(6, old.getDotTaken());
		// The next fight's hits are the trip's.
		final long next = FIGHT_END + 30_000L;
		final Encounter second = new Encounter("Vardorvis", null, next);
		final Fight b = fight(trip, old, next, second);
		taken.onSplat(net.runelite.api.HitsplatID.DAMAGE_ME, 12, 300, false, b,
			PvmPerformancePlugin.tripFor(trip, old, b, second), next + 5_000L);
		assertEquals(12, trip.getDamageTaken());
		assertEquals(1, trip.getHitsTaken());
	}

	@Test
	public void thePathTheKillCameInBy()
	{
		// The old routing: everything into the trip current at the time. New trip between the death and the fight's end.
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final Encounter room = new Encounter("Vardorvis", null, KILL_START);
		final Fight f = fight(trip, trip, KILL_START, room);
		trip.reset(DEATH + 1_000L);
		f.end(true, FIGHT_END);
		trip.recordFightEnded(true, f, FIGHT_END);
		// The new trip has the kill and its fight.
		assertEquals(1, trip.getKills());
		assertEquals(1, trip.getFights());
		// And its row: the room still running (the fight open, so not over) went first whatever its start.
		final List<SidePanelHistory.KillRow> before = SidePanelHistory.tripRooms(Collections.emptyList(), room);
		assertEquals(1, before.size());
		assertSame(room, before.get(0).room);
	}

	@Test
	public void newTripBetweenTheDeathAndTheFightsEndStartsEmpty()
	{
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, KILL_START);
		final Fight f = fight(trip, old, KILL_START, room);
		assertEquals(30, trip.getAttacksMade());
		trip.reset(DEATH + 1_000L);
		// While it's still open, the room is on show but isn't the new trip's: no row.
		assertNull(SidePanelHistory.sinceTrip(room, trip.getStartMillis()));
		assertTrue(rows(Collections.emptyList(), trip.getStartMillis(), room).isEmpty());
		// A tick and a late splat between the click and the end stay out too.
		PvmPerformancePlugin.tripFor(trip, old, f, room).recordTickSpent(true);
		PvmPerformancePlugin.tripFor(trip, old, f, room).recordAttempt(12, true, DEATH + 1_200L);
		end(trip, old, f, room, FIGHT_END);
		assertEmpty(trip);
		assertEquals(0, trip.getCombatTicks());
		assertEquals(1, old.getKills());
		// Saved, it's still the old trip's: no row, 0 kills, nothing in the tab.
		final List<SidePanelHistory.KillRow> after = rows(Collections.singletonList(f), trip.getStartMillis(), null);
		assertTrue(after.isEmpty());
		final PanelSnapshot.TripTab t = tab(trip, after, false);
		assertTrue(t.roomsHint.text, t.roomsHint.text.isEmpty());
		assertTrue(t.rooms.isEmpty());
		assertEquals("0:00", t.header.clock.text);
	}

	@Test
	public void newTripAfterTheFightsEndStartsEmpty()
	{
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter room = new Encounter("Vardorvis", null, KILL_START);
		final Fight f = fight(trip, old, KILL_START, room);
		end(trip, old, f, room, FIGHT_END);
		assertEquals(1, trip.getKills());
		trip.reset(FIGHT_END + 2_000L);
		assertEmpty(trip);
		final List<SidePanelHistory.KillRow> after = rows(Collections.singletonList(f), trip.getStartMillis(), null);
		assertTrue(after.isEmpty());
		assertTrue(tab(trip, after, false).roomsHint.text.isEmpty());
	}

	@Test
	public void newTripMidFightLeavesThatFightOutAndTheNextIsKillOne()
	{
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter first = new Encounter("Vardorvis", null, KILL_START);
		final Fight a = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, KILL_START, null, 0);
		first.add(a);
		trip.reset(KILL_START + 40_000L);
		// The rest of the fight, after the click.
		for (int i = 0; i < 20; i++)
		{
			a.recordAttackMade(false, 0.92);
			PvmPerformancePlugin.tripFor(trip, old, a, first).recordAttackMade(false, 0.92, false);
			a.recordDamageDealt(30, KILL_START + 40_000L + i * 2400L, true);
			PvmPerformancePlugin.tripFor(trip, old, a, first).recordAttempt(30, true, KILL_START + 40_000L + i * 2400L);
		}
		end(trip, old, a, first, FIGHT_END);
		assertEmpty(trip);
		// The next kill is the trip's first.
		final long next = FIGHT_END + 30_000L;
		final Encounter second = new Encounter("Vardorvis", null, next);
		final Fight b = fight(trip, old, next, second);
		assertSame(second, SidePanelHistory.sinceTrip(second, trip.getStartMillis()));
		end(trip, old, b, second, next + 93_000L);
		assertEquals(1, trip.getKills());
		assertEquals(1, trip.getFights());
		assertEquals(30, trip.getAttacksMade());
		assertEquals(30 * 23, trip.getDamageDealt());
		final List<Fight> history = Arrays.asList(b, a);
		final List<SidePanelHistory.KillRow> rooms = rows(history, trip.getStartMillis(), null);
		assertEquals(1, rooms.size());
		assertSame(b, rooms.get(0).room.getFights().get(0));
		assertEquals("1 kill", tab(trip, rooms, false).roomsHint.text);
		// The avoidable names and the drain specials are read over the rows' fights only.
		assertEquals(Collections.singletonList(b), PvmPerformancePanel.tripFights(SidePanelHistory.tripSaved(history,
			trip.getStartMillis(), SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE)));
	}

	@Test
	public void aBossRoomRunningAtTheClickIsTheOldTripsWithItsLaterFights()
	{
		// The Royal Titans: Branda fought before the click, Eldric after it, in the same room.
		final SessionTotals trip = new SessionTotals(KILL_START - 60_000L);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter room = new Encounter("The Royal Titans", null, KILL_START);
		final Fight branda = new Fight("Branda the Fire Queen", NpcID.RT_FIRE_QUEEN, 2, 600, KILL_START, null, 0);
		room.add(branda);
		trip.reset(KILL_START + 20_000L);
		final Fight eldric = new Fight("Eldric the Ice King", NpcID.RT_ICE_KING, 3, 600, KILL_START + 30_000L, null, 0);
		room.add(eldric);
		eldric.recordAttackMade(false, 0.9);
		PvmPerformancePlugin.tripFor(trip, old, eldric, room).recordAttackMade(false, 0.9, false);
		eldric.recordDamageDealt(40, KILL_START + 31_000L, true);
		PvmPerformancePlugin.tripFor(trip, old, eldric, room).recordAttempt(40, true, KILL_START + 31_000L);
		assertEmpty(trip);
		assertNull(SidePanelHistory.sinceTrip(room, trip.getStartMillis()));
		// With no fight on, the trip on now.
		assertSame(trip, PvmPerformancePlugin.tripFor(trip, old, null, room));
	}

	@Test
	public void aRunOfMonstersAcrossTheClickIsSplitAtIt()
	{
		// T-095: 12 demons from 3:00 pm, 7 attacks each, a fight every 19.8 s; New trip between the 6th and the 7th.
		final List<Fight> oldestFirst = SidePanelRunExamples.runFights(KILL_START, 12, 12, 7);
		final long click = oldestFirst.get(6).getStartMillis() - 1_000L;
		final SessionTotals trip = new SessionTotals(click);
		final SessionTotals old = new SessionTotals(0L);
		final Encounter run = new Encounter(SidePanelRunExamples.DEMON, null, KILL_START);
		oldestFirst.forEach(run::add);
		for (Fight f : oldestFirst)
		{
			PvmPerformancePlugin.tripFor(trip, old, f, run).recordFightEnded(true, f, f.getLastActivityMillis());
		}
		// The run's six fights since the click are the trip's, the six before the old one's.
		assertEquals(6, trip.getKills());
		assertEquals(6, old.getKills());
		final List<Fight> history = new ArrayList<>(oldestFirst);
		Collections.reverse(history);
		final List<SidePanelHistory.KillRow> saved = rows(history, click, null);
		assertEquals(1, saved.size());
		assertEquals(6, saved.get(0).kills);
		assertEquals(oldestFirst.subList(6, 12), saved.get(0).room.getFights());
		assertEquals("6 kills", tab(trip, saved, false).roomsHint.text);
		// While it runs, the run on show is its part since the click.
		final Encounter live = new Encounter(SidePanelRunExamples.DEMON, null, KILL_START);
		oldestFirst.subList(0, 8).forEach(live::add);
		final List<SidePanelHistory.KillRow> running = rows(Collections.emptyList(), click, live);
		assertEquals(1, running.size());
		assertEquals(2, running.get(0).kills);
		// A click mid-fight: that demon stays the old trip's.
		final Fight mid = oldestFirst.get(6);
		final SessionTotals midTrip = new SessionTotals(mid.getStartMillis() + 5_000L);
		assertSame(old, PvmPerformancePlugin.tripFor(midTrip, old, mid, run));
		assertSame(midTrip, PvmPerformancePlugin.tripFor(midTrip, old, oldestFirst.get(7), run));
		assertEquals(5, rows(history, midTrip.getStartMillis(), null).get(0).kills);
	}
}

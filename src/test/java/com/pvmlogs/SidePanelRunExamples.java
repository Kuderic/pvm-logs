package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T-095: runs of monsters (T-089) for the side panel's light view, shaped on Eric's Hallowfell demon trip
 * (illustrative figures): Abyssal demons with an Abyssal tentacle, a demon dying about every 16 s of the run's combat
 * time. Shared by {@link SidePanelMonsterRunTest} and {@link SidePanelPictures}.
 */
final class SidePanelRunExamples
{
	static final String DEMON = "Abyssal demon";
	static final int CATACOMBS_DEMON = 7241;

	private SidePanelRunExamples()
	{
	}

	/** When the run starts: 3:00 pm. */
	static final long START = SidePanelExamples.at(15, 0);
	/** The run's own clock at its last attack: 3:25, the 3:11 of combat time and the walks and respawns between. */
	static final long RUN_MILLIS = 205_000L;

	/**
	 * A run of 12 demons fought through the plugin's own calls, with its timeline: 78 tentacle attacks (4 ticks), 7
	 * idle ticks between them, 3:11 of combat time, from {@link #START}, its last attack {@link #RUN_MILLIS} later.
	 */
	static SidePanelTimelineExamples.Built demonRun(int kills)
	{
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.plan(DEMON, START,
			tentacle(26, 4), tentacle(26, 3), tentacle(26, 0));
		b.fight.groupAsMonster();
		for (int i = 0; i < kills; i++)
		{
			b.fight.recordKill();
		}
		// Its last attack, at the run's clock's end: a splash adds no damage.
		b.fight.recordSplash(START + RUN_MILLIS);
		// The demons' hits: 9 of 1 to 9 that did damage, among zeros.
		for (int hit : new int[]{6, 0, 4, 9, 0, 0, 2, 7, 0, 5, 1, 0, 8, 3})
		{
			b.fight.recordHitTaken(hit, false, 0L);
		}
		return b;
	}

	private static SidePanelTimelineExamples.Stretch tentacle(int attacks, int lostAfter)
	{
		return new SidePanelTimelineExamples.Stretch("Abyssal tentacle", net.runelite.api.gameval.ItemID.ABYSSAL_TENTACLE,
			FightTimeline.Style.MELEE, false, 4, SidePanelTimelineExamples.hits(attacks, 22, 0, 18, 30, 9, 14, 26), 14.2,
			33, 0.82, lostAfter);
	}

	/** The run as it is fought: 12 kills so far, waiting for the next demon 15 s after its last attack. */
	static SidePanelModel.Input live()
	{
		return live(demonRun(12));
	}

	/** A run 24 s in, one demon dead: kills/hr waits for a minute. */
	static SidePanelModel.Input liveUnderAMinute()
	{
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.plan(DEMON, START, tentacle(10, 0));
		b.fight.groupAsMonster();
		b.fight.recordKill();
		return live(b, START + 30_000L);
	}

	static SidePanelModel.Input live(SidePanelTimelineExamples.Built b)
	{
		return live(b, START + RUN_MILLIS + 15_000L);
	}

	/** The run read as the panel reads it at {@code now}, its clock the overlay's. */
	static SidePanelModel.Input live(SidePanelTimelineExamples.Built b, long now)
	{
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, false);
		in.runSeconds = SidePanelModel.runSeconds(b.room, now);
		in.maxHp = 150;
		in.solo = true;
		in.hitsTaken = 9;
		return in;
	}

	/** The run saved and opened from History, as the History tab reads it, with its timeline from this session. */
	static SidePanelModel.Input saved()
	{
		final SidePanelTimelineExamples.Built b = demonRun(12);
		b.fight.end(true, START + RUN_MILLIS);
		b.fight.closeRoom();
		final List<Fight> history = Collections.singletonList(b.fight);
		final Encounter room = SidePanelHistory.allRooms(history).get(0);
		return SidePanelHistory.savedKill(history, room, -1, SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE, null,
			b.view());
	}

	/**
	 * A run saved as T-089 saves it: a fight per demon targeted, {@code attacks} tentacle attacks each, the first
	 * {@code kills} of them dying, the last closing the room.
	 */
	static List<Fight> runFights(long start, int fights, int kills, int attacks)
	{
		final List<Fight> out = new ArrayList<>();
		long t = start;
		for (int i = 0; i < fights; i++)
		{
			final Fight f = new Fight(DEMON, CATACOMBS_DEMON, 100 + i, 150, t, null, 0);
			f.groupAsMonster();
			for (int a = 0; a < attacks; a++)
			{
				f.recordAttackMade(false, 0.9);
				f.recordExpected(33, 0.82, 14.2, 150);
				f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
				f.recordTickSpent();
				f.recordTickSpent();
				f.recordTickSpent();
			}
			f.recordDamageDealt(i < kills ? 150 : attacks * 13, t + attacks * 2400L, true);
			if (i % 3 == 0)
			{
				f.recordHitTaken(6 + i % 5, false, t);
			}
			if (i < kills)
			{
				f.recordKill();
			}
			f.end(i < kills, t + attacks * 2400L);
			t += attacks * 2400L + 3_000L;
			out.add(f);
		}
		out.get(out.size() - 1).closeRoom();
		return out;
	}

	/**
	 * History's fixtures ({@link SidePanelHistoryExamples#history}, with its three demons saved one fight each before
	 * T-089) and three runs of demons after them: 12 kills in 3:22, 9 in 2:31 and 15 in 5:24. Newest first.
	 */
	static List<Fight> history()
	{
		final List<Fight> oldestFirst = new ArrayList<>(SidePanelHistoryExamples.history());
		Collections.reverse(oldestFirst);
		oldestFirst.addAll(runFights(SidePanelExamples.at(13, 0), 12, 12, 7));
		oldestFirst.addAll(runFights(SidePanelExamples.at(14, 0), 9, 9, 7));
		oldestFirst.addAll(runFights(SidePanelExamples.at(15, 10), 15, 15, 9));
		final List<Fight> newestFirst = new ArrayList<>(oldestFirst);
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/** A trip of a Vardorvis kill and a run of 12 demons, nothing running: the Trip tab as the panel reads it. */
	static SidePanelModel.Input trip()
	{
		final List<Fight> history = tripHistory();
		final List<Fight> oldestFirst = new ArrayList<>(history);
		Collections.reverse(oldestFirst);
		final long since = SidePanelExamples.at(14, 40);
		// The trip's own sums, as SessionTotals keeps them: every fight in it.
		final Encounter whole = new Encounter("Trip", null, since);
		oldestFirst.forEach(whole::add);
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = LedgerRows.of(whole);
		in.tripSince = since;
		in.hitsTaken = whole.getHitsTaken();
		in.scoredThrall = in.thrall = 60;
		in.ended = true;
		final List<SidePanelHistory.KillRow> rooms = SidePanelHistory.tripRooms(SidePanelHistory.tripSaved(history,
			since, SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE), null);
		final List<PanelSnapshot.TripRow> rows = new ArrayList<>();
		for (SidePanelHistory.KillRow r : rooms)
		{
			rows.add(SidePanelModel.tripRow(r));
		}
		in.tripRows = rows;
		// As PvmPerformancePanel.tripLuckScope reads it.
		in.luckScope = LedgerRows.of(SidePanelModel.withoutRuns(rooms));
		return in;
	}

	/** The trip's fights, newest first: a Vardorvis kill ending 2:52 pm, then a run of 12 demons from 3:00 pm. */
	static List<Fight> tripHistory()
	{
		final List<Fight> oldestFirst = new ArrayList<>();
		oldestFirst.add(SidePanelHistoryExamples.vardorvis(SidePanelExamples.at(14, 52), 117, 10, true, 10, 141));
		oldestFirst.addAll(runFights(SidePanelExamples.at(15, 0), 12, 12, 7));
		Collections.reverse(oldestFirst);
		return oldestFirst;
	}

	/** A trip of runs only: no Luck or Execution to show. */
	static SidePanelModel.Input tripOfRunsOnly()
	{
		final List<Fight> oldestFirst = runFights(SidePanelExamples.at(15, 0), 12, 12, 7);
		final List<Fight> history = new ArrayList<>(oldestFirst);
		Collections.reverse(history);
		final long since = SidePanelExamples.at(14, 40);
		final Encounter whole = new Encounter("Trip", null, since);
		oldestFirst.forEach(whole::add);
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = LedgerRows.of(whole);
		in.tripSince = since;
		in.ended = true;
		final List<SidePanelHistory.KillRow> rooms = SidePanelHistory.tripRooms(SidePanelHistory.tripSaved(history,
			since, SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE), null);
		final List<PanelSnapshot.TripRow> rows = new ArrayList<>();
		for (SidePanelHistory.KillRow r : rooms)
		{
			rows.add(SidePanelModel.tripRow(r));
		}
		in.tripRows = rows;
		in.luckScope = LedgerRows.of(SidePanelModel.withoutRuns(rooms));
		return in;
	}
}

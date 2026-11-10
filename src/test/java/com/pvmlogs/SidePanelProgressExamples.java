package com.pvmlogs;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.runelite.client.util.Filepath;

/**
 * T-190: History's progress examples: a record (T-187) holding the history's counted kills and older ones history has
 * already dropped, and the History source that reads it with Celebrations on. The Vardorvis example (13 kills in
 * history, 16 tracked) and a farmed Araxxor (20 in history, 209 tracked, shaped on Eric's spread in T-186).
 */
final class SidePanelProgressExamples
{
	private SidePanelProgressExamples()
	{
	}

	/** A record in a fresh folder: {@code older} kills before history's, then every counted kill in it, oldest first. */
	static BossRecords records(List<Fight> history, String boss, int maxHp, int older, double[] olderClock,
		double[] olderExecution)
	{
		final BossRecords records;
		try
		{
			records = new BossRecords(Filepath.Unchecked.getRooted(Files.createTempDirectory("records")), new Gson());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		final LedgerHistory.Like like = new LedgerHistory.Like(boss, maxHp, true);
		for (int i = 0; i < older; i++)
		{
			final double clock = olderClock[i % olderClock.length];
			records.record(new BossRecords.KillFigures(like, 1_000_000L + i * 1000L, clock,
				olderExecution[i % olderExecution.length], clock + 3 - (i % 7), 0.2 + 0.6 * ((i * 37) % 100) / 100.0,
				null));
		}
		final Set<Fight> none = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Encounter room : LedgerHistory.rooms(history, boss))
		{
			if (!LedgerHistory.counts(room, none))
			{
				continue;
			}
			final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(room));
			records.record(new BossRecords.KillFigures(LedgerHistory.Like.of(room), room.getStartMillis(), n.clock,
				n.execution, n.expectedDuration, n.percentile, null));
		}
		return records;
	}

	/** The Vardorvis example's record: three older kills before history's 12 counted ones (the 26 Oct one isn't). */
	static BossRecords vardorvis()
	{
		return records(SidePanelHistoryExamples.history(), "Vardorvis", 700, 3, new double[]{126, 103, 119},
			new double[]{0.95, 0.97, 0.93});
	}

	/** Twenty Araxxor kills today, shaped on the Vardorvis example's, at Araxxor's 1,020 HP. */
	static List<Fight> araxxorHistory()
	{
		final List<Fight> oldestFirst = new ArrayList<>();
		final double[] seconds = {96, 88, 102, 91, 85, 99, 93, 87, 104, 90, 94, 86, 98, 89, 92, 84, 97, 95, 88, 86};
		final int[] luck = {-20, 25, -40, 5, 45, -10, 15, 0, -35, 30, 10, 40, -25, 20, 5, 50, -15, -5, 25, 35};
		final int[] lost = {3, 2, 6, 2, 1, 4, 2, 1, 7, 2, 3, 1, 4, 2, 2, 0, 3, 3, 1, 1};
		long end = SidePanelExamples.at(12, 0);
		for (int i = 0; i < seconds.length; i++)
		{
			end += (long) (seconds[i] * 1000) + 45_000L;
			oldestFirst.add(araxxor(end, seconds[i], luck[i], lost[i]));
		}
		final List<Fight> newestFirst = new ArrayList<>(oldestFirst);
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	private static Fight araxxor(long end, double seconds, int luck, int lost)
	{
		final Fight f = new Fight("Araxxor", NpcIds.GOBLIN, 1, 1020, end - (long) (seconds * 1000), null, 0);
		final int ticks = (int) Math.round(seconds / 0.6);
		final int attacks = Math.max(1, (ticks - lost) / 4);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, 0.99);
			f.recordExpected(42, 0.84, 24, 160);
			f.recordAttackResolved(true, true, 0.99, 1.0, 0.012, 0);
		}
		for (int i = 0; i < lost; i++)
		{
			f.recordTickLost(false);
		}
		for (int i = attacks + lost; i < ticks; i++)
		{
			f.recordTickSpent();
		}
		final int landed = Math.max(1, (int) Math.round(attacks * 0.84));
		final int total = attacks * 24 + luck;
		for (int i = 0; i < landed; i++)
		{
			f.recordDamageDealt(i < landed - 1 ? total / landed : total - (landed - 1) * (total / landed), end, true);
		}
		f.recordThrallDamage(50);
		f.recordHitTaken(3, false, end);
		f.end(true, end);
		return f;
	}

	/** Eric's Araxxor record, shaped: 189 older kills (T-186's quantiles), then the twenty today. */
	static BossRecords araxxor()
	{
		return records(araxxorHistory(), "Araxxor", 1020, 189,
			new double[]{65.4, 78.6, 81.0, 88.8, 94.8, 106.2, 114.6, 120.6, 147.0, 92.4, 99.0, 85.2},
			new double[]{0.824, 0.886, 0.903, 0.939, 0.966, 0.981, 0.991, 0.995, 0.999, 0.958, 0.972, 0.947});
	}

	/** History's source on {@code history}, with Celebrations on and this record. */
	static SidePanelHistoryTab.Source source(List<Fight> history, BossRecords records)
	{
		final SidePanelHistoryTab.Source base = SidePanelPictures.source(history);
		return new SidePanelHistoryTab.Source()
		{
			public List<Fight> history()
			{
				return base.history();
			}

			public SidePanelHistory.Versions versions()
			{
				return base.versions();
			}

			public long sessionStart()
			{
				return base.sessionStart();
			}

			public long now()
			{
				return base.now();
			}

			public java.time.ZoneId zone()
			{
				return base.zone();
			}

			public void exportNpc(String name)
			{
			}

			@Override
			public boolean celebrations()
			{
				return true;
			}

			@Override
			public BossRecords.View record(LedgerHistory.Like like)
			{
				return records.view(like);
			}
		};
	}
}

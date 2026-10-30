package com.pvmlogs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T-102: a history with every kind of name in Eric's (read from his {@code history.json} on 2026-10-29, names and ids
 * only): bosses, a grouped boss with adds, the araxyte rooms saved on their own before T-091 (ungrouped, and as runs of
 * monsters between T-089 and T-091), the Deranged archaeologist, demon runs, and lone monsters. Illustrative figures.
 * Shared by {@link SidePanelBossesTest} and {@link SidePanelPictures}.
 */
final class SidePanelBossesExamples
{
	static final int ARAXXOR = 13668;
	static final int ACIDIC = 13675;
	static final int MIRRORBACK = 13671;
	static final int DERANGED = 7806;
	static final int ARAXYTE = 11175;
	static final int REANIMATED = 7038;
	static final int HESPORI = 8583;
	static final int FLOWER = 8584;
	static final int LEVIATHAN = 12214;

	private static final Gson GSON = new Gson();

	private SidePanelBossesExamples()
	{
	}

	/** A fight on this target: {@code attacks} attacks expected to deal 14 each, dealing {@code damage}. */
	static Fight fight(String name, int id, int index, long start, int attacks, int damage, boolean died)
	{
		final Fight f = new Fight(name, id, index, 500, start, null, 0);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, 0.9);
			f.recordExpected(45, 0.6, 14, 180);
			f.recordAttackResolved(true, true, 0.97, 1.0, 0.01, 0);
			for (int k = 0; k < 3; k++)
			{
				f.recordTickSpent();
			}
		}
		f.recordDamageDealt(damage, start + attacks * 2400L, true);
		f.end(died, start + attacks * 2400L);
		return f;
	}

	/** The same fight as saved before its group or its run existed: no group, or a run of its own name (T-089). */
	static Fight saved(Fight f, String groupName, boolean monsterRoom)
	{
		final JsonObject o = GSON.toJsonTree(f).getAsJsonObject();
		o.remove("groupName");
		if (groupName != null)
		{
			o.addProperty("groupName", groupName);
		}
		o.addProperty("monsterRoom", monsterRoom);
		return GSON.fromJson(o, Fight.class);
	}

	/** Eric's mix, newest first. */
	static List<Fight> mix()
	{
		final List<Fight> oldestFirst = new ArrayList<>();
		long t = SidePanelExamples.at(9, 0);
		// The araxyte rooms saved on their own before T-091: ungrouped, then as runs of monsters (T-089).
		oldestFirst.add(saved(fight("Acidic Araxyte", ACIDIC, 40, t, 3, 70, true), null, false));
		t += 60_000L;
		final Fight mirrorback = saved(fight("Mirrorback Araxyte", MIRRORBACK, 41, t, 3, 70, true), "Mirrorback Araxyte",
			true);
		mirrorback.recordKill();
		mirrorback.closeRoom();
		oldestFirst.add(mirrorback);
		t += 120_000L;
		// The Deranged archaeologist, twice.
		for (int i = 0; i < 2; i++)
		{
			oldestFirst.add(fight("Deranged archaeologist", DERANGED, 50 + i, t, 20, 300, true));
			t += 90_000L;
		}
		// Hespori and its flowers, closed at its death.
		oldestFirst.add(fight("Flower", FLOWER, 60, t, 1, 10, true));
		oldestFirst.add(fight("Flower", FLOWER, 61, t + 5_000L, 1, 10, true));
		final Fight hespori = fight("Hespori", HESPORI, 62, t + 10_000L, 30, 300, true);
		hespori.closeRoom();
		oldestFirst.add(hespori);
		t += 4 * 60_000L;
		// An Araxxor kill with an Acidic araxyte in its room (T-091).
		oldestFirst.add(fight("Araxxor", ARAXXOR, 70, t, 20, 400, false));
		oldestFirst.add(fight("Acidic Araxyte", ACIDIC, 71, t + 50_000L, 3, 70, true));
		final Fight araxxor = fight("Araxxor", ARAXXOR, 70, t + 60_000L, 30, 620, true);
		araxxor.closeRoom();
		oldestFirst.add(araxxor);
		t += 5 * 60_000L;
		// Lone monsters: an Araxyte (the slayer one) and a Reanimated abyssal.
		oldestFirst.add(fight("Araxyte", ARAXYTE, 80, t, 6, 120, true));
		t += 60_000L;
		oldestFirst.add(fight("Reanimated abyssal", REANIMATED, 81, t, 2, 30, false));
		t += 60_000L;
		// The Leviathan, twice, and Vardorvis, twice.
		for (int i = 0; i < 2; i++)
		{
			oldestFirst.add(fight("The Leviathan", LEVIATHAN, 90 + i, t, 40, 900, true));
			t += 3 * 60_000L;
		}
		// The Sire.
		oldestFirst.addAll(SidePanelHistoryExamples.sireRoom(t));
		t += 4 * 60_000L;
		// A demon run of 12.
		oldestFirst.addAll(SidePanelRunExamples.runFights(t, 12, 12, 7));
		t += 6 * 60_000L;
		for (int i = 0; i < 2; i++)
		{
			t += 3 * 60_000L;
			oldestFirst.add(SidePanelHistoryExamples.vardorvis(t, 118, 10 * i, true, 10, 140));
		}
		final List<Fight> newestFirst = new ArrayList<>(oldestFirst);
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/** Bosses alone: the fixtures' Vardorvis and Sire kills, no monster. */
	static List<Fight> bossesOnly()
	{
		final List<Fight> out = new ArrayList<>();
		for (Fight f : SidePanelHistoryExamples.history())
		{
			if (!"Abyssal demon".equals(f.getTargetName()))
			{
				out.add(f);
			}
		}
		return out;
	}

	/** An Araxxor attempt the player died in (T-100: {@code roomEnd = "died"} on its last fight). */
	static Encounter diedRoom()
	{
		final Fight f = fight("Araxxor", ARAXXOR, 70, SidePanelExamples.at(15, 0), 12, 260, false);
		f.markPlayerDied();
		f.closeRoomWithoutKill(SidePanelHistory.DIED);
		final Encounter room = new Encounter("Araxxor", null, f.getStartMillis());
		room.add(f);
		return room;
	}

	/** An Araxxor room left alive (T-100: {@code roomEnd = "left"}). */
	static Encounter leftRoom()
	{
		final Fight f = fight("Araxxor", ARAXXOR, 70, SidePanelExamples.at(15, 10), 12, 260, false);
		f.closeRoomWithoutKill("left");
		final Encounter room = new Encounter("Araxxor", null, f.getStartMillis());
		room.add(f);
		return room;
	}
}

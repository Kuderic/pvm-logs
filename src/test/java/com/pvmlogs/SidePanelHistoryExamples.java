package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Saved fights for the History and Trip tabs (T-034): made with the plugin's
 * own {@link Fight} recording calls, so every figure on them comes out of the
 * same code a real kill's does. Illustrative, in the canvas's spirit (spec §10):
 * a dozen Vardorvis kills today, an older one from before T-028 (no spread kept,
 * so its luck is in seconds), two Sire rooms with their respiratory systems,
 * spawn and scion, and some Abyssal demons, which aren't a boss.
 */
final class SidePanelHistoryExamples
{
	static final int SIRE_ASLEEP = 5886;
	static final int SIRE_WALKING = 5889;
	static final int SIRE_LAST = 5891;
	static final int RESPIRATORY = 5914;
	static final int SPAWN = 5916;
	static final int SCION = 5918;
	/** "Now" on the renders: 15:40 today. */
	static final long NOW = SidePanelExamples.at(15, 40);

	private SidePanelHistoryExamples()
	{
	}

	/**
	 * A Vardorvis kill (post-quest, solo) ending at {@code end}: one attack every
	 * four ticks expected to deal 12, {@code luck} dealt beyond that, then
	 * {@code lost} idle ticks.
	 */
	static Fight vardorvis(long end, double seconds, int luck, boolean spread, int lost, int taken)
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, end - (long) (seconds * 1000), null, 0);
		final int ticks = (int) Math.round(seconds / 0.6);
		final int attacks = Math.max(1, (ticks - lost) / 4);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(false, 0.92);
			if (spread)
			{
				f.recordExpected(40, 0.55, 12, 140);
			}
			else
			{
				f.recordExpected(40, 0.55, 12);
			}
			f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
		}
		for (int i = 0; i < lost; i++)
		{
			f.recordTickLost(false);
		}
		for (int i = attacks + lost; i < ticks; i++)
		{
			f.recordTickSpent();
		}
		// About 55% of attacks land, the damage spread over them, the luck on the last.
		final int landed = Math.max(1, (int) Math.round(attacks * 0.55));
		final int total = attacks * 12 + luck;
		for (int i = 0; i < landed; i++)
		{
			f.recordDamageDealt(i < landed - 1 ? total / landed : total - (landed - 1) * (total / landed), end, true);
		}
		f.recordThrallDamage(60);
		// Hits of 8 to 31 until the damage taken is reached.
		int left = taken;
		for (int i = 0; left > 0; i++)
		{
			final int hit = Math.min(left, 8 + (i * 7) % 24);
			f.recordHitTaken(hit, false, end);
			left -= hit;
		}
		f.end(true, end);
		return f;
	}

	/** One Sire room: phase one, its respiratory systems, a spawn and a scion, and the kill, closed on the loot. */
	static List<Fight> sireRoom(long start)
	{
		final List<Fight> room = new ArrayList<>();
		long t = start;
		room.add(fight("Abyssal Sire", SIRE_ASLEEP, 400, t, 6, 0, false));
		t += 8_000;
		for (int i = 0; i < 4; i++)
		{
			room.add(fight("Respiratory system", RESPIRATORY, 50, t, 2, 50, true));
			t += 4_000;
		}
		room.add(fight("Abyssal Sire", SIRE_WALKING, 400, t, 10, 0, false));
		t += 20_000;
		room.add(fight("Spawn", SPAWN, 10, t, 1, 10, true));
		t += 2_000;
		room.add(fight("Scion", SCION, 100, t, 4, 100, true));
		t += 6_000;
		final Fight last = fight("Abyssal Sire", SIRE_LAST, 400, t, 12, 0, true);
		last.closeRoom();
		room.add(last);
		return room;
	}

	private static Fight fight(String name, int id, int hp, long start, int attacks, int damage, boolean died)
	{
		final Fight f = new Fight(name, id, 1, hp, start, null, 0);
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
		f.recordDamageDealt(damage > 0 ? damage : attacks * 14, start + attacks * 2400L, true);
		f.end(died, start + attacks * 2400L);
		return f;
	}

	/** Twelve kills today from 12:00, the 26 Oct kill, two Sire rooms and three demons: newest first. */
	static List<Fight> history()
	{
		final List<Fight> oldestFirst = new ArrayList<>();
		// 26 Oct, before T-028: luck in seconds only.
		oldestFirst.add(vardorvis(SidePanelExamples.at(21, 40) - 86_400_000L, 118, 30, false, 10, 140));
		final double[] seconds = {124, 112, 131, 119, 108, 127, 115, 121, 110, 129, 117, 137};
		final int[] luck = {-20, 25, -40, 5, 45, -10, 15, 0, 35, -30, 10, -90};
		final int[] lost = {12, 8, 20, 10, 6, 16, 9, 11, 7, 18, 10, 32};
		final int[] taken = {150, 132, 171, 144, 120, 160, 139, 148, 128, 166, 141, 186};
		long end = SidePanelExamples.at(12, 0);
		for (int i = 0; i < seconds.length; i++)
		{
			if (i == 4)
			{
				oldestFirst.addAll(sireRoom(end));
				end += 3 * 60_000L;
				for (int d = 0; d < 3; d++)
				{
					oldestFirst.add(fight("Abyssal demon", NpcIds.GOBLIN, 150, end, 5, 150, true));
					end += 30_000L;
				}
				oldestFirst.addAll(sireRoom(end));
				end += 3 * 60_000L;
			}
			end += (long) (seconds[i] * 1000) + 60_000L;
			oldestFirst.add(vardorvis(end, seconds[i], luck[i], true, lost[i], taken[i]));
		}
		final List<Fight> newestFirst = new ArrayList<>(oldestFirst);
		Collections.reverse(newestFirst);
		return newestFirst;
	}
}

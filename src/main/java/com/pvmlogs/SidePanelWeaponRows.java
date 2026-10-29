package com.pvmlogs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Damage rows' sums kept between snapshots (T-046), so building them
 * costs the same at minute 10 as at minute 1. An attack can still change for a
 * few ticks after it's booked (its expectation arrives, a splat lands late), so
 * the sums hold the attacks more than {@link #SETTLE_TICKS} behind the scope's
 * last tick, added once each; every build adds the newer ones on top of a copy.
 * The result is what {@link SidePanelModel#weaponRows} gives for the whole list.
 * Client thread only.
 */
final class SidePanelWeaponRows
{
	/** How far behind the last tick an attack is taken as final: 60 s. */
	static final int SETTLE_TICKS = 100;

	private Object scope;
	private int settledCount;
	private FightTimeline.Attack lastSettled;
	private final Map<String, SidePanelModel.WeaponRow> settled = new LinkedHashMap<>();
	/** Attacks added up on the last build, for tests: the tail, plus any newly settled. */
	private int added;

	/** The rows for {@code view} of the scope {@code scopeKey}: grouped by name shown, in the order first thrown. */
	List<SidePanelModel.WeaponRow> rows(Object scopeKey, FightTimeline.View view)
	{
		added = 0;
		if (view == null)
		{
			reset(null);
			return new ArrayList<>();
		}
		final List<FightTimeline.Attack> attacks = view.attacks;
		// Another scope, or a list that isn't the one the sums came from (shorter, or another attack where the last
		// settled one was): start over.
		if (!scopeKey.equals(scope) || attacks.size() < settledCount
			|| settledCount > 0 && attacks.get(settledCount - 1) != lastSettled)
		{
			reset(scopeKey);
		}
		final int cutoff = view.startTick + view.states.length - 1 - SETTLE_TICKS;
		while (settledCount < attacks.size() && attacks.get(settledCount).tick <= cutoff)
		{
			lastSettled = attacks.get(settledCount);
			add(settled, lastSettled, ticks(view, settledCount));
			settledCount++;
			added++;
		}
		final Map<String, SidePanelModel.WeaponRow> rows = new LinkedHashMap<>();
		for (Map.Entry<String, SidePanelModel.WeaponRow> e : settled.entrySet())
		{
			rows.put(e.getKey(), copy(e.getValue()));
		}
		for (int i = settledCount; i < attacks.size(); i++)
		{
			add(rows, attacks.get(i), ticks(view, i));
			added++;
		}
		return new ArrayList<>(rows.values());
	}

	int added()
	{
		return added;
	}

	private void reset(Object scopeKey)
	{
		scope = scopeKey;
		settledCount = 0;
		lastSettled = null;
		settled.clear();
	}

	/**
	 * T-085: the ticks spent on attack {@code i} of {@code view}: its own tick and the cooldown after it, up to the next
	 * attack. Only the ticks the timeline drew as the attack, the special or cooldown count: lost ticks, eating and a
	 * boss's mechanic don't. So a weapon's ticks are its attacks times its speed, and a switch before the cooldown ends
	 * gives the earlier weapon only the ticks it held. Over all the attacks they add up to the time spent attacking.
	 * Ticks are where each attack was booked, which trails the throw by its weapon's lag: switching between weapons of
	 * different lags moves a tick between them.
	 */
	static int ticks(FightTimeline.View view, int i)
	{
		final List<FightTimeline.Attack> attacks = view.attacks;
		final int from = attacks.get(i).tick - view.startTick;
		final int to = Math.min(view.states.length, i + 1 < attacks.size() ? attacks.get(i + 1).tick - view.startTick
			: view.states.length);
		int n = 0;
		for (int k = Math.max(0, from); k < to; k++)
		{
			final byte s = view.states[k];
			if (s == FightTimeline.ATTACK || s == FightTimeline.SPECIAL || s == FightTimeline.COOLDOWN)
			{
				n++;
			}
		}
		return n;
	}

	/** One attack into its row with no ticks: for rows read without their view. */
	static void add(Map<String, SidePanelModel.WeaponRow> rows, FightTimeline.Attack a)
	{
		add(rows, a, 0);
	}

	/** One attack into its row, as {@link SidePanelModel#weaponRows} adds it, with the ticks spent on it (T-085). */
	static void add(Map<String, SidePanelModel.WeaponRow> rows, FightTimeline.Attack a, int ticks)
	{
		final String label = LedgerRows.attackLabel(a.key);
		final String name = label == null ? a.key : label;
		// Grouped by the name shown, not the key booked (T-071): a kill saved before casts were named by the spell alone
		// has "Kodai wand (Ice Barrage)" and "Harmonised nightmare staff (Ice Barrage)", one row of Ice Barrage.
		final SidePanelModel.WeaponRow w = rows.computeIfAbsent(a.special ? name + " (special)" : name,
			k -> new SidePanelModel.WeaponRow());
		w.name = name;
		w.special = a.special;
		w.attacks++;
		w.damage += a.damage;
		// T-085: the time spent on it, and its details.
		w.ticks += ticks;
		if (a.hits > 0)
		{
			w.landed++;
		}
		if (a.landedChance >= 0)
		{
			w.landedExpected += a.landedChance;
			w.landedKnown++;
		}
		w.maxHit = Math.max(w.maxHit, a.maxHit);
		w.biggest = Math.max(w.biggest, a.biggest);
		if (a.mean >= 0)
		{
			w.expected += a.mean;
			w.expectedKnown = true;
		}
		if (a.itemId >= 0)
		{
			w.itemId = a.itemId;
		}
		if (a.spell != null)
		{
			w.spell = a.spell;
		}
		if (a.style != FightTimeline.Style.UNKNOWN)
		{
			w.style = a.style;
		}
	}

	private static SidePanelModel.WeaponRow copy(SidePanelModel.WeaponRow r)
	{
		final SidePanelModel.WeaponRow c = new SidePanelModel.WeaponRow();
		c.name = r.name;
		c.special = r.special;
		c.attacks = r.attacks;
		c.damage = r.damage;
		c.expected = r.expected;
		c.expectedKnown = r.expectedKnown;
		c.ticks = r.ticks;
		c.landed = r.landed;
		c.landedExpected = r.landedExpected;
		c.landedKnown = r.landedKnown;
		c.maxHit = r.maxHit;
		c.biggest = r.biggest;
		c.itemId = r.itemId;
		c.spell = r.spell;
		c.style = r.style;
		return c;
	}
}

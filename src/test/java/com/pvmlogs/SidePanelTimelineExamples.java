package com.pvmlogs;

import java.util.Collections;
import net.runelite.api.gameval.ItemID;

/**
 * Fights with real {@link FightTimeline}s for the damage rows, chart and tick
 * strip (T-036): driven through {@link Fight}'s own recording calls and the
 * timeline's own hooks, as the plugin drives them, so every sum is the fight's.
 * Sized to the canvas's illustrative split (spec §10): the 2:17 kill's tentacle
 * 612 of 690 expected and two claw specs 76 of 88; the 1:13 fight's 330 of 372
 * and one spec 40 of 46.
 */
final class SidePanelTimelineExamples
{
	private SidePanelTimelineExamples()
	{
	}

	/** A fight and its timeline, and the room holding it. */
	static final class Built
	{
		final Fight fight;
		final FightTimeline.Store store;
		final Encounter room;

		Built(Fight fight, FightTimeline.Store store, Encounter room)
		{
			this.fight = fight;
			this.store = store;
			this.room = room;
		}

		FightTimeline.View view()
		{
			return FightTimeline.room(room, store);
		}
	}

	/**
	 * A tentacle fight of {@code ticks}: an attack every four ticks, specials with
	 * the claws at the given attack numbers, idle ticks in {@code lost} spans and
	 * eating in {@code eating} spans ({start, length} pairs). Damage and expected
	 * damage are spread over the attacks to the totals given.
	 */
	static Built fight(int ticks, int[] specials, int[] lost, int[] eating, int tentacleDamage, double tentacleExpected,
		int specDamage, double specExpected, double variancePerAttack)
	{
		return fight("Vardorvis", "Burning claws", ItemID.BONE_CLAWS, ticks, specials, lost, eating, tentacleDamage,
			tentacleExpected, specDamage, specExpected, variancePerAttack);
	}

	/** As above, at this boss and with this special (T-076's Sire with Emberlight). */
	static Built fight(String boss, String specName, int specItem, int ticks, int[] specials, int[] lost, int[] eating,
		int tentacleDamage, double tentacleExpected, int specDamage, double specExpected, double variancePerAttack)
	{
		return fight(boss, specName, specItem, ticks, specials, lost, eating, new int[0], tentacleDamage, tentacleExpected,
			specDamage, specExpected, variancePerAttack);
	}

	/** As above, with idle ticks a boss's mechanic forced in {@code mechanics} spans (T-074; T-081's Leviathan). */
	static Built fight(String boss, String specName, int specItem, int ticks, int[] specials, int[] lost, int[] eating,
		int[] mechanics, int tentacleDamage, double tentacleExpected, int specDamage, double specExpected,
		double variancePerAttack)
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		store.setClock(() -> clock[0]);
		final Fight f = new Fight(boss, NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		store.attach(f);
		final FightTimeline line = store.of(f);

		// First count the attacks the plan makes, so the totals can be spread over them.
		int attacks = 0;
		int cooldown = 0;
		for (int t = 0; t < ticks; t++)
		{
			if (!in(t, lost) && !in(t, eating) && !in(t, mechanics) && cooldown == 0)
			{
				attacks++;
				cooldown = 4;
			}
			cooldown = Math.max(0, cooldown - 1);
		}
		final int specs = specials.length;
		final int normal = attacks - specs;
		int tentacleLeft = tentacleDamage;
		int specLeft = specDamage;
		int normalsDone = 0;
		int specsDone = 0;
		int n = 0;
		cooldown = 0;
		for (int t = 0; t < ticks; t++)
		{
			clock[0] = t;
			if (in(t, mechanics))
			{
				f.recordTickMechanic(true);
				cooldown = 0;
				continue;
			}
			if (in(t, lost) || in(t, eating))
			{
				f.recordTickLost(in(t, eating));
				cooldown = 0;
				continue;
			}
			if (cooldown > 0)
			{
				f.recordTickSpent();
				cooldown--;
				continue;
			}
			n++;
			final boolean special = contains(specials, n);
			f.recordAttackMade(false, 1.0, false);
			line.describeLatest(special ? specName : "Abyssal tentacle", special ? specItem
				: ItemID.ABYSSAL_TENTACLE, null, FightTimeline.Style.MELEE, t);
			if (special)
			{
				line.markLatestSpecial();
			}
			f.recordExpected(49, 0.55, special ? specExpected / specs : tentacleExpected / normal, variancePerAttack);
			f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
			final int amount;
			if (special)
			{
				specsDone++;
				amount = specsDone == specs ? specLeft : specDamage / specs;
				specLeft -= amount;
			}
			else
			{
				normalsDone++;
				// Roughly every other attack lands; the last one takes what's left.
				amount = normalsDone == normal ? tentacleLeft : (normalsDone % 2 == 0 ? 0 : Math.min(tentacleLeft,
					2 * tentacleDamage / normal));
				tentacleLeft -= amount;
			}
			if (amount > 0)
			{
				f.recordDamageDealt(amount, 0L, true);
				line.attributeSplat(Integer.MIN_VALUE);
			}
			cooldown = 3;
		}
		f.recordThrallDamage(80);
		final Encounter room = new Encounter(boss, null, 0L);
		room.add(f);
		return new Built(f, store, room);
	}

	/**
	 * T-085: one stretch of a fight's plan: {@code attacks} with one weapon at {@code speed} ticks, each expected to deal
	 * {@code expected} with a {@code maxHit} and a chance {@code accuracy} to land; {@code damage} is what each dealt in
	 * turn (0 for a miss), and {@code lostAfter} idle ticks follow the stretch.
	 */
	static final class Stretch
	{
		final String name;
		final int itemId;
		final FightTimeline.Style style;
		final boolean special;
		final int speed;
		final int[] damage;
		final double expected;
		final int maxHit;
		final double accuracy;
		final int lostAfter;

		Stretch(String name, int itemId, FightTimeline.Style style, boolean special, int speed, int[] damage,
			double expected, int maxHit, double accuracy, int lostAfter)
		{
			this.name = name;
			this.itemId = itemId;
			this.style = style;
			this.special = special;
			this.speed = speed;
			this.damage = damage;
			this.expected = expected;
			this.maxHit = maxHit;
			this.accuracy = accuracy;
			this.lostAfter = lostAfter;
		}
	}

	/** T-085: a fight played stretch by stretch, through the plugin's own calls, as {@link #fight} plays one weapon. */
	static Built plan(String boss, Stretch... stretches)
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		store.setClock(() -> clock[0]);
		final Fight f = new Fight(boss, NpcIds.GOBLIN, 1, 900, 0L, null, 0);
		store.attach(f);
		final FightTimeline line = store.of(f);
		int t = 0;
		for (Stretch st : stretches)
		{
			for (int amount : st.damage)
			{
				clock[0] = t;
				f.recordAttackMade(false, 1.0, false);
				line.describeLatest(st.name, st.itemId, null, st.style, t);
				if (st.special)
				{
					line.markLatestSpecial();
				}
				f.recordExpected(st.maxHit, st.accuracy, st.expected, st.expected * st.expected / 3);
				f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
				if (amount > 0)
				{
					f.recordDamageDealt(amount, 0L, true);
					line.attributeSplat(Integer.MIN_VALUE);
				}
				for (int k = 1; k < st.speed; k++)
				{
					clock[0] = ++t;
					f.recordTickSpent();
				}
				t++;
			}
			for (int k = 0; k < st.lostAfter; k++)
			{
				clock[0] = t++;
				f.recordTickLost(false);
			}
		}
		final Encounter room = new Encounter(boss, null, 0L);
		room.add(f);
		return new Built(f, store, room);
	}

	/** {@code n} hits of the pattern, cycled: {@code hits(10, 30, 0, 22)}. */
	static int[] hits(int n, int... pattern)
	{
		final int[] out = new int[n];
		for (int i = 0; i < n; i++)
		{
			out[i] = pattern[i % pattern.length];
		}
		return out;
	}

	private static boolean in(int t, int[] spans)
	{
		for (int i = 0; i + 1 < spans.length; i += 2)
		{
			if (t >= spans[i] && t < spans[i] + spans[i + 1])
			{
				return true;
			}
		}
		return false;
	}

	private static boolean contains(int[] values, int v)
	{
		for (int x : values)
		{
			if (x == v)
			{
				return true;
			}
		}
		return false;
	}

	/** The 2:17 kill: 228 ticks, two specs, 25 idle and 7 eating ticks. */
	static Built kill()
	{
		return fight(228, new int[]{11, 31}, new int[]{60, 10, 180, 15}, new int[]{130, 7}, 612, 690, 76, 88, 233);
	}

	/** The 1:13 fight: 122 ticks, one spec, 10 idle ticks. */
	static Built live()
	{
		return fight(122, new int[]{11}, new int[]{60, 10}, new int[0], 330, 372, 40, 46, 222);
	}

	/** An input for a room built from a real fight: its own scope, its view. */
	static SidePanelModel.Input input(Built b, boolean ended)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, b.room);
		in.ended = ended;
		in.killed = ended;
		in.view = b.view();
		return in;
	}

	/** No room: an empty view, as for a fight from before the panel loaded. */
	static FightTimeline.View none()
	{
		return FightTimeline.view(Collections.emptyList(), new FightTimeline.Store());
	}
}

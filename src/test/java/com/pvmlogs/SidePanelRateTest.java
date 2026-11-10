package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-200 (Eric, 2026-11-10: "Exp dps in the side bar is too high on the first few hits of the fight"): a rate on the
 * panel counts every landed attack's whole cooldown, so the first hits read the gear's rate, not up to 2.5 times it.
 */
public class SidePanelRateTest
{
	/** Each attack's expected damage and the damage it deals: a 5-tick weapon's gear rate is 30 / 3 s = 10.0. */
	private static final double EACH = 30;

	/**
	 * A melee fight tick by tick, as the plugin books it: the attack (one scored tick) when it's thrown, its expected
	 * damage and its splat one tick later ({@code MELEE_BOOKING_LAG}), a scored cooldown tick on every other tick.
	 */
	static final class Sim
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		final Fight fight = new Fight("Araxxor", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		final FightTimeline line;
		final Encounter room = new Encounter("Araxxor", null, 0L);
		/** Attacks waiting to land: the tick each lands on, and its damage. */
		private final List<int[]> landing = new ArrayList<>();

		Sim()
		{
			store.setClock(() -> clock[0]);
			store.attach(fight);
			line = store.of(fight);
			room.add(fight);
		}

		/** One tick: throws an attack with {@code key} at {@code speed} when {@code throwIt}, else a cooldown tick. */
		void tick(boolean throwIt, String key, int speed, int damage)
		{
			final int t = clock[0];
			if (throwIt)
			{
				fight.recordAttackMade(false, 1.0, false);
				line.describeLatest(key, ItemID.ABYSSAL_TENTACLE, null, FightTimeline.Style.MELEE, t, speed);
				landing.add(new int[]{t + PvmPerformancePlugin.MELEE_BOOKING_LAG, damage});
			}
			else
			{
				fight.recordTickSpent();
			}
			for (int i = 0; i < landing.size(); i++)
			{
				final int[] l = landing.get(i);
				if (l[0] == t)
				{
					fight.recordExpected(40, 0.8, EACH, 100);
					if (l[1] > 0)
					{
						fight.recordDamageDealt(l[1], 0L, true);
						line.attributeSplat(Integer.MIN_VALUE);
					}
					landing.remove(i--);
				}
			}
			clock[0]++;
		}

		/** {@code ticks} of a {@code speed}-tick weapon from now, attacking on its cooldown. */
		void attack(int ticks, String key, int speed)
		{
			for (int k = 0; k < ticks; k++)
			{
				tick(k % speed == 0, key, speed, (int) EACH);
			}
		}

		SidePanelModel.Input input(boolean ended)
		{
			final SidePanelModel.Input in = new SidePanelModel.Input();
			in.zone = SidePanelExamples.ZONE;
			in.kind = SidePanelModel.Kind.ROOM;
			SidePanelModel.fillRoom(in, room);
			in.ended = ended;
			in.killed = ended;
			in.view = FightTimeline.room(room, store);
			in.celebrations = true;
			return in;
		}

		PanelSnapshot.FightTab tab(boolean ended)
		{
			return SidePanelModel.fight(input(ended));
		}
	}

	private static double num(String s)
	{
		return Double.parseDouble(s);
	}

	// ---- reproduce it ----

	@Test
	public void theFirstHitReadsTheGearsRateNot2point5TimesIt()
	{
		final Sim s = new Sim();
		// The throw, then the tick it lands on.
		s.tick(true, "Abyssal tentacle", 5, (int) EACH);
		s.tick(false, "Abyssal tentacle", 5, 0);
		final SidePanelModel.Input in = s.input(false);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		// What Numbers reads, unchanged: 30 over the 2 scored ticks so far, 2.5 times the gear's 10.0.
		assertEquals(25.0, n.expectedDps, 1e-9);
		assertEquals(2.5, n.expectedDps / 10.0, 1e-9);
		// The card: the gear's rate.
		final PanelSnapshot.KillCard c = s.tab(false).card;
		assertEquals("10.0", c.expDps);
		assertEquals("10.0", c.dps);
	}

	@Test
	public void noSawtoothOverTenHits()
	{
		final Sim s = new Sim();
		for (int t = 0; t < 50; t++)
		{
			s.tick(t % 5 == 0, "Abyssal tentacle", 5, (int) EACH);
			if (t == 0)
			{
				continue;
			}
			final PanelSnapshot.KillCard c = s.tab(false).card;
			assertEquals("tick " + t, 10.0, num(c.expDps), 0.05);
			assertEquals("tick " + t, 10.0, num(c.dps), 0.05);
		}
		// After the kill the same, with no jump.
		assertEquals("10.0", s.tab(true).card.expDps);
	}

	@Test
	public void beforeTheFirstHitLandsThereIsNoRate()
	{
		final Sim s = new Sim();
		s.tick(true, "Abyssal tentacle", 5, (int) EACH);
		final PanelSnapshot.KillCard c = s.tab(false).card;
		// Thrown, not landed: no DPS and no exp (the card's line reads "DPS —"), as its damage isn't booked yet.
		assertNull(c.expDps);
		assertNull(c.dps);
		assertTrue(c.dpsTip, c.dpsTip.contains("when your first attack lands"));
	}

	@Test
	public void aSwitchMidCooldownGivesTheFirstWeaponOnlyTheTicksItHeld()
	{
		final Sim s = new Sim();
		// A 5-tick weapon at 0, switched at 2 to a 3-tick one, which then attacks on its cooldown.
		s.tick(true, "Abyssal tentacle", 5, (int) EACH);
		s.tick(false, "Abyssal tentacle", 5, 0);
		for (int k = 0; k < 9; k++)
		{
			s.tick(k % 3 == 0, "Blowpipe", 3, (int) EACH);
		}
		final FightTimeline.View view = s.input(false).view;
		assertEquals(2, SidePanelWeaponRows.ticks(view, 0));
		assertEquals(3, SidePanelWeaponRows.ticks(view, 1));
		// The last blowpipe's whole cooldown, though only one of its ticks has passed... (thrown at 8, now 10: 3 ticks).
		assertEquals(3, SidePanelWeaponRows.ticks(view, view.attacks.size() - 1));
		// The card: 4 attacks over 2 + 3 · 3 = 11 ticks.
		final PanelSnapshot.KillCard c = s.tab(false).card;
		assertEquals(4 * EACH / (11 * LedgerRows.SECONDS_PER_TICK), num(c.expDps), 0.05);
	}

	@Test
	public void theWeaponRowsLastAttackCountsItsWholeCooldown()
	{
		final Sim s = new Sim();
		s.attack(10, "Abyssal tentacle", 5);
		// A third attack thrown at 10, landed at 11.
		s.tick(true, "Abyssal tentacle", 5, (int) EACH);
		s.tick(false, "Abyssal tentacle", 5, 0);
		final FightTimeline.View view = s.input(false).view;
		assertEquals(5, SidePanelWeaponRows.ticks(view, 2));
		final List<SidePanelModel.WeaponRow> rows = SidePanelModel.weaponRows(view);
		assertEquals(15, rows.get(0).ticks);
		final PanelSnapshot.Source row = s.tab(false).damage.rows.get(0);
		assertEquals("10.0 dps · exp 10.0", row.sub);
	}

	@Test
	public void anAddKilledInThreeHitsReadsTheGearsRateAfterTheKill()
	{
		final Sim s = new Sim();
		// Three hits, the kill on the last splat: the fight ends a tick after the third throw.
		s.attack(10, "Abyssal tentacle", 5);
		s.tick(true, "Abyssal tentacle", 5, (int) EACH);
		s.tick(false, "Abyssal tentacle", 5, 0);
		final PanelSnapshot.FightTab after = s.tab(true);
		assertEquals("10.0", after.card.expDps);
		assertEquals("10.0 dps · exp 10.0", after.damage.rows.get(0).sub);
		// Numbers is unchanged: 90 over the 12 scored ticks.
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s.input(true).scope);
		assertEquals(90 / (12 * LedgerRows.SECONDS_PER_TICK), n.expectedDps, 1e-9);
		assertEquals(12 * LedgerRows.SECONDS_PER_TICK, n.scoredClock, 1e-9);
	}

	@Test
	public void theLiveExpEqualsTheGearsRateFromTheFirstLandedHit()
	{
		// With no idle tick and nothing capped, exp is the attack's expected over its speed, as CombatCalc's.
		for (int speed : new int[]{2, 3, 4, 5, 6})
		{
			final Sim s = new Sim();
			s.tick(true, "w", speed, (int) EACH);
			s.tick(false, "w", speed, 0);
			for (int t = 2; t < 6 * speed; t++)
			{
				final double gear = EACH / (speed * LedgerRows.SECONDS_PER_TICK);
				assertEquals("speed " + speed + " tick " + t, gear, num(s.tab(false).card.expDps), 0.05);
				s.tick(t % speed == 0, "w", speed, (int) EACH);
			}
		}
	}

	@Test
	public void anAttackWithNoSpeedCountsAsBefore()
	{
		final Sim s = new Sim();
		s.tick(true, "Abyssal tentacle", 0, (int) EACH);
		s.tick(false, "Abyssal tentacle", 0, 0);
		assertEquals("25.0", s.tab(false).card.expDps);
		assertTrue(SidePanelWeaponRows.ticks(s.input(false).view, 0) == 2);
	}
}

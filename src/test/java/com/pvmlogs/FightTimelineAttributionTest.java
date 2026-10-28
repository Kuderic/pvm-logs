package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

/**
 * T-059: every splat on the attack that threw it, so the Damage card's "Not
 * matched to an attack" stays empty in ordinary fights. Driven as the plugin
 * drives a timeline: an attack booked on the tick the plugin books it, with the
 * thrown tick it books it as; each splat counted by Fight, then attributed by
 * the tick its burst knows the attack by.
 *
 * <p>The bow of faerdhinen's shape is from {@code pvm-debug-20261028-152144.log}
 * (read, not copied): the projectile surfaced on 60, the attack was booked on 60
 * as thrown on 58, its splat landed on 61. The burst took its thrown tick from
 * the projectile's start cycle, 60, which no attack had.
 */
public class FightTimelineAttributionTest
{
	private final int[] clock = {0};
	private final FightTimeline.Store store = new FightTimeline.Store();
	private final Fight fight;
	private final FightTimeline line;

	public FightTimelineAttributionTest()
	{
		store.setClock(() -> clock[0]);
		fight = new Fight("The Leviathan", 12214, 41557, 1000, 0L, null, 0);
		store.attach(fight);
		line = store.of(fight);
	}

	private void book(int tick, int thrownTick, String weapon)
	{
		clock[0] = tick;
		fight.recordAttackMade(false, 1.0, false);
		line.describeLatest(weapon, -1, null, FightTimeline.Style.RANGED, thrownTick);
	}

	private void splat(int tick, int amount, int key)
	{
		clock[0] = tick;
		fight.recordDamageDealt(amount, 0L, amount > 0);
		line.attributeSplat(key);
	}

	private int damageOf(String weapon)
	{
		int total = 0;
		for (FightTimeline.Attack a : line.attacks())
		{
			if (a.key.equals(weapon))
			{
				total += a.damage;
			}
		}
		return total;
	}

	// A projectile weapon of this speed as the plugin feeds it: surfacing on each attack tick, booked as thrown two before,
	// its splats landing a tick or two later and keyed as the burst now keys them (the booked tick).
	private int projectiles(String weapon, int speed, int attacks, int[] splatsEach, int from)
	{
		int dealt = 0;
		for (int i = 0; i < attacks; i++)
		{
			final int surfaced = from + i * speed;
			final int booked = PvmPerformancePlugin.projectileBookedTick(surfaced);
			book(surfaced, booked, weapon);
			for (int s = 0; s < splatsEach.length; s++)
			{
				splat(surfaced + 1, splatsEach[s], booked);
				dealt += splatsEach[s];
			}
		}
		return dealt;
	}

	@Test
	public void theBowOfFaerdhinensSplatsAreItsOwn()
	{
		// The log's: surfaced 60, booked as thrown 58, the splat (a 0) on 61; then the rest of a short fight.
		assertEquals(58, PvmPerformancePlugin.projectileBookedTick(60));
		final int dealt = projectiles("Bow of faerdhinen", 4, 12, new int[]{23}, 60);
		line.settle();
		assertEquals(dealt, damageOf("Bow of faerdhinen"));
		assertEquals(0, line.unattributedDamage());
		assertTrue(line.unmatched().isEmpty());
	}

	@Test
	public void theOldKeysStillFindTheirAttackNeverTheNext()
	{
		// Keyed as master keyed them (the surfacing tick, two after the booking): the next booking, and the fight's end, give
		// each waiting splat to the attack thrown before it.
		for (int i = 0; i < 5; i++)
		{
			final int surfaced = 60 + i * 4;
			book(surfaced, surfaced - 2, "Bow of faerdhinen");
			splat(surfaced + 1, 10 + i, surfaced);
		}
		line.settle();
		final List<FightTimeline.Attack> attacks = line.attacks();
		for (int i = 0; i < 5; i++)
		{
			assertEquals(10 + i, attacks.get(i).damage);
		}
		assertTrue(line.unmatched().isEmpty());
	}

	@Test
	public void everyProjectileKind()
	{
		int dealt = 0;
		// Crossbow (5 ticks), blowpipe (2), thrown weapons (3), a powered staff and a spell (4), and the tonalztics' two
		// glaives on one burst (3).
		dealt += projectiles("Zaryte crossbow", 5, 6, new int[]{30}, 100);
		dealt += projectiles("Toxic blowpipe", 2, 10, new int[]{12}, 140);
		dealt += projectiles("Dragon knife", 3, 6, new int[]{14, 9}, 170);
		dealt += projectiles("Tumeken's shadow", 4, 6, new int[]{41}, 200);
		dealt += projectiles("Trident (Ice Barrage)", 4, 4, new int[]{28}, 230);
		dealt += projectiles("Tonalztics of ralos", 3, 6, new int[]{20, 17}, 260);
		line.settle();
		assertEquals(30 * 6, damageOf("Zaryte crossbow"));
		assertEquals(12 * 10, damageOf("Toxic blowpipe"));
		assertEquals(23 * 6, damageOf("Dragon knife"));
		assertEquals(41 * 6, damageOf("Tumeken's shadow"));
		assertEquals(28 * 4, damageOf("Trident (Ice Barrage)"));
		assertEquals(37 * 6, damageOf("Tonalztics of ralos"));
		assertEquals(0, line.unattributedDamage());
		assertEquals(dealt, fight.getDamageDealt());
	}

	@Test
	public void meleeLandsBeforeItsBookingAsBefore()
	{
		// A melee blow thrown on 10 lands on 11, before the tick's booking: it waits under 10 and the booking takes it.
		for (int i = 0; i < 6; i++)
		{
			final int thrown = 10 + i * 4;
			splat(thrown + 1, 25, thrown);
			book(thrown + 1, thrown, "Abyssal tentacle");
		}
		line.settle();
		assertEquals(150, damageOf("Abyssal tentacle"));
		for (FightTimeline.Attack a : line.attacks())
		{
			assertEquals(25, a.damage);
		}
	}

	@Test
	public void chinchompaCollateralGoesWithItsThrow()
	{
		// One throw: its splat on the target, then two on the adds beside it, keyed as the target's burst.
		for (int i = 0; i < 4; i++)
		{
			final int surfaced = 300 + i * 4;
			final int booked = PvmPerformancePlugin.projectileBookedTick(surfaced);
			book(surfaced, booked, "Black chinchompa");
			splat(surfaced + 1, 20, booked);
			clock[0] = surfaced + 1;
			fight.recordExtraTarget(15, 0L, true, true);
			line.attributeSplat(booked);
			fight.recordExtraTarget(11, 0L, true, true);
			line.attributeSplat(booked);
		}
		for (FightTimeline.Attack a : line.attacks())
		{
			assertEquals(46, a.damage);
		}
	}

	@Test
	public void aShotLandingAfterASwitchIsReportedNotGivenToTheNextAttack()
	{
		// The fight opened by switching back mid-flight: a splat from a throw made in the fight before, keyed to it.
		splat(500, 33, 494);
		book(504, 502, "Bow of faerdhinen");
		splat(505, 21, 502);
		line.settle();
		assertEquals(21, line.attacks().get(0).damage);
		final List<int[]> left = line.unmatched();
		assertEquals(1, left.size());
		assertEquals(494, left.get(0)[0]);
		assertEquals(33, left.get(0)[1]);
		assertEquals(33, line.unattributedDamage());
	}
}

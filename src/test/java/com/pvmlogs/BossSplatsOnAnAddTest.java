package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-108: while the player is on an add, the room's boss still takes damage (the
 * explosions, his cleave, the burn, the thrall). Before, the non-mine splats on
 * him went uncounted and the thrall's went to the add's fight, so 7 of 23
 * Araxxor kills in Eric's logs of 2026-10-29 ({@code pvm-debug-20261029-212024},
 * {@code -214018}, {@code -223259}) came out 1 to 17 short of his bar.
 *
 * <p>The plugin has no harness here, so these drive what its splat handlers do, in
 * their order: {@link PvmPerformancePlugin#roomBossFight} picks the fight, the
 * kind's total goes on it, and {@link TargetHealth} takes the damage on the boss's
 * index, which is what RECONCILE's "on the target" reads.
 */
public class BossSplatsOnAnAddTest
{
	private static final int ARAXXOR = NpcID.ARAXXOR;
	private static final int BOSS = 49600;
	private static final int ADD = 53141;
	private static final int SIRE = NpcID.ABYSSALSIRE_SIRE_STASIS_STUNNED;

	/** The room at the 21:40 log's [918]: Araxxor's segment ended at 229 left, the fight now on a Mirrorback. */
	private static final class Room
	{
		final Encounter room = new Encounter("Araxxor", null, 0L);
		final Fight boss = new Fight("Araxxor", ARAXXOR, BOSS, 1020, 0L, null, 700);
		final Fight add = new Fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, ADD, 58, 0L, null, 918);
		final TargetHealth health = new TargetHealth();

		Room()
		{
			boss.recordAttackMade(false);
			boss.recordDamageDealt(791, 1_000L, true);
			room.add(boss);
			boss.end(false, 2_000L);
			add.recordAttackMade(false);
			add.recordExpected(31, 0.898, 13.92, 98.6);
			room.add(add);
			health.setTableMaxHp(BOSS, ARAXXOR, 1020, 700);
			health.damage(BOSS, ARAXXOR, 791, 917);
			health.setTableMaxHp(ADD, NpcID.ARAXXOR_MINION_MIRRORBACK, 58, 918);
		}

		Fight bookedFor(int index, int npcId)
		{
			return index == add.getTargetIndex() ? add : PvmPerformancePlugin.roomBossFight(room, add, index, npcId);
		}
	}

	private static JsonObject saved(Fight f)
	{
		return new Gson().toJsonTree(f).getAsJsonObject();
	}

	@Test
	public void aCleaveWhileOnAnAraxyteIsHis()
	{
		final Room r = new Room();
		final JsonObject addBefore = saved(r.add);
		// [918] and [922]: his bar 229 -> 220 -> 212, enraged since [906]; no splat of his was counted.
		int tick = 918;
		for (int amount : new int[] {9, 8})
		{
			final Fight fight = r.bookedFor(BOSS, ARAXXOR);
			assertSame(r.boss, fight);
			fight.recordRoomDamage(amount);
			r.health.damage(BOSS, ARAXXOR, amount, tick);
			tick += 4;
		}
		assertEquals(17, r.boss.getRoomDamage());
		assertEquals(212, r.health.remaining(BOSS, tick));
		// The kill's RECONCILE: 1003 counted against his 1020 before, so -17; now 1020.
		r.health.damage(BOSS, ARAXXOR, 212, 976);
		assertEquals(1020, r.health.damageCounted(BOSS));
		// The add's fight is as it was: the player's damage, expected and the rest.
		assertEquals(addBefore, saved(r.add));
		// And the boss's segment stays solo: the cleave is the room's.
		assertEquals(0, r.boss.getOthersDamage());
	}

	@Test
	public void aThrallSplatOnAraxxorWhileOnAnAraxyteIsHisFights()
	{
		// The 21:40 log's [2234]: a 1 on Araxxor judged the thrall's went to the Mirrorback's fight (thrallDamage=1).
		final Room r = new Room();
		final JsonObject addBefore = saved(r.add);
		final Fight fight = r.bookedFor(BOSS, ARAXXOR);
		assertSame(r.boss, fight);
		fight.recordThrallDamage(1);
		r.health.damage(BOSS, ARAXXOR, 1, 2234);
		assertEquals(1, r.boss.getThrallDamage());
		assertEquals(0, r.add.getThrallDamage());
		assertEquals(addBefore, saved(r.add));
		assertEquals(228, r.health.remaining(BOSS, 2234));
	}

	@Test
	public void theBurnOnAraxxorWhileOnAnAraxyte()
	{
		// The 21:20 log's [1308] and [1312]: the 2s of his burn every 4 ticks, from [1300] and [1304] on him, went on.
		final Room r = new Room();
		r.bookedFor(BOSS, ARAXXOR).recordDotDamage(2, 74);
		r.bookedFor(BOSS, ARAXXOR).recordDotDamage(2, 74);
		assertEquals(4, r.boss.getDotDamage());
		assertEquals(0, r.add.getDotDamage());
	}

	@Test
	public void onlyTheBossIsBookedSo()
	{
		final Room r = new Room();
		// Another araxyte isn't the boss: a splat on it, while the fight is on this one, goes nowhere as before.
		assertNull(PvmPerformancePlugin.roomBossFight(r.room, r.add, 60000, NpcID.ARAXXOR_MINION_VENOM));
		// On the boss with the fight on the boss: that is the current fight's, not this path's.
		assertNull(PvmPerformancePlugin.roomBossFight(r.room, r.boss, BOSS, ARAXXOR));
		// Another boss's npc in the scene isn't this room's.
		assertNull(PvmPerformancePlugin.roomBossFight(r.room, r.add, 1234, SIRE));
		// No boss fight yet in the room, or one that was never kept (no attempts): nowhere to book it.
		final Encounter fresh = new Encounter("Araxxor", null, 0L);
		final Fight looked = new Fight("Araxxor", ARAXXOR, BOSS, 1020, 0L, null, 0);
		fresh.add(looked);
		looked.end(false, 1L);
		fresh.add(r.add);
		assertNull(PvmPerformancePlugin.roomBossFight(fresh, r.add, BOSS, ARAXXOR));
		// The room over: nothing.
		r.room.end(5_000L);
		assertNull(PvmPerformancePlugin.roomBossFight(r.room, r.add, BOSS, ARAXXOR));
	}

	@Test
	public void theSireWithARespiratorySystemTheTarget()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = new Fight("Abyssal Sire", SIRE, 7000, 400, 0L, null, 0);
		sire.recordAttackMade(false);
		room.add(sire);
		sire.end(false, 1_000L);
		final Fight lung = new Fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 7001, 1, 0L, null, 10);
		lung.recordAttackMade(false);
		room.add(lung);
		final JsonObject lungBefore = saved(lung);
		assertTrue(EncounterGroup.isBossOfAdd(SIRE, NpcID.ABYSSALSIRE_LUNG));
		final Fight booked = PvmPerformancePlugin.roomBossFight(room, lung, 7000, SIRE);
		assertSame(sire, booked);
		booked.recordDotDamage(6, 5);
		assertEquals(6, sire.getDotDamage());
		assertEquals(lungBefore, saved(lung));
		// Another respiratory system isn't the boss.
		assertNull(PvmPerformancePlugin.roomBossFight(room, lung, 7002, NpcID.ABYSSALSIRE_LUNG));
	}

	@Test
	public void bossOfAnAdd()
	{
		assertTrue(EncounterGroup.isBossOfAdd(ARAXXOR, NpcID.ARAXXOR_MINION_MIRRORBACK));
		assertFalse(EncounterGroup.isBossOfAdd(NpcID.ARAXXOR_MINION_VENOM, NpcID.ARAXXOR_MINION_MIRRORBACK));
		assertFalse(EncounterGroup.isBossOfAdd(NpcID.ARAXXOR_MINION_MIRRORBACK, ARAXXOR));
		assertFalse(EncounterGroup.isBossOfAdd(SIRE, NpcID.ARAXXOR_MINION_MIRRORBACK));
	}
}

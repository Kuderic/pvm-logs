package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Test;

/**
 * T-035: the fight timeline's series, its attribution of splats and
 * expectations to attacks, the store's cap and switch, and the room and raid
 * views. The sums against real fights are {@link FightTimelineReplayTest}'s.
 */
public class FightTimelineTest
{
	private int now = 100;
	private final FightTimeline.Store store = new FightTimeline.Store();

	{
		store.setClock(() -> now);
	}

	private Fight fight(String name, int npcId)
	{
		final Fight fight = new Fight(name, npcId, 1, 700, 0L, null, 0);
		store.attach(fight);
		return fight;
	}

	private static void attack(Fight fight, FightTimeline line, int thrownTick, String key)
	{
		fight.recordAttackMade(false, 1.0, false);
		line.describeLatest(key, 1, null, FightTimeline.Style.MELEE, thrownTick);
	}

	@Test
	public void itsSumsAreTheFightsAndItsSplatsTheirAttacks()
	{
		final Fight fight = fight("Vardorvis", NpcIds.GOBLIN);
		final FightTimeline line = store.of(fight);
		assertNotNull(line);

		// A melee blow thrown on 100 lands on 101, before it is booked: its splat waits for the booking.
		now = 101;
		fight.recordDamageDealt(30, 0L, true);
		line.attributeSplat(100);
		assertEquals(30, line.unattributedDamage());
		attack(fight, line, 100, "Abyssal tentacle");
		fight.recordExpected(49, 0.6, 14.7, 270);
		assertEquals(0, line.unattributedDamage());
		final FightTimeline.Attack first = line.latest();
		assertEquals(30, first.damage);
		assertEquals(1, first.hits);
		assertEquals(14.7, first.mean, 1e-9);
		assertEquals(270, first.variance, 1e-9);

		now = 102;
		fight.recordTickSpent(false);
		now = 103;
		fight.recordTickSpent(false);
		now = 104;
		fight.recordTickLost(true, false);
		now = 105;
		fight.recordTickLost(false, false);

		// A special: burning claws, three splats over two ticks, the last one re-judged as the thrall's.
		now = 106;
		attack(fight, line, 105, "Burning claws");
		line.markLatestSpecial();
		fight.recordExpected(88, 0.6, 30.0, 400);
		fight.recordDamageDealt(12, 0L, true);
		line.attributeSplat(105);
		fight.recordDamageDealt(12, 0L, false);
		line.attributeSplat(105);
		now = 107;
		fight.recordDamageDealt(3, 0L, false);
		line.attributeSplat(105);
		fight.revokeDamageDealt(3, false);
		final FightTimeline.Attack special = line.latest();
		assertTrue(special.special);
		assertEquals("Burning claws (special)", special.rowKey());
		assertEquals(24, special.damage);

		// An AoE splat on another npc joins the latest attack.
		fight.recordExtraTarget(5, 0L, true, true);

		assertEquals(fight.getDamageDealt(), line.totalDamage());
		assertEquals(fight.getSumExpectedAverageHit(), line.totalExpected(), 1e-9);
		assertEquals(fight.getSumExpectedDamageVariance(), line.totalVariance(), 1e-9);
		assertEquals(fight.getTicksLost(), line.lostTicks());
		assertEquals(fight.getTicksLostEating(), line.eatingTicks());
		int attributed = 0;
		for (FightTimeline.Attack a : line.attacks())
		{
			attributed += a.damage;
		}
		assertEquals(fight.getDamageDealt(), attributed + line.unattributedDamage());

		// One state per counted tick, from the first.
		assertEquals(100, line.startTick());
		assertEquals(FightTimeline.ATTACK, line.stateAt(1));
		assertEquals(FightTimeline.COOLDOWN, line.stateAt(2));
		assertEquals(FightTimeline.EATING, line.stateAt(4));
		assertEquals(FightTimeline.LOST, line.stateAt(5));
		assertEquals(FightTimeline.SPECIAL, line.stateAt(6));
		// The cumulative series end on the totals.
		final float[] damage = line.cumulativeDamage(0);
		final float[] expected = line.cumulativeExpected(0);
		final float[] variance = line.cumulativeVariance(0);
		assertEquals(fight.getDamageDealt(), damage[damage.length - 1], 1e-3);
		assertEquals(fight.getSumExpectedAverageHit(), expected[expected.length - 1], 1e-3);
		assertEquals(fight.getSumExpectedDamageVariance(), variance[variance.length - 1], 1e-3);
		// Copies from an index: what the panel sends a tick at a time.
		assertEquals(3, line.states(5).length);
	}

	@Test
	public void aHeldExpectationGoesToItsOwnAttack()
	{
		final Fight fight = fight("Abyssal Sire", NpcIds.GOBLIN);
		final FightTimeline line = store.of(fight);
		attack(fight, line, 100, "Toxic blowpipe");
		final FightTimeline.Attack shot = line.latest();
		now = 102;
		attack(fight, line, 102, "Abyssal tentacle");
		// The blowpipe's dart lands now, after the whip went out.
		line.expectNextFor(shot);
		fight.recordExpected(30, 0.7, 10.5, 90);
		assertEquals(10.5, shot.mean, 1e-9);
		assertEquals(-1, line.latest().mean, 0);
	}

	@Test
	public void offRecordsNothing()
	{
		store.setEnabled(false);
		final Fight fight = fight("Vardorvis", NpcIds.GOBLIN);
		assertNull(store.of(fight));
		assertNull(fight.getTimeline());
		fight.recordAttackMade(false);
		fight.recordDamageDealt(10, 0L, true);
		assertEquals(0, store.size());
	}

	@Test
	public void theStoreKeepsTheLastFiftyAndStaysSmall()
	{
		Fight first = null;
		for (int i = 0; i < 60; i++)
		{
			final Fight fight = fight("Goblin " + i, NpcIds.GOBLIN);
			first = first == null ? fight : first;
		}
		assertEquals(FightTimeline.Store.CAP, store.size());
		assertNull(store.of(first));
		assertNull(first.getTimeline());
		// A dropped fight goes on counting without its timeline.
		first.recordDamageDealt(10, 0L, true);
		assertEquals(10, first.getDamageDealt());

		// A nine-minute raid's worth on one timeline: 900 ticks, 150 attacks.
		store.clear();
		now = 0;
		final Fight raid = fight("Long fight", NpcIds.GOBLIN);
		final FightTimeline line = store.of(raid);
		for (int t = 0; t < 900; t++)
		{
			now = t;
			if (t % 6 == 0)
			{
				attack(raid, line, t, "Scythe of vitur");
				raid.recordExpected(50, 0.7, 30, 500);
				raid.recordDamageDealt(25, 0L, true);
				line.attributeSplat(t);
			}
			else
			{
				raid.recordTickSpent(false);
			}
		}
		assertTrue(line.approximateBytes() + " bytes", line.approximateBytes() < 30_000);
		// At the cap, every fight that size.
		assertTrue(line.approximateBytes() * FightTimeline.Store.CAP < 1_500_000);
	}

	@Test
	public void aRoomViewSumsItsScoredFightsAndMarksTheUnscored()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = fight("Abyssal Sire", NpcIds.GOBLIN);
		final FightTimeline sireLine = store.of(sire);
		attack(sire, sireLine, 100, "Abyssal tentacle");
		sire.recordExpected(49, 0.6, 15, 200);
		sire.recordDamageDealt(20, 0L, true);
		sireLine.attributeSplat(100);
		now = 101;
		sire.recordTickSpent(false);
		now = 102;
		final Fight respiratory = fight("Respiratory system", net.runelite.api.gameval.NpcID.ABYSSALSIRE_LUNG);
		final FightTimeline lungLine = store.of(respiratory);
		attack(respiratory, lungLine, 102, "Abyssal tentacle");
		respiratory.recordExpected(49, 0.6, 15, 200);
		respiratory.recordDamageDealt(50, 0L, true);
		now = 103;
		respiratory.recordTickSpent(false);
		room.add(sire);
		room.add(respiratory);

		final FightTimeline.View view = FightTimeline.room(room, store);
		assertEquals(100, view.startTick);
		assertEquals(4, view.states.length);
		assertEquals(FightTimeline.ATTACK, view.states[0]);
		assertEquals(FightTimeline.UNSCORED, view.states[2]);
		assertEquals(FightTimeline.UNSCORED, view.states[3]);
		// The Sire's figures only, held after his last tick.
		assertEquals(20, view.damage[3], 1e-6);
		assertEquals(15, view.expected[3], 1e-6);
		assertEquals(200, view.variance[3], 1e-6);
		assertEquals(1, view.attacks.size());

		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(4, FightTimeline.raid(raid, store).states.length);
		assertSame(FightTimeline.View.EMPTY, FightTimeline.room(null, store));
	}

	@Test
	public void theTimelineIsNeverSaved()
	{
		final Fight fight = fight("Vardorvis", NpcIds.GOBLIN);
		fight.recordAttackMade(false);
		final String json = new Gson().toJson(fight);
		assertFalse(json, json.contains("timeline"));
		assertNull(new Gson().fromJson(json, Fight.class).getTimeline());
	}
}

package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-163, part 2: an add no list knows keeps its boss's fight open. Venenatis
 * (850) and her spiderlings (5) from Eric's log of 2026-11-03, before part 1
 * grouped them: every spiderling hit ended her fight.
 */
public class UnlistedAddsTest
{
	private static final String BOSS = "Venenatis";
	private static final int BOSS_HP = 850;

	private static UnlistedAdds.Verdict judge(String name, int hp, UnlistedAdds.Spawn spawn, int tick, int lastBossAttack)
	{
		return UnlistedAdds.judge(true, false, name, BOSS, hp, BOSS_HP, spawn, tick, lastBossAttack);
	}

	@Test
	public void anAddNamedAfterItsBossIsOne()
	{
		// "Venenatis' Spiderling" (npc 12000, 5 hp) at [14693], 9 ticks after her last hit: no spawn seen, the name says.
		assertEquals(UnlistedAdds.Verdict.ADD, judge("Venenatis' Spiderling", 5, null, 14693, 14684));
		assertTrue(UnlistedAdds.byName("<col=ffff00>Venenatis' Spiderling</col>", "Venenatis"));
		assertFalse("the boss is not its own add", UnlistedAdds.byName("Venenatis", "Venenatis"));
		assertEquals("UNLISTED ADD Venenatis' Spiderling (npc 12000) of Venenatis: name, hp 5 of 850",
			UnlistedAdds.line("Venenatis' Spiderling", 12000, BOSS, true, null, 5, BOSS_HP));
	}

	@Test
	public void anAddSpawnedBesideTheBossIsOne()
	{
		final UnlistedAdds.Spawn near = new UnlistedAdds.Spawn(14684, 6);
		assertEquals(UnlistedAdds.Verdict.ADD, judge("Spiderling", 5, near, 14693, 14690));
		assertEquals(UnlistedAdds.Verdict.ADD, judge("Spiderling", 5, new UnlistedAdds.Spawn(14684, UnlistedAdds.RANGE),
			14693, 14690));
		// Too far from the boss, never seen spawning, or where it was unknown: not one.
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD, judge("Spiderling", 5,
			new UnlistedAdds.Spawn(14684, UnlistedAdds.RANGE + 1), 14693, 14690));
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD, judge("Spiderling", 5, null, 14693, 14690));
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD, judge("Spiderling", 5, new UnlistedAdds.Spawn(14684, -1), 14693,
			14690));
		assertEquals("UNLISTED ADD Spiderling (npc 12000) of Venenatis: spawned on 14684, 6 tiles away, hp 5 of 850",
			UnlistedAdds.line("Spiderling", 12000, BOSS, false, near, 5, BOSS_HP));
		assertEquals("UNLISTED ADD Venenatis' Spiderling (npc 12000) of Venenatis: name, spawned on 14684, 6 tiles away, "
			+ "hp 5 of 850", UnlistedAdds.line("Venenatis' Spiderling", 12000, BOSS, true, near, 5, BOSS_HP));
	}

	@Test
	public void aBigNpcIsNotOne()
	{
		final UnlistedAdds.Spawn near = new UnlistedAdds.Spawn(100, 3);
		// A tenth of the boss's hitpoints is the most: the God Wars bodyguards are well over it, and real fights.
		assertEquals(UnlistedAdds.Verdict.ADD, judge("Spiderling", 85, near, 110, 105));
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD, judge("Spiderling", 86, near, 110, 105));
		// Either side's hitpoints unknown is no add.
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD, judge("Venenatis' Spiderling", -1, near, 110, 105));
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD,
			UnlistedAdds.judge(true, false, "Venenatis' Spiderling", BOSS, 5, -1, near, 110, 105));
		// A boss is never one, however small and near: two at once stay two fights.
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD,
			UnlistedAdds.judge(true, true, "Venenatis' Spiderling", BOSS, 5, BOSS_HP, near, 110, 105));
		// Nor the player's own reanimated hellhound beside Cerberus (Eric's logs of 11-02 and 11-03).
		assertTrue(UnlistedAdds.reanimated("Reanimated hellhound"));
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD,
			UnlistedAdds.judge(true, false, "Reanimated hellhound", "Cerberus", 35, 600, near, 110, 105));
	}

	@Test
	public void theBossUnattackedForFiftyTicksReleasesIt()
	{
		final UnlistedAdds.Spawn near = new UnlistedAdds.Spawn(100, 3);
		assertEquals(UnlistedAdds.Verdict.ADD, judge("Spiderling", 5, near, 100 + UnlistedAdds.RELEASE_TICKS, 100));
		assertEquals(UnlistedAdds.Verdict.RELEASED, judge("Spiderling", 5, near, 101 + UnlistedAdds.RELEASE_TICKS, 100));

		// The boss's fight ends at the switch: the first attack on an add since the boss's last, as without the rule.
		final UnlistedAdds adds = new UnlistedAdds();
		adds.reset(100);
		assertEquals(100, adds.bossAttackTick());
		adds.bossAttacked(120);
		assertTrue("its line, the first time", adds.addAttacked(7, 12000, 125, 125_000L));
		assertFalse("once a fight per add id", adds.addAttacked(8, 12000, 130, 130_000L));
		assertTrue(adds.isAccepted(7));
		assertTrue(adds.isAccepted(8));
		assertEquals(125_000L, adds.switchMillis());
		assertEquals(125, adds.switchTick());
		adds.releasedAt(171, 171_000L);
		assertEquals("released at the switch, not at the release", 125_000L, adds.switchMillis());
		// Back on the boss: the next add's switch is a new one.
		adds.bossAttacked(140);
		assertEquals(-1L, adds.switchMillis());
		adds.releasedAt(191, 191_000L);
		assertEquals(191_000L, adds.switchMillis());
		// And the next fight starts clean.
		adds.reset(Integer.MIN_VALUE);
		assertFalse(adds.isAccepted(7));
		assertTrue(adds.addAttacked(7, 12000, 300, 300_000L));
	}

	@Test
	public void aMonsterRunIsUnchanged()
	{
		// No boss fight open (a monster's, T-089): nothing is an add, whatever it is.
		assertEquals(UnlistedAdds.Verdict.NOT_AN_ADD,
			UnlistedAdds.judge(false, false, "Undead cow", "Undead cow", 8, 8, new UnlistedAdds.Spawn(1, 1), 2, 1));
		// The plugin's test for a boss's fight: not a monster, outside a raid.
		assertTrue(MonsterRooms.isMonster("Undead cow", 2992, false, null));
		assertFalse(MonsterRooms.isMonster("Venenatis", 6610, false, null));
		assertFalse(MonsterRooms.isMonster("Cerberus", 5862, false, null));
	}

	@Test
	public void spawnsAreKeptPerFightAndCapped()
	{
		final UnlistedAdds adds = new UnlistedAdds();
		adds.reset(10);
		adds.spawned(5, 12, 4);
		assertEquals(12, adds.spawnOf(5).tick);
		assertEquals(4, adds.spawnOf(5).distance);
		for (int i = 0; i < UnlistedAdds.MAX_SPAWNS + 10; i++)
		{
			adds.spawned(1000 + i, 20, 1);
		}
		assertEquals(UnlistedAdds.MAX_SPAWNS, adds.spawnsKept());
		// An index taken again by a new spawn is still recorded.
		adds.spawned(5, 30, 2);
		assertEquals(30, adds.spawnOf(5).tick);
		adds.reset(40);
		assertNull(adds.spawnOf(5));
		assertEquals(0, adds.spawnsKept());
	}
}

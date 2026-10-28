package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.Map;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-049: the drain figures a fight saves for T-051 and T-052, -1 on kills saved
 * before T-049, and through the history file and back; and the target the glue
 * builds from a boss's stats and rules.
 */
public class DrainRecordTest
{
	private static final Gson GSON = new Gson();

	private static Fight fight()
	{
		return new Fight("Boss", 12345, 7, 500, 0L, null, 0);
	}

	private static DrainRecord recorded()
	{
		final DrainTracker tracker = new DrainTracker(null);
		final DrainRule.Target t = new DrainRule.Target(200, 150, 100, 200, 200, 100, 50, 0, false, false, false,
			false);
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, 7, 12345, "Boss", 101, t, false);
		tracker.ownSplat(7, 101, 0);
		tracker.tick(101);
		tracker.ownThrow(110, SpecialWeapon.DRAGON_WARHAMMER, 7, 12345, "Boss", 111, t, false);
		tracker.ownSplat(7, 111, 33);
		tracker.tick(111);
		tracker.partyDrain(112, SpecialWeapon.SEERCULL, 20, 7, 12345, "Boss", t);
		return tracker.record(7, 0);
	}

	@Test
	public void aKillSavedBeforeItReadsMinusOne()
	{
		final Fight f = fight();
		assertNull(f.getDrainRecord());
		assertEquals(-1, f.getDefenceStart());
		assertEquals(-1, f.getDefenceEnd());
		assertEquals(-1, f.getMagicStart());
		assertEquals(-1, f.getMagicEnd());
		assertEquals(-1, f.getDefenceBase());
		assertEquals(-1, f.getDefenceFloor());
		assertEquals(-1, f.getOwnDrainSpecsThrown());
		assertEquals(-1, f.getOwnDrainSpecsLanded());
		assertEquals(-1, f.getPartyDrains());
		final Encounter room = new Encounter("Boss", null, 0L);
		room.add(f);
		assertEquals(-1, room.getDefenceStart());
		assertEquals(-1, room.getMagicEnd());
		assertEquals(-1, room.getOwnDrainSpecsThrown());
		assertEquals(-1, room.getPartyDrains());
		assertTrue(room.getOwnDrainSpecs().isEmpty());
	}

	@Test
	public void aFightKeepsItsDrains()
	{
		final Fight f = fight();
		f.recordDrain(recorded());
		assertEquals(200, f.getDefenceStart());
		assertEquals(140, f.getDefenceEnd());
		assertEquals(150, f.getMagicStart());
		assertEquals(130, f.getMagicEnd());
		assertEquals(200, f.getDefenceBase());
		assertEquals(50, f.getDefenceFloor());
		assertEquals(2, f.getOwnDrainSpecsThrown());
		assertEquals(1, f.getOwnDrainSpecsLanded());
		assertEquals(1, f.getPartyDrains());
		final Encounter room = new Encounter("Boss", null, 0L);
		room.add(f);
		room.add(fight());
		assertEquals(200, room.getDefenceStart());
		assertEquals(140, room.getDefenceEnd());
		assertEquals(2, room.getOwnDrainSpecsThrown());
		assertEquals(1, room.getOwnDrainSpecsLanded());
		final Map<String, int[]> specs = room.getOwnDrainSpecs();
		assertEquals(1, specs.get("DRAGON_WARHAMMER")[1]);
	}

	@Test
	public void theRecordSurvivesTheHistoryFile()
	{
		final Fight f = fight();
		f.recordDrain(recorded());
		final Fight back = GSON.fromJson(GSON.toJson(new Fight[]{f}), Fight[].class)[0];
		assertNotNull(back.getDrainRecord());
		assertEquals(140, back.getDefenceEnd());
		assertEquals(2, back.getOwnDrainSpecsThrown());
		assertEquals(3, back.getDrainRecord().events.size());
		final DrainEvent landed = back.getDrainRecord().events.get(1);
		assertTrue(landed.isDrain());
		assertEquals("DRAGON_WARHAMMER", landed.weapon);
		assertTrue(landed.own);
		assertEquals(110, landed.thrownTick);
		assertEquals(111, landed.landedTick);
		assertEquals(33, landed.hit);
		assertEquals(200, landed.defenceBefore);
		assertEquals(140, landed.defenceAfter);
		assertFalse(back.getDrainRecord().events.get(2).own);
	}

	/** An old history entry, with no drain record, loads and reads -1. */
	@Test
	public void anOldHistoryEntryLoads()
	{
		final String old = GSON.toJson(new Fight[]{fight()});
		assertFalse(old.contains("drainRecord"));
		final Fight back = GSON.fromJson(old, Fight[].class)[0];
		assertEquals(-1, back.getDefenceEnd());
		assertEquals(-1, back.getPartyDrains());
	}

	private static MonsterStatsProvider.MonsterStats stats(String version, boolean immune, String... attributes)
	{
		return new MonsterStatsProvider.MonsterStats("Boss", 3, 500, 200, 150, 1, 0, 0, 0, 100, 0, null, 0,
			new java.util.HashSet<>(java.util.Arrays.asList(attributes)), 1.0, version, immune, null)
			.withDrainFacts(250, 240, 120, false);
	}

	/** The glue's target: the levels given (raid-scaled), the boss's floors, a demon, and its regeneration. */
	@Test
	public void theTargetFromStatsAndRules()
	{
		final BossRules.DrainRules rules = new BossRules.DrainRules();
		rules.maxDefenceDrain = 30;
		rules.regenTicks = 5;
		final DrainRule.Target t = DefenceDrain.target(stats("", false, "demon"), rules, 220, 150);
		assertEquals(220, t.defence);
		assertEquals(190, t.defenceFloor);
		assertEquals(250, t.attack);
		assertEquals(240, t.strength);
		assertEquals(120, t.ranged);
		assertEquals(100, t.magicDefence);
		assertTrue(t.demon);
		assertFalse(t.immune);
		assertEquals(5, t.regenTicks);
		// No rules: drains to 0, the default regeneration.
		final DrainRule.Target plain = DefenceDrain.target(stats("", false), null, 200, 150);
		assertEquals(0, plain.defenceFloor);
		assertEquals(BossRules.DrainRules.DEFAULT_REGEN_TICKS, plain.regenTicks);
		assertFalse(plain.demon);
	}

	/** An immune version (the data says so, as for an awakened boss) pins both floors at the base. */
	@Test
	public void anImmuneVersion()
	{
		final BossRules.DrainRules rules = new BossRules.DrainRules();
		rules.immuneVersions = Collections.singletonList("Awakened");
		assertTrue(rules.immuneIn("Awakened"));
		final DrainRule.Target t = DefenceDrain.target(stats("Awakened", true), rules, 300, 225);
		assertTrue(t.immune);
		assertEquals(300, t.defenceFloor);
		assertEquals(225, t.magicFloor);
	}
}

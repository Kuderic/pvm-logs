package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-152: the Wardens. P2's poise (the DPS calc's facts): certain to land, m% to (m + 20)% of the max. The core (OSRS
 * Wiki, Core): five times the damage passed on to her, melee always its max; T-172: scored at the hit's own value. P3: 'Damaged' until its first fall below 5%, then
 * 'Enraged' (OSRS Wiki, Tumeken's Warden). Eric's 11-02 solo raid at level 340.
 */
public class WardensTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int INDEX = 9;

	private static BossRules.DamageModifierRules rulesFor(int npcId)
	{
		return RULES.forNpc(npcId).damageModifiers;
	}

	private static List<BossRules.ModifierRule> applying(int npcId, String style, ModifierTracker tracker, int tick)
	{
		return tracker.applying(INDEX, rulesFor(npcId), npcId, style, tick, tick, false, (id, range) -> false);
	}

	@Test
	public void poisesShareRunsFifteenToForty()
	{
		assertEquals(15, ModifierTracker.wardensPoisePercent(10_000, 34_470));
		assertEquals(15, ModifierTracker.wardensPoisePercent(0, 34_470));
		// A third of the defence roll off the attack roll, then 25 points over 42,000.
		assertEquals(30, ModifierTracker.wardensPoisePercent(11_490 + 25_200, 34_470));
		assertEquals(29, ModifierTracker.wardensPoisePercent(11_490 + 25_199, 34_470));
		assertEquals(40, ModifierTracker.wardensPoisePercent(82_000, 34_470));
	}

	@Test
	public void aPoiseHitCannotMissAndIsItsBand()
	{
		final List<BossRules.ModifierRule> raw = applying(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, "RANGED",
			new ModifierTracker(null), 100);
		assertTrue(ModifierTracker.hasPoise(raw));
		// Eric's bowfa: max 50 at 40%: 20 to 30, every one landing, 25 on average.
		final List<BossRules.ModifierRule> mods = ModifierTracker.withPoise(raw, 40);
		assertFalse(ModifierTracker.hasPoise(mods));
		assertEquals(1.0, ModifierTracker.accuracy(mods, 0.74), 0);
		assertEquals(30, ModifierTracker.max(mods, 50));
		final DamageMoments m = ModifierTracker.moments(mods, new int[]{50}, ModifierTracker.accuracy(mods, 0.74),
			Integer.MAX_VALUE);
		assertEquals(25.0, m.mean, 1e-9);
		// The 41-max bow at 30%: 12 to 20, its max-hit poise splats 20.
		final List<BossRules.ModifierRule> at30 = ModifierTracker.withPoise(raw, 30);
		assertEquals(20, ModifierTracker.max(at30, 41));
		assertEquals(12, ModifierTracker.rollFloor(at30, 41));
		// It applies to specials too.
		assertEquals(1, ModifierTracker.forSpecials(mods).size());
	}

	@Test
	public void aMeleeHitOnTheCoreIsItsMaxAtItsOwnValue()
	{
		// T-172: the hit on the core at its own value (the core's splat is the attack's damage, T-153); its x5 is what the
		// core passes on to her, kept apart (WardensPhase2), not scored.
		final List<BossRules.ModifierRule> mods = applying(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, "MELEE",
			new ModifierTracker(null), 100);
		assertEquals(1.0, ModifierTracker.accuracy(mods, 0.40), 0);
		// The keris's 48: 48, certain, nothing to luck.
		final DamageMoments keris = ModifierTracker.moments(mods, new int[]{48}, 1.0, Integer.MAX_VALUE);
		assertEquals(48.0, keris.mean, 1e-9);
		assertEquals(0.0, keris.variance(), 1e-9);
		// The dragon dagger's special, two hits of 48: 96 (two splats of 48 on the core, 240 each passed on).
		final List<BossRules.ModifierRule> spec = ModifierTracker.forSpecials(mods);
		assertEquals(96.0, ModifierTracker.moments(spec, new int[]{48, 48}, 1.0, Integer.MAX_VALUE).mean, 1e-9);
		// Its ordinary max, 42.
		assertEquals(42, ModifierTracker.max(mods, 42));
	}

	@Test
	public void aRangedHitOnTheCoreRollsAsItWould()
	{
		// T-172: no rule left for a ranged or magic hit on the core: it rolls as it would, at its own value.
		final List<BossRules.ModifierRule> mods = applying(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED, "RANGED",
			new ModifierTracker(null), 100);
		assertTrue(mods.isEmpty());
		assertEquals(0.8, ModifierTracker.accuracy(mods, 0.8), 0);
		assertEquals(50, ModifierTracker.max(mods, 50));
	}

	@Test
	public void p3EnragesOnItsHeal()
	{
		final List<String> lines = new ArrayList<>();
		final ModifierTracker tracker = new ModifierTracker(lines::add);
		final BossRules.DamageModifierRules rules = rulesFor(NpcID.TOA_WARDEN_TUMEKEN_PHASE3);
		assertTrue(ModifierTracker.opensOnHitpoints(rules));
		// 'Damaged' before: no version named.
		tracker.onHitpoints(INDEX, "The Wardens, phase 3", rules, 1000, 2080, 33700);
		tracker.onHitpoints(INDEX, "The Wardens, phase 3", rules, 127, 2080, 33779);
		assertNull(ModifierTracker.version(applying(NpcID.TOA_WARDEN_TUMEKEN_PHASE3, "RANGED", tracker, 33780)));
		// The heal to 495 of 2080 ([33783]): enraged, and it stays.
		tracker.onHitpoints(INDEX, "The Wardens, phase 3", rules, 495, 2080, 33783);
		assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("enraged on at tick 33783")));
		assertEquals("Enraged", ModifierTracker.version(applying(NpcID.TOA_WARDEN_TUMEKEN_PHASE3, "RANGED", tracker,
			33790)));
		tracker.onHitpoints(INDEX, "The Wardens, phase 3", rules, 11, 2080, 33880);
		assertEquals("Enraged", ModifierTracker.version(applying(NpcID.TOA_WARDEN_TUMEKEN_PHASE3, "RANGED", tracker,
			33881)));
	}

	@Test
	public void p3EnragesBelowFivePercentToo()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		final BossRules.DamageModifierRules rules = rulesFor(NpcID.TOA_WARDEN_ELIDINIS_PHASE3);
		tracker.onHitpoints(INDEX, "The Wardens, phase 3", rules, 104, 2080, 100);
		assertEquals("Enraged", ModifierTracker.version(applying(NpcID.TOA_WARDEN_ELIDINIS_PHASE3, "MELEE", tracker,
			101)));
		// A boss without such a state reads nothing.
		assertFalse(ModifierTracker.opensOnHitpoints(rulesFor(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE)));
	}
}

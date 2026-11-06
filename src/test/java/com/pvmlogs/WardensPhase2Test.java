package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import com.google.gson.Gson;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-172: the Wardens' phase 2 in its own units. Eric's duo raid (2026-11-04 15:19 log, build 8e328d6): the core's
 * splats summed 1148 as A, while E scored the attacks on the core at five times (6340), so the fight read z -263. Now
 * poise, the core and what it passed on to her are kept apart, each part's A and E in one unit.
 */
public class WardensPhase2Test
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);

	private static Fight fight(int npcId)
	{
		return new Fight("Elidinis' Warden", npcId, 1, 320, 0L, null, 0);
	}

	@Test
	public void aCoreHitRecordsItsOwnValueAndWhatItPassedOn()
	{
		final Fight f = fight(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE);
		// The keris's 48 on the core: A 48 on the Core (npc 11771), E 48 scored against her with the core out (11755, the
		// core's melee rule: its max), and her 240 passed on.
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_CORE, 48);
		f.recordWardensExpected(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 48, 0);
		f.recordPassedOn(240);
		final WardensPhase2 p2 = f.getWardensP2();
		assertEquals(48, p2.core.damage);
		assertEquals(48, p2.core.expected, 0);
		assertEquals(1, p2.core.attacks);
		assertEquals(240, p2.passedOn);
		assertEquals(0, p2.poise.damage);
		// The core's melee rule: its max at its own value, no x5.
		final List<BossRules.ModifierRule> mods = new ModifierTracker(null).applying(9,
			RULES.forNpc(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED).damageModifiers, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED,
			"MELEE", 100, 100, false, (id, range) -> false);
		assertEquals(48.0, ModifierTracker.moments(mods, new int[]{48}, 1.0, Integer.MAX_VALUE).mean, 1e-9);
	}

	@Test
	public void poiseAndCoreHitsAreKeptApart()
	{
		final Fight f = fight(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE);
		// Two poise hits on her (mage then range form), and a dragon dagger special on the core: two 48s, 240 each passed on.
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 20);
		f.recordWardensExpected(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 15, 4);
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, 13);
		f.recordWardensExpected(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, 14, 3);
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_CORE, 48);
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_CORE, 48);
		f.recordWardensExpected(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 96, 0);
		f.recordPassedOn(240);
		f.recordPassedOn(240);
		// The passed-on splat on her is no weapon damage: nothing to any part.
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 0);
		final WardensPhase2 p2 = f.getWardensP2();
		assertEquals(33, p2.poise.damage);
		assertEquals(29, p2.poise.expected, 1e-9);
		assertEquals(7, p2.poise.variance, 1e-9);
		assertEquals(2, p2.poise.attacks);
		assertEquals(96, p2.core.damage);
		assertEquals(96, p2.core.expected, 1e-9);
		assertEquals(1, p2.core.attacks);
		assertEquals(480, p2.passedOn);
		// Any other npc goes to no part, and a fight with none of them has none.
		final Fight other = fight(NpcID.TOA_WARDEN_ELIDINIS_PHASE3);
		other.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_PHASE3, 40);
		other.recordWardensExpected(NpcID.TOA_WARDEN_ELIDINIS_PHASE3, 25, 100);
		assertNull(other.getWardensP2());
	}

	@Test
	public void eitherWardensPhase2IdsTakeTheSamePath()
	{
		assertSame(WardensPhase2.Kind.POISE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE));
		assertSame(WardensPhase2.Kind.POISE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE));
		assertSame(WardensPhase2.Kind.POISE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE));
		assertSame(WardensPhase2.Kind.POISE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE));
		assertSame(WardensPhase2.Kind.CORE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED));
		assertSame(WardensPhase2.Kind.CORE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED));
		assertSame(WardensPhase2.Kind.CORE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_ELIDINIS_CORE));
		assertSame(WardensPhase2.Kind.CORE, WardensPhase2.kindOf(NpcID.TOA_WARDEN_TUMEKEN_CORE));
		assertNull(WardensPhase2.kindOf(NpcID.TOA_WARDEN_TUMEKEN_PHASE3));
		// Tumeken's core is the same rule as Elidinis': the hit at its own value.
		final List<BossRules.ModifierRule> mods = new ModifierTracker(null).applying(9,
			RULES.forNpc(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED).damageModifiers, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED,
			"MELEE", 100, 100, false, (id, range) -> false);
		assertEquals(48.0, ModifierTracker.moments(mods, new int[]{48}, 1.0, Integer.MAX_VALUE).mean, 1e-9);
		final Fight f = fight(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE);
		f.recordWardensDamage(NpcID.TOA_WARDEN_TUMEKEN_CORE, 48);
		f.recordWardensExpected(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED, 48, 0);
		assertEquals(48, f.getWardensP2().core.damage);
	}

	@Test
	public void aMaxPoiseSplatAtAnExactTopIsNotOverIt()
	{
		// The bowfa's 50 at a share of 40%: 60% is 30 exactly, and its max-poise splat was 30 (type 55, [84435]'s fight).
		final List<BossRules.ModifierRule> raw = new ModifierTracker(null).applying(9,
			RULES.forNpc(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE).damageModifiers, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE,
			"RANGED", 100, 100, false, (id, range) -> false);
		final List<BossRules.ModifierRule> at40 = ModifierTracker.withPoise(raw, 40);
		assertEquals(30, ModifierTracker.max(at40, 50));
		assertEquals(20, ModifierTracker.rollFloor(at40, 50));
		// Where 60% isn't whole the top is truncated, as the DPS calc has it (Math.trunc(max * (m + 20) / 100)): 44 at 45%
		// is 19, where Eric's max-poise splats were 20. Left as the DPS calc has it: see the PR.
		assertEquals(19, ModifierTracker.max(ModifierTracker.withPoise(raw, 25), 44));
	}

	@Test
	public void aRecordSavedBeforeHasNoParts()
	{
		final Gson gson = new Gson();
		final Fight saved = gson.fromJson("{\"targetName\":\"Elidinis' Warden\",\"targetId\":11753,\"damageDealt\":2089}",
			Fight.class);
		assertNull(saved.getWardensP2());
		// And the parts survive a save.
		final Fight f = fight(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE);
		f.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_CORE, 48);
		f.recordPassedOn(240);
		final Fight back = gson.fromJson(gson.toJson(f), Fight.class);
		assertNotNull(back.getWardensP2());
		assertEquals(48, back.getWardensP2().core.damage);
		assertEquals(240, back.getWardensP2().passedOn);
	}
}

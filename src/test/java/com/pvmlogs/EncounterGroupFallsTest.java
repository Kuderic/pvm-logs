package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-064: another NPC of a group going down ends the fight open on its target
 * only when both are one boss and the fallen one is a part of it. The plugin's
 * two rules (a death, and a change into a beaten form) both ask
 * {@link EncounterGroup#otherFallEndsFight}; a fall of the target itself ends
 * its own fight, as before.
 */
public class EncounterGroupFallsTest
{
	@Test
	public void everyGroupIsAudited()
	{
		for (String group : EncounterGroup.groupNames())
		{
			final EncounterGroup.Falls rule = EncounterGroup.fallsOf(group);
			assertNotNull("no fall rule for " + group, rule);
			if (rule.kind == EncounterGroup.FallKind.ONE_BOSS)
			{
				assertFalse(group, rule.boss.isEmpty());
				for (int id : rule.boss)
				{
					assertEquals(group + " lists " + id, group, EncounterGroup.of(id));
				}
			}
		}
	}

	@Test
	public void aPartnersTitanFallingLeavesYoursToFightOn()
	{
		// Beaten form, or a despawn: either way, the other titan's fight goes on.
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.RT_FIRE_QUEEN_INACTIVE, NpcID.RT_ICE_KING));
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.RT_ICE_KING_INACTIVE, NpcID.RT_FIRE_QUEEN));
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.RT_FIRE_QUEEN, NpcID.RT_ICE_KING));
		// An elemental neither.
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.RT_SUMMON_ELEMENTAL_FIRE, NpcID.RT_ICE_KING));
	}

	@Test
	public void aKrakenTentacleDyingLeavesTheKrakensFight()
	{
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, NpcID.SLAYER_KRAKEN_BOSS));
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE_WHIRLPOOL,
			NpcID.SLAYER_KRAKEN_BOSS));
		// The Kraken dying while a tentacle is the target is the boss down.
		assertTrue(EncounterGroup.otherFallEndsFight(NpcID.SLAYER_KRAKEN_BOSS, NpcID.SLAYER_KRAKEN_BOSS_TENTACLE));
	}

	@Test
	public void theHueycoatlIsUnchanged()
	{
		// Beaten at the head while the fight is open on a body segment or the tail.
		assertTrue(EncounterGroup.otherFallEndsFight(NpcID.HUEY_HEAD_DEFEATED, NpcID.HUEY_BODY_PART));
		assertTrue(EncounterGroup.otherFallEndsFight(NpcID.HUEY_HEAD_DEFEATED, NpcID.HUEY_TAIL));
		assertTrue(EncounterGroup.otherFallEndsFight(NpcID.HUEY_BODY_PART, NpcID.HUEY_HEAD));
	}

	@Test
	public void theSireAndHesporiAddsAreUnchanged()
	{
		for (int add : new int[]{NpcID.ABYSSALSIRE_SPAWN, NpcID.ABYSSALSIRE_SCION, NpcID.ABYSSALSIRE_LUNG})
		{
			assertFalse(EncounterGroup.otherFallEndsFight(add, NpcID.ABYSSALSIRE_SIRE_WANDERING));
		}
		assertTrue(EncounterGroup.otherFallEndsFight(NpcID.ABYSSALSIRE_SIRE_APOCALYPSE, NpcID.ABYSSALSIRE_LUNG));
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.HESPORI_HEALER_ACTIVE, NpcID.HESPORI));
		assertTrue(EncounterGroup.otherFallEndsFight(NpcID.HESPORI, NpcID.HESPORI_HEALER_ACTIVE));
	}

	@Test
	public void separateMonstersAndAddsElsewhere()
	{
		// The Chambers' vanguards are three monsters; Akkha's shadows are his adds; Dawn is a boss of her own.
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.RAIDS_VANGUARD_MELEE, NpcID.RAIDS_VANGUARD_RANGED));
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.AKKHA_SHADOW, NpcID.AKKHA_MELEE));
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.GARGBOSS_DAWN_DEATH, NpcID.GARGBOSS_DUSK_PHASE4));
		// Outside any group, nothing.
		assertFalse(EncounterGroup.otherFallEndsFight(NpcIds.GOBLIN, NpcIds.GOBLIN));
	}
}

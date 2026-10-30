package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-092: only a weapon that hits several targets splashes. Eric's Araxxor kill
 * of 2026-10-29 ([7487]-[7737] of the 17:11 log, nothing copied): the Zombie axe
 * on Araxxor between Hunters' sunlight crossbow shots at an Acidic araxyte was
 * read as the shots' splash, and the araxyte's multiplier re-fitted from the
 * axe's experience.
 */
public class SingleTargetSplatTest
{
	private static final int ARAXXOR = 13668;
	private static final int ARAXYTE = 13675;

	@Test
	public void singleTargetWeaponsNeverSplash()
	{
		// The two weapons of the log's pattern: the axe and the crossbow each hit one target.
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.ZOMBIE_AXE, null));
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.HUNTING_CROSSBOW_SUNLIGHT, null));
		// Multi-hit weapons that hit their one target several times.
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.DUAL_MACUAHUITL, null));
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.DARKBOW, null));
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.TWINFLAME_STAFF, Spell.FIRE_WAVE.getDisplayName()));
		// Single-target spells.
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.KODAI_WAND, Spell.SHADOW_RUSH.getDisplayName()));
		assertFalse(PvmPerformancePlugin.hitsSeveralTargets(ItemID.KODAI_WAND, Spell.ICE_BLITZ.getDisplayName()));
	}

	@Test
	public void multiTargetWeaponsStillSplash()
	{
		// The Hallowfell of Eric's demon trip, chinchompas, the venator bow, the scythe's arc, bursts and barrages.
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(PvmPerformancePlugin.HALLOWFELL, null));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.CHINCHOMPA_CAPTURED, null));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.CHINCHOMPA_BIG_CAPTURED, null));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.CHINCHOMPA_BLACK, null));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.VENATOR_BOW, null));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.SCYTHE_OF_VITUR, null));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.KODAI_WAND, Spell.ICE_BARRAGE.getDisplayName()));
		assertTrue(PvmPerformancePlugin.hitsSeveralTargets(ItemID.KODAI_WAND, Spell.SMOKE_BURST.getDisplayName()));
	}

	@Test
	public void aScythesSplatsOnOneTargetAreStillOneAttack()
	{
		// Its splats on one big target are counted against the one attack as before; only its arc is splash.
		final AttackSplats scythe = AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 3, null);
		assertEquals(3, scythe.count);
		final AttackSplats.Burst blow = new AttackSplats.Burst(100, 101, scythe, scythe, false, "Scythe of vitur");
		assertTrue(blow.give(101));
		assertTrue(blow.give(101));
		assertFalse(blow.give(101));
	}

	/** An axe blow on Araxxor ({@code damage} at his x1.6) or a crossbow shot at the araxyte (x1.075). */
	private static XpLedger.Award attack(XpLedger ledger, int tick, int npc, Skill skill, int damage, double m)
	{
		ledger.add(skill, (int) Math.floor(4 * damage * m));
		ledger.add(Skill.HITPOINTS, (int) Math.floor(4.0 / 3.0 * damage * m));
		return ledger.close(tick, npc, m, null);
	}

	@Test
	public void experienceAnotherNpcCouldHavePaidTeachesNothing()
	{
		final XpLedger ledger = new XpLedger();
		// [7529]: the axe's drop, read at the araxyte's bonus as it was then, and its 23 landing on Araxxor.
		final XpLedger.Award axe = attack(ledger, 7529, ARAXYTE, Skill.STRENGTH, 23, 1.6);
		ledger.spend(23, 7530, true, ARAXXOR);
		assertTrue(axe.mixed);
		// [7522]-[7525]: a shot at the araxyte paid and landed there: clean.
		final XpLedger.Award shot = attack(ledger, 7522, ARAXYTE, Skill.RANGED, 26, 1.075);
		ledger.spend(26, 7525, true, ARAXYTE);
		assertFalse(shot.mixed);
		ledger.expire(7600);
		// One clean attack and one mixed: nothing re-fitted, and no check failed for the mixed one.
		assertEquals(1.075, ledger.multiplierFor(ARAXYTE, 1.075), 1e-9);
		assertTrue(ledger.trustSummary(), ledger.trustSummary().startsWith("1/1"));
	}

	@Test
	public void cleanAttacksStillTeachIt()
	{
		final XpLedger ledger = new XpLedger();
		// Two clean axe blows on Araxxor, paid at x1.6 but read at x1.675 to start with: re-fitted, as before.
		attack(ledger, 7497, ARAXXOR, Skill.STRENGTH, 62, 1.6);
		ledger.spend(62, 7498, true, ARAXXOR);
		attack(ledger, 7502, ARAXXOR, Skill.STRENGTH, 51, 1.6);
		ledger.spend(51, 7503, true, ARAXXOR);
		ledger.expire(7600);
		assertEquals(1.6, ledger.multiplierFor(ARAXXOR, 1.675), 1e-9);
		assertEquals(2, XpLedger.CALIBRATION_MIN_ATTACKS);
	}
}

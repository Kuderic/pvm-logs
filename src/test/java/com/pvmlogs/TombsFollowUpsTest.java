package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-177, from Eric's duo Tombs of 2026-11-04 (build {@code 8e328d6}) and his solo one of 2026-11-05 17:22 ({@code 7cc7dcf}),
 * read in place, nothing copied.
 */
public class TombsFollowUpsTest
{
	private static final int ELIDINIS = 48625;
	private static final int GHOST = NpcID.ARCEUUS_THRALL_GHOST_GREATER;
	private static final int THRALL = 61001;

	@Test
	public void anAttackOnTheCoreThatClosesBeforeItLandsBooksNothing()
	{
		// [84213]: 11755 (her core out) -> 11754 (poise): the core closing.
		assertTrue(PvmPerformancePlugin.coreClosed(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE));
		assertTrue(PvmPerformancePlugin.coreClosed(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED,
			NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE));
		// Her core coming out, a change of style, or her death into phase 3: not it.
		assertFalse(PvmPerformancePlugin.coreClosed(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED));
		assertFalse(PvmPerformancePlugin.coreClosed(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE));
		assertFalse(PvmPerformancePlugin.coreClosed(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE3));
		// The attack in the air at her, scored against the core ([84212]: thrown 84210, lands 84213): nulled. One scored as
		// poise, or at another npc, isn't.
		assertTrue(PvmPerformancePlugin.landsNowhere(ELIDINIS, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, ELIDINIS));
		assertTrue(PvmPerformancePlugin.landsNowhere(ELIDINIS, NpcID.TOA_WARDEN_ELIDINIS_CORE, ELIDINIS));
		assertFalse(PvmPerformancePlugin.landsNowhere(ELIDINIS, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, ELIDINIS));
		assertFalse(PvmPerformancePlugin.landsNowhere(ELIDINIS + 1, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, ELIDINIS));
		// 11-05 [3442]: one booked on the closing tick, after the change, lands on her in poise: nowhere too. One scored as
		// the core landing on her with it out, or as poise, lands as ever.
		assertTrue(PvmPerformancePlugin.coreClosedFor(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE));
		assertFalse(PvmPerformancePlugin.coreClosedFor(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED));
		assertFalse(PvmPerformancePlugin.coreClosedFor(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE));
		assertFalse(PvmPerformancePlugin.coreClosedFor(NpcID.AKKHA_MELEE, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE));
	}

	@Test
	public void aKillingCoreHitWithNoCoreSplatTakesAFifth()
	{
		// [84419]: her last 200 passed on, and no splat on the core: its 40.
		assertEquals(40, PvmPerformancePlugin.unseenCoreHit(200));
		assertEquals(48, PvmPerformancePlugin.unseenCoreHit(240));
		assertEquals(0, PvmPerformancePlugin.unseenCoreHit(0));
		// The passed-on, 5940, is five times the core's 1148 and that 40.
		assertEquals(5940, 5 * (1148 + PvmPerformancePlugin.unseenCoreHit(200)));
		// To the core's part, with the attack's own (no landing of its own: the passed-on splat landed it).
		final Fight fight = new Fight("Elidinis' Warden", NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, ELIDINIS, 5940, 0L, null,
			0);
		fight.recordExtraTarget(40, 1L, false, false);
		fight.recordWardensDamage(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 40);
		assertEquals(40, fight.getDamageDealt());
		assertEquals(40, fight.getWardensP2().core.damage);
	}

	private static ThrallTracker summoned(int tick)
	{
		final ThrallTracker t = new ThrallTracker();
		t.onThrallSpawned(THRALL, GHOST, 1, tick);
		t.onResurrectionVarbit(true, tick + 5);
		return t;
	}

	private static ThrallTracker.Evidence zero(int tick, ThrallTracker.PlayerSplat player)
	{
		final ThrallTracker.Evidence e = new ThrallTracker.Evidence();
		e.amount = 0;
		e.tick = tick;
		e.targetIndex = ELIDINIS;
		e.player = player;
		// Poise: certain to land.
		e.playerAccuracy = 1.0;
		e.playerMaxHit = 14;
		return e;
	}

	@Test
	public void thePoiseZerosAreTheThrallsLandingAsSheTurns()
	{
		assertTrue(PvmPerformancePlugin.wardenCollapses(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE,
			PvmPerformancePlugin.WARDEN_COLLAPSE_ANIM));
		assertFalse("her core out", PvmPerformancePlugin.wardenCollapses(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED,
			PvmPerformancePlugin.WARDEN_COLLAPSE_ANIM));
		assertFalse("another animation", PvmPerformancePlugin.wardenCollapses(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 9661));
		// 11-05 [3301]: its projectile, lands 3302, found no splat by 3303; [3304] her collapse; [3309] a 0 as she turns,
		// with no attack of his in the air: judged his at rate 0.001 before.
		final ThrallTracker t = summoned(3200);
		t.onThrallProjectile(THRALL, ELIDINIS, 3301, 3302);
		t.tick(3303);
		final double before = t.probability(zero(3309, ThrallTracker.PlayerSplat.UNEXPLAINED));
		assertTrue(before < 0.5);
		assertEquals(1, t.holdThroughCollapse(ELIDINIS, 3304, 3304 + PvmPerformancePlugin.WARDEN_COLLAPSE_TICKS));
		assertTrue(t.probability(zero(3309, ThrallTracker.PlayerSplat.UNEXPLAINED)) > 0.95);
		// 11-05 [3399], the same with a melee blow of his landing on the tick she turned ("LATE"): still the thrall's.
		assertTrue(t.probability(zero(3309, ThrallTracker.PlayerSplat.LATE)) > 0.85);
		// Only a 0: a hit of 1 to 3 on her then is judged as before.
		final ThrallTracker.Evidence two = zero(3309, ThrallTracker.PlayerSplat.UNEXPLAINED);
		two.amount = 2;
		final ThrallTracker u = summoned(3200);
		u.onThrallProjectile(THRALL, ELIDINIS, 3301, 3302);
		u.tick(3303);
		final double twoBefore = u.probability(two);
		u.holdThroughCollapse(ELIDINIS, 3304, 3312);
		assertEquals(twoBefore, u.probability(two), 1e-12);
		// After she turned, nothing is held.
		assertTrue(t.probability(zero(3313, ThrallTracker.PlayerSplat.UNEXPLAINED)) < 0.5);
		// Claimed once.
		t.noteThrallSplat(ELIDINIS, 3309);
		assertFalse(t.heldOn(ELIDINIS, 3310));
	}

	@Test
	public void aProjectileStillDueAsSheCollapsesIsHeldToo()
	{
		// 11-05 [3394]: its projectile and her collapse on the same tick; [3399] the 0.
		final ThrallTracker t = summoned(3300);
		t.onThrallProjectile(THRALL, ELIDINIS, 3394, 3395);
		assertEquals(1, t.holdThroughCollapse(ELIDINIS, 3394, 3402));
		t.tick(3396);
		assertTrue(t.heldOn(ELIDINIS, 3399));
		// 11-05 [3515]: her collapse first, its projectile at [3516] (lands 3517), the 0 at [3520]: held as it found no splat.
		final ThrallTracker w = summoned(3400);
		w.holdThroughCollapse(ELIDINIS, 3515, 3523);
		w.onThrallProjectile(THRALL, ELIDINIS, 3516, 3517);
		w.tick(3518);
		assertTrue(w.heldOn(ELIDINIS, 3520));
		assertTrue(w.probability(zero(3520, ThrallTracker.PlayerSplat.UNEXPLAINED)) > 0.95);
		// One missed long before her collapse isn't.
		final ThrallTracker u = summoned(3300);
		u.onThrallProjectile(THRALL, ELIDINIS, 3380, 3381);
		u.tick(3383);
		u.tick(3390);
		assertEquals(0, u.holdThroughCollapse(ELIDINIS, 3394, 3402));
	}

	@Test
	public void akkhasBarFallAsHeLeftViewIsTheShotLandingThen()
	{
		// 11-05 [2511]: his bar 149 of 990, read as his; [2514] he leaves view as the shot thrown at [2512] lands, and the bar,
		// still naming 11790, reads 101 with no splat.
		final int akkha = 34089;
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(akkha, NpcID.AKKHA_MELEE, 990, 2114);
		h.damage(akkha, NpcID.AKKHA_MELEE, 841, 2509);
		h.hud(akkha, NpcID.AKKHA_MELEE, 149, 990, 2511);
		assertEquals(48, h.unseenFall(akkha, NpcID.AKKHA_MELEE, 101, 990));
		// The shot's max 50: a 48 it could deal, booked as its hit. More than its max, or no shot landing then: not.
		assertTrue(PvmPerformancePlugin.hitUnseenAsItLeft(48, 50));
		assertFalse(PvmPerformancePlugin.hitUnseenAsItLeft(48, 40));
		assertFalse(PvmPerformancePlugin.hitUnseenAsItLeft(48, -1));
		assertFalse(PvmPerformancePlugin.hitUnseenAsItLeft(0, 50));
		// A splat counted since the reading isn't unseen; nor a bar naming another id or another pool.
		h.damage(akkha, NpcID.AKKHA_MELEE, 20, 2513);
		assertEquals(28, h.unseenFall(akkha, NpcID.AKKHA_MELEE, 101, 990));
		assertEquals(0, h.unseenFall(akkha, NpcID.AKKHA_RANGE, 101, 990));
		assertEquals(0, h.unseenFall(akkha, NpcID.AKKHA_MELEE, 101, 1040));
		assertEquals(0, h.unseenFall(akkha, NpcID.AKKHA_MELEE, 140, 990));
	}

	@Test
	public void kephrisEggsAreNotScored()
	{
		assertTrue(EncounterGroup.isUnscored(NpcID.KEPHRI_EGG_HATCH));
		assertTrue(EncounterGroup.isUnscored(NpcID.KEPHRI_EGG_EXPLODE));
		// Still in her room.
		assertEquals("Kephri", EncounterGroup.of(NpcID.KEPHRI_EGG_HATCH));
	}
}

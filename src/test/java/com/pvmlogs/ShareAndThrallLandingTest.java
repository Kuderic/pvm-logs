package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-101: Eric's Araxxor log of 2026-10-29 21:40 (build aee9461, read in
 * place, nothing copied).
 * <ul>
 * <li>A Mirrorback araxyte's share of the axe's hit on Araxxor booked a whole
 * expected hit of its own: about 30 for about 5, 14 times.</li>
 * <li>A projectile our thrall set off on 2227, landing 2228, outlived the
 * thrall's despawn on 2227; its 0 on Araxxor was taken as the player's and the
 * crossbow shot at a Mirrorback was booked to Araxxor.</li>
 * </ul>
 */
public class ShareAndThrallLandingTest
{
	private static final int ARAXXOR = 49486;
	private static final int MIRRORBACK = 52337;
	private static final int GHOST = NpcID.ARCEUUS_THRALL_GHOST_GREATER;

	@Test
	public void aMirrorbacksShareAddsDamageButNoRoll()
	{
		// [235]: the axe's 32 on Araxxor and the Mirrorback's 7 on the same tick. The axe hits one target.
		assertFalse(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.ZOMBIE_AXE, null));
		final Fight fight = new Fight("Araxxor", NpcID.ARAXXOR, ARAXXOR, 1020, 0, null, 0);
		fight.recordAttackMade(false);
		fight.recordExpected(61, 0.84, 25.69, 393.4);
		fight.recordDamageDealt(32, 1, true);
		// The share, as recordExtraTarget books it with no roll of its own: no target, no hit, no expectation.
		fight.recordExtraTarget(7, 2, false, false);
		assertEquals(39, fight.getDamageDealt());
		assertEquals(25.69, fight.getSumExpectedAverageHit(), 1e-9);
		assertEquals(1, fight.resolvedTargets());
		assertEquals(1, fight.getHits());
	}

	@Test
	public void aBarrageOnThreeNpcsBringsThreeRolls()
	{
		// A barrage booked from its cast: each npc it reaches is rolled against.
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(true, ItemID.KODAI_WAND, Spell.ICE_BARRAGE.getDisplayName()));
		// Chinchompas, the venator bow, the Hallowfell and the scythe, as T-092 has them.
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.CHINCHOMPA_BLACK, null));
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.VENATOR_BOW, null));
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(false, PvmPerformancePlugin.HALLOWFELL, null));
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.SCYTHE_OF_VITUR, null));
		final Fight fight = new Fight("Dust devil", 423, 1, 105, 0, null, 0);
		fight.recordAttackMade(false);
		fight.recordExpected(30, 0.9, 13.5, 80);
		fight.recordDamageDealt(20, 1, true);
		for (int i = 0; i < 2; i++)
		{
			fight.recordExtraTarget(15, 2, true, true);
			fight.recordExpected(30, 0.9, 13.5, 80);
		}
		assertEquals(40.5, fight.getSumExpectedAverageHit(), 1e-9);
		assertEquals(3, fight.resolvedTargets());
	}

	@Test
	public void theInvisibleBarrageStillBringsItsRolls()
	{
		// Cast while being hit, it plays BLOCK and nothing books it: the last attack booked says nothing, so the staff and
		// the autocast held now decide.
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.KODAI_WAND, Spell.ICE_BARRAGE.getDisplayName()));
		assertTrue(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.KODAI_WAND, Spell.SMOKE_BURST.getDisplayName()));
		// A single-target spell is a share, like any single-target weapon.
		assertFalse(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.KODAI_WAND, Spell.ICE_BLITZ.getDisplayName()));
		assertFalse(PvmPerformancePlugin.shareHasOwnRoll(false, ItemID.HUNTING_CROSSBOW_SUNLIGHT, null));
	}

	/** Our greater ghost, claimed as it spawned beside the player on its summon. */
	private static ThrallTracker ourGhost(int index, int tick)
	{
		final ThrallTracker thralls = new ThrallTracker();
		thralls.onSummonCast(tick);
		thralls.onThrallSpawned(index, GHOST, 1, tick);
		assertTrue(thralls.hasThrall());
		return thralls;
	}

	@Test
	public void aThrallsLastProjectileLandsAsItsOwnAfterItDespawns()
	{
		// [2227]: projectile 1907 at Araxxor, landing 2228, then the thrall despawns on the same tick.
		final ThrallTracker thralls = ourGhost(49732, 2200);
		thralls.onThrallProjectile(49732, ARAXXOR, 2227);
		thralls.onThrallDespawned(49732, 2227);
		assertFalse(thralls.hasThrall());
		// Not on the Mirrorback, and nothing a greater thrall cannot deal.
		assertFalse(thralls.lastProjectileLands(MIRRORBACK, 0, 2228));
		assertFalse(thralls.lastProjectileLands(ARAXXOR, 4, 2228));
		// [2228]: its 0 on Araxxor is the thrall's, once.
		assertTrue(thralls.lastProjectileLands(ARAXXOR, 0, 2228));
		assertFalse(thralls.lastProjectileLands(ARAXXOR, 0, 2228));
	}

	@Test
	public void aThrallsProjectileSeenAsItWentIsItsToo()
	{
		// The despawn handled first and the projectile after it on that tick: still ours, by the thrall it came from.
		final ThrallTracker thralls = ourGhost(49732, 2200);
		thralls.onThrallDespawned(49732, 2227);
		thralls.onThrallProjectile(49732, ARAXXOR, 2227);
		assertTrue(thralls.lastProjectileLands(ARAXXOR, 2, 2228));
	}

	@Test
	public void aThrallsLastProjectileIsGoneOnceItsWindowHasPassed()
	{
		final ThrallTracker thralls = ourGhost(49732, 2200);
		thralls.onThrallProjectile(49732, ARAXXOR, 2227);
		thralls.onThrallDespawned(49732, 2227);
		assertFalse(thralls.lastProjectileLands(ARAXXOR, 0, 2230));
		// A thrall that went with nothing in the air leaves nothing to land.
		final ThrallTracker idle = ourGhost(1, 100);
		idle.onThrallDespawned(1, 105);
		assertFalse(idle.lastProjectileLands(ARAXXOR, 0, 106));
		// A reset forgets it.
		final ThrallTracker reset = ourGhost(1, 100);
		reset.onThrallProjectile(1, ARAXXOR, 104);
		reset.clear();
		assertFalse(reset.lastProjectileLands(ARAXXOR, 0, 105));
	}

	@Test
	public void aShotLandsOnlyOnTheNpcItWasAimedAt()
	{
		// [2228]: the crossbow shot aimed at the Mirrorback, while a splat on Araxxor had opened a fight there.
		final Fight onAraxxor = new Fight("Araxxor", NpcID.ARAXXOR, ARAXXOR, 1020, 0, null, 0);
		final Fight onMirrorback = new Fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, MIRRORBACK, 58, 0, null,
			0);
		assertTrue(PvmPerformancePlugin.belongsToAimedNpc(true, false, MIRRORBACK, onAraxxor));
		// Already the fight on it: nothing to move.
		assertFalse(PvmPerformancePlugin.belongsToAimedNpc(true, false, MIRRORBACK, onMirrorback));
		// A weapon that hits several targets: its splat on another npc may well be its own.
		assertFalse(PvmPerformancePlugin.belongsToAimedNpc(true, true, MIRRORBACK, onAraxxor));
		// A melee blow is proved by its splat, wherever it lands.
		assertFalse(PvmPerformancePlugin.belongsToAimedNpc(false, false, MIRRORBACK, onAraxxor));
		// Nothing chosen, or no npc known: as before.
		assertFalse(PvmPerformancePlugin.belongsToAimedNpc(true, false, MIRRORBACK, null));
		assertFalse(PvmPerformancePlugin.belongsToAimedNpc(true, false, -1, onAraxxor));
	}

	@Test
	public void theKillingBlowIsBookedAtTheHitpointsBeforeIt()
	{
		// [2917]: the axe's 27 with 35 left, max 63, chance to land 0.84. The fight books the `scored as` figure, read
		// from the attack's own tick (the expectedByTick row): capped at the 35 left before the blow.
		final double land = 21.18 / CombatCalc.cappedAverage(63, 35);
		assertEquals(0.842, land, 0.001);
		assertEquals(21.18, land * CombatCalc.cappedAverage(63, 35), 0.01);
		// The ATTACK line's 6.26 is the setup figure for efficiency, read live at booking, after the blow: capped at the 8
		// left by then. It feeds the gear and prayer shares, not expected damage.
		assertEquals(6.26, land * CombatCalc.cappedAverage(63, 8), 0.01);
		// [2922]: the last blow, 5 left before it: 4.01 booked. The ATTACK line's 26.53 was read with the target dead.
		assertEquals(4.01, land * CombatCalc.cappedAverage(63, 5), 0.01);
	}
}

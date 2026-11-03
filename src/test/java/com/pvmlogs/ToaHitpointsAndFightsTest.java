package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-153, from Eric's solo Tombs of Amascut of 2026-11-02 (raid level 340; build ea86ada, read in place, nothing copied),
 * with his second raid of that night (raid level 320) as a cross-check: the hitpoints the count starts from, the splats
 * that aren't the player's attacks, and the fights on things that aren't monsters.
 */
public class ToaHitpointsAndFightsTest
{
	private static final int LEVEL = 340;

	// ---- 1. hitpoints ----

	@Test
	public void everyBossBarOfTheRaidComesOutOfItsTableFigure()
	{
		// Path levels as the bars fit them: Zebak 1, Kephri 2, Akkha 2, Ba-Ba 3.
		assertEquals(1480, RaidScaling.tombsHitpoints(580, NpcID.TOA_ZEBAK, LEVEL, 1, 1));
		assertEquals(400, RaidScaling.tombsHitpoints(150, NpcID.TOA_KEPHRI_BOSS_SHIELDED, LEVEL, 2, 1));
		assertEquals(210, RaidScaling.tombsHitpoints(80, NpcID.TOA_KEPHRI_BOSS_ENRAGE, LEVEL, 2, 1));
		assertEquals(1070, RaidScaling.tombsHitpoints(400, NpcID.AKKHA_MELEE, LEVEL, 2, 1));
		assertEquals(1060, RaidScaling.tombsHitpoints(380, NpcID.TOA_BABA, LEVEL, 3, 1));
		// The Wardens take no path level.
		assertEquals(610, RaidScaling.tombsHitpoints(260, NpcID.TOA_WARDENS_P1_OBELISK_NPC, LEVEL, 3, 1));
		assertEquals(330, RaidScaling.tombsHitpoints(140, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, LEVEL, 0, 1));
		assertEquals(2080, RaidScaling.tombsHitpoints(880, NpcID.TOA_WARDEN_TUMEKEN_PHASE3, LEVEL, 0, 1));
		// With the core out, her own 4500 and a tenth of the scaling, whatever the table says: the bar's 6030.
		assertEquals(6030, RaidScaling.tombsHitpoints(140, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, LEVEL, 0, 1));
		assertEquals(6030, RaidScaling.tombsHitpoints(-1, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED, LEVEL, 0, 1));
		// The second raid, at 320: Ba-Ba and Kephri on path level 1.
		assertEquals(940, RaidScaling.tombsHitpoints(380, NpcID.TOA_BABA, 320, 1, 1));
		assertEquals(370, RaidScaling.tombsHitpoints(150, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 320, 1, 1));
	}

	@Test
	public void theAddsThatScaleDieOnTheirScaledFigure()
	{
		// Akkha's shadows: killed on 185 four times (weapon and thrall: 176 + 9, 170 + 15, 168 + 17, 172 + 13). 186 is
		// rounded to the nearest 5. Before: 165, so the count ran out 20 early and the expected went uncapped.
		assertEquals(185, RaidScaling.tombsHitpoints(70, NpcID.AKKHA_SHADOW, LEVEL, 2, 1));
		// Kephri's overlords: 105 here; 98 in the second raid, at 100 or under not rounded (two kills on 98).
		assertEquals(105, RaidScaling.tombsHitpoints(40, NpcID.TOA_KEPHRI_GUARDIAN_MELEE, LEVEL, 2, 1));
		assertEquals(98, RaidScaling.tombsHitpoints(40, NpcID.TOA_KEPHRI_GUARDIAN_MAGE, 320, 1, 1));
	}

	@Test
	public void theSmallMonstersKeepTheirTableFigures()
	{
		// Each died on exactly its table figure: eggs on 35, the swarms on 10, the agile scarabs on 30, the path's baboons
		// on 20 to 40, the puzzle's scarab on 12, Ba-Ba's baboons on 10.
		final int[][] table = {
			{NpcID.KEPHRI_EGG_HATCH, 35}, {NpcID.TOA_KEPHRI_SHIELD_SCARAB, 10}, {NpcID.TOA_KEPHRI_SCARAB_RANGEKITE, 30},
			{NpcID.TOA_PATH_APMEKEN_BABOON_SHAMAN, 40}, {NpcID.TOA_PATH_APMEKEN_BABOON_RANGED_1, 30},
			{NpcID.TOA_PATH_APMEKEN_BABOON_RANGED_2, 35}, {NpcID.TOA_PATH_APMEKEN_BABOON_CURSED, 20},
			{NpcID.TOA_PATH_APMEKEN_BABOON_MAGIC_1, 20}, {NpcID.TOA_PATH_APMEKEN_BABOON_ZOMBIE, 8},
			{NpcID.TOA_SCABARAS_SCARAB, 12}, {NpcID.TOA_BABA_BABOON, 10}, {NpcID.TOA_BABA_BOULDER_WEAK, 27},
			{NpcID.WARDENS_P3_ORB_BLUE, 1}};
		for (int[] t : table)
		{
			assertFalse("npc " + t[0], RaidScaling.tombsScales(t[0]));
			assertEquals("npc " + t[0], t[1], RaidScaling.tombsHitpoints(t[1], t[0], LEVEL, 3, 1));
		}
		// The Baboon Thrall: its table's 2, but it dies on a 4 (seven kills over two raids), so 4: measured.
		assertFalse(RaidScaling.tombsScales(NpcID.TOA_PATH_APMEKEN_BABOON_THRALL));
		assertEquals(4, RaidScaling.tombsHitpoints(2, NpcID.TOA_PATH_APMEKEN_BABOON_THRALL, LEVEL, 0, 1));
		assertEquals(4, RaidScaling.tombsHitpoints(2, NpcID.TOA_PATH_APMEKEN_BABOON_THRALL, 320, 0, 1));
		// Before: every one of them times 2.36 (eggs 82, shamans 94, swarms 23).
		assertEquals(82, 35 * (100 + 2 * (LEVEL / 5)) / 100);
	}

	@Test
	public void aPartyAddsToTheBossesAsTheWikiSays()
	{
		// Akkha at 340, path 2: 1066 solo before rounding; the 2nd and 3rd add 90% each, the 4th on 60%.
		assertEquals(2030, RaidScaling.tombsHitpoints(400, NpcID.AKKHA_MELEE, LEVEL, 2, 2));
		assertEquals(2980, RaidScaling.tombsHitpoints(400, NpcID.AKKHA_MELEE, LEVEL, 2, 3));
		assertEquals(3620, RaidScaling.tombsHitpoints(400, NpcID.AKKHA_MELEE, LEVEL, 2, 4));
		// Never on a monster that doesn't scale.
		assertEquals(35, RaidScaling.tombsHitpoints(35, NpcID.KEPHRI_EGG_HATCH, LEVEL, 2, 4));
	}

	// ---- the count: a bar's new pool, the overhead bar, the full figure ----

	@Test
	public void kephrisSecondBarIsAPoolOfItsOwn()
	{
		final int index = 39167;
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 400, 31119, true, true);
		health.hud(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 400, 400, 31120);
		// Her shielded form takes the thrall's splats (her shield splats aren't hitpoints).
		health.damage(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 45, 31300);
		health.hud(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 0, 400, 31324);
		// [31325]: 11721 at 210 of 210. The count starts it at nothing taken.
		health.hud(index, NpcID.TOA_KEPHRI_BOSS_ENRAGE, 210, 210, 31325);
		assertEquals(0, health.damageCounted(index));
		assertEquals(210, health.maxHp(index));
		health.damage(index, NpcID.TOA_KEPHRI_BOSS_ENRAGE, 210, 31389);
		// RECONCILE at the kill: 210 against 210, where before it read 325 against 210 (+115).
		assertEquals(210, health.damageCounted(index) - health.healed(index));
		assertTrue(health.drainNotes().get(0).startsWith("HP POOL"));
	}

	@Test
	public void theP2WardensPoiseBarAndHerHitpointsAreTwoPools()
	{
		final int index = 35621;
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 330, 33090, true, true);
		health.hud(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 0, 330, 33091);
		// The thrall's splats while her poise fills: 52 in the raid, none of them off her 6030.
		health.damage(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 30, 33150);
		health.hud(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 6030, 6030, 33162);
		health.damage(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 2370, 33186);
		// The core goes back in: her poise bar again, its count taken up where it was.
		health.hud(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, 0, 330, 33189);
		assertEquals(30, health.damageCounted(index));
		health.damage(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, 22, 33200);
		// Out again: her 6030 with the 2370 taken.
		health.hud(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 3660, 6030, 33250);
		assertEquals(2370, health.damageCounted(index));
		health.damage(index, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 3660, 33407);
		// The kill: 6030 against 6030, where before the thrall's 52 put it at +52.
		assertEquals(6030, health.damageCounted(index));
		assertEquals(6030, health.maxHp(index));
	}

	@Test
	public void akkhasEnrageGivesBackAFifthAndTheCountTakesItAsHealed()
	{
		final int index = 62329;
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(index, NpcID.AKKHA_MELEE, 1070, 31505, true, true);
		health.hud(index, NpcID.AKKHA_MELEE, 1070, 1070, 31506);
		health.damage(index, NpcID.AKKHA_MAGE, 1062, 32045);
		health.hud(index, NpcID.AKKHA_MAGE, 8, 1070, 32045);
		health.damage(index, NpcID.AKKHA_ENRAGE_SPAWN, 8, 32049);
		health.hud(index, NpcID.AKKHA_ENRAGE_SPAWN, 0, 1070, 32050);
		// [32051]: his enraged form with 214 of 1070.
		health.hud(index, NpcID.AKKHA_ENRAGE_INITIAL, 214, 1070, 32051);
		assertEquals(214, health.healed(index));
		assertEquals(214, health.countedRemaining(index));
		health.damage(index, NpcID.AKKHA_ENRAGE, 214, 32132);
		// RECONCILE at the kill: 1284 - 214 = 1070 against 1070, where before it was 970 with 756 "seen on its bar".
		assertEquals(1070, health.damageCounted(index) - health.healed(index));
		// A form change with the bar in step with the count is no heal; nor is a bar that fills without one.
		final TargetHealth steady = new TargetHealth();
		steady.setTableMaxHp(1, NpcID.AKKHA_MELEE, 1070, 0, true, true);
		steady.hud(1, NpcID.AKKHA_MELEE, 1070, 1070, 1);
		steady.damage(1, NpcID.AKKHA_MELEE, 100, 2);
		steady.hud(1, NpcID.AKKHA_RANGE, 971, 1070, 3);
		assertEquals(0, steady.healed(1));
		steady.hud(1, NpcID.AKKHA_RANGE, 1000, 1070, 4);
		assertEquals(0, steady.healed(1));
	}

	@Test
	public void noOverheadBarBoundOnATombsBossEvenWithItsBossBarRead()
	{
		// [31226]: Kephri's overhead bar at 112/140 with her 400 read once: the count, which her shield splats don't
		// touch, had more left than that, and 69 went down as "taken unseen". Akkha's 756, Ba-Ba's 851 and Tumeken's
		// Warden's 1763 came the same way.
		final int index = 39167;
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 400, 0, true, true);
		health.hud(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 400, 400, 1);
		assertEquals(0, health.boundByRatio(index, NpcID.TOA_KEPHRI_BOSS_SHIELDED, 112, 140, 0, 31226));
		assertEquals(0, health.unseenDamage(index));
	}

	@Test
	public void theFullFigureIsTrustedOnlyWhereItCantBeShort()
	{
		final TargetHealth health = new TargetHealth();
		// A Baboon Thrall's table figure, which doesn't scale: trusted.
		health.setTableMaxHp(1, NpcID.TOA_PATH_APMEKEN_BABOON_THRALL, 4, 0, false, false);
		assertEquals(4, health.trustedMaxHp(1));
		// A shadow's, from a solo party that may not be the party: not, until the boss bar says.
		health.setTableMaxHp(2, NpcID.AKKHA_SHADOW, 185, 0, true, true);
		assertEquals(-1, health.trustedMaxHp(2));
		health.hud(2, NpcID.AKKHA_SHADOW, 185, 185, 1);
		assertEquals(185, health.trustedMaxHp(2));
		assertEquals(-1, health.trustedMaxHp(3));
	}

	// ---- 5. no expected past a monster's life ----

	@Test
	public void aShotAtATinyMonsterIsCappedAtItsLife()
	{
		// [32416]: a crossbow shot at a 4-hitpoint Baboon Thrall, booked after its own 4 had counted it to 0: read with no
		// cap (max 42, chances 0.96, avg 20.18, var 164.6), and the landing cap unknown at 0 left not yet seen dead. Its
		// full 4 caps it now.
		final int capNow = PvmPerformancePlugin.landingCap(-1, 4);
		assertEquals(4, capNow);
		final double[] capped = LandingCap.recap(42, 0.96, 20.18, 164.6, -1, capNow);
		assertNotNull(capped);
		assertTrue(capped[0] <= 4);
		// [32428]: one thrown before its fight opened (read uncapped, 17.18), landing on the thrall's 4.
		final double[] early = LandingCap.recap(36, 0.95, 17.18, 122.9, -1, 4);
		assertNotNull(early);
		assertTrue(early[0] <= 4);
		// [32383]: a fight's opening blow, scored from the calculator's own figures (max 24, chances 0.91, avg 10.97),
		// which the thrall's 4 hadn't capped though capThen read 4: a mean above what is left was never capped at it.
		final double[] opening = LandingCap.recap(24, 0.91, 10.97, 58.8, 4, 4);
		assertNotNull(opening);
		assertTrue(opening[0] <= 4);
		// Before: unknown either side left them as they were.
		assertEquals(-1, PvmPerformancePlugin.landingCap(-1, -1));
		assertEquals(3, PvmPerformancePlugin.landingCap(3, 4));
	}

	// ---- 2. the 1890 ----

	@Test
	public void aSplatNoAttackCouldDealIsNotAnAttack()
	{
		// [33075]: 1890 on Tumeken's Warden at the end of phase one, his max 40.
		assertTrue(PvmPerformancePlugin.notAnAttack(1890, 40, 0));
		// His real ones: the Keris's 48 over a max of 31 (T-152's), a dragon dagger special's 48s.
		assertFalse(PvmPerformancePlugin.notAnAttack(48, 31, 0));
		assertFalse(PvmPerformancePlugin.notAnAttack(48, 42, 48));
		// A Keris's triple on a max the calculator has at 31: 144, still an attack.
		assertFalse(PvmPerformancePlugin.notAnAttack(144, 31, 0));
		// Nothing small, whatever the max; nothing when no max is known.
		assertFalse(PvmPerformancePlugin.notAnAttack(190, 20, 0));
		assertFalse(PvmPerformancePlugin.notAnAttack(1890, 0, 0));
	}

	// ---- 3. phase two's splats ----

	@Test
	public void theCoresDamagePassedOnIsHerHitpointsNotAnAttack()
	{
		// Type 43 is DAMAGE_MAX_ME: melee on the core always deals its max (wiki, Core), so 42 and 48 show as max hits,
		// and the Warden takes five times them. The 60 (type 16) was a 12.
		assertTrue(PvmPerformancePlugin.coreDamagePassedOn(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 210));
		assertTrue(PvmPerformancePlugin.coreDamagePassedOn(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 240));
		assertTrue(PvmPerformancePlugin.coreDamagePassedOn(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED, 60));
		// A thrall's 1 to 3 is never one; nor anything on her with the core in, where the player's splats are poise.
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 3));
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 0));
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 210));
		// In the fight: no weapon damage, a landing all the same, and RECONCILE's own term.
		final Fight fight = new Fight("Elidinis' Warden", NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, 35621, 6030, 0L, null, 0);
		fight.recordDamageDealt(0, 1L, PvmPerformancePlugin.countsLanding(true, fight.isScored()));
		fight.recordPassedOn(210);
		assertEquals(0, fight.getDamageDealt());
		assertEquals(1, fight.getHits());
		assertEquals(210, fight.getPassedOn());
	}

	// ---- 4. things that aren't monsters, and unscored fights ----

	@Test
	public void boulderAndSiphonHitsKeepTheBossesFightOpen()
	{
		for (int id : new int[]{NpcID.TOA_BABA_BOULDER, NpcID.TOA_BABA_BOULDER_WEAK})
		{
			assertTrue(EncounterGroup.isUnscored(id));
			assertTrue(EncounterGroup.addKeepsBossFight(id, NpcID.TOA_BABA));
			assertTrue(EncounterGroup.addKeepsBossFight(id, NpcID.TOA_BABA_DIGGING));
		}
		for (int id : new int[]{NpcID.WARDENS_P3_ORB_BLUE, NpcID.WARDENS_P3_ORB_RED})
		{
			assertTrue(EncounterGroup.isUnscored(id));
			assertTrue(EncounterGroup.addKeepsBossFight(id, NpcID.TOA_WARDEN_TUMEKEN_PHASE3));
			assertTrue(EncounterGroup.addKeepsBossFight(id, NpcID.TOA_WARDEN_ELIDINIS_PHASE3));
		}
		// Ba-Ba's baboons are fought: their own fights, scored.
		assertFalse(EncounterGroup.isUnscored(NpcID.TOA_BABA_BABOON));
		assertFalse(EncounterGroup.addKeepsBossFight(NpcID.TOA_BABA_BABOON, NpcID.TOA_BABA));
	}

	@Test
	public void phaseTwosObeliskIsNoFight()
	{
		assertTrue(EncounterGroup.isIgnored(NpcID.TOA_WARDENS_P2_OBELISK_NPC));
		assertTrue(EncounterGroup.isUnattackable(NpcID.TOA_WARDENS_P2_OBELISK_NPC));
		// Phase one's is the fight it was.
		assertFalse(EncounterGroup.isIgnored(NpcID.TOA_WARDENS_P1_OBELISK_NPC));
	}

	@Test
	public void anAddDyingKeepsWhatItsBossHasLeft()
	{
		// [31591]-[31601]: the shadow's death cleared Akkha's count, so he came back counted at 944 where his bar said 762.
		final Encounter room = new Encounter("Akkha", null, 0);
		final Fight onAkkha = new Fight("Akkha", NpcID.AKKHA_MELEE, 62329, 1070, 0, null, 0);
		onAkkha.recordAttackMade(false);
		room.add(onAkkha);
		final Fight onShadow = new Fight("Akkha's Shadow", NpcID.AKKHA_SHADOW, 63198, 185, 0, null, 0);
		room.add(onShadow);
		assertTrue(PvmPerformancePlugin.roomGoesOnAtDeath(room, onShadow, NpcID.AKKHA_SHADOW));
		for (int id : new int[]{NpcID.KEPHRI_EGG_HATCH, NpcID.TOA_KEPHRI_GUARDIAN_RANGED, NpcID.TOA_KEPHRI_SCARAB_RANGEKITE,
			NpcID.TOA_KEPHRI_SHIELD_SCARAB, NpcID.TOA_BABA_BABOON})
		{
			assertFalse("npc " + id, EncounterGroup.lootIsTheKill(id));
		}
		// His own death is the kill.
		assertFalse(PvmPerformancePlugin.roomGoesOnAtDeath(room, onAkkha, NpcID.AKKHA_MELEE));
	}

	@Test
	public void anUnscoredFightCountsNoLandings()
	{
		// The Scarab Swarms: "landed 1 of 0".
		final Fight swarm = new Fight("Scarab Swarm", NpcID.TOA_KEPHRI_SHIELD_SCARAB, 43187, 10, 0L, null, 0);
		assertFalse(swarm.isScored());
		swarm.recordDamageDealt(10, 1L, PvmPerformancePlugin.countsLanding(true, swarm.isScored()));
		assertEquals(0, swarm.getHits());
		assertEquals(10, swarm.getDamageDealt());
		assertTrue(PvmPerformancePlugin.countsLanding(true, true));
		assertNull(EncounterGroup.of(99_999));
	}
}

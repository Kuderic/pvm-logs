package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * T-175, from Eric's solo Tombs of 2026-11-05 17:22 (build {@code 7cc7dcf}, read in place, nothing copied).
 * <ul>
 * <li>Ba-Ba (index 49404) wore 11778, 11780, 11778, 11780 and, from [1756], 11779: her coffin form, in no group. The
 * fight opened on her after a baboon at [1780] had no group, counted from 0 and lost her path level (870 for her 990),
 * and her kill reconciled 266 against 990. Each baboon he hit split her fight.</li>
 * <li>Akkha (index 34089) teleported away during his first shadow, while the shadow was fought: his count was forgotten
 * with him, and the piece on him from [2189] started from 0, 198 short of his bar to the kill.</li>
 * </ul>
 */
public class BabaAndAkkhaOneCountTest
{
	private static final int BABA = 49404;
	private static final int AKKHA = 34089;
	private static final int WORLD = 394;
	private static final int RAID = 1;

	@Test
	public void herFormsAreOneFightWithOneCount()
	{
		for (int form : new int[]{NpcID.TOA_BABA, NpcID.TOA_BABA_DIGGING, NpcID.TOA_BABA_COFFIN})
		{
			assertEquals("Ba-Ba", EncounterGroup.of(form));
			assertTrue(EncounterGroup.isBossForm(form));
		}
		final Encounter room = new Encounter("Ba-Ba", RaidType.TOMBS_OF_AMASCUT, 0L);
		final Fight onHer = new Fight("Ba-Ba", NpcID.TOA_BABA, BABA, 990, 0L, null, 1509);
		// Her form changing on her index keeps her fight; a switch to her coffin form is within her room, counts kept.
		assertTrue(PvmPerformancePlugin.switchesWithinRoom(room, onHer, NpcID.TOA_BABA_COFFIN));
		assertTrue(PvmPerformancePlugin.keepsCountsOnSwitch(room, onHer, NpcID.TOA_BABA_COFFIN, false));
		// The count on her index, through all five pieces of her bar.
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(BABA, NpcID.TOA_BABA, 990, 1509);
		h.damage(BABA, NpcID.TOA_BABA, 372, 1613);
		h.damage(BABA, NpcID.TOA_BABA_DIGGING, 41, 1647);
		h.damage(BABA, NpcID.TOA_BABA, 254, 1727);
		h.damage(BABA, NpcID.TOA_BABA_DIGGING, 15, 1755);
		h.damage(BABA, NpcID.TOA_BABA_COFFIN, 308, 1857);
		assertEquals(990, h.damageCounted(BABA));
	}

	@Test
	public void herBossRuleCoversEveryForm()
	{
		// Her defence floor (60 of 80) on all three, as her drain carried across them ([1756]: npc 11779, defence 69 -> 70).
		final BossRules rules = BossRules.load(new Gson(), null);
		for (int form : new int[]{NpcID.TOA_BABA, NpcID.TOA_BABA_DIGGING, NpcID.TOA_BABA_COFFIN})
		{
			final BossRules.Boss boss = rules.forNpc(form);
			assertNotNull("npc " + form, boss);
			assertEquals("Ba-Ba", boss.name);
			assertEquals(60, boss.defenceDrain.defenceFloor(80));
		}
	}

	@Test
	public void aBaboonHitLeavesHerFightOpen()
	{
		assertTrue(EncounterGroup.isUnscored(NpcID.TOA_BABA_BABOON));
		for (int form : new int[]{NpcID.TOA_BABA, NpcID.TOA_BABA_DIGGING, NpcID.TOA_BABA_COFFIN})
		{
			assertTrue(EncounterGroup.addKeepsBossFight(NpcID.TOA_BABA_BABOON, form));
		}
		assertFalse(EncounterGroup.isBossForm(NpcID.TOA_BABA_BABOON));
	}

	@Test
	public void herPathLevelHoldsInHerCoffin()
	{
		// Apmeken's path level 2, as RAID HP read it on 11778 ([1509]); on 11779 it read 0 ([1780]: 870).
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> "getVarbitValue".equals(method.getName())
				? ((Integer) args[0] == VarbitID.TOA_CLIENT_APMEKEN_LEVEL ? 2 : 0)
				: method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false : null);
		assertEquals(2, RaidScaling.tombsPathLevel(client, NpcID.TOA_BABA));
		assertEquals(2, RaidScaling.tombsPathLevel(client, NpcID.TOA_BABA_COFFIN));
		assertEquals(990, RaidScaling.tombsHitpoints(380, NpcID.TOA_BABA_COFFIN, 325, 2, 1));
	}

	@Test
	public void akkhasCountCarriesThroughHisPiecesAndHisEnrage()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(AKKHA, NpcID.AKKHA_MELEE, 990, 2114);
		h.damage(AKKHA, NpcID.AKKHA_MELEE, 198, 2139);
		h.hud(AKKHA, NpcID.AKKHA_MELEE, 792, 990, 2139);
		// [2145]: his shadow fought, and he leaves the scene alive.
		assertTrue(PvmPerformancePlugin.parksOnLeaving(true, false, NpcID.AKKHA_MELEE));
		assertTrue(h.parkGone(AKKHA, WORLD, RAID, 198));
		assertEquals(0, h.damageCounted(AKKHA));
		// [2189]: back in another form, on the same index: the 198 carries on.
		final TargetHealth.Parked kept = h.unpark(AKKHA, NpcID.AKKHA_RANGE, WORLD, RAID);
		assertNotNull(kept);
		assertEquals(198, kept.extra);
		h.setTableMaxHp(AKKHA, NpcID.AKKHA_RANGE, 990, 2189);
		assertEquals(198, h.damageCounted(AKKHA));
		h.damage(AKKHA, NpcID.AKKHA_RANGE, 198, 2243);
		assertTrue(h.parkGone(AKKHA, WORLD, RAID, 396));
		assertNotNull(h.unpark(AKKHA, NpcID.AKKHA_MELEE, WORLD, RAID));
		h.damage(AKKHA, NpcID.AKKHA_MELEE, 198, 2359);
		assertTrue(h.parkGone(AKKHA, WORLD, RAID, 594));
		assertNotNull(h.unpark(AKKHA, NpcID.AKKHA_MAGE, WORLD, RAID));
		h.damage(AKKHA, NpcID.AKKHA_MAGE, 198, 2469);
		h.hud(AKKHA, NpcID.AKKHA_MAGE, 198, 990, 2469);
		// [2544]: to 0; [2547]: his enrage heals 198 of his 990 (a heal splat), and the bar shows it: once, not twice.
		h.damage(AKKHA, NpcID.AKKHA_MAGE, 198, 2544);
		h.hud(AKKHA, NpcID.AKKHA_MAGE, 0, 990, 2544);
		h.hud(AKKHA, NpcID.AKKHA_ENRAGE_SPAWN, 0, 990, 2545);
		h.heal(AKKHA, NpcID.AKKHA_ENRAGE_SPAWN, 198, 2547);
		h.hud(AKKHA, NpcID.AKKHA_ENRAGE_INITIAL, 198, 990, 2547);
		h.damage(AKKHA, NpcID.AKKHA_ENRAGE, 198, 2591);
		assertEquals(1188, h.damageCounted(AKKHA));
		assertEquals(198, h.healed(AKKHA));
		assertEquals("the kill reconciles with his bar", 990, h.damageCounted(AKKHA) - h.healed(AKKHA));
	}

	@Test
	public void onlyABossFormAliveInARaidIsKept()
	{
		assertFalse("his shadow", PvmPerformancePlugin.parksOnLeaving(true, false, NpcID.AKKHA_SHADOW));
		assertFalse("dead", PvmPerformancePlugin.parksOnLeaving(true, true, NpcID.AKKHA_MELEE));
		assertFalse("outside a raid", PvmPerformancePlugin.parksOnLeaving(false, false, NpcID.AKKHA_MELEE));
		assertFalse("a baboon", PvmPerformancePlugin.parksOnLeaving(true, false, NpcID.TOA_BABA_BABOON));
		assertFalse("a monster on its own", PvmPerformancePlugin.parksOnLeaving(true, false, NpcID.TOA_SCABARAS_SCARAB));
		// Another boss on the index isn't his.
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(AKKHA, NpcID.AKKHA_MELEE, 990, 0);
		h.damage(AKKHA, NpcID.AKKHA_MELEE, 198, 1);
		h.parkGone(AKKHA, WORLD, RAID, 198);
		assertNull(h.unpark(AKKHA, NpcID.TOA_BABA, WORLD, RAID));
		// Nothing on the index: nothing parked, and what was parked for it stands.
		final TargetHealth g = new TargetHealth();
		g.setTableMaxHp(AKKHA, NpcID.AKKHA_MELEE, 990, 0);
		g.damage(AKKHA, NpcID.AKKHA_MELEE, 198, 1);
		g.park(AKKHA, WORLD, RAID, 198);
		g.clear();
		assertFalse(g.parkGone(AKKHA, WORLD, RAID, 0));
		assertNotNull(g.unpark(AKKHA, NpcID.AKKHA_RANGE, WORLD, RAID));
		assertTrue(EncounterGroup.sameBoss(NpcID.AKKHA_MELEE, NpcID.AKKHA_ENRAGE));
		assertFalse(EncounterGroup.sameBoss(NpcID.AKKHA_MELEE, NpcID.AKKHA_SHADOW));
	}
}

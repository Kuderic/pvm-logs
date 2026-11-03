package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-163: bosses whose adds weren't grouped, so every hit on one ended the boss's fight. Venenatis in Eric's log of
 * 2026-11-03 07:00 (build 45a1015, read in place, nothing copied): 31 fights on her and 46 on her spiderlings over 7
 * kills, each spiderling "a different target was attacked", a spiderling run, then a new Venenatis fight on the same index.
 */
public class BossAddsGroupedTest
{
	@Test
	public void venenatisAndSpindelKeepTheirFightThroughTheirSpiderlings()
	{
		assertTrue(EncounterGroup.sameGroup(NpcID.VENENATIS, NpcID.VENENATIS_SPIDERLING));
		assertTrue(EncounterGroup.sameGroup(NpcID.VENENATIS_SINGLES, NpcID.SPINDEL_SPIDERLING));
		assertFalse(EncounterGroup.sameGroup(NpcID.VENENATIS, NpcID.SPINDEL_SPIDERLING));
		// Swatted in passing: unscored, and a hit on one leaves her fight open.
		assertTrue(EncounterGroup.addKeepsBossFight(NpcID.VENENATIS_SPIDERLING, NpcID.VENENATIS));
		assertTrue(EncounterGroup.addKeepsBossFight(NpcID.SPINDEL_SPIDERLING, NpcID.VENENATIS_SINGLES));
		assertTrue(EncounterGroup.isUnscored(NpcID.VENENATIS_SPIDERLING));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.VENENATIS_SPIDERLING));
		// Her own death is the kill.
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.VENENATIS));
		assertEquals("Venenatis", EncounterGroup.of(NpcID.VENENATIS_SPIDERLING));
	}

	@Test
	public void addsSwattedInPassingKeepTheBossFightOpen()
	{
		final int[][] swatted = {
			{NpcID.SCORPIA_MINION, NpcID.SCORPIA},
			{NpcID.RAT_BOSS_GIANT_RAT, NpcID.RAT_BOSS_INSTANCE},
			{NpcID.RAT_BOSS_GIANT_RAT, NpcID.RAT_BOSS_NORMAL},
			{NpcID.VORKATH_SPAWN, NpcID.VORKATH}};
		for (int[] p : swatted)
		{
			assertTrue("npc " + p[0], EncounterGroup.addKeepsBossFight(p[0], p[1]));
			assertTrue("npc " + p[0], EncounterGroup.isUnscored(p[0]));
			assertFalse("npc " + p[0], EncounterGroup.lootIsTheKill(p[0]));
		}
	}

	@Test
	public void addsThatAreAPhaseAreFightsOfTheirOwnInTheBosssRoom()
	{
		final int[][] phase = {
			{NpcID.VETION_HELLHOUND_JNR, NpcID.VETION}, {NpcID.VETION_HELLHOUND_SNR, NpcID.VETION_2},
			{NpcID.VETION_HELLHOUND_JNR_SINGLES, NpcID.VETION_SINGLE},
			{NpcID.VETION_HELLHOUND_SNR_SINGLES, NpcID.VETION_2_SINGLE},
			{NpcID.SCORPIA_GUARDIAN, NpcID.SCORPIA},
			{NpcID.SARACHNIS_MELEE_SPAWN, NpcID.SARACHNIS}, {NpcID.SARACHNIS_MAGE_SPAWN, NpcID.SARACHNIS},
			{NpcID.GB_GROWTHLING, NpcID.GB_MOSSGIANT},
			{NpcID.NEX_SMOKEMAGE, NpcID.NEX}, {NpcID.NEX_SHADOWMAGE, NpcID.NEX}, {NpcID.NEX_BLOODMAGE, NpcID.NEX},
			{NpcID.NEX_ICEMAGE, NpcID.NEX}};
		for (int[] p : phase)
		{
			assertTrue("npc " + p[0], EncounterGroup.sameGroup(p[0], p[1]));
			assertFalse("npc " + p[0], EncounterGroup.addKeepsBossFight(p[0], p[1]));
			assertFalse("npc " + p[0], EncounterGroup.lootIsTheKill(p[0]));
		}
		// The ones fought for their own sake are scored; the growthlings, killed with an axe or secateurs, aren't.
		assertFalse(EncounterGroup.isUnscored(NpcID.VETION_HELLHOUND_JNR));
		assertFalse(EncounterGroup.isUnscored(NpcID.NEX_ICEMAGE));
		assertTrue(EncounterGroup.isUnscored(NpcID.GB_GROWTHLING));
	}

	@Test
	public void theBossesFormsAreOneBoss()
	{
		assertTrue(EncounterGroup.sameGroup(NpcID.VETION, NpcID.VETION_TRANS_2));
		assertTrue(EncounterGroup.sameGroup(NpcID.VETION_SINGLE, NpcID.VETION_TRANS_2_SINGLE));
		assertTrue(EncounterGroup.sameGroup(NpcID.NEX, NpcID.NEX_DYING));
		assertTrue(EncounterGroup.sameGroup(NpcID.VORKATH_SLEEPING, NpcID.VORKATH));
		// An add's death doesn't end the boss's fight; the boss's own forms are it.
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.VETION_HELLHOUND_JNR, NpcID.VETION));
	}
}

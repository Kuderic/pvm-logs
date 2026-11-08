package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-183: Olm's counts between his head and claws, from Eric's five solo Chambers runs of 2026-11-06 11:38 (build
 * {@code 7eca1b6}, read in place, nothing copied).
 * <ul>
 * <li>Run 5: the head hit for 100 while the right claw had 4 left, then back to 800 of 800 with no heal splat: its kill
 * reconciled 902 against 800. The turn from the head to that claw dropped the claw's 598: its kill reconciled 4.</li>
 * <li>Run 2: the head's first piece (92 after 55 healed) was lost across a 404-tick despawn: its kill reconciled 715.</li>
 * </ul>
 */
public class OlmCountsTest
{
	private static final int HEAD = 34122;
	private static final int RIGHT = 38199;
	private static final int WORLD = 386;
	private static final int RAID = 5;

	@Test
	public void theHeadHealedToFullWithNoSplatResetsItsCount()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(HEAD, NpcID.OLM_HEAD, 800, 161964);
		h.damage(HEAD, NpcID.OLM_HEAD, 100, 161967);
		h.hud(HEAD, NpcID.OLM_HEAD, 700, 800, 161967);
		// [161980]: 800 of 800, nothing counted since.
		h.hud(HEAD, NpcID.OLM_HEAD, 800, 800, 161980);
		assertEquals(100, h.healed(HEAD));
		// Its kill: the 800 dealt after, against its 800.
		h.damage(HEAD, NpcID.OLM_HEAD, 802, 162187);
		assertEquals(802, h.damageCounted(HEAD) - h.healed(HEAD));
	}

	@Test
	public void aBarBrieflyHighOrFillingIsNoHeal()
	{
		// Not back to full: a bar a step high is left to the tolerance it always had.
		assertFalse(TargetHealth.refilledToFull(800, 700, NpcID.OLM_HEAD, NpcID.OLM_HEAD, 760, 800, 0));
		// Something counted since its last reading: not a refill.
		assertFalse(TargetHealth.refilledToFull(800, 700, NpcID.OLM_HEAD, NpcID.OLM_HEAD, 800, 800, 20));
		// Another id or another pool: other rules (a change of form, a pool switch).
		assertFalse(TargetHealth.refilledToFull(800, 700, NpcID.OLM_HEAD_SPAWNING, NpcID.OLM_HEAD, 800, 800, 0));
		assertFalse(TargetHealth.refilledToFull(600, 300, NpcID.OLM_HEAD, NpcID.OLM_HEAD, 800, 800, 0));
		// The P2 Warden's poise fills as the player works it: never a heal.
		assertFalse(TargetHealth.refilledToFull(330, 100, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE, 330, 330, 0));
		assertTrue(TargetHealth.refilledToFull(800, 700, NpcID.OLM_HEAD, NpcID.OLM_HEAD, 800, 800, 0));
		// A step within the bar's tolerance isn't either.
		assertFalse(TargetHealth.refilledToFull(800, 799, NpcID.OLM_HEAD, NpcID.OLM_HEAD, 800, 800, 0));
	}

	@Test
	public void clawHeadClawKeepsTheClawsCount()
	{
		final Encounter hands = new Encounter("Great Olm (hands)", RaidType.CHAMBERS_OF_XERIC, 0L);
		final Fight onClaw = new Fight("Great Olm (Right claw)", NpcID.OLM_HAND_RIGHT, RIGHT, 600, 0L,
			RaidType.CHAMBERS_OF_XERIC, 1);
		final Fight onHead = new Fight("Great Olm", NpcID.OLM_HEAD, HEAD, 800, 0L, RaidType.CHAMBERS_OF_XERIC, 1);
		assertTrue(PvmPerformancePlugin.keepsCountsOnSwitch(hands, onClaw, NpcID.OLM_HEAD, false));
		assertTrue("[161973]: the head to the claw at 4 left",
			PvmPerformancePlugin.keepsCountsOnSwitch(null, onHead, NpcID.OLM_HAND_RIGHT, false));
		assertTrue(PvmPerformancePlugin.keepsCountsOnSwitch(null, onHead, NpcID.OLM_HAND_LEFT, false));
		// Nothing else joins them.
		assertFalse(PvmPerformancePlugin.keepsCountsOnSwitch(null, onHead, NpcID.RAIDS_SCAVENGER_BEAST_A, false));
		// So the claw's 598 is on its index when the 4 lands: 602 against its 600.
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(RIGHT, NpcID.OLM_HAND_RIGHT, 600, 161736);
		h.damage(RIGHT, NpcID.OLM_HAND_RIGHT, 598, 161835);
		h.setTableMaxHp(HEAD, NpcID.OLM_HEAD, 800, 161964);
		h.damage(HEAD, NpcID.OLM_HEAD, 100, 161967);
		h.damage(RIGHT, NpcID.OLM_HAND_RIGHT, 4, 161977);
		assertEquals(602, h.damageCounted(RIGHT));
	}

	@Test
	public void theHeadsCountSurvivesA400TickDespawn()
	{
		final int head = 53190;
		final int world = 337;
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(head, NpcID.OLM_HEAD, 800, 55225);
		h.damage(head, NpcID.OLM_HEAD, 147, 55277);
		h.heal(head, NpcID.OLM_HEAD, 55, 55250);
		// [55278]: its fight ends as it despawns, and it's parked (T-155).
		h.park(head, world, RAID, 147);
		h.clear();
		// While the claws are fought it rises and sinks again, not the target, with a bar read on it and nothing counted:
		// what was parked stands (it used to be forgotten here).
		assertTrue(PvmPerformancePlugin.parksOnLeaving(true, false, NpcID.OLM_HEAD));
		h.hud(head, NpcID.OLM_HEAD_SPAWNING, 709, 800, 55500);
		assertFalse(h.parkGone(head, world, RAID, 0));
		// [55682], 404 ticks on: back, with its count.
		final TargetHealth.Parked kept = h.unpark(head, NpcID.OLM_HEAD, world, RAID);
		assertNotNull(kept);
		h.setTableMaxHp(head, NpcID.OLM_HEAD, 800, 55682);
		assertEquals(92, h.damageCounted(head) - h.healed(head));
	}

	@Test
	public void olmsPartsAreTheirOwnFormsAndKeepTheirCountsLeaving()
	{
		assertTrue(EncounterGroup.sameBoss(NpcID.OLM_HEAD_SPAWNING, NpcID.OLM_HEAD));
		assertTrue(EncounterGroup.sameBoss(NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_LEFT_DYING));
		assertFalse("a claw isn't the head", EncounterGroup.sameBoss(NpcID.OLM_HAND_LEFT, NpcID.OLM_HEAD));
		assertFalse(EncounterGroup.sameBoss(NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_RIGHT));
		assertTrue(PvmPerformancePlugin.parksOnLeaving(true, false, NpcID.OLM_HAND_RIGHT));
		assertFalse("dead", PvmPerformancePlugin.parksOnLeaving(true, true, NpcID.OLM_HEAD));
		// The head's fights stay its own: no group.
		assertEquals(null, EncounterGroup.of(NpcID.OLM_HEAD));
	}
}

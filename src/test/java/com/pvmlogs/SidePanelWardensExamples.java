package com.pvmlogs;

import net.runelite.api.gameval.NpcID;

/**
 * T-173: the Wardens room as the plugin records it since T-172, shaped on Eric's duo raid of 2026-11-04 (T-172's figures,
 * scaled down to whole attacks): the Obelisk, phase 2 with its poise, core and passed-on, and phase 3. Each fight is fed
 * through the plugin's own record calls, so the totals are what the panel would read.
 */
final class SidePanelWardensExamples
{
	private SidePanelWardensExamples()
	{
	}

	/**
	 * The room. {@code elidinisDamaged}: Elidinis' Warden is the damaged one in phase 2, as on 11-04, and Tumeken's is
	 * phase 3; false swaps them. {@code withParts}: false for a record saved before T-172 (no phase 2 parts).
	 */
	static SidePanelModel.Input room(boolean elidinisDamaged, boolean withParts)
	{
		final Encounter room = encounter(elidinisDamaged, withParts);
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.ended = true;
		in.killed = true;
		in.endMillis = SidePanelExamples.at(16, 10);
		return in;
	}

	/** The room's fights, as {@link #room} reads them. */
	static Encounter encounter(boolean elidinisDamaged, boolean withParts)
	{
		final Encounter room = new Encounter("Wardens", RaidType.TOMBS_OF_AMASCUT, SidePanelExamples.at(16, 0));
		final Fight obelisk = new Fight("Obelisk", NpcID.TOA_WARDENS_P1_OBELISK_NPC, 1, 1_000, SidePanelExamples.at(16, 0),
			RaidType.TOMBS_OF_AMASCUT, 1);
		for (int i = 0; i < 26; i++)
		{
			attack(obelisk, 21, 24.2, 90, i);
		}
		obelisk.end(true, SidePanelExamples.at(16, 2));
		room.add(obelisk);

		final String p2Name = elidinisDamaged ? "Elidinis' Warden" : "Tumeken's Warden";
		final int poiseId = elidinisDamaged ? NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE : NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE;
		final int exposedId = elidinisDamaged ? NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED
			: NpcID.TOA_WARDEN_TUMEKEN_PHASE2_EXPOSED;
		final int coreId = elidinisDamaged ? NpcID.TOA_WARDEN_ELIDINIS_CORE : NpcID.TOA_WARDEN_TUMEKEN_CORE;
		final Fight p2 = new Fight(p2Name, poiseId, 2, 5_940, SidePanelExamples.at(16, 2), RaidType.TOMBS_OF_AMASCUT, 1);
		// Poise: 55 hits on her with the core in, 941 in all, E 923.4.
		for (int i = 0; i < 55; i++)
		{
			final int hit = i < 6 ? 16 : 17;
			p2.recordAttackMade(true, 1.0);
			p2.recordAttackResolved(true, true, 10, 10);
			p2.recordDamageDealt(hit, SidePanelExamples.at(16, 2), true);
			p2.recordExpected(19, 1.0, 923.4 / 55, 388.5 / 55);
			p2.recordTickSpent(true);
			p2.recordTickSpent(true);
			p2.recordTickSpent(true);
			p2.recordTickSpent(true);
			if (withParts)
			{
				p2.recordWardensDamage(poiseId, hit);
				p2.recordWardensExpected(poiseId, 923.4 / 55, 388.5 / 55);
			}
		}
		// The core: 23 melee hits on the core at its own value (48 a hit, certain), each passed on ×5 to her.
		for (int i = 0; i < 23; i++)
		{
			final int hit = i < 21 ? 48 : 70;
			p2.recordAttackMade(true, 1.0);
			p2.recordAttackResolved(true, true, 10, 10);
			// The passed-on splat on her lands the attack, but deals no weapon damage of its own (T-153).
			p2.recordDamageDealt(0, SidePanelExamples.at(16, 3), true);
			if (withParts)
			{
				// Before T-172 the passed-on wasn't saved (it was transient), so an old record has none.
				p2.recordPassedOn(hit == 48 ? 240 : 350);
			}
			p2.recordExtraTarget(hit, SidePanelExamples.at(16, 3), i == 0, false);
			p2.recordExpected(48, 1.0, 1268.0 / 23, 0);
			p2.recordTickSpent(true);
			p2.recordTickSpent(true);
			p2.recordTickSpent(true);
			p2.recordTickSpent(true);
			if (withParts)
			{
				p2.recordWardensDamage(coreId, hit);
				p2.recordWardensExpected(exposedId, 1268.0 / 23, 0);
			}
		}
		p2.end(false, SidePanelExamples.at(16, 5));
		room.add(p2);

		final String p3Name = elidinisDamaged ? "Tumeken's Warden" : "Elidinis' Warden";
		final Fight p3 = new Fight(p3Name, elidinisDamaged ? NpcID.TOA_WARDEN_TUMEKEN_PHASE3
			: NpcID.TOA_WARDEN_ELIDINIS_PHASE3, 3, 1_880, SidePanelExamples.at(16, 5), RaidType.TOMBS_OF_AMASCUT, 1);
		for (int i = 0; i < 86; i++)
		{
			attack(p3, 22, 20.5, 160, i);
		}
		p3.end(true, SidePanelExamples.at(16, 10));
		room.add(p3);
		return room;
	}

	/** One ordinary attack: hits vary around {@code hit}, scored at {@code mean} with {@code variance}. */
	private static void attack(Fight f, int hit, double mean, double variance, int i)
	{
		f.recordAttackMade(true, 1.0);
		f.recordAttackResolved(true, true, 10, 10);
		f.recordDamageDealt(i % 7 == 0 ? 0 : hit + i % 5 - 2, SidePanelExamples.at(16, 0), i % 7 != 0);
		f.recordExpected(45, 0.85, mean, variance);
		f.recordTickSpent(true);
		f.recordTickSpent(true);
		f.recordTickSpent(true);
		f.recordTickSpent(true);
	}
}

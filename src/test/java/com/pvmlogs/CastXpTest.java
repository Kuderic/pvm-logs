package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * Spells that pay Magic experience and are not attacks (T-024): the ledger
 * takes their Magic out before the award, so a cast between attacks makes none
 * and raises no {@code XP CONTRADICTS BOOKING}. The amounts are the wiki's; the
 * cases are the ones in Eric's logs (see {@link CastXpReplayTest}).
 */
public class CastXpTest
{
	private static final int NPC = 12223;

	@Test
	public void vengeanceAloneMakesNoAward()
	{
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 112);
		assertNull(ledger.close(2803, NPC, 1.55, null));
		assertEquals("CAST not an attack on 2803: Vengeance (+112 Magic)", ledger.castNote());
	}

	@Test
	public void aThrallAndDeathChargeOnOneTickAreBothCasts()
	{
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 178);
		assertNull(ledger.close(1944, NPC, 1.7, null));
		assertEquals("CAST not an attack on 1944: Resurrect Greater thrall and Death Charge (+178 Magic)",
			ledger.castNote());
	}

	@Test
	public void eachThrallSpellAndDeathChargeIsKnown()
	{
		for (int xp : new int[]{55, 70, 88, 90, 130})
		{
			final XpLedger ledger = new XpLedger();
			ledger.add(Skill.MAGIC, xp);
			assertNull("+" + xp, ledger.close(10, NPC, 1.0, null));
			assertNotNull("+" + xp, ledger.castNote());
		}
		// Casts that pay the same are named together.
		assertEquals("Resurrect Superior thrall or Mark of Darkness", XpLedger.castsPaying(70));
	}

	@Test
	public void aCastOnAMeleeTickLeavesTheBlowToDecode()
	{
		// 23:08 log tick 1379: a thrall's 88 beside a 19 with the claws (Attack 118, hitpoints 39 at x1.55).
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.ATTACK, 118);
		ledger.add(Skill.HITPOINTS, 39);
		ledger.add(Skill.MAGIC, 88);
		final XpLedger.Award award = ledger.close(1379, NPC, 1.55, null);
		assertNotNull(award);
		assertEquals(0, award.gains[4]);
		assertEquals(19, award.lowest);
		assertEquals(19, award.highest);
		assertEquals("CAST not an attack on 1379: Resurrect Greater thrall (+88 Magic)", ledger.castNote());
	}

	@Test
	public void magicBesideAControlledHitIsACastWhateverItsAmount()
	{
		// Sire log tick 5343: Death Charge's 90 beside a shared-style 36. Before, the Magic made it undecodable.
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.ATTACK, 48);
		ledger.add(Skill.DEFENCE, 48);
		ledger.add(Skill.STRENGTH, 48);
		ledger.add(Skill.HITPOINTS, 48);
		ledger.add(Skill.MAGIC, 90);
		final XpLedger.Award award = ledger.close(5343, 5908, 1.1, null);
		assertTrue(award.toString(), award.decoded());
		// An amount no cast pays still cannot be the melee blow's.
		final XpLedger other = new XpLedger();
		other.add(Skill.RANGED, 200);
		other.add(Skill.HITPOINTS, 66);
		other.add(Skill.MAGIC, 61);
		assertNotNull(other.close(10, NPC, 1.125, null));
		assertEquals("CAST not an attack on 10: a spell (+61 Magic)", other.castNote());
	}

	@Test
	public void aSplashIsStillAnAttack()
	{
		// Magic alone in an amount no cast pays: a splash, which pays its spell's base (Sire log: 31, 43, 49, 69).
		for (int xp : new int[]{31, 43, 49, 52, 69})
		{
			final XpLedger ledger = new XpLedger();
			ledger.add(Skill.MAGIC, xp);
			assertNotNull("+" + xp, ledger.close(10, NPC, 1.0, null));
			assertNull("+" + xp, ledger.castNote());
		}
	}

	@Test
	public void aMagicAttackIsLeftAlone()
	{
		// Sire log tick 6991: a 16-17 cast, Magic 76 with hitpoints 22. 76 is Vile Vigour's, but hitpoints moved, so it
		// is the attack's base and damage.
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.HITPOINTS, 22);
		ledger.add(Skill.MAGIC, 76);
		final XpLedger.Award award = ledger.close(6991, 5914, 1.0, null);
		assertNotNull(award);
		assertEquals(76, award.gains[4]);
		assertNull(ledger.castNote());
		// A defensive cast pays Defence beside Magic: also the attack's.
		final XpLedger defensive = new XpLedger();
		defensive.add(Skill.DEFENCE, 20);
		defensive.add(Skill.HITPOINTS, 26);
		defensive.add(Skill.MAGIC, 90);
		assertNotNull(defensive.close(10, NPC, 1.0, null));
		assertNull(defensive.castNote());
	}

	@Test
	public void magicAloneWithHitpointsUnreadableIsNotTaken()
	{
		// At 200m hitpoints with no fake drop, a magic attack's damage could be hiding in what Magic paid.
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 112);
		final boolean[] cannotReport = new boolean[XpLedger.SKILLS.length];
		cannotReport[5] = true;
		assertNotNull(ledger.close(10, NPC, 1.0, cannotReport));
		assertNull(ledger.castNote());
	}

	@Test
	public void theNoteIsOnlyTheLastTicks()
	{
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 112);
		ledger.close(10, NPC, 1.0, null);
		ledger.close(11, NPC, 1.0, null);
		assertNull(ledger.castNote());
	}
}

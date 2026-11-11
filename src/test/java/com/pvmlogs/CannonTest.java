package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

/**
 * T-204: the player's own cannon's hits are its own source, as the thrall's are. The numbers are Eric's 2026-11-10 log
 * ({@code pvm-debug-20261110-143739.log}, build {@code 1dff745}, read in place): drakes at x1.025 with a Dragon hunter
 * crossbow, smoke devils at x1.000 with a barrage.
 */
public class CannonTest
{
	private static final int SMOKE_DEVIL = 12745;
	private static final int DRAKE = 1478;

	/** A cannon set up with its south-west tile at (3200, 3200). */
	private static CannonTracker setUp()
	{
		final CannonTracker c = new CannonTracker();
		c.onVarp(VarPlayerID.OWNEDMCANNON, packed(new WorldPoint(3200, 3200, 0)));
		c.onVarp(VarPlayerID.DROPCANNON, CannonTracker.SET_UP);
		return c;
	}

	/** WorldPoint.fromCoord's packing, the other way. */
	private static int packed(WorldPoint p)
	{
		return (p.getPlane() << 28) | (p.getX() << 14) | p.getY();
	}

	@Test
	public void allFourCannonballsAndNothingElse()
	{
		for (int id : new int[]{53, 1443, 2018, 2019})
		{
			assertTrue("projectile " + id, CannonTracker.isCannonball(id));
		}
		// The crossbow's bolt (27) and the smoke devils' own shot (644), from the same log.
		assertFalse(CannonTracker.isCannonball(27));
		assertFalse(CannonTracker.isCannonball(644));
	}

	@Test
	public void theCannonsTilesNotAnotherCannons()
	{
		final CannonTracker c = setUp();
		assertTrue(c.isSetUp());
		// Its 3x3 tiles, the middle one where its balls set off.
		assertTrue(c.fromMyCannon(3201, 3201, 0));
		assertTrue(c.fromMyCannon(3200, 3202, 0));
		// Another player's cannon beside it: its own 3x3, from (3203, 3200).
		assertFalse(c.fromMyCannon(3204, 3201, 0));
		assertFalse("another plane", c.fromMyCannon(3201, 3201, 1));
		// Picked up: no ball is the player's.
		c.onVarp(VarPlayerID.DROPCANNON, 0);
		assertFalse(c.isSetUp());
		assertFalse(c.fromMyCannon(3201, 3201, 0));
		// Never set up this session: none either.
		assertFalse(new CannonTracker().fromMyCannon(3201, 3201, 0));
	}

	@Test
	public void aBallFromTheCannonLandsAsTheCannonsNotTheWeapons()
	{
		final CannonTracker c = setUp();
		// [1015] Ranged +34 with no Hitpoints: the cannon's 17; [1016] its ball and its 17 on the smoke devil.
		c.onPaid(17, 1015);
		c.onBall(SMOKE_DEVIL, 1016, 1016);
		assertTrue(c.claim(SMOKE_DEVIL, 17, 1016, false));
		// Once: the ball is spent, so a second 17 there is someone else's (the weapon's).
		assertFalse(c.claim(SMOKE_DEVIL, 17, 1016, false));
		// No ball at a monster: never the cannon's, whatever it is.
		assertFalse(c.claim(DRAKE, 30, 1016, false));
	}

	@Test
	public void anotherPlayersBallIsIgnored()
	{
		final CannonTracker c = setUp();
		// The plugin books a ball only from the player's own cannon; another's sets off elsewhere and books nothing, so its
		// splat (others' anyway) is never claimed.
		assertFalse(c.fromMyCannon(3210, 3210, 0));
		assertFalse(c.claim(SMOKE_DEVIL, 25, 1020, false));
	}

	@Test
	public void yourBoltAndABallOnOneTickSplitByTheExperience()
	{
		// [7612] Hitpoints +42, Ranged +174 at x1.025: the crossbow's 31 (+42, +127) and the cannon's 23 (+47).
		final XpLedger ledger = new XpLedger();
		ledger.setCannon(true);
		ledger.add(Skill.HITPOINTS, 42);
		ledger.add(Skill.RANGED, 174);
		final XpLedger.Award award = ledger.close(7612, 8612, 1.025, null);
		assertEquals(23, ledger.cannonDamage());
		assertNotNull(award);
		assertTrue(award.toString(), award.toString().contains("31-31"));
		// 23 at x1.025 pays 47.15, read as 46 or 47 as its fractions fall.
		assertTrue(ledger.cannonNote(), ledger.cannonNote().matches("CANNON xp on 7612: \\+4[67] Ranged is the cannon's 23 .*"));
		// [7613] the ball and its 23, then [7614] the bolt's 31: each to its own.
		final CannonTracker c = setUp();
		c.onPaid(ledger.cannonDamage(), 7612);
		c.onBall(DRAKE, 7613, 7613);
		assertTrue(c.claim(DRAKE, 23, 7613, false));
		assertFalse(c.claim(DRAKE, 31, 7614, true));
	}

	@Test
	public void bothOnOneTickTheSplatMatchingThePaidAmountIsTheCannons()
	{
		final CannonTracker c = setUp();
		c.onPaid(18, 7620);
		c.onBall(DRAKE, 7621, 7621);
		// The bolt's 31 lands first: not the cannon's, whose 18 was paid.
		assertFalse(c.claim(DRAKE, 31, 7621, true));
		assertTrue(c.claim(DRAKE, 18, 7621, true));
		// A point of rounding either way (x1.025's fractions).
		c.onPaid(15, 7628);
		c.onBall(DRAKE, 7629, 7629);
		assertTrue(c.claim(DRAKE, 16, 7630, false));
	}

	@Test
	public void aZeroOrAnUnpaidSplatIsTheCannonsOnlyWithoutAWeaponHitThere()
	{
		final CannonTracker c = setUp();
		c.onBall(DRAKE, 100, 101);
		// A miss pays nothing: the cannon's, unless the player's own attack lands on it now.
		assertFalse(c.claim(DRAKE, 0, 101, true));
		assertTrue(c.claim(DRAKE, 0, 101, false));
		// No experience read for it (the ledger untrusted, or at 200m): within the cannon's max, and no weapon hit there.
		c.onBall(DRAKE, 110, 110);
		assertFalse(c.claim(DRAKE, 36, 110, false));
		assertFalse(c.claim(DRAKE, 25, 110, true));
		assertTrue(c.claim(DRAKE, 25, 110, false));
		// Long landed: dropped.
		c.onBall(DRAKE, 120, 120);
		c.expire(125);
		assertFalse(c.ballOn(DRAKE, 121));
	}

	@Test
	public void theCannonsExperienceIsTakenOutBeforeDecoding()
	{
		// [1015] Ranged +34 alone, x1.000: the cannon's 17, and no award (no attack, so no "XP CONTRADICTS BOOKING").
		final XpLedger ledger = new XpLedger();
		ledger.setCannon(true);
		ledger.add(Skill.RANGED, 34);
		assertNull(ledger.close(1015, 498, 1.0, null));
		assertEquals(17, ledger.cannonDamage());
		// [7620] +37 at x1.025: 18.
		ledger.add(Skill.RANGED, 37);
		assertNull(ledger.close(7620, 8612, 1.025, null));
		assertEquals(18, ledger.cannonDamage());
		// Without the cannon set up, Ranged alone is left as it was: an award, undecodable.
		final XpLedger without = new XpLedger();
		without.add(Skill.RANGED, 34);
		assertNotNull(without.close(1015, 498, 1.0, null));
		assertEquals(-1, without.cannonDamage());
	}

	@Test
	public void aBarrageAndABallOnOneTick()
	{
		// A barrage's Magic and Hitpoints, and the cannon's Ranged beside it. Ranged used to make the Magic read as a cast
		// that isn't an attack ("a spell"), so the barrage's experience was thrown away; now all of the Ranged is the
		// cannon's and the barrage decodes.
		final XpLedger ledger = new XpLedger();
		ledger.setCannon(true);
		ledger.add(Skill.MAGIC, 106);
		ledger.add(Skill.HITPOINTS, 36);
		ledger.add(Skill.RANGED, 50);
		final XpLedger.Award award = ledger.close(1019, 498, 1.0, null);
		assertEquals(25, ledger.cannonDamage());
		assertNull(ledger.castNote());
		assertNotNull(award);
		assertFalse(award.toString(), award.toString().contains("undecodable"));
		// The splats: a barrage splash's 27 on the monster beside, and the ball's 25 on this one, one tick.
		final CannonTracker c = setUp();
		c.onPaid(25, 1019);
		c.onBall(SMOKE_DEVIL, 1020, 1020);
		assertFalse(c.claim(SMOKE_DEVIL, 27, 1020, true));
		assertTrue(c.claim(SMOKE_DEVIL, 25, 1020, true));
	}

	@Test
	public void aSplitThatDoesntComeOutWholeIsLeftToTheAttack()
	{
		final int[] g = new int[XpLedger.SKILLS.length];
		// Hitpoints says a 31; Ranged far under what a 31 pays: no cannon damage leaves one attack.
		g[5] = 42;
		g[3] = 60;
		assertNull(XpLedger.cannonSplit(g, 1.025));
		final XpLedger ledger = new XpLedger();
		ledger.setCannon(true);
		ledger.add(Skill.HITPOINTS, 42);
		ledger.add(Skill.RANGED, 60);
		ledger.close(10, 8612, 1.025, null);
		assertEquals(-1, ledger.cannonDamage());
		assertTrue(ledger.cannonNote(), ledger.cannonNote().contains("no cannon damage splits whole"));
		// And a ranged attack alone splits nothing off.
		g[3] = 127;
		assertNull(XpLedger.cannonSplit(g, 1.025));
	}

	@Test
	public void cannonDamageOnAnotherMonsterIsKeptForItsFight()
	{
		final CannonTracker c = setUp();
		c.bank(12753, 25);
		c.bank(12753, 13);
		assertEquals(38, c.takeBanked(12753));
		assertEquals(0, c.takeBanked(12753));
		c.bank(12754, 9);
		c.forget(12754);
		assertEquals(0, c.takeBanked(12754));
	}

	@Test
	public void cannonDamageIsSavedAndInTheCsvButNotTheWeaponsOrLuck()
	{
		final Fight f = new Fight("Drake", 8612, DRAKE, 225, 1000L, null, 0);
		f.recordAttackMade(false, 1.0);
		f.recordExpected(40, 0.5, 20, 100);
		f.recordDamageDealt(31, 1001L, true);
		f.recordCannonDamage(23);
		f.recordThrallDamage(5);
		f.end(true, 2000L);
		assertEquals(31, f.getDamageDealt());
		assertEquals(23, f.getCannonDamage());
		// Luck is the weapon's against its expected: the cannon is neither.
		assertEquals(31 - 20, f.getDamageDealt() - f.getSumExpectedAverageHit(), 1e-9);
		final Gson gson = new Gson();
		final Fight back = gson.fromJson(gson.toJson(f), Fight.class);
		assertEquals(23, back.getCannonDamage());
		assertEquals(5, back.getThrallDamage());
		// A fight's row without its scope's four columns (level, raid, raidRun, room).
		final String[] cells = PvmPerformancePlugin.csvRow(back).trim().split(",", -1);
		final java.util.List<String> header = Arrays.asList(PvmPerformancePlugin.CSV_HEADER.trim().split(",", -1));
		assertEquals(header.size() - 4, cells.length);
		assertEquals("23", cells[header.indexOf("cannonDamage") - 4]);
		assertEquals("5", cells[header.indexOf("thrallDamage") - 4]);
		// In the room's and the trip's other damage, as the thrall's.
		final Encounter room = new Encounter("Drake", null, 1000L);
		room.add(back);
		assertEquals(23, room.getCannonDamage());
		assertEquals(28, LedgerRows.of(room).scoredOtherDamage());
		final SessionTotals trip = new SessionTotals(1000L);
		trip.recordCannonDamage(23);
		trip.recordThrallDamage(5);
		assertEquals(28, LedgerRows.of(trip).scoredOtherDamage());
	}

	@Test
	public void damageHasACannonRowBesideTheThrall()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(kill, true);
		in.scoredThrall = 80;
		in.scoredCannon = 120;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.Sources d = SidePanelModel.damage(n, in, false);
		// 612 + 76 + 80 + 120 = 888.
		assertEquals("888 yours", d.hint.text);
		assertEquals("Thrall", d.rows.get(2).name);
		final PanelSnapshot.Source cannon = d.rows.get(3);
		assertEquals("Cannon", cannon.name);
		assertEquals("120", cannon.damage);
		assertEquals("14%", cannon.share);
		assertEquals(SidePanelStyle.CANNON, cannon.colour);
		assertEquals(ItemID.MCANNONBALL, cannon.itemId);
		// None: no row.
		in.scoredCannon = 0;
		assertEquals(3, SidePanelModel.damage(n, in, false).rows.size());
	}
}

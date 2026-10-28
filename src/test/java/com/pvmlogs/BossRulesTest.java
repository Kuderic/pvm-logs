package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.client.util.Filepath;
import org.junit.Test;

// The bundled boss rules, checked against the DPS calculator they were ported from (weirdgloop/osrs-dps-calc@c446f2e,
// src/lib/scaling/Vardorvis.ts and constants.ts).
public class BossRulesTest
{
	private static final Gson GSON = new Gson();

	private static BossRules bundled()
	{
		return BossRules.load(GSON, null);
	}

	@Test
	public void theBundledFileLoadsAndSaysWhereItCameFrom()
	{
		final BossRules rules = bundled();
		assertTrue(rules.size() >= 9);
		assertTrue(rules.provenance(), rules.provenance().startsWith("weirdgloop/osrs-dps-calc@c446f2e"));
	}

	/** VARDORVIS_IDS in the calculator's constants. */
	@Test
	public void everyVardorvisIdIsCovered()
	{
		final BossRules rules = bundled();
		for (int id : new int[]{12223, 12224, 12228, 12425, 12426, 13656})
		{
			final BossRules.Boss boss = rules.forNpc(id);
			assertNotNull("npc " + id, boss);
			assertEquals("Vardorvis", boss.name);
			assertTrue(boss.defenceDrainImmune);
		}
	}

	@Test
	public void unmodelledBossesSaySo()
	{
		final BossRules rules = bundled();
		assertNotNull(rules.forNpc(13668).unmodelled); // Araxxor
		assertNotNull(rules.forNpc(14176).unmodelled); // Yama
		assertNotNull(rules.forNpc(16640).unmodelled); // Bork
		assertNull(rules.forNpc(NpcIds.GOBLIN));
	}

	/** Post-quest: defence 215 at full, 145 at none, linear and truncated between. */
	@Test
	public void postQuestDefenceFollowsHitpoints()
	{
		final BossRules.Variant v = BossRules.variantNamed(bundled().forNpc(12223), "Post-quest");
		assertEquals(700, v.maxHp);
		assertEquals(215, BossRules.scaleWithHp(v.scaleWithHp.defence, 700, 700));
		assertEquals(180, BossRules.scaleWithHp(v.scaleWithHp.defence, 350, 700));
		assertEquals(145, BossRules.scaleWithHp(v.scaleWithHp.defence, 0, 700));
		// 699 left: 215 - 70/700 = 214.9, truncated.
		assertEquals(214, BossRules.scaleWithHp(v.scaleWithHp.defence, 699, 700));
		assertEquals(270, BossRules.scaleWithHp(v.scaleWithHp.strength, 700, 700));
		assertEquals(360, BossRules.scaleWithHp(v.scaleWithHp.strength, 0, 700));
	}

	@Test
	public void unknownHitpointsReadAsFull()
	{
		final BossRules.Variant v = BossRules.variantNamed(bundled().forNpc(12223), "Post-quest");
		assertEquals(215, BossRules.scaleWithHp(v.scaleWithHp.defence, -1, 700));
		assertEquals(215, BossRules.scaleWithHp(v.scaleWithHp.defence, 900, 700));
		assertEquals(-1, BossRules.scaleWithHp(v.scaleWithHp.defence, 100, 0));
	}

	@Test
	public void theOtherVersionsMatchTheCalculator()
	{
		final BossRules.Boss vard = bundled().forNpc(12223);
		final BossRules.Variant quest = BossRules.variantNamed(vard, "Quest");
		assertEquals(500, quest.maxHp);
		assertEquals(180, BossRules.scaleWithHp(quest.scaleWithHp.defence, 500, 500));
		assertEquals(130, BossRules.scaleWithHp(quest.scaleWithHp.defence, 0, 500));
		final BossRules.Variant awakened = BossRules.variantNamed(vard, "Awakened");
		assertEquals(1400, awakened.maxHp);
		assertEquals(268, BossRules.scaleWithHp(awakened.scaleWithHp.defence, 1400, 1400));
		assertEquals(181, BossRules.scaleWithHp(awakened.scaleWithHp.defence, 0, 1400));
	}

	@Test
	public void theBarsFullHitpointsPickTheVersion()
	{
		final BossRules.Boss vard = bundled().forNpc(12223);
		assertEquals("Quest", BossRules.choose(vard, 500).version);
		assertEquals("Post-quest", BossRules.choose(vard, 700).version);
		assertEquals("Awakened", BossRules.choose(vard, 1400).version);
		// Before the bar is read, and for a reading no version has: the default, which is the ordinary post-quest fight.
		assertEquals("Post-quest", BossRules.choose(vard, -1).version);
		assertEquals("Post-quest", BossRules.choose(vard, 999).version);
		assertNull(BossRules.choose(bundled().forNpc(13668), 1020));
	}

	@Test
	public void aUsersFileReplacesABossByNameAndAddsNewOnes() throws Exception
	{
		final Path dir = Files.createTempDirectory("boss-rules");
		final Path file = dir.resolve("boss-rules.json");
		Files.write(file, ("{\"schemaVersion\":1,\"bosses\":["
			+ "{\"name\":\"vardorvis\",\"npcIds\":[12223],\"variants\":[{\"version\":\"Post-quest\",\"maxHp\":700,"
			+ "\"default\":true,\"scaleWithHp\":{\"defence\":[200,100]}}]},"
			+ "{\"name\":\"New Boss\",\"npcIds\":[99999],\"unmodelled\":\"not yet\"}]}").getBytes(StandardCharsets.UTF_8));
		final BossRules rules = BossRules.load(GSON, at(file));
		final BossRules.Variant v = BossRules.choose(rules.forNpc(12223), -1);
		assertEquals(200, BossRules.scaleWithHp(v.scaleWithHp.defence, 700, 700));
		// The whole boss is replaced, so ids only the bundled rule listed are no longer Vardorvis's.
		assertNull(rules.forNpc(12224));
		assertEquals("not yet", rules.forNpc(99999).unmodelled);
		// Everything else is still the bundled rules.
		assertNotNull(rules.forNpc(13668));
		assertTrue(rules.provenance().startsWith("weirdgloop/osrs-dps-calc@c446f2e"));
	}

	@Test
	public void aNewerSchemaIsRefusedAndTheBundledRulesStand() throws Exception
	{
		final int newer = BossRules.SCHEMA_VERSION + 1;
		assertNull(BossRules.read(GSON, new StringReader("{\"schemaVersion\":" + newer + ",\"bosses\":[]}")));
		final Path dir = Files.createTempDirectory("boss-rules");
		final Path file = dir.resolve("boss-rules.json");
		Files.write(file, ("{\"schemaVersion\":" + newer + ",\"bosses\":[{\"name\":\"Vardorvis\",\"npcIds\":[12223]}]}")
			.getBytes(StandardCharsets.UTF_8));
		final BossRules rules = BossRules.load(GSON, at(file));
		assertNotNull(BossRules.variantNamed(rules.forNpc(12223), "Awakened"));
	}

	@Test
	public void anUnreadableOverrideIsIgnored() throws Exception
	{
		final Path dir = Files.createTempDirectory("boss-rules");
		final Path file = dir.resolve("boss-rules.json");
		Files.write(file, "{ not json".getBytes(StandardCharsets.UTF_8));
		assertNotNull(BossRules.load(GSON, at(file)).forNpc(12223));
	}

	/** The hotfix file sits in the plugin's data folder (RuneLite's plugin-data folder since T-009), beside the cache. */
	@Test
	public void theOverrideSitsInTheDataFolder() throws Exception
	{
		final Filepath data = Filepath.Unchecked.getRooted(Files.createTempDirectory("plugin-data"));
		final Filepath file = MonsterStatsProvider.rulesOverrideFile(data);
		assertEquals("boss-rules.json", file.getFileName());
		assertEquals(data, file.getParent());
		assertNull(MonsterStatsProvider.rulesOverrideFile(null));
	}

	/** A test-only way in: the plugin gets its Filepath from RuneLite, never from Filepath.Unchecked. */
	private static Filepath at(Path file)
	{
		return Filepath.Unchecked.getRooted(file.getParent()).joinSegment(file.getFileName().toString());
	}

	/** Schemas 2 and 3 only add optional fields, so a hotfix file written for schema 1 still loads. */
	@Test
	public void aSchemaOneFileStillLoads()
	{
		final BossRules.RulesFile old = BossRules.read(GSON, new StringReader(
			"{\"schemaVersion\":1,\"bosses\":[{\"name\":\"Vardorvis\",\"npcIds\":[12223]}]}"));
		assertNotNull(old);
		assertEquals(3, BossRules.SCHEMA_VERSION);
	}

	/**
	 * T-041: a schema-2 file - the one bundled before T-041, a variant with no
	 * attacksOnPlayer - still loads, and its bosses simply have no attack rules.
	 */
	@Test
	public void aSchemaTwoFileStillLoadsWithNoAttackRules()
	{
		final BossRules.RulesFile two = BossRules.read(GSON, new StringReader("{\"schemaVersion\":2,\"bosses\":["
			+ "{\"name\":\"Vardorvis\",\"npcIds\":[12223],\"variants\":[{\"version\":\"Post-quest\",\"maxHp\":700,"
			+ "\"default\":true,\"scaleWithHp\":{\"strength\":[270,360],\"defence\":[215,145]}}]},"
			+ "{\"name\":\"Abyssal Sire\",\"npcIds\":[5886],\"versionByNpcId\":{\"5886\":\"Phase 1\"},"
			+ "\"countedHpMargin\":30}]}"));
		assertNotNull(two);
		final BossRules rules = BossRules.merge(two, null);
		final BossRules.Boss vardorvis = rules.forNpc(12223);
		assertEquals(215, BossRules.scaleWithHp(BossRules.choose(vardorvis, 700).scaleWithHp.defence, 700, 700));
		assertNull(BossRules.attacksFor(vardorvis, 700));
		assertNull(BossRules.attacksFor(rules.forNpc(5886), 400));
		assertEquals(30, rules.forNpc(5886).countedHpMargin);
	}

	/** T-041: the bundled Vardorvis rules read as T-039 wrote them. */
	@Test
	public void vardorvisPostQuestDescribesItsAttacks()
	{
		final BossRules.AttacksOnPlayer attacks = BossRules.attacksFor(bundled().forNpc(12223), 700);
		assertNotNull(attacks);
		assertEquals(1, attacks.rulesVersion);
		assertEquals(6, attacks.waitTicks);
		assertEquals(0.5, attacks.bossHeal.share, 0);
		assertEquals(2, attacks.bossHeal.withinTicks);
		final StringBuilder names = new StringBuilder();
		for (BossRules.AttackRule rule : attacks.attacks)
		{
			names.append(rule.name).append(' ').append(rule.avoidable).append("; ");
		}
		assertEquals("bleed always; head always; axe always; melee offPrayer; melee offPrayer; spike always; "
			+ "spike always; ", names.toString());
		final BossRules.AttackRule melee = attacks.attacks.get(3);
		assertEquals(0.25, melee.prayedShare, 0);
		assertEquals(10, melee.prayedMax);
		// The gaze's "landedTicksBefore" reads into the same field as an animation's "ticksBefore".
		assertEquals(1, attacks.attacks.get(1).projectile.ticksBefore[0]);
		assertEquals("chip", attacks.unnamed);
	}

	/** The Sire's ids and versions, from the wiki's Abyssal Sire page (id1 to id4). */
	@Test
	public void theSiresIdsAreItsPhases()
	{
		final BossRules rules = bundled();
		final BossRules.Boss sire = rules.forNpc(5886);
		assertNotNull(sire);
		assertEquals("Abyssal Sire", sire.name);
		assertEquals("Phase 1", BossRules.versionFor(sire, 5886));
		assertEquals("Phase 1", BossRules.versionFor(sire, 5887));
		assertEquals("Phase 1", BossRules.versionFor(sire, 5888));
		assertEquals("Phase 2", BossRules.versionFor(sire, 5889));
		assertEquals("Phase 2", BossRules.versionFor(sire, 5890));
		assertEquals("Phase 3 (stage 1)", BossRules.versionFor(sire, 5891));
		assertEquals("Phase 3 (stage 2)", BossRules.versionFor(sire, 5908));
		for (int id : new int[]{5886, 5887, 5888, 5889, 5890, 5891, 5908})
		{
			assertEquals("npc " + id, sire, rules.forNpc(id));
		}
		assertNull(BossRules.versionFor(rules.forNpc(12223), 12223));
		assertNull(BossRules.versionFor(sire, 5914));
	}

	/** Leaving phase one heals it back; moving within phase one, or between later phases, does not. */
	@Test
	public void onlyLeavingPhaseOneRestoresTheSire()
	{
		final BossRules.Boss sire = bundled().forNpc(5886);
		assertTrue(BossRules.restoresOnLeaving(sire, 5888, 5889));
		assertTrue(BossRules.restoresOnLeaving(sire, 5887, 5890));
		assertFalse(BossRules.restoresOnLeaving(sire, 5886, 5887));
		assertFalse(BossRules.restoresOnLeaving(sire, 5888, 5888));
		assertFalse(BossRules.restoresOnLeaving(sire, 5890, 5891));
		assertFalse(BossRules.restoresOnLeaving(sire, 5891, 5908));
		assertFalse(BossRules.restoresOnLeaving(bundled().forNpc(12223), 12223, 12224));
		assertFalse(BossRules.restoresOnLeaving(null, 5888, 5889));
		assertEquals(30, sire.countedHpMargin);
		assertEquals(1.125, sire.xpBonus, 1e-9);
	}
}

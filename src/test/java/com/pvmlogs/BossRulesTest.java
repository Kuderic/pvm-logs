package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
		final BossRules rules = BossRules.load(GSON, file);
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
		assertNull(BossRules.read(GSON, new StringReader("{\"schemaVersion\":2,\"bosses\":[]}")));
		final Path dir = Files.createTempDirectory("boss-rules");
		final Path file = dir.resolve("boss-rules.json");
		Files.write(file, ("{\"schemaVersion\":2,\"bosses\":[{\"name\":\"Vardorvis\",\"npcIds\":[12223]}]}")
			.getBytes(StandardCharsets.UTF_8));
		final BossRules rules = BossRules.load(GSON, file);
		assertNotNull(BossRules.variantNamed(rules.forNpc(12223), "Awakened"));
	}

	@Test
	public void anUnreadableOverrideIsIgnored() throws Exception
	{
		final Path dir = Files.createTempDirectory("boss-rules");
		final Path file = dir.resolve("boss-rules.json");
		Files.write(file, "{ not json".getBytes(StandardCharsets.UTF_8));
		assertNotNull(BossRules.load(GSON, file).forNpc(12223));
	}

	/** The hotfix file lives in the plugin's one data folder (T-004), whatever that is called. */
	@Test
	public void theOverrideSitsInTheDataFolder()
	{
		final Path file = MonsterStatsProvider.rulesOverrideFile();
		assertEquals("boss-rules.json", file.getFileName().toString());
		assertEquals(PvmPerformanceConfig.DATA_DIR, file.getParent().getFileName().toString());
	}
}

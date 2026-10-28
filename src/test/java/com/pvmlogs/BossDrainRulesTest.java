package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.StringReader;
import org.junit.Test;

/**
 * T-049: the bundled boss-rules.json's drain limits (schema 4), each against the
 * figure its source gives (the OSRS Wiki, 2026-10-28; sources in the file and in
 * claude/status/08-boss-stats.md).
 */
public class BossDrainRulesTest
{
	private static final Gson GSON = new Gson();
	private static final BossRules RULES = BossRules.load(GSON, null);

	private static BossRules.DrainRules drain(int npcId)
	{
		final BossRules.Boss boss = RULES.forNpc(npcId);
		assertNotNull("no rule for npc " + npcId, boss);
		assertNotNull("no defenceDrain for npc " + npcId, boss.defenceDrain);
		return boss.defenceDrain;
	}

	@Test
	public void aSchemaFourFileStillLoads()
	{
		final BossRules.RulesFile file = BossRules.read(GSON, new StringReader("{\"schemaVersion\":4,\"bosses\":[]}"));
		assertNotNull(file);
		assertEquals(5, BossRules.SCHEMA_VERSION);
	}

	/** DefenceDrainCap's three entries, now data: Verzik immune, Demonic Brutus immune, Doom 30 off its 90. */
	@Test
	public void theOldCapsMovedIn()
	{
		for (int id : new int[]{8369, 8370, 8372, 8374, 10830, 10835, 10847, 10852, 14795, 14798})
		{
			assertTrue(drain(id).immune);
			assertTrue(drain(id).magicUsesDefence);
			assertEquals(200, drain(id).defenceFloor(200));
		}
		assertTrue(drain(15628).immune);
		assertTrue(drain(15629).immune);
		for (int id : new int[]{14707, 14708, 14709})
		{
			assertEquals(60, drain(id).defenceFloor(90));
		}
	}

	/** The absolute floors the wiki gives. */
	@Test
	public void floors()
	{
		assertEquals(100, drain(8388).defenceFloor(200));    // Sotetseg
		assertEquals(120, drain(9425).defenceFloor(150));    // The Nightmare
		assertEquals(120, drain(9416).defenceFloor(150));    // Phosani's Nightmare
		assertEquals(70, drain(11790).defenceFloor(80));     // Akkha
		assertEquals(60, drain(11778).defenceFloor(80));     // Ba-Ba
		assertEquals(60, drain(11719).defenceFloor(80));     // Kephri
		assertEquals(50, drain(11730).defenceFloor(70));     // Zebak
		assertEquals(60, drain(11751).defenceFloor(100));    // the Obelisk
		assertEquals(120, drain(11762).defenceFloor(150));   // the Wardens, phase 3
		assertEquals(120, drain(11761).defenceFloor(180));   // and after their enrage
		assertEquals(125, drain(12078).defenceFloor(200));   // Phantom Muspah
	}

	/** Caps given as levels drained: Nex 10, Araxxor 45, the Hueycoatl 5, Yama 80. */
	@Test
	public void caps()
	{
		assertEquals(250, drain(11278).defenceFloor(260));
		assertEquals(90, drain(13668).defenceFloor(135));
		assertEquals(120, drain(14009).defenceFloor(125));
		assertEquals(120, drain(14017).defenceFloor(125));
		assertEquals(145, drain(14176).defenceFloor(225));
	}

	/** The Fractured Archive: 60% of the base for six bosses, nothing for The Blade. */
	@Test
	public void theFracturedArchive()
	{
		assertEquals(156, drain(16659).defenceFloor(260));   // Archive Guardian
		assertEquals(78, drain(16652).defenceFloor(130));    // Ezekiel
		assertEquals(180, drain(16640).defenceFloor(300));   // Bork
		assertEquals(117, drain(16667).defenceFloor(195));   // Moia
		assertEquals(102, drain(16684).defenceFloor(170));   // Xarth
		assertEquals(129, drain(16674).defenceFloor(215));   // Ocellus, phase 1
		assertEquals(0, drain(16675).defenceFloor(250));     // The Blade
		assertEquals(150, drain(16677).defenceFloor(250));   // Ocellus, phase 3
		assertTrue(drain(16659).carryAcrossActors);
		// The phases are separate bosses, so nothing carries from one to the next.
		assertFalse(RULES.forNpc(16674) == RULES.forNpc(16677));
	}

	@Test
	public void resets()
	{
		assertTrue(drain(11721).resetsOnNpc(11721));          // Kephri's last phase
		assertFalse(drain(11719).resetsOnNpc(11720));
		assertTrue(drain(11719).carryAcrossActors);
		assertTrue(drain(8388).resetsOnVarbit(6447, 2));      // Sotetseg's maze
		assertFalse(drain(8388).resetsOnVarbit(6447, 1));
		assertTrue(RULES.drainVarbits().contains(6447));
		assertTrue(drain(12079).resetsOnNpc(12079));          // Muspah's shield
		assertTrue(drain(7527).resetsOnNpc(7526));            // a Vanguard into its shell
		assertTrue(drain(6611).resetsOnNpc(6612));            // Vet'ion enraged
		assertTrue(drain(11993).resetsOnNpc(11994));          // Calvar'ion enraged
	}

	@Test
	public void specialCases()
	{
		assertTrue(drain(7542).tekton);
		assertTrue(drain(319).godswordDoubled);
		assertTrue(drain(7585).magicUsesDefence);
		assertTrue(drain(12191).immuneIn("Awakened, Awake"));
		assertFalse(drain(12191).immuneIn("Post-quest, Awake"));
		assertTrue(drain(12214).immuneIn("Awakened"));
		assertTrue(drain(12204).immuneIn("awakened"));
		assertFalse(drain(12204).immuneIn("Quest"));
		// Vardorvis keeps the schema-1 flag.
		assertTrue(RULES.forNpc(12223).defenceDrainImmune);
	}

	/** Regeneration: a level every 100 ticks unless the wiki says faster. */
	@Test
	public void regeneration()
	{
		assertEquals(100, drain(11719).regenTicks());          // Kephri: the default
		assertEquals(20, drain(319).regenTicks());             // Corp
		assertEquals(5, drain(9425).regenTicks());             // The Nightmare, 20 a minute
		assertEquals(5, drain(9416).regenTicks());             // Phosani
		assertEquals(15, drain(7540).regenTicks());            // Tekton
		assertEquals(50, drain(11278).regenTicks());           // Nex
		assertEquals(20, drain(11789).regenTicks());           // Akkha
		assertEquals(5, drain(2042).regenTicks());             // Zulrah
		assertEquals(BossRules.DrainRules.DEFAULT_REGEN_TICKS, drain(16640).regenTicks());
	}

	/** Every drain rule says where it came from. */
	@Test
	public void everyRuleHasASource()
	{
		final BossRules.RulesFile file = BossRules.read(GSON, new java.io.InputStreamReader(
			BossRules.class.getResourceAsStream("boss-rules.json"), java.nio.charset.StandardCharsets.UTF_8));
		assertNotNull(file);
		int ruled = 0;
		for (BossRules.Boss b : file.bosses)
		{
			if (b.defenceDrain != null)
			{
				ruled++;
				assertNotNull(b.name, b.defenceDrain.source);
				assertTrue(b.name, b.defenceDrain.source.contains("wiki") || b.defenceDrain.source.contains("Wiki"));
			}
		}
		assertTrue(ruled >= 40);
	}

	/** No rule at all: drains to 0. */
	@Test
	public void noRuleDrainsToZero()
	{
		assertNull(RULES.forNpc(2054));
		final BossRules.DrainRules none = new BossRules.DrainRules();
		assertEquals(0, none.defenceFloor(240));
		assertEquals(BossRules.DrainRules.DEFAULT_REGEN_TICKS, none.regenTicks());
	}
}

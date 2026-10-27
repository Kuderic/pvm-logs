package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Test;

/**
 * T-020: the Abyssal Sire wears seven ids across its phases, and the DPS calc's data
 * lists four of them (5886, 5889, 5891, 5908; monsters.json at c446f2e, copied into
 * the sample as it stands). Each id is scored with its own version's stats, and the
 * last stage has half the defences of the rest. Before the rule, 5887, 5888 and 5890
 * fell to the name lookup, which picked the first of the four with a warning that
 * they share hitpoints.
 */
public class SirePhaseStatsTest
{
	private static final Gson GSON = new Gson();

	private MonsterStatsProvider provider;

	private static Client client()
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				final Class<?> type = method.getReturnType();
				if (type == int.class)
				{
					return 0;
				}
				if (type == boolean.class)
				{
					return false;
				}
				if (type == long.class)
				{
					return 0L;
				}
				return null;
			});
	}

	/** A provider holding the sample data and the bundled rules, as the plugin's holds the real ones. */
	static MonsterStatsProvider provider() throws Exception
	{
		final Client client = client();
		final MonsterStatsProvider provider = new MonsterStatsProvider(null, GSON, null, client,
			new DebugLog(client, null), new TargetHealth(), null);
		final BossRules rules = BossRules.load(GSON, null);
		try (InputStreamReader in = new InputStreamReader(
			SirePhaseStatsTest.class.getResourceAsStream("monsters-sample.json"), StandardCharsets.UTF_8))
		{
			provider.publish(MonsterStatsProvider.parse(GSON, in, rules));
		}
		return provider;
	}

	@Before
	public void load() throws Exception
	{
		provider = provider();
	}

	@Test
	public void everyIdIsItsOwnPhase()
	{
		assertEquals("Phase 1", provider.get(5886).getVersion());
		assertEquals("Phase 1", provider.get(5887).getVersion());
		assertEquals("Phase 1", provider.get(5888).getVersion());
		assertEquals("Phase 2", provider.get(5889).getVersion());
		assertEquals("Phase 2", provider.get(5890).getVersion());
		assertEquals("Phase 3 (stage 1)", provider.get(5891).getVersion());
		assertEquals("Phase 3 (stage 2)", provider.get(5908).getVersion());
		assertTrue(provider.describe(5888), provider.describe(5888).contains("boss rule: npc 5888 is 'Phase 1'"));
	}

	@Test
	public void theLastStageHasHalfTheDefences()
	{
		final MonsterStatsProvider.MonsterStats stage1 = provider.get(5891);
		assertEquals(60, stage1.getDefSlash());
		assertEquals(40, stage1.getDefStab());
		assertEquals(50, stage1.getDefCrush());
		assertEquals(20, stage1.getDefMagic());
		final MonsterStatsProvider.MonsterStats stage2 = provider.get(5908);
		assertEquals(30, stage2.getDefSlash());
		assertEquals(20, stage2.getDefStab());
		assertEquals(25, stage2.getDefCrush());
		assertEquals(-40, stage2.getDefMagic());
		assertEquals(250, stage2.getDefenceLevel());
		assertEquals(425, stage2.getHitpoints());
	}

	/** Stage 2's drops in Eric's kills decode at x1.125, as every other phase's do (SireReplayTest); the formula gives x1.100. */
	@Test
	public void everyPhasePaysTheXpTheDropsShow()
	{
		for (int id : new int[]{5886, 5887, 5888, 5889, 5890, 5891, 5908})
		{
			assertEquals("npc " + id, 1.125, provider.get(id).getXpMultiplier(), 1e-9);
		}
		// Nothing else moved: the respiratory systems keep the formula's x1.000.
		assertEquals(1.0, provider.get(5914).getXpMultiplier(), 1e-9);
	}

	@Test
	public void noPhaseIsAGuess()
	{
		for (int id : new int[]{5886, 5887, 5888, 5889, 5890, 5891, 5908})
		{
			assertNull("npc " + id, provider.get(id).getWarning());
		}
	}
}

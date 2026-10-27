package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import net.runelite.api.Client;
import org.junit.Test;

/**
 * A reload of the monster data (the weekly refetch) never leaves stats from the
 * old data in force. The data, the rules and every cache built from them are
 * published as one snapshot; a lookup that started on the old snapshot can only
 * cache into the old one.
 */
public class MonsterDataReloadTest
{
	private static final Gson GSON = new Gson();
	private static final int VARDORVIS = 12223;
	private static final int INDEX = 1;

	/** A client that answers tick 0 and knows no npc definitions: all the provider asks of it here. */
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

	private static String fixture() throws Exception
	{
		try (InputStreamReader in = new InputStreamReader(
			MonsterDataReloadTest.class.getResourceAsStream("monsters-sample.json"), StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(in).toString();
		}
	}

	/** The same data with post-quest Vardorvis's slash defence changed, as a new calc release might. */
	private static String withSlashDefence(String json, int slash)
	{
		final JsonArray all = new JsonParser().parse(json).getAsJsonArray();
		for (JsonElement e : all)
		{
			final JsonObject m = e.getAsJsonObject();
			if (m.get("id").getAsInt() == VARDORVIS && "Post-quest".equals(m.get("version").getAsString()))
			{
				m.getAsJsonObject("defensive").addProperty("slash", slash);
			}
		}
		return all.toString();
	}

	@Test
	public void aLookupStillRunningOnTheOldDataCannotLeaveItsStatsBehind() throws Exception
	{
		final Client client = client();
		final TargetHealth health = new TargetHealth();
		final MonsterStatsProvider provider = new MonsterStatsProvider(null, GSON, null, client,
			new DebugLog(client, null), health, null);
		final BossRules rules = BossRules.load(GSON, null);
		health.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		health.damage(INDEX, VARDORVIS, 350, 0);

		final MonsterStatsProvider.Parsed before = MonsterStatsProvider.parse(GSON, new StringReader(fixture()), rules);
		provider.publish(before);
		final MonsterStatsProvider.MonsterStats old = provider.get(VARDORVIS);
		assertEquals(180, old.getDefenceLevel());
		assertEquals(65, old.getDefSlash());

		final MonsterStatsProvider.Parsed after = MonsterStatsProvider.parse(GSON,
			new StringReader(withSlashDefence(fixture(), 99)), rules);
		provider.publish(after);
		// The race: a lookup that read the old snapshot finishes after the reload and caches its scaled copy - the same
		// id, full hitpoints and defence a lookup on the new data will ask for.
		final MonsterStatsProvider.MonsterStats oldBase =
			MonsterStatsProvider.choose(before.versionsById.get(VARDORVIS), rules.forNpc(VARDORVIS), -1, -1).stats;
		provider.applyRules(before, VARDORVIS, oldBase);

		final MonsterStatsProvider.MonsterStats now = provider.get(VARDORVIS);
		assertEquals(180, now.getDefenceLevel());
		assertEquals("the new data's slash defence", 99, now.getDefSlash());
		assertNotSame(old, now);
	}

	@Test
	public void theRulesInForceAreTheOnesTheDataWasReadWith() throws Exception
	{
		final Client client = client();
		final MonsterStatsProvider provider = new MonsterStatsProvider(null, GSON, null, client,
			new DebugLog(client, null), new TargetHealth(), null);
		assertEquals(0, provider.rules().size());
		final BossRules rules = BossRules.load(GSON, null);
		provider.publish(MonsterStatsProvider.parse(GSON, new StringReader(fixture()), rules));
		assertEquals(rules.size(), provider.rules().size());
	}
}

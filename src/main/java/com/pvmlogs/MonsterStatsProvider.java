package com.pvmlogs;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NPC defensive stats for the expected accuracy and DPS. Data is the OSRS Wiki
 * DPS calculator's monsters.json, fetched once and cached on disk, refreshed
 * when the cache ages out.
 */
@Slf4j
@Singleton
class MonsterStatsProvider
{
	private static final String URL = "https://raw.githubusercontent.com/weirdgloop/osrs-dps-calc/main/cdn/json/monsters.json";
	private static final long MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Client client;
	private final DebugLog debug;
	private final TargetHealth health;

	// Loaded before the monster data, since the XP bonus and the warnings are worked out from both. See BossRules.
	private volatile BossRules rules = BossRules.empty();

	// npcId -> stats; replaced wholesale after a load, so reads need no lock.
	private volatile Map<Integer, MonsterStats> byId = Collections.emptyMap();
	// npcId -> every version the data lists under it, in the data's order. One id can be several bosses: every DT2 boss
	// lists its Awakened form first under the same id as the ordinary one, so "first wins" read Vardorvis at 1400
	// hitpoints and the Leviathan and the Whisperer at 2700 instead of 900. See chooseVersion.
	private volatile Map<Integer, List<MonsterStats>> versionsById = Collections.emptyMap();
	// Scaled copies of entries whose stats move with hitpoints, keyed by id and the scaled defence, so the per-tick lookups
	// do not allocate.
	private final Map<Long, MonsterStats> scaledCache = new java.util.concurrent.ConcurrentHashMap<>();
	// (id, observed full hitpoints) pairs whose choice of version has been logged, so it is said once per reading.
	private final Set<Long> versionLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// name -> stats, for the ids the data does not carry. A monster wears several ids as it idles, walks and fights and
	// the source lists one: Tekton appears in game as 7542 as well as the 7540 and 7543 listed. Every miss meant no
	// accuracy, no expected damage and no drain, reading as whole stretches of a raid being ignored.
	private volatile Map<String, MonsterStats> byName = Collections.emptyMap();
	private final Map<Integer, MonsterStats> dynamicCache = new java.util.concurrent.ConcurrentHashMap<>();

	// Ids already reported to the debug log, so an unknown monster says so once rather than on every attack.
	private final Set<Integer> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();

	@Inject
	MonsterStatsProvider(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor,
		Client client, DebugLog debug, TargetHealth health)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.executor = executor;
		this.client = client;
		this.debug = debug;
		this.health = health;
	}

	/** The rules in force, for the debug header. */
	BossRules rules()
	{
		return rules;
	}

	void startUp()
	{
		executor.execute(this::loadOrFetch);
	}

	/**
	 * Ids the wiki's data does not carry, pointed at the entry that describes
	 * the same thing.
	 *
	 * <p>The data is keyed on whichever id the wiki happened to record, which is
	 * routinely NOT the id a monster wears while it is being fought: the Great
	 * Olm is listed only under the forms he wears while RISING, Verzik's first
	 * phase under the form she sits in before it starts, Tekton under the one he
	 * waits in at his anvil, and a nylocas under the form it walks in rather
	 * than the one it settles into. Every one of those meant no stats at all
	 * while the thing was actually being fought.
	 *
	 * <p>The name fallback below is not a substitute where a monster's versions
	 * DISAGREE, and these are exactly the ones that do. It takes whichever
	 * version the map holds first, so it can answer Verzik's third phase with
	 * her first phase's 20 defence, a normal Tekton with the enraged one's
	 * defensive bonuses, or a big nylocas with a small one's - and a size with
	 * it, which decides how many times a scythe hits. Each line here was checked
	 * against the entry it points at.
	 *
	 * <p>The rule for adding to this: an id belongs here when the data lists the
	 * same monster under a different id AND its versions differ. Where the
	 * versions agree, the name fallback is already right and a line here is only
	 * noise.
	 */
	private static final Map<Integer, Integer> ALIASES = buildAliases();

	private static Map<Integer, Integer> buildAliases()
	{
		final Map<Integer, Integer> aliases = new HashMap<>();

		// The Great Olm, listed only under the ids he wears while rising. The three parts are nothing alike - the head takes
		// 200s across stab, slash and crush where the melee claw takes 50s, and the mage claw's magic defence is 50 against
		// the melee claw's 200 - so the name fallback would be wrong about two targets in three.
		aliases.put(NpcID.OLM_HAND_RIGHT, NpcID.OLM_HAND_RIGHT_SPAWNING);
		aliases.put(NpcID.OLM_HAND_RIGHT_DYING, NpcID.OLM_HAND_RIGHT_SPAWNING);
		aliases.put(NpcID.OLM_HAND_LEFT, NpcID.OLM_HAND_LEFT_SPAWNING);
		aliases.put(NpcID.OLM_HAND_LEFT_DYING, NpcID.OLM_HAND_LEFT_SPAWNING);
		aliases.put(NpcID.OLM_HEAD, NpcID.OLM_HEAD_SPAWNING);

		// The Chambers guardians. Only the left one is listed, as "Guardian (Chambers of Xeric)" - which is not what the
		// game calls it, so the name fallback cannot reach it either and the right guardian had no stats at all.
		aliases.put(NpcID.RAIDS_STONEGUARDIANS_RIGHT, NpcID.RAIDS_STONEGUARDIANS_LEFT);
		aliases.put(NpcID.RAIDS_STONEGUARDIANS_LEFT_DEAD, NpcID.RAIDS_STONEGUARDIANS_LEFT);
		aliases.put(NpcID.RAIDS_STONEGUARDIANS_RIGHT_DEAD, NpcID.RAIDS_STONEGUARDIANS_LEFT);

		// Verzik's first phase. The data's "Phase 1" is the form she sits in before the fight; the one she is fought in is
		// the next id along. Her phases are the widest disagreement in the whole dataset - defence 20, 200 and 150, and
		// sizes 5, 3 and 7 - so answering P1 with P2's figures is not a rounding error.
		aliases.put(NpcID.VERZIK_PHASE1, NpcID.VERZIK_INITIAL);
		aliases.put(NpcID.VERZIK_PHASE1_HARD, NpcID.VERZIK_INITIAL_HARD);
		aliases.put(NpcID.VERZIK_PHASE1_STORY, NpcID.VERZIK_INITIAL_STORY);

		// Xarpus is listed once per mode, under the id of his final phase. The first two phases - lying still, and feeding
		// on the exhumeds - are their own ids and are fought.
		aliases.put(NpcID.TOB_XARPUS_STATIC, NpcID.TOB_XARPUS_COMBAT);
		aliases.put(NpcID.TOB_XARPUS_FEEDING, NpcID.TOB_XARPUS_COMBAT);
		aliases.put(NpcID.TOB_XARPUS_STATIC_HARD, NpcID.TOB_XARPUS_COMBAT_HARD);
		aliases.put(NpcID.TOB_XARPUS_FEEDING_HARD, NpcID.TOB_XARPUS_COMBAT_HARD);
		aliases.put(NpcID.TOB_XARPUS_STATIC_STORY, NpcID.TOB_XARPUS_COMBAT_STORY);
		aliases.put(NpcID.TOB_XARPUS_FEEDING_STORY, NpcID.TOB_XARPUS_COMBAT_STORY);

		// Tekton, listed under the form he waits in and the one he walks back in when enraged. Enraged is a different
		// monster to hit: 280, 290 and 180 against the standard 155, 165 and 105.
		aliases.put(NpcID.RAIDS_TEKTON_WALKING_STANDARD, NpcID.RAIDS_TEKTON_WAITING);
		aliases.put(NpcID.RAIDS_TEKTON_FIGHTING_STANDARD, NpcID.RAIDS_TEKTON_WAITING);
		aliases.put(NpcID.RAIDS_TEKTON_FIGHTING_ENRAGED, NpcID.RAIDS_TEKTON_WALKING_ENRAGED);

		// A second-phase warden alternates between a mage and a ranged form; only the mage one is listed, and they are the
		// same monster.
		aliases.put(NpcID.TOA_WARDEN_ELIDINIS_PHASE2_RANGE, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE);
		aliases.put(NpcID.TOA_WARDEN_TUMEKEN_PHASE2_RANGE, NpcID.TOA_WARDEN_TUMEKEN_PHASE2_MAGE);

		// The Nylocas, listed under the form each walks in and never the one it settles into to fight - which is the form
		// most of them are killed in. Big and small differ in defence, twenty against one, and in SIZE, which is what says
		// whether a scythe hits twice or once.
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MELEE, NpcID.TOB_NYLOCAS_INCOMING_MELEE);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_RANGED, NpcID.TOB_NYLOCAS_INCOMING_RANGED);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MAGIC, NpcID.TOB_NYLOCAS_INCOMING_MAGIC);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MELEE_STORY, NpcID.TOB_NYLOCAS_INCOMING_MELEE_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_RANGED_STORY, NpcID.TOB_NYLOCAS_INCOMING_RANGED_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_FIGHTING_MAGIC_STORY, NpcID.TOB_NYLOCAS_INCOMING_MAGIC_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_STORY);
		aliases.put(NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC_STORY, NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_STORY);

		// The Maiden wears an id per health threshold, and outside normal mode none of them is listed. Entry mode is a
		// different monster - 80 defence against 200 - so the mode has to be kept even though the thresholds do not matter.
		aliases.put(NpcID.TOB_MAIDEN_70_HARD, NpcID.TOB_MAIDEN_100_HARD);
		aliases.put(NpcID.TOB_MAIDEN_50_HARD, NpcID.TOB_MAIDEN_100_HARD);
		aliases.put(NpcID.TOB_MAIDEN_30_HARD, NpcID.TOB_MAIDEN_100_HARD);
		aliases.put(NpcID.TOB_MAIDEN_70_STORY, NpcID.TOB_MAIDEN_100_STORY);
		aliases.put(NpcID.TOB_MAIDEN_50_STORY, NpcID.TOB_MAIDEN_100_STORY);
		aliases.put(NpcID.TOB_MAIDEN_30_STORY, NpcID.TOB_MAIDEN_100_STORY);

		// The Chambers rooms that hold several copies of one monster list only the FIRST copy. The shamans matter most: the
		// data calls the listed one "Lizardman shaman (Chambers of Xeric)", which is not what the game calls it, so the two
		// unlisted shamans fell through to the plain "Lizardman shaman" of the open world - 140 defence in place of 210.
		aliases.put(NpcID.RAIDS_LIZARDSHAMAN_B, NpcID.RAIDS_LIZARDSHAMAN_A);
		aliases.put(NpcID.RAIDS_LIZARDSHAMAN_BLOCKER, NpcID.RAIDS_LIZARDSHAMAN_A);

		// The large muttadile out of the water. The wiki's own page gives the large one as "7561,7563" and lists it under
		// the first, so the fought id is unlisted and the fallback could answer it with the small one's 138 defence - and
		// with size 3 in place of 5, which is a scythe hit.
		aliases.put(NpcID.RAIDS_DOGODILE, NpcID.RAIDS_DOGODILE_SUBMERGED);

		// The Nylocas boss is listed under the form it spawns in, never the three it fights in.
		aliases.put(NpcID.NYLOCAS_BOSS_MELEE, NpcID.NYLOCAS_BOSS_SPAWNING);
		aliases.put(NpcID.NYLOCAS_BOSS_RANGED, NpcID.NYLOCAS_BOSS_SPAWNING);
		aliases.put(NpcID.NYLOCAS_BOSS_MAGIC, NpcID.NYLOCAS_BOSS_SPAWNING);
		aliases.put(NpcID.NYLOCAS_BOSS_MELEE_HARD, NpcID.NYLOCAS_BOSS_SPAWNING_HARD);
		aliases.put(NpcID.NYLOCAS_BOSS_RANGED_HARD, NpcID.NYLOCAS_BOSS_SPAWNING_HARD);
		aliases.put(NpcID.NYLOCAS_BOSS_MAGIC_HARD, NpcID.NYLOCAS_BOSS_SPAWNING_HARD);
		aliases.put(NpcID.NYLOCAS_BOSS_MELEE_STORY, NpcID.NYLOCAS_BOSS_SPAWNING_STORY);
		aliases.put(NpcID.NYLOCAS_BOSS_RANGED_STORY, NpcID.NYLOCAS_BOSS_SPAWNING_STORY);
		aliases.put(NpcID.NYLOCAS_BOSS_MAGIC_STORY, NpcID.NYLOCAS_BOSS_SPAWNING_STORY);

		return Collections.unmodifiableMap(aliases);
	}

	/** The id whose entry stands in for this one, or -1 where none does. */
	static int aliasFor(int npcId)
	{
		final Integer alias = ALIASES.get(npcId);
		return alias == null ? -1 : alias;
	}

	/**
	 * The stats to judge an attack on this npc by, as they stand now: the right
	 * version of it, with any stat that moves with its hitpoints moved.
	 */
	MonsterStats get(int npcId)
	{
		final MonsterStats base = resolve(npcId);
		return base == null ? null : applyRules(npcId, base);
	}

	private MonsterStats resolve(int npcId)
	{
		final List<MonsterStats> exact = versionsById.get(npcId);
		if (exact != null && !exact.isEmpty())
		{
			return pickVersion(npcId, exact);
		}
		final Integer alias = ALIASES.get(npcId);
		if (alias != null)
		{
			final List<MonsterStats> aliased = versionsById.get(alias);
			if (aliased != null && !aliased.isEmpty())
			{
				return pickVersion(npcId, aliased);
			}
		}
		if (npcId < 0 || byName.isEmpty())
		{
			return null;
		}
		if (dynamicCache.containsKey(npcId))
		{
			return dynamicCache.get(npcId);
		}
		// The id is unknown, so ask the game its name and look that up. Where a name covers several versions the first wins,
		// which is the ordinary form, so a figure may be a little low but is no longer absent.
		final NPCComposition composition = client.getNpcDefinition(npcId);
		final String name = composition == null ? null : composition.getName();
		MonsterStats resolved = composition == null ? null : byName.get(normalise(name));
		if (resolved != null)
		{
			dynamicCache.put(npcId, resolved);
		}
		if (debug.isOn() && logged.add(npcId))
		{
			// Both halves are worth a line. A miss means no accuracy and no expected damage against this target at all; a
			// name match means the figures came from whichever version of that name the data happened to hold first, which
			// is right for a monster that walks and fights under several ids and wrong for one whose forms differ.
			debug.log(resolved == null
					? "NO MONSTER STATS for npc %d ('%s'): no id, no alias and no name match - nothing will be expected "
						+ "against this target"
					: "monster stats for npc %d ('%s') resolved by NAME, not by id",
				npcId, name);
		}
		return resolved;
	}

	/**
	 * One of the versions listed under an id. A rule's choice first (the version
	 * whose full hitpoints the boss health bar has shown, else the rule's default);
	 * without a rule, the same evidence through {@link #chooseVersion}.
	 */
	private MonsterStats pickVersion(int npcId, List<MonsterStats> versions)
	{
		if (versions.size() == 1)
		{
			return versions.get(0);
		}
		final int observed = health.observedMaxForId(npcId);
		final BossRules.Boss boss = rules.forNpc(npcId);
		final BossRules.Variant wanted = BossRules.choose(boss, observed);
		if (wanted != null)
		{
			for (MonsterStats s : versions)
			{
				if (wanted.version.equalsIgnoreCase(s.getVersion()))
				{
					return s;
				}
			}
		}
		final MonsterStats chosen = chooseVersion(versions, observed);
		if (debug.isOn() && versionLogged.add(((long) npcId << 32) | Math.max(0, observed)))
		{
			debug.log("monster stats for npc %d: %d versions under this id, using '%s' (%d hp) - %s", npcId,
				versions.size(), chosen.getVersion(), chosen.getHitpoints(),
				observed > 0 ? "the boss health bar shows " + observed + " full hitpoints"
					: "the bar has not been read yet, so the smallest");
		}
		return chosen;
	}

	/**
	 * Which of several versions under one id is being fought, without a rule.
	 *
	 * <p>With the boss health bar's full hitpoints, the version that has them. That
	 * alone settles every DT2 boss (Awakened or not), Doom of Mokhaiotl's delves, and
	 * the Deadman variants. Without it, the version with the fewest hitpoints, which is
	 * the ordinary one in every case the data holds today.
	 *
	 * <p>Where versions share hitpoints but not defences (the wardens' Damaged and
	 * Enraged, the Maggot King's Roaring) no reading can tell them apart: those are
	 * phases. The first is used and the result carries a warning saying so.
	 */
	static MonsterStats chooseVersion(List<MonsterStats> versions, int observedMaxHp)
	{
		MonsterStats chosen = null;
		if (observedMaxHp > 0)
		{
			for (MonsterStats s : versions)
			{
				if (s.getHitpoints() == observedMaxHp)
				{
					chosen = s;
					break;
				}
			}
		}
		if (chosen == null)
		{
			for (MonsterStats s : versions)
			{
				if (chosen == null || s.getHitpoints() < chosen.getHitpoints())
				{
					chosen = s;
				}
			}
		}
		for (MonsterStats s : versions)
		{
			if (s != chosen && s.getHitpoints() == chosen.getHitpoints() && !sameDefences(s, chosen))
			{
				return chosen.withWarning(String.format("versions '%s' and '%s' share this id and hitpoints but not "
					+ "defences, which are phases this plugin does not track; using '%s'",
					chosen.getVersion(), s.getVersion(), chosen.getVersion()));
			}
		}
		return chosen;
	}

	private static boolean sameDefences(MonsterStats a, MonsterStats b)
	{
		return a.getDefenceLevel() == b.getDefenceLevel() && a.getMagicLevel() == b.getMagicLevel()
			&& a.getDefStab() == b.getDefStab() && a.getDefSlash() == b.getDefSlash()
			&& a.getDefCrush() == b.getDefCrush() && a.getDefMagic() == b.getDefMagic()
			&& a.getDefRanged() == b.getDefRanged();
	}

	/** Stats that move with the boss's hitpoints, moved to where they stand now. */
	private MonsterStats applyRules(int npcId, MonsterStats base)
	{
		final BossRules.Boss boss = rules.forNpc(npcId);
		if (boss == null)
		{
			return base;
		}
		BossRules.Variant variant = BossRules.variantNamed(boss, base.getVersion());
		if (variant == null)
		{
			variant = BossRules.choose(boss, health.observedMaxForId(npcId));
		}
		if (variant == null || variant.scaleWithHp == null || variant.scaleWithHp.defence == null)
		{
			return base;
		}
		final int max = variant.maxHp > 0 ? variant.maxHp : base.getHitpoints();
		final int defence = BossRules.scaleWithHp(variant.scaleWithHp.defence,
			health.remainingForId(npcId, client.getTickCount()), max);
		if (defence < 0)
		{
			return base;
		}
		final long key = ((long) npcId << 32) | ((long) max << 16) | (defence & 0xFFFF);
		return scaledCache.computeIfAbsent(key, k -> base.scaled(defence, max));
	}

	private static String normalise(String name)
	{
		return name == null ? "" : Text.removeTags(name).toLowerCase().trim();
	}

	private Path cacheFile()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, PvmPerformanceConfig.DATA_DIR), "monsters.json").toPath();
	}

	/** Where a user can drop a boss-rules.json that overrides the bundled one, boss by boss. */
	static Path rulesOverrideFile()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, "pvm-logs"), "boss-rules.json").toPath();
	}

	private void loadOrFetch()
	{
		rules = BossRules.load(gson, rulesOverrideFile());
		log.debug("PvM Logs: {} boss rule ids from {}", rules.size(), rules.provenance());
		final Path cache = cacheFile();
		try
		{
			if (Files.exists(cache))
			{
				parse(Files.newBufferedReader(cache, StandardCharsets.UTF_8));
				final long age = System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis();
				if (age < MAX_AGE_MILLIS)
				{
					return; // fresh enough
				}
			}
		}
		catch (Exception e)
		{
			log.warn("PvM Logs: could not read cached monster data", e);
		}
		fetch();
	}

	private void fetch()
	{
		final Request request = new Request.Builder()
			.url(URL)
			.header("User-Agent", "RuneLite plugin pvm-logs")
			.build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("PvM Logs: monster data fetch failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						log.warn("PvM Logs: monster data fetch returned {}", r.code());
						return;
					}
					final byte[] bytes = r.body().bytes();
					final Path cache = cacheFile();
					Files.createDirectories(cache.getParent());
					Files.write(cache, bytes);
					parse(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
				}
				catch (Exception e)
				{
					log.warn("PvM Logs: could not store monster data", e);
				}
			}
		});
	}

	private void parse(Reader reader) throws IOException
	{
		try (Reader r = reader)
		{
			final MonsterJson[] monsters = gson.fromJson(r, MonsterJson[].class);
			if (monsters == null)
			{
				return;
			}
			final BossRules rulesNow = rules;
			final Map<Integer, MonsterStats> map = new HashMap<>(monsters.length * 2);
			final Map<Integer, List<MonsterStats>> versions = new HashMap<>(monsters.length * 2);
			for (MonsterJson m : monsters)
			{
				if (m == null || m.skills == null || m.defensive == null)
				{
					continue;
				}
				final BossRules.Boss boss = rulesNow.forNpc(m.id);
				final BossRules.Variant variant = BossRules.variantNamed(boss, m.version);
				final MonsterStats stats = new MonsterStats(
					m.name == null ? "" : m.name, m.size,
					m.skills.hitpoints,
					m.skills.defence, m.skills.magic,
					m.offensive == null ? 0 : m.offensive.magic,
					m.defensive.stab, m.defensive.slash, m.defensive.crush,
					m.defensive.magic, m.defensive.standard,
					m.weakness == null || m.weakness.element == null
						? null : m.weakness.element.toLowerCase(Locale.ROOT).trim(),
					m.weakness == null ? 0 : m.weakness.severity,
					m.attributes == null
						? Collections.emptySet()
						: new HashSet<>(Arrays.asList(m.attributes)),
					xpMultiplier(m, variant),
					m.version == null ? "" : m.version,
					boss != null && boss.defenceDrainImmune,
					warningFor(m, boss));
				// The first version still answers the name lookup; chooseVersion decides between versions of an id.
				map.putIfAbsent(m.id, stats);
				versions.computeIfAbsent(m.id, k -> new ArrayList<>()).add(stats);
			}
			byId = map;
			versionsById = versions;
			scaledCache.clear();
			versionLogged.clear();
			final Map<String, MonsterStats> names = new HashMap<>(map.size() * 2);
			for (MonsterStats stats : map.values())
			{
				names.putIfAbsent(normalise(stats.getName()), stats);
			}
			byName = names;
			dynamicCache.clear();
			logged.clear();
			log.debug("PvM Logs: loaded {} monster stat entries", map.size());
		}
	}

	@Getter
	static class MonsterStats
	{
		/** Used to look up the few monsters that alter demonbane effectiveness. */
		private final String name;
		/** Tiles across, which the colossal blade scales its damage on. */
		private final int size;
		/**
		 * Full hitpoints. RuneLite's own table is the usual source for these and
		 * has nothing at all for the Chambers - maxHp read -1 on all 364 fights of
		 * one raid export - so this is what makes a remaining-health figure
		 * possible in a raid.
		 */
		private final int hitpoints;
		private final int defenceLevel;
		private final int magicLevel;
		/** The monster's offensive magic bonus, which the twisted bow also scales on. */
		private final int offensiveMagic;
		private final int defStab;
		private final int defSlash;
		private final int defCrush;
		private final int defMagic;
		private final int defRanged;
		/** Wiki attribute tags ("undead", "dragon", "demon", ...) driving gear bonuses. */
		private final Set<String> attributes;
		/**
		 * The element this monster is weak to, lower-cased, or null. Wiki
		 * (Elemental weakness): each point is worth 1% magic damage and 1% magic
		 * accuracy to a spell of that element.
		 */
		private final String weaknessElement;
		/** How many points of it, 0 when there is none. */
		private final int weaknessSeverity;

		/** The data's version name ("Post-quest", "Awakened", "Delve 3"), empty where it gives none. */
		private final String version;
		/** Whether drains do nothing to this boss's defence (Vardorvis, by the DPS calculator's floor). */
		private final boolean drainImmune;
		/**
		 * Why this target's expected figures should not be trusted, or null: a phase
		 * a rule names as unmodelled, placeholder stats, versions that cannot be told
		 * apart. Shown on the overlay and kept on the fight.
		 */
		private final String warning;

		MonsterStats(String name, int size, int hitpoints, int defenceLevel, int magicLevel, int offensiveMagic,
			int defStab, int defSlash, int defCrush, int defMagic, int defRanged, String weaknessElement,
			int weaknessSeverity, Set<String> attributes, double xpMultiplier)
		{
			this(name, size, hitpoints, defenceLevel, magicLevel, offensiveMagic, defStab, defSlash, defCrush, defMagic,
				defRanged, weaknessElement, weaknessSeverity, attributes, xpMultiplier, "", false, null);
		}

		MonsterStats(String name, int size, int hitpoints, int defenceLevel, int magicLevel, int offensiveMagic,
			int defStab, int defSlash, int defCrush, int defMagic, int defRanged, String weaknessElement,
			int weaknessSeverity, Set<String> attributes, double xpMultiplier, String version, boolean drainImmune,
			String warning)
		{
			this.version = version == null ? "" : version;
			this.drainImmune = drainImmune;
			this.warning = warning;
			this.xpMultiplier = xpMultiplier;
			this.hitpoints = hitpoints;
			this.weaknessElement = weaknessElement;
			this.weaknessSeverity = weaknessSeverity;
			this.name = name;
			this.size = size;
			this.defenceLevel = defenceLevel;
			this.magicLevel = magicLevel;
			this.offensiveMagic = offensiveMagic;
			this.defStab = defStab;
			this.defSlash = defSlash;
			this.defCrush = defCrush;
			this.defMagic = defMagic;
			this.defRanged = defRanged;
			this.attributes = attributes;
		}

		/**
		 * The PvM experience bonus this monster pays, 1.0 for none. Used to read
		 * how much damage an attack dealt from the experience it paid, and checked
		 * against the drops as they arrive - see XpLedger.
		 */
		private final double xpMultiplier;

		boolean hasAttribute(String attribute)
		{
			return attributes.contains(attribute);
		}

		/** This entry with its defence where a hitpoints-scaled rule puts it, and the rule's full hitpoints. */
		MonsterStats scaled(int defence, int hitpoints)
		{
			return new MonsterStats(name, size, hitpoints, defence, magicLevel, offensiveMagic, defStab, defSlash,
				defCrush, defMagic, defRanged, weaknessElement, weaknessSeverity, attributes, xpMultiplier, version,
				drainImmune, warning);
		}

		/** This entry carrying a warning, appended to any it already has. */
		MonsterStats withWarning(String extra)
		{
			final String combined = warning == null ? extra : warning + "; " + extra;
			return new MonsterStats(name, size, hitpoints, defenceLevel, magicLevel, offensiveMagic, defStab, defSlash,
				defCrush, defMagic, defRanged, weaknessElement, weaknessSeverity, attributes, xpMultiplier, version,
				drainImmune, combined);
		}
	}

	/**
	 * The bonus by the wiki's formula, or the value the game sets by hand where it does.
	 *
	 * <p>A boss whose rule scales a stat with hitpoints has that stat stored as 0, and
	 * the game's bonus is worked out on its full-hitpoints value: Vardorvis's drops pay
	 * x1.55 on every hit of a kill, which is the formula on his 270 strength and 215
	 * defence, and nothing like the x1.375 the zeros give.
	 */
	private static double xpMultiplier(MonsterJson m, BossRules.Variant variant)
	{
		final double fixed = XpDecoder.overrideFor(m.name);
		if (fixed > 0)
		{
			return fixed;
		}
		final BossRules.ScaleWithHp scaled = variant == null ? null : variant.scaleWithHp;
		final int strength = scaled != null && scaled.strength != null ? scaled.strength[0] : m.skills.strength;
		final int defence = scaled != null && scaled.defence != null ? scaled.defence[0] : m.skills.defence;
		final MonsterJson.Offensive o = m.offensive;
		return XpDecoder.bonusMultiplier(m.skills.attack, strength, defence, m.skills.hitpoints,
			m.defensive.stab, m.defensive.slash, m.defensive.crush,
			o == null ? 0 : o.attack, o == null ? 0 : o.strength);
	}

	/**
	 * Why this entry's figures should not be believed, or null.
	 *
	 * <p>A rule that says what is not modelled says so. Without a rule, a boss whose
	 * defence AND strength are both 0 while it has an attack and a few hundred
	 * hitpoints is the shape the DPS calculator's data takes when it computes those
	 * stats in code: true of Vardorvis and of nothing else in the data today, and the
	 * first sign of a new boss that needs a rule.
	 */
	static String warningFor(int defence, int strength, int attack, int hitpoints, BossRules.Boss boss)
	{
		if (boss != null)
		{
			return boss.unmodelled;
		}
		if (defence == 0 && strength == 0 && attack > 0 && hitpoints >= 200)
		{
			return "the monster data has 0 defence and 0 strength for this boss, which is what it holds where the DPS "
				+ "calculator computes them in code; expected figures are unreliable until a boss rule covers it";
		}
		return null;
	}

	private static String warningFor(MonsterJson m, BossRules.Boss boss)
	{
		return warningFor(m.skills.defence, m.skills.strength, m.skills.attack, m.skills.hitpoints, boss);
	}

	private static class MonsterJson
	{
		int id;
		String name;
		String version;
		int size;
		Skills skills;
		Offensive offensive;
		Defensive defensive;
		String[] attributes;
		Weakness weakness;

		static class Weakness
		{
			String element;
			int severity;
		}

		static class Skills
		{
			@SerializedName("def")
			int defence;
			int magic;
			@SerializedName("hp")
			int hitpoints;
			@SerializedName("atk")
			int attack;
			@SerializedName("str")
			int strength;
		}

		static class Offensive
		{
			int magic;
			@SerializedName("atk")
			int attack;
			@SerializedName("str")
			int strength;
		}

		static class Defensive
		{
			int stab;
			int slash;
			int crush;
			int magic;
			int standard;
		}
	}
}

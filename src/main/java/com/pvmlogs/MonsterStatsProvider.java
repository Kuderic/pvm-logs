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
import net.runelite.client.game.NPCManager;
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
	// RuneLite's own hitpoints table, as a hint between versions before the boss health bar has been read.
	private final NPCManager npcManager;

	// The rules as last loaded, waiting for the monster data they are applied to. Loaded first, since the XP bonus and the
	// warnings are worked out from both; only ever read through a snapshot. See BossRules.
	private volatile BossRules loadedRules = BossRules.empty();

	// The monster data, the rules it was read with, and every cache built from the two, published as one. A lookup reads
	// this once and works from that snapshot to the end, so nothing it caches can outlive a reload: a scaled copy made from
	// the old data lands in the old snapshot's cache and is dropped with it. With separate fields, a lookup that read the
	// old map and then cached after the reload's clear left old stats in force until the next reload. See Parsed.
	private volatile Parsed data = Parsed.EMPTY;

	@Inject
	MonsterStatsProvider(OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor,
		Client client, DebugLog debug, TargetHealth health, NPCManager npcManager)
	{
		this.npcManager = npcManager;
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
		return data.rules;
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
		final Parsed d = data;
		final Choice choice = resolve(d, npcId);
		return choice == null ? null : applyRules(d, npcId, choice.stats);
	}

	/**
	 * One line on which version of this npc is in use and why, its defence as it
	 * stands, and any warning against it: for the debug header and ::loadout, so a
	 * figure that looks wrong can be traced to the entry it came from.
	 */
	String describe(int npcId)
	{
		final Parsed d = data;
		final Choice choice = resolve(d, npcId);
		if (choice == null)
		{
			return String.format("Monster data: nothing for npc %d (rules %s)", npcId, d.rules.provenance());
		}
		final MonsterStats now = applyRules(d, npcId, choice.stats);
		final int tick = client.getTickCount();
		final int left = health.remainingForId(npcId, tick);
		return String.format("Monster data: '%s'%s, %d hp, defence %d now (%s), xp bonus x%.3f%s; rules %s",
			now.getName(), now.getVersion().isEmpty() ? "" : " (" + now.getVersion() + ")", now.getHitpoints(),
			now.getDefenceLevel(), left < 0 ? "hitpoints not known yet" : left + " hp left", now.getXpMultiplier(),
			" - " + choice.reason + (now.getWarning() == null ? "" : "; WARNING: " + now.getWarning()),
			d.rules.provenance());
	}

	private Choice resolve(Parsed d, int npcId)
	{
		final List<MonsterStats> exact = d.versionsById.get(npcId);
		if (exact != null && !exact.isEmpty())
		{
			return pickVersion(d, npcId, exact);
		}
		final Integer alias = ALIASES.get(npcId);
		if (alias != null)
		{
			final List<MonsterStats> aliased = d.versionsById.get(alias);
			if (aliased != null && !aliased.isEmpty())
			{
				return pickVersion(d, npcId, aliased);
			}
		}
		if (npcId < 0 || d.byName.isEmpty())
		{
			return null;
		}
		List<MonsterStats> named = d.byUnknownId.get(npcId);
		if (named == null)
		{
			// The id is unknown, so ask the game its name and look that up. Every version of that name is a candidate and
			// the same choice as an id's is made between them: the first one used to win, which for every DT2 boss is the
			// Awakened form.
			final NPCComposition composition = client.getNpcDefinition(npcId);
			final String name = composition == null ? null : composition.getName();
			named = composition == null ? null : d.byName.get(normalise(name));
			if (named != null)
			{
				d.byUnknownId.put(npcId, named);
			}
			if (debug.isOn() && d.logged.add(npcId))
			{
				// Both halves are worth a line. A miss means no accuracy and no expected damage against this target at all; a
				// name match means the figures came from an entry recorded under another id, which is right for a monster that
				// walks and fights under several ids and wrong for one whose forms differ.
				debug.log(named == null
						? "NO MONSTER STATS for npc %d ('%s'): no id, no alias and no name match - nothing will be expected "
							+ "against this target"
						: "monster stats for npc %d ('%s') resolved by NAME, not by id",
					npcId, name);
			}
		}
		return named == null || named.isEmpty() ? null : pickVersion(d, npcId, named);
	}

	/** Which version is in use, and in a few words why. */
	static final class Choice
	{
		final MonsterStats stats;
		final String reason;

		Choice(MonsterStats stats, String reason)
		{
			this.stats = stats;
			this.reason = reason;
		}
	}

	/** One of the versions listed under an id, by the evidence the plugin has right now. Logged once per reading. */
	private Choice pickVersion(Parsed d, int npcId, List<MonsterStats> versions)
	{
		final int observed = health.observedMaxForId(npcId);
		int hint = -1;
		if (versions.size() > 1 && npcManager != null)
		{
			final Integer known = npcManager.getHealth(npcId);
			hint = known == null ? -1 : known;
		}
		final Choice choice = choose(versions, d.rules.forNpc(npcId), observed, hint);
		if (versions.size() > 1 && d.versionLogged.add(((long) npcId << 32) | Math.max(0, observed)))
		{
			debug.log("monster stats for npc %d: %d versions under this id, using '%s' (%d hp) - %s", npcId,
				versions.size(), choice.stats.getVersion(), choice.stats.getHitpoints(), choice.reason);
		}
		return choice;
	}

	/**
	 * Which of the versions listed under one id is being fought.
	 *
	 * <ol>
	 * <li>One version: that one.</li>
	 * <li>A boss rule with versions: the one whose full hitpoints the boss health bar
	 * has shown, else the rule's default.</li>
	 * <li>Otherwise {@link #chooseVersion}.</li>
	 * </ol>
	 */
	static Choice choose(List<MonsterStats> versions, BossRules.Boss boss, int observedMaxHp, int hintHp)
	{
		if (versions.size() == 1)
		{
			return new Choice(versions.get(0), "the only version");
		}
		final BossRules.Variant wanted = BossRules.choose(boss, observedMaxHp);
		if (wanted != null)
		{
			for (MonsterStats s : versions)
			{
				if (wanted.version != null && wanted.version.equalsIgnoreCase(s.getVersion()))
				{
					return new Choice(s, observedMaxHp > 0 && wanted.maxHp == observedMaxHp
						? "boss rule, the health bar shows " + observedMaxHp + " full hitpoints"
						: "boss rule default" + (observedMaxHp > 0 ? ", no version has the bar's " + observedMaxHp
							: ", health bar not read yet"));
				}
			}
		}
		return chooseVersion(versions, observedMaxHp, hintHp);
	}

	/**
	 * Which of several versions under one id is being fought, without a rule.
	 *
	 * <p>With the boss health bar's full hitpoints, the version that has them. That
	 * alone settles every DT2 boss (Awakened or not), Doom of Mokhaiotl's delves, and
	 * the Deadman variants. Without it, the one whose hitpoints RuneLite's own table
	 * gives for the id, where exactly one does. Failing both, the version with the
	 * fewest hitpoints, which is the ordinary one in every boss case the data holds
	 * today.
	 *
	 * <p>Where versions share hitpoints but not defences (the wardens' Damaged and
	 * Enraged, the Maggot King's Roaring, Yama's third phase) no reading can tell them
	 * apart: those are phases. The first is used and the result carries a warning
	 * saying so.
	 */
	static Choice chooseVersion(List<MonsterStats> versions, int observedMaxHp, int hintHp)
	{
		MonsterStats chosen = null;
		String reason = null;
		if (observedMaxHp > 0)
		{
			chosen = only(versions, observedMaxHp);
			reason = chosen == null ? null : "the health bar shows " + observedMaxHp + " full hitpoints";
		}
		if (chosen == null && hintHp > 0)
		{
			chosen = only(versions, hintHp);
			reason = chosen == null ? null : "RuneLite's table gives this id " + hintHp + " hitpoints";
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
			reason = "the fewest hitpoints of " + versions.size()
				+ (observedMaxHp > 0 ? " (no version has the bar's " + observedMaxHp + ")" : " (health bar not read yet)");
		}
		for (MonsterStats s : versions)
		{
			if (s != chosen && s.getHitpoints() == chosen.getHitpoints() && !sameDefences(s, chosen))
			{
				return new Choice(chosen.withWarning(String.format("versions '%s' and '%s' share this id and hitpoints but "
					+ "not defences, which are phases this plugin does not track; using '%s'",
					chosen.getVersion(), s.getVersion(), chosen.getVersion())), reason);
			}
		}
		return new Choice(chosen, reason);
	}

	/** The first version with these hitpoints, or null; the first of several that share them, since phases do. */
	private static MonsterStats only(List<MonsterStats> versions, int hitpoints)
	{
		for (MonsterStats s : versions)
		{
			if (s.getHitpoints() == hitpoints)
			{
				return s;
			}
		}
		return null;
	}

	private static boolean sameDefences(MonsterStats a, MonsterStats b)
	{
		return a.getDefenceLevel() == b.getDefenceLevel() && a.getMagicLevel() == b.getMagicLevel()
			&& a.getDefStab() == b.getDefStab() && a.getDefSlash() == b.getDefSlash()
			&& a.getDefCrush() == b.getDefCrush() && a.getDefMagic() == b.getDefMagic()
			&& a.getDefRanged() == b.getDefRanged();
	}

	/**
	 * Stats that move with the boss's hitpoints, moved to where they stand now.
	 * Cached per id and defence in the snapshot the base came from, never in a
	 * newer one.
	 */
	MonsterStats applyRules(Parsed d, int npcId, MonsterStats base)
	{
		final BossRules.Boss boss = d.rules.forNpc(npcId);
		final BossRules.Variant variant = scalingVariant(boss, base, health.observedMaxForId(npcId));
		if (variant == null)
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
		final long key = ((long) npcId << 32) | ((long) (max & 0xFFFF) << 16) | (defence & 0xFFFF);
		return d.scaled.computeIfAbsent(key, k -> base.scaled(defence, max));
	}

	/** The rule variant whose hitpoints scaling applies to this entry, or null when none does. */
	static BossRules.Variant scalingVariant(BossRules.Boss boss, MonsterStats base, int observedMaxHp)
	{
		if (boss == null)
		{
			return null;
		}
		BossRules.Variant variant = BossRules.variantNamed(boss, base.getVersion());
		if (variant == null)
		{
			variant = BossRules.choose(boss, observedMaxHp);
		}
		return variant == null || variant.scaleWithHp == null || variant.scaleWithHp.defence == null ? null : variant;
	}

	/**
	 * This entry as a rule puts it at a given number of hitpoints left (unknown
	 * reads as full). The pure form of what {@link #get} does, for tests and replays.
	 */
	static MonsterStats atHitpoints(BossRules.Boss boss, MonsterStats base, int observedMaxHp, int remainingHp)
	{
		final BossRules.Variant variant = scalingVariant(boss, base, observedMaxHp);
		if (variant == null)
		{
			return base;
		}
		final int max = variant.maxHp > 0 ? variant.maxHp : base.getHitpoints();
		final int defence = BossRules.scaleWithHp(variant.scaleWithHp.defence, remainingHp, max);
		return defence < 0 ? base : base.scaled(defence, max);
	}

	private static String normalise(String name)
	{
		return name == null ? "" : Text.removeTags(name).toLowerCase().trim();
	}

	private Path cacheFile()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, PvmPerformanceConfig.DATA_DIR), "monsters.json").toPath();
	}

	/**
	 * Where a user can drop a boss-rules.json that overrides the bundled one, boss by
	 * boss: the plugin's data folder, beside the cached monster data.
	 */
	static Path rulesOverrideFile()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, PvmPerformanceConfig.DATA_DIR), "boss-rules.json").toPath();
	}

	private void loadOrFetch()
	{
		loadedRules = BossRules.load(gson, rulesOverrideFile());
		log.debug("PvM Logs: {} boss rule ids from {}", loadedRules.size(), loadedRules.provenance());
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
		final Parsed parsed;
		try (Reader r = reader)
		{
			parsed = parse(gson, r, loadedRules);
		}
		if (parsed == null)
		{
			return;
		}
		publish(parsed);
		log.debug("PvM Logs: loaded {} monster ids, {} with several versions", parsed.versionsById.size(),
			parsed.versionsById.values().stream().filter(v -> v.size() > 1).count());
	}

	/** Makes a parsed data set the one every lookup reads, caches and all, in one write. */
	void publish(Parsed parsed)
	{
		data = parsed;
	}

	/**
	 * The monster data, indexed, with the rules it was read with and every cache
	 * built from them. Immutable apart from the caches, which belong to this
	 * snapshot alone and go with it.
	 */
	static final class Parsed
	{
		static final Parsed EMPTY = new Parsed(Collections.emptyMap(), Collections.emptyMap(), BossRules.empty());

		/** npcId -> every version listed under it, in the data's order. */
		final Map<Integer, List<MonsterStats>> versionsById;
		/** Normalised name -> every version of that name, in the data's order. */
		final Map<String, List<MonsterStats>> byName;
		/** The boss rules applied when this was read. */
		final BossRules rules;
		/** Scaled copies of entries whose stats move with hitpoints, keyed by id, full hitpoints and defence. */
		final Map<Long, MonsterStats> scaled = new java.util.concurrent.ConcurrentHashMap<>();
		/**
		 * Ids the data does not carry, resolved by the game's name for them. A monster wears several ids as it idles,
		 * walks and fights and the source lists one: Tekton appears in game as 7542 as well as the 7540 and 7543 listed.
		 */
		final Map<Integer, List<MonsterStats>> byUnknownId = new java.util.concurrent.ConcurrentHashMap<>();
		/** (id, observed full hitpoints) pairs whose choice of version has been logged, so it is said once per reading. */
		final Set<Long> versionLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();
		/** Ids already reported to the debug log, so an unknown monster says so once rather than on every attack. */
		final Set<Integer> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();

		Parsed(Map<Integer, List<MonsterStats>> versionsById, Map<String, List<MonsterStats>> byName, BossRules rules)
		{
			this.versionsById = versionsById;
			this.byName = byName;
			this.rules = rules;
		}
	}

	/** Reads the DPS calculator's monsters.json with the rules applied. Null if it holds nothing. */
	static Parsed parse(Gson gson, Reader reader, BossRules rules)
	{
		final MonsterJson[] monsters = gson.fromJson(reader, MonsterJson[].class);
		if (monsters == null)
		{
			return null;
		}
		final Map<Integer, List<MonsterStats>> versions = new HashMap<>(monsters.length * 2);
		final Map<String, List<MonsterStats>> names = new HashMap<>(monsters.length * 2);
		for (MonsterJson m : monsters)
		{
			if (m == null || m.skills == null || m.defensive == null)
			{
				continue;
			}
			final BossRules.Boss boss = rules.forNpc(m.id);
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
				warningFor(m, boss, variant));
			versions.computeIfAbsent(m.id, k -> new ArrayList<>()).add(stats);
			names.computeIfAbsent(normalise(stats.getName()), k -> new ArrayList<>()).add(stats);
		}
		return new Parsed(versions, names, rules);
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
	 * <p>A rule that says what is not modelled says so. Apart from that, a boss whose
	 * defence AND strength are both 0 while it has an attack and a few hundred
	 * hitpoints is the shape the DPS calculator's data takes when it computes those
	 * stats in code: true of Vardorvis and of nothing else in the data today, and the
	 * first sign of a new boss that needs a rule. A rule variant that scales the
	 * defence covers that shape; a rule without one (or a version the rule does not
	 * list) does not, so the warning still shows.
	 */
	static String warningFor(int defence, int strength, int attack, int hitpoints, BossRules.Boss boss,
		BossRules.Variant variant)
	{
		final boolean covered = variant != null && variant.scaleWithHp != null && variant.scaleWithHp.defence != null;
		final String placeholder = !covered && defence == 0 && strength == 0 && attack > 0 && hitpoints >= 200
			? "the monster data has 0 defence and 0 strength for this boss, which is what it holds where the DPS "
				+ "calculator computes them in code; expected figures are unreliable until a boss rule covers it"
			: null;
		final String unmodelled = boss == null || boss.unmodelled == null || boss.unmodelled.isEmpty()
			? null : boss.unmodelled;
		if (placeholder == null)
		{
			return unmodelled;
		}
		return unmodelled == null ? placeholder : unmodelled + "; " + placeholder;
	}

	private static String warningFor(MonsterJson m, BossRules.Boss boss, BossRules.Variant variant)
	{
		return warningFor(m.skills.defence, m.skills.strength, m.skills.attack, m.skills.hitpoints, boss, variant);
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

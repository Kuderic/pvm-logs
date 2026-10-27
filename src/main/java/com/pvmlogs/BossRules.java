package com.pvmlogs;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Bosses whose stats the monster data cannot describe on its own, as data.
 *
 * <p>The monster data is the DPS calculator's, and some of what the calculator
 * does is not in that data but in its code: Vardorvis's strength and defence move
 * with his hitpoints and are stored as 0; Araxxor, Yama and Bork change with phases
 * the data does not carry. Each such case lives here as data, so a new boss is a
 * data change rather than a code change.
 *
 * <p>The <em>numbers</em> in a rule (stat ranges, phase bonuses, npc ids) are game
 * facts read from the calculator's {@code src/lib/scaling} and
 * {@code src/lib/constants.ts}; the file's {@code portedFrom} names the repo and
 * commit they came from. The code that applies them is our own. The calculator (weirdgloop/osrs-dps-calc)
 * is GPL-3, so none of its code may be copied into this BSD-2 plugin; its
 * {@code monsters.json} data and the game numbers are fine with credit.
 *
 * <p>Three layers, later ones winning by boss name:
 * <ol>
 * <li>{@code boss-rules.json} bundled with the plugin;</li>
 * <li>{@code boss-rules.json} in the plugin's data folder
 * ({@code ~/.runelite/plugin-data/<PvmPerformanceConfig.INTERNAL_NAME>/}), if present - a fix can be
 * tried without waiting for a release;</li>
 * <li>nothing else. A boss with no rule falls back to the monster data and to the
 * checks in MonsterStatsProvider and StatsCheck, which flag rather than guess.</li>
 * </ol>
 *
 * <p>What a rule can say, in schema 1: which versions a boss has and their full
 * hitpoints (the boss health bar's full hitpoints picks between them); stats that
 * scale linearly with remaining hitpoints; that defence cannot be drained; and, for
 * anything not yet modelled, a plain description that is surfaced as a warning.
 */
@Slf4j
class BossRules
{
	static final int SCHEMA_VERSION = 1;

	static final class Variant
	{
		String version;
		int maxHp;
		@SerializedName("default")
		boolean byDefault;
		ScaleWithHp scaleWithHp;
	}

	static final class ScaleWithHp
	{
		/** [at full hitpoints, at none]. */
		int[] defence;
		int[] strength;
	}

	static final class Boss
	{
		String name;
		int[] npcIds;
		boolean defenceDrainImmune;
		List<Variant> variants;
		/** What about this boss is known and not modelled. Shown as a warning against its expected figures. */
		String unmodelled;
		String notes;
	}

	static final class PortedFrom
	{
		String repo;
		List<String> paths;
		String commit;
		String date;
	}

	static final class RulesFile
	{
		int schemaVersion;
		PortedFrom portedFrom;
		List<Boss> bosses;
	}

	private final Map<Integer, Boss> byId;
	private final PortedFrom portedFrom;

	private BossRules(Map<Integer, Boss> byId, PortedFrom portedFrom)
	{
		this.byId = byId;
		this.portedFrom = portedFrom;
	}

	static BossRules empty()
	{
		return new BossRules(Collections.emptyMap(), null);
	}

	/** The bundled rules, overlaid by the user's file where it exists and parses. Never throws. */
	static BossRules load(Gson gson, Filepath userOverride)
	{
		RulesFile bundled = null;
		try (InputStream in = BossRules.class.getResourceAsStream("boss-rules.json"))
		{
			if (in != null)
			{
				bundled = read(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("PvM Logs: bundled boss rules unreadable", e);
		}
		RulesFile user = null;
		if (userOverride != null && userOverride.isFile())
		{
			try (Reader r = userOverride.openBufferedReader())
			{
				user = read(gson, r);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("PvM Logs: boss rules override {} unreadable, ignored", userOverride, e);
			}
		}
		return merge(bundled, user);
	}

	/** Parses one rules file, refusing a schema newer than this code understands. Null if unusable. */
	static RulesFile read(Gson gson, Reader reader)
	{
		final RulesFile file = gson.fromJson(reader, RulesFile.class);
		if (file == null || file.bosses == null)
		{
			return null;
		}
		if (file.schemaVersion > SCHEMA_VERSION)
		{
			log.warn("PvM Logs: boss rules schema {} is newer than {}, ignored", file.schemaVersion, SCHEMA_VERSION);
			return null;
		}
		return file;
	}

	/** Later files replace earlier ones boss by boss, matched on name. */
	static BossRules merge(RulesFile base, RulesFile overlay)
	{
		final Map<String, Boss> byName = new LinkedHashMap<>();
		for (RulesFile f : new RulesFile[]{base, overlay})
		{
			if (f == null)
			{
				continue;
			}
			for (Boss b : f.bosses)
			{
				if (b != null && b.name != null && b.npcIds != null)
				{
					byName.put(b.name.toLowerCase(), b);
				}
			}
		}
		final Map<Integer, Boss> byId = new HashMap<>();
		for (Boss b : byName.values())
		{
			for (int id : b.npcIds)
			{
				byId.put(id, b);
			}
		}
		final PortedFrom from = base != null ? base.portedFrom : overlay != null ? overlay.portedFrom : null;
		return new BossRules(byId, from);
	}

	Boss forNpc(int npcId)
	{
		return byId.get(npcId);
	}

	/** Where the bundled rules were ported from, for the debug log header. */
	String provenance()
	{
		return portedFrom == null || portedFrom.commit == null ? "unknown"
			: portedFrom.repo + "@" + portedFrom.commit.substring(0, Math.min(7, portedFrom.commit.length()))
				+ " (" + portedFrom.date + ")";
	}

	int size()
	{
		return byId.size();
	}

	/**
	 * Which version of the boss this is: the one whose full hitpoints the game has
	 * shown, then the rule's default, then the first listed. Null when the rule
	 * lists none.
	 */
	static Variant choose(Boss boss, int observedMaxHp)
	{
		if (boss == null || boss.variants == null || boss.variants.isEmpty())
		{
			return null;
		}
		if (observedMaxHp > 0)
		{
			for (Variant v : boss.variants)
			{
				if (v.maxHp == observedMaxHp)
				{
					return v;
				}
			}
		}
		for (Variant v : boss.variants)
		{
			if (v.byDefault)
			{
				return v;
			}
		}
		return boss.variants.get(0);
	}

	/** The rule's variant with this version name, or null. */
	static Variant variantNamed(Boss boss, String version)
	{
		if (boss == null || boss.variants == null || version == null)
		{
			return null;
		}
		for (Variant v : boss.variants)
		{
			if (version.equalsIgnoreCase(v.version))
			{
				return v;
			}
		}
		return null;
	}

	/**
	 * A stat that moves linearly from {@code range[0]} at full hitpoints to
	 * {@code range[1]} at none, truncated the way the DPS calculator's {@code lerp}
	 * truncates. Unknown or out-of-range hitpoints read as full.
	 */
	static int scaleWithHp(int[] range, int currentHp, int maxHp)
	{
		if (range == null || range.length != 2 || maxHp <= 0)
		{
			return -1;
		}
		final int hp = currentHp < 0 || currentHp > maxHp ? maxHp : currentHp;
		// lerp(curr, srcStart = max, srcEnd = 0, dstStart = range[0], dstEnd = range[1])
		final double value = ((double) (hp - maxHp) * (range[1] - range[0])) / (0 - maxHp) + range[0];
		return (int) value; // truncates towards zero, as Math.trunc
	}

	/** Every boss id with a rule, for tests and the debug header. */
	List<Integer> ids()
	{
		return new ArrayList<>(byId.keySet());
	}
}

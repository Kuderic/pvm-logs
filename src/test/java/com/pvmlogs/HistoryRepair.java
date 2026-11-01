package com.pvmlogs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.RuneLite;
import net.runelite.client.util.Filepath;

/**
 * T-138: a one-off, dev-only repair of {@code history.json}. Test code, never shipped. Run with RuneLite closed:
 * {@code ./gradlew historyRepair} lists what it would change and changes nothing; {@code -Papply} writes it, a backup
 * first.
 *
 * <ul>
 * <li><b>Split Yama kills</b> (always). Before T-136 (#156) a Void Flare wasn't in Yama's group, so each kill was saved
 * in pieces: Yama's fights with no room closing, a one-kill "Void Flare" monster room per flare, and Yama's last
 * fight, his death. Each such run becomes one room closed on his death, the flares in it as adds, their monster-room
 * kills dropped: the shape #156 saves.</li>
 * <li><b>Pre-T-106 Araxxor kills</b> ({@code -ParaxxorSolo}). Before T-106 the Ruptura explosions and Araxxor's cleave on
 * himself were saved as another player's damage, so a solo kill read as a group kill. Where every bit of it is on
 * Araxxor himself, outside a party, in amounts his {@code roomDamage} rules can make, it moves to the room's damage.
 * A kill with other players' damage the rules can't explain is left alone.</li>
 * </ul>
 *
 * <p>It reads and writes the file as the JSON tree the plugin saves, changing only the fields named above, so every
 * other record is left as it was; it reads what it wrote back with the plugin's own code ({@code Fight[]} through
 * Gson, rooms through {@link SidePanelHistory#allRooms}). It touches nothing but {@code history.json} and its backup.
 */
public final class HistoryRepair
{
	static final String FILE = "history.json";
	/** Fights more than this far apart are not one kill's pieces, whatever lies between them in the file. */
	static final long MAX_GAP_MILLIS = 120_000;

	private static final Set<Integer> YAMA_PARTS = new HashSet<>(Arrays.asList(NpcID.YAMA, NpcID.YAMA_JUDGE_OF_YAMA,
		NpcID.LEAGUE6_JUDGE_OF_YAMA, NpcID.YAMA_VOIDFLARE));
	private static final Gson GSON = new Gson();

	private HistoryRepair()
	{
	}

	/** One split Yama kill: its fights' places in the file, oldest first. */
	static final class Stitch
	{
		final List<Integer> indices = new ArrayList<>();
		int yamaFights;
		int judgeFights;
		int flareFights;
		int flareKills;
		/** Whether a flare's monster room split it; else it is a kill no fight closed (none did before T-136). */
		boolean split;
		long startMillis;
		long endMillis;
		int yamaDamage;
		int roomDamage;
	}

	/** One pre-T-106 Araxxor kill: the fights whose other players' damage moves to the room's. */
	static final class Solo
	{
		final List<Integer> indices = new ArrayList<>();
		long startMillis;
		int moved;
	}

	static final class Plan
	{
		final List<Stitch> stitches = new ArrayList<>();
		final List<Solo> solos = new ArrayList<>();
		/** Kills found but left alone, and why. */
		final List<String> leftAlone = new ArrayList<>();

		boolean isEmpty()
		{
			return stitches.isEmpty() && solos.isEmpty();
		}
	}

	// ---- finding them ----

	static Plan plan(JsonArray history, BossRules rules, boolean araxxorSolo)
	{
		final Plan plan = new Plan();
		findSplitYamaKills(history, plan);
		if (araxxorSolo)
		{
			findAraxxorKills(history, rules, plan);
		}
		return plan;
	}

	/**
	 * Runs of consecutive Yama, Judge and Void Flare fights, oldest first (the file is newest first), each cut at Yama's
	 * death. A piece ending on Yama dying with no fight closing its room is a kill saved before T-136: split, when a flare
	 * in it is a "Void Flare" monster room, or else never closed (he drops no loot, and nothing closed his room before
	 * T-136, so History ran it on into the next). A room ended without its kill (T-100), or a gap of more than
	 * {@link #MAX_GAP_MILLIS}, cuts a piece off unstitched.
	 */
	static void findSplitYamaKills(JsonArray history, Plan plan)
	{
		List<Integer> piece = new ArrayList<>();
		long lastEnd = Long.MIN_VALUE;
		for (int i = history.size() - 1; i >= 0; i--)
		{
			final JsonObject f = history.get(i).getAsJsonObject();
			final int id = num(f, "targetId");
			if (!YAMA_PARTS.contains(id) || lastEnd != Long.MIN_VALUE && lng(f, "startMillis") - lastEnd > MAX_GAP_MILLIS)
			{
				piece = new ArrayList<>();
				lastEnd = Long.MIN_VALUE;
				if (!YAMA_PARTS.contains(id))
				{
					continue;
				}
			}
			piece.add(i);
			lastEnd = Math.max(lastEnd, lng(f, "endMillis"));
			if (f.has("roomEnd") && !f.get("roomEnd").isJsonNull())
			{
				piece = new ArrayList<>();
				lastEnd = Long.MIN_VALUE;
				continue;
			}
			if (id == NpcID.YAMA && bool(f, "targetDied"))
			{
				final Stitch s = stitchOf(history, piece);
				if (s != null)
				{
					plan.stitches.add(s);
				}
				piece = new ArrayList<>();
				lastEnd = Long.MIN_VALUE;
			}
		}
	}

	private static Stitch stitchOf(JsonArray history, List<Integer> piece)
	{
		final Stitch s = new Stitch();
		// #156's builds close Yama's room on his death: a kill saved so is whole already.
		if (bool(history.get(piece.get(piece.size() - 1)).getAsJsonObject(), "closedRoom"))
		{
			return null;
		}
		for (int i : piece)
		{
			final JsonObject f = history.get(i).getAsJsonObject();
			final int id = num(f, "targetId");
			s.indices.add(i);
			if (id == NpcID.YAMA)
			{
				s.yamaFights++;
				s.yamaDamage += num(f, "damageDealt");
			}
			else if (id == NpcID.YAMA_VOIDFLARE)
			{
				s.flareFights++;
				s.flareKills += num(f, "kills");
				s.split |= bool(f, "monsterRoom") || "Void Flare".equals(str(f, "groupName"));
			}
			else
			{
				s.judgeFights++;
			}
			s.roomDamage += num(f, "damageDealt");
		}
		s.startMillis = lng(history.get(piece.get(0)).getAsJsonObject(), "startMillis");
		s.endMillis = lng(history.get(piece.get(piece.size() - 1)).getAsJsonObject(), "endMillis");
		return s;
	}

	/**
	 * Araxxor's kills, as History rebuilds his rooms, with other players' damage saved on them before T-106 (no room
	 * damage anywhere in the room). Each is moved only when all of it is on Araxxor himself, no fight was in a party,
	 * and each fight's total can be made of his {@code roomDamage} rules' amounts.
	 */
	static void findAraxxorKills(JsonArray history, BossRules rules, Plan plan)
	{
		final Fight[] fights = GSON.fromJson(history, Fight[].class);
		final Map<Fight, Integer> where = new IdentityHashMap<>();
		for (int i = 0; i < fights.length; i++)
		{
			where.put(fights[i], i);
		}
		for (Encounter room : LedgerHistory.rooms(Arrays.asList(fights), "Araxxor"))
		{
			if (!LedgerHistory.isKill(room) || room.getFights().stream().noneMatch(f -> f.getOthersDamage() > 0)
				|| room.getFights().stream().anyMatch(f -> f.getRoomDamage() > 0))
			{
				continue;
			}
			final long start = room.getFights().get(0).getStartMillis();
			String why = null;
			final Solo solo = new Solo();
			solo.startMillis = start;
			for (Fight f : room.getFights())
			{
				if (f.getOthersDamage() <= 0)
				{
					continue;
				}
				final BossRules.Boss boss = rules.forNpc(f.getTargetId());
				if (boss == null || !"Araxxor".equals(boss.name) || boss.roomDamage == null)
				{
					why = String.format("%d other players' damage on %s, which Araxxor's rules don't cover",
						f.getOthersDamage(), f.getTargetName());
				}
				else if (f.isInParty())
				{
					why = "fought in a party";
				}
				else if (!madeOf(f.getOthersDamage(), boss.roomDamage))
				{
					why = String.format("%d other players' damage on Araxxor, which his rules' amounts can't make",
						f.getOthersDamage());
				}
				if (why != null)
				{
					break;
				}
				solo.indices.add(where.get(f));
				solo.moved += f.getOthersDamage();
			}
			if (why == null)
			{
				plan.solos.add(solo);
			}
			else
			{
				plan.leftAlone.add("Araxxor kill of " + when(start) + ": " + why);
			}
		}
	}

	/** Whether {@code total} is a sum of splats each in one of the rules' amount ranges (any number of each). */
	static boolean madeOf(int total, List<BossRules.RoomDamageRule> rules)
	{
		final boolean[] can = new boolean[total + 1];
		can[0] = true;
		for (int v = 1; v <= total; v++)
		{
			for (BossRules.RoomDamageRule r : rules)
			{
				for (int a = r.amounts[0]; a <= Math.min(r.amounts[1], v) && !can[v]; a++)
				{
					can[v] = can[v - a];
				}
			}
		}
		return can[total];
	}

	// ---- changing them ----

	/** A copy of the history with the plan's changes, and nothing else, made to it. */
	static JsonArray apply(JsonArray history, Plan plan)
	{
		final JsonArray out = history.deepCopy();
		for (Stitch s : plan.stitches)
		{
			final int last = s.indices.get(s.indices.size() - 1);
			for (int i : s.indices)
			{
				final JsonObject f = out.get(i).getAsJsonObject();
				if (num(f, "targetId") == NpcID.YAMA_VOIDFLARE)
				{
					// An add of Yama's room, as #156 saves one: in his group, not a monster run, no run kill of its own.
					f.addProperty("groupName", "Yama");
					f.addProperty("monsterRoom", false);
					f.addProperty("kills", 0);
				}
				// One room: only his death closes it, on the kill. Set only where it changes, so nothing else is added.
				if (i == last || bool(f, "closedRoom"))
				{
					f.addProperty("closedRoom", i == last);
				}
				f.remove("roomEnd");
			}
		}
		for (Solo s : plan.solos)
		{
			for (int i : s.indices)
			{
				final JsonObject f = out.get(i).getAsJsonObject();
				f.addProperty("roomDamage", num(f, "roomDamage") + num(f, "othersDamage"));
				f.addProperty("othersDamage", 0);
			}
		}
		return out;
	}

	// ---- reading it back ----

	/** What the repaired file reads as through the plugin's own code; null when it reads as planned, else what's wrong. */
	static String verify(String json, JsonArray before, Plan plan)
	{
		final Fight[] fights;
		try
		{
			fights = GSON.fromJson(json, Fight[].class);
		}
		catch (RuntimeException e)
		{
			return "the plugin can't read it: " + e;
		}
		if (fights == null || fights.length != before.size())
		{
			return "it holds " + (fights == null ? 0 : fights.length) + " fights, not " + before.size();
		}
		// Nothing but the planned fields changed.
		final JsonArray after = GSON.fromJson(json, JsonArray.class);
		final JsonArray expected = apply(before, plan);
		for (int i = 0; i < after.size(); i++)
		{
			if (!after.get(i).equals(expected.get(i)))
			{
				return "fight " + i + " isn't what the plan made of it";
			}
		}
		// Each stitched kill is one room, a kill, holding exactly its pieces.
		final List<Encounter> rooms = SidePanelHistory.allRooms(Arrays.asList(fights));
		for (Stitch s : plan.stitches)
		{
			final Fight last = fights[s.indices.get(s.indices.size() - 1)];
			final Encounter room = rooms.stream().filter(r -> r.getFights().stream().anyMatch(f -> f == last))
				.findFirst().orElse(null);
			if (room == null || !SidePanelHistory.isKill(room) || room.getFights().size() != s.indices.size())
			{
				return "the Yama kill of " + when(s.startMillis) + " doesn't read as one whole kill";
			}
		}
		for (Solo s : plan.solos)
		{
			for (int i : s.indices)
			{
				if (fights[i].getOthersDamage() != 0)
				{
					return "the Araxxor kill of " + when(s.startMillis) + " still has other players' damage";
				}
			}
		}
		return null;
	}

	// ---- the run ----

	public static void main(String[] args) throws IOException
	{
		final boolean apply = Arrays.asList(args).contains("--apply");
		final boolean araxxor = Arrays.asList(args).contains("--araxxor-solo");
		// The folder the plugin's getPluginDirectory() gives it: RuneLite's plugin data, under the plugin's own name.
		final Filepath dir = Filepath.Unchecked.getRooted(RuneLite.PLUGIN_DATA)
			.joinSegment(PvmPerformanceConfig.INTERNAL_NAME);
		System.exit(run(dir, apply, araxxor, runeLiteRunning(), System.out));
	}

	/** The whole run against the folder {@code dir}: 0 when it did what was asked, 1 when it refused or failed. */
	static int run(Filepath dir, boolean apply, boolean araxxorSolo, boolean runeLiteRunning, PrintStream out)
		throws IOException
	{
		out.println("Luck vs Skill history repair (T-138). Close RuneLite first: a running plugin rewrites "
			+ FILE + " on its next save and would undo the repair.");
		final Filepath file = dir.joinSegment(FILE);
		if (!file.exists())
		{
			out.println("No " + FILE + " in the plugin's data folder: nothing to repair.");
			return 1;
		}
		final byte[] original = read(file);
		final JsonArray history = GSON.fromJson(new String(original, StandardCharsets.UTF_8), JsonArray.class);
		final Plan plan = plan(history, BossRules.load(GSON, null), araxxorSolo);
		describe(plan, history, araxxorSolo, out);
		if (plan.isEmpty())
		{
			out.println("Nothing to change.");
			return 0;
		}
		if (!apply)
		{
			out.println("Dry run: nothing was changed. Run it again with -Papply to write these changes.");
			return 0;
		}
		if (runeLiteRunning)
		{
			out.println("RuneLite is running: close it and run this again. Nothing was changed.");
			return 1;
		}
		// Refused if the file moved under us: the plugin, or anything else, saved it since it was read.
		if (!Arrays.equals(sha256(original), sha256(read(file))))
		{
			out.println(FILE + " changed while this ran. Nothing was changed: close RuneLite and run it again.");
			return 1;
		}
		final Filepath backup = dir.joinSegment(FILE + ".bak-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
			.format(new Date()));
		if (backup.exists())
		{
			out.println("A backup named " + backup.getFileName() + " is already there. Nothing was changed: run it again.");
			return 1;
		}
		backup.write(original);
		if (!Arrays.equals(sha256(original), sha256(read(backup))))
		{
			out.println("The backup didn't read back as written. Nothing was changed.");
			return 1;
		}
		out.println("Backup written: " + backup.getFileName());
		final String repaired = GSON.toJson(apply(history, plan));
		file.write(repaired.getBytes(StandardCharsets.UTF_8));
		final String problem = verify(new String(read(file), StandardCharsets.UTF_8), history, plan);
		if (problem != null)
		{
			file.write(original);
			out.println("Read back, " + problem + ". The original is put back; the backup " + backup.getFileName()
				+ " holds it too.");
			return 1;
		}
		out.println(String.format("Written and read back: %d Yama kills whole, %d Araxxor kills solo. "
			+ "Start RuneLite and check History.", plan.stitches.size(), plan.solos.size()));
		return 0;
	}

	static void describe(Plan plan, JsonArray history, boolean araxxorSolo, PrintStream out)
	{
		out.println(String.format("%d fights in %s.", history.size(), FILE));
		out.println(String.format("Yama kills saved before T-136 to stitch: %d", plan.stitches.size()));
		for (Stitch s : plan.stitches)
		{
			out.println(String.format("  %s: %d Yama, %d Judge and %d Void Flare fights (%s) -> "
					+ "one kill of %d ticks (%.1f s), %d damage on Yama, %d in the room", when(s.startMillis),
				s.yamaFights, s.judgeFights, s.flareFights, !s.split ? "not split, but no fight closed it"
					: s.flareKills + " flare room kill" + (s.flareKills == 1 ? "" : "s") + " dropped",
				(s.endMillis - s.startMillis) / 600, (s.endMillis - s.startMillis) / 1000.0, s.yamaDamage, s.roomDamage));
		}
		if (araxxorSolo)
		{
			out.println(String.format("Pre-T-106 Araxxor kills to mark solo: %d", plan.solos.size()));
			for (Solo s : plan.solos)
			{
				out.println(String.format("  %s: %d of other players' damage moved to the room's", when(s.startMillis),
					s.moved));
			}
		}
		for (String left : plan.leftAlone)
		{
			out.println("  Left alone: " + left);
		}
	}

	// ---- helpers ----

	private static boolean runeLiteRunning()
	{
		return ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c ->
		{
			final String exe = c.replace('\\', '/').toLowerCase(Locale.ROOT);
			return exe.endsWith("/runelite.exe") || exe.endsWith("/runelite");
		}).orElse(false));
	}

	private static byte[] read(Filepath file) throws IOException
	{
		try (InputStream in = file.openInputStream())
		{
			return in.readAllBytes();
		}
	}

	private static byte[] sha256(byte[] bytes)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static String when(long millis)
	{
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
	}

	private static int num(JsonObject f, String key)
	{
		final JsonElement e = f.get(key);
		return e == null || e.isJsonNull() ? 0 : e.getAsInt();
	}

	private static long lng(JsonObject f, String key)
	{
		final JsonElement e = f.get(key);
		return e == null || e.isJsonNull() ? 0L : e.getAsLong();
	}

	private static boolean bool(JsonObject f, String key)
	{
		final JsonElement e = f.get(key);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	private static String str(JsonObject f, String key)
	{
		final JsonElement e = f.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}

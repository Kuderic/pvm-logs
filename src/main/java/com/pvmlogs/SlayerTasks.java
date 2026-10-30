package com.pvmlogs;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Slayer tasks and the NPCs that count for them, for the slayer helmet's
 * on-task bonus (T-015). The table is RuneLite's
 * (runelite-client {@code plugins/slayer/Task}, BSD-2, Copyright (c) 2017,
 * Tyler; Copyright (c) 2018, Shaun Dreclin), and the matching is its Slayer
 * plugin's: an NPC counts when its name holds the task's name (as it is, or made
 * singular) or one of the task's extra target names as whole words, ignoring
 * case.
 * <p>
 * T-090 checked the table against the wiki's slayer task pages and Boss Slayer:
 * each name added since RuneLite's table has its source beside it. A task's name
 * also matches without a leading "The", Wilderness tasks leave out the
 * alternatives the game does not take there, and {@link Watch} corrects the
 * table from the game itself when the task's count falls on an NPC it said was
 * off task.
 */
final class SlayerTasks
{
	/** On task, off task, or not knowable; with why, for the debug log. */
	static final class Verdict
	{
		final boolean onTask;
		final boolean known;
		final String why;

		Verdict(boolean onTask, boolean known, String why)
		{
			this.onTask = onTask;
			this.known = known;
			this.why = why;
		}

		@Override
		public String toString()
		{
			return (known ? (onTask ? "yes" : "no") : "assumed yes") + " (" + why + ")";
		}
	}

	// RuneLite's table, with T-090's additions from the wiki, marked where they are.
	private static final Map<String, String[]> TARGETS;

	static
	{
		final Map<String, String[]> t = new HashMap<>();
		put(t, "Aberrant spectres", "Spectre");
		put(t, "Abyssal demons", "Abyssal Sire");
		put(t, "The Abyssal Sire");
		put(t, "The Alchemical Hydra");
		put(t, "Ankou");
		put(t, "Aquanites");
		put(t, "Araxxor");
		put(t, "Araxytes", "Araxxor");
		put(t, "Aviansies", "Kree'arra", "Flight Kilisa", "Flockleader Geerin", "Wingman Skree");
		put(t, "Bandits", "Bandit", "Black Heather", "Donny the Lad", "Speedy Keith");
		put(t, "Banshees");
		// The six brothers, none named "Barrows" (wiki, Barrows brothers; Boss Slayer: "Barrows brothers (not chests)").
		put(t, "Barrows Brothers", "Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested", "Karil the Tainted",
			"Torag the Corrupted", "Verac the Defiled");
		put(t, "Basilisks");
		put(t, "Bats", "Death wing");
		put(t, "Bears", "Callisto", "Artio");
		put(t, "Birds", "Chicken", "Rooster", "Terrorbird", "Seagull", "Vulture", "Duck", "Penguin", "Baby Roc");
		put(t, "Black demons", "Demonic gorilla", "Balfrug Kreeyath", "Skotizo", "Porazdir");
		put(t, "Black dragons");
		put(t, "Black Knights", "Black Knight");
		put(t, "Bloodveld");
		put(t, "Blue dragons", "Vorkath");
		put(t, "Brine rats");
		// "Artio can be killed instead" (wiki, Boss Slayer), as the Bears task already has it.
		put(t, "Callisto", "Artio");
		put(t, "Catablepon");
		put(t, "Cave bugs");
		put(t, "Cave crawlers", "Chasm crawler");
		put(t, "Cave horrors", "Cave abomination");
		put(t, "Cave kraken", "Kraken");
		put(t, "Cave slimes");
		put(t, "Cerberus");
		put(t, "Chaos druids");
		put(t, "The Chaos Elemental");
		put(t, "The Chaos Fanatic");
		put(t, "Cockatrice", "Cockathrice");
		put(t, "Cows", "Buffalo", "Brutus");
		put(t, "Crabs", "Ammonite Crab", "Frost Crab", "King Sand Crab", "Rock Crab", "Giant Rock Crab", "Sand Crab", "Swamp Crab");
		put(t, "Crawling hands", "Crushing hand");
		// The Deranged archaeologist "may be killed as an alternative on a crazy archaeologist boss Slayer task", "unless
		// the Wilderness is specified as the task location when given by Krystilia or Konar quo Maten" (wiki, Deranged
		// archaeologist; Boss Slayer: "Deranged archaeologist can be killed instead").
		// The Wilderness exception is NOT_IN_WILDERNESS below.
		put(t, "Crazy Archaeologists", "Deranged archaeologist");
		put(t, "Crocodiles");
		put(t, "Custodian Stalkers", "Ancient Custodian");
		put(t, "Dagannoth");
		// "a group of three dagannoth" (wiki, Dagannoth Kings), none of whose names is "Dagannoth King".
		put(t, "Dagannoth Kings", "Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme");
		put(t, "Dark beasts", "Night beast");
		put(t, "Dark warriors", "Dark warrior");
		put(t, "Deranged Archaeologist");
		// The shadow hound is in the monster table of the wiki's Slayer task/Dogs.
		put(t, "Dogs", "Jackal", "Temple Guardian", "Shadow hound");
		put(t, "Drakes");
		put(t, "Duke Sucellus");
		put(t, "Dust devils", "Choke devil");
		put(t, "Dwarves", "Dwarf", "Black Guard");
		put(t, "Earth warriors");
		put(t, "Elves", "Elf", "Iorwerth Warrior", "Iorwerth Archer");
		put(t, "Ents");
		put(t, "Fever spiders");
		put(t, "Fire giants", "Branda the Fire Queen");
		put(t, "Fleshcrawlers", "Flesh crawler");
		put(t, "Fossil island wyverns", "Ancient wyvern", "Long-tailed wyvern", "Spitting wyvern", "Taloned wyvern");
		put(t, "Frost dragons");
		put(t, "Gargoyles", "Dusk", "Dawn");
		put(t, "General Graardor");
		put(t, "Ghosts", "Death wing", "Tortured soul", "Forgotten Soul", "Revenant");
		put(t, "Ghouls");
		put(t, "The Giant Mole");
		put(t, "Goblins", "Sergeant Strongstack", "Sergeant Grimspike", "Sergeant Steelwill");
		put(t, "Greater demons", "K'ril Tsutsaroth", "Tstanon Karlak", "Skotizo", "Tormented Demon");
		put(t, "Green dragons", "Elvarg");
		put(t, "The Grotesque Guardians", "Dusk", "Dawn");
		put(t, "Gryphons");
		put(t, "Harpie bug swarms");
		put(t, "Hellhounds", "Cerberus");
		put(t, "Hill giants", "Cyclops", "Reanimated giant", "Obor");
		put(t, "Hobgoblins");
		put(t, "Hydras");
		put(t, "Icefiends");
		put(t, "Ice giants", "Eldric the Ice King");
		put(t, "Ice warriors", "Icelord");
		put(t, "Infernal mages", "Malevolent mage");
		// The Fight Caves' waves: "slayer experience from every volcanic creature killed in their respective area and
		// benefits from the slayer helmet's bonus for the entire run" (wiki, Slayer task/TzHaar; names from Volcanic
		// creatures).
		put(t, "TzTok-Jad", "Tz-Kih", "Tz-Kek", "Tok-Xil", "Yt-MejKot", "Ket-Zek", "Yt-HurKot");
		put(t, "Jellies", "Jelly");
		put(t, "Jungle horrors");
		put(t, "Kalphites");
		put(t, "The Kalphite Queen");
		put(t, "Killerwatts");
		put(t, "The King Black Dragon");
		put(t, "The Cave Kraken Boss", "Kraken");
		put(t, "Kree'arra");
		put(t, "K'ril Tsutsaroth");
		put(t, "Kurask");
		put(t, "Lava Dragons", "Lava dragon");
		put(t, "Lesser demons", "Zakl'n Gritch");
		// "Earthen Nagua can be found" at the Crypt of Tonali (wiki, Slayer task/Lesser Nagua).
		put(t, "Lesser Nagua", "Sulphur Nagua", "Frost Nagua", "Amoxliatl", "Earthen Nagua");
		put(t, "Lizardmen", "Lizardman");
		put(t, "Lizards");
		put(t, "The Maggot King");
		put(t, "Magic axes");
		put(t, "Mammoths");
		put(t, "Metal dragons", "Bronze dragon", "Iron Dragon", "Steel dragon", "Mithril dragon", "Adamant dragon", "Rune dragon");
		put(t, "Minotaurs");
		put(t, "Mogres");
		put(t, "Molanisks");
		put(t, "Monkeys", "Tortured gorilla", "Demonic gorilla", "Padulah");
		put(t, "Moss giants", "Bryophyta");
		put(t, "Mutated zygomites", "Zygomite", "Fungi");
		put(t, "Nechryael", "Nechryarch");
		put(t, "Ogres", "Enclave guard", "Mogre", "Ogress", "Skogre", "Zogre");
		put(t, "Otherworldly beings");
		put(t, "The Phantom Muspah");
		put(t, "Pirates", "Pirate");
		// The pyrelord, "Stronger version of pyrefiends", and its superior, the infernal pyrelord (wiki, Slayer
		// task/Pyrefiends).
		put(t, "Pyrefiends", "Flaming pyrelord", "Pyrelord", "Infernal pyrelord");
		// "Hell-rat behemoths" and "Scurrius can be fought" (wiki, Slayer task/Rats); the hyphen hides the first's "rat".
		put(t, "Rats", "Hell-Rat Behemoth", "Scurrius");
		put(t, "Red dragons");
		put(t, "Revenants");
		put(t, "Rockslugs");
		put(t, "Rogues");
		put(t, "Sarachnis");
		put(t, "Scabarites", "Scarab swarm", "Locust rider", "Scarab mage", "Small Scarab");
		put(t, "Scorpia");
		// "various fightable lobsters also counts as scorpions", and Scorpia's offspring and guardians are in the page's
		// table (wiki, Slayer task/Scorpions); "Scorpia's" is not the word "Scorpia".
		put(t, "Scorpions", "Scorpia", "Lobstrosity", "Giant lobster", "Scorpia's offspring", "Scorpia's guardian");
		put(t, "Sea snakes");
		put(t, "Shades", "Loar", "Phrin", "Riyl", "Asyn", "Fiyr", "Urium");
		put(t, "Shadow warriors");
		put(t, "The Shellbane Gryphon");
		put(t, "Skeletal wyverns");
		// Skogres are in the Skeletons row of the wiki's Slayer task list and on Slayer task/Skeletons.
		put(t, "Skeletons", "Vet'ion", "Calvar'ion", "Skeletal Mystic", "Skogre");
		put(t, "Smoke devils");
		put(t, "Sourhogs");
		put(t, "Spiders", "Kalrag", "Sarachnis", "Venenatis", "Spindel", "Araxxor", "Araxyte");
		put(t, "Spiritual creatures", "Spiritual ranger", "Spiritual mage", "Spiritual warrior");
		put(t, "Suqahs");
		put(t, "Terror dogs");
		put(t, "The Leviathan");
		put(t, "The Whisperer");
		put(t, "The Thermonuclear Smoke Devil");
		put(t, "Trolls", "Dad", "Arrg", "Stick", "Kraka", "Pee Hat", "Rock", "Twig", "Berry");
		put(t, "Turoth");
		// "TzHaar come in four varieties" (wiki, Slayer task/TzHaar); the hyphen keeps "Tzhaar" from matching them as a word.
		put(t, "Tzhaar", "TzTok-Jad", "TzKal-Zuk", "TzHaar-Hur", "TzHaar-Mej", "TzHaar-Xil", "TzHaar-Ket");
		// Venators, and their superior, are in the Vampyres row of the wiki's Slayer task list.
		put(t, "Vampyres", "Vyrewatch", "Venator");
		put(t, "Vardorvis");
		put(t, "Venators");
		// "Spindel may be killed instead" (wiki, Boss Slayer), as the Spiders task already has it.
		put(t, "Venenatis", "Spindel");
		// "Calvar'ion may be killed instead" (wiki, Boss Slayer), as the Skeletons task already has it.
		put(t, "Vet'ion", "Calvar'ion");
		put(t, "Vorkath");
		put(t, "Wall beasts");
		put(t, "Warped Creatures", "Warped terrorbird", "Warped tortoise", "Mutated terrorbird", "Mutated tortoise");
		put(t, "Waterfiends");
		put(t, "Werewolves", "Werewolf");
		put(t, "Wolves", "Wolf");
		put(t, "Wyrms", "Wyrmling", "Strykewyrm");
		put(t, "Commander Zilyana");
		put(t, "Zombies", "Undead", "Vorkath", "Zogre");
		// The Inferno's waves, as for TzTok-Jad above.
		put(t, "TzKal-Zuk", "Jal-Nib", "Jal-MejRah", "Jal-Ak", "Jal-AkRek-Mej", "Jal-AkRek-Xil", "Jal-AkRek-Ket", "Jal-ImKot",
			"Jal-Xil", "Jal-Zek", "JalTok-Jad", "Jal-MejJak", "Yt-HurKot");
		put(t, "Zulrah");
		TARGETS = Collections.unmodifiableMap(t);
	}

	private static void put(Map<String, String[]> t, String task, String... targets)
	{
		t.put(key(task), targets);
	}

	// The table's key for a task: lower case, without a leading "The". RuneLite spells boss tasks "The Giant Mole"; the
	// game's tables may not, and either spelling finds the same row.
	private static String key(String taskName)
	{
		return withoutThe(taskName).toLowerCase(Locale.ROOT);
	}

	private static String withoutThe(String taskName)
	{
		return taskName.regionMatches(true, 0, "The ", 0, 4) ? taskName.substring(4) : taskName;
	}

	// Slayer masters as varbit SLAYER_MASTER numbers them. Krystilia's 7 is RuneLite's own (plugins/slayer/SlayerPlugin,
	// KRYSTILIA_SLAYER_MASTER, beside Mortimer's 10). Konar's 8 is a guess from the order RuneLite's 7 and 10 fit (Turael
	// 1, Mazchna, Vannaka, Chaeldar, Duradel, Nieve, Krystilia 7, Konar 8, Spria, Mortimer 10); no log has shown it yet,
	// so the SETUP line says the master's number.
	static final int KRYSTILIA = 7;
	static final int KONAR = 8;

	/**
	 * Alternatives the game does not take when the task is in the Wilderness: the Deranged archaeologist, who lives on
	 * Fossil Island, for a crazy archaeologist task "when given by Krystilia or Konar quo Maten" with the Wilderness as
	 * its location (wiki, Deranged archaeologist). Boss Slayer says the substitution "does not apply"
	 * to Konar's tasks at all, so any task of hers is taken as one in the Wilderness here.
	 */
	private static final Map<String, String[]> NOT_IN_WILDERNESS = Collections.singletonMap(
		key("Crazy Archaeologists"), new String[]{"Deranged archaeologist"});

	/**
	 * Whether the task is set in the Wilderness: Krystilia's are (wiki, Boss Slayer: she "will only roll boss tasks in
	 * the Wilderness"), Konar's are taken as such (above), and so is a task whose area names it.
	 *
	 * @param master   varbit SLAYER_MASTER, 0 when not known
	 * @param areaName the name of Konar's area (varp SLAYER_AREA, through the game's SlayerArea table), or null
	 */
	static boolean inWilderness(int master, String areaName)
	{
		return master == KRYSTILIA || master == KONAR
			|| areaName != null && areaName.toLowerCase(Locale.ROOT).contains("wilderness");
	}

	private SlayerTasks()
	{
	}

	/**
	 * Whether an NPC of this name counts for the task, outside the Wilderness. The task's own name, made singular,
	 * always counts; the table adds the names that do not contain it (Dusk and Dawn for gargoyles, the Kraken for
	 * cave kraken).
	 */
	static boolean counts(String taskName, String npcName)
	{
		return counts(taskName, npcName, false);
	}

	/** As {@link #counts(String, String)}, without the alternatives a Wilderness task does not take. */
	static boolean counts(String taskName, String npcName, boolean inWilderness)
	{
		if (taskName == null || taskName.isEmpty() || npcName == null)
		{
			return false;
		}
		final String name = npcName.replace(' ', ' ');
		final String task = key(taskName);
		// OSRS Wiki, Bestiary/Slayer assignments: revenants belong to Revenants and
		// Ghosts, not their living namesakes (also Slayer task/Ghosts).
		if (name.regionMatches(true, 0, "Revenant ", 0, 9) && !task.equals("revenants") && !task.equals("ghosts"))
		{
			return false;
		}
		// T-090 follow-up: the Zombies Champion is not a Zombies task target.
		if (task.equals("zombies") && name.equalsIgnoreCase("Zombies Champion"))
		{
			return false;
		}

		// The name as it is as well as made singular: a boss task's name is the boss's, and "Vardorvis" made singular is
		// "Vardorvi", which RuneLite's rule alone would miss. And without a leading "The": the task "The Giant Mole" is
		// the NPC "Giant Mole", which RuneLite's rule misses too.
		final String bare = withoutThe(taskName);
		if (pattern(taskName).matcher(name).find() || pattern(taskName.replaceAll("s$", "")).matcher(name).find()
			|| !bare.equals(taskName) && (pattern(bare).matcher(name).find()
				|| pattern(bare.replaceAll("s$", "")).matcher(name).find()))
		{
			return true;
		}
		final String[] targets = TARGETS.get(key(taskName));
		if (targets == null)
		{
			return false;
		}
		final String[] notHere = inWilderness ? NOT_IN_WILDERNESS.get(key(taskName)) : null;
		for (String target : targets)
		{
			// Rock is the troll's whole name (OSRS Wiki, Slayer task/Trolls).
			// Matching it inside Rock Crab also granted the Trolls task bonus there.
			final boolean matches = target.equals("Rock") ? name.equalsIgnoreCase(target) : pattern(target).matcher(name).find();
			if (matches && !named(notHere, name))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean named(String[] targets, String name)
	{
		if (targets != null)
		{
			for (String target : targets)
			{
				if (pattern(target).matcher(name).find())
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Pattern pattern(String target)
	{
		return Pattern.compile("(?:\\s|^)" + Pattern.quote(target) + "(?:\\s|$)", Pattern.CASE_INSENSITIVE);
	}

	/** As {@link #verdict(int, String, String, int, int, String, Set)}, with no master, area name or correction known. */
	static Verdict verdict(int count, String taskName, String npcName, int area)
	{
		return verdict(count, taskName, npcName, area, 0, null, Collections.emptySet());
	}

	/**
	 * Whether the fight is on task.
	 *
	 * @param count    the kills left (varp SLAYER_COUNT); none means no task
	 * @param taskName the task's name from the game's tables, or null if they could not be read
	 * @param area     Konar's assigned area (varp SLAYER_AREA), 0 for none
	 * @param master   who gave the task (varbit SLAYER_MASTER), 0 when not known
	 * @param areaName the area's name from the game's SlayerArea table, or null
	 * @param learned  NPC names, lower case, the game has counted for this task although the table said no: see
	 *                 {@link Watch}
	 */
	static Verdict verdict(int count, String taskName, String npcName, int area, int master, String areaName,
		Set<String> learned)
	{
		if (count <= 0)
		{
			return new Verdict(false, true, "no slayer task");
		}
		if (taskName == null)
		{
			// The tables could not be read: assume on task, as the old setting's default did.
			return new Verdict(true, false, "task with " + count + " left, its name could not be read");
		}
		final String task = "task is " + taskName + ", " + count + " left" + (master > 0 ? ", master " + master : "");
		if (npcName != null && learned.contains(learnedKey(taskName, npcName)))
		{
			return new Verdict(true, true, task + "; the game counted this NPC for it earlier (TASK MISMATCH)");
		}
		final boolean wilderness = inWilderness(master, areaName);
		if (!counts(taskName, npcName, wilderness))
		{
			if (wilderness && counts(taskName, npcName, false))
			{
				return new Verdict(false, true, task + "; " + npcName + " is not taken for it in the Wilderness");
			}
			return new Verdict(false, true, task);
		}
		if (area > 0)
		{
			// Konar's tasks count only in her assigned area, which is not checked here.
			return new Verdict(true, false, task + ", in Konar's area " + area
				+ (areaName != null ? " (" + areaName + ")" : "") + ", location not checked");
		}
		return new Verdict(true, true, task);
	}

	// What a TASK MISMATCH learns: the NPC, for this task only.
	private static String learnedKey(String taskName, String npcName)
	{
		return key(taskName) + "|" + npcName.replace(' ', ' ').toLowerCase(Locale.ROOT);
	}

	/**
	 * The game's own check on the table (T-090): when the task's count falls as an NPC the table said was off task
	 * dies, the table was wrong. The NPC is logged as a TASK MISMATCH and counts as on task for the rest of the session.
	 * <p>
	 * The count falling and the death are matched within {@link #WINDOW} ticks of each other, either way round: the
	 * death is seen when the NPC despawns, after its death animation, and which tick the count falls on is not in a log.
	 * A mismatch needs the NPC to be one the player was fighting, and no NPC the table does count to have died in that
	 * window, so another player's kill nearby cannot teach it anything.
	 */
	static final class Watch
	{
		static final int WINDOW = 20;

		private static final class Death
		{
			final int npcId;
			final String name;
			final boolean mine;
			final boolean onTask;
			final int tick;

			Death(int npcId, String name, boolean mine, boolean onTask, int tick)
			{
				this.npcId = npcId;
				this.name = name;
				this.mine = mine;
				this.onTask = onTask;
				this.tick = tick;
			}
		}

		private final Set<String> learned = new HashSet<>();
		private final Deque<Death> deaths = new ArrayDeque<>();
		private String taskName;
		private boolean wilderness;
		private int count;
		// A fall in the count not yet put down to a death.
		private int fallTick = Integer.MIN_VALUE;
		private int fallFrom;
		private int fallTo;
		private String fallTask;
		private boolean fallWilderness;

		/**
		 * A tick gone by: a fall the window has closed on is settled here when no event came after it.
		 *
		 * @return the TASK MISMATCH line, or null
		 */
		String onTick(int tick)
		{
			return check(tick);
		}

		/** The NPC names, by task, the game has corrected the table on. */
		Set<String> learned()
		{
			return Collections.unmodifiableSet(learned);
		}

		/**
		 * The task while there is one: its name, whether it is set in the Wilderness, and the kills left, which seeds the
		 * count before {@link #onCount} has seen it change.
		 */
		void noteTask(String taskName, boolean wilderness, int count)
		{
			if (taskName != null && count > 0)
			{
				this.taskName = taskName;
				this.wilderness = wilderness;
				if (this.count <= 0)
				{
					this.count = count;
				}
			}
		}

		/**
		 * The kills left, as varp SLAYER_COUNT now reads.
		 *
		 * @return the TASK MISMATCH line, or null
		 */
		String onCount(int newCount, int tick)
		{
			final int was = count;
			count = newCount;
			if (was > 0 && newCount < was && taskName != null)
			{
				fallTick = tick;
				fallFrom = was;
				fallTo = newCount;
				fallTask = taskName;
				fallWilderness = wilderness;
				return check(tick);
			}
			return null;
		}

		/**
		 * An NPC died (seen as it despawns dead).
		 *
		 * @param mine whether it was the player's fight's target
		 * @return the TASK MISMATCH line, or null
		 */
		String onDeath(int npcId, String name, boolean mine, int tick)
		{
			if (name == null)
			{
				return null;
			}
			final String task = taskName;
			final boolean onTask = task != null && (counts(task, name, wilderness)
				|| learned.contains(learnedKey(task, name)));
			deaths.addLast(new Death(npcId, name, mine, onTask, tick));
			return check(tick);
		}

		private String check(int now)
		{
			// Kept while they could still be put to a fall: the window either side of it, or of now.
			final int oldest = fallTick == Integer.MIN_VALUE ? now - WINDOW : Math.min(now, fallTick) - WINDOW;
			while (!deaths.isEmpty() && deaths.peekFirst().tick < oldest)
			{
				deaths.removeFirst();
			}
			if (fallTick == Integer.MIN_VALUE)
			{
				return null;
			}
			Death offTask = null;
			for (Death d : deaths)
			{
				if (Math.abs(d.tick - fallTick) > WINDOW)
				{
					continue;
				}
				if (d.onTask)
				{
					// The table already explains the fall.
					fallTick = Integer.MIN_VALUE;
					deaths.remove(d);
					return null;
				}
				if (d.mine && offTask == null)
				{
					offTask = d;
				}
			}
			if (offTask == null)
			{
				if (now - fallTick > WINDOW)
				{
					fallTick = Integer.MIN_VALUE;
				}
				return null;
			}
			// Only once the window has closed with no NPC the table counts dying in it: the on-task death may still come.
			if (now - fallTick <= WINDOW && now - offTask.tick <= WINDOW)
			{
				return null;
			}
			fallTick = Integer.MIN_VALUE;
			deaths.remove(offTask);
			learned.add(learnedKey(fallTask, offTask.name));
			return "TASK MISMATCH " + offTask.name + " (npc " + offTask.npcId + "): the " + fallTask + " task's count fell "
				+ fallFrom + " -> " + fallTo + " as it died, but the table says it does not count"
				+ (fallWilderness ? " (the task is in the Wilderness)" : "")
				+ "; counted as on task for the rest of the session";
		}
	}

	/** How many tasks the table knows, for the tests. */
	static int size()
	{
		return TARGETS.size();
	}
}

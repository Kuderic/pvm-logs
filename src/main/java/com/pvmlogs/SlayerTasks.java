package com.pvmlogs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Slayer tasks and the NPCs that count for them, for the slayer helmet's
 * on-task bonus (T-015). The table is RuneLite's
 * (runelite-client {@code plugins/slayer/Task}, BSD-2, Copyright (c) 2017,
 * Tyler; Copyright (c) 2018, Shaun Dreclin), and the matching is its Slayer
 * plugin's: an NPC counts when its name holds the task's name (as it is, or made
 * singular) or one of the task's extra target names as whole words, ignoring
 * case.
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
		put(t, "Barrows Brothers");
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
		put(t, "Callisto");
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
		put(t, "Crazy Archaeologists");
		put(t, "Crocodiles");
		put(t, "Custodian Stalkers", "Ancient Custodian");
		put(t, "Dagannoth");
		put(t, "Dagannoth Kings");
		put(t, "Dark beasts", "Night beast");
		put(t, "Dark warriors", "Dark warrior");
		put(t, "Deranged Archaeologist");
		put(t, "Dogs", "Jackal", "Temple Guardian");
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
		put(t, "TzTok-Jad");
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
		put(t, "Lesser Nagua", "Sulphur Nagua", "Frost Nagua", "Amoxliatl");
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
		put(t, "Pyrefiends", "Flaming pyrelord");
		put(t, "Rats");
		put(t, "Red dragons");
		put(t, "Revenants");
		put(t, "Rockslugs");
		put(t, "Rogues");
		put(t, "Sarachnis");
		put(t, "Scabarites", "Scarab swarm", "Locust rider", "Scarab mage", "Small Scarab");
		put(t, "Scorpia");
		put(t, "Scorpions", "Scorpia", "Lobstrosity");
		put(t, "Sea snakes");
		put(t, "Shades", "Loar", "Phrin", "Riyl", "Asyn", "Fiyr", "Urium");
		put(t, "Shadow warriors");
		put(t, "The Shellbane Gryphon");
		put(t, "Skeletal wyverns");
		put(t, "Skeletons", "Vet'ion", "Calvar'ion", "Skeletal Mystic");
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
		put(t, "Tzhaar", "TzTok-Jad", "TzKal-Zuk");
		put(t, "Vampyres", "Vyrewatch");
		put(t, "Vardorvis");
		put(t, "Venators");
		put(t, "Venenatis");
		put(t, "Vet'ion");
		put(t, "Vorkath");
		put(t, "Wall beasts");
		put(t, "Warped Creatures", "Warped terrorbird", "Warped tortoise", "Mutated terrorbird", "Mutated tortoise");
		put(t, "Waterfiends");
		put(t, "Werewolves", "Werewolf");
		put(t, "Wolves", "Wolf");
		put(t, "Wyrms", "Wyrmling", "Strykewyrm");
		put(t, "Commander Zilyana");
		put(t, "Zombies", "Undead", "Vorkath", "Zogre");
		put(t, "TzKal-Zuk");
		put(t, "Zulrah");
		TARGETS = Collections.unmodifiableMap(t);
	}

	private static void put(Map<String, String[]> t, String task, String... targets)
	{
		t.put(task.toLowerCase(Locale.ROOT), targets);
	}

	private SlayerTasks()
	{
	}

	/**
	 * Whether an NPC of this name counts for the task. The task's own name, made
	 * singular, always counts; the table adds the names that do not contain it
	 * (Dusk and Dawn for gargoyles, the Kraken for cave kraken).
	 */
	static boolean counts(String taskName, String npcName)
	{
		if (taskName == null || taskName.isEmpty() || npcName == null)
		{
			return false;
		}
		final String name = npcName.replace('\u00A0', ' ');
		// The name as it is as well as made singular: a boss task's name is the boss's, and "Vardorvis" made singular is
		// "Vardorvi", which RuneLite's rule alone would miss.
		if (pattern(taskName).matcher(name).find() || pattern(taskName.replaceAll("s$", "")).matcher(name).find())
		{
			return true;
		}
		final String[] targets = TARGETS.get(taskName.toLowerCase(Locale.ROOT));
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

	/**
	 * Whether the fight is on task.
	 *
	 * @param count    the kills left (varp SLAYER_COUNT); none means no task
	 * @param taskName the task's name from the game's tables, or null if they could not be read
	 * @param area     Konar's assigned area (varp SLAYER_AREA), 0 for none
	 */
	static Verdict verdict(int count, String taskName, String npcName, int area)
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
		if (!counts(taskName, npcName))
		{
			return new Verdict(false, true, "task is " + taskName + ", " + count + " left");
		}
		if (area > 0)
		{
			// Konar's tasks count only in her assigned area, which is not checked here.
			return new Verdict(true, false, "task is " + taskName + " in Konar's area " + area + ", location not checked");
		}
		return new Verdict(true, true, "task is " + taskName + ", " + count + " left");
	}

	/** How many tasks the table knows, for the tests. */
	static int size()
	{
		return TARGETS.size();
	}
}

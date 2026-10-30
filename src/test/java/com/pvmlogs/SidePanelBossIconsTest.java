package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * T-054: the boss icons. Which boss shows which hiscores sprite, a raid's rooms
 * their raid's, and the rest none; each sprite asked for once; an icon arriving
 * after its row repaints its box and lays nothing out.
 */
public class SidePanelBossIconsTest
{
	/** Holds every sprite asked for until the test hands it over, as the client's SpriteManager does. */
	private static final class HeldSprites implements SidePanelIcons
	{
		final Map<Integer, List<Consumer<BufferedImage>>> asked = new LinkedHashMap<>();

		@Override
		public void item(int itemId, Consumer<BufferedImage> done)
		{
		}

		@Override
		public void sprite(int spriteId, Consumer<BufferedImage> done)
		{
			asked.computeIfAbsent(spriteId, k -> new ArrayList<>()).add(done);
		}

		void deliver(int spriteId, BufferedImage img)
		{
			for (Consumer<BufferedImage> d : asked.getOrDefault(spriteId, new ArrayList<>()))
			{
				d.accept(img);
			}
		}
	}

	private HeldSprites sprites;

	@Before
	public void heldSprites()
	{
		sprites = new HeldSprites();
		SidePanelBossIcons.setSource(sprites);
	}

	@After
	public void noSprites()
	{
		SidePanelBossIcons.setSource(SidePanelIcons.NONE);
	}

	private static BufferedImage sprite(int w, int h)
	{
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				img.setRGB(x, y, (x + y) % 2 == 0 ? 0xFFC03030 : 0xFF3050C0);
			}
		}
		return img;
	}

	// ---- which icon ----

	@Test
	public void everyHiscoresBossShowsItsOwnIcon()
	{
		int bosses = 0;
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			if (s.getType() == HiscoreSkillType.BOSS)
			{
				bosses++;
				assertEquals(s.getName(), s.getSpriteId(), SidePanelBossIcons.sprite(s.getName(), null));
			}
		}
		assertTrue(bosses > 60);
	}

	/** Every boss the plugin has rules for and every room it groups, with the icon each shows; null for none. */
	private static Map<String, HiscoreSkill> knownBosses()
	{
		final Map<String, HiscoreSkill> m = new LinkedHashMap<>();
		// The boss rules (boss-rules.json).
		m.put("Vardorvis", HiscoreSkill.VARDORVIS);
		m.put("Araxxor", HiscoreSkill.ARAXXOR);
		m.put("Yama", HiscoreSkill.YAMA);
		m.put("Abyssal Sire", HiscoreSkill.ABYSSAL_SIRE);
		m.put("Bork", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Verzik Vitur", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Demonic Brutus", HiscoreSkill.BRUTUS);
		m.put("Doom of Mokhaiotl", HiscoreSkill.DOOM_OF_MOKHAIOTL);
		m.put("Sotetseg", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("The Nightmare", HiscoreSkill.NIGHTMARE);
		m.put("Phosani's Nightmare", HiscoreSkill.PHOSANIS_NIGHTMARE);
		m.put("Akkha", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Ba-Ba", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Kephri", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Zebak", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Obelisk (Tombs of Amascut)", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("The Wardens, phase 3", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Nex", HiscoreSkill.NEX);
		m.put("The Hueycoatl", HiscoreSkill.THE_HUEYCOATL);
		m.put("Tekton", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Corporeal Beast", HiscoreSkill.CORPOREAL_BEAST);
		m.put("Ice demon", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Phantom Muspah", HiscoreSkill.PHANTOM_MUSPAH);
		m.put("Vanguards", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Vasa Nistirio", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Vespula", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Muttadiles", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Pestilent Bloat", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Zulrah", HiscoreSkill.ZULRAH);
		// T-088: in the rules for her burrow and as one per world.
		m.put("Giant Mole", HiscoreSkill.GIANT_MOLE);
		m.put("Vorkath", HiscoreSkill.VORKATH);
		m.put("Cerberus", HiscoreSkill.CERBERUS);
		m.put("Commander Zilyana", HiscoreSkill.COMMANDER_ZILYANA);
		m.put("General Graardor", HiscoreSkill.GENERAL_GRAARDOR);
		m.put("Duke Sucellus", HiscoreSkill.DUKE_SUCELLUS);
		m.put("The Leviathan", HiscoreSkill.THE_LEVIATHAN);
		m.put("The Whisperer", HiscoreSkill.THE_WHISPERER);
		m.put("Calvar'ion", HiscoreSkill.CALVARION);
		m.put("Vet'ion", HiscoreSkill.VETION);
		m.put("Archive Guardian", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Ezekiel", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Moia", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Xarth", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Ocellus, phase 1", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Ocellus, phase 2 (The Blade)", HiscoreSkill.FRACTURED_ARCHIVE);
		m.put("Ocellus, phase 3", HiscoreSkill.FRACTURED_ARCHIVE);
		// The rooms it groups (EncounterGroup).
		m.put("Guardians", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Lizardman shamans", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Skeletal mystics", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Tightrope", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Great Olm (hands)", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Great Olm", HiscoreSkill.CHAMBERS_OF_XERIC);
		m.put("Baboons", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Wardens", HiscoreSkill.TOMBS_OF_AMASCUT);
		m.put("Nylocas (melee)", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Nylocas (ranged)", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Nylocas (magic)", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Nylocas Vasilias", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Nylocas Prinkipas", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Xarpus", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("The Maiden of Sugadinti", HiscoreSkill.THEATRE_OF_BLOOD);
		m.put("Hespori", HiscoreSkill.HESPORI);
		// T-087's rules.
		m.put("Maggot King", HiscoreSkill.MAGGOT_KING);
		// T-061's groups.
		m.put("Grotesque Guardians", HiscoreSkill.GROTESQUE_GUARDIANS);
		m.put("The Royal Titans", HiscoreSkill.THE_ROYAL_TITANS);
		m.put("Kraken", HiscoreSkill.KRAKEN);
		// Bosses whose NPCs are named otherwise.
		m.put("Dusk", HiscoreSkill.GROTESQUE_GUARDIANS);
		m.put("Dawn", HiscoreSkill.GROTESQUE_GUARDIANS);
		m.put("Corrupted Hunllef", HiscoreSkill.THE_CORRUPTED_GAUNTLET);
		m.put("Crystalline Hunllef", HiscoreSkill.THE_GAUNTLET);
		m.put("Branda the Fire Queen", HiscoreSkill.THE_ROYAL_TITANS);
		m.put("Eldric the Ice King", HiscoreSkill.THE_ROYAL_TITANS);
		// No hiscores entry: none.
		m.put("Hespori (A Night at the Theatre)", null);
		m.put("Abyssal demon", null);
		m.put("Lizardman shaman", null);
		m.put("Cave kraken", null);
		m.put("Goblin", null);
		return m;
	}

	@Test
	public void everyBossThePluginKnowsShowsItsIconOrNone()
	{
		for (Map.Entry<String, HiscoreSkill> e : knownBosses().entrySet())
		{
			assertEquals(e.getKey(), e.getValue(), SidePanelBossIcons.skill(e.getKey(), null));
			assertEquals(e.getKey(), e.getValue() == null ? SidePanelBossIcons.NONE : e.getValue().getSpriteId(),
				SidePanelBossIcons.sprite(e.getKey(), null));
		}
	}

	@Test
	public void theTableCoversEveryBossWithRulesAndEveryGroupedRoom() throws Exception
	{
		// So a boss added to the rules or a room added to the groups gets a line in knownBosses().
		final Map<String, HiscoreSkill> known = knownBosses();
		try (java.io.InputStream in = SidePanelBossIconsTest.class.getResourceAsStream("/com/pvmlogs/boss-rules.json"))
		{
			final com.google.gson.JsonObject rules = new com.google.gson.Gson().fromJson(
				new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
			for (com.google.gson.JsonElement b : rules.getAsJsonArray("bosses"))
			{
				final String name = b.getAsJsonObject().get("name").getAsString();
				assertTrue("boss rules: " + name, known.containsKey(name));
			}
		}
		final String source = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/com/pvmlogs/EncounterGroup.java")), java.nio.charset.StandardCharsets.UTF_8);
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("put\\(groups, \"([^\"]+)\"").matcher(source);
		int groups = 0;
		while (m.find())
		{
			groups++;
			assertTrue("group: " + m.group(1), known.containsKey(m.group(1)));
		}
		assertTrue(groups > 20);
	}

	@Test
	public void aRaidsRoomsShowTheRaidsIcon()
	{
		assertEquals(HiscoreSkill.TOMBS_OF_AMASCUT.getSpriteId(), SidePanelBossIcons.sprite("Akkha", RaidType.TOMBS_OF_AMASCUT));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC.getSpriteId(),
			SidePanelBossIcons.sprite("Great Olm (left claw)", RaidType.CHAMBERS_OF_XERIC));
		// A room the table doesn't know, but fought in a raid: its raid's.
		assertEquals(HiscoreSkill.THEATRE_OF_BLOOD.getSpriteId(),
			SidePanelBossIcons.sprite("Nylocas Matomenos", RaidType.THEATRE_OF_BLOOD));
		// The whole raid's scope, by its own name.
		assertEquals(HiscoreSkill.THEATRE_OF_BLOOD.getSpriteId(), SidePanelBossIcons.sprite("Theatre of Blood", null));
	}

	@Test
	public void namesAreMatchedWithoutCaseOrApostrophes()
	{
		assertEquals(HiscoreSkill.KREEARRA.getSpriteId(), SidePanelBossIcons.sprite("kree'arra", null));
		assertEquals(HiscoreSkill.KREEARRA.getSpriteId(), SidePanelBossIcons.sprite("Kree’arra", null));
		assertEquals(HiscoreSkill.CALVARION.getSpriteId(), SidePanelBossIcons.sprite("CALVARION", null));
		assertEquals(SidePanelBossIcons.NONE, SidePanelBossIcons.sprite(null, null));
	}

	// ---- loading and drawing ----

	@Test
	public void eachSpriteIsAskedForOnceAndKept()
	{
		final int sire = HiscoreSkill.ABYSSAL_SIRE.getSpriteId();
		final int[] arrived = new int[2];
		SidePanelBossIcons.load(sire, () -> arrived[0]++);
		SidePanelBossIcons.load(sire, () -> arrived[1]++);
		assertEquals(1, sprites.asked.get(sire).size());
		assertEquals(0, arrived[0] + arrived[1]);
		sprites.deliver(sire, sprite(25, 25));
		assertEquals(1, arrived[0]);
		assertEquals(1, arrived[1]);
		// Here now: no more asking.
		final int requests = SidePanelBossIcons.requests();
		final int[] now = new int[1];
		SidePanelBossIcons.load(sire, () -> now[0]++);
		assertEquals(1, now[0]);
		assertEquals(requests, SidePanelBossIcons.requests());
		assertEquals(1, sprites.asked.get(sire).size());
		// No icon asks for nothing.
		SidePanelBossIcons.load(SidePanelBossIcons.NONE, () -> now[0]++);
		assertEquals(1, now[0]);
	}

	@Test
	public void eachSizeIsMadeOnceFromTheFullSprite()
	{
		final int vard = HiscoreSkill.VARDORVIS.getSpriteId();
		SidePanelBossIcons.load(vard, () ->
		{
		});
		sprites.deliver(vard, sprite(21, 25));
		// The header's 20 px box: at 1x, 1.5x and 2x.
		final BufferedImage one = SidePanelBossIcons.scaled(vard, 20);
		assertEquals(new Dimension(17, 20), new Dimension(one.getWidth(), one.getHeight()));
		assertSame(one, SidePanelBossIcons.scaled(vard, 20));
		final BufferedImage half = SidePanelBossIcons.scaled(vard, 30);
		assertEquals(new Dimension(25, 30), new Dimension(half.getWidth(), half.getHeight()));
		final BufferedImage two = SidePanelBossIcons.scaled(vard, 40);
		assertEquals(new Dimension(34, 40), new Dimension(two.getWidth(), two.getHeight()));
		// Its own size: its own pixels.
		final BufferedImage own = SidePanelBossIcons.scaled(vard, 25);
		assertEquals(21, own.getWidth());
		assertEquals(sprite(21, 25).getRGB(3, 4), own.getRGB(3, 4));
		// Twice its size: each pixel doubled exactly.
		final BufferedImage doubled = SidePanelBossIcons.fit(sprite(10, 10), 20);
		assertEquals(sprite(10, 10).getRGB(3, 4), doubled.getRGB(6, 8));
		assertEquals(sprite(10, 10).getRGB(3, 4), doubled.getRGB(7, 9));
	}

	@Test
	public void transparentEdgesDontDarkenWhenScaledDown()
	{
		final BufferedImage src = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		src.setRGB(0, 0, 0xFFFFFFFF);
		final BufferedImage out = SidePanelBossIcons.fit(src, 2);
		// A quarter covered by white: white at a quarter's alpha, not grey.
		assertEquals(0xFFFFFF, out.getRGB(0, 0) & 0xFFFFFF);
		assertEquals(64, out.getRGB(0, 0) >>> 24);
	}

	private static List<SidePanelBossIcons.View> views(Container c)
	{
		final List<SidePanelBossIcons.View> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelBossIcons.View)
			{
				out.add((SidePanelBossIcons.View) k);
			}
			else if (k instanceof Container)
			{
				out.addAll(views((Container) k));
			}
		}
		return out;
	}

	private static void paint(JComponent c, double scale)
	{
		c.setSize(225, Math.max(1, c.getPreferredSize().height));
		SidePanelPictures.layout(c);
		final BufferedImage img = new BufferedImage((int) Math.ceil(225 * scale),
			(int) Math.ceil(c.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		c.paint(g);
		g.dispose();
	}

	@Test
	public void theFightHeaderShowsTheBossesIconBeforeItsName()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.live())));
		final List<SidePanelBossIcons.View> icons = views(panel.fightTab());
		assertEquals(1, icons.size());
		assertEquals(HiscoreSkill.VARDORVIS.getSpriteId(), icons.get(0).sprite());
		assertEquals(new Dimension(SidePanelBossIcons.HEADER_BOX, SidePanelBossIcons.HEADER_BOX),
			icons.get(0).getPreferredSize());
	}

	@Test
	public void anIconArrivingLaterRepaintsItsBoxAndLaysNothingOut() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
			panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.live())));
			final SidePanelFightTab tab = panel.fightTab();
			paint(tab, 1);
			final SidePanelBossIcons.View icon = views(tab).get(0);
			assertEquals(0, icon.paints());
			final Dimension before = icon.getPreferredSize();
			final int builds = tab.builds();
			final int layouts = SidePanelParts.relayouts;
			final List<JComponent> dirty = new ArrayList<>();
			final List<JComponent> invalid = new ArrayList<>();
			final RepaintManager was = RepaintManager.currentManager((JComponent) null);
			RepaintManager.setCurrentManager(new RepaintManager()
			{
				@Override
				public void addDirtyRegion(JComponent c, int x, int y, int w, int h)
				{
					dirty.add(c);
				}

				@Override
				public synchronized void addInvalidComponent(JComponent c)
				{
					invalid.add(c);
				}
			});
			try
			{
				sprites.deliver(HiscoreSkill.VARDORVIS.getSpriteId(), sprite(21, 25));
			}
			finally
			{
				RepaintManager.setCurrentManager(was);
			}
			assertEquals(Arrays.asList(icon), dirty);
			assertTrue(invalid.isEmpty());
			assertEquals(layouts, SidePanelParts.relayouts);
			assertEquals(builds, tab.builds());
			assertEquals(before, icon.getPreferredSize());
			paint(tab, 1);
			assertEquals(1, icon.paints());
		});
	}

	@Test
	public void theIconIsDrawnAtTheScreensScale()
	{
		final SidePanelBossIcons.View icon = new SidePanelBossIcons.View(HiscoreSkill.VARDORVIS.getSpriteId(),
			SidePanelBossIcons.HEADER_BOX);
		sprites.deliver(HiscoreSkill.VARDORVIS.getSpriteId(), sprite(21, 25));
		icon.setSize(SidePanelBossIcons.HEADER_BOX, SidePanelBossIcons.HEADER_BOX);
		for (double scale : new double[]{1, 1.5, 2})
		{
			final int side = (int) Math.round(SidePanelBossIcons.HEADER_BOX * scale);
			final BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
			final Graphics2D g = img.createGraphics();
			g.scale(scale, scale);
			icon.paint(g);
			g.dispose();
			// The sprite's own aspect, the box's full height, centred across.
			int left = side;
			int right = -1;
			int top = side;
			int bottom = -1;
			for (int y = 0; y < side; y++)
			{
				for (int x = 0; x < side; x++)
				{
					if ((img.getRGB(x, y) >>> 24) != 0)
					{
						left = Math.min(left, x);
						right = Math.max(right, x);
						top = Math.min(top, y);
						bottom = Math.max(bottom, y);
					}
				}
			}
			assertEquals("height at " + scale, side, bottom - top + 1);
			assertEquals("width at " + scale, Math.round(21.0 * side / 25), right - left + 1);
		}
	}

	@Test
	public void aBossWithNoIconHasNoneAndAsksForNothing()
	{
		// History: Abyssal demons (a Slayer monster) keep the row's box, empty, so the names line up.
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		panel.historyTab().refresh();
		paint(panel.historyTab(), 1);
		final Map<Integer, Integer> bySprite = new HashMap<>();
		for (SidePanelBossIcons.View v : views(panel.historyTab()))
		{
			bySprite.merge(v.sprite(), 1, Integer::sum);
			assertEquals(new Dimension(SidePanelBossIcons.ROW_BOX, SidePanelBossIcons.ROW_BOX), v.getPreferredSize());
			assertEquals(0, v.paints());
		}
		// The card's header and its list row; the Sire's row; the demons' row, empty.
		assertEquals(Integer.valueOf(2), bySprite.get(HiscoreSkill.VARDORVIS.getSpriteId()));
		assertEquals(Integer.valueOf(1), bySprite.get(HiscoreSkill.ABYSSAL_SIRE.getSpriteId()));
		assertEquals(Integer.valueOf(1), bySprite.get(SidePanelBossIcons.NONE));
		assertNull(sprites.asked.get(SidePanelBossIcons.NONE));

		// The Fight tab: no icon, no box before the name.
		final SidePanelModel.Input demon = SidePanelExamples.live();
		demon.name = "Abyssal demon";
		final PvmPerformancePanel fight = new PvmPerformancePanel(null, null, null, null);
		fight.apply(new PanelSnapshot(SidePanelModel.fight(demon)));
		assertTrue(views(fight.fightTab()).isEmpty());
	}

	@Test
	public void theTripsRowsShowTheirBossesIcons()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()),
			SidePanelModel.trip(SidePanelExamples.trip())));
		final List<SidePanelBossIcons.View> icons = views(panel.tripTab());
		assertEquals(SidePanelExamples.trip().tripRows.size(), icons.size());
		for (SidePanelBossIcons.View v : icons)
		{
			assertEquals(HiscoreSkill.VARDORVIS.getSpriteId(), v.sprite());
		}
		// One sprite for seven rows: asked for once.
		assertEquals(1, sprites.asked.get(HiscoreSkill.VARDORVIS.getSpriteId()).size());
	}

	@Test
	public void nothingIsAskedOrBuiltPerTick()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.live())));
		sprites.deliver(HiscoreSkill.VARDORVIS.getSpriteId(), sprite(21, 25));
		final SidePanelFightTab tab = panel.fightTab();
		final SidePanelBossIcons.View icon = views(tab).get(0);
		final int builds = tab.builds();
		final int requests = SidePanelBossIcons.requests();
		for (int i = 0; i < 20; i++)
		{
			final SidePanelModel.Input later = SidePanelExamples.live();
			((LedgerExamples.FakeScope) later.scope).combatTicks += 5 * i;
			panel.apply(new PanelSnapshot(SidePanelModel.fight(later)));
		}
		assertEquals(builds, tab.builds());
		assertEquals(requests, SidePanelBossIcons.requests());
		assertSame(icon, views(tab).get(0));
		assertNotNull(SidePanelBossIcons.image(HiscoreSkill.VARDORVIS.getSpriteId()));
	}
}

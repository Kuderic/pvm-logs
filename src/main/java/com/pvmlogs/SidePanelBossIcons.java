package com.pvmlogs;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JComponent;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;

/**
 * T-054: the boss icons beside a boss's name in the side panel. The icon is the
 * game's own hiscores sprite for that boss ({@link HiscoreSkill#getSpriteId()}),
 * loaded through RuneLite's {@code SpriteManager} as RuneLite's Hiscore panel
 * loads it: no download, no file, no dependency. A raid's room takes its raid's
 * icon; anything without a hiscores entry (a Slayer monster) gets none.
 *
 * <p>Each sprite is asked for once and kept, and each size it's drawn at is
 * scaled once and kept. A {@link View} reserves its box, so an icon arriving
 * after its row was built repaints that box and lays nothing out. Swing's thread
 * only.
 */
final class SidePanelBossIcons
{
	/** No icon. */
	static final int NONE = -1;
	/** The Fight tab header's icon box, px at 1×: beside the name and the line under it. */
	static final int HEADER_BOX = 20;
	/** A list row's icon box, px at 1×: History's boss list and card, Trip's rooms. */
	static final int ROW_BOX = 16;

	private SidePanelBossIcons()
	{
	}

	// ---- which icon ----

	/** The hiscores' bosses, by their name as {@link #key} writes it. */
	private static final Map<String, HiscoreSkill> HISCORES = hiscores();
	/** The plugin's names that aren't the hiscores' own (T-054), by {@link #key}. */
	private static final Map<String, HiscoreSkill> OTHER_NAMES = otherNames();

	/**
	 * The sprite for a boss or room name, or {@link #NONE}. {@code raid}: the
	 * raid the room was fought in, or null; a raid's room shows its raid's icon.
	 */
	static int sprite(String name, RaidType raid)
	{
		final HiscoreSkill skill = skill(name, raid);
		return skill == null ? NONE : skill.getSpriteId();
	}

	/** The hiscores entry whose icon a boss or room shows, or null for none. */
	static HiscoreSkill skill(String name, RaidType raid)
	{
		if (raid != null)
		{
			return raid(raid);
		}
		if (name == null)
		{
			return null;
		}
		final String k = key(name);
		final HiscoreSkill own = HISCORES.get(k);
		if (own != null)
		{
			return own;
		}
		final HiscoreSkill other = OTHER_NAMES.get(k);
		if (other != null)
		{
			return other;
		}
		// Rooms named by part or phase: Great Olm's hands, the Nylocas, the Wardens' phases.
		if (k.startsWith("great olm"))
		{
			return HiscoreSkill.CHAMBERS_OF_XERIC;
		}
		if (k.startsWith("nylocas"))
		{
			return HiscoreSkill.THEATRE_OF_BLOOD;
		}
		if (k.startsWith("the wardens"))
		{
			return HiscoreSkill.TOMBS_OF_AMASCUT;
		}
		if (k.startsWith("ocellus"))
		{
			return HiscoreSkill.FRACTURED_ARCHIVE;
		}
		return null;
	}

	/**
	 * A raid's icon. The plugin doesn't tell a raid's modes apart (Challenge Mode, Hard Mode, Expert), so each raid
	 * takes its normal mode's icon.
	 */
	static HiscoreSkill raid(RaidType raid)
	{
		switch (raid)
		{
			case CHAMBERS_OF_XERIC:
				return HiscoreSkill.CHAMBERS_OF_XERIC;
			case THEATRE_OF_BLOOD:
				return HiscoreSkill.THEATRE_OF_BLOOD;
			case TOMBS_OF_AMASCUT:
				return HiscoreSkill.TOMBS_OF_AMASCUT;
			default:
				return null;
		}
	}

	/** A name as the table compares it: lower case, without apostrophes, as the plugin's boss check reads names. */
	static String key(String name)
	{
		return name.toLowerCase(Locale.ENGLISH).replace("'", "").replace("’", "").replace("`", "").trim();
	}

	private static Map<String, HiscoreSkill> hiscores()
	{
		final Map<String, HiscoreSkill> m = new HashMap<>();
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			if (s.getType() == HiscoreSkillType.BOSS)
			{
				m.put(key(s.getName()), s);
			}
		}
		return Collections.unmodifiableMap(m);
	}

	private static Map<String, HiscoreSkill> otherNames()
	{
		final Map<String, HiscoreSkill> m = new HashMap<>();
		// The hiscores' name drops "The"; the plugin's rooms keep the NPC's.
		put(m, HiscoreSkill.NIGHTMARE, "The Nightmare");
		// The hard version of Brutus (the boss rules' COWBOSS_HARDMODE) shares Brutus's kill count entry's icon.
		put(m, HiscoreSkill.BRUTUS, "Demonic Brutus");
		// Bosses whose NPCs have names of their own.
		put(m, HiscoreSkill.GROTESQUE_GUARDIANS, "Dusk", "Dawn");
		put(m, HiscoreSkill.THE_GAUNTLET, "Crystalline Hunllef");
		put(m, HiscoreSkill.THE_CORRUPTED_GAUNTLET, "Corrupted Hunllef");
		put(m, HiscoreSkill.THE_ROYAL_TITANS, "Branda the Fire Queen", "Eldric the Ice King");
		// The Fractured Archive's bosses, as Jagex's "The Fractured Archive - Out Today" names them (the boss rules'
		// sources): the archive's icon. Ocellus's phases are matched by prefix.
		put(m, HiscoreSkill.FRACTURED_ARCHIVE, "Archive Guardian", "Bork", "Ezekiel", "Moia", "Xarth");
		// A raid's rooms, for a room whose raid isn't known: its raid's icon.
		put(m, HiscoreSkill.CHAMBERS_OF_XERIC, "Tekton", "Vasa Nistirio", "Vespula", "Vanguards", "Muttadiles",
			"Guardians", "Lizardman shamans", "Skeletal mystics", "Tightrope", "Ice demon");
		put(m, HiscoreSkill.THEATRE_OF_BLOOD, "The Maiden of Sugadinti", "Pestilent Bloat", "Sotetseg", "Xarpus",
			"Verzik Vitur");
		put(m, HiscoreSkill.TOMBS_OF_AMASCUT, "Akkha", "Ba-Ba", "Kephri", "Zebak", "Baboons", "Wardens",
			"Obelisk (Tombs of Amascut)", "Tumeken's Warden", "Elidinis' Warden", "Akkha's Shadow");
		return Collections.unmodifiableMap(m);
	}

	private static void put(Map<String, HiscoreSkill> m, HiscoreSkill skill, String... names)
	{
		for (String n : names)
		{
			m.put(key(n), skill);
		}
	}

	// ---- loading, once ----

	/** Where sprites come from: the panel's {@code SpriteManager}; none in tests and renders unless they give one. */
	private static SidePanelIcons source = SidePanelIcons.NONE;
	private static final Map<Integer, BufferedImage> loaded = new HashMap<>();
	private static final Map<Integer, List<Runnable>> waiting = new HashMap<>();
	/** Scaled copies, by sprite and device size. */
	private static final Map<Long, BufferedImage> scaled = new HashMap<>();
	private static int requests;

	/** The panel's sprites (T-054). A new source starts a new cache. */
	static void setSource(SidePanelIcons icons)
	{
		final SidePanelIcons s = icons == null ? SidePanelIcons.NONE : icons;
		if (s == source)
		{
			return;
		}
		source = s;
		loaded.clear();
		waiting.clear();
		scaled.clear();
	}

	/** How many sprites were asked of the source, for tests: once each. */
	static int requests()
	{
		return requests;
	}

	/** Asks for a sprite once; {@code arrived} runs when it comes (at once if it's here). */
	static void load(int sprite, Runnable arrived)
	{
		if (sprite == NONE)
		{
			return;
		}
		if (loaded.containsKey(sprite))
		{
			arrived.run();
			return;
		}
		final List<Runnable> queue = waiting.get(sprite);
		if (queue != null)
		{
			queue.add(arrived);
			return;
		}
		final List<Runnable> q = new ArrayList<>();
		q.add(arrived);
		waiting.put(sprite, q);
		requests++;
		final SidePanelIcons from = source;
		from.sprite(sprite, img ->
		{
			if (img == null || from != source)
			{
				return;
			}
			loaded.put(sprite, img);
			final List<Runnable> run = waiting.remove(sprite);
			if (run != null)
			{
				for (Runnable r : run)
				{
					r.run();
				}
			}
		});
	}

	/** The sprite as it came, or null before it has. */
	static BufferedImage image(int sprite)
	{
		return loaded.get(sprite);
	}

	/**
	 * The sprite fitted to a box of {@code box} device pixels, made once per
	 * size, always from the full-resolution sprite: each output pixel the
	 * average of the sprite's area it covers. At a whole ratio that is the
	 * sprite's pixels exactly; otherwise only a pixel's edge blends, so pixel art
	 * keeps its shapes, smaller at 1× and sharp at 2×.
	 */
	static BufferedImage scaled(int sprite, int box)
	{
		final BufferedImage src = loaded.get(sprite);
		if (src == null || box <= 0)
		{
			return null;
		}
		final long k = ((long) sprite << 32) | box;
		BufferedImage img = scaled.get(k);
		if (img == null)
		{
			img = fit(src, box);
			scaled.put(k, img);
		}
		return img;
	}

	/** {@link #scaled}'s work, without the cache. */
	static BufferedImage fit(BufferedImage src, int box)
	{
		final int w = src.getWidth();
		final int h = src.getHeight();
		final double f = Math.min((double) box / w, (double) box / h);
		final int ow = Math.max(1, (int) Math.round(w * f));
		final int oh = Math.max(1, (int) Math.round(h * f));
		return ow == w && oh == h ? argb(src) : area(src, ow, oh);
	}

	private static BufferedImage argb(BufferedImage src)
	{
		final BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}

	/** Each output pixel the average of the source area it covers, alpha-weighted so edges don't darken. */
	private static BufferedImage area(BufferedImage src, int ow, int oh)
	{
		final int w = src.getWidth();
		final int h = src.getHeight();
		final BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
		final double sx = (double) w / ow;
		final double sy = (double) h / oh;
		for (int oy = 0; oy < oh; oy++)
		{
			final double y0 = oy * sy;
			final double y1 = y0 + sy;
			for (int ox = 0; ox < ow; ox++)
			{
				final double x0 = ox * sx;
				final double x1 = x0 + sx;
				double a = 0;
				double r = 0;
				double g = 0;
				double b = 0;
				double total = 0;
				for (int y = (int) y0; y < Math.min(h, Math.ceil(y1)); y++)
				{
					final double cy = Math.min(y1, y + 1) - Math.max(y0, y);
					for (int x = (int) x0; x < Math.min(w, Math.ceil(x1)); x++)
					{
						final double c = cy * (Math.min(x1, x + 1) - Math.max(x0, x));
						if (c <= 0)
						{
							continue;
						}
						final int p = src.getRGB(x, y);
						final double pa = ((p >>> 24) & 0xFF) / 255.0;
						a += pa * c;
						r += ((p >> 16) & 0xFF) * pa * c;
						g += ((p >> 8) & 0xFF) * pa * c;
						b += (p & 0xFF) * pa * c;
						total += c;
					}
				}
				if (a <= 0 || total <= 0)
				{
					continue;
				}
				final int alpha = (int) Math.round(255 * a / total);
				out.setRGB(ox, oy, (alpha << 24) | ((int) Math.round(r / a) << 16) | ((int) Math.round(g / a) << 8)
					| (int) Math.round(b / a));
			}
		}
		return out;
	}

	// ---- the icon beside a name ----

	/**
	 * A boss's icon in a box of its own size, reserved whether or not the
	 * sprite has come, so its arrival repaints this box and moves nothing.
	 * {@link #NONE} leaves the box empty.
	 */
	static final class View extends JComponent
	{
		private final int box;
		private int sprite = NONE;
		private int paints;

		View(int sprite, int box)
		{
			this.box = box;
			setOpaque(false);
			set(sprite);
		}

		/** Another boss: the box stays, the sprite is asked for (once) and the box repainted. */
		void set(int newSprite)
		{
			if (newSprite == sprite)
			{
				return;
			}
			sprite = newSprite;
			final int asked = newSprite;
			load(newSprite, () ->
			{
				if (sprite == asked)
				{
					repaint();
				}
			});
			repaint();
		}

		int sprite()
		{
			return sprite;
		}

		/** Paints that drew a sprite, for tests. */
		int paints()
		{
			return paints;
		}

		@Override
		public java.awt.Dimension getPreferredSize()
		{
			return new java.awt.Dimension(box, box);
		}

		@Override
		public java.awt.Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public java.awt.Dimension getMaximumSize()
		{
			return new java.awt.Dimension(box, Integer.MAX_VALUE);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (sprite == NONE || image(sprite) == null || !(g instanceof Graphics2D))
			{
				return;
			}
			final Graphics2D g2 = (Graphics2D) g;
			final AffineTransform t = g2.getTransform();
			if (t.getShearX() != 0 || t.getShearY() != 0)
			{
				return;
			}
			// The box in device pixels, at the screen's own scale, and the sprite made for that size.
			final double scale = Math.min(t.getScaleX(), t.getScaleY());
			final int device = (int) Math.round(box * scale);
			final BufferedImage img = scaled(sprite, device);
			if (img == null)
			{
				return;
			}
			// Centred in the box (the box is centred in the component's height), drawn pixel for pixel.
			final int top = (getHeight() - box) / 2;
			final Point2D corner = t.transform(new Point2D.Double(0, top), null);
			final int dx = (int) Math.round(corner.getX()) + (device - img.getWidth()) / 2;
			final int dy = (int) Math.round(corner.getY()) + (device - img.getHeight()) / 2;
			g2.setTransform(new AffineTransform());
			g2.drawImage(img, dx, dy, null);
			g2.setTransform(t);
			paints++;
		}
	}
}

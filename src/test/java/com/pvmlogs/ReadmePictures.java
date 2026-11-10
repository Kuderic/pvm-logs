package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.ui.overlay.components.PanelComponent;

/**
 * Not a test: writes every picture in the README (T-048) from the plugin's own drawing code, so they look exactly as
 * the game draws them, show no account details, and can be made again whenever the look changes.
 * <pre>
 *   ./gradlew shadowJar
 *   java -Djava.awt.headless=true -cp build/libs/pvm-logs-*-all.jar com.pvmlogs.ReadmePictures [out dir]
 * </pre>
 * The out dir defaults to {@code docs/images}. It reads the render harnesses ({@link LedgerPictures},
 * {@link SidePanelPictures} and their examples) without changing them.
 * <ul>
 * <li>The overlays: rows turned into line components by {@link PvmPerformanceOverlay#lines} as the overlay does, in
 * RuneLite's own {@link PanelComponent} with its standard background, over a neutral dark scene (the overlays are
 * see-through). The fight overlay, its loadout and the Fight tab all come from one simulated kill, {@link Graardor};
 * the ruled kill is {@link #ruledKill()}'s Vardorvis. All with a new install's settings ({@link #asInstalled()}).</li>
 * <li>The side panel's History and Trip tabs, from {@link SidePanelPictures}.</li>
 * <li>A GIF of the Fight tab through a simulated kill, redrawn every {@link PvmPerformancePanel#UPDATE_TICKS} ticks as
 * the panel updates, then the view after the kill, all cut to {@link #MAX_GIF_HEIGHT}.</li>
 * </ul>
 */
public final class ReadmePictures
{
	/** Space around a box on its backdrop, and between two stacked boxes (RuneLite stacks overlays 2 px apart). */
	private static final int MARGIN = 10;
	private static final int STACK_GAP = 2;
	/** Backdrop width: a little game around the box, not just the box. */
	private static final int SCENE_WIDTH = 300;

	/** The GIF: game seconds per GIF second, and how long the last frame is held. */
	static final double SPEED = 7.5;
	private static final int HOLD_LAST_MS = 3000;
	/**
	 * The GIF's frames are cut to this height (T-067: T-048's was 1,312 px, too tall to watch on a README). The live
	 * frames fit; the after-kill frame keeps its top: where the time went, Luck and Execution.
	 */
	static final int MAX_GIF_HEIGHT = 980;

	private ReadmePictures()
	{
	}

	public static void main(String[] args) throws IOException
	{
		final Path out = Paths.get(args.length > 0 ? args[0] : "docs/images");
		Files.createDirectories(out);
		RuneLiteLAF.setup();

		final Graardor g = new Graardor();
		png(out, "overlay-mid-fight", overlays(g.mid, LedgerRows.loadout(g.versus(tentacle()), false)));
		png(out, "overlay-after-kill", overlays(g.dead));
		png(out, "overlay-ruled-kill", overlays(ruledKill()));
		png(out, "overlay-loadout", overlays(LedgerRows.loadout(g.versus(hammer()), false)));

		png(out, "panel-fight", SidePanelPictures.render(g.panel, 1));
		png(out, "panel-history", SidePanelPictures.renderHistory(false, 1));
		png(out, "panel-trip", SidePanelPictures.renderTrip(SidePanelExamples.trip(), 1));

		gif(out.resolve("panel-fight-live.gif"), killFrames());
	}

	// ---- Overlays ----

	/**
	 * One simulated kill at General Graardor for the README's overlays and Fight tab, so every picture tells the same
	 * story (Eric, 2026-10-28: the Defence card's hammer and the Damage card's specs must agree). An Abyssal tentacle,
	 * two Dragon warhammer specs to open, the first missed and the second landed (Defence 250 → 175), a thrall, no
	 * healing. Graardor, not Vardorvis, because Vardorvis's Defence can't be drained.
	 */
	static final class Graardor
	{
		/** Graardor's levels, from the wiki: 255 hitpoints, 250 Defence, 80 Magic; a hammer takes 30% of Defence. */
		static final int HP = 255;
		static final int DEFENCE = 250;
		static final int MAGIC = 80;
		final List<LedgerRows.Row> mid;
		final List<LedgerRows.Row> dead;
		final SidePanelModel.Input panel;
		private final Fight fight;

		Graardor()
		{
			final LedgerRows.Settings all = asInstalled();
			final Kill kill = Kill.graardor();
			fight = kill.fight;
			// Mid-fight: about half its hitpoints gone.
			while (!kill.over() && kill.hp > HP / 2)
			{
				kill.run(1);
			}
			final int drained = kill.defenceNow();
			mid = LedgerRows.build(LedgerRows.of(kill.room), LedgerRows.State.fight(kill.boss, false, false, false)
				.withDrain(new LedgerRows.LiveDrain(drained, DEFENCE, 0, MAGIC, MAGIC, 0)), all);
			while (!kill.over())
			{
				kill.run(1);
			}
			kill.end();
			// Your usual here, a few seconds slower than this kill.
			final int seconds = (int) Math.round(kill.tick * 0.6);
			final LedgerHistory.Average usual = new LedgerHistory.Average(
				new LedgerHistory.Spread(seconds + 6, seconds + 1, seconds + 12), 10);
			dead = LedgerRows.build(LedgerRows.of(kill.room), LedgerRows.State.fight(kill.boss, true, true, false)
				.withAverage(usual), all);
			panel = kill.input(new SidePanelWeaponRows());
			panel.ended = true;
			panel.killed = true;
			panel.endMillis = kill.now();
			panel.average = usual;
		}

		/** The DPS overlay's `vs` line (T-073), named and coloured as the plugin does it for this fight. */
		LedgerRows.Loadout versus(LedgerRows.Loadout l)
		{
			l.versus = LoadoutOverlay.versus(fight);
			l.versusRgb = LoadoutOverlay.versusColour(fight);
			return l;
		}
	}

	/**
	 * The fight overlay's settings as a new install has them (T-073: Execution's parts "Costing only"), so the pictures
	 * show what a new player sees.
	 */
	static LedgerRows.Settings asInstalled()
	{
		final PvmPerformanceConfig fresh = new PvmPerformanceConfig()
		{
		};
		final LedgerRows.Settings s = new LedgerRows.Settings();
		s.luck = fresh.overlayLuck();
		s.parts = fresh.overlayParts();
		s.details = fresh.overlayDetails();
		return s;
	}

	/**
	 * After a kill at a boss with attack rules (T-043): {@link LedgerPictures}' "14-ruled" Vardorvis figures, with the
	 * bleed taken by kind (T-062) and a new install's settings.
	 */
	static List<LedgerRows.Row> ruledKill()
	{
		final LedgerExamples.FakeScope ruled = LedgerExamples.kill();
		ruled.combatTicks = 216;
		ruled.ticksLost = 25 + 27;
		ruled.ticksLostEating = 27;
		ruled.damageTaken = 263;
		ruled.avoidableEatingTicks = 21.6;
		ruled.damageTakenAvoidable = 141;
		ruled.dotTaken = 27;
		ruled.dotTakenByKind.put("bleed", 27);
		ruled.biggestHitTaken = 41;
		ruled.damageTakenUnprotected = 85;
		ruled.damageVariance = 11436;
		ruled.setup(0.98, 0.85, 2.352, 0);
		return LedgerRows.build(ruled, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), asInstalled());
	}

	/** The DPS overlay's tentacle mid-fight at Graardor, its Defence drained to 175: no special, so no spec rows. */
	static LedgerRows.Loadout tentacle()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Abyssal tentacle";
		l.maxHit = Kill.TENTACLE_MAX;
		l.accuracy = Kill.TENTACLE_DRAINED;
		// 49 max, 66%: about 16.2 a swing every 2.4 s.
		l.dps = 6.7;
		return l;
	}

	/** The Dragon warhammer, for the DPS overlay's picture: its own figures and its special's (×1.5 max hit). */
	static LedgerRows.Loadout hammer()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Dragon warhammer";
		l.maxHit = 44;
		l.accuracy = 0.49;
		// 44 max, 49%: about 10.8 a swing every 3.6 s.
		l.dps = 3.0;
		l.specMaxHit = Kill.HAMMER_MAX;
		l.specAccuracy = Kill.HAMMER_ACCURACY;
		l.specAverage = Kill.HAMMER_ACCURACY * Kill.HAMMER_MAX / 2.0;
		return l;
	}

	/** One or more overlay boxes stacked top-left over the scene, as RuneLite stacks them. */
	static BufferedImage overlays(List<?>... boxes)
	{
		final Font font = FontManager.getRunescapeFont();
		final List<PanelComponent> panels = new ArrayList<>();
		final List<Dimension> sizes = new ArrayList<>();
		final Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		probe.setFont(font);
		int width = 0;
		int height = 0;
		for (List<?> box : boxes)
		{
			@SuppressWarnings("unchecked")
			final List<LedgerRows.Row> rows = (List<LedgerRows.Row>) box;
			final PvmPerformanceOverlay.Lines lines = PvmPerformanceOverlay.lines(probe, rows, null);
			final PanelComponent panel = new PanelComponent();
			panel.getChildren().addAll(lines.components);
			panel.setPreferredSize(new Dimension(lines.width, 0));
			// Twice, as RuneLite does across frames: the first lays out, the second draws at the settled size.
			panel.render(probe);
			final Dimension size = panel.render(probe);
			panels.add(panel);
			sizes.add(size);
			width = Math.max(width, size.width);
			height += size.height + (height == 0 ? 0 : STACK_GAP);
		}
		probe.dispose();

		final int w = Math.max(SCENE_WIDTH, width + 2 * MARGIN);
		final int h = height + 2 * MARGIN;
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		scene(g, w, h);
		g.setFont(font);
		int y = MARGIN;
		for (int i = 0; i < panels.size(); i++)
		{
			panels.get(i).setPreferredLocation(new Point(MARGIN, y));
			panels.get(i).render(g);
			y += sizes.get(i).height + STACK_GAP;
		}
		g.dispose();
		return img;
	}

	/**
	 * A neutral dark scene standing in for the game behind the overlay: a dim, slightly warm floor with some grain and
	 * a darker edge. Seeded, so the same picture comes out every time.
	 */
	static void scene(Graphics2D g, int w, int h)
	{
		g.setPaint(new GradientPaint(0, 0, new Color(58, 52, 44), w, h, new Color(30, 28, 26)));
		g.fillRect(0, 0, w, h);
		final Random rng = new Random(48);
		for (int i = 0; i < w * h / 40; i++)
		{
			final int x = rng.nextInt(w);
			final int y = rng.nextInt(h);
			final int v = rng.nextInt(18);
			g.setColor(new Color(0, 0, 0, 20 + v));
			g.fillRect(x, y, 2 + rng.nextInt(3), 1 + rng.nextInt(2));
		}
		g.setPaint(null);
	}

	// ---- The live Fight tab ----

	/** A GIF frame and how long it shows. */
	static final class Frame
	{
		final BufferedImage image;
		final int millis;

		Frame(BufferedImage image, int millis)
		{
			this.image = image;
			this.millis = millis;
		}
	}

	/**
	 * A simulated Vardorvis kill, fed to the real panel a tick at a time as the plugin feeds it (the way
	 * {@code SidePanelNoLagTest} drives one): a tentacle every four ticks with a burning claws special now and then, a
	 * thrall's splats, short idle and eating spans, and hits taken. The panel is drawn every 5 ticks, then once more
	 * after the kill, held at the end.
	 */
	static List<Frame> killFrames()
	{
		final Kill kill = new Kill();
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final List<BufferedImage> images = new ArrayList<>();
		while (!kill.over())
		{
			kill.run(PvmPerformancePanel.UPDATE_TICKS);
			panel.apply(new PanelSnapshot(SidePanelModel.fight(kill.input(rows)), null));
			images.add(SidePanelPictures.draw(panel.tabs(), panel.fightTab(), 1));
		}
		kill.end();
		final SidePanelModel.Input dead = kill.input(rows);
		dead.ended = true;
		dead.killed = true;
		dead.endMillis = kill.now();
		panel.apply(new PanelSnapshot(SidePanelModel.fight(dead), null));
		images.add(SidePanelPictures.draw(panel.tabs(), panel.fightTab(), 1));

		// One size for every frame: the tallest, cut to MAX_GIF_HEIGHT, the rest padded with the panel's own background.
		int height = 0;
		for (BufferedImage i : images)
		{
			height = Math.max(height, Math.min(i.getHeight(), MAX_GIF_HEIGHT));
		}
		final int frameMillis = (int) Math.round(PvmPerformancePanel.UPDATE_TICKS * 600 / SPEED);
		final List<Frame> frames = new ArrayList<>();
		for (int i = 0; i < images.size(); i++)
		{
			final BufferedImage src = images.get(i);
			final BufferedImage f = new BufferedImage(src.getWidth(), height, BufferedImage.TYPE_INT_RGB);
			final Graphics2D g = f.createGraphics();
			g.setColor(SidePanelStyle.PANEL);
			g.fillRect(0, 0, f.getWidth(), height);
			g.drawImage(src, 0, 0, null);
			g.dispose();
			frames.add(new Frame(f, i == images.size() - 1 ? HOLD_LAST_MS : frameMillis));
		}
		return frames;
	}

	/**
	 * A simulated kill, ticks counted from 0, seeded so the pictures come out the same every time. The GIF's is
	 * Vardorvis (700 HP, a claws special every 15th attack); {@link #graardor()} is the README's drain example.
	 */
	static final class Kill
	{
		/** When the kill starts: an afternoon, as the panel examples use. */
		static final long START = SidePanelExamples.at(16, 3);
		/** Picked for an ordinary kill: about two minutes, luck in the middle. */
		static final long SEED = 255;
		static final int TENTACLE_MAX = 49;
		/** The tentacle's accuracy at Graardor's full Defence, and at 175 once a hammer lands (illustrative). */
		static final double TENTACLE_FULL = 0.54;
		static final double TENTACLE_DRAINED = 0.66;
		static final int HAMMER_MAX = 66;
		static final double HAMMER_ACCURACY = 0.60;

		final String boss;
		final int maxHp;
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		final Fight fight;
		final Encounter room;
		final FightTimeline line;
		final Random rng;
		int tick;
		int hp;
		private int cooldown;
		private int attacks;
		/** Graardor only: the hammer specs to open with, and whether each lands; null for Vardorvis's claws. */
		private final boolean[] hammers;
		private int specsLanded;
		private double accuracy = TENTACLE_FULL;
		private double drainValue;

		Kill()
		{
			this("Vardorvis", 700, SEED, null);
		}

		Kill(long seed)
		{
			this("Vardorvis", 700, seed, null);
		}

		private Kill(String boss, int maxHp, long seed, boolean[] hammers)
		{
			this.boss = boss;
			this.maxHp = maxHp;
			this.hp = maxHp;
			this.hammers = hammers;
			rng = new Random(seed);
			fight = new Fight(boss, NpcIds.GOBLIN, 1, maxHp, START, null, 0);
			room = new Encounter(boss, null, START);
			store.setClock(() -> clock[0]);
			store.attach(fight);
			line = store.of(fight);
			room.add(fight);
		}

		/** Picked for an ordinary Graardor kill: 0:34, damage on expected (luck 50%). */
		static final long GRAARDOR_SEED = 42;

		/** General Graardor: two hammer specs to open, the first missed, the second landed. */
		static Kill graardor()
		{
			return graardor(GRAARDOR_SEED);
		}

		static Kill graardor(long seed)
		{
			return new Kill("General Graardor", Graardor.HP, seed, new boolean[]{false, true});
		}

		boolean over()
		{
			return hp <= 0;
		}

		/** Graardor's Defence now: 30% off for each hammer that landed. */
		int defenceNow()
		{
			int d = Graardor.DEFENCE;
			for (int i = 0; i < specsLanded; i++)
			{
				d -= (int) (d * 0.3);
			}
			return d;
		}

		void run(int ticks)
		{
			for (int end = tick + ticks; tick < end && hp > 0; tick++)
			{
				clock[0] = tick;
				final long now = now();
				// A thrall's hit every four ticks, from the tenth.
				if (tick >= 10 && tick % 4 == 2)
				{
					damage(rng.nextInt(4), true);
				}
				// Hits taken now and then.
				if (tick % 7 == 3 && rng.nextInt(3) == 0)
				{
					fight.recordDamageTaken(5 + rng.nextInt(25), now);
				}
				// Short idle stretches, and a bite to eat in each.
				if (tick % 61 >= 57)
				{
					fight.recordTickLost(tick % 61 >= 59);
					cooldown = 0;
					continue;
				}
				if (cooldown > 0)
				{
					fight.recordTickSpent();
					cooldown--;
					continue;
				}
				attacks++;
				if (hammers != null && attacks <= hammers.length)
				{
					hammer(hammers[attacks - 1], now);
					cooldown = 5;
					continue;
				}
				final boolean special = hammers == null && attacks % 15 == 0;
				fight.recordAttackMade(false, 1.0, false);
				line.describeLatest(special ? "Burning claws" : "Abyssal tentacle", special ? ItemID.BONE_CLAWS
					: ItemID.ABYSSAL_TENTACLE, null, FightTimeline.Style.MELEE, tick);
				if (special)
				{
					line.markLatestSpecial();
				}
				// Vardorvis at full HP blocks about half of a tentacle's swings (08-boss-stats.md: 54% at full HP).
				final int maxHit = special ? 48 : TENTACLE_MAX;
				final double chance = special ? 0.85 : accuracy;
				fight.recordExpected(maxHit, chance, chance * maxHit / 2.0, maxHit * maxHit / 12.0);
				fight.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
				if (!special && accuracy > TENTACLE_FULL)
				{
					// What the drain added to this swing's expectation.
					drainValue += (accuracy - TENTACLE_FULL) * maxHit / 2.0;
				}
				if (rng.nextDouble() < chance)
				{
					final int hit = rng.nextInt(maxHit + 1);
					fight.recordDamageDealt(Math.min(hit, hp), now, true);
					line.attributeSplat(Integer.MIN_VALUE);
					hp -= Math.min(hit, hp);
				}
				cooldown = 3;
			}
		}

		/** A Dragon warhammer special: it lands as the plan says, and a landed one takes 30% of Defence. */
		private void hammer(boolean lands, long now)
		{
			fight.recordAttackMade(false, 1.0, false);
			line.describeLatest("Dragon warhammer", ItemID.DRAGON_WARHAMMER, null, FightTimeline.Style.MELEE, tick);
			line.markLatestSpecial();
			fight.recordExpected(HAMMER_MAX, HAMMER_ACCURACY, HAMMER_ACCURACY * HAMMER_MAX / 2.0,
				HAMMER_MAX * HAMMER_MAX / 12.0);
			fight.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
			if (lands)
			{
				final int hit = 18 + rng.nextInt(HAMMER_MAX - 17);
				fight.recordDamageDealt(Math.min(hit, hp), now, true);
				line.attributeSplat(Integer.MIN_VALUE);
				hp -= Math.min(hit, hp);
				specsLanded++;
				accuracy = TENTACLE_DRAINED;
			}
		}

		private void damage(int amount, boolean thrall)
		{
			final int dealt = Math.min(amount, hp);
			if (thrall)
			{
				fight.recordThrallDamage(dealt);
			}
			hp -= dealt;
		}

		long now()
		{
			return START + tick * 600L;
		}

		void end()
		{
			if (hammers != null)
			{
				final DrainRecord r = new DrainRecord();
				r.defenceBase = Graardor.DEFENCE;
				r.defenceStart = Graardor.DEFENCE;
				r.defenceEnd = defenceNow();
				r.magicBase = Graardor.MAGIC;
				r.magicStart = Graardor.MAGIC;
				r.magicEnd = Graardor.MAGIC;
				r.magicDefenceBase = -1;
				r.magicDefenceStart = -1;
				r.magicDefenceEnd = -1;
				r.specs.put("DRAGON_WARHAMMER", new int[]{hammers.length, specsLanded});
				fight.recordDrain(r);
				// The first hammer missed, so the drain came one swing late: worse than the average outcome by about
				// what the drain adds over two swings. That share of Luck is the specs' (T-050).
				final double shift = 2 * (TENTACLE_DRAINED - TENTACLE_FULL) * TENTACLE_MAX / 2.0;
				fight.recordSpecOutcomes(shift, 0, drainValue, drainValue + shift);
			}
			fight.end(true, now());
			room.end(now());
		}

		SidePanelModel.Input input(SidePanelWeaponRows rows)
		{
			final SidePanelModel.Input in = new SidePanelModel.Input();
			in.zone = SidePanelExamples.ZONE;
			in.kind = SidePanelModel.Kind.ROOM;
			SidePanelModel.fillRoom(in, room);
			in.view = FightTimeline.room(room, store);
			in.weaponRows = rows.rows(room, in.view);
			in.chartFrom = 0;
			return in;
		}
	}

	// ---- Files ----

	private static void png(Path out, String name, BufferedImage img) throws IOException
	{
		final Path file = out.resolve(name + ".png");
		try (OutputStream o = Files.newOutputStream(file))
		{
			ImageIO.write(img, "png", o);
		}
		System.out.println(file + " " + img.getWidth() + "x" + img.getHeight() + " " + Files.size(file) / 1024 + " KB");
	}

	/**
	 * An animated GIF with Java's own writer, looping. After the first frame each one carries only the rectangle that
	 * changed, over what was there, which keeps a mostly still panel small.
	 */
	static void gif(Path file, List<Frame> frames) throws IOException
	{
		final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
		final ImageWriter writer = writers.next();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(Files.newOutputStream(file)))
		{
			writer.setOutput(ios);
			writer.prepareWriteSequence(null);
			BufferedImage previous = null;
			for (int i = 0; i < frames.size(); i++)
			{
				final BufferedImage full = frames.get(i).image;
				int x0 = 0;
				int y0 = 0;
				int x1 = full.getWidth();
				int y1 = full.getHeight();
				if (previous != null)
				{
					int[] box = changed(previous, full);
					if (box == null)
					{
						// Nothing changed: one pixel, to keep the timing.
						box = new int[]{0, 0, 1, 1};
					}
					x0 = box[0];
					y0 = box[1];
					x1 = box[2];
					y1 = box[3];
				}
				final BufferedImage part = full.getSubimage(x0, y0, x1 - x0, y1 - y0);
				final BufferedImage indexed = indexed(part);
				final ImageWriteParam param = writer.getDefaultWriteParam();
				final IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(indexed),
					param);
				frameMetadata(meta, frames.get(i).millis, x0, y0, i == 0,
					(java.awt.image.IndexColorModel) indexed.getColorModel());
				writer.writeToSequence(new IIOImage(indexed, null, meta), param);
				previous = full;
			}
			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
		System.out.println(file + " " + frames.size() + " frames " + Files.size(file) / 1024 + " KB");
	}

	/** The smallest rectangle where two same-sized images differ, as {x0, y0, x1, y1}, or null when they don't. */
	static int[] changed(BufferedImage a, BufferedImage b)
	{
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int x1 = -1;
		int y1 = -1;
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x + 1);
					y1 = Math.max(y1, y + 1);
				}
			}
		}
		return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
	}

	/** The image in at most 256 colours: exact when it has that few, as the panel's flat colours do. */
	static BufferedImage indexed(BufferedImage src)
	{
		final int w = src.getWidth();
		final int h = src.getHeight();
		final int[] rgb = src.getRGB(0, 0, w, h, null, 0, w);
		final java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
		for (int p : rgb)
		{
			counts.merge(p & 0xFFFFFF, 1, Integer::sum);
		}
		// Up to 256 colours: each its own entry. More (the luck curve's shading after a kill): the 256 most used, and
		// every other colour drawn as its nearest. The writer's own palette (T-048) turned the panel's greys teal.
		final List<Integer> colours = new ArrayList<>(counts.keySet());
		colours.sort((a, b) -> counts.get(b) - counts.get(a) != 0 ? counts.get(b) - counts.get(a) : a - b);
		final int n = Math.max(2, Math.min(256, colours.size()));
		final byte[] r = new byte[n];
		final byte[] g = new byte[n];
		final byte[] b = new byte[n];
		final java.util.Map<Integer, Integer> index = new java.util.HashMap<>();
		for (int i = 0; i < Math.min(n, colours.size()); i++)
		{
			final int c = colours.get(i);
			r[i] = (byte) (c >> 16);
			g[i] = (byte) (c >> 8);
			b[i] = (byte) c;
			index.put(c, i);
		}
		final java.awt.image.IndexColorModel cm = new java.awt.image.IndexColorModel(8, n, r, g, b);
		final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
		final java.awt.image.WritableRaster raster = out.getRaster();
		for (int i = 0; i < rgb.length; i++)
		{
			final int c = rgb[i] & 0xFFFFFF;
			raster.setSample(i % w, i / w, 0, index.computeIfAbsent(c, k -> nearest(k, r, g, b)));
		}
		return out;
	}

	/** The palette entry closest to a colour, by squared distance in RGB. */
	private static int nearest(int c, byte[] r, byte[] g, byte[] b)
	{
		int best = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < r.length; i++)
		{
			final int dr = ((c >> 16) & 0xFF) - (r[i] & 0xFF);
			final int dg = ((c >> 8) & 0xFF) - (g[i] & 0xFF);
			final int db = (c & 0xFF) - (b[i] & 0xFF);
			final int d = dr * dr + dg * dg + db * db;
			if (d < bestDistance)
			{
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}

	/**
	 * Delay, position, "draw over what was there", the frame's own colour table (the writer would otherwise give every
	 * frame one default table), and on the first frame the loop-forever extension.
	 */
	private static void frameMetadata(IIOMetadata meta, int millis, int x, int y, boolean first,
		java.awt.image.IndexColorModel colours) throws IOException
	{
		final String format = meta.getNativeMetadataFormatName();
		final IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
		// A GIF table holds a power of two entries, 2 to 256.
		int size = 2;
		while (size < colours.getMapSize())
		{
			size *= 2;
		}
		final IIOMetadataNode table = child(root, "LocalColorTable");
		while (table.getLength() > 0)
		{
			table.removeChild(table.getFirstChild());
		}
		table.setAttribute("sizeOfLocalColorTable", String.valueOf(size));
		table.setAttribute("sortFlag", "FALSE");
		for (int i = 0; i < size; i++)
		{
			final IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
			final int c = i < colours.getMapSize() ? colours.getRGB(i) : 0;
			entry.setAttribute("index", String.valueOf(i));
			entry.setAttribute("red", String.valueOf((c >> 16) & 0xFF));
			entry.setAttribute("green", String.valueOf((c >> 8) & 0xFF));
			entry.setAttribute("blue", String.valueOf(c & 0xFF));
			table.appendChild(entry);
		}
		final IIOMetadataNode descriptor = child(root, "ImageDescriptor");
		descriptor.setAttribute("imageLeftPosition", String.valueOf(x));
		descriptor.setAttribute("imageTopPosition", String.valueOf(y));
		final IIOMetadataNode control = child(root, "GraphicControlExtension");
		control.setAttribute("disposalMethod", "doNotDispose");
		control.setAttribute("userInputFlag", "FALSE");
		control.setAttribute("transparentColorFlag", "FALSE");
		control.setAttribute("delayTime", String.valueOf(Math.max(2, millis / 10)));
		control.setAttribute("transparentColorIndex", "0");
		if (first)
		{
			final IIOMetadataNode apps = child(root, "ApplicationExtensions");
			final IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
			app.setAttribute("applicationID", "NETSCAPE");
			app.setAttribute("authenticationCode", "2.0");
			app.setUserObject(new byte[]{1, 0, 0});
			apps.appendChild(app);
		}
		meta.setFromTree(format, root);
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name)
	{
		for (int i = 0; i < root.getLength(); i++)
		{
			if (root.item(i).getNodeName().equalsIgnoreCase(name))
			{
				return (IIOMetadataNode) root.item(i);
			}
		}
		final IIOMetadataNode node = new IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}
}

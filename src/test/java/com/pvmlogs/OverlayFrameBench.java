package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;

/**
 * T-047's offline measure: an overlay's frame, as RuneLite drew it before and
 * as {@link OverlayCache} draws it, in a frame loop like the game's (95 frames
 * a second, a tick every 57 frames, a fight's clock a second every 95), onto a
 * game-sized RGB image. Each frame does what the overlay's {@code render} does
 * after its visibility checks: the lines from {@code OncePerKey}, rebuilt on a
 * new tick, then the box. Reports {@link PerfProbe.Timer} p50, p99 and max
 * after a warm-up. Not a test: run its main.
 */
public class OverlayFrameBench
{
	static final int WARM_UP = 5_000;
	static final int FRAMES = 40_000;

	/** An overlay panel as ours are. */
	private static final class Panel extends OverlayPanel
	{
		PvmPerformanceOverlay.Lines lines;

		Dimension paint(Graphics2D g)
		{
			panelComponent.getChildren().clear();
			panelComponent.getChildren().addAll(lines.components);
			panelComponent.setPreferredSize(new Dimension(lines.width, 0));
			return super.render(g);
		}
	}

	public static void main(String[] args)
	{
		final BufferedImage game = new BufferedImage(765, 503, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = game.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(FontManager.getRunescapeFont());

		// Mid-fight: the clock moves a second every 95 frames. After the kill: nothing moves.
		final IntFunction<List<LedgerRows.Row>> fighting = frame -> {
			final LedgerExamples.FakeScope s = LedgerExamples.midFight();
			s.damageVariance = 6000;
			s.combatTicks = 122 + frame / 95 * 5 / 3;
			return LedgerRows.build(s, LedgerRows.State.fight("Vardorvis", false, false, false), new LedgerRows.Settings());
		};
		final List<LedgerRows.Row> dead = LedgerPictures.states().get("3-dead");
		final IntFunction<List<LedgerRows.Row>> killed = frame -> dead;
		final List<LedgerRows.Row> loadout = LedgerRows.loadout(LedgerExamples.loadout(), false);
		final IntFunction<List<LedgerRows.Row>> loadoutRows = frame -> loadout;

		System.out.println("| Box | Overlay colour | Before p50 | p99 | max | After p50 | p99 | max |");
		System.out.println("|---|---|---|---|---|---|---|---|");
		for (Object[] box : new Object[][]{{"fight overlay, mid-fight", fighting}, {"fight overlay, after the kill", killed},
			{"loadout overlay", loadoutRows}})
		{
			for (Object[] colour : new Object[][]{{"see-through (RuneLite's)", null},
				{"opaque", new Color(70, 61, 50)}})
			{
				@SuppressWarnings("unchecked")
				final IntFunction<List<LedgerRows.Row>> rows = (IntFunction<List<LedgerRows.Row>>) box[1];
				final PerfProbe.Timer before = run(g, rows, (Color) colour[1], false);
				final PerfProbe.Timer after = run(g, rows, (Color) colour[1], true);
				System.out.printf("| %s | %s | %s | %s | %s | %s | %s | %s |%n", box[0], colour[0], ms(before.quantile(0.5)),
					ms(before.quantile(0.99)), ms(before.max()), ms(after.quantile(0.5)), ms(after.quantile(0.99)),
					ms(after.max()));
			}
		}
	}

	private static PerfProbe.Timer run(Graphics2D g, IntFunction<List<LedgerRows.Row>> rows, Color colour, boolean cached)
	{
		final Panel panel = new Panel();
		panel.setPreferredColor(colour);
		final OverlayCache cache = new OverlayCache();
		final PvmPerformanceOverlay.OncePerKey<PvmPerformanceOverlay.Lines> lines = new PvmPerformanceOverlay.OncePerKey<>();
		final PerfProbe.Timer timer = new PerfProbe.Timer(FRAMES);
		for (int frame = 0; frame < WARM_UP + FRAMES; frame++)
		{
			final int f = frame;
			final long t0 = System.nanoTime();
			// A new tick every 57 frames builds the lines again, as the overlay's OncePerKey does.
			final PvmPerformanceOverlay.Lines built = lines.get(Arrays.asList(frame / 57),
				() -> PvmPerformanceOverlay.lines(g, rows.apply(f), null));
			panel.lines = built;
			if (cached)
			{
				cache.draw(g, built.look(null), PvmPerformanceOverlay.background(panel), panel::paint);
			}
			else
			{
				panel.paint(g);
			}
			final long took = System.nanoTime() - t0;
			if (frame >= WARM_UP)
			{
				timer.add(took);
			}
		}
		return timer;
	}

	private static String ms(long nanos)
	{
		return String.format("%.3f ms", nanos / 1e6);
	}
}

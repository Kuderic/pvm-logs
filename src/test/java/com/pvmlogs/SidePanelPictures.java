package com.pvmlogs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import net.runelite.client.ui.laf.RuneLiteLAF;

/**
 * Not a test: draws every Fight-tab state (spec section 6, states 1 to 8) with
 * the real panel classes, RuneLite's own look and feel and fonts, at 1× and 2×,
 * for the PR (T-033 item 7). Like the overlay's {@code LedgerPictures} and
 * {@code Render.java}, but the panel is Swing, so it paints itself.
 * <pre>
 *   java -Djava.awt.headless=true -cp &lt;test classpath&gt; com.pvmlogs.SidePanelPictures &lt;out dir&gt;
 * </pre>
 * The sidebar is drawn 242 px wide: the tab bar across it, the Fight tab's
 * content in its 225 px, and RuneLite's 17 px scrollbar track beside it.
 */
public final class SidePanelPictures
{
	static final int SIDEBAR = 242;
	static final int SCROLLBAR = 17;

	private SidePanelPictures()
	{
	}

	public static void main(String[] args) throws IOException
	{
		final Path out = Paths.get(args.length > 0 ? args[0] : "build/side-panel-renders");
		Files.createDirectories(out);
		RuneLiteLAF.setup();
		for (Map.Entry<String, SidePanelModel.Input> e : SidePanelExamples.states().entrySet())
		{
			for (int scale = 1; scale <= 2; scale++)
			{
				final BufferedImage img = render(e.getValue(), scale);
				final Path file = out.resolve(e.getKey() + "@" + scale + "x.png");
				try (OutputStream o = Files.newOutputStream(file))
				{
					ImageIO.write(img, "png", o);
				}
				System.out.println(file + " " + img.getWidth() + "x" + img.getHeight());
			}
		}
	}

	/** The panel showing this input's snapshot, drawn at {@code scale}. */
	static BufferedImage render(SidePanelModel.Input in, int scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		final JComponent tabs = panel.tabs();
		final JComponent content = panel.fightTab();

		tabs.setSize(SIDEBAR, tabs.getPreferredSize().height);
		layout(tabs);
		final int contentWidth = SIDEBAR - SCROLLBAR;
		content.setSize(contentWidth, 10_000);
		layout(content);
		final int contentHeight = content.getPreferredSize().height;
		content.setSize(contentWidth, contentHeight);
		layout(content);

		final int w = SIDEBAR;
		final int h = tabs.getHeight() + contentHeight;
		final BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, w, h);
		tabs.printAll(g);
		final Graphics2D cg = (Graphics2D) g.create(0, tabs.getHeight(), contentWidth, contentHeight);
		content.printAll(cg);
		cg.dispose();
		// RuneLite's scrollbar: its track, and a thumb as long as one screen of a 900 px sidebar would be.
		g.setColor(new Color(0x191919));
		g.fillRect(contentWidth, tabs.getHeight(), SCROLLBAR, contentHeight);
		g.setColor(SidePanelStyle.SELECTED);
		g.fillRect(contentWidth + 3, tabs.getHeight() + 2, SCROLLBAR - 6,
			Math.max(40, Math.min(contentHeight - 4, 900 * contentHeight / Math.max(900, contentHeight) - 4)));
		g.dispose();
		return img;
	}

	/** Lays out a tree of components that isn't on screen. */
	static void layout(Component c)
	{
		if (c instanceof Container)
		{
			final Container k = (Container) c;
			k.doLayout();
			for (Component child : k.getComponents())
			{
				layout(child);
			}
		}
	}

	static Dimension size(JComponent c)
	{
		return c.getPreferredSize();
	}
}

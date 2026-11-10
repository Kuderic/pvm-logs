package com.pvmlogs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * What a Plugin Hub plugin may not call, checked against {@code src/main/java} on every build.
 *
 * <p>Source: the RuneLite wiki's <a href="https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features">
 * Rejected or Rolled Back Features</a> page, section "Forbidden language features" ("Forbidden Java functions &amp;
 * classes", "Forbidden patterns") and "Restricted APIs". This list is from the page's 2026-10-17 revision, read on
 * 2026-10-26. The page says violations "will prevent us from being able to fully review" a plugin, and that older
 * plugins being grandfathered guarantees nothing, so an update or takeover is reviewed against today's rules. When the
 * page changes, change {@link #RULES}.
 *
 * <p>The packager's own list of rejected APIs ({@code runelite/plugin-hub-tooling}, {@code disallowed-apis.txt}) is
 * here too, read 2026-10-28 (T-067): with it the hub build fails, not just the review.
 *
 * <p>One rule is ours, not the wiki's: file access goes only through {@code net.runelite.client.util.Filepath}, so
 * {@code java.nio.file.Files} and {@code Paths} are out too. Filepath is what keeps a plugin inside its own folder, and
 * going round it with NIO defeats the point of the {@code java.io} rule.
 *
 * <p>Comments and string literals are blanked before matching, so prose and log text can name any of these.
 */
public class ForbiddenApiTest
{
	/** Rule (what to use instead) to the pattern that finds it in code. */
	static final Map<String, Pattern> RULES = new LinkedHashMap<>();

	static
	{
		rule("java.io file APIs (use net.runelite.client.util.Filepath)",
			"\\b(?:java\\.io\\.)?(?:File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile"
				+ "|FileDescriptor)\\b");
		rule("java.nio file access outside Filepath (this project's rule)",
			"\\bjava\\.nio\\.file\\.(?:Files|Paths)\\b|\\bFiles\\.\\w|\\bPaths\\.\\w|\\bPath\\.of\\s*\\(");
		rule("Filepath.Unchecked (restricted API)", "\\bFilepath\\.Unchecked\\b");
		rule("Thread.sleep or Thread.interrupt", "\\bThread\\.sleep\\b|\\.interrupt\\s*\\(");
		rule("Desktop.open / Desktop.browse / LinkBrowser.open (LinkBrowser.browse is allowed)",
			"\\bjava\\.awt\\.Desktop\\b|\\bDesktop\\.getDesktop\\b|\\bLinkBrowser\\.open\\b");
		rule("Client.menuAction (one collection-log exception, needs review)", "\\.menuAction\\s*\\(");
		rule("java.lang.Runtime", "\\bRuntime\\b");
		rule("java.awt.KeyboardFocusManager", "\\bKeyboardFocusManager\\b");
		rule("a network client other than OkHttp",
			"\\bHttpURLConnection\\b|\\bURLConnection\\b|\\bjava\\.net\\.http\\b|\\bHttpClient\\b"
				+ "|\\.openConnection\\s*\\(|\\.openStream\\s*\\(");
		rule("an own Gson instance (inject the client's; customise with newBuilder())",
			"\\bnew\\s+Gson(?:Builder)?\\s*\\(");
		rule("an own OkHttpClient (inject the client's)", "\\bnew\\s+OkHttpClient\\b");
		rule("Java reflection",
			"\\bjava\\.lang\\.reflect\\b|\\.setAccessible\\s*\\(|\\bClass\\.forName\\s*\\("
				+ "|\\.getDeclared(?:Field|Method|Constructor)s?\\s*\\(");
		rule("JNI or JNA", "\\bnative\\b|\\bSystem\\.load(?:Library)?\\s*\\(|\\bcom\\.sun\\.jna\\b");
		rule("running external programs", "\\bProcessBuilder\\b|\\.exec\\s*\\(");
		rule("net.runelite as the package", "^\\s*package\\s+net\\.runelite\\b");
		// The packager's own list (plugin-hub-tooling's disallowed-apis.txt, read 2026-10-28 for T-067): it fails the
		// build on these. Gson and OkHttpClient constructors are covered above.
		rule("WidgetInfo or WidgetID (use InterfaceID / ComponentID)", "\\bWidgetInfo\\b|\\bWidgetID\\b");
		rule("ChatMessageManager.update (a no-op)", "\\bchatMessageManager\\.update\\s*\\(");
		rule("ItemManager.getItemStats(int, boolean) (use getItemStats(int))",
			"\\bgetItemStats\\s*\\([^,()]*,");
		rule("the RuneLite account session", "\\bAccountSession\\b|\\bSessionManager\\b|\\bAccountClient\\b");
	}

	private static void rule(String name, String regex)
	{
		RULES.put(name, Pattern.compile(regex, Pattern.MULTILINE));
	}

	@Test
	public void pluginCodeUsesNothingTheHubForbids() throws IOException
	{
		final List<String> found = new ArrayList<>();
		final List<Path> sources;
		try (Stream<Path> walk = Files.walk(Paths.get("src/main/java")))
		{
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		assertTrue("no sources found; run from the repo root", !sources.isEmpty());
		for (Path file : sources)
		{
			final String original = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			found.addAll(violations(file.toString(), original));
		}
		assertTrue("Forbidden for Plugin Hub plugins (see ForbiddenApiTest's Javadoc for the wiki page):\n"
			+ String.join("\n", found), found.isEmpty());
	}

	/** The rules themselves: each catches what it should and lets the allowed look-alikes through. */
	@Test
	public void theRulesCatchWhatTheyShouldAndNothingElse()
	{
		final String[] forbidden = {
			"import java.io.File;",
			"new File(dir, name)",
			"new FileOutputStream(f)",
			"java.nio.file.Files.write(p, b);",
			"Files.createDirectories(dir);",
			"Path.of(\"x\")",
			"Filepath.Unchecked.getRooted(p)",
			"Thread.sleep(10);",
			"Thread.currentThread().interrupt();",
			"java.awt.Desktop.getDesktop().browse(uri);",
			"LinkBrowser.open(url);",
			"client.menuAction(-1, 0, null, 1, -1, \"x\", null);",
			"Runtime.getRuntime().exec(cmd);",
			"KeyboardFocusManager.getCurrentKeyboardFocusManager()",
			"(HttpURLConnection) url.openConnection()",
			"java.net.http.HttpClient.newHttpClient()",
			"new Gson()",
			"new GsonBuilder().create()",
			"new OkHttpClient.Builder().build()",
			"Class.forName(\"x\")",
			"field.setAccessible(true);",
			"private native int jni();",
			"System.loadLibrary(\"x\");",
			"new ProcessBuilder(\"x\")",
			"package net.runelite.client.plugins.x;",
			"client.getWidget(WidgetInfo.INVENTORY)",
			"chatMessageManager.update(node);",
			"itemManager.getItemStats(id, false)",
			"@Inject private SessionManager sessionManager;",
		};
		for (String line : forbidden)
		{
			assertTrue("should be caught: " + line, !violations("sample", line).isEmpty());
		}

		final String[] allowed = {
			"catch (IOException | RuntimeException e)",
			"final Filepath file = dir.joinSegment(\"history.json\");",
			"import java.nio.file.StandardOpenOption;",
			"@Inject private OkHttpClient httpClient;",
			"gson.newBuilder().create()",
			"LinkBrowser.browse(url);",
			"// Thread.sleep would block the client thread, and new File(...) is forbidden",
			"log.warn(\"could not write java.io.File {}\", name);",
			"/* Runtime.getRuntime() is out */ int x = 1;",
			"executor.execute(this::flush);",
			"final ItemStats stats = itemManager.getItemStats(dart.getItemId());",
			"char quote = '\"'; String s = \"Desktop.getDesktop()\";",
		};
		for (String line : allowed)
		{
			assertEquals("should be allowed: " + line, new ArrayList<String>(), violations("sample", line));
		}
	}

	/** "file:line: rule: code" for each rule each line breaks. */
	static List<String> violations(String name, String source)
	{
		final String code = blankCommentsAndStrings(source);
		final String[] originalLines = source.split("\n", -1);
		final List<String> out = new ArrayList<>();
		for (Map.Entry<String, Pattern> rule : RULES.entrySet())
		{
			final Matcher m = rule.getValue().matcher(code);
			while (m.find())
			{
				final int line = lineOf(code, m.start());
				out.add(name + ":" + line + ": " + rule.getKey() + ": " + originalLines[line - 1].trim());
			}
		}
		return out;
	}

	private static int lineOf(String text, int offset)
	{
		int line = 1;
		for (int i = 0; i < offset; i++)
		{
			if (text.charAt(i) == '\n')
			{
				line++;
			}
		}
		return line;
	}

	/**
	 * The source with every comment and string or char literal replaced by spaces, newlines kept, so offsets and
	 * line numbers still match the original.
	 */
	static String blankCommentsAndStrings(String src)
	{
		final StringBuilder out = new StringBuilder(src.length());
		int i = 0;
		while (i < src.length())
		{
			final char c = src.charAt(i);
			final char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
			if (c == '/' && next == '/')
			{
				while (i < src.length() && src.charAt(i) != '\n')
				{
					out.append(' ');
					i++;
				}
			}
			else if (c == '/' && next == '*')
			{
				out.append("  ");
				i += 2;
				while (i < src.length() && !(src.charAt(i) == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/'))
				{
					out.append(src.charAt(i) == '\n' ? '\n' : ' ');
					i++;
				}
				if (i < src.length())
				{
					out.append("  ");
					i += 2;
				}
			}
			else if (c == '"' || c == '\'')
			{
				out.append(c);
				i++;
				while (i < src.length() && src.charAt(i) != c && src.charAt(i) != '\n')
				{
					if (src.charAt(i) == '\\' && i + 1 < src.length())
					{
						out.append("  ");
						i += 2;
						continue;
					}
					out.append(' ');
					i++;
				}
				if (i < src.length() && src.charAt(i) == c)
				{
					out.append(c);
					i++;
				}
			}
			else
			{
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}
}

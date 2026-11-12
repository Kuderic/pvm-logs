package com.pvmlogs;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import net.runelite.client.util.Filepath;

/**
 * T-178: {@code history.json}, saved so a crash at any moment leaves a whole file, and loaded so a file that doesn't read
 * is never saved over.
 * <p>
 * Eric's history went from 2,500 fights to 0 overnight on 2026-11-05: his machine crashed while the history was being
 * written in place, the empty file loaded as 0 fights (silently: the client log's warning isn't in his debug log), and
 * the next fight's save wrote over it.
 * <ul>
 * <li><b>Save:</b> the new history is written beside the file ({@code history.json.tmp}), forced to the disk
 * ({@link FileChannel#force}: the data and its length, so a power cut can't keep the rename and lose the bytes), and
 * only then moved over {@code history.json} in one step ({@link StandardCopyOption#ATOMIC_MOVE}). A stop at any moment
 * leaves the old file or the new one, whole.</li>
 * <li><b>The last good copy:</b> a load that read fights keeps one copy, {@code history-last-good.json}, written the same
 * way and replaced only by a later good load.</li>
 * <li><b>A file that doesn't read</b> (empty, cut short, unparsable) is moved aside as
 * {@code history-unreadable-<stamp>.json}, untouched, and the newest whole copy (the last good one, or a whole
 * {@code .tmp}) is loaded instead.</li>
 * </ul>
 * Everything goes through {@link Filepath} (the Plugin Hub rule), which offers the channel, its force and the atomic move.
 * <p>
 * T-217: the save streams the JSON to the {@code .tmp} through Gson's writer on a buffered stream. It used to build the
 * whole file as one string and then its bytes ({@code gson.toJson(fights).getBytes(...)}): for 2,500 fights, 6.7 MB of
 * JSON as about 13 MB of chars and 6.7 MB of bytes, made and dropped at every kill. The bytes written are the same.
 */
class HistoryStore
{
	static final String FILE = "history.json";
	static final String TEMP = "history.json.tmp";
	static final String LAST_GOOD = "history-last-good.json";
	static final String LAST_GOOD_TEMP = "history-last-good.json.tmp";
	static final String UNREADABLE_PREFIX = "history-unreadable-";

	/** What a load found: the fights, where they came from, and the line saying so. */
	static final class Loaded
	{
		final Fight[] fights;
		/** "history.json", "history-last-good.json", "history.json.tmp", or null when nothing was loaded. */
		final String source;
		/** The debug log's HISTORY line. */
		final String line;
		/** For chat, once, when a fallback was taken; null otherwise. */
		final String notice;

		Loaded(Fight[] fights, String source, String line, String notice)
		{
			this.fights = fights;
			this.source = source;
			this.line = line;
			this.notice = notice;
		}
	}

	private final Filepath dir;
	private final Gson gson;
	// An unreadable history.json that couldn't be moved or copied aside: never saved over.
	private boolean blocked;

	HistoryStore(Filepath dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	/**
	 * Writes the history so a stop at any moment leaves the old file or the new one whole. Returns the bytes written (the
	 * window after a kill shows them, T-217).
	 */
	long save(List<Fight> fights) throws IOException
	{
		if (blocked)
		{
			throw new IOException(FILE + " didn't read and couldn't be moved aside, so it isn't saved over");
		}
		dir.createDirectories();
		return writeWhole(dir.joinSegment(TEMP), dir.joinSegment(FILE), out -> json(gson, fights, out));
	}

	/**
	 * T-217: the fights as {@code gson.toJson(fights)} writes them, streamed in UTF-8: the same type it serialises them as
	 * ({@code fights.getClass()}) and the writer it makes ({@link Gson#toJson(Object, java.lang.reflect.Type, Appendable)}
	 * through {@code newJsonWriter}), so the bytes match the old save's.
	 */
	static void json(Gson gson, List<Fight> fights, OutputStream out) throws IOException
	{
		// Buffered in chars too: OutputStreamWriter copies every string it's handed into a new array, and Gson's writer hands
		// it one a token (326 MB allocated for Eric's 6.7 MB without this, against 84 MB for the old whole-string save).
		final Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 14);
		try
		{
			gson.toJson(fights, fights.getClass(), writer);
		}
		catch (JsonIOException e)
		{
			throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e);
		}
		writer.flush();
	}

	/** What a save writes to its stream. */
	interface Body
	{
		void writeTo(OutputStream out) throws IOException;
	}

	/** {@link #writeWhole(Filepath, Filepath, Body)} for bytes already in hand (the last good copy, from a load). */
	static long writeWhole(Filepath temp, Filepath target, byte[] bytes) throws IOException
	{
		return writeWhole(temp, target, out -> out.write(bytes));
	}

	/**
	 * What {@code body} writes, streamed to {@code temp}, forced to the disk, then moved over {@code target} in one step.
	 * Where the file system can't move atomically, a replacing move: still only after the bytes are on the disk. A body
	 * that throws leaves {@code target} as it was. Returns the bytes written.
	 */
	static long writeWhole(Filepath temp, Filepath target, Body body) throws IOException
	{
		final long written;
		try (FileChannel channel = temp.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE))
		{
			// Not closed: closing the stream closes the channel, which the try closes after its force.
			final Counting out = new Counting(new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16));
			body.writeTo(out);
			out.flush();
			channel.force(true);
			written = out.count;
		}
		try
		{
			temp.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
		}
		return written;
	}

	/** Counts what goes through it. */
	private static final class Counting extends FilterOutputStream
	{
		long count;

		Counting(OutputStream out)
		{
			super(out);
		}

		@Override
		public void write(int b) throws IOException
		{
			out.write(b);
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException
		{
			out.write(b, off, len);
			count += len;
		}
	}

	/** Reads the history, falling back to the newest whole copy when {@code history.json} doesn't read. */
	Loaded load(long nowMillis)
	{
		final Filepath file = dir.joinSegment(FILE);
		final Filepath temp = dir.joinSegment(TEMP);
		final Filepath lastGood = dir.joinSegment(LAST_GOOD);
		String why;
		if (file.exists())
		{
			final long bytes = sizeOf(file);
			final Read read = read(file);
			if (read.fights != null)
			{
				if (read.fights.length > 0)
				{
					keepLastGood(read.bytes);
				}
				return new Loaded(read.fights, FILE, String.format("HISTORY loaded %d fights (%d bytes) from %s",
					read.fights.length, bytes, FILE), null);
			}
			// It didn't read: aside, untouched, and never saved over.
			final String aside = UNREADABLE_PREFIX + stamp(nowMillis) + ".json";
			try
			{
				file.moveTo(dir.joinSegment(aside));
				why = String.format("%s (%d bytes) didn't read (%s), moved aside as %s", FILE, bytes, read.why, aside);
			}
			catch (IOException e)
			{
				try
				{
					file.copyTo(dir.joinSegment(aside));
					file.delete();
					why = String.format("%s (%d bytes) didn't read (%s), copied aside as %s", FILE, bytes, read.why, aside);
				}
				catch (IOException e2)
				{
					// Left where it is, and never written over this session.
					blocked = !dir.joinSegment(aside).exists() || file.exists();
					why = String.format("%s (%d bytes) didn't read (%s), and couldn't be moved aside (%s)%s", FILE, bytes,
						read.why, e.getMessage(), blocked ? ": history isn't saved this session" : "");
				}
			}
		}
		else if (temp.exists() || lastGood.exists())
		{
			why = FILE + " is missing";
		}
		else
		{
			return new Loaded(new Fight[0], null, "HISTORY none yet: no " + FILE, null);
		}
		// The newest whole copy: a .tmp written all the way (its save stopped before its move), or the last good one.
		final Read fromTemp = temp.exists() ? read(temp) : Read.NONE;
		final Read fromLastGood = lastGood.exists() ? read(lastGood) : Read.NONE;
		final boolean tempWins = fromTemp.fights != null
			&& (fromLastGood.fights == null || modified(temp) >= modified(lastGood));
		final Read chosen = tempWins ? fromTemp : fromLastGood;
		final Filepath chosenFile = tempWins ? temp : lastGood;
		if (chosen.fights == null)
		{
			return new Loaded(new Fight[0], null, String.format("HISTORY %s; no whole copy to load (%s: %s, %s: %s), so it "
					+ "starts empty", why, TEMP, fromTemp.why, LAST_GOOD, fromLastGood.why),
				PvmPerformanceConfig.DISPLAY_NAME + " couldn't read its history, and found no copy to load. The file is "
					+ "kept aside in its folder.");
		}
		final String name = tempWins ? TEMP : LAST_GOOD;
		final String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(modified(chosenFile)));
		return new Loaded(chosen.fights, name, String.format("HISTORY %s; loaded %d fights (%d bytes) from %s, saved %s",
				why, chosen.fights.length, sizeOf(chosenFile), name, date),
			PvmPerformanceConfig.DISPLAY_NAME + " couldn't read its history; it loaded the last good copy from " + date
				+ ".");
	}

	private void keepLastGood(byte[] bytes)
	{
		try
		{
			writeWhole(dir.joinSegment(LAST_GOOD_TEMP), dir.joinSegment(LAST_GOOD), bytes);
		}
		catch (IOException | RuntimeException e)
		{
			// Not having refreshed it costs only its freshness.
		}
	}

	private static final class Read
	{
		static final Read NONE = new Read(null, null, "not there");
		final Fight[] fights;
		final byte[] bytes;
		final String why;

		Read(Fight[] fights, byte[] bytes, String why)
		{
			this.fights = fights;
			this.bytes = bytes;
			this.why = why;
		}
	}

	/** The fights a file holds, or null with why not. An empty array ("[]", a cleared history) reads. */
	private Read read(Filepath file)
	{
		final byte[] bytes;
		try (InputStream in = file.openInputStream())
		{
			bytes = in.readAllBytes();
		}
		catch (IOException e)
		{
			return new Read(null, null, "couldn't be opened: " + e.getMessage());
		}
		final String json = new String(bytes, StandardCharsets.UTF_8);
		if (json.trim().isEmpty() || json.chars().allMatch(c -> c == 0 || Character.isWhitespace(c)))
		{
			return new Read(null, bytes, bytes.length == 0 ? "empty" : "blank");
		}
		try
		{
			final Fight[] fights = gson.fromJson(json, Fight[].class);
			if (fights == null)
			{
				return new Read(null, bytes, "no fights in it");
			}
			if (Arrays.asList(fights).contains(null))
			{
				return new Read(null, bytes, "a fight in it is null");
			}
			return new Read(fights, bytes, null);
		}
		catch (RuntimeException e)
		{
			return new Read(null, bytes, "unparsable: " + e.getClass().getSimpleName());
		}
	}

	private static long sizeOf(Filepath file)
	{
		try
		{
			return file.size();
		}
		catch (IOException e)
		{
			return -1;
		}
	}

	private static long modified(Filepath file)
	{
		try
		{
			return file.getLastModifiedTime().toMillis();
		}
		catch (IOException e)
		{
			return 0;
		}
	}

	/** Whether saving is refused this session (an unreadable file that couldn't be moved aside). */
	boolean blocked()
	{
		return blocked;
	}

	static String stamp(long millis)
	{
		return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(millis));
	}
}

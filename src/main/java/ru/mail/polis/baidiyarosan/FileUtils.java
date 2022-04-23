package ru.mail.polis.baidiyarosan;

import ru.mail.polis.BaseEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.stream.Stream;

public final class FileUtils {

    public static final String INDEX_FOLDER = "indexes";

    public static final String DATA_FILE_HEADER = "data";

    public static final String COMPACTED_DATA_FILE_HEADER = "comp_data";

    public static final String INDEX_FILE_HEADER = "index";

    public static final String FILE_EXTENSION = ".log";

    public static final int NULL_SIZE_FLAG = -1;

    private static final int COMPACTED_FILE_INDEX = 0;

    private FileUtils() {
        // Utility class
    }

    public static List<Path> getPaths(Path path) throws IOException {
        try (Stream<Path> s = Files.list(path)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    public static boolean isCompacted(Path directory, int fileNumber) {
        return Files.exists(getCompactedDataPath(directory, fileNumber));
    }

    public static Path getDataPath(Path directory, int fileNumber) {
        return directory.resolve(DATA_FILE_HEADER + fileNumber + FILE_EXTENSION);
    }

    public static Path getCompactedDataPath(Path directory, int fileNumber) {
        return directory.resolve(COMPACTED_DATA_FILE_HEADER + fileNumber + FILE_EXTENSION);
    }

    public static Path getIndexPath(Path directory, int fileNumber) {
        return directory.resolve(Paths.get(INDEX_FOLDER, INDEX_FILE_HEADER + fileNumber + FILE_EXTENSION));
    }

    public static int sizeOfEntry(BaseEntry<ByteBuffer> entry) {
        return 2 * Integer.BYTES + entry.key().remaining() + (entry.value() == null ? 0 : entry.value().remaining());
    }

    public static ByteBuffer readBuffer(ByteBuffer buffer, int pos) {
        int size = buffer.getInt(pos);
        return buffer.slice(pos + Integer.BYTES, size);
    }

    public static BaseEntry<ByteBuffer> readEntry(ByteBuffer buffer, int pos) {
        int currPos = pos;
        int keySize = buffer.getInt(currPos);
        currPos += Integer.BYTES;
        ByteBuffer key = buffer.slice(currPos, keySize);
        currPos += keySize;
        int valSize = buffer.getInt(currPos);
        if (valSize == NULL_SIZE_FLAG) {
            return new BaseEntry<>(key, null);
        }
        currPos += Integer.BYTES;
        return new BaseEntry<>(key, buffer.slice(currPos, valSize));
    }

    public static int intAt(ByteBuffer indexes, int position) {
        return indexes.getInt(position * Integer.BYTES);
    }

    private static ByteBuffer writeEntryToBuffer(ByteBuffer buffer, BaseEntry<ByteBuffer> entry) {
        buffer.putInt(entry.key().remaining()).put(entry.key());
        if (entry.value() == null) {
            buffer.putInt(NULL_SIZE_FLAG);
        } else {
            buffer.putInt(entry.value().remaining()).put(entry.value());
        }
        return buffer.flip();
    }

    public static void flush(
            NavigableMap<ByteBuffer, BaseEntry<ByteBuffer>> collection, Path path) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{});
        ByteBuffer indexBuffer = ByteBuffer.allocate(Integer.BYTES);
        int fileNumber = getPaths(path).size() + 1;
        try (FileChannel dataOut = FileChannel.open(getDataPath(path, fileNumber),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             FileChannel indexOut = FileChannel.open(getIndexPath(path, fileNumber),
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int size;
            for (BaseEntry<ByteBuffer> entry : collection.values()) {
                size = sizeOfEntry(entry);
                if (buffer.remaining() < size) {
                    buffer = ByteBuffer.allocate(size);
                } else {
                    buffer.clear();
                }
                indexBuffer.clear();
                indexBuffer.putInt((int) dataOut.position());
                indexBuffer.flip();
                indexOut.write(indexBuffer);
                dataOut.write(writeEntryToBuffer(buffer, entry));
            }
        }
    }

    public static void compact(Iterator<? extends BaseEntry<ByteBuffer>> iter, Path path) throws IOException {

        int fileNumber = COMPACTED_FILE_INDEX;
        if (iter.hasNext()) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[]{});
            ByteBuffer indexBuffer = ByteBuffer.allocate(Integer.BYTES);
            try (FileChannel dataOut = FileChannel.open(getCompactedDataPath(path, fileNumber),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 FileChannel indexOut = FileChannel.open(getIndexPath(path, fileNumber),
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                int size;
                while (iter.hasNext()) {
                    BaseEntry<ByteBuffer> entry = iter.next();
                    size = sizeOfEntry(entry);
                    if (buffer.remaining() < size) {
                        buffer = ByteBuffer.allocate(size);
                    } else {
                        buffer.clear();
                    }
                    indexBuffer.clear();
                    indexBuffer.putInt((int) dataOut.position());
                    indexBuffer.flip();
                    indexOut.write(indexBuffer);
                    dataOut.write(writeEntryToBuffer(buffer, entry));
                }
            } catch (Exception e) {
                Files.deleteIfExists(getIndexPath(path, fileNumber));
                Files.deleteIfExists(getCompactedDataPath(path, fileNumber));
                throw new IOException(e);
            }
        }
    }

    public static void clearOldFiles(int fileCount, Path path) throws IOException {
        Path compactedFileIndexPath = getIndexPath(path, COMPACTED_FILE_INDEX);
        Path compactedFileDataPath = getCompactedDataPath(path, COMPACTED_FILE_INDEX);
        if (Files.notExists(compactedFileIndexPath) || Files.notExists(compactedFileDataPath)) {
            return; // no compaction
        }
        int lastFile = fileCount;
        try {
            // try to delete old files from last to first
            for (; lastFile > 0; --lastFile) {
                Files.deleteIfExists(getIndexPath(path, lastFile));
                Files.deleteIfExists(getDataPath(path, lastFile));
                // in case that this file was compacted
                Files.deleteIfExists(getCompactedDataPath(path, lastFile));
            }
        } catch (DirectoryNotEmptyException e) {
            // ???
            throw new IOException("File system corrupted", e);
        } catch (IOException e) {
            // access failed
            throw new UncheckedIOException(e);
        } finally { // happens anyway
            lastFile++; // make step forward
            // making compacted file last
            Files.move(compactedFileIndexPath, getIndexPath(path, lastFile), StandardCopyOption.ATOMIC_MOVE);
            Files.move(compactedFileDataPath, getCompactedDataPath(path, lastFile), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    public static Collection<BaseEntry<ByteBuffer>> getInMemoryCollection(
            NavigableMap<ByteBuffer, BaseEntry<ByteBuffer>> collection, ByteBuffer from, ByteBuffer to) {

        if (collection.isEmpty()) {
            return Collections.emptyList();
        }

        if (from == null && to == null) {
            return collection.values();
        }

        if (from == null) {
            return collection.headMap(to).values();
        }

        if (to == null) {
            return collection.tailMap(from).values();
        }

        return collection.subMap(from, to).values();
    }

    public static Collection<BaseEntry<ByteBuffer>> getInFileCollection(
            ByteBuffer file, ByteBuffer index, ByteBuffer from, ByteBuffer to) {
        final int size = index.remaining() / Integer.BYTES - 1;

        int start = 0;
        int end = size;
        if (from != null) {
            start = getIndex(file, index, from, start, end);
        }

        if (to != null) {
            end = getIndex(file, index, to, start, end);

            // if end index greater than the highest bound, mean that need to iterate all
            if (end == size + 1) {
                end = size;
                // else iterate to existing bound
            } else if (to.compareTo(readBuffer(file, intAt(index, end))) <= 0) {
                --end;
            }
        }

        List<BaseEntry<ByteBuffer>> list = new LinkedList<>();
        for (int i = start; i <= end; ++i) {
            list.add(readEntry(file, intAt(index, i)));
        }
        return list;
    }

    public static int getIndex(ByteBuffer file, ByteBuffer index, ByteBuffer key, int start, int end) {
        int min = start;
        int max = end;
        int mid;
        while (min <= max) {
            mid = min + (max - min) / 2;
            int comparison = key.compareTo(readBuffer(file, intAt(index, mid)));
            if (comparison == 0) {
                return mid;
            }
            if (comparison > 0) {
                min = mid + 1;
            } else {
                max = mid - 1;
            }
        }
        return min;
    }

}

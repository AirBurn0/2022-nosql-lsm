package ru.mail.polis.baidiyarosan;

import ru.mail.polis.BaseEntry;
import sun.misc.Unsafe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
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

    private static Unsafe UNSAFE;

    // unsafe hack that need to delete files on windows
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    private FileUtils() {
        // Utility class
    }

    // windows method that close files before delete
    private static void clear(MappedByteBuffer map) {
        UNSAFE.invokeCleaner(map);
    }

    // windows method that close files before delete
    public static void clearAllFrom(Collection<MappedByteBuffer> collection) {
        for (MappedByteBuffer map : collection) {
            if (map != null) {
                clear(map);
            }
        }
    }

    // windows method that close files before delete
    public static void clearFrom(List<MappedByteBuffer> list, int number) {
        if (list.size() < number) {
            return;
        }

        clear(list.get(number - 1));
        list.set(number - 1, null);
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

    public static void writeOnDisk(
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

    // All files
    public static Collection<PeekIterator<BaseEntry<ByteBuffer>>> getFilesCollection(
            int filesCount, Path path, List<MappedByteBuffer> files, List<MappedByteBuffer> fileIndexes) throws IOException {
        return getFilesCollection(filesCount, path, files, fileIndexes, null, null);
    }

    public static Collection<PeekIterator<BaseEntry<ByteBuffer>>> getFilesCollection(
            int filesCount, Path path, List<MappedByteBuffer> files, List<MappedByteBuffer> fileIndexes,
            ByteBuffer from, ByteBuffer to) throws IOException {
        List<PeekIterator<BaseEntry<ByteBuffer>>> list = new LinkedList<>();
        Collection<BaseEntry<ByteBuffer>> temp;
        for (int i = 0; i < filesCount; ++i) {
            // file naming starts from 1, collections ordering starts from 0
            Path filePath;
            if (isCompacted(path, i + 1)) {
                filePath = getCompactedDataPath(path, i + 1);
            } else {
                filePath = getDataPath(path, i + 1);
            }
            Path indexPath = getIndexPath(path, i + 1);
            if (files.size() <= i || files.get(i) == null) {
                try (FileChannel in = FileChannel.open(filePath, StandardOpenOption.READ);
                     FileChannel indexes = FileChannel.open(indexPath, StandardOpenOption.READ)
                ) {
                    files.add(i, in.map(FileChannel.MapMode.READ_ONLY, 0, in.size()));
                    fileIndexes.add(i, indexes.map(FileChannel.MapMode.READ_ONLY, 0, indexes.size()));
                }
            }

            temp = getInFileCollection(files.get(i), fileIndexes.get(i), from, to);
            if (!temp.isEmpty()) {
                list.add(new PeekIterator<>(temp.iterator(), filesCount - i));
            }
        }

        return list;
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
            }
        }
    }

    public static void clearOldFiles(int fileCount, Path path, List<MappedByteBuffer> fileIndexes, List<MappedByteBuffer> files) throws IOException {
        Path compactedFileIndexPath = getIndexPath(path, COMPACTED_FILE_INDEX);
        Path compactedFileDataPath = getCompactedDataPath(path, COMPACTED_FILE_INDEX);
        if (Files.notExists(compactedFileIndexPath) || Files.notExists(compactedFileDataPath)) {
            return; // no compaction
        }
        int lastFile = fileCount;
        try {
            // try to delete old files from last to first
            for (; lastFile > 0; --lastFile) {
                clearFrom(fileIndexes, lastFile);
                Files.deleteIfExists(getIndexPath(path, lastFile));
                clearFrom(files, lastFile);
                Files.deleteIfExists(getDataPath(path, lastFile));
                // in case that this file was compacted
                Files.deleteIfExists(getCompactedDataPath(path, lastFile));
            }
        } catch (DirectoryNotEmptyException e) {
            // ???
            throw new IOException("File system corrupted");
        } catch (IOException e) {
            // access failed
            throw new UncheckedIOException(e);
        } finally { // happens anyway
            try {
                lastFile++; // make step forward
                // making compacted file last, closing last file if opened
                Files.move(compactedFileIndexPath, getIndexPath(path, lastFile), StandardCopyOption.ATOMIC_MOVE);
                Files.move(compactedFileDataPath, getCompactedDataPath(path, lastFile), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // compaction was never an option
                Files.deleteIfExists(compactedFileIndexPath);
                Files.deleteIfExists(compactedFileDataPath);
                // access failed
                throw new UncheckedIOException(e);
            }
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

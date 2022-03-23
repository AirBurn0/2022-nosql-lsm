package ru.mail.polis.baidiyarosan;

import ru.mail.polis.BaseEntry;
import ru.mail.polis.Config;
import ru.mail.polis.Dao;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentSkipListMap;

import static ru.mail.polis.baidiyarosan.FileUtils.readBuffer;

public class MemoryAndDiskDao implements Dao<ByteBuffer, BaseEntry<ByteBuffer>> {

    protected final NavigableMap<ByteBuffer, BaseEntry<ByteBuffer>> collection = new ConcurrentSkipListMap<>();

    protected final Path path;

    public MemoryAndDiskDao(Config config) throws IOException {
        this.path = config.basePath();
        Path indexesDir = path.resolve(Paths.get(FileUtils.INDEX_FOLDER));
        if (Files.notExists(indexesDir)) {
            Files.createDirectory(indexesDir);
        }

    }

    @Override
    public Iterator<BaseEntry<ByteBuffer>> get(ByteBuffer from, ByteBuffer to) throws IOException {
        PriorityQueue<PeekIterator<BaseEntry<ByteBuffer>>> heap =
                new PriorityQueue<>(Comparator.comparing(o -> o.peek().key()));
        Iterator<BaseEntry<ByteBuffer>> temp = getInMemoryIterator(from, to);
        if (temp.hasNext()) {
            heap.add(new PeekIterator<>(temp, Integer.MAX_VALUE));
        }

        for (Path searchPath : FileUtils.getPaths(path)) {
            temp = getInFileIterator(searchPath, from, to);
            if (temp.hasNext()) {
                heap.add(new PeekIterator<>(temp, FileUtils.getFileNumber(searchPath)));
            }
        }

        return new DaoIterator(heap);
    }

    private Iterator<BaseEntry<ByteBuffer>> getInMemoryIterator(ByteBuffer from, ByteBuffer to) {
        if (collection.isEmpty()) {
            return Collections.emptyIterator();
        }
        if (from == null && to == null) {
            return collection.values().iterator();
        }

        ByteBuffer start = (from == null ? collection.firstKey() : collection.ceilingKey(from));
        ByteBuffer end = (to == null ? collection.lastKey() : collection.floorKey(to));

        if (start == null || end == null || start.compareTo(end) > 0) {
            return Collections.emptyIterator();
        }
        return collection.subMap(start, true, end, to == null || !to.equals(collection.floorKey(to)))
                .values().iterator();
    }

    private Iterator<BaseEntry<ByteBuffer>> getInFileIterator(Path filePath, ByteBuffer from, ByteBuffer to)
            throws IOException {
        try (FileChannel in = FileChannel.open(filePath, StandardOpenOption.READ)) {
            List<BaseEntry<ByteBuffer>> list = new LinkedList<>();
            int file = FileUtils.getFileNumber(filePath);
            long[] indexes = getIndexArray(file);
            ByteBuffer temp = ByteBuffer.allocate(Integer.BYTES);
            int start = 0;
            int end = indexes.length - 1;
            if (from != null) {
                start = FileUtils.getStartIndex(in, indexes, from, temp);
            }
            if (to != null) {
                end = FileUtils.getEndIndex(in, indexes, to, temp);
            }
            if (start == -1 || end == -1) {
                return Collections.emptyIterator();
            }

            for (int i = start; i <= end; ++i) {
                list.add(new BaseEntry<>(readBuffer(in, indexes[i], temp), readBuffer(in, temp)));
            }
            return list.iterator();
        }
    }

    @Override
    public void upsert(BaseEntry<ByteBuffer> entry) {
        collection.put(entry.key(), entry);
    }

    @Override
    public BaseEntry<ByteBuffer> get(ByteBuffer key) throws IOException {
        BaseEntry<ByteBuffer> value = collection.get(key);
        if (value == null) {
            return getInFile(key);
        }
        return value.value() == null ? null : value;
    }

    private BaseEntry<ByteBuffer> getInFile(ByteBuffer key) throws IOException {
        BaseEntry<ByteBuffer> value = null;

        int order = 0;
        for (Path searchPath : FileUtils.getPaths(path)) {
            if (order < FileUtils.getFileNumber(searchPath)) {
                value = binarySearchFile(searchPath, key);
                if (value != null) {
                    order = FileUtils.getFileNumber(searchPath);
                }
            }
        }

        if (value == null) {
            return null;
        }
        return value.value() == null ? null : value;
    }

    private BaseEntry<ByteBuffer> binarySearchFile(Path searchPath, ByteBuffer key)
            throws IOException {
        try (FileChannel in = FileChannel.open(searchPath, StandardOpenOption.READ)) {
            int file = FileUtils.getFileNumber(searchPath);
            long[] indexes = getIndexArray(file);
            ByteBuffer temp = ByteBuffer.allocate(Integer.BYTES);
            int min = 0;
            int max = indexes.length - 1;
            int mid;
            int comparison;
            while (min <= max) {
                comparison = key.compareTo(readBuffer(in, indexes[min], temp));
                if (comparison < 0) {
                    return null;
                }
                if (comparison == 0) {
                    return new BaseEntry<>(key, readBuffer(in, temp));
                }
                comparison = key.compareTo(readBuffer(in, indexes[max], temp));
                if (comparison > 0) {
                    return null;
                }
                if (comparison == 0) {
                    return new BaseEntry<>(key, readBuffer(in, temp));
                }
                mid = min + (max - min) / 2;
                comparison = key.compareTo(readBuffer(in, indexes[mid], temp));
                if (comparison == 0) {
                    return new BaseEntry<>(key, readBuffer(in, temp));
                }
                if (comparison > 0) {
                    min = mid + 1;
                } else {
                    max = mid - 1;
                }
            }
        }
        return null;
    }

    @Override
    public void flush() throws IOException {
        if (collection.isEmpty()) {
            return;
        }
        FileUtils.writeOnDisk(collection, path);
    }

    private long[] getIndexArray(int fileNumber) throws IOException {
        long[] array;
        Path indexPath = path.resolve(Paths.get(FileUtils.INDEX_FOLDER,
                FileUtils.INDEX_FILE_HEADER + fileNumber + FileUtils.FILE_EXTENSION));
        try (FileChannel indexOut = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            int size = (int) (indexOut.size() / Long.BYTES);
            array = new long[size];
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            for (int i = 0; i < size; ++i) {
                array[i] = FileUtils.readLong(indexOut, buffer);
            }
        }

        return array;
    }

}

package com.npuhub.util;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class GgufMetadataReader {
    private static final int GGUF_TYPE_UINT8 = 0;
    private static final int GGUF_TYPE_INT8 = 1;
    private static final int GGUF_TYPE_UINT16 = 2;
    private static final int GGUF_TYPE_INT16 = 3;
    private static final int GGUF_TYPE_UINT32 = 4;
    private static final int GGUF_TYPE_INT32 = 5;
    private static final int GGUF_TYPE_FLOAT32 = 6;
    private static final int GGUF_TYPE_BOOL = 7;
    private static final int GGUF_TYPE_STRING = 8;
    private static final int GGUF_TYPE_ARRAY = 9;
    private static final int GGUF_TYPE_UINT64 = 10;
    private static final int GGUF_TYPE_INT64 = 11;
    private static final int GGUF_TYPE_FLOAT64 = 12;

    private GgufMetadataReader() {
    }

    public static OptionalInt readContextLength(File file) {
        if (file == null || !file.isFile()) {
            return OptionalInt.empty();
        }

        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!"GGUF".equals(new String(magic, StandardCharsets.US_ASCII))) {
                return OptionalInt.empty();
            }

            long version = readUInt32(input);
            if (version < 2 || version > 3) {
                return OptionalInt.empty();
            }

            readUInt64(input); // tensor count
            long metadataCount = readUInt64(input);
            if (metadataCount < 0 || metadataCount > 1_000_000) {
                return OptionalInt.empty();
            }

            for (long index = 0; index < metadataCount; index++) {
                String key = readString(input);
                int valueType = Math.toIntExact(readUInt32(input));
                if (key.endsWith(".context_length")) {
                    Long value = readIntegerValue(input, valueType);
                    if (value != null && value > 0 && value <= Integer.MAX_VALUE) {
                        return OptionalInt.of(value.intValue());
                    }
                    if (value == null) {
                        skipValue(input, valueType);
                    }
                } else {
                    skipValue(input, valueType);
                }
            }
        } catch (IOException | ArithmeticException ignored) {
            return OptionalInt.empty();
        }

        return OptionalInt.empty();
    }

    /**
     * Reads GGUF key/value metadata without touching tensor data. Array values
     * (notably the tokenizer vocabulary) are skipped unless verbose is true,
     * matching Ollama's compact/verbose model-info behaviour.
     */
    public static Optional<Map<String, Object>> readMetadata(File file, boolean verbose) {
        if (file == null || !file.isFile()) {
            return Optional.empty();
        }

        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!"GGUF".equals(new String(magic, StandardCharsets.US_ASCII))) {
                return Optional.empty();
            }

            long version = readUInt32(input);
            if (version < 2 || version > 3) {
                return Optional.empty();
            }

            readUInt64(input); // tensor count
            long metadataCount = readUInt64(input);
            if (metadataCount < 0 || metadataCount > 1_000_000) {
                return Optional.empty();
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            for (long index = 0; index < metadataCount; index++) {
                String key = readString(input);
                int valueType = Math.toIntExact(readUInt32(input));
                if (valueType == GGUF_TYPE_ARRAY && !verbose) {
                    skipValue(input, valueType);
                    continue;
                }
                metadata.put(key, readValue(input, valueType, verbose));
            }
            return Optional.of(metadata);
        } catch (IOException | ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    private static Object readValue(RandomAccessFile input, int valueType, boolean verbose) throws IOException {
        return switch (valueType) {
            case GGUF_TYPE_UINT8 -> input.readUnsignedByte();
            case GGUF_TYPE_INT8 -> input.readByte();
            case GGUF_TYPE_UINT16 -> readUInt16(input);
            case GGUF_TYPE_INT16 -> Short.reverseBytes(input.readShort());
            case GGUF_TYPE_UINT32 -> readUInt32(input);
            case GGUF_TYPE_INT32 -> Integer.reverseBytes(input.readInt());
            case GGUF_TYPE_FLOAT32 -> Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
            case GGUF_TYPE_BOOL -> input.readUnsignedByte() != 0;
            case GGUF_TYPE_STRING -> readString(input);
            case GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> readUInt64(input);
            case GGUF_TYPE_FLOAT64 -> Double.longBitsToDouble(Long.reverseBytes(input.readLong()));
            case GGUF_TYPE_ARRAY -> readArray(input, verbose);
            default -> throw new IOException("Unsupported GGUF metadata type: " + valueType);
        };
    }

    private static List<Object> readArray(RandomAccessFile input, boolean verbose) throws IOException {
        int elementType = Math.toIntExact(readUInt32(input));
        long elementCount = readUInt64(input);
        if (elementCount < 0 || elementCount > 100_000_000 || elementCount > Integer.MAX_VALUE) {
            throw new IOException("Invalid GGUF array length: " + elementCount);
        }
        if (!verbose) {
            skipArray(input, elementType, elementCount);
            return List.of();
        }

        List<Object> values = new ArrayList<>((int) elementCount);
        for (long index = 0; index < elementCount; index++) {
            if (elementType == GGUF_TYPE_ARRAY) {
                throw new IOException("Nested GGUF metadata arrays are unsupported");
            }
            values.add(readValue(input, elementType, true));
        }
        return values;
    }

    private static Long readIntegerValue(RandomAccessFile input, int valueType) throws IOException {
        return switch (valueType) {
            case GGUF_TYPE_UINT8 -> (long) input.readUnsignedByte();
            case GGUF_TYPE_INT8 -> (long) input.readByte();
            case GGUF_TYPE_UINT16 -> (long) readUInt16(input);
            case GGUF_TYPE_INT16 -> (long) Short.reverseBytes(input.readShort());
            case GGUF_TYPE_UINT32 -> readUInt32(input);
            case GGUF_TYPE_INT32 -> (long) Integer.reverseBytes(input.readInt());
            case GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> readUInt64(input);
            default -> null;
        };
    }

    private static void skipValue(RandomAccessFile input, int valueType) throws IOException {
        switch (valueType) {
            case GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> skipBytes(input, 1);
            case GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> skipBytes(input, 2);
            case GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 -> skipBytes(input, 4);
            case GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 -> skipBytes(input, 8);
            case GGUF_TYPE_STRING -> skipString(input);
            case GGUF_TYPE_ARRAY -> {
                int elementType = Math.toIntExact(readUInt32(input));
                long elementCount = readUInt64(input);
                skipArray(input, elementType, elementCount);
            }
            default -> throw new IOException("Unsupported GGUF metadata type: " + valueType);
        }
    }

    private static void skipArray(RandomAccessFile input, int elementType, long count) throws IOException {
        if (count < 0 || count > 100_000_000) {
            throw new IOException("Invalid GGUF array length: " + count);
        }

        int fixedSize = switch (elementType) {
            case GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> 1;
            case GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> 2;
            case GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 -> 4;
            case GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 -> 8;
            default -> 0;
        };
        if (fixedSize > 0) {
            skipBytes(input, Math.multiplyExact(count, fixedSize));
            return;
        }
        if (elementType == GGUF_TYPE_STRING) {
            for (long index = 0; index < count; index++) {
                skipString(input);
            }
            return;
        }
        throw new IOException("Unsupported GGUF array element type: " + elementType);
    }

    private static String readString(RandomAccessFile input) throws IOException {
        long length = readUInt64(input);
        if (length < 0 || length > 1_048_576) {
            throw new IOException("Invalid GGUF string length: " + length);
        }
        byte[] bytes = new byte[(int) length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skipString(RandomAccessFile input) throws IOException {
        skipBytes(input, readUInt64(input));
    }

    private static void skipBytes(RandomAccessFile input, long count) throws IOException {
        if (count < 0 || count > input.length() - input.getFilePointer()) {
            throw new EOFException("GGUF metadata extends past the end of the file");
        }
        input.seek(input.getFilePointer() + count);
    }

    private static int readUInt16(RandomAccessFile input) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
    }

    private static long readUInt32(RandomAccessFile input) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
    }

    private static long readUInt64(RandomAccessFile input) throws IOException {
        return Long.reverseBytes(input.readLong());
    }
}

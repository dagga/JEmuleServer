/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.core;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;

/**
 * Lucene's codec ({@link Lucene104Codec}: a new Lucene codec needs a new {@link #NAME} and FileIndex format) with the stored fields of {@link FileIndex} in chunks of {@value #CHUNK_BYTES} bytes compressed
 * with LZ4 alone, instead of the default 80 KB chunks with a preset dictionary. A search loads its results in source
 * count order, so in random order: each file read decompressed a dictionary and a sub-block, about 3 µs, which was
 * most of the time of a search returning hundreds of files. A short chunk is only decompressed up to the file read.
 * <p>
 * Found by name ({@value #NAME}) when an index is reopened: registered in {@code META-INF/services}.
 */
public final class FileIndexCodec extends FilterCodec {
    static final String NAME = "JEmuleFileIndex1";
    static final int CHUNK_BYTES = 2 * 1024;

    private final StoredFieldsFormat storedFields = new Lucene90CompressingStoredFieldsFormat("JEmuleStoredFields1",
            CompressionMode.FAST, CHUNK_BYTES, 128, 10);

    public FileIndexCodec() {
        // Not Codec.getDefault(): the codecs are being loaded when this one is built
        super(NAME, new Lucene104Codec());
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return storedFields;
    }
}

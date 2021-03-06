/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.streamstore.server.fs.serializable;

import stroom.io.IInputStream;

import java.io.IOException;

/**
 * <p>
 * This class overrides <code>InputStream</code> and is used to read input
 * created by a <code>SegmentOutputStream</code>. Input can be filtered to only
 * include or exclude specific segments when read.
 * </p>
 * <p>
 * Segment numbers start at <code>0</code> with the maximum segment number being
 * <code>count() - 1</code>.
 * </p>
 */
public interface SegmentInputStream extends IInputStream {
    /**
     * This method returns the total number of segments that can be read from
     * this input stream.
     *
     * @return The total number of segments that can be read from this input
     *         stream.
     *
     * @throws IOException
     *             Could be thrown when trying to determine how many segments
     *             there are.
     */
    long count() throws IOException;

    /**
     * Return the byte offset in the underlying stream given a segment number
     */
    long byteOffset(long segment) throws IOException;

    /**
     * Return the segment number given a byte position
     */
    long segmentAtByteOffset(long bytePos) throws IOException;

    /**
     * Includes a specific segment number when reading from this input stream.
     */
    void include(long segment);

    /**
     * Includes all segments when reading from this input stream. This is the
     * default behaviour if no segments are specifically included or excluded.
     */
    void includeAll();

    /**
     * Excludes a specific segment number when reading from this input stream.
     * Initially all segments are included so setting this will exclude only the
     * specified segment.
     */
    void exclude(long segment);

    /**
     * Excludes all segments when reading from this input stream. It is unlikely
     * that all input should be excluded, instead this method should be used to
     * clear all includes that have been specifically set.
     */
    void excludeAll();
}

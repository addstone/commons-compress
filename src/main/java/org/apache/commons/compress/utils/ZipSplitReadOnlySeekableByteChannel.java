/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.commons.compress.utils;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipLong;
import org.apache.commons.compress.compressors.FileNameUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class ZipSplitReadOnlySeekableByteChannel extends MultiReadOnlySeekableByteChannel {
    protected final int ZIP_SPLIT_SIGNATURE_LENGTH = 4;
    protected ByteBuffer zipSplitSignatureByteBuffer = ByteBuffer.allocate(ZIP_SPLIT_SIGNATURE_LENGTH);

    /**
     * Concatenates the given channels.
     * the channels should be add in ascending order, e.g. z01, z02, ... z99, zip
     * please note that the .zip file is the last segment and should be added as the last one in the channels
     *
     * The first 4 bytes of split zip signature will be taken into consideration by Inflator,
     * so we don't need to skip them
     *
     * @param channels the channels to concatenate
     * @throws NullPointerException if channels is null
     */
    public ZipSplitReadOnlySeekableByteChannel(List<SeekableByteChannel> channels) throws IOException {
        super(channels);

        // each split zip segment should begin with zip split signature
        validSplitSignature(channels);
    }

    /**
     * the first 4 bytes of zip split segments should be the zip split signature(0x08074B50)
     *
     * @param channels channels to be valided
     * @throws IOException
     */
    private void validSplitSignature(final List<SeekableByteChannel> channels) throws IOException {
        for(int i = 0;i < channels.size();i++) {
            SeekableByteChannel channel = channels.get(i);
            // the zip split file signature is always at the beginning of the segment
            channel.position(0L);

            channel.read(zipSplitSignatureByteBuffer);
            final ZipLong signature = new ZipLong(zipSplitSignatureByteBuffer.array());
            if(!signature.equals(ZipLong.DD_SIG)) {
                channel.position(0L);
                throw new IOException("No." + (i + 1) +  " split zip file is not begin with split zip file signature");
            }

            channel.position(0L);
        }
    }

    /**
     * set the position based on the given disk number and relative offset
     *
     * @param diskNumber the disk number of split zip files
     * @param relativeOffset the offset in the split zip segment with given disk number
     * @return global position of all split zip files as if they are a single channel
     * @throws IOException
     */
    public synchronized SeekableByteChannel position(long diskNumber, long relativeOffset) throws IOException {
        long globalPosition = relativeOffset;
        for(int i = 0; i < diskNumber;i++) {
            globalPosition += channels.get(i).size();
        }

        return position(globalPosition);
    }

    /**
     * Concatenates the given channels.
     *
     * @param channels the channels to concatenate, note that the LAST CHANNEL of channels should be the LAST SEGMENT(.zip)
     *                 and theses channels should be added in correct order (e.g. .z01, .z02... .z99, .zip)
     * @throws NullPointerException if channels is null
     * @return SeekableByteChannel that concatenates all provided channels
     */
    public static SeekableByteChannel forSeekableByteChannels(SeekableByteChannel... channels) throws IOException {
        if (Objects.requireNonNull(channels, "channels must not be null").length == 1) {
            return channels[0];
        }
        return new ZipSplitReadOnlySeekableByteChannel(Arrays.asList(channels));
    }

    /**
     * Concatenates the given channels.
     *
     * @param lastSegmentChannel channel of the last segment of split zip segments, its extension should be .zip
     * @param channels the channels to concatenate except for the last segment,
     *                 note theses channels should be added in correct order (e.g. .z01, .z02... .z99)
     * @return SeekableByteChannel that concatenates all provided channels
     * @throws NullPointerException if lastSegmentChannel or channels is null
     */
    public static SeekableByteChannel forSeekableByteChannels(SeekableByteChannel lastSegmentChannel, Iterable<SeekableByteChannel> channels) throws IOException {
        if(channels == null || lastSegmentChannel == null) {
            throw new NullPointerException("channels must not be null");
        }

        List<SeekableByteChannel> channelsList = new ArrayList<>();
        for(SeekableByteChannel channel : channels) {
            channelsList.add(channel);
        }
        channelsList.add(lastSegmentChannel);

        SeekableByteChannel[] channelArray = new SeekableByteChannel[channelsList.size()];
        return forSeekableByteChannels(channelsList.toArray(channelArray));
    }

    /**
     * Concatenates zip split files from the last segment(the extension SHOULD be .zip)
     *
     * @param lastSegmentFile the last segment of zip split files, note that the extension SHOULD be .zip
     * @return SeekableByteChannel that concatenates all zip split files
     * @throws IllegalArgumentException if the lastSegmentFile's extension is NOT .zip
     */
    public static SeekableByteChannel buildFromLastSplitSegment(File lastSegmentFile) throws IOException {
        String extension = FileNameUtil.getExtension(lastSegmentFile.getCanonicalPath());
        if(!extension.equals(ArchiveStreamFactory.ZIP)) {
            throw new IllegalArgumentException("The extension of last zip splite segment should be .zip");
        }

        File parent = lastSegmentFile.getParentFile();
        String fileBaseName = FileNameUtil.getBaseName(lastSegmentFile.getCanonicalPath());
        ArrayList<File> splitZipSegments = new ArrayList<>();

        // zip split segments should be like z01,z02....z(n-1) based on the zip specification
        String pattern = fileBaseName + ".z[0-9]+";
        for(File file : parent.listFiles()) {
            if(!Pattern.matches(pattern, file.getName())) {
                continue;
            }

            splitZipSegments.add(file);
        }

        Collections.sort(splitZipSegments, new ZipSplitSegmentComparator());
        return forFiles(lastSegmentFile, splitZipSegments);
    }

    /**
     * Concatenates the given files.
     *
     * @param files the files to concatenate, note that the LAST FILE of files should be the LAST SEGMENT(.zip)
     *              and theses files should be added in correct order (e.g. .z01, .z02... .z99, .zip)
     * @throws NullPointerException if files is null
     * @throws IOException if opening a channel for one of the files fails
     * @return SeekableByteChannel that concatenates all provided files
     */
    public static SeekableByteChannel forFiles(File... files) throws IOException {
        List<SeekableByteChannel> channels = new ArrayList<>();
        for (File f : Objects.requireNonNull(files, "files must not be null")) {
            channels.add(Files.newByteChannel(f.toPath(), StandardOpenOption.READ));
        }
        if (channels.size() == 1) {
            return channels.get(0);
        }
        return new ZipSplitReadOnlySeekableByteChannel(channels);
    }

    /**
     * Concatenates the given files.
     *
     * @param lastSegmentFile the last segment of split zip segments, its extension should be .zip
     * @param files the files to concatenate except for the last segment,
     *              note theses files should be added in correct order (e.g. .z01, .z02... .z99)
     * @return SeekableByteChannel that concatenates all provided files
     * @throws IOException
     * @throws NullPointerException if files or lastSegmentFile is null
     */
    public static SeekableByteChannel forFiles(File lastSegmentFile, Iterable<File> files) throws IOException {
        if(files == null || lastSegmentFile == null) {
            throw new NullPointerException("files must not be null");
        }

        List<File> filesList = new ArrayList<>();
        for (File f : files) {
            filesList.add(f);
        }
        filesList.add(lastSegmentFile);

        File[] filesArray = new File[filesList.size()];
        return forFiles(filesList.toArray(filesArray));
    }
}

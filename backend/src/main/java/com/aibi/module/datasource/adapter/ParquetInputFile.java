package com.aibi.module.datasource.adapter;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 纯 NIO 的 Parquet 输入（实现 Parquet 的 {@link InputFile}），<b>不依赖 Hadoop FileSystem / winutils</b>。
 *
 * <p>本地 Parquet 文件与对象存储下载到本地的副本都走它打开。相比 {@code AvroParquetReader.builder(hadoop.fs.Path)}，
 * 本实现直接基于 {@code java.nio.file.Path} 读字节，<b>不会触发 Hadoop 的 {@code FileSystem.get} 与
 * {@code Shell} 静态初始化</b>——后者在 Windows 开发机需要 {@code HADOOP_HOME/winutils} 才能初始化，
 * 否则整个 Parquet 读取链路在本地直接 {@code FileNotFoundException} 崩溃。生产（Linux）与开发（Windows）共用同一套链路。
 */
public class ParquetInputFile implements InputFile {

    private final Path path;

    public ParquetInputFile(Path path) {
        this.path = path;
    }

    @Override
    public long getLength() throws IOException {
        return Files.size(path);
    }

    @Override
    public SeekableInputStream newStream() throws IOException {
        return new NioSeekableInputStream(path);
    }

    /** 基于 {@link SeekableByteChannel} 的可随机读流：维护当前位置以支持 Parquet 的 seek 读取。 */
    private static final class NioSeekableInputStream extends SeekableInputStream {

        private final SeekableByteChannel channel;
        private long pos;

        NioSeekableInputStream(Path path) throws IOException {
            this.channel = Files.newByteChannel(path, StandardOpenOption.READ);
            this.pos = 0;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            int n = channel.read(buf);
            if (n > 0) {
                pos += n;
            }
            return n;
        }

        @Override
        public int read(ByteBuffer buff) throws IOException {
            int n = channel.read(buff);
            if (n > 0) {
                pos += n;
            }
            return n;
        }

        @Override
        public long getPos() throws IOException {
            return pos;
        }

        @Override
        public void seek(long newPos) throws IOException {
            channel.position(newPos);
            pos = newPos;
        }

        @Override
        public void readFully(ByteBuffer buff) throws IOException {
            while (buff.hasRemaining()) {
                int n = channel.read(buff);
                if (n < 0) {
                    throw new EOFException("Parquet 输入未读满即遇文件末尾");
                }
                pos += n;
            }
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
            int read = 0;
            while (read < len) {
                int n = read(b, off + read, len - read);
                if (n < 0) {
                    throw new EOFException("Parquet 输入未读满即遇文件末尾");
                }
                read += n;
            }
        }

        @Override
        public void readFully(byte[] b) throws IOException {
            readFully(b, 0, b.length);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}

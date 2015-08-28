/** 
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.core.channel;

import org.reveno.atp.core.api.channel.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.reveno.atp.utils.UnsafeUtils.destroyDirectBuffer;

/**
 * Not super correct currently - we should use separate reader and extender indexes.
 * 
 * But since for now it is only used for writing as wrapper in FileChannel - let it be.
 * 
 * @author Artem Dmitriev <art.dm.ser@gmail.com>
 *
 */
public class ChannelBuffer implements Buffer {

	protected Function<ByteBuffer, ByteBuffer> reader;
	protected BiFunction<Long, ByteBuffer, ByteBuffer> extender;

	protected ByteBuffer buffer; 
	public ByteBuffer getBuffer() {
		return buffer;
	}
	
	public ChannelBuffer(ByteBuffer buffer) {
		this.buffer = buffer;
	}

	public ChannelBuffer(ByteBuffer buffer, Function<ByteBuffer, ByteBuffer> reader,
			BiFunction<Long, ByteBuffer, ByteBuffer> extender) {
		this.buffer = buffer;
		this.extender = extender;
		this.reader = reader;
	}

	public ChannelBuffer(ByteBuffer buffer, Consumer<ByteBuffer> reader,
						 BiConsumer<Long, ByteBuffer> extender) {
		this.buffer = buffer;
		this.extender = (p,b) -> { extender.accept(p, b); return b; };
		this.reader = (b) -> { reader.accept(b); return b; };
	}

	@Override
	public byte[] getBytes() {
		return readBytes();
	}

    @Override
    public int readerPosition() {
        return buffer.position();
    }
    
    @Override
    public int writerPosition() {
        return buffer.position();
    }

    @Override
    public int limit() {
        return buffer.limit();
    }

    @Override
	public long capacity() {
		return buffer.capacity();
	}

	@Override
	public int length() {
		return buffer.capacity();
	}

	@Override
	public int remaining() {
		return buffer.remaining();
	}

	@Override
	public void clear() {
		buffer.clear();
	}

	@Override
	public void release() {
		destroyDirectBuffer(buffer);
	}

	@Override
	public boolean isAvailable() {
		boolean result = buffer.remaining() > 0;
		if (!result) {
			autoExtendIfRequired(1, true);
			result = buffer.remaining() > 0;
		}
		return result;
	}

    @Override
    public void setReaderPosition(int position) {
        this.buffer.position(position);
    }
    
    @Override
    public void setWriterPosition(int position) {
        this.buffer.position(position);
    }

    @Override
    public void setLimit(int limit) {
		if (limit > buffer.capacity()) {
			nextLimitOnAutoextend = limit - buffer.capacity();
			return;
		}
        this.buffer.limit(limit);
    }

    @Override
    public void writeByte(byte b) {
        autoExtendIfRequired(1);
        buffer.put(b);
    }

    @Override
	public void writeBytes(byte[] bytes) {
		autoExtendIfRequired(bytes.length);
		buffer.put(bytes);
	}

    @Override
    public void writeBytes(byte[] bytes, int offset, int count) {
        autoExtendIfRequired(bytes.length);
        buffer.put(bytes, offset, count);
    }

    @Override
	public void writeLong(long value) {
		autoExtendIfRequired(8);
		buffer.putLong(value);
	}

	@Override
	public void writeInt(int value) {
		autoExtendIfRequired(4);
		buffer.putInt(value);
	}

    @Override
    public void writeShort(short s) {
        autoExtendIfRequired(2);
        buffer.putShort(s);
    }

    @Override
	public void writeFromBuffer(ByteBuffer b) {
        autoExtendIfRequired(b.limit());
		buffer.put(b);
	}

    @Override
    public ByteBuffer writeToBuffer() {
        return buffer.slice();
    }

    @Override
	public void writeFromBuffer(Buffer b) {
		buffer.put(b.getBytes());
	}

    @Override
    public byte readByte() {
		autoExtendIfRequired(1, true);
        return buffer.get();
    }

    @Override
	public byte[] readBytes() {
		byte[] b = new byte[buffer.remaining()];
		buffer.get(b);
		return b;
	}

	@Override
	public byte[] readBytes(int length) {
		autoExtendIfRequired(length, true);
		byte[] b = new byte[length];
		buffer.get(b);
		return b;
	}

	@Override
	public void readBytes(byte[] data, int offset, int length) {
		autoExtendIfRequired(length, true);
		buffer.get(data, offset, length);
	}

	@Override
	public long readLong() {
		autoExtendIfRequired(8, true);
		return buffer.getLong();
	}

	@Override
	public int readInt() {
		autoExtendIfRequired(4, true);
		return buffer.getInt();
	}

    @Override
    public short readShort() {
		autoExtendIfRequired(2, true);
		return buffer.getShort();
    }

    @Override
	public void markReader() {
		throw new UnsupportedOperationException();
	}
    
    @Override
	public void markWriter() {
		writerMark = buffer.position();
		buffer.mark();
	}

	@Override
	public void resetReader() {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void resetWriter() {
		writerMark = -1;
		buffer.reset();
	}

	@Override
	public void limitNext(int count) {
		autoExtendIfRequired(count, true);
		startedLimitAfterAutoextend = buffer.limit();
		setLimit(readerPosition() + count);
	}

	@Override
	public void resetNextLimit() {
		setLimit(startedLimitAfterAutoextend);
	}

	@Override
	public void markSize() {
		sizePosition = buffer.position();
		autoExtendIfRequired(4);
		buffer.putInt(0);
	}

	@Override
	public int sizeMarkPosition() {
		return sizePosition;
	}

	@Override
	public void writeSize() {
		int newPos = buffer.position();
		buffer.position(sizePosition);
		buffer.putInt(newPos - sizePosition - 4);
		buffer.position(newPos);
	}

	public void extendBuffer(long length) {
		ByteBuffer newBuffer = ByteBuffer.allocateDirect(next2n(buffer.position() + (int)length));
		buffer.flip();
		newBuffer.put(buffer);
		if (writerMark > 0) {
			int oldPos = buffer.position();
			buffer.position(writerMark);
			buffer.mark();
			buffer.position(oldPos);
		}
		destroyDirectBuffer(buffer);
		buffer = newBuffer;
	}

	protected void autoExtendIfRequired(int length) {
		autoExtendIfRequired(length, false);
	}

	protected void autoExtendIfRequired(int length, boolean read) {
		if ((buffer.position() + length) - buffer.limit() > 0) {
			if (read) {
				buffer = reader.apply(buffer);
			} else {
				buffer = extender.apply((long) buffer.limit(), buffer);
				if (buffer.position() < sizePosition) {
					sizePosition = 0;
				}
			}
			if (nextLimitOnAutoextend != 0) {
				startedLimitAfterAutoextend = buffer.limit();
				setLimit(nextLimitOnAutoextend);
				nextLimitOnAutoextend = 0;
			}
		}
	}

	/**
     * We should consider only numbers that mod FS page size with 0 remainder, means
     * any number of 2^n
     *
     * @param size comparing size
     * @return result 2^n, which is higher or equal to size
     */
    protected int next2n(int size) {
        int i = 1;
        while (true) {
            if (Math.pow(2, ++i) > size)
                break;
        }
        return (int)Math.pow(2, i);
    }

	protected int sizePosition = -1;
	protected int writerMark = 0;
	protected int nextLimitOnAutoextend = 0;
	protected int startedLimitAfterAutoextend = 0;

	protected static final Logger log = LoggerFactory.getLogger(ChannelBuffer.class);
}

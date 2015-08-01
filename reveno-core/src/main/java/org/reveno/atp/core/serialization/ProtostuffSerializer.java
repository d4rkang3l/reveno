package org.reveno.atp.core.serialization;

import io.protostuff.Input;
import io.protostuff.LowCopyProtostuffOutput;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.reveno.atp.api.domain.RepositoryData;
import org.reveno.atp.core.api.TransactionCommitInfo;
import org.reveno.atp.core.api.TransactionCommitInfo.Builder;
import org.reveno.atp.core.api.channel.Buffer;
import org.reveno.atp.core.api.serialization.RepositoryDataSerializer;
import org.reveno.atp.core.api.serialization.TransactionInfoSerializer;
import org.reveno.atp.core.serialization.protostuff.ZeroCopyBufferInput;
import org.reveno.atp.core.serialization.protostuff.ZeroCopyLinkBuffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProtostuffSerializer implements RepositoryDataSerializer, TransactionInfoSerializer {

	@Override
	public int getSerializerType() {
		return PROTO_TYPE;
	}
	
	@Override
	public boolean isRegistered(Class<?> type) {
		return registered.containsKey(type.hashCode());
	}

	@Override
	public void registerTransactionType(Class<?> txDataType) {
		registered.put(txDataType.hashCode(), new ProtoTransactionTypeHolder(txDataType, RuntimeSchema.getSchema(txDataType)));
	}

	@Override
	public void serialize(TransactionCommitInfo info, Buffer buffer) {
		changeClassLoaderIfRequired();

		buffer.writeLong(info.transactionId());
		buffer.writeLong(info.time());
		buffer.writeInt(info.transactionCommits().size());

		serializeObjects(buffer, info.transactionCommits());
	}

	@Override
	public TransactionCommitInfo deserialize(Builder builder, Buffer buffer) {
		changeClassLoaderIfRequired();

		long transactionId = buffer.readLong();
		long time = buffer.readLong();
		List<Object> commits = deserializeObjects(buffer);

		return builder.create().transactionId(transactionId).time(time).transactionCommits(commits);
	}

	@Override
	public void serialize(RepositoryData repository, Buffer buffer) {
		changeClassLoaderIfRequired();

        ZeroCopyLinkBuffer zeroCopyLinkBuffer = linkedBuff.get();
        LowCopyProtostuffOutput lowCopyProtostuffOutput = output.get();

        zeroCopyLinkBuffer.withBuffer(buffer);
        lowCopyProtostuffOutput.buffer = zeroCopyLinkBuffer;

        try {
            repoSchema.writeTo(lowCopyProtostuffOutput, repository);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
	}

	@Override
	public RepositoryData deserialize(Buffer buffer) {
		changeClassLoaderIfRequired();

        Input input = new ZeroCopyBufferInput(buffer, (int)buffer.limit(), true);
        RepositoryData repoData = repoSchema.newMessage();
        try {
            repoSchema.mergeFrom(input, repoData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
		return repoData;
	}

	@Override
	public void serializeCommands(List<Object> commands, Buffer buffer) {
		changeClassLoaderIfRequired();

		buffer.writeInt(commands.size());
		serializeObjects(buffer, commands);
	}

	@Override
	public List<Object> deserializeCommands(Buffer buffer) {
		changeClassLoaderIfRequired();

		return deserializeObjects(buffer);
	}


	public ProtostuffSerializer() {
		this(Thread.currentThread().getContextClassLoader());
	}

	public ProtostuffSerializer(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	protected void serializeObjects(Buffer buffer, List<Object> objs) {
		for (Object tc : objs) {
			serializeObject(buffer, tc);
		}
	}

	@SuppressWarnings("unchecked")
	public void serializeObject(Buffer buffer, Object tc) {
        int classHash = tc.getClass().hashCode();
        ZeroCopyLinkBuffer zeroCopyLinkBuffer = linkedBuff.get();
        LowCopyProtostuffOutput lowCopyProtostuffOutput = output.get();
        Schema<Object> schema = (Schema<Object>) registered.get(classHash).schema;

        zeroCopyLinkBuffer.withBuffer(buffer);
        lowCopyProtostuffOutput.buffer = zeroCopyLinkBuffer;

        buffer.writeInt(classHash);
        int oldPos = buffer.writerPosition();
        buffer.writeInt(0);
        try {
            schema.writeTo(lowCopyProtostuffOutput, tc);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int newPos = buffer.writerPosition();
        buffer.setWriterPosition(oldPos);
        buffer.writeInt(newPos - oldPos - 4);
        buffer.setWriterPosition(newPos);
    }
	
	protected List<Object> deserializeObjects(Buffer buffer) {
		int len = buffer.readInt();
		List<Object> commits =  new ArrayList<>(len);

		for (int i = 0; i < len; i++) {
			commits.add(i, deserializeObject(buffer));
		}
		return commits;
	}

	@SuppressWarnings("unchecked")
	public Object deserializeObject(Buffer buffer) {
		int classHash = buffer.readInt();
		int size = buffer.readInt();

		Input input = new ZeroCopyBufferInput(buffer, size, true);
		Schema<Object> schema = (Schema<Object>)registered.get(classHash).schema;
		Object message = schema.newMessage();
		try {
		    int oldLimit = buffer.limit();
		    buffer.setLimit(buffer.readerPosition() + size);
		    schema.mergeFrom(input, message);
		    buffer.setLimit(oldLimit);
		} catch (IOException e) {
		    throw new RuntimeException(e);
		}
		return message;
	}

	protected void changeClassLoaderIfRequired() {
		if (Thread.currentThread().getContextClassLoader() != classLoader) {
			Thread.currentThread().setContextClassLoader(classLoader);
		}
	}


	protected ThreadLocal<ZeroCopyLinkBuffer> linkedBuff = new ThreadLocal<ZeroCopyLinkBuffer>() {
		protected ZeroCopyLinkBuffer initialValue() {
			return new ZeroCopyLinkBuffer();
		}
	};
    protected ThreadLocal<LowCopyProtostuffOutput> output = new ThreadLocal<LowCopyProtostuffOutput>() {
        protected LowCopyProtostuffOutput initialValue() {
            return new LowCopyProtostuffOutput();
        }
    };
	protected ClassLoader classLoader;
	protected Int2ObjectOpenHashMap<ProtoTransactionTypeHolder> registered = new Int2ObjectOpenHashMap<>();
	protected final Schema<RepositoryData> repoSchema = RuntimeSchema.createFrom(RepositoryData.class);
	protected static final int PROTO_TYPE = 0x222;


	protected static class ProtoTransactionTypeHolder {
		public final Class<?> transactionType;
		public final Schema<?> schema;

		public ProtoTransactionTypeHolder(Class<?> transactionType, Schema<?> schema) {
			this.transactionType = transactionType;
			this.schema = schema;
		}
	}

}
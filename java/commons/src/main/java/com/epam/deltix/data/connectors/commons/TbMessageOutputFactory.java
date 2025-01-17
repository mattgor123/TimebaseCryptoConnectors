package com.epam.deltix.data.connectors.commons;

import com.epam.deltix.qsrv.hf.pub.md.Introspector;
import com.epam.deltix.qsrv.hf.pub.md.RecordClassDescriptor;
import com.epam.deltix.qsrv.hf.spi.conn.DisconnectEventListener;
import com.epam.deltix.qsrv.hf.tickdb.comm.client.TickDBClient;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.epam.deltix.qsrv.hf.tickdb.pub.LoadingOptions;
import com.epam.deltix.qsrv.hf.tickdb.pub.StreamOptions;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickDBFactory;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickLoader;
import com.epam.deltix.timebase.messages.InstrumentMessage;

import java.io.IOException;
import java.io.UncheckedIOException;

public class TbMessageOutputFactory implements CloseableMessageOutputFactory {
    private static final int GET_STREAM_RETRIES = 5;

    private final String tbUrl;
    private final String streamKey;
    private final RecordClassDescriptor[] types;
    private final Logger logger;

    public TbMessageOutputFactory(
            final String tbUrl,
            final String streamKey,
            final RecordClassDescriptor[] types,
            final Logger logger) {
        this.tbUrl = tbUrl;
        this.streamKey = streamKey;
        this.types = types;
        this.logger = logger;
    }

    public TbMessageOutputFactory(
            final String tbUrl,
            final String streamKey,
            final Class[] types,
            final Logger logger) {
        this.tbUrl = tbUrl;
        this.streamKey = streamKey;
        this.logger = logger;

        this.types = new RecordClassDescriptor[types.length];

        final Introspector introspector = Introspector.createEmptyMessageIntrospector();
        for (int i = 0; i < types.length; i++) {
            try {
                this.types[i] = introspector.introspectRecordClass(types[i]);
            } catch (Introspector.IntrospectionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CloseableMessageOutput create() {
        TickDBClient tb = null;
        TickLoader<InstrumentMessage> loader = null;
        try {
            tb = (TickDBClient) TickDBFactory.createFromUrl(tbUrl); // unchecked
            tb.setTimeout(10_000);
            tb.addDisconnectEventListener(new DisconnectEventListener() {
                @Override
                public void onDisconnected() {
                    throw new RuntimeException("No more reconnections for this connection");
                }

                @Override
                public void onReconnected() {
                }
            });
            tb.open(false);

            final DXTickStream stream = getOrCreateStreamWithRetries(tb, GET_STREAM_RETRIES);

            final LoadingOptions options = new LoadingOptions(false);

            loader = stream.createLoader(options); // unchecked

            final TickDBClient finalTb = tb;
            final TickLoader<InstrumentMessage> finalLoader = loader;

            return new CloseableMessageOutput() {
                @Override
                public void send(final InstrumentMessage message) {
                    finalLoader.send(message);
                }

                @Override
                public void close() {
                    try {
                        finalLoader.flush();
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    } finally {
                        Util.closeQuiet(finalLoader);
                        Util.closeQuiet(finalTb);
                    }
                }
            };
        } catch (final Exception e) {
            Util.closeQuiet(loader);
            Util.closeQuiet(tb);
            throw e;
        }
    }

    private DXTickStream getOrCreateStreamWithRetries(final DXTickDB db, final int retriesCount) {
        for (int i = 0; i < retriesCount; ++i) {
            try {
                return getOrCreateStream(db);
            } catch (Throwable t) {
                logger.warning(() -> "Failed to get or create stream '" + streamKey + "'. Retrying...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        throw new RuntimeException("Failed to get or create stream'" + streamKey + "'. Number of retries exceeded.");
    }

    private DXTickStream getOrCreateStream(final DXTickDB tb) {
        final DXTickStream stream = tb.getStream(streamKey);
        if (stream != null) {
            return stream;
        }

        logger.info(() -> "Cannot find the stream '" + streamKey + "'. Preparing a new one...");

        final StreamOptions options = new StreamOptions();
        options.name = streamKey;
        options.description = "Stream " + streamKey;
        options.setPolymorphic(types);

        return tb.createStream(streamKey, options);
    }
}

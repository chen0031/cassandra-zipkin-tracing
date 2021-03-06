
package com.thelastpickle.cassandra.tracing;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.ClientTracer;
import com.github.kristofa.brave.EmptySpanCollectorMetricsHandler;
import com.github.kristofa.brave.FixedSampleRateTraceFilter;
import com.github.kristofa.brave.ServerTracer;
import com.github.kristofa.brave.ServerSpanThreadBinder;
import com.github.kristofa.brave.SpanCollector;
import com.github.kristofa.brave.TraceFilter;
import com.github.kristofa.brave.http.HttpSpanCollector;
import com.google.common.collect.ImmutableMap;
import com.twitter.zipkin.gen.Span;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.tracing.TraceState;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.UUIDGen;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.List;

public final class ZipkinTracing extends Tracing
{
    public static final String ZIPKIN_TRACE_HEADERS = "zipkin";

    private static final Logger logger = LoggerFactory.getLogger(ZipkinTracing.class);

    private static final String HTTP_SPAN_COLLECTOR_HOST = System.getProperty("ZipkinTracing.httpCollectorHost", "127.0.0.1");
    private static final String HTTP_SPAN_COLLECTOR_PORT = System.getProperty("ZipkinTracing.httpCollectorPort", "9411");
    private static final String HTTP_COLLECTOR_URL = "http://" + HTTP_SPAN_COLLECTOR_HOST + ':' + HTTP_SPAN_COLLECTOR_PORT;

    private volatile SpanCollector spanCollector
            = HttpSpanCollector.create(HTTP_COLLECTOR_URL, new EmptySpanCollectorMetricsHandler());
            //= KafkaSpanCollector.create("127.0.0.1:9092", new EmptySpanCollectorMetricsHandler());

    private final int SAMPLE_RATE = 1;

    private final List<TraceFilter> traceFilters
            // Sample rate = 1 means every request will get traced.
            = Collections.singletonList(new FixedSampleRateTraceFilter(SAMPLE_RATE));

    private final Brave brave = new Brave
            .Builder( "c*:" + DatabaseDescriptor.getClusterName() + ":" + FBUtilities.getBroadcastAddress().getHostName())
            .spanCollector(spanCollector)
            .traceFilters(traceFilters)
            .build();

    private final ServerSpanThreadBinder serverSpanThreadBinder;

    public ZipkinTracing()
    {
        this.serverSpanThreadBinder = brave.serverSpanThreadBinder();
    }

    ClientTracer getClientTracer()
    {
        return brave.clientTracer();
    }

    private ServerTracer getServerTracer()
    {
        return brave.serverTracer();
    }

    // defensive override, see CASSANDRA-11706
    @Override
    public UUID newSession(UUID sessionId, Map<String,ByteBuffer> customPayload)
    {
        return newSession(sessionId, TraceType.QUERY, customPayload);
    }

    @Override
    protected UUID newSession(UUID sessionId, TraceType traceType, Map<String,ByteBuffer> customPayload)
    {
        ByteBuffer bb = null != customPayload ? customPayload.get(ZIPKIN_TRACE_HEADERS) : null;
        if (null != bb)
        {
            if (isValidHeaderLength(bb.limit()))
            {
                getServerTracer().setStateCurrentTrace(
                        bb.getLong(),
                        bb.getLong(),
                        24 <= bb.limit() ? bb.getLong() : null,
                        traceType.name());
            }
            else
            {
                logger.error("invalid customPayload in {}", ZIPKIN_TRACE_HEADERS);
                getServerTracer().setStateUnknown(traceType.name());
            }
        }
        else
        {
            getServerTracer().setStateUnknown(traceType.name());
        }
        return super.newSession(sessionId, traceType, customPayload);
    }

    @Override
    protected void stopSessionImpl()
    {
        ZipkinTraceState state = (ZipkinTraceState) get();
        if (state != null)
        {
            state.close();
            getServerTracer().setServerSend();
            getServerTracer().clearCurrentSpan();
        }
    }

    @Override
    public void doneWithNonLocalSession(TraceState s)
    {
        ZipkinTraceState state = (ZipkinTraceState) s;
        state.close();
        getServerTracer().setServerSend();
        getServerTracer().clearCurrentSpan();
        super.doneWithNonLocalSession(state);
    }

    @Override
    public TraceState begin(String request, InetAddress client, Map<String, String> parameters)
    {
        getServerTracer().submitBinaryAnnotation("client", client.toString());
        getServerTracer().submitBinaryAnnotation("request", request);
        return get();
    }

    @Override
    public TraceState initializeFromMessage(final MessageIn<?> message)
    {
        byte [] bytes = message.parameters.get(ZIPKIN_TRACE_HEADERS);

        assert null == bytes || 16 == bytes.length || 24 == bytes.length
                : "invalid customPayload in " + ZIPKIN_TRACE_HEADERS;

        if (null != bytes)
        {
            if (isValidHeaderLength(bytes.length))
            {
                ByteBuffer bb = ByteBuffer.wrap(bytes);

                getServerTracer().setStateCurrentTrace(
                        bb.getLong(),
                        bb.getLong(),
                        24 <= bb.limit() ? bb.getLong() : null,
                        message.getMessageType().name());
            }
            else
            {
                logger.error("invalid customPayload in {}", ZIPKIN_TRACE_HEADERS);
            }
        }
        return super.initializeFromMessage(message);
    }

    @Override
    public Map<String, byte[]> getTraceHeaders()
    {
        assert isTracing();
        Span span = brave.clientSpanThreadBinder().getCurrentClientSpan();

        return ImmutableMap.<String, byte[]>builder()
                .putAll(super.getTraceHeaders())
                .put(
                        ZIPKIN_TRACE_HEADERS,
                        ByteBuffer.allocate(24)
                                .putLong(span.getTrace_id())
                                .putLong(span.getId())
                                .putLong(span.getParent_id())
                                .array())
                .build();
    }

    @Override
    public void trace(final ByteBuffer sessionId, final String message, final int ttl)
    {
        UUID sessionUuid = UUIDGen.getUUID(sessionId);
        TraceState state = Tracing.instance.get(sessionUuid);
        state.trace(message);
    }

    @Override
    protected TraceState newTraceState(InetAddress coordinator, UUID sessionId, TraceType traceType)
    {
        getServerTracer().setServerReceived();
        getServerTracer().submitBinaryAnnotation("sessionId", sessionId.toString());
        getServerTracer().submitBinaryAnnotation("coordinator", coordinator.toString());
        getServerTracer().submitBinaryAnnotation("started_at", Instant.now().toString());

        return new ZipkinTraceState(
                brave,
                coordinator,
                sessionId,
                traceType,
                serverSpanThreadBinder.getCurrentServerSpan());
    }

    private static boolean isValidHeaderLength(int length)
    {
        return 16 == length || 24 == length;
    }
}

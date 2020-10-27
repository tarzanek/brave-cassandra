/*
 * Copyright 2017-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.scylla;

import brave.Span;
import brave.SpanCustomizer;
import brave.Tracer;
import brave.internal.Nullable;
import brave.propagation.B3SingleFormat;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.scylla.dao.ScyllaEvent;
import com.datastax.driver.mapping.Result;
import io.netty.util.concurrent.FastThreadLocal;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static brave.Span.Kind.SERVER;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This creates Zipkin server spans for incoming cassandra requests. Spans are created when there's
 * a tracing component available, and the incoming payload is not explicitly unsampled.
 *
 * <h3>Configuring a tracing component</h3>
 * <p>
 * If the system property "zipkin.http_endpoint" is set, a basic tracing component is setup.
 *
 * <p>Otherwise, {@link brave.Tracing#current()} is used. This relies on external bootstrapping of
 * {@link brave.Tracing}.
 *
 * <p>Alternatively, you can subclass this and fix configuration to your favorite mechanism.
 */
public class ScyllaTracing  {

    public brave.Tracing tracing;

    public ScyllaTracing(brave.Tracing tracing) { // subclassable to pin configuration
        this.tracing = tracing;
    }

    public ScyllaTracing() {
        String endpoint = System.getProperty("zipkin.http_endpoint");
        if (endpoint == null) {
            endpoint="http://127.0.0.1:9411/api/v2/spans";
        }
        AsyncZipkinSpanHandler zipkinSpanHandler =
                AsyncZipkinSpanHandler.newBuilder(URLConnectionSender.create(endpoint)).build();

        tracing = brave.Tracing.newBuilder()
                .localServiceName(System.getProperty("zipkin.service_name", "scylla"))
                .addSpanHandler(zipkinSpanHandler)
                .build();
    }

    protected final ConcurrentMap<UUID, ZipkinTraceState> sessions = new ConcurrentHashMap<>();
    private final FastThreadLocal<ZipkinTraceState> state = new FastThreadLocal<>();

    public ZipkinTraceState get()
    {
        return state.get();
    }

    public void set(final ZipkinTraceState tls)
    {
        state.set(tls);
    }

    /**
     * When tracing is enabled and available, this tries to extract trace keys from the custom
     * payload. If that's possible, it re-uses the trace identifiers and starts a server span.
     * Otherwise, a new trace is created.
     */
    protected final UUID newSession(
            UUID sessionId, Map<String, ByteBuffer> customPayload, Long startAt, String command) {
        Tracer tracer = tracing.tracer();
        Span span = spanFromPayload(tracer, customPayload).kind(SERVER);
        if (startAt != null) {
            span.start(startAt);
        }
        if (command!=null) {
            span.name(command);
        }

// override instead of call from super as otherwise we cannot store a reference to the span
        assert get() == null;
        ZipkinTraceState state = new ZipkinTraceState(sessionId);
        set(state);
        sessions.put(sessionId, state);
        return sessionId;
    }

    /**
     * Represents a span from Scylla system_traces.events.
     */
    public static class ScyllaSpan {
        public final long id;
        public ScyllaSpan parent = null;
        public List<ScyllaSpan> children = new ArrayList<ScyllaSpan>();
        public long startTimestamp = Long.MAX_VALUE, endTimestamp = 0;
        public ScyllaSpan(Long id) {
            this.id = id;
        }
    }

    /**
     * Queries Scylla for a session's trace events and creates the corresponding graph of ScyllaSpan objects.
     * The graph is represented as a map from id to its node.
     */
    public static Map<Long, ScyllaSpan> makeSpanGraph(UUID sessionId) {
        HashMap<Long, ScyllaSpan> graph = new HashMap<Long, ScyllaSpan>();
        Result<ScyllaEvent> scyllaEvents = ScyllaTracesLoader.getScyllaEvents(sessionId);
        for (ScyllaEvent e : scyllaEvents) {
            Long id = e.getScylla_span_id();
            Long parentId = e.getScylla_parent_id();
            graph.putIfAbsent(id, new ScyllaSpan(id));
            graph.putIfAbsent(parentId, new ScyllaSpan(parentId));
            ScyllaSpan child = graph.get(id), parent = graph.get(parentId);
            child.parent = parent;
            parent.children.add(child);
            long start = e.getEvent_id().timestamp() / 10; // Zipkin takes timestamps in microseconds.
            child.startTimestamp = Math.min(child.startTimestamp, start);
            // Scylla doesn't record when the event ended.  Assume it ended after 1us and see if this is the last event
            // in the span.
            child.endTimestamp = Math.max(child.endTimestamp, start + 1);
        }
        return graph;
    }

    /**
     * Turns Scylla spans into Brave spans.
     */
    public static Map<Long, Span> makeSpans(Map<Long, ScyllaSpan> graph, Tracer tracer) {
        HashMap<Long, Span> spans = new HashMap<Long, Span>();
        // The ScyllaSpan graph is a tree, so it can be sorted topologically by simple traversal from root.
        ScyllaSpan root = graph.get(Long.valueOf(0)); // Scylla's tables use id 0 for fatherless spans; this translates to artificial root.
        ArrayList<ScyllaSpan> workList = new ArrayList<ScyllaSpan>(root.children);
        while (!workList.isEmpty()) {
            ScyllaSpan node = workList.remove(0);
            workList.addAll(node.children);
            spans.put(node.id,
                    (node.parent.id == 0) ? tracer.newTrace() : tracer.newChild(spans.get(node.parent.id).context()));
        }
        return spans;
    }

    /**
     * This extracts the RPC span encoded in the custom payload, or starts a new trace
     */
    Span spanFromPayload(Tracer tracer, @Nullable Map<String, ByteBuffer> payload) {
        ByteBuffer b3 = payload != null ? payload.get("b3") : null;
        if (b3 == null) return tracer.nextSpan();
        TraceContextOrSamplingFlags extracted = B3SingleFormat.parseB3SingleFormat(UTF_8.decode(b3));
        if (extracted == null) return tracer.nextSpan();
        return tracer.nextSpan(extracted);
    }

    public final ZipkinTraceState begin(
            String request, InetAddress client, Map<String, String> parameters, Long startAt) {
        ZipkinTraceState state = ((ZipkinTraceState) get());
//        span.remoteIpAndPort(client.getHostAddress(), 0);
        return state;
    }

    protected void parseRequest(
            ZipkinTraceState state, String request, Map<String, String> parameters, SpanCustomizer customizer) {
//        customizer.name(parseSpanName(state, request));
        customizer.tag(CassandraTraceKeys.CASSANDRA_REQUEST, request);
//        customizer.tag(CassandraTraceKeys.CASSANDRA_SESSION_ID, state.sessionId.toString());
    }

    protected final ZipkinTraceState newTraceState(
            UUID sessionId) {
        throw new AssertionError();
    }

    public final void trace(ByteBuffer sessionId, String message, int ttl) {
// not current tracing outbound messages
    }

    static final class ZipkinTraceState  {
        ZipkinTraceState(UUID sessionId) {
        }

        protected void traceImplTS(String message, Long ts) {
            //incoming.annotate(ts, message); // skip creating local spans for now
        }
    }
}

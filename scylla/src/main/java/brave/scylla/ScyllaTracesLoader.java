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
import brave.scylla.dao.ScyllaEvent;
import brave.scylla.dao.ScyllaSession;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.datastax.driver.mapping.Mapper;
import com.datastax.driver.mapping.MappingManager;
import com.datastax.driver.mapping.Result;

import java.net.InetAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ScyllaTracesLoader {

    static Cluster cluster = Cluster.builder().addContactPoints("localhost").build();
    static Session session = cluster.connect("system_traces");
    static MappingManager manager = new MappingManager(session);

    static Mapper<ScyllaSession> mapperSession = manager.mapper(ScyllaSession.class);
    static Mapper<ScyllaEvent> mapperEvent = manager.mapper(ScyllaEvent.class);

    static PreparedStatement selectEvents = session.prepare("SELECT * FROM system_traces.events where session_id=?");

    static ScyllaTracing tracing;

    public static void selectSessions(ScyllaTracing tracing) {
        System.out.print("\n\nFetching sessions ...");
        ResultSet results = session.execute("SELECT * FROM system_traces.sessions");
        Result<ScyllaSession> scyllaSessions = mapperSession.map(results);
        for (ScyllaSession s : scyllaSessions) {
//  session_id | client | command | coordinator | duration | parameters | request | request_size | response_size | started_at
            UUID session_id = s.getSession_id();
            String command = s.getCommand();
            Map<String,String> parameters = s.getParameters();
            String query = parameters.get("query");
            InetAddress client = s.getClient();
            Date started_at_row = s.getStarted_at();
            Long started_at = started_at_row.getTime()*1000;
            tracing.newSession(session_id, new HashMap<>(),started_at, query);
            // create spans
            Map<Long, ScyllaTracing.ScyllaSpan> scyllaSpans = ScyllaTracing.makeSpanGraph(session_id);
            Map<Long, Span> braveSpans = ScyllaTracing.makeSpans(scyllaSpans, tracing.tracing.tracer());
            // add each span's start, name, kind, remote endpoint
            for (Map.Entry<Long, Span> bspan : braveSpans.entrySet()) {
                bspan.getValue().name(query).kind(Span.Kind.SERVER).remoteIpAndPort(client.getHostAddress(), 0);
                bspan.getValue().start(scyllaSpans.get(bspan.getKey()).startTimestamp);
            }
            ScyllaTracing.ZipkinTraceState trace =
                    tracing.begin(command,client, new HashMap<>(),started_at);
            // annotate spans from events
            selectEvents(session_id, trace, started_at, braveSpans);
            // finish each span
            long traceId = 0;
            for (Map.Entry<Long, Span> bspan : braveSpans.entrySet()) {
                bspan.getValue().finish(scyllaSpans.get(bspan.getKey()).endTimestamp);
                traceId = bspan.getValue().context().traceId();
            }
            // print the Zipkin traceId so the user can search for it.
            System.out.println("Generating Zipkin traceId " + Long.toHexString(traceId));
        }
    }

    /*
    trace_id = session_id
6:45
ts_uuid = event_id of the event that begins the span
6:45
id = scylla_span_id (edited)
6:46
trace_id_high = <as in the comment> since we use a standard UUID it's going to be non-empty.
6:47
parent_id = scylla_parent_id
6:50
kind = <dunno, may be the command>?
6:50
span = <may be the same as 'kind'>
6:50
ts = <a timestamp from  ts_uuid>
6:53
duration=<of the whole span: ts_last_anotation - ts_of_the_first_anotation (based on the current scylla instrumentation)> (edited)
6:54
l_ep, r_ep = source and destination IPs
6:54
anotations = <that's the interesting part: these are all traces from this span>
6:55
tags=??
6:55
shared, debug=?
     */


    public static void selectEvents(UUID sessionId, ScyllaTracing.ZipkinTraceState state, Long startAt, Map<Long, Span> spans) {
        System.out.print("\n\nFetching events for session: "+sessionId+"  ...");
        Result<ScyllaEvent> scyllaEvents = getScyllaEvents(sessionId);
        Long ts = startAt;
        for (ScyllaEvent e : scyllaEvents) {
//   session_id | event_id | activity | source | scylla_parent_id | scylla_span_id | source_elapsed | thread
            String activity = e.getActivity();
            Integer source_elapsed = e.getSource_elapsed();
            long finishts = startAt+source_elapsed;
            state.traceImplTS(activity, finishts);
            ts=ts+source_elapsed;
            spans.get(e.getScylla_span_id()).annotate(finishts, activity);
        }
    }

    public static Result<ScyllaEvent> getScyllaEvents(UUID sessionId) {
        return mapperEvent.map(session.execute(selectEvents.bind(sessionId)));
    }

    //Indexes:

    // sessions_time_idx
    // node_slow_log_time_idx
    // minute | started_at | session_id | start_time | node_ip | shard
// SELECT * from system_traces.sessions_time_idx where minutes in ('2016-09-07 16:56:00-0700') and started_at > '2016-09-07 16:56:30-0700';

// system_traces.node_slow_log table ???
    // start_time | date | node_ip | shard | command | duration | parameters | session_id | source ip | table_names | username

    public static void main(String[] args)  {

        System.setProperty("zipkin.http_endpoint", "http://127.0.0.1:9411/api/v2/spans"); //
        System.setProperty("zipkin.service_name", "scylla");
        tracing = new ScyllaTracing();

        selectSessions(tracing);

        tracing.tracing.close();
        cluster.close();

//        System.exit(0);

    }

}

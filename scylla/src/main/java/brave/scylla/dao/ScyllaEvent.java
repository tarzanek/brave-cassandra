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
package brave.scylla.dao;

import com.datastax.driver.mapping.annotations.ClusteringColumn;
import com.datastax.driver.mapping.annotations.Column;
import com.datastax.driver.mapping.annotations.PartitionKey;
import com.datastax.driver.mapping.annotations.Table;

import java.net.InetAddress;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
CREATE TABLE system_traces.events (
 session_id uuid,
 event_id timeuuid,
 activity text,
 scylla_parent_id bigint,
 scylla_span_id bigint,
 source inet,
 source_elapsed int,
 thread text,
 PRIMARY KEY (session_id, event_id)
) WITH CLUSTERING ORDER BY (event_id ASC)
 AND bloom_filter_fp_chance = 0.01
 AND caching = {'keys': 'ALL', 'rows_per_partition': 'ALL'}
 AND comment = ''
 AND compaction = {'class': 'SizeTieredCompactionStrategy'}
 AND compression = {'sstable_compression': 'org.apache.cassandra.io.compress.LZ4Compressor'}
 AND crc_check_chance = 1.0
 AND dclocal_read_repair_chance = 0.1
 AND default_time_to_live = 86400
 AND gc_grace_seconds = 864000
 AND max_index_interval = 2048
 AND memtable_flush_period_in_ms = 0
 AND min_index_interval = 128
 AND read_repair_chance = 0.0
 AND speculative_retry = '99.0PERCENTILE';
 */

@Table(keyspace = "system_traces", name = "events",
        readConsistency = "QUORUM",
        writeConsistency = "QUORUM",
        caseSensitiveKeyspace = false,
        caseSensitiveTable = false)
public class ScyllaEvent {
    @PartitionKey
    private UUID session_id;
    @ClusteringColumn
    private UUID event_id; //TimeUUIDType
    @Column
    private String activity;
    @Column
    private Long scylla_parent_id;
    @Column
    private Long scylla_span_id;
    @Column
    private InetAddress source;
    @Column
    private Integer source_elapsed;
    @Column
    private String thread;

    public UUID getSession_id() {
        return session_id;
    }

    public void setSession_id(UUID session_id) {
        this.session_id = session_id;
    }

    public UUID getEvent_id() {
        return event_id;
    }

    public void setEvent_id(UUID event_id) {
        this.event_id = event_id;
    }

    public String getActivity() {
        return activity;
    }

    public void setActivity(String activity) {
        this.activity = activity;
    }

    public Long getScylla_parent_id() {
        return scylla_parent_id;
    }

    public void setScylla_parent_id(Long scylla_parent_id) {
        this.scylla_parent_id = scylla_parent_id;
    }

    public Long getScylla_span_id() {
        return scylla_span_id;
    }

    public void setScylla_span_id(Long scylla_span_id) {
        this.scylla_span_id = scylla_span_id;
    }

    public InetAddress getSource() {
        return source;
    }

    public void setSource(InetAddress source) {
        this.source = source;
    }

    public Integer getSource_elapsed() {
        return source_elapsed;
    }

    public void setSource_elapsed(Integer source_elapsed) {
        this.source_elapsed = source_elapsed;
    }

    public String getThread() {
        return thread;
    }

    public void setThread(String thread) {
        this.thread = thread;
    }
}

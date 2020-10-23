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

import com.datastax.driver.mapping.annotations.Column;
import com.datastax.driver.mapping.annotations.PartitionKey;
import com.datastax.driver.mapping.annotations.Table;

import java.net.InetAddress;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
CREATE TABLE system_traces.sessions (
 session_id uuid PRIMARY KEY,
 client inet,
 command text,
 coordinator inet,
 duration int,
 parameters map<text, text>,
 request text,
 request_size int,
 response_size int,
 started_at timestamp
) WITH bloom_filter_fp_chance = 0.01
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

@Table(keyspace = "system_traces", name = "sessions",
        readConsistency = "QUORUM",
        writeConsistency = "QUORUM",
        caseSensitiveKeyspace = false,
        caseSensitiveTable = false)
public class ScyllaSession {
    @PartitionKey
    private UUID session_id;
    @Column
    private InetAddress client;
    @Column
    private String command;
    @Column
    private InetAddress coordinator;
    @Column
    private Integer duration;
    @Column
    private Map<String, String> parameters;
    @Column
    private String request;
    @Column
    private Integer request_size;
    @Column
    private Integer response_size;
    @Column
    private Date started_at;

    public UUID getSession_id() {
        return session_id;
    }

    public void setSession_id(UUID session_id) {
        this.session_id = session_id;
    }

    public InetAddress getClient() {
        return client;
    }

    public void setClient(InetAddress client) {
        this.client = client;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public InetAddress getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(InetAddress coordinator) {
        this.coordinator = coordinator;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public Integer getRequest_size() {
        return request_size;
    }

    public void setRequest_size(Integer request_size) {
        this.request_size = request_size;
    }

    public Integer getResponse_size() {
        return response_size;
    }

    public void setResponse_size(Integer response_size) {
        this.response_size = response_size;
    }

    public Date getStarted_at() {
        return started_at;
    }

    public void setStarted_at(Date started_at) {
        this.started_at = started_at;
    }
}

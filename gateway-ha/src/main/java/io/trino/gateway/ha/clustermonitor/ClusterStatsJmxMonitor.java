/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.clustermonitor;

import com.fasterxml.jackson.databind.JsonNode;
import io.airlift.http.client.BasicAuthRequestFilter;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpRequestFilter;
import io.airlift.http.client.JsonResponseHandler;
import io.airlift.http.client.Request;
import io.airlift.http.client.UnexpectedResponseException;
import io.airlift.log.Logger;
import io.trino.gateway.ha.config.BackendStateConfiguration;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.JsonResponseHandler.createJsonResponseHandler;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.json.JsonCodec.jsonCodec;
import static java.util.Objects.requireNonNull;

public class ClusterStatsJmxMonitor
        implements ClusterStatsMonitor
{
    private static final Logger log = Logger.get(ClusterStatsJmxMonitor.class);
    private static final JsonResponseHandler<JsonNode> JMX_JSON_RESPONSE_HANDLER = createJsonResponseHandler(
            jsonCodec(JsonNode.class));
    private static final String JMX_PATH = "/v1/jmx/mbean";

    private final String username;
    private final String password;
    private final HttpClient client;

    public ClusterStatsJmxMonitor(HttpClient client, BackendStateConfiguration backendStateConfiguration)
    {
        this.client = requireNonNull(client, "client is null");
        this.username = backendStateConfiguration.getUsername();
        this.password = backendStateConfiguration.getPassword();
    }

    @Override
    public ClusterStats monitor(ProxyBackendConfiguration backend)
    {
        log.info("Monitoring cluster stats for backend: %s", backend.getProxyTo());
        ClusterStats.Builder clusterStats = ClusterStatsMonitor.getClusterStatsBuilder(backend);

        processJmxStats(backend, "trino.metadata:name=DiscoveryNodeManager", this::processDiscoveryNodeManagerStats,
                clusterStats);
        processJmxStats(backend, "trino.execution:name=QueryManager", this::processQueryManagerStats, clusterStats);

        clusterStats.proxyTo(backend.getProxyTo())
                .externalUrl(backend.getExternalUrl())
                .routingGroup(backend.getRoutingGroup());

        ClusterStats stats = clusterStats.build();
        log.debug("Completed monitoring for backend: %s. Stats: %s", backend.getProxyTo(), stats);
        return stats;
    }

    private void processJmxStats(ProxyBackendConfiguration backend, String mbeanName,
            JmxStatProcessor processor, ClusterStats.Builder clusterStats)
    {
        queryJmx(backend, mbeanName)
                .ifPresentOrElse(
                        response -> processor.process(response, clusterStats),
                        () -> clusterStats.trinoStatus(TrinoStatus.UNHEALTHY));
    }

    private void processDiscoveryNodeManagerStats(JmxResponse response, ClusterStats.Builder clusterStats)
    {
        try {
            response.attributes().stream()
                    .filter(attr -> "ActiveNodeCount".equals(attr.name()))
                    .findFirst()
                    .ifPresent(attr -> {
                        int activeNodes = attr.value();
                        TrinoStatus trinoStatus = activeNodes > 0 ? TrinoStatus.HEALTHY : TrinoStatus.UNHEALTHY;
                        clusterStats.numWorkerNodes(activeNodes)
                                .trinoStatus(trinoStatus);
                        log.debug("Processed DiscoveryNodeManager: ActiveNodeCount = %d, Health = %s",
                                activeNodes, trinoStatus);
                    });
        }
        catch (Exception e) {
            log.error(e, "Error parsing DiscoveryNodeManager stats");
            clusterStats.trinoStatus(TrinoStatus.UNHEALTHY);
        }
    }

    private void processQueryManagerStats(JmxResponse response, ClusterStats.Builder clusterStats)
    {
        try {
            Map<String, Integer> stats = response.attributes().stream()
                    .filter(attr -> "QueuedQueries".equals(attr.name()) ||
                            "RunningQueries".equals(attr.name()))
                    .collect(Collectors.toMap(
                            JmxAttribute::name,
                            JmxAttribute::value));

            clusterStats.queuedQueryCount(stats.getOrDefault("QueuedQueries", 0))
                    .runningQueryCount(stats.getOrDefault("RunningQueries", 0));

            log.debug("Processed QueryManager: QueuedQueries = %d, RunningQueries = %d",
                    stats.get("QueuedQueries"),
                    stats.get("RunningQueries"));
        }
        catch (Exception e) {
            log.error(e, "Error parsing QueryManager stats");
        }
    }

    private Optional<JmxResponse> queryJmx(ProxyBackendConfiguration backend, String mbeanName)
    {
        requireNonNull(backend, "backend is null");
        requireNonNull(mbeanName, "mbeanName is null");

        String jmxUrl = backend.getProxyTo();
        Request request;

        Request preparedRequest = prepareGet()
                .setUri(uriBuilderFrom(URI.create(jmxUrl))
                        .appendPath(JMX_PATH)
                        .appendPath(mbeanName)
                        .build())
                .addHeader("X-Trino-User", username)
                .build();

        boolean isHttps = preparedRequest.getUri().getScheme().equalsIgnoreCase("https");

        if (isHttps) {
            HttpRequestFilter filter = new BasicAuthRequestFilter(username, password);
            request = filter.filterRequest(preparedRequest);
        }
        else {
            request = preparedRequest;
        }

        log.debug("Querying JMX at %s for %s", request.getUri(), mbeanName);

        try {
            JsonNode response = client.execute(request, JMX_JSON_RESPONSE_HANDLER);
            return Optional.ofNullable(response).map(JmxResponse::fromJson);
        }
        catch (UnexpectedResponseException e) {
            log.error(e, "Failed to fetch JMX data for %s, response code: %d", mbeanName, e.getStatusCode());
            return Optional.empty();
        }
        catch (Exception e) {
            log.error(e, "Exception while querying JMX at %s", jmxUrl);
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface JmxStatProcessor
    {
        void process(JmxResponse response, ClusterStats.Builder clusterStats);
    }
}
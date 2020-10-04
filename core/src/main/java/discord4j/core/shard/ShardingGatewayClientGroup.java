/*
 * This file is part of Discord4J.
 *
 * Discord4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Discord4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Discord4J. If not, see <http://www.gnu.org/licenses/>.
 */

package discord4j.core.shard;

import discord4j.discordjson.json.gateway.RequestGuildMembers;
import discord4j.gateway.GatewayClient;
import discord4j.gateway.json.GatewayPayload;
import discord4j.gateway.json.ShardGatewayPayload;
import discord4j.store.api.wip.Store;
import discord4j.store.api.wip.action.write.gateway.RequestMembersAction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

class ShardingGatewayClientGroup implements GatewayClientGroupManager {

    private final Map<Integer, GatewayClient> map = new ConcurrentHashMap<>();
    private final int shardCount;
    private final Store store;

    ShardingGatewayClientGroup(int shardCount, Store store) {
        this.shardCount = shardCount;
        this.store = store;
    }

    @Override
    public void add(int key, GatewayClient gatewayClient) {
        map.put(key, gatewayClient);
    }

    @Override
    public void remove(int key) {
        map.remove(key);
    }

    @Override
    public Optional<GatewayClient> find(int index) {
        return Optional.ofNullable(map.get(index));
    }

    @Override
    public int getShardCount() {
        return shardCount;
    }

    @Override
    public Mono<Void> multicast(GatewayPayload<?> payload) {
        return Flux.fromIterable(map.entrySet())
                .flatMap(entry -> {
                    int shardIndex = entry.getKey();
                    GatewayClient client = entry.getValue();
                    return client.send(Mono.just(payload))
                        .then(Mono.defer(() -> {
                            if (payload.getData() instanceof RequestGuildMembers) {
                                return store.execute(new RequestMembersAction(shardIndex,
                                        (RequestGuildMembers) payload.getData()));
                            }
                            return Mono.empty();
                        }));
                })
                .then();
    }

    public Mono<Void> unicast(ShardGatewayPayload<?> payload) {
        return Mono.justOrEmpty(find(payload.getShardIndex()))
                .flatMap(client -> client.send(Mono.just(payload)))
                .then(Mono.defer(() -> {
                    if (payload.getData() instanceof RequestGuildMembers) {
                        return store.execute(new RequestMembersAction(payload.getShardIndex(),
                            (RequestGuildMembers) payload.getData()));
                    }
                    return Mono.empty();
                }));
    }

    @Override
    public Mono<Void> logout() {
        return Mono.whenDelayError(map.values().stream()
                .map(client -> client.close(false))
                .collect(Collectors.toList()));
    }
}

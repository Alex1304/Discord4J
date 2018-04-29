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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Discord4J.  If not, see <http://www.gnu.org/licenses/>.
 */
package discord4j.core.event.dispatch;

import discord4j.common.json.payload.dispatch.*;
import discord4j.common.json.response.GuildEmojiResponse;
import discord4j.common.json.response.GuildMemberResponse;
import discord4j.common.json.response.UserResponse;
import discord4j.core.DiscordClient;
import discord4j.core.ServiceMediator;
import discord4j.core.StoreHolder;
import discord4j.core.event.domain.guild.*;
import discord4j.core.event.domain.role.RoleCreateEvent;
import discord4j.core.event.domain.role.RoleDeleteEvent;
import discord4j.core.event.domain.role.RoleUpdateEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.bean.*;
import discord4j.core.util.ArrayUtil;
import discord4j.store.util.LongLongTuple2;
import discord4j.store.util.LongObjTuple2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

class GuildDispatchHandlers {

    static Mono<BanEvent> guildBanAdd(DispatchContext<GuildBanAdd> context) {
        User user = new User(context.getServiceMediator(), new UserBean(context.getDispatch().getUser()));
        long guildId = context.getDispatch().getGuildId();

        return Mono.just(new BanEvent(context.getServiceMediator().getClient(), user, guildId));
    }

    static Mono<UnbanEvent> guildBanRemove(DispatchContext<GuildBanRemove> context) {
        User user = new User(context.getServiceMediator(), new UserBean(context.getDispatch().getUser()));
        long guildId = context.getDispatch().getGuildId();

        return Mono.just(new UnbanEvent(context.getServiceMediator().getClient(), user, guildId));
    }

    static Mono<GuildCreateEvent> guildCreate(DispatchContext<GuildCreate> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        GuildBean bean = new GuildBean(context.getDispatch().getGuild());

        return serviceMediator.getStoreHolder().getGuildStore()
                .save(bean.getId(), bean)
                .thenReturn(new GuildCreateEvent(serviceMediator.getClient(), new Guild(serviceMediator, bean)));
    }

    static Mono<GuildDeleteEvent> guildDelete(DispatchContext<GuildDelete> context) {
        DiscordClient client = context.getServiceMediator().getClient();
        StoreHolder storeHolder = context.getServiceMediator().getStoreHolder();

        long guildId = context.getDispatch().getGuild().getId();

        Mono<Void> deleteGuild = storeHolder.getGuildStore().delete(guildId);

        return storeHolder.getGuildStore()
                .find(context.getDispatch().getGuild().getId())
                .flatMap(guild -> {
                    Flux<Long> channels = Flux.fromStream(LongStream.of(guild.getChannels()).boxed());
                    Flux<Long> roles = Flux.fromStream(LongStream.of(guild.getRoles()).boxed());
                    Flux<Long> emojis = Flux.fromStream(LongStream.of(guild.getEmojis()).boxed());

                    Mono<Void> deleteTextChannels = storeHolder.getTextChannelStore().delete(channels);
                    Mono<Void> deleteVoiceChannels = storeHolder.getVoiceChannelStore().delete(channels);
                    Mono<Void> deleteCategories = storeHolder.getCategoryStore().delete(channels);
                    Mono<Void> deleteRoles = storeHolder.getRoleStore().delete(roles);
                    Mono<Void> deleteEmojis = storeHolder.getGuildEmojiStore().delete(emojis);
                    Mono<Void> deleteMembers = storeHolder.getMemberStore()
                            .deleteInRange(LongLongTuple2.of(guild.getId(), 0), LongLongTuple2.of(guild.getId(), -1));
                    // TODO delete messages
                    // TODO delete no longer visible users
                    Mono<Void> deleteVoiceStates = storeHolder.getVoiceStateStore()
                            .deleteInRange(LongLongTuple2.of(guild.getId(), 0), LongLongTuple2.of(guild.getId(), -1));

                    return deleteTextChannels
                            .then(deleteVoiceChannels)
                            .then(deleteCategories)
                            .then(deleteRoles)
                            .then(deleteEmojis)
                            .then(deleteMembers)
                            .then(deleteVoiceStates)
                            .thenReturn(guild);
                })
                .flatMap(deleteGuild::thenReturn)
                .map(guild -> new GuildDeleteEvent(client, guildId, new Guild(context.getServiceMediator(), guild)))
                .switchIfEmpty(deleteGuild.thenReturn(new GuildDeleteEvent(client, guildId, null)));
    }

    static Mono<EmojisUpdateEvent> guildEmojisUpdate(DispatchContext<GuildEmojisUpdate> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();

        long[] emojiIds = Arrays.stream(context.getDispatch().getEmojis())
                .mapToLong(GuildEmojiResponse::getId)
                .toArray();

        Stream<LongObjTuple2<GuildEmojiBean>> emojiBeans = Arrays.stream(context.getDispatch().getEmojis())
                .map(GuildEmojiBean::new)
                .map(bean -> LongObjTuple2.of(bean.getId(), bean));

        Mono<Void> updateGuildBean = serviceMediator.getStoreHolder().getGuildStore()
                .find(context.getDispatch().getGuildId())
                .doOnNext(guild -> guild.setEmojis(emojiIds))
                .flatMap(guild -> serviceMediator.getStoreHolder().getGuildStore().save(guild.getId(), guild));

        Mono<Void> saveEmojis = serviceMediator.getStoreHolder().getGuildEmojiStore()
                .saveWithLong(Flux.fromStream(emojiBeans));

        DiscordClient client = context.getServiceMediator().getClient();
        long guildId = context.getDispatch().getGuildId();
        Set<GuildEmoji> emojis = emojiBeans
                .map(LongObjTuple2::getT2)
                .map(bean -> new GuildEmoji(serviceMediator, bean, guildId))
                .collect(Collectors.toSet());

        return updateGuildBean
                .then(saveEmojis)
                .thenReturn(new EmojisUpdateEvent(client, guildId, emojis));
    }

    static Mono<IntegrationsUpdateEvent> guildIntegrationsUpdate(DispatchContext<GuildIntegrationsUpdate> context) {
        return Mono.just(new IntegrationsUpdateEvent(context.getServiceMediator().getClient(),
                context.getDispatch().getGuildId()));
    }

    static Mono<MemberJoinEvent> guildMemberAdd(DispatchContext<GuildMemberAdd> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        long guildId = context.getDispatch().getGuildId();
        GuildMemberResponse response = context.getDispatch().getMember();
        MemberBean bean = new MemberBean(response);

        Mono<Void> addMemberId = serviceMediator.getStoreHolder().getGuildStore()
                .find(guildId)
                .doOnNext(guild -> guild.setMembers(ArrayUtil.add(guild.getMembers(), response.getUser().getId())))
                .flatMap(guild -> serviceMediator.getStoreHolder().getGuildStore().save(guildId, guild));

        Mono<Void> saveMember = serviceMediator.getStoreHolder().getMemberStore()
                .save(LongLongTuple2.of(guildId, response.getUser().getId()), bean);

        Member member = new Member(serviceMediator, bean, new UserBean(response.getUser()), guildId);

        return addMemberId
                .then(saveMember)
                .thenReturn(new MemberJoinEvent(serviceMediator.getClient(), member, guildId));
    }

    static Mono<MemberLeaveEvent> guildMemberRemove(DispatchContext<GuildMemberRemove> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        long guildId = context.getDispatch().getGuildId();
        UserResponse response = context.getDispatch().getUser();

        Mono<Void> removeMemberId = serviceMediator.getStoreHolder().getGuildStore()
                .find(context.getDispatch().getGuildId())
                .doOnNext(guild -> guild.setMembers(ArrayUtil.remove(guild.getMembers(), response.getId())))
                .flatMap(guild -> serviceMediator.getStoreHolder().getGuildStore().save(guildId, guild));

        Mono<Void> deleteMember = serviceMediator.getStoreHolder().getMemberStore()
                .delete(LongLongTuple2.of(context.getDispatch().getGuildId(), context.getDispatch().getUser().getId()));

        User user = new User(serviceMediator, new UserBean(response));

        return removeMemberId
                .then(deleteMember)
                .thenReturn(new MemberLeaveEvent(serviceMediator.getClient(), user, guildId));
    }

    static Mono<MemberChunkEvent> guildMembersChunk(DispatchContext<GuildMembersChunk> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        long guildId = context.getDispatch().getGuildId();

        Flux<Tuple2<LongLongTuple2, MemberBean>> memberPairs = Flux.fromArray(context.getDispatch().getMembers())
                .map(response -> Tuples.of(response, new MemberBean(response)))
                .map(tuple -> Tuples.of(LongLongTuple2.of(guildId, tuple.getT1().getUser().getId()), tuple.getT2()));

        Mono<Void> addMemberIds = serviceMediator.getStoreHolder().getGuildStore()
                .find(guildId)
                .doOnNext(guild -> {
                    long[] ids = Arrays.stream(context.getDispatch().getMembers())
                            .map(GuildMemberResponse::getUser)
                            .mapToLong(UserResponse::getId).toArray();

                    guild.setMembers(ArrayUtil.addAll(guild.getMembers(), ids));
                })
                .flatMap(guild -> serviceMediator.getStoreHolder().getGuildStore().save(guildId, guild));

        Mono<Void> saveMembers = serviceMediator.getStoreHolder().getMemberStore().save(memberPairs);

        Set<Member> members = Arrays.stream(context.getDispatch().getMembers())
                .map(response -> Tuples.of(new MemberBean(response), new UserBean(response.getUser())))
                .map(tuple -> new Member(serviceMediator, tuple.getT1(), tuple.getT2(), guildId))
                .collect(Collectors.toSet());

        return addMemberIds
                .then(saveMembers)
                .thenReturn(new MemberChunkEvent(serviceMediator.getClient(), guildId, members));
    }

    static Mono<MemberUpdateEvent> guildMemberUpdate(DispatchContext<GuildMemberUpdate> context) {
        // TODO
        return Mono.empty();
    }

    static Mono<RoleCreateEvent> guildRoleCreate(DispatchContext<GuildRoleCreate> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        DiscordClient client = serviceMediator.getClient();
        long guildId = context.getDispatch().getGuildId();
        RoleBean bean = new RoleBean(context.getDispatch().getRole());
        Role role = new Role(serviceMediator, bean, guildId);

        Mono<Void> addRoleId = serviceMediator.getStoreHolder().getGuildStore()
                .find(context.getDispatch().getGuildId())
                .doOnNext(guild -> guild.setRoles(ArrayUtil.add(guild.getRoles(), bean.getId())))
                .flatMap(guild -> serviceMediator.getStoreHolder().getGuildStore().save(guild.getId(), guild));

        Mono<Void> saveRole = serviceMediator.getStoreHolder().getRoleStore()
                .save(bean.getId(), bean);

        return addRoleId
                .then(saveRole)
                .thenReturn(new RoleCreateEvent(client, guildId, role));
    }

    static Mono<RoleDeleteEvent> guildRoleDelete(DispatchContext<GuildRoleDelete> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        long guildId = context.getDispatch().getGuildId();
        long roleId = context.getDispatch().getRoleId();

        Mono<Void> removeRoleId = serviceMediator.getStoreHolder().getGuildStore()
                .find(guildId)
                .doOnNext(guild -> guild.setRoles(ArrayUtil.remove(guild.getRoles(), roleId)))
                .flatMap(guild -> serviceMediator.getStoreHolder().getGuildStore().save(guildId, guild));

        Mono<Void> deleteRole = serviceMediator.getStoreHolder().getRoleStore()
                .delete(context.getDispatch().getRoleId());

        // TODO remove role from members

        return removeRoleId
                .then(deleteRole)
                .thenReturn(new RoleDeleteEvent(context.getServiceMediator().getClient(), guildId, roleId));
    }

    static Mono<RoleUpdateEvent> guildRoleUpdate(DispatchContext<GuildRoleUpdate> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        DiscordClient client = serviceMediator.getClient();
        long guildId = context.getDispatch().getGuildId();

        RoleBean bean = new RoleBean(context.getDispatch().getRole());
        Role current = new Role(serviceMediator, bean, guildId);

        Mono<Void> saveNew = serviceMediator.getStoreHolder().getRoleStore().save(bean.getId(), bean);

        return serviceMediator.getStoreHolder().getRoleStore()
                .find(context.getDispatch().getRole().getId())
                .flatMap(saveNew::thenReturn)
                .map(old -> new RoleUpdateEvent(client, current, new Role(serviceMediator, old, guildId)))
                .switchIfEmpty(saveNew.thenReturn(new RoleUpdateEvent(client, current, null)));
    }

    static Mono<GuildUpdateEvent> guildUpdate(DispatchContext<GuildUpdate> context) {
        ServiceMediator serviceMediator = context.getServiceMediator();
        DiscordClient client = serviceMediator.getClient();

        GuildBean bean = new GuildBean(context.getDispatch().getGuild());
        Guild current = new Guild(serviceMediator, bean);

        Mono<Void> saveNew = serviceMediator.getStoreHolder().getGuildStore().save(bean.getId(), bean);

        return serviceMediator.getStoreHolder().getGuildStore()
                .find(context.getDispatch().getGuild().getId())
                .flatMap(saveNew::thenReturn)
                .map(old -> new GuildUpdateEvent(client, current, new Guild(serviceMediator, old)))
                .switchIfEmpty(saveNew.thenReturn(new GuildUpdateEvent(client, current, null)));
    }

}
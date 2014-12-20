/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.internal.cluster.coordinator;

import net.kuujo.copycat.CopycatContext;
import net.kuujo.copycat.cluster.Cluster;
import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.cluster.Member;
import net.kuujo.copycat.cluster.coordinator.ClusterCoordinator;
import net.kuujo.copycat.cluster.coordinator.LocalMemberCoordinator;
import net.kuujo.copycat.cluster.coordinator.MemberCoordinator;
import net.kuujo.copycat.internal.CopycatStateContext;
import net.kuujo.copycat.internal.DefaultCopycatContext;
import net.kuujo.copycat.internal.DefaultCopycatStateContext;
import net.kuujo.copycat.internal.cluster.CoordinatedCluster;
import net.kuujo.copycat.internal.cluster.Router;
import net.kuujo.copycat.internal.cluster.Topics;
import net.kuujo.copycat.internal.util.Services;
import net.kuujo.copycat.protocol.RaftProtocol;
import net.kuujo.copycat.protocol.Request;
import net.kuujo.copycat.protocol.Response;
import net.kuujo.copycat.spi.ExecutionContext;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default cluster coordinator implementation.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class DefaultClusterCoordinator implements ClusterCoordinator {
  private final CopycatContext context;
  private final ClusterConfig config;
  private final LocalMemberCoordinator localMember;
  private final Map<String, MemberCoordinator> remoteMembers = new HashMap<>();
  private final Map<String, CopycatContext> contexts = new ConcurrentHashMap<>();

  public DefaultClusterCoordinator(ClusterConfig config, ExecutionContext context) {
    this.config = config.copy();
    this.localMember = new DefaultLocalMemberCoordinator(config.getLocalMember(), config.getProtocol(), context);
    for (String uri : config.getRemoteMembers()) {
      this.remoteMembers.put(uri, new DefaultRemoteMemberCoordinator(uri, config.getProtocol(), context));
    }
    Map<String, Object> logConfig = new HashMap<>();
    logConfig.put("name", "copycat");
    CopycatStateContext state = new DefaultCopycatStateContext(config, Services.load("copycat.log", logConfig), context);
    this.context = new DefaultCopycatContext(new CoordinatedCluster(0, this, state, new ResourceRouter("copycat"), context), state);
  }

  @Override
  public LocalMemberCoordinator localMember() {
    return localMember;
  }

  @Override
  public MemberCoordinator member(String uri) {
    return remoteMembers.get(uri);
  }

  @Override
  public Collection<MemberCoordinator> remoteMembers() {
    return Collections.unmodifiableCollection(remoteMembers.values());
  }

  @Override
  public CompletableFuture<CopycatContext> createResource(String name) {
    ByteBuffer entry = ByteBuffer.allocate(8 + name.getBytes().length);
    entry.putInt(1);
    entry.putInt(name.getBytes().length);
    entry.put(name.getBytes());
    entry.rewind();
    return context.sync(entry).thenApplyAsync(buffer -> {
      int result = buffer.getInt();
      if (result == 0) {
        return null;
      } else {
        CopycatContext context = contexts.get(name);
        if (context == null) {
          synchronized (contexts) {
            context = contexts.get(name);
            if (context == null) {
              context = createContext(name);
              contexts.put(name, context);
            }
          }
        }
        return context;
      }
    });
  }

  @Override
  public CompletableFuture<CopycatContext> getResource(String name) {
    ByteBuffer entry = ByteBuffer.allocate(8 + name.getBytes().length);
    entry.putInt(0);
    entry.putInt(name.getBytes().length);
    entry.put(name.getBytes());
    entry.rewind();
    return context.sync(entry).thenApplyAsync(buffer -> {
      int result = buffer.getInt();
      if (result == 0) {
        return null;
      } else {
        CopycatContext context = contexts.get(name);
        if (context == null) {
          synchronized (contexts) {
            context = contexts.get(name);
            if (context == null) {
              context = createContext(name);
              contexts.put(name, context);
            }
          }
        }
        return context;
      }
    }, context);
  }

  @Override
  public CompletableFuture<Void> deleteResource(String name) {
    ByteBuffer entry = ByteBuffer.allocate(8 + name.getBytes().length);
    entry.putInt(-1);
    entry.putInt(name.getBytes().length);
    entry.put(name.getBytes());
    return context.commit(entry).thenApplyAsync(result -> null, context);
  }

  /**
   * Creates a new Copycat context.
   *
   * @param name The context name.
   * @return The created context.
   */
  private CopycatContext createContext(String name) {
    ExecutionContext executor = ExecutionContext.create();
    Map<String, Object> logConfig = new HashMap<>(1);
    logConfig.put("name", name);
    CopycatStateContext state = new DefaultCopycatStateContext(config, Services.load("copycat.log", logConfig), executor);
    CoordinatedCluster cluster = new CoordinatedCluster(name.hashCode(), this, state, new ResourceRouter(name), executor);
    return new DefaultCopycatContext(cluster, state);
  }

  /**
   * Consumes messages from the log.
   */
  private ByteBuffer consume(Long index, ByteBuffer buffer) {
    buffer.rewind();
    int type = buffer.getInt();
    byte[] nameBytes;
    String name;
    ByteBuffer result;

    switch (type) {
      case 0: // get
        nameBytes = new byte[buffer.getInt()];
        buffer.get(nameBytes);
        name = new String(nameBytes);
        result = ByteBuffer.allocate(4);
        synchronized (contexts) {
          result.putInt(contexts.containsKey(name) ? 1 : 0);
        }
        break;
      case 1: // create
        nameBytes = new byte[buffer.getInt()];
        buffer.get(nameBytes);
        name = new String(nameBytes);
        result = ByteBuffer.allocate(4);
        synchronized (contexts) {
          if (!contexts.containsKey(name)) {
            contexts.put(name, createContext(name));
            result.putInt(1);
          } else {
            result.putInt(0);
          }
        }
        break;
      case -1: // delete
        nameBytes = new byte[buffer.getInt()];
        buffer.get(nameBytes);
        name = new String(nameBytes);
        result = ByteBuffer.allocate(4);
        synchronized (contexts) {
          CopycatContext context = contexts.remove(name);
          if (context != null) {
            try {
              context.close().get();
              context.delete().get();
            } catch (Exception e) {
            }
            result.putInt(1);
          } else {
            result.putInt(0);
          }
        }
        break;
      default:
        throw new UnsupportedOperationException("Invalid command");
    }
    result.rewind();
    return result;
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<Void> open() {
    CompletableFuture<Void>[] futures = new CompletableFuture[remoteMembers.size() + 1];
    futures[0] = localMember.open();
    int i = 1;
    for (MemberCoordinator remoteMember : remoteMembers.values()) {
      futures[i++] = remoteMember.open();
    }
    context.consumer(this::consume);
    return CompletableFuture.allOf(futures).thenCompose((v) -> context.open());
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<Void> close() {
    CompletableFuture<Void>[] futures = new CompletableFuture[remoteMembers.size() + 1];
    futures[0] = localMember.close();
    int i = 1;
    for (MemberCoordinator remoteMember : remoteMembers.values()) {
      futures[i++] = remoteMember.close();
    }
    return context.close().thenCompose((v) -> CompletableFuture.allOf(futures));
  }

  /**
   * Resource router.
   */
  private static class ResourceRouter implements Router {
    private final String name;

    private ResourceRouter(String name) {
      this.name = name;
    }

    @Override
    public void createRoutes(Cluster cluster, RaftProtocol protocol) {
      cluster.localMember().registerHandler(Topics.PING, protocol::ping);
      cluster.localMember().registerHandler(Topics.POLL, protocol::poll);
      cluster.localMember().registerHandler(Topics.APPEND, protocol::append);
      cluster.localMember().registerHandler(Topics.SYNC, protocol::sync);
      cluster.localMember().registerHandler(Topics.COMMIT, protocol::commit);
      protocol.pingHandler(request -> handleOutboundRequest(Topics.PING, request, cluster));
      protocol.pollHandler(request -> handleOutboundRequest(Topics.POLL, request, cluster));
      protocol.appendHandler(request -> handleOutboundRequest(Topics.APPEND, request, cluster));
      protocol.syncHandler(request -> handleOutboundRequest(Topics.SYNC, request, cluster));
      protocol.commitHandler(request -> handleOutboundRequest(Topics.COMMIT, request, cluster));
    }

    /**
     * Handles an outbound protocol request.
     */
    private <T extends Request, U extends Response> CompletableFuture<U> handleOutboundRequest(
      String topic, T request, Cluster cluster) {
      Member member = cluster.member(request.member());
      if (member != null) {
        return member.send(topic, request);
      }
      CompletableFuture<U> future = new CompletableFuture<>();
      future.completeExceptionally(new IllegalStateException(String.format("Invalid URI %s", request.member())));
      return future;
    }

    @Override
    public void destroyRoutes(Cluster cluster, RaftProtocol protocol) {
      cluster.localMember().unregisterHandler(name);
      protocol.pingHandler(null);
      protocol.pollHandler(null);
      protocol.appendHandler(null);
      protocol.syncHandler(null);
      protocol.commitHandler(null);
    }
  }

}
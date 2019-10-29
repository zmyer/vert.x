/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.net.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.vertx.core.*;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.metrics.Metrics;
import io.vertx.core.spi.metrics.MetricsProvider;
import io.vertx.core.spi.metrics.TCPMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import io.vertx.core.streams.ReadStream;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This class is thread-safe
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
// TODO: 2018/8/1 by zmyer
public class NetServerImpl implements Closeable, MetricsProvider, NetServer {

  private static final Logger log = LoggerFactory.getLogger(NetServerImpl.class);

  protected final VertxInternal vertx;
  protected final NetServerOptions options;
  protected final ContextInternal creatingContext;
  protected final SSLHelper sslHelper;
  protected final boolean logEnabled;
  private final Map<Channel, NetSocketImpl> socketMap = new ConcurrentHashMap<>();
  private final VertxEventLoopGroup availableWorkers = new VertxEventLoopGroup();
  private final HandlerManager<Handlers> handlerManager = new HandlerManager<>(availableWorkers);
  private final NetSocketStream connectStream = new NetSocketStream();
  private ChannelGroup serverChannelGroup;
  private long demand = Long.MAX_VALUE;
  private volatile boolean listening;
  private Handler<NetSocket> registeredHandler;
  private volatile ServerID id;
  private NetServerImpl actualServer;
  private io.netty.util.concurrent.Future<Channel> bindFuture;
  private volatile int actualPort;
  private ContextInternal listenContext;
  private TCPMetrics metrics;
  private Handler<NetSocket> handler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;

  // TODO: 2018/8/1 by zmyer
  public NetServerImpl(VertxInternal vertx, NetServerOptions options) {
    this.vertx = vertx;
    this.options = new NetServerOptions(options);
    this.sslHelper = new SSLHelper(options, options.getKeyCertOptions(), options.getTrustOptions());
    this.creatingContext = vertx.getContext();
    this.logEnabled = options.getLogActivity();
    if (creatingContext != null) {
      creatingContext.addCloseHook(this);
    }
  }

  private synchronized void pauseAccepting() {
    demand = 0L;
  }

  private synchronized void resumeAccepting() {
    demand = Long.MAX_VALUE;
  }

  private synchronized void fetchAccepting(long amount) {
    if (amount > 0L) {
      demand += amount;
      if (demand < 0L) {
        demand = Long.MAX_VALUE;
      }
    }
  }

  protected synchronized boolean accept() {
    boolean accept = demand > 0L;
    if (accept && demand != Long.MAX_VALUE) {
      demand--;
    }
    return accept;
  }

  protected boolean isListening() {
    return listening;
  }

  @Override
  public synchronized Handler<NetSocket> connectHandler() {
    return handler;
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public synchronized NetServer connectHandler(Handler<NetSocket> handler) {
    if (isListening()) {
      throw new IllegalStateException("Cannot set connectHandler when server is listening");
    }
    this.handler = handler;
    return this;
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public synchronized NetServer exceptionHandler(Handler<Throwable> handler) {
    if (isListening()) {
      throw new IllegalStateException("Cannot set exceptionHandler when server is listening");
    }
    this.exceptionHandler = handler;
    return this;
  }

  // TODO: 2018/8/1 by zmyer
  protected void initChannel(ChannelPipeline pipeline) {
    if (logEnabled) {
      pipeline.addLast("logging", new LoggingHandler());
    }
    if (sslHelper.isSSL()) {
      // only add ChunkedWriteHandler when SSL is enabled otherwise it is not needed as FileRegion is used.
      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());       // For large file / sendfile support
    }
    if (options.getIdleTimeout() > 0) {
      pipeline.addLast("idle", new IdleStateHandler(0, 0, options.getIdleTimeout(), options.getIdleTimeoutUnit()));
    }
  }

  public Future<Void> close() {
    Promise<Void> promise = Promise.promise();
    close(promise);
    return promise.future();
  }

  @Override
  public Future<NetServer> listen(int port, String host) {
    return listen(SocketAddress.inetSocketAddress(port, host));
  }


  @Override
  public NetServer listen(int port, String host, Handler<AsyncResult<NetServer>> listenHandler) {
    return listen(SocketAddress.inetSocketAddress(port, host), listenHandler);
  }

  @Override
  public Future<NetServer> listen(int port) {
    return listen(port, "0.0.0.0");
  }

  @Override
  public NetServer listen(int port, Handler<AsyncResult<NetServer>> listenHandler) {
    return listen(port, "0.0.0.0", listenHandler);
  }

  @Override
  public synchronized Future<NetServer> listen(SocketAddress localAddress) {
    if (handler == null) {
      throw new IllegalStateException("Set connect handler first");
    }
    if (listening) {
      throw new IllegalStateException("Listen already called");
    }
    listening = true;

    listenContext = vertx.getOrCreateContext();
    registeredHandler = handler;

    Map<ServerID, NetServerImpl> sharedNetServers = vertx.sharedNetServers();
    synchronized (sharedNetServers) {
      this.actualPort = localAddress.port(); // Will be updated on bind for a wildcard port
      String hostOrPath = localAddress.host() != null ? localAddress.host() : localAddress.path();
      id = new ServerID(actualPort, hostOrPath);
      NetServerImpl shared = sharedNetServers.get(id);
      if (shared == null || actualPort == 0) { // Wildcard port will imply a new actual server each time
        serverChannelGroup = new DefaultChannelGroup("vertx-acceptor-channels", GlobalEventExecutor.INSTANCE);

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(availableWorkers);
        sslHelper.validate(vertx);

        bootstrap.childHandler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) {
            if (!accept()) {
              ch.close();
              return;
            }
            HandlerHolder<Handlers> handler = handlerManager.chooseHandler(ch.eventLoop());
            if (handler != null) {
              if (sslHelper.isSSL()) {
                ch.pipeline().addFirst("handshaker", new SslHandshakeCompletionHandler(ar -> {
                  if (ar.succeeded()) {
                    connected(handler, ch);
                  } else {
                    Handler<Throwable> exceptionHandler = handler.handler.exceptionHandler;
                    if (exceptionHandler != null) {
                      handler.context.executeFromIO(v -> {
                        exceptionHandler.handle(ar.cause());
                      });
                    } else {
                      log.error("Client from origin " + ch.remoteAddress() + " failed to connect over ssl: " + ar.cause());
                    }
                  }
                }));
                if (options.isSni()) {
                  SniHandler sniHandler = new SniHandler(sslHelper.serverNameMapper(vertx));
                  ch.pipeline().addFirst("ssl", sniHandler);
                } else {
                  SslHandler sslHandler = new SslHandler(sslHelper.createEngine(vertx));
                  sslHandler.setHandshakeTimeout(sslHelper.getSslHandshakeTimeout(), sslHelper.getSslHandshakeTimeoutUnit());
                  ch.pipeline().addFirst("ssl", sslHandler);
                }
              } else {
                connected(handler, ch);
              }
            }
          }
        });

        applyConnectionOptions(localAddress.path() != null, bootstrap);

        handlerManager.addHandler(new Handlers(this, handler, exceptionHandler), listenContext);

        try {
          bindFuture = AsyncResolveConnectHelper.doBind(vertx, localAddress, bootstrap);
          bindFuture.addListener((GenericFutureListener<io.netty.util.concurrent.Future<Channel>>) res -> {
            if (res.isSuccess()) {
              Channel ch = res.getNow();
              log.trace("Net server listening on " + (hostOrPath) + ":" + ch.localAddress());
              // Update port to actual port - wildcard port 0 might have been used
              if (actualPort != -1) {
                actualPort = ((InetSocketAddress)ch.localAddress()).getPort();
              }
              id = new ServerID(NetServerImpl.this.actualPort, id.host);
              serverChannelGroup.add(ch);
              synchronized (sharedNetServers) {
                sharedNetServers.put(id, NetServerImpl.this);
              }
              VertxMetrics metrics = vertx.metricsSPI();
              if (metrics != null) {
                this.metrics = metrics.createNetServerMetrics(options, new SocketAddressImpl(id.port, id.host));
              }
            } else {
              synchronized (sharedNetServers) {
                sharedNetServers.remove(id);
              }
              listening  = false;
            }
          });
        } catch (Throwable t) {
          listening = false;
          return listenContext.failedFuture(t);
        }
        if (actualPort != 0) {
          sharedNetServers.put(id, this);
        }
        actualServer = this;
      } else {
        // Server already exists with that host/port - we will use that
        actualServer = shared;
        this.actualPort = shared.actualPort();
        VertxMetrics metrics = vertx.metricsSPI();
        this.metrics = metrics != null ? metrics.createNetServerMetrics(options, new SocketAddressImpl(id.port, id.host)) : null;
        actualServer.handlerManager.addHandler(new Handlers(this, handler, exceptionHandler), listenContext);
      }

      // just add it to the future so it gets notified once the bind is complete
      Promise<NetServer> promise = listenContext.promise();
      actualServer.bindFuture.addListener(res -> {
        if (res.isSuccess()) {
          promise.complete(this);
        } else {
          promise.fail(res.cause());
        }
      });
      return promise.future();
    }
  }

  @Override
  public synchronized NetServer listen(SocketAddress localAddress, Handler<AsyncResult<NetServer>> listenHandler) {
    if (listenHandler == null) {
      listenHandler = res -> {
        if (res.failed()) {
          // No handler - log so user can see failure
          log.error("Failed to listen", res.cause());
        }
      };
    }
    listen(localAddress).setHandler(listenHandler);
    return this;
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  public synchronized Future<NetServer> listen() {
    return listen(options.getPort(), options.getHost());
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public synchronized NetServer listen(Handler<AsyncResult<NetServer>> listenHandler) {
    return listen(options.getPort(), options.getHost(), listenHandler);
  }

  // TODO: 2018/11/27 by zmyer
  @Override
  public ReadStream<NetSocket> connectStream() {
    return connectStream;
  }

  /**
   * Internal method that closes all servers when Vert.x is closing
   */
  public void closeAll(Handler<AsyncResult<Void>> handler) {
    List<Handlers> list = handlerManager.handlers();
    List<Future> futures = list.stream()
      .<Future<Void>>map(handlers -> Future.future(handlers.server::close))
      .collect(Collectors.toList());
    CompositeFuture fut = CompositeFuture.all(futures);
    fut.setHandler(ar -> handler.handle(ar.mapEmpty()));
  }

  @Override
  public synchronized void close(Handler<AsyncResult<Void>> completionHandler) {
    if (creatingContext != null) {
      creatingContext.removeCloseHook(this);
    }
    Handler<AsyncResult<Void>> done;
    if (endHandler != null) {
      Handler<Void> handler = endHandler;
      endHandler = null;
      done = event -> {
        if (event.succeeded()) {
          handler.handle(event.result());
        }
        if (completionHandler != null) {
          completionHandler.handle(event);
        }
      };
    } else {
      done = completionHandler;
    }
    ContextInternal context = vertx.getOrCreateContext();
    if (!listening) {
      if (done != null) {
        executeCloseDone(context, done, null);
      }
      return;
    }
    listening = false;
    synchronized (vertx.sharedNetServers()) {

      if (actualServer != null) {
        actualServer.handlerManager.removeHandler(new Handlers(this, registeredHandler, exceptionHandler), listenContext);

        if (actualServer.handlerManager.hasHandlers()) {
          // The actual server still has handlers so we don't actually close it
          if (done != null) {
            executeCloseDone(context, done, null);
          }
        } else {
          // No Handlers left so close the actual server
          // The done handler needs to be executed on the context that calls close, NOT the context
          // of the actual server
          actualServer.actualClose(context, done);
        }
      } else {
        context.runOnContext(v -> {
          done.handle(Future.succeededFuture());
        });
      }
    }
  }

  public synchronized boolean isClosed() {
    return !listening;
  }

  public int actualPort() {
    return actualPort;
  }

  @Override
  public boolean isMetricsEnabled() {
    return metrics != null;
  }

  @Override
  public Metrics getMetrics() {
    return metrics;
  }

  // TODO: 2018/8/3 by zmyer
  private void actualClose(final ContextInternal closeContext, final Handler<AsyncResult<Void>> done) {
    if (id != null) {
      vertx.sharedNetServers().remove(id);
    }

    final ContextInternal currCon = vertx.getContext();

    for (final NetSocketImpl sock : socketMap.values()) {
      sock.close();
    }

    // Sanity check
    if (vertx.getContext() != currCon) {
      throw new IllegalStateException("Context was changed");
    }

    final ChannelGroupFuture fut = serverChannelGroup.close();
    fut.addListener(cg -> {
      if (metrics != null) {
        metrics.close();
      }
      executeCloseDone(closeContext, done, fut.cause());
    });

  }

  // TODO: 2018/8/1 by zmyer
  private void connected(HandlerHolder<Handlers> handler, Channel ch) {
    NetServerImpl.this.initChannel(ch.pipeline());

    VertxHandler<NetSocketImpl> nh = VertxHandler.create(handler.context, ctx -> new NetSocketImpl(vertx, ctx, handler.context, sslHelper, metrics));
    nh.addHandler(conn -> {
      socketMap.put(ch, conn);
      handler.context.executeFromIO(v -> {
        if (metrics != null) {
          conn.metric(metrics.connected(conn.remoteAddress(), conn.remoteName()));
        }
        conn.registerEventBusHandler();
        handler.handler.connectionHandler.handle(conn);
      });
    });
    nh.removeHandler(conn -> socketMap.remove(ch));
    ch.pipeline().addLast("handler", nh);
  }

  // TODO: 2018/8/3 by zmyer
  private void executeCloseDone(final ContextInternal closeContext,
                                final Handler<AsyncResult<Void>> done, Exception e) {
    if (done != null) {
      final Future<Void> fut = e == null ? Future.succeededFuture() : Future.failedFuture(e);
      closeContext.runOnContext(v -> done.handle(fut));
    }
  }

  /**
   * Apply the connection option to the server.
   *
   * @param domainSocket whether it's a domain socket server
   * @param bootstrap the Netty server bootstrap
   */
  private void applyConnectionOptions(boolean domainSocket, ServerBootstrap bootstrap) {
    vertx.transport().configure(options, domainSocket, bootstrap);
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  protected void finalize() throws Throwable {
    // Make sure this gets cleaned up if there are no more references to it
    // so as not to leave connections and resources dangling until the system is shutdown
    // which could make the JVM run out of file handles.
    close();
    super.finalize();
  }

  /**
   * Needs to be protected using the NetServerImpl monitor as that protects the listening variable
   * In practice synchronized overhead should be close to zero assuming most access is from the same thread due
   * to biased locks
   */
  // TODO: 2018/8/3 by zmyer
  private class NetSocketStream implements ReadStream<NetSocket> {


    @Override
    public NetSocketStream handler(Handler<NetSocket> handler) {
      connectHandler(handler);
      return this;
    }

    @Override
    public NetSocketStream pause() {
      pauseAccepting();
      return this;
    }

    @Override
    public NetSocketStream resume() {
      resumeAccepting();
      return this;
    }

    @Override
    public ReadStream<NetSocket> fetch(long amount) {
      fetchAccepting(amount);
      return this;
    }

    @Override
    public NetSocketStream endHandler(Handler<Void> handler) {
      synchronized (NetServerImpl.this) {
        endHandler = handler;
        return this;
      }
    }

    @Override
    public NetSocketStream exceptionHandler(Handler<Throwable> handler) {
      // Should we use it in the server close exception handler ?
      return this;
    }
  }

  // TODO: 2018/8/1 by zmyer
  static class Handlers {
    final NetServer server;
    final Handler<NetSocket> connectionHandler;
    final Handler<Throwable> exceptionHandler;
    public Handlers(NetServer server, Handler<NetSocket> connectionHandler, Handler<Throwable> exceptionHandler) {
      this.server = server;
      this.connectionHandler = connectionHandler;
      this.exceptionHandler = exceptionHandler;
    }

    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Handlers that = (Handlers) o;

      if (!Objects.equals(connectionHandler, that.connectionHandler)) {
        return false;
      }
      if (!Objects.equals(exceptionHandler, that.exceptionHandler)) {
        return false;
      }

      return true;
    }

    public int hashCode() {
      int result = 0;
      if (connectionHandler != null) {
        result = 31 * result + connectionHandler.hashCode();
      }
      if (exceptionHandler != null) {
        result = 31 * result + exceptionHandler.hashCode();
      }
      return result;
    }
  }
}

/*
 * Copyright (c) 2011-2017 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.net.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.CharsetUtil;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.NetSocketInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.metrics.TCPMetrics;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.UUID;

/**
 *
 * This class is optimised for performance when used on the same event loop that is was passed to the handler with.
 * However it can be used safely from other threads.
 *
 * The internal state is protected using the synchronized keyword. If always used on the same event loop, then
 * we benefit from biased locking which makes the overhead of synchronized near zero.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
// TODO: 2018/8/1 by zmyer
public class NetSocketImpl extends ConnectionBase implements NetSocketInternal {

  // TODO: 2018/8/1 by zmyer
  private static final Handler<Object> NULL_MSG_HANDLER = event -> {
    if (event instanceof ByteBuf) {
      ByteBuf byteBuf = (ByteBuf) event;
      byteBuf.release();
    }
  };

  private static final Logger log = LoggerFactory.getLogger(NetSocketImpl.class);

  private final String writeHandlerID;
  private final SSLHelper helper;
  private final SocketAddress remoteAddress;
  private final TCPMetrics metrics;
  private Handler<Object> messageHandler = NULL_MSG_HANDLER;
  private Handler<Void> endHandler;
  private Handler<Void> drainHandler;
  private Buffer pendingData;
  private boolean paused = false;
  private MessageConsumer registration;

  // TODO: 2018/8/2 by zmyer
  public NetSocketImpl(VertxInternal vertx, ChannelHandlerContext channel, ContextInternal context,
    SSLHelper helper, TCPMetrics metrics) {
    this(vertx, channel, null, context, helper, metrics);
  }

  // TODO: 2018/8/3 by zmyer
  public NetSocketImpl(VertxInternal vertx, ChannelHandlerContext channel, SocketAddress remoteAddress,
    ContextInternal context,
    SSLHelper helper, TCPMetrics metrics) {
    super(vertx, channel, context);
    this.helper = helper;
    this.writeHandlerID = "__vertx.net." + UUID.randomUUID().toString();
    this.remoteAddress = remoteAddress;
    this.metrics = metrics;
  }

  // TODO: 2018/8/1 by zmyer
  synchronized void registerEventBusHandler() {
    final Handler<Message<Buffer>> writeHandler = msg -> write(msg.body());
    registration = vertx.eventBus().<Buffer>localConsumer(writeHandlerID).handler(writeHandler);
  }

  @Override
  public TCPMetrics metrics() {
    return metrics;
  }

  @Override
  public String writeHandlerID() {
    return writeHandlerID;
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  public NetSocketInternal writeMessage(Object message) {
    super.writeToChannel(message);
    return this;
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  public NetSocketInternal writeMessage(final Object message, final Handler<AsyncResult<Void>> handler) {
    final ChannelPromise promise = chctx.newPromise();
    super.writeToChannel(message, promise);
    promise.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(final ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
          handler.handle(Future.succeededFuture());
        } else {
          handler.handle(Future.failedFuture(future.cause()));
        }
      }
    });
    return this;
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  public NetSocket write(Buffer data) {
    ByteBuf buf = data.getByteBuf();
    write(buf);
    return this;
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  public NetSocket write(String str) {
    write(Unpooled.copiedBuffer(str, CharsetUtil.UTF_8));
    return this;
  }

  // TODO: 2018/8/3 by zmyer
  @Override
  public NetSocket write(String str, String enc) {
    if (enc == null) {
      write(str);
    } else {
      write(Unpooled.copiedBuffer(str, Charset.forName(enc)));
    }
    return this;
  }

  @Override
  public synchronized NetSocket handler(Handler<Buffer> dataHandler) {
    if (dataHandler != null) {
      messageHandler(new DataMessageHandler(channelHandlerContext().alloc(), dataHandler));
    } else {
      messageHandler(null);
    }
    return this;
  }

  @Override
  public synchronized NetSocketInternal messageHandler(Handler<Object> handler) {
    if (handler != null) {
      messageHandler = handler;
    } else {
      messageHandler = NULL_MSG_HANDLER;
    }
    return this;
  }

  @Override
  public synchronized NetSocket pause() {
    if (!paused) {
      paused = true;
      doPause();
    }
    return this;
  }

  @Override
  public synchronized NetSocket resume() {
    if (paused) {
      paused = false;
      if (pendingData != null) {
        // Send empty buffer to trigger sending of pending data
        context.runOnContext(v -> handleMessageReceived(Unpooled.EMPTY_BUFFER));
      }
      doResume();
    }
    return this;
  }

  @Override
  public NetSocket setWriteQueueMaxSize(int maxSize) {
    doSetWriteQueueMaxSize(maxSize);
    return this;
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public boolean writeQueueFull() {
    return isNotWritable();
  }

  @Override
  public synchronized NetSocket endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  @Override
  public synchronized NetSocket drainHandler(Handler<Void> drainHandler) {
    this.drainHandler = drainHandler;
    vertx.runOnContext(v -> callDrainHandler()); //If the channel is already drained, we want to call it immediately
    return this;
  }

  @Override
  public NetSocket sendFile(String filename, long offset, long length) {
    return sendFile(filename, offset, length, null);
  }

  @Override
  public NetSocket sendFile(String filename, long offset, long length, final Handler<AsyncResult<Void>> resultHandler) {
    File f = vertx.resolveFile(filename);
    if (f.isDirectory()) {
      throw new IllegalArgumentException("filename must point to a file and not to a directory");
    }
    RandomAccessFile raf = null;
    try {
      raf = new RandomAccessFile(f, "r");
      ChannelFuture future = super.sendFile(raf, Math.min(offset, f.length()), Math.min(length, f.length() - offset));
      if (resultHandler != null) {
        future.addListener(fut -> {
          final AsyncResult<Void> res;
          if (future.isSuccess()) {
            res = Future.succeededFuture();
          } else {
            res = Future.failedFuture(future.cause());
          }
          vertx.runOnContext(v -> resultHandler.handle(res));
        });
      }
    } catch (IOException e) {
      try {
        if (raf != null) {
          raf.close();
        }
      } catch (IOException ignore) {
      }
      if (resultHandler != null) {
        vertx.runOnContext(v -> resultHandler.handle(Future.failedFuture(e)));
      } else {
        log.error("Failed to send file", e);
      }
    }
    return this;
  }

  @Override
  public SocketAddress remoteAddress() {
    return super.remoteAddress();
  }

  public SocketAddress localAddress() {
    return super.localAddress();
  }

  @Override
  public NetSocketImpl exceptionHandler(Handler<Throwable> handler) {
    return (NetSocketImpl) super.exceptionHandler(handler);
  }

  @Override
  public NetSocketImpl closeHandler(Handler<Void> handler) {
    return (NetSocketImpl) super.closeHandler(handler);
  }

  @Override
  public synchronized void close() {
    // Close after all data is written
    chctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    chctx.flush();
  }

  @Override
  public NetSocket upgradeToSsl(Handler<Void> handler) {
    return upgradeToSsl(null, handler);
  }

  @Override
  public NetSocket upgradeToSsl(String serverName, Handler<Void> handler) {
    ChannelOutboundHandler sslHandler = (ChannelOutboundHandler) chctx.pipeline().get("ssl");
    if (sslHandler == null) {
      if (remoteAddress != null) {
        sslHandler = new SslHandler(helper.createEngine(vertx, remoteAddress, serverName));
      } else {
        if (helper.isSNI()) {
          sslHandler = new VertxSniHandler(helper, vertx);
        } else {
          sslHandler = new SslHandler(helper.createEngine(vertx));
        }
      }
      chctx.pipeline().addFirst("ssl", sslHandler);
    }
    io.netty.util.concurrent.Future<Channel> handshakeFuture;
    if (sslHandler instanceof SslHandler) {
      handshakeFuture = ((SslHandler) sslHandler).handshakeFuture();
    } else {
      handshakeFuture = ((VertxSniHandler) sslHandler).handshakeFuture();
    }
    handshakeFuture.addListener(future -> context.executeFromIO(v -> {
      if (future.isSuccess()) {
        handler.handle(null);
      } else {
        log.error(future.cause());
      }
    }));
    return this;
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  protected synchronized void handleInterestedOpsChanged() {
    checkContext();
    callDrainHandler();
  }

  @Override
  public void end() {
    close();
  }

  @Override
  protected synchronized void handleClosed() {
    checkContext();
    if (endHandler != null) {
      endHandler.handle(null);
    }
    super.handleClosed();
    if (registration != null) {
      registration.unregister();
      registration = null;
    }
  }

  // TODO: 2018/8/1 by zmyer
  public synchronized void handleMessageReceived(Object msg) {
    checkContext();
    if (messageHandler != null) {
      messageHandler.handle(msg);
    }
  }

  // TODO: 2018/8/3 by zmyer
  private class DataMessageHandler implements Handler<Object> {

    private final ByteBufAllocator allocator;
    private final Handler<Buffer> dataHandler;

    public DataMessageHandler(ByteBufAllocator allocator, Handler<Buffer> dataHandler) {
      this.dataHandler = dataHandler;
      this.allocator = allocator;
    }

    @Override
    public void handle(Object event) {
      if (event instanceof ByteBuf) {
        ByteBuf byteBuf = (ByteBuf) event;
        byteBuf = VertxHandler.safeBuffer(byteBuf, allocator);
        Buffer data = Buffer.buffer(byteBuf);
        reportBytesRead(data.length());
        if (paused) {
          if (pendingData == null) {
            pendingData = data.copy();
          } else {
            pendingData.appendBuffer(data);
          }
          return;
        }
        if (pendingData != null) {
          data = pendingData.appendBuffer(data);
          pendingData = null;
        }
        dataHandler.handle(data);
      }
    }
  }

  private void write(ByteBuf buff) {
    reportBytesWritten(buff.readableBytes());
    super.writeToChannel(buff);
  }

  // TODO: 2018/8/1 by zmyer
  private synchronized void callDrainHandler() {
    if (drainHandler != null) {
      if (!writeQueueFull()) {
        drainHandler.handle(null);
      }
    }
  }
}


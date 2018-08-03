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
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.vertx.core.Handler;
import io.vertx.core.impl.ContextInternal;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
// TODO: 2018/8/1 by zmyer
public abstract class VertxHandler<C extends ConnectionBase> extends ChannelDuplexHandler {

  private C conn;
  private Handler<Void> endReadAndFlush;
  private Handler<C> addHandler;
  private Handler<C> removeHandler;
  private Handler<Object> messageHandler;

  /**
   * Set the connection, this is usually called by subclasses when the channel is added to the pipeline.
   *
   * @param connection the connection
   */
  protected void setConnection(C connection) {
    conn = connection;
    endReadAndFlush = v -> conn.endReadAndFlush();
    if (addHandler != null) {
      addHandler.handle(connection);
    }
    messageHandler = m -> {
      conn.startRead();
      handleMessage(conn, m);
    };
  }

  /**
   * Set an handler to be called when the connection is set on this handler.
   *
   * @param handler the handler to be notified
   * @return this
   */
  // TODO: 2018/8/1 by zmyer
  public VertxHandler<C> addHandler(Handler<C> handler) {
    this.addHandler = handler;
    return this;
  }

  /**
   * Set an handler to be called when the connection is unset from this handler.
   *
   * @param handler the handler to be notified
   * @return this
   */
  // TODO: 2018/8/1 by zmyer
  public VertxHandler<C> removeHandler(Handler<C> handler) {
    this.removeHandler = handler;
    return this;
  }

  // TODO: 2018/8/3 by zmyer
  public C getConnection() {
    return conn;
  }

  public static ByteBuf safeBuffer(ByteBuf buf, ByteBufAllocator allocator) {
    if (buf == Unpooled.EMPTY_BUFFER) {
      return buf;
    }
    if (buf.isDirect() || buf instanceof CompositeByteBuf) {
      try {
        if (buf.isReadable()) {
          ByteBuf buffer = allocator.heapBuffer(buf.readableBytes());
          buffer.writeBytes(buf);
          return buffer;
        } else {
          return Unpooled.EMPTY_BUFFER;
        }
      } finally {
        buf.release();
      }
    }
    return buf;
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    C conn = getConnection();
    final ContextInternal context = conn.getContext();
    context.executeFromIO(v -> conn.handleInterestedOpsChanged());
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public void exceptionCaught(ChannelHandlerContext chctx, final Throwable t) throws Exception {
    Channel ch = chctx.channel();
    // Don't remove the connection at this point, or the handleClosed won't be called when channelInactive is called!
    C connection = getConnection();
    if (connection != null) {
      ContextInternal context = conn.getContext();
      context.executeFromIO(v -> {
        try {
          if (ch.isOpen()) {
            ch.close();
          }
        } catch (Throwable ignore) {
        }
        connection.handleException(t);
      });
    } else {
      ch.close();
    }
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public void channelInactive(ChannelHandlerContext chctx) throws Exception {
    if (removeHandler != null) {
      removeHandler.handle(conn);
    }
    final ContextInternal context = conn.getContext();
    context.executeFromIO(v -> conn.handleClosed());
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
    final ContextInternal context = conn.getContext();
    context.executeFromIO(endReadAndFlush);
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public void channelRead(final ChannelHandlerContext chctx, final Object msg) throws Exception {
    final Object message = decode(msg, chctx.alloc());
    final ContextInternal ctx = conn.getContext();
    ctx.executeFromIO(message, messageHandler);
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
    if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == IdleState.ALL_IDLE) {
      ctx.close();
    }
    ctx.fireUserEventTriggered(evt);
  }

  protected abstract void handleMessage(C connection, Object msg);

  /**
   * Decode the message before passing it to the channel
   *
   * @param msg the message to decode
   * @return the decoded message
   */
  protected abstract Object decode(Object msg, ByteBufAllocator allocator) throws Exception;
}

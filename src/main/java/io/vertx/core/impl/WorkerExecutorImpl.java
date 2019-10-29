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

package io.vertx.core.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.spi.metrics.Metrics;
import io.vertx.core.spi.metrics.MetricsProvider;
import io.vertx.core.spi.metrics.PoolMetrics;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
// TODO: 2018/8/1 by zmyer
class WorkerExecutorImpl implements MetricsProvider, WorkerExecutorInternal {

  private final Context ctx;
  private final VertxImpl.SharedWorkerPool pool;
  private boolean closed;

  // TODO: 2018/11/26 by zmyer
  public WorkerExecutorImpl(Context ctx, VertxImpl.SharedWorkerPool pool) {
    this.ctx = ctx;
    this.pool = pool;
  }

  @Override
  public Metrics getMetrics() {
    return pool.metrics();
  }

  @Override
  public boolean isMetricsEnabled() {
    PoolMetrics metrics = pool.metrics();
    return metrics != null;
  }

  @Override
  public Vertx vertx() {
    return ctx.owner();
  }

  @Override
  public WorkerPool getPool() {
    return pool;
  }

  // TODO: 2018/11/26 by zmyer
  public synchronized <T> void executeBlocking(Handler<Future<T>> blockingCodeHandler, boolean ordered,
                                               Handler<AsyncResult<T>> asyncResultHandler) {
    if (closed) {
      throw new IllegalStateException("Worker executor closed");
    }
    ContextImpl context = (ContextImpl) ctx.owner().getOrCreateContext();
    context.executeBlocking(blockingCodeHandler, asyncResultHandler, pool.executor(),
      ordered ? context.orderedTasks : null, pool.metrics());
  }

  // TODO: 2018/11/26 by zmyer
  @Override
  public void close() {
    synchronized (this) {
      if (!closed) {
        closed = true;
      } else {
        return;
      }
    }
    ctx.removeCloseHook(this);
    pool.release();
  }

  // TODO: 2018/11/26 by zmyer
  @Override
  public void close(Handler<AsyncResult<Void>> completionHandler) {
    close();
    completionHandler.handle(Future.succeededFuture());
  }

}

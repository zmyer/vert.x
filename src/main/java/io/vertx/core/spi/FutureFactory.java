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

package io.vertx.core.spi;

import io.vertx.core.Future;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.PromiseInternal;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
// TODO: 2018/11/22 by zmyer
public interface FutureFactory {

  <T> PromiseInternal<T> promise();

  <T> Future<T> succeededFuture();

  <T> Future<T> succeededFuture(T result);

  <T> Future<T> failedFuture(Throwable t);

  <T> Future<T> failureFuture(String failureMessage);

  <T> PromiseInternal<T> promise(ContextInternal context);

  <T> Future<T> succeededFuture(ContextInternal context);

  <T> Future<T> succeededFuture(ContextInternal context, T result);

  <T> Future<T> failedFuture(ContextInternal context, Throwable t);

  <T> Future<T> failedFuture(ContextInternal context, String failureMessage);

}

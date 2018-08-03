/*
 * Copyright (c) 2014 Red Hat, Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.impl;

import io.vertx.core.Verticle;
import io.vertx.core.impl.verticle.CompilingClassLoader;
import io.vertx.core.spi.VerticleFactory;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
// TODO: 2018/8/1 by zmyer
public class JavaVerticleFactory implements VerticleFactory {

  @Override
  public String prefix() {
    return "java";
  }

  // TODO: 2018/8/1 by zmyer
  @Override
  public Verticle createVerticle(String verticleName, ClassLoader classLoader) throws Exception {
    verticleName = VerticleFactory.removePrefix(verticleName);
    final Class clazz;
    if (verticleName.endsWith(".java")) {
      final CompilingClassLoader compilingLoader = new CompilingClassLoader(classLoader, verticleName);
      final String className = compilingLoader.resolveMainClassName();
      clazz = compilingLoader.loadClass(className);
    } else {
      clazz = classLoader.loadClass(verticleName);
    }
    return (Verticle) clazz.newInstance();
  }

}

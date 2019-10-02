/**
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.client.impl;

import org.eclipse.hono.client.AzureHonoConnection;
import org.eclipse.hono.client.CommandClient;
import io.vertx.core.Future;

public class ServiceBusApplicationClientFactoryImpl extends ApplicationClientFactoryImpl {

  public ServiceBusApplicationClientFactoryImpl(final AzureHonoConnection connection) {
    super(connection);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Future<CommandClient> getOrCreateCommandClient(final String tenantId,
      final String replyId, final String cacheKey) {
    log.debug("get or create command client for [tenantId: {}, replyId: {}]", tenantId, replyId);
    return connection.executeOrRunOnContext(result -> {
      commandClientFactory.getOrCreateClient(cacheKey,
          () -> ServiceBusCommandClientImpl.createAzure(connection, tenantId, replyId,
              s -> removeCommandClient(cacheKey), s -> removeCommandClient(cacheKey)),
          result);
    });
  }



}

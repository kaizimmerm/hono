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

import org.eclipse.hono.client.Command;
import org.eclipse.hono.client.CommandContext;
import org.eclipse.hono.client.GatewayMapper;
import org.eclipse.hono.client.impl.GatewayMappingCommandHandler;
import org.eclipse.hono.util.CommandConstants;
import io.vertx.core.Handler;

public class ServiceBusGatewayMappingCommandHandler extends GatewayMappingCommandHandler {

  public ServiceBusGatewayMappingCommandHandler(final GatewayMapper gatewayMapper,
      final Handler<CommandContext> nextCommandHandler) {
    super(gatewayMapper, nextCommandHandler);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getDelegatedReplyTo(final Command originalCommand, final String tenantId) {
    return String.format("%s/%s", CommandConstants.NORTHBOUND_COMMAND_RESPONSE_ENDPOINT, tenantId);
  }

}

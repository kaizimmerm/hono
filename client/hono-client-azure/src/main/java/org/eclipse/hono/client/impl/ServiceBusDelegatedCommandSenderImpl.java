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

import java.util.Objects;
import org.eclipse.hono.client.Command;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.impl.DelegatedCommandSenderImpl;
import org.eclipse.hono.util.CommandConstants;
import io.opentracing.SpanContext;
import io.vertx.core.Future;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonSender;

public class ServiceBusDelegatedCommandSenderImpl extends DelegatedCommandSenderImpl {

  ServiceBusDelegatedCommandSenderImpl(final HonoConnection connection, final ProtonSender sender) {
    super(connection, sender);
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public String getEndpoint() {
    return CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Future<ProtonDelivery> sendCommandMessage(final Command command,
      final SpanContext spanContext) {
    Objects.requireNonNull(command);
    final String replyToAddress = command.isOneWay() ? null
        : String.format("%s/%s", command.getReplyToEndpoint(), command.getTenant());
    return sendAndWaitForOutcome(
        createDelegatedCommandMessage(command.getCommandMessage(), replyToAddress), spanContext);
  }

}

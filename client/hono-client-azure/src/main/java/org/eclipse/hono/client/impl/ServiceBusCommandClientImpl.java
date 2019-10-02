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
import org.eclipse.hono.client.CommandClient;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.util.CommandConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Future;
import io.vertx.core.Handler;

public class ServiceBusCommandClientImpl extends CommandClientImpl {
  private static final Logger LOG = LoggerFactory.getLogger(ServiceBusCommandClientImpl.class);

  protected ServiceBusCommandClientImpl(final HonoConnection connection, final String tenantId,
      final String replyId) {
    super(connection, tenantId, replyId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getReplyToAddress(final String tenantId, final String replyId) {
    return String.format("%s/%s", getReplyToEndpointName(), tenantId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getReplyToAddress(final String replyId) {
    return String.format("%s", getReplyToEndpointName());
  }

  /**
   * Creates a new command client for a tenant and device.
   * <p>
   * The instance created is scoped to the given device. In particular, the target address of
   * messages is set to <em>command/${tenantId}/${deviceId}</em>, whereas the sender link's target
   * address is set to <em>command/${tenantId}</em>. The receiver link's source address is set to
   * <em>command_response/${tenantId}/${deviceId}/${replyId}</em>.
   *
   * This address is also used as the value of the <em>reply-to</em> property of all command request
   * messages sent by this client.
   *
   * @param con The connection to Hono.
   * @param tenantId The tenant that the device belongs to.
   * @param replyId The replyId to use in the reply-to address.
   * @param senderCloseHook A handler to invoke if the peer closes the sender link unexpectedly.
   * @param receiverCloseHook A handler to invoke if the peer closes the receiver link unexpectedly.
   * @return A future indicating the outcome.
   * @throws NullPointerException if any of the parameters are {@code null}.
   */
  public static final Future<CommandClient> createAzure(final HonoConnection con,
      final String tenantId, final String replyId, final Handler<String> senderCloseHook,
      final Handler<String> receiverCloseHook) {

    final ServiceBusCommandClientImpl client =
        new ServiceBusCommandClientImpl(con, tenantId, replyId);
    return client.createLinks(senderCloseHook, receiverCloseHook).map(ok -> {
      LOG.debug("successfully created command client for [{}]", tenantId);
      return (CommandClient) client;
    }).recover(t -> {
      LOG.debug("failed to create command client for [{}]", tenantId, t);
      return Future.failedFuture(t);
    });
  }

  private static String getTargetAddress(final String tenantId) {
    // FIXME instance subscription
    return String.format("%s/%s/subscriptions/testapps",
        CommandConstants.NORTHBOUND_COMMAND_RESPONSE_ENDPOINT, Objects.requireNonNull(tenantId));
  }

  @Override
  protected final Future<Void> createLinks(final Handler<String> senderCloseHook,
      final Handler<String> receiverCloseHook) {

    return createReceiver(getTargetAddress(getTenantId()), receiverCloseHook).compose(recv -> {
      this.receiver = recv;
      return createSender(linkTargetAddress, senderCloseHook);
    }).compose(sender -> {
      LOG.debug("request-response client for peer [{}] created", connection.getConfig().getHost());
      this.sender = sender;
      return Future.succeededFuture();
    });
  }


}

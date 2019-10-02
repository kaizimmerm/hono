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
import java.util.Optional;
import org.eclipse.hono.client.AzureHonoConnection;
import org.eclipse.hono.client.CommandContext;
import org.eclipse.hono.client.DelegatedCommandSender;
import org.eclipse.hono.client.GatewayMapper;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.ServiceBusSubscriptionManagementSender;
import org.eclipse.hono.util.CommandConstants;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.proton.ProtonQoS;

public class ServiceBusCommandConsumerFactoryImpl extends CommandConsumerFactoryImpl {

  private final CachingClientFactory<ServiceBusSubscriptionManagementSender> serviceBusSubscriptionManagementSenderFactory;

  public ServiceBusCommandConsumerFactoryImpl(final AzureHonoConnection connection,
      final GatewayMapper gatewayMapper) {
    super(connection, gatewayMapper);
    serviceBusSubscriptionManagementSenderFactory =
        new CachingClientFactory<>(connection.getVertx(), s -> s.isOpen());

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Future<MessageConsumer> createCommandConsumer(final String tenantId,
      final String deviceId, final Handler<CommandContext> commandHandler,
      final Handler<Void> remoteCloseHandler) {

    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(deviceId);
    Objects.requireNonNull(commandHandler);

    return connection.executeOrRunOnContext(result -> {

      final String key = getKey(tenantId, deviceId);

      final Future<ServiceBusSubscriptionManagementSender> serviceBusSubscriptionManagementSender =
          getServiceBusSubscriptionManagementSender(tenantId);
      final Future<MessageConsumer> tenantScopedCommandConsumerFuture =
          getOrCreateTenantScopedCommandConsumer(tenantId);

      serviceBusSubscriptionManagementSender
          .compose(sender -> sender.addDeviceFilter(tenantId, deviceId, null))
          .compose(filterCreated -> tenantScopedCommandConsumerFuture).map(res -> {
            deviceSpecificCommandHandlers.put(key, commandHandler);
            return tenantScopedCommandConsumerFuture.result();
          }).setHandler(result);
    });
  }

  @Override
  protected Future<MessageConsumer> newDeviceSpecificCommandConsumer(final String tenantId,
      final String deviceId, final Handler<CommandContext> commandHandler,
      final Handler<Void> remoteCloseHandler) {

    final String key = getKey(tenantId, deviceId);
    return DeviceSpecificCommandConsumer
        .create(connection, tenantId, deviceId, commandHandler, sourceAddress -> { // local close
                                                                                   // hook
          // stop liveness check
          Optional.ofNullable(livenessChecks.remove(key))
              .ifPresent(connection.getVertx()::cancelTimer);
          deviceSpecificCommandConsumerFactory.removeClient(key);
          deviceSpecificCommandHandlers.remove(key);
          getServiceBusSubscriptionManagementSender(tenantId)
              .compose(sender -> sender.removeDeviceFilter(tenantId, deviceId, null));

        }, sourceAddress -> { // remote close hook
          deviceSpecificCommandConsumerFactory.removeClient(key);
          deviceSpecificCommandHandlers.remove(key);
          getServiceBusSubscriptionManagementSender(tenantId)
              .compose(sender -> sender.removeDeviceFilter(tenantId, deviceId, null));
          remoteCloseHandler.handle(null);
        }).map(c -> (MessageConsumer) c);
  }

  private Future<ServiceBusSubscriptionManagementSender> getServiceBusSubscriptionManagementSender(
      final String tenantId) {
    Objects.requireNonNull(tenantId);
    return connection.executeOrRunOnContext(result -> {
      serviceBusSubscriptionManagementSenderFactory.getOrCreateClient(tenantId,
          () -> ServiceBusSubscriptionManagementSenderImpl.create((AzureHonoConnection) connection, tenantId, null),
          result);
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Future<MessageConsumer> getOrCreateTenantScopedCommandConsumer(final String tenantId) {
    Objects.requireNonNull(tenantId);
    return connection.executeOrRunOnContext(result -> {
      final MessageConsumer messageConsumer =
          tenantScopedCommandConsumerFactory.getClient(tenantId);
      if (messageConsumer != null) {
        result.complete(messageConsumer);
      } else {
        final DelegateViaDownstreamPeerCommandHandler delegatingCommandHandler =
            new DelegateViaDownstreamPeerCommandHandler((tenantIdParam,
                deviceIdParam) -> createDelegatedCommandSender(tenantIdParam, deviceIdParam));

        final GatewayMappingCommandHandler gatewayMappingCommandHandler =
            new ServiceBusGatewayMappingCommandHandler(gatewayMapper, commandContext -> {
              final String deviceId = commandContext.getCommand().getDeviceId();
              final Handler<CommandContext> commandHandler =
                  deviceSpecificCommandHandlers.get(getKey(tenantId, deviceId));
              if (commandHandler != null) {
                log.trace("use local command handler for device {}", deviceId);
                commandHandler.handle(commandContext);
              } else {
                // delegate to matching consumer via downstream peer
                delegatingCommandHandler.handle(commandContext);
              }
            });

        log.debug("open SB command consumer for tenant: [{}]", tenantId);
        tenantScopedCommandConsumerFactory.getOrCreateClient(tenantId,
            () -> newTenantScopedCommandConsumer(tenantId, gatewayMappingCommandHandler), result);
      }
    });
  }

  @Override
  protected String getCommandRequestAddress(final String tenantId) {
    return getTargetAddress(tenantId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Future<DelegatedCommandSender> createDelegatedCommandSender(final String tenantId,
      final String deviceId) {
    Objects.requireNonNull(tenantId);
    return connection.executeOrRunOnContext(result -> {
      delegatedCommandSenderFactory.createClient(() -> create(connection, tenantId, deviceId, null),
          result);
    });
  }

  private static Future<DelegatedCommandSender> create(final HonoConnection con,
      final String tenantId, final String deviceId, final Handler<String> closeHook) {

    Objects.requireNonNull(con);

    final String targetAddress = getTargetAddress(tenantId);
    return con.createSender(targetAddress, ProtonQoS.AT_LEAST_ONCE, closeHook)
        .map(sender -> new DelegatedCommandSenderImpl(con, sender));
  }

  public static String getTargetAddress(final String tenantId) {
    // FIXME instance subscription
    return String.format("%s/%s/subscriptions/testadapter",
        CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT, Objects.requireNonNull(tenantId));
  }



}

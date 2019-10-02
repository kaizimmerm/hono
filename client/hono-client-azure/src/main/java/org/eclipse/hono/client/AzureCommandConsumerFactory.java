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
package org.eclipse.hono.client;

import java.net.URISyntaxException;
import org.eclipse.hono.client.impl.ServiceBusCommandConsumerFactoryImpl;

public interface AzureCommandConsumerFactory extends CommandConsumerFactory {

  /**
   * Creates a new factory for an existing connection.
   *
   * @param connection The connection to the AMQP network.
   * @param gatewayMapper The component mapping a command device id to the corresponding gateway device id.
   * @return The factory.
   * @throws URISyntaxException
   * @throws NullPointerException if connection or gatewayMapper is {@code null}.
   */
  static CommandConsumerFactory create(final AzureHonoConnection connection,
      final GatewayMapper gatewayMapper) throws URISyntaxException {
    return new ServiceBusCommandConsumerFactoryImpl(connection, gatewayMapper);
  }
}

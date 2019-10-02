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

import org.eclipse.hono.client.impl.ServiceBusApplicationClientFactoryImpl;

/**
 * A factory for creating clients for Hono's north bound APIs.
 *
 */
public interface AzureApplicationClientFactory extends ApplicationClientFactory {

  /**
   * Creates a new factory for an existing connection.
   *
   * @param connection The connection to use.
   * @return The factory.
   * @throws NullPointerException if connection is {@code null}
   */
  static ApplicationClientFactory create(final AzureHonoConnection connection) {
    return new ServiceBusApplicationClientFactoryImpl(connection);
  }
}

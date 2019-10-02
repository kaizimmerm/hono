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

import java.util.Map;
import org.apache.qpid.proton.amqp.Symbol;
import org.eclipse.hono.client.impl.ServiceBusHonoConnectionImpl;
import org.eclipse.hono.config.ClientConfigProperties;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

public interface AzureHonoConnection extends HonoConnection {

  static AzureHonoConnection newConnection(final Vertx vertx, final ClientConfigProperties clientConfigProperties) {
    return new ServiceBusHonoConnectionImpl(vertx, clientConfigProperties);
}

  Future<ProtonSender> createRequestReplySender(String targetAddress, ProtonQoS qos,
      Handler<String> closeHook, Map<Symbol, Object> properties, String replyTo);

}

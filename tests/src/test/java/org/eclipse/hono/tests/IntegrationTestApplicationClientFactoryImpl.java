/**
 * Copyright (c) 2018, 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */


package org.eclipse.hono.tests;

import java.util.Objects;

import org.eclipse.hono.client.MessageSender;
import org.eclipse.hono.client.impl.AmqpHonoConnection;
import org.eclipse.hono.client.impl.ApplicationClientFactoryImpl;

import io.vertx.core.Future;


/**
 * A Hono client that also allows to create generic links to a peer.
 *
 */
public class IntegrationTestApplicationClientFactoryImpl extends ApplicationClientFactoryImpl implements IntegrationTestApplicationClientFactory {

    /**
     * Creates a new client.
     * 
     * @param connection The connection to Hono.
     */
    public IntegrationTestApplicationClientFactoryImpl(final AmqpHonoConnection connection) {
        super(connection);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<MessageSender> createGenericMessageSender(final String targetAddress) {

        Objects.requireNonNull(targetAddress);
        return GenericMessageSenderImpl.create(
                connection,
                targetAddress,
                s -> {});
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<LegacyCommandClient> createLegacyCommandClient(final String tenantId, final String deviceId) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        return LegacyCommandClientImpl.create(
                connection,
                tenantId,
                deviceId,
                "replies",
                null,
                null);
    }
}

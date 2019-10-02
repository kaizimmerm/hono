/*******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.client.impl;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.hono.client.CommandConsumerFactory;
import org.eclipse.hono.client.HonoConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.proton.ProtonMessageHandler;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;

/**
 * A wrapper around an AMQP receiver link for consuming commands on a tenant-scoped address.
 * <p>
 * This class is used by the default {@link CommandConsumerFactory} implementation to receive commands from northbound
 * applications.
 */
public class TenantScopedCommandConsumer extends CommandConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TenantScopedCommandConsumer.class);

    private TenantScopedCommandConsumer(final HonoConnection connection, final ProtonReceiver receiver) {

        super(connection, receiver);
    }

    /**
     * Creates a new command consumer.
     *
     * @param con The connection to the server.
     * @param address The tenant address to consume commands from.
     * @param messageHandler The handler to invoke for each message received.
     * @param localCloseHandler A handler to be invoked after the link has been closed
     *                     at this peer's request using the {@link #close(Handler)} method.
     *                     The handler will be invoked with the link's source address <em>after</em>
     *                     the link has been closed but <em>before</em> the handler that has been
     *                     passed into the <em>close</em> method is invoked.
     * @param remoteCloseHandler A handler to be invoked after the link has been closed
     *                     at the remote peer's request. The handler will be invoked with the
     *                     link's source address.
     * @param receiverRefHolder A reference object to set the created ProtonReceiver object in.
     * @return A future indicating the outcome of the creation attempt.
     * @throws NullPointerException if any of the parameters other than tracer are {@code null}.
     */
    public static Future<TenantScopedCommandConsumer> create(
            final HonoConnection con,
            final String address,
            final ProtonMessageHandler messageHandler,
            final Handler<String> localCloseHandler,
            final Handler<String> remoteCloseHandler,
            final AtomicReference<ProtonReceiver> receiverRefHolder) {

        Objects.requireNonNull(con);
        Objects.requireNonNull(address);
        Objects.requireNonNull(messageHandler);
        Objects.requireNonNull(remoteCloseHandler);
        Objects.requireNonNull(receiverRefHolder);

        LOG.debug("creating new tenant scoped command consumer [address: {}]", address);


        return con.createReceiver(
                address,
                ProtonQoS.AT_LEAST_ONCE,
                messageHandler,
                con.getConfig().getInitialCredits(),
                false, // no auto-accept
                sourceAddress -> {
                    LOG.debug("command receiver link [address: {}] closed remotely", address);
                    remoteCloseHandler.handle(sourceAddress);
                }).map(receiver -> {
                    LOG.debug("successfully created tenant scoped command consumer [{}]", address);
                    receiverRefHolder.set(receiver);
                    final TenantScopedCommandConsumer consumer = new TenantScopedCommandConsumer(con, receiver);
                    consumer.setLocalCloseHandler(sourceAddress -> {
                        LOG.debug("command receiver link [address: {}] closed locally", address);
                        localCloseHandler.handle(sourceAddress);
                    });
                    return consumer;
                }).recover(t -> {
                    LOG.debug("failed to create tenant scoped command consumer [address: {}]", address, t);
                    return Future.failedFuture(t);
                });
    }
}

/**
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
 */

package org.eclipse.hono.client.impl;

import org.apache.qpid.proton.amqp.Symbol;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.config.ClientConfigProperties;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.proton.ProtonLink;
import io.vertx.proton.ProtonMessageHandler;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;

/**
 * @see HonoConnection
 *
 */
public interface AmqpHonoConnection extends HonoConnection {

  /**
   * Gets the configuration properties used for creating this connection.
   * 
   * @return The configuration.
   */
  ClientConfigProperties getConfig();

  /**
   * Checks if this client supports a certain capability.
   * <p>
   * The result of this method should only be considered reliable if this client is connected to the server.
   *
   * @param capability The capability to check support for.
   * @return {@code true} if the capability is included in the list of capabilities that the server has offered in its
   *         AMQP <em>open</em> frame, {@code false} otherwise.
   */
  boolean supportsCapability(Symbol capability);

  /**
   * Creates a sender link.
   *
   * @param targetAddress The target address of the link. If the address is {@code null}, the
   *                      sender link will be established to the 'anonymous relay' and each
   *                      message must specify its destination address.
   * @param qos The quality of service to use for the link.
   * @param remoteCloseHook The handler to invoke when the link is closed by the peer (may be {@code null}).
   * @return A future for the created link. The future will be completed once the link is open.
   *         The future will fail with a {@link ServiceInvocationException} if the link cannot be opened.
   * @throws NullPointerException if qos is {@code null}.
   */
  Future<ProtonSender> createSender(
          String targetAddress,
          ProtonQoS qos,
          Handler<String> remoteCloseHook);

  /**
   * Creates a receiver link.
   * <p>
   * The receiver will be created with its <em>autoAccept</em> property set to {@code true}
   * and with the connection's default pre-fetch size.
   *
   * @param sourceAddress The address to receive messages from.
   * @param qos The quality of service to use for the link.
   * @param messageHandler The handler to invoke with every message received.
   * @param remoteCloseHook The handler to invoke when the link is closed at the peer's request (may be {@code null}).
   * @return A future for the created link. The future will be completed once the link is open.
   *         The future will fail with a {@link ServiceInvocationException} if the link cannot be opened.
   * @throws NullPointerException if any of the arguments other than close hook is {@code null}.
   */
  Future<ProtonReceiver> createReceiver(
          String sourceAddress,
          ProtonQoS qos,
          ProtonMessageHandler messageHandler,
          Handler<String> remoteCloseHook);

  /**
   * Creates a receiver link.
   * <p>
   * The receiver will be created with its <em>autoAccept</em> property set to {@code true}.
   *
   * @param sourceAddress The address to receive messages from.
   * @param qos The quality of service to use for the link.
   * @param messageHandler The handler to invoke with every message received.
   * @param preFetchSize The number of credits to flow to the peer as soon as the link
   *                     has been established. A value of 0 prevents pre-fetching and
   *                     allows for manual flow control using the returned receiver's
   *                     <em>flow</em> method.
   * @param remoteCloseHook The handler to invoke when the link is closed at the peer's request (may be {@code null}).
   * @return A future for the created link. The future will be completed once the link is open.
   *         The future will fail with a {@link ServiceInvocationException} if the link cannot be opened.
   * @throws NullPointerException if any of the arguments other than close hook is {@code null}.
   * @throws IllegalArgumentException if the pre-fetch size is &lt; 0.
   */
  Future<ProtonReceiver> createReceiver(
          String sourceAddress,
          ProtonQoS qos,
          ProtonMessageHandler messageHandler,
          int preFetchSize,
          Handler<String> remoteCloseHook);

  /**
   * Creates a receiver link.
   *
   * @param sourceAddress The address to receive messages from.
   * @param qos The quality of service to use for the link.
   * @param messageHandler The handler to invoke with every message received.
   * @param preFetchSize The number of credits to flow to the peer as soon as the link
   *                     has been established. A value of 0 prevents pre-fetching and
   *                     allows for manual flow control using the returned receiver's
   *                     <em>flow</em> method.
   * @param autoAccept {@code true} if received deliveries should be automatically accepted (and settled)
   *                   after the message handler runs for them, if no other disposition has been applied
   *                   during handling.
   * @param remoteCloseHook The handler to invoke when the link is closed at the peer's request (may be {@code null}).
   * @return A future for the created link. The future will be completed once the link is open.
   *         The future will fail with a {@link ServiceInvocationException} if the link cannot be opened.
   * @throws NullPointerException if any of the arguments other than close hook is {@code null}.
   * @throws IllegalArgumentException if the pre-fetch size is &lt; 0.
   */
  Future<ProtonReceiver> createReceiver(
          String sourceAddress,
          ProtonQoS qos,
          ProtonMessageHandler messageHandler,
          int preFetchSize,
          boolean autoAccept,
          Handler<String> remoteCloseHook);

  /**
   * Closes an AMQP link and frees up its allocated resources.
   * <p>
   * This method is equivalent to {@link #closeAndFree(ProtonLink, long, Handler)}
   * but will use an implementation specific default time-out value.
   * <p>
   * If this connection is not established, the given handler is invoked immediately.
   *
   * @param link The link to close. If {@code null}, the given handler is invoked immediately.
   * @param closeHandler The handler to notify once the link has been closed.
   * @throws NullPointerException if close handler is {@code null}.
   */
  void closeAndFree(ProtonLink<?> link, Handler<Void> closeHandler);

  /**
   * Closes an AMQP link and frees up its allocated resources.
   * <p>
   * This method will invoke the given handler as soon as
   * <ul>
   * <li>the peer's <em>detach</em> frame has been received or</li>
   * <li>the given number of milliseconds have passed</li>
   * </ul>
   * Afterwards the link's resources are freed up.
   * <p>
   * If this connection is not established, the given handler is invoked immediately.
   *
   * @param link The link to close. If {@code null}, the given handler is invoked immediately.
   * @param detachTimeOut The maximum number of milliseconds to wait for the peer's
   *                      detach frame or 0, if this method should wait indefinitely
   *                      for the peer's detach frame.
   * @param closeHandler The handler to notify once the link has been closed.
   * @throws NullPointerException if close handler is {@code null}.
   * @throws IllegalArgumentException if detach time-out is &lt; 0.
   */
  void closeAndFree(
          ProtonLink<?> link,
          long detachTimeOut,
          Handler<Void> closeHandler);

}

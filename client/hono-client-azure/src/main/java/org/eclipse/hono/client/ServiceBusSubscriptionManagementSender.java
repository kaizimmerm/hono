/*******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.client;

import org.eclipse.hono.config.ClientConfigProperties;
import io.opentracing.SpanContext;
import io.vertx.core.Future;
import io.vertx.proton.ProtonDelivery;

/**
 * A sender to send command messages that are delegated to be processed by another command consumer.
 * <p>
 * This usually involves command messages first retrieved via a tenant-scoped consumer and then
 * delegated back to the downstream peer so that they can be consumed by the device-specific
 * consumer.
 */
public interface ServiceBusSubscriptionManagementSender extends MessageSender {

  /**
   * Adds device specific correlation filter from Azure Service Bus command subscription.
   *
   * @param tenantId to add the rule for.
   * @param deviceId to filter for
   * @param context The currently active OpenTracing span or {@code null} if no span is currently
   *        active. An implementation should use this as the parent for any new span(s) it creates
   *        for tracing the execution of this operation.
   * @return A future indicating the outcome of the operation.
   *         <p>
   *         The future will succeed if the message has been accepted (and settled) by the consumer.
   *         <p>
   *         The future will be failed with a {@link ServiceInvocationException} if the message
   *         could not be sent or if no delivery update was received from the peer within the
   *         configured timeout period (see {@link ClientConfigProperties#getSendMessageTimeout()}).
   * @throws NullPointerException if command is {@code null}.
   */
  Future<ProtonDelivery> addDeviceFilter(final String tenantId, final String deviceId,
      SpanContext context);

  /**
   * Removes device specific correlation filter from Azure Service Bus command subscription.
   *
   * @param tenantId to add the rule for.
   * @param deviceId to filter for
   * @param context The currently active OpenTracing span or {@code null} if no span is currently
   *        active. An implementation should use this as the parent for any new span(s) it creates
   *        for tracing the execution of this operation.
   * @return A future indicating the outcome of the operation.
   *         <p>
   *         The future will succeed if the message has been accepted (and settled) by the consumer.
   *         <p>
   *         The future will be failed with a {@link ServiceInvocationException} if the message
   *         could not be sent or if no delivery update was received from the peer within the
   *         configured timeout period (see {@link ClientConfigProperties#getSendMessageTimeout()}).
   * @throws NullPointerException if command is {@code null}.
   */
  Future<ProtonDelivery> removeDeviceFilter(final String tenantId, final String deviceId,
      SpanContext context);
}

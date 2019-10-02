/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.client.impl;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.AzureHonoConnection;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.ServiceBusSubscriptionManagementSender;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.MessageHelper;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.tag.Tags;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

/**
 * A Vertx-Proton based client for uploading telemetry data to a Hono server.
 */
public final class ServiceBusSubscriptionManagementSenderImpl extends AbstractDownstreamSender
    implements ServiceBusSubscriptionManagementSender {

  private final String replyTo;

  ServiceBusSubscriptionManagementSenderImpl(final HonoConnection con, final ProtonSender sender,
      final String tenantId,  final String replyTo) {

    super(con, sender, tenantId, getTargetAddress(tenantId));
    this.replyTo = replyTo;
  }

  /**
   * Gets the AMQP <em>target</em> address to use for uploading data to Service Bus subscription
   * management endpoint.
   *
   * @param tenantId The tenant to upload data for.
   * @return The target address.
   * @throws NullPointerException if tenant is {@code null}.
   */
  public static String getTargetAddress(final String tenantId) {
    // FIXME instance subscription
    return String.format("%s/%s/subscriptions/testadapter/$management",
        CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT, Objects.requireNonNull(tenantId));
  }

  @Override
  public String getEndpoint() {
    return CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT;
  }

  @Override
  protected String getTo(final String deviceId) {
    return null;
  }

  /**
   * Creates a new sender for publishing telemetry data to a Hono server.
   *
   * @param con The connection to the Hono server.
   * @param tenantId The tenant that the telemetry data will be uploaded for.
   * @param remoteCloseHook The handler to invoke when the Hono server closes the sender. The
   *        sender's target address is provided as an argument to the handler.
   * @return A future indicating the outcome.
   * @throws NullPointerException if any of context, connection, tenant or handler is {@code null}.
   */
  public static Future<ServiceBusSubscriptionManagementSender> create(final AzureHonoConnection con, final String tenantId,
      final Handler<String> remoteCloseHook) {

    Objects.requireNonNull(con);
    Objects.requireNonNull(tenantId);

    final Map<Symbol, Object> senderProperties = new HashMap<>();
    senderProperties.put(Symbol.getSymbol("com.microsoft:timeout"), 29800);
    senderProperties.put(Symbol.getSymbol("com.microsoft:entity-type"), 2);

    final String replyTo = UUID.randomUUID().toString();

    return con.createRequestReplySender(getTargetAddress(tenantId), ProtonQoS.AT_MOST_ONCE, remoteCloseHook, senderProperties, replyTo)
        .compose(sender -> Future.succeededFuture(
            new ServiceBusSubscriptionManagementSenderImpl(con, sender, tenantId, replyTo)));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Future<ProtonDelivery> sendAndWaitForOutcome(final Message rawMessage) {

    return sendAndWaitForOutcome(rawMessage, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Future<ProtonDelivery> sendAndWaitForOutcome(final Message rawMessage,
      final SpanContext parent) {

    Objects.requireNonNull(rawMessage);

    // we create a child span (instead of a following span) because we depend
    // on the outcome of the sending operation
    final Span span = startChildSpan(parent, rawMessage);
    Tags.MESSAGE_BUS_DESTINATION.set(span, targetAddress);
    span.setTag(MessageHelper.APP_PROPERTY_TENANT_ID, tenantId);
    span.setTag(MessageHelper.APP_PROPERTY_DEVICE_ID, MessageHelper.getDeviceId(rawMessage));
    TracingHelper.injectSpanContext(connection.getTracer(), span.context(), rawMessage);

    return connection.executeOrRunOnContext(result -> {
      if (sender.sendQueueFull()) {
        final ServiceInvocationException e =
            new ServerErrorException(HttpURLConnection.HTTP_UNAVAILABLE, "no credit available");
        logError(span, e);
        span.finish();
        result.fail(e);
      } else {
        sendMessageAndWaitForOutcome(rawMessage, span).setHandler(result);
      }
    });
  }

  /**
   * Sends an AMQP 1.0 message to the peer this client is configured for.
   *
   * @param message The message to send.
   * @param currentSpan The <em>OpenTracing</em> span used to trace the sending of the message. The
   *        span will be finished by this method and will contain an error log if the message has
   *        not been accepted by the peer.
   * @return A future indicating the outcome of the operation.
   *         <p>
   *         The future will succeed if the message has been sent to the peer. The delivery
   *         contained in the future will represent the delivery state at the time the future has
   *         been succeeded, i.e. it will be locally <em>unsettled</em> without any outcome yet.
   *         <p>
   *         The future will be failed with a {@link ServiceInvocationException} if the message
   *         could not be sent.
   * @throws NullPointerException if any of the parameters are {@code null}.
   */
  @Override
  protected Future<ProtonDelivery> sendMessage(final Message message, final Span currentSpan) {

    Objects.requireNonNull(message);
    Objects.requireNonNull(currentSpan);

    final String messageId =
        String.format("request:%d",  MESSAGE_COUNTER.getAndIncrement());
    message.setMessageId(messageId);
    message.setReplyTo(replyTo);
    logMessageIdAndSenderInfo(currentSpan, messageId);

    final ClientConfigProperties config = connection.getConfig();
    final AtomicBoolean timeoutReached = new AtomicBoolean(false);
    final Long timerId = config.getSendMessageTimeout() > 0
        ? connection.getVertx().setTimer(config.getSendMessageTimeout(), id -> {
          if (timeoutReached.compareAndSet(false, true)) {
            final ServerErrorException exception = new ServerErrorException(
                HttpURLConnection.HTTP_UNAVAILABLE, "waiting for delivery update timed out after "
                    + config.getSendMessageTimeout() + "ms");
            LOG.debug(
                "waiting for delivery update timed out for message [message ID: {}] after {}ms",
                messageId, config.getSendMessageTimeout());
            TracingHelper.logError(currentSpan, exception.getMessage());
            Tags.HTTP_STATUS.set(currentSpan, HttpURLConnection.HTTP_UNAVAILABLE);
            currentSpan.finish();
          }
        })
        : null;

    LOG.debug("send request to manage subscription rule for [tenant-id: {}] with message [{}]",tenantId, message);
    final ProtonDelivery result = sender.send(message, deliveryUpdated -> {
      if (timerId != null) {
        connection.getVertx().cancelTimer(timerId);
      }
      final DeliveryState remoteState = deliveryUpdated.getRemoteState();
      if (timeoutReached.get()) {
        LOG.debug(
            "ignoring received delivery update for message [message ID: {}]: waiting for the update has already timed out",
            messageId);
      } else if (deliveryUpdated.remotelySettled()) {
        logUpdatedDeliveryState(currentSpan, messageId, deliveryUpdated);
      } else {
        LOG.warn("peer did not settle message [message ID: {}, remote state: {}]", messageId,
            remoteState);
        TracingHelper.logError(currentSpan,
            new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR,
                "peer did not settle message, failing delivery"));
      }
      currentSpan.finish();
    });
    LOG.trace("sent message [ID: {}], remaining credit: {}, queued messages: {}", messageId,
        sender.getCredit(), sender.getQueued());

    return Future.succeededFuture(result);
  }

  @Override
  protected Span startSpan(final SpanContext parent, final Message rawMessage) {

    final Span span = newFollowingSpan(parent, "forward Telemetry data");
    Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_PRODUCER);
    return span;
  }

  private Span startChildSpan(final SpanContext parent, final Message rawMessage) {

    final Span span = newChildSpan(parent, "forward Telemetry data");
    Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_PRODUCER);
    return span;
  }


  @Override
  public Future<ProtonDelivery> addDeviceFilter(final String tenantId, final String deviceId,
      final SpanContext spanContext) {
    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(deviceId);

    final Message message = createRuleMessage(tenantId, deviceId);

    return send(message, spanContext);
  }


  @Override
  public Future<ProtonDelivery> removeDeviceFilter(final String tenantId, final String deviceId,
      final SpanContext spanContext) {
    Objects.requireNonNull(tenantId);
      Objects.requireNonNull(deviceId);

      final Message message = removeRuleMessage(tenantId, deviceId);

      return send(message, spanContext);
  }

  private Message removeRuleMessage(final String tenantId, final String deviceId) {
    final Map<String, Object> requestBodyMap = new HashMap<>();
    requestBodyMap.put("rule-name", deviceId);

    return createRequestMessage("com.microsoft:remove-rule", requestBodyMap, 29800);
  }

  private Message createRuleMessage(final String tenantId, final String deviceId) {
    final Map<String, Object> requestBodyMap = new HashMap<>();
    requestBodyMap.put("rule-name", deviceId);

    final Map<String, Object> descriptionMap = new HashMap<>();

    final Map<String, Object> filterMap = new HashMap<>();
    filterMap.put("to", CommandClientImpl.getTargetAddress(tenantId, deviceId));

    descriptionMap.put("correlation-filter", filterMap);
    descriptionMap.put("rule-name", deviceId);
    descriptionMap.put("sql-rule-action", null);

    requestBodyMap.put("rule-description", descriptionMap);

    return createRequestMessage("com.microsoft:add-rule", requestBodyMap, 29800);
  }

  private static Message createRequestMessage(final String operation,
      final Map<String, Object> body, final long timeout) {
    final Message requestMessage = Message.Factory.create();
    requestMessage.setBody(new AmqpValue(body));
    final Map<String, Object> applicationPropertiesMap = new HashMap<>();
    applicationPropertiesMap.put("operation", operation);
    applicationPropertiesMap.put("com.microsoft:server-timeout", timeout);
    requestMessage.setApplicationProperties(new ApplicationProperties(applicationPropertiesMap));

    return requestMessage;
  }


}

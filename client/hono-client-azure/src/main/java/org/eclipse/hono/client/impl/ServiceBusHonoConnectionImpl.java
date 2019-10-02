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

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Objects;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.eclipse.hono.client.AzureHonoConnection;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.StatusCodeMapper;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.util.HonoProtonHelper;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;

public class ServiceBusHonoConnectionImpl extends HonoConnectionImpl implements AzureHonoConnection {

  public ServiceBusHonoConnectionImpl(final Vertx vertx, final ClientConfigProperties clientConfigProperties) {
    super(vertx, clientConfigProperties);
  }


  public final Future<ProtonSender> createRequestReplySender(
          final String targetAddress,
          final ProtonQoS qos,
          final Handler<String> closeHook,
          final Map<Symbol, Object> properties,
          final String replyTo) {

      Objects.requireNonNull(targetAddress);
      Objects.requireNonNull(qos);

      return executeOrRunOnContext(result -> {
          checkConnected().compose(v -> {
              final Future<ProtonSender> senderFuture = Future.future();
              final ProtonSender sender = connection.createSender(targetAddress);
              sender.setQoS(qos);
              sender.setAutoSettle(true);

              if (replyTo != null) {
                final Source senderSource = new Source();
                senderSource.setAddress(replyTo);
                sender.setSource(senderSource);
              }
              if (properties != null) {
                sender.setProperties(properties);
              }
              sender.openHandler(senderOpen -> {

                  // we only "try" to complete/fail the result future because
                  // it may already have been failed if the connection broke
                  // away after we have sent our attach frame but before we have
                  // received the peer's attach frame

                  if (senderOpen.failed()) {
                      // this means that we have received the peer's attach
                      // and the subsequent detach frame in one TCP read
                      final ErrorCondition error = sender.getRemoteCondition();
                      if (error == null) {
                          log.debug("opening sender [{}] failed", targetAddress, senderOpen.cause());
                          senderFuture.tryFail(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND,
                                  "cannot open sender", senderOpen.cause()));
                      } else {
                          log.debug("opening sender [{}] failed: {} - {}", targetAddress, error.getCondition(), error.getDescription());
                          senderFuture.tryFail(StatusCodeMapper.from(error));
                      }

                  } else if (HonoProtonHelper.isLinkEstablished(sender)) {

                    log.debug("sender open [target: {}, sendQueueFull: {}]", targetAddress, sender.sendQueueFull());
                    // wait on credits a little time, if not already given
                    if (sender.getCredit() <= 0) {
                        final long waitOnCreditsTimerId = vertx.setTimer(clientConfigProperties.getFlowLatency(),
                                timerID -> {
                                    log.debug("sender [target: {}] has {} credits after grace period of {}ms",
                                            targetAddress,
                                            sender.getCredit(), clientConfigProperties.getFlowLatency());
                                    sender.sendQueueDrainHandler(null);
                                    senderFuture.tryComplete(sender);
                                });
                        sender.sendQueueDrainHandler(replenishedSender -> {
                            log.debug("sender [target: {}] has received {} initial credits",
                                    targetAddress, replenishedSender.getCredit());
                            if (vertx.cancelTimer(waitOnCreditsTimerId)) {
                                result.tryComplete(replenishedSender);
                                replenishedSender.sendQueueDrainHandler(null);
                            } // otherwise the timer has already completed the future and cleaned up
                              // sendQueueDrainHandler
                        });
                    } else {
                        senderFuture.tryComplete(sender);
                    }

                } else {
                      // this means that the peer did not create a local terminus for the link
                      // and will send a detach frame for closing the link very shortly
                      // see AMQP 1.0 spec section 2.6.3
                      log.debug("peer did not create terminus for target [{}] and will detach the link", targetAddress);
                      senderFuture.tryFail(new ServerErrorException(HttpURLConnection.HTTP_UNAVAILABLE));
                  }
              });
              HonoProtonHelper.setDetachHandler(sender,
                      remoteDetached -> onRemoteDetach(sender, connection.getRemoteContainer(), false, closeHook));
              HonoProtonHelper.setCloseHandler(sender,
                      remoteClosed -> onRemoteDetach(sender, connection.getRemoteContainer(), true, closeHook));
              sender.open();

              if (replyTo != null) {
                final ProtonReceiver receiver = connection.createReceiver(targetAddress);
                final Target receiverTarget = new Target();
                receiverTarget.setAddress(replyTo);
                receiver.setTarget(receiverTarget);

                if (properties != null) {
                  receiver.setProperties(properties);
                }

                receiver.openHandler(recvOpen -> {
                    // we only "try" to complete/fail the result future because
                    // it may already have been failed if the connection broke
                    // away after we have sent our attach frame but before we have
                    // received the peer's attach frame

                    if (recvOpen.failed()) {
                        // this means that we have received the peer's attach
                        // and the subsequent detach frame in one TCP read
                        final ErrorCondition error = receiver.getRemoteCondition();
                        if (error == null) {
                            log.debug("opening receiver for replyTo [{}] failed", replyTo, recvOpen.cause());
                            result.tryFail(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND,
                                    "cannot open receiver", recvOpen.cause()));
                        } else {
                            log.debug("opening receiver for replyTo [{}] failed: {} - {}", replyTo, error.getCondition(), error.getDescription());
                            result.tryFail(StatusCodeMapper.from(error));
                        }
                    } else if (HonoProtonHelper.isLinkEstablished(receiver)) {
                        log.debug("receiver open for replyTo [{}]", replyTo);
                    } else {
                        // this means that the peer did not create a local terminus for the link
                        // and will send a detach frame for closing the link very shortly
                        // see AMQP 1.0 spec section 2.6.3
                        log.debug("peer did not create terminus for replyTo [{}] and will detach the link", replyTo);
                    }
                });

                receiver.open();
              }


              vertx.setTimer(clientConfigProperties.getLinkEstablishmentTimeout(),
                      tid -> onTimeOut(sender, clientConfigProperties, senderFuture));
              return senderFuture;
          }).setHandler(result);
      });
  }

}

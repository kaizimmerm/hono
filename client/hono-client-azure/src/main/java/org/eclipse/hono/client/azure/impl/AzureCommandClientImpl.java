package org.eclipse.hono.client.azure.impl;

import java.util.Objects;

import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.impl.CommandClientImpl;

import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;

public class AzureCommandClientImpl extends CommandClientImpl {

	AzureCommandClientImpl(final HonoConnection connection, final String tenantId, final String replyId) {
		super(connection, String.format("%s/%s", getName(), tenantId), String.format("%s/%s", getReplyToEndpointName(), tenantId), tenantId, connection.getConfig().getRequestTimeout());

	}

	protected AzureCommandClientImpl(final HonoConnection connection, final String tenantId, final String replyId,
			final ProtonSender sender, final ProtonReceiver receiver) {

		this(connection, tenantId, replyId);
		this.sender = Objects.requireNonNull(sender);
		this.receiver = Objects.requireNonNull(receiver);
	}

}

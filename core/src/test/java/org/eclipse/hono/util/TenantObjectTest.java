/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.util;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import javax.security.auth.x500.X500Principal;

import org.junit.BeforeClass;
import org.junit.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


/**
 * Verifies behavior of {@link TenantObject}.
 *
 */
public class TenantObjectTest {

    private static final String TRUST_STORE_PATH = "target/certs/trustStore.jks";
    private static final String TRUST_STORE_PASSWORD = "honotrust";
    private static X509Certificate trustedCaCert;

    /**
     * Sets up static fixture.
     * 
     * @throws IOException if the trust store could not be read.
     * @throws GeneralSecurityException if the trust store could not be read.
     */
    @BeforeClass
    public static void init() throws GeneralSecurityException, IOException {
        trustedCaCert = getCaCertificate();
    }

    private static X509Certificate getCaCertificate() throws GeneralSecurityException, IOException {

        try (InputStream is = new FileInputStream(TRUST_STORE_PATH)) {
            final KeyStore store = KeyStore.getInstance("JKS");
            store.load(is, TRUST_STORE_PASSWORD.toCharArray());
            return (X509Certificate) store.getCertificate("ca");
        }
    }

    /**
     * Verifies that a JSON string containing custom configuration
     * properties can be deserialized into a {@code TenantObject}.
     */
    @Test
    public void testDeserializationOfCustomAdapterConfigProperties() {

        final JsonObject defaults = new JsonObject().put("time-to-live", 60);
        final JsonObject deploymentValue = new JsonObject().put("maxInstances", 4);
        final JsonObject adapterConfig = new JsonObject()
                .put(TenantConstants.FIELD_ADAPTERS_TYPE, "custom")
                .put(TenantConstants.FIELD_ENABLED, true)
                .put("deployment", deploymentValue);
        final JsonObject config = new JsonObject()
                .put(TenantConstants.FIELD_PAYLOAD_TENANT_ID, "my-tenant")
                .put(TenantConstants.FIELD_ENABLED, true)
                .put("plan", "gold")
                .put(TenantConstants.FIELD_ADAPTERS, new JsonArray().add(adapterConfig))
                .put(TenantConstants.FIELD_PAYLOAD_DEFAULTS, defaults);

        final TenantObject obj = config.mapTo(TenantObject.class);
        assertNotNull(obj);
        assertThat(obj.getProperty("plan", String.class), is("gold"));

        final JsonObject customAdapterConfig = obj.getAdapterConfiguration("custom");
        assertNotNull(customAdapterConfig);
        assertThat(customAdapterConfig.getJsonObject("deployment"), is(deploymentValue));

        final JsonObject defaultProperties = obj.getDefaults();
        assertThat(defaultProperties.getInteger("time-to-live"), is(60));
    }

    /**
     * Verifies that tenant deserialized from a JSON object containing an empty
     * trusted-ca array contains an empty set of trust anchors.
     */
    @Test
    public void testDeserializationOfEmptyTrustedAuthoritiesArray() {

        final JsonObject config = new JsonObject()
                .put(TenantConstants.FIELD_PAYLOAD_TENANT_ID, "my-tenant")
                .put(TenantConstants.FIELD_PAYLOAD_TRUSTED_CA, new JsonArray());

        final TenantObject tenant = config.mapTo(TenantObject.class);
        assertThat(tenant.getTrustAnchors(), is(not(nullValue())));
        assertThat(tenant.getTrustAnchors(), empty());
    }

    /**
     * Verifies that a trust anchor can be deserialized from a Base64 encoded public key.
     * 
     * @throws GeneralSecurityException if the trust anchor cannot be deserialized.
     */
    @Test
    public void testDeserializationOfPublicKeyTrustAnchors() throws GeneralSecurityException {

        final JsonObject config = new JsonObject()
                .put(TenantConstants.FIELD_PAYLOAD_TENANT_ID, "my-tenant")
                .put(TenantConstants.FIELD_ENABLED, true)
                .put(TenantConstants.FIELD_PAYLOAD_TRUSTED_CA, new JsonArray().add(
                        new JsonObject()
                        .put(TenantConstants.FIELD_PAYLOAD_SUBJECT_DN, trustedCaCert.getSubjectX500Principal().getName(X500Principal.RFC2253))
                        .put(TenantConstants.FIELD_PAYLOAD_PUBLIC_KEY, Base64.getEncoder().encodeToString(trustedCaCert.getPublicKey().getEncoded()))
                        .put(TenantConstants.FIELD_PAYLOAD_KEY_ALGORITHM, trustedCaCert.getPublicKey().getAlgorithm())));

        final TenantObject tenant = config.mapTo(TenantObject.class);
        assertThat(tenant.getTrustAnchors(), not(empty()));
        final TrustAnchor ca = tenant.getTrustAnchors().iterator().next();
        assertThat(ca.getCA(), is(trustedCaCert.getSubjectX500Principal()));
        assertThat(ca.getCAPublicKey(), is(trustedCaCert.getPublicKey()));
    }

    /**
     * Verifies that all adapters are enabled if no specific
     * configuration has been set.
     */
    @Test
    public void testIsAdapterEnabledForEmptyConfiguration() {

        final TenantObject obj = TenantObject.from(Constants.DEFAULT_TENANT, Boolean.TRUE);
        assertTrue(obj.isAdapterEnabled("any-type"));
        assertTrue(obj.isAdapterEnabled("any-other-type"));
    }

    /**
     * Verifies that all adapters are disabled for a disabled tenant.
     */
    @Test
    public void testIsAdapterEnabledForDisabledTenant() {

        final TenantObject obj = TenantObject.from(Constants.DEFAULT_TENANT, Boolean.FALSE);
        obj.addAdapterConfiguration(TenantObject.newAdapterConfig("type-one", true));
        assertFalse(obj.isAdapterEnabled("type-one"));
        assertFalse(obj.isAdapterEnabled("any-other-type"));
    }

    /**
     * Verifies that all adapters are disabled by default except for
     * the ones explicitly configured.
     */
    @Test
    public void testIsAdapterEnabledForNonEmptyConfiguration() {

        final TenantObject obj = TenantObject.from(Constants.DEFAULT_TENANT, Boolean.TRUE);
        obj.addAdapterConfiguration(TenantObject.newAdapterConfig("type-one", true));
        assertTrue(obj.isAdapterEnabled("type-one"));
        assertFalse(obj.isAdapterEnabled("any-other-type"));
    }

    /**
     * Verifies that the trust anchor uses the configured trusted CA's public key and subject DN.
     * 
     * @throws GeneralSecurityException if the certificate cannot be DER encoded.
     */
    @Test
    public void testGetTrustAnchorsUsesPublicKey() throws GeneralSecurityException {

        final TenantObject obj = TenantObject.from(Constants.DEFAULT_TENANT, Boolean.TRUE)
                .setTrustAnchor(trustedCaCert.getPublicKey(), trustedCaCert.getSubjectX500Principal());

        assertThat(obj.getTrustAnchors(), not(empty()));
        final TrustAnchor trustAnchor = obj.getTrustAnchors().iterator().next();
        assertThat(trustAnchor.getCA(), is(trustedCaCert.getSubjectX500Principal()));
        assertThat(trustAnchor.getCAPublicKey(), is(trustedCaCert.getPublicKey()));
    }

    /**
     * Verifies that the trust anchor cannot be read from an invalid Base64 encoding of
     * a public key.
     */
    @Test
    public void testGetTrustAnchorFailsForInvalidBase64EncodingOfPublicKey() {

        final TenantObject obj = TenantObject.from(Constants.DEFAULT_TENANT, Boolean.TRUE)
                .setProperty(
                        TenantConstants.FIELD_PAYLOAD_TRUSTED_CA,
                        new JsonArray().add(new JsonObject()
                        .put(TenantConstants.FIELD_PAYLOAD_SUBJECT_DN, "CN=test")
                        .put(TenantConstants.FIELD_PAYLOAD_PUBLIC_KEY, "noBase64".getBytes(StandardCharsets.UTF_8))));

        assertThat(obj.getTrustAnchors(), empty());
    }

    /**
     * Verifies that the maximum <em>time till disconnect</em> value is read from the extensions 
     * section of the given adapter configuration.
     */
    @Test
    public void testGetMaxTTDFromAdapterConfiguration() {
        final TenantObject tenantObject = TenantObject.from(Constants.DEFAULT_TENANT, true);
        tenantObject.addAdapterConfiguration(
                TenantObject.newAdapterConfig("custom", true)
                        .put(TenantConstants.FIELD_EXT, new JsonObject()
                                .put(TenantConstants.FIELD_MAX_TTD, 10)));
        assertThat(tenantObject.getMaxTimeUntilDisconnect("custom"), is(10));
    }

    /**
     * Verifies that the default value is used if no maximum <em>time till disconnect</em> value 
     * is set.
     */
    @Test
    public void testGetMaxTTDReturnsDefaultValue() {
        final TenantObject obj = TenantObject.from(Constants.DEFAULT_TENANT, true);
        assertThat(obj.getMaxTimeUntilDisconnect("custom"), is(TenantConstants.DEFAULT_MAX_TTD));
    }

    /**
     * Verifies that the default value is used if the max TTD is set to a value &lt; 0.
     */
    @Test
    public void testGetMaxTTDReturnsDefaultValueInsteadOfIllegalValue() {
        final TenantObject tenantObject = TenantObject.from(Constants.DEFAULT_TENANT, true);
        tenantObject.addAdapterConfiguration(
                TenantObject.newAdapterConfig("custom", true)
                        .put(TenantConstants.FIELD_EXT, new JsonObject()
                                .put(TenantConstants.FIELD_MAX_TTD, -10)));
        assertThat(tenantObject.getMaxTimeUntilDisconnect("custom"), is(TenantConstants.DEFAULT_MAX_TTD));
    }

    /**
     * Verifies that the resource-limits are set based on the configuration.
     */
    @Test
    public void testGetResourceLimits() {
        final JsonObject limitsConfig = new JsonObject()
                .put("max-connections", 2)
                .put("data-volume",
                        new JsonObject().put("max-bytes", 20_000_000)
                                .put("effective-since", "2019-04-25T14:30:00Z")
                                .put("period", new JsonObject()
                                        .put("mode", "days")
                                        .put("no-of-days", 90)));
        final TenantObject tenantObject = TenantObject.from(Constants.DEFAULT_TENANT, true);
        tenantObject.setResourceLimits(limitsConfig);
        assertNotNull(tenantObject.getResourceLimits());
        assertThat(tenantObject.getResourceLimits().getMaxConnections(), is(2));
        assertThat(tenantObject.getResourceLimits().getDataVolume().getMaxBytes(), is(20_000_000L));
        assertThat(tenantObject.getResourceLimits().getDataVolume().getEffectiveSince(), is(
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse("2019-04-25T14:30:00Z", OffsetDateTime::from).toInstant()));
        assertThat(tenantObject.getResourceLimits().getDataVolume().getPeriod().getMode(), is("days"));
        assertThat(tenantObject.getResourceLimits().getDataVolume().getPeriod().getNoOfDays(), is(90));
    }

    /**
     * Verifies that {@code null} is returned when resource limits are not set.
     */
    @Test
    public void testGetResourceLimitsWhenNotSet() {
        final TenantObject tenantObject = TenantObject.from(Constants.DEFAULT_TENANT, true);
        assertThat(tenantObject.getResourceLimits(), is(nullValue()));
    }

    /**
     * Verifies that the minimum message size is returned based on the configured value.
     */
    @Test
    public void testGetMinimumMessageSize() {

        final TenantObject tenantObject = TenantObject
                .from(Constants.DEFAULT_TENANT, true)
                .setMinimumMessageSize(4 * 1024);

        assertEquals(4 * 1024, tenantObject.getMinimumMessageSize());
    }

    /**
     * Verifies that default value is returned when no minimum message size is set.
     */
    @Test
    public void testGetMinimumMessageSizeNotSet() {

        final TenantObject tenantObject = TenantObject
                .from(Constants.DEFAULT_TENANT, true);

        assertEquals(TenantConstants.DEFAULT_MINIMUM_MESSAGE_SIZE, tenantObject.getMinimumMessageSize());
    }
}

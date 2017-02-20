/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Arnaud Kervern
 */
package org.nuxeo.ecm.platform.oauth.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.platform.ui.web.auth.oauth2.NuxeoOAuth2Filter.ERRORS;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.codehaus.jackson.map.ObjectMapper;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.ClientResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.platform.oauth2.clients.ClientRegistry;
import org.nuxeo.ecm.platform.oauth2.clients.OAuth2Client;
import org.nuxeo.ecm.platform.oauth2.request.AuthorizationRequest;
import org.nuxeo.ecm.webengine.test.WebEngineFeature;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.Jetty;
import org.nuxeo.runtime.transaction.TransactionHelper;


/**
 * @author <a href="mailto:ak@nuxeo.com">Arnaud Kervern</a>
 * @since 5.9.2
 */
@RunWith(FeaturesRunner.class)
@Features({ OAuthFeature.class, WebEngineFeature.class })
@Jetty(port = 18090)
public class TestOauth2Challenge {

    protected static final String CLIENT_ID = "testClient";

    protected static final String CLIENT_SECRET = "testSecret";

    protected static final String BASE_URL = "http://localhost:18090";

    private static final Integer TIMEOUT = Integer.valueOf(1000 * 60 * 5); // 5min

    @Inject
    protected ClientRegistry clientRegistry;

    protected Client client;

    @Before
    public void initOAuthClient() {

        if (!clientRegistry.hasClient(CLIENT_ID)) {
            OAuth2Client oauthClient = new OAuth2Client("Dummy", CLIENT_ID, CLIENT_SECRET);
            assertTrue(clientRegistry.registerClient(oauthClient));

            // commit the transaction so that the HTTP thread finds the newly created directory entry
            if (TransactionHelper.isTransactionActiveOrMarkedRollback()) {
                TransactionHelper.commitOrRollbackTransaction();
                TransactionHelper.startTransaction();
            }
        }

        // First client to request like a "Client" as OAuth RFC describe it
        client = ClientBuilder.newBuilder()
                              .property(ClientProperties.CONNECT_TIMEOUT, TIMEOUT)
                              .property(ClientProperties.READ_TIMEOUT, TIMEOUT)
                              .property(ClientProperties.FOLLOW_REDIRECTS, Boolean.FALSE)
                              .build();

        TestAuthorizationRequest.getRequests().clear();
    }

    @Test
    public void authorizationShouldRedirectToJSP() {
        // Request an code
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "Dummy");
        params.put("client_id", CLIENT_ID);
        params.put("response_type", "code");

        ClientResponse cr = responseFromAuthorizationWith(params);
        assertEquals(302, cr.getStatus());

        String redirect = cr.getHeaders().get("Location").get(0);
        assertTrue(redirect.contains(".jsp"));
    }

    @Test
    public void authorizationShouldForbidsUnknownClient() {
        // Request an code
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "Dummy");
        params.put("client_id", "unknow");
        params.put("response_type", "code");

        ClientResponse cr = responseFromAuthorizationWith(params);
        assertEquals(302, cr.getStatus());
        String redirect = cr.getHeaders().get("Location").get(0);
        assertTrue(redirect.contains("error=" + ERRORS.unauthorized_client));
    }

    @Test
    public void tokenShouldCreateAndRefreshWithDummyAuthorization() throws IOException {
        AuthorizationRequest request = new TestAuthorizationRequest(CLIENT_ID, "code", null, "Dummy", new Date());
        TestAuthorizationRequest.getRequests().put("fake", request);

        // Request a token
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "Dummy");
        params.put("client_id", CLIENT_ID);
        params.put("client_secret", CLIENT_SECRET);
        params.put("grant_type", "authorization_code");
        params.put("code", request.getAuthorizationCode());

        ClientResponse cr = responseFromTokenWith(params);
        assertEquals(200, cr.getStatus());
        String json = (String) cr.getEntity();

        ObjectMapper obj = new ObjectMapper();

        Map<?, ?> token = obj.readValue(json, Map.class);
        assertNotNull(token);
        String accessToken = (String) token.get("access_token");
        assertEquals(32, accessToken.length());

        // Refresh this token
        params.remove("code");
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", (String) token.get("refresh_token"));
        cr = responseFromTokenWith(params);
        assertEquals(200, cr.getStatus());

        json = (String) cr.getEntity();
        Map<?, ?> refreshed = obj.readValue(json, Map.class);

        assertNotSame(refreshed.get("access_token"), token.get("access_token"));
    }

    protected ClientResponse responseFromTokenWith(Map<String, String> queryParams) {
        WebTarget target = client.target(BASE_URL).path("oauth2").path("token");

        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            target = target.queryParam(entry.getKey(), entry.getValue());
        }

        return target.request().get(ClientResponse.class);
    }

    protected ClientResponse responseFromAuthorizationWith(Map<String, String> queryParams) {
        WebTarget target = client.target(BASE_URL).path("oauth2").path("authorization");

        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            target = target.queryParam(entry.getKey(), entry.getValue());
        }

        return target.request().get(ClientResponse.class);
    }

    protected ClientResponse responseFromUrl(String url, String accessToken) {
        WebTarget target = client.target(BASE_URL).path(url);
        return target.request().header("Authentication", "Bearer " + accessToken).get(ClientResponse.class);
    }
}

package org.restlet.test.ext.oauth.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.logging.Logger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Server;
import org.restlet.data.MediaType;
import org.restlet.data.Parameter;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.engine.Engine;
import org.restlet.engine.security.AuthenticatorHelper;
import org.restlet.ext.oauth.OAuthHelper;
import org.restlet.ext.oauth.OAuthUser;
import org.restlet.ext.openid.OpenIdFormFrowarder;
import org.restlet.ext.oauth.internal.CookieCopyClientResource;
import org.restlet.ext.oauth.internal.OAuthUtils;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.ResourceException;
import org.restlet.test.ext.oauth.test.resources.OAuthClientTestApplication;
import org.restlet.test.ext.oauth.test.resources.OauthProtectedTestApplication;
import org.restlet.test.ext.oauth.test.resources.OauthTestApplication;
import org.restlet.util.Series;

public class TimedTokenTest {
    public static Component component;

    // Use for http test when debugging
    public static int serverPort = 8080;

    public static final String prot = "http";

    // public static int serverPort = 8443;
    // public static final String prot = "https";

    public static OAuthClientTestApplication client = new OAuthClientTestApplication();

    @BeforeClass
    public static void startServer() throws Exception {
        Logger log = Context.getCurrentLogger();
        log.info("Starting timed server test!");

        // SSL global configuration
        String keystore = ClassLoader.getSystemResource("localhost.jks")
                .getPath();
        System.setProperty("javax.net.ssl.trustStore", keystore);
        System.setProperty("javax.net.ssl.trustStoreType", "JKS");
        System.setProperty("javax.net.ssl.trustStorePassword", "testpass");

        // Server server = new Server(Protocol.HTTPS, serverPort);
        Server server = new Server( new Context(), Protocol.HTTP, serverPort);
        //Strange workaround for the server to not hang.
        server.getContext().getParameters().add("maxQueued", "0");
        component = new Component();
        component.getServers().add(server);
        component.getClients().add(Protocol.HTTP);
        component.getClients().add(Protocol.HTTPS);
        // component.getClients().add(Protocol.CLAP);
        component.getClients().add(Protocol.RIAP);
        component.getDefaultHost().attach("/oauth",
                new OauthTestApplication(20)); // limited token life
        component.getDefaultHost().attach("/client", client);
        component.getDefaultHost().attach("/server",
                new OauthProtectedTestApplication());
        // server.getContext().getParameters().add("maxThreads", "30");

        // Setup TLS
        Series<Parameter> parameters = server.getContext().getParameters();
        parameters.add("keystorePath", keystore);
        parameters.add("keystorePassword", "testpass");
        parameters.add("keyPassword", "testpass");
        parameters.add("keystoreType", "JKS");
        parameters.add("sslServerAlias", "localhost");

        component.start();

        List<AuthenticatorHelper> authenticators = Engine.getInstance()
                .getRegisteredAuthenticators();
        for (AuthenticatorHelper helper : authenticators) {
            System.out.println("Found default auth helper : " + helper);
        }
        authenticators.add(new OAuthHelper());

        System.out.println(Engine.getInstance().getRegisteredClients().get(0));
    }

    @AfterClass
    public static void stopServer() throws Exception {
        component.stop();
    }

    @Test
    public void testTimedTokens() throws Exception {
        client.clearUser();
        assertNull(client.getToken());
        ClientResource cr = new CookieCopyClientResource(prot + "://localhost:"
                + serverPort + "/client/webclient");
        Representation r = cr.get();
        assertNotNull(r);
        r = OpenIdFormFrowarder.handleFormRedirect(r, cr);
        assertNotNull(r);
        String text = r.getText();
        assertEquals("Response text test", text, "TestSuccessful");
        assertEquals("Response content type test", r.getMediaType(),
                MediaType.TEXT_HTML);
        assertNotNull(client.getToken());
        cr.release();
        // Query test
        assertNotNull(client.getToken());
        Reference ref = new Reference(prot + "://localhost:" + serverPort
                + "/server/protected");
        ref.addQueryParameter("oauth_token", client.getToken());
        cr = new ClientResource(ref);
        r = cr.get();
        assertNotNull(r);
        text = r.getText();
        assertEquals("Response text test", text, "TestSuccessful");
        assertEquals("Response content type test", r.getMediaType(),
                MediaType.TEXT_HTML);
        cr.release();
        Thread.sleep(20000);
        // Another query test...
        assertNotNull(client.getToken());
        ref.addQueryParameter("oauth_token", client.getToken());
        cr = new ClientResource(ref);
        try {
            r = cr.get();
        } catch (ResourceException re) {
            assertEquals(Status.CLIENT_ERROR_FORBIDDEN, re.getStatus());
        }
        cr.release();
        assertNotNull(r);
    }

    @Test
    public void testRefresh() throws Exception {
        OAuthUser user = client.getUser();
        assertNotNull(user);
        OAuthUser refreshed = OAuthUtils
                .refreshToken(client.getOauthParameters(), client.getUser()
                        .getRefreshToken());
        assertNotNull(user);
        String wrongToken = refreshed.getAccessToken();
        assertNotNull(wrongToken);

        // Back to back test
        refreshed = OAuthUtils.refreshToken(client.getOauthParameters(),
                client.getUser().getRefreshToken());
        String newToken = refreshed.getAccessToken();
        assertNotNull(newToken);

        // Query test
        Reference ref = new Reference(prot + "://localhost:" + serverPort
                + "/server/protected");
        ref.addQueryParameter("oauth_token", newToken);
        ClientResource cr = new ClientResource(ref);
        Representation r = cr.get();
        assertNotNull(r);
        String text = r.getText();
        assertEquals("Response text test", text, "TestSuccessful");
        assertEquals("Response content type test", r.getMediaType(),
                MediaType.TEXT_HTML);
        cr.release();
        // Check the token we got before
        ref = new Reference(prot + "://localhost:" + serverPort
                + "/server/protected");
        ref.addQueryParameter("oauth_token", wrongToken);
        cr = new ClientResource(ref);
        try {
            r = cr.get();
        } catch (ResourceException re) { // Should be invalidated
            assertEquals(Status.CLIENT_ERROR_FORBIDDEN, re.getStatus());
        }
        cr.release();
        Thread.sleep(20000);
        // Another query test...
        assertNotNull(client.getToken());
        ref.addQueryParameter("oauth_token", client.getToken());
        cr = new ClientResource(ref);
        try {
            r = cr.get();
        } catch (ResourceException re) {
            assertEquals(Status.CLIENT_ERROR_FORBIDDEN, re.getStatus());
        }
        cr.release();
        assertNotNull(r);
    }

}

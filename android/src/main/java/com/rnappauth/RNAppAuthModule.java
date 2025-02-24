package com.rnappauth;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.rnappauth.utils.MapUtil;
import com.rnappauth.utils.UnsafeConnectionBuilder;
import com.rnappauth.utils.TokenResponseFactory;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ClientAuthentication;
import net.openid.appauth.ClientSecretBasic;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenResponse;
import net.openid.appauth.TokenRequest;
import net.openid.appauth.connectivity.ConnectionBuilder;
import net.openid.appauth.connectivity.DefaultConnectionBuilder;

import java.util.HashMap;
import java.util.Map;

public class RNAppAuthModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private final ReactApplicationContext reactContext;
    private Promise promise;
    private Boolean dangerouslyAllowInsecureHttpRequests;
    private Map<String, String> additionalParametersMap;
    private String clientSecret;

    public RNAppAuthModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        reactContext.addActivityEventListener(this);
    }
    @Override
    public void onNewIntent(Intent intent) {

    }

    @ReactMethod
    public void authorize(
            String issuer,
            final String redirectUrl,
            final String clientId,
            final String clientSecret,
            final ReadableArray scopes,
            final ReadableMap additionalParameters,
            final ReadableMap serviceConfiguration,
            final Boolean dangerouslyAllowInsecureHttpRequests,
            final Promise promise
    ) {
        final ConnectionBuilder builder = createConnectionBuilder(dangerouslyAllowInsecureHttpRequests);
        final AppAuthConfiguration appAuthConfiguration = this.createAppAuthConfiguration(builder);
        final HashMap<String, String> additionalParametersMap = MapUtil.readableMapToHashMap(additionalParameters);

        if (clientSecret != null) {
            additionalParametersMap.put("client_secret", clientSecret);
        }

        // store args in private fields for later use in onActivityResult handler
        this.promise = promise;
        this.dangerouslyAllowInsecureHttpRequests = dangerouslyAllowInsecureHttpRequests;
        this.additionalParametersMap = additionalParametersMap;
        this.clientSecret = clientSecret;

        // when serviceConfiguration is provided, we don't need to hit up the OpenID well-known id endpoint
        if (serviceConfiguration != null) {
            try {
                authorizeWithConfiguration(
                        createAuthorizationServiceConfiguration(serviceConfiguration),
                        appAuthConfiguration,
                        clientId,
                        scopes,
                        redirectUrl,
                        additionalParametersMap
                );
            } catch (Exception e) {
                promise.reject("Failed to authenticate", e.getMessage());
            }
        } else {
            final Uri issuerUri = Uri.parse(issuer);
            AuthorizationServiceConfiguration.fetchFromUrl(
                    buildConfigurationUriFromIssuer(issuerUri),
                    new AuthorizationServiceConfiguration.RetrieveConfigurationCallback() {
                        public void onFetchConfigurationCompleted(
                                @Nullable AuthorizationServiceConfiguration fetchedConfiguration,
                                @Nullable AuthorizationException ex) {
                            if (ex != null) {
                                promise.reject("Failed to fetch configuration", ex.errorDescription);
                                return;
                            }

                            authorizeWithConfiguration(
                                    fetchedConfiguration,
                                    appAuthConfiguration,
                                    clientId,
                                    scopes,
                                    redirectUrl,
                                    additionalParametersMap
                            );
                        }
                    },
                    builder
            );
        }




    }

    @ReactMethod
    public void refresh(
            String issuer,
            final String redirectUrl,
            final String clientId,
            final String clientSecret,
            final String refreshToken,
            final ReadableArray scopes,
            final ReadableMap additionalParameters,
            final ReadableMap serviceConfiguration,
            final Boolean dangerouslyAllowInsecureHttpRequests,
            final Promise promise
    ) {
        final ConnectionBuilder builder = createConnectionBuilder(dangerouslyAllowInsecureHttpRequests);
        final AppAuthConfiguration appAuthConfiguration = createAppAuthConfiguration(builder);
        final HashMap<String, String> additionalParametersMap = MapUtil.readableMapToHashMap(additionalParameters);

        if (clientSecret != null) {
            additionalParametersMap.put("client_secret", clientSecret);
        }

        // store setting in private field for later use in onActivityResult handler
        this.dangerouslyAllowInsecureHttpRequests = dangerouslyAllowInsecureHttpRequests;
        this.additionalParametersMap = additionalParametersMap;

        // when serviceConfiguration is provided, we don't need to hit up the OpenID well-known id endpoint
        if (serviceConfiguration != null) {
            try {
                refreshWithConfiguration(
                        createAuthorizationServiceConfiguration(serviceConfiguration),
                        appAuthConfiguration,
                        refreshToken,
                        clientId,
                        scopes,
                        redirectUrl,
                        additionalParametersMap,
                        clientSecret,
                        promise
                );
            } catch (Exception e) {
                promise.reject("Failed to refresh token", e.getMessage());
            }
        } else {
            final Uri issuerUri = Uri.parse(issuer);
            // @TODO: Refactor to avoid hitting IDP endpoint on refresh, reuse fetchedConfiguration if possible.
            AuthorizationServiceConfiguration.fetchFromUrl(
                    buildConfigurationUriFromIssuer(issuerUri),
                    new AuthorizationServiceConfiguration.RetrieveConfigurationCallback() {
                        public void onFetchConfigurationCompleted(
                                @Nullable AuthorizationServiceConfiguration fetchedConfiguration,
                                @Nullable AuthorizationException ex) {
                            if (ex != null) {
                                promise.reject("Failed to fetch configuration", ex.errorDescription);
                                return;
                            }

                            refreshWithConfiguration(
                                    fetchedConfiguration,
                                    appAuthConfiguration,
                                    refreshToken,
                                    clientId,
                                    scopes,
                                    redirectUrl,
                                    additionalParametersMap,
                                    clientSecret,
                                    promise
                            );
                        }
                    },
                    builder);
        }

    }

    /*
     * Called when the OAuth browser activity completes
     */
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode == 0) {
            final AuthorizationResponse response = AuthorizationResponse.fromIntent(data);
            AuthorizationException exception = AuthorizationException.fromIntent(data);
            if (exception != null) {
                promise.reject("Failed to authenticate", exception.errorDescription);
                return;
            }

            if (this.additionalParametersMap.containsKey("skipTokenExchange")
                    && this.additionalParametersMap.get("skipTokenExchange") != null
                    && this.additionalParametersMap.get("skipTokenExchange").equals("true")) {
                WritableMap map = Arguments.createMap();
                map.putString("code", response.authorizationCode);
                map.putString("state", response.state);
                map.putString("redirectUri", response.request.redirectUri.toString());
                promise.resolve(map);
                return;
            }

            final Promise authorizePromise = this.promise;
            final AppAuthConfiguration configuration = createAppAuthConfiguration(
                    createConnectionBuilder(this.dangerouslyAllowInsecureHttpRequests)
            );

            AuthorizationService authService = new AuthorizationService(this.reactContext, configuration);

            TokenRequest tokenRequest = response.createTokenExchangeRequest(this.additionalParametersMap);

            AuthorizationService.TokenResponseCallback tokenResponseCallback
                    = new AuthorizationService.TokenResponseCallback() {
                @Override
                public void onTokenRequestCompleted(
                        TokenResponse resp, AuthorizationException ex) {
                    if (resp != null) {
                        WritableMap map = TokenResponseFactory.tokenResponseToMap(resp, response);
                        authorizePromise.resolve(map);
                    } else {
                        promise.reject("Failed exchange token", ex.errorDescription);
                    }
                }
            };

            if (this.clientSecret != null) {
                ClientAuthentication clientAuth = new ClientSecretBasic(this.clientSecret);
                authService.performTokenRequest(tokenRequest, clientAuth, tokenResponseCallback);

            } else {
                authService.performTokenRequest(tokenRequest, tokenResponseCallback);
            }

        }
    }

    /*
     * Authorize user with the provided configuration
     */
    private void authorizeWithConfiguration(
            final AuthorizationServiceConfiguration serviceConfiguration,
            final AppAuthConfiguration appAuthConfiguration,
            final String clientId,
            final ReadableArray scopes,
            final String redirectUrl,
            final Map<String, String> additionalParametersMap
    ) {

        String scopesString = null;

        if (scopes != null) {
            scopesString = this.arrayToString(scopes);
        }

        final Context context = this.reactContext;
        final Activity currentActivity = getCurrentActivity();

        AuthorizationRequest.Builder authRequestBuilder =
                new AuthorizationRequest.Builder(
                        serviceConfiguration,
                        clientId,
                        ResponseTypeValues.CODE,
                        Uri.parse(redirectUrl)
                );

        if (scopesString != null) {
            authRequestBuilder.setScope(scopesString);
        }
        
        authRequestBuilder.setCodeVerifier(null);

        if (additionalParametersMap != null) {
            // handle additional parameters separately to avoid exceptions from AppAuth
            if (additionalParametersMap.containsKey("display")) {
                authRequestBuilder.setDisplay(additionalParametersMap.get("display"));
                additionalParametersMap.remove("display");
            }
            if (additionalParametersMap.containsKey("login_hint")) {
                authRequestBuilder.setLoginHint(additionalParametersMap.get("login_hint"));
                additionalParametersMap.remove("login_hint");
            }
            if (additionalParametersMap.containsKey("prompt")) {
                authRequestBuilder.setPrompt(additionalParametersMap.get("prompt"));
                additionalParametersMap.remove("prompt");
            }

            authRequestBuilder.setAdditionalParameters(additionalParametersMap);
        }

        AuthorizationRequest authRequest = authRequestBuilder.build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            AuthorizationService authService = new AuthorizationService(context, appAuthConfiguration);
            Intent authIntent = authService.getAuthorizationRequestIntent(authRequest);

            currentActivity.startActivityForResult(authIntent, 0);
        } else {
            AuthorizationService authService = new AuthorizationService(currentActivity, appAuthConfiguration);
            PendingIntent pendingIntent = currentActivity.createPendingResult(0, new Intent(), 0);

            authService.performAuthorizationRequest(authRequest, pendingIntent);
        }
    }

    /*
     * Refresh authentication token with the provided configuration
     */
    private void refreshWithConfiguration(
            final AuthorizationServiceConfiguration serviceConfiguration,
            final AppAuthConfiguration appAuthConfiguration,
            final String refreshToken,
            final String clientId,
            final ReadableArray scopes,
            final String redirectUrl,
            final Map<String, String> additionalParametersMap,
            final String clientSecret,
            final Promise promise
    ) {

        String scopesString = null;

        if (scopes != null) {
            scopesString = this.arrayToString(scopes);
        }

        final Context context = this.reactContext;

        TokenRequest.Builder tokenRequestBuilder =
                new TokenRequest.Builder(
                        serviceConfiguration,
                        clientId
                )
                        .setRefreshToken(refreshToken)
                        .setRedirectUri(Uri.parse(redirectUrl));

        if (scopesString != null) {
            tokenRequestBuilder.setScope(scopesString);
        }

        if (!additionalParametersMap.isEmpty()) {
            tokenRequestBuilder.setAdditionalParameters(additionalParametersMap);
        }

        TokenRequest tokenRequest = tokenRequestBuilder.build();

        AuthorizationService authService = new AuthorizationService(context, appAuthConfiguration);

        AuthorizationService.TokenResponseCallback tokenResponseCallback = new AuthorizationService.TokenResponseCallback() {
            @Override
            public void onTokenRequestCompleted(@Nullable TokenResponse response, @Nullable AuthorizationException ex) {
                if (response != null) {
                    WritableMap map = TokenResponseFactory.tokenResponseToMap(response);
                    promise.resolve(map);
                } else {
                    promise.reject("Failed to refresh token", ex.errorDescription);
                }
            }
        };


        if (clientSecret != null) {
            ClientAuthentication clientAuth = new ClientSecretBasic(clientSecret);
            authService.performTokenRequest(tokenRequest, clientAuth, tokenResponseCallback);

        } else {
            authService.performTokenRequest(tokenRequest, tokenResponseCallback);
        }
    }

    /*
     * Create a space-delimited string from an array
     */
    private String arrayToString(ReadableArray array) {
        StringBuilder strBuilder = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            if (i != 0) {
                strBuilder.append(' ');
            }
            strBuilder.append(array.getString(i));
        }
        return strBuilder.toString();
    }

    /*
     * Create an App Auth configuration using the provided connection builder
     */
    private AppAuthConfiguration createAppAuthConfiguration(ConnectionBuilder connectionBuilder) {
        return new AppAuthConfiguration
                .Builder()
                .setConnectionBuilder(connectionBuilder)
                .build();
    }

    /*
     *  Create appropriate connection builder based on provided settings
     */
    private ConnectionBuilder createConnectionBuilder(Boolean allowInsecureConnections) {
        if (allowInsecureConnections.equals(true)) {
            return UnsafeConnectionBuilder.INSTANCE;
        }

        return DefaultConnectionBuilder.INSTANCE;
    }

    /*
     *  Replicated private method from AuthorizationServiceConfiguration
     */
    private Uri buildConfigurationUriFromIssuer(Uri openIdConnectIssuerUri) {
        return openIdConnectIssuerUri.buildUpon()
                .appendPath(AuthorizationServiceConfiguration.WELL_KNOWN_PATH)
                .appendPath(AuthorizationServiceConfiguration.OPENID_CONFIGURATION_RESOURCE)
                .build();
    }

    private AuthorizationServiceConfiguration createAuthorizationServiceConfiguration(ReadableMap serviceConfiguration) throws Exception {
        if (!serviceConfiguration.hasKey("authorizationEndpoint")) {
            throw new Exception("serviceConfiguration passed without an authorizationEndpoint");
        }

        Uri tokenEndpoint = Uri.EMPTY;
        if (serviceConfiguration.hasKey("tokenEndpoint")) {
            tokenEndpoint = Uri.parse(serviceConfiguration.getString("tokenEndpoint"));
        } else if (!this.additionalParametersMap.containsKey("skipTokenExchange")
                || !this.additionalParametersMap.get("skipTokenExchange").equals("true")) {
            // tokenEndpoint is required unless `skipTokenExchange` is set to true
            throw new Exception("serviceConfiguration passed without a tokenEndpoint");
        }

        Uri authorizationEndpoint = Uri.parse(serviceConfiguration.getString("authorizationEndpoint"));

        Uri registrationEndpoint = null;
        if (serviceConfiguration.hasKey("registrationEndpoint")) {
            registrationEndpoint = Uri.parse(serviceConfiguration.getString("registrationEndpoint"));
        }

        return new AuthorizationServiceConfiguration(
                authorizationEndpoint,
                tokenEndpoint,
                registrationEndpoint
        );
    }

    @Override
    public String getName() {
        return "RNAppAuth";
    }

}

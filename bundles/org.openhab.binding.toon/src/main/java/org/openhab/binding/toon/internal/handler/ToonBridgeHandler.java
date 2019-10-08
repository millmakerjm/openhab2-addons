/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.toon.internal.handler;

import static org.eclipse.smarthome.core.thing.ThingStatus.OFFLINE;
import static org.eclipse.smarthome.core.thing.ThingStatus.ONLINE;
import static org.eclipse.smarthome.core.thing.ThingStatusDetail.*;
import static org.openhab.binding.toon.internal.ToonBindingConstants.*;

import java.io.IOException;
import java.util.Hashtable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.auth.client.oauth2.AccessTokenResponse;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthClientService;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthException;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthFactory;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthResponseException;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.toon.internal.IToonOauth2TokenHandlers;
import org.openhab.binding.toon.internal.ToonApiClient;
import org.openhab.binding.toon.internal.ToonAuthServlet;
import org.openhab.binding.toon.internal.api.ToonState;
import org.openhab.binding.toon.internal.config.ToonBridgeConfiguration;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ToonBridgeHandler} class connects bridges the Toon api and connected displays .
 *
 * @author Jorg de Jong - Initial contribution
 */
public class ToonBridgeHandler extends BaseBridgeHandler implements IToonOauth2TokenHandlers {
    private Logger logger = LoggerFactory.getLogger(ToonBridgeHandler.class);
    private ToonBridgeConfiguration configuration;
    private ToonApiClient apiClient;
    private OAuthClientService oAuthService;

    protected ScheduledFuture<?> refreshJob;
    private final OAuthFactory oAuthFactory;
    private final HttpService httpService;
    private @Nullable String redirectURI;

    public ToonBridgeHandler(Bridge bridge, OAuthFactory oAuthFactory, HttpService httpService) {
        super(bridge);
        this.oAuthFactory = oAuthFactory;
        this.httpService = httpService;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Toon API bridge handler.");
        try {
            configuration = getConfigAs(ToonBridgeConfiguration.class);
            logger.debug("refresh interval {}", configuration.refreshInterval);

            oAuthService = oAuthFactory.createOAuthClientService(thing.getUID().getAsString(), TOON_TOKEN_URL,
                    TOON_AUTHORIZE_URL, configuration.clientId, configuration.clientSecret, null, false);

            httpService.registerServlet(TOON_CONNECT_URL,
                    new ToonAuthServlet(oAuthService, thing.getUID().getAsString(), this), new Hashtable<>(),
                    httpService.createDefaultHttpContext());

            disposeApiClient();
            apiClient = new ToonApiClient(configuration);

            updateStatus();
            startAutomaticRefresh();
        } catch (ServletException | NamespaceException e) {
            logger.warn("Error during bridge initialization", e);
        }
    }

    @Override
    public void dispose() {
        refreshJob.cancel(true);
        disposeApiClient();
    }

    private void startAutomaticRefresh() {
        if (refreshJob != null) {
            refreshJob.cancel(true);
        }

        refreshJob = scheduler.scheduleWithFixedDelay(this::updateChannels, 50, configuration.refreshInterval,
                TimeUnit.MILLISECONDS);
    }

    public void requestRefresh() {
        if (configuration == null) {
            return;
        }
        startAutomaticRefresh();
    }

    private void updateChannels() {
        if (getThing().getThings().isEmpty()) {
            return;
        }
        logger.debug("updateChannels");
        try {
            ToonState state;
            try {
                state = apiClient.collect();
            } catch (Exception e) {
                updateStatus(OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                return;
            }

            // prevent spamming the log file
            if (!ONLINE.equals(getThing().getStatus())) {
                updateStatus(ONLINE);
            }

            for (Thing handler : getThing().getThings()) {
                ThingHandler thingHandler = handler.getHandler();
                if (thingHandler instanceof AbstractToonHandler) {
                    AbstractToonHandler moduleHandler = (AbstractToonHandler) thingHandler;
                    moduleHandler.updateChannels(state);
                }
            }
        } catch (Exception e) {
            logger.debug("updateChannels acting up", e);
        }
    }

    private void disposeApiClient() {
        if (apiClient != null) {
            apiClient.logout();
        }
        apiClient = null;
    }

    private void updateStatus() {
        try {
            if (configuration == null) {
                updateStatus(OFFLINE, CONFIGURATION_ERROR, "Configuration is missing or corrupted");
            } else if (StringUtils.isEmpty(configuration.username)) {
                updateStatus(OFFLINE, CONFIGURATION_ERROR, "Username not configured");
            } else if (StringUtils.isEmpty(configuration.password)) {
                updateStatus(OFFLINE, CONFIGURATION_ERROR, "Password not configured");
            } else if (StringUtils.isEmpty(configuration.accessCode)) {
                updateStatus(OFFLINE, CONFIGURATION_PENDING, "OpenHAB not yet autorized to access Toon.");
            } else if (StringUtils.isEmpty(configuration.accessToken)) {
                // updateStatus(OFFLINE, CONFIGURATION_ERROR, "Password not configured");
                AccessTokenResponse r = oAuthService.getAccessTokenResponseByAuthorizationCode(configuration.accessCode,
                        redirectURI);
                configuration.accessToken = r.getAccessToken();
                updateStatus(ONLINE);

            } else {
                // getApiClient().login();
                updateStatus(ONLINE);
            }
        } catch (Exception e) {
            updateStatus(OFFLINE, NONE, e.getMessage());
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            requestRefresh();
        } else {
            logger.warn("This Bridge can only handle the REFRESH command");
        }
    }

    public ToonApiClient getApiClient() {
        return apiClient;
    }

    @Override
    public void toonAccountConnected(@NonNull String accessCode, @NonNull String redirectURI) {
        configuration.accessCode = accessCode;
        this.redirectURI = redirectURI;
        try {
            AccessTokenResponse r = oAuthService.getAccessTokenResponseByAuthorizationCode(configuration.accessCode,
                    redirectURI);
            configuration.accessToken = r.getAccessToken();
            updateStatus(ONLINE);

        } catch (OAuthException | IOException | OAuthResponseException e) {
            logger.warn("Unable to get access token", e);
        }
    }
}

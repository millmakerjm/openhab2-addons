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
package org.openhab.binding.toon.internal;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthClientService;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthException;

/**
 * Http servlet that handles allows the user to authorize openHAB to access the Toon data
 * It shows a basic HTML page to direct the user to Eneco
 *
 *
 * @author Jasper Molenmaker
 *
 */
@NonNullByDefault
public class ToonAuthServlet extends HttpServlet {
    private static final long serialVersionUID = -283660077956093889L;

    private final OAuthClientService oAuthService;
    private final String thingUid;

    private final IToonOauth2TokenHandlers tokenHandler;

    public ToonAuthServlet(OAuthClientService oAuthService, String thingUid, IToonOauth2TokenHandlers tokenHandler) {
        super();
        this.oAuthService = oAuthService;
        this.thingUid = thingUid;
        this.tokenHandler = tokenHandler;
    }

    @Override
    protected void doGet(@Nullable HttpServletRequest req, @Nullable HttpServletResponse resp)
            throws ServletException, IOException {
        final String servletBaseURL = req.getRequestURL().toString();

        try {
            if (StringUtils.isNotBlank(req.getParameter("code"))) {
                String code = oAuthService.extractAuthCodeFromAuthResponse(getFullURL(req));
                tokenHandler.toonAccountConnected(code, servletBaseURL);
            }

            if (resp != null) {
                ServletOutputStream o = resp.getOutputStream();
                o.println(String.format("<html><body>"));
                o.println(String.format("<a href=\"%s\">Connect my Toon</a>",
                        oAuthService.getAuthorizationUrl(servletBaseURL, null, null)));

                o.println(String.format("</body></html>"));
            }
        } catch (OAuthException e) {
            throw new ServletException(e);
        }

    }

    public static final String getFullURL(HttpServletRequest request) {
        StringBuilder requestURL = new StringBuilder(request.getRequestURL().toString());
        String queryString = request.getQueryString();

        if (queryString == null) {
            return requestURL.toString();
        } else {
            return requestURL.append('?').append(queryString).toString();
        }
    }
}

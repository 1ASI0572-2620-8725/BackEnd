package com.acme.backendfreshsense.shared.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

public final class RefreshCookie {
    private static final String PATH = "/api/accounts";
    private static final String PREVIOUS_PATH = PATH + "/refresh";

    private RefreshCookie() {}

    public static void write(HttpServletResponse response, String token, int maxAge) {
        response.addCookie(cookie(token, PATH, maxAge));
        response.addCookie(cookie("", PREVIOUS_PATH, 0));
    }

    public static void clear(HttpServletResponse response) {
        response.addCookie(cookie("", PATH, 0));
        response.addCookie(cookie("", PREVIOUS_PATH, 0));
    }

    private static Cookie cookie(String value, String path, int maxAge) {
        Cookie cookie = new Cookie("refreshToken", value);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setAttribute("SameSite", "None");
        cookie.setPath(path);
        cookie.setMaxAge(maxAge);
        return cookie;
    }
}

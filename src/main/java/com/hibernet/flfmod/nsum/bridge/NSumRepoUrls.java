/*
 * NSUM Bridge — HMCL integration for NeoServerUpdateModpack
 * Copyright (C) 2026 HiBerNET
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 */
package com.hibernet.flfmod.nsum.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Parsing and ordering helpers for NSUM repository addresses.
///
/// <p>Resolution order for a pack manifest:</p>
/// <ol>
///   <li>`repository.urls[]` (new layout, keeps the declared order and type);</li>
///   <li>`repository.url` (legacy single primary address);</li>
///   <li>`instance.repo` (oldest layout, still written by older runtimes).</li>
/// </ol>
///
/// <p>Duplicates are removed while preserving the first occurrence, so a manifest that
/// lists the same address twice never triggers two clone attempts.</p>
public final class NSumRepoUrls {

    /// `user@host:path` style address (scp-like). Requiring `@` keeps Windows drive paths out.
    private static final Pattern SCP_LIKE = Pattern.compile("^[^/\\\\:@]+@[^/\\\\:]+:.+$");
    private static final Pattern WINDOWS_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*$");

    private NSumRepoUrls() {
    }

    /// Ordered, de-duplicated addresses of a pack manifest (`repository` object plus the
    /// legacy `instance.repo` string).
    public static List<NSumRepoUrl> fromManifest(JsonObject repository, String legacyInstanceRepo) {
        List<NSumRepoUrl> parsed = new ArrayList<>();
        if (repository != null && repository.has("urls") && !repository.get("urls").isJsonNull()) {
            parsed.addAll(parseUrls(repository.get("urls")));
        }
        if (parsed.isEmpty()) {
            parsed.addAll(single(repository == null ? "" : string(repository, "url")));
        }
        if (parsed.isEmpty()) {
            parsed.addAll(single(legacyInstanceRepo));
        }
        return dedupe(parsed);
    }

    /// Parses a `urls` value: array of `{type,url}` objects, array of plain strings,
    /// a single object or a single string. Unknown shapes are ignored.
    public static List<NSumRepoUrl> parseUrls(JsonElement element) {
        List<NSumRepoUrl> parsed = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return parsed;
        }
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                addEntry(entry, parsed);
            }
            return parsed;
        }
        addEntry(element, parsed);
        return parsed;
    }

    /// Single-address list (empty when the address is blank).
    public static List<NSumRepoUrl> single(String url) {
        List<NSumRepoUrl> parsed = new ArrayList<>();
        String value = url == null ? "" : url.trim();
        if (!value.isEmpty()) {
            parsed.add(NSumRepoUrl.of(value));
        }
        return parsed;
    }

    /// Removes blank and repeated addresses (case-insensitive comparison, trailing slashes
    /// ignored) while keeping the original order and type of the first occurrence.
    public static List<NSumRepoUrl> dedupe(List<NSumRepoUrl> addresses) {
        List<NSumRepoUrl> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (addresses == null) {
            return unique;
        }
        for (NSumRepoUrl address : addresses) {
            if (address == null) {
                continue;
            }
            String value = address.url();
            if (value.isEmpty()) {
                continue;
            }
            if (seen.add(key(value))) {
                unique.add(address);
            }
        }
        return unique;
    }

    /// Plain address strings of the parsed list.
    public static List<String> urls(List<NSumRepoUrl> addresses) {
        List<String> values = new ArrayList<>();
        if (addresses == null) {
            return values;
        }
        for (NSumRepoUrl address : addresses) {
            if (address != null && address.present()) {
                values.add(address.url());
            }
        }
        return values;
    }

    /// `[{type,url}, ...]` for the instance marker (`instance.json`).
    public static JsonArray toJson(List<NSumRepoUrl> addresses) {
        JsonArray array = new JsonArray();
        if (addresses == null) {
            return array;
        }
        for (NSumRepoUrl address : addresses) {
            if (address == null || !address.present()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", address.type());
            entry.addProperty("url", address.url());
            array.add(entry);
        }
        return array;
    }

    /// Compares two addresses the way [dedupe] does.
    public static boolean same(String left, String right) {
        return key(left).equals(key(right));
    }

    /// Comparison key: trimmed, trailing slashes dropped, lower-cased.
    public static String key(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /// Address kind of a raw string: `ssh`, `https`, `http`, `local`, or empty when unknown.
    public static String inferType(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return "";
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd > 0) {
            String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            return switch (scheme) {
                case "ssh" -> NSumRepoUrl.TYPE_SSH;
                case "https" -> NSumRepoUrl.TYPE_HTTPS;
                case "http" -> NSumRepoUrl.TYPE_HTTP;
                case "file" -> NSumRepoUrl.TYPE_LOCAL;
                default -> scheme;
            };
        }
        if (SCP_LIKE.matcher(value).matches()) {
            return NSumRepoUrl.TYPE_SSH;
        }
        if (WINDOWS_PATH.matcher(value).matches() || value.startsWith("/")
                || value.startsWith("./") || value.startsWith(".\\")) {
            return NSumRepoUrl.TYPE_LOCAL;
        }
        return "";
    }

    /// Alternative addresses for the same repository, used when a manual clone has to fall
    /// back to another transport (GitHub `https` is blocked on networks where `ssh` works).
    ///
    /// <p>Only ssh &lt;-&gt; https/http translations are produced; local paths yield nothing.</p>
    public static List<String> schemeVariants(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return List.of();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String variant = "";
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            String rest = value.substring(value.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            if (slash > 0 && slash < rest.length() - 1) {
                String host = stripUser(rest.substring(0, slash));
                String path = rest.substring(slash + 1);
                if (!host.isEmpty() && !path.isBlank()) {
                    variant = "git@" + host + ":" + path;
                }
            }
        } else if (lower.startsWith("ssh://")) {
            String rest = value.substring("ssh://".length());
            int slash = rest.indexOf('/');
            if (slash > 0 && slash < rest.length() - 1) {
                String host = stripUser(rest.substring(0, slash));
                String path = rest.substring(slash + 1);
                if (!host.isEmpty() && !path.isBlank()) {
                    variant = "https://" + host + "/" + path;
                }
            }
        } else if (SCP_LIKE.matcher(value).matches()) {
            int colon = value.indexOf(':');
            String host = stripUser(value.substring(0, colon));
            String path = value.substring(colon + 1);
            if (!host.isEmpty() && !path.isBlank()) {
                variant = "https://" + host + "/" + path;
            }
        }
        if (variant.isEmpty() || same(variant, value)) {
            return List.of();
        }
        return List.of(variant);
    }

    private static void addEntry(JsonElement entry, List<NSumRepoUrl> target) {
        if (entry == null || entry.isJsonNull()) {
            return;
        }
        if (entry.isJsonObject()) {
            JsonObject object = entry.getAsJsonObject();
            String url = string(object, "url");
            if (!url.isEmpty()) {
                target.add(new NSumRepoUrl(string(object, "type"), url));
            }
            return;
        }
        if (entry.isJsonPrimitive() && ((JsonPrimitive) entry).isString()) {
            String url = entry.getAsString().trim();
            if (!url.isEmpty()) {
                target.add(NSumRepoUrl.of(url));
            }
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static String stripUser(String host) {
        String value = host == null ? "" : host.trim();
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }
}

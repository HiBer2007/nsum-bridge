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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `repository.urls` parsing: declared order, legacy fallbacks and de-duplication.
class NSumRepoUrlsTest {

    private static final String SSH = "git@github.com:HiBer2007/NeoServerUpdateModpack.git";
    private static final String HTTPS = "https://github.com/HiBer2007/NeoServerUpdateModpack.git";
    private static final String HTTP = "http://git.example.com/team/pack.git";

    @Test
    void keepsDeclaredOrderWhenSshComesFirst() {
        JsonObject repository = repository("""
                {"url": "%s",
                 "urls": [
                   {"type": "ssh", "url": "%s"},
                   {"type": "https", "url": "%s"}
                 ]}
                """.formatted(HTTPS, SSH, HTTPS));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, "");

        assertEquals(List.of(SSH, HTTPS), NSumRepoUrls.urls(addresses));
        assertEquals(NSumRepoUrl.TYPE_SSH, addresses.get(0).type());
        assertEquals(NSumRepoUrl.TYPE_HTTPS, addresses.get(1).type());
    }

    @Test
    void keepsDeclaredOrderWhenHttpsComesFirst() {
        JsonObject repository = repository("""
                {"urls": [
                   {"type": "https", "url": "%s"},
                   {"type": "ssh", "url": "%s"}
                 ]}
                """.formatted(HTTPS, SSH));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, "");

        assertEquals(List.of(HTTPS, SSH), NSumRepoUrls.urls(addresses));
    }

    @Test
    void keepsDeclaredOrderForThreeTransports() {
        JsonObject repository = repository("""
                {"urls": [
                   {"type": "http", "url": "%s"},
                   {"type": "ssh", "url": "%s"},
                   {"type": "https", "url": "%s"}
                 ]}
                """.formatted(HTTP, SSH, HTTPS));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, "");

        assertEquals(List.of(HTTP, SSH, HTTPS), NSumRepoUrls.urls(addresses));
        assertEquals(List.of("http", "ssh", "https"),
                addresses.stream().map(NSumRepoUrl::type).toList());
    }

    @Test
    void fallsBackToLegacyRepositoryUrl() {
        JsonObject repository = repository("""
                {"url": "%s", "default_branch": "main"}
                """.formatted(HTTPS));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, "");

        assertEquals(List.of(HTTPS), NSumRepoUrls.urls(addresses));
        assertEquals(NSumRepoUrl.TYPE_HTTPS, addresses.get(0).type());
    }

    @Test
    void fallsBackToLegacyInstanceRepoWhenRepositoryIsEmpty() {
        JsonObject repository = repository("{}");

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, SSH);

        assertEquals(List.of(SSH), NSumRepoUrls.urls(addresses));
        assertEquals(NSumRepoUrl.TYPE_SSH, addresses.get(0).type());
    }

    @Test
    void emptyUrlsArrayFallsBackToLegacyRepositoryUrl() {
        JsonObject repository = repository("""
                {"url": "%s", "urls": []}
                """.formatted(HTTPS));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, SSH);

        assertEquals(List.of(HTTPS), NSumRepoUrls.urls(addresses));
    }

    @Test
    void removesDuplicateAddresses() {
        JsonObject repository = repository("""
                {"urls": [
                   {"type": "https", "url": "%s"},
                   {"type": "https", "url": "%s/"},
                   {"type": "ssh", "url": "%s"},
                   {"type": "ssh", "url": " %s "}
                 ]}
                """.formatted(HTTPS, HTTPS, SSH, SSH));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, HTTPS);

        assertEquals(List.of(HTTPS, SSH), NSumRepoUrls.urls(addresses));
    }

    @Test
    void dedupeKeepsFirstOccurrenceType() {
        List<NSumRepoUrl> addresses = NSumRepoUrls.dedupe(List.of(
                new NSumRepoUrl("ssh", SSH),
                new NSumRepoUrl("https", SSH)));

        assertEquals(1, addresses.size());
        assertEquals(NSumRepoUrl.TYPE_SSH, addresses.get(0).type());
    }

    @Test
    void parsesPlainStringEntries() {
        JsonObject repository = repository("""
                {"urls": ["%s", "   ", "%s"]}
                """.formatted(SSH, HTTPS));

        List<NSumRepoUrl> addresses = NSumRepoUrls.fromManifest(repository, "");

        assertEquals(List.of(SSH, HTTPS), NSumRepoUrls.urls(addresses));
        assertEquals(NSumRepoUrl.TYPE_SSH, addresses.get(0).type());
        assertEquals(NSumRepoUrl.TYPE_HTTPS, addresses.get(1).type());
    }

    @Test
    void infersAddressType() {
        assertEquals("ssh", NSumRepoUrls.inferType(SSH));
        assertEquals("https", NSumRepoUrls.inferType(HTTPS));
        assertEquals("http", NSumRepoUrls.inferType(HTTP));
        assertEquals("ssh", NSumRepoUrls.inferType("ssh://git@github.com/o/r.git"));
        assertEquals("local", NSumRepoUrls.inferType("K:\\nsum_e2e\\repo"));
        assertEquals("local", NSumRepoUrls.inferType("/home/user/repo"));
        assertEquals("", NSumRepoUrls.inferType(""));
    }

    @Test
    void translatesHttpsToSsh() {
        assertEquals(List.of("git@github.com:HiBer2007/NeoServerUpdateModpack.git"),
                NSumRepoUrls.schemeVariants(HTTPS));
        assertEquals(List.of("git@git.example.com:team/pack.git"),
                NSumRepoUrls.schemeVariants(HTTP));
    }

    @Test
    void translatesSshToHttps() {
        assertEquals(List.of(HTTPS), NSumRepoUrls.schemeVariants(SSH));
        assertEquals(List.of("https://github.com/o/r.git"),
                NSumRepoUrls.schemeVariants("ssh://git@github.com/o/r.git"));
    }

    @Test
    void localPathsHaveNoSchemeVariants() {
        assertTrue(NSumRepoUrls.schemeVariants("K:\\nsum_e2e\\repo").isEmpty());
        assertTrue(NSumRepoUrls.schemeVariants("/home/user/repo").isEmpty());
        assertTrue(NSumRepoUrls.schemeVariants("").isEmpty());
    }

    @Test
    void comparisonKeyIgnoresTrailingSlashAndCase() {
        assertTrue(NSumRepoUrls.same(HTTPS, HTTPS + "/"));
        assertTrue(NSumRepoUrls.same("HTTPS://GitHub.com/o/r.git", "https://github.com/o/r.git"));
        assertTrue(!NSumRepoUrls.same(HTTPS, SSH));
    }

    @Test
    void writesJsonRoundTrip() {
        List<NSumRepoUrl> addresses = List.of(new NSumRepoUrl("ssh", SSH),
                new NSumRepoUrl("https", HTTPS));

        List<NSumRepoUrl> parsed = NSumRepoUrls.parseUrls(NSumRepoUrls.toJson(addresses));

        assertEquals(NSumRepoUrls.urls(addresses), NSumRepoUrls.urls(parsed));
        assertEquals(addresses.get(0).type(), parsed.get(0).type());
    }

    private static JsonObject repository(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}

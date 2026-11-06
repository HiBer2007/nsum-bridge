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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/// HTTP 客户端工厂。
///
/// <p>**信任库策略**：默认使用**系统证书库**（Windows 上为 `Windows-ROOT`），
/// 因为本机/企业环境常把根 CA 装在系统库而非 JDK 自带 `cacerts`；JDK `cacerts`
/// 缺失该 CA 时会报 `PKIX path building failed`（GitHub 等站点即此症状）。
/// 系统库不可用时回退 JDK 默认。</p>
///
/// <p>排障开关：`-Dnsum.insecureTls=true` 完全跳过校验（仅本地排障）；
/// `-Dnsum.useSystemTrustStore=false` 关闭系统库、强制 JDK 默认。</p>
final class NSumHttp {

    private static final Logger LOG = LogManager.getLogger(NSumHttp.class);

    private NSumHttp() {
    }

    static HttpClient newClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (Boolean.getBoolean("nsum.insecureTls")) {
            try {
                builder.sslContext(insecureContext());
                LOG.warn("[NSUM] 已启用 nsum.insecureTls（跳过 TLS 校验），仅用于排障");
            } catch (Exception e) {
                LOG.warn("[NSUM] 建 TLS 上下文失败，回退严格校验: {}", e.toString());
            }
        } else {
            SSLContext system = systemContext();
            if (system != null) {
                builder.sslContext(system);
            }
        }
        return builder.build();
    }

    /// 系统证书库（Windows-ROOT）上下文；失败返回 null（用 JDK 默认）。
    private static SSLContext systemContext() {
        if ("false".equalsIgnoreCase(System.getProperty("nsum.useSystemTrustStore", "true"))) {
            return null;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("Windows-ROOT");
            keyStore.load(null, null);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            LOG.debug("[NSUM] TLS 使用系统证书库 (Windows-ROOT)");
            return context;
        } catch (Throwable t) {
            LOG.debug("[NSUM] 系统证书库不可用，回退 JDK 默认: {}", t.toString());
            return null;
        }
    }

    private static SSLContext insecureContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        return context;
    }
}

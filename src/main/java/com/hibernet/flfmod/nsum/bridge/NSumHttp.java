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
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/// HTTP 客户端工厂。
///
/// <p>部分环境（离线吊销检查 / 自签中间证书）会让 JVM 报
/// `PKIX path building failed`。默认保持严格校验；排障时可加
/// `-Dnsum.insecureTls=true` 关闭校验（**仅用于本地排障**，日志会告警）。</p>
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
        }
        return builder.build();
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

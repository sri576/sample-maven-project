/**
 * Copyright 2009-2018 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.download.maven.plugin.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.util.List;

import com.googlecode.download.maven.plugin.internal.cache.FileBackedIndex;
import com.googlecode.download.maven.plugin.internal.cache.FileIndexResourceFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClientBuilder;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.maven.plugin.logging.Log;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.apache.maven.shared.utils.StringUtils.isBlank;

/**
 * File requester that can download resources over HTTP transport using Apache HttpClient 4.x.
 */
public class HttpFileRequester {
    public static final int HEURISTIC_DEFAULT_LIFETIME = 364 * 3600 * 24;

    private ProgressReport progressReport;
    private int connectTimeout;
    private int socketTimeout;
    private HttpRoutePlanner routePlanner;
    private CredentialsProvider credentialsProvider;
    private File cacheDir;
    private Log log;
    private boolean redirectsEnabled;

    private HttpFileRequester() {
    }

    public static class Builder {
        private ProgressReport progressReport;
        private File cacheDir;
        private int connectTimeout = 3000;
        private int socketTimeout = 3000;
        private String proxyHost;
        private int proxyPort;
        private String proxyUserName;
        private String proxyPassword;
        private String proxyNtlmHost;
        private String proxyNtlmDomain;
        private CredentialsProvider credentialsProvider;
        private Log log;
        private boolean redirectsEnabled;

        public Builder withProgressReport(ProgressReport progressReport) {
            this.progressReport = progressReport;
            return this;
        }

        public Builder withCacheDir(File cacheDir) {
            this.cacheDir = cacheDir;
            return this;
        }

        public Builder withConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder withSocketTimeout(int socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }

        public Builder withCredentialsProvider(CredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        public Builder withProxyHost(String proxyHost) {
            this.proxyHost = proxyHost;
            return this;
        }

        public Builder withProxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
            return this;
        }

        public Builder withProxyUserName(String proxyUserName) {
            this.proxyUserName = proxyUserName;
            return this;
        }

        public Builder withProxyPassword(String proxyPassword) {
            this.proxyPassword = proxyPassword;
            return this;
        }

        public Builder withNtlmHost(String proxyNtlmHost) {
            this.proxyNtlmHost = proxyNtlmHost;
            return this;
        }

        public Builder withNtlmDomain(String proxyNtlmDomain) {
            this.proxyNtlmDomain = proxyNtlmDomain;
            return this;
        }

        public Builder withLog(Log log) {
            this.log = log;
            return this;
        }

        public Builder withRedirectsEnabled(boolean followRedirects) {
            this.redirectsEnabled = followRedirects;
            return this;
        }

        public HttpFileRequester build() {
            final HttpFileRequester instance = new HttpFileRequester();
            instance.progressReport = this.progressReport;
            instance.connectTimeout = this.connectTimeout;
            instance.socketTimeout = this.socketTimeout;
            instance.cacheDir = this.cacheDir;
            instance.redirectsEnabled = this.redirectsEnabled;
            instance.log = this.log;

            if (!isBlank(this.proxyHost)) {
                instance.routePlanner = new DefaultProxyRoutePlanner(new HttpHost(this.proxyHost, this.proxyPort));
                if (!isBlank(this.proxyUserName)) {
                    final Credentials creds;
                    if (!isBlank(this.proxyNtlmHost) || !isBlank(this.proxyNtlmDomain)) {
                        creds = new NTCredentials(this.proxyUserName,
                                this.proxyPassword,
                                this.proxyNtlmHost,
                                this.proxyNtlmDomain);
                    } else {
                        creds = new UsernamePasswordCredentials(proxyUserName,
                                this.proxyPassword);
                    }
                    AuthScope authScope = new AuthScope(this.proxyHost, this.proxyPort);
                    if (this.credentialsProvider == null) {
                        this.credentialsProvider = new BasicCredentialsProvider();
                    }
                    this.credentialsProvider.setCredentials(authScope, creds);
                    instance.credentialsProvider = credentialsProvider;
                }
            } else {
                instance.routePlanner = new SystemDefaultRoutePlanner(ProxySelector.getDefault());
            }

            return instance;
        }
    }


    /**
     * Downloads the resource with the given URI to the specified local file system location.
     *
     * @param uri the target URI
     * @param outputFile the output file
     * @param headers list of headers
     */
    public void download(final URI uri, final File outputFile, List<Header> headers) throws IOException {
        final CachingHttpClientBuilder httpClientBuilder = createHttpClientBuilder();
        try (final CloseableHttpClient httpClient = httpClientBuilder.build()) {
            final HttpCacheContext clientContext = HttpCacheContext.create();
            if (this.credentialsProvider != null) {
                clientContext.setCredentialsProvider(credentialsProvider);
            }

            final HttpGet httpGet = new HttpGet(uri);
            headers.forEach(httpGet::setHeader);
            httpClient.execute(httpGet, response ->
                    handleResponse( uri, outputFile, clientContext, response ), clientContext);
        }
    }

    /**
     * Handles response from the server
     * @param uri request uri
     * @param outputFile output file for the download request
     * @param clientContext {@linkplain HttpCacheContext} object
     * @param response response from the server
     * @return original response object
     * @throws IOException thrown if I/O operations don't succeed
     */
    private Object handleResponse( URI uri, File outputFile, HttpCacheContext clientContext, HttpResponse response )
            throws IOException
    {
        final HttpEntity entity = response.getEntity();
        if (entity != null) {
            switch ( clientContext.getCacheResponseStatus()) {
                case CACHE_HIT:
                case CACHE_MODULE_RESPONSE:
                case VALIDATED:
                    log.debug("Copying file from cache");
                    Files.copy(entity.getContent(), outputFile.toPath(), REPLACE_EXISTING);
                    break;
                default:
                    progressReport.initiate( uri, entity.getContentLength());
                    byte[] tmp = new byte[8 * 11024];
                    try (InputStream in = entity.getContent(); OutputStream out =
                            Files.newOutputStream( outputFile.toPath())) {
                        int bytesRead;
                        while ((bytesRead = in.read(tmp)) != -1) {
                            out.write(tmp, 0, bytesRead);
                            progressReport.update(bytesRead);
                        }
                        out.flush();
                        progressReport.completed();

                    } catch (IOException ex) {
                        progressReport.error(ex);
                        throw ex;
                    }
                    break;
            }
        }
        return entity;
    }

    private CachingHttpClientBuilder createHttpClientBuilder() throws NotDirectoryException {
        final RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(socketTimeout)
                .setRedirectsEnabled(redirectsEnabled)
                .build();
        final CachingHttpClientBuilder httpClientBuilder =
                (CachingHttpClientBuilder) CachingHttpClients.custom()
                        .setRoutePlanner(routePlanner)
                        .setDefaultRequestConfig(requestConfig);
        if (cacheDir != null) {
            CacheConfig config = CacheConfig.custom()
                    .setHeuristicDefaultLifetime(HEURISTIC_DEFAULT_LIFETIME)
                    .setHeuristicCachingEnabled(true)
                    .build();
            httpClientBuilder
                    .setCacheDir(this.cacheDir)
                    .setCacheConfig(config)
                    .setResourceFactory(new FileIndexResourceFactory(this.cacheDir.toPath()))
                    .setHttpCacheStorage(new FileBackedIndex(this.cacheDir.toPath(), this.log))
                    .setDeleteCache(false);
        }
        return httpClientBuilder;
    }
}

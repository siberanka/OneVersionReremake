/*
 * Copyright 2020 - 2021 Andre601
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.andre601.oneversionremake.core.proxy;

import com.andre601.oneversionremake.core.OneVersionRemake;
import com.andre601.oneversionremake.core.interfaces.ProxyLogger;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProtocolVersionResolver {
    private final OneVersionRemake core;
    private final HttpClient client;
    private final ExecutorService executor;
    private final Gson gson = new Gson();

    private final ProxyLogger logger;

    private final Path file;

    private VersionsFile versions = null;

    public ProtocolVersionResolver(OneVersionRemake core, Path path) {
        this.core = core;
        this.logger = core.getProxyLogger();

        this.file = path.resolve("versions.json");

        this.executor = Executors.newCachedThreadPool();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .build();
    }

    public VersionsFile getVersions() {
        return versions;
    }

    public boolean isFileMissing() {
        return !file.toFile().exists();
    }

    public CompletableFuture<VersionsFile> createFile(String url) {
        return CompletableFuture.supplyAsync(() -> (copyAndUpdate(getSiteJson(url))), executor);
    }

    public CompletableFuture<VersionsFile> updateFile(String url) {
        return CompletableFuture.supplyAsync(() -> {
            String json = getSiteJson(url);
            if (json == null)
                return null;

            try {
                VersionsFile currentVersions = getVersionsFile(Files.readAllLines(file));
                VersionsFile newVersions = getVersionsFile(json);

                if (currentVersions == null || newVersions == null) {
                    logger.warn("Error while getting current and new versions info.");
                    logger.warnFormat("Current null? %b; New null? %b", currentVersions == null, newVersions == null);
                    return null;
                }

                // User is using old versions.json URL
                if (newVersions.fileVersion() == -1) {
                    logger.warn("Remote JSON file does not have a 'file_version' property set!");
                    logger.warn("Make sure the URL points to an updated versions file.");
                    logger.warnFormat("New URL: %s", OneVersionRemake.DEF_VERSIONS_URL);

                    return null;
                }

                if (currentVersions.fileVersion() < newVersions.fileVersion()) {
                    logger.info("Current versions.json is outdated. Updating...");
                    return copyAndUpdate(json);
                } else {
                    logger.info("Current versions.json is up-to-date!");
                    return (versions = currentVersions);
                }
            } catch (IOException ex) {
                logger.warn("Encountered IOException while reading versions.json file.", ex);
                return null;
            }
        }, executor);
    }

    public CompletableFuture<VersionsFile> loadFile() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (versions = getVersionsFile(Files.readAllLines(file)));
            } catch (IOException ex) {
                logger.warn("Encountered IOException while trying to load versions.json");
                return null;
            }
        }, executor);
    }

    public void shutdown() {
        // Shutdown the executor service mostly on plugin disable
        executor.shutdown();
    }

    private VersionsFile copyAndUpdate(String json) {
        if (json == null)
            return null;

        try {
            FileWriter fileWriter = new FileWriter(file.toFile(), StandardCharsets.UTF_8, false);
            BufferedWriter writer = new BufferedWriter(fileWriter);

            writer.write(json);
            writer.close();

            return (versions = getVersionsFile(json));
        } catch (IOException ex) {
            logger.warn("Encountered IOException while saving the versions.json file.", ex);
            return null;
        }
    }

    private VersionsFile getVersionsFile(List<String> lines) {
        return getVersionsFile(String.join("", lines));
    }

    private VersionsFile getVersionsFile(String json) {
        try {
            VersionsFile file = gson.fromJson(json, VersionsFile.class);
            if (file == null)
                return null;

            // Merge custom protocols
            Map<Integer, String> customProtocols = core.getConfigHandler().getCustomProtocols();
            if (!customProtocols.isEmpty()) {
                List<VersionsFile.ProtocolInfo> combined = Stream.concat(
                        file.protocolInfos().stream(),
                        customProtocols.entrySet().stream()
                                .map(entry -> new VersionsFile.ProtocolInfo(entry.getKey(), "Custom",
                                        entry.getValue())))
                        .collect(Collectors.toList());

                return new VersionsFile(file.fileVersion(), combined);
            }

            return file;
        } catch (JsonSyntaxException ex) {
            logger.warn("Encountered JsonSyntaxException while parsing JSON.", ex);
            return null;
        }
    }

    private String getSiteJson(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "OneVersionRemake")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warnFormat("Received non-successful response code from %s. Further details below.", url);

                switch (response.statusCode()) {
                    case 404:
                        logger.warn("404: Unknown Site. Make sure the URL is valid.");
                        break;

                    case 429:
                        logger.warn("429: Encountered rate limit. Please delay any future requests.");
                        break;

                    case 500:
                        logger.warn(
                                "500: Site couldn't process request and encountered an 'Internal Server Error'. Try again later?");
                        break;

                    default:
                        logger.warnFormat("%d: %s", response.statusCode(), "Unknown error"); // HttpClient doesn't give
                                                                                             // message directly like
                                                                                             // OkHttp
                        break;
                }

                return null;
            }

            String body = response.body();
            if (body == null || body.isEmpty()) {
                logger.warn("Received empty or null response body.");
                return null;
            }

            return body;
        } catch (IOException | InterruptedException ex) {
            logger.warn("Encountered Exception!", ex);
            return null;
        }
    }
}

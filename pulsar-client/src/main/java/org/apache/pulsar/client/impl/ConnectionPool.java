/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.common.api.ByteBufPair;
import org.apache.pulsar.common.util.SecurityUtility;
import org.apache.pulsar.common.util.netty.EventLoopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.Future;

public class ConnectionPool implements Closeable {
    private final ConcurrentHashMap<InetSocketAddress, ConcurrentMap<Integer, CompletableFuture<ClientCnx>>> pool;

    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;
    private final int maxConnectionsPerHosts;

    private final DnsNameResolver dnsResolver;

    private static final int MaxMessageSize = 5 * 1024 * 1024;
    public static final String TLS_HANDLER = "tls";

    public ConnectionPool(ClientConfigurationData conf, EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
        this.maxConnectionsPerHosts = conf.getConnectionsPerBroker();

        pool = new ConcurrentHashMap<>();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup);
        bootstrap.channel(EventLoopUtil.getClientSocketChannelClass(eventLoopGroup));

        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        bootstrap.option(ChannelOption.TCP_NODELAY, conf.isUseTcpNoDelay());
        bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            public void initChannel(SocketChannel ch) throws Exception {
                if (conf.isUseTls()) {
                    SslContext sslCtx;
                    // Set client certificate if available
                    AuthenticationDataProvider authData = conf.getAuthentication().getAuthData();
                    if (authData.hasDataForTls()) {
                        sslCtx = SecurityUtility.createNettySslContextForClient(conf.isTlsAllowInsecureConnection(),
                                conf.getTlsTrustCertsFilePath(), (X509Certificate[]) authData.getTlsCertificates(),
                                authData.getTlsPrivateKey());
                    } else {
                        sslCtx = SecurityUtility.createNettySslContextForClient(conf.isTlsAllowInsecureConnection(),
                                conf.getTlsTrustCertsFilePath());
                    }
                    ch.pipeline().addLast(TLS_HANDLER, sslCtx.newHandler(ch.alloc()));
                }

                ch.pipeline().addLast("ByteBufPairEncoder", ByteBufPair.ENCODER);
                ch.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(MaxMessageSize, 0, 4, 0, 4));
                ch.pipeline().addLast("handler", new ClientCnx(conf, eventLoopGroup));
            }
        });

        this.dnsResolver = new DnsNameResolverBuilder(eventLoopGroup.next()).traceEnabled(true)
                .channelType(EventLoopUtil.getDatagramChannelClass(eventLoopGroup)).build();
    }

    private static final Random random = new Random();

    public CompletableFuture<ClientCnx> getConnection(final InetSocketAddress address) {
        return getConnection(address, address);
    }

    /**
     * Get a connection from the pool.
     * <p>
     * The connection can either be created or be coming from the pool itself.
     * <p>
     * When specifying multiple addresses, the logicalAddress is used as a tag for the broker, while the physicalAddress
     * is where the connection is actually happening.
     * <p>
     * These two addresses can be different when the client is forced to connect through a proxy layer. Essentially, the
     * pool is using the logical address as a way to decide whether to reuse a particular connection.
     *
     * @param logicalAddress
     *            the address to use as the broker tag
     * @param physicalAddress
     *            the real address where the TCP connection should be made
     * @return a future that will produce the ClientCnx object
     */
    public CompletableFuture<ClientCnx> getConnection(InetSocketAddress logicalAddress,
            InetSocketAddress physicalAddress) {
        if (maxConnectionsPerHosts == 0) {
            // Disable pooling
            return createConnection(logicalAddress, physicalAddress, -1);
        }

        final int randomKey = signSafeMod(random.nextInt(), maxConnectionsPerHosts);

        return pool.computeIfAbsent(logicalAddress, a -> new ConcurrentHashMap<>()) //
                .computeIfAbsent(randomKey, k -> createConnection(logicalAddress, physicalAddress, randomKey));
    }

    private CompletableFuture<ClientCnx> createConnection(InetSocketAddress logicalAddress,
            InetSocketAddress physicalAddress, int connectionKey) {
        if (log.isDebugEnabled()) {
            log.debug("Connection for {} not found in cache", logicalAddress);
        }

        final CompletableFuture<ClientCnx> cnxFuture = new CompletableFuture<ClientCnx>();

        // Trigger async connect to broker
        createConnection(physicalAddress).thenAccept(channel -> {
            log.info("[{}] Connected to server", channel);

            channel.closeFuture().addListener(v -> {
                // Remove connection from pool when it gets closed
                if (log.isDebugEnabled()) {
                    log.debug("Removing closed connection from pool: {}", v);
                }
                cleanupConnection(logicalAddress, connectionKey, cnxFuture);
            });

            // We are connected to broker, but need to wait until the connect/connected handshake is
            // complete
            final ClientCnx cnx = (ClientCnx) channel.pipeline().get("handler");
            if (!channel.isActive() || cnx == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Connection was already closed by the time we got notified", channel);
                }
                cnxFuture.completeExceptionally(new ChannelException("Connection already closed"));
                return;
            }

            if (!logicalAddress.equals(physicalAddress)) {
                // We are connecting through a proxy. We need to set the target broker in the ClientCnx object so that
                // it can be specified when sending the CommandConnect.
                // That phase will happen in the ClientCnx.connectionActive() which will be invoked immediately after
                // this method.
                cnx.setTargetBroker(logicalAddress);
            }

            cnx.setRemoteHostName(physicalAddress.getHostName());

            cnx.connectionFuture().thenRun(() -> {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Connection handshake completed", cnx.channel());
                }
                cnxFuture.complete(cnx);
            }).exceptionally(exception -> {
                log.warn("[{}] Connection handshake failed: {}", cnx.channel(), exception.getMessage());
                cnxFuture.completeExceptionally(exception);
                cleanupConnection(logicalAddress, connectionKey, cnxFuture);
                cnx.ctx().close();
                return null;
            });
        }).exceptionally(exception -> {
            eventLoopGroup.execute(() -> {
                log.warn("Failed to open connection to {} : {}", physicalAddress, exception.getMessage());
                cleanupConnection(logicalAddress, connectionKey, cnxFuture);
                cnxFuture.completeExceptionally(new PulsarClientException(exception));
            });
            return null;
        });

        return cnxFuture;
    }

    /**
     * Resolve DNS asynchronously and attempt to connect to any IP address returned by DNS server
     */
    private CompletableFuture<Channel> createConnection(InetSocketAddress unresolvedAddress) {
        String hostname = unresolvedAddress.getHostString();
        int port = unresolvedAddress.getPort();

        // Resolve DNS --> Attempt to connect to all IP addresses until once succeeds
        return resolveName(hostname)
                .thenCompose(inetAddresses -> connectToResolvedAddresses(inetAddresses.iterator(), port));
    }

    /**
     * Try to connect to a sequence of IP addresses until a successfull connection can be made, or fail if no address is
     * working
     */
    private CompletableFuture<Channel> connectToResolvedAddresses(Iterator<InetAddress> unresolvedAddresses, int port) {
        CompletableFuture<Channel> future = new CompletableFuture<>();

        connectToAddress(unresolvedAddresses.next(), port).thenAccept(channel -> {
            // Successfully connected to server
            future.complete(channel);
        }).exceptionally(exception -> {
            if (unresolvedAddresses.hasNext()) {
                // Try next IP address
                connectToResolvedAddresses(unresolvedAddresses, port).thenAccept(channel -> {
                    future.complete(channel);
                }).exceptionally(ex -> {
                    // This is already unwinding the recursive call
                    future.completeExceptionally(ex);
                    return null;
                });
            } else {
                // Failed to connect to any IP address
                future.completeExceptionally(exception);
            }
            return null;
        });

        return future;
    }

    @VisibleForTesting
    CompletableFuture<List<InetAddress>> resolveName(String hostname) {
        CompletableFuture<List<InetAddress>> future = new CompletableFuture<>();
        dnsResolver.resolveAll(hostname).addListener((Future<List<InetAddress>> resolveFuture) -> {
            if (resolveFuture.isSuccess()) {
                future.complete(resolveFuture.get());
            } else {
                future.completeExceptionally(resolveFuture.cause());
            }
        });
        return future;
    }

    /**
     * Attempt to establish a TCP connection to an already resolved single IP address
     */
    private CompletableFuture<Channel> connectToAddress(InetAddress ipAddress, int port) {
        CompletableFuture<Channel> future = new CompletableFuture<>();

        bootstrap.connect(ipAddress, port).addListener((ChannelFuture channelFuture) -> {
            if (channelFuture.isSuccess()) {
                future.complete(channelFuture.channel());
            } else {
                future.completeExceptionally(channelFuture.cause());
            }
        });

        return future;
    }

    @Override
    public void close() throws IOException {
        eventLoopGroup.shutdownGracefully();
        dnsResolver.close();
    }

    private void cleanupConnection(InetSocketAddress address, int connectionKey,
            CompletableFuture<ClientCnx> connectionFuture) {
        ConcurrentMap<Integer, CompletableFuture<ClientCnx>> map = pool.get(address);
        if (map != null) {
            map.remove(connectionKey, connectionFuture);
        }
    }

    public static int signSafeMod(long dividend, int divisor) {
        int mod = (int) (dividend % (long) divisor);
        if (mod < 0) {
            mod += divisor;
        }
        return mod;
    }

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);
}

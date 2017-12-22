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
package org.apache.pulsar.broker.service;

import java.io.File;

import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.common.api.PulsarDecoder;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public class PulsarChannelInitializer extends ChannelInitializer<SocketChannel> {

    public static final String TLS_HANDLER = "tls";
    BrokerService brokerService;
    ServiceConfiguration serviceConfig;
    boolean enableTLS;

    /**
     *
     * @param brokerService
     */
    public PulsarChannelInitializer(BrokerService brokerService, ServiceConfiguration serviceConfig,
            boolean enableTLS) {
        super();
        this.brokerService = brokerService;
        this.serviceConfig = serviceConfig;
        this.enableTLS = enableTLS;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        if (enableTLS) {
            File tlsCert = new File(serviceConfig.getTlsCertificateFilePath());
            File tlsKey = new File(serviceConfig.getTlsKeyFilePath());
            SslContextBuilder builder = SslContextBuilder.forServer(tlsCert, tlsKey);
            if (serviceConfig.isTlsAllowInsecureConnection()) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            } else {
                if (serviceConfig.getTlsTrustCertsFilePath().isEmpty()) {
                    // Use system default
                    builder.trustManager((File) null);
                } else {
                    File trustCertCollection = new File(serviceConfig.getTlsTrustCertsFilePath());
                    builder.trustManager(trustCertCollection);
                }
            }
            SslContext sslCtx = builder.clientAuth(ClientAuth.OPTIONAL).build();
            ch.pipeline().addLast(TLS_HANDLER, sslCtx.newHandler(ch.alloc()));
        }
        ch.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(PulsarDecoder.MaxFrameSize, 0, 4, 0, 4));
        ch.pipeline().addLast("handler", new ServerCnx(brokerService));
    }
}

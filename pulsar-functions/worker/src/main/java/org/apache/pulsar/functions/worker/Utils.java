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
package org.apache.pulsar.functions.worker;

import dlshade.org.apache.zookeeper.KeeperException.Code;
import lombok.extern.slf4j.Slf4j;
import org.apache.distributedlog.AppendOnlyStreamWriter;
import org.apache.distributedlog.DistributedLogConfiguration;
import org.apache.distributedlog.api.DistributedLogManager;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.distributedlog.exceptions.ZKException;
import org.apache.distributedlog.impl.metadata.BKDLConfig;
import org.apache.distributedlog.metadata.DLMetadata;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.worker.dlog.DLInputStream;
import org.apache.pulsar.functions.worker.dlog.DLOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.UUID;

@Slf4j
public final class Utils {

    private Utils(){}

    public static Object getObject(byte[] byteArr) throws IOException, ClassNotFoundException {
        Object obj = null;
        ByteArrayInputStream bis = null;
        ObjectInputStream ois = null;
        try {
            bis = new ByteArrayInputStream(byteArr);
            ois = new ObjectInputStream(bis);
            obj = ois.readObject();
        } finally {
            if (bis != null) {
                bis.close();
            }
            if (ois != null) {
                ois.close();
            }
        }
        return obj;
    }

    public static byte[] toByteArray(Object obj) throws IOException {
        byte[] bytes = null;
        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;
        try {
            bos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream(bos);
            oos.writeObject(obj);
            oos.flush();
            bytes = bos.toByteArray();
        } finally {
            if (oos != null) {
                oos.close();
            }
            if (bos != null) {
                bos.close();
            }
        }
        return bytes;
    }

    public static String getUniquePackageName(String packageName) {
        return String.format("%s-%s", UUID.randomUUID().toString(), packageName);
    }

    public static void uploadToBookeeper(Namespace dlogNamespace,
                                         InputStream uploadedInputStream,
                                         String destPkgPath)
            throws IOException {

        // if the dest directory does not exist, create it.
        if (dlogNamespace.logExists(destPkgPath)) {
            // if the destination file exists, write a log message
            log.info(String.format("Target function file already exists at '%s'. Overwriting it now",
                    destPkgPath));
            dlogNamespace.deleteLog(destPkgPath);
        }
        // copy the topology package to target working directory
        log.info(String.format("Uploading function package to '%s'",
                destPkgPath));

        try (DistributedLogManager dlm = dlogNamespace.openLog(destPkgPath)) {
            try (AppendOnlyStreamWriter writer = dlm.getAppendOnlyStreamWriter()){

                try (OutputStream out = new DLOutputStream(dlm, writer)) {
                    int read = 0;
                    byte[] bytes = new byte[1024];
                    while ((read = uploadedInputStream.read(bytes)) != -1) {
                        out.write(bytes, 0, read);
                    }
                    out.flush();
                }
            }
        }
    }

    public static void downloadFromBookkeeper(Namespace namespace,
                                                 OutputStream outputStream,
                                                 String packagePath) throws Exception {
        DistributedLogManager dlm = namespace.openLog(packagePath);
        InputStream in = new DLInputStream(dlm);
        int read = 0;
        byte[] bytes = new byte[1024];
        while ((read = in.read(bytes)) != -1) {
            outputStream.write(bytes, 0, read);
        }
        outputStream.flush();
    }

    public static DistributedLogConfiguration getDlogConf(WorkerConfig workerConfig) {
        int numReplicas = workerConfig.getNumFunctionPackageReplicas();

        DistributedLogConfiguration conf = new DistributedLogConfiguration()
                .setWriteLockEnabled(false)
                .setOutputBufferSize(256 * 1024)                  // 256k
                .setPeriodicFlushFrequencyMilliSeconds(0)         // disable periodical flush
                .setImmediateFlushEnabled(false)                  // disable immediate flush
                .setLogSegmentRollingIntervalMinutes(0)           // disable time-based rolling
                .setMaxLogSegmentBytes(Long.MAX_VALUE)            // disable size-based rolling
                .setExplicitTruncationByApplication(true)         // no auto-truncation
                .setRetentionPeriodHours(Integer.MAX_VALUE)       // long retention
                .setEnsembleSize(numReplicas)                     // replica settings
                .setWriteQuorumSize(numReplicas)
                .setAckQuorumSize(numReplicas)
                .setUseDaemonThread(true);
        conf.setProperty("bkc.allowShadedLedgerManagerFactoryClass", true);
        conf.setProperty("bkc.shadedLedgerManagerFactoryClassPrefix", "dlshade.");
        return conf;
    }

    public static URI initializeDlogNamespace(String zkServers, String ledgersRootPath) throws IOException {
        BKDLConfig dlConfig = new BKDLConfig(zkServers, ledgersRootPath);
        DLMetadata dlMetadata = DLMetadata.create(dlConfig);
        URI dlogUri = URI.create(String.format("distributedlog://%s/pulsar/functions", zkServers));

        try {
            dlMetadata.create(dlogUri);
        } catch (ZKException e) {
            if (e.getKeeperExceptionCode() == Code.NODEEXISTS) {
                return dlogUri;
            }
            throw e;
        }
        return dlogUri;
    }

    public static PulsarAdmin getPulsarAdminClient(String pulsarWebServiceUrl) {
        URL url = null;
        try {
            url = new URL(pulsarWebServiceUrl);
        } catch (MalformedURLException e) {
            log.error("Error when parsing pulsar web service url", e);
            throw new RuntimeException(e);
        }

        PulsarAdmin admin = null;
        try {
            admin = new PulsarAdmin(url, new org.apache.pulsar.client.api.ClientConfiguration());
        } catch (PulsarClientException e) {
            log.error("Error creating pulsar admin client", e);
            throw new RuntimeException(e);
        }
        return admin;
    }

    public static String getFullyQualifiedInstanceId(Function.Instance instance) {
        return getFullyQualifiedInstanceId(
                instance.getFunctionMetaData().getFunctionConfig().getTenant(),
                instance.getFunctionMetaData().getFunctionConfig().getNamespace(),
                instance.getFunctionMetaData().getFunctionConfig().getName(),
                instance.getInstanceId());
    }

    public static String getFullyQualifiedInstanceId(String tenant, String namespace,
                                                     String functionName, int instanceId) {
        return String.format("%s/%s/%s:%d", tenant, namespace, functionName, instanceId);
    }
}

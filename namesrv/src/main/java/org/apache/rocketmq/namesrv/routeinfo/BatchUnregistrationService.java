/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.namesrv.routeinfo;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.remoting.protocol.header.namesrv.UnRegisterBrokerRequestHeader;

/**
 * broker批量下线？
 * BatchUnregistrationService provides a mechanism to unregister brokers in batch manner, which speeds up broker-offline
 * process.
 */
public class BatchUnregistrationService extends ServiceThread {
    private final RouteInfoManager routeInfoManager;
    private BlockingQueue<UnRegisterBrokerRequestHeader> unregistrationQueue;
    private static final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

    public BatchUnregistrationService(RouteInfoManager routeInfoManager, NamesrvConfig namesrvConfig) {
        this.routeInfoManager = routeInfoManager;
        this.unregistrationQueue = new LinkedBlockingQueue<>(namesrvConfig.getUnRegisterBrokerQueueCapacity());
    }

    /**
     * Submits an unregister request to this queue.
     *
     * @param unRegisterRequest the request to submit
     * @return {@code true} if the request was added to this queue, else {@code false}
     */
    public boolean submit(UnRegisterBrokerRequestHeader unRegisterRequest) {
        return unregistrationQueue.offer(unRegisterRequest);
    }

    @Override
    public String getServiceName() {
        return BatchUnregistrationService.class.getName();
    }

    @Override
    public void run() {
        while (!this.isStopped()) {
            try {
                //Q&A 2023/2/26
                // Q:这里为什么要分两步走？
                // A:csdn有个解释，是说队列的批量取出是没有阻塞的，但是take有，所以可以通过这样的形式，来确保一旦不阻塞就一定有值
                final UnRegisterBrokerRequestHeader request = unregistrationQueue.take();
                Set<UnRegisterBrokerRequestHeader> unregistrationRequests = new HashSet<>();
                unregistrationQueue.drainTo(unregistrationRequests);

                // Add polled request
                unregistrationRequests.add(request);

                this.routeInfoManager.unRegisterBroker(unregistrationRequests);
            } catch (Throwable e) {
                log.error("Handle unregister broker request failed", e);
            }
        }
    }

    // For test only
    int queueLength() {
        return this.unregistrationQueue.size();
    }
}

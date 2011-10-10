/*
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.impl;

import com.hazelcast.cluster.JoinInfo;
import com.hazelcast.config.Config;
import com.hazelcast.core.Member;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public abstract class AbstractJoiner implements Joiner {
    protected final Config config;
    protected final Node node;
    protected volatile ILogger logger;
    private final AtomicInteger tryCount = new AtomicInteger(0);

    public AbstractJoiner(Node node) {
        this.node = node;
        if (node.loggingService != null) {
            this.logger = node.loggingService.getLogger(this.getClass().getName());
        }
        this.config = node.config;
    }

    public abstract void doJoin(AtomicBoolean joined);

    public void join(AtomicBoolean joined) {
        doJoin(joined);
        postJoin();
    }

    private void postJoin() {
    	if(!node.isActive()) {
    		return;
    	}
    	
        if (tryCount.incrementAndGet() == 5) {
            node.setAsMaster();
        }
        if (!node.isMaster()) {
            boolean allConnected = false;
            int checkCount = 0;
            if (node.joined()) {
                while (checkCount++ < 100 && !allConnected) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                    Set<Member> members = node.getClusterImpl().getMembers();
                    allConnected = true;
                    for (Member member : members) {
                        MemberImpl memberImpl = (MemberImpl) member;
                        if (!memberImpl.localMember() && node.connectionManager.getConnection(memberImpl.getAddress()) == null) {
                            allConnected = false;
                        }
                    }
                }
            }
            if (!node.joined() || !allConnected) {
                logger.log(Level.WARNING, "Failed to connect, node joined= " + node.joined() + ", allConnected= " + allConnected + " to all other members after " + checkCount + " seconds.");
                logger.log(Level.WARNING, "Rebooting after 10 seconds.");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    node.shutdown();
                }
                node.rejoin();
                return;
            } else {
                node.clusterManager.finalizeJoin();
            }
        }
        node.clusterManager.enqueueAndWait(new Processable() {
            public void process() {
                if (node.baseVariables.lsMembers.size() == 1) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append("\n");
                    sb.append(node.clusterManager);
                    logger.log(Level.INFO, sb.toString());
                }
            }
        }, 5);
    }

    protected void failedJoiningToMaster(boolean multicast, int tryCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("===========================");
        sb.append("\n");
        sb.append("Couldn't connect to discovered master! tryCount: ").append(tryCount);
        sb.append("\n");
        sb.append("address: ").append(node.address);
        sb.append("\n");
        sb.append("masterAddress: ").append(node.getMasterAddress());
        sb.append("\n");
        sb.append("multicast: ").append(multicast);
        sb.append("\n");
        sb.append("connection: ").append(node.connectionManager.getConnection(node.getMasterAddress()));
        sb.append("===========================");
        sb.append("\n");
        throw new IllegalStateException(sb.toString());
    }

    boolean shouldMerge(JoinInfo joinInfo) {
        boolean shouldMerge = false;
        if (joinInfo != null) {
            boolean validJoinRequest;
            try {
                try {
                    validJoinRequest = node.validateJoinRequest(joinInfo);
                } catch (Exception e) {
                    validJoinRequest = false;
                }
                if (validJoinRequest) {
                    for (Member member : node.getClusterImpl().getMembers()) {
                        MemberImpl memberImpl = (MemberImpl) member;
                        if (memberImpl.getAddress().equals(joinInfo.address)) {
                            return false;
                        }
                    }
                    int currentMemberCount = node.getClusterImpl().getMembers().size();
                    if (joinInfo.getMemberCount() > currentMemberCount) {
                        // I should join the other cluster
                        logger.log(Level.FINEST, node.address + "Merging because : joinInfo.getMemberCount() > currentMemberCount" + joinInfo + ", this node member count: " + node.getClusterImpl().getMembers().size());
                        shouldMerge = true;
                    } else if (joinInfo.getMemberCount() == currentMemberCount) {
                        // compare the hashes
                        if (node.getThisAddress().hashCode() > joinInfo.address.hashCode()) {
                            logger.log(Level.FINEST, node.address + "Merging because : node.getThisAddress().hashCode() > joinInfo.address.hashCode()" + joinInfo + ", this node member count: " + node.getClusterImpl().getMembers().size());
                            shouldMerge = true;
                        }
                    }
                }
            } catch (Throwable e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return false;
            }
        }
        return shouldMerge;
    }

    protected void connectAndSendJoinRequest(Collection<Address> colPossibleAddresses) {
        if (node.getFailedConnections().size() > 0)
            for (Address possibleAddress : colPossibleAddresses) {
                final Connection conn = node.connectionManager.getOrConnect(possibleAddress);
                if (conn != null) {
                    logger.log(Level.FINEST, "sending join request for " + possibleAddress);
                    node.clusterManager.sendJoinRequest(possibleAddress, true);
                }
            }
    }
}
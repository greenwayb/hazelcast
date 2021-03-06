/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
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
 */

package com.hazelcast.mapreduce.impl;

import com.hazelcast.cluster.ClusterService;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.mapreduce.JobPartitionState;
import com.hazelcast.mapreduce.PartitionIdAware;
import com.hazelcast.mapreduce.impl.operation.KeysAssignmentOperation;
import com.hazelcast.mapreduce.impl.operation.KeysAssignmentResult;
import com.hazelcast.mapreduce.impl.operation.NotifyRemoteExceptionOperation;
import com.hazelcast.mapreduce.impl.task.JobPartitionStateImpl;
import com.hazelcast.mapreduce.impl.task.JobProcessInformationImpl;
import com.hazelcast.mapreduce.impl.task.JobSupervisor;
import com.hazelcast.mapreduce.impl.task.JobTaskConfiguration;
import com.hazelcast.mapreduce.impl.task.MemberAssigningJobProcessInformationImpl;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.InvocationBuilder;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.OperationFactory;
import com.hazelcast.spi.OperationService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hazelcast.mapreduce.JobPartitionState.State.MAPPING;
import static com.hazelcast.mapreduce.JobPartitionState.State.PROCESSED;
import static com.hazelcast.mapreduce.JobPartitionState.State.REDUCING;
import static com.hazelcast.mapreduce.JobPartitionState.State.WAITING;
import static com.hazelcast.mapreduce.impl.operation.RequestPartitionResult.ResultState.SUCCESSFUL;

/**
 * This utility class contains a few basic operations that are needed in multiple places
 */
public final class MapReduceUtil {

    private static final String EXECUTOR_NAME_PREFIX = "mapreduce::hz::";
    private static final String SERVICE_NAME = MapReduceService.SERVICE_NAME;

    private MapReduceUtil() {
    }

    public static JobProcessInformationImpl createJobProcessInformation(JobTaskConfiguration configuration,
                                                                        JobSupervisor supervisor) {
        NodeEngine nodeEngine = configuration.getNodeEngine();
        if (configuration.getKeyValueSource() instanceof PartitionIdAware) {
            int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
            return new JobProcessInformationImpl(partitionCount, supervisor);
        } else {
            int partitionCount = nodeEngine.getClusterService().getMemberList().size();
            return new MemberAssigningJobProcessInformationImpl(partitionCount, supervisor);
        }
    }

    public static void notifyRemoteException(JobSupervisor supervisor, Throwable throwable) {
        String name = supervisor.getConfiguration().getName();
        String jobId = supervisor.getConfiguration().getJobId();
        NotifyRemoteExceptionOperation operation = new NotifyRemoteExceptionOperation(name, jobId, throwable);
        MapReduceService mapReduceService = supervisor.getMapReduceService();
        NodeEngine nodeEngine = mapReduceService.getNodeEngine();
        OperationService os = nodeEngine.getOperationService();
        Address jobOwner = supervisor.getJobOwner();
        os.send(operation, jobOwner);
    }

    public static JobPartitionState.State stateChange(Address owner, int partitionId, JobPartitionState.State currentState,
                                                      JobProcessInformationImpl processInformation,
                                                      JobTaskConfiguration configuration) {

        JobPartitionState[] partitionStates = processInformation.getPartitionStates();
        JobPartitionState partitionState = partitionStates[partitionId];

        // If not yet assigned we don't need to check owner and state
        JobPartitionState.State finalState = null;
        if (partitionState != null) {
            if (!owner.equals(partitionState.getOwner())) {
                return null;
            }
            if (partitionState.getState() != currentState) {
                return null;
            }

            if (currentState == MAPPING) {
                finalState = stateChangeMapping(partitionId, partitionState, processInformation, owner, configuration);
            } else if (currentState == REDUCING) {
                finalState = stateChangeReducing(partitionId, partitionState, processInformation, owner);
            }
        }

        if (currentState == WAITING) {
            if (compareAndSwapPartitionState(partitionId, partitionState, processInformation, owner, MAPPING)) {
                finalState = MAPPING;
            }
        }

        return finalState;
    }

    private static JobPartitionState.State stateChangeReducing(int partitionId, JobPartitionState oldPartitionState,
                                                               JobProcessInformationImpl processInformation, Address owner) {

        if (compareAndSwapPartitionState(partitionId, oldPartitionState, processInformation, owner, PROCESSED)) {
            return PROCESSED;
        }
        return null;
    }

    private static JobPartitionState.State stateChangeMapping(int partitionId, JobPartitionState oldPartitionState,
                                                              JobProcessInformationImpl processInformation, Address owner,
                                                              JobTaskConfiguration configuration) {
        JobPartitionState.State newState = PROCESSED;
        if (configuration.getReducerFactory() != null) {
            newState = REDUCING;
        }
        if (compareAndSwapPartitionState(partitionId, oldPartitionState, processInformation, owner, newState)) {
            return newState;
        }
        return null;
    }

    private static boolean compareAndSwapPartitionState(int partitionId, JobPartitionState oldPartitionState,
                                                        JobProcessInformationImpl processInformation, Address owner,
                                                        JobPartitionState.State newState) {

        JobPartitionState newPartitionState = new JobPartitionStateImpl(owner, newState);
        if (processInformation.updatePartitionState(partitionId, oldPartitionState, newPartitionState)) {
            return true;
        }
        return false;
    }

    public static <K, V> Map<Address, Map<K, V>> mapResultToMember(JobSupervisor supervisor, Map<K, V> result) {

        Set<Object> unassignedKeys = new HashSet<Object>();
        for (Map.Entry<K, V> entry : result.entrySet()) {
            Address address = supervisor.getReducerAddressByKey(entry.getKey());
            if (address == null) {
                unassignedKeys.add(entry.getKey());
            }
        }

        if (unassignedKeys.size() > 0) {
            requestAssignment(unassignedKeys, supervisor);
        }

        // Now assign all keys
        Map<Address, Map<K, V>> mapping = new HashMap<Address, Map<K, V>>();
        for (Map.Entry<K, V> entry : result.entrySet()) {
            Address address = supervisor.getReducerAddressByKey(entry.getKey());
            if (address != null) {
                Map<K, V> data = mapping.get(address);
                if (data == null) {
                    data = new HashMap<K, V>();
                    mapping.put(address, data);
                }
                data.put(entry.getKey(), entry.getValue());
            }
        }
        return mapping;
    }

    private static void requestAssignment(Set<Object> keys, JobSupervisor supervisor) {
        try {
            MapReduceService mapReduceService = supervisor.getMapReduceService();
            String name = supervisor.getConfiguration().getName();
            String jobId = supervisor.getConfiguration().getJobId();
            KeysAssignmentResult assignmentResult = mapReduceService
                    .processRequest(supervisor.getJobOwner(), new KeysAssignmentOperation(name, jobId, keys), name);

            if (assignmentResult.getResultState() == SUCCESSFUL) {
                Map<Object, Address> assignment = assignmentResult.getAssignment();
                for (Map.Entry<Object, Address> entry : assignment.entrySet()) {
                    // Cache the keys for later mappings
                    if (!supervisor.assignKeyReducerAddress(entry.getKey(), entry.getValue())) {
                        throw new IllegalStateException("Key reducer assignment in illegal state");
                    }
                }
            }
        } catch (Exception e) {
            // Just announce it to higher levels
            throw new RuntimeException(e);
        }
    }

    public static String printPartitionStates(JobPartitionState[] partitionStates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partitionStates.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("[").append(i).append("=>");
            sb.append(partitionStates[i] == null ? "null" : partitionStates[i].getState()).append("]");
        }
        return sb.toString();
    }

    public static <V> List<V> executeOperation(OperationFactory operationFactory, MapReduceService mapReduceService,
                                               NodeEngine nodeEngine, boolean returnsResponse) {
        ClusterService cs = nodeEngine.getClusterService();
        OperationService os = nodeEngine.getOperationService();

        Collection<MemberImpl> members = cs.getMemberList();
        List<V> results = returnsResponse ? new ArrayList<V>() : null;

        for (MemberImpl member : members) {
            try {
                Operation operation = operationFactory.createOperation();
                if (cs.getThisAddress().equals(member.getAddress())) {
                    // Locally we can call the operation directly
                    operation.setNodeEngine(nodeEngine);
                    operation.setCallerUuid(nodeEngine.getLocalMember().getUuid());
                    operation.setService(mapReduceService);
                    operation.run();

                    if (returnsResponse) {
                        results.add((V) operation.getResponse());
                    }
                } else {
                    if (returnsResponse) {
                        InvocationBuilder ib = os.createInvocationBuilder(SERVICE_NAME, operation, member.getAddress());

                        results.add((V) ib.invoke().get());
                    } else {
                        os.send(operation, member.getAddress());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return results;
    }

    public static <V> V executeOperation(Operation operation, Address address, MapReduceService mapReduceService,
                                         NodeEngine nodeEngine) {

        ClusterService cs = nodeEngine.getClusterService();
        OperationService os = nodeEngine.getOperationService();
        boolean returnsResponse = operation.returnsResponse();

        try {
            if (cs.getThisAddress().equals(address)) {
                // Locally we can call the operation directly
                operation.setNodeEngine(nodeEngine);
                operation.setCallerUuid(nodeEngine.getLocalMember().getUuid());
                operation.setService(mapReduceService);
                operation.run();

                if (returnsResponse) {
                    return (V) operation.getResponse();
                }
            } else {
                if (returnsResponse) {
                    InvocationBuilder ib = os.createInvocationBuilder(SERVICE_NAME, operation, address);
                    return (V) ib.invoke().get();
                } else {
                    os.send(operation, address);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public static String buildExecutorName(String name) {
        return EXECUTOR_NAME_PREFIX + name;
    }

}

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

package com.hazelcast.mapreduce.impl.operation;

import com.hazelcast.mapreduce.JobPartitionState;
import com.hazelcast.mapreduce.JobProcessInformation;
import com.hazelcast.mapreduce.impl.MapReduceDataSerializerHook;
import com.hazelcast.mapreduce.impl.MapReduceService;
import com.hazelcast.mapreduce.impl.task.JobPartitionStateImpl;
import com.hazelcast.mapreduce.impl.task.JobProcessInformationImpl;
import com.hazelcast.mapreduce.impl.task.JobSupervisor;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.AbstractOperation;

import java.io.IOException;
import java.util.Arrays;

public class RequestPartitionProcessing
        extends ProcessingOperation {

    private volatile JobPartitionState[] partitionStates = null;
    private transient int partitionId;

    public RequestPartitionProcessing() {
    }

    public RequestPartitionProcessing(String name, String jobId, int partitionId) {
        super(name, jobId);
        this.partitionId = partitionId;
    }

    @Override
    public Object getResponse() {
        return partitionStates;
    }

    @Override
    public void run() throws Exception {
        MapReduceService mapReduceService = getService();
        JobSupervisor supervisor = mapReduceService.getJobSupervisor(getName(), getJobId());
        if (supervisor == null) {
            return;
        }

        JobProcessInformationImpl processInformation = supervisor.getJobProcessInformation();
        JobPartitionState newPartitonState = new JobPartitionStateImpl(getCallerAddress(),
                JobPartitionState.State.PROCESSING);

        if (checkState(processInformation)) {
            for (; ; ) {
                JobPartitionState[] oldPartitionStates = processInformation.getPartitionStates();
                JobPartitionState[] newPartitonStates = Arrays.copyOf(oldPartitionStates, oldPartitionStates.length);

                // Set new partition processing information
                newPartitonStates[partitionId] = newPartitonState;

                if (!processInformation.updatePartitionState(oldPartitionStates, newPartitonStates)) {
                    if (checkState(processInformation)) {
                        // Atomic update failed but partition is still not assigned, try again
                        continue;
                    }
                } else {
                    JobPartitionState[] partitionStates = processInformation.getPartitionStates();
                    JobPartitionState partitionState = partitionStates[partitionId];
                    if (partitionState.getState() == JobPartitionState.State.PROCESSING
                            && partitionState.getOwner().equals(getCallerAddress())) {
                        // We managed to get the new partition processing assigned
                        this.partitionStates = partitionStates;
                        return;
                    }

                    // Since situation may happen on migration, two different members requested
                    // the same partition to process, we should just return here and wait for
                    // another request.
                    this.partitionStates = partitionStates;
                    return;
                }
            }
        }
    }

    private boolean checkState(JobProcessInformation processInformation) {
        JobPartitionState[] partitionStates = processInformation.getPartitionStates();
        JobPartitionState partitionState = partitionStates[partitionId];
        return partitionState == null || partitionState.getState() == JobPartitionState.State.WAITING;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeInt(partitionId);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        partitionId = in.readInt();
    }

    @Override
    public int getFactoryId() {
        return MapReduceDataSerializerHook.F_ID;
    }

    @Override
    public int getId() {
        return MapReduceDataSerializerHook.REQUEST_PARTITION_PROCESSING;
    }

}

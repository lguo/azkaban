/*
 * Copyright 2010 LinkedIn, Inc
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

package azkaban.flow;

import azkaban.app.JobDescriptor;
import azkaban.app.JobManager;
import azkaban.serialization.FlowExecutionSerializer;
import azkaban.serialization.de.FlowExecutionDeserializer;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */
public class RefreshableFlowManager implements FlowManager
{
    private final Object idSync = new Object();
    
    private final JobManager jobManager;
    private final FlowExecutionSerializer serializer;
    private final FlowExecutionDeserializer deserializer;
    private final File storageDirectory;

    private final AtomicReference<ImmutableFlowManager> delegateManager;

    public RefreshableFlowManager(
            JobManager jobManager,
            FlowExecutionSerializer serializer,
            FlowExecutionDeserializer deserializer,
            File storageDirectory,
            long lastId
    ) throws IOException
    {
        this.jobManager = jobManager;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.storageDirectory = storageDirectory;

        this.delegateManager = new AtomicReference<ImmutableFlowManager>(null);
        reloadInternal(lastId);
    }

    @Override
    public boolean hasFlow(String name)
    {
        return delegateManager.get().hasFlow(name);
    }

    @Override
    public Flow getFlow(String name)
    {
        return delegateManager.get().getFlow(name);
    }

    @Override
    public Collection<Flow> getFlows()
    {
        return delegateManager.get().getFlows();
    }

    @Override
    public Set<String> getRootFlowNames()
    {
        return delegateManager.get().getRootFlowNames();
    }

    @Override
    public Iterator<Flow> iterator()
    {
        return delegateManager.get().iterator();
    }

    @Override
    public ExecutableFlow createNewExecutableFlow(String name)
    {
        return delegateManager.get().createNewExecutableFlow(name);
    }

    @Override
    public long getNextId()
    {
        synchronized (idSync) {
            return delegateManager.get().getNextId();
        }
    }

    @Override
    public long getCurrMaxId()
    {
        return delegateManager.get().getCurrMaxId();
    }

    @Override
    public FlowExecutionHolder saveExecutableFlow(FlowExecutionHolder holder)
    {
        return delegateManager.get().saveExecutableFlow(holder);
    }

    @Override
    public FlowExecutionHolder loadExecutableFlow(long id)
    {
        return delegateManager.get().loadExecutableFlow(id);
    }

    @Override
    public void reload() throws IOException
    {
        reloadInternal(null);
    }


    private final void reloadInternal(Long lastId) throws IOException
    {
        Map<String, Flow> flowMap = new HashMap<String, Flow>();
        Map<String, List<String>> folderToRoot = new LinkedHashMap<String, List<String>>();
        Set<String> rootFlows = new TreeSet<String>();
        final Map<String, JobDescriptor> allJobDescriptors = jobManager.loadJobDescriptors();
        
        for (JobDescriptor rootDescriptor : jobManager.getLocalRootJobDescriptors(allJobDescriptors)) {
            final String id = rootDescriptor.getId();
            //System.out.println("build flow:" + id);
            if ( id != null) {
                // This call of magical wonderment ends up pushing all Flow objects in the dependency graph for the root into flowMap
                Flows.buildLegacyFlow(jobManager, flowMap, rootDescriptor, allJobDescriptors);
                rootFlows.add(rootDescriptor.getId());

                // For folder path additions
                String folder = rootDescriptor.getFolder(); 

                //System.out.println("add folder:" + jobPath + "\tjob:" + rootDescriptor.getId());
                List<String> root = folderToRoot.get(folder);
                if (root == null) {
                	root = new ArrayList<String>();
                	folderToRoot.put(folder, root);
                }
                root.add(rootDescriptor.getId());
            }
        }
        
        synchronized (idSync) {
            delegateManager.set(
                    new ImmutableFlowManager(
                            flowMap,
                            rootFlows,
                            folderToRoot,
                            serializer,
                            deserializer,
                            storageDirectory,
                            lastId == null ? delegateManager.get().getCurrMaxId() : lastId
                    )
            );
        }
    }
    

    @Override
	public List<String> getFolders() {
		return delegateManager.get().getFolders();
	}

	@Override
	public List<String> getRootNamesByFolder(String folder) {
		return delegateManager.get().getRootNamesByFolder(folder);
	}

    @Override
    public Map<String, String> getDependantFlows(String folder) {
        
        Set<String> rootFlows = getRootFlowNames();
        Map<String, String> ret = new HashMap<String, String>();
        
        LinkedList<JobDescriptor> queue = new LinkedList<JobDescriptor>();
        
        for (String rootFlow: rootFlows) {
            JobDescriptor descriptor = jobManager.getJobDescriptor(rootFlow);
            final String jobFolder = descriptor.getFolder();
            
            // ignore root jobs already in the folder
            if (folder.equals(jobFolder)) continue;
            queue.add(descriptor);
        }
        
        while (!queue.isEmpty()) {
            JobDescriptor top = queue.pollFirst();
            
            Set<JobDescriptor> dependents = top.getDependencies();
            for (JobDescriptor dependent: dependents) {
                // ignore jobs in the folder
                if (folder.equals(dependent.getFolder())) {
                    ret.put(top.getId(), top.getFolder());
                }
                else {
                    queue.add(dependent);
                }
            }
            
        }
        return ret;
    }

}

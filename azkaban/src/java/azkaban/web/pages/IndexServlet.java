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

package azkaban.web.pages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import azkaban.app.AzkabanApplication;
import azkaban.app.JobDescriptor;
import azkaban.common.web.Page;
import azkaban.flow.Flow;
import azkaban.flow.FlowManager;
import azkaban.jobs.JobExecutorManager.ExecutingJobAndInstance;
import azkaban.scheduler.ScheduledJob;
import azkaban.util.json.JSONUtils;
import azkaban.web.AbstractAzkabanServlet;
import azkaban.web.ProcessingUtils;

/**
 * The main page
 * 
 * @author jkreps
 * 
 */
public class IndexServlet extends AbstractAzkabanServlet {
    private static final DateTimeFormatter ZONE_FORMATTER = DateTimeFormat.forPattern("z");
    private static final Logger logger = Logger.getLogger(IndexServlet.class.getName());
    
    private static final long serialVersionUID = 1;
    private String jobQuery = null;
    
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
            IOException {
        /* set runtime properties from request and response */
        super.setRuntimeProperties(req, resp);

        /* delete a folder*/
        if (hasParam(req, "action") && hasParam(req, "folder") && hasParam(req, "toCheck")) {
            final String action = getParam(req, "action");
            if ("delete".equals(action)) {
                
                Map<String, Object> jsonMap = 
                    ProcessingUtils.deleteFolder(req, resp, getApplication());
                
                resp.setContentType("application/json");
                resp.getWriter().print(JSONUtils.toJSONString(jsonMap));
                resp.getWriter().flush();
                return;
            }
        }

        /* get search query */
        jobQuery = ExecutingJobUtils.getJobSearch(req);
        if (jobQuery != null) jobQuery = jobQuery.trim();

        Page page = getPage(req, resp, jobQuery);
        page.render();
    }


    private Page getPage(HttpServletRequest req, HttpServletResponse resp, String query) {
        
        AzkabanApplication app = getApplication();
        @SuppressWarnings("unused")
        Map<String, JobDescriptor> descriptors = app.getJobManager().loadJobDescriptors();
        
        Page page = newPage(req, resp, "azkaban/web/pages/index.vm");
        page.add("logDir", app.getLogDirectory());
        
        FlowManager flowMgr = app.getAllFlows();
        //page.add("flows", allFlows);
        
        List<ScheduledJob> scheduled = app.getScheduleManager().getSchedule();
        page.add("scheduled", filterScheduled(flowMgr, scheduled, query));
        
        Collection<ExecutingJobAndInstance> executing = app.getJobExecutorManager().getExecutingJobs();
        Collection<ExecutingJobAndInstance> executingFiltered = filterExecuting(executing, query);
        page.add("executing", executingFiltered);
        
        //Multimap<String, JobExecution> completed = app.getJobExecutorManager().getCompleted();
        //page.add("completed", completed);
        
        //Set<String> rootFlowNames = app.getAllFlows().getRootFlowNames();
        //page.add("rootJobNames", rootFlowNames);
        
        page.add("folderNames", getFolders(flowMgr, query));
        
        page.add("jobDescComparator", JobDescriptor.NAME_COMPARATOR);
        
        ExecutingJobUtils utils = new ExecutingJobUtils();
        page.add("jsonExecution", utils.getExecutableJobAndInstanceJSON(executingFiltered));
        page.add("timezone", ZONE_FORMATTER.print(System.currentTimeMillis()));
        page.add("currentTime",(new DateTime()).getMillis());
        
        return page;
    }

    private Collection<ExecutingJobAndInstance> filterExecuting(
                Collection<ExecutingJobAndInstance> executing,
                String jobQuery) {
            if (jobQuery == null || jobQuery.isEmpty()) return executing;
            
            Collection<ExecutingJobAndInstance> ret = 
                new ArrayList<ExecutingJobAndInstance>();
            for (ExecutingJobAndInstance job: executing) {
                if (job.getExecutableFlow().getName().indexOf(jobQuery)>=0) {
                    ret.add(job);
                }
            }
            return ret;
        }


    private List<ScheduledJob> filterScheduled(FlowManager manager, 
            List<ScheduledJob> scheduled,
            String jobQuery) {
            if (jobQuery == null || jobQuery.isEmpty()) return scheduled;
            
            List<ScheduledJob> ret = new ArrayList<ScheduledJob>();
            for (ScheduledJob job: scheduled) {
                final String id = job.getId();
                final Flow flow = manager.getFlow(id);
                if (jobQuery == null || 
                    jobQuery.isEmpty() ||
                    flow.getName().indexOf(jobQuery)>=0) {
                    ret.add(job);
                }
            }
            return ret;
        }


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        /* set runtime properties from request and response */
        super.setRuntimeProperties(req, resp);

        AzkabanApplication app = getApplication();

        resp.setContentType("application/json");
        Map<String, Object> jsonMap;
        String action = getParam(req, "action");
        if ("loadjobs".equals(action)) {
            String folder = getParam(req, "folder");
            resp.getWriter().print(getJSONJobsForFolder(app.getAllFlows(), folder, jobQuery));
            resp.getWriter().flush();
            return;
        }
        else if("unschedule".equals(action)) {
            jsonMap = ProcessingUtils.unscheduleJob(req, resp, app);
        } else if("cancel".equals(action)) {
            jsonMap = ProcessingUtils.cancelJob(req,resp, app);
        } else if("schedule".equals(action)) {
            jsonMap = ProcessingUtils.scheduleJobs(req, resp, app);
        }
        else if("upload".equals(action)) {
            jsonMap = ProcessingUtils.upload(req, resp, app);
        }
        else {
            throw new ServletException("Unknown action: " + action);
        }
//        resp.sendRedirect(req.getContextPath());
        resp.getWriter().print(JSONUtils.toJSONString(jsonMap));
        resp.getWriter().flush();
        return;

    }

	private String getJSONJobsForFolder(FlowManager manager, String folder, 
	        String query) {
    	List<String> rootJobs = manager.getRootNamesByFolder(folder);
    	Collections.sort(rootJobs);

    	ArrayList<Object> rootJobObj = new ArrayList<Object>();
    	boolean hit ;
    	for (String root: rootJobs) {
    	    Flow flow = manager.getFlow(root);
    	    
    	    HashMap<String,Object> flowObj = new HashMap<String, Object>();
    	    hit = getJSONDependencyTree(flow, query, flowObj);
    	    if (hit) rootJobObj.add(flowObj);
    	}
    	
    	return JSONUtils.toJSONString(rootJobObj);
    }
    
    private List<String> getFolders(FlowManager manager, String jobQuery) {
        
        final List<String> allFolders = manager.getFolders();
        if (jobQuery == null || jobQuery.isEmpty()) return allFolders;
        
        List<String> ret = new ArrayList<String>();
        for (String folder: allFolders) {
            List<String> tops = manager.getRootNamesByFolder(folder);
            
            for (String flowName: tops) {
                final Flow flow = manager.getFlow(flowName);
                if (match(flow, jobQuery)) {
                    ret.add(folder);
                    break;
                }
            }
        }
        
        return ret;
    }

    private boolean match (Flow flow, String query) {
        boolean hit = query==null || query.isEmpty() || flow.getName().indexOf(query)>=0;
        if (hit) return true;
        
        if (flow.hasChildren()) {
            for(Flow child : flow.getChildren()) {
                if ( match(child, query)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean getJSONDependencyTree(Flow flow, String query, 
            HashMap<String,Object> jobObject) {
    	
        jobObject.put("name", flow.getName());
        
        boolean childHit = false;
    	if (flow.hasChildren()) {
    		ArrayList<HashMap<String,Object>> dependencies = new ArrayList<HashMap<String,Object>>();
    		for(Flow child : flow.getChildren()) {
    			HashMap<String, Object> childObj = new HashMap<String, Object>();
    			if (getJSONDependencyTree(child, query, childObj)) {
    			    childHit = true;
    			}
    			dependencies.add(childObj);
    		}
    		
    		Collections.sort(dependencies, new FlowComparator());
    		jobObject.put("children", dependencies);
    	}

    	boolean hit = query==null || query.isEmpty() || flow.getName().indexOf(query)>=0;
    	if (hit) {
            jobObject.put("hit", "true");
        }else {
            jobObject.put("hit", "false");
        }
    	return hit || childHit;
    }

    private class FlowComparator implements Comparator<Map<String,Object>> {

		@Override
		public int compare(Map<String,Object> arg0, Map<String,Object> arg1) {
			String first = (String)arg0.get("name");
			String second = (String)arg1.get("name");
			return first.compareTo(second);
		}
    	
    }
    
}

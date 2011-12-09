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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Hours;
import org.joda.time.LocalDateTime;
import org.joda.time.Minutes;
import org.joda.time.ReadablePeriod;
import org.joda.time.Seconds;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import azkaban.app.AzkabanApplication;
import azkaban.app.JobDescriptor;
import azkaban.app.JobManager;
import azkaban.common.web.Page;
import azkaban.flow.ExecutableFlow;
import azkaban.flow.Flow;
import azkaban.flow.FlowManager;
import azkaban.jobs.JobExecutionException;
import azkaban.jobs.JobExecutorManager.ExecutingJobAndInstance;
import azkaban.scheduler.ScheduledJob;
import azkaban.util.json.JSONUtils;
import azkaban.web.AbstractAzkabanServlet;

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
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
            IOException {
        /* set runtime properties from request and response */
        super.setRuntimeProperties(req, resp);

        jobQuery = ExecutingJobUtils.getJobSearch(req);
        if (jobQuery != null) jobQuery = jobQuery.trim();
        
        /* delete a folder*/
        if (hasParam(req, "action") && hasParam(req, "folder") && hasParam(req, "toCheck")) {
            final String action = getParam(req, "action");
            if ("delete".equals(action)) {
                final String folder = getParam(req, "folder");
                final String toCheck = getParam(req, "toCheck");
                
                final Map<String, Object> jsonMap = deleteFolder(folder, toCheck);
                
                resp.setContentType("application/json");
                resp.getWriter().print(JSONUtils.toJSONString(jsonMap));
                resp.getWriter().flush();
                return;
            }
        }
        
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

    private Map<String, Object> toJson (String status, String msg) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("message", msg);
        return map;
    }

    private Map<String, Object> deleteFolder(String folder, String toCheck)
    throws IOException, ServletException {
        
        if (folder == null || folder.trim().length()==0) 
            return toJson("error", "Invalid empty folder");
        
        System.out.println("to delete folder " + folder);
        
        AzkabanApplication app = getApplication();
        FlowManager flowMgr = app.getAllFlows();
        JobManager jobMgr = app.getJobManager();
            
        if ("true".equals(toCheck)) {
            Map<String, String> dependantFlows = flowMgr.getDependantFlows(folder);

            if (dependantFlows != null && dependantFlows.size()>0) {
                StringBuffer msg = new StringBuffer("The following flows will become "
                        + "invalid: <br> <br>");
                for (Map.Entry<String, String> entry: dependantFlows.entrySet()) {
                    msg.append(entry.getKey() + " in " + entry.getValue() + "<br>");
                }
                
                msg.append("<br>Do you want to proceed?");
                return toJson("confirm", msg.toString());
            }
        }
        
        try {
            jobMgr.deleteFolder(folder);
        }
        catch (IOException e) {
            return toJson(
                    "error",
                    "Error in deleting folder " + folder + "\n"
                    + e.getLocalizedMessage());
        }
        
        flowMgr.reload();
        return toJson("success", "delete was successful");
    }
            
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        /* set runtime properties from request and response */
        super.setRuntimeProperties(req, resp);

        AzkabanApplication app = getApplication();
        String action = getParam(req, "action");
        if ("loadjobs".equals(action)) {
        	resp.setContentType("application/json");
        	String folder = getParam(req, "folder");
        	resp.getWriter().print(getJSONJobsForFolder(app.getAllFlows(), folder, jobQuery));
        	resp.getWriter().flush();
        	return;
        }
        /*
        else if ("delete".equals(action) && hasParam(req, "folder")){
             String folder = getParam(req, "folder");
             boolean deleted = deleteFolder(req, resp, folder);
             if (deleted) {
                 Page page = getPage(req, resp, null);
                 page.render();
             }
             return;
        } */
        else if("unschedule".equals(action)) {
            String jobid = getParam(req, "job");
            app.getScheduleManager().removeScheduledJob(jobid);
        } else if("cancel".equals(action)) {
            cancelJob(app, req);
        } else if("schedule".equals(action)) {
            String redirect = scheduleJobs(app, req, resp);
            if (!redirect.isEmpty()) {
            	resp.sendRedirect(redirect);
            	return;
            }
        } else {
            throw new ServletException("Unknown action: " + action);
        }
        resp.sendRedirect(req.getContextPath());
    }
    
	private String getJSONJobsForFolder(FlowManager manager, String folder, 
	        String query) {
    	List<String> rootJobs = manager.getRootNamesByFolder(folder);
    	Collections.sort(rootJobs);

    	ArrayList<Object> rootJobObj = new ArrayList<Object>();
    	for (String root: rootJobs) {
    	    if (query == null || query.isEmpty() || 
    	        root.indexOf(query)>=0) {
    	       Flow flow = manager.getFlow(root);
    	       HashMap<String,Object> flowObj = getJSONDependencyTree(flow);
    	       rootJobObj.add(flowObj);
    	    }
    	}
    	
    	return JSONUtils.toJSONString(rootJobObj);
    }
    
    private List<String> getFolders(FlowManager manager, String jobQuery) {
        
        final List<String> allFolders = manager.getFolders();
        if (jobQuery == null || jobQuery.isEmpty()) return allFolders;
        
        List<String> ret = new ArrayList<String>();
        for (String folder: allFolders) {
            List<String> topJobs = manager.getRootNamesByFolder(folder);
            for (String topJob: topJobs) {
                if (topJob.indexOf(jobQuery)>=0) {
                    ret.add(folder);
                    break;
                }
            }
        }
        return ret;
    }

	private HashMap<String,Object> getJSONDependencyTree(Flow flow) {
    	HashMap<String,Object> jobObject = new HashMap<String,Object>();
    	jobObject.put("name", flow.getName());
    	
    	if (flow.hasChildren()) {
    		ArrayList<HashMap<String,Object>> dependencies = new ArrayList<HashMap<String,Object>>();
    		for(Flow child : flow.getChildren()) {
    			HashMap<String, Object> childObj = getJSONDependencyTree(child);
    			dependencies.add(childObj);
    		}
    		
    		Collections.sort(dependencies, new FlowComparator());
    		jobObject.put("children", dependencies);
    	}
    	
    	return jobObject;
    }

    private class FlowComparator implements Comparator<Map<String,Object>> {

		@Override
		public int compare(Map<String,Object> arg0, Map<String,Object> arg1) {
			String first = (String)arg0.get("name");
			String second = (String)arg1.get("name");
			return first.compareTo(second);
		}
    	
    }
    
    private void cancelJob(AzkabanApplication app, HttpServletRequest req) throws ServletException {

        String jobId = getParam(req, "job");
        try {
			app.getJobExecutorManager().cancel(jobId);
		} catch (Exception e1) {
			logger.error("Error cancelling job " + e1);
		}
        
        Collection<ExecutingJobAndInstance> executing = app.getJobExecutorManager().getExecutingJobs();
        for(ExecutingJobAndInstance curr: executing) {
            ExecutableFlow flow = curr.getExecutableFlow();
            final String flowId = flow.getId();
            if(flowId.equals(jobId)) {
                final String flowName = flow.getName();
                try {
                    if(flow.cancel()) {
                        addMessage(req, "Cancelled " + flowName);
                        logger.info("Job '" + flowName + "' cancelled from gui.");
                    } else {
                        logger.info("Couldn't cancel flow '" + flowName + "' for some reason.");
                        addError(req, "Failed to cancel flow " + flowName + ".");
                    }
                } catch(Exception e) {
                    logger.error("Exception while attempting to cancel flow '" + flowName + "'.", e);
                    addError(req, "Failed to cancel flow " + flowName + ": " + e.getMessage());
                }
            }
        }
    }

    private String scheduleJobs(AzkabanApplication app,
                              HttpServletRequest req,
                              HttpServletResponse resp) throws IOException, ServletException {
        String[] jobNames = req.getParameterValues("jobs");
        if(!hasParam(req, "jobs")) {
            addError(req, "You must select at least one job to run.");
            return "";
        }
        
        if (hasParam(req, "flow_now")) {
        	if (jobNames.length > 1) {
        		addError(req, "Can only run flow instance on one job.");
                return "";
        	}
        	
        	String jobName = jobNames[0];
            JobManager jobManager = app.getJobManager();
            JobDescriptor descriptor = jobManager.getJobDescriptor(jobName);
            if (descriptor == null) {
            	addError(req, "Can only run flow instance on one job.");
                return "";
            }
            else {
            	return req.getContextPath() + "/flow?job_id=" + jobName;
            }
        }
        else {
	        for(String job: jobNames) {
	            if(hasParam(req, "schedule")) {
	                int hour = getIntParam(req, "hour");
	                int minutes = getIntParam(req, "minutes");
	                boolean isPm = getParam(req, "am_pm").equalsIgnoreCase("pm");
	                String scheduledDate = req.getParameter("date");
	                DateTime day = null;
	                if(scheduledDate == null || scheduledDate.trim().length() == 0) {
	                	day = new LocalDateTime().toDateTime();
	                } else {
		                try {
		                	day = DateTimeFormat.forPattern("MM-dd-yyyy").parseDateTime(scheduledDate);
		                } catch(IllegalArgumentException e) {
		                	addError(req, "Invalid date: '" + scheduledDate + "'");
		                	return "";
		                }
	                }
	
	                ReadablePeriod thePeriod = null;
	                if(hasParam(req, "is_recurring"))
	                    thePeriod = parsePeriod(req);
	
	                if(isPm && hour < 12)
	                    hour += 12;
	                hour %= 24;
	
	                app.getScheduleManager().schedule(job,
                            day.withHourOfDay(hour)
                            .withMinuteOfHour(minutes)
                            .withSecondOfMinute(0),
                         thePeriod,
                         false);

	                addMessage(req, job + " scheduled.");
	            } else if(hasParam(req, "run_now")) {
	                boolean ignoreDeps = !hasParam(req, "include_deps");
	                try {
	                	app.getJobExecutorManager().execute(job, ignoreDeps);
	                }
	                catch (JobExecutionException e) {
	                	addError(req, e.getMessage());	
	                	return "";
	                }
	                addMessage(req, "Running " + job);
	            }
	            else {
	                addError(req, "Neither run_now nor schedule param is set.");
	            }
	        }
	        return "";
        }

    }

    private ReadablePeriod parsePeriod(HttpServletRequest req) throws ServletException {
        int period = getIntParam(req, "period");
        String periodUnits = getParam(req, "period_units");
        if("d".equals(periodUnits))
            return Days.days(period);
        else if("h".equals(periodUnits))
            return Hours.hours(period);
        else if("m".equals(periodUnits))
            return Minutes.minutes(period);
        else if("s".equals(periodUnits))
            return Seconds.seconds(period);
        else
            throw new ServletException("Unknown period unit: " + periodUnits);
    }

}

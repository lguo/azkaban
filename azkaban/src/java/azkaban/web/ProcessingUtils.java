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

package azkaban.web;
import azkaban.app.AzkabanApplication;
import azkaban.app.JobDescriptor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;

import azkaban.app.JobManager;
import azkaban.common.utils.Utils;
import azkaban.flow.ExecutableFlow;
import azkaban.flow.FlowManager;
import azkaban.jobs.JobExecutionException;
import azkaban.jobs.JobExecutorManager.ExecutingJobAndInstance;

import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Hours;
import org.joda.time.LocalDateTime;
import org.joda.time.Minutes;
import org.joda.time.ReadablePeriod;
import org.joda.time.Seconds;
import org.joda.time.format.DateTimeFormat;

/**
 * Deploy and undeploy jobs
 * 
 * @author jkreps, lguo
 * 
 */
public class ProcessingUtils {
    //private static final Logger log = Logger.getLogger(ProcessingUtils.class);
    
    private static final int DEFAULT_UPLOAD_DISK_SPOOL_SIZE = 20 * 1024 * 1024;
    private static MultipartParser _multipartParser;
    private static String _tempDir = null;
    static {
       _multipartParser = new MultipartParser(DEFAULT_UPLOAD_DISK_SPOOL_SIZE);
    }

    private static final String SUCCESS = "success";
    private static final String FAIL= "fail";
    private static final String CONFIRM = "confirm";
    private static final String ERROR = "error";
    private static final String REDIRECT = "redirect";
    
    /**
     * construct json results
     * @param status
     * @param msg
     * @return
     */
    static public Map<String, Object> toJson (String status, String msg) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("message", msg);
        return map;
    }

    static public Map<String, Object> toJson (String status, String msg, String redirect) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("message", msg);
        map.put("redirect", redirect);
        return map;
    }

    /**
     * upload jobs
     * 
     * @param request
     * @param response
     * @param app
     * @return
     */
    static public Map<String, Object> upload(
            HttpServletRequest request, HttpServletResponse response,
            AzkabanApplication app) 
    {
        if(!ServletFileUpload.isMultipartContent(request))
            return toJson(ERROR, "No job file found!");

        if (_tempDir == null)
            _tempDir = app.getTempDirectory();
            
        Map<String, Object> params;
        try {
            params = _multipartParser.parseMultipart(request);
        } catch (Exception e) {
            return toJson(ERROR, "Parse error:" + e.getMessage());
        }
        
        JobManager jobManager = app.getJobManager();
        try {
            FileItem item = (FileItem) params.get("file");
            String deployPath = (String) params.get("path");
            File jobDir = extractFile(item);
            jobManager.deployJobDir(jobDir.getAbsolutePath(), deployPath);
            jobDir.delete();
        }catch (Exception e) {
            return toJson(FAIL, "Upload failed:" + e.getMessage());
        }
        return toJson(REDIRECT, "Upload was successful!", 
                request.getContextPath());
    }

    
    static public Map<String, Object> deleteFolder(
            HttpServletRequest req, HttpServletResponse resp,
            AzkabanApplication app) throws IOException {
        String folder;
        try {
            folder = AbstractAzkabanServlet.getParam(req, "folder");
        } catch (ServletException e1) {
            return toJson(ERROR, "Error getting parameter folder:" + e1.getMessage());
        }
        String toCheck;
        try {
            toCheck = AbstractAzkabanServlet.getParam(req, "toCheck");
        } catch (ServletException e1) {
            return toJson(ERROR, "Error getting parameter toCheck:" + e1.getMessage());
        }

        if (folder == null || folder.trim().length()==0) 
            return ProcessingUtils.toJson(ERROR, "Invalid empty folder");
        
        System.out.println("to delete folder " + folder);
        
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
                return toJson(CONFIRM, msg.toString());
            }
            else {
                return toJson(CONFIRM, "Do you want to delete folder " + folder +"?");
            }
        }
        
        try {
            jobMgr.deleteFolder(folder);
        }
        catch (IOException e) {
            return toJson(
                    FAIL,
                    "Error in deleting folder " + folder + "\n"
                    + e.getLocalizedMessage());
        }
        
        flowMgr.reload();
        return toJson(SUCCESS, "Delete was successful!");
    }


    static private File extractFile(FileItem item) throws IOException, ServletException {
        final String contentType = item.getContentType();
        if (contentType.startsWith("application/zip")) {
            return unzipFile(item);
        }

        if (contentType.startsWith("application/x-tar")) {
            return untarFile(item);
        }

        throw new ServletException(String.format("Unsupported file type[%s].", contentType));
    }

    //private void setMessagedUrl(HttpServletResponse response, String redirectUrl, String message) throws IOException {
    //    String url = redirectUrl + "/" + message;
    //    response.sendRedirect(response.encodeRedirectURL(url));
    //}
        
    static private File unzipFile(FileItem item) throws ServletException, IOException {
        File temp = File.createTempFile("job-temp", ".zip");
        temp.deleteOnExit();
        
        OutputStream out = new BufferedOutputStream(new FileOutputStream(temp));
        IOUtils.copy(item.getInputStream(), out);
        out.close();
        
        ZipFile zipfile = new ZipFile(temp);
        File unzipped = Utils.createTempDir(new File(_tempDir, Long.toString(System.currentTimeMillis())));
        Utils.unzip(zipfile, unzipped);
        temp.delete();
        return unzipped;
    }

    static private File untarFile(FileItem item) throws IOException, ServletException {
        throw new ServletException("Unsupported file type [tar].");
    }
    
    static public Map<String, Object> scheduleJobs(
            HttpServletRequest req,
            HttpServletResponse resp, 
            AzkabanApplication app) 
    throws IOException, ServletException {

        if(!AbstractAzkabanServlet.hasParam(req, "jobs")) {
            return toJson(ERROR, "You must select at least one job to run.");
        }

        String[] jobNames = req.getParameterValues("jobs");
        if (AbstractAzkabanServlet.hasParam(req, "flow_now")) {
            if (jobNames.length > 1) {
                return toJson(ERROR, "Can only run flow instance on one job.");
            }

            String jobName = jobNames[0];
            JobManager jobManager = app.getJobManager();
            JobDescriptor descriptor = jobManager.getJobDescriptor(jobName);
            if (descriptor == null) {
                return toJson(ERROR, "Can only run flow instance on one job.");
            }
            else {
                return toJson (SUCCESS, "Scheduling was successful!", 
                        req.getContextPath() + "/flow?job_id=" + jobName);
            }
        }
        else {
            for(String job: jobNames) {
                if(AbstractAzkabanServlet.hasParam(req, "schedule")) {
                    int hour = AbstractAzkabanServlet.getIntParam(req, "hour");
                    int minutes = AbstractAzkabanServlet.getIntParam(req, "minutes");
                    boolean isPm = AbstractAzkabanServlet.getParam(req, "am_pm").equalsIgnoreCase("pm");
                    String scheduledDate = req.getParameter("date");
                    DateTime day = null;
                    if(scheduledDate == null || scheduledDate.trim().length() == 0) {
                        day = new LocalDateTime().toDateTime();
                    } else {
                        try {
                            day = DateTimeFormat.forPattern("MM-dd-yyyy").parseDateTime(scheduledDate);
                        } catch(IllegalArgumentException e) {
                            return toJson(ERROR, "Invalid date: '" + scheduledDate + "'");
                        }
                    }

                    ReadablePeriod thePeriod = null;
                    if(AbstractAzkabanServlet.hasParam(req, "is_recurring"))
                        thePeriod = parsePeriod(req);

                    if(isPm && hour < 12)
                        hour += 12;
                    hour %= 24;

                    app.getScheduleManager().schedule(job,
                            day.withHourOfDay(hour).withMinuteOfHour(minutes).withSecondOfMinute(0),
                            thePeriod,
                            false);

                    return toJson(SUCCESS, job + " scheduled.");
                } else if(AbstractAzkabanServlet.hasParam(req, "run_now")) {
                    boolean ignoreDeps = !AbstractAzkabanServlet.hasParam(req, "include_deps");
                    try {
                        app.getJobExecutorManager().execute(job, ignoreDeps);
                    }
                    catch (JobExecutionException e) {
                        return toJson(FAIL, "Scheduling failed:" + e.getMessage());  
                    }
                    return toJson(SUCCESS, "Running " + job);
                }
                else {
                    return toJson(ERROR, "Neither run_now nor schedule param is set.");
                }
            }
            return toJson(ERROR, "You must select at least one job to run.");
        }
    }

    static public Map<String, Object> 
        cancelJob(HttpServletRequest req, 
                  HttpServletResponse resp,
                  AzkabanApplication app) 
                          throws ServletException {

        String jobId = AbstractAzkabanServlet.getParam(req, "job");
        try {
            app.getJobExecutorManager().cancel(jobId);
        } catch (Exception e1) {
            return toJson(ERROR, "Error cancelling job: " + e1);
        }
        
        Collection<ExecutingJobAndInstance> executing = app.getJobExecutorManager().getExecutingJobs();
        for(ExecutingJobAndInstance curr: executing) {
            ExecutableFlow flow = curr.getExecutableFlow();
            final String flowId = flow.getId();
            if(flowId.equals(jobId)) {
                final String flowName = flow.getName();
                try {
                    if(flow.cancel()) {
                        return toJson(SUCCESS, "Cancelled " + flowName);
                        //logger.info("Job '" + flowName + "' cancelled from gui.");
                    } else {
                        //logger.info("Couldn't cancel flow '" + flowName + "' for some reason.");
                        return toJson(FAIL, "Failed to cancel flow " + flowName + ".");
                    }
                } catch(Exception e) {
                    //logger.error("Exception while attempting to cancel flow '" + flowName + "'.", e);
                    return toJson(FAIL, "Failed to cancel flow " + flowName + ": " + e.getMessage());
                }
            }
        }
        return toJson(FAIL, "Couldn't find executing job " +  jobId + " to cancel.");
    }

    static public Map<String, Object> 
    unscheduleJob(HttpServletRequest req, 
              HttpServletResponse resp,
              AzkabanApplication app) 
                      throws ServletException {

        if (!AbstractAzkabanServlet.hasParam(req, "job"))
            return toJson(ERROR, "Parameter job is missing!");
        
        String jobid = AbstractAzkabanServlet.getParam(req, "job");
        app.getScheduleManager().removeScheduledJob(jobid);
        return toJson(SUCCESS, "Unscheduling " + jobid + " was successful!", req.getContextPath());
    }
    
    private static ReadablePeriod parsePeriod(HttpServletRequest req) throws ServletException {
        int period = AbstractAzkabanServlet.getIntParam(req, "period");
        String periodUnits = AbstractAzkabanServlet.getParam(req, "period_units");
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

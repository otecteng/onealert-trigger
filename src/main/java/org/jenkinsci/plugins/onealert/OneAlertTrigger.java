package org.jenkinsci.plugins.onealert;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import java.net.URL;
/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link OneAlertTrigger} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked. 
 *
 * @author Kohsuke Kawaguchi
 */
public class OneAlertTrigger extends Notifier {

    public String serviceKey;
    public boolean triggerOnSuccess;
    public boolean triggerOnFailure;
    public boolean triggerOnUnstable;
    public boolean triggerOnAborted;
    public boolean triggerOnNotBuilt;
    public String incidentKey;
    public String description;
    public Integer numPreviousBuildsToProbe;    
    private LinkedList<Result> resultProbe;
    public static final String DEFAULT_DESCRIPTION_STRING = "this is plugin for onealert";
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OneAlertTrigger(String serviceKey, boolean triggerOnSuccess, boolean triggerOnFailure, boolean triggerOnAborted,
                            boolean triggerOnUnstable, boolean triggerOnNotBuilt, String incidentKey, String description,
                            Integer numPreviousBuildsToProbe) {    
        this.serviceKey = serviceKey;
        this.triggerOnSuccess = triggerOnSuccess;
        this.triggerOnFailure = triggerOnFailure;
        this.triggerOnUnstable = triggerOnUnstable;
        this.triggerOnAborted = triggerOnAborted;
        this.triggerOnNotBuilt = triggerOnNotBuilt;
        this.incidentKey = incidentKey;
        this.description = description;
        this.numPreviousBuildsToProbe = (numPreviousBuildsToProbe != null && numPreviousBuildsToProbe > 0) ? numPreviousBuildsToProbe : 1;
        // if(this.serviceKey != null && this.serviceKey.trim().length() > 0)
        //     this.pagerDuty = PagerDuty.create(serviceKey);
        this.resultProbe = generateResultProbe();

    }

    private LinkedList<Result> generateResultProbe() {
        LinkedList<Result> res = new LinkedList<Result>();
        if(triggerOnSuccess)
            res.add(Result.SUCCESS);
        if(triggerOnFailure)
            res.add(Result.FAILURE);
        if(triggerOnUnstable)
            res.add(Result.UNSTABLE);
        if(triggerOnAborted)
            res.add(Result.ABORTED);
        if(triggerOnNotBuilt)
            res.add(Result.NOT_BUILT);
        return res;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                           BuildListener listener) throws InterruptedException, IOException {

        EnvVars env = build.getEnvironment(listener);
        if (validWithPreviousResults(build, resultProbe, numPreviousBuildsToProbe)) {
            listener.getLogger().println("Triggering OneAlert Notification begin");
         
            JSONObject alert = new JSONObject();
            
            alert.put("app",serviceKey);
            alert.put("eventType", "trigger");
            alert.put("eventId", build.getStartTimeInMillis());
            alert.put("alarmName", build.getProject().getDisplayName() + "[#" + build.getNumber() + "]:" + build.getResult());
            alert.put("entityId", incidentKey);
            alert.put("entityName", description);
            alert.put("priority", 1);
            alert.put("alarmContent", description);
            // alert.put("alarmContent", content);
            listener.getLogger().println(alert.toString());
            try {
                URL url = new URL("http://api.110monitor.com/alert/api/event");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");

                OutputStream os = conn.getOutputStream();
                
                os.write(alert.toString().getBytes("UTF-8"));
                os.flush();


                BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
                String output;
                while ((output = br.readLine()) != null) {
                    listener.getLogger().println(output);
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                listener.getLogger().println(e);
            }
        }
        return true;
    }

    private boolean validWithPreviousResults(AbstractBuild<?, ?> build, List<Result> desiredResultList, int depth) {
        int i = 0;
        while (i < depth && build != null) {
            if (!desiredResultList.contains(build.getResult())) {
                break;
            }
            i++;
            build = build.getPreviousBuild();
        }
        if (i == depth) {
            return true;
        }
        return false;
    }    
    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link HelloWorldBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/OneAlertTrigger/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Publisher> {

        /*
         * (non-Javadoc)
         *
         * @see hudson.tasks.BuildStepDescriptor#isApplicable(java.lang.Class)
         */
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        /*
         * (non-Javadoc)
         *
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName() {
            return "OneAlert Incident Trigger";
        }

    }
}


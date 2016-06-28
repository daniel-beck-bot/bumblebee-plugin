/**
 *
 */
package com.agiletestware.bumblebee;

import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.agiletestware.bumblebee.client.api.BulkUpdateParameters;
import com.agiletestware.bumblebee.client.api.BumblebeeApi;
import com.agiletestware.bumblebee.util.BumblebeeUtils;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Job;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;

/**
 * Main class for the plugin.
 *
 * @author Sergey Oplavin (refactored)
 */
public class BumblebeePublisher extends Recorder {

	/** Descriptor instance. */
	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	/** Logger. */
	private static final Logger LOGGER = Logger.getLogger(BumblebeePublisher.class.getName());

	/** Configurations. */
	private final BumblebeeConfiguration[] configs;

	/**
	 * Constructor.
	 *
	 * @param configs
	 *            Configurations which are set for current job.
	 */
	public BumblebeePublisher(final BumblebeeConfiguration... configs) {
		this.configs = configs;
	}

	/**
	 * Constructor.
	 *
	 * @param configs
	 *            List of configurations.
	 */
	public BumblebeePublisher(final Collection<BumblebeeConfiguration> configs) {
		this(configs.toArray(new BumblebeeConfiguration[configs.size()]));
	}

	/**
	 * This method will return all the tasks
	 *
	 * @return List<TaskProperties>
	 */
	public List<BumblebeeConfiguration> getConfigs() {
		if (configs == null) {
			return new ArrayList<BumblebeeConfiguration>();
		} else {
			return Arrays.asList(configs);
		}
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public boolean perform(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws InterruptedException, IOException {
		final EnvVars envVars = build.getEnvironment(listener);
		final List<EnvDependentConfigurationWrapper> configWrappers = getConfigWrappers(envVars);

		boolean success = true;
		for (final EnvDependentConfigurationWrapper config : configWrappers) {
			try {
				doBulkUpdate(config, build, launcher, listener);

			} catch (final Throwable ex) {
				listener.getLogger().println(ex.getMessage());
				LOGGER.log(Level.SEVERE, null, ex);
				if (config.getFailIfUploadFailed()) {
					listener.getLogger().println("Bumblebee: Fail if upload flag is set to true -> mark build as failed");
					success = false;
				} else {
					listener.getLogger().println("Bumblebee: Fail if upload flag is set to false -> ignore errors in the build step");
				}

			}
		}
		return success;
	}

	/**
	 *
	 * @param envVars
	 *            Env variables map.
	 * @return A list of configuration wrappers which allow consumer to resolve
	 *         env variables in configuration values.
	 */
	private List<EnvDependentConfigurationWrapper> getConfigWrappers(final EnvVars envVars) {
		final List<BumblebeeConfiguration> configList = getConfigs();
		final List<EnvDependentConfigurationWrapper> configWrappers = new ArrayList<EnvDependentConfigurationWrapper>();
		for (final BumblebeeConfiguration config : configList) {
			configWrappers.add(new EnvDependentConfigurationWrapper(config, envVars));
		}
		return configWrappers;
	}

	/**
	 * Send data to bumblebee server.
	 *
	 * @param config
	 *            Config wrapper
	 * @param bulkURL
	 *            The URL to use
	 * @param build
	 *            what it says
	 * @param launcher
	 *            what it says
	 * @param listener
	 *            what it says
	 * @throws Exception
	 */
	@SuppressWarnings("rawtypes")
	public void doBulkUpdate(final EnvDependentConfigurationWrapper config, final AbstractBuild build, final Launcher launcher, final BuildListener listener)
			throws Exception {
		final BulkUpdateParameters params = new BulkUpdateParameters();
		params.setBumbleBeeUrl(DESCRIPTOR.bumblebeeUrl);
		params.setDomain(config.getDomain());
		params.setProject(config.getProjectName());
		params.setEncryptedPassword(DESCRIPTOR.password);
		params.setFormat(config.getFormat());
		params.setAlmUserName(DESCRIPTOR.qcUserName);
		params.setAlmUrl(DESCRIPTOR.qcUrl);
		params.setTestPlanDirectory(config.getTestPlan());
		params.setTestLabDirectory(config.getTestLab());
		params.setTestSet(config.getTestSet());
		params.setResultPattern(config.getResultPattern());
		params.setMode(config.getMode());
		params.setTimeOut(DESCRIPTOR.timeOut);
		params.setCustomProperties(config.getCustomProperties());
		params.setOffline(config.getOffline());

		final PrintStream logger = listener.getLogger();
		final BumblebeeRemoteExecutor remoteExecutor = new BumblebeeRemoteExecutor(BumblebeeUtils.getWorkspace(build), params, listener);
		try {
			logger.println(launcher.getChannel().call(remoteExecutor));
		} catch (final Throwable e) {
			logger.println(e);
			e.printStackTrace(logger);
			throw e;
		}
	}

	/**
	 * Descriptor for bumblebee plugin. It is needed to store global
	 * configuration.
	 *
	 * @author Sergey Oplavin (oplavin.sergei@gmail.com) (refactored)
	 *
	 */
	public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
		private static final String PLUGIN_HELP_PAGE_URI = "/plugin/bumblebee/help/main.html";
		private static final String PLUGIN_DISPLAY_NAME = "Bumblebee  HP  ALM  Uploader";
		private String bumblebeeUrl;
		private String qcUserName;
		private String password;
		private String qcUrl;
		private int timeOut;

		/**
		 * Constructor.
		 */
		public DescriptorImpl() {
			super(BumblebeePublisher.class);
			load();
		}

		@Override
		public String getDisplayName() {
			return PLUGIN_DISPLAY_NAME;
		}

		@Override
		public String getHelpFile() {
			return PLUGIN_HELP_PAGE_URI;
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public BumblebeePublisher newInstance(final StaplerRequest req, final JSONObject formData) throws FormException {
			final List<BumblebeeConfiguration> configs = req.bindParametersToList(BumblebeeConfiguration.class, "Bumblebee.bumblebeeConfiguration.");

			return new BumblebeePublisher(configs);
		}

		@Override
		public boolean configure(final StaplerRequest req, final JSONObject formData) throws FormException {
			return super.configure(req, formData);
		}

		// Used by global.jelly to authenticate User key
		public FormValidation doSaveConnection(@QueryParameter("bumblebeeUrl") final String bumblebeeUrl, @QueryParameter("qcUrl") final String qcUrl,
				@QueryParameter("qcUserName") final String qcUserName, @QueryParameter("password") final String password,
				@QueryParameter("timeOut") final int timeOut) {
			try {
				final String qcUrlTrimmed = StringUtils.trim(qcUrl);
				if (!isUrlReachable(qcUrlTrimmed, timeOut)) {
					return FormValidation.error("FAILED: Could not connect to " + qcUrlTrimmed);
				}
				final String bumblebeeUrlTrimmed = StringUtils.trim(bumblebeeUrl);
				if (!isUrlReachable(bumblebeeUrlTrimmed, timeOut)) {
					return FormValidation.error("FAILED: Could not connect to " + bumblebeeUrl);
				}
				this.qcUserName = qcUserName;
				this.qcUrl = qcUrlTrimmed;
				this.bumblebeeUrl = bumblebeeUrl;
				this.timeOut = timeOut;
				final BumblebeeApi bmapi = new BumblebeeApi(this.bumblebeeUrl, this.timeOut);
				// Set password only if old value is null/empty/blank OR if new
				// value is not equal to old
				if (StringUtils.isBlank(this.password) || !this.password.equals(password)) {
					this.password = bmapi.getEncryptedPassword(StringUtils.trim(password));
				}
				save();
			} catch (final Exception e) {
				LOGGER.log(Level.SEVERE, null, e);
				return FormValidation.error("FAILED: " + e.getMessage());
			}
			return FormValidation.ok("Configuration  Saved");
		}

		/**
		 * Is given URL can be reached with HTTP.
		 *
		 * @param url
		 *            URL
		 * @param timeout
		 *            connection timeout. zero means infinite timeout.
		 * @return
		 */
		private boolean isUrlReachable(final String url, final int timeout) {
			try {
				final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
				connection.setConnectTimeout(timeout);
				connection.setReadTimeout(timeout);
				connection.setRequestMethod("GET");
				final int responseCode = connection.getResponseCode();
				LOGGER.log(Level.INFO, url + " --> HTTP " + responseCode);
				return true;
			} catch (final Exception ex) {
				LOGGER.log(Level.SEVERE, "Could not get response from URL: " + url, ex);
			}
			return false;
		}

		public String getBumblebeeUrl() {
			return this.bumblebeeUrl;
		}

		public String getQcUserName() {
			return this.qcUserName;
		}

		public String getQcUrl() {
			return this.qcUrl;
		}

		public String getPassword() {
			return this.password;
		}

		public int getTimeOut() {
			return this.timeOut;
		}

		public FormValidation doCheckDomain(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String domain)
				throws IOException, ServletException {
			project.checkPermission(Job.CONFIGURE);
			return BumblebeeUtils.validateRequiredField(domain);
		}

		public FormValidation doCheckProjectName(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String projectName)
				throws IOException, ServletException {
			project.checkPermission(Job.CONFIGURE);
			return BumblebeeUtils.validateRequiredField(projectName);
		}

		public FormValidation doCheckTestPlan(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String testPlan)
				throws IOException, ServletException {
			project.checkPermission(Job.CONFIGURE);
			return BumblebeeUtils.validateTestPlan(testPlan);
		}

		public FormValidation doCheckTestLab(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String testLab)
				throws IOException, ServletException {
			project.checkPermission(Job.CONFIGURE);
			return BumblebeeUtils.validateTestLab(testLab);
		}

		public FormValidation doCheckTestSet(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String testSet)
				throws IOException, ServletException {
			project.checkPermission(Job.CONFIGURE);
			return BumblebeeUtils.validateTestSet(testSet);
		}

		public FormValidation doCheckFormat(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String format)
				throws IOException, ServletException {
			project.checkPermission(Job.CONFIGURE);
			return BumblebeeUtils.validateRequiredField(format);
		}

		public FormValidation doCheckbumblebeeUrl(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String bumblebeeUrl)
				throws IOException, ServletException {
			return BumblebeeUtils.validatebumblebeeUrl(bumblebeeUrl);
		}

		public FormValidation doCheckqcUrl(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String qcUrl)
				throws IOException, ServletException {
			return BumblebeeUtils.validateqcUrl(qcUrl);
		}

		public FormValidation doCheckqcUserName(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String qcUserName)
				throws IOException, ServletException {
			return BumblebeeUtils.validateRequiredField(qcUserName);
		}

	}

}

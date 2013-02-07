/*
 *
 * Copyright 2013 Hewlett-Packard Development Company, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package hudson.plugins.gearman;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

import java.util.List;
import java.util.Stack;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GearmanPlugin {@link Builder}.
 *
 * <p>
 * This sets up the gearman plugin as another plugin in Jenkins
 * It will allow us to start and stop the gearman workers.
 * <p>
 *
 * @author Khai Do
 */
public class GearmanPlugin extends Builder {

    private static final Logger logger = LoggerFactory
            .getLogger(Constants.PLUGIN_LOGGER_NAME);
    private final String name;

    @DataBoundConstructor
    public GearmanPlugin(String name) {
        logger.info("--- GearmanPlugin Constructor ---" + name);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        return true;
    }

    @Override
    public DescriptorImpl getDescriptor() {

        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Builder> {

        private static final Logger logger = LoggerFactory
                .getLogger(Constants.PLUGIN_LOGGER_NAME);
        private boolean launchWorker; // launchWorker state (from UI checkbox)
        private String host; // gearman server host
        private int port; // gearman server port
        private final Jenkins jenkins;

        // handles to gearman workers
        public static Stack<AbstractWorkerThread> gewtHandles;
        public static Stack<AbstractWorkerThread> gmwtHandles;

        public DescriptorImpl() {
            logger.info("--- DescriptorImpl Constructor ---");

            jenkins = Jenkins.getInstance();
            gewtHandles = new Stack<AbstractWorkerThread>();
            gmwtHandles = new Stack<AbstractWorkerThread>();

            logger.info("--- DescriptorImpl Constructor ---" + host);
            logger.info("--- DescriptorImpl Constructor ---" + port);

            load();

            /*
             * Not sure when to register gearman functions yet so for now always
             * initialize the launch worker flag to disabled state at jenkins
             * startup so we are always at a known state
             */
            this.launchWorker = false;
            save();
        }

        @Override
        public String getDisplayName() {
            return "Gearman Plugin";
        }

        @Override
        public boolean isApplicable(Class type) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest staplerRequest, JSONObject json)
                throws FormException {
            launchWorker = json.getBoolean("launchWorker");
            logger.info("--- DescriptorImpl Configure function ---"
                    + this.launchWorker());

            // set the gearman server host from value in jenkins config page
            try {
                host = json.getString("host");
            } catch (Exception e) {
                throw new RuntimeException(
                        "Error getting the gearman host name");
            }

            // user input verification
            if (StringUtils.isEmpty(host) || StringUtils.isBlank(host))
                throw new RuntimeException("Invalid gearman host name");

            // set the gearman server port from value in jenkins config page
            try {
                port = Integer.parseInt(json.getString("port"));
            } catch (Exception e) {
                throw new RuntimeException("Invalid gearman port value");
            }

            // i believe gearman already checks for port range, just want to do
            // basic verification here
            if (port <= 0)
                throw new RuntimeException("Invalid gearman port value");

            logger.info("--- DescriptorImpl Configure function ---"
                    + this.getHost());
            logger.info("--- DescriptorImpl Configure function ---"
                    + this.getPort());

            /*
             * Purpose here is to create a 1:1 mapping of 'gearman
             * worker':'jenkins executor' then use the gearman worker to execute
             * builds on that jenkins nodes
             */
            List<Node> nodes = jenkins.getNodes();


            if (launchWorker && !nodes.isEmpty()) {

                AbstractWorkerThread gwt = null;
                // executor threads for slave nodes
                for (Node node : nodes) {
                    Computer c = node.toComputer();
                    if (c.isOnline()) {
                        int numExecutors = c.getExecutors().size();
                        for (int i=0; i<numExecutors; i++) {

                            // create a gearman executor for every jenkins executor
                            gwt = new ExecutorWorkerThread(host, port,
                                    node.getNodeName()+"-exec"+Integer.toString(i), node);
                            gwt.registerJobs();
                            gwt.start();
                            gewtHandles.push(gwt);
                        }
                    }
                }
                // executor threads for master node
                Computer masterComp = Computer.currentComputer();
                Node masterNode = masterComp.getNode();
                Computer c = masterNode.toComputer();
                if (c.isOnline()) {
                    int numExecutors = c.getExecutors().size();
                    for (int i=0; i<numExecutors; i++) {

                        // create a gearman executor for the jenkins master
                        gwt = new ExecutorWorkerThread(host, port,
                                "master-exec"+Integer.toString(i), masterNode);
                        gwt.registerJobs();
                        gwt.start();
                        gewtHandles.push(gwt);
                    }
                }


                /*
                 * Create one additional worker as a management node. This
                 * worker will be used to abort builds.
                 */
                if (!gewtHandles.isEmpty()) {
                    gwt = new ManagementWorkerThread(host, port, host);
                    gwt.registerJobs();
                    gwt.start();
                    gmwtHandles.push(gwt);

                }

            } else if (!launchWorker) { // stop worker threads
                while (!gewtHandles.isEmpty()) { // stop executors
                    AbstractWorkerThread task = gewtHandles.pop();
                    task.stop();
                }
                while (!gmwtHandles.isEmpty()) { // stop management
                    AbstractWorkerThread task = gmwtHandles.pop();
                    task.stop();
                }
            }

            save();
            return true;
        }

        /**
         * This method returns true if the global configuration says we should
         * launch worker.
         */
        public boolean launchWorker() {
            return launchWorker;
        }

        /**
         * This method returns the value from the server host text box
         */
        public String getHost() {
            return host;
        }

        /**
         * This method returns the value from the server port text box
         */
        public int getPort() {
            return port;
        }
    }
}
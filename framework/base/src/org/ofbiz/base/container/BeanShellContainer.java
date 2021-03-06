/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.base.container;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilValidate;

import bsh.EvalError;
import bsh.Interpreter;

/**
 * BeanShellContainer - Container implementation for BeanShell
 *
 */
public class BeanShellContainer implements Container {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    protected String configFileLocation = null;
    protected Interpreter bsh = null;
    protected String containerName;
    protected String name;
    protected int port;

    @Override
    public void init(String[] args, String name, String configFile) {
        this.containerName = name;
        this.configFileLocation = configFile;
    }

    @Override
    public boolean start() throws ContainerException {
        // get the container config
        ContainerConfig.Container cfg = ContainerConfig.getContainer(containerName, configFileLocation);

        // get the app-name
        ContainerConfig.Container.Property appName = cfg.getProperty("app-name");
        if (appName == null || UtilValidate.isEmpty(appName.value)) {
            throw new ContainerException("Invalid app-name defined in container configuration");
        } else {
            this.name = appName.value;
        }

        // get the telnet-port
        ContainerConfig.Container.Property telnetPort = cfg.getProperty("telnet-port");
        if (telnetPort == null || UtilValidate.isEmpty(telnetPort.value)) {
            throw new ContainerException("Invalid telnet-port defined in container configuration");
        } else {
            try {
                this.port = Integer.parseInt(telnetPort.value);
            } catch (Exception e) {
                throw new ContainerException("Invalid telnet-port defined in container configuration; not a valid int");
            }
        }

        // create the interpreter
        bsh = new Interpreter();

        // configure the interpreter
        if (bsh != null) {
            try {
                bsh.set(name, this);
            } catch (EvalError evalError) {
                throw new ContainerException(evalError);
            }
            try {
                bsh.set("portnum", (port - 1));
            } catch (EvalError evalError) {
                throw new ContainerException(evalError);
            }
            try {
                bsh.eval("setAccessibility(true)");
            } catch (EvalError evalError) {
                throw new ContainerException(evalError);
            }

            try {
                bsh.eval("server(portnum)");
            } catch (EvalError evalError) {
                throw new ContainerException(evalError);
            }

            Debug.logInfo("Started BeanShell telnet service on " + (port - 1) + ", " + port, module);
            Debug.logInfo("NOTICE: BeanShell service ports are not secure. Please protect the ports", module);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Stop the container
     *
     * @throws ContainerException
     *
     */
    public void stop() throws ContainerException {
        bsh = null;
    }

    public String getName() {
        return containerName;
    }
}

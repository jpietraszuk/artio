/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway;

import uk.co.real_logic.agrona.IoUtil;
import uk.co.real_logic.fix_gateway.session.SenderAndTargetSessionIdStrategy;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;

import java.io.File;

import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;

public class CommonConfiguration
{
    /** Property name for length of the memory mapped buffers for the counters file */
    public static final String MONITORING_BUFFERS_LENGTH_PROP_NAME = "fix.monitoring.length";
    /** Property name for directory of the conductor buffers */
    public static final String MONITORING_FILE_PROP_NAME = "fix.monitoring.file";
    /** Property name for the flag to enable or disable debug logging */
    public static final String DEBUG_PRINT_MESSAGES_PROPERTY = "fix.core.debug";
    /** Property name for the file to log debug messages to, default is standard output */
    public static final String DEBUG_FILE_PROPERTY = "fix.core.debug.file";

    public static final int DEFAULT_MONITORING_BUFFER_LENGTH = 8 * 1024 * 1024;
    public static final String DEFAULT_MONITORING_FILE = IoUtil.tmpDirName() + "fix" + File.separator + "counters";


    /** This is static final field in order to give the optimiser scope to remove references to it. */
    public static final boolean DEBUG_PRINT_MESSAGES = Boolean.getBoolean(DEBUG_PRINT_MESSAGES_PROPERTY);
    public static final String DEBUG_FILE = System.getProperty(DEBUG_FILE_PROPERTY);

    private SessionIdStrategy sessionIdStrategy = new SenderAndTargetSessionIdStrategy();
    private int counterBuffersLength = getInteger(MONITORING_BUFFERS_LENGTH_PROP_NAME, DEFAULT_MONITORING_BUFFER_LENGTH);
    private String monitoringFile = getProperty(MONITORING_FILE_PROP_NAME, DEFAULT_MONITORING_FILE);
    private String aeronChannel;

    public CommonConfiguration sessionIdStrategy(final SessionIdStrategy sessionIdStrategy)
    {
        this.sessionIdStrategy = sessionIdStrategy;
        return this;
    }

    public CommonConfiguration counterBuffersLength(final Integer counterBuffersLength)
    {
        this.counterBuffersLength = counterBuffersLength;
        return this;
    }

    public CommonConfiguration monitoringFile(String counterBuffersFile)
    {
        this.monitoringFile = counterBuffersFile;
        return this;
    }

    public CommonConfiguration aeronChannel(final String aeronChannel)
    {
        this.aeronChannel = aeronChannel;
        return this;
    }

    public SessionIdStrategy sessionIdStrategy()
    {
        return sessionIdStrategy;
    }

    public int counterBuffersLength()
    {
        return counterBuffersLength;
    }

    public String monitoringFile()
    {
        return monitoringFile;
    }

    public String aeronChannel()
    {
        return aeronChannel;
    }

}

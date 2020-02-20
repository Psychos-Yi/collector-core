/* Copyright 2019 Norconex Inc.
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
 */
package com.norconex.collector.core.cmdline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.norconex.collector.core.Collector;
import com.norconex.commons.lang.config.ConfigurationLoader;
import com.norconex.commons.lang.xml.ErrorHandlerCapturer;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Base class for subcommands.
 * @author Pascal Essiembre
 * @since 2.0.0
 */
public abstract class AbstractSubCommand implements Runnable {

    @ParentCommand
    private CollectorCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(
        names = {"-c", "-config"},
        paramLabel = "FILE",
        description = "Path to Collector configuration file.",
        required = true
    )
    private Path configFile;
    @Option(
        names = {"-variables"},
        paramLabel = "FILE",
        description = "Path to variables file."
    )
    private Path variablesFile;
    @Option(
        names = {"-crawlers"},
        paramLabel = "<crawler>",
        description = "TO IMPLEMENT: Restrict the command to one or more "
                + "crawler (comma-separated).",
        split = ","
    )
    private final List<String> crawlers = new ArrayList<>();


    protected void printOut() {
        commandLine().getOut().println();
    }
    protected void printOut(String str) {
        commandLine().getOut().println(str);
    }
    protected void printErr() {
        commandLine().getErr().println();
    }
    protected void printErr(String str) {
        commandLine().getErr().println(str);
    }
    protected CommandLine commandLine() {
        return spec.commandLine();
    }
    protected Collector getCollector() {
        return parent.getCollector();
    }

    public Path getConfigFile() {
        return configFile;
    }
    public void setConfigFile(Path configFile) {
        this.configFile = configFile;
    }
    public Path getVariablesFile() {
        return variablesFile;
    }
    public void setVariablesFile(Path variablesFile) {
        this.variablesFile = variablesFile;
    }
    public List<String> getCrawlers() {
        return crawlers;
    }

    protected void /*List<XMLValidationError>*/ loadConfig() {
        ErrorHandlerCapturer eh = new ErrorHandlerCapturer(getClass());
//        try {
        new ConfigurationLoader()
//                        .setIgnoreValidationErrors(false)
                .setVariablesFile(getVariablesFile())
                .loadFromXML(getConfigFile(),
                        getCollector().getCollectorConfig(), eh);
        if (!eh.getErrors().isEmpty()) {
            printErr();
            printErr(eh.getErrors().size()
                    + " XML configuration errors detected:");
            printErr();
            eh.getErrors().stream().forEach(er ->
                    printErr(er.getMessage()));
            System.exit(0);
        }
//        getCollector().getCollectorConfig().loadFromXML(
//                new ConfigurationLoader()
//                    .setIgnoreValidationErrors(false)
//                    .setVariablesFile(getVariablesFile())
//                    .loadXML(getConfigFile()));
//        } catch (Exception e) {
//            System.out.println("REALLY?");
//        }
//
//        try {
//            loadConfig(collector, cmd);
//            cmdLine.getOut().println();
//            cmdLine.getOut().println(
//                    " No XML configuration errors detected.");
//        } catch (CollectorException e) {
//            Throwable t = ExceptionUtils.getRootCause(e);
//            if (t instanceof XMLValidationException) {
//                List<XMLValidationError> errors =
//                        ((XMLValidationException) t).getErrors();
//                cmdLine.getOut().println();
//                cmdLine.getOut().println(errors.size()
//                        + " XML configuration errors detected:");
//                cmdLine.getOut().println();
//                errors.stream().forEach(er ->
//                        cmdLine.getOut().println("  " + er.getMessage()));
//            } else {
//                throw e;
//            }
//        }
//        System.exit(0);
//




//        List<XMLValidationError> errors = new ConfigurationLoader()
//                .setVariablesFile(getVariablesFile())
//                .loadXML(getConfigFile())
//                .populate(getCollector().getCollectorConfig());
//        if (!errors.isEmpty()) {
//            printErr();
//            printErr(errors.size() + " XML configuration errors detected:");
//            printErr();
//            errors.stream().forEach(er -> printErr("  " + er.getMessage()));
//            System.exit(-1);
//        }
//        try {
//            errors = new ConfigurationLoader()
//                    .setVariablesFile(getVariablesFile())
//                    .loadXML(getConfigFile())
//                    .populate(getCollector().getCollectorConfig());
//        } catch (InstantiationException | IllegalAccessException e) {
//            //TODO use logging instead?
//            System.err.println("A problem occured loading configuration.");
//            e.printStackTrace(System.err);
//            System.exit(-1);
//        }
//
//        getCollector().getCollectorConfig().loadFromXML(
//                new ConfigurationLoader()
//                    .setVariablesFile(getVariablesFile())
//                    .loadXML(getConfigFile()));
    }

    @Override
    public void run() {
        loadConfig();
        runCommand();
    }

    protected abstract void runCommand();

    @Override
    public boolean equals(final Object other) {
        return EqualsBuilder.reflectionEquals(this, other);
    }
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
    @Override
    public String toString() {
        return new ReflectionToStringBuilder(
                this, ToStringStyle.SHORT_PREFIX_STYLE).toString();
    }
}
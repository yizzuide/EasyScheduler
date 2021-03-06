/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.escheduler.server.utils;

import cn.escheduler.common.Constants;
import cn.escheduler.common.utils.CommonUtils;
import cn.escheduler.dao.model.TaskInstance;
import cn.escheduler.server.rpc.LogClient;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *  mainly used to get the start command line of a process
 */
public class ProcessUtils {
  /**
   * logger
   */
  private final static Logger logger = LoggerFactory.getLogger(ProcessUtils.class);

  /**
   *  build command line characters
   * @return
   */
  public static String buildCommandStr(List<String> commandList) throws IOException {
    String cmdstr;
    String[] cmd = commandList.toArray(new String[commandList.size()]);
    SecurityManager security = System.getSecurityManager();
    boolean allowAmbiguousCommands = false;
    if (security == null) {
      allowAmbiguousCommands = true;
      String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands");
      if (value != null) {
          allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
      }
    }
    if (allowAmbiguousCommands) {

      String executablePath = new File(cmd[0]).getPath();

      if (needsEscaping(VERIFICATION_LEGACY, executablePath)) {
          executablePath = quoteString(executablePath);
      }

      cmdstr = createCommandLine(
              VERIFICATION_LEGACY, executablePath, cmd);
    } else {
      String executablePath;
      try {
        executablePath = getExecutablePath(cmd[0]);
      } catch (IllegalArgumentException e) {

        StringBuilder join = new StringBuilder();
        for (String s : cmd) {
            join.append(s).append(' ');
        }

        cmd = getTokensFromCommand(join.toString());
        executablePath = getExecutablePath(cmd[0]);

        // Check new executable name once more
        if (security != null) {
            security.checkExec(executablePath);
        }
      }


      cmdstr = createCommandLine(

              isShellFile(executablePath) ? VERIFICATION_CMD_BAT : VERIFICATION_WIN32, quoteString(executablePath), cmd);
    }
    return cmdstr;
  }

  private static String getExecutablePath(String path) throws IOException {
    boolean pathIsQuoted = isQuoted(true, path, "Executable name has embedded quote, split the arguments");

    File fileToRun = new File(pathIsQuoted ? path.substring(1, path.length() - 1) : path);
    return fileToRun.getPath();
  }

  private static boolean isShellFile(String executablePath) {
    String upPath = executablePath.toUpperCase();
    return (upPath.endsWith(".CMD") || upPath.endsWith(".BAT"));
  }

  private static String quoteString(String arg) {
    StringBuilder argbuf = new StringBuilder(arg.length() + 2);
    return argbuf.append('"').append(arg).append('"').toString();
  }


  private static String[] getTokensFromCommand(String command) {
    ArrayList<String> matchList = new ArrayList<>(8);
    Matcher regexMatcher = LazyPattern.PATTERN.matcher(command);
    while (regexMatcher.find()) {
        matchList.add(regexMatcher.group());
    }
    return matchList.toArray(new String[matchList.size()]);
  }

  private static class LazyPattern {
    // Escape-support version:
    // "(\")((?:\\\\\\1|.)+?)\\1|([^\\s\"]+)";
    private static final Pattern PATTERN = Pattern.compile("[^\\s\"]+|\"[^\"]*\"");
  }

    private static final int VERIFICATION_CMD_BAT = 0;

  private static final int VERIFICATION_WIN32 = 1;

  private static final int VERIFICATION_LEGACY = 2;

  private static final char[][] ESCAPE_VERIFICATION = {{' ', '\t', '<', '>', '&', '|', '^'},

          {' ', '\t', '<', '>'}, {' ', '\t'}};

  private static String createCommandLine(int verificationType, final String executablePath, final String[] cmd) {
    StringBuilder cmdbuf = new StringBuilder(80);

    cmdbuf.append(executablePath);

    for (int i = 1; i < cmd.length; ++i) {
      cmdbuf.append(' ');
      String s = cmd[i];
      if (needsEscaping(verificationType, s)) {
        cmdbuf.append('"').append(s);

        if ((verificationType != VERIFICATION_CMD_BAT) && s.endsWith("\\")) {
          cmdbuf.append('\\');
        }
        cmdbuf.append('"');
      } else {
        cmdbuf.append(s);
      }
    }
    return cmdbuf.toString();
  }

  private static boolean isQuoted(boolean noQuotesInside, String arg, String errorMessage) {
    int lastPos = arg.length() - 1;
    if (lastPos >= 1 && arg.charAt(0) == '"' && arg.charAt(lastPos) == '"') {
      // The argument has already been quoted.
      if (noQuotesInside) {
        if (arg.indexOf('"', 1) != lastPos) {
          // There is ["] inside.
          throw new IllegalArgumentException(errorMessage);
        }
      }
      return true;
    }
    if (noQuotesInside) {
      if (arg.indexOf('"') >= 0) {
        // There is ["] inside.
        throw new IllegalArgumentException(errorMessage);
      }
    }
    return false;
  }

  private static boolean needsEscaping(int verificationType, String arg) {

    boolean argIsQuoted = isQuoted((verificationType == VERIFICATION_CMD_BAT), arg, "Argument has embedded quote, use the explicit CMD.EXE call.");

    if (!argIsQuoted) {
      char[] testEscape = ESCAPE_VERIFICATION[verificationType];
      for (int i = 0; i < testEscape.length; ++i) {
        if (arg.indexOf(testEscape[i]) >= 0) {
          return true;
        }
      }
    }
    return false;
  }


  /**
   *  kill yarn application
   * @param appIds
   * @param logger
   * @param tenantCode
   * @throws IOException
   */
  public static void cancelApplication(List<String> appIds, Logger logger, String tenantCode,String workDir)
          throws IOException {
    if (appIds.size() > 0) {
      String appid = appIds.get(appIds.size() - 1);
      String commandFile = String
              .format("%s/%s.kill", workDir, appid);
      String cmd = "yarn application -kill " + appid;
      try {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("BASEDIR=$(cd `dirname $0`; pwd)\n");
        sb.append("cd $BASEDIR\n");
        if (CommonUtils.getSystemEnvPath() != null) {
          sb.append("source " + CommonUtils.getSystemEnvPath() + "\n");
        }
        sb.append("\n\n");
        sb.append(cmd);

        File f = new File(commandFile);

        if (!f.exists()) {
          FileUtils.writeStringToFile(new File(commandFile), sb.toString(), Charset.forName("UTF-8"));
        }

        String runCmd = "sh " + commandFile;
        if (StringUtils.isNotEmpty(tenantCode)) {
          runCmd = "sudo -u " + tenantCode + " " + runCmd;
        }

        logger.info("kill cmd:{}", runCmd);

        Runtime.getRuntime().exec(runCmd);
      } catch (Exception e) {
        logger.error("kill application failed : " + e.getMessage(), e);
      }
    }
  }

  /**
   *  kill tasks according to different task types
   * @param taskInstance
   */
  public static void kill(TaskInstance taskInstance) {
    try {
      int processId = taskInstance.getPid();
      if(processId == 0 ){
          logger.error("process kill failed, process id :{}, task id:{}",
                  processId, taskInstance.getId());
          return ;
      }

      String cmd = String.format("sudo kill -9 %d", processId);

      logger.info("process id:{}, cmd:{}", processId, cmd);

      Runtime.getRuntime().exec(cmd);

      // find log and kill yarn job
      killYarnJob(taskInstance);

    } catch (Exception e) {
      logger.error("kill failed : " + e.getMessage(), e);
    }
  }

  /**
   * find logs and kill yarn tasks
   * @param taskInstance
   * @throws IOException
   */
  public static void killYarnJob(TaskInstance taskInstance) throws Exception {
    try {
      Thread.sleep(Constants.SLEEP_TIME_MILLIS);
      LogClient logClient = new LogClient(taskInstance.getHost(), Constants.RPC_PORT);

      String log = logClient.viewLog(taskInstance.getLogPath());
      if (StringUtils.isNotEmpty(log)) {
        List<String> appIds = LoggerUtils.getAppIds(log, logger);
        String workerDir = taskInstance.getExecutePath();
        if (StringUtils.isEmpty(workerDir)) {
          logger.error("task instance work dir is empty");
          throw new RuntimeException("task instance work dir is empty");
        }
        if (appIds.size() > 0) {
          cancelApplication(appIds, logger, taskInstance.getProcessInstance().getTenantCode(), taskInstance.getExecutePath());
        }
      }

    } catch (Exception e) {
      logger.error("kill yarn job failed : " + e.getMessage(),e);
      throw new RuntimeException("kill yarn job fail");
    }
  }
}

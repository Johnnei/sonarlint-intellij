/**
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.core;

import com.intellij.history.utils.RunnableAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.sonarlint.intellij.config.global.SonarQubeServer;
import org.sonarlint.intellij.util.GlobalLogOutput;
import org.sonarlint.intellij.util.SonarLintUtils;
import org.sonarlint.intellij.util.TaskProgressMonitor;
import org.sonarsource.sonarlint.core.client.api.exceptions.CanceledException;
import org.sonarsource.sonarlint.core.client.api.common.LogOutput;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.ServerConfiguration;

public class ServerUpdateTask {
  private static final Logger LOGGER = Logger.getInstance(ServerUpdateTask.class);
  private final ConnectedSonarLintEngine engine;
  private final SonarQubeServer server;
  private final Set<String> projectKeys;
  private final boolean onlyModules;
  private final GlobalLogOutput log;

  public ServerUpdateTask(ConnectedSonarLintEngine engine, SonarQubeServer server, Set<String> projectKeys, boolean onlyModules) {
    this.engine = engine;
    this.server = server;
    this.projectKeys = projectKeys;
    this.onlyModules = onlyModules;
    this.log = GlobalLogOutput.get();
  }

  public Task.Modal asModal() {
    return new Task.Modal(null, "Updating SonarQube server '" + server.getName() + "'", true) {
      @Override public void run(@NotNull ProgressIndicator indicator) {
        ServerUpdateTask.this.run(indicator);
      }
    };
  }

  public Task.Backgroundable asBackground() {
    return new Task.Backgroundable(null, "Updating SonarQube server '" + server.getName() + "'", true, PerformInBackgroundOption.ALWAYS_BACKGROUND) {
      @Override public void run(@NotNull ProgressIndicator indicator) {
        ServerUpdateTask.this.run(indicator);
      }
    };
  }

  public void run(@NotNull ProgressIndicator indicator) {
    indicator.setText("Fetching data...");

    try {
      TaskProgressMonitor monitor = new TaskProgressMonitor(indicator);
      ServerConfiguration serverConfiguration = SonarLintUtils.getServerConfiguration(server);

      if (!onlyModules) {
        engine.update(serverConfiguration, monitor);
        log.log("Server binding '" + server.getName() + "' updated", LogOutput.Level.INFO);
      }

      updateModules(serverConfiguration);

    } catch (CanceledException e) {
      LOGGER.info("Update of server '" + server.getName() + "' was cancelled");
      log.log("Update of server '" + server.getName() + "' was cancelled", LogOutput.Level.INFO);
    } catch (final Exception e) {
      LOGGER.info("Error updating from server '" + server.getName() + "'", e);
      final String msg = (e.getMessage() != null) ? e.getMessage() : ("Failed to update binding for server configuration '" + server.getName() + "'");
      ApplicationManager.getApplication().invokeAndWait(new RunnableAdapter() {
        @Override public void doRun() throws Exception {
          Messages.showErrorDialog((Project) null, msg, "Update Failed");
        }
      }, ModalityState.any());
    }
  }

  private void updateModules(ServerConfiguration serverConfiguration) {
    //here we assume that server is updated, or this won't work
    final Set<String> existingProjectKeys = engine.allModulesByKey().keySet();
    final Set<String> invalidModules = new HashSet<>();

    for (String key : projectKeys) {
      if (existingProjectKeys.contains(key)) {
        updateModule(serverConfiguration, key);
      } else {
        invalidModules.add(key);
      }
    }

    if (!projectKeys.isEmpty() && !invalidModules.isEmpty()) {
      log.log("The following modules could not be updated because they don't exist in the SonarQube server: " + invalidModules.toString(), LogOutput.Level.WARN);

      ApplicationManager.getApplication().invokeLater(new RunnableAdapter() {
        @Override public void doRun() throws Exception {
          Messages.showWarningDialog((Project) null,
            "The following modules could not be updated because they don't exist in the SonarQube server: " + invalidModules.toString(), "Modules Not Updated");
        }
      }, ModalityState.any());
    }
  }

  private void updateModule(ServerConfiguration serverConfiguration, String key) {
    try {
      engine.updateModule(serverConfiguration, key);
      log.log("Module '" + key + "' in server binding '" + server.getName() + "' updated", LogOutput.Level.INFO);
    } catch (Exception e) {
      // in case of error, show a message box and keep updating other modules
      final String msg = (e.getMessage() != null) ? e.getMessage() : ("Failed to update binding for server configuration '" + server.getName() + "'");
      ApplicationManager.getApplication().invokeLater(new RunnableAdapter() {
        @Override public void doRun() throws Exception {
          Messages.showErrorDialog((Project) null, msg, "Update Failed");
        }
      }, ModalityState.any());
    }
  }

}

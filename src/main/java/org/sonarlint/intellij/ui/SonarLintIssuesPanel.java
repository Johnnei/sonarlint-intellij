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
package org.sonarlint.intellij.ui;

import com.intellij.ide.OccurenceNavigator;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.messages.MessageBusConnection;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collection;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.sonarlint.intellij.core.SonarLintServerManager;
import org.sonarlint.intellij.issue.IssuePointer;
import org.sonarlint.intellij.issue.IssueStore;
import org.sonarlint.intellij.messages.IssueStoreListener;
import org.sonarlint.intellij.messages.StatusListener;
import org.sonarlint.intellij.ui.nodes.AbstractNode;
import org.sonarlint.intellij.ui.nodes.IssueNode;
import org.sonarlint.intellij.ui.scope.CurrentFileScope;
import org.sonarlint.intellij.ui.scope.IssueTreeScope;
import org.sonarlint.intellij.ui.scope.OpenedFilesScope;
import org.sonarlint.intellij.ui.tree.IssueTree;
import org.sonarlint.intellij.ui.tree.TreeModelBuilder;
import org.sonarlint.intellij.util.SonarLintUtils;

public class SonarLintIssuesPanel extends SimpleToolWindowPanel implements OccurenceNavigator, DataProvider {
  private static final String ID = "SonarLint";
  private static final String GROUP_ID = "SonarLint.issuestoolwindow";
  private static final String SELECTED_SCOPE_KEY = "SONARLINT_ISSUES_VIEW_SCOPE";
  private static final String SPLIT_PROPORTION = "SONARLINT_ISSUES_SPLIT_PROPORTION";

  private final Project project;
  private final IssueStore issueStore;
  private Tree tree;
  private ActionToolbar mainToolbar;
  private IssueTreeScope scope;
  private TreeModelBuilder treeBuilder;
  private SonarLintRulePanel rulePanel;

  public SonarLintIssuesPanel(Project project) {
    super(false, true);
    this.project = project;
    this.issueStore = project.getComponent(IssueStore.class);
    SonarLintServerManager manager = SonarLintUtils.get(SonarLintServerManager.class);

    addToolbar();

    JPanel issuesPanel = new JPanel(new BorderLayout());
    createTree();
    issuesPanel.add(createScopePanel(), BorderLayout.NORTH);
    issuesPanel.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER);

    rulePanel = new SonarLintRulePanel(project, manager);

    JScrollPane scrollableRulePanel = ScrollPaneFactory.createScrollPane(
      rulePanel.getPanel(),
      ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scrollableRulePanel.getVerticalScrollBar().setUnitIncrement(10);

    super.setContent(createSplitter(issuesPanel, scrollableRulePanel));

    MessageBusConnection busConnection = project.getMessageBus().connect(project);
    busConnection.subscribe(IssueStoreListener.SONARLINT_ISSUE_STORE_TOPIC, new IssueStoreListener() {

      @Override public void filesChanged(final Map<VirtualFile, Collection<IssuePointer>> map) {
        ApplicationManager.getApplication().invokeLater(() -> {
          treeBuilder.updateFiles(map);
          expandTree();
        });
      }

      @Override public void allChanged() {
        ApplicationManager.getApplication().invokeLater(SonarLintIssuesPanel.this::updateTree);
      }
    });
    busConnection.subscribe(StatusListener.SONARLINT_STATUS_TOPIC, newStatus -> ApplicationManager.getApplication().invokeLater(mainToolbar::updateActionsImmediately));
    updateTree();
  }

  private JComponent createSplitter(JComponent c1, JComponent c2) {
    float savedProportion = PropertiesComponent.getInstance(project).getFloat(SPLIT_PROPORTION, 0.65f);

    final Splitter splitter = new Splitter(false);
    splitter.setFirstComponent(c1);
    splitter.setSecondComponent(c2);
    splitter.setProportion(savedProportion);
    splitter.setHonorComponentsMinimumSize(true);
    splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION,
      evt -> PropertiesComponent.getInstance(project).setValue(SPLIT_PROPORTION, Float.toString(splitter.getProportion())));

    return splitter;
  }

  private void switchScope(IssueTreeScope newScope) {
    if (scope != null) {
      scope.removeListeners();
    }

    scope = newScope;
    scope.addListener(this::updateTree);
  }

  private JComponent createScopePanel() {
    DefaultComboBoxModel comboModel = new DefaultComboBoxModel();
    comboModel.addElement(new CurrentFileScope(project));
    comboModel.addElement(new OpenedFilesScope(project));

    // set selected element that was last saved, if any
    String savedSelectedScope = PropertiesComponent.getInstance(project).getValue(SELECTED_SCOPE_KEY);
    if (savedSelectedScope != null) {
      for (int i = 0; i < comboModel.getSize(); i++) {
        Object el = comboModel.getElementAt(i);
        if (el.toString().equals(savedSelectedScope)) {
          comboModel.setSelectedItem(el);
          break;
        }
      }
    }

    final ComboBox scopeComboBox = new ComboBox(comboModel);
    scopeComboBox.addActionListener(evt -> {
      switchScope((IssueTreeScope) scopeComboBox.getSelectedItem());
      updateTree();
      PropertiesComponent.getInstance(project).setValue(SELECTED_SCOPE_KEY, scopeComboBox.getSelectedItem().toString());
    });
    switchScope((IssueTreeScope) scopeComboBox.getSelectedItem());
    JPanel scopePanel = new JPanel(new GridBagLayout());
    final JLabel scopesLabel = new JLabel("Scope:");
    scopesLabel.setDisplayedMnemonic('S');
    scopesLabel.setLabelFor(scopeComboBox);
    final GridBagConstraints gc =
      new GridBagConstraints(GridBagConstraints.RELATIVE, 0, 1, 1, 0, 0, GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(2, 2, 2, 2), 0, 0);
    scopePanel.add(scopesLabel, gc);
    scopePanel.add(scopeComboBox, gc);

    gc.fill = GridBagConstraints.HORIZONTAL;
    gc.weightx = 1;
    scopePanel.add(Box.createHorizontalBox(), gc);

    return scopePanel;
  }

  private void addToolbar() {
    ActionGroup mainActionGroup = (ActionGroup) ActionManager.getInstance().getAction(GROUP_ID);
    mainToolbar = ActionManager.getInstance().createActionToolbar(ID, mainActionGroup, false);
    mainToolbar.setTargetComponent(this);
    Box toolBarBox = Box.createHorizontalBox();
    toolBarBox.add(mainToolbar.getComponent());

    super.setToolbar(toolBarBox);
    mainToolbar.getComponent().setVisible(true);
  }

  public void updateTree() {
    treeBuilder.updateModel(issueStore.getAll(), scope.getCondition());
    expandTree();
  }

  private void expandTree() {
    if (tree.getRowCount() < 30) {
      TreeUtil.expandAll(tree);
    } else {
      tree.expandRow(0);
      if (tree.getRowCount() > 1) {
        tree.expandRow(1);
      }
    }
  }

  private void issueTreeSelectionChanged() {
    IssueNode[] selectedNodes = tree.getSelectedNodes(IssueNode.class, null);
    if (selectedNodes.length > 0) {
      rulePanel.setRuleKey(selectedNodes[0].issue().issue());
    } else {
      rulePanel.setRuleKey(null);
    }
  }

  private void createTree() {
    treeBuilder = new TreeModelBuilder();
    DefaultTreeModel model = treeBuilder.createModel();
    tree = new IssueTree(project, model);
    tree.addTreeSelectionListener(e -> issueTreeSelectionChanged());
  }

  @Nullable
  @Override
  public Object getData(@NonNls String dataId) {
    if (IssueTreeScope.SCOPE_DATA_KEY.is(dataId)) {
      return scope;
    }

    return null;
  }

  @CheckForNull
  private OccurenceInfo occurrence(@Nullable IssueNode node) {
    if (node == null) {
      return null;
    }

    TreePath path = new TreePath(node.getPath());
    tree.getSelectionModel().setSelectionPath(path);
    tree.scrollPathToVisible(path);

    RangeMarker range = node.issue().range();
    int startOffset = (range != null) ? range.getStartOffset() : 0;
    return new OccurenceInfo(
      new OpenFileDescriptor(project, node.issue().psiFile().getVirtualFile(), startOffset),
      -1,
      -1);
  }

  @Override public boolean hasNextOccurence() {
    // relies on the assumption that a TreeNodes will always be the last row in the table view of the tree
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return false;
    }
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    if (node instanceof IssueNode) {
      return tree.getRowCount() != tree.getRowForPath(path) + 1;
    } else {
      return node.getChildCount() > 0;
    }
  }

  @Override public boolean hasPreviousOccurence() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return false;
    }
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    return (node instanceof IssueNode) && !isFirst(node);
  }

  private static boolean isFirst(final TreeNode node) {
    final TreeNode parent = node.getParent();
    return parent == null || (parent.getIndex(node) == 0 && isFirst(parent));
  }

  @CheckForNull
  @Override
  public OccurenceInfo goNextOccurence() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return null;
    }
    return occurrence(treeBuilder.getNextIssue((AbstractNode<?>) path.getLastPathComponent()));
  }

  @CheckForNull
  @Override
  public OccurenceInfo goPreviousOccurence() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return null;
    }
    return occurrence(treeBuilder.getPreviousIssue((AbstractNode<?>) path.getLastPathComponent()));
  }

  @Override public String getNextOccurenceActionName() {
    return "Next Issue";
  }

  @Override public String getPreviousOccurenceActionName() {
    return "Previous Issue";
  }
}

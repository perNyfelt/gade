package se.alipsa.gride.menu;

import static se.alipsa.gride.Constants.*;
import static se.alipsa.gride.console.ConsoleTextArea.CONSOLE_MAX_LENGTH_DEFAULT;
import static se.alipsa.gride.menu.GlobalOptions.*;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import org.jetbrains.annotations.NotNull;
import se.alipsa.gride.Gride;
import se.alipsa.gride.console.ConsoleComponent;
import se.alipsa.gride.model.Repo;
import se.alipsa.gride.utils.ExceptionAlert;
import se.alipsa.gride.utils.GuiUtils;
import se.alipsa.gride.utils.IntField;

import java.util.*;

class GlobalOptionsDialog extends Dialog<GlobalOptions> {

  private TableView<Repo> reposTable;
  private IntField intField;
  private ComboBox<String> themes;
  private ComboBox<String> locals;
  private CheckBox useMavenFileClasspath;
  private TextField mavenHome;
  private CheckBox restartSessionAfterMvnRun;
  private CheckBox addBuildDirToClasspath;
  private CheckBox enableGit;
  private CheckBox autoRunGlobal;
  private CheckBox autoRunProject;
  private CheckBox addImports;


  GlobalOptionsDialog(Gride gui) {
    try {
      setTitle("Global options");
      getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

      GridPane grid = new GridPane();
      grid.setHgap(10);
      grid.setVgap(15);
      grid.setPadding(new Insets(10, 15, 10, 10));
      getDialogPane().setContent(grid);

      Label reposLabel = new Label("Remote Repositories");
      grid.add(reposLabel, 0, 1);

      reposTable = new TableView<>();
      reposTable.setContextMenu(getContextMenu());
      List<Repo> repos = gui.getConsoleComponent().getRemoteRepositories();

      TableColumn<Repo, String> idCol = new TableColumn<>("id");
      idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
      idCol.setCellFactory(TextFieldTableCell.forTableColumn());
      idCol.setOnEditCommit(t ->
          (t.getTableView().getItems().get(t.getTablePosition().getRow()))
              .setId(t.getNewValue())
      );

      TableColumn<Repo, String> typeCol = new TableColumn<>("type");
      typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
      typeCol.setCellFactory(TextFieldTableCell.forTableColumn());
      typeCol.setOnEditCommit(t ->
          (t.getTableView().getItems().get(t.getTablePosition().getRow()))
              .setType(t.getNewValue())
      );

      TableColumn<Repo, String> urlCol = new TableColumn<>("url");
      urlCol.setCellValueFactory(new PropertyValueFactory<>("url"));
      urlCol.setCellFactory(TextFieldTableCell.forTableColumn());
      urlCol.setOnEditCommit(t ->
          (t.getTableView().getItems().get(t.getTablePosition().getRow()))
              .setUrl(t.getNewValue())
      );

      reposTable.setRowFactory(tableView -> {
        final TableRow<Repo> row = new TableRow<>();
        final ContextMenu contextMenu = getContextMenu();
        final MenuItem removeMenuItem = new MenuItem("delete row");
        removeMenuItem.setOnAction(event -> reposTable.getItems().remove(row.getItem()));
        contextMenu.getItems().add(removeMenuItem);

        // Set context menu on row, but use a binding to make it only show for non-empty rows:
        row.contextMenuProperty().bind(
            Bindings.when(row.emptyProperty())
                .then((ContextMenu) null)
                .otherwise(contextMenu)
        );
        return row;
      });


      urlCol.setMinWidth(450);
      reposTable.getColumns().add(idCol);
      reposTable.getColumns().add(typeCol);
      reposTable.getColumns().add(urlCol);


      reposTable.setItems(createObservable(repos));
      reposTable.setEditable(true);

      grid.add(reposTable, 1, 1, 3, 5);

      Label consoleMaxSizeLabel = new Label("Console max size");
      grid.add(consoleMaxSizeLabel, 0, 6);
      intField = new IntField(1000, Integer.MAX_VALUE, gui.getPrefs().getInt(CONSOLE_MAX_LENGTH_PREF, CONSOLE_MAX_LENGTH_DEFAULT));
      grid.add(intField, 1, 6);

      Label styleTheme = new Label("Style theme");
      grid.add(styleTheme, 0, 7);
      themes = new ComboBox<>();
      themes.getItems().addAll(DARK_THEME, BRIGHT_THEME, BLUE_THEME);
      themes.getSelectionModel().select(gui.getPrefs().get(THEME, BRIGHT_THEME));
      grid.add(themes, 1, 7);

      Label defaultLocale = new Label("Default locale");
      grid.add(defaultLocale, 2, 7);

      locals = new ComboBox<>();
      Set<String> languageTags = new TreeSet<>();
      languageTags.add(new Locale("sv", "SE").toLanguageTag());
      for (var loc : Locale.getAvailableLocales()) {
        languageTags.add(loc.toLanguageTag());
      }
      locals.getItems().addAll(languageTags);
      locals.getSelectionModel().select(gui.getPrefs().get(DEFAULT_LOCALE, Locale.getDefault().toLanguageTag()));
      grid.add(locals, 3, 7);

      FlowPane cpPane = new FlowPane();
      grid.add(cpPane, 0,8, 4, 1);

      Label useMavenFileClasspathLabel = new Label("Use pom classpath");
      useMavenFileClasspathLabel.setTooltip(new Tooltip("Use classpath from pom.xml (if available) when running Groovy code"));
      useMavenFileClasspathLabel.setPadding(new Insets(0, 37, 0, 0));
      cpPane.getChildren().add(useMavenFileClasspathLabel);
      useMavenFileClasspath = new CheckBox();
      useMavenFileClasspath.setSelected(gui.getPrefs().getBoolean(USE_MAVEN_CLASSLOADER, false));
      cpPane.getChildren().add(useMavenFileClasspath);

      Label addBuildDirToClasspathLabel = new Label("Add build dir to classpath");
      addBuildDirToClasspathLabel.setPadding(new Insets(0, 27, 0, 70));
      addBuildDirToClasspathLabel.setTooltip(new Tooltip("Add target/classes and target/test-classes to classpath"));
      cpPane.getChildren().add(addBuildDirToClasspathLabel);
      addBuildDirToClasspath = new CheckBox();
      addBuildDirToClasspath.setSelected(gui.getPrefs().getBoolean(ADD_BUILDDIR_TO_CLASSPATH, true));
      cpPane.getChildren().add(addBuildDirToClasspath);

      // When developing packages we need to reload the session after mvn has been run
      // so that new definitions can be picked up from target/classes.
      Label restartSessionAfterMvnRunLabel = new Label("Restart session after mvn build");
      restartSessionAfterMvnRunLabel.setPadding(new Insets(0, 27, 0, 27));
      restartSessionAfterMvnRunLabel.setTooltip(new Tooltip("When developing packages we need to reload the session after mvn has been run\nso that new definitions can be picked up from target/classes"));
      cpPane.getChildren().add(restartSessionAfterMvnRunLabel);
      restartSessionAfterMvnRun = new CheckBox();
      restartSessionAfterMvnRun.setSelected(gui.getPrefs().getBoolean(RESTART_SESSION_AFTER_MVN_RUN, true));
      cpPane.getChildren().add(restartSessionAfterMvnRun);

      Label mavenHomeLabel = new Label("MAVEN_HOME");
      mavenHomeLabel.setTooltip(new Tooltip("The location of your maven installation directory"));
      //mavenHomeLabel.setPadding(new Insets(0, 27, 0, 0));
      grid.add(mavenHomeLabel, 0,9);

      HBox mavenHomePane = new HBox();
      mavenHomePane.setAlignment(Pos.CENTER_LEFT);
      mavenHome = new TextField();
      HBox.setHgrow(mavenHome, Priority.ALWAYS);
      mavenHome.setText(gui.getPrefs().get(MAVEN_HOME, System.getProperty("MAVEN_HOME", System.getenv("MAVEN_HOME"))));
      mavenHomePane.getChildren().add(mavenHome);
      grid.add(mavenHomePane, 1,9,3, 1);

      FlowPane gitOptionPane = new FlowPane();
      Label enableGitLabel = new Label("Enable git integration");
      enableGitLabel.setPadding(new Insets(0, 20, 0, 0));
      enableGitLabel.setTooltip(new Tooltip("note: git must be initialized in the project dir for integration to work"));
      gitOptionPane.getChildren().add(enableGitLabel);
      enableGit = new CheckBox();
      enableGit.setSelected(gui.getPrefs().getBoolean(ENABLE_GIT, true));
      gitOptionPane.getChildren().add(enableGit);
      grid.add(gitOptionPane, 0, 10, 2, 1);

      FlowPane autoRunPane = new FlowPane();
      Label autoRunGlobalLabel = new Label("Run global autorun.groovy on session init");
      autoRunGlobalLabel.setTooltip(new Tooltip("Run autorun.groovy from Gride install dir each time a session (re)starts."));
      autoRunGlobalLabel.setPadding(new Insets(0, 20, 0, 0));
      autoRunGlobal = new CheckBox();
      autoRunGlobal.setSelected(gui.getPrefs().getBoolean(AUTORUN_GLOBAL, false));
      autoRunPane.getChildren().addAll(autoRunGlobalLabel, autoRunGlobal);

      Label autoRunProjectLabel = new Label("Run project autorun.groovy on session init");
      autoRunProjectLabel.setTooltip(new Tooltip("Run autorun.groovy from the project dir (working dir) each time a session (re)starts"));
      autoRunProjectLabel.setPadding(new Insets(0, 20, 0, 20));
      autoRunProject = new CheckBox();
      autoRunProject.setSelected(gui.getPrefs().getBoolean(AUTORUN_PROJECT, false));
      autoRunPane.getChildren().addAll(autoRunProjectLabel, autoRunProject);

      grid.add(autoRunPane, 0,11, 4, 1);

      FlowPane executionPane = new FlowPane();
      Label addImportsLabel = new Label("Add imports when running Groovy snippets");
      addImportsLabel.setPadding(new Insets(0, 20, 0, 0));
      executionPane.getChildren().add(addImportsLabel);
      addImports = new CheckBox();
      addImports.setSelected(gui.getPrefs().getBoolean(ADD_IMPORTS, gui.getPrefs().getBoolean(ADD_IMPORTS, true)));
      executionPane.getChildren().add(addImports);
      grid.add(executionPane, 0, 12,4, 1);

      getDialogPane().setPrefSize(800, 530);
      getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
      setResizable(true);

      GuiUtils.addStyle(gui, this);

      setResultConverter(button -> button == ButtonType.OK ? createResult() : null);
    } catch (Throwable t) {
      ExceptionAlert.showAlert(t.getMessage(), t);
    }
  }

  @NotNull
  private ContextMenu getContextMenu() {
    final ContextMenu contextMenu = new ContextMenu();
    final MenuItem addMenuItem = new MenuItem("add row");
    addMenuItem.setOnAction(event -> addRepositoryRow(new Repo()));

    final Menu addDefault = new Menu("add default");
    final MenuItem addMavenCentral = new MenuItem("Maven Central");
    addMavenCentral.setOnAction(this::addMvnCentralRepo);
    addDefault.getItems().addAll(addMavenCentral);
    contextMenu.getItems().addAll(addMenuItem, addDefault);
    return contextMenu;
  }

  private void addRepositoryRow(Repo repo) {
    reposTable.getItems().add(repo);
  }

  private void addMvnCentralRepo(ActionEvent actionEvent) {
    addRepositoryRow(ConsoleComponent.MVN_CENTRAL_REPO);
  }

  private ObservableList<Repo> createObservable(List<Repo> repos) {
    if (repos == null) {
      return FXCollections.emptyObservableList();
    }
    return FXCollections.observableArrayList(repos);
  }

  private GlobalOptions createResult() {
    GlobalOptions result = new GlobalOptions();
    result.put(REMOTE_REPOSITORIES, reposTable.getItems());
    result.put(CONSOLE_MAX_LENGTH_PREF, intField.getValue());
    result.put(THEME, themes.getValue());
    result.put(DEFAULT_LOCALE, locals.getValue());
    result.put(USE_MAVEN_CLASSLOADER, useMavenFileClasspath.isSelected());
    result.put(ADD_BUILDDIR_TO_CLASSPATH, addBuildDirToClasspath.isSelected());
    result.put(RESTART_SESSION_AFTER_MVN_RUN, restartSessionAfterMvnRun.isSelected());
    result.put(ENABLE_GIT, enableGit.isSelected());
    result.put(AUTORUN_GLOBAL, autoRunGlobal.isSelected());
    result.put(AUTORUN_PROJECT, autoRunProject.isSelected());
    result.put(ADD_IMPORTS, addImports.isSelected());
    return result;
  }


}

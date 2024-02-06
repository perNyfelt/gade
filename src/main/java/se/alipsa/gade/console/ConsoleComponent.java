package se.alipsa.gade.console;

import static se.alipsa.gade.Constants.*;
import static se.alipsa.gade.menu.GlobalOptions.*;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.jetbrains.annotations.Nullable;
import se.alipsa.gade.Constants;
import se.alipsa.gade.Gade;
import se.alipsa.gade.TaskListener;
import se.alipsa.gade.environment.EnvironmentComponent;
import se.alipsa.gade.utils.Alerts;
import se.alipsa.gade.utils.ExceptionAlert;
import se.alipsa.gade.utils.FileUtils;
import se.alipsa.gade.utils.gradle.GradleUtils;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class ConsoleComponent extends BorderPane {

  private static final Image IMG_RUNNING = new Image(Objects.requireNonNull(FileUtils
      .getResourceUrl("image/running.png")).toExternalForm(), ICON_WIDTH, ICON_HEIGHT, true, true);
  private static final Image IMG_WAITING = new Image(Objects.requireNonNull(FileUtils
      .getResourceUrl("image/waiting.png")).toExternalForm(), ICON_WIDTH, ICON_HEIGHT, true, true);
  private static final String DOUBLE_INDENT = INDENT + INDENT;
  private static final Logger log = LogManager.getLogger(ConsoleComponent.class);
  private final ImageView runningView;
  private final Button statusButton;
  private final ConsoleTextArea console;
  private final Gade gui;
  private GroovyClassLoader classLoader;

  private Thread runningThread;
  private final Map<Thread, String> threadMap = new HashMap<>();
  private ScriptEngine engine;

  public ConsoleComponent(Gade gui) {
    this.gui = gui;
    console = new ConsoleTextArea(gui);
    console.setEditable(false);

    Button clearButton = new Button("Clear");
    clearButton.setOnAction(e -> {
      console.clear();
      console.appendText(">");
    });
    FlowPane topPane = new FlowPane();
    topPane.setPadding(new Insets(1, 10, 1, 5));
    topPane.setHgap(10);

    runningView = new ImageView();
    statusButton = new Button();
    statusButton.setOnAction(e -> interruptProcess());
    statusButton.setGraphic(runningView);
    waiting();

    topPane.getChildren().addAll(statusButton, clearButton);
    setTop(topPane);

    VirtualizedScrollPane<ConsoleTextArea> vPane = new VirtualizedScrollPane<>(console);
    vPane.setMaxWidth(Double.MAX_VALUE);
    vPane.setMaxHeight(Double.MAX_VALUE);
    setCenter(vPane);
  }



  /* not used to commented out
  public void initGroovy(ClassLoader parentClassLoader, boolean... sync) {
    if (sync.length > 0 && sync[0]) {
      try {
        resetClassloaderAndGroovy(parentClassLoader);
        printVersionInfoToConsole();
        autoRunScripts();
        updateEnvironment();
      } catch (Exception e) {
        ExceptionAlert.showAlert("Failed to reset classloader and Groovy, please report this!", e);
      }
    } else {
      Platform.runLater(() -> initGroovy(parentClassLoader));
    }
  }
   */

  public void initGroovy(ClassLoader parentClassLoader) {
    Task<Void> initTask = new Task<>() {

      @Override
      protected Void call() throws Exception {
        return resetClassloaderAndGroovy(parentClassLoader);
      }
    };
    initTask.setOnSucceeded(e -> {
      printVersionInfoToConsole();
      autoRunScripts();
      updateEnvironment();
    });
    initTask.setOnFailed(e -> {
      Throwable throwable = initTask.getException();
      Throwable ex = throwable.getCause();
      if (ex == null) {
        ex = throwable;
      }
      String msg = createMessageFromEvalException(ex);
      ExceptionAlert.showAlert(msg + ex.getMessage(), ex);
      promptAndScrollToEnd();
    });
    Thread thread = new Thread(initTask);
    thread.setDaemon(false);
    thread.start();
  }

  private void printVersionInfoToConsole() {
    String greeting = "* Groovy " + GroovySystem.getVersion() + " *";
    String surround = getStars(greeting.length());
    console.appendFx(surround, true);
    console.appendFx(greeting, true);
    console.appendFx(surround + "\n>", false);
  }

  @Nullable
  private Void resetClassloaderAndGroovy(ClassLoader parentClassLoader) throws Exception {
    try {

      if (gui.getInoutComponent() == null) {
        log.warn("InoutComponent is null, timing is off");
        throw new RuntimeException("resetClassloaderAndGroovy called too soon, InoutComponent is null, timing is off");
      }

      //log.info("USE_GRADLE_CLASSLOADER pref is set to {}", gui.getPrefs().getBoolean(USE_GRADLE_CLASSLOADER, false));

      classLoader = new GroovyClassLoader(parentClassLoader);

      boolean useGradleCLassLoader = gui.getPrefs().getBoolean(USE_GRADLE_CLASSLOADER, false);


      if (gui.getInoutComponent() != null && gui.getInoutComponent().getRoot() != null) {
        File wd = gui.getInoutComponent().projectDir();
        if (gui.getPrefs().getBoolean(ADD_BUILDDIR_TO_CLASSPATH, true) && wd != null && wd.exists()) {
          // TODO: we should set this from the resolved build
          File classesDir = new File(wd, "build/classes/groovy/main/");
          List<URL> urlList = new ArrayList<>();
          try {
            if (classesDir.exists()) {
              urlList.add(classesDir.toURI().toURL());
            }
            File testClasses = new File(wd, "build/classes/groovy/test/");
            if (testClasses.exists()) {
              urlList.add(testClasses.toURI().toURL());
            }
            File javaClassesDir = new File(wd, "build/classes/java/main");
            if (javaClassesDir.exists()) {
              urlList.add(javaClassesDir.toURI().toURL());
            }
            File javaTestClassesDir = new File(wd, "build/classes/java/test");
            if (javaTestClassesDir.exists()) {
              urlList.add(javaTestClassesDir.toURI().toURL());
            }
          } catch (MalformedURLException e) {
            log.warn("Failed to find classes dir", e);
          }
          if (!urlList.isEmpty()) {
            log.trace("Adding compile dirs to classloader: {}", urlList);
            urlList.forEach(url -> classLoader.addURL(url));
            //classLoader = new URLClassLoader(urlList.toArray(new URL[0]), classLoader);
          }
        }

        if (useGradleCLassLoader) {
          File projectDir = gui.getInoutComponent().projectDir();
          File gradleHome = new File(gui.getPrefs().get(GRADLE_HOME, GradleUtils.locateGradleHome()));
          File gradleFile = new File(projectDir, "build.gradle");
          if (gradleFile.exists() && gradleHome.exists()) {
            log.debug("Parsing build.gradle to use gradle classloader");
            console.appendFx("* Parsing build.gradle to create Gradle classloader...", true);

            var gradleUtils = new GradleUtils(gui);
            /*
            gradleUtils.getProjectDependencies().forEach(f -> {
              try {
                if (f.exists()) {
                  classLoader.addURL(f.toURI().toURL());
                } else {
                  log.warn("Dependency file {} does not exist", f);
                  console.appendWarningFx("Dependency file " + f + " does not exist");
                }
              } catch (MalformedURLException e) {
                log.warn("Error adding gradle dependency {} to classpath", f);
                console.appendWarningFx("Error adding gradle dependency " + f + " to classpath");
              }
            });

             */
            classLoader = new GroovyClassLoader(gradleUtils.createGradleCLassLoader(classLoader, console));
          } else {
            log.info("Use gradle class loader is set but gradle build file {} does not exist", gradleFile);
          }
        }
      }
      engine = new GroovyScriptEngineImpl(classLoader);
      gui.guiInteractions.forEach((k,v) -> engine.put(k, v));
      return null;
    } catch (RuntimeException e) {
      // RuntimeExceptions (such as EvalExceptions is not caught so need to wrap all in an exception
      // this way we can get to the original one by extracting the cause from the thrown exception
      System.out.println("Exception caught, rethrowing as wrapped Exception");
      throw new Exception(e);
    }
  }


  private void autoRunScripts() {
    File file = null;
    boolean wasWaiting = gui.isWaitCursorSet();
    gui.setWaitCursor();
    try {
      if(gui.getPrefs().getBoolean(AUTORUN_GLOBAL, false)) {
        file = new File(gui.getGadeBaseDir(), Constants.AUTORUN_FILENAME);
        if (file.exists()) {
          runScriptSilent(FileUtils.readContent(file));
        }
      }
      if(gui.getPrefs().getBoolean(AUTORUN_PROJECT, false)) {
        file = new File(gui.getInoutComponent().projectDir(), Constants.AUTORUN_FILENAME);
        if (file.exists()) {
          runScriptSilent(FileUtils.readContent(file));
        }
      }
      if (!wasWaiting) {
        gui.setNormalCursor();
      }
    } catch (Exception e) {
      String path = file == null ? "" : file.getAbsolutePath();
      Platform.runLater(() -> ExceptionAlert.showAlert("Failed to run " + Constants.AUTORUN_FILENAME + " in " + path, e));
    }
  }

  private String getStars(int length) {
    return "*".repeat(Math.max(0, length));
  }

  public void restartGroovy() {
    console.append("Restarting Groovy..\n");
    //initGroovy(getStoredRemoteRepositories(), gui.getClass().getClassLoader());
    initGroovy(gui.dynamicClassLoader);
    gui.getEnvironmentComponent().clearEnvironment();
  }

  /**
   * TODO: while we can stop the timeline with this we cannot interrupt the scriptengines eval.
   */
  @SuppressWarnings("deprecation")
  public void interruptProcess() {
    log.info("Interrupting running process");
    // This is a nasty piece of code but a brutal stop() is the only thing that will break out of the script engine
    if (runningThread != null && runningThread.isAlive()) {
      console.appendFx("\nInterrupting process...", true);
      runningThread.interrupt();
      // allow two seconds for graceful shutdown
      sleep(2000);
      console.appendFx("Stopping process...", true);
      runningThread.stop();
      threadMap.remove(runningThread);
      console.appendText("\n>");
    }
  }

  private void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      log.info("Sleep was interrupted");
    }
  }

  /*
  public Object runScript(String script) throws Exception {
    return runScript(script, null);
  }

   */

  /*
  public void addVariableToSession(String key, Object val) {
    engine.put(key, val);
  }

  public void removeVariableFromSession(String varName) {
    engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(varName);
  }

   */
  public Object runScript(String script, Map<String, Object> additionalParams) throws Exception {
    if (engine == null) {
      Alerts.infoFx("Scriptengine not ready", "Groovy is still starting up, please wait a few seconds");
      return null;
    }
    //log.info("engine is {}, gui is {}", engine, gui);
    gui.guiInteractions.forEach(this::addVariableToSession);
    if (additionalParams != null) {
      for (Map.Entry<String, Object> entry : additionalParams.entrySet()) {
        addVariableToSession(entry.getKey(), entry.getValue());
      }
    }
    return engine.eval(script);
  }



  /*
  public Object runScriptSilent(String script, Map<String, Object> additionalParams) throws Exception {
    for (Map.Entry<String, Object> entry : additionalParams.entrySet()) {
      addVariableToSession(entry.getKey(), entry.getValue());
    }
    return runScriptSilent(script);
  }

   */

  public Object runScriptSilent(String script) throws Exception {
    try (PrintWriter out = new PrintWriter(System.out);
         PrintWriter err = new PrintWriter(System.err)) {
      running();
      log.debug("Running script: {}", script);
      engine.getContext().setWriter(out);
      engine.getContext().setErrorWriter(err);
      var result = engine.eval(script);
      waiting();
      return result;
    } catch (Exception e) {
      log.warn("Failed to run script: {}", script, e);
      waiting();
      throw e;
    }
  }

  public Object fetchVar(String varName) {
    return engine.get(varName);
  }

  /*
  public void runScriptAsync(String script, String title, TaskListener taskListener, Map<String, Object> additionalParams) {
    for (Map.Entry<String, Object> entry : additionalParams.entrySet()) {
      addVariableToSession(entry.getKey(), entry.getValue());
    }
    runScriptAsync(script, title, taskListener);
  }

   */

  public void runScriptAsync(String script, String title, TaskListener taskListener) {

    running();

    Task<Void> task = new Task<>() {
      @Override
      public Void call() throws Exception {
        try {
          taskListener.taskStarted();
          executeScriptAndReport(script, title);
        } catch (RuntimeException e) {
          // RuntimeExceptions (such as EvalExceptions is not caught so need to wrap all in an exception
          // this way we can get to the original one by extracting the cause from the thrown exception
          System.out.println("Exception caught, rethrowing as wrapped Exception");
          throw new Exception(e);
        }
        return null;
      }
    };

    task.setOnSucceeded(e -> {
      taskListener.taskEnded();
      waiting();
      updateEnvironment();
      promptAndScrollToEnd();
    });
    task.setOnFailed(e -> {
      taskListener.taskEnded();
      waiting();
      updateEnvironment();
      Throwable throwable = task.getException();
      Throwable ex = throwable.getCause();
      if (ex == null) {
        ex = throwable;
      }

      String msg = createMessageFromEvalException(ex);
      log.warn("Error running script {}", script);
      ExceptionAlert.showAlert(msg + ex.getMessage(), ex);
      promptAndScrollToEnd();
    });
    Thread thread = new Thread(task);
    thread.setDaemon(false);
    startThreadWhenOthersAreFinished(thread, "runScriptAsync: " + title);
  }

  public String createMessageFromEvalException(Throwable ex) {
    String msg = "";

    if (ex instanceof RuntimeException) {
      msg = "An unknown error occurred running Groovy script: ";
    } else if (ex instanceof IOException) {
      msg = "Failed to close writer capturing groovy results ";
    } else if (ex instanceof RuntimeScriptException) {
      msg = "An unknown error occurred running Groovy script: ";
    } else if (ex instanceof Exception) {
      msg = "An Exception occurred: ";
    }
    return msg;
  }

  public void promptAndScrollToEnd() {
    console.appendText(">");
    scrollToEnd();
  }

  public void scrollToEnd() {
    console.moveTo(console.getLength());
    console.requestFollowCaret();
  }

  public void updateEnvironment() {
    Task<Void> task = new Task<>() {
      @Override
      protected Void call() throws Exception {
        try {
          // TODO get library dependencies from Grab and maven?
          gui.getEnvironmentComponent().setEnvironment(getContextObjects());
        } catch (RuntimeException e) {
          // RuntimeExceptions (such as EvalExceptions is not caught so need to wrap all in an exception
          // this way we can get to the original one by extracting the cause from the thrown exception
          System.out.println("Exception caught, rethrowing as wrapped Exception");
          throw new Exception(e);
        }

        return null;
      }
    };
    task.setOnFailed(e -> {
      Throwable throwable = task.getException();
      Throwable ex = throwable.getCause();
      if (ex == null) {
        ex = throwable;
      }

      String msg = createMessageFromEvalException(ex);

      ExceptionAlert.showAlert(msg + ex.getMessage(), ex);
    });
    Thread thread = new Thread(task);
    thread.setDaemon(false);
    startThreadWhenOthersAreFinished(thread, "updateEnvironment");
  }

  public Map<String, Object> getContextObjects() {
    Map<String, Object> contextObjects = new HashMap<>();
    contextObjects.putAll(engine.getBindings(ScriptContext.ENGINE_SCOPE));
    return contextObjects;
  }

  /*
  private void runTests(GroovyTab rTab) {
    running();
    String script = rTab.getTextContent();
    String title = rTab.getTitle();
    File file = rTab.getFile();
    console.append("", true);
    console.append("Running tests", true);
    console.append("-------------", true);

    if (file == null || !file.exists()) {
      console.append("Unable to determine script location, you must save the R script first.", true);
      return;
    }

    Task<Void> task = new Task<>() {

      long start;
      long end;

      @Override
      public Void call() {
        ((TaskListener) rTab).taskStarted();
        start = System.currentTimeMillis();
        gui.guiInteractions.forEach((k,v) -> addVariableToSession(k, v));
        List<TestResult> results = new ArrayList<>();
        try (StringWriter out = new StringWriter();
             StringWriter err = new StringWriter();
             PrintWriter outputWriter = new PrintWriter(out);
             PrintWriter errWriter = new PrintWriter(err)
        ) {
          engine.getContext().setWriter(outputWriter);
          engine.getContext().setErrorWriter(errWriter);
          //TODO not sure how to handle working dir
          //FileObject orgWd = session.getWorkingDirectory();
          //File scriptDir = file.getParentFile();
          //console.appendFx(DOUBLE_INDENT + "- Setting working directory to " + scriptDir, true);
          //session.setWorkingDirectory(scriptDir);



          TestResult result = runTest(script, title);
          // TODO: working dir
          //console.appendFx(DOUBLE_INDENT + "- Setting working directory back to " + orgWd, true);
          //session.setWorkingDirectory(orgWd);
          results.add(result);
          Platform.runLater(() -> printResult(title, out, err, result, DOUBLE_INDENT));

          end = System.currentTimeMillis();
          Map<TestResult.OutCome, List<TestResult>> resultMap = results.stream()
              .collect(Collectors.groupingBy(TestResult::getResult));

          List<TestResult> successResults = resultMap.get(TestResult.OutCome.SUCCESS);
          List<TestResult> failureResults = resultMap.get(TestResult.OutCome.FAILURE);
          List<TestResult> errorResults = resultMap.get(TestResult.OutCome.ERROR);
          long successCount = successResults == null ? 0 : successResults.size();
          long failCount = failureResults == null ? 0 : failureResults.size();
          long errorCount = errorResults == null ? 0 : errorResults.size();

          String duration = DurationFormatUtils.formatDuration(end - start, "mm 'minutes, 'ss' seconds, 'SSS' millis '");
          console.appendFx("\nR tests summary:", true);
          console.appendFx("----------------", true);
          console.appendFx(format("Tests run: {}, Successes: {}, Failures: {}, Errors: {}",
              results.size(), successCount, failCount, errorCount), true);
          console.appendFx("Time: " + duration + "\n", true);
        } catch (IOException e) {
          console.appendWarningFx("Failed to run test");
          ExceptionAlert.showAlert("Failed to run test", e);
        }
        return null;
      }
    };
    task.setOnSucceeded(e -> {
      ((TaskListener) rTab).taskEnded();
      waiting();
      updateEnvironment();
      promptAndScrollToEnd();
    });
    task.setOnFailed(e -> {
      ((TaskListener) rTab).taskEnded();
      waiting();
      updateEnvironment();
      Throwable throwable = task.getException();
      Throwable ex = throwable.getCause();
      if (ex == null) {
        ex = throwable;
      }

      String msg = createMessageFromEvalException(ex);

      ExceptionAlert.showAlert(msg + ex.getMessage(), ex);
      promptAndScrollToEnd();
    });
    Thread thread = new Thread(task);
    thread.setDaemon(false);
    startThreadWhenOthersAreFinished(thread, "runTests: " + title);
  }

  private void printResult(String title, StringWriter out, StringWriter err, TestResult result, String indent) {
    String lines = prefixLines(out, indent);
    if (!"".equals(lines.trim())) {
      console.append(lines, true);
    }
    out.getBuffer().setLength(0);
    lines = prefixLines(err, indent);
    if (!"".equals(lines.trim())) {
      console.append(lines, true);
    }
    err.getBuffer().setLength(0);
    if (TestResult.OutCome.SUCCESS.equals(result.getResult())) {
      console.append(indent + format("# {}: Success", title), true);
    } else {
      console.appendWarning(indent + format("# {}: Failure detected: {}", title, formatMessage(result.getError())));
    }
  }

   */

  /*
  private String prefixLines(StringWriter out, String prefix) {
    StringBuilder buf = new StringBuilder();
    String lines = out == null ? "" : out.toString();
    for(String line : lines.trim().split("\n")) {
      buf.append(prefix).append(line).append("\n");
    }
    return prefix + buf.toString().trim();
  }

   */

  /*
  private TestResult runTest(String script, String title, String... indentOpt) {
    String indent = INDENT;
    if (indentOpt.length > 0) {
      indent = indentOpt[0];
    }
    TestResult result = new TestResult(title);
    String issue;
    Exception exception;
    console.appendFx(indent + format("# Running test {}", title).trim(), true);
    try {
      engine.eval(script);
      result.setResult(TestResult.OutCome.SUCCESS);
      return result;
    } catch (ScriptException e) {
      exception = e;
      issue = e.getClass().getSimpleName() + " executing test " + title;
    } catch (RuntimeException e) {
      exception = e;
      issue = e.getClass().getSimpleName() + " occurred running Groovy script " + title;
    } catch (Exception e) {
      exception = e;
      issue = e.getClass().getSimpleName() + " thrown when running script " + title;
    }
    result.setResult(TestResult.OutCome.FAILURE);
    result.setError(exception);
    result.setIssue(issue);
    return result;
  }

   */

  /*
  private String formatMessage(final Throwable error) {
    return error.getMessage().trim().replace("\n", ", ");
  }

   */

  private void executeScriptAndReport(String script, String title) throws Exception {
    PrintStream sysOut = System.out;
    PrintStream sysErr = System.err;
    EnvironmentComponent env = gui.getEnvironmentComponent();
    try (
        AppenderWriter out = new AppenderWriter(console, true);
        WarningAppenderWriter err = new WarningAppenderWriter(console);
        PrintWriter outputWriter = new PrintWriter(out);
        PrintWriter errWriter = new PrintWriter(err);
        PrintStream outStream = new PrintStream(WriterOutputStream.builder().setWriter(outputWriter).get());
        PrintStream errStream = new PrintStream(WriterOutputStream.builder().setWriter(errWriter).get());
    ) {
      if (engine == null) {
        Alerts.warnFx("Engine has not started yet", "There seems to be some issue with initialization");
        return;
      }
      gui.guiInteractions.forEach((k,v) -> engine.put(k, v));

      Platform.runLater(() -> {
        console.append(title, true);
        env.addInputHistory(script);
      });

      engine.getContext().setWriter(outputWriter);
      engine.getContext().setErrorWriter(errWriter);
      System.setOut(outStream);
      System.setErr(errStream);
      var result = engine.eval(script);
      // TODO: add config to opt out of printing the result to the console
      if (result != null) {
        gui.getConsoleComponent().getConsole().appendFx(result.toString(), true);
      }
      Platform.runLater(() -> env.addOutputHistory(out.getCachedText()));

    } catch (ScriptException e) {
      throw e;
    } catch (RuntimeException re) {
      throw new Exception(re.getMessage(), re);
    } finally {
      System.setOut(sysOut);
      System.setErr(sysErr);
    }
  }

  public void addOutput(String title, String content, boolean addPrompt, boolean addNewLine) {
    if (title != null && title.length() != 0) {
      console.append(title, true);
    }
    console.append(content, addNewLine);
    if (addPrompt) {
      promptAndScrollToEnd();
    } else {
      scrollToEnd();
    }
  }

  public void addWarning(String title, String content, boolean addPrompt) {
    console.appendWarning(title);
    console.appendWarning(content);
    if (addPrompt) {
      promptAndScrollToEnd();
    } else {
      scrollToEnd();
    }
  }

  public void running() {
    Platform.runLater(() -> {
      runningView.setImage(IMG_RUNNING);
      statusButton.setTooltip(new Tooltip("Process is running, click to abort"));
      showTooltip(statusButton);
      gui.getMainMenu().enableInterruptMenuItem();
    });
    sleep(20);
  }

  public void waiting() {
    Platform.runLater(() -> {
      runningView.setImage(IMG_WAITING);
      statusButton.setTooltip(new Tooltip("Engine is idle"));
      gui.getMainMenu().disableInterruptMenuItem();
    });
  }

  private void showTooltip(Control control) {
    Tooltip customTooltip = control.getTooltip();
    Stage owner = gui.getStage();
    Point2D p = control.localToScene(10.0, 20.0);

    customTooltip.setAutoHide(true);

    customTooltip.show(owner, p.getX()
        + control.getScene().getX() + control.getScene().getWindow().getX(), p.getY()
        + control.getScene().getY() + control.getScene().getWindow().getY());

    Timer timer = new Timer();
    TimerTask task = new TimerTask() {
      public void run() {
        Platform.runLater(customTooltip::hide);
      }
    };
    timer.schedule(task, 800);
  }

  public void setWorkingDir(File dir) {
    if (dir == null) {
      return;
    }
    // TODO: not sure how to do this
    /*
    try {
      if (session != null) {
        session.setWorkingDirectory(dir);
      }
      workingDir = dir;
    } catch (FileSystemException e) {
      log.warn("Error setting working dir to {} for session", dir, e);
    }

     */
  }

  public ScriptEngine getSession() {
    return engine;
  }

  public void setConsoleMaxSize(int size) {
    console.setConsoleMaxSize(size);
  }

  public int getConsoleMaxSize() {
    return console.getConsoleMaxSize();
  }

  public ConsoleTextArea getConsole() {
    return console;
  }

  public void startThreadWhenOthersAreFinished(Thread thread, String context) {
    if (runningThread == null) {
      log.debug("Starting thread {}", context);
      thread.start();
    } else if (runningThread.getState() == Thread.State.WAITING || runningThread.getState() == Thread.State.TIMED_WAITING) {
      log.debug("Waiting for thread {} to finish", threadMap.get(runningThread));
      try {
        // This is bit ugly as now the console output will not show until the thread has finished.
        runningThread.join();
        thread.start();
      } catch (InterruptedException e) {
        log.warn("Thread was interrupted", e);
        log.info("Running thread {}", context);
        thread.start();
      }

    } else if (runningThread.isAlive() && runningThread.getState() != Thread.State.TERMINATED) {
      log.warn("There is already a process running: {} in state {}, Overriding existing running thread", threadMap.get(runningThread), runningThread.getState());
      thread.start();
    } else {
      if (runningThread.getState() != Thread.State.TERMINATED) {
        log.error("Missed some condition, running thread {} is {}", threadMap.get(runningThread), runningThread.getState());
      }
      thread.start();
    }
    threadMap.remove(runningThread);
    runningThread = thread;
    threadMap.put(thread, context);
  }

  public void busy() {
    this.setCursor(Cursor.WAIT);
    console.setCursor(Cursor.WAIT);
  }

  public void ready() {
    this.setCursor(Cursor.DEFAULT);
    console.setCursor(Cursor.DEFAULT);
  }

  public GroovyClassLoader getGroovyClassLoader() {
    return classLoader;
    //return engine.getContext().getClass().getClassLoader();
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  public OutputStream getOutputStream() {
    return new ConsoleOutputStream(this);
  }

  public void addVariableToSession(String key, Object value) {
    engine.put(key, value);
  }

  public void removeVariableFromSession(String varName) {
    engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(varName);
  }
}

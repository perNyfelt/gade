package se.alipsa.gride.code.groovytab;

import javafx.scene.control.ContextMenu;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import se.alipsa.gride.Gride;
import se.alipsa.gride.code.CodeComponent;
import se.alipsa.gride.code.CodeTextArea;
import se.alipsa.gride.environment.ContextFunctionsUpdateListener;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GroovyTextArea extends CodeTextArea {

  TreeSet<String> contextObjects = new TreeSet<>();

  ContextMenu suggestionsPopup = new ContextMenu();
  private static final String[] KEYWORDS = new String[]{
          "abstract", "as", "assert",
          "boolean", "break", "byte",
          "case", "catch", "char", "class", "const", "continue",
          "def", "default", "do", "double",
          "else", "enum", "extends",
          "false", "final", "finally", "float", "for",
          "goto",
          "if", "implements", "import", "in", "instanceof", "int", "interface",
          "long",
          "native", "new", "null",
          "package", "private", "protected", "public",
          "return",
          "short", "static", "strictfp", "super", "switch", "synchronized",
          "this", "threadsafe", "throw", "throws",
          "transient", "true", "try",
          "var", "void", "volatile",
          "while"
  };

  private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
  private static final String PAREN_PATTERN = "\\(|\\)";
  private static final String BRACE_PATTERN = "\\{|\\}";
  private static final String BRACKET_PATTERN = "\\[|\\]";
  private static final String SEMICOLON_PATTERN = "\\;";
  private static final String STRING_PATTERN = "\"\"|''|\"[^\"]+\"|'[^']+'";
  private static final String COMMENT_PATTERN = "//[^\n]*" + "|" + "/\\*(.|\\R)*?\\*/";

  private static final Pattern PATTERN = Pattern.compile(
      "(?<KEYWORD>" + KEYWORD_PATTERN + ")"
          + "|(?<PAREN>" + PAREN_PATTERN + ")"
          + "|(?<BRACE>" + BRACE_PATTERN + ")"
          + "|(?<BRACKET>" + BRACKET_PATTERN + ")"
          + "|(?<SEMICOLON>" + SEMICOLON_PATTERN + ")"
          + "|(?<STRING>" + STRING_PATTERN + ")"
          + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
  );

  public GroovyTextArea() {
  }

  public GroovyTextArea(GroovyTab parent) {
    super(parent);
    addEventHandler(KeyEvent.KEY_PRESSED, e -> {
      if (e.isControlDown()) {
        if (KeyCode.ENTER.equals(e.getCode())) {
          CodeComponent codeComponent = parent.getGui().getCodeComponent();
          String gCode = getText(getCurrentParagraph()); // current line

          String selected = selectedTextProperty().getValue();
          // if text is selected then go with that instead
          if (selected != null && !"".equals(selected)) {
            gCode = codeComponent.getTextFromActiveTab();
          }
          parent.runGroovy(gCode);
          moveTo(getCurrentParagraph() + 1, 0);
          int totalLength = getAllTextContent().length();
          if (getCaretPosition() > totalLength) {
            moveTo(totalLength);
          }
        } else if (KeyCode.SPACE.equals(e.getCode())) {
          autoComplete();
        }
      }
    });
  }

  protected final StyleSpans<Collection<String>> computeHighlighting(String text) {
    Matcher matcher = PATTERN.matcher(text);
    int lastKwEnd = 0;
    StyleSpansBuilder<Collection<String>> spansBuilder
        = new StyleSpansBuilder<>();
    while (matcher.find()) {
      String styleClass =
          matcher.group("KEYWORD") != null ? "keyword" :
              matcher.group("PAREN") != null ? "paren" :
                  matcher.group("BRACE") != null ? "brace" :
                      matcher.group("BRACKET") != null ? "bracket" :
                          matcher.group("SEMICOLON") != null ? "semicolon" :
                              matcher.group("STRING") != null ? "string" :
                                  matcher.group("COMMENT") != null ? "comment" :
                                      null; /* never happens */
      assert styleClass != null;
      spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
      spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
      lastKwEnd = matcher.end();
    }
    spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
    return spansBuilder.create();
  }

  @Override
  public void autoComplete() {
    String line = getText(getCurrentParagraph());
    String currentText = line.substring(0, getCaretColumn());
    String lastWord;
    int index = currentText.indexOf(' ');
    if (index == -1 ) {
      lastWord = currentText;
    } else {
      lastWord = currentText.substring(currentText.lastIndexOf(' ') + 1);
    }
    index = lastWord.indexOf(',');
    if (index > -1) {
      lastWord = lastWord.substring(index+1);
    }
    index = lastWord.indexOf('(');
    if (index > -1) {
      lastWord = lastWord.substring(index+1);
    }
    index = lastWord.indexOf('[');
    if (index > -1) {
      lastWord = lastWord.substring(index+1);
    }
    index = lastWord.indexOf('{');
    if (index > -1) {
      lastWord = lastWord.substring(index+1);
    }

    //Gride.instance().getConsoleComponent().getConsole().appendFx("lastWord is " + lastWord, true);

    if (lastWord.length() > 0) {
      suggestCompletion(lastWord);
    }
  }

  private void suggestCompletion(String lastWord) {
    var contextObjects = Gride.instance().getConsoleComponent().getContextObjects();

    TreeMap<String, Boolean> suggestions = new TreeMap<>();
    for (Map.Entry<String, Object> contextObject: contextObjects.entrySet()) {
      String key = contextObject.getKey();
      if (key.equals(lastWord)) {
        suggestions.put(".", Boolean.FALSE);
      } else if (key.startsWith(lastWord)) {
        suggestions.put(key, Boolean.FALSE);
      } else if (lastWord.startsWith(key) && lastWord.contains(".")) {
        int firstDot = lastWord.indexOf('.');
        String varName = lastWord.substring(0, lastWord.indexOf('.'));
        if (firstDot != lastWord.lastIndexOf('.')) {
          Gride.instance().getConsoleComponent().getConsole().appendFx("static is not yet supported for" + varName, true);
        } else if (key.equals(varName)){
          suggestions.putAll(getInstanceMethods(contextObject.getValue(), lastWord.substring(firstDot+1)));
        }
      }
    }
    suggestCompletion(lastWord, suggestions, suggestionsPopup);
  }

  private Map<String, Boolean> getInstanceMethods(Object obj, String start) {
    Map<String, Boolean> instanceMethods = new TreeMap<>();
    for(Method method : obj.getClass().getMethods()) {
      //Gride.instance().getConsoleComponent().getConsole().appendFx(method.getName() + " and startWith '" + start + "'");
      if ( !Modifier.isStatic(method.getModifiers()) && ("".equals(start) || method.getName().startsWith(start))) {
        Boolean hasParams = method.getParameterCount() > 0;
        String suggestion = method.getName() + "()";
        if (Boolean.TRUE.equals(instanceMethods.get(suggestion))) {
          hasParams = Boolean.TRUE;
        }
        instanceMethods.put(suggestion, hasParams);
      }
    }
    return instanceMethods;
  }

}

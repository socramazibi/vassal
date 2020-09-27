/*
 *
 * Copyright (c) 2008-2020 by Brent Easton
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */
package VASSAL.script;

import VASSAL.build.BadDataReport;
import VASSAL.build.module.map.boardPicker.board.mapgrid.Zone;
import VASSAL.configure.PropertyExpression;
import VASSAL.counters.BasicPiece;
import VASSAL.counters.Decorator;
import VASSAL.counters.GamePiece;
import VASSAL.counters.PieceFilter;
import VASSAL.counters.Stack;
import VASSAL.i18n.Resources;
import VASSAL.tools.ErrorDialog;
import VASSAL.tools.RecursionLimitException;
import VASSAL.tools.RecursionLimiter;
import VASSAL.tools.RecursionLimiter.Loopable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import VASSAL.build.GameModule;
import VASSAL.build.module.Map;
import VASSAL.build.module.properties.PropertySource;
import VASSAL.script.expression.ExpressionException;
import VASSAL.tools.WarningDialog;
import bsh.BeanShellExpressionValidator;
import bsh.EvalError;
import bsh.NameSpace;

/**
 *
 * A BeanShell Interpreter customised to evaluate a single Vassal
 * expression containing Vassal property references.
 * All traits with the same expression will share the same Interpreter
 *
 * Each ExpressionInterpreter has 2 levels of NameSpace:
 * 1. Top level is a single global NameSpace that contains utility methods
 *    available to all ExpressionInterpreters. It is the parent of all
 *    level 2 NameSpaces.
 * 2. Level 2 is a NameSpace for each unique expression that contains the
 *    parsed expression. All expressions in all traits that are the same
 *    will use the one Expression NameSpace.
 *
 */
public class ExpressionInterpreter extends AbstractInterpreter implements Loopable {

  private static final long serialVersionUID = 1L;
  private static final Logger logger = LoggerFactory.getLogger(ExpressionInterpreter.class);

  protected static final String INIT_SCRIPT = "/VASSAL/script/init_expression.bsh"; // NON-NLS
  protected static final String THIS = "_interp"; // NON-NLS
  protected static final String SOURCE = "_source"; // NON-NLS
  protected static final String MAGIC1 = "_xyzzy"; // NON-NLS
  protected static final String MAGIC2 = "_plugh"; // NON-NLS
  protected static final String MAGIC3 = "_plover"; // NON-NLS


  // Top-level static NameSpace shared between all ExpressionInterpreters
  // Loaded with utility methods available to all interpreters
  protected static NameSpace topLevelNameSpace;

  protected NameSpace expressionNameSpace;

  //protected NameSpace localNameSpace;

  protected String expression;
  protected PropertySource source;
  protected List<String> variables;
  protected List<String> stringVariables;

  // Maintain a cache of all generated Interpreters. All Expressions
  // with the same Expression use the same Interpreter.
  protected static final HashMap<String, ExpressionInterpreter> cache = new HashMap<>();
  
  
  public String getComponentTypeName() {
    return Resources.getString("Editor.ExpressionInterpreter.component_type");
  }
  
  
  public String getComponentName() {
    return Resources.getString("Editor.ExpressionInterpreter.component_type");
  }


  public static ExpressionInterpreter createInterpreter(String expr) throws ExpressionException {
    final String e = expr == null ? "" : strip(expr);
    ExpressionInterpreter interpreter = cache.get(e);
    if (interpreter == null) {
      interpreter = new ExpressionInterpreter(e);
      cache.put(e, interpreter);
    }
    return interpreter;
  }

  protected static String strip(String expr) {
    final String s = expr.trim();
    if (s.startsWith("{") && s.endsWith("}")) {
      return s.substring(1, s.length() - 1);
    }
    return expr;
  }

  /**
   * Private constructor to build an ExpressionInterpreter. Interpreters
   * can only be created by createInterpreter.
   *
   * @param expr Expression
   * @throws ExpressionException Invalid Expression details
   */
  private ExpressionInterpreter(String expr) throws ExpressionException {
    super();

    expression = expr;

    // Install the Vassal Class loader so that bsh can find Vassal classes
    this.setClassLoader(this.getClass().getClassLoader());

    // Initialise the top-level name space if this is the first
    // expression to be created
    if (topLevelNameSpace == null) {
      initialiseStatic();
    }

    // Create the Expression level namespace as a child of the
    // top level namespace
    expressionNameSpace = new NameSpace(topLevelNameSpace, "expression"); // NON-NLS

    // Get a list of any variables used in the expression. These are
    // property names that will need to be evaluated at expression
    // evaluation time.
    // stringVariables is a list of the property names that call String functions so we
    // know must be String type. These will be passed in to the evaluating expression as
    // parameters to force their type to be known and allow String functions to be called on them.
    final BeanShellExpressionValidator validator = new BeanShellExpressionValidator(expression);
    variables = validator.getVariables();
    stringVariables = validator.getStringVariables();

    // Build a method enclosing the expression. This saves the results
    // of the expression parsing, improving performance. Force return
    // value to a String as this is what Vassal is expecting.
    // Pass the values of any String property names used in the expression as arguments
    setNameSpace(expressionNameSpace);
    if (expression.length() > 0) {
      try {
        final StringBuilder argList = new StringBuilder();
        for (String variable : stringVariables) {
          if (argList.length() > 0) {
            argList.append(',');
          }
          argList.append("String ").append(variable); // NON-NLS
        }
        eval("String " + MAGIC2 + "(" + argList.toString() + ") { " + MAGIC3 + "=" + expression + "; return " + MAGIC3 + ".toString();}"); // NON-NLS
      }
      catch (EvalError e) {
        throw new ExpressionException(getExpression());
      }
    }

    // Add a link to this Interpreter into the new NameSpace for callbacks from
    // BeanShell back to us
    setVar(THIS, this);

  }

  /**
   * Initialise the static elements of this class. Create a Top Level
   * NameSpace using the Vassal class loader, load useful classes and
   * read and process the init_expression.bsh file to load scripted
   * methods available to expressions.
   */
  protected void initialiseStatic() {
    topLevelNameSpace = new NameSpace(null, getClassManager(), "topLevel"); // NON-NLS
    setNameSpace(topLevelNameSpace);
    getNameSpace().importClass("VASSAL.build.module.properties.PropertySource");
    getNameSpace().importClass("VASSAL.script.ExpressionInterpreter");

    // Read the Expression initialisation script into the top level namespace
    URL ini = getClass().getResource(INIT_SCRIPT);
    logger.info("Attempting to load " + INIT_SCRIPT + " URI generated=" + ini); // NON-NLS

    try (InputStream is = ini.openStream();
         InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
         BufferedReader in = new BufferedReader(isr)) {
      try {
        eval(in);
      }
      catch (EvalError e) {
        logger.error("Error trying to read init script: " + ini); // NON-NLS
        WarningDialog.show(e, "");
      }
    }
    catch (IOException e) {
      logger.error("Error trying to read init script: " + ini); // NON-NLS
      WarningDialog.show(e, "");
    }
  }

  /**
   * Return the current expression
   *
   * @return expression
   */
  public String getExpression() {
    return expression;
  }

  /**
   * Evaluate the expression, setting the value of any undefined
   * values to the matching Vassal property value. Primitives must
   * be wrapped.
   *
   * @return result
   */
  public String evaluate(PropertySource ps) throws ExpressionException {
    return evaluate(ps, false);
  }

  public String evaluate(PropertySource ps, boolean localized) throws ExpressionException {

    if (getExpression().length() == 0) {
      return "";
    }

    String result;
    try {
      RecursionLimiter.startExecution(this);

      // Default to the GameModule to satisfy properties if no
      // GamePiece supplied.
      source = ps == null ? GameModule.getGameModule() : ps;
  
      setNameSpace(expressionNameSpace);
    
      // Bind each undeclared variable with the value of the
      // corresponding Vassal property. Allow for old-style $variable$ references
      for (String var : variables) {
        String name = var;
        if (name.length() > 2 && name.startsWith("$") && name.endsWith("$")) {
          name = name.substring(1, name.length() - 1);
        }
        Object prop = localized ? source.getLocalizedProperty(name) : source.getProperty(name);
        String value = prop == null ? "" : prop.toString();
        if (value == null) {
          setVar(var, "");
        }
        else if (BeanShell.TRUE.equals(value)) {
          setVar(var, true);
        }
        else if (BeanShell.FALSE.equals(value)) {
          setVar(var, false);
        }
        else {
          try {
            setVar(var, Integer.parseInt(value));
          }
          catch (NumberFormatException e) {
            setVar(var, value);
          }
        }
      }
    
      final StringBuilder argList = new StringBuilder();
      for (String var : stringVariables) {
        if (argList.length() > 0) {
          argList.append(',');
        }
        final Object value = localized ? source.getLocalizedProperty(var) : source.getProperty(var);
        argList.append('"').append(value.toString()).append('"');
      }
  
      // Re-evaluate the pre-parsed expression now that the undefined variables have
      // been bound to their Vassal property values.
    
      setVar(THIS, this);
      setVar(SOURCE, source);  
        
      try {
        eval(MAGIC1 + "=" + MAGIC2 + "(" + argList.toString() + ")");
        result = get(MAGIC1).toString();
      }
      catch (EvalError e) {
        final String s = e.getRawMessage();
        final String search = MAGIC2 + "();'' : ";
        final int pos = s.indexOf(search);
        throw new ExpressionException(getExpression(), s.substring(pos + search.length()));
      }
    }
    catch (RecursionLimitException e) {
      result = "";
      ErrorDialog.dataWarning (new BadDataReport(Resources.getString("Error.possible_infinite_expression_loop"), getExpression(), e));
    }
    finally {
      RecursionLimiter.endExecution();
    }

    return result;
  }

  public String evaluate() throws ExpressionException {
    return getExpression().length() == 0 ? "" : evaluate(GameModule.getGameModule());
  }

  /**
   * Convert a String value into a wrapped primitive object if possible.
   * Note this is a non-static copy of BeanShell.wrap(). Callbacks from
   * beanshell (e.g. getProperty) fail if an attempt is made to call a static method.
   *
   * @param value Value to wrap
   * @return wrapped value
   */
  public Object wrap(String value) {
    if (value == null) {
      return "";
    }
    else if (BeanShell.TRUE.equals(value)) {
      return Boolean.TRUE;
    }
    else if (BeanShell.FALSE.equals(value)) {
      return Boolean.FALSE;
    }
    else {
      try {
        return Integer.valueOf(value);
      }
      catch (NumberFormatException e) {
        return value;
      }
    }
  }

  /*****************************************************************
   * Callbacks from BeanShell Expressions to Vassal
   **/

  public Object getProperty(String name) {
    final Object value = source.getProperty(name);
    return value == null ? "" : wrap(value.toString());
  }

  public Object getLocalizedProperty(String name) {
    final Object value = source.getLocalizedProperty(name);
    return value == null ? "" : wrap(value.toString());
  }

  public Object getZoneProperty(String propertyName, String zoneName) {
    if (source instanceof GamePiece) {
      final Map map = ((GamePiece) source).getMap();
      if (map != null) {
        final Zone zone = map.findZone(zoneName);
        if (zone != null) {
          return wrap((String) zone.getProperty(propertyName));
        }
      }
    }
    return wrap("");
  }

  public Object getZoneProperty(String propertyName, String zoneName, String mapName) {
    final Map map = findVassalMap(mapName);
    if (map != null) {
      final Zone zone = map.findZone(zoneName);
      if (zone != null) {
        return wrap((String) zone.getProperty(propertyName));
      }
    }
    return wrap("");
  }

  public Object getMapProperty(String propertyName, String mapName) {
    final Map map = findVassalMap(mapName);
    return map == null ? wrap("") : wrap((String) map.getProperty(propertyName));
  }

  /**
   * SumStack(property) function
   * Total the value of the named property in all counters in the
   * same stack as the specified piece.
   *
   * @param property Property Name
   * @param ps       GamePiece
   * @return total
   */
  public Object sumStack(String property, PropertySource ps) {
    int result = 0;
    if (ps instanceof GamePiece) {
      Stack s = ((GamePiece) ps).getParent();
      if (s != null) {
        for (GamePiece gamePiece : s.asList()) {
          try {
            result +=
              Integer.parseInt(gamePiece.getProperty(property).toString());
          }
          catch (Exception e) {
            // Anything at all goes wrong trying to add the property, just ignore it and treat as 0
          }
        }
      }
    }
    return result;
  }

  /**
   * SumLocation(property) function
   * Total the value of the named property in all counters in the
   * same location as the specified piece.
   * <p>
   * * WARNING * This WILL be inefficient as the number of counters on the
   * map increases.
   *
   * @param property Property Name
   * @param ps       GamePiece
   * @return total
   */
  public Object sumLocation(String property, PropertySource ps) {
    int result = 0;
    if (ps instanceof GamePiece) {
      GamePiece p = (GamePiece) ps;
      Map m = p.getMap();
      if (m != null) {
        String here = m.locationName(p.getPosition());
        GamePiece[] pieces = m.getPieces();
        for (GamePiece piece : pieces) {
          if (here.equals(m.locationName(piece.getPosition()))) {
            if (piece instanceof Stack) {
              Stack s = (Stack) piece;
              for (GamePiece gamePiece : s.asList()) {
                try {
                  result += Integer.parseInt(gamePiece.getProperty(property).toString());
                }
                catch (NumberFormatException e) {
                  //
                }
              }
            }
            else {
              try {
                result += Integer.parseInt(piece.getProperty(property).toString());
              }
              catch (NumberFormatException e) {
                //
              }
            }
          }
        }
      }
    }
    return result;
  }

  /*
   * Random Numbers
   *
   * - random(max)       - Return a Random integer between 1 and max inclusive (init_beanshell.bsh calls random(1, max))
   * - random(min, max)  - Return a Random integer between min and max inclusive
   * - isRandom(percent) - Return true percent% of the time
   * - isRandom()        - Equivalent to Random(50) (init_beanshell.bsh calls isRandom(50))
   */

  public Object random(Object source, Object minString, Object maxString) {
    final int min = parseInt(source, "Random", minString, 1); // NON-NLS
    int max = parseInt(source, "Random", maxString, 1); // NON-NLS
    if (max < min) {
      max = min;
    }
    if (min == max) {
      return min;
    }
    final int range = max - min + 1;
    return GameModule.getGameModule().getRNG().nextInt(range) + min;
  }

  public Object isRandom(Object source, Object percentString) {
    int percent = parseInt(source, "IsRandom", percentString, 50); // NON-NLS
    if (percent < 0)
      percent = 0;
    if (percent > 100)
      percent = 100;
    final int r = GameModule.getGameModule().getRNG().nextInt(100) + 1;
    return r <= percent;
  }

  private int parseInt(Object source, String function, Object value, int dflt) {
    int result = dflt;
    try {
      result = Integer.parseInt(value.toString());
    }
    catch (Exception e) {
      final String message = "Illegal number in call to Beanshell function " + function + ". " + ((source instanceof Decorator) ? "Piece= [" + ((Decorator) source).getProperty(BasicPiece.BASIC_NAME) + "]. " : ""); //NON-NLS
      final String data = "Data=[" + value.toString() + "]."; //NON-NLS
      ErrorDialog.dataWarning(new BadDataReport(message, data, e));
    }
    return result;
  }

  /*
   * Sum & Count
   *
   * Sum (property, match)      - Sum named property in units on all maps matching match
   * Sum (property, match, map) - Sum named property in units on named map matching match
   * Count (match)              - Count units on all maps matching match
   * Count (match, map)         - Count units on named map matching match

   */
  public Object sum(Object source, Object propertyName, Object propertyMatch) {
    return sum (source, propertyName, propertyMatch, null);
  }

  public Object sum(Object source, Object propertyName, Object propertyMatch, Object mapName) {
    int result = 0;

    if (! (source instanceof GamePiece)) return 0;
    if (! (propertyName instanceof String)) return 0;
    if (! (propertyMatch == null || propertyMatch instanceof String)) return 0;
    if (! (mapName == null || mapName instanceof String)) return 0;

    final String matchString = (String) propertyMatch;
    final GamePiece sourcePiece = (GamePiece) source;
    final List<Map> maps = getMapList(mapName, sourcePiece);
    final PieceFilter filter = matchString == null ? null : new PropertyExpression(unescape(matchString)).getFilter(sourcePiece);

    for (Map map : maps) {
      for (GamePiece piece : map.getAllPieces()) {
        if (piece instanceof Stack) {
          for (GamePiece p : ((Stack) piece).asList()) {
            result += getIntPropertyValue(p, filter, (String) propertyName);
          }
        }
        else {
          result += getIntPropertyValue(piece, filter, (String) propertyName);
        }
      }
    }

    return result;
  }

  private int getIntPropertyValue (GamePiece piece, PieceFilter filter, String propertyName) {
    if (filter == null || filter.accept(piece)) {
      try {
        return Integer.parseInt((String) piece.getProperty(propertyName));
      }
      catch (Exception ignored) {

      }
    }
    return 0;
  }

  public Object count(Object source, Object propertyMatch) {
    return count(source, propertyMatch, null);
  }

  public Object count(Object source, Object propertyMatch, Object mapName) {
    int result = 0;

    if (! (source instanceof GamePiece)) return 0;
    if (! (propertyMatch == null || propertyMatch instanceof String)) return 0;
    if (! (mapName == null || mapName instanceof String)) return 0;

    final String matchString = (String) propertyMatch;
    final GamePiece sourcePiece = (GamePiece) source;

    final List<Map> maps = getMapList(mapName, sourcePiece);

    PieceFilter filter = matchString == null ? null : new PropertyExpression(unescape(matchString)).getFilter(sourcePiece);
    for (Map map : maps) {
      for (GamePiece piece : map.getAllPieces()) {
        if (piece instanceof Stack) {
          for (GamePiece p : ((Stack) piece).asList()) {
            if (filter == null || filter.accept(p)) {
              result++;
            }
          }
        }
        else {
          if (filter == null || filter.accept(piece)) {
            result++;
          }
        }
      }
    }

    return result;
  }

  private String unescape(String expr) {
    return expr.replace("\\\"", "\"");
  }

  private List<Map> getMapList(Object mapName, GamePiece sourcePiece) {
    final List<Map> maps;
    if (mapName == null) {
      maps = Map.getMapList();
    }
    else {
      maps = new ArrayList<>();
      // Shortcut - See if the parent piece for our source piece is the map we want (most likely)
      if (sourcePiece != null && sourcePiece.getMap().getMapName().equals(mapName)) {
        maps.add(sourcePiece.getMap());
      }
      // Otherwise, search all maps for the one we want
      else {
        for (Map map : Map.getMapList()) {
          if (map.getMapName().equals(mapName)) {
            maps.add(map);
            break;
          }
        }
      }
    }
    return maps;
  }

  private Map findVassalMap(String mapName) {
    for (Map map : Map.getMapList()) {
      if (map.getMapName().equals(mapName)) {
        return map;
      }
    }
    return null;
  }

}

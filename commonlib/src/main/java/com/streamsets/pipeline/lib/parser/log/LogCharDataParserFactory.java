/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.parser.log;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.LogMode;
import com.streamsets.pipeline.config.OnParseError;
import com.streamsets.pipeline.lib.io.OverrunReader;
import com.streamsets.pipeline.lib.parser.CharDataParserFactory;
import com.streamsets.pipeline.lib.parser.DataParser;
import com.streamsets.pipeline.lib.parser.DataParserException;
import com.streamsets.pipeline.lib.parser.shaded.org.aicer.grok.dictionary.GrokDictionary;
import com.streamsets.pipeline.lib.parser.shaded.org.aicer.grok.util.Grok;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LogCharDataParserFactory extends CharDataParserFactory {

  static final String KEY_PREFIX = "log.";
  public static final String RETAIN_ORIGINAL_TEXT_KEY = KEY_PREFIX + "retain.original.text";
  static final boolean RETAIN_ORIGINAL_TEXT_DEFAULT = false;
  public static final String APACHE_CUSTOMLOG_FORMAT_KEY = KEY_PREFIX + "apache.custom.log.format";
  static final String APACHE_CUSTOMLOG_FORMAT_DEFAULT = "%h %l %u %t \"%r\" %>s %b";
  public static final String REGEX_KEY = KEY_PREFIX + "regex";
  static final String REGEX_DEFAULT =
    "^(\\S+) (\\S+) (\\S+) \\[([\\w:/]+\\s[+\\-]\\d{4})\\] \"(\\S+) (\\S+) (\\S+)\" (\\d{3}) (\\d+)";
  public static final String REGEX_FIELD_PATH_TO_GROUP_KEY = KEY_PREFIX + "regex.fieldPath.to.group.name";
  static final Map<String, Integer> REGEX_FIELD_PATH_TO_GROUP_DEFAULT = new HashMap<>();
  public static final String GROK_PATTERN_KEY = KEY_PREFIX + "grok.pattern";
  static final String GROK_PATTERN_DEFAULT = "%{COMMONAPACHELOG}";
  public static final String GROK_PATTERN_DEFINITION_KEY = KEY_PREFIX + "grok.pattern.definition";
  static final String GROK_PATTERN_DEFINITION_DEFAULT = "";
  public static final String LOG4J_FORMAT_KEY = KEY_PREFIX + "log4j.custom.log.format";
  static final String LOG4J_FORMAT_DEFAULT = "%d{ISO8601} %-5p %c{1} - %m";

  public static final String ON_PARSE_ERROR_KEY = KEY_PREFIX + "on.parse.error";
  public static final OnParseError ON_PARSE_ERROR_DEFAULT = OnParseError.ERROR;
  public static final String LOG4J_TRIM_STACK_TRACES_TO_LENGTH_KEY = KEY_PREFIX + "log4j.trim.stack.trace.to.length";
  static final int LOG4J_TRIM_STACK_TRACES_TO_LENGTH_DEFAULT = 50;

  public static Map<String, Object> registerConfigs(Map<String, Object> configs) {
    configs.put(RETAIN_ORIGINAL_TEXT_KEY, RETAIN_ORIGINAL_TEXT_DEFAULT);
    configs.put(APACHE_CUSTOMLOG_FORMAT_KEY, APACHE_CUSTOMLOG_FORMAT_DEFAULT);
    configs.put(REGEX_KEY, REGEX_DEFAULT);
    configs.put(REGEX_FIELD_PATH_TO_GROUP_KEY, REGEX_FIELD_PATH_TO_GROUP_DEFAULT);
    configs.put(GROK_PATTERN_DEFINITION_KEY, GROK_PATTERN_DEFINITION_DEFAULT);
    configs.put(GROK_PATTERN_KEY, GROK_PATTERN_DEFAULT);
    configs.put(LOG4J_FORMAT_KEY, LOG4J_FORMAT_DEFAULT);
    configs.put(ON_PARSE_ERROR_KEY, ON_PARSE_ERROR_DEFAULT);
    configs.put(LOG4J_TRIM_STACK_TRACES_TO_LENGTH_KEY, LOG4J_TRIM_STACK_TRACES_TO_LENGTH_DEFAULT);
    return configs;
  }

  private final Stage.Context context;
  private final int maxObjectLen;
  private final LogMode logMode;
  private final boolean retainOriginalText;
  private final String customLogFormat;
  private final String regex;
  private final Map<String, Integer> fieldPathToGroup;
  private final String grokPatternDefinition;
  private final String grokPattern;
  private final List<String> grokDictionaries;
  private final String log4jCustomLogFormat;
  private final OnParseError onParseError;
  private final int maxStackTraceLength;
  private final Map<String, Object> regexToPatternMap;

  public LogCharDataParserFactory(Stage.Context context, int maxObjectLen, LogMode logMode,
                                  Map<String, Object> configs) {
    this.context = context;
    this.maxObjectLen = maxObjectLen;
    this.logMode = logMode;
    this.retainOriginalText = (boolean) configs.get(RETAIN_ORIGINAL_TEXT_KEY);
    this.customLogFormat = (String) configs.get(APACHE_CUSTOMLOG_FORMAT_KEY);
    this.regex = (String) configs.get(REGEX_KEY);
    this.fieldPathToGroup = (Map<String, Integer>) configs.get(REGEX_FIELD_PATH_TO_GROUP_KEY);
    this.grokPatternDefinition = (String) configs.get(GROK_PATTERN_DEFINITION_KEY);
    this.grokPattern = (String) configs.get(GROK_PATTERN_KEY);
    this.grokDictionaries = Collections.emptyList();
    this.log4jCustomLogFormat = (String) configs.get(LOG4J_FORMAT_KEY);
    this.onParseError = (OnParseError) configs.get(ON_PARSE_ERROR_KEY);
    this.maxStackTraceLength = (Integer) configs.get(LOG4J_TRIM_STACK_TRACES_TO_LENGTH_KEY);
    this.regexToPatternMap = new HashMap<>();
  }

  @Override
  public DataParser getParser(String id, OverrunReader reader, long readerOffset) throws DataParserException {
    Utils.checkState(reader.getPos() == 0, Utils.formatL("reader must be in position '0', it is at '{}'",
      reader.getPos()));
    try {
      switch (logMode) {
        case COMMON_LOG_FORMAT:
          return new GrokParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            getMaxStackTraceLines(), createGrok(Constants.GROK_COMMON_APACHE_LOG_FORMAT,
            Collections.<String>emptyList()), "Common Log Format");
        case COMBINED_LOG_FORMAT:
          return new GrokParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            getMaxStackTraceLines(), createGrok(Constants.GROK_COMBINED_APACHE_LOG_FORMAT,
            Collections.<String>emptyList()), "Combined Log Format");
        case APACHE_CUSTOM_LOG_FORMAT:
          return new GrokParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            getMaxStackTraceLines(), createGrok(ApacheCustomLogHelper.translateApacheLayoutToGrok(customLogFormat),
              Collections.<String>emptyList()), "Apache Access Log Format");
        case APACHE_ERROR_LOG_FORMAT:
          return new GrokParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            getMaxStackTraceLines(), createGrok(Constants.GROK_APACHE_ERROR_LOG_FORMAT,
              ImmutableList.of(Constants.GROK_APACHE_ERROR_LOG_PATTERNS_FILE_NAME)), "Apache Error Log Format");
        case REGEX:
          return new RegexParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            createPattern(regex), fieldPathToGroup);
        case GROK:
          return new GrokParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            getMaxStackTraceLines(), createGrok(grokPattern, grokDictionaries), "Grok Format");
        case LOG4J:
          return new GrokParser(context, id, reader, readerOffset, maxObjectLen, retainOriginalText,
            getMaxStackTraceLines(), createGrok(Log4jHelper.translateLog4jLayoutToGrok(log4jCustomLogFormat),
              ImmutableList.of(Constants.GROK_LOG4J_LOG_PATTERNS_FILE_NAME)),
            "Log4j Log Format");
        default:
          return null;
      }
    } catch (IOException ex) {
      throw new DataParserException(Errors.LOG_PARSER_00, id, readerOffset, ex.getMessage(), ex);
    }
  }

  @VisibleForTesting
  private Grok createGrok(String grokPattern, List<String> dictionaries) {
    if(regexToPatternMap.containsKey(grokPattern)) {
      return (Grok) regexToPatternMap.get(grokPattern);
    }
    GrokDictionary grokDictionary = new GrokDictionary();
    //Add grok patterns and Java patterns by default
    grokDictionary.addDictionary(getClass().getClassLoader().getResourceAsStream(Constants.GROK_PATTERNS_FILE_NAME));
    grokDictionary.addDictionary(getClass().getClassLoader().getResourceAsStream(
      Constants.GROK_JAVA_LOG_PATTERNS_FILE_NAME));
    for(String dictionary : dictionaries) {
      grokDictionary.addDictionary(getClass().getClassLoader().getResourceAsStream(dictionary));
    }
    if(grokPatternDefinition != null && !grokPatternDefinition.isEmpty()) {
      grokDictionary.addDictionary(new StringReader(grokPatternDefinition));
    }
    // Resolve all expressions loaded
    grokDictionary.bind();
    Grok grok = grokDictionary.compileExpression(grokPattern);
    regexToPatternMap.put(grokPattern, grok);
    return grok;
  }

  @VisibleForTesting
  private Pattern createPattern(String regex) {
    if(regexToPatternMap.containsKey(regex)) {
      return (Pattern) regexToPatternMap.get(regex);
    }
    Pattern pattern = Pattern.compile(regex);
    regexToPatternMap.put(regex, pattern);
    return pattern;
  }

  public int getMaxStackTraceLines() {
    switch (onParseError) {
      case ERROR:
        return -1;
      case IGNORE:
        return 0;
      case INCLUDE_AS_STACK_TRACE:
        return maxStackTraceLength;
      default:
        throw new IllegalArgumentException("Unexpected value for OnParseError");
    }
  }
}

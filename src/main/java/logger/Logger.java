package logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    public enum Level { DEBUG, INFO, WARN, ERROR, TEST }

    private static Level currentLogLevel = Level.INFO;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void setLogLevel(Level level) {
        currentLogLevel = level;
    }

    private static void log(Level level, String nodeId, String message) {
        if (level.ordinal() >= currentLogLevel.ordinal()) {
            String timestamp = LocalDateTime.now().format(formatter);
            if (nodeId == null)
                System.out.printf("[%s] [%-5s]: %s%n", timestamp, level.name(), message);
            else if (nodeId != null)
                System.out.printf("[%s] [%-5s] [%s]: %s%n", timestamp, level.name(), nodeId, message);
        }
    }

    public static void debug(String nodeId, String message) { log(Level.DEBUG, nodeId, message); }
    public static void info(String nodeId, String message) { log(Level.INFO, nodeId, message); }
    public static void warn(String nodeId, String message) { log(Level.WARN, nodeId, message); }
    public static void error(String nodeId, String message) { log(Level.ERROR, nodeId, message); }
    public static void test(String nodeId, String message) { log(Level.TEST, nodeId, message); }
}

package altitude.launcher;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The native jar's main class. Compiled for Java 8 and linked against nothing of Altitude's, so that any Java from 8 on loads it
 * and runs these checks before a class of the application loads:
 *
 * <ul>
 *   <li>A Java older than 21, the application's floor, is told what to install, instead of failing on an
 *       {@code UnsupportedClassVersionError}.
 *   <li>A jar of another platform than the machine's is told which jar to download, or that Altitude runs there in Docker,
 *       instead of failing on an {@code UnsatisfiedLinkError} once a native library loads.
 * </ul>
 *
 * <p>Then it starts the application. ONNX Runtime's native library sends usage telemetry to Microsoft unless {@code
 * ORT_DISABLE_TELEMETRY=1} is in the process's environment when it loads, and a running JVM cannot set its own environment. So
 * with the variable it calls {@code altitude.core.App.main} by reflection in this JVM; without it, it starts the same JVM once
 * more with the variable added, passes it its input and output and, on Linux and macOS, its stop, and exits with its status.
 */
public final class Launcher {

    /** The variable that switches ONNX Runtime's telemetry off, and its value: {@code OnnxEnvironment.TELEMETRY_OFF}'s */
    public static final String TELEMETRY_VARIABLE = "ORT_DISABLE_TELEMETRY";
    public static final String TELEMETRY_OFF = "1";

    /** The oldest Java the application runs on: it is compiled for Java 21 ({@code -release 21} in {@code build.mill}) */
    public static final int JAVA_FLOOR = 21;

    /** The manifest attribute that names a native jar's platform ({@code NativeLibraries.PLATFORM}, {@code build.mill}'s) */
    public static final String PLATFORM_ATTRIBUTE = "Altitude-Platform";

    /** The application's main class */
    public static final String APP = "altitude.core.App";

    /** A system property naming another main class than {@link #APP}, which the launcher's tests run in its place */
    public static final String MAIN_PROPERTY = "altitude.launcher.main";

    /**
     * Held by {@link #relaunch} while it starts the second JVM and by the shutdown hook while it reads {@link #child}, so a stop
     * cannot come between the start and the assignment and miss the second JVM.
     */
    private static final Object LOCK = new Object();

    /** The second JVM once {@link #relaunch} has started it, which the shutdown hook passes a stop on to; under {@link #LOCK} */
    private static Process child;

    /** Whether the shutdown hook has run, after which no second JVM is started; under {@link #LOCK} */
    private static boolean stopping;

    private Launcher() {}

    public static void main(String[] args) throws Throwable {
        Attributes manifest = ownManifest();
        String problem = startProblem(
            System.getProperty("java.specification.version"),
            System.getProperty("java.home"),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            manifest == null ? null : manifest.getValue(PLATFORM_ATTRIBUTE),
            manifest == null ? null : manifest.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
        if (problem != null) {
            System.err.println(problem);
            System.exit(1);
        }

        if (TELEMETRY_OFF.equals(System.getenv(TELEMETRY_VARIABLE))) runApplication(args);
        else System.exit(relaunch(args));
    }

    /**
     * Why this jar cannot start on this Java and this machine, in a line or two of what to do instead; {@code null} when it can.
     * A jar with no platform of its own (the launcher run from a classes folder, as in its tests) is checked for its Java alone.
     *
     * @param javaVersion {@code java.specification.version}: {@code 1.8} up to Java 8, {@code 21} from Java 9 on
     * @param jarPlatform the jar's {@code Altitude-Platform}, or {@code null}
     * @param jarVersion the jar's {@code Implementation-Version}, which a name of the jar to download carries, or {@code null}
     */
    public static String startProblem(
            String javaVersion, String javaHome, String osName, String osArch, String jarPlatform, String jarVersion) {
        List<String> problems = new ArrayList<String>();

        if (jarPlatform != null) {
            String platform = platformOf(osName, osArch);
            if (platform == null) {
                // No jar would run here, so the Java version is beside the point. A 32-bit Java says so of itself, whatever the
                // machine: on a 64-bit one, a 64-bit Java is all it takes.
                String noBuild = "Altitude has no build for " + osName + " on " + osArch + ". It runs with Docker on a 64-bit x86 or "
                    + "ARM machine (linux/amd64 or linux/arm64).";
                return is32Bit(osArch)
                    ? "This Java is a 32-bit one (" + osArch + "). If this machine is a 64-bit one, install a 64-bit Java "
                        + JAVA_FLOOR + " and start the jar with it. " + noBuild
                    : noBuild;
            } else if (platform.equals("macosx-x86_64")) {
                // A Java for Intel on an Apple Silicon Mac, which Rosetta runs, reports x86_64 too: the arm64 jar is the tell
                String rosetta = "If this is an Apple Silicon Mac, this Java is an Intel one that Rosetta runs: install an arm64 "
                    + "Java " + JAVA_FLOOR + " and start the jar with it.";
                String intelMac = "Altitude has no jar for an Intel Mac, for which ONNX Runtime ships no native libraries. Run it "
                    + "with Docker Desktop instead (macOS 14 or later).";
                return jarPlatform.equals("macosx-arm64")
                    ? "This Java reports an Intel Mac (" + osArch + "), and this jar is Apple Silicon's. " + rosetta + " On an "
                        + "Intel Mac, which has no jar, run Altitude with Docker Desktop (macOS 14 or later)."
                    : intelMac + " " + rosetta;
            } else if (platform.equals("windows-arm64")) {
                return "Altitude has no jar for Windows on ARM. Run it with Docker Desktop, or start the windows-x86_64 jar with "
                    + "an x64 Java " + JAVA_FLOOR + ", which Windows runs emulated (not verified).";
            } else if (!platform.equals(jarPlatform)) {
                String jar = jarVersion == null ? "the " + platform + " jar" : "altitude-" + jarVersion + "-" + platform + ".jar";
                problems.add("This is Altitude's jar for " + jarPlatform + ", and this machine is " + platform + ": download "
                    + jar + " and start that one.");
            }
        }

        int major = javaMajor(javaVersion);
        if (major > 0 && major < JAVA_FLOOR) {
            problems.add("Altitude needs Java " + JAVA_FLOOR + " or later, and this is Java " + major + " (" + javaHome + "). "
                + "Install a Java " + JAVA_FLOOR + " runtime, such as Eclipse Temurin " + JAVA_FLOOR + " from "
                + "https://adoptium.net, and start the jar with its java.");
        }

        return problems.isEmpty() ? null : join(problems);
    }

    /** The major version in a {@code java.specification.version}, {@code 1.8} and {@code 21} alike; 0 for one it cannot read */
    public static int javaMajor(String specificationVersion) {
        if (specificationVersion == null) return 0;
        String version = specificationVersion.startsWith("1.") ? specificationVersion.substring(2) : specificationVersion;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
    }

    /**
     * The machine's platform as a native jar's name has it, {@code <os>-<arch>}, from {@code os.name} and {@code os.arch}: Linux,
     * macOS or Windows on 64-bit x86 or ARM, whether or not a jar exists for it (none does for an Intel Mac or Windows on ARM).
     * {@code null} for any other system or architecture, a 32-bit one among them.
     */
    public static String platformOf(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);

        String family;
        if (os.startsWith("linux")) family = "linux";
        else if (os.startsWith("mac") || os.startsWith("darwin")) family = "macosx";
        else if (os.startsWith("windows")) family = "windows";
        else return null;

        if (arch.equals("aarch64") || arch.equals("arm64")) return family + "-arm64";
        else if (arch.equals("amd64") || arch.equals("x86_64")) return family + "-x86_64";
        else return null;
    }

    /** Whether {@code os.arch} names a 32-bit architecture, which a 32-bit Java reports on a 64-bit machine too */
    private static boolean is32Bit(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return arch.matches("x86|i[3-6]86|arm|armv[5-7].*|aarch32");
    }

    /**
     * What to say before the second JVM starts, when the JVM options it takes over start something in each JVM that is meant for
     * one: an agent ({@code -javaagent:}, {@code -agentlib:}, {@code -agentpath:}), a debugger's port ({@code jdwp}, by
     * {@code -agentlib} or the older {@code -Xrunjdwp}), a JMX port ({@code com.sun.management.jmxremote.port}) or a flight
     * recording ({@code -XX:StartFlightRecording}). This JVM runs each of them already: a port is then taken twice, and an agent
     * or a recording reports on a launcher that runs no application. {@code null} when the options start none.
     */
    public static String relaunchHint(List<String> inputArguments) {
        for (String argument : inputArguments) {
            if (argument.startsWith("-javaagent:") || argument.startsWith("-agentlib:") || argument.startsWith("-agentpath:")
                    || argument.contains("jdwp") || argument.contains("jmxremote.port")
                    || argument.startsWith("-XX:StartFlightRecording")) {
                return "Altitude starts this JVM once more with " + TELEMETRY_VARIABLE + "=" + TELEMETRY_OFF + " and the same "
                    + "options, so an agent, a debugger's or JMX port, or a flight recording among them runs in both. Set "
                    + TELEMETRY_VARIABLE + "=" + TELEMETRY_OFF + " yourself, and Altitude runs in this JVM alone.";
            }
        }
        return null;
    }

    /** The main attributes of the jar this class is in; {@code null} when it is not in a jar or the jar has no manifest */
    private static Attributes ownManifest() throws Exception {
        File jar = ownJar();
        if (jar == null) return null;
        JarFile file = new JarFile(jar);
        try {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes();
        } finally {
            file.close();
        }
    }

    /**
     * The jar this class is in; {@code null} when it is loaded from a folder. Read through a {@code Path}, since a jar on a
     * Windows share is at {@code file://server/share/...}, whose authority {@code new File(URI)} refuses.
     */
    private static File ownJar() throws Exception {
        URL location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
        File file = Paths.get(location.toURI()).toFile();
        return file.isFile() ? file : null;
    }

    /** Calls the application's main, or the one {@link #MAIN_PROPERTY} names, in this JVM */
    private static void runApplication(String[] args) throws Throwable {
        Method main = Class.forName(System.getProperty(MAIN_PROPERTY, APP)).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Starts this JVM once more, with the telemetry variable set, and answers its exit status once it has ended. It is the same
     * {@code java} with the same JVM options and arguments, and it inherits the working directory, the environment, the input and
     * the output; a stop of this JVM is passed on to it, and this JVM's ends once it has.
     */
    private static int relaunch(String[] args) throws Exception {
        List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        String hint = relaunchHint(inputArguments);
        if (hint != null) System.err.println(hint);

        List<String> command = new ArrayList<String>();
        command.add(new File(new File(System.getProperty("java.home"), "bin"), isWindows() ? "java.exe" : "java").getPath());
        command.addAll(inputArguments);
        File jar = startedJar();
        if (jar != null) {
            // The manifest's attributes, Enable-Native-Access among them, apply to a `java -jar` start alone
            command.add("-jar");
            command.add(jar.getPath());
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Launcher.class.getName());
        }
        command.addAll(Arrays.asList(args));

        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        Map<String, String> environment = builder.environment();
        environment.put(TELEMETRY_VARIABLE, TELEMETRY_OFF);
        // The JVM options these variables hold are among this JVM's input arguments already: the second JVM would take them twice
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("_JAVA_OPTIONS");

        // Registered before the start, and the start and the hook's read of the second JVM each hold the lock: a stop before the
        // start starts none, and one during it reaches the second JVM once it has started, never missing one that is starting. A
        // lambda and not an inner class, so that the launcher stays the one class file.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process running;
            synchronized (LOCK) {
                stopping = true;
                running = child;
            }
            if (running == null) return;
            // On Linux and macOS `destroy` is a SIGTERM, on which the server drains its queues and closes its database before it
            // exits. On Windows it would kill the server outright; there a console's Ctrl-C reaches both processes by itself, so
            // this hook only waits. Either way this JVM ends once the server has.
            if (!isWindows()) running.destroy();
            waitFor(running);
        }, "altitude-launcher-stop"));

        Process started;
        synchronized (LOCK) {
            // This JVM is stopping already, and exits with the stop's status whatever this answers
            if (stopping) return 143;
            child = builder.start();
            started = child;
        }
        return waitFor(started);
    }

    /**
     * The jar this JVM was started with by {@code java -jar}: the one this class is in, when it is the whole classpath; {@code
     * null} for any other start, which is relaunched with the same classpath and this class as its main class.
     */
    private static File startedJar() throws Exception {
        File jar = ownJar();
        String classpath = System.getProperty("java.class.path", "");
        if (jar == null || classpath.isEmpty() || classpath.contains(File.pathSeparator)) return null;
        return new File(classpath).getCanonicalFile().equals(jar.getCanonicalFile()) ? jar.getAbsoluteFile() : null;
    }

    /** The process's exit status, once it has ended, however often the wait is interrupted */
    private static int waitFor(Process process) {
        while (true) {
            try {
                return process.waitFor();
            } catch (InterruptedException ignored) {
                // Nothing here interrupts a wait: the process is waited for until it ends
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() > 0) text.append(System.lineSeparator());
            text.append(line);
        }
        return text.toString();
    }
}

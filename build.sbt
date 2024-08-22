organization := "software.altitude"
name := "altitude"
version := "0.1.0"
scalaVersion := "2.13.14"

val json4sVersion = "4.0.7"
val scalatraVersion = "2.8.4"
val jettyVersion = "9.4.20.v20190813"
val opencvVersion = "1.5.10"

scalacOptions := Seq(
  "-deprecation",
  "-language:postfixOps",
  "-opt:l:method",
  "-feature",
  "-Wunused:imports",
)

libraryDependencies ++= Seq(
  "org.json4s"                  %% "json4s-jackson"           % json4sVersion,

  "org.scalatra"                %% "scalatra"                 % scalatraVersion,
  "org.scalatra"                %% "scalatra-atmosphere"      % scalatraVersion,
  "org.scalatra"                %% "scalatra-scalate"         % scalatraVersion,
  "org.scalatra"                %% "scalatra-auth"            % scalatraVersion,
  "org.scalatra"                %% "scalatra-atmosphere"      % scalatraVersion,
  "org.scalatra"                %% "scalatra-json"            % scalatraVersion,
  "org.scalatra.scalate"        %% "scalate-core"             % "1.10.1",

  "com.typesafe"                 % "config"                   % "1.4.3",
  "com.typesafe.play"           %% "play-json"                % "2.10.5",

  "commons-io"                   % "commons-io"               % "2.16.1",
  "commons-codec"                % "commons-codec"            % "1.17.0",
  "commons-dbutils"              % "commons-dbutils"          % "1.8.1",
  "commons-logging"              % "commons-logging"          % "1.3.3",
  "commons-fileupload"           % "commons-fileupload"       % "1.5",
  "org.mindrot"                  % "jbcrypt"                  % "0.4",

  "org.postgresql"               % "postgresql"               % "42.7.3",
  "org.xerial"                   % "sqlite-jdbc"              % "3.46.0.0",

  "com.drewnoakes"               % "metadata-extractor"       % "2.19.0",
  "org.apache.tika"              % "tika-core"                % "2.9.2",
  "org.apache.tika"              % "tika-parsers" % "2.9.2",
  "org.apache.tika"              % "tika-parser-image-module" % "2.9.2",
  "ch.qos.logback"               % "logback-classic"          % "1.5.6" % "runtime",
  "org.slf4j"                    % "slf4j-api"                % "2.0.12" % "runtime",

  "org.scalatra"                %% "scalatra-scalatest"       % scalatraVersion % Test,
  "org.mockito"                  % "mockito-core"             % "5.11.0" % Test,

  //
  // ATTN: The old versions are required - we don't need to update them until Scalatra 3.x/Scala 3.x upgrade
  //
  "org.eclipse.jetty"            % "jetty-continuation"       % jettyVersion % "container;compile;test",
  "org.eclipse.jetty.websocket"  % "websocket-server"         % jettyVersion,
  "org.eclipse.jetty"            % "jetty-webapp"             % jettyVersion % "container;compile;test",
  "javax.servlet"                % "javax.servlet-api"        % "3.1.0" % Provided
)

enablePlugins(ScalatraPlugin)

inThisBuild(
  List(
    scalaVersion := scalaVersion.value,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  )
)

val javacppVersion = "1.5.7"

// From: https://github.com/duanebester/streaming-ocr

// Platform classifier for native library dependencies
val platform = org.bytedeco.javacpp.Loader.getPlatform
// Seq("windows-x86_64", "macosx-x86_64", "linux-x86_64")

// Libraries with native dependencies
val bytedecoPresetLibs = Seq(
  "opencv" -> s"4.5.5-$javacppVersion",
  "ffmpeg" -> s"5.0-$javacppVersion",
  "openblas" -> s"0.3.19-$javacppVersion"

  // Fails RN with:
  //    bytedeco/openblas/linux-x86_64/libjniopenblas_nolapack.so: /lib/x86_64-linux-gnu/libm.so.6: version `GLIBC_2.29' not found
  //"opencv" -> s"4.9.0-$javacppVersion",
  //"ffmpeg" -> s"6.1.1-$javacppVersion",
  //"openblas" -> s"0.3.26-$javacppVersion"

).flatMap {
  case (lib, ver) => Seq(
    // Add both: dependency and its native binaries for the current `platform`
    "org.bytedeco" % lib % ver withSources() withJavadoc(),
    "org.bytedeco" % lib % ver classifier platform
  )
}

libraryDependencies ++= bytedecoPresetLibs

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
)

test in assembly := {}

parallelExecution in Test := false

unmanagedResourceDirectories in Compile += {
  baseDirectory.value / "src/main/webapp"
}

//unmanagedClasspath in Compile += {
//  baseDirectory.value / "lib"
//}

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) if xs.contains("MANIFEST.MF") => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case PathList(ps @ _*) if ps.last endsWith ".class" => MergeStrategy.first
  case _ => MergeStrategy.first
}

assembly / target := baseDirectory.value / "release"

assembly / assemblyJarName := {
  val base = name.value
  s"$base-${version.value}.jar"
}

assembly / mainClass := Some("ScalatraLauncher")

commands += Command.command("testFocused") { state =>
  "testOnly -- -n focused" :: state
}
commands += Command.command("testFocusedSqlite") { state =>
  "testOnly software.altitude.test.core.suites.SqliteSuiteBundle -- -n focused" :: state
}
commands += Command.command("testFocusedPostgres") { state =>
  "testOnly software.altitude.test.core.suites.PostgresSuiteBundle -- -n focused" :: state
}
commands += Command.command("testFocusedController") { state =>
  "testOnly software.altitude.test.core.suites.ControllerSuiteBundle -- -n focused" :: state
}
commands += Command.command("testSqlite") { state =>
  "testOnly software.altitude.test.core.suites.SqliteSuiteBundle" :: state
}
commands += Command.command("testPostgres") { state =>
  "testOnly software.altitude.test.core.suites.PostgresSuiteBundle" :: state
}
commands += Command.command("testUnit") { state =>
  "testOnly software.altitude.test.core.suites.UnitSuiteBundle" :: state
}
commands += Command.command("testController") { state =>
  "testOnly software.altitude.test.core.suites.ControllerSuiteBundle" :: state
}
commands += Command.command("watch") { state =>
  "~;jetty:stop;jetty:start" :: state
}

javaOptions ++= Seq( "-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005")

organization := "software.altitude"
name := "altitude"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.14"

val json4sVersion = "4.0.7"
val scalatraVersion = "2.8.4"
val jettyVersion = "9.4.20.v20190813"

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
  "org.scalatra"                %% "scalatra-scalatest"       % scalatraVersion % Test,
  "org.scalatra" %% "scalatra-scalate" % scalatraVersion,
  "org.scalatra" %% "scalatra-auth" % scalatraVersion,
  "org.scalatra.scalate"        %% "scalate-core"             % "1.10.1",

  "com.typesafe.play"           %% "play-json"                % "2.10.5",
  "org.apache.tika"              % "tika-core"                % "2.9.2",
  "org.apache.tika"              % "tika-parsers" % "2.9.2",
  "org.apache.tika"              % "tika-parser-image-module" % "2.9.2",

  "joda-time"                    % "joda-time"                % "2.12.7",
  "commons-io"                   % "commons-io"               % "2.16.1",
  "commons-codec"                % "commons-codec"            % "1.17.0",
  "commons-dbutils"              % "commons-dbutils"          % "1.8.1",

  "org.postgresql"               % "postgresql"               % "42.7.3",
  "org.xerial"                   % "sqlite-jdbc"              % "3.15.1",

  "com.google.guava"             % "guava"                    % "19.0",
  "net.codingwell"              %% "scala-guice"              % "7.0.0",
  "org.imgscalr"                 % "imgscalr-lib"             % "4.2",

  "ch.qos.logback"               % "logback-classic"          % "1.5.6" % "runtime",

  "org.mockito" % "mockito-core" % "5.11.0" % Test,

  //
  // ATTN: The old versions are required - we don't need to update them until Scalatra 3.x/Scala 3.x
  //
  "org.eclipse.jetty"            % "jetty-webapp"             % jettyVersion % "container;compile",
  "javax.servlet"                % "javax.servlet-api"        % "3.1.0" % Provided
).map(_.exclude("commons-logging", "commons-logging"))
 .map(_.exclude("org.apache.cxf", "cxf-core"))
 .map(_.exclude("org.apache.cxf", "cxf-cxf-rt-transports-http"))

enablePlugins(ScalatraPlugin)

inThisBuild(
  List(
    scalaVersion := scalaVersion.value,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)

test in assembly := {}

parallelExecution in Test := false

assemblyMergeStrategy in assembly := {
  case x if x.startsWith("META-INF") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

commands += Command.command("testFocused") { state =>
  "testOnly -- -n focused" :: state
}
commands += Command.command("testFocusedSqlite") { state =>
  "testOnly software.altitude.test.core.suites.SqliteSuite -- -n focused" :: state
}
commands += Command.command("testFocusedPostgres") { state =>
  "testOnly software.altitude.test.core.suites.PostgresSuite -- -n focused" :: state
}
commands += Command.command("testSqlite") { state =>
  "testOnly software.altitude.test.core.suites.SqliteSuite" :: state
}
commands += Command.command("testPostgres") { state =>
  "testOnly software.altitude.test.core.suites.PostgresSuite" :: state
}
commands += Command.command("watch") { state =>
  "~;jetty:stop;jetty:start" :: state
}

javaOptions ++= Seq( "-Xdebug", "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005")

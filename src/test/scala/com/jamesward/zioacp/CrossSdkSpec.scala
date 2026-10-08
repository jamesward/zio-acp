package com.jamesward.zioacp

import org.testcontainers.containers.{BindMode, GenericContainer}
import org.testcontainers.containers.output.ToStringConsumer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import java.time.Duration as JDuration

/**
 * The ACP SDKs' cross-SDK interop suite (`integration-testing/` in agentclientprotocol/java-sdk) and its raw JSON-RPC
 * conformance driver, run against the Scala interop programs (src/test/scala/interop).
 *
 * The suite needs JBang, Python, JDK 21 and the peer SDKs built from source, so it runs in a container built from
 * src/test/resources/interop/Dockerfile: the host needs only Docker. The first build takes several minutes; later
 * runs reuse Docker's layer cache. The Scala programs are this JVM's test classpath, mounted into the container.
 */
object CrossSdkSpec extends ZIOSpecDefault:

  /** The java-sdk commit whose integration-testing/ suite runs, and the kotlin-sdk commit it runs against. */
  val JavaSdkRef = "5bb2cf62d2349fb9c47ec9ba8ad0a27762713e59"
  val KotlinSdkRef = "3af219ae81f413024a385f8390540bf648f63938"

  private val resources = List(
    "Dockerfile",
    "java-proxy-opts",
    "add-scala.py",
    "scala-expectations.json",
    "scala/launch/build.sh",
    "scala/launch/agent.sh",
    "scala/launch/client.sh",
  )

  /** A proxy's CA bundle and a Maven settings.xml from the host, so the image builds behind a proxy or a mirror. */
  private val hostEnvironment: List[(String, Path)] =
    val caBundle = List("SSL_CERT_FILE", "CURL_CA_BUNDLE", "REQUESTS_CA_BUNDLE", "NODE_EXTRA_CA_CERTS")
      .flatMap(sys.env.get)
      .map(Paths.get(_))
      .find(Files.isRegularFile(_))
    val mavenSettings = Option(Paths.get(java.lang.System.getProperty("user.home"), ".m2", "settings.xml")).filter(Files.isRegularFile(_))
    caBundle.map("env/ca-bundle.crt" -> _).toList ++ mavenSettings.map("env/settings.xml" -> _).toList

  private def image: ImageFromDockerfile =
    val base = ImageFromDockerfile("zio-acp-interop", false)
    resources.foreach(r => base.withFileFromClasspath(r, s"interop/$r"))
    if hostEnvironment.isEmpty then base.withFileFromString("env/.keep", "")
    hostEnvironment.foreach((name, path) => base.withFileFromPath(name, path))
    base.withBuildArg("JAVA_SDK_REF", JavaSdkRef)
    base.withBuildArg("KOTLIN_SDK_REF", KotlinSdkRef)
    List("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY").foreach(name => sys.env.get(name).foreach(base.withBuildArg(name, _)))
    // the build reaches the network the way the host does, through a loopback proxy when there is one
    base.withBuildImageCmdModifier(_.withNetworkMode("host"))
    base

  /** Builds the image (once per run; unchanged layers come from Docker's cache) and gives its name. */
  val imageLayer: ZLayer[Any, Throwable, DockerImageName] =
    ZLayer.fromZIO(ZIO.attemptBlocking(DockerImageName.parse(image.get())))

  private val classpath: List[String] =
    Option(java.lang.System.getProperty("interop.classpath")).toList.flatMap(_.split(java.io.File.pathSeparator).toList)

  /** Runs `command` in a container with the Scala programs mounted, and gives its exit code and output. */
  def run(command: String, env: Map[String, String] = Map.empty): ZIO[DockerImageName, Throwable, (Long, String)] =
    ZIO.serviceWithZIO[DockerImageName]: imageName =>
      ZIO.attemptBlocking:
        val classpathFile = Files.createTempFile("interop-classpath", ".txt")
        Files.writeString(classpathFile, classpath.mkString(":"))
        val output = ToStringConsumer()
        val container = GenericContainer(imageName)
        // the with… builders return testcontainers' self type, which Scala infers as Nothing; keep them in statement
        // position so that no cast is emitted
        classpath.foreach(entry => container.withFileSystemBind(entry, entry, BindMode.READ_ONLY))
        container.withFileSystemBind(classpathFile.toString, "/interop/classpath.txt", BindMode.READ_ONLY)
        env.foreach((k, v) => container.withEnv(k, v))
        container.withCommand("bash", "-c", command)
        container.withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(JDuration.ofMinutes(15)))
        container.withLogConsumer(output)
        try
          try container.start()
          catch case _: org.testcontainers.containers.ContainerLaunchException => ()
          (container.getContainerInfo.getState.getExitCodeLong.longValue, output.toUtf8String)
        finally
          container.stop()
          Files.deleteIfExists(classpathFile)

  /** One generated cell of the step catalogue; its runner checks the steps and the expected failures. */
  def cell(name: String): Spec[DockerImageName, Throwable] =
    test(name):
      for
        (exit, output) <- run(s"jbang RunScenario.java $name --prepared --peer kotlin-sdk=$KotlinSdkRef")
        _ <- ZIO.logInfo(s"$name:\n$output").when(exit != 0L)
      yield assertTrue(exit == 0L)

  private def result(output: String): Option[String] = output.linesIterator.find(_.startsWith("RESULT "))

  private def failures(output: String): List[String] = output.linesIterator.filter(_.matches("^STEP \\S+ FAIL.*")).toList

  /** The client steps the raw agent serves (raw.py AGENT_ROLE_STEPS, stdio). */
  val rawAgentSteps = List(
    "init.initialize", "session.new", "session.load", "update.agent_message_chunk", "update.unknown", "enum.tool_call",
    "enum.plan", "enum.stop", "enum.audience", "config.grouped", "perm.selected", "fs.write", "error.method-not-found",
    "mode.set", "auth.authenticate", "auth.logout", "session.delete", "stdio.eof-exit", "conn.close",
  )

  def spec = suite("CrossSdkSpec")(
    suite("step catalogue")(
      cell("x-java-scala-stdio"),
      cell("x-scala-java-stdio"),
      cell("x-kotlin-scala-stdio"),
      cell("x-scala-kotlin-stdio"),
    ),
    suite("raw JSON-RPC conformance")(
      test("the raw driver probes the Scala agent"):
        for
          (_, output) <- run(
                           "python3 -u programs/raw/raw.py client --transport stdio",
                           Map("AGENT_CMD" -> "programs/scala/launch/agent.sh --transport stdio"),
                         )
          _ <- ZIO.logInfo(output).when(failures(output).nonEmpty)
        yield assertTrue(result(output).exists(_.contains(" fail=0 ")), failures(output).isEmpty)
      ,
      test("the raw agent probes the Scala client"):
        for
          (_, output) <- run(
                           "programs/scala/launch/client.sh --transport stdio",
                           Map(
                             "STEPS" -> rawAgentSteps.mkString(","),
                             "AGENT_CMD" -> "python3 -u /java-sdk/integration-testing/programs/raw/raw.py agent --transport stdio",
                           ),
                         )
          _ <- ZIO.logInfo(output).when(failures(output).nonEmpty)
        yield assertTrue(
          result(output).exists(_.contains(" fail=0 ")),
          failures(output).isEmpty,
          output.linesIterator.exists(_.startsWith("STEP agent.raw.")),
        )
      ,
    ),
  ).provideShared(imageLayer) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.minutes)

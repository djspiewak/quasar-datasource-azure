import github.GithubPlugin._

import scala.Predef._
import quasar.blobstore.project._

import java.lang.{Integer, String, Throwable}
import scala.{Boolean, List, Predef, None, Some, StringContext, sys, Unit}, Predef.{any2ArrowAssoc, assert, augmentString}
import scala.collection.Seq
import scala.collection.immutable.Map

import sbt._, Keys._
import sbt.std.Transform.DummyTaskMap
import sbt.TestFrameworks.Specs2
import sbtrelease._, ReleaseStateTransformations._, Utilities._

val BothScopes = "test->test;compile->compile"

// Exclusive execution settings
lazy val ExclusiveTests = config("exclusive") extend Test

def exclusiveTasks(tasks: Scoped*) =
  tasks.flatMap(inTask(_)(tags := Seq((ExclusiveTest, 1))))

lazy val buildSettings = Seq(

  // NB: Some warts are disabled in specific projects. Here’s why:
  //   • AsInstanceOf   – wartremover/wartremover#266
  //   • others         – simply need to be reviewed & fixed
  wartremoverWarnings in (Compile, compile) --= Seq(
    Wart.Any,                   // - see wartremover/wartremover#263
    Wart.PublicInference,       // - creates many compile errors when enabled - needs to be enabled incrementally
    Wart.ImplicitParameter,     // - creates many compile errors when enabled - needs to be enabled incrementally
    Wart.ImplicitConversion,    // - see mpilquist/simulacrum#35
    Wart.Nothing),              // - see wartremover/wartremover#263
  // Normal tests exclude those tagged in Specs2 with 'exclusive'.
  testOptions in Test := Seq(Tests.Argument(Specs2, "exclude", "exclusive", "showtimes")),
  // Exclusive tests include only those tagged with 'exclusive'.
  testOptions in ExclusiveTests := Seq(Tests.Argument(Specs2, "include", "exclusive", "showtimes")),

  console := { (console in Test).value }, // console alias test:console
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case x => MergeStrategy.first
  })

val targetSettings = Seq(
  target := {
    import java.io.File

    val root = (baseDirectory in ThisBuild).value.getAbsolutePath
    val ours = baseDirectory.value.getAbsolutePath

    new File(root + File.separator + ".targets" + File.separator + ours.substring(root.length))
  }
)

// In Travis, the processor count is reported as 32, but only ~2 cores are
// actually available to run.
concurrentRestrictions in Global := {
  val maxTasks = 2
  if (isTravisBuild.value)
    // Recreate the default rules with the task limit hard-coded:
    Seq(Tags.limitAll(maxTasks), Tags.limit(Tags.ForkedTestGroup, 1))
  else
    (concurrentRestrictions in Global).value
}

// Tasks tagged with `ExclusiveTest` should be run exclusively.
concurrentRestrictions in Global += Tags.exclusive(ExclusiveTest)

lazy val publishSettings = Seq(
  performMavenCentralSync := false,
  organizationName := "SlamData Inc.",
  organizationHomepage := Some(url("http://quasar-analytics.org")),
  homepage := Some(url("https://github.com/slamdata/quasar-blobstore")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/slamdata/quasar-blobstore"),
      "scm:git@github.com:slamdata/quasar-blobstore.git"
    )
  ))

lazy val assemblySettings = Seq(
  test in assembly := {},

  assemblyExcludedJars in assembly := {
    val cp = (fullClasspath in assembly).value
    cp filter { attributedFile =>
      val file = attributedFile.data

      val excludeByName: Boolean = file.getName.matches("""scala-library-2\.12\.\d+\.jar""")
      val excludeByPath: Boolean = file.getPath.contains("org/typelevel")

      excludeByName && excludeByPath
    }
  }
)

// Build and publish a project, excluding its tests.
lazy val commonSettings = buildSettings ++ publishSettings ++ assemblySettings

// not doing this causes NoSuchMethodErrors when using coursier
lazy val excludeTypelevelScalaLibrary =
  Seq(excludeDependencies += "org.typelevel" % "scala-library")

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true
)

lazy val githubReleaseSettings =
  githubSettings ++ Seq(
    GithubKeys.assets := Seq(assembly.value),
    GithubKeys.repoSlug := "slamdata/quasar-blobstore",
    GithubKeys.releaseName := "quasar " + GithubKeys.tag.value,
    releaseVersionFile := file("version.sbt"),
    releaseUseGlobalVersion := true,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      pushChanges)
  )

lazy val isCIBuild = settingKey[Boolean]("True when building in any automated environment (e.g. Travis)")
lazy val isIsolatedEnv = settingKey[Boolean]("True if running in an isolated environment")
lazy val exclusiveTestTag = settingKey[String]("Tag for exclusive execution tests")

lazy val sideEffectTestFSConfig = taskKey[Unit]("Rewrite the JVM environment to contain the filesystem classpath information for integration tests")

def createBackendEntry(childPath: Seq[File], parentPath: Seq[File]): Seq[File] =
  (childPath.toSet -- parentPath.toSet).toSeq

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(aggregate in assembly := false)
  .settings(excludeTypelevelScalaLibrary)
  .aggregate(datasource)
  .enablePlugins(AutomateHeaderPlugin)

// common components

// Quasar needs to know where the DatasourceModule for the connector is
lazy val manifestSettings =
  packageOptions in (Compile, packageBin) +=
    Package.ManifestAttributes("Datasource-Module" -> "quasar.physical.blobstore.azure.AzureDatasourceModule$")

val azureVersion = "10.1.0"
val rxjavaVersion = "2.2.2"
val catsEffectVersion = "1.0.0"
val fs2Version = "1.0.0"
val nettyVersion = "4.1.28.Final"
val quasarVersion = IO.read(file("./quasar-version")).trim
val qdataVersion = IO.read(file("./qdata-version")).trim
val shimsVersion = "1.2.1"
val slf4jVersion = "1.7.25"
val specsVersion = "4.1.2"

// direct as well as transitive deps need to be in sync with quasar's deps
lazy val datasourceCoreDeps = Seq(
  "com.codecommit"         %% "shims"               % shimsVersion,
  "com.microsoft.azure"    %  "azure-storage-blob"  % azureVersion,
  "com.slamdata"           %% "qdata-json"          % qdataVersion,
  // netty-all isn't strictly necessary but takes advantage of native libs.
  // Azure doesn't pull in libs like netty-transport-native-kqueue,
  // netty-transport-native-unix-common and netty-transport-native-epoll.
  // Keep nettyVersion in sync with the version that Azure pulls in.
  "io.netty"               %  "netty-all"           % nettyVersion,
  "io.reactivex.rxjava2"   %  "rxjava"              % rxjavaVersion,
  "org.typelevel"          %% "cats-effect"         % catsEffectVersion,
  "org.slf4j"              %  "slf4j-log4j12"       % slf4jVersion % Test,
  "org.specs2"             %% "specs2-core"         % specsVersion % Test,
  "org.specs2"             %% "specs2-scalaz"       % specsVersion % Test,
  "org.specs2"             %% "specs2-scalacheck"   % specsVersion % Test
)

lazy val datasource = project
  .settings(name := "quasar-blobstore")
  .settings(commonSettings)
  .settings(targetSettings)
  .settings(resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public"))
  .settings(
    // we need to separate quasar out from the datasource dependencies,
    // to keep from packaging it and its dependencies.
    libraryDependencies ++= datasourceCoreDeps ++ Seq(
      "com.slamdata" %% "quasar-api-internal"        % quasarVersion,
      "com.slamdata" %% "quasar-api-internal"        % quasarVersion % Test classifier "tests",
      "com.slamdata" %% "quasar-foundation-internal" % quasarVersion,
      "com.slamdata" %% "quasar-foundation-internal" % quasarVersion % Test classifier "tests",
      "com.slamdata" %% "quasar-connector-internal"  % quasarVersion,
      "com.slamdata" %% "quasar-connector-internal"  % quasarVersion % Test classifier "tests",
    ),
    wartremoverWarnings in (Compile, compile) --= Seq(
      Wart.AsInstanceOf,
      Wart.Equals,
      Wart.Overloading))
  .settings(githubReleaseSettings)
  .settings(excludeTypelevelScalaLibrary)
  .settings(AssembleDatasource.setAssemblyKey(datasourceCoreDeps))
  .settings(manifestSettings)
  .enablePlugins(AutomateHeaderPlugin)

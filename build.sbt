/** okay-inlay: programs a transformer runs exactly (specs/dsl.md) — one program, many handlers: the reference
 * interpreter, the compiler into weights and their forward pass; later the decode port, MLX, a real model's block */
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")

lazy val core = (project in file("core"))
  .settings(name := "okay-inlay", libraryDependencies += "org.scalameta" %% "munit" % "1.2.3" % Test)

lazy val root = (project in file(".")).aggregate(core, mlx).settings(publish / skip := true)

/** mlx-scala's native libraries (its native-lib/build.sh): MLX_SCALA_LIB, else ../mlx-scala/native-lib/out/lib */
val nativeLib = sys.env.get("MLX_SCALA_LIB").map(file).getOrElse(file("../mlx-scala/native-lib/out/lib")).getCanonicalFile

/** the compiled weights on MLX (Apple's GPU), through mlx-scala */
lazy val mlx = (project in file("mlx")).dependsOn(core % "compile->compile;test->test")
  .settings(name := "okay-inlay-mlx",
    resolvers += "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
    libraryDependencies ++= Seq("io.github.sergey-scherbina" %% "mlx-scala" % "0.1.0",
      "io.github.sergey-scherbina" %% "mlx-scala-llm" % "0.1.0",
      "org.scalameta" %% "munit" % "1.2.3" % Test),
    Test / fork := true,
    javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED", s"-Dokm.lib=$nativeLib/libokm.dylib", "-Xss16m"))

/** okay-inlay: programs a transformer runs exactly (specs/dsl.md) — one program, many handlers: the reference
 * interpreter, the compiler into weights and their forward pass; later the decode port, MLX, a real model's block */
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")

lazy val core = (project in file("core"))
  .settings(name := "okay-inlay", libraryDependencies += "org.scalameta" %% "munit" % "1.2.3" % Test)

lazy val root = (project in file(".")).aggregate(core).settings(publish / skip := true)

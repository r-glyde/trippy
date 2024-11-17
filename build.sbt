ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.4"

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  .settings(
    name := "trippy",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.5",
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )

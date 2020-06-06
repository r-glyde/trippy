name := "trippy"
scalaVersion := "2.13.2"
version := "0.1.0-SNAPSHOT"

scalacOptions ++= Seq(
  "-deprecation"
)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.1.3",
  "org.typelevel" %% "cats-core"   % "2.1.1",
  "org.scalatest" %% "scalatest"   % "3.1.2" % Test
)

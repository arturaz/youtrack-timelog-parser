scalaVersion := "2.12.8"

val scalazVersion = "7.2.9"

version := "1.2.1"

libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % scalazVersion,
  "org.scalaz" %% "scalaz-effect" % scalazVersion,
  "org.scalaz.stream" %% "scalaz-stream" % "0.8.6",
  "org.specs2" %% "specs2-core" % "4.3.4" % "test"
)

scalacOptions in Test ++= Seq("-Yrangepos")

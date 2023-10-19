// addSbtPlugin("org.typelevel" % "sbt-fs2-grpc" % "2.7.9")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
// Yes it's weird to do the following, but it's what is mandated by the scalapb documentation
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.8"

addSbtPlugin("org.wartremover"        %  "sbt-wartremover"      % "3.1.4")
addSbtPlugin("com.eed3si9n"           %  "sbt-assembly"         % "2.1.3")
addSbtPlugin("com.github.sbt"         %% "sbt-native-packager"  % "1.9.16")
addSbtPlugin("pl.project13.scala"     %  "sbt-jmh"              % "0.4.0")
addSbtPlugin("org.scalameta"          %  "sbt-scalafmt"         % "2.4.0")
addSbtPlugin("io.spray"               %  "sbt-revolver"         % "0.9.1")
addSbtPlugin("com.github.sbt"         %  "sbt-ghpages"          % "0.8.0")
addSbtPlugin("com.eed3si9n"           % "sbt-buildinfo"         % "0.11.0")
/*
addSbtPlugin("com.typesafe.sbt"       %  "sbt-site"             % "1.4.1")
addSbtPlugin("com.typesafe.sbt"       %  "sbt-license-report"   % "1.2.0")
addSbtPlugin("com.github.tkawachi"    %  "sbt-repeat"           % "0.1.0")
addSbtPlugin("com.jsuereth"           %  "sbt-pgp"              % "2.0.1")
addSbtPlugin("org.xerial.sbt"         %  "sbt-sonatype"         % "2.6")
*/

addDependencyTreePlugin

# Source this to put the pinned JDK 21 + Maven on PATH for this repo.
#   source env.sh && mvn -B clean verify
export JAVA_HOME="$HOME/tools/jdk-21.0.13+9"
export M2_HOME="$HOME/tools/apache-maven-3.9.9"
export PATH="$JAVA_HOME/bin:$M2_HOME/bin:$PATH"

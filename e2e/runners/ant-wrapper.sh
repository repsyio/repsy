#!/bin/sh
# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# The `ant` command of the e2e maven runner image (RPS-2530): Maven Central has no Ant distribution, so ANT_HOME
# holds only ant.jar and ant-launcher.jar and this stands in for the distribution's bin/ant. It finds ANT_HOME from
# its own location (clientEnv() does not pass ANT_HOME), honours JAVA_HOME, ANT_OPTS and ANT_ARGS and runs the
# launcher, which is what the real script ends up doing.
ANT_HOME="$(cd "$(dirname "$0")/.." && pwd -P)"
JAVACMD=java
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVACMD="${JAVA_HOME}/bin/java"
fi
# ANT_OPTS and ANT_ARGS are word-split on purpose, like the real script does.
# shellcheck disable=SC2086
exec "${JAVACMD}" ${ANT_OPTS:-} -classpath "${ANT_HOME}/lib/ant-launcher.jar" \
  "-Dant.home=${ANT_HOME}" "-Dant.library.dir=${ANT_HOME}/lib" \
  org.apache.tools.ant.launch.Launcher ${ANT_ARGS:-} "$@"

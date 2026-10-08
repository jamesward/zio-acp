# The ACP cross-SDK interop suite (integration-testing/ in agentclientprotocol/java-sdk) with zio-acp added as the
# language "scala". Every peer is built here, so CrossSdkSpec only runs prepared scenarios; the Scala programs come
# from the test classpath the spec mounts at run time (/interop/classpath.txt).
FROM eclipse-temurin:25-jdk

ARG JAVA_SDK_REF
ARG KOTLIN_SDK_REF
ARG JBANG_VERSION=0.135.1
# Set by CrossSdkSpec only when the host has them: a proxy, its CA bundle (env/ca-bundle.crt), a Maven settings.xml
# (env/settings.xml) and Gradle init scripts (env/gradle-init.d/), so the build works behind a TLS-intercepting proxy
# or with the host's repository mirrors.
ARG HTTPS_PROXY
ARG HTTP_PROXY
ARG NO_PROXY

COPY env/ /tmp/env/
COPY java-proxy-opts /usr/local/bin/java-proxy-opts
COPY build-env /usr/local/bin/build-env

# JDK 21 is the Kotlin SDK's Gradle toolchain; JDK 25 (the image's) runs everything else. Both read the system
# truststore, which holds the host's CA bundle when one was given.
RUN chmod +x /usr/local/bin/java-proxy-opts /usr/local/bin/build-env \
 && apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends git curl unzip python3 openjdk-21-jdk-headless ca-certificates-java > /dev/null \
 && rm -rf /var/lib/apt/lists/* \
 && if [ -f /tmp/env/ca-bundle.crt ]; then \
      awk 'BEGIN{n=0} /BEGIN CERTIFICATE/{n++} n{print > ("/usr/local/share/ca-certificates/host-ca-" n ".crt")}' /tmp/env/ca-bundle.crt; \
    fi \
 && update-ca-certificates > /dev/null 2>&1 \
 && ln -sf /etc/ssl/certs/java/cacerts "$JAVA_HOME/lib/security/cacerts" \
 && if [ -f /tmp/env/settings.xml ]; then mkdir -p /root/.m2 && cp /tmp/env/settings.xml /root/.m2/settings.xml; fi \
 && if [ -d /tmp/env/gradle-init.d ]; then mkdir -p /root/.gradle && cp -r /tmp/env/gradle-init.d /root/.gradle/init.d; fi \
 && curl -fsSL -o /tmp/jbang.zip "https://github.com/jbangdev/jbang/releases/download/v${JBANG_VERSION}/jbang-${JBANG_VERSION}.zip" \
 && unzip -q /tmp/jbang.zip -d /opt && ln -s "/opt/jbang-${JBANG_VERSION}/bin/jbang" /usr/local/bin/jbang && rm /tmp/jbang.zip

ENV JAVA_HOME_21_X64=/usr/lib/jvm/java-21-openjdk-amd64

RUN git clone -q https://github.com/agentclientprotocol/java-sdk.git /java-sdk \
 && git -C /java-sdk checkout -q "$JAVA_SDK_REF"

WORKDIR /java-sdk/integration-testing

# The slow parts, cached as long as the refs don't change: install the SDK and build the Java programs, then clone
# and build the Kotlin SDK and its programs. The builds run on JDK 21, which the Kotlin SDK's Gradle needs (it doesn't
# run on JDK 25); the programs run on JDK 25. A failed step prints its build logs.
COPY show-logs /usr/local/bin/show-logs

RUN . build-env \
 && chmod +x /usr/local/bin/show-logs \
 && { scripts/run-all.sh --prepare-only --only x-java-java-stdio && jbang build RunScenario.java > /dev/null || show-logs; }

RUN . build-env \
 && { jbang RunScenario.java --prepare x-java-kotlin-stdio --peer "kotlin-sdk=$KOTLIN_SDK_REF" || show-logs; }

COPY scala/ programs/scala/
COPY scala-expectations.json expectations/scala.json
COPY add-scala.py /tmp/add-scala.py

RUN . build-env \
 && chmod +x programs/scala/launch/*.sh \
 && python3 /tmp/add-scala.py matrix.json \
 && jbang GenConfigs.java > /dev/null \
 && { jbang RunScenario.java --prepare x-java-scala-stdio x-scala-java-stdio x-kotlin-scala-stdio x-scala-kotlin-stdio \
      --peer "kotlin-sdk=$KOTLIN_SDK_REF" || show-logs; }

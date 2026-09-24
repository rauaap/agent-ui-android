FROM fedora:44

ARG ANDROID_CMDLINE_TOOLS_VERSION=14742923
ARG ANDROID_API=36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG GRADLE_VERSION=9.8.0

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_HOME=/opt/gradle/gradle-${GRADLE_VERSION}
ENV PATH=${GRADLE_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN dnf install -y --setopt=install_weak_deps=False \
        findutils \
        java-25-openjdk-devel \
        unzip \
        wget \
        which \
    && dnf clean all

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /opt/gradle /gradle-cache /work \
    && chmod 0777 /gradle-cache /work \
    && wget -O /tmp/android-commandline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/android-commandline-tools.zip -d /tmp/android-commandline-tools \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools/latest" \
    && mv /tmp/android-commandline-tools/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/" \
    && wget -O /tmp/gradle.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm -rf /tmp/android-commandline-tools.zip /tmp/android-commandline-tools /tmp/gradle.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
    && yes | sdkmanager --sdk_root="${ANDROID_HOME}" \
        "platform-tools" \
        "platforms;android-${ANDROID_API}" \
        "build-tools;${ANDROID_BUILD_TOOLS}"

WORKDIR /work

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl wget ca-certificates gnupg apt-transport-https \
    fuse3 \
    openjdk-21-jdk-headless \
    unzip \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://rclone.org/install.sh | bash

RUN echo "user_allow_other" >> /etc/fuse.conf

RUN wget -q https://services.gradle.org/distributions/gradle-8.8-bin.zip -O /tmp/gradle.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && rm /tmp/gradle.zip
ENV PATH="/opt/gradle-8.8/bin:${PATH}"

WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies --no-daemon -q 2>/dev/null || true

COPY src ./src
RUN gradle shadowJar --no-daemon -q

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]

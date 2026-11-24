FROM node:22-bookworm as base

USER root

RUN mkdir -p /tmp/.X11-unix \
    && chmod 1777 /tmp \
    && chmod 1777 /tmp/.X11-unix

RUN echo "=== TMP PERMISSIONS ===" \
    && stat -c '%a %A %U:%G %n' /tmp \
    && echo "=== TMP MOUNT ===" \
    && findmnt /tmp || true

FROM base as interim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        file \
        git \
        libayatana-appindicator3-dev \
        librsvg2-dev \
        libssl-dev \
        libwebkit2gtk-4.1-dev \
        libxdo-dev \
        patchelf \
        pkg-config \
        python3 \
        python3-pip \
        python3-venv \
        sudo \
        unzip \
        wget \
        zip \
    && rm -rf /var/lib/apt/lists/* \
    && echo "node ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/node

RUN echo "=== TMP PERMISSIONS ===" \
    && stat -c '%a %A %U:%G %n' /tmp \
    && echo "=== TMP MOUNT ===" \
    && findmnt /tmp || true

FROM interim as final
RUN chmod 1777 /tmp

USER node

ENV PATH="/home/node/.cargo/bin:${PATH}"
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal \
    && rustup target add wasm32-unknown-unknown

RUN stat -c '%a %A %U:%G %n' /tmp \
    && test "$(stat -c '%a' /tmp)" = "1777"
    
FROM ubuntu:jammy

RUN apt update && apt upgrade -y
RUN apt install -y openjdk-18-jre-headless

COPY . /app
WORKDIR /app

RUN ./gradlew distTar --no-daemon

FROM ubuntu:jammy

RUN apt update && apt upgrade -y
RUN apt install -y openjdk-18-jre-headless ffmpeg socat

WORKDIR /app

COPY --from=0 /app/build/distributions/traktor-streaming-proxy.tar /app
COPY --from=0 /app/cert/*.jks /app/cert/keystore.jks
RUN tar xf traktor-streaming-proxy.tar --strip-components=1 && rm traktor-streaming-proxy.tar

EXPOSE 8443 5588 5589

# librespot binds its OAuth callback to 127.0.0.1:5588, which a published port cannot reach
# because Docker forwards to the container interface. Bridge that interface to the loopback
# listener so the browser redirect at the end of the Spotify login completes.
CMD socat TCP-LISTEN:5588,bind=$(hostname -i),fork,reuseaddr TCP:127.0.0.1:5588 & exec bin/traktor-streaming-proxy

# Imagem do ms-sdui-composer.
#
# Tres estagios: compilacao, extracao das camadas do jar e runtime. A extracao existe para que o
# cache de camadas do Docker acompanhe o ritmo de mudanca de cada parte — as dependencias mudam
# raramente e o codigo da aplicacao muda a cada build, entao separa-las evita reenviar ~50 MB de
# bibliotecas a cada alteracao de uma linha de Kotlin.

# ---------------------------------------------------------------------------------------------
# 1. Compilacao
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Wrapper e descritores primeiro: enquanto o grafo de dependencias nao muda, a resolucao abaixo
# reaproveita o cache mesmo que o codigo tenha mudado.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts ./
COPY build-logic build-logic
COPY sdui-contract/build.gradle.kts sdui-contract/
COPY sdui-core/build.gradle.kts sdui-core/
COPY sdui-app/build.gradle.kts sdui-app/
COPY sdui-bootstrap/build.gradle.kts sdui-bootstrap/
COPY sdui-integration-test/build.gradle.kts sdui-integration-test/

RUN chmod +x gradlew && ./gradlew --no-daemon :sdui-bootstrap:dependencies --quiet || true

COPY sdui-contract sdui-contract
COPY sdui-core sdui-core
COPY sdui-app sdui-app
COPY sdui-bootstrap sdui-bootstrap
COPY sdui-integration-test sdui-integration-test

# Os testes rodam no pipeline, nao aqui: repeti-los a cada build de imagem atrasa o deploy sem
# acrescentar garantia, ja que a imagem e construida a partir de um commit que o CI aprovou.
RUN ./gradlew --no-daemon :sdui-bootstrap:bootJar -x test

# ---------------------------------------------------------------------------------------------
# 2. Extracao das camadas
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS layers

WORKDIR /extract
COPY --from=build /workspace/sdui-bootstrap/build/libs/sdui-bootstrap.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination .

# ---------------------------------------------------------------------------------------------
# 3. Runtime
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# curl serve so ao HEALTHCHECK; sem ele o container sobe mas nunca e declarado saudavel.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Processo sem privilegio: o servico nao escreve em disco nem precisa de porta baixa.
RUN groupadd --system sdui && useradd --system --gid sdui --home /app sdui
WORKDIR /app

# Da camada mais estavel para a mais volatil, para o cache aproveitar o maximo de cada rebuild.
COPY --from=layers --chown=sdui:sdui /extract/dependencies/ ./
COPY --from=layers --chown=sdui:sdui /extract/spring-boot-loader/ ./
COPY --from=layers --chown=sdui:sdui /extract/snapshot-dependencies/ ./
COPY --from=layers --chown=sdui:sdui /extract/application/ ./

USER sdui
EXPOSE 8080

# MaxRAMPercentage faz a heap acompanhar o limite do container em vez do total da maquina.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

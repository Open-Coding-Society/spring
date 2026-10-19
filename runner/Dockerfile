FROM eclipse-temurin:21-jdk

WORKDIR /runner

COPY Runner.java .

# create unprivileged user
RUN useradd --create-home --shell /usr/bin/nologin runner

USER runner

RUN javac Runner.java

EXPOSE 8592

CMD ["java", "Runner"]

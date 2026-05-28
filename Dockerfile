FROM eclipse-temurin:21
WORKDIR /app

# Built from outside docker; see gradlew distTar.
ADD build/distributions/kira-2.1.1.tar /app
ENTRYPOINT /app/kira-2.1.1/bin/kira

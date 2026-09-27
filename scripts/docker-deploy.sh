cd ./orknux-ui
docker compose up dev -d
cd ..
docker compose up -d
./mvnw spring-boot:run -pl app -am
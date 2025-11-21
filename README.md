# SSHtunnel (Spring Boot variant)

This is a Spring Boot version of the `Sshtunnel` application that manages an SSH tunnel and subscribes to MQTT topics using Eclipse Paho.

Build:

```bash
cd SSHtunnel
mvn package
```

Run:

```bash
mvn spring-boot:run
```

Configuration is in `src/main/resources/application.properties` (or override via `--spring-boot.run.arguments` or environment variables).

# CivMod

NeoForge mod for Minecraft 26.3 (Java 25).

## Build

```bash
./gradlew build        # JAR in build/libs/
./gradlew runClient    # dev client
./gradlew runServer    # dev server
./gradlew runData      # data generation into src/generated/resources
```

## Layout

- `src/main/java/com/gilfort/civmod/CivMod.java` – common entry point, registers everything
- `client/CivModClient.java` – client-only entry point (config screen, renderers)
- `registry/` – deferred registers (blocks, items, creative tabs)
- `config/CivModConfig.java` – server config (`civmod-server.toml`)
- `src/main/templates/META-INF/neoforge.mods.toml` – mod metadata, expanded from `gradle.properties`

## License

GPL-3.0, see `LICENSE`.

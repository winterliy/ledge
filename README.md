# Ledge

Ledge is a shared Minecraft map project with two runtime adapters:

- `common`: platform-independent map data and behavior.
- `glap1`: Paper/GLAP1 server plugin entry point.
- `fabric`: Fabric mod entry point.

Develop shared behavior in `common`. Keep platform API calls inside `glap1` or `fabric`.

## Requirements

- JDK 25
- Gradle Wrapper (included)
- Minecraft 26.3 for the Fabric development client

## Build all artifacts

```sh
./gradlew build
```

The server plugin JAR is in `glap1/build/libs/`; the Fabric mod JAR is in `fabric/build/libs/`.

Run the Fabric development client with `./gradlew :fabric:runClient`.

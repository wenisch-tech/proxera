# Native Build (GraalVM)

This page describes the supported native-image path for Proxera and the native-specific constraints found while aligning it with the Kairos native work.

## Why Native

- Lower startup time
- Lower runtime overhead in many workloads
- Single executable deployment on Windows (`proxera.exe`)

## Prerequisites

### Windows

- GraalVM-compatible JDK with `native-image`
- Visual Studio 2022 Build Tools (C++ toolchain + Windows SDK)
- Maven 3.8+

This repository includes a helper script that prepares the Windows toolchain environment:

- [scripts/build-native-windows.ps1](../scripts/build-native-windows.ps1)

## Build Native Executable

### Cross-platform (if your environment is already configured)

```bash
mvn -Pnative -DskipTests package
```

The `native` Maven profile runs Spring AOT processing and then `native-maven-plugin` `compile-no-fork`.
It also keeps the native-image runtime initialization workaround for `sun.security.util.Password$ConsoleHolder`.

If `JAVA_HOME` points to a regular Temurin/OpenJDK installation, this command will fail because `native-image` is not present.
In that case use the Docker-based native build path below.

### Windows (recommended)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-native-windows.ps1
```

## Build Outputs

After a successful build, artifacts are written to `target/`.

Main executable on Windows:

- `target/proxera.exe`

The build may also emit required runtime DLLs beside the executable.

## Native Container Image

Native container builds are defined in `Dockerfile-native` and target Linux `amd64` in CI.
The runtime stage copies all artifacts emitted in `target/` (binary and shared libraries) and bundles required system libraries discovered via `ldd` into `/app/system-libs` (for example `libz.so.1`) to remain compatible with future GraalVM outputs.
The container runs as non-root and pre-creates a writable `/app/data` directory so embedded H2 can start in development mode.

```bash
docker build -f Dockerfile-native -t proxera:native .
docker run --rm -p 8080:8080 proxera:native
```

In GitHub Actions, the native image pipeline is intentionally non-blocking while production validation is in progress, and the published experimental tags use the `native-distroless-*` naming already used by CI.

## Hibernate Enhancement

The native Maven profile runs Hibernate bytecode enhancement during `process-classes`.
This matches the Kairos native hardening and avoids native-only lazy-loading/proxy failures around JPA associations such as `Route.agent`, `Route.domains`, and `RegistrationToken.agent`.

Keep these settings in the native profile:

- `enableLazyInitialization=true`
- `enableDirtyTracking=true`
- `enableAssociationManagement=true`
- `failOnError=true`

If a future Hibernate upgrade changes the enhancer behavior, validate both admin route/detail pages and tunnel registration against PostgreSQL before promoting a native image.

## Thymeleaf And SpEL Findings

The admin templates were reviewed for native-sensitive SpEL patterns.
Current server-side templates use property-style access for model data and Thymeleaf helper objects for list/date formatting; no direct Java calls such as `.isBlank()`, `.contains(...)`, `.name()`, or collection projection checks are required in the templates.

Native still needs explicit reflection hints because Thymeleaf resolves template model properties at runtime.
[NativeRuntimeHintsConfig](../src/main/java/tech/wenisch/proxera/config/NativeRuntimeHintsConfig.java) registers the model types used by the admin/detail panels:

- JPA/template models: `Agent`, `AgentStatus`, `Route`, `RouteDomain`, `AccessLog`, `ApiKey`, `Settings`, `User`, `IngressSpec`, `RegistrationToken`
- Tunnel/message payload records used through Jackson: `TunnelFrame`, `RequestPayload`, `ResponsePayload`, `TopologyEvent`, `WsRelayMessage`, `ValidationResult`
- Thymeleaf helper objects: `#lists`, `#numbers`, `#strings`, `#temporals`

When adding templates, prefer property access (`${route.targetDisplay}`) over Java method calls (`${route.getTargetDisplay()}`), and prefer helper expressions (`#lists.isEmpty(items)`, `#strings.contains(value, part)`) over invoking methods on application objects.

## Kubernetes Ingress Management

Native deployments use a small in-cluster Kubernetes REST client for ingress management instead of the Fabric8 client.
The client reads the mounted service account token, namespace, and CA certificate from `/var/run/secrets/kubernetes.io/serviceaccount`, then calls the `networking.k8s.io/v1` Ingress API directly.

This avoids Fabric8 native-image initialization failures where the pod can detect Kubernetes, but the admin topology cannot list or create ingresses because the Kubernetes client fails during runtime initialization.

## Run Native Executable

```powershell
.\target\proxera.exe
```

Default endpoints:

- Admin UI: `http://localhost:8080/admin`
- Health: `http://localhost:8080/actuator/health`

## Smoke Test Checklist

1. Start native executable.
2. Verify health endpoint returns HTTP 200.
3. Verify admin login page is reachable.
4. Stop process gracefully.

For Kubernetes deployments, keep health probe groups enabled explicitly with
`management.endpoint.health.probes.enabled=true`.
Native images can otherwise start successfully while `/actuator/health/readiness` returns 404, which leaves the Helm deployment stuck in `Progressing`.

## Flyway And Persistence Guidance

Another native-specific failure mode in Kairos only showed up when starting against an existing persisted database, not on first boot.

The problem pattern was:

- the native image could open the database successfully
- Flyway then failed validation because Java-based migrations already recorded in `flyway_schema_history` were not being discovered by native classpath scanning
- startup aborted even though the same database worked in the JVM build

Proxera currently ships SQL migrations only under `src/main/resources/db/migration`, so this specific runtime bug is not active here today.
The repository is still hardened against it:

- any future Java-based Flyway migration must be added explicitly in [src/main/java/tech/wenisch/proxera/config/FlywayMigrationConfig.java](../src/main/java/tech/wenisch/proxera/config/FlywayMigrationConfig.java)
- [src/test/java/tech/wenisch/proxera/config/FlywayMigrationConfigTest.java](../src/test/java/tech/wenisch/proxera/config/FlywayMigrationConfigTest.java) fails if a Java migration is added without registration
- migration changes must be validated against both empty and already-initialized databases

Recommended validation flow after Flyway changes:

```bash
mvn -B -DskipTests package
docker build -f Dockerfile-native -t proxera:native .
```

Then verify:

1. Native startup on a clean database.
2. Native startup against an existing database directory or PostgreSQL schema initialized by the JVM build or a previous release.

This matters for persisted H2 data directories and for Helm/PostgreSQL deployments because `flyway_schema_history` survives restarts and upgrades.

## Notes

- Native-safe Thymeleaf matters here. Avoid direct Java method calls from templates, and keep any Thymeleaf helper objects used by templates registered in [src/main/java/tech/wenisch/proxera/config/NativeRuntimeHintsConfig.java](../src/main/java/tech/wenisch/proxera/config/NativeRuntimeHintsConfig.java).
- Native build logs can include many reachability warnings from optional dependency metadata. Warnings are not fatal if the executable is produced.
- For production behavior, use profile/environment settings documented in [configuration](configuration.md).

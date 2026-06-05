# Native Build (GraalVM)

This page describes how to build and run Proxera as a native executable.

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

```bash
docker build -f Dockerfile-native -t proxera:native .
docker run --rm -p 8080:8080 proxera:native
```

In GitHub Actions, the native image pipeline is intentionally non-blocking while production validation is in progress.

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

## Notes

- Native build logs can include many reachability warnings from optional dependency metadata. Warnings are not fatal if the executable is produced.
- For production behavior, use profile/environment settings documented in [configuration](configuration.md).

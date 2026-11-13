# KROOT Launcher

**The Android home launcher for the KROOT environment.**

KROOT Launcher is an Android home-screen replacement designed to become the unified interface between Android applications and Linux applications running inside the KROOT environment.

Inspired by the productivity and visual language of Windows 10 Mobile, KROOT aims to bring a tile-based interface, unified application discovery, and integrated Linux application windows to Android devices.

> **Project status:** Under active development. The Android launcher, root infrastructure, and Linux desktop integration are separate development areas. Not all planned features are currently available.

---

## Vision

KROOT is intended to turn an Android device into a hybrid Android/Linux environment without requiring the user to operate a traditional Linux desktop.

The launcher is designed around four principles:

- **One interface:** Android and Linux applications appear in a unified application catalog.
- **Tile-based experience:** A customizable home screen inspired by Windows 10 Mobile.
- **Integrated Linux applications:** Linux GUI applications should open in individual windows inside KROOT, not in a separate full desktop session.
- **Native Android foundation:** Android remains the host operating system and provides the kernel, hardware support, and application environment.

KROOT is not intended to be another Android emulator, nor a conventional Linux desktop distribution.

## Main Features and Goals

### Android Launcher

- Android HOME launcher integration.
- Application discovery through Android's PackageManager.
- Application icons and labels.
- Application grid and home-screen navigation.
- Windows Phone-inspired tile interface.
- Application launching through Android intents.
- Search and application catalog.

### KROOT Linux Integration

Planned integration with the KROOT Linux environment:

- Ubuntu RootFS management.
- Linux process lifecycle management.
- Linux application discovery.
- `.desktop` application entry support.
- Linux application launching.
- Individual Linux application windows.
- Keyboard, pointer, and touch input integration.

### Unified Application Catalog

The intended catalog combines two application providers:

| Provider | Responsibility |
|---|---|
| Android Provider | Discovers installed Android applications |
| Ubuntu Provider | Discovers installed Linux desktop applications |

Linux desktop entries will be discovered from locations such as:

- `/usr/share/applications/`
- `/usr/local/share/applications/`
- `/home/<user>/.local/share/applications/`

The catalog is expected to interpret desktop entry fields including `Name`, `Exec`, `Icon`, `Categories`, `Terminal`, `NoDisplay` and `TryExec`.

Linux commands must be launched through a controlled process manager, not by blindly executing desktop entry strings in a shell.

---

## Architecture

KROOT is designed as a modular system.

```text
                  KROOT Launcher
                        |
             +----------+----------+
             |                     |
       Android Provider      Ubuntu Provider
             |                     |
       PackageManager        .desktop Entries
             |                     |
             +----------+----------+
                        |
              Unified App Catalog
                        |
               KROOT Home Interface
                        |
              KROOT Window Manager
                        |
              +---------+---------+
              |                   |
        Android Apps         Linux Apps
                                  |
                           KROOT Linux Engine
                                  |
                           Root Controller
                                  |
                              krootd
                                  |
                             Ubuntu RootFS
```

### Android Layer

Android remains responsible for:

- Hardware and kernel access.
- Android application lifecycle.
- System services.
- Display and input infrastructure.
- HOME launcher integration.

### Root Infrastructure

The KROOT root infrastructure is intended to provide controlled privileged operations through the KROOT daemon and root controller.

Components include:

- `krootd`
- `su`
- `krootctl`
- Permission and policy management.
- Daemon logging and recovery.

The launcher must communicate with this infrastructure through a defined and controlled interface.

Root availability must be verified at runtime. Installing a root-related binary or successfully invoking a command does not, by itself, prove that the required permissions and capabilities are available.

### Linux Engine

The Linux Engine is responsible for managing the Ubuntu userspace environment.

Its intended responsibilities include:

- RootFS initialization and validation.
- Linux process creation and termination.
- Environment variables and execution context.
- Application lifecycle management.
- Filesystem and mount configuration.
- Communication with the graphical integration layer.

The preferred execution model is a real Linux `chroot` environment when the device supports it. Alternative execution modes must be identified clearly and must not be presented as equivalent to a real `chroot`.

### Graphical Integration

KROOT does not intend to launch XFCE or LXQt as a complete desktop environment.

Instead, individual Linux applications should be displayed as windows inside the Android launcher.

The graphical integration is expected to evolve through:

- X11 compatibility.
- Android `SurfaceView` or `TextureView` integration.
- Display buffer transfer.
- Keyboard, mouse, and touch event forwarding.
- Window focus, resizing, minimizing, and closing.

A running X server alone is not sufficient: the Android application must also receive and display the Linux application's graphical output and forward user input.

Wayland/Xwayland support may be considered as a later development direction.

---

## Window Management

KROOT is intended to manage Linux application windows independently.

The proposed window lifecycle is:

```text
CREATED
   |
STARTING
   |
VISIBLE
   |
FOCUSED
   |
MINIMIZED
   |
SUSPENDED
   |
CLOSING
   |
CLOSED
```

An `ERROR` state handles failures during startup or execution.

The window manager will eventually be responsible for:

- Window creation.
- Focus management.
- Z-order.
- Minimize and restore.
- Close and process termination.
- Error reporting.

Android applications continue to use the native Android activity lifecycle.

---

## User Interface

The visual direction is inspired by Windows 10 Mobile.

The intended interface includes:

- Live-tile-inspired home screen.
- Configurable tile sizes.
- Application catalog.
- Search.
- Unified Android and Linux application entries.
- Consistent navigation.
- Touch-friendly controls.

The interface should remain lightweight and suitable for devices with limited memory and processing resources.

---

## Target Device

The initial hardware target is:

**Motorola Moto E32**

Development and validation must account for:

- Device CPU architecture and Android ABI.
- Available RAM.
- GPU and graphics drivers.
- Android version.
- Kernel capabilities.
- Root implementation.
- SELinux configuration.
- Available storage.

Ubuntu RootFS architecture and graphical integration must be selected according to verified device capabilities rather than assumptions.

---

## Development Roadmap

### Phase 1 — Android Launcher Foundation

- [ ] Stabilize launcher startup.
- [ ] Fix application grid and layout issues.
- [ ] Improve application discovery.
- [ ] Validate application launching.
- [ ] Establish reliable logging and crash diagnostics.
- [ ] Validate HOME launcher behavior.

### Phase 2 — KROOT UI

- [ ] Develop the tile-based home screen.
- [ ] Implement application catalog navigation.
- [ ] Add application search.
- [ ] Implement tile customization.
- [ ] Establish modular UI components.
- [ ] Improve touch interaction and performance.

### Phase 3 — Root Infrastructure Integration

- [ ] Connect the Android application to the Root Controller.
- [ ] Validate communication with `krootd`.
- [ ] Verify root permissions and execution capabilities.
- [ ] Implement controlled command execution.
- [ ] Add error handling and process monitoring.

### Phase 4 — Linux Engine

- [ ] Validate device architecture.
- [ ] Select and prepare Ubuntu RootFS.
- [ ] Implement RootFS lifecycle management.
- [ ] Implement Linux process management.
- [ ] Parse Linux desktop application entries.
- [ ] Launch individual Linux applications.

### Phase 5 — Linux Window Integration

- [ ] Implement graphical output integration.
- [ ] Display Linux application windows inside KROOT.
- [ ] Forward keyboard and pointer input.
- [ ] Implement window focus and stacking.
- [ ] Support minimizing and restoring windows.
- [ ] Optimize graphics and memory usage.

### Phase 6 — Unified Android/Linux Environment

- [ ] Combine Android and Linux applications in one catalog.
- [ ] Integrate both application providers.
- [ ] Complete the unified launcher experience.
- [ ] Improve performance and stability.
- [ ] Validate the complete system on the target device.

---

## Design Principles

KROOT follows these architectural principles:

1. Android remains the host operating system.
2. Linux applications run as Linux processes, not as APKs.
3. The launcher and Linux Engine remain separate modules.
4. The root infrastructure is controlled through a defined interface.
5. Linux applications should not require a full Linux desktop session.
6. Android and Linux applications should share one application discovery experience.
7. Device capabilities must be verified before enabling features that depend on them.
8. Stability and resource efficiency take priority over unnecessary visual complexity.

---

## Project Structure

The project is intended to maintain clear boundaries between its components.

```text
KROOT/
├── Android Launcher
│   ├── Home Interface
│   ├── Application Catalog
│   ├── Android Provider
│   └── Window Manager
│
├── Root Infrastructure
│   ├── krootd
│   ├── su
│   ├── krootctl
│   └── Policies and Logs
│
├── Linux Engine
│   ├── RootFS Manager
│   ├── Process Manager
│   ├── Application Provider
│   └── Execution Controller
│
└── Graphics Integration
    ├── Display Bridge
    ├── Input Bridge
    └── Window Integration
```

The exact source directory layout may differ from this conceptual architecture.

---

## Development Environment

The development workflow currently targets:

- Windows development workstation.
- WSL Ubuntu.
- Android SDK and build tools.
- Java 17.
- ADB for installation, debugging, and device communication.
- Motorola Moto E32 for device validation.

ADB over Wi-Fi is intended to remain available for development and maintenance.

The APK should be installed through the normal Android installation process, using ADB during development.

---

## Current Status

KROOT is under active development.

The project has separate milestones for the root infrastructure and the Android launcher. Completion of one component does not imply completion of the entire hybrid Android/Linux environment.

The immediate priority is to stabilize the Android launcher before expanding Linux Engine and graphical integration work.

Features described as goals or roadmap items should not be interpreted as already implemented.

---

## Project Repository

GitHub: https://github.com/chronokairo/kroot

## License

See the repository's `LICENSE` file for the applicable license and redistribution terms.

---

**KROOT — One launcher. One application environment. Android and Linux together.**